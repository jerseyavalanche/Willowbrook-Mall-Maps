package dev.ruthlab.afterdark;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.ScanResult;
import android.provider.MediaStore;
import android.content.ContentResolver;
import android.net.Uri;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.view.Window;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private WebView web;
    private SensorManager sensors;
    private LocationManager locations;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,JSONObject> found = new LinkedHashMap<>();
    private boolean surveying = false, gps = false, scanning = false;
    private float headingDeg = 0;
    private String pending = "";
    private String salt;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7,16,55));
        getWindow().setNavigationBarColor(Color.rgb(7,16,55));
        salt = getPreferences(MODE_PRIVATE).getString("id_salt", "");
        if(salt.isEmpty()) {
            salt = UUID.randomUUID().toString();
            getPreferences(MODE_PRIVATE).edit().putString("id_salt",salt).apply();
        }
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        locations = (LocationManager)getSystemService(LOCATION_SERVICE);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(7,17,55));
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        web.addJavascriptInterface(new Api(),"Ruth");
        web.setWebViewClient(new WebViewClient() {
           @Override public void onPageFinished(WebView v,String u) {
              send("status", obj("message","Ready · map stored offline"));
           }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private JSONObject obj(Object... args) {
        JSONObject o = new JSONObject();
        try { for(int i=0;i+1<args.length;i+=2)o.put(String.valueOf(args[i]),args[i+1]); }
        catch(JSONException ignored){}
        return o;
    }
    private void send(String type, JSONObject data) {
       JSONObject message = obj("type",type,"data",data);
       if(web!=null) runOnUiThread(()->web.evaluateJavascript(
         "window.ruthNative("+JSONObject.quote(message.toString())+")",null));
    }
    private boolean locationAllowed() {
       return Build.VERSION.SDK_INT < 23 ||
         checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean bleAllowed() {
       return Build.VERSION.SDK_INT < 31 ||
         checkSelfPermission("android.permission.BLUETOOTH_SCAN")==PackageManager.PERMISSION_GRANTED;
    }
    private void needLocation(String next) {
       pending=next;
       if(!locationAllowed()) requestPermissions(Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION},10);
       else resumePending();
    }
    private void needScan(String next) {
       pending=next;
       if(Build.VERSION.SDK_INT>=31 && !bleAllowed())
          requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN"},11);
       else resumePending();
    }
    private void resumePending() {
       String task=pending; pending="";
       if(task.equals("gps")) beginGps();
       else if(task.equals("scan")) doScan();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] p,int[] result) {
       super.onRequestPermissionsResult(requestCode,p,result);
       if(result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED) {
          if(requestCode==10 && "scan".equals(pending)) needScan("scan");
          else resumePending();
       } else {pending=""; send("status",obj("message","Permission denied · survey still works manually"));}
    }

    private void beginSurvey() {
       surveying=true;
       Sensor step=sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
       Sensor rotation=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
       if(step!=null)sensors.registerListener(this,step,SensorManager.SENSOR_DELAY_UI);
       if(rotation!=null)sensors.registerListener(this,rotation,SensorManager.SENSOR_DELAY_UI);
       send("status",obj("message",step==null ?
           "Survey running · step sensor unavailable, tap to trace" :
           "Survey running · tap your location to calibrate"));
    }
    private void stopSurvey() {
       surveying=false;
       sensors.unregisterListener(this);
       send("status",obj("message","Survey paused · data remains on phone"));
    }
    @Override public void onSensorChanged(SensorEvent e) {
       if(e.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){
          float[] rot=new float[9],ori=new float[3];
          SensorManager.getRotationMatrixFromVector(rot,e.values);
          SensorManager.getOrientation(rot,ori);
          headingDeg=(float)Math.toDegrees(ori[0]);
       } else if(e.sensor.getType()==Sensor.TYPE_STEP_DETECTOR && surveying) {
          // A 0.7m step is an estimate. The mall plan's axes are not georeferenced yet.
          send("step",obj("meters",0.7,"heading",headingDeg));
       }
    }
    @Override public void onAccuracyChanged(Sensor s,int accuracy){}
    @SuppressWarnings("MissingPermission")
    private void beginGps() {
       if(!locationAllowed())return;
       try{
          gps=true;
          if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER))
             locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,1,this);
          if(locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
             locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000,1,this);
          send("status",obj("message","GPS reading enabled · not aligned to indoor plan"));
       }catch(Exception e){send("status",obj("message","GPS unavailable: "+e.getMessage()));}
    }
    @Override public void onLocationChanged(Location l){
       send("gps",obj("lat",l.getLatitude(),"lon",l.getLongitude(),
          "accuracy",l.hasAccuracy()?l.getAccuracy():-1, "time",l.getTime()));
    }
    private String privateId(String mac) {
       try {
          byte[] dig = MessageDigest.getInstance("SHA-256").digest(
             (salt+":"+mac).getBytes(StandardCharsets.UTF_8));
          StringBuilder b = new StringBuilder();
          for(int i=0;i<7;i++)b.append(String.format(java.util.Locale.US,"%02x",dig[i]&255));
          return b.toString();
       }catch(Exception e){return "unknown";}
    }
    @SuppressWarnings("MissingPermission")
    private void doScan(){
       scanning=true;found.clear();
       // Public scan results can be cached/throttled by Android. We never claim these are fresh or exact locations.
       try{
          WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
          if(wifi!=null){
             boolean requestAccepted=wifi.startScan();
             List<ScanResult> signals=wifi.getScanResults();
             if(signals!=null)for(ScanResult s:signals){
                if(found.size()>=70)break;
                String id=privateId(s.BSSID==null?"wifi":s.BSSID);
                JSONObject n=obj("id",id,"kind","Wi-Fi","name",
                   s.SSID==null||s.SSID.isEmpty()?"Hidden Wi-Fi":s.SSID,
                   "rssi",s.level,"cached",!requestAccepted);
                found.put("wifi:"+id,n);
             }
          }
       }catch(Exception e){send("status",obj("message","Wi-Fi scan unavailable: "+e.getMessage()));}
       sendFound();
       try {
           BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
           BluetoothAdapter adapter=bm==null?null:bm.getAdapter();
           if(adapter==null||!adapter.isEnabled()){
               send("status",obj("message","Wi-Fi results ready · Bluetooth is off"));
               scanning=false; return;
           }
           scanner=adapter.getBluetoothLeScanner();
           if(scanner==null){scanning=false;return;}
           scanCallback=new ScanCallback(){
               @Override public void onScanResult(int callbackType,android.bluetooth.le.ScanResult result){
                   if(found.size()>=120)return;
                   ScanRecord record=result.getScanRecord();
                   String n=record==null?null:record.getDeviceName();
                   String mac=result.getDevice().getAddress();
                   String id=privateId(mac==null?"ble":mac);
                   found.put("ble:"+id,obj("id",id,"kind","BLE",
                    "name",n==null||n.isEmpty()?"Unnamed BLE beacon":n,
                    "rssi",result.getRssi(),"cached",false));
                   if(found.size()%4==0)sendFound();
               }
               @Override public void onScanFailed(int errorCode) {
                   send("status",obj("message","BLE scan unavailable ("+errorCode+")"));
               }
           };
           scanner.startScan(scanCallback);
           handler.postDelayed(()->{endBluetooth();sendFound();send("status",obj("message","Scan complete · tap any signal to pin a sighting"));},8500);
       }catch(Exception e) {
           send("status",obj("message","Bluetooth scan unavailable: "+e.getMessage()));
           scanning=false;
       }
    }
    private void sendFound() {
       JSONArray a=new JSONArray();
       for(JSONObject j:found.values())a.put(j);
       send("scan",obj("signals",a));
    }
    private void endBluetooth(){
       if(scanner!=null && scanCallback!=null){
           try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
       }
       scanner=null;scanCallback=null;scanning=false;
    }
    private void export(String json) {
       try {
           JSONObject parsed=new JSONObject(json);
           String name="ruthmap-willowbrook-"+System.currentTimeMillis()+".json";
           OutputStream out;
           if(Build.VERSION.SDK_INT>=29) {
              ContentValues v=new ContentValues();
              v.put(MediaStore.Downloads.DISPLAY_NAME,name);
              v.put(MediaStore.Downloads.MIME_TYPE,"application/json");
              v.put(MediaStore.Downloads.RELATIVE_PATH,"Download/");
              Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
              if(uri==null)throw new IOException("Could not create Downloads file");
              out=getContentResolver().openOutputStream(uri);
           } else {
              File f=new File(getExternalFilesDir(null),name);
              out=new FileOutputStream(f);
           }
           if(out==null)throw new IOException("Could not open output");
           try(OutputStream stream=out){stream.write(parsed.toString(2).getBytes(StandardCharsets.UTF_8));}
           send("status",obj("message",Build.VERSION.SDK_INT>=29?
              "Saved to Downloads/"+name:"Saved to app files/"+name));
       }catch(Exception e){send("status",obj("message","Save failed: "+e.getMessage()));}
    }
    public class Api {
        @JavascriptInterface public void survey(boolean on){
           runOnUiThread(()->{if(on)beginSurvey();else stopSurvey();});
        }
        @JavascriptInterface public void gps(){
           runOnUiThread(()->{if(locationAllowed())beginGps();else needLocation("gps");});
        }
        @JavascriptInterface public void scan(){
           runOnUiThread(()->{
               if(!locationAllowed())needLocation("scan");
               else if(!bleAllowed())needScan("scan");
               else doScan();
           });
        }
        @JavascriptInterface public void save(String json){runOnUiThread(()->export(json));}
    }
    @Override protected void onPause(){super.onPause();sensors.unregisterListener(this);endBluetooth();}
    @Override protected void onResume(){super.onResume();if(surveying)beginSurvey();}
    @Override protected void onDestroy(){endBluetooth();locations.removeUpdates(this);web.destroy();super.onDestroy();}
}
