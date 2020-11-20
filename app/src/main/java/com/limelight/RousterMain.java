package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.ServerHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RousterMain extends Activity {
    public WebView webView;
    // Change to local address (e.g. http://192.168.1.8:3000/) during development.
    final public String baseWebAppUrl = "https://hybrid.rouster.eu";
    final public String defaultPin = "6174";
    final public String gameStreamToolPort = "48765";
    final public int maxPairingAttempts = 100;

    final private ChromeClient chromeClient = new ChromeClient();
    private String computerIpAddress;

    public void setWebViewRoute(final String path) {
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl("javascript:(function f() { window.androidAppHistroy.push('" + path + "'); } )()");
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rouster_main);

        ConnectivityManager con_manager = (ConnectivityManager)
                RousterMain.this.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (con_manager.getActiveNetworkInfo() != null && con_manager.getActiveNetworkInfo().isAvailable() && con_manager.getActiveNetworkInfo().isConnected()) {
            webView = findViewById(R.id.rousterWebUi);
            WebSettings webSettings = webView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webView.setWebChromeClient(this.chromeClient);
            webView.loadUrl(this.baseWebAppUrl);
            webView.setWebViewClient(new WebViewClient(){});
        } else {
            Toast.makeText(getApplicationContext(), "No Internet! Please connect in order to use Rouster!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.chromeClient.cleanUp();
    }

    private class ChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            LimeLog.info("Rouster - WebView - Intercept: " + view.getUrl() + " (" + progress + ")");

            Uri uri = Uri.parse(webView.getUrl());
            String path = uri.getLastPathSegment();

            if (progress == 100) {
                switch (path) {
                    case "doConnect":
                        RousterMain.this.computerIpAddress = uri.getQueryParameter("computer_ip_address");
                        this.doConnect();
                        break;
                    case "doShutDown":
                        this.shutDown();
                        break;
                    case "doDeleteConnection":
                        this.doDeleteConnection();
                        break;
                    case "doSettings":
                        startActivity(new Intent(RousterMain.this, StreamSettings.class));

                        RousterMain.this.setWebViewRoute("on-hold");
                        break;
                }
            }
        }

        public void doConnect() {
            LimeLog.info("Rouster - Prepare to start streaming");
            bindService(new Intent(RousterMain.this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
        }

        public void shutDown() {
            LimeLog.info("Rouster - Shut Down the app");

            if(Build.VERSION.SDK_INT>=16 && Build.VERSION.SDK_INT<21){
                finishAffinity();
            } else if(Build.VERSION.SDK_INT>=21){
                finishAndRemoveTask();
            }
        }

        public void doDeleteConnection() {
            LimeLog.info("Rouster - Prepare to delete connection");

            ComputerDatabaseManager cpm = new ComputerDatabaseManager(RousterMain.this);
            List<ComputerDetails> allComputers = cpm.getAllComputers();

            for (ComputerDetails d : allComputers) { cpm.deleteComputer(d); }
        }

        public void cleanUp() {
            LimeLog.info("Rouster - Cleaning up");

            try {
                unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                LimeLog.info("Rouster - Unbounding unbounded service.");
            }

            managerBinder = null;
            connectionInProgress = false;
        }

        private ComputerManagerService.ComputerManagerBinder managerBinder;
        private boolean connectionInProgress = false;

        private final ServiceConnection serviceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder binder) {
                final ComputerManagerService.ComputerManagerBinder localBinder = ((ComputerManagerService.ComputerManagerBinder)binder);
                new Thread() {
                    @Override
                    public void run() {
                        localBinder.waitForReady();
                        managerBinder = localBinder;

                        if(ensureComputerExists()) {
                            // Connect to the current user's dedicated VM's streaming.
                            LimeLog.info("Rouster - Start obtaining computer details");
                            startComputerConnect();

                            // Force a keypair to be generated early to avoid discovery delays
                            new AndroidCryptoProvider(RousterMain.this).getClientCertificate();
                        } else {
                            LimeLog.severe("Rouster - Cannot initialize computer");
                            RousterMain.this.setWebViewRoute("oops");
                            cleanUp();
                        }
                    }
                }.start();
            }

            public void onServiceDisconnected(ComponentName className) { cleanUp(); }
        };

        private boolean ensureComputerExists() {
            // Ensure the current computer is the only one in the DB
            ComputerDatabaseManager cpm = new ComputerDatabaseManager(RousterMain.this);
            List<ComputerDetails> allComputers = cpm.getAllComputers();
            ComputerDetails details = null;
            String ip = RousterMain.this.computerIpAddress;
            boolean hasComputer = false;

            for (ComputerDetails d : allComputers) {
                if (d.manualAddress.equals(ip)) {
                    LimeLog.info("Rouster - Computer found. " + d.manualAddress);
                    details = d;
                    hasComputer = true;
                } else {
                    LimeLog.info("Rouster - Old computer found. Delete. " + d.manualAddress);
                    cpm.deleteComputer(d);
                }
            }

            if (details == null) {
                LimeLog.info("Rouster - Computer not found. Add.");

                try {
                    details = new ComputerDetails();
                    details.manualAddress = ip;
                    hasComputer = managerBinder.addComputerBlocking(details);
                } catch (IllegalArgumentException e) {
                    LimeLog.warning("Rouster - Cannot add new computer: " + e.toString());
                }
            }

            return hasComputer;
        }

        private void startComputerConnect() {
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    LimeLog.info("Rouster - Computer state: " + details.state + "; Connection in progress: " + connectionInProgress);

                    if (details.state != ComputerDetails.State.ONLINE || connectionInProgress) {
                        LimeLog.info("Rouster - Computer not online or connection already in progress. Waiting");
                        return;
                    }

                    LimeLog.info("Rouster - Computer is online.");
                    connectionInProgress = true;

                    try {
                        NvHTTP  httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(details),
                                managerBinder.getUniqueId(),
                                details.serverCert,
                                PlatformBinding.getCryptoProvider(RousterMain.this));
                        PairingManager.PairState pairState = httpConn.getPairState();

                        if (pairState != PairingManager.PairState.PAIRED) {
                            LimeLog.info("Rouster - Computer not paired - Start pairing");

                            PairingManager pm = httpConn.getPairingManager();
                            int currentPairingAttempt = 0;

                            // This is actually needed only if the host is NVidia GameStream based.
                            // When the host is Sunshine is just do nothing, so I'm leaving it here, no matter the host
                            // Also, as it is delayed, when host is Sunshine, most of the time it should
                            // be stopped before even a single attempt to be executed.
                            ScheduledExecutorService sendPinTask = Executors.newSingleThreadScheduledExecutor();
                            sendPinTask.scheduleAtFixedRate(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        URL url = new URL("http://" + details.manualAddress + ":" + gameStreamToolPort + "/connect?" + defaultPin);
                                        LimeLog.info("Rouster - Send PIN to the host; URL: " + url);
                                        url.getContent();
                                    } catch (IOException e) {
                                        LimeLog.warning("Rouster - Send PIN exception caught - " + e.toString());
                                    }
                                }
                            }, 2, 2, TimeUnit.SECONDS);

                            do {
                                currentPairingAttempt++;
                                LimeLog.info("Rouster - Start pairing attempt " + currentPairingAttempt);

                                try {
                                    LimeLog.info("Rouster - Open pairing dialog at the client");

                                    // "Pair" is blocking, so we wait until the above "schedule" is done.
                                    pairState = pm.pair(httpConn.getServerInfo(), RousterMain.this.defaultPin);

                                    LimeLog.info("Rouster - Manager pair state: " + pairState + "; Connection pair state: " + httpConn.getPairState());
                                } catch (IOException e) {
                                    LimeLog.warning("Rouster - Pairing exception caught - " + e.toString());
                                }
                            } while (pairState != PairingManager.PairState.PAIRED && currentPairingAttempt < RousterMain.this.maxPairingAttempts);

                            managerBinder.getComputer(details.uuid).serverCert = pm.getPairedCert();

                            sendPinTask.shutdownNow();

                            if (pairState != PairingManager.PairState.PAIRED) {
                                LimeLog.warning("Rouster - Unable to pair");
                                RousterMain.this.setWebViewRoute("oops");
                                cleanUp();
                            }
                        }

                        // There is still chance that we are not paired
                        if (pairState == PairingManager.PairState.PAIRED) {
                            LimeLog.info("Rouster - Obtaining 'Desktop' app");
                            NvApp desktopApp;
                            String appListRaw = httpConn.getAppListRaw();
                            List<NvApp> appList = NvHTTP.getAppListByReader(new StringReader(appListRaw));
                            do {
                                desktopApp = appList.remove(0);
                            } while (!desktopApp.getAppName().equals("Desktop"));

                            LimeLog.info("Rouster - Flush computer details to DB");
                            ComputerDatabaseManager dbManager = new ComputerDatabaseManager(getApplicationContext());
                            dbManager.updateComputer(details);

                            LimeLog.info("Rouster - Start streaming");
                            ServerHelper.doStart(RousterMain.this, desktopApp, details, managerBinder);

                            RousterMain.this.setWebViewRoute("on-hold");
                        }
                    } catch (IOException e) {
                        LimeLog.warning("Rouster - IO Error - " + e.toString());
                        RousterMain.this.setWebViewRoute("oops");
                    } catch (XmlPullParserException e) {
                        LimeLog.warning("Rouster - Apps Error - " + e.toString());
                        RousterMain.this.setWebViewRoute("oops");
                    } catch (IndexOutOfBoundsException e) {
                        LimeLog.warning("Rouster - Error - 'Desktop' application not found!");
                        RousterMain.this.setWebViewRoute("oops");
                    } catch (NullPointerException e) {
                        LimeLog.warning("Rouster - Null Pointer Error - " + e.toString());
                        RousterMain.this.setWebViewRoute("oops");
                    } finally {
                        cleanUp();
                    }
                }
            });
        }
    }
}
