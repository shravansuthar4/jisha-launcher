package com.jisha.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** The real Jisha home screen activity. */
public class MainActivity extends Activity implements HomeScreen.Host {

    static final String EXTRA_RECENTS = "jisha.recents";

    private HomeScreen home;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        home = new HomeScreen(this, this, false);
        setContentView(home.view());
        if (getIntent().getBooleanExtra(EXTRA_RECENTS, false)) home.showRecents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Jisha is now really on screen, so the instant overlay is no longer needed.
        HomeWatcherService.hideOverlay();
        // Every time the home screen appears, start on the first app.
        home.goFirst();
        startService(new Intent(this, HomeWatcherService.class));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_RECENTS, false)) {
            home.showRecents();
        } else {
            home.goFirst();
        }
    }

    @Override
    protected void onDestroy() {
        home.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home screens don't close on Back.
    }

    @Override
    public void openApp(Intent launch, String label) {
        try {
            startActivity(launch);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + label, Toast.LENGTH_SHORT).show();
        }
    }
}
