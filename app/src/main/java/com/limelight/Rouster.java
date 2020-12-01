package com.limelight;

import android.app.Application;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.core.Amplify;

public class Rouster extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Amplify.addPlugin(new AWSCognitoAuthPlugin());
            Amplify.configure(getApplicationContext());

            LimeLog.info("Rouster - Amplify - Initialized");
        } catch (AmplifyException e) {
            LimeLog.info("Rouster - Amplify - Could not initialize " + e.toString());
        }
    }
}