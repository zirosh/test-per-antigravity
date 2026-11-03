package app.caricasolare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (AutoEngine.enabled(context)) {
            try {
                AutoService.start(context);
            } catch (Exception unused) {
                AutoService.scheduleNext(context);
            }
        }
    }
}

