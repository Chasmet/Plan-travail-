package com.chasmet.plantravail;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.List;

final class TextRows extends ArrayAdapter<String> {
  TextRows(Context context, List<String> rows) {
    super(context, android.R.layout.simple_list_item_1, rows);
  }

  @Override
  public View getView(int position, View recycled, ViewGroup parent) {
    TextView view = (TextView) super.getView(position, recycled, parent);
    view.setTextColor(Color.WHITE);
    view.setTextSize(16);
    view.setPadding(12, 20, 12, 20);
    return view;
  }
}
