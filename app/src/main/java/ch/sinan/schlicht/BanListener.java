package ch.sinan.schlicht;

import android.app.Notification;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Hält Benachrichtigungen verbannter Apps bis zum Ende der Sperre zurück (Anrufe, Wecker und Laufendes ausgenommen). */
public class BanListener extends NotificationListenerService {
    static BanListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        sweep();
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        hold(sbn);
    }

    void sweep() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return;
            for (StatusBarNotification sbn : all) hold(sbn);
        } catch (Exception ignored) {
        }
    }

    private void hold(StatusBarNotification sbn) {
        SharedPreferences p = getSharedPreferences("schlicht", MODE_PRIVATE);
        long until = p.getLong("ban_" + sbn.getPackageName(), 0);
        long now = System.currentTimeMillis();
        if (until <= now || sbn.isOngoing()) return;
        String cat = sbn.getNotification().category;
        if (Notification.CATEGORY_CALL.equals(cat) || Notification.CATEGORY_ALARM.equals(cat)) return;
        try {
            snoozeNotification(sbn.getKey(), until - now);   // kommt nach der Sperre zurück
        } catch (Exception e) {
            try { cancelNotification(sbn.getKey()); } catch (Exception ignored) { }
        }
    }
}
