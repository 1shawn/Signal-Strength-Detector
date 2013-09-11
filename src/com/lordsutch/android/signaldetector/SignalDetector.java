package com.lordsutch.android.signaldetector;

// Android Packages

import android.annotation.SuppressLint;
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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.lordsutch.android.signaldetector.SignalDetectorService.LocalBinder;
import com.lordsutch.android.signaldetector.SignalDetectorService.signalInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import static com.lordsutch.android.signaldetector.SignalDetectorService.*;

public final class SignalDetector extends Activity
{
	public static final String TAG = SignalDetector.class.getSimpleName();
	public static final String EMAIL = "lordsutch@gmail.com";
	    
    public static WebView leafletView = null;
    
    /** Called when the activity is first created. */
	@SuppressLint("SetJavaScriptEnabled")
	@Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);        

    	leafletView = (WebView) findViewById(R.id.leafletView);
    	leafletView.loadUrl("file:///android_asset/leaflet.html");
    	
    	WebSettings webSettings = leafletView.getSettings();
    	// webSettings.setAllowFileAccessFromFileURLs(true);
    	webSettings.setJavaScriptEnabled(true);    	   

    	// Enable client caching
    	leafletView.setWebChromeClient(new WebChromeClient() {
    	      @Override
    	      public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
    	                   WebStorage.QuotaUpdater quotaUpdater)
    	      {
    	            quotaUpdater.updateQuota(spaceNeeded * 2);
    	      }
    	});
    	webSettings.setDomStorageEnabled(true);
    	 
    	// This next one is crazy. It's the DEFAULT location for your app's cache
    	// But it didn't work for me without this line.
    	// UPDATE: no hardcoded path. Thanks to Kevin Hawkins
    	String appCachePath = getApplicationContext().getCacheDir().getAbsolutePath();
    	webSettings.setAppCachePath(appCachePath);
    	webSettings.setAllowFileAccess(true);
    	webSettings.setAppCacheEnabled(true);
    	webSettings.setBuiltInZoomControls(false);
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

        checkGooglePlayServicesAvailability();

        // Bind cell tracking service
        Intent intent = new Intent(this, SignalDetectorService.class);
        
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }

    public void checkGooglePlayServicesAvailability()
    {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if(resultCode != ConnectionResult.SUCCESS)
        {
            Dialog dialog = GooglePlayServicesUtil.getErrorDialog(resultCode, this, 69);
            if(dialog != null) {
                dialog.show();
            }
        }

        Log.d("GooglePlayServicesUtil Check", "Result is: " + resultCode);
    }

    @Override
    protected void onResume() {
    	super.onResume();

    	Log.d(TAG, "Resuming");
        if(mSignalInfo != null)
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
    
    private Boolean validSignalStrength(int strength)
    {
    	return (strength > -900 && strength < 900);
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
                	if(activity != null)
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
    	if(bearing > 0) {
    		int index = (int) Math.ceil((bearing + 11.25)/22.5);

    		int dir[] = {0, R.string.bearing_north, R.string.bearing_nne, R.string.bearing_northeast, 
    				R.string.bearing_ene, R.string.bearing_east, R.string.bearing_ese, R.string.bearing_southeast,
    				R.string.bearing_sse, R.string.bearing_south, R.string.bearing_ssw, R.string.bearing_southwest,
    				R.string.bearing_wsw, R.string.bearing_west, R.string.bearing_wnw, R.string.bearing_northwest,
    				R.string.bearing_nnw, R.string.bearing_north};

    		return getResources().getString(dir[index]);
    	}
    	else {
    		return "";
    	}
    }
    
    final private Boolean validPhysicalCellID(int pci)
    {
    	return (pci >= 0 && pci <= 503);
    }

    private boolean tradunits = false;
    private boolean bsmarker = false;
    
    private void updateGui(signalInfo signal) {
    	bslat = signal.bslat;
    	bslon = signal.bslon;
    	
    	if(signal.bearing > 0.0)
    		bearing = signal.bearing;

		TextView latlon = (TextView) findViewById(R.id.positionLatLon);
		
		latlon.setText(String.format("%3.5f\u00b0%s %3.5f\u00b0%s (±%.0f\u202f%s)",
				Math.abs(signal.latitude), getResources().getString(signal.latitude >= 0 ? R.string.bearing_north : R.string.bearing_south),
				Math.abs(signal.longitude), getResources().getString(signal.longitude >= 0 ? R.string.bearing_east : R.string.bearing_west),
				signal.accuracy * accuracyfactor, accuracylabel));

		TextView speed = (TextView) findViewById(R.id.speed);
		
		if(bearing > 0.0)
			speed.setText(String.format("%3.1f %s %s", signal.speed * speedfactor, speedlabel, directionForBearing(bearing)));
		else
			speed.setText(String.format("%3.1f %s", signal.speed * speedfactor, speedlabel));
		
		TextView servingid = (TextView) findViewById(R.id.cellid);
		TextView bsLabel = (TextView) findViewById(R.id.bsLabel);
		TextView cdmaBS = (TextView) findViewById(R.id.cdma_sysinfo);
		TextView cdmaStrength = (TextView) findViewById(R.id.cdmaSigStrength);
        TextView otherSites = (TextView) findViewById(R.id.otherLteSites);

        LinearLayout voiceSignalBlock = (LinearLayout) findViewById(R.id.voiceSignalBlock);

        if(signal.networkType == TelephonyManager.NETWORK_TYPE_LTE) {
            ArrayList<String> cellIds = new ArrayList<String>();

            if(signal.tac < Integer.MAX_VALUE)
                cellIds.add(String.format("TAC\u00a0%04X", signal.tac));

            if(signal.eci < Integer.MAX_VALUE)
                cellIds.add(String.format("GCI\u00a0%08X", signal.eci));

            if(signal.pci < Integer.MAX_VALUE)
                cellIds.add(String.format("PCI\u00a0%03d", signal.pci));

            if(!cellIds.isEmpty()) {
				servingid.setText(TextUtils.join(", ", cellIds));
			} else {
				servingid.setText(R.string.missing);
			}
		} else {
			servingid.setText(R.string.none);
		}

        if(signal.otherCells != null) {
            ArrayList<String> otherSitesList = new ArrayList<String>();
            for (SignalDetectorService.otherLteCell otherCell : signal.otherCells) {
                if (validPhysicalCellID(otherCell.pci) && validSignalStrength(otherCell.lteSigStrength)) {
                    otherSitesList.add(String.format("%03d\u00a0(%d\u202FdBm)",
                            otherCell.pci, otherCell.lteSigStrength));
                }
            }
            if(otherSitesList.isEmpty())
                otherSites.setText(R.string.none);
            else
                otherSites.setText(TextUtils.join("; ", otherSitesList));
        }

		TextView network = (TextView) findViewById(R.id.networkString);

        int voiceSigStrength = Integer.MAX_VALUE;
        boolean voiceDataSame = true;

        if(signal.phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            voiceSigStrength = signal.cdmaSigStrength;
        } else if(signal.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            voiceSigStrength = signal.gsmSigStrength;
        }
        int dataSigStrength = voiceSigStrength;

        switch(signal.networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                if(validSignalStrength(signal.lteSigStrength)) {
                    getActionBar().setLogo(R.drawable.ic_launcher);
                    voiceDataSame = false;
                    dataSigStrength = signal.lteSigStrength;
                } else {
                    getActionBar().setLogo(R.drawable.ic_stat_non4g);
                }
                break;

            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                getActionBar().setLogo(R.drawable.ic_stat_non4g);
                if(validSignalStrength(signal.evdoSigStrength)) {
                    voiceDataSame = false;
                    dataSigStrength = signal.evdoSigStrength;
                }
                break;

            default:
                getActionBar().setLogo(R.drawable.ic_stat_non4g);
                break;
        }

        String netText = String.format("%s %d\u00A0dBm",
                networkString(signal.networkType),
                dataSigStrength);
        if(signal.roaming)
            netText += " " + getString(R.string.roamingInd);

		network.setText(netText);

        if(!voiceDataSame && validSignalStrength(voiceSigStrength)) {
            cdmaStrength.setText(String.valueOf(voiceSigStrength) + "\u202FdBm");
            voiceSignalBlock.setVisibility(View.VISIBLE);
        } else {
            voiceSignalBlock.setVisibility(View.GONE);
        }
		
        ArrayList<String> bsList = new ArrayList<String>();

        if(signal.sid >= 0 && signal.nid >= 0 && signal.bsid >= 0 &&
                (signal.phoneType == TelephonyManager.PHONE_TYPE_CDMA)) {
			bsLabel.setText(R.string.cdma_1xrtt_base_station);

            bsList.add("SID\u00A0"+signal.sid);
            bsList.add("NID\u00A0"+signal.nid);
            bsList.add(String.format("BSID\u00A0%d\u00A0(x%X)", signal.bsid, signal.bsid));
		} else if(signal.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
			bsLabel.setText(R.string._2g_3g_tower);

            bsList.add("MNC\u00A0"+signal.operator);
			if(signal.lac > 0)
                bsList.add("LAC\u00A0"+String.valueOf(signal.lac));
			
			if(signal.rnc > 0 && signal.rnc != signal.lac)
                bsList.add("RNC\u00A0"+String.valueOf(signal.rnc));
				
			if(signal.cid > 0)
                bsList.add("CID\u00A0"+String.valueOf(signal.cid));
			
			if(signal.psc > 0)
                bsList.add("PSC\u00A0"+String.valueOf(signal.psc));
        }

        if(!bsList.isEmpty())
            cdmaBS.setText(TextUtils.join(", ", bsList));
        else
            cdmaBS.setText(R.string.none);

		if(Math.abs(signal.latitude) <= 200)
			centerMap(signal.latitude, signal.longitude, signal.accuracy, signal.avgspeed, bearing,
                    signal.fixAge);
    	addBsMarker();
    }

    private String networkString(int networkType) {
    	switch(networkType) {
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
		return(networkType == TelephonyManager.NETWORK_TYPE_EHRPD ||
				networkType == TelephonyManager.NETWORK_TYPE_EVDO_0 ||
				networkType == TelephonyManager.NETWORK_TYPE_EVDO_A ||
				networkType == TelephonyManager.NETWORK_TYPE_EVDO_B);
	}

	private static void centerMap(double latitude, double longitude, double accuracy, double speed,
                                  double bearing, long fixAge) {
        boolean staleFix = fixAge > (30*1000); // 30 seconds

		leafletView.loadUrl(String.format("javascript:recenter(%f,%f,%f,%f,%f,%s)",
				latitude, longitude, accuracy, speed, bearing, staleFix));
    }
    
    private void addBsMarker() {
    	SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    	bsmarker = sharedPref.getBoolean("show_base_station", false);
    	
    	if(bsmarker && Math.abs(bslat) <= 90 && Math.abs(bslon) <= 190)
    		leafletView.loadUrl(String.format("javascript:placeMarker(%f,%f)",
    				bslat, bslon));
    	else
    		leafletView.loadUrl("javascript:clearMarker()");
    }
    
    private void updateUnits() {
    	if(tradunits) {
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
    	if(mBound) {
    		unbindService(mConnection);
    		mBound = false;
    	}

    	Intent intent = new Intent(this, SignalDetectorService.class);
        stopService(intent);
    	finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(mBound) {
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