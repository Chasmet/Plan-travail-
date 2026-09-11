package com.chasmet.plantravail;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ExportTest {
  @Test
  public void completePlanAndLongNotesHaveA4PagesWithoutLostText() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    List<Street> streets =
        MapDataCache.local(context, "orsay_streets.json", StreetRepository::parse);
    List<org.osmdroid.util.GeoPoint> boundary =
        MapDataCache.local(context, "orsay_boundary.geojson", OrsayBoundaryRepository::parse);
    List<String[]> work =
        Arrays.asList(
            new String[] {
              streets.get(0).getName(),
              "2026-09-07",
              String.valueOf(DayColor.forDate("2026-09-07")),
              "test",
              "50"
            },
            new String[] {
              streets.get(1).getName(),
              "2026-09-12",
              String.valueOf(DayColor.forDate("2026-09-12")),
              "test",
              "100"
            });
    StringBuilder longNote = new StringBuilder();
    for (int i = 0; i < 240; i++)
      longNote
          .append("Ligne ")
          .append(i)
          .append(" : particularité de la rue, accès et stationnement à vérifier.\n");
    longNote.append("FIN-NOTE-CONSERVEE");
    List<String[]> notes =
        Collections.singletonList(
            new String[] {"1", "Rue — note longue", longNote.toString(), "2026-09-07"});
    PlanDrawing drawing = new PlanDrawing(streets, boundary, work, "2026-09-07");
    File dir = new File("build/verification");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    PdfTextLayout layout = HighResWeeklyExporter.textLayout("2026-09-07", work, notes);
    assertEquals(595, PdfTextLayout.WIDTH);
    assertEquals(842, PdfTextLayout.HEIGHT);
    assertTrue(layout.pages.size() > 4);
    StringBuilder content = new StringBuilder();
    for (PdfTextLayout.Page page : layout.pages)
      for (PdfTextLayout.Line line : page.lines) {
        assertTrue(line.y <= 792);
        content.append(line.text).append('\n');
      }
    assertTrue(content.toString().contains("FIN-NOTE-CONSERVEE"));
    assertTrue(content.toString().contains("50 %"));
    assertTrue(content.toString().contains("Samedi"));
    for (int i = 0; i < 240; i++)
      assertTrue("Missing line " + i, content.toString().contains("Ligne " + i + " :"));
    Bitmap bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.scale(2, 2);
    drawing.draw(canvas);
    try (OutputStream out = new FileOutputStream(new File(dir, "plan-test.png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }
}
