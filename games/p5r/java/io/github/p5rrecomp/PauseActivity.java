package io.github.p5rrecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pause overlay for the native game; settings are saved for the next runtime start. */
public final class PauseActivity extends Activity {
    static final int ORANGE = Color.rgb(255, 164, 66);
    static final int YELLOW = Color.rgb(255, 211, 79);
    static final int WHITE = Color.rgb(239, 241, 250);
    static final int MUTED = Color.rgb(184, 188, 208);
    static final int PANEL = Color.rgb(23, 25, 47);
    static final String MAP_KEY = "NX_GAMEPAD_MAP";
    static final String[] MAP_ACTIONS_ES = {"A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−", "L3", "R3",
            "D-pad izquierda", "D-pad arriba", "D-pad derecha", "D-pad abajo"};
    static final String[] MAP_ACTIONS_EN = {"A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−", "L3", "R3",
            "D-pad left", "D-pad up", "D-pad right", "D-pad down"};

    static final class Choice {
        final String category, key, es, en;
        final String[] values, labelsEs, labelsEn;
        Choice(String category, String key, String es, String en, String[] values, String[] labelsEs, String[] labelsEn) {
            this.category = category;
            this.key = key;
            this.es = es;
            this.en = en;
            this.values = values;
            this.labelsEs = labelsEs;
            this.labelsEn = labelsEn;
        }
    }

    final List<Choice> choices = new ArrayList<>();
    final Map<String, String> config = new LinkedHashMap<>();
    final List<Button> categoryButtons = new ArrayList<>();
    final String[] categories = {
            t("Video", "Video"), t("Rendimiento", "Performance"), t("Efectos", "Effects"),
            t("Interfaz", "Interface"), t("Juego", "Game"), t("Mandos", "Controllers")
    };
    LinearLayout settingsPanel;
    int selectedCategory;

    static String t(String es, String en) { return Lang.t(es, en); }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        loadOptions();
        config.putAll(Launcher.readConfig(new File(Launcher.dataDir(), "config.txt")));
        buildScreen();
    }

    void loadOptions() {
        choices.add(new Choice(categories[0], "RESOLUTION", "Resolución interna", "Internal resolution",
                new String[] {"360p", "480p", "720p", "1080p"}, new String[] {"360p", "480p", "720p", "1080p"},
                new String[] {"360p", "480p", "720p", "1080p"}));
        choices.add(toggle(categories[0], "NX_STRETCH", "Pantalla completa (estirar)", "Fullscreen (stretch)", false));
        choices.add(toggle(categories[0], "NX_FSR", "Superresolución FSR", "FSR super resolution", false));
        choices.add(new Choice(categories[0], "NX_HUD_1080", "Interfaz", "Interface", new String[] {"0", "1"},
                new String[] {"720p · más rápida", "1080p · textos nítidos"}, new String[] {"720p · faster", "1080p · sharper text"}));

        choices.add(new Choice(categories[1], "GRAPHICS", "Calidad de gráficos", "Graphics quality",
                new String[] {"low", "medium", "high"}, new String[] {"Bajos", "Medios", "Altos"},
                new String[] {"Low", "Medium", "High"}));
        choices.add(new Choice(categories[1], "NX_FPS_CAP", "Límite de fotogramas", "Frame rate limit",
                new String[] {"30", "60"}, new String[] {"30 fps", "60 fps · experimental"},
                new String[] {"30 fps", "60 fps · experimental"}));
        choices.add(toggle(categories[1], "NX_SHADOWS", "Sombras", "Shadows", true));
        choices.add(new Choice(categories[1], "NX_MALI_LOW_SHADOWS", "Calidad de sombras", "Shadow quality",
                new String[] {"1", "0"}, new String[] {"Baja · más rendimiento", "Original"},
                new String[] {"Low · better performance", "Original"}));

        choices.add(new Choice(categories[2], "NX_NO_CROWD", "Multitudes de NPC", "NPC crowds",
                new String[] {"0", "1"}, new String[] {"Activadas", "Desactivadas · más FPS"},
                new String[] {"Enabled", "Disabled · more FPS"}));
        choices.add(new Choice(categories[2], "NX_DISABLE_BLOOM", "Bloom y brillo", "Bloom and glow",
                new String[] {"0", "1"}, new String[] {"Activados", "Desactivados · más FPS"},
                new String[] {"Enabled", "Disabled · more FPS"}));

        choices.add(toggle(categories[3], "NX_SHOW_FPS", "Contador de FPS", "FPS counter", true));
        choices.add(new Choice(categories[3], "NX_TOUCH_SHOW", "Controles táctiles", "Touch controls",
                new String[] {"1", "0"}, new String[] {"Activados", "Desactivados"}, new String[] {"On", "Off"}));
        choices.add(toggle(categories[3], "NX_TOUCH_HIDE", "Ocultar controles táctiles", "Hide touch controls", true));
        choices.add(new Choice(categories[3], "NX_TOUCH_SIZE", "Tamaño de botones táctiles", "Touch button size",
                new String[] {"80", "100", "125"}, new String[] {"Pequeño", "Normal", "Grande"},
                new String[] {"Small", "Normal", "Large"}));
        choices.add(new Choice(categories[3], "NX_TOUCH_OPACITY", "Opacidad de controles", "Control opacity",
                new String[] {"50", "85", "100"}, new String[] {"Baja", "Normal", "Alta"},
                new String[] {"Low", "Normal", "High"}));
        choices.add(new Choice(categories[3], "NX_STICK_SENSITIVITY", "Sensibilidad de palanca", "Stick sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"}));
        choices.add(new Choice(categories[3], "NX_CAMERA_SENSITIVITY", "Sensibilidad de cámara", "Camera sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200", "250"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"}));

        choices.add(new Choice(categories[4], "language", "Idioma de textos y subtítulos", "Text and subtitle language",
                new String[] {"auto", "en-US", "es", "fr", "de", "it", "ja", "ko", "zh-Hans", "zh-Hant"},
                new String[] {"Automático", "English", "Español", "Français", "Deutsch", "Italiano", "日本語", "한국어", "简体中文", "繁體中文"},
                new String[] {"Automatic", "English", "Español", "Français", "Deutsch", "Italiano", "日本語", "한국어", "简体中文", "繁體中文"}));
        choices.add(new Choice(categories[5], "NX_GAMEPAD_LAYOUT", "Distribución A/B", "A/B layout",
                new String[] {"0", "1"}, new String[] {"Nintendo · A a la derecha", "Xbox · A abajo"},
                new String[] {"Nintendo · A on right", "Xbox · A on bottom"}));
    }

    Choice toggle(String category, String key, String es, String en, boolean defaultOn) {
        return new Choice(category, key, es, en, new String[] {defaultOn ? "1" : "0", defaultOn ? "0" : "1"},
                new String[] {t("Activado", "On"), t("Desactivado", "Off")},
                new String[] {t("Activado", "On"), t("Desactivado", "Off")});
    }

    void buildScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.argb(218, 15, 16, 31));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(18), dp(28), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("PERSONA 5 ROYAL", YELLOW, 27, true);
        titleBox.addView(title);
        titleBox.addView(text(t("EN PAUSA", "PAUSED"), MUTED, 15, true));
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button quit = actionButton(t("↪  Salir", "↪  Quit"), false);
        quit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { returnToLauncher(); }
        });
        header.addView(quit, buttonParams(170, 58));
        Button resume = actionButton(t("▶  Continuar", "▶  Continue"), true);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams resumeParams = buttonParams(220, 58);
        resumeParams.leftMargin = dp(12);
        header.addView(resume, resumeParams);
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        bodyParams.topMargin = dp(16);
        bodyParams.bottomMargin = dp(8);
        root.addView(body, bodyParams);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        body.addView(side, new LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.MATCH_PARENT));
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(22), dp(18), dp(22), dp(14));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.argb(245, 23, 25, 47));
        panelBg.setCornerRadius(dp(18));
        panelBg.setStroke(dp(1), Color.rgb(54, 59, 89));
        settingsPanel.setBackground(panelBg);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        panelParams.leftMargin = dp(16);
        body.addView(settingsPanel, panelParams);

        for (int i = 0; i < categories.length; i++) {
            final int index = i;
            Button category = actionButton(categories[i], i == selectedCategory);
            category.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            category.setTextSize(17);
            category.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedCategory = index;
                    refreshCategories();
                    showCategory();
                }
            });
            LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
            catParams.bottomMargin = dp(8);
            side.addView(category, catParams);
            categoryButtons.add(category);
        }

        TextView hint = text(t("Los cambios se guardan para el próximo inicio del juego.",
                "Changes are saved for the next game launch."), MUTED, 12, false);
        hint.setPadding(dp(4), 0, 0, 0);
        root.addView(hint);

        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        frame.addView(root, full);
        setContentView(frame);
        showCategory();
        focusWhenReady(resume);
    }

    void refreshCategories() {
        for (int i = 0; i < categoryButtons.size(); i++) {
            boolean active = i == selectedCategory;
            Button b = categoryButtons.get(i);
            b.setTextColor(active ? Color.rgb(42, 27, 30) : WHITE);
            b.setBackground(buttonBackground(active));
        }
    }

    void showCategory() {
        settingsPanel.removeAllViews();
        TextView heading = text(categories[selectedCategory], YELLOW, 23, true);
        settingsPanel.addView(heading);
        View accent = new View(this);
        accent.setBackgroundColor(ORANGE);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(72), dp(4));
        accentParams.topMargin = dp(10);
        accentParams.bottomMargin = dp(12);
        settingsPanel.addView(accent, accentParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (final Choice choice : choices) if (choice.category.equals(categories[selectedCategory])) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            TextView label = text(t(choice.es, choice.en), WHITE, 16, false);
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button value = actionButton(choiceLabel(choice), false);
            value.setTextSize(15);
            value.setMinWidth(dp(150));
            value.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { advance(choice); showCategory(); }
            });
            row.addView(value);
            rows.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
            View divider = new View(this);
            divider.setBackgroundColor(Color.rgb(48, 51, 76));
            rows.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
        if (categories[selectedCategory].equals(categories[5])) addGamepadMapperRow(rows);
        if (categories[selectedCategory].equals(categories[3])) addControlsPositionRow(rows);
        scroll.addView(rows);
        settingsPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    void addGamepadMapperRow(LinearLayout rows) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(t("Asignar botones del mando", "Remap gamepad buttons"), WHITE, 16, false);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button configure = actionButton(t("Configurar", "Configure"), false);
        configure.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGamepadMapper(readGamepadMap()); }
        });
        row.addView(configure);
        rows.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));
    }

    void addControlsPositionRow(LinearLayout rows) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(t("Posición de los controles táctiles", "Touch control positions"), WHITE, 16, false);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button edit = actionButton(t("Mover", "Move"), false);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(PauseActivity.this, ControlsEditor.class)); }
        });
        row.addView(edit);
        rows.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));
    }

    void advance(Choice choice) {
        String current = config.get(choice.key);
        int index = 0;
        for (int i = 0; i < choice.values.length; i++) if (choice.values[i].equals(current)) index = i;
        config.put(choice.key, choice.values[(index + 1) % choice.values.length]);
        if ("RESOLUTION".equals(choice.key)) {
            String resolution = config.get(choice.key);
            config.put("resolution", resolution);
            config.put("NX_RENDER_RES", resolution);
            config.put("NX_RES", resolution);
            config.put("NX_HANDHELD", "1080p".equals(resolution) ? "0" : "1");
        } else if ("GRAPHICS".equals(choice.key)) {
            config.remove("NX_LOD_BIAS");
            String preset = config.get(choice.key);
            if ("low".equals(preset)) config.put("NX_LOD_BIAS", "1");
            else if ("medium".equals(preset)) config.put("NX_LOD_BIAS", "0.5");
        }
        persist();
    }

    String choiceLabel(Choice choice) {
        String current = config.get(choice.key);
        for (int i = 0; i < choice.values.length; i++)
            if (choice.values[i].equals(current)) return Lang.t(choice.labelsEs[i], choice.labelsEn[i]);
        return Lang.t(choice.labelsEs[0], choice.labelsEn[0]);
    }

    boolean persist() {
        File dir = Launcher.dataDir();
        if (!dir.exists()) dir.mkdirs();
        boolean saved = Launcher.writeConfig(new File(dir, "config.txt"), config);
        if (!saved) Toast.makeText(this, t("No se pudo guardar la configuración", "Could not save settings"),
                Toast.LENGTH_SHORT).show();
        return saved;
    }

    void showGamepadMapper(final int[] map) {
        String[] rows = new String[map.length];
        for (int i = 0; i < rows.length; i++) rows[i] = Lang.t(MAP_ACTIONS_ES[i], MAP_ACTIONS_EN[i])
                + "  ·  " + gamepadKeyName(map[i]);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Asignar botones del mando", "Remap gamepad buttons"))
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { captureGamepadButton(map, which); }
                })
                .setNeutralButton(t("Restaurar preset", "Restore preset"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        config.remove(MAP_KEY);
                        persist();
                    }
                })
                .setNegativeButton(t("Cerrar", "Close"), null).create();
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int key, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
                if (key == KeyEvent.KEYCODE_BUTTON_A) {
                    int which = dialog.getListView().getSelectedItemPosition();
                    if (which >= 0) dialog.getListView().performItemClick(
                            dialog.getListView().getSelectedView(), which, dialog.getListView().getItemIdAtPosition(which));
                    return true;
                }
                if (key == KeyEvent.KEYCODE_BUTTON_B) { dialog.cancel(); return true; }
                return false;
            }
        });
        dialog.show();
    }

    void captureGamepadButton(final int[] map, final int action) {
        TextView prompt = text(t("Pulsa el botón que quieres asignar. Atrás cancela.",
                "Press the button to assign. Back cancels."), WHITE, 18, false);
        prompt.setPadding(dp(22), dp(18), dp(22), dp(18));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(Lang.f("Asignar %s", "Assign %s", Lang.t(MAP_ACTIONS_ES[action], MAP_ACTIONS_EN[action])))
                .setView(prompt).setNegativeButton(t("Cancelar", "Cancel"), null).create();
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int key, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
                if (key == KeyEvent.KEYCODE_BACK || !isGamepadKey(event, key)) return false;
                int captured = canonicalKey(key);
                int old = map[action];
                for (int i = 0; i < map.length; i++) if (i != action && map[i] == captured) map[i] = old;
                map[action] = captured;
                StringBuilder encoded = new StringBuilder();
                for (int i = 0; i < map.length; i++) {
                    if (i != 0) encoded.append(',');
                    encoded.append(map[i]);
                }
                config.put(MAP_KEY, encoded.toString());
                persist();
                dialog.dismiss();
                return true;
            }
        });
        dialog.show();
    }

    boolean isGamepadKey(KeyEvent event, int key) {
        if (KeyEvent.isGamepadButton(key)) return true;
        int sources = event.getSource();
        InputDevice device = event.getDevice();
        if (device != null) sources |= device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    int[] readGamepadMap() {
        String encoded = config.get(MAP_KEY);
        if (encoded != null) {
            String[] fields = encoded.split(",", -1);
            if (fields.length == MAP_ACTIONS_ES.length) try {
                int[] result = new int[fields.length];
                for (int i = 0; i < fields.length; i++) result[i] = canonicalKey(Integer.parseInt(fields[i]));
                return result;
            } catch (NumberFormatException ignored) {}
        }
        boolean xbox = "1".equals(config.get("NX_GAMEPAD_LAYOUT"));
        return new int[] {xbox ? KeyEvent.KEYCODE_BUTTON_A : KeyEvent.KEYCODE_BUTTON_B,
                xbox ? KeyEvent.KEYCODE_BUTTON_B : KeyEvent.KEYCODE_BUTTON_A,
                xbox ? KeyEvent.KEYCODE_BUTTON_X : KeyEvent.KEYCODE_BUTTON_Y,
                xbox ? KeyEvent.KEYCODE_BUTTON_Y : KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN};
    }

    int canonicalKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_1: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_2: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_3: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_4: return KeyEvent.KEYCODE_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_5: case KeyEvent.KEYCODE_BUTTON_C: return KeyEvent.KEYCODE_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_6: case KeyEvent.KEYCODE_BUTTON_Z: return KeyEvent.KEYCODE_BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_7: return KeyEvent.KEYCODE_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_8: return KeyEvent.KEYCODE_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_9: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_10: case KeyEvent.KEYCODE_BUTTON_MODE: return KeyEvent.KEYCODE_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_11: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case KeyEvent.KEYCODE_BUTTON_12: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            default: return key;
        }
    }

    String gamepadKeyName(int key) {
        return KeyEvent.keyCodeToString(key).replace("KEYCODE_BUTTON_", "")
                .replace("KEYCODE_DPAD_", "D-pad ").replace("KEYCODE_", "");
    }

    void returnToLauncher() {
        Intent i = new Intent(this, Launcher.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                View focus = getCurrentFocus();
                if (focus != null && focus.isEnabled() && focus.isClickable()) focus.performClick();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() { finish(); }

    Button actionButton(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(selected ? Color.rgb(42, 27, 30) : WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setMinHeight(dp(48));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setBackground(buttonBackground(selected));
        return b;
    }

    GradientDrawable buttonBackground(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(selected ? ORANGE : Color.argb(225, 25, 27, 52));
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), selected ? ORANGE : Color.rgb(79, 83, 112));
        return d;
    }

    LinearLayout.LayoutParams buttonParams(int width, int height) {
        return new LinearLayout.LayoutParams(dp(width), dp(height));
    }

    TextView text(String value, int color, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return v;
    }

    void focusWhenReady(final View v) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.post(new Runnable() { @Override public void run() { v.requestFocus(); } });
    }
}
