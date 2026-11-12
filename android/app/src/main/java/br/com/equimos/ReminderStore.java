package br.com.equimos;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

final class ReminderStore {
    private static final String PREFS = "equimos_reminder_store";
    private static final String LIST = "list";

    private ReminderStore() {}

    static synchronized void put(Context context, int id, String text, long when) {
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && item.optInt("id") != id) next.put(item);
        }
        next.put(new JSONObjectBuilder(id, text, when).build());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(LIST, next.toString()).apply();
    }

    static synchronized void remove(Context context, int id) {
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && item.optInt("id") != id) next.put(item);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(LIST, next.toString()).apply();
    }

    static synchronized void rescheduleAll(Context context) {
        JSONArray reminders = read(context);
        long now = System.currentTimeMillis();
        for (int i = 0; i < reminders.length(); i++) {
            JSONObject item = reminders.optJSONObject(i);
            if (item == null) continue;
            schedule(context, item.optInt("id"), item.optString("text", "Lembrete"),
                    Math.max(now + 5000, item.optLong("when", now + 5000)));
        }
    }

    static void schedule(Context context, int id, String text, long when) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .putExtra("text", text)
                .putExtra("id", id);
        PendingIntent pending = PendingIntent.getBroadcast(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
                return;
            } catch (SecurityException ignored) {
                // Fall back if the system revokes exact-alarm access between checks.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
    }

    private static JSONArray read(Context context) {
        try {
            String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(LIST, "[]");
            return new JSONArray(stored);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static final class JSONObjectBuilder {
        private final int id;
        private final String text;
        private final long when;

        JSONObjectBuilder(int id, String text, long when) {
            this.id = id;
            this.text = text;
            this.when = when;
        }

        JSONObject build() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id);
                value.put("text", text);
                value.put("when", when);
            } catch (Exception ignored) {}
            return value;
        }
    }
}
