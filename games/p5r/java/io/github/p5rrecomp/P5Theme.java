package io.github.p5rrecomp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Typeface;

/**
 * Visual design tokens and geometry helpers for the P5R Launcher rework.
 * Currently configured for Direction A: Authentic P5 Pause / Calling Card.
 */
final class P5Theme {
    // Strict high-contrast print palette (no soft gradients, no muddy grays)
    static final int INK_BLACK      = Color.rgb(10, 10, 12);
    static final int INK_SURFACE    = Color.argb(232, 13, 13, 17);
    static final int INK_ELEVATED   = Color.rgb(24, 24, 30);
    static final int P5_RED         = Color.rgb(228, 0, 18);
    static final int P5_CRIMSON_BG  = Color.rgb(206, 6, 22);
    static final int P5_DEEP_RED    = Color.rgb(154, 0, 14);
    static final int PAPER_WHITE    = Color.rgb(246, 245, 240);
    static final int ACID_YELLOW    = Color.rgb(255, 230, 0);
    static final int MUTED_TEXT     = Color.rgb(176, 176, 184);
    static final int BORDER_SUBTLE  = Color.rgb(58, 58, 66);
    static final int SCRIM_HOME     = Color.argb(118, 10, 10, 12);
    static final int SCRIM_SETTINGS = Color.argb(208, 10, 10, 12);

    private static Typeface sDisplayBold;
    private static Typeface sCondensedBold;
    private static Typeface sBodyRegular;
    private static Typeface sMonoBold;

    static Typeface displayBold() {
        if (sDisplayBold == null) {
            sDisplayBold = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC);
        }
        return sDisplayBold;
    }

    static Typeface condensedBold() {
        if (sCondensedBold == null) {
            sCondensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
        return sCondensedBold;
    }

    static Typeface bodyRegular() {
        if (sBodyRegular == null) {
            sBodyRegular = Typeface.create("sans-serif", Typeface.NORMAL);
        }
        return sBodyRegular;
    }

    static Typeface monoBold() {
        if (sMonoBold == null) {
            sMonoBold = Typeface.create("monospace", Typeface.BOLD);
        }
        return sMonoBold;
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static int dpx(Context c, float v) {
        return Math.round(dp(c, v));
    }

    /**
     * Builds an asymmetric sharp-cut parallelogram/trapezoid polygon (no rounded corners).
     * Positive skewTop shifts top-left/bottom-right inward like P5's slanted slabs.
     */
    static void setSlantedQuad(Path p, float l, float t, float r, float b, float skewLeft, float skewRight) {
        p.reset();
        p.moveTo(l + Math.max(0f, skewLeft), t);
        p.lineTo(r - Math.max(0f, -skewRight), t);
        p.lineTo(r - Math.max(0f, skewRight), b);
        p.lineTo(l + Math.max(0f, -skewLeft), b);
        p.close();
    }

    /**
     * Builds a slightly hand-cut Calling Card polygon with subtle asymmetric corner offsets.
     */
    static void setCallingCardQuad(Path p, float l, float t, float r, float b,
                                   float tlx, float tly, float trx, float tryOff,
                                   float brx, float bry, float blx, float bly) {
        p.reset();
        p.moveTo(l + tlx, t + tly);
        p.lineTo(r + trx, t + tryOff);
        p.lineTo(r + brx, b + bry);
        p.lineTo(l + blx, b + bly);
        p.close();
    }
}
