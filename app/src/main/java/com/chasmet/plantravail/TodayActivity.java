package com.chasmet.plantravail;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class TodayActivity extends AppCompatActivity {
    private WorkDatabase db;
    private TextView summary;
    private ListView list;
    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_today);db=new WorkDatabase(this);summary=findViewById(R.id.tvTodaySummary);list=findViewById(R.id.listToday);Button undo=findViewById(R.id.btnUndoToday);undo.setOnClickListener(v->{String last=db.getLastTodayStreet();if(last==null){Toast.makeText(this,"Aucune rue à annuler aujourd'hui",Toast.LENGTH_SHORT).show();return;}db.undoLastToday();Toast.makeText(this,last+" supprimée",Toast.LENGTH_SHORT).show();refresh();});refresh();}
    @Override protected void onResume(){super.onResume();if(db!=null)refresh();}
    private void refresh(){List<String> streets=db.getTodayStreets();String last=db.getLastTodayStreet();summary.setText(db.getTodayCount()+" rue(s) effectuée(s) aujourd'hui"+(last==null?"":"\nDernière : "+last));list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,streets));}
}