package com.chasmet.plantravail;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public abstract class DataActivity extends AppCompatActivity {
  private boolean listening;
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          onDataChanged();
        }
      };

  protected abstract void onDataChanged();

  @Override
  protected void onResume() {
    super.onResume();
    ContextCompat.registerReceiver(
        this,
        receiver,
        new IntentFilter(McpBridgeClient.ACTION_DATA_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED);
    listening = true;
    onDataChanged();
  }

  @Override
  protected void onPause() {
    if (listening) {
      unregisterReceiver(receiver);
      listening = false;
    }
    super.onPause();
  }
}
