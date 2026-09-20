package com.chasmet.plantravail;

import android.content.Context;
import android.widget.*;
import java.util.*;

final class StreetSuggestions extends ArrayAdapter<String> {
  private final List<String> names;

  StreetSuggestions(Context context, List<String> all) {
    super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(all));
    names = new ArrayList<>(all);
  }

  private final Filter filter =
      new Filter() {
        protected FilterResults performFiltering(CharSequence text) {
          List<String> found =
              StreetResolver.candidates(text == null ? "" : text.toString(), names);
          FilterResults r = new FilterResults();
          r.values = found;
          r.count = found.size();
          return r;
        }

        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence text, FilterResults r) {
          setNotifyOnChange(false);
          clear();
          if (r.values != null) addAll((List<String>) r.values);
          notifyDataSetChanged();
        }
      };

  @Override
  public Filter getFilter() {
    return filter;
  }
}
