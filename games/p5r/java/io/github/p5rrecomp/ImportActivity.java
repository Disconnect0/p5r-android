package io.github.p5rrecomp;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Prepares the game once (its own process, ":import": the shader translator's runtime never shares the game's): checks
 * the NSP the player picked with their keys and translates its shaders into Internal storage/Persona 5 Royal/spirv
 * (Importer, games/p5r/import). Started by Launcher with the game's URI and name.
 */
public class ImportActivity extends Activity {
    static final String EXTRA_URI = "uri", EXTRA_NAME = "name";

    TextView status;
    P5Views.SlashProgressBar bar;
    P5Views.SlashButton back;
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean running;

    String t(String spanish, String english) { return Lang.t(spanish, english); }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        hideSystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(P5Theme.INK_BLACK);

        // Subtle crimson diagonal accent slashes in the background
        View bg = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path slash = new Path();

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                c.drawColor(P5Theme.INK_BLACK);
                p.setStyle(Paint.Style.FILL);
                p.setColor(P5Theme.P5_DEEP_RED);
                slash.reset();
                slash.moveTo(0, h * 0.68f);
                slash.lineTo(w * 0.34f, 0);
                slash.lineTo(w * 0.44f, 0);
                slash.lineTo(w * 0.12f, h);
                slash.lineTo(0, h);
                slash.close();
                c.drawPath(slash, p);

                p.setColor(P5Theme.P5_RED);
                slash.reset();
                slash.moveTo(w * 0.78f, 0);
                slash.lineTo(w, 0);
                slash.lineTo(w, h * 0.36f);
                slash.lineTo(w * 0.68f, h);
                slash.lineTo(w * 0.61f, h);
                slash.close();
                c.drawPath(slash, p);
            }
        };
        root.addView(bg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        final FrameLayout stage = new FrameLayout(this);
        stage.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int l = dp(24), r = dp(24), top = dp(18), bot = dp(18);
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout dc = insets.getDisplayCutout();
                    if (dc != null) {
                        l += dc.getSafeInsetLeft();
                        r += dc.getSafeInsetRight();
                        top += dc.getSafeInsetTop();
                        bot += dc.getSafeInsetBottom();
                    }
                }
                v.setPadding(l, top, r, bot);
                return insets;
            }
        });

        P5Views.DialogFrameLayout card = new P5Views.DialogFrameLayout(this);

        P5Views.SectionHeaderView hdr = new P5Views.SectionHeaderView(this,
                t("PREPARANDO EL JUEGO", "PREPARING THE GAME"),
                t("SHADERS · UNA SOLA VEZ", "SHADERS · ONE-TIME"));
        card.addView(hdr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView note = new TextView(this);
        note.setText(t("Solo hace falta una vez. Deja la app abierta hasta que termine (unos minutos).",
                "This is needed only once. Keep the app open until it ends (a few minutes)."));
        note.setTypeface(P5Theme.bodyRegular());
        note.setTextColor(P5Theme.MUTED_TEXT);
        note.setTextSize(13.5f);
        note.setPadding(dp(2), dp(10), dp(2), dp(18));
        card.addView(note);

        bar = new P5Views.SlashProgressBar(this);
        bar.setMax(1000);
        card.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        status = new TextView(this);
        status.setTypeface(P5Theme.condensedBold());
        status.setTextColor(P5Theme.PAPER_WHITE);
        status.setTextSize(16f);
        status.setPadding(dp(2), dp(14), dp(2), dp(14));
        card.addView(status);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);

        back = new P5Views.SlashButton(this, t("VOLVER", "BACK"), true, 46);
        back.setVisibility(View.GONE);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        footer.addView(back, new LinearLayout.LayoutParams(dp(180), dp(46)));
        card.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int maxCardW = Math.min(dp(580), getResources().getDisplayMetrics().widthPixels - dp(48));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                maxCardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        stage.addView(card, cardLp);

        root.addView(stage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        card.playPopIn();
        start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (!running) super.onBackPressed();
    }

    void start() {
        final Uri uri = Uri.parse(getIntent().getStringExtra(EXTRA_URI));
        final String name = getIntent().getStringExtra(EXTRA_NAME);
        final File keys = Launcher.keysFile(this, "prod.keys"), titleKeys = Launcher.keysFile(this, "title.keys");
        final File data = Launcher.dataDir(), work = new File(getCacheDir(), "import");
        running = true;
        status.setText(t("Abriendo el juego…", "Opening the game…"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                int rc;
                String error = "";
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                    int jobs = Math.max(2, Runtime.getRuntime().availableProcessors());
                    rc = Importer.run(pfd.getFd(), name, keys.getPath(), titleKeys.exists() ? titleKeys.getPath() : "",
                            data.getPath(), work.getPath(), jobs);
                    if (rc != 0) error = Importer.error();
                } catch (Exception e) {
                    rc = 1;
                    error = String.valueOf(e.getMessage());
                }
                final int result = rc;
                final String message = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        running = false;
                        back.setVisibility(View.VISIBLE);
                        if (result == 0) {
                            bar.setProgress(1000);
                            status.setTextColor(P5Theme.PAPER_WHITE);
                            status.setText(t("Listo. Ya puedes jugar.", "Done. You can play now."));
                            setResult(RESULT_OK);
                        } else {
                            status.setTextColor(P5Theme.ACID_YELLOW);
                            status.setText(t("No se pudo preparar el juego: ", "The game could not be prepared: ") + message);
                        }
                    }
                });
            }
        }).start();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                int[] p = Importer.progress();
                int phase = p[0], done = p[1], total = Math.max(1, p[2]);
                // the bar: opening 0-5 %, extracting 5-25 %, translating 25-98 %, finishing to 100 %
                double share = (double) Math.min(done, total) / total;
                int value = phase <= 1 ? 20 : phase == 2 ? 50 + (int) (200 * share) : phase == 3 ? 250 + (int) (730 * share) : 990;
                bar.setProgress(value);
                if (phase == 2)
                    status.setText(Lang.f("Extrayendo los shaders del juego (%d de %d)…", "Extracting the game's shaders (%d of %d)…",
                            done, total));
                else if (phase == 3)
                    status.setText(Lang.f("Traduciendo los shaders (%d de %d)…", "Translating the shaders (%d of %d)…", done,
                            total));
                else if (phase == 4)
                    status.setText(t("Terminando…", "Finishing…"));
                ui.postDelayed(this, 500);
            }
        }, 500);
    }
}
