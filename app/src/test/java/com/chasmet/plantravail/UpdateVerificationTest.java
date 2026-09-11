package com.chasmet.plantravail;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSigningInfo;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 28})
public class UpdateVerificationTest {
  private PackageInfo info(Context context, int code, String version, byte signature) {
    PackageInfo info = new PackageInfo();
    info.packageName = context.getPackageName();
    info.versionCode = code;
    info.versionName = version;
    info.applicationInfo = new ApplicationInfo();
    info.applicationInfo.packageName = info.packageName;
    info.signatures = new Signature[] {new Signature(new byte[] {signature, 2, 3})};
    if (Build.VERSION.SDK_INT >= 28) {
      info.signingInfo = new SigningInfo();
      ((ShadowSigningInfo) Shadow.extract(info.signingInfo)).setSignatures(info.signatures);
    }
    return info;
  }

  @Test
  public void onlyNewerPackageWithTheInstalledSignatureCanBeInstalled() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    org.robolectric.shadows.ShadowPackageManager pm = Shadows.shadowOf(context.getPackageManager());
    pm.installPackage(info(context, 85, "1.0.85", (byte) 1));
    File file = new File(context.getCacheDir(), "candidate.apk");
    PackageInfo incoming = info(context, 86, "1.0.86", (byte) 1);
    pm.setPackageArchiveInfo(file.getAbsolutePath(), incoming);
    UpdateManager.verifyArchive(context, file, "1.0.86");
    pm.setPackageArchiveInfo(file.getAbsolutePath(), info(context, 86, "1.0.86", (byte) 9));
    assertThrows(Exception.class, () -> UpdateManager.verifyArchive(context, file, "1.0.86"));
    pm.setPackageArchiveInfo(file.getAbsolutePath(), info(context, 85, "1.0.85", (byte) 1));
    assertThrows(Exception.class, () -> UpdateManager.verifyArchive(context, file, "1.0.85"));
    incoming.packageName = "another.application";
    pm.setPackageArchiveInfo(file.getAbsolutePath(), incoming);
    assertThrows(Exception.class, () -> UpdateManager.verifyArchive(context, file, "1.0.86"));
  }
}
