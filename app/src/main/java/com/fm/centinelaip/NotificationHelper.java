package com.fm.centinelaip;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class NotificationHelper {
    static final String CHANNEL_ID = "person_detection";

    static void createChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Eventos de detección",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Alertas locales para las clases elegidas por el usuario");
        manager.createNotificationChannel(channel);
    }

    static void notifyEvent(Context context, EventRecord event) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        Intent open = new Intent(context, EventsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                100,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Detección seleccionada")
                .setContentText(event.cameraName + " · " + event.summary)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();
        context.getSystemService(NotificationManager.class)
                .notify((int) (event.timestamp % Integer.MAX_VALUE), notification);
    }
}
