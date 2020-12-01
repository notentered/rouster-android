package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.auth.result.AuthSignInResult;
import com.amplifyframework.core.Amplify;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import java.security.cert.CertificateEncodingException;

public class RousterMain extends Activity {
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == AWSCognitoAuthPlugin.WEB_UI_SIGN_IN_ACTIVITY_CODE) {
            Amplify.Auth.handleWebUISignInResponse(data);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        Amplify.Auth.signInWithWebUI(
                this,
                this::onAuthenticate,
                error -> LimeLog.severe("Rouster - Amplify - Auth: " + error.toString())
        );
    }

    public void onAuthenticate(AuthSignInResult result) {
        LimeLog.info("Rouster - Amplify - Auth: " + result.toString());

//        Amplify.Auth.signOut(
//                () -> LimeLog.info("Rouster - Amplify - Auth: Signed out successfully"),
//                error -> LimeLog.severe("Rouster - Amplify - Auth: " + error.toString())
//        );

        ComputerDatabaseManager dbManager = new ComputerDatabaseManager(this);
        ComputerDetails existingComputer = dbManager.getComputerByUUID("ddb8a81e-0bef-44fe-bf26-d1df5ed687a9");

        Intent intent = new Intent(this, Game.class);
        intent.putExtra(Game.EXTRA_HOST, existingComputer.manualAddress);
        intent.putExtra(Game.EXTRA_APP_NAME, "mstsc.exe");
        intent.putExtra(Game.EXTRA_APP_ID, 12869904);
        // No HDR (https://github.com/moonlight-stream/moonlight-qt/issues/61)
        intent.putExtra(Game.EXTRA_APP_HDR, false);
        intent.putExtra(Game.EXTRA_UNIQUEID, "dde1c4033302271d");
        intent.putExtra(Game.EXTRA_PC_UUID, existingComputer.uuid);
        intent.putExtra(Game.EXTRA_PC_NAME, existingComputer.name);
        try {
            if (existingComputer.serverCert != null) {
                intent.putExtra(Game.EXTRA_SERVER_CERT, existingComputer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }

        startActivity(intent);
    }
}