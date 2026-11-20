package io.github.p5rrecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The home screen in front of the game (a NativeActivity, libp5r.so): checks for the player's data in Internal
 * storage/Persona 5 Royal (asking for all-files access), shows the options and writes them to that folder's
 * config.txt, whose NX_* lines the runtime turns into its switches:
 *   game text   the language of the game's texts and subtitles (language=auto, default: the device's, English when
 *               the game lacks it; or en-US, es, fr, de, it, ja, ko, zh-Hans, zh-Hant). The voices are the game's own
 *               option.
 *   resolution  720p (NX_HANDHELD=1, default) or 1080p (NX_HANDHELD=0)
 *   interface   at 720p (NX_HUD_1080=0, default: faster) or, in 720p, drawn at 1080p (NX_HUD_1080=1: sharper text)
 *   graphics    low (default), medium or high: textures one mip smaller (NX_LOD_BIAS=1), half a mip, or the game's
 *               own. (A lower render resolution needs the engine's target sizes: games/p5r/PROGRESO.md.)
 *   frame rate  30 (NX_FPS_CAP=30, default) or 60 (NX_FPS_CAP=60: games/p5r/game.cpp patches the engine's interval)
 *   FPS counter off (default) or on (NX_SHOW_FPS)
 *   shadows     off (NX_SHADOWS=0, default: the shadow maps are not drawn, ~9% of the GPU on the 3D title) or on
 *   on-screen controls on (default) or off (NX_TOUCH_SHOW), their size (NX_TOUCH_SIZE) and opacity (NX_TOUCH_OPACITY),
 *               and where they are (NX_TOUCH_LAYOUT, written by ControlsEditor)
 * and imports or exports the saves (user/save, SaveFiles.java): a ZIP or a folder in, a ZIP in Download out.
 * The game: the player picks their own NSP or XCI (Persona 5 Royal 1.0.0) and prod.keys here; ImportActivity checks
 * them and translates the game's shaders once, and every start reads the game from that file as it is (the runtime's
 * game_image.cpp, through NX_GAME_FD / NX_GAME_NAME / NX_KEYS). Nothing of the game is copied but its shaders. A data
 * folder made on a PC (exefs/ or analysis/, extract/romfs, spirv/) still works when no file was picked.
 * The icon: a home screen shortcut with a picture of the player's own (Android lets an app pin shortcuts, not change
 * its own icon).
 * Other lines of config.txt are kept as they were. The launcher itself speaks the device's language (Lang.java:
 * Spanish, English, French, German, Italian, Portuguese, Japanese, Korean, Chinese; English otherwise).
 */
public class Launcher extends Activity {
    static final String DATA = "Persona 5 Royal";

    static final class Option {
        final String key, es, en;
        final String[] values, labelsEs, labelsEn;
        int pick;

        Option(String key, String es, String en, String[] values, String[] labelsEs, String[] labelsEn, int pick) {
            this.key = key;
            this.es = es;
            this.en = en;
            this.values = values;
            this.labelsEs = labelsEs;
            this.labelsEn = labelsEn;
            this.pick = pick;
        }
    }

    final List<Option> options = new ArrayList<>();
    final Map<String, String> config = new LinkedHashMap<>();
    TextView status;
    Button play;
    boolean settingsVisible;
    LinearLayout settingsPanel;
    ScrollView settingsPanelScroll, settingsCategoryScroll;
    final List<Button> settingsCategoryButtons = new ArrayList<>();
    final List<Button> settingsNavigation = new ArrayList<>();
    String[] settingsCategories;
    int settingsCategoryIndex;
    static final int PICK_ZIP = 1, PICK_FOLDER = 2, PICK_GAME = 3, PICK_KEYS = 4, PICK_ICON = 5, PREPARE = 6, PICK_CPK = 7, PICK_DRIVER = 8;
    static final String GAMEPAD_MAP_KEY = "NX_GAMEPAD_MAP";
    static final String[] GAMEPAD_ACTIONS = {"A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−", "L3", "R3",
            "D-pad izquierda", "D-pad arriba", "D-pad derecha", "D-pad abajo"};
    static final String[] GAMEPAD_ACTIONS_EN = {"A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−", "L3", "R3",
            "D-pad left", "D-pad up", "D-pad right", "D-pad down"};

    String t(String spanish, String english) { return Lang.t(spanish, english); }

    String label(Option o) { return t(o.labelsEs[o.pick], o.labelsEn[o.pick]); }

    Option findOption(String key) {
        for (Option o : options) {
            if (key.equals(o.key)) return o;
        }
        return null;
    }

    static File dataDir() { return new File(Environment.getExternalStorageDirectory(), DATA); }

    static final class Api30Helper {
        static boolean isExternalStorageManager() {
            return Environment.isExternalStorageManager();
        }
    }

    boolean hasAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return Api30Helper.isExternalStorageManager();
    }

    // ---- the game file, read in place at every start ----

    SharedPreferences prefs() { return getSharedPreferences("game", MODE_PRIVATE); }

    String gameUri() { return prefs().getString("uri", null); }

    String gameName() { return prefs().getString("name", ""); }

    static File keysFile(Context c, String name) { return new File(new File(c.getFilesDir(), "keys"), name); }

    boolean hasKeys() { return keysFile(this, "prod.keys").length() > 0; }

    boolean gameReadable() {
        try (ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r")) {
            return f != null;
        } catch (Exception e) {
            return false;
        }
    }

    // The shaders were translated from this game file (ImportActivity writes spirv/import.txt last).
    boolean prepared() {
        File note = new File(dataDir(), "spirv/import.txt");
        if (!note.canRead()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(note))) {
            return gameName().equals(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    void pick(int request, String type, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, request);
    }

    void onGamePicked(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".nsp") && !lower.endsWith(".xci")) {
            tell(t("Archivo no válido", "Not a game file"),
                    t("Elige el archivo .nsp o .xci del juego base (no el de la actualización).",
                            "Choose the base game's .nsp or .xci file (not the update's)."));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {  // a provider without persistable grants: works while the app runs
        }
        prefs().edit().putString("uri", uri.toString()).putString("name", name).apply();
    }

    // prod.keys (and title.keys, when the dump needs it): copied into the app's own storage, never shared or logged.
    void onKeysPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null)
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        boolean prod = false;
        for (Uri uri : uris) {
            String name = displayName(uri).toLowerCase(Locale.ROOT);
            String target = name.contains("title") ? "title.keys" : "prod.keys";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[1 << 20];
                int n = 0, r;
                while (in != null && n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, "UTF-8");
                if (target.equals("prod.keys") && !text.contains("header_key") && !text.contains("master_key_00")) continue;
                File f = keysFile(this, target);
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(buf, 0, n);
                }
                prod |= target.equals("prod.keys");
            } catch (Exception ignored) {
            }
        }
        if (!prod && !hasKeys())
            tell(t("Claves no válidas", "Not a keys file"),
                    t("Ese archivo no tiene las claves (header_key). Elige el prod.keys de tu consola.",
                            "That file has no keys (header_key). Choose your console's prod.keys."));
    }

    void prepare() {
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra(ImportActivity.EXTRA_URI, gameUri());
        i.putExtra(ImportActivity.EXTRA_NAME, gameName());
        startActivityForResult(i, PREPARE);
    }

    // The runtime reads the game from this descriptor (same process: the NativeActivity runs in the launcher's).
    boolean openGameForRuntime() {
        try {
            ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r");
            if (f == null) return false;
            Os.setenv("NX_GAME_FD", String.valueOf(f.detachFd()), true);
            Os.setenv("NX_GAME_NAME", gameName(), true);
            Os.setenv("NX_KEYS", keysFile(this, "prod.keys").getPath(), true);
            File tk = keysFile(this, "title.keys");
            if (tk.exists()) Os.setenv("NX_TITLE_KEYS", tk.getPath(), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // A home screen shortcut with the player's picture (cropped to a square; an adaptive icon, so the home screen gives
    // it its own shape).
    void onIconPicked(Uri uri) {
        try {
            Bitmap src;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) throw new IllegalArgumentException("not a picture");
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap icon = Bitmap.createScaledBitmap(square, 432, 432, true);
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                tell(t("Icono", "Icon"), t("Tu pantalla de inicio no admite accesos directos.",
                        "Your home screen does not support shortcuts."));
                return;
            }
            Intent launch = new Intent(this, Launcher.class).setAction(Intent.ACTION_MAIN);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "picture_" + System.currentTimeMillis())
                    .setShortLabel("P5R").setLongLabel("Persona 5 Royal").setIcon(Icon.createWithAdaptiveBitmap(icon))
                    .setIntent(launch).build();
            sm.requestPinShortcut(info, null);
            tell(t("Icono", "Icon"), t("Acepta el aviso para poner el acceso directo con tu imagen en la pantalla de inicio. "
                            + "El icono del cajón de apps no cambia: Android no deja que una app cambie el suyo.",
                    "Accept the prompt to put the shortcut with your picture on the home screen. The icon in the app "
                            + "drawer stays: Android does not let an app change its own."));
        } catch (Exception e) {
            tell(t("Icono", "Icon"), t("No se pudo usar esa imagen.", "That picture could not be used."));
        }
    }

    // The recompiled build reads analysis/main.bin, the native one (the game's own code) exefs/main.
    boolean hasData() {
        return new File(dataDir(), "exefs/main").canRead() || new File(dataDir(), "analysis/main.bin").canRead();
    }

    // The build ID the port is made for (Persona 5 Royal 1.0.0, the base game): the start of exefs/main's module ID
    // (NSO header +0x40). The native build refuses another (runtime main.cpp); say so here instead of closing.
    static final String[] KNOWN_BUILDS = {
        "708BCACA873EA32B",  // US
        "B78A03E489E5BD92",  // EU
        "6E1F4CAE92A28C91",  // JP
        "2F2253ACD6BE5D87"   // Asia
    };

    // Null when exefs/main is missing or a recognized base game version, else its build ID.
    String wrongBuild() {
        File main = new File(dataDir(), "exefs/main");
        if (!main.canRead()) return null;
        byte[] id = new byte[8];
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(main, "r")) {
            f.seek(0x40);
            f.readFully(id);
        } catch (java.io.IOException e) {
            return "?";
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : id) hex.append(String.format("%02X", b & 0xFF));
        String have = hex.toString();
        for (String k : KNOWN_BUILDS) {
            if (have.equals(k)) return null;
        }
        return have;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // The game's texts and subtitles (runtime language.h): its own language names, as a console lists them.
        options.add(new Option("language", "Idioma de textos y subtítulos", "Text and subtitle language",
                new String[] {"auto", "en-US", "es", "fr", "de", "it", "ja", "ko", "zh-Hans", "zh-Hant"},
                new String[] {"Automático (idioma del dispositivo)", "English", "Español", "Français", "Deutsch",
                        "Italiano", "日本語", "한국어", "简体中文", "繁體中文"},
                new String[] {"Automatic (device language)", "English", "Español", "Français", "Deutsch", "Italiano",
                        "日本語", "한국어", "简体中文", "繁體中文"}, 0));
        options.add(new Option("RESOLUTION", "Resolución", "Resolution",
                new String[] {"360p", "480p", "720p", "1080p", "1440p", "2160p", "native"},
                new String[] {"360p", "480p", "720p", "1080p", "1440p (Experimental)",
                        "2160p (Experimental)", "Nativa del teléfono (Experimental)"},
                new String[] {"360p", "480p", "720p", "1080p", "1440p (Experimental)",
                        "2160p (Experimental)", "Phone native (Experimental)"}, 2));
        options.add(new Option("NX_STRETCH", "Pantalla completa (estirar)", "Fullscreen (stretch)",
                new String[] {"0", "1"},
                new String[] {"Desactivada", "Activada"},
                new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_ULTRAWIDE", "Pantalla ancha real (Hor+)", "True widescreen (Hor+)",
                new String[] {"0", "1"},
                new String[] {"Desactivada", "Automática (más campo de visión)"},
                new String[] {"Off", "Auto (wider field of view)"}, 0));
        options.add(new Option("NX_FSR", "Superresolución (FSR 1.0)", "Super resolution (FSR 1.0)",
                new String[] {"0", "1"},
                new String[] {"Desactivada", "Activada"},
                new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_HUD_1080", "Interfaz", "Interface", new String[] {"0", "1"},
                new String[] {"720p (más rápida)", "1080p (textos nítidos)"},
                new String[] {"720p (faster)", "1080p (sharp text)"}, 0));
        options.add(new Option("GRAPHICS", "Gráficos", "Graphics", new String[] {"low", "medium", "high"},
                new String[] {"Bajos", "Medios", "Altos"}, new String[] {"Low", "Medium", "High"}, 0));
        options.add(new Option("NX_MAX_ANISO", "Filtro anisotrópico", "Anisotropic filtering",
                new String[] {"auto", "1", "2", "4", "8", "16"},
                new String[] {"Automático", "1x (desactivado)", "2x", "4x", "8x", "16x"},
                new String[] {"Auto", "1x (off)", "2x", "4x", "8x", "16x"}, 0));
        options.add(new Option("NX_FPS_CAP", "Fotogramas por segundo", "Frame rate", new String[] {"30", "60"},
                new String[] {"30 fps", "60 fps (experimental)"}, new String[] {"30 fps", "60 fps (experimental)"}, 0));
        options.add(new Option("NX_SHADOWS", "Sombras", "Shadows", new String[] {"0", "1"},
                new String[] {"Desactivadas", "Activadas"}, new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_MALI_LOW_SHADOWS", "Calidad de sombras", "Shadow quality",
                new String[] {"1", "0"},
                new String[] {"Baja (0.5x - mejor rendimiento)", "Original (1.0x)"},
                new String[] {"Low (0.5x - best performance)", "Original (1.0x)"}, 0));
        options.add(new Option("NX_SHOW_FPS", "Contador de FPS", "FPS counter", new String[] {"0", "1"},
                new String[] {"Desactivado", "Activado"}, new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_TOUCH_SHOW", "Controles táctiles", "On-screen controls", new String[] {"1", "0"},
                new String[] {"Activados", "Desactivados"}, new String[] {"On", "Off"}, 0));
        options.add(new Option("NX_TOUCH_HIDE", "Ocultar controles", "Hide controls", new String[] {"0", "1"},
                new String[] {"No (visibles)", "Sí (invisibles)"}, new String[] {"No (visible)", "Yes (invisible)"}, 0));
        options.add(new Option("NX_TOUCH_SIZE", "Tamaño de los botones", "Button size",
                new String[] {"80", "100", "125"}, new String[] {"Pequeño", "Normal", "Grande"},
                new String[] {"Small", "Normal", "Large"}, 1));
        options.add(new Option("NX_TOUCH_OPACITY", "Opacidad de los botones", "Button opacity",
                new String[] {"50", "85", "100"}, new String[] {"Baja", "Normal", "Alta"},
                new String[] {"Low", "Normal", "High"}, 1));
        options.add(new Option("NX_NO_CROWD", "Gente / Multitudes de NPCs", "Crowd NPCs",
                new String[] {"0", "1"},
                new String[] {"Activada (normal)", "Desactivada (más FPS)"},
                new String[] {"Enabled (normal)", "Disabled (more FPS)"}, 0));
        options.add(new Option("NX_DISABLE_BLOOM", "Post-procesado (bloom/brillo)", "Post-processing (bloom)",
                new String[] {"0", "1"},
                new String[] {"Activado (normal)", "Desactivado (más FPS)"},
                new String[] {"Enabled (normal)", "Disabled (more FPS)"}, 0));
        options.add(new Option("NX_GAMEPAD_LAYOUT", "Botones del mando físico", "Physical gamepad buttons",
                new String[] {"0", "1"},
                new String[] {"Nintendo (A dcha, B abajo)", "Xbox / PC (A abajo, B dcha)"},
                new String[] {"Nintendo (A right, B down)", "Xbox / PC (A down, B right)"}, 0));
        options.add(new Option("NX_STICK_SENSITIVITY", "Sensibilidad palanca táctil", "Touch stick sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"}, 2));
        options.add(new Option("NX_CAMERA_SENSITIVITY", "Sensibilidad cámara táctil", "Touch camera sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200", "250"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"}, 2));
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    void build() {
        buildHome();
    }

    void buildHome() {
        settingsVisible = false;
        FrameLayout screen = new FrameLayout(this);
        ImageView backdropImage = new ImageView(this);
        int backdropId = getResources().getIdentifier("p5r_launcher_background", "drawable", getPackageName());
        if (backdropId != 0) backdropImage.setImageResource(backdropId);
        backdropImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        screen.addView(backdropImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(48, 0, 0, 0));
        screen.addView(shade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(18));
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        contentParams.topMargin = dp(8);
        contentParams.bottomMargin = dp(8);
        root.addView(content, contentParams);

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.2f);
        heroParams.rightMargin = dp(28);
        content.addView(hero, heroParams);
        TextView title = new TextView(this);
        SpannableString logo = new SpannableString("P5R\nANDROID");
        logo.setSpan(new ForegroundColorSpan(Color.rgb(230, 18, 34)), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(logo);
        title.setTextColor(Color.WHITE);
        title.setTextSize(52);
        title.setLineSpacing(-dp(6), 1.0f);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setShadowLayer(dp(2), dp(2), dp(2), Color.BLACK);
        hero.addView(title);
        View accent = new View(this);
        accent.setBackgroundColor(Color.rgb(224, 18, 34));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(84), dp(4));
        accentParams.topMargin = dp(15);
        hero.addView(accent, accentParams);
        TextView subtitle = new TextView(this);
        subtitle.setText(t("PORT NATIVO PARA ANDROID", "NATIVE ANDROID PORT"));
        subtitle.setTextColor(Color.rgb(220, 188, 111));
        subtitle.setTextSize(13);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setPadding(0, dp(12), 0, 0);
        hero.addView(subtitle);

        LinearLayout menu = new LinearLayout(this);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        menu.setOrientation(LinearLayout.VERTICAL);
        content.addView(menu, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
        play = menuButton(t("Jugar", "Play"), true);
        menu.addView(play, menuButtonParams(68, 10));
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGame();
            }
        });
        Button settings = menuButton(t("Ajustes", "Settings"), false);
        menu.addView(settings, menuButtonParams(62, 10));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildSettings();
            }
        });
        Button quit = menuButton(t("Salir", "Quit"), false);
        menu.addView(quit, menuButtonParams(62, 10));
        quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAffinity();
            }
        });

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(10), dp(16), dp(10));
        statusCard.setBackground(new MenuPanelDrawable(dp(4), false));
        TextView indicator = new TextView(this);
        indicator.setText("●");
        indicator.setTextColor(Color.rgb(220, 188, 111));
        indicator.setTextSize(17);
        statusCard.addView(indicator);
        status = new TextView(this);
        status.setTextColor(Color.rgb(235, 231, 225));
        status.setTextSize(14);
        status.setMaxLines(2);
        status.setPadding(dp(10), 0, 0, 0);
        statusCard.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout footerInfo = new LinearLayout(this);
        footerInfo.setOrientation(LinearLayout.VERTICAL);
        TextView version = new TextView(this);
        version.setText("v0.5.24");
        version.setTextColor(Color.WHITE);
        version.setTextSize(15);
        version.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        version.setShadowLayer(dp(2), dp(1), dp(2), Color.BLACK);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        versionParams.bottomMargin = dp(5);
        footerInfo.addView(version, versionParams);
        footerInfo.addView(statusCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        footer.addView(footerInfo, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));
        footer.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 0.7f));
        root.addView(footer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        screen.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(screen);
        focusWhenReady(play);
        refresh();
    }

    void focusWhenReady(final View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.post(new Runnable() {
            @Override
            public void run() {
                view.requestFocus();
            }
        });
    }

    Button menuButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(27);
        button.setAllCaps(true);
        button.setLetterSpacing(0.015f);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setShadowLayer(dp(2), dp(1), dp(2), Color.BLACK);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(22), 0, dp(18), 0);
        button.setTextColor(primary ? Color.BLACK : Color.WHITE);
        button.setBackground(new MenuPanelDrawable(dp(4), primary));
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                boolean selected = focused;
                button.setTextColor(selected ? Color.BLACK : Color.WHITE);
                button.setBackground(new MenuPanelDrawable(dp(4), selected));
            }
        });
        return button;
    }

    void styleSettingsButton(final Button button) {
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackground(new MenuPanelDrawable(dp(4), false));
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                button.setTextColor(focused ? Color.BLACK : Color.WHITE);
                button.setBackground(new MenuPanelDrawable(dp(4), focused));
            }
        });
    }

    void styleSettingsLabel(TextView label) {
        label.setTextColor(Color.WHITE);
        label.setTextSize(17);
        label.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        label.setShadowLayer(dp(2), dp(1), dp(1), Color.BLACK);
    }

    static final class MenuPanelDrawable extends android.graphics.drawable.Drawable {
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final int border;
        final boolean selected;

        MenuPanelDrawable(int border, boolean selected) {
            this.border = border;
            this.selected = selected;
        }

        void polygon(android.graphics.Canvas canvas, float left, float top, float right, float bottom,
                float cut, int color) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(left + cut, top);
            path.lineTo(right, top);
            path.lineTo(right - cut, bottom);
            path.lineTo(left, bottom);
            path.close();
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            float cut = Math.min(border * 4.5f, width * 0.13f);
            polygon(canvas, 0, 0, width, height, cut,
                    selected ? Color.rgb(238, 0, 28) : Color.rgb(156, 10, 23));
            polygon(canvas, border, border, width - border, height - border,
                    cut * 0.8f, selected ? Color.rgb(8, 7, 9) : Color.WHITE);
            polygon(canvas, border * 2, border * 2, width - border * 2, height - border * 2,
                    cut * 0.65f, selected ? Color.WHITE : Color.rgb(8, 7, 9));
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    LinearLayout.LayoutParams menuButtonParams(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(height));
        params.topMargin = dp(topMargin);
        return params;
    }

    void buildSettings() {
        settingsVisible = true;
        settingsCategoryIndex = 0;
        settingsCategories = new String[] {
                t("Video", "Video"), t("Rendimiento", "Performance"), t("Efectos", "Effects"),
                t("Interfaz", "Interface"), t("Juego", "Game"), t("Mandos", "Controllers"),
                t("Datos y herramientas", "Data and tools")
        };
        settingsCategoryButtons.clear();

        FrameLayout screen = new FrameLayout(this);
        ImageView backdropImage = new ImageView(this);
        int backdropId = getResources().getIdentifier("p5r_launcher_background", "drawable", getPackageName());
        if (backdropId != 0) backdropImage.setImageResource(backdropId);
        backdropImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        screen.addView(backdropImage, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(145, 0, 0, 0));
        screen.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText(t("← Inicio", "← Home"));
        styleSettingsButton(back);
        back.setGravity(Gravity.CENTER);
        header.addView(back, new LinearLayout.LayoutParams(dp(150), dp(50)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { buildHome(); }
        });
        TextView title = new TextView(this);
        title.setText(t("AJUSTES", "SETTINGS"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setShadowLayer(dp(2), dp(1), dp(2), Color.BLACK);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = dp(18);
        header.addView(title, titleParams);
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = dp(14);
        bodyParams.bottomMargin = dp(8);
        root.addView(body, bodyParams);

        settingsCategoryScroll = new ScrollView(this);
        settingsCategoryScroll.setVerticalScrollBarEnabled(true);
        settingsCategoryScroll.setScrollbarFadingEnabled(false);
        LinearLayout categoryList = new LinearLayout(this);
        categoryList.setOrientation(LinearLayout.VERTICAL);
        settingsCategoryScroll.addView(categoryList, new ScrollView.LayoutParams(-1, -2));
        body.addView(settingsCategoryScroll, new LinearLayout.LayoutParams(dp(218), -1));
        for (int i = 0; i < settingsCategories.length; i++) {
            final int index = i;
            Button category = new Button(this);
            category.setText(settingsCategories[i]);
            category.setAllCaps(false);
            category.setTextSize(16);
            category.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            category.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            category.setPadding(dp(14), 0, dp(8), 0);
            category.setFocusable(true);
            category.setFocusableInTouchMode(true);
            category.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean focused) {
                    styleSettingsCategory((Button) v, index == settingsCategoryIndex || focused);
                }
            });
            category.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showSettingsCategory(index); }
            });
            LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(-1, dp(54));
            categoryParams.bottomMargin = dp(7);
            categoryList.addView(category, categoryParams);
            settingsCategoryButtons.add(category);
        }

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(20), dp(16), dp(20), dp(14));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.argb(242, 8, 7, 9));
        panelBg.setCornerRadius(dp(5));
        panelBg.setStroke(dp(2), Color.rgb(230, 18, 34));
        settingsPanel.setBackground(panelBg);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(0, -1, 1);
        panelParams.leftMargin = dp(14);
        body.addView(settingsPanel, panelParams);

        TextView hint = new TextView(this);
        hint.setText(t("L1/R1: categorías  ·  ↑/↓: opciones  ·  A: seleccionar  ·  B: volver",
                "L1/R1: categories  ·  ↑/↓: options  ·  A: select  ·  B: back"));
        hint.setTextColor(Color.rgb(184, 188, 208));
        hint.setTextSize(12);
        root.addView(hint);

        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        showSettingsCategory(0);
    }

    void styleSettingsCategory(Button button, boolean selected) {
        button.setTextColor(selected ? Color.BLACK : Color.WHITE);
        button.setBackground(new MenuPanelDrawable(dp(4), selected));
    }

    void refreshSettingsCategories() {
        for (int i = 0; i < settingsCategoryButtons.size(); i++) {
            Button button = settingsCategoryButtons.get(i);
            styleSettingsCategory(button, i == settingsCategoryIndex || button.hasFocus());
        }
    }

    void showSettingsCategory(int index) {
        if (settingsCategories == null || settingsPanel == null) return;
        settingsCategoryIndex = Math.max(0, Math.min(index, settingsCategories.length - 1));
        refreshSettingsCategories();
        final Button selectedCategory = settingsCategoryButtons.get(settingsCategoryIndex);
        selectedCategory.post(new Runnable() {
            @Override public void run() {
                android.graphics.Rect rect = new android.graphics.Rect();
                selectedCategory.getDrawingRect(rect);
                settingsCategoryScroll.offsetDescendantRectToMyCoords(selectedCategory, rect);
                int top = settingsCategoryScroll.getScrollY();
                if (rect.top < top) settingsCategoryScroll.smoothScrollTo(0, rect.top);
                else if (rect.bottom > top + settingsCategoryScroll.getHeight())
                    settingsCategoryScroll.smoothScrollTo(0, rect.bottom - settingsCategoryScroll.getHeight());
            }
        });

        settingsPanel.removeAllViews();
        settingsNavigation.clear();
        TextView heading = new TextView(this);
        heading.setText(settingsCategories[settingsCategoryIndex].toUpperCase(java.util.Locale.ROOT));
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(23);
        heading.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        settingsPanel.addView(heading);
        View accent = new View(this);
        accent.setBackgroundColor(Color.rgb(230, 18, 34));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(72), dp(4));
        accentParams.topMargin = dp(8);
        accentParams.bottomMargin = dp(10);
        settingsPanel.addView(accent, accentParams);

        settingsPanelScroll = new ScrollView(this);
        settingsPanelScroll.setFillViewport(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        String categoryName = settingsCategories[settingsCategoryIndex];
        String currentSubcategory = null;
        for (final Option option : options) {
            if (categoryName.equals(settingsCategoryFor(option))) {
                String subcategory = settingsSubcategoryFor(option);
                if (!subcategory.equals(currentSubcategory)) {
                    addSettingsSectionHeading(rows, subcategory);
                    currentSubcategory = subcategory;
                }
                addOptionSettingsRow(rows, option);
            }
        }
        if (categoryName.equals(t("Mandos", "Controllers"))) {
            addSettingsSectionHeading(rows, t("Asignación manual", "Manual mapping"));
            addSettingsActionRow(rows, t("Asignar botones del mando", "Remap gamepad buttons"),
                    t("Configurar", "Configure"), new View.OnClickListener() {
                        @Override public void onClick(View v) { openGamepadMapper(); }
                    });
        }
        if (categoryName.equals(t("Interfaz", "Interface"))) {
            addSettingsSectionHeading(rows, t("Distribución táctil", "Touch layout"));
            addSettingsActionRow(rows, t("Posición de los controles", "Touch control positions"),
                    t("Mover", "Move"), new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!hasAccess()) { askAccess(); return; }
                            save();
                            startActivity(new Intent(Launcher.this, ControlsEditor.class));
                        }
                    });
        }
        if (categoryName.equals(t("Datos y herramientas", "Data and tools"))) addDataToolRows(rows);

        settingsPanelScroll.addView(rows);
        settingsPanel.addView(settingsPanelScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateButtons(settingsPanel);
        focusFirstSettingsOption();
    }

    String settingsCategoryFor(Option option) {
        if ("RESOLUTION".equals(option.key) || "NX_STRETCH".equals(option.key) || "NX_ULTRAWIDE".equals(option.key)
                || "NX_FSR".equals(option.key) || "NX_HUD_1080".equals(option.key))
            return t("Video", "Video");
        if ("GRAPHICS".equals(option.key) || "NX_FPS_CAP".equals(option.key)
                || "NX_SHADOWS".equals(option.key) || "NX_MALI_LOW_SHADOWS".equals(option.key)
                || "NX_MAX_ANISO".equals(option.key))
            return t("Rendimiento", "Performance");
        if ("NX_NO_CROWD".equals(option.key) || "NX_DISABLE_BLOOM".equals(option.key))
            return t("Efectos", "Effects");
        if ("NX_SHOW_FPS".equals(option.key) || option.key.startsWith("NX_TOUCH_")
                || "NX_STICK_SENSITIVITY".equals(option.key) || "NX_CAMERA_SENSITIVITY".equals(option.key))
            return t("Interfaz", "Interface");
        if ("language".equals(option.key)) return t("Juego", "Game");
        return t("Mandos", "Controllers");
    }

    String settingsSubcategoryFor(Option option) {
        if ("RESOLUTION".equals(option.key) || "NX_STRETCH".equals(option.key))
            return t("Pantalla", "Display");
        if ("NX_FSR".equals(option.key) || "NX_HUD_1080".equals(option.key) || "NX_ULTRAWIDE".equals(option.key))
            return t("Calidad de imagen", "Image quality");
        if (settingsCategoryFor(option).equals(t("Rendimiento", "Performance")))
            return t("Rendimiento y gráficos", "Performance and graphics");
        if ("NX_NO_CROWD".equals(option.key)) return t("Mundo", "World");
        if ("NX_DISABLE_BLOOM".equals(option.key)) return t("Postprocesado", "Post-processing");
        if ("NX_SHOW_FPS".equals(option.key)) return t("Indicadores", "Indicators");
        if (option.key.startsWith("NX_TOUCH_") && !"NX_STICK_SENSITIVITY".equals(option.key)
                && !"NX_CAMERA_SENSITIVITY".equals(option.key))
            return t("Controles táctiles", "Touch controls");
        if ("NX_STICK_SENSITIVITY".equals(option.key) || "NX_CAMERA_SENSITIVITY".equals(option.key))
            return t("Sensibilidad", "Sensitivity");
        if ("language".equals(option.key)) return t("Idioma", "Language");
        return t("Distribución de botones", "Button layout");
    }

    void addSettingsSectionHeading(LinearLayout rows, String title) {
        TextView heading = new TextView(this);
        heading.setText(title.toUpperCase(java.util.Locale.ROOT));
        heading.setTextColor(Color.rgb(220, 188, 111));
        heading.setTextSize(15);
        heading.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        heading.setPadding(dp(7), dp(9), 0, dp(2));
        rows.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        View accent = new View(this);
        accent.setBackgroundColor(Color.rgb(230, 18, 34));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(38), dp(2));
        accentParams.leftMargin = dp(7);
        accentParams.bottomMargin = dp(3);
        rows.addView(accent, accentParams);
    }

    void addOptionSettingsRow(LinearLayout rows, final Option option) {
        LinearLayout row = settingsRow(t(option.es, option.en));
        Button value = new Button(this);
        value.setAllCaps(false);
        value.setMinWidth(dp(190));
        value.setText(label(option));
        styleSettingsButton(value);
        value.setTag(option);
        value.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showOptionMenu(option, (Button) v); }
        });
        row.addView(value);
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        settingsNavigation.add(value);
    }

    LinearLayout settingsRow(String name) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        TextView label = new TextView(this);
        label.setText(name);
        styleSettingsLabel(label);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    Button addSettingsActionRow(LinearLayout rows, String label, String action, View.OnClickListener click) {
        LinearLayout row = settingsRow(label);
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setMinWidth(dp(150));
        button.setText(action);
        styleSettingsButton(button);
        button.setOnClickListener(click);
        row.addView(button);
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        settingsNavigation.add(button);
        return button;
    }

    void addDataToolRows(LinearLayout rows) {
        addSettingsSectionHeading(rows, t("Archivos del juego", "Game files"));
        addSettingsActionRow(rows, t("Archivo del juego (.nsp / .xci)", "Game file (.nsp / .xci)"),
                t("Elegir", "Choose"), new View.OnClickListener() {
                    @Override public void onClick(View v) { pick(PICK_GAME, "*/*", false); }
                });
        addSettingsActionRow(rows, t("Claves (prod.keys)", "Keys (prod.keys)"), t("Elegir", "Choose"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pick(PICK_KEYS, "*/*", true); }
                });
        addSettingsActionRow(rows, t("Icono", "Icon"), t("Elegir imagen", "Choose a picture"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pick(PICK_ICON, "image/*", false); }
                });
        addSettingsSectionHeading(rows, t("Partidas", "Saved games"));
        addSettingsActionRow(rows, t("Partidas guardadas", "Saves"), t("Importar", "Import"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!hasAccess()) { askAccess(); return; }
                        askImport();
                    }
                });
        addSettingsActionRow(rows, t("Partidas guardadas", "Saves"), t("Exportar", "Export"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!hasAccess()) { askAccess(); return; }
                        exportSaves();
                    }
                });
        addSettingsSectionHeading(rows, t("Complementos", "Add-ons"));
        addSettingsActionRow(rows, t("Traducción / Paquete (.cpk)", "Translation / CPK mod"),
                t("Importar CPK", "Import CPK"), new View.OnClickListener() {
                    @Override public void onClick(View v) { pick(PICK_CPK, "*/*", false); }
                });

        LinearLayout driverRow = settingsRow(t("Driver de GPU", "GPU driver"));
        driverName = new TextView(this);
        styleSettingsLabel(driverName);
        driverRow.addView(driverName, new LinearLayout.LayoutParams(0, -2, 1));
        Button importDriverButton = new Button(this);
        importDriverButton.setText(t("Importar", "Import"));
        styleSettingsButton(importDriverButton);
        importDriverButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(PICK_DRIVER, "*/*", false); }
        });
        driverRow.addView(importDriverButton);
        Button removeDriverButton = new Button(this);
        removeDriverButton.setText(t("Quitar", "Remove"));
        styleSettingsButton(removeDriverButton);
        removeDriverButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { removeDriver(); } });
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(-2, -2);
        removeParams.leftMargin = dp(6);
        driverRow.addView(removeDriverButton, removeParams);
        rows.addView(driverRow, new LinearLayout.LayoutParams(-1, dp(58)));
        settingsNavigation.add(importDriverButton);
        settingsNavigation.add(removeDriverButton);
        showDriver();

        addSettingsSectionHeading(rows, t("Diagnóstico", "Diagnostics"));
        addSettingsActionRow(rows, t("Registros de depuración", "Debug logs"), t("Exportar registros", "Export logs"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { exportLogs(); }
                });
    }

    void showOptionMenu(final Option option, final Button valueButton) {
        final AlertDialog dialog = new AlertDialog.Builder(this).create();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(8, 7, 9));
        background.setCornerRadius(dp(5));
        background.setStroke(dp(2), Color.rgb(230, 18, 34));
        card.setBackground(background);
        TextView title = new TextView(this);
        title.setText(t(option.es, option.en).toUpperCase(java.util.Locale.ROOT));
        title.setTextColor(Color.rgb(220, 188, 111));
        title.setTextSize(21);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        card.addView(title);
        View accent = new View(this);
        accent.setBackgroundColor(Color.rgb(230, 18, 34));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(70), dp(4));
        accentParams.topMargin = dp(8);
        accentParams.bottomMargin = dp(10);
        card.addView(accent, accentParams);
        TextView prompt = new TextView(this);
        prompt.setText(t("Selecciona una opción", "Choose an option"));
        prompt.setTextColor(Color.rgb(184, 188, 208));
        prompt.setTextSize(14);
        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(-1, -2);
        promptParams.bottomMargin = dp(8);
        card.addView(prompt, promptParams);

        ScrollView choiceScroll = new ScrollView(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        final List<Button> choiceButtons = new ArrayList<>();
        for (int i = 0; i < option.values.length; i++) {
            final int selected = i;
            Button choice = new Button(this);
            choice.setText(t(option.labelsEs[i], option.labelsEn[i]));
            choice.setAllCaps(false);
            choice.setTextSize(16);
            choice.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            choice.setPadding(dp(16), 0, dp(12), 0);
            choice.setFocusable(true);
            choice.setFocusableInTouchMode(true);
            styleOptionChoice(choice, selected == option.pick);
            choice.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean focused) {
                    styleOptionChoice((Button) v, selected == option.pick || focused);
                }
            });
            choice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    option.pick = selected;
                    if ("NX_ULTRAWIDE".equals(option.key) && "1".equals(option.values[selected])) {
                        Option stretch = findOption("NX_STRETCH");
                        if (stretch != null) stretch.pick = 0;
                    } else if ("NX_STRETCH".equals(option.key) && "1".equals(option.values[selected])) {
                        Option ultrawide = findOption("NX_ULTRAWIDE");
                        if (ultrawide != null) ultrawide.pick = 0;
                    }
                    save();
                    valueButton.setText(label(option));
                    for (String siblingKey : new String[] {"NX_STRETCH", "NX_ULTRAWIDE"}) {
                        if (siblingKey.equals(option.key)) continue;
                        Option sibling = findOption(siblingKey);
                        View siblingView = sibling == null ? null : settingsPanel.findViewWithTag(sibling);
                        if (siblingView instanceof Button) ((Button) siblingView).setText(label(sibling));
                    }
                    updateButtons(settingsPanel);
                    dialog.dismiss();
                    focusWhenReady(valueButton);
                }
            });
            choiceButtons.add(choice);
            LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(-1, dp(50));
            choiceParams.bottomMargin = dp(5);
            choices.addView(choice, choiceParams);
        }
        choiceScroll.addView(choices);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - dp(190);
        int listHeight = Math.max(dp(105), Math.min(dp(420), availableHeight));
        card.addView(choiceScroll, new LinearLayout.LayoutParams(-1, listHeight));

        Button close = new Button(this);
        close.setText(t("Cancelar", "Cancel"));
        styleSettingsButton(close);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.topMargin = dp(9);
        card.addView(close, closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.cancel(); }
        });

        dialog.setView(card);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dialog.cancel();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        View focused = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView().findFocus();
                        if (focused != null && focused.isEnabled() && focused.isClickable()) focused.performClick();
                    }
                    return true;
                }
                return false;
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(580), getResources().getDisplayMetrics().widthPixels - dp(36));
            int height = Math.min(dp(640), getResources().getDisplayMetrics().heightPixels - dp(36));
            window.setLayout(width, height);
        }
        focusWhenReady(choiceButtons.get(option.pick));
    }

    void styleOptionChoice(Button button, boolean selected) {
        button.setTextColor(selected ? Color.BLACK : Color.WHITE);
        button.setBackground(new MenuPanelDrawable(dp(4), selected));
    }

    void focusFirstSettingsOption() {
        if (settingsNavigation.isEmpty()) {
            focusWhenReady(settingsCategoryButtons.get(settingsCategoryIndex));
            return;
        }
        focusWhenReady(settingsNavigation.get(0));
    }

    void moveSettingsCategory(int direction) {
        int next = (settingsCategoryIndex + direction + settingsCategories.length) % settingsCategories.length;
        showSettingsCategory(next);
    }

    void moveSettingsVertical(int direction) {
        if (settingsNavigation.isEmpty()) return;
        int current = settingsNavigation.indexOf(getCurrentFocus());
        int next = current < 0 ? (direction > 0 ? 0 : settingsNavigation.size() - 1)
                : (current + direction + settingsNavigation.size()) % settingsNavigation.size();
        final View target = settingsNavigation.get(next);
        target.requestFocus();
        target.post(new Runnable() {
            @Override public void run() {
                android.graphics.Rect rect = new android.graphics.Rect();
                target.getDrawingRect(rect);
                settingsPanelScroll.offsetDescendantRectToMyCoords(target, rect);
                int top = settingsPanelScroll.getScrollY();
                int bottom = top + settingsPanelScroll.getHeight();
                if (rect.top < top) settingsPanelScroll.smoothScrollTo(0, rect.top);
                else if (rect.bottom > bottom) settingsPanelScroll.smoothScrollTo(0, rect.bottom - settingsPanelScroll.getHeight());
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (settingsVisible && (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_7
                || keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_8)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                moveSettingsCategory(keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_7 ? -1 : 1);
            return true;
        }
        if (settingsVisible && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                moveSettingsVertical(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                View focused = getCurrentFocus();
                if (focused != null && focused.isEnabled() && focused.isClickable()) {
                    focused.performClick();
                    return true;
                }
            }
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (settingsVisible) onBackPressed();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void startGame() {
        if (!hasAccess()) {
            askAccess();
            return;
        }
        if (gameUri() != null) {
            if (!gameReadable()) pick(PICK_GAME, "*/*", false);
            else if (!hasKeys()) pick(PICK_KEYS, "*/*", true);
            else if (!prepared()) prepare();
            else if (openGameForRuntime()) {
                save();
                startGameActivity();
            } else {
                tell(t("No se pudo abrir el juego", "The game could not be opened"), gameName());
            }
            return;
        }
        if (!hasData()) {
            pick(PICK_GAME, "*/*", false);
            return;
        }
        if (wrongBuild() != null) {
            refresh();
            return;
        }
        save();
        startGameActivity();
    }

    void startGameActivity() {
        Intent intent = new Intent(Launcher.this, GameActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    public void onBackPressed() {
        if (settingsVisible) {
            buildHome();
            return;
        }
        super.onBackPressed();
    }

    void refresh() {
        load();
        for (Option o : options) {
            String v = config.get(o.key);
            if ("RESOLUTION".equals(o.key) && v == null) {
                String hh = config.get("NX_HANDHELD");
                String r = config.get("resolution");
                if (r != null && "native".equalsIgnoreCase(r)) v = "native";
                else if (r != null && r.contains("2160")) v = "2160p";
                else if (r != null && r.contains("1440")) v = "1440p";
                else if (r != null && r.contains("1080")) v = "1080p";
                else if (r != null && r.contains("720")) v = "720p";
                else if (r != null && r.contains("480")) v = "480p";
                else if (r != null && r.contains("360")) v = "360p";
                else v = "0".equals(hh) ? "1080p" : "720p";
            }
            for (int k = 0; v != null && k < o.values.length; k++)
                if (o.values[k].equals(v)) o.pick = k;
        }
        updateButtons(getWindow().getDecorView());
        play.setText(t("Jugar", "Play"));
        if (!hasAccess()) {
            status.setText(t("Hace falta el permiso de acceso a todos los archivos para leer los datos del juego.",
                    "All-files access is needed to read the game data."));
        } else if (gameUri() != null && !gameReadable()) {
            status.setText(Lang.f("Ya no se puede leer el archivo del juego (%s): vuelve a elegirlo.",
                    "The game file can no longer be read (%s): choose it again.", gameName()));
        } else if (gameUri() != null && !hasKeys()) {
            status.setText(t("Falta tu archivo prod.keys.", "Your prod.keys file is missing."));
        } else if (gameUri() != null && !prepared()) {
            status.setText(Lang.f("Juego: %s. Falta prepararlo: solo una vez, unos minutos.",
                    "Game: %s. It needs preparing: once, a few minutes.", gameName()));
        } else if (gameUri() != null) {
            status.setText(Lang.f("Juego listo: %s", "Game ready: %s", gameName()));
        } else if (!hasData()) {
            status.setText(t("Elige el archivo de tu juego (.nsp o .xci de Persona 5 Royal 1.0.0) y tu prod.keys.",
                    "Choose your game file (a Persona 5 Royal 1.0.0 .nsp or .xci) and your prod.keys."));
        } else if (wrongBuild() != null) {
            status.setText(Lang.f("Almacenamiento interno/%s/exefs/main no es Persona 5 Royal 1.0.0 (build %s). Copia el "
                            + "exefs del juego base, sin la actualización.",
                    "Internal storage/%s/exefs/main is not Persona 5 Royal 1.0.0 (build %s). Copy the exefs of the base "
                            + "game, without the update.", DATA, wrongBuild()));
        } else {
            status.setText(Lang.f("Datos listos: %s", "Data ready: %s", dataDir().getPath()));
        }
    }

    void updateButtons(View v) {
        if (v instanceof Button && v.getTag() instanceof Option) {
            Option o = (Option) v.getTag();
            Button b = (Button) v;
            b.setText(label(o));
            if ("NX_MALI_LOW_SHADOWS".equals(o.key)) {
                Option shadows = findOption("NX_SHADOWS");
                boolean enabled = shadows != null && "1".equals(shadows.values[shadows.pick]);
                b.setEnabled(enabled);
                b.setAlpha(enabled ? 1.0f : 0.4f);
                if (b.getParent() instanceof View) {
                    ((View) b.getParent()).setAlpha(enabled ? 1.0f : 0.4f);
                }
            }
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) updateButtons(g.getChildAt(k));
        }
    }

    static int canonicalGamepadKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_1: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_2: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_3: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_4: return KeyEvent.KEYCODE_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_5:
            case KeyEvent.KEYCODE_BUTTON_C: return KeyEvent.KEYCODE_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_6:
            case KeyEvent.KEYCODE_BUTTON_Z: return KeyEvent.KEYCODE_BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_7: return KeyEvent.KEYCODE_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_8: return KeyEvent.KEYCODE_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_9: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_10:
            case KeyEvent.KEYCODE_BUTTON_MODE: return KeyEvent.KEYCODE_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_11: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case KeyEvent.KEYCODE_BUTTON_12: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            default: return keyCode;
        }
    }

    int[] defaultGamepadMap() {
        Option layout = findOption("NX_GAMEPAD_LAYOUT");
        boolean xbox = layout != null && layout.pick == 1;
        return new int[] {
                xbox ? KeyEvent.KEYCODE_BUTTON_A : KeyEvent.KEYCODE_BUTTON_B,
                xbox ? KeyEvent.KEYCODE_BUTTON_B : KeyEvent.KEYCODE_BUTTON_A,
                xbox ? KeyEvent.KEYCODE_BUTTON_X : KeyEvent.KEYCODE_BUTTON_Y,
                xbox ? KeyEvent.KEYCODE_BUTTON_Y : KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN
        };
    }

    int[] readGamepadMap() {
        String saved = config.get(GAMEPAD_MAP_KEY);
        if (saved != null) {
            String[] fields = saved.split(",", -1);
            if (fields.length == GAMEPAD_ACTIONS.length) {
                int[] map = new int[fields.length];
                boolean valid = true;
                for (int i = 0; i < fields.length; i++) {
                    try {
                        map[i] = canonicalGamepadKey(Integer.parseInt(fields[i]));
                        if (map[i] <= KeyEvent.KEYCODE_UNKNOWN) valid = false;
                        for (int j = 0; j < i; j++) if (map[i] == map[j]) valid = false;
                    } catch (NumberFormatException e) {
                        valid = false;
                    }
                }
                if (valid) return map;
            }
        }
        return defaultGamepadMap();
    }

    String encodeGamepadMap(int[] map) {
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < map.length; i++) {
            if (i != 0) encoded.append(',');
            encoded.append(map[i]);
        }
        return encoded.toString();
    }

    String gamepadKeyName(int keyCode) {
        String name = KeyEvent.keyCodeToString(keyCode);
        return name.replace("KEYCODE_BUTTON_", "").replace("KEYCODE_DPAD_", "D-pad ").replace("KEYCODE_", "");
    }

    void openGamepadMapper() {
        if (!hasAccess()) {
            askAccess();
            return;
        }
        load();
        showGamepadMapper(readGamepadMap());
    }

    void showGamepadMapper(final int[] map) {
        String[] rows = new String[GAMEPAD_ACTIONS.length];
        for (int i = 0; i < rows.length; i++)
            rows[i] = t(GAMEPAD_ACTIONS[i], GAMEPAD_ACTIONS_EN[i]) + "  ·  " + gamepadKeyName(map[i]);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Asignar botones del mando", "Remap gamepad buttons"))
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        captureGamepadButton(map, which);
                    }
                })
                .setNeutralButton(t("Restaurar preset", "Restore preset"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        load();
                        config.remove(GAMEPAD_MAP_KEY);
                        if (!writeConfig(new File(dataDir(), "config.txt"), config)) {
                            tell(t("No se pudo guardar", "Could not save"),
                                    t("No se pudieron restaurar los botones.", "The gamepad buttons could not be restored."));
                            return;
                        }
                        Toast.makeText(Launcher.this, t("Preset del mando restaurado", "Gamepad preset restored"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(t("Cerrar", "Close"), null)
                .create();
        dialog.show();
    }

    boolean isGamepadKey(KeyEvent event, int keyCode) {
        if (KeyEvent.isGamepadButton(keyCode)) return true;
        int sources = event.getSource();
        InputDevice device = event.getDevice();
        if (device != null) sources |= device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    void captureGamepadButton(final int[] map, final int action) {
        final TextView prompt = new TextView(this);
        prompt.setText(t("Pulsa el botón físico que quieres asignar. Toca Cancelar para salir.",
                "Press the physical button to assign. Tap Cancel to exit."));
        prompt.setTextSize(18);
        prompt.setPadding(dp(24), dp(20), dp(24), dp(20));
        prompt.setFocusableInTouchMode(true);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(Lang.f("Asignar %s", "Assign %s", t(GAMEPAD_ACTIONS[action], GAMEPAD_ACTIONS_EN[action])))
                .setView(prompt)
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .create();
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
                if (keyCode == KeyEvent.KEYCODE_BACK || !isGamepadKey(event, keyCode)) return false;
                finishGamepadCapture(map, action, keyCode, dialog);
                return true;
            }
        });
        prompt.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View view, MotionEvent event) {
                int sources = event.getSource();
                if ((sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
                int keyCode = 0;
                if (event.getAxisValue(MotionEvent.AXIS_HAT_X) < -0.5f) keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                else if (event.getAxisValue(MotionEvent.AXIS_HAT_X) > 0.5f) keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                else if (event.getAxisValue(MotionEvent.AXIS_HAT_Y) < -0.5f) keyCode = KeyEvent.KEYCODE_DPAD_UP;
                else if (event.getAxisValue(MotionEvent.AXIS_HAT_Y) > 0.5f) keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                else if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f
                        || event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f)
                    keyCode = KeyEvent.KEYCODE_BUTTON_L2;
                else if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f
                        || event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f)
                    keyCode = KeyEvent.KEYCODE_BUTTON_R2;
                if (keyCode == 0) return false;
                finishGamepadCapture(map, action, keyCode, dialog);
                return true;
            }
        });
        dialog.show();
        prompt.requestFocus();
    }

    void finishGamepadCapture(final int[] map, int action, int keyCode, AlertDialog dialog) {
        keyCode = canonicalGamepadKey(keyCode);
        int oldKey = map[action];
        for (int i = 0; i < map.length; i++)
            if (i != action && map[i] == keyCode) map[i] = oldKey;
        map[action] = keyCode;
        load();
        config.put(GAMEPAD_MAP_KEY, encodeGamepadMap(map));
        final boolean saved = writeConfig(new File(dataDir(), "config.txt"), config);
        dialog.dismiss();
        if (!saved) {
            tell(t("No se pudo guardar", "Could not save"),
                    t("La asignación del mando no se pudo guardar.", "The gamepad mapping could not be saved."));
            return;
        }
        Toast.makeText(this, Lang.f("%s asignado a %s", "%s mapped to %s",
                t(GAMEPAD_ACTIONS[action], GAMEPAD_ACTIONS_EN[action]), gamepadKeyName(keyCode)), Toast.LENGTH_SHORT).show();
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                showGamepadMapper(map);
            }
        });
    }

    void tell(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    void askImport() {
        new AlertDialog.Builder(this)
                .setTitle(t("Importar partidas", "Import saves"))
                .setMessage(t("Elige un archivo ZIP o una carpeta con partidas de Persona 5 Royal (las carpetas DATA01, "
                                + "DATA02... y SYSTEM, como las exporta JKSV o Checkpoint, o el ZIP que exporta este botón). "
                                + "Antes se guarda una copia de tus partidas actuales.",
                        "Pick a ZIP file or a folder with Persona 5 Royal saves (the DATA01, DATA02... and SYSTEM folders, "
                                + "as JKSV or Checkpoint export them, or the ZIP the Export button makes). Your current saves "
                                + "are copied aside first."))
                .setPositiveButton(t("Archivo ZIP", "ZIP file"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip",
                                "application/x-zip-compressed", "application/octet-stream"});
                        startActivityForResult(i, PICK_ZIP);
                    }
                })
                .setNeutralButton(t("Carpeta", "Folder"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
                    }
                })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PREPARE) {
            refresh();
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_KEYS) {
            onKeysPicked(data);
            refresh();
            return;
        }
        if (data.getData() == null) return;
        if (request == PICK_GAME) {
            onGamePicked(data.getData());
            refresh();
            return;
        }
        if (request == PICK_ICON) {
            onIconPicked(data.getData());
            return;
        }
        if (request == PICK_DRIVER) {
            importDriver(data.getData());
            return;
        }
        if (request == PICK_CPK) {
            importCpk(data.getData());
            return;
        }
        try {
            SaveFiles.Result r = request == PICK_ZIP ? SaveFiles.importZip(getContentResolver(), data.getData())
                    : request == PICK_FOLDER ? SaveFiles.importTree(getContentResolver(), data.getData()) : null;
            if (r == null) {
                tell(t("No hay partidas", "No saves found"),
                        t("Ahí no encontré partidas de Persona 5 Royal: busca las carpetas DATA01, DATA02... o SYSTEM.",
                                "No Persona 5 Royal saves there: look for the DATA01, DATA02... or SYSTEM folders."));
                return;
            }
            String slots = r.slots.isEmpty() ? t("solo ajustes del sistema", "system settings only")
                    : Lang.f("ranuras %s", "slots %s", android.text.TextUtils.join(", ", r.slots));
            String kept = r.backup == null ? "" : Lang.f("\n\nTus partidas anteriores quedaron en user/%s.",
                    "\n\nYour previous saves are in user/%s.", r.backup);
            tell(t("Partidas importadas", "Saves imported"),
                    Lang.f("%d archivos (%s). Ábrelas desde Cargar partida.", "%d files (%s). Open them from Load Game.",
                            r.files, slots) + kept);
        } catch (Exception e) {
            tell(t("No se pudo importar", "Import failed"), String.valueOf(e.getMessage()));
        }
    }

    void exportSaves() {
        try {
            java.io.File out = SaveFiles.export();
            // Listed at once by the file pickers and the Files app (written straight to disk, it would wait for a scan).
            if (out != null) android.media.MediaScannerConnection.scanFile(this, new String[] {out.getPath()}, null, null);
            if (out == null) tell(t("No hay partidas", "No saves"), t("Todavía no hay partidas guardadas.", "There are no saves yet."));
            else tell(t("Partidas exportadas", "Saves exported"),
                    Lang.f("Guardadas en Descargas/%s. Se importan tal cual con el botón Importar.",
                            "Saved to Download/%s. The Import button takes it back as is.", out.getName()));
        } catch (Exception e) {
            tell(t("No se pudo exportar", "Export failed"), String.valueOf(e.getMessage()));
        }
    }

    void askAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 100);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // config.txt: KEY=value lines, in order.
    static Map<String, String> readConfig(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.canRead()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean writeConfig(File f, Map<String, String> config) {
        try (FileWriter w = new FileWriter(f)) {
            for (Map.Entry<String, String> e : config.entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void load() {
        config.clear();
        config.putAll(readConfig(new File(dataDir(), "config.txt")));
    }

    void save() {
        if (!hasAccess()) return;
        dataDir().mkdirs();
        if (!dataDir().isDirectory()) return;
        load();
        for (Option o : options) config.put(o.key, o.values[o.pick]);
        if ("1".equals(config.get("NX_ULTRAWIDE"))) {
            config.put("NX_STRETCH", "0");
            Option stretch = findOption("NX_STRETCH");
            if (stretch != null) stretch.pick = 0;
        }
        final String res = config.get("RESOLUTION");
        if ("360p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "360p");
            config.put("NX_RENDER_RES", "360p");
            config.put("NX_RES", "360p");
        } else if ("480p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "480p");
            config.put("NX_RENDER_RES", "480p");
            config.put("NX_RES", "480p");
        } else if ("1080p".equals(res) || "1440p".equals(res) || "2160p".equals(res) || "native".equals(res)) {
            config.put("NX_HANDHELD", "0");
            config.put("resolution", res);
            config.put("NX_RENDER_RES", res);
            config.put("NX_RES", res);
        } else {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "720p");
            config.put("NX_RENDER_RES", "720p");
            config.put("NX_RES", "720p");
        }
        for (String k : new String[] {"RENDER_SCALE", "NX_ANISO_MAX", "NX_LOD_BIAS"}) config.remove(k);
        final String preset = config.get("GRAPHICS");
        if ("low".equals(preset)) config.put("NX_LOD_BIAS", "1");
        else if ("medium".equals(preset)) config.put("NX_LOD_BIAS", "0.5");
        writeConfig(new File(dataDir(), "config.txt"), config);
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---- custom GPU driver ------------------------------------------------------------------------------------
    // <files>/gpu_driver/<name>.so and active.txt (its name): the runtime opens it with libadrenotools instead of the
    // system Vulkan driver (platform_android.cpp). A .so, or a .zip holding one (the AdrenoTools driver packages:
    // the .so and a meta.json whose "libraryName" is the file to load).
    TextView driverName;

    File driverDir() {
        File d = new File(getFilesDir(), "gpu_driver");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    String activeDriver() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new FileReader(new File(driverDir(), "active.txt")))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    void showDriver() {
        if (driverName == null) return;
        String a = activeDriver();
        driverName.setText(a.isEmpty() ? t("Driver de GPU: del sistema", "GPU driver: system")
                : Lang.f("Driver de GPU: %s", "GPU driver: %s", a));
    }

    void removeDriver() {
        File[] files = driverDir().listFiles();
        if (files != null) for (File f : files) f.delete();
        showDriver();
        Toast.makeText(this, t("Driver del sistema activado", "System driver restored"), Toast.LENGTH_LONG).show();
    }

    static void copy(InputStream in, File dest) throws java.io.IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    void importDriver(Uri uri) {
        try {
            String picked = displayName(uri);
            File dir = driverDir();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            String libName = null;
            if (picked.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String soEntry = null, wanted = null;
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        String base = new File(e.getName()).getName();
                        if (e.isDirectory()) continue;
                        if (base.equals("meta.json")) {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                                    .matcher(bo.toString("UTF-8"));
                            if (m.find()) wanted = m.group(1);
                        } else if (base.endsWith(".so")) {
                            copy(zin, new File(dir, base));
                            if (soEntry == null) soEntry = base;
                        }
                    }
                }
                libName = wanted != null && new File(dir, wanted).exists() ? wanted : soEntry;
            } else {
                libName = picked.endsWith(".so") ? picked : picked + ".so";
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Cannot open selected file");
                    copy(in, new File(dir, libName));
                }
            }
            if (libName == null) throw new java.io.IOException(t("No hay ningun .so dentro", "No .so file inside"));
            try (FileWriter w = new FileWriter(new File(dir, "active.txt"))) {
                w.write(libName + "\n");
            }
            showDriver();
            tell(t("Driver importado", "Driver imported"),
                    Lang.f("%s\n\nCierra el juego por completo y abrelo otra vez. Para volver al driver del sistema pulsa Quitar.",
                            "%s\n\nClose the game completely and start it again. To go back to the system driver press Remove.",
                            libName));
        } catch (Exception e) {
            tell(t("No se pudo importar el driver", "Driver import failed"), String.valueOf(e.getMessage()));
        }
    }

    void importCpk(Uri uri) {
        try {
            File cpkDir = new File(dataDir(), "mods" + File.separator + "translation" + File.separator + "romfs" + File.separator + "CPK");
            if (!cpkDir.exists()) cpkDir.mkdirs();
            File dest = new File(cpkDir, "PATCH1.CPK");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new java.io.IOException("Cannot open selected file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Toast.makeText(this, t("Traducción instalada en mods/translation/ (PATCH1.CPK)",
                    "Translation installed to mods/translation/ (as PATCH1.CPK)"), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, t("Error al importar CPK: " + e.getMessage(),
                    "Error importing CPK: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    void exportLogs() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "p5r-log.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== Persona 5 Royal Recomp Log Export ===\n");
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Date: ").append(new java.util.Date().toString()).append("\n\n");

            try {
                Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-t", "500"});
                try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("logcat capture error: ").append(e.getMessage()).append("\n");
            }

            File nxLog = new File(dataDir(), "nx.log");
            if (nxLog.exists()) {
                sb.append("\n=== nx.log ===\n");
                try (BufferedReader br = new BufferedReader(new FileReader(nxLog))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            }

            File nxLogPrev = new File(dataDir(), "nx.log.prev");
            if (nxLogPrev.exists()) {
                sb.append("\n=== nx.log.prev ===\n");
                try (BufferedReader br = new BufferedReader(new FileReader(nxLogPrev))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            }

            try (FileWriter fw = new FileWriter(out)) {
                fw.write(sb.toString());
            }

            Toast.makeText(this, t("Registros exportados a Download/p5r-log.txt",
                    "Logs exported to Download/p5r-log.txt"), Toast.LENGTH_SHORT).show();

            Uri logUri = Uri.parse("content://" + getPackageName() + ".logprovider" + out.getAbsolutePath());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, logUri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Persona 5 Royal Debug Log");
            share.setClipData(android.content.ClipData.newRawUri("Log", logUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, t("Compartir archivo de registros", "Share log file")));
        } catch (Exception e) {
            Toast.makeText(this, t("Error al exportar registros: " + e.getMessage(),
                    "Error exporting logs: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    void addRow(LinearLayout root, String label, String button, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView name = new TextView(this);
        name.setText(label);
        styleSettingsLabel(name);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        b.setMinWidth(dp(180));
        b.setText(button);
        styleSettingsButton(b);
        b.setOnClickListener(click);
        row.addView(b);
        root.addView(row);
    }
}
