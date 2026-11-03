package app.caricasolare;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoService extends Service {
    static final String ACTION_RUN = "app.caricasolare.RUN";
    static final String ACTION_START = "app.caricasolare.START";
    static final String ACTION_STOP = "app.caricasolare.STOP";
    static final String CHANNEL = "auto";
    static final int NOTIF_ID = 1;

    private AutoEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context context) {
        AutoEngine.setEnabled(context, true);
        SolarWidget.refresh(context);
        context.startForegroundService(new Intent(context, AutoService.class).setAction(ACTION_START));
    }

    static void stop(Context context) {
        AutoEngine.setEnabled(context, false);
        SolarWidget.refresh(context);
        cancelAlarm(context);
        context.stopService(new Intent(context, AutoService.class));
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIF_ID);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.engine = new AutoEngine(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Carica automatica", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Stato della carica automatica con il sole");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        Notification notif = notification(AutoEngine.enabled(this) ? "Controllo in corso…" : "Spengo…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        if (ACTION_STOP.equals(action) || !AutoEngine.enabled(this)) {
            AutoEngine.setEnabled(this, false);
            cancelAlarm(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        final PowerManager.WakeLock wl = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "caricasolare:ciclo");
        wl.acquire(180_000L);
        final boolean force = intent != null && intent.getBooleanExtra("force", false);
        this.worker.execute(() -> {
            try {
                String result = this.engine.cycle(force);
                SolarWidget.refresh(this);
                if (AutoEngine.enabled(this)) {
                    this.engine.setNextAt(scheduleNext(this));
                    update(result);
                } else {
                    cancelAlarm(this);
                    this.engine.setNextAt(0L);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    goodnight(result);
                    stopSelf();
                }
            } finally {
                if (wl.isHeld()) {
                    wl.release();
                }
                AlarmReceiver.release();
            }
        });
        return START_STICKY;
    }

    private void goodnight(String msg) {
        if (msg.startsWith("Tramonto")) {
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
            );
            String nowStr = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_sun)
                    .setContentTitle("Automatico spento al tramonto")
                    .setContentText(nowStr + " · Domani riaccendilo dall'app, se ti serve")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            getSystemService(NotificationManager.class).notify(2, n);
        }
    }

    private void update(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(text));
    }

    private Notification notification(String text) {
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopAuto = PendingIntent.getService(
                this,
                NOTIF_ID,
                new Intent(this, AutoService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
        );
        String nowStr = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sun)
                .setContentTitle("Carica automatica attiva")
                .setContentText(nowStr + " · " + text)
                .setStyle(new Notification.BigTextStyle().bigText(nowStr + " · " + text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder((Icon) null, "Spegni", stopAuto).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    static long scheduleNext(Context context) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, 30);
        int rem = (6 - (cal.get(Calendar.MINUTE) % 5)) % 5;
        cal.add(Calendar.MINUTE, (rem != 0 || cal.get(Calendar.SECOND) < 20) ? rem : 5);
        cal.set(Calendar.SECOND, 20);
        cal.set(Calendar.MILLISECOND, 0);
        long nextAt = cal.getTimeInMillis();
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(context);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi);
        }
        return nextAt;
    }

    static void cancelAlarm(Context context) {
        context.getSystemService(AlarmManager.class).cancel(alarmIntent(context));
    }

    private static PendingIntent alarmIntent(Context context) {
        return PendingIntent.getBroadcast(
                context,
                0,
                new Intent(context, AlarmReceiver.class),
                PendingIntent.FLAG_IMMUTABLE
        );
    }

    @Override
    public void onDestroy() {
        this.worker.shutdown();
        super.onDestroy();
    }
}

