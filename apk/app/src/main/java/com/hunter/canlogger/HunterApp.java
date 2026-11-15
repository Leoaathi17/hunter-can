package com.hunter.canlogger;

import android.app.Application;
import android.content.SharedPreferences;

import com.hunter.canlogger.core.AppSettings;
import com.hunter.canlogger.core.CanEngine;

/**
 * Process-wide owner of the CanEngine and the saved settings. The engine lives here, not in the service,
 * so profiles, bitrate and signals can be edited before the CANable is connected.
 */
public class HunterApp extends Application {
    private static final String PREFS = "hunter_can";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_ONBOARDING = "onboarding_done";

    private static HunterApp instance;
    private CanEngine engine;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        AppSettings settings = AppSettings.fromJson(prefs.getString(KEY_SETTINGS, null));
        engine = new CanEngine(settings);
        engine.setSettingsListener(new Runnable() {
            @Override
            public void run() {
                prefs.edit().putString(KEY_SETTINGS, engine.settingsJson()).apply();
            }
        });
    }

    static HunterApp get() { return instance; }

    static CanEngine engine() { return instance.engine; }

    static boolean onboardingDone() { return instance.prefs.getBoolean(KEY_ONBOARDING, false); }

    static void setOnboardingDone(boolean done) { instance.prefs.edit().putBoolean(KEY_ONBOARDING, done).apply(); }
}
