package com.chasmet.plantravail;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class HistoryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        ListView list = findViewById(R.id.listHistory);
        WorkDatabase database = new WorkDatabase(this);
        List<String> history = database.getHistory(500);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, history));
    }
}
