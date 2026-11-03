package app.caricasolare;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

public class SolarWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "app.caricasolare.WIDGET_TOGGLE";

    @Override
    public void onUpdate(final Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        refresh(context);
        final BroadcastReceiver.PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Api.get(context).solax();
            } catch (Exception unused) {
            } finally {
                refresh(context);
                pending.finish();
            }
        }).start();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            try {
                JSONObject cfg = Api.get(context).config();
                if (AutoEngine.enabled(context)) {
                    AutoService.stop(context);
                } else if (cfg.optBoolean("tesla_ready") && cfg.optBoolean("logged_in")) {
                    AutoService.start(context);
                } else {
                    context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (Exception unused) {
            }
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    static void refresh(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, SolarWidget.class));
            if (ids != null && ids.length != 0) {
                mgr.updateAppWidget(ids, views(context));
            }
        } catch (Exception unused) {
        }
    }

    private static RemoteViews views(Context context) throws Exception {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget);
        Api api = Api.get(context);
        JSONObject lastSolax = api.lastSolax();
        JSONObject lastCar = api.lastCar();
        String timeStr = "";

        if (lastSolax != null) {
            int homeSoc = (int) Math.round(lastSolax.optDouble("home_soc"));
            double batW = lastSolax.optDouble("battery_w");
            rv.setTextViewText(R.id.w_home_soc, homeSoc + "%");
            rv.setProgressBar(R.id.w_home_bar, 100, homeSoc, false);
            String batSuffix;
            if (batW > 20.0d) {
                batSuffix = " · +" + kw(batW);
            } else if (batW < -20.0d) {
                batSuffix = " · −" + kw(-batW);
            } else {
                batSuffix = "";
            }
            rv.setTextViewText(R.id.w_home_label, "Casa" + batSuffix);
            timeStr = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(lastSolax.optLong("timestamp")));
        }

        if (lastCar != null && lastCar.has("soc")) {
            int carSoc = lastCar.optInt("soc");
            int limit = lastCar.optInt("limit", 100);
            rv.setTextViewText(R.id.w_car_soc, carSoc + "%");
            rv.setProgressBar(R.id.w_car_bar, 100, carSoc, false);
            String carState;
            if (lastCar.optBoolean("charging")) {
                carState = lastCar.optInt("amps") + " A";
            } else {
                carState = lastCar.optBoolean("plugged") ? "collegata" : "";
            }
            rv.setTextViewText(R.id.w_car_label, "Tesla" + (carState.isEmpty() ? "" : " · " + carState) + " · di " + limit + "%");
        }

        boolean autoOn = AutoEngine.enabled(context);
        JSONObject status = new AutoEngine(context).status();
        rv.setTextViewText(R.id.w_toggle, autoOn ? "Auto: sì" : "Auto: no");
        rv.setInt(R.id.w_toggle, "setBackgroundResource", autoOn ? R.drawable.widget_pill_on : R.drawable.widget_pill_off);
        rv.setTextColor(R.id.w_toggle, autoOn ? 0xFF3EC46D : 0xFFF4F4F5);

        String statusText;
        if (autoOn && !status.optString("last_text").isEmpty()) {
            statusText = status.optString("last_text");
        } else {
            statusText = status.optBoolean("off_at_sunset") ? "Spento al tramonto" : "Manuale";
        }
        rv.setTextViewText(R.id.w_status, (timeStr.isEmpty() ? "" : timeStr + " · ") + statusText);

        rv.setOnClickPendingIntent(
                R.id.w_root,
                PendingIntent.getActivity(
                        context,
                        10,
                        new Intent(context, MainActivity.class)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE
                )
        );
        rv.setOnClickPendingIntent(
                R.id.w_toggle,
                PendingIntent.getBroadcast(
                        context,
                        11,
                        new Intent(context, SolarWidget.class).setAction(ACTION_TOGGLE),
                        PendingIntent.FLAG_IMMUTABLE
                )
        );
        return rv;
    }

    private static String kw(double w) {
        return w >= 1000.0d ? String.format(Locale.ITALY, "%.1f kW", w / 1000.0d) : Math.round(w) + " W";
    }
}

