package com.lordsutch.android.signaldetector;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
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

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class SignalDetectorService extends Service implements
	ConnectionCallbacks, OnConnectionFailedListener {
	public static final String TAG = SignalDetector.class.getSimpleName();

	public static final int MSG_SIGNAL_UPDATE = 1;

	private Builder mBuilder;
	private int mNotificationId = 1;
	
	private CellLocation mCellLocation;
	private SignalStrength mSignalStrength;
	private TelephonyManager mManager;
	private Object mHTCManager;
    private Location mLocation = null;    
    private List<CellInfo> mCellInfo = null;
    private NotificationManager mNotifyMgr;
    private PendingIntent pintent = null;

    IBinder mBinder = new LocalBinder();      // interface for clients that bind

	private LocationClient mLocationClient;

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

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	@Override
	public IBinder onBind(Intent intent) {
		Intent resultIntent = new Intent(this, SignalDetector.class);
    	PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, 0);

    	mBuilder = new Notification.Builder(this).setSmallIcon(R.drawable.ic_stat_0g)
    		    .setContentTitle(getString(R.string.signal_detector_is_running))
    		    .setContentText("Loading...")
    		    .setOnlyAlertOnce(true)
    		    .setPriority(Notification.PRIORITY_LOW)
    		    .setContentIntent(resultPendingIntent);

    	startForeground(mNotificationId, mBuilder.build());

        mManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mHTCManager = getSystemService("htctelephony");
    	
    	// Register the listener with the telephony manager
    	mManager.listen(mListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS |
    		PhoneStateListener.LISTEN_CELL_LOCATION);
    	
        mLocationClient = new LocationClient(this, this, this);
        mLocationClient.connect();
    	mNotifyMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if(pintent == null) {
            Calendar cal = Calendar.getInstance();

            Intent xintent = new Intent(this, SignalDetectorService.class);
            pintent = PendingIntent.getService(this, 0, xintent, 0);

            AlarmManager alarm = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            // Start every 30 seconds
            alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), 30*1000, pintent);
        }
        return mBinder;
	}

    private void appendLog(String logfile, String text, String header)
    {       
    	Boolean newfile = false;
    	File filesdir = getExternalFilesDir(null);
    	
    	File logFile = new File(filesdir, logfile);
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
    
    final private Boolean validPhysicalCellID(int pci)
    {
    	return (pci >= 0 && pci <= 503);
    }

    class otherLteCell {
        int pci = Integer.MAX_VALUE;
        int lteSigStrength = Integer.MAX_VALUE;
    }
        
	class signalInfo {
		// Location location = null;
		
		double longitude;
		double latitude;
		double altitude;
		double accuracy;
		double speed;
		double avgspeed;
		double bearing;

		// LTE
		int eci = Integer.MAX_VALUE;
		int pci = Integer.MAX_VALUE;
		int tac = Integer.MAX_VALUE;
		int mcc = Integer.MAX_VALUE;
		int mnc = Integer.MAX_VALUE;
		int lteSigStrength = Integer.MAX_VALUE;
		
		// CDMA2000
		int bsid = -1;
		int nid = -1;
		int sid = -1;
		double bslat = 999;
		double bslon = 999;
		int cdmaSigStrength = -9999;
		int evdoSigStrength = -9999;

		// GSM/UMTS/W-CDMA
		String operator = "";
		int lac = -1;
		int cid = -1;
		int psc = -1;
		int rnc = -1;
		int gsmSigStrength = -9999;
		
		int phoneType = TelephonyManager.PHONE_TYPE_NONE;
		int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
		
		boolean roaming = false;

        List<otherLteCell> otherCells = null;
    }
	
	private static long FIVE_SECONDS = 5*1000;
	private LinkedList<Location> locs = new LinkedList<Location>();
	
	private double calcAverageSpeed() {
		double totspeed = 0;
		double weights = 0;
		long now = System.currentTimeMillis();
		
		if(locs.size() < 1)
			return 0.0;
		
		for(Location loc : locs) {
			if(loc.hasSpeed()) {
				long tdiff = Math.max(FIVE_SECONDS-Math.abs(loc.getTime() - now), 0);
				double weight = Math.log1p(tdiff)+1;
				
				totspeed += loc.getSpeed() * weight;
				weights += weight;
				
//				Log.d(TAG, String.format("%d %.5f", tdiff, weight));
			}
		}
		
		if(weights < 1.0)
			return 0.0;
		
//		Log.d(TAG, String.format("%.2f %.2f", totspeed, weights));
		
		return totspeed / weights;
	}

	private void updateLocations(Location loc) {
		long now = System.currentTimeMillis();
		
		Iterator<Location> it = locs.iterator();
		boolean inlist = false;
		
		while(it.hasNext()) {
			Location x = it.next();
			if(x.equals(loc)) {
				inlist = true;
			} else if(Math.abs(now - x.getTime()) > FIVE_SECONDS) {
				it.remove();
			}
		}
		
		if(!inlist)
			locs.add(loc);
	}

	private double lastValidSpeed = 0.0;

	private String lteLine;

	private String ESMRLine;
	
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	private void updatelog(boolean log) {
    	if(mLocation == null || mSignalStrength == null || mCellLocation == null)
    		return;

		Boolean gotID = false;
		
    	signalInfo signal = new signalInfo();

    	signal.latitude = mLocation.getLatitude();
    	signal.longitude = mLocation.getLongitude();
    	if(mLocation.hasSpeed()) {
    		signal.speed = mLocation.getSpeed();
    		lastValidSpeed = signal.speed;
    	} else {
    		signal.speed = lastValidSpeed;
    	}
    	signal.accuracy = mLocation.getAccuracy();
    	signal.altitude = mLocation.getAltitude();
    	signal.bearing = mLocation.getBearing();
    	signal.avgspeed = calcAverageSpeed();
    	
		signal.phoneType = mManager.getPhoneType();
		signal.networkType = mManager.getNetworkType();
		signal.roaming = mManager.isNetworkRoaming();
		signal.operator = mManager.getNetworkOperator();

		if(mCellLocation instanceof CdmaCellLocation) {
			CdmaCellLocation x = (CdmaCellLocation) mCellLocation;

			Log.d(TAG, x.toString());
			
			signal.bsid = x.getBaseStationId();
			signal.nid = x.getNetworkId();
			signal.sid = x.getSystemId();
			
			signal.bslat = x.getBaseStationLatitude()/14400.0;
			signal.bslon = x.getBaseStationLongitude()/14400.0;
		}
		
		if(mCellLocation instanceof GsmCellLocation) {
			GsmCellLocation x = (GsmCellLocation) mCellLocation;
			
			Log.d(TAG, x.toString());
			
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
		signal.evdoSigStrength = mSignalStrength.getEvdoDbm();
		
		signal.gsmSigStrength = (signal.gsmSigStrength < 32 ? -113+2*signal.gsmSigStrength : -9999);

        mCellInfo = mManager.getAllCellInfo();
    	if(mCellInfo != null) {
            signal.otherCells = new ArrayList<otherLteCell>();

    		for(CellInfo item : mCellInfo) {
    			if(item != null && item instanceof CellInfoLte) {
    				CellInfoLte x = (CellInfoLte) item;
                    if(item.isRegistered()) {
                        CellSignalStrengthLte cstr = x.getCellSignalStrength();
                        if(cstr != null)
                            signal.lteSigStrength = cstr.getDbm();

                        CellIdentityLte cellid = x.getCellIdentity();
                        if(cellid != null) {
                            signal.eci = cellid.getCi();
                            signal.pci = cellid.getPci();
                            signal.tac = cellid.getTac();
                            signal.mnc = cellid.getMnc();
                            signal.mcc = cellid.getMcc();
                            gotID = true;
                        }
                    } else {
                        otherLteCell otherCell = new otherLteCell();

                        CellSignalStrengthLte cstr = x.getCellSignalStrength();
                        if(cstr != null)
                            otherCell.lteSigStrength = cstr.getDbm();
                        CellIdentityLte cellid = x.getCellIdentity();
                        if(cellid != null) {
                            otherCell.pci = cellid.getPci();
                        }
                        signal.otherCells.add(otherCell);
                    }
    			}
    		}
    	}
    	
    	if(!validSignalStrength(signal.lteSigStrength))
    		signal.lteSigStrength = parseSignalStrength();
    	
		if(!gotID && mHTCManager != null) {
			Method m = null;
			
			try {
				String cellID;
				
				m = mHTCManager.getClass().getMethod("getSectorId", int.class);
				cellID = (String) m.invoke(mHTCManager, new Object[] {Integer.valueOf(1)} );
				signal.eci = Integer.parseInt(cellID, 16);
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

		if(signal.eci < Integer.MAX_VALUE && signal.networkType == TelephonyManager.NETWORK_TYPE_LTE)
			mBuilder.setContentText(String.format("%s: %08X", getString(R.string.serving_lte_cell_id), signal.eci));
		else
			mBuilder.setContentText(String.format("%s: %s", getString(R.string.serving_lte_cell_id), getString(R.string.none)));			

		int icon = R.drawable.ic_stat_0g;
		
		switch(signal.networkType) {
		case TelephonyManager.NETWORK_TYPE_LTE:
			icon = R.drawable.ic_stat_4g;
			break;
			
		case TelephonyManager.NETWORK_TYPE_UMTS:
		case TelephonyManager.NETWORK_TYPE_EVDO_0:
		case TelephonyManager.NETWORK_TYPE_EVDO_A:
		case TelephonyManager.NETWORK_TYPE_EVDO_B:
		case TelephonyManager.NETWORK_TYPE_EHRPD:
		case TelephonyManager.NETWORK_TYPE_HSDPA: /* 3.5G? */
		case TelephonyManager.NETWORK_TYPE_HSPA:
		case TelephonyManager.NETWORK_TYPE_HSPAP:
		case TelephonyManager.NETWORK_TYPE_HSUPA:
			icon = R.drawable.ic_stat_3g;
			break;
		
		case TelephonyManager.NETWORK_TYPE_GPRS:
		case TelephonyManager.NETWORK_TYPE_EDGE:
		case TelephonyManager.NETWORK_TYPE_1xRTT:
		case TelephonyManager.NETWORK_TYPE_CDMA:
		case TelephonyManager.NETWORK_TYPE_IDEN:
			icon = R.drawable.ic_stat_2g;
			break;
			
		default:
			icon = R.drawable.ic_stat_0g;
			break;
		}
		
		mBuilder.setSmallIcon(icon);
    	mNotifyMgr.notify(mNotificationId, mBuilder.build());

    	if(log) {
        	String slat = Location.convert(signal.latitude, Location.FORMAT_DEGREES);
        	String slon = Location.convert(signal.longitude, Location.FORMAT_DEGREES);

        	// Log.d(TAG, "Logging location.");
        	// appendLog("location.csv", slat+","+slon, "latitude,longitude");
        	
    		if(signal.networkType == TelephonyManager.NETWORK_TYPE_LTE &&
    				(validSignalStrength(signal.lteSigStrength) || validPhysicalCellID(signal.pci) || signal.eci < Integer.MAX_VALUE)) {
    			String newLteLine = slat+","+slon+","+
						(signal.eci < Integer.MAX_VALUE ? String.format("%08X", signal.eci) : "")+","+
						(validPhysicalCellID(signal.pci) ? String.valueOf(signal.pci) : "")+","+
						(validSignalStrength(signal.lteSigStrength) ? String.valueOf(signal.lteSigStrength) : "")+","+
						String.format("%.0f", signal.altitude)+","+
						(signal.tac < Integer.MAX_VALUE ? String.format("%04X", signal.tac) : "")+","+
						String.format("%.0f", signal.accuracy);
    			if(lteLine == null || !newLteLine.equals(lteLine)) {
    				Log.d(TAG, "Logging LTE cell.");
    				appendLog("ltecells.csv", newLteLine, "latitude,longitude,cellid,physcellid,dBm,altitude,tac,accuracy");
    				lteLine = newLteLine;
    			}
    		}
    		if(signal.sid >= 22404 && signal.sid <= 22451)
    		{
    			String bslatstr = (signal.bslat <= 200 ? Location.convert(signal.bslat, Location.FORMAT_DEGREES) : "");
    			String bslonstr = (signal.bslon <= 200 ? Location.convert(signal.bslon, Location.FORMAT_DEGREES) : "");

    			String newESMRLine = String.format(Locale.US, "%s,%s,%d,%d,%d,%s,%s,%s,%.0f,%.0f", slat, slon, signal.sid, signal.nid, signal.bsid,
						(validSignalStrength(signal.cdmaSigStrength) ? String.valueOf(signal.cdmaSigStrength) : ""),
						bslatstr, bslonstr, signal.altitude, signal.accuracy);
    			if(ESMRLine == null || !newESMRLine.equals(ESMRLine)) {
    				Log.d(TAG, "Logging ESMR cell.");
    				appendLog("esmrcells.csv", newESMRLine,
    						"latitude,longitude,sid,nid,bsid,rssi,bslat,bslon,altitude,accuracy");
    				ESMRLine = newESMRLine;
    			}
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
        	updateLocations(mLoc);
        	if(mLocation != mLoc) {
        		mLocation = mLoc;
        		updatelog(true);
        	}
    	}
    };
    
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
    	
    	@Override
    	public void onSignalStrengthsChanged(SignalStrength sStrength)
    	{
    		mSignalStrength = sStrength;
    		if(mSignalStrength != null) {
    			Log.d(TAG, mSignalStrength.toString());
    		}
    		updatelog(true);
    	}
    };

	@Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed
		mLocationClient.removeLocationUpdates(mLocListener);
		mLocationClient.disconnect();
        mManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
		stopForeground(true);

        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if(pintent != null) {
            AlarmManager alarm = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            alarm.cancel(pintent);
            pintent = null;
        }

        super.onUnbind(intent);
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);

        if(pintent == null) {
            Calendar cal = Calendar.getInstance();

            Intent xintent = new Intent(this, SignalDetectorService.class);
            pintent = PendingIntent.getService(this, 0, xintent, 0);

            AlarmManager alarm = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            // Start every 30 seconds
            alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), 30*1000, pintent);
        }
    }

	private Messenger pushMessenger = null;
	public void setMessenger(Messenger mMessenger) {
		pushMessenger = mMessenger;
//		Log.d(TAG, "pushMessenger set");
		if(pushMessenger != null)
			Log.d(TAG, pushMessenger.toString());

		updatelog(false);
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onConnected(Bundle arg0) {
        LocationRequest mLocationRequest = LocationRequest.create();
        // Use high accuracy
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        // Set the update interval to 1 second
        mLocationRequest.setInterval(1000);
        // Set the fastest update interval to 0.5 seconds
        mLocationRequest.setFastestInterval(500);
        mLocationRequest.setSmallestDisplacement(0);

        mLocationClient.requestLocationUpdates(mLocationRequest, mLocListener);

        Location mLoc = mLocationClient.getLastLocation();
        if(mLoc != null) {
            updateLocations(mLoc);
            mLocation = mLoc;
        }
    }

	@Override
	public void onDisconnected() {
		mLocationClient.connect(); // Try to reconnect
	}
}
