package com.chasmet.plantravail;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.Html;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private OrsayMapView map;
    private TextView status, legend;
    private EditText searchBox;
    private StreetRepository repository;
    private OrsayBoundaryRepository boundaryRepository;
    private WorkDatabase db;
    private List<Street> streets = new ArrayList<>();
    private final List<Polyline> lines = new ArrayList<>();
    private boolean remainingOnly = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);
        map=findViewById(R.id.map); status=findViewById(R.id.tvStatus); legend=findViewById(R.id.tvLegend); searchBox=findViewById(R.id.etStreetSearch);
        db=new WorkDatabase(this); repository=new StreetRepository(this); boundaryRepository=new OrsayBoundaryRepository(this);
        map.setMultiTouchControls(true); map.setMinZoomLevel(14.0); map.setMaxZoomLevel(20.0); map.getController().setCenter(new GeoPoint(48.6993,2.1875)); map.getController().setZoom(14.7);
        findViewById(R.id.btnSearch).setOnClickListener(v->searchStreet());
        searchBox.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEARCH){searchStreet();return true;}return false;});
        findViewById(R.id.btnRefresh).setOnClickListener(v->load(true));
        findViewById(R.id.btnExport).setOnClickListener(v->{});
        findViewById(R.id.btnLexique).setOnClickListener(v->startActivity(new Intent(this,LexiqueActivity.class)));
        findViewById(R.id.btnHistory).setOnClickListener(v->startActivity(new Intent(this,HistoryActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        findViewById(R.id.btnToday).setOnClickListener(v->startActivity(new Intent(this,TodayActivity.class)));
        findViewById(R.id.btnMyLocation).setOnClickListener(v->locate());
        Button remaining=findViewById(R.id.btnRemaining); remaining.setOnClickListener(v->{remainingOnly=!remainingOnly;remaining.setText(remainingOnly?"Tout afficher":"À faire");render();});
        findViewById(R.id.btnSync).setOnClickListener(v->sync());
        updateLegend(); loadBoundary(); load(false);
        try { Intent i=new Intent(this,McpServerService.class); i.setAction(McpServerService.ACTION_START); startService(i); } catch(Exception ignored){}
        if(getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("auto_update",true)) UpdateManager.check(this,null,null,false);
    }

    private void updateLegend(){legend.setText(Html.fromHtml("<b>Semaine</b> • <font color='#1565C0'>■ Lundi</font> <font color='#2E7D32'>■ Mardi</font> <font color='#EF6C00'>■ Mercredi</font> <font color='#6A1B9A'>■ Jeudi</font> <font color='#C62828'>■ Vendredi</font>",Html.FROM_HTML_MODE_LEGACY));}
    private void loadBoundary(){boundaryRepository.load(false,new OrsayBoundaryRepository.Callback(){public void onLoaded(List<GeoPoint> p,boolean c){runOnUiThread(()->{map.setBoundary(p);fit(p);});}public void onError(String m){}});}
    private void fit(List<GeoPoint> pts){if(pts==null||pts.size()<3)return;double n=-90,s=90,e=-180,w=180;for(GeoPoint p:pts){n=Math.max(n,p.getLatitude());s=Math.min(s,p.getLatitude());e=Math.max(e,p.getLongitude());w=Math.min(w,p.getLongitude());}BoundingBox b=new BoundingBox(n,e,s,w);map.setScrollableAreaLimitDouble(b);map.post(()->map.zoomToBoundingBox(b,false,20));}
    private void load(boolean force){status.setText("Chargement des rues d'Orsay…");repository.load(force,new StreetRepository.Callback(){public void onLoaded(List<Street> x,boolean cache){runOnUiThread(()->{streets=x;render();refreshStatus();});}public void onError(String m){runOnUiThread(()->status.setText("Erreur : "+m));}});}
    private void refreshStatus(){LinkedHashMap<String,Boolean> u=new LinkedHashMap<>();for(Street s:streets)u.put(s.getName(),true);status.setText(u.size()+" rues d'Orsay • "+db.getCurrentWeekCount()+" effectuée(s) cette semaine");}
    private void render(){for(Polyline p:lines)map.getOverlays().remove(p);lines.clear();Map<String,Integer> done=db.getCurrentWeekColors();for(Street s:streets){Integer c=done.get(s.getName());if(remainingOnly&&c!=null)continue;Polyline p=new Polyline(map);p.setPoints(s.getPoints());p.setTitle(s.getName());p.getOutlinePaint().setColor(c==null?Color.argb(80,70,70,70):Color.argb(175,Color.red(c),Color.green(c),Color.blue(c)));p.getOutlinePaint().setStrokeWidth(c==null?3f:7f);p.setOnClickListener((poly,m,e)->{showStreet(s.getName());return true;});lines.add(p);map.getOverlays().add(p);}map.invalidate();}
    private void showStreet(String name){boolean done=db.isDoneThisWeek(name);new AlertDialog.Builder(this).setTitle(name).setMessage(done?"Rue déjà effectuée cette semaine":"Marquer cette rue avec la couleur d'aujourd'hui ?").setPositiveButton(done?"Supprimer":"FAITE",(d,w)->{if(done)db.deleteCurrentWeekStreet(name);else db.addOrUpdate(name,DayColor.today(),DayColor.todayColor(),"manuel");render();refreshStatus();}).setNeutralButton("Lexique",(d,w)->startActivity(new Intent(this,LexiqueActivity.class))).setNegativeButton("Annuler",null).show();}
    private void searchStreet(){String q=norm(searchBox.getText().toString());if(q.isEmpty())return;LinkedHashMap<String,List<Street>> found=new LinkedHashMap<>();for(Street s:streets){String n=norm(s.getName());if(n.contains(q)||q.contains(n)){found.computeIfAbsent(s.getName(),k->new ArrayList<>()).add(s);}}if(found.isEmpty()){Toast.makeText(this,"Rue introuvable dans Orsay",Toast.LENGTH_SHORT).show();return;}String name=found.keySet().iterator().next();focus(found.get(name));searchBox.setText(name);status.setText("Trouvé : "+name);}
    private String norm(String v){if(v==null)return"";return Normalizer.normalize(v,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.FRANCE).replace("orsay","").replace("boulevard","").replace("avenue","").replace("chemin","").replace("allee","").replace("rue","").replace("-"," ").replace("'"," ").replaceAll("\\s+"," ").trim();}
    private void focus(List<Street> a){double n=-90,s=90,e=-180,w=180;for(Street st:a)for(GeoPoint p:st.getPoints()){n=Math.max(n,p.getLatitude());s=Math.min(s,p.getLatitude());e=Math.max(e,p.getLongitude());w=Math.min(w,p.getLongitude());}map.zoomToBoundingBox(new BoundingBox(n+.0004,e+.0004,s-.0004,w-.0004),true,80);}
    private void locate(){if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION},41);return;}LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location l=null;try{l=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);if(l==null)l=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);}catch(Exception ignored){}if(l==null){Toast.makeText(this,"Position GPS pas encore disponible",Toast.LENGTH_SHORT).show();return;}GeoPoint me=new GeoPoint(l.getLatitude(),l.getLongitude());map.getController().animateTo(me);map.getController().setZoom(18.5);Street near=nearest(me);if(near!=null){searchBox.setText(near.getName());status.setText("Rue proche : "+near.getName());showStreet(near.getName());}}
    private Street nearest(GeoPoint p){Street best=null;double bd=Double.MAX_VALUE;for(Street s:streets)for(GeoPoint x:s.getPoints()){double d=(x.getLatitude()-p.getLatitude())*(x.getLatitude()-p.getLatitude())+(x.getLongitude()-p.getLongitude())*(x.getLongitude()-p.getLongitude());if(d<bd){bd=d;best=s;}}return best;}
    private void sync(){new McpBridgeClient(this,db).sync(streets,new McpBridgeClient.Callback(){public void onDone(int c){runOnUiThread(()->{render();refreshStatus();Toast.makeText(MainActivity.this,c+" rue(s) reçue(s) du MCP",Toast.LENGTH_SHORT).show();});}public void onError(String m){runOnUiThread(()->Toast.makeText(MainActivity.this,"MCP : "+m,Toast.LENGTH_LONG).show());}});}
    @Override protected void onResume(){super.onResume();if(map!=null){map.onResume();if(db!=null&&!streets.isEmpty()){render();refreshStatus();}}}
    @Override protected void onPause(){super.onPause();if(map!=null)map.onPause();}
}