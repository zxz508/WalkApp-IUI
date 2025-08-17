package com.example.walkpromote22.Activities;



import static android.content.ContentValues.TAG;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.walkpromote22.R;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.dto.UserDTO;
import com.example.walkpromote22.data.model.User;
import com.example.walkpromote22.service.ApiService;
import com.example.walkpromote22.tool.LoginCallback;
import com.example.walkpromote22.tool.Notification;
import com.example.walkpromote22.tool.UserPreferences;

import java.io.IOException;
import java.util.Objects;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    /* ---------- UI ---------- */
    private ViewSwitcher switcher;
    private EditText etUser, etPwd;          // 登录
    private EditText etRegUser, etRegPwd;    // 注册
    private Button btnLogin, btnRegister;
    private TextView tvToRegister, tvToLogin;

    /* ---------- 网络 & 本地持久化 ---------- */
    private ApiService apiService;
    private UserPreferences userPreferences;
    AppDatabase db ;



    /* ---------- 定位权限 ---------- */
    private ActivityResultLauncher<String> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        db = AppDatabase.getDatabase(getApplicationContext());
        bindViews();
        initPrefsAndApi();
        initListeners();
        initPermissionLauncher();
    }

    /* -------------------------------- 绑定视图 -------------------------------- */
    private void bindViews() {
        switcher = findViewById(R.id.viewSwitcher);

        etUser = findViewById(R.id.username);
        etPwd = findViewById(R.id.password);

        etRegUser = findViewById(R.id.regUsername);
        etRegPwd = findViewById(R.id.regPassword);

        btnLogin = findViewById(R.id.login_button);
        btnRegister = findViewById(R.id.register_button);

        tvToRegister = findViewById(R.id.register_switch);
        tvToLogin = findViewById(R.id.login_switch);
    }

    /* --------------------------- Retrofit + Prefs ----------------------------- */
    private void initPrefsAndApi() {

        userPreferences = new UserPreferences(this);          // 你自己的封装

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(
                msg -> Log.d("OkHttp", msg));
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient okHttpClient = new OkHttpClient.Builder()   // ← 统一用这个名字
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://101.132.96.134:8080/")     // 末尾 /
                .addConverterFactory(ScalarsConverterFactory.create()) // 解析 String 响应 (login/register OK body)
                .addConverterFactory(GsonConverterFactory.create())    // 序列化 UserDTO & 解析 JSON
                .client(okHttpClient)
                .build();


        apiService = retrofit.create(ApiService.class);
    }

    /* ------------------------------- 事件监听 ---------------------------------- */
    private void initListeners() {

        /* 登录按钮 */
        btnLogin.setOnClickListener(v -> {
            String username = etUser.getText().toString().trim();
            String password = etPwd.getText().toString().trim();
            if (username.isEmpty() || password.isEmpty()) {
                toast("Please fill in all fields");
                return;
            }
            login(username, password);
        });

        /* 注册按钮 */
        btnRegister.setOnClickListener(v -> {
            String username = etRegUser.getText().toString().trim();
            String password = etRegPwd.getText().toString().trim();
            if (username.isEmpty() || password.isEmpty()) {
                toast("Please fill in all fields");
                return;
            }
            register(username, password);
        });

        /* 登录 → 注册切换 */
        tvToRegister.setOnClickListener(v -> switcher.showNext());

        /* 注册 → 登录切换 */
        tvToLogin.setOnClickListener(v -> switcher.showPrevious());
    }

    /* -------------------------------- 注册逻辑 -------------------------------- */
    private void register(String username, String password) {

        // 1. 生成或获取本地 userKey
        String userKey = userPreferences.getUserKey();
        if (userKey == null) {
            userKey = UUID.randomUUID().toString();
            userPreferences.saveUserKey(userKey);
        }

        // 2. 调接口
        UserDTO dto = new UserDTO(userKey, username, password);
        apiService.register(dto).enqueue(new Callback<String>() {
            @Override public void onResponse(Call<String> c, Response<String> r) {
                if (r.isSuccessful()) {
                    showCenterToast("Register success! Please log in");
                    switcher.showPrevious();
                    etUser.setText(username);
                } else {
                    String msg = "Error " + r.code();                 // 先给默认
                    try {
                        if (r.errorBody() != null && r.errorBody().contentLength() != 0) {
                            msg = r.errorBody().string();
                        }
                    } catch (IOException ignored) {}

                    showCenterToast(msg);                             // 一定非空
                }
            }
            @Override public void onFailure(Call<String> c, Throwable t) {
                showCenterToast("Network error: " + t.getMessage());
            }
        });
    }

    private void showCenterToast(@NonNull String text) {
        if (text.trim().isEmpty()) text = "Unknown error";            // 防空串
        Toast t = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

    /* -------------------------------- 登录逻辑 -------------------------------- */
    private void login(String username, String password) {

        UserDTO dto = new UserDTO(null, username, password);
        apiService.login(dto).enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> c, @NonNull Response<String> r) {
                if (r.isSuccessful() && r.body() != null) {
                    String newKey = r.body();
                    userPreferences.saveUserKey(newKey);      // 覆盖或保存 key

                    User user = new User(newKey);
                    new Thread(() -> {
                        db.userDao().insert(user);
                    }).start();


                    saveLoginStatus(true, username);
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                } else {
                    toast(getErr(r));
                }
            }

            @Override
            public void onFailure(Call<String> c, Throwable t) {
                toast("Network error: " + t.getMessage());
            }
        });
    }

    /* --------------------------- 权限申请（原逻辑） --------------------------- */
    private void initPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) showPermissionDeniedDialog();
                });

        if (!hasLocationPermissions()) {
            requestLocationPermissions();
        }
    }

    private boolean hasLocationPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermissions() {
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
    }



    /* ----------------------------- 工具方法 ------------------------------ */
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String getErr(Response<?> r) {
        try {
            return r.errorBody() != null ? r.errorBody().string() : "Unknown error";
        } catch (IOException e) {
            return "Unknown error";
        }
    }

    /* ---------------------- 你原先的 saveLoginStatus -------------------- */



    private void saveLoginStatus(boolean isLoggedIn, String username) {
        SharedPreferences sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("is_logged_in", isLoggedIn);
        editor.putString("username", username);  // 可以保存用户名或其他信息
        editor.apply();
    }


    /**
     * 请求ACCESS_FINE_LOCATION权限
     */

    public void showPermissionDeniedDialog() {
        // 使用自定义布局加载对话框
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_fullscreen_permission, null);

        // 创建AlertDialog并应用自定义样式
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        // 获取对话框中的视图组件
        TextView dialogMessage = dialogView.findViewById(R.id.dialog_message);
        Button buttonSettings = dialogView.findViewById(R.id.dialog_button_settings);
        Button buttonExit = dialogView.findViewById(R.id.dialog_button_exit);

        // 设置按钮点击事件
        buttonSettings.setOnClickListener(v -> {
            dialog.dismiss();
            // 引导用户前往应用设置页面
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        });

        buttonExit.setOnClickListener(v -> {
            dialog.dismiss();
            // 退出应用
            finishAffinity();
        });

        // 显示对话框
        dialog.show();
    }
}


