package com.chasmet.plantravail;

import static org.junit.Assert.*;

import android.content.*;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 28})
public class ScreenTest {
  private Context context;
  private WorkDatabase db;
  private ActivityController<MainActivity> controller;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    context
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("mcp_server_auto", false)
        .putBoolean("auto_update", false)
        .commit();
    db =
        new WorkDatabase(context) {
          @Override
          public void notifyChanged() {
            context.sendBroadcast(
                new Intent(McpBridgeClient.ACTION_DATA_CHANGED)
                    .setPackage(context.getPackageName()));
          }
        };
    ReflectionHelpers.setStaticField(WorkDatabase.class, "instance", db);
  }

  @After
  public void cleanup() {
    if (controller != null) controller.pause().stop().destroy();
    db.close();
    ReflectionHelpers.setStaticField(WorkDatabase.class, "instance", null);
  }

  @Test
  public void markingChoicesAreVisibleAndSaveTheChosenPercentage() {
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity activity = controller.get();
    ReflectionHelpers.setField(
        activity, "streets", Collections.singletonList(ReliabilityTest.street("Rue de Paris", 0)));
    // Exercise the actual dialog; osmdroid camera animations need a real Android frame clock.
    ReflectionHelpers.callInstanceMethod(
        activity,
        "showStreet",
        ReflectionHelpers.ClassParameter.from(String.class, "Rue de Paris"));
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    assertTrue(dialog.isShowing());
    assertEquals(5, dialog.getListView().getCount());
    assertEquals(android.view.View.VISIBLE, dialog.getListView().getVisibility());
    dialog
        .getListView()
        .performItemClick(
            dialog.getListView().getChildAt(1), 1, dialog.getListView().getAdapter().getItemId(1));
    assertEquals(50, db.getCurrentWeekProgress("Rue de Paris"));
    dialog.dismiss();
    assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
  }

  @Test
  public void receivedChangesRefreshTheVisibleMainScreen() {
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity activity = controller.get();
    db.addOrUpdate("Rue de Paris", DayColor.today(), 0, "test", 100);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(
        ((TextView) activity.findViewById(R.id.tvStatus))
            .getText()
            .toString()
            .contains("1 terminées"));
  }
}
