package ch.sinan.schlicht;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Erinnert ruhig an eine Pause; Antippen führt zurück zu Kanso. */
public class PauseReminder extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel("pause", "Pausen-Erinnerungen",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent home = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 2, home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String app = i.getStringExtra("app");
        int min = i.getIntExtra("min", 0);
        Notification n = new Notification.Builder(c, "pause")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Zeit für eine Pause")
                .setContentText("Du bist seit " + min + " Minuten in " + app + ".")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(42, n);
    }
}
