package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.amazonaws.amplify.generated.graphql.CloudComputerInfoQuery;
import com.amazonaws.mobile.config.AWSConfiguration;
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient;
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers;
import com.amazonaws.mobileconnectors.appsync.sigv4.BasicCognitoUserPoolsAuthProvider;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool;
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.auth.result.AuthSignInResult;
import com.amplifyframework.core.Amplify;

import com.apollographql.apollo.GraphQLCall;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.exception.ApolloException;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import java.security.cert.CertificateEncodingException;

import javax.annotation.Nonnull;

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




        AWSConfiguration awsConfig = new AWSConfiguration(getApplicationContext());

        CognitoUserPool cognitoUserPool = new CognitoUserPool(getApplicationContext(), awsConfig);
        BasicCognitoUserPoolsAuthProvider basicCognitoUserPoolsAuthProvider =
                new BasicCognitoUserPoolsAuthProvider(cognitoUserPool);

        AWSAppSyncClient awsAppSyncClient = AWSAppSyncClient.builder()
                .context(getApplicationContext())
                .awsConfiguration(awsConfig)
                .cognitoUserPoolsAuthProvider(basicCognitoUserPoolsAuthProvider)
                .build();

        GraphQLCall.Callback<CloudComputerInfoQuery.Data> cloudComputerCallback = new GraphQLCall.Callback<CloudComputerInfoQuery.Data>() {
            @Override
            public void onResponse(@Nonnull Response<CloudComputerInfoQuery.Data> response) {
                LimeLog.severe("Results: " + response.data().toString());
            }

            @Override
            public void onFailure(@Nonnull ApolloException e) {
                LimeLog.severe("ERROR: " + e.toString());
            }
        };

        awsAppSyncClient.query(CloudComputerInfoQuery.builder().build())
            .responseFetcher(AppSyncResponseFetchers.NETWORK_ONLY)
            .enqueue(cloudComputerCallback);



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