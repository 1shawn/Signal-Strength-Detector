package com.lordsutch.android.signaldetector;

// Android Packages

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lordsutch.android.signaldetector.SignalDetectorService.LocalBinder;
import com.lordsutch.android.signaldetector.SignalDetectorService.signalInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;

import static com.lordsutch.android.signaldetector.SignalDetectorService.MSG_SIGNAL_UPDATE;

public final class SignalDetector extends Activity {
    public static final String TAG = SignalDetector.class.getSimpleName();

    public static WebView leafletView = null;
    private TelephonyManager mTelephonyManager = null;

    /**
     * Called when the activity is first created.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // getWindow().requestFeature(Window.FEATURE_PROGRESS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.main);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        leafletView = (WebView) findViewById(R.id.leafletView);

        WebSettings webSettings = leafletView.getSettings();
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadsImagesAutomatically(true);

        final Activity activity = this;

        leafletView.setWebChromeClient(new WebChromeClient() {
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(activity, description, Toast.LENGTH_SHORT).show();
            }

            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " -- From line "
                        + cm.lineNumber() + " of "
                        + cm.sourceId());
                return true;
            }

            // Enable client caching
            @Override
            public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
                                                 WebStorage.QuotaUpdater quotaUpdater) {
                quotaUpdater.updateQuota(spaceNeeded * 2);
            }
        });

        webSettings.setDomStorageEnabled(true);

    	/*
        This next one is crazy. It's the DEFAULT location for your app's cache
    	But it didn't work for me without this line.
    	UPDATE: no hardcoded path. Thanks to Kevin Hawkins */
        String appCachePath = getApplicationContext().getCacheDir().getAbsolutePath();
        webSettings.setAppCachePath(appCachePath);
        webSettings.setAppCacheEnabled(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setAllowFileAccess(true);

        leafletView.loadUrl("file:///android_asset/leaflet.html");
        // leafletView.loadUrl("http://www.openstreetmap.org/");

        reloadPreferences();
    }

    private SignalDetectorService mService;
    private boolean mBound = false;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.mainmenu, menu);

        return true;
    }

    // Define a DialogFragment that displays the error dialog
    public static class ErrorDialogFragment extends DialogFragment {
        // Global field to contain the error dialog
        private Dialog mDialog;

        // Default constructor. Sets the dialog field to null
        public ErrorDialogFragment() {
            super();
            mDialog = null;
        }

        // Set the dialog to display
        public void setDialog(Dialog dialog) {
            mDialog = dialog;
        }

        // Return a Dialog to the DialogFragment.
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return mDialog;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        bindSDService();
    }

    private void bindSDService() {
        // Bind cell tracking service
        Intent intent = new Intent(this, SignalDetectorService.class);

        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }

    private void unbindSDService() {
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        Intent intent = new Intent(this, SignalDetectorService.class);
        stopService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

//        Log.d(TAG, "Resuming");
        // leafletView.reload();
        if (mSignalInfo != null)
            updateGui(mSignalInfo);
    }

    private void enableLocationSettings() {
        Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(settingsIntent);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LocalBinder binder = (LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            mService.setMessenger(mMessenger);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    private Boolean validLTESignalStrength(int strength) {
        return (strength > -200 && strength < 0);
    }

    private Boolean validRSSISignalStrength(int strength) {
        return (strength > -120 && strength < 0);
    }

    private Boolean validCellID(int eci) {
        return (eci >= 0 && eci <= 0x0FFFFFFF);
    }

    private double bslat = 999;
    private double bslon = 999;

    /**
     * Activity Handler of incoming messages from service.
     */
    static class IncomingHandler extends Handler {
        private final WeakReference<SignalDetector> mActivity;

        IncomingHandler(SignalDetector activity) {
            mActivity = new WeakReference<SignalDetector>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            SignalDetector activity = mActivity.get();
            switch (msg.what) {
                case MSG_SIGNAL_UPDATE:
                    if (activity != null)
                        activity.updateSigInfo((signalInfo) msg.obj);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    private signalInfo mSignalInfo = null;

    public void updateSigInfo(signalInfo signal) {
        mSignalInfo = signal;
        updateGui(signal);
    }

    double speedfactor = 3.6;
    String speedlabel = "km/h";

    double accuracyfactor = 1.0;
    String accuracylabel = "m";

    double bearing = 0.0;

    private String directionForBearing(double bearing) {
        if (bearing > 0) {
            int index = (int) Math.ceil((bearing + 11.25) / 22.5);

            int dir[] = {0, R.string.bearing_north, R.string.bearing_nne, R.string.bearing_northeast,
                    R.string.bearing_ene, R.string.bearing_east, R.string.bearing_ese, R.string.bearing_southeast,
                    R.string.bearing_sse, R.string.bearing_south, R.string.bearing_ssw, R.string.bearing_southwest,
                    R.string.bearing_wsw, R.string.bearing_west, R.string.bearing_wnw, R.string.bearing_northwest,
                    R.string.bearing_nnw, R.string.bearing_north};

            return getResources().getString(dir[index]);
        } else {
            return "";
        }
    }

    private Boolean validPhysicalCellID(int pci) {
        return (pci >= 0 && pci <= 503);
    }

    private boolean tradunits = false;
    private boolean bsmarker = false;

    private void updateGui(signalInfo signal) {
        bslat = signal.bslat;
        bslon = signal.bslon;

        if (signal.bearing > 0.0)
            bearing = signal.bearing;

        TextView latlon = (TextView) findViewById(R.id.positionLatLon);

        latlon.setText(String.format("%3.5f\u00b0%s %3.5f\u00b0%s (\u00b1%.0f\u202f%s)",
                Math.abs(signal.latitude), getResources().getString(signal.latitude >= 0 ? R.string.bearing_north : R.string.bearing_south),
                Math.abs(signal.longitude), getResources().getString(signal.longitude >= 0 ? R.string.bearing_east : R.string.bearing_west),
                signal.accuracy * accuracyfactor, accuracylabel));

        TextView speed = (TextView) findViewById(R.id.speed);

        if (bearing > 0.0)
            speed.setText(String.format("%3.1f %s %s", signal.speed * speedfactor, speedlabel,
                    directionForBearing(bearing)));
        else
            speed.setText(String.format("%3.1f %s", signal.speed * speedfactor, speedlabel));

        TextView servingid = (TextView) findViewById(R.id.cellid);
        TextView bsLabel = (TextView) findViewById(R.id.bsLabel);
        TextView cdmaBS = (TextView) findViewById(R.id.cdma_sysinfo);
        TextView cdmaStrength = (TextView) findViewById(R.id.cdmaSigStrength);
        TextView otherSites = (TextView) findViewById(R.id.otherLteSites);

        LinearLayout voiceSignalBlock = (LinearLayout) findViewById(R.id.voiceSignalBlock);
        LinearLayout lteBlock = (LinearLayout) findViewById(R.id.lteBlock);
        LinearLayout lteOtherBlock = (LinearLayout) findViewById(R.id.lteOtherBlock);
        LinearLayout preLteBlock = (LinearLayout) findViewById(R.id.preLteBlock);

        if (signal.networkType == TelephonyManager.NETWORK_TYPE_LTE) {
            ArrayList<String> cellIds = new ArrayList<String>();

            if (validTAC(signal.tac))
                cellIds.add(String.format("TAC\u00a0%04X", signal.tac));

            if (validCellID(signal.eci))
                cellIds.add(String.format("GCI\u00a0%08X", signal.eci));

            if (validPhysicalCellID(signal.pci))
                cellIds.add(String.format("PCI\u00a0%03d", signal.pci));

            if (!cellIds.isEmpty()) {
                servingid.setText(TextUtils.join(", ", cellIds));
            } else {
                servingid.setText(R.string.missing);
            }
            lteBlock.setVisibility(View.VISIBLE);
            lteOtherBlock.setVisibility(View.VISIBLE);
        } else {
            servingid.setText(R.string.none);
            lteBlock.setVisibility(View.GONE);
            lteOtherBlock.setVisibility(View.GONE);
        }

        if (signal.otherCells != null) {
            ArrayList<String> otherSitesList = new ArrayList<String>();

            Collections.sort(otherSitesList);

            for (SignalDetectorService.otherLteCell otherCell : signal.otherCells) {
                if (validPhysicalCellID(otherCell.pci) && validLTESignalStrength(otherCell.lteSigStrength)) {
                    otherSitesList.add(String.format("%03d\u00a0(%d\u202FdBm)",
                            otherCell.pci, otherCell.lteSigStrength));
                }
            }
            if (otherSitesList.isEmpty())
                otherSites.setText(R.string.none);
            else
                otherSites.setText(TextUtils.join("; ", otherSitesList));
        }

        TextView network = (TextView) findViewById(R.id.networkString);

        int voiceSigStrength = Integer.MAX_VALUE;
        boolean voiceDataSame = true;

        if (signal.phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            voiceSigStrength = signal.cdmaSigStrength;
        } else if (signal.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            voiceSigStrength = signal.gsmSigStrength;
        }
        int dataSigStrength = voiceSigStrength;
        boolean lteMode = false;

        switch (signal.networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                if (validLTESignalStrength(signal.lteSigStrength)) {
                    getActionBar().setLogo(R.drawable.ic_launcher);
                    voiceDataSame = false;
                    dataSigStrength = signal.lteSigStrength;
                    lteMode = true;
                } else {
                    getActionBar().setLogo(R.drawable.ic_stat_non4g);
                }
                break;

            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                getActionBar().setLogo(R.drawable.ic_stat_non4g);
                if (validRSSISignalStrength(signal.evdoSigStrength)) {
                    voiceDataSame = false;
                    dataSigStrength = signal.evdoSigStrength;
                }
                break;

            default:
                getActionBar().setLogo(R.drawable.ic_stat_non4g);
                break;
        }

        String netText = networkString(signal.networkType);
        if (lteMode && validMnc(signal.mcc) && validMnc(signal.mnc)) {
            netText += String.format(" %03d%03d", signal.mcc, signal.mnc);
        }

        if (validLTESignalStrength(dataSigStrength)) {
            netText += String.format(" %d\u202FdBm", dataSigStrength);
        }

        if (signal.roaming)
            netText += " " + getString(R.string.roamingInd);

        network.setText(netText);

        if (!voiceDataSame && validRSSISignalStrength(voiceSigStrength)) {
            cdmaStrength.setText(String.valueOf(voiceSigStrength) + "\u202FdBm");
            voiceSignalBlock.setVisibility(View.VISIBLE);
        } else {
            voiceSignalBlock.setVisibility(View.GONE);
        }

        ArrayList<String> bsList = new ArrayList<String>();

        if (signal.sid >= 0 && signal.nid >= 0 && signal.bsid >= 0 &&
                (signal.phoneType == TelephonyManager.PHONE_TYPE_CDMA)) {
            bsLabel.setText(R.string.cdma_1xrtt_base_station);

            bsList.add("SID\u00A0" + signal.sid);
            bsList.add("NID\u00A0" + signal.nid);
            bsList.add(String.format("BSID\u00A0%d\u00A0(x%X)", signal.bsid, signal.bsid));
        } else if (signal.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            bsLabel.setText(R.string._2g_3g_tower);

            bsList.add("MNC\u00A0" + signal.operator);
            if (signal.lac > 0)
                bsList.add("LAC\u00A0" + String.valueOf(signal.lac));

            if (signal.rnc > 0 && signal.rnc != signal.lac)
                bsList.add("RNC\u00A0" + String.valueOf(signal.rnc));

            if (signal.cid > 0)
                bsList.add("CID\u00A0" + String.valueOf(signal.cid));

            if (signal.psc > 0)
                bsList.add("PSC\u00A0" + String.valueOf(signal.psc));
        }

        if (!bsList.isEmpty()) {
            cdmaBS.setText(TextUtils.join(", ", bsList));
            preLteBlock.setVisibility(View.VISIBLE);
        } else {
            cdmaBS.setText(R.string.none);
            preLteBlock.setVisibility(View.GONE);
        }

        if (Math.abs(signal.latitude) <= 200)
            centerMap(signal.latitude, signal.longitude, signal.accuracy, signal.avgspeed, bearing,
                    signal.fixAge);
        addBsMarker();
    }

    private boolean validTAC(int tac) {
        return (tac > 0x0000 && tac < 0xFFFF); // 0, FFFF are reserved values
    }

    private boolean validMnc(int mcc) {
        return (mcc > 0 && mcc <= 999);
    }

    private String networkString(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "eHRPD";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "EVDO Rel. 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "EVDO Rev. A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "EVDO Rev. B";
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "1xRTT";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "iDEN";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            default:
                return "Unknown";
        }
    }

    private boolean isEVDONetwork(int networkType) {
        return (networkType == TelephonyManager.NETWORK_TYPE_EHRPD ||
                networkType == TelephonyManager.NETWORK_TYPE_EVDO_0 ||
                networkType == TelephonyManager.NETWORK_TYPE_EVDO_A ||
                networkType == TelephonyManager.NETWORK_TYPE_EVDO_B);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    // Use evaluateJavascript if available (KITKAT+), otherwise hack
    private void execJavascript(String script) {
//        Log.d(TAG, script);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            leafletView.evaluateJavascript(script, null);
        else
            leafletView.loadUrl("javascript:" + script);
    }

    private void centerMap(double latitude, double longitude, double accuracy, double speed,
                           double bearing, long fixAge) {
        boolean staleFix = fixAge > (30 * 1000); // 30 seconds

        String operator = mTelephonyManager.getSimOperator();
        if (operator == null)
            operator = mTelephonyManager.getNetworkOperator();
        if (operator == null)
            operator = "";

        execJavascript(String.format("recenter(%f,%f,%f,%f,%f,%s,\"%s\");",
                latitude, longitude, accuracy, speed, bearing, staleFix, operator));
    }

    private void addBsMarker() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        bsmarker = sharedPref.getBoolean("show_base_station", false);

        if (bsmarker && Math.abs(bslat) <= 90 && Math.abs(bslon) <= 190)
            execJavascript(String.format("placeMarker(%f,%f);", bslat, bslon));
        else
            execJavascript("clearMarker();");
    }

    private void updateUnits() {
        if (tradunits) {
            speedfactor = 2.237;
            speedlabel = "mph";
            accuracyfactor = 3.28084;
            accuracylabel = "ft";
        } else {
            speedfactor = 3.6;
            speedlabel = "km/h";
            accuracyfactor = 1.0;
            accuracylabel = "m";
        }
    }

    public void launchSettings(MenuItem x) {
        Intent myIntent = new Intent(this, SettingsActivity.class);
        startActivityForResult(myIntent, 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        reloadPreferences();
        unbindSDService();
        bindSDService();
    }

    public void clearMapCache() {
        leafletView.clearCache(true);
    }

    private void reloadPreferences() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        bsmarker = sharedPref.getBoolean("show_base_station", false);
        tradunits = sharedPref.getBoolean("traditional_units", false);

        updateUnits();
    }

    public void exitApp(MenuItem x) {
        unbindSDService();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
//        System.gc();
    }

    /**
     * Dialog to prompt users to enable GPS on the device.
     */
    @SuppressLint("ValidFragment")
    public class EnableGpsDialogFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.enable_gps)
                    .setMessage(R.string.enable_gps_dialog)
                    .setPositiveButton(R.string.enable_gps, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            enableLocationSettings();
                        }
                    })
                    .create();
        }
    }
}
