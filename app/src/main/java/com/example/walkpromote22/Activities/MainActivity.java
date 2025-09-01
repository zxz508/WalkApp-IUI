// MainActivity.java
package com.example.walkpromote22.Activities;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.app.AlertDialog;
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
import com.example.walkpromote22.ProfileFragments.PersonalInfoFragment;

import com.example.walkpromote22.TodayFragments.StepService;
import com.example.walkpromote22.TodayFragments.TodayFragment;

import com.example.walkpromote22.ProfileFragments.ProfileFragment;
import com.example.walkpromote22.WalkFragments.WalkFragment;
import com.example.walkpromote22.R;
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

    // 创建各个页面的 Fragment

    private final WalkFragment walkFragment = new WalkFragment();
    private final TodayFragment todayFragmentGooglefitness = new TodayFragment();
    private final ProfileFragment profileFragment = new ProfileFragment();
    private final ChatbotFragment chatbotFragment = new ChatbotFragment();
    private Fragment currentFragment;
    private UserPreferences userPreferences;
    private String userKey;
    // 底部导航按钮
    AlertDialog exitDialog;

    private TextView navToday;
    private TextView navRunning;
    private TextView navProfile;
    private TextView navChatbot;
    private SharedPreferences sharedPreferences;
    private static final int REQ_ACTIVITY_RECOGNITION = 1001;
    // 使用单线程执行器进行后台操作
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapsInitializer.updatePrivacyShow(this,true,true);
        MapsInitializer.updatePrivacyAgree(this,true);
        // 检查登录状态
        if (!isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        } else {
            setContentView(R.layout.activity_home);
        }
        checkAndRequestActivityPermission();



        sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
        userKey = sharedPreferences.getString("USER_KEY", null);
        new StepSyncManager(this).importHistorySteps();



        // 初始化数据库（注意：不要在主线程执行查询）
        AppDatabase db = AppDatabase.getDatabase(this);
        UserPreferences prefs = new UserPreferences(this);

        UserDao userDao = db.userDao();


        RouteSyncManager.init(getApplicationContext());

        RouteSyncManager.syncFromCloudToLocal(userKey);


        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            String userKey = prefs.getUserKey();
            Log.e(TAG,"userkey="+userKey);
            User localUser = userDao.getUserByKey(userKey);
            // ✅ 如果 userKey 不存在，说明用户未登录或丢失数据
            if (localUser == null || userKey.isEmpty()) {
                runOnUiThread(() -> {
                    Log.e(TAG,"用户信息丢失，请重新登录");
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                });
                return;
            }

            Log.e(TAG,"11112222113");
            // ✅ userKey 存在，但本地数据库可能空 → 查询是否存在该用户

            if (localUser.hasNull()) {
                // ✅ 在这里手动构建 Retrofit（跟 login 一样）
                OkHttpClient okHttpClient = new OkHttpClient.Builder().build();

                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl("http://101.132.96.134:8080/") // 一定要有 /
                        .addConverterFactory(ScalarsConverterFactory.create())
                        .addConverterFactory(GsonConverterFactory.create())
                        .client(okHttpClient)
                        .build();

                ApiService apiService = retrofit.create(ApiService.class);

                try {
                    Response<UserDTO> resp = apiService.getUserByKey(userKey).execute();

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



        // 使用后台线程查询用户数据
        executorService.execute(() -> {
            // 这里执行数据库查询
            final User user = db.userDao().getUserByKey(userKey);
            final boolean exists = (user != null);

            // 回到主线程更新 UI
            // 回到主线程更新 UI
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) { // ✅ 防止 WindowLeaked
                    if (!exists || user.getWeight() == 0) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Tips")
                                .setMessage("You have not filled in the personal information, please fill in now？")
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

        });

        // 获取底部导航按钮

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

    public void updateNavigationSelection(int navButtonId, Fragment fragment) {
        // 更新按钮颜色
        Log.e("e","111");
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

    // 检查是否已登录
    private boolean isLoggedIn() {
        SharedPreferences sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
        return sharedPreferences.getBoolean("is_logged_in", false);
    }


    /**
     * 检查并在必要时申请 ACTIVITY_RECOGNITION 权限
     */
    private void checkAndRequestActivityPermission() {
        // Android 10(Q) 之前系统默认授予，不必管
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onActivityPermissionGranted();
            Log.e(TAG,"has permisson");
            return;
        }

        // 判断当前是否已授权
        int granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION);

        if (granted == PackageManager.PERMISSION_GRANTED) {
            // 已有权限，直接开始计步流程
            onActivityPermissionGranted();
            Log.e(TAG,"has permisson");
        } else {
            // 如有必要，先给出解释
            Log.e(TAG,"has no permisson");
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.ACTIVITY_RECOGNITION)) {
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
                // 直接申请
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                        REQ_ACTIVITY_RECOGNITION);
            }
        }
    }

    /**
     * 接收申请结果
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_ACTIVITY_RECOGNITION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户同意
                onActivityPermissionGranted();
            } else {
                // 用户拒绝，可做降级处理或提示
                Toast.makeText(this,
                        "没有活动识别权限，无法计步哦～", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 获得权限后的初始化逻辑（比如启动 StepService 或绑定 Provider）
     */
    private void onActivityPermissionGranted() {
        // TODO: 这里启动 / 绑定你的 StepService
        Intent svc = new Intent(this, StepService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    protected void onStop() {
        if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss();          // 防泄露
        }
        super.onStop();
    }


}
