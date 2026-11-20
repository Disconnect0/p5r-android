package io.github.p5rrecomp;

import android.app.NativeActivity;
import android.content.Intent;
import android.view.KeyEvent;

/** NativeActivity wrapper that turns Android's Back shortcut into an in-game pause screen. */
public final class GameActivity extends NativeActivity {
    private boolean pauseScreenOpening;

    @Override
    protected void onResume() {
        super.onResume();
        pauseScreenOpening = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) openPauseScreen();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        openPauseScreen();
    }

    private void openPauseScreen() {
        if (pauseScreenOpening || isFinishing()) return;
        pauseScreenOpening = true;
        startActivity(new Intent(this, PauseActivity.class));
        overridePendingTransition(0, 0);
    }
}
