package com.chasmet.plantravail;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatButton;

public class HighResExportButton extends AppCompatButton {
    private boolean listenerInstalled;

    public HighResExportButton(Context context) {
        super(context);
    }

    public HighResExportButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HighResExportButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setOnClickListener(View.OnClickListener ignored) {
        if (listenerInstalled) return;
        listenerInstalled = true;
        super.setOnClickListener(v -> {
            Context c = getContext();
            if (!(c instanceof Activity)) {
                Toast.makeText(c, "Export indisponible", Toast.LENGTH_SHORT).show();
                return;
            }
            Activity activity = (Activity) c;
            OrsayMapView map = activity.findViewById(R.id.map);
            if (map == null || map.getWidth() <= 0 || map.getHeight() <= 0) {
                Toast.makeText(c, "La carte n'est pas encore prête", Toast.LENGTH_SHORT).show();
                return;
            }
            setEnabled(false);
            setText("EXPORT HD…");
            HighResWeeklyExporter.export(activity, map, new HighResWeeklyExporter.Callback() {
                @Override
                public void onProgress(String text) {
                    setText(text);
                }

                @Override
                public void onDone(String pngName, String pdfName) {
                    setEnabled(true);
                    setText("EXPORTER HD PNG + PDF");
                    Toast.makeText(c, "Export HD terminé : PNG + PDF", Toast.LENGTH_LONG).show();
                }

                @Override
                public void onError(String message) {
                    setEnabled(true);
                    setText("EXPORTER HD PNG + PDF");
                    Toast.makeText(c, "Export HD impossible : " + message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
