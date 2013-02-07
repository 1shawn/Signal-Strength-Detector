package com.lordsutch.android.signaldetector;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.lordsutch.android.signaldetector.HomeActivity.IncomingHandler;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellLocation;
import android.telephony.CellSignalStrengthLte;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

public class SignalDetectorService extends Service {
	public static final String TAG = HomeActivity.class.getSimpleName();

	public static final int MSG_SIGNAL_UPDATE = 1;

	private Builder mBuilder;
	private int mNotificationId = 1;
	
	private CellLocation mCellLocation;
	private SignalStrength mSignalStrength;
	private TelephonyManager mManager;
	private Object mHTCManager;
	private LocationManager mLocationManager;
    private Location mLocation = null;    
    private List<CellInfo> mCellInfo = null;
    private NotificationManager mNotifyMgr;
    
    IBinder mBinder = new LocalBinder();      // interface for clients that bind

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        SignalDetectorService getService() {
            // Return this instance of SignalDetectorService so clients can call public methods
            return SignalDetectorService.this;
        }
    }

	@Override
	public IBinder onBind(Intent intent) {
		Intent resultIntent = new Intent(this, HomeActivity.class);
    	PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, 0);

    	mBuilder = new Notification.Builder(this)
    		    .setSmallIcon(R.drawable.ic_launcher)
    		    .setContentTitle(getString(R.string.signal_detector_is_running))
    		    .setContentText("Hello World!")
    		    .setOnlyAlertOnce(true)
    		    .setPriority(Notification.PRIORITY_LOW)
    		    .setContentIntent(resultPendingIntent);

    	startForeground(mNotificationId, mBuilder.build());

        mManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mHTCManager = getSystemService("htctelephony");
    	
    	// Register the listener with the telephony manager
    	mManager.listen(mListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS |
    		PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_CELL_INFO);
    	
    	Criteria gpsCriteria = new Criteria();
    	gpsCriteria.setCostAllowed(false);
    	gpsCriteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
    	
    	List<String> providers = mLocationManager.getProviders(gpsCriteria, true);
    	
    	for(String provider : providers) {
    		Log.d(TAG, "Registering "+provider);
    		mLocationManager.requestLocationUpdates(provider, 1000, 10, mLocListener);
    		Location mLoc = mLocationManager.getLastKnownLocation(provider);

    		if(mLoc != null)
    			mLocation = getBetterLocation(mLoc, mLocation);
    	}
    	mNotifyMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    	
    	return mBinder;
	}

    private void appendLog(String logfile, String text, String header)
    {       
    	Boolean newfile = false;
    	File logFile = new File(getExternalFilesDir(null), logfile);
    	if (!logFile.exists())
    	{
    		try
    		{
    			logFile.createNewFile();
    			newfile = true;
    		} 
    		catch (IOException e)
    		{
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		}
    	}
    	try
    	{
    		//BufferedWriter for performance, true to set append to file flag
    		BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true)); 
    		if (newfile) {
    			buf.append(header);
    			buf.newLine();
    		}
    		buf.append(text);
    		buf.newLine();
    		buf.close();
    	}
    	catch (IOException e)
    	{
    		// TODO Auto-generated catch block
    		e.printStackTrace();
    	}
    }

    private int parseSignalStrength() {
    	String sstrength = mSignalStrength.toString();
    	int strength = -999;
    	
    	String[] bits = sstrength.split("\\s+");
    	if(bits.length >= 10)
    		try {
    			strength = Integer.parseInt(bits[9]);
    		}
    		catch (NumberFormatException e) {}
    	
    	return strength;
    }
    
    final private Boolean validSignalStrength(int strength)
    {
    	return (strength > -900 && strength < 900);
    }
    
	class signalInfo {
		// Location location = null;
		
		double longitude;
		double latitude;
		double accuracy;
		double speed;

		// LTE
		String cellID = "";
		int physCellID = -1;
		int tac = -1;
		int mcc = -1;
		int mnc = -1;
		int lteSigStrength = Integer.MAX_VALUE;
		
		// CDMA2000
		int bsid = -1;
		int nid = -1;
		int sid = -1;
		double bslat = 999;
		double bslon = 999;
		int cdmaSigStrength = -9999;

		// GSM/UMTS/W-CDMA
		String operator = "";
		int lac = -1;
		int cid = -1;
		int psc = -1;
		int rnc = -1;
		int gsmSigStrength = -9999;
		
		int phoneType = TelephonyManager.PHONE_TYPE_NONE;
	}
	
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	private void updatelog(boolean log) {
    	if(mLocation == null || mSignalStrength == null || mCellLocation == null)
    		return;

		Boolean gotID = false;
		
    	signalInfo signal = new signalInfo();
    	
    	signal.latitude = mLocation.getLatitude();
    	signal.longitude = mLocation.getLongitude();
    	signal.speed = mLocation.getSpeed();
    	signal.accuracy = mLocation.getAccuracy();
    	
		signal.phoneType = mManager.getPhoneType();

		signal.operator = mManager.getNetworkOperator();

		if(mCellLocation.getClass() == CdmaCellLocation.class) {
			CdmaCellLocation x = (CdmaCellLocation) mCellLocation;

//			Log.d(TAG, x.toString());
			
			signal.bsid = x.getBaseStationId();
			signal.nid = x.getNetworkId();
			signal.sid = x.getSystemId();
			
			signal.bslat = x.getBaseStationLatitude()/14400.0;
			signal.bslon = x.getBaseStationLongitude()/14400.0;
		}
		
		if(mCellLocation.getClass() == GsmCellLocation.class) {
			GsmCellLocation x = (GsmCellLocation) mCellLocation;
			
//			Log.d(TAG, x.toString());
			
			signal.lac = x.getLac();
			signal.psc = x.getPsc();
			signal.cid = x.getCid();
			if(signal.cid >= 0) {
				signal.rnc = signal.cid >> 16;
				signal.cid = signal.cid & 0xffff;
			}			
		}
		
		signal.cdmaSigStrength = mSignalStrength.getCdmaDbm();
		signal.gsmSigStrength = mSignalStrength.getGsmSignalStrength();
		
		signal.gsmSigStrength = (signal.gsmSigStrength < 32 ? -113+2*signal.gsmSigStrength : -9999);
		
    	if(mCellInfo != null) {
    		for(CellInfo item : mCellInfo) {
    			if(item != null && item.getClass() == CellInfoLte.class) {
    				CellInfoLte x = (CellInfoLte) item;
    				CellSignalStrengthLte cstr = x.getCellSignalStrength();
    				if(cstr != null)
    					signal.lteSigStrength = cstr.getDbm();

    				CellIdentityLte cellid = x.getCellIdentity();
    				if(cellid != null) {
    					signal.cellID = String.format("%08x", cellid.getCi());
    					signal.physCellID = cellid.getPci();
    					signal.tac = cellid.getTac();
    					signal.mnc = cellid.getMnc();
    					signal.mcc = cellid.getMcc();
    					gotID = true;
    				}
    			}
    		}
    	}
    	
    	if(!validSignalStrength(signal.lteSigStrength))
    		signal.lteSigStrength = parseSignalStrength();
    	
		if(!gotID && mHTCManager != null) {
			Method m = null;
			
			try {
				m = mHTCManager.getClass().getMethod("getSectorId", int.class);
				signal.cellID = (String) m.invoke(mHTCManager, new Object[] {Integer.valueOf(1)} );
			} catch (NoSuchMethodException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		if(!validSignalStrength(signal.lteSigStrength)) {
			Method m;

			try {
				m = mSignalStrength.getClass().getMethod("getLteRsrp");
				signal.lteSigStrength = (Integer) m.invoke(mSignalStrength, (Object []) null);
			} catch (NoSuchMethodException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		if(signal.cellID.isEmpty())
			mBuilder.setContentText(getString(R.string.serving_lte_cell_id) + ": " + getString(R.string.none));
		else
			mBuilder.setContentText(getString(R.string.serving_lte_cell_id) + ": " + signal.cellID);

		mBuilder.setSmallIcon(validSignalStrength(signal.lteSigStrength) ? R.drawable.ic_launcher : R.drawable.ic_stat_non4g);
    	mNotifyMgr.notify(mNotificationId, mBuilder.build());

    	if(log) {
        	String slat = Location.convert(signal.latitude, Location.FORMAT_DEGREES);
        	String slon = Location.convert(signal.longitude, Location.FORMAT_DEGREES);

    		if(validSignalStrength(signal.lteSigStrength) || signal.physCellID >= 0 || !signal.cellID.isEmpty()) {
    			Log.d(TAG, "Logging LTE cell.");
    			appendLog("ltecells.csv", slat+","+slon+","+signal.cellID+","+
    					(signal.physCellID >= 0 ? String.valueOf(signal.physCellID) : "")+","+
    					(validSignalStrength(signal.lteSigStrength) ? String.valueOf(signal.lteSigStrength) : ""),
    					"latitude,longitude,cellid,physcellid,dBm");
    		}
    		if(signal.sid >= 22404 && signal.sid <= 22451)
    		{
    			String bslatstr = (signal.bslat <= 200 ? Location.convert(signal.bslat, Location.FORMAT_DEGREES) : "");
    			String bslonstr = (signal.bslon <= 200 ? Location.convert(signal.bslon, Location.FORMAT_DEGREES) : "");

    			Log.d(TAG, "Logging ESMR cell.");
    			appendLog("esmrcells.csv", 
    					String.format("%s,%s,%d,%d,%d,%s,%s,%s", slat, slon, signal.sid, signal.nid, signal.bsid,
    							(validSignalStrength(signal.cdmaSigStrength) ? String.valueOf(signal.cdmaSigStrength) : ""),
    							bslatstr, bslonstr), "latitude,longitude,sid,nid,bsid,rssi,bslat,bslon");
    		}
    	}
    	
    	if(pushMessenger != null) {
        	Message msg = new Message();
        	msg.obj = signal;
        	msg.what = MSG_SIGNAL_UPDATE;
    		try {
				pushMessenger.send(msg);
			} catch (RemoteException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}    	
    }
    
    private final LocationListener mLocListener = new LocationListener()
    {
    	@Override
    	public void onLocationChanged(Location mLoc)
    	{
    		mLocation = getBetterLocation(mLoc, mLocation);
    		
    		if(mLocation != null)
    			updatelog(true);
    	}

		@Override
		public void onProviderDisabled(String provider) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onProviderEnabled(String provider) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
			// TODO Auto-generated method stub
			
		}
    };

    private static final int TWO_MINUTES = 1000 * 60 * 2;

    /** Determines whether one Location reading is better than the current Location fix.
     * Code taken from
     * http://developer.android.com/guide/topics/location/obtaining-user-location.html
     *
     * @param newLocation  The new Location that you want to evaluate
     * @param currentBestLocation  The current Location fix, to which you want to compare the new
     *        one
     * @return The better Location object based on recency and accuracy.
     */
   protected Location getBetterLocation(Location newLocation, Location currentBestLocation) {
       if (currentBestLocation == null) {
           // A new location is always better than no location
           return newLocation;
       }

       // Check whether the new location fix is newer or older
       long timeDelta = newLocation.getTime() - currentBestLocation.getTime();
       boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
       boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
       boolean isNewer = timeDelta > 0;

       // If it's been more than two minutes since the current location, use the new location
       // because the user has likely moved.
       if (isSignificantlyNewer) {
           return newLocation;
       // If the new location is more than two minutes older, it must be worse
       } else if (isSignificantlyOlder) {
           return currentBestLocation;
       }

       // Check whether the new location fix is more or less accurate
       int accuracyDelta = (int) (newLocation.getAccuracy() - currentBestLocation.getAccuracy());
       boolean isLessAccurate = accuracyDelta > 0;
       boolean isMoreAccurate = accuracyDelta < 0;
       boolean isSignificantlyLessAccurate = accuracyDelta > 200;

       // Check if the old and new location are from the same provider
       boolean isFromSameProvider = isSameProvider(newLocation.getProvider(),
               currentBestLocation.getProvider());

       // Determine location quality using a combination of timeliness and accuracy
       if (isMoreAccurate) {
           return newLocation;
       } else if (isNewer && !isLessAccurate) {
           return newLocation;
       } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
           return newLocation;
       }
       return currentBestLocation;
   }

   /** Checks whether two providers are the same */
   private boolean isSameProvider(String provider1, String provider2) {
       if (provider1 == null) {
         return provider2 == null;
       }
       return provider1.equals(provider2);
   }

    // Listener for signal strength.
    final PhoneStateListener mListener = new PhoneStateListener()
    {
    	@Override
    	public void onCellLocationChanged(CellLocation mLocation)
    	{
    		mCellLocation = mLocation;
//    		if(mCellLocation != null) {
//    			Log.d(TAG, mCellLocation.toString());
//    		}
			updatelog(true);
    	}
    	
    	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
		@Override
    	public void onCellInfoChanged(List<CellInfo> mInfo)
    	{
    		mCellInfo = mInfo;
    		if(mCellInfo != null) {
    			Log.d(TAG, mCellInfo.toString());
    		}
			updatelog(true);
    	}

    	@Override
    	public void onSignalStrengthsChanged(SignalStrength sStrength)
    	{
    		mSignalStrength = sStrength;
//    		if(mSignalStrength != null) {
//    			Log.d(TAG, mSignalStrength.toString());
//    		}
    		updatelog(true);
    	}
    };

	@Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed
        mLocationManager.removeUpdates(mLocListener);
        mManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
		stopForeground(true);
    }

	private Messenger pushMessenger = null;
	public void setMessenger(Messenger mMessenger) {
		pushMessenger = mMessenger;
//		Log.d(TAG, "pushMessenger set");
		if(pushMessenger != null)
			Log.d(TAG, pushMessenger.toString());

		updatelog(false);
	}
}
