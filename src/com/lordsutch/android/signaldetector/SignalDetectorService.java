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
import android.content.SharedPreferences;
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
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
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
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class SignalDetectorService extends Service {
    public static final String TAG = SignalDetector.class.getSimpleName();

    public static final int MSG_SIGNAL_UPDATE = 1;

    private Builder mBuilder;
    private int mNotificationId = 1;

    private CellLocation mCellLocation;
    private SignalStrength mSignalStrength;
    private TelephonyManager mManager;
    private Object mHTCManager;
    private Location mLocation = null;
    private NotificationManager mNotifyMgr;
    private PendingIntent pintent = null;

    IBinder mBinder = new LocalBinder();      // interface for clients that bind

    private boolean loggingEnabled = false;
    private LocationManager mLocationManager;

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
        //noinspection ResourceType
        mHTCManager = getSystemService("htctelephony");

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Register the listener with the telephony manager
        mManager.listen(mListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS |
                PhoneStateListener.LISTEN_CELL_LOCATION);

        mNotifyMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (false && pintent == null) {
            Calendar cal = Calendar.getInstance();

            Intent xintent = new Intent(this, SignalDetectorService.class);
            pintent = PendingIntent.getService(this, 0, xintent, 0);

            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            // Start every 30 seconds
            alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), 30 * 1000, pintent);
        }

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this.getApplication());

        boolean lessPower = sharedPref.getBoolean("low_power", false);
        loggingEnabled = sharedPref.getBoolean("logging", true);

        Criteria mCriteria = new Criteria();
        mCriteria.setAltitudeRequired(false);
        mCriteria.setCostAllowed(false);
        mCriteria.setBearingRequired(!lessPower);
        mCriteria.setSpeedRequired(!lessPower);
        mCriteria.setAccuracy(lessPower ? Criteria.ACCURACY_COARSE : Criteria.ACCURACY_FINE);

        String provider = mLocationManager.getBestProvider(mCriteria, true);
        Log.d(TAG, "Using GPS provider " + provider);

        mLocationManager.requestLocationUpdates(provider, 1000, 0, mLocListener);
        mLocation = mLocationManager.getLastKnownLocation(provider);

        return mBinder;
    }

    private void appendLog(String logfile, String text, String header) {
        Boolean newfile = false;
        File filesdir = getExternalFilesDir(null);

        File logFile = new File(filesdir, logfile);
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
                newfile = true;
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        try {
            //BufferedWriter for performance, true to set append to file flag
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            if (newfile) {
                buf.append(header);
                buf.newLine();
            }
            buf.append(text);
            buf.newLine();
            buf.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private int parseSignalStrength() {
        String sstrength = mSignalStrength.toString();
        int strength = -999;

        String[] bits = sstrength.split("\\s+");
        if (bits.length >= 10)
            try {
                strength = Integer.parseInt(bits[9]);
            } catch (NumberFormatException e) {
            }

        return strength;
    }

    private Boolean validRSSISignalStrength(int strength) // RSSI
    {
        return (strength > -120 && strength < 0);
    }

    private Boolean validLTESignalStrength(int strength) {
        return (strength > -200 && strength < 0);
    }

    private Boolean validPhysicalCellID(int pci) {
        return (pci >= 0 && pci <= 503);
    }

    private Boolean validCellID(int eci) {
        return (eci >= 0 && eci <= 0x0FFFFFFF);
    }

    private boolean validTAC(int tac) {
        return (tac > 0x0000 && tac < 0xFFFF); // 0, FFFF are reserved values
    }

    class otherLteCell {
        int gci = Integer.MAX_VALUE;
        int pci = Integer.MAX_VALUE;
        int tac = Integer.MAX_VALUE;
        int mcc = Integer.MAX_VALUE;
        int mnc = Integer.MAX_VALUE;
        int lteBand = 0;

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
        long fixAge; // in milliseconds

        // LTE
        int gci = Integer.MAX_VALUE;
        int pci = Integer.MAX_VALUE;
        int tac = Integer.MAX_VALUE;
        int mcc = Integer.MAX_VALUE;
        int mnc = Integer.MAX_VALUE;
        int lteSigStrength = Integer.MAX_VALUE;
        int lteBand = 0;

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

    private static long FIVE_SECONDS = 5 * 1000;
    private LinkedList<Location> locs = new LinkedList<Location>();

    private double calcAverageSpeed() {
        double totspeed = 0;
        double weights = 0;
        long now = System.currentTimeMillis();

        if (locs.size() < 1)
            return 0.0;

        for (Location loc : locs) {
            if (loc.hasSpeed()) {
                long tdiff = Math.max(FIVE_SECONDS - Math.abs(loc.getTime() - now), 0);
                double weight = Math.log1p(tdiff) + 1;

                totspeed += loc.getSpeed() * weight;
                weights += weight;

//				Log.d(TAG, String.format("%d %.5f", tdiff, weight));
            }
        }

        if (weights < 1.0)
            return 0.0;

//		Log.d(TAG, String.format("%.2f %.2f", totspeed, weights));

        return totspeed / weights;
    }

    private void updateLocations(Location loc) {
        long now = System.currentTimeMillis();

        Iterator<Location> it = locs.iterator();
        boolean inlist = false;

        while (it.hasNext()) {
            Location x = it.next();
            if (x.equals(loc)) {
                inlist = true;
            } else if (Math.abs(now - x.getTime()) > FIVE_SECONDS) {
                it.remove();
            }
        }

        if (!inlist)
            locs.add(loc);
    }

    private double lastValidSpeed = 0.0;
    private String lteLine = null;
    private String CdmaLine = null;
    private String GSMLine = null;

    private int guessLteBand(int mcc, int mnc, int gci) {
        int sector = gci & 0xff;

        if(mcc == 311 && (mnc == 490 || mnc == 870))
            return 41; // Legacy Clear sites are on band 41
        else if(mcc == 310 && mnc == 120) {
            // Sprint
            int b41 = gci & 0x00100000; // 3rd digit is odd if B41
            if(b41 > 0)
                return 41;

            if((sector >= 0x19 && sector <= 0x1b) || // Ericsson/ALU
                    (sector >= 0x0f && sector <= 0x11)) // Samsung
                return 26;
            return 25;
        } else if (mcc == 310 && (mnc == 410 || mnc == 150)) {
            // AT&T
            if(sector >= 0x00 && sector <= 0x02)
                return 5;
            else if(sector >= 0x08 && sector <= 0x0a)
                return 2;
            else if(sector >= 0x16 && sector <= 0x19)
                return 4;
            return 17;
        } else if (mcc == 310 && mnc == 260) {
            // T-Mobile
            //if(sector >= 0x00 && sector <= 0x03)
            return 0;
        } else if (mcc == 311 && mnc == 480) {
            // Verizon
            if(sector == 0x0c || sector == 0x16 || sector == 0x20)
                return 4;
            else if(sector == 0x0e || sector == 0x18 || sector == 0x22)
                return 2;
            return 13;
        }
        return 0;
    }

    private int networkIcon(int networkType) {
        int icon;

        switch (networkType) {
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
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_CDMA:
                icon = R.drawable.ic_stat_3g;
                break;

            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                icon = R.drawable.ic_stat_2g;
                break;

            default:
                icon = R.drawable.ic_stat_0g;
                break;
        }

        return icon;
    }

    private long locationFixAge(Location loc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return (loc.getElapsedRealtimeNanos() - SystemClock.elapsedRealtimeNanos()) / (1000 * 1000);
        } else {
            return (loc.getTime() - (new Date()).getTime());
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void updatelog(boolean log) {
        if (mLocation == null || mSignalStrength == null || mCellLocation == null)
            return;

        Boolean gotID = false;

        signalInfo signal = new signalInfo();

        signal.latitude = mLocation.getLatitude();
        signal.longitude = mLocation.getLongitude();
        if (mLocation.hasSpeed()) {
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

        if (mCellLocation instanceof CdmaCellLocation) {
            CdmaCellLocation x = (CdmaCellLocation) mCellLocation;

            signal.bsid = x.getBaseStationId();
            signal.nid = x.getNetworkId();
            signal.sid = x.getSystemId();

            signal.bslat = x.getBaseStationLatitude() / 14400.0;
            signal.bslon = x.getBaseStationLongitude() / 14400.0;
        } else if (mCellLocation instanceof GsmCellLocation) {
            GsmCellLocation x = (GsmCellLocation) mCellLocation;

            signal.lac = x.getLac();
            signal.psc = x.getPsc();
            signal.cid = x.getCid();
            if (signal.cid >= 0) {
                signal.rnc = signal.cid >> 16;
                signal.cid = signal.cid & 0xffff;
            }
        }

        signal.cdmaSigStrength = mSignalStrength.getCdmaDbm();
        signal.gsmSigStrength = mSignalStrength.getGsmSignalStrength();
        signal.evdoSigStrength = mSignalStrength.getEvdoDbm();

        signal.gsmSigStrength = (signal.gsmSigStrength < 32 ? -113 + 2 * signal.gsmSigStrength : -9999);

        List<CellInfo> mCellInfo = mManager.getAllCellInfo();
        if (mCellInfo != null) {
            signal.otherCells = new ArrayList<otherLteCell>();

            for (CellInfo item : mCellInfo) {
                if (item != null && item instanceof CellInfoLte) {
                    CellSignalStrengthLte cstr = ((CellInfoLte) item).getCellSignalStrength();
                    CellIdentityLte cellid = ((CellInfoLte) item).getCellIdentity();

                    if (item.isRegistered()) {
                        if (cstr != null)
                            signal.lteSigStrength = cstr.getDbm();

                        if (cellid != null) {
                            signal.gci = cellid.getCi();
                            signal.pci = cellid.getPci();
                            signal.tac = cellid.getTac();
                            signal.mnc = cellid.getMnc();
                            signal.mcc = cellid.getMcc();
                            signal.lteBand = guessLteBand(signal.mcc, signal.mnc, signal.gci);
                            gotID = true;
                        }
                    } else {
                        otherLteCell otherCell = new otherLteCell();

                        if (cstr != null)
                            otherCell.lteSigStrength = cstr.getDbm();
                        if (cellid != null) {
                            otherCell.gci = cellid.getCi();
                            otherCell.pci = cellid.getPci();
                            otherCell.tac = cellid.getTac();
                            otherCell.mnc = cellid.getMnc();
                            otherCell.mcc = cellid.getMcc();
                            otherCell.lteBand = guessLteBand(otherCell.mcc, otherCell.mnc, otherCell.gci);
                        }
                        signal.otherCells.add(otherCell);
                    }
                }
            }
        }

        if (!validLTESignalStrength(signal.lteSigStrength))
            signal.lteSigStrength = parseSignalStrength();

        if (!gotID && mHTCManager != null) {
            Method m = null;

            try {
                String cellID;

                m = mHTCManager.getClass().getMethod("getSectorId", int.class);
                cellID = (String) m.invoke(mHTCManager, new Object[]{Integer.valueOf(1)});
                signal.gci = Integer.parseInt(cellID, 16);
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

        if (!validLTESignalStrength(signal.lteSigStrength)) {
            Method m;

            try {
                m = mSignalStrength.getClass().getMethod("getLteRsrp");
                signal.lteSigStrength = (Integer) m.invoke(mSignalStrength, (Object[]) null);
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

        String cellIdInfo = getString(R.string.none);
        if (signal.networkType == TelephonyManager.NETWORK_TYPE_LTE &&
                ((signal.gci != Integer.MAX_VALUE || signal.pci != Integer.MAX_VALUE))) {
            ArrayList<String> cellIds = new ArrayList<String>();

            if (validTAC(signal.tac))
                cellIds.add(String.format("TAC\u00a0%04X", signal.tac));

            if (validCellID(signal.gci))
                cellIds.add(String.format("GCI\u00a0%08X", signal.gci));

            if (validPhysicalCellID(signal.pci))
                cellIds.add(String.format("PCI\u00a0%03d", signal.pci));

            if (cellIds.isEmpty())
                cellIdInfo = getString(R.string.missing);
            else
                cellIdInfo = TextUtils.join(", ", cellIds);
        }
        mBuilder.setContentText(getString(R.string.serving_lte_cell_id) + ": " + cellIdInfo)
                .setSmallIcon(networkIcon(signal.networkType));

        mNotifyMgr.notify(mNotificationId, mBuilder.build());

        signal.fixAge = locationFixAge(mLocation);

        long THIRTY_SECONDS = (long) (30 * 1000);
        if (loggingEnabled && log && (signal.fixAge < THIRTY_SECONDS)) {
            String slat = Location.convert(signal.latitude, Location.FORMAT_DEGREES);
            String slon = Location.convert(signal.longitude, Location.FORMAT_DEGREES);

            if (signal.networkType == TelephonyManager.NETWORK_TYPE_LTE &&
                    (validLTESignalStrength(signal.lteSigStrength) ||
                            validPhysicalCellID(signal.pci) || validCellID(signal.gci))) {
                String newLteLine = slat + "," + slon + "," +
                        (validCellID(signal.gci) ? String.format("%08X", signal.gci) : "") + "," +
                        (validPhysicalCellID(signal.pci) ? String.valueOf(signal.pci) : "") + "," +
                        (validLTESignalStrength(signal.lteSigStrength) ? String.valueOf(signal.lteSigStrength) : "") + "," +
                        String.format("%.0f", signal.altitude) + "," +
                        (validTAC(signal.tac) ? String.format("%04X", signal.tac) : "") + "," +
                        String.format("%.0f", signal.accuracy) + "," +
                        (validCellID(signal.gci) ? String.format("%06X", signal.gci /256) : "") + "," +
                        (signal.lteBand > 0 ? String.valueOf(signal.lteBand) : "");
                if (lteLine == null || !newLteLine.equals(lteLine)) {
                    Log.d(TAG, "Logging LTE cell.");
                    appendLog("ltecells.csv", newLteLine, "latitude,longitude,cellid,physcellid,dBm,altitude,tac,accuracy,baseGci,band");
                    lteLine = newLteLine;
                }
            }

            if (mCellInfo != null) {
                for (CellInfo item : mCellInfo) {
                    if (item instanceof CellInfoLte) {
                        CellIdentityLte mIdentity = ((CellInfoLte) item).getCellIdentity();
                        CellSignalStrengthLte mSS = ((CellInfoLte) item).getCellSignalStrength();

                        int tac = mIdentity.getTac();
                        int eci = mIdentity.getCi();
                        int pci = mIdentity.getPci();
                        int mcc = mIdentity.getMcc();
                        int mnc = mIdentity.getMnc();
                        int rsrp = mSS.getDbm();

                        String cellLine = slat + "," + slon + "," +
                                String.format("%.0f", signal.accuracy) + "," +
                                String.format("%.0f", signal.altitude) + "," +
                                (mcc != Integer.MAX_VALUE ? String.valueOf(mcc) : "") + "," +
                                (mnc != Integer.MAX_VALUE ? String.valueOf(mnc) : "") + "," +
                                (validTAC(tac) ? String.format("%04X", tac) : "") + "," +
                                (validCellID(eci) ? String.format("%08X", eci) : "") + "," +
                                (validPhysicalCellID(pci) ? String.valueOf(pci) : "") + "," +
                                (validLTESignalStrength(rsrp) ? String.valueOf(rsrp) : "") + "," +
                                (item.isRegistered() ? "1" : "0") + "," +
                                (validCellID(signal.gci) ? String.format("%06X", signal.gci /256) : "") + "," +
                                (signal.lteBand > 0 ? String.valueOf(signal.lteBand) : "");

                        appendLog("cellinfolte.csv", cellLine,
                                "latitude,longitude,accuracy,altitude,mcc,mnc,tac,gci,pci,rsrp,registered,baseGci,band");
                    }
                }
            }

            if (validRSSISignalStrength(signal.cdmaSigStrength) && validSID(signal.sid)) {
                String bslatstr = (signal.bslat <= 200 ? Location.convert(signal.bslat, Location.FORMAT_DEGREES) : "");
                String bslonstr = (signal.bslon <= 200 ? Location.convert(signal.bslon, Location.FORMAT_DEGREES) : "");

                String newCdmaLine = String.format(Locale.US, "%s,%s,%d,%d,%d,%d,%s,%s,%.0f,%.0f",
                        slat, slon, signal.sid, signal.nid, signal.bsid, signal.cdmaSigStrength,
                        bslatstr, bslonstr, signal.altitude, signal.accuracy);
                if (CdmaLine == null || !newCdmaLine.equals(CdmaLine)) {
                    Log.d(TAG, "Logging CDMA cell.");
                    appendLog(((signal.sid >= 22404) && (signal.sid <= 22451)) ? "esmrcells.csv" : "cdmacells.csv",
                            newCdmaLine, "latitude,longitude,sid,nid,bsid,rssi,bslat,bslon,altitude,accuracy");
                    CdmaLine = newCdmaLine;
                }
            } else if (validRSSISignalStrength(signal.gsmSigStrength) && validCID(signal.cid)) {
                String newGSMLine = String.format(Locale.US, "%s,%s,%.0f,%.0f,%d,%d,%d,%d,%s", slat, slon,
                        signal.altitude, signal.accuracy,
                        signal.cid, signal.rnc, signal.lac, signal.psc, signal.gsmSigStrength);
                if (GSMLine == null || !newGSMLine.equals(GSMLine)) {
                    Log.d(TAG, "Logging GSM cell.");
                    appendLog("gsmcells.csv", newGSMLine, "latitude,longitude,altitude,accuracy,cid,rnc,lac,psc,rssi");
                    GSMLine = newGSMLine;
                }
            }
        }

        if (pushMessenger != null) {
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

    private boolean validSID(int sid) { // CDMA System Identifier
        return sid >= 0 && sid <= 0x7fff;
    }

    private boolean validCID(int cid) { // GSM cell ID
        return cid >= 0 && cid <= 0xffff;
    }

    private final LocationListener mLocListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location mLoc) {
            updateLocations(mLoc);
            if (mLocation != mLoc) {
                mLocation = mLoc;
                updatelog(true);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }

        @Override
        public void onProviderDisabled(String provider) {

        }
    };

    // Listener for signal strength.
    final PhoneStateListener mListener = new PhoneStateListener() {
        @Override
        public void onCellLocationChanged(CellLocation mLocation) {
            mCellLocation = mLocation;
//    		if(mCellLocation != null) {
//    			Log.d(TAG, mCellLocation.toString());
//    		}
            updatelog(true);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength sStrength) {
            mSignalStrength = sStrength;
            if (mSignalStrength != null) {
                Log.d(TAG, mSignalStrength.toString());
            }
            updatelog(true);
        }
    };

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed
        mLocationManager.removeUpdates(mLocListener);
        mManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
        stopForeground(true);

        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (pintent != null) {
            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            alarm.cancel(pintent);
            pintent = null;
        }

        super.onUnbind(intent);
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);

        if (false && pintent == null) {
            Calendar cal = Calendar.getInstance();

            Intent xintent = new Intent(this, SignalDetectorService.class);
            pintent = PendingIntent.getService(this, 0, xintent, 0);

            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            // Start every 30 seconds
            alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), 30 * 1000, pintent);
        }
    }

    private Messenger pushMessenger = null;

    public void setMessenger(Messenger mMessenger) {
        pushMessenger = mMessenger;
//		Log.d(TAG, "pushMessenger set");
        if (pushMessenger != null)
            Log.d(TAG, pushMessenger.toString());

        updatelog(false);
    }
}
