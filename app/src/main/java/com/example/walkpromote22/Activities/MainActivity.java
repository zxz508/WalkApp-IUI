// MainActivity.java
package com.example.walkpromote22.Activities;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.amap.api.maps.MapsInitializer;
import com.example.walkpromote22.ChatbotFragments.ChatbotFragment;
import com.example.walkpromote22.Manager.RouteSyncManager;
import com.example.walkpromote22.Manager.StepSyncManager;
import com.example.walkpromote22.Manager.PathSyncManager;
import com.example.walkpromote22.ProfileFragments.PersonalInfoFragment;
import com.example.walkpromote22.ProfileFragments.ProfileFragment;
import com.example.walkpromote22.R;
import com.example.walkpromote22.TodayFragments.StepService;
import com.example.walkpromote22.TodayFragments.TodayFragment;
import com.example.walkpromote22.WalkFragments.WalkFragment;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.dto.UserDTO;
import com.example.walkpromote22.data.model.User;
import com.example.walkpromote22.service.ApiService;
import com.example.walkpromote22.tool.UserPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

public class MainActivity extends AppCompatActivity {

    // Fragments
    public static boolean isWeatherFetched = false;  // 用于标记天气是否已获取
    private final WalkFragment walkFragment = new WalkFragment();
    private final TodayFragment todayFragmentGooglefitness = new TodayFragment();
    private final ProfileFragment profileFragment = new ProfileFragment();
    private final ChatbotFragment chatbotFragment = new ChatbotFragment();

    private String userKey;
    AlertDialog exitDialog;

    // Bottom nav
    private TextView navToday;
    private TextView navRunning;
    private TextView navProfile;
    private TextView navChatbot;

    private SharedPreferences sharedPreferences;

    // ---- Permission request codes ----
    private static final int REQ_ACTIVITY_RECOGNITION = 1001; // 计步/活动识别
    private static final int REQ_LOCATION            = 2001;  // FINE+COARSE
    private static final int REQ_BG_LOCATION         = 2002;  // ACCESS_BACKGROUND_LOCATION
    private static final int REQ_POST_NOTIF          = 2003;  // Android 13+ 通知

    // Single-thread executor
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        // 登录检查
        if (!isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        } else {
            setContentView(R.layout.activity_home);
        }

        // ===== 权限“三段式”入口 =====
        // 1) 计步/活动识别（授予后会在回调里启动 StepService）
        checkAndRequestActivityPermission();
        // 2) 前台定位（精确/模糊一次性），若仅模糊则引导开启精确
        ensureForegroundLocationPermissions();
        // 3) Android 13+ 通知权限（保障前台服务通知稳定展示）
        ensurePostNotificationsPermission();
        // 后台定位：建议在“开始步行/开始导航”时机再调 ensureBackgroundLocationIfNeeded()

        // 读取 userKey、步数历史导入
        sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
        userKey = sharedPreferences.getString("USER_KEY", null);
        new StepSyncManager(this).importHistorySteps();

        // 初始化数据库 & 偏好
        AppDatabase db = AppDatabase.getDatabase(this);
        UserPreferences prefs = new UserPreferences(this);
        UserDao userDao = db.userDao();

        // 路线云-本地同步
        RouteSyncManager.init(getApplicationContext());
        RouteSyncManager.syncFromCloudToLocal(userKey);
        // 路线云-本地同步
        RouteSyncManager.init(getApplicationContext());
        RouteSyncManager.syncFromCloudToLocal(userKey);


        new PathSyncManager(this).pullAllForCurrentUser();


        // 若本地用户信息缺失，则从云端补齐
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            String k = prefs.getUserKey();
            Log.e(TAG, "userkey=" + k);
            User localUser = userDao.getUserByKey(k);

            if (localUser == null || k == null || k.isEmpty()) {
                runOnUiThread(() -> {
                    Log.e(TAG, "用户信息丢失，请重新登录");
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                });
                return;
            }

            if (localUser.hasNull()) {
                OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl("http://101.132.96.134:8080/")
                        .addConverterFactory(ScalarsConverterFactory.create())
                        .addConverterFactory(GsonConverterFactory.create())
                        .client(okHttpClient)
                        .build();

                ApiService apiService = retrofit.create(ApiService.class);
                try {
                    Response<UserDTO> resp = apiService.getUserByKey(k).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        UserDTO dto = resp.body();
                        User user = new User(
                                dto.getUserKey(),
                                dto.getGender(),
                                dto.getHeight(),
                                dto.getWeight(),
                                dto.getAge()
                        );
                        userDao.insert(user);
                        Log.i("MainActivity", "✅ 用户信息已从云端恢复");
                    } else {
                        Log.e("MainActivity", "❌ 拉取失败 code=" + resp.code());
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "❌ 拉取异常: " + e.getMessage());
                }
            }
        });

       /* // 后台线程查询用户数据 → 弹窗引导填写个人信息
        executorService.execute(() -> {
            final User user = db.userDao().getUserByKey(userKey);
            final boolean exists = (user != null);

            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    if (!exists || user.getWeight() == 0) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Tips")
                                .setMessage("You have not filled in the personal information, please fill in now")
                                .setPositiveButton("Yes", (dialog, which) -> {
                                    updateNavigationSelection(R.id.nav_profile, new ProfileFragment());
                                    getSupportFragmentManager().beginTransaction()
                                            .replace(R.id.host_fragment_container, new PersonalInfoFragment())
                                            .commit();
                                })
                                .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                                .show();
                    }
                }
            });
        });*/

        // 底部导航
        navToday = findViewById(R.id.nav_today);
        navRunning = findViewById(R.id.nav_walk);
        navProfile = findViewById(R.id.nav_profile);
        navChatbot = findViewById(R.id.nav_gpt);

        navToday.setOnClickListener(v -> updateNavigationSelection(R.id.nav_today, todayFragmentGooglefitness));
        navRunning.setOnClickListener(v -> updateNavigationSelection(R.id.nav_walk, walkFragment));
        navProfile.setOnClickListener(v -> updateNavigationSelection(R.id.nav_profile, profileFragment));
        navChatbot.setOnClickListener(v -> updateNavigationSelection(R.id.nav_gpt, chatbotFragment));

        // 默认加载首页
        updateNavigationSelection(R.id.nav_today, new TodayFragment());
    }

    // -------------------- 权限工具函数 --------------------

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarseLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasApproximateOnly() {
        return hasCoarseLocation() && !hasFineLocation(); // 仅“模糊定位”
    }

    /** 前台定位（一次性 FINE+COARSE）；Android 12+ 会弹“精确/模糊”选项 **/
    private void ensureForegroundLocationPermissions() {
        if (hasFineLocation() || hasCoarseLocation()) {
            if (hasApproximateOnly()) {
                promptEnablePreciseLocation();
            }
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQ_LOCATION
        );
    }

    /** 后台定位分步（建议在“开始步行/导航”按钮点击时机调用） **/
    public void ensureBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return; // Android 10 以下不需要
        if (!hasFineLocation() && !hasCoarseLocation()) return;    // 先得有前台定位

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQ_BG_LOCATION
            );
        }
    }

    /** Android 13+ 通知权限（用于展示前台服务通知） **/
    private void ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIF
                );
            }
        }
    }

    /** “仅模糊定位” → 引导开启“精确定位”的弹窗 **/
    private void promptEnablePreciseLocation() {
        new AlertDialog.Builder(this)
                .setTitle("开启精确定位")
                .setMessage("当前仅授予了“模糊定位”。为保证路线规划与附近兴趣点准确，请在设置中开启“精确定位”。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(i);
                    } catch (Exception e) {
                        // 兜底：打开系统定位设置
                        Intent i = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        startActivity(i);
                    }
                })
                .setNegativeButton("暂时不用", null)
                .show();
    }

    // -------------------- 其它业务工具函数 --------------------



    public void updateNavigationSelection(int navButtonId, Fragment fragment) {
        // 更新按钮颜色
        navToday.setTextColor(ContextCompat.getColor(this, R.color.gray));
        navRunning.setTextColor(ContextCompat.getColor(this, R.color.gray));
        navProfile.setTextColor(ContextCompat.getColor(this, R.color.gray));
        navChatbot.setTextColor(ContextCompat.getColor(this, R.color.gray));

        TextView selectedButton = findViewById(navButtonId);
        if (selectedButton != null) {
            selectedButton.setTextColor(ContextCompat.getColor(this, R.color.black));
        }

        // 切换 Fragment
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.host_fragment_container, fragment)
                .commit();
    }

    private boolean isLoggedIn() {
        SharedPreferences sp = getSharedPreferences("user_prefs", MODE_PRIVATE);
        return sp.getBoolean("is_logged_in", false);
    }

    // -------------------- ACTIVITY_RECOGNITION 权限专用流程 --------------------

    /** 检查并在必要时申请 ACTIVITY_RECOGNITION 权限（Android 10+） */
    private void checkAndRequestActivityPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onActivityPermissionGranted();
            Log.e(TAG, "has permission (pre-Q)");
            return;
        }

        int granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION);
        if (granted == PackageManager.PERMISSION_GRANTED) {
            onActivityPermissionGranted();
            Log.e(TAG, "has permission (AR)");
        } else {
            Log.e(TAG, "no permission (AR)");
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACTIVITY_RECOGNITION)) {
                new AlertDialog.Builder(this)
                        .setTitle("需要活动识别权限")
                        .setMessage("我们需要获取您的步数来记录每日运动量，拒绝后将无法统计步数。")
                        .setPositiveButton("好的", (d, w) ->
                                ActivityCompat.requestPermissions(
                                        MainActivity.this,
                                        new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                                        REQ_ACTIVITY_RECOGNITION))
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                        REQ_ACTIVITY_RECOGNITION
                );
            }
        }
    }

    /** 获得活动识别权限后的初始化（这里启动 StepService） */
    private void onActivityPermissionGranted() {
        // 这里启动 / 绑定你的 StepService（前台服务）
        Intent svc = new Intent(this, StepService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    // -------------------- 统一权限回调 --------------------

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_ACTIVITY_RECOGNITION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onActivityPermissionGranted();
            } else {
                Toast.makeText(this, "未授予活动识别权限，步数统计将不可用。", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQ_LOCATION) {
            boolean granted = false;
            for (int r : grantResults) granted |= (r == PackageManager.PERMISSION_GRANTED);
            if (!granted) {
                Toast.makeText(this, "未授予定位权限，无法获取当前位置。", Toast.LENGTH_LONG).show();
            } else if (hasApproximateOnly()) {
                promptEnablePreciseLocation();
            }
            return;
        }

        if (requestCode == REQ_BG_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "已获得后台定位权限。", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未授予后台定位权限，熄屏后可能无法持续更新里程/路线进度。", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQ_POST_NOTIF) {
            // 给没给不强制；不给可能导致前台服务通知被折叠/不显示
            return;
        }
    }

    @Override
    protected void onStop() {
        if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss(); // 防泄露
        }
        super.onStop();
    }
}
