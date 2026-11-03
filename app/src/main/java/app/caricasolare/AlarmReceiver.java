package app.caricasolare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {
    private static PowerManager.WakeLock bridge;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (AutoEngine.enabled(context)) {
            synchronized (AlarmReceiver.class) {
                if (bridge == null) {
                    PowerManager pm = context.getSystemService(PowerManager.class);
                    bridge = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "caricasolare:sveglia");
                }
                bridge.acquire(60_000L);
            }
            try {
                context.startForegroundService(
                        new Intent(context, AutoService.class).setAction(AutoService.ACTION_RUN)
                );
            } catch (Exception unused) {
                AutoService.scheduleNext(context);
                release();
            }
        }
    }

    static synchronized void release() {
        if (bridge != null && bridge.isHeld()) {
            bridge.release();
        }
    }
}

