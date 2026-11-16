package dev.lsiwo.motionfg;

import android.app.Application;

public final class AppText extends Application {
    private static volatile AppText app;

    @Override public void onCreate() {
        super.onCreate();
        app = this;
    }

    static String get(int id) {
        return app.getString(id);
    }
}
