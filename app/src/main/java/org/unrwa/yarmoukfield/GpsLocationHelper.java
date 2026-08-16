package org.unrwa.yarmoukfield;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** Captures a home-location fix in one of two explicit, transparent modes:
 *   MODE_GPS_OFFLINE — GPS_PROVIDER only. Real satellite fix, works with zero connectivity, the official
 *     default. This app never adds INTERNET permission, and this mode never needs it.
 *   MODE_DEVICE_HIGH_ACCURACY — every provider the device currently has enabled (GPS_PROVIDER AND, if
 *     enabled, NETWORK_PROVIDER). NETWORK_PROVIDER fixes can be Wi-Fi/cell-assisted and resolved by the
 *     OS's own location service — this app still makes no network call of its own and needs no INTERNET
 *     permission, but the UI must always disclose when the winning fix did NOT come from GPS_PROVIDER.
 * Either mode listens continuously for up to windowMs (60-90s recommended), reporting live progress on every
 * update, and keeps only the single most accurate (lowest Location.getAccuracy()) reading seen — never the
 * first, never the last, just the best. The caller can accept the current best early instead of waiting out
 * the full window. Nothing is ever persisted by this class itself — it only ever hands the caller a Location
 * (or null); saving/overwrite-confirmation is entirely the caller's responsibility. */
public final class GpsLocationHelper {
    public static final String MODE_GPS_OFFLINE = "gps_offline";
    public static final String MODE_DEVICE_HIGH_ACCURACY = "device_high_accuracy";

    public interface Callback {
        /** Fired on every improved (or first) reading during the capture window. best.getProvider() tells
         * you which provider produced it ("gps" or "network"). */
        void onProgress(Location best, long elapsedMs, long windowMs);
        /** Fired when the window ends naturally or acceptNow() is called. best is null only if literally no
         * reading was ever received (report as a failure — never save a null fix). */
        void onFinished(Location best);
        void onUnavailable(String reason);
    }

    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> activeProviders = new ArrayList<>();
    private LocationListener listener;
    private Runnable timeoutRunnable, tickRunnable;
    private Location best;
    private Callback activeCallback;
    private long windowMs, startedAt;

    public GpsLocationHelper(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean isGpsProviderEnabled() {
        return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    /** Whether MODE_DEVICE_HIGH_ACCURACY has anything extra to offer right now beyond plain GPS — i.e. the
     * device's network-based location provider is currently enabled. Used to decide whether to even show
     * the "دقة عالية من الهاتف" option, rather than offering a mode that can't do anything. */
    public boolean isNetworkProviderEnabled() {
        return locationManager != null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @SuppressLint("MissingPermission")
    public void startCapture(String mode, long windowMs, Callback callback) {
        if (locationManager == null) { callback.onUnavailable("خدمة الموقع غير متاحة على هذا الجهاز"); return; }
        List<String> providers = new ArrayList<>();
        boolean gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (MODE_GPS_OFFLINE.equals(mode)) {
            if (!gpsOn) { callback.onUnavailable("نظام GPS غير مفعّل على الجهاز — فعّله من إعدادات الموقع ثم أعد المحاولة"); return; }
            providers.add(LocationManager.GPS_PROVIDER);
        } else {
            if (!gpsOn && !networkOn) { callback.onUnavailable("لا يوجد أي مزود موقع مفعّل على الجهاز — فعّل خدمة الموقع من الإعدادات"); return; }
            if (gpsOn) providers.add(LocationManager.GPS_PROVIDER);
            if (networkOn) providers.add(LocationManager.NETWORK_PROVIDER);
        }
        cancel();
        this.windowMs = windowMs; this.startedAt = System.currentTimeMillis(); this.best = null; this.activeCallback = callback;
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (best == null || location.getAccuracy() < best.getAccuracy()) {
                    best = location;
                    activeCallback.onProgress(best, System.currentTimeMillis() - startedAt, GpsLocationHelper.this.windowMs);
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        for (String provider : providers) {
            locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper());
            activeProviders.add(provider);
        }
        tickRunnable = new Runnable() {
            @Override public void run() {
                if (activeCallback == null) return;
                activeCallback.onProgress(best, System.currentTimeMillis() - startedAt, GpsLocationHelper.this.windowMs);
                handler.postDelayed(this, 1000L);
            }
        };
        handler.postDelayed(tickRunnable, 1000L);
        timeoutRunnable = this::finish;
        handler.postDelayed(timeoutRunnable, windowMs);
    }

    /** Stops early and reports whatever best reading has been seen so far (or a failure if none yet) —
     * mirrors what happens when the window naturally ends. */
    public void acceptNow() { finish(); }

    private void finish() {
        Callback callback = activeCallback;
        Location result = best;
        stopListening();
        if (callback == null) return;
        if (result == null) callback.onUnavailable("انتهت مهلة الانتظار دون الحصول على إشارة موقع كافية — جرّب مكاناً مفتوحاً بعيداً عن المباني والأسقف، أو أعد المحاولة.");
        else callback.onFinished(result);
    }

    /** True cancel — stops everything and reports nothing to the caller (caller already knows it cancelled). */
    @SuppressLint("MissingPermission")
    public void cancel() {
        stopListening();
        activeCallback = null;
    }

    @SuppressLint("MissingPermission")
    private void stopListening() {
        if (listener != null && locationManager != null) { locationManager.removeUpdates(listener); listener = null; }
        activeProviders.clear();
        if (timeoutRunnable != null) { handler.removeCallbacks(timeoutRunnable); timeoutRunnable = null; }
        if (tickRunnable != null) { handler.removeCallbacks(tickRunnable); tickRunnable = null; }
    }
}
