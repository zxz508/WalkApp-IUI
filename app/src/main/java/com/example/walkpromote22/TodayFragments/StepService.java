package com.example.walkpromote22.TodayFragments;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.example.walkpromote22.Activities.MainActivity;
import com.example.walkpromote22.R;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StepService extends Service implements LocationListener, SensorEventListener {

    private static final String TAG = "StepService";

    // 通知
    private static final String CHANNEL_ID = "walkcoach_location_channel";
    private static final String CHANNEL_NAME = "Walking Location Service";
    private static final int NOTIF_ID = 1001;

    // Actions（对外）
    public static final String ACTION_START = "com.example.walkpromote22.action.START";
    public static final String ACTION_STOP  = "com.example.walkpromote22.action.STOP";
    public static final String ACTION_STEP_UPDATE = "com.example.walkpromote22.action.STEP_UPDATE"; // 新增：步数广播

    // 广播 extra
    public static final String EXTRA_STEPS = "steps";
    public static final String EXTRA_SOURCE = "source"; // "counter"/"detector"/"restore"

    // 定位
    private LocationManager locationManager;
    private String currentProvider;

    // 计步
    private SensorManager sensorManager;
    private Sensor stepCounter;   // TYPE_STEP_COUNTER（开机以来总步数）
    private Sensor stepDetector;  // TYPE_STEP_DETECTOR（每次+1）
    private boolean activityPermissionGranted = false;

    // 当日计步持久化（SharedPreferences）
    private static final String SP_NAME = "step_prefs";
    private static final String KEY_DATE = "date";                 // yyyyMMdd
    private static final String KEY_BASELINE = "counter_baseline"; // STEP_COUNTER基线
    private static final String KEY_TODAY_STEPS = "today_steps";   // 当日累计（用于DETECTOR或无基线时）

    private int todaySteps = 0;          // 当日步数
    private float counterBaseline = -1f; // STEP_COUNTER 基线
    private String todayDateStr = "";    // 当天日期（yyyyMMdd）

    private boolean running = false;

    // =============== 生命周期 ===============
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        createNotificationChannel();


        startAsForegroundWithTypes();
        initLocationManager();
        restoreStepState();         // 恢复当日步数/基线
        initSensorsAndRegister();   // 初始化并注册计步传感器（有权限才启用）
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand action=" + action + " running=" + running);

        if (ACTION_STOP.equals(action)) {
            stopLocationUpdates();
            unregisterStepSensors();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 默认行为：启动并请求定位 +（若有权限）计步已在 onCreate 注册
        startLocationUpdatesSafe();
        running = true;

        // 刚启动时也广播一次，便于 UI 立即拿到最新值
        broadcastSteps(todaySteps, "restore");

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopLocationUpdates();
        unregisterStepSensors();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // 不提供绑定
        return null;
    }
    private boolean hasActivityRecPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                        == PackageManager.PERMISSION_GRANTED;
    }



    private void startAsForegroundWithTypes() {
        Notification n = buildNotification("SmartWalkCoach 正在运行", "定位与行走监测已开启");
        Log.e(TAG,"startAsForegroundWithTypes");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;

            if (hasActivityRecPermission()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            Log.e(TAG, "FGS types=" + types + " (health=" + ((types & ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) != 0) + ")");

            startForeground(NOTIF_ID, n, types);

            if (hasActivityRecPermission()) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            }
            // 仅三参；不要回退两参（两参会按 Manifest 所有类型校验，容易踩坑）
            startForeground(NOTIF_ID, n, types);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }



    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            );
            c.setDescription("用于持续定位与步行服务的前台通知");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tapIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(
                        Build.VERSION.SDK_INT >= 31
                                ? NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                                : NotificationCompat.FOREGROUND_SERVICE_DEFAULT
                )
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return b.build();
    }

    // =============== 定位（保留你的实现） ===============
    private void initLocationManager() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private void startLocationUpdatesSafe() {
        if (locationManager == null) initLocationManager();

        boolean fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fine && !coarse) {
            Log.w(TAG, "No location permission, skip requesting updates.");
            return;
        }

        try {
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setPowerRequirement(Criteria.POWER_LOW);
            String provider = locationManager.getBestProvider(criteria, true);

            if (provider == null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    provider = LocationManager.GPS_PROVIDER;
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    provider = LocationManager.NETWORK_PROVIDER;
                }
            }

            currentProvider = provider;

            if (provider != null) {
                long minTimeMs = 5000L;
                float minDistanceM = 10f;
                locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, this);
                Log.i(TAG, "Requesting location updates from provider=" + provider);
            } else {
                Log.w(TAG, "No available provider (GPS/Network disabled).");
            }

        } catch (SecurityException se) {
            Log.e(TAG, "Missing location permission when requesting updates", se);
        } catch (Throwable t) {
            Log.e(TAG, "requestLocationUpdates failed", t);
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Throwable t) {
                Log.w(TAG, "removeUpdates failed", t);
            }
        }
    }

    // LocationListener
    @Override public void onLocationChanged(Location location) {
        if (location == null) return;
        Log.d(TAG, "onLocationChanged: " + location.getLatitude() + ", " + location.getLongitude());
        // TODO：需要的话在此同步到 RouteSyncManager / 数据库
    }
    @Override public void onProviderEnabled(String provider) { Log.d(TAG, "onProviderEnabled: " + provider); }
    @Override public void onProviderDisabled(String provider) { Log.d(TAG, "onProviderDisabled: " + provider); }
    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

    // =============== 计步：初始化/注册/卸载 ===============
    private void initSensorsAndRegister() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            Log.w(TAG, "No SensorManager");
            return;
        }

        // Android 10+ 需要 ACTIVITY_RECOGNITION 运行时权限读取计步传感器
        activityPermissionGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                        == PackageManager.PERMISSION_GRANTED;

        if (!activityPermissionGranted) {
            Log.w(TAG, "No ACTIVITY_RECOGNITION permission, skip step sensors.");
            return;
        }

        stepCounter  = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

        boolean anyRegistered = false;
        if (stepCounter != null) {
            anyRegistered |= sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (stepDetector != null) {
            anyRegistered |= sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (!anyRegistered) {
            Log.w(TAG, "No step sensors available on this device.");
        }
    }

    private void unregisterStepSensors() {
        if (sensorManager != null) {
            try { sensorManager.unregisterListener(this); } catch (Throwable ignore) {}
        }
    }

    // =============== 计步：状态恢复/保存/日切 ===============
    private void restoreStepState() {
        String today = getTodayStr();
        android.content.SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        String savedDate = sp.getString(KEY_DATE, "");
        if (!today.equals(savedDate)) {
            // 日期变更：重置
            todayDateStr = today;
            counterBaseline = -1f;
            todaySteps = 0;
            persistStepState();
        } else {
            todayDateStr = savedDate;
            counterBaseline = sp.getFloat(KEY_BASELINE, -1f);
            todaySteps = sp.getInt(KEY_TODAY_STEPS, 0);
        }
        Log.i(TAG, "restoreStepState date=" + todayDateStr + " baseline=" + counterBaseline + " steps=" + todaySteps);
    }

    private void persistStepState() {
        android.content.SharedPreferences.Editor sp = getSharedPreferences(SP_NAME, MODE_PRIVATE).edit();
        sp.putString(KEY_DATE, todayDateStr);
        sp.putFloat(KEY_BASELINE, counterBaseline);
        sp.putInt(KEY_TODAY_STEPS, todaySteps);
        sp.apply();
    }

    private void ensureToday() {
        String t = getTodayStr();
        if (!t.equals(todayDateStr)) {
            todayDateStr = t;
            counterBaseline = -1f;
            todaySteps = 0;
            persistStepState();
            broadcastSteps(todaySteps, "date-reset");
        }
    }

    private static String getTodayStr() {
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
    }

    // =============== 计步：回调计算 ===============
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;

        ensureToday();

        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            float totalSinceBoot = event.values != null && event.values.length > 0 ? event.values[0] : 0f;

            // 第一次或重启后建立基线
            if (counterBaseline < 0f || totalSinceBoot < counterBaseline) {
                counterBaseline = totalSinceBoot;
            }

            int computed = Math.max(0, Math.round(totalSinceBoot - counterBaseline));
            if (computed != todaySteps) {
                todaySteps = computed;
                persistStepState();
                broadcastSteps(todaySteps, "counter");

                // TODO：如需写数据库/同步服务，这里调用（避免“虚构”，留钩子）
                // StepSyncManager.saveLocalToday(todaySteps);
                // stepDao.upsert(...);
            }
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            // 某些设备无 COUNTER，可用 DETECTOR 叠加
            // 注意：若同时有 COUNTER，这里可以选择不叠加，避免重复
            if (stepCounter == null) {
                int newVal = todaySteps + 1;
                if (newVal != todaySteps) {
                    todaySteps = newVal;
                    persistStepState();
                    broadcastSteps(todaySteps, "detector");

                    // TODO：如需写数据库/同步服务，这里调用
                }
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =============== 广播给 UI ===============
    private void broadcastSteps(int steps, String source) {
        Intent i = new Intent(ACTION_STEP_UPDATE);
        i.putExtra(EXTRA_STEPS, steps);
        i.putExtra(EXTRA_SOURCE, source);
        // 直接应用内广播（普通广播即可；若你使用 LocalBroadcastManager，可自行替换）
        sendBroadcast(i);
        Log.d(TAG, "broadcastSteps steps=" + steps + " source=" + source);
    }
}
