package com.tvkouame.studio;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Sauvegarde automatique dans KouameAp chaque fois qu'un écran de l'application passe en arrière-plan. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStopped(Activity a) { KouameAp.sauvegarderAsync(a); }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
