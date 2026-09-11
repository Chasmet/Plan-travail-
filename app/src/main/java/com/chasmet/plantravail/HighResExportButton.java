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
    super.setOnClickListener(
        v -> {
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
          HighResWeeklyExporter.export(
              activity,
              map,
              new HighResWeeklyExporter.Callback() {
                @Override
                public void onProgress(String text) {
                  setText(text);
                }

                @Override
                public void onDone(String pngName, String pdfName) {
                  setEnabled(true);
                  setText("EXPORTER HD PNG + PDF");
                  if (activity.isFinishing() || activity.isDestroyed()) return;
                  new androidx.appcompat.app.AlertDialog.Builder(activity)
                      .setTitle("Export terminé")
                      .setMessage(
                          "Plan complet de la semaine affichée, avec le détail des pourcentages et"
                              + " le lexique dans le PDF.")
                      .setPositiveButton(
                          "Ouvrir le PDF",
                          (d, w) -> {
                            try {
                              activity.startActivity(
                                  new android.content.Intent(android.content.Intent.ACTION_VIEW)
                                      .setDataAndType(
                                          android.net.Uri.parse(pdfName), "application/pdf")
                                      .addFlags(
                                          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            } catch (android.content.ActivityNotFoundException e) {
                              Toast.makeText(
                                      activity,
                                      "Aucun lecteur PDF installé. Utilisez Partager pour"
                                          + " enregistrer le fichier.",
                                      Toast.LENGTH_LONG)
                                  .show();
                            }
                          })
                      .setNeutralButton(
                          "Partager PNG + PDF",
                          (d, w) -> {
                            java.util.ArrayList<android.net.Uri> files =
                                new java.util.ArrayList<>();
                            files.add(android.net.Uri.parse(pngName));
                            files.add(android.net.Uri.parse(pdfName));
                            android.content.Intent share =
                                new android.content.Intent(
                                        android.content.Intent.ACTION_SEND_MULTIPLE)
                                    .setType("*/*")
                                    .putParcelableArrayListExtra(
                                        android.content.Intent.EXTRA_STREAM, files)
                                    .addFlags(
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            android.content.ClipData clip =
                                android.content.ClipData.newUri(
                                    activity.getContentResolver(), "Plan Travail", files.get(0));
                            clip.addItem(new android.content.ClipData.Item(files.get(1)));
                            share.setClipData(clip);
                            activity.startActivity(
                                android.content.Intent.createChooser(
                                    share, "Enregistrer ou partager le plan"));
                          })
                      .setNegativeButton("Fermer", null)
                      .show();
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
