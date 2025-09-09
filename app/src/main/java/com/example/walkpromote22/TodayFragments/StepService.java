package com.example.walkpromote22.TodayFragments;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.walkpromote22.Activities.MainActivity;
import com.example.walkpromote22.Manager.StepSyncManager;
import com.example.walkpromote22.R;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Step;
import com.example.walkpromote22.tool.TimeUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 完整版本地计步前台 Service。与 SensorStepProvider 配合，在无 Google / Samsung / Huawei
 * 服务时依靠硬件计步器 (TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR) 获取步数。
 * <p>
 * 主要改动：
 * <ul>
 *     <li>Counter 模式逻辑简化，直接用 (raw – base) 计算今日步数。</li>
 *     <li>实时写 SharedPreferences，SensorStepProvider 能立即读取。</li>
 *     <li>每 30 秒持久化数据库，关屏/关机/换日亦持久化。</li>
 *     <li>前台通知展示今日步数。</li>
 * </ul>
 */
public class StepService extends Service implements SensorEventListener {

    // ==================== 常量 ====================
    private static final String TAG = "StepService";
    private static final String CHANNEL_ID   = "step_service_channel";
    private static final String CHANNEL_NAME = "Step Service";
    private static final int    NOTIFY_ID    = 10086;
    // ① 新增常量（放在 KEY_TODAY_STEP 下面即可）
    private static final String KEY_BASE_COUNT = "base_count";


    /** SharedPreferences 缓存键，供 SensorStepProvider 读取 */
    private static final String PREF_NAME      = "step_service_cache";
    private static final String KEY_TODAY_STEP = "today_steps";
    private static final int PERMISSION_REQUEST_CODE = 100 ;

    // ==================== 运行时字段 ====================
    private SensorManager sensorManager;
    private int           sensorType   = -1;   // 0 = Counter , 1 = Detector
    private boolean       hasBase      = false;
    private int           baseCount    = 0;    // TYPE_STEP_COUNTER 的基准值

    private int           todaySteps   = 0;    // 今日步数
    private String        todayDate;
    // 放在 KEY_TODAY_STEP 下面
    private static final String KEY_TODAY_DISTANCE = "today_distance"; // m
    // ------- 运行时 -------
    private float todayDistanceM = 0f;      // 今日距离 (米)

    private float strideM;
    private NotificationManager  nm;
    private NotificationCompat.Builder builder;

    private StepDao       stepDao;
    private final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long AUTO_SAVE_INTERVAL_MS = 5_000000;      // 5 秒

    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());

    private final Runnable autoSaveTask = new Runnable() {
        @Override
        public void run() {
            saveToDb();                                   // ⬅ 每次调用持久化
            autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL_MS);
        }
    };
    /* 与 Activity 通讯，可选 */
    private final Messenger messenger = new Messenger(new Handler(msg -> {
        if (msg.what == 1) { // 1 = Activity 请求今日步数
            Messenger reply = msg.replyTo;
            if (reply != null) {
                Message res = Message.obtain(null, 2); // 2 = 返回步数
                Bundle b = new Bundle();
                b.putInt("steps", todaySteps);
                res.setData(b);
                try { reply.send(res); } catch (RemoteException ignored) {}
            }
            return true;
        }
        return false;
    }));

    // ==================== 生命周期 ====================
    @Override
    public void onCreate() {
        super.onCreate();
        stepDao   = AppDatabase.getDatabase(getApplicationContext()).stepDao();
        todayDate = TimeUtil.getCurrentDate();




        // StepService.onCreate()
        startStepSensor();        // ① 注册传感器，确定 sensorType
        restoreFromPrefs();       // ② 现在再恢复 hasBase 更安全
        resumeFromDb();           // ③ 取回历史数据

        initStride();
        initForeground();
        registerSystemReceiver();


    }

    /**
     * 初始化今日步长 strideM（单位：米）。
     * 调用时机：StepService.onCreate() 或 restoreFromPrefs() 之后。
     * 数据来源：用户偏好 SharedPreferences，键名可按实际项目调整。
     */
    private void initStride() {
        SharedPreferences userSp = getSharedPreferences("user_prefs", MODE_PRIVATE);

        // ① 读取身高（cm），若不存在默认 170cm
        float heightCm = userSp.getFloat("HEIGHT_CM", 170f);

        // ② 可选：读取性别，若无则默认为男性系数
        boolean isMale = userSp.getBoolean("IS_MALE", true);

        // ③ 计算并写回字段
        strideM = calculateStrideM(heightCm, isMale);
    }
    /**
     * 经验公式计算步长（米）：
     * 男性 stride ≈ 0.415 × 身高(cm) / 100
     * 女性 stride ≈ 0.413 × 身高(cm) / 100
     *
     * @param heightCm 使用 cm 为单位的身高
     * @param isMale   true=使用男性系数，false=女性
     * @return         步长（米）
     */
    private float calculateStrideM(float heightCm, boolean isMale) {
        float coeff = isMale ? 0.415f : 0.413f;
        return heightCm * coeff / 100f;
    }

    // ③ 新增恢复方法
    private void restoreFromPrefs() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        todaySteps = sp.getInt(KEY_TODAY_STEP, 0);
        baseCount  = sp.getInt(KEY_BASE_COUNT, 0);
        todayDistanceM  = sp.getFloat (KEY_TODAY_DISTANCE, 0f);
        hasBase    = (sensorType == 0 && baseCount != 0);
    }
    private void cacheToPrefs() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_TODAY_STEP, todaySteps)
                .putInt(KEY_BASE_COUNT,  baseCount)   // 关键新增
                .putFloat(KEY_TODAY_DISTANCE, todayDistanceM)
                .apply();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        autoSaveHandler.removeCallbacks(autoSaveTask);
        autoSaveHandler.postDelayed(autoSaveTask, AUTO_SAVE_INTERVAL_MS);
        return START_STICKY; }

    @Override
    public void onDestroy() {

        if (sensorManager != null) sensorManager.unregisterListener(this);
        unregisterReceiver(sysReceiver);
        IO.shutdown();
        stopForeground(true);

        autoSaveHandler.removeCallbacks(autoSaveTask);  // 停止循环
        super.onDestroy();


    }

    // ==================== 前台通知 ====================
    @SuppressLint("ForegroundServiceType")
    private void initForeground() {
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN);
            nc.enableLights(false);
            nc.setShowBadge(false);
            nc.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(nc);
        }

        Intent nfIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, nfIntent,
                Build.VERSION.SDK_INT >= 31 ?
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                        PendingIntent.FLAG_UPDATE_CURRENT);

        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentIntent(pi)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentTitle("Today\u2019s steps: 0")
                .setContentText("Let\u2019s move!");

        // Add this import at the top of your file if not already present:
        // import static android.content.Context.FOREGROUND_SERVICE_TYPE_HEALTH;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, builder.build(), FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFY_ID, builder.build());
        }
    }

    private void updateNotification() {
        if (builder == null) {                 // 保险：任何线程调用前都先判断
            Log.w(TAG, "builder is null, skip notify");
            return;
        }
        builder.setContentTitle("Today’s steps: " + todaySteps);
        nm.notify(NOTIFY_ID, builder.build());
    }


    // ==================== 传感器 ====================
    private void startStepSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) return;

        Sensor counter  = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Sensor detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

        if (counter != null) {
            sensorType = 0;
            sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Using TYPE_STEP_COUNTER");
        } else if (detector != null) {
            sensorType = 1;
            sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Using TYPE_STEP_DETECTOR");
        } else {
            Log.e(TAG, "No step sensor available!");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (sensorType == 0) { // Counter
            int raw = (int) event.values[0];
            if (!hasBase) {
                baseCount = raw;
                hasBase   = true;
            }
            todaySteps = raw - baseCount;
        } else if (sensorType == 1) { // Detector
            if (event.values[0] == 1.0f) todaySteps++;
        }




        cacheToPrefs();
        updateNotification();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }

    // ==================== 数据持久化 ====================
    /** 每次写 SharedPreferences，SensorStepProvider 能读取最新值 */


    /** 恢复今日步数（如服务被重启）*/


    /** 保存到数据库 */
    /** 保存到数据库 */
    /**
     * 把今日步数写入数据库（本方法由定时器每 5 秒调用一次）
     * 不修改方法名、不省略任何逻辑。
     */
    private void saveToDb() {
        IO.execute(() -> {
            String userKey = getSharedPreferences("user_prefs", MODE_PRIVATE)
                    .getString("USER_KEY", "default_user");

            Step rec = stepDao.getStepByDate(userKey, todayDate);
            todayDistanceM = todaySteps * strideM;
            if (rec == null) {
                rec = new Step(userKey, todayDate, todaySteps, todayDistanceM / 1000f); // km
            } else {
                rec.setStepCount(todaySteps);
                rec.setDistance(todayDistanceM / 1000f);
            }
            stepDao.insertStep(rec);
            new StepSyncManager(getApplicationContext()).uploadToday();
        });
    }

    private void resumeFromDb() {
        IO.execute(() -> {
            String userKey = getSharedPreferences("user_prefs", MODE_PRIVATE)
                    .getString("USER_KEY", "default_user");

            Step rec = stepDao.getStepByDate(userKey, todayDate);
            if (rec != null) {
                todaySteps      = rec.getStepCount();
                todayDistanceM  = rec.getDistance() * 1000f;

                // ---- 切回主线程再更新 ----
                new Handler(Looper.getMainLooper()).post(() -> {
                    cacheToPrefs();            // 写共享缓存
                    updateNotification();      // 此时 builder 已初始化
                });
            }
        });
    }


    // ==================== 系统广播 ====================
    private BroadcastReceiver sysReceiver;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSystemReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_SHUTDOWN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sysReceiver = new InnerReceiver(), f, RECEIVER_EXPORTED);
        } else {
            registerReceiver(sysReceiver = new InnerReceiver(), f);
        }
    }

    private class InnerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            saveToDb();

        }
    }

    /** 零点跨天处理 */

}
