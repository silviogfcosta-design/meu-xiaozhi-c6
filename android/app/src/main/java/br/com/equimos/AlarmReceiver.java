package br.com.equimos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {
    static final String CHANNEL = "equimos_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ReminderStore.rescheduleAll(context);
            return;
        }
        ReminderStore.remove(context, intent.getIntExtra("id", 0));
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Lembretes Equimos", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 100,
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = intent.getStringExtra("text");
        Notification.Builder note = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        manager.notify(intent.getIntExtra("id", (int) System.currentTimeMillis()),
                note.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("Lembrete do Equimos")
                        .setContentText(text == null ? "Seu lembrete chegou." : text)
                        .setAutoCancel(true)
                        .setContentIntent(content)
                        .build());
        context.sendBroadcast(new Intent("br.com.equimos.REMINDER_FIRED")
                .setPackage(context.getPackageName())
                .putExtra("text", text == null ? "Seu lembrete chegou." : text));
    }
}
