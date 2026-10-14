package org.ruthroad.alpha;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.maplibre.android.style.layers.PropertyFactory.*;

public class MainActivity extends Activity implements LocationListener {
    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/dark";
    private static final String DEFAULT_ROUTER = "https://router.project-osrm.org";
    private static final String PREF = "roadnav_local";
    private static final String SOURCE_ROUTE = "route-src", SOURCE_START = "start-src";
    private static final String SOURCE_DEST = "destination-src", SOURCE_HAZARD = "hazard-src";
    private MapView mapView;
    private MapLibreMap map;
    private TextView banner, details, stepText, header;
    private LocationManager locationManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Point> line = new ArrayList<>();
    private final List<Instruction> steps = new ArrayList<>();
    private final List<Report> reports = new ArrayList<>();
    private LatLng current, manualStart, destination;
    private boolean ready = false, followGps = true, placingStart = false;
    private boolean routing = false, voiceEnabled = false, gpsPermission = false;
    private boolean routeActive = false;
    private long lastReroute = 0;
    private double routeDistanceMeters = 0, routeDurationSeconds = 0;
    private int nextStep = 0;
    private TextToSpeech tts;
    private SharedPreferences prefs;

    static class Instruction {
        LatLng coordinate;
        String text;
        double distance;
        Instruction(LatLng c, String t, double d) { coordinate=c; text=t; distance=d; }
    }
    static class Report {
        LatLng coordinate;
        String kind;
        long time;
        Report(LatLng c, String k, long t) { coordinate=c; kind=k; time=t; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8,15,42));
        getWindow().setNavigationBarColor(Color.rgb(8,15,42));
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        restoreReports();
        MapLibre.getInstance(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8,16,43));
        setContentView(root);
        mapView = new MapView(this);
        mapView.onCreate(state);
        root.addView(mapView,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14),dp(11),dp(14),dp(9));
        top.setBackground(background(0xed071432,20));
        header = label("ROADNAV  /  ALPHA 0.1",13,0xff27f0f4,true);
        top.addView(header);
        banner = label("Real roads, real GPS. Long-press map for destination.",14,Color.WHITE,false);
        banner.setPadding(0,dp(8),0,0);
        top.addView(banner);
        FrameLayout.LayoutParams t = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        t.setMargins(dp(10),dp(9),dp(10),0); root.addView(top,t);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(12),dp(12),dp(12),dp(13));
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(background(0xf1091542,18));
        details = label("Waiting for GPS • map requires internet",12,0xffb5d1e6,false);
        bottom.addView(details);
        stepText = label("Not navigating • traffic data unavailable",16,Color.WHITE,true);
        stepText.setPadding(0,dp(8),0,dp(10));bottom.addView(stepText);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button start=button("START",0xff176be4);
        Button route=button("ROUTE",0xff09a1a9);
        Button report=button("REPORT",0xffc73090);
        Button options=button("MORE",0xff343f72);
        controls.addView(start,weight());
        controls.addView(route,weight());
        controls.addView(report,weight());
        controls.addView(options,weight());
        bottom.addView(controls);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button locate=button("GPS ◎",0xff20376e);
        Button voice=button("VOICE OFF",0xff20376e);
        Button stop=button("CLEAR",0xff20376e);
        tools.addView(locate,weight());tools.addView(voice,weight());tools.addView(stop,weight());
        bottom.addView(tools);
        FrameLayout.LayoutParams b=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        b.setMargins(dp(10),0,dp(10),dp(13));root.addView(bottom,b);

        start.setOnClickListener(v->{
            placingStart=true;followGps=false;
            banner.setText("Tap the map for a starting point; GPS still available");
            message("Tap once to set a manual starting point");
        });
        route.setOnClickListener(v->requestRoute(false));
        report.setOnClickListener(v->reportDialog());
        options.setOnClickListener(v->optionsDialog());
        locate.setOnClickListener(v->{
            followGps=true;manualStart=null;refreshMarkers();
            if(current!=null)moveTo(current,15);
            else requestLocation();
        });
        voice.setOnClickListener(v->{
            voiceEnabled=!voiceEnabled;
            voice.setText(voiceEnabled?"VOICE ON":"VOICE OFF");
            if(voiceEnabled) speak("Voice guidance enabled");
        });
        stop.setOnClickListener(v->{
            routeActive=false;line.clear();steps.clear();destination=null;nextStep=0;
            routeDistanceMeters=routeDurationSeconds=0;refreshMarkers();refreshRoute();
            banner.setText("Route cleared. Long-press map to pick a destination.");
            stepText.setText("Not navigating • traffic data unavailable");
        });

        tts = new TextToSpeech(this,status->{
            if(status==TextToSpeech.SUCCESS&&tts!=null) tts.setLanguage(Locale.US);
        });
        mapView.getMapAsync(m->{
            map=m;
            map.setStyle(STYLE_URL,style->{
                ready=true;
                installLayers(style);
                LatLng initial=new LatLng(41.2230,-74.2880);
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(initial,12));
                refreshMarkers(); refreshRoute();
                map.addOnMapLongClickListener(point->{
                    destination=point;
                    banner.setText("Destination selected • tap ROUTE");
                    refreshMarkers();
                    if(startPoint()!=null)requestRoute(false);
                    return true;
                });
                map.addOnMapClickListener(point->{
                    if(placingStart){
                        manualStart=point;placingStart=false;followGps=false;
                        banner.setText("Starting point selected • long-press destination");
                        refreshMarkers();
                        return true;
                    }
                    return false;
                });
            });
        });
        requestLocation();
    }

    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    private TextView label(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(value);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private GradientDrawable background(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private Button button(String name,int color){
        Button b=new Button(this);
        b.setText(name);b.setTextColor(Color.WHITE);b.setTextSize(11);b.setAllCaps(false);
        b.setPadding(dp(2),0,dp(2),0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setBackground(background(color,12));return b;
    }
    private LinearLayout.LayoutParams weight(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(45),1);
        p.setMargins(dp(3),dp(4),dp(3),dp(4));return p;
    }
    private void message(String m){Toast.makeText(this,m,Toast.LENGTH_LONG).show();}
    private void moveTo(LatLng l,int zoom){
        if(ready)map.animateCamera(CameraUpdateFactory.newLatLngZoom(l,zoom),650);
    }
    private LatLng startPoint(){return manualStart!=null?manualStart:current;}

    private boolean hasLocation(){
        return Build.VERSION.SDK_INT<23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestLocation(){
        if(!hasLocation()){
            if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},30);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},30);
            return;
        }
        gpsPermission=true;
        try{
            if(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500,3,this);
            if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000,8,this);
            Location last=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if(last!=null && System.currentTimeMillis()-last.getTime()<120000L)onLocationChanged(last);
        }catch(SecurityException e){banner.setText("Location permission unavailable");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==30&&hasLocation())requestLocation();
        else if(request==30)message("GPS permission denied. Use START and tap the map to simulate start point.");
    }
    @Override public void onLocationChanged(Location fix){
        if(fix.getAccuracy()>250||fix.getTime()<System.currentTimeMillis()-120000) return;
        current=new LatLng(fix.getLatitude(),fix.getLongitude());
        if(manualStart==null&&followGps && ready){
            refreshMarkers();
            if(map.getCameraPosition().zoom<13)moveTo(current,15);
            else map.animateCamera(CameraUpdateFactory.newLatLng(current),300);
        }else refreshMarkers();
        String accuracy=String.format(Locale.US,"GPS ±%.0f m",fix.getAccuracy());
        details.setText(accuracy+"  •  "+String.format(Locale.US,"%.5f, %.5f",current.getLatitude(),current.getLongitude()));
        if(routeActive)advanceSteps(fix);
    }
    private FeatureCollection empty(){return FeatureCollection.fromJson("{\"type\":\"FeatureCollection\",\"features\":[]}");}
    private void installLayers(Style s){
        s.addSource(new GeoJsonSource(SOURCE_ROUTE,empty()));
        s.addSource(new GeoJsonSource(SOURCE_START,empty()));
        s.addSource(new GeoJsonSource(SOURCE_DEST,empty()));
        s.addSource(new GeoJsonSource(SOURCE_HAZARD,empty()));
        s.addLayer(new LineLayer("route-outline",SOURCE_ROUTE).withProperties(lineColor(Color.rgb(11,13,51)),lineWidth(12f),lineOpacity(.95f)));
        s.addLayer(new LineLayer("route-line",SOURCE_ROUTE).withProperties(lineColor(Color.rgb(18,243,240)),lineWidth(6f),lineOpacity(.92f)));
        s.addLayer(new CircleLayer("hazards",SOURCE_HAZARD).withProperties(circleColor(Color.rgb(255,177,48)),circleRadius(8f),circleStrokeColor(Color.WHITE),circleStrokeWidth(2f)));
        s.addLayer(new CircleLayer("dest",SOURCE_DEST).withProperties(circleColor(Color.rgb(243,29,181)),circleRadius(9f),circleStrokeColor(Color.WHITE),circleStrokeWidth(2f)));
        s.addLayer(new CircleLayer("gps",SOURCE_START).withProperties(circleColor(Color.rgb(19,236,255)),circleRadius(9f),circleStrokeColor(Color.WHITE),circleStrokeWidth(3f)));
    }
    private void setPointSource(String id,LatLng location){
        if(!ready)return;
        GeoJsonSource s=(GeoJsonSource)map.getStyle().getSource(id);
        if(s!=null){
            if(location==null)s.setGeoJson(empty());
            else s.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{Feature.fromGeometry(Point.fromLngLat(location.getLongitude(),location.getLatitude()))}));
        }
    }
    private void refreshMarkers(){
        if(!ready||map.getStyle()==null)return;
        setPointSource(SOURCE_START,startPoint());
        setPointSource(SOURCE_DEST,destination);
        ArrayList<Feature> a=new ArrayList<>();
        for(Report r:reports)a.add(Feature.fromGeometry(Point.fromLngLat(r.coordinate.getLongitude(),r.coordinate.getLatitude())));
        GeoJsonSource src=(GeoJsonSource)map.getStyle().getSource(SOURCE_HAZARD);
        if(src!=null)src.setGeoJson(FeatureCollection.fromFeatures(a));
    }
    private void refreshRoute(){
        if(!ready||map.getStyle()==null)return;
        GeoJsonSource src=(GeoJsonSource)map.getStyle().getSource(SOURCE_ROUTE);
        if(src!=null){
            if(line.size()<2)src.setGeoJson(empty());
            else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(line)));
        }
    }
    private void requestRoute(boolean reroute){
        final LatLng start=startPoint();
        final LatLng goal=destination;
        if(start==null){message("No GPS fix yet. Tap START then select map position.");return;}
        if(goal==null){message("Long-press the street map to choose destination.");return;}
        if(routing)return;
        routing=true;
        banner.setText(reroute?"Recalculating route…":"Calculating road route…");
        final String host=prefs.getString("router",DEFAULT_ROUTER).replaceAll("/+$","");
        if(!host.startsWith("https://") || host.contains("?") || host.contains("#")){
            routing=false;message("Routing URL must be HTTPS");return;
        }
        String uri=host+"/route/v1/driving/"+
            String.format(Locale.US,"%.6f,%.6f;%.6f,%.6f",start.getLongitude(),start.getLatitude(),goal.getLongitude(),goal.getLatitude())+
            "?steps=true&overview=full&geometries=geojson&alternatives=false";
        io.execute(()->{
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(uri).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(12000); conn.setReadTimeout(17000);
                conn.setRequestProperty("Accept","application/json");
                conn.setRequestProperty("User-Agent","RoadNavAlpha/0.1 (personal navigation test)");
                int http=conn.getResponseCode();
                if(http!=200)throw new Exception("Routing server returned HTTP "+http);
                StringBuilder body=new StringBuilder();
                try(InputStream in=conn.getInputStream();BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                    String text;while((text=br.readLine())!=null){
                        body.append(text);
                        if(body.length()>3000000)throw new Exception("Route response too large");
                    }
                }
                JSONObject json=new JSONObject(body.toString());
                if(!"Ok".equalsIgnoreCase(json.optString("code")))
                    throw new Exception("No drivable route: "+json.optString("code"));
                JSONArray routes=json.getJSONArray("routes");
                JSONObject route=routes.getJSONObject(0);
                JSONArray coordinates=route.getJSONObject("geometry").getJSONArray("coordinates");
                ArrayList<Point> geo=new ArrayList<>();
                for(int i=0;i<coordinates.length();i++){
                    JSONArray c=coordinates.getJSONArray(i);
                    geo.add(Point.fromLngLat(c.getDouble(0),c.getDouble(1)));
                }
                ArrayList<Instruction> maneuvers=new ArrayList<>();
                JSONArray legs=route.optJSONArray("legs");
                if(legs!=null)for(int k=0;k<legs.length();k++){
                    JSONArray ss=legs.getJSONObject(k).optJSONArray("steps");
                    if(ss==null)continue;
                    for(int i=0;i<ss.length();i++){
                        JSONObject st=ss.getJSONObject(i),man=st.getJSONObject("maneuver");
                        JSONArray c=man.getJSONArray("location");
                        String type=man.optString("type","continue");
                        String mod=man.optString("modifier","");
                        String street=st.optString("name","");
                        String say=instruction(type,mod,street);
                        maneuvers.add(new Instruction(new LatLng(c.getDouble(1),c.getDouble(0)),say,st.optDouble("distance",0)));
                    }
                }
                final double meters=route.getDouble("distance"), seconds=route.getDouble("duration");
                main.post(()->{
                    routing=false;
                    line.clear();line.addAll(geo);
                    steps.clear();steps.addAll(maneuvers);nextStep=0;routeActive=true;
                    routeDistanceMeters=meters;routeDurationSeconds=seconds;
                    refreshRoute();refreshMarkers();
                    int minutes=(int)Math.max(1,Math.ceil(seconds/60));
                    banner.setText(String.format(Locale.US,"ROUTE · %.1f mi · %d min · no live traffic",meters/1609.344,minutes));
                    stepText.setText(steps.size()>1?steps.get(1).text:"Route ready · follow highlighted road");
                    if(!reroute){
                        LatLng middle=geo.size()>2?new LatLng(geo.get(geo.size()/2).latitude(),geo.get(geo.size()/2).longitude()):start;
                        moveTo(middle,11);
                    }
                });
            }catch(Exception e){
                String err=e.getMessage();
                main.post(()->{
                    routing=false;
                    banner.setText("Routing unavailable · "+(err==null?"network error":err));
                    message("Routing server unavailable. Check internet or change endpoint in MORE.");
                });
            }finally{if(conn!=null)conn.disconnect();}
        });
    }
    private static String instruction(String type,String modifier,String street){
        String road=street==null||street.isEmpty()?"the next road":street;
        switch(type){
            case "depart":return "Begin driving on "+road;
            case "arrive":return "Arrive at destination";
            case "turn":return "Turn "+(modifier.isEmpty()?"":modifier+" ")+"onto "+road;
            case "merge":return "Merge "+modifier+" onto "+road;
            case "ramp":return "Take the "+modifier+" ramp toward "+road;
            case "fork":return "Keep "+modifier+" toward "+road;
            case "roundabout":return "At the roundabout, take the exit to "+road;
            case "end of road":return "At end of road, turn "+modifier+" onto "+road;
            case "continue":return "Continue on "+road;
            default:return type.replace('-',' ')+" "+modifier+" "+road;
        }
    }
    private void advanceSteps(Location location){
        if(steps.isEmpty())return;
        int candidate=Math.min(nextStep+1,steps.size()-1);
        Instruction next=steps.get(candidate);
        float[] dist=new float[1];
        Location.distanceBetween(location.getLatitude(),location.getLongitude(),
                next.coordinate.getLatitude(),next.coordinate.getLongitude(),dist);
        if(dist[0]<150){
            String instr=next.text;
            stepText.setText(instr);
            if(voiceEnabled)speak(instr);
            nextStep=candidate;
            if(candidate==steps.size()-1 && dist[0]<30){
                routeActive=false;
                banner.setText("Destination reached");stepText.setText("You have arrived");
            }
        }else if(dist[0]>500 && nextStep>0 && System.currentTimeMillis()-lastReroute>90000){
            // Conservative high threshold, cannot distinguish all GPS jumps from actual deviations.
            if(location.hasAccuracy()&&location.getAccuracy()<30){
                lastReroute=System.currentTimeMillis();
                requestRoute(true);
            }
        }
    }
    private void speak(String text){
        if(tts!=null)tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"navigation");
    }
    private void reportDialog(){
        LatLng where=current;
        if(where==null){message("GPS fix required to record an observed road event");return;}
        String[] choices={"Crash","Hazard","Road closed","Roadwork","Stopped vehicle"};
        new AlertDialog.Builder(this).setTitle("Save a local report")
            .setMessage("This does NOT publish a live report to other drivers.")
            .setItems(choices,(d,index)->{
                reports.add(new Report(where,choices[index],System.currentTimeMillis()));
                persistReports();refreshMarkers();message("Saved locally: "+choices[index]);
            }).setNegativeButton("Cancel",null).show();
    }
    private void restoreReports(){
        try{
            JSONArray a=new JSONArray(prefs.getString("reports","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject j=a.getJSONObject(i);
                reports.add(new Report(new LatLng(j.getDouble("lat"),j.getDouble("lon")),j.getString("kind"),j.getLong("time")));
            }
        }catch(Exception ignored){}
    }
    private void persistReports(){
        JSONArray a=new JSONArray();
        for(Report r:reports){
            JSONObject j=new JSONObject();
            try{j.put("lat",r.coordinate.getLatitude());j.put("lon",r.coordinate.getLongitude());j.put("kind",r.kind);j.put("time",r.time);a.put(j);}catch(Exception ignored){}
        }
        prefs.edit().putString("reports",a.toString()).apply();
    }
    private void optionsDialog(){
        String[] options={"Routing server (HTTPS)","Clear local reports","About and limitations"};
        new AlertDialog.Builder(this).setTitle("RoadNav settings").setItems(options,(dialog,n)->{
            if(n==0){
                EditText field=new EditText(this);
                field.setSingleLine(true);
                field.setText(prefs.getString("router",DEFAULT_ROUTER));
                new AlertDialog.Builder(this).setTitle("OSRM-compatible base URL")
                    .setMessage("Demo server is for low-volume testing only. Your GPS coordinates go to the selected routing server.")
                    .setView(field).setPositiveButton("Save",(d,w)->{
                        String url=field.getText().toString().trim().replaceAll("/+$","");
                        if(!url.matches("https://[A-Za-z0-9.-]+(?::[0-9]{2,5})?(?:/[-A-Za-z0-9_./]*)?")){
                            message("HTTPS URL with host required");return;
                        }
                        prefs.edit().putString("router",url).apply();message("Routing URL saved");
                    }).setNegativeButton("Cancel",null).show();
            }else if(n==1){
                new AlertDialog.Builder(this).setTitle("Erase local reports?")
                    .setPositiveButton("Erase",(d,w)->{reports.clear();persistReports();refreshMarkers();})
                    .setNegativeButton("Cancel",null).show();
            }else new AlertDialog.Builder(this).setTitle("RoadNav Alpha 0.1")
                .setMessage("Experimental standalone route viewer. MapLibre + OpenStreetMap; routing via chosen OSRM endpoint. No live traffic, public reports, map search, offline routing, or validated driving guidance yet. Never operate the screen while driving.")
                .setPositiveButton("OK",null).show();
        }).show();
    }
    @Override public void onStart(){super.onStart();if(mapView!=null)mapView.onStart();}
    @Override public void onResume(){super.onResume();if(mapView!=null)mapView.onResume();if(hasLocation())requestLocation();}
    @Override public void onPause(){if(mapView!=null)mapView.onPause();super.onPause();}
    @Override public void onStop(){if(mapView!=null)mapView.onStop();super.onStop();}
    @Override public void onLowMemory(){super.onLowMemory();if(mapView!=null)mapView.onLowMemory();}
    @Override protected void onSaveInstanceState(Bundle bundle){super.onSaveInstanceState(bundle);if(mapView!=null)mapView.onSaveInstanceState(bundle);}
    @Override public void onDestroy(){
        if(locationManager!=null)locationManager.removeUpdates(this);
        io.shutdownNow();if(tts!=null){tts.stop();tts.shutdown();}
        if(mapView!=null)mapView.onDestroy();super.onDestroy();
    }
}
