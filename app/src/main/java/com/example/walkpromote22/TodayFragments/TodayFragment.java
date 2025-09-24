
package com.example.walkpromote22.TodayFragments;





import static android.content.Context.MODE_PRIVATE;


import static com.example.walkpromote22.Activities.MainActivity.isWeatherFetched;
import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.fetchPOIs;
import static com.example.walkpromote22.tool.MapTool.getCurrentLocation;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.CalendarView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.fragment.app.Fragment;


import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.Activities.MainActivity;
import com.example.walkpromote22.ChatbotFragments.ChatbotFragment;
import com.example.walkpromote22.R;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Step;
import com.example.walkpromote22.data.model.User;
import com.example.walkpromote22.tool.UserPreferences;
import com.example.walkpromote22.tool.WeatherTool;


import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class TodayFragment extends Fragment {



    private static final String TAG = "TodayFragment";
    private static final int TARGET_STEPS = 10000;

    private static final int RC_SIGN_IN = 1001;
    private static final int RC_FIT_PERM = 1002;
    private final float maxDistance = 3.0f;

    private SharedPreferences sharedPreferences;
    //private View progressContainer;

    //private ProgressBar progressBar;



    private TextView distanceTextView;
    private TextView caloriesBurnedTextView;
    private TextView stepTextView;

    private float userWeight = 70f;
    private AppDatabase appDatabase;
    private StepDao stepDao;
    private UserDao userDao;
    private String userKey;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private enum ProviderType { GOOGLE, SAMSUNG, HUAWEI, LOCAL, UNKNOWN }
    private ProviderType currentProvider = ProviderType.UNKNOWN;
    private ProgressBar progressBar;


    private static Activity mActivity = null;
    private static Context mContext = null;

    private UserPreferences userPref;
    //text
// ★ 新增：主线程 Handler

    private boolean stepFeatureEnabled = true;




    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View rootView;
        try {
            rootView = inflater.inflate(R.layout.fragment_today, container, false);
        } catch (Throwable t) {
            Log.e(TAG, "inflate fragment_today failed", t);
            // 无法加载布局只好直接抛给系统，但先尽力提示
            if (getContext() != null) {
                try { showErrorDialog(getContext(), "界面加载失败",
                        "无法加载 today 布局，请检查资源完整性。", t); } catch (Throwable ignore) {}
            }
            throw t;
        }

        try {
            // —— 安全获取上下文
            final Context ctx = getContext();
            final boolean canUseCtx = isAdded() && ctx != null;

            // —— 绑定视图并尽量判空
            try {
                distanceTextView = rootView.findViewById(R.id.distance_count_text);
                caloriesBurnedTextView = rootView.findViewById(R.id.calories_burned_text);
                stepTextView = rootView.findViewById(R.id.distance_text);

                progressBar = rootView.findViewById(R.id.progressBar);
                if (progressBar != null) {
                    progressBar.setMax(100);
                    try {
                        View wrapper = rootView.findViewById(R.id.progress_container);
                        if (wrapper != null) wrapper.bringToFront();
                        progressBar.bringToFront();
                    } catch (Throwable t) {
                        Log.w(TAG, "bringToFront failed", t);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "bind views failed", t);
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "界面初始化异常",
                            "部分控件初始化失败，相关展示将被禁用。", t); } catch (Throwable ignore) {}
                }
            }

            // —— DB/DAO 再次兜底（若 onCreate 中失败）
            try {
                if (appDatabase == null && canUseCtx) {
                    appDatabase = AppDatabase.getDatabase(ctx);
                }
                if (stepDao == null && appDatabase != null) stepDao = appDatabase.stepDao();
                if (userDao == null && appDatabase != null) userDao = appDatabase.userDao();
            } catch (Throwable t) {
                Log.e(TAG, "DB/DAO re-init failed", t);
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "数据库异常",
                            "访问数据库失败，历史与统计功能将受限。", t); } catch (Throwable ignore) {}
                }
            }

            // —— 读取 userKey 的兜底
            try {
                if (userKey == null) {
                    if (canUseCtx) {
                        SharedPreferences sp = ctx.getSharedPreferences("user_prefs", MODE_PRIVATE);
                        userKey = sp.getString("USER_KEY", "default_user");
                    } else {
                        userKey = "default_user";
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "read userKey from SharedPreferences failed", t);
                userKey = "default_user";
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "用户信息读取失败",
                            "已使用默认用户继续运行。", t); } catch (Throwable ignore) {}
                }
            }

            // —— 加载今天数据（UI 相关：切主线程）
            try {
                if (isAdded()) {
                    loadTodayData();
                }
            } catch (Throwable t) {
                Log.e(TAG, "loadTodayData failed", t);
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "加载今日数据失败",
                            "今日统计展示已临时禁用。", t); } catch (Throwable ignore) {}
                }
            }

            // —— 日历选择
            try {
                CalendarView calendarView = rootView.findViewById(R.id.calendar_view);
                if (calendarView != null) {
                    calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                            sdf.setTimeZone(TimeZone.getDefault());
                            Calendar cal = Calendar.getInstance();
                            cal.set(year, month, dayOfMonth);
                            String selectedDate = sdf.format(cal.getTime());
                            Log.e(TAG, "用户选择的日期：" + selectedDate);

                            // 这些方法可能触 UI，确保在主线程且 fragment 仍附着
                            if (isAdded()) {
                                try { loadDataFromDatabase(selectedDate); } catch (Throwable t1) {
                                    Log.e(TAG, "loadDataFromDatabase failed", t1);
                                    if (getContext() != null) {
                                        try { showErrorDialog(getContext(), "读取历史失败",
                                                "无法读取所选日期的数据。", t1); } catch (Throwable ignore) {}
                                    }
                                }
                                try { loadDataForDate(selectedDate); } catch (Throwable t2) {
                                    Log.e(TAG, "loadDataForDate failed", t2);
                                    if (getContext() != null) {
                                        try { showErrorDialog(getContext(), "加载展示失败",
                                                "无法展示所选日期的数据图表。", t2); } catch (Throwable ignore) {}
                                    }
                                }
                            }
                        } catch (Throwable tSel) {
                            Log.e(TAG, "onDateChange failed", tSel);
                            if (getContext() != null) {
                                try { showErrorDialog(getContext(), "日期选择异常",
                                        "无法解析或加载所选日期的数据。", tSel); } catch (Throwable ignore) {}
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                Log.e(TAG, "calendar init failed", t);
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "日历初始化失败",
                            "日历功能不可用。", t); } catch (Throwable ignore) {}
                }
            }

            // —— 确保用户信息存在（DB I/O）
            try {
                if (userDao != null && executorService != null) {
                    executorService.execute(() -> {
                        try {
                            User user = userDao.getUserByKey(userKey);
                            if (user == null) {
                                user = new User(userKey, "--", 0f, 0f, 0);
                                try { userDao.insert(user); } catch (Throwable tIns) {
                                    Log.e(TAG, "insert user failed", tIns);
                                }
                            }
                            userWeight = (user != null) ? user.getWeight() : 0f;

                            // 回主线程刷新
                            try {
                                Handler main = new Handler(Looper.getMainLooper());
                                main.post(() -> {
                                    try {
                                        if (!isAdded()) return;
                                        String todayStr = new SimpleDateFormat(
                                                "yyyy-MM-dd", Locale.getDefault()).format(new Date());
                                        try { loadDataFromDatabase(todayStr); } catch (Throwable tL) {
                                            Log.e(TAG, "loadDataFromDatabase(today) failed", tL);
                                            if (getContext() != null) {
                                                try { showErrorDialog(getContext(), "今日数据加载失败",
                                                        "部分统计展示已禁用。", tL); } catch (Throwable ignore) {}
                                            }
                                        }
                                    } catch (Throwable tPost) {
                                        Log.e(TAG, "post UI update failed", tPost);
                                    }
                                });
                            } catch (Throwable tMain) {
                                Log.e(TAG, "switch to main thread failed", tMain);
                            }
                        } catch (Throwable tDb) {
                            Log.e(TAG, "userDao get/insert failed", tDb);
                            if (getContext() != null) {
                                try { showErrorDialog(getContext(), "用户数据异常",
                                        "无法读取/写入用户信息，统计功能受限。", tDb); } catch (Throwable ignore) {}
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                Log.e(TAG, "executorService submit failed", t);
            }

            // —— 调 AMap 隐私接口（部分厂商机上若 context/进程状态异常会崩）
            try {
                if (canUseCtx) {
                    AMapLocationClient.updatePrivacyShow(ctx, true, true);
                    AMapLocationClient.updatePrivacyAgree(ctx, true);
                }
            } catch (Throwable t) {
                Log.e(TAG, "AMap privacy calls failed", t);
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "定位隐私初始化失败",
                            "高德定位隐私初始化失败，定位功能已禁用。", t); } catch (Throwable ignore) {}
                }
            }

            // —— 定位 + POI + 天气（放子线程，所有回调用 getContext() 判空；一旦异常即停止后续链路）
            try {
                new Thread(() -> {
                    try {
                        final Context c0 = getContext();
                        if (c0 == null || !isAdded()) return;

                        getCurrentLocation(true, c0, new ChatbotFragment.LocationCallback() {
                            @Override
                            public void onLocationReceived(LatLng location) throws Exception {
                                try {
                                    Context c1 = getContext();
                                    if (c1 == null || !isAdded()) return;

                                    // 保存位置
                                    try {
                                        SharedPreferences prefs = c1.getSharedPreferences("AppData", MODE_PRIVATE);
                                        prefs.edit()
                                                .putString("location_lat", String.valueOf(location.latitude))
                                                .putString("location_long", String.valueOf(location.longitude))
                                                .apply();
                                        Log.e("App Startup", "定位成功：" + location.latitude + ", " + location.longitude);
                                    } catch (Throwable t) {
                                        Log.e(TAG, "save location failed", t);
                                    }

                                    // 获取 POI
                                    try {
                                        fetchPOIs(c1, location, 5000);
                                    } catch (Throwable tPoi) {
                                        Log.e(TAG, "fetchPOIs failed", tPoi);
                                        try {
                                            showErrorDialog(c1, "POI 获取失败",
                                                    "附近兴趣点获取失败，相关推荐功能已禁用。", tPoi);
                                        } catch (Throwable ignore) {}
                                    }

                                    if(!isWeatherFetched) {
                                        isWeatherFetched=true;
                                        // 获取天气
                                        try {
                                            WeatherTool.getCityCodeFromLatLng(c1, location, new WeatherTool.CityCodeCallback() {
                                                @Override
                                                public void onCodeResolved(String cityCode) {
                                                    try {
                                                        final Context c2 = getContext();
                                                        if (c2 == null || !isAdded()) return;
                                                        WeatherTool.fetchWeatherWithCode(cityCode, new WeatherTool.WeatherCallback() {
                                                            @Override
                                                            public void onWeatherReceived(String weatherInfo) {
                                                                try {
                                                                    Context c3 = getContext();
                                                                    if (c3 == null || !isAdded())
                                                                        return;
                                                                    SharedPreferences prefs = c3.getSharedPreferences("AppData", MODE_PRIVATE);
                                                                    prefs.edit().putString("weather", weatherInfo).apply();
                                                                    Log.e("App Startup", "天气信息获取成功：" + weatherInfo);
                                                                } catch (Throwable t) {
                                                                    Log.e(TAG, "save weather failed", t);
                                                                }
                                                            }

                                                            @Override
                                                            public void onError(String errorMessage) {
                                                                Log.e("App Startup", "天气请求失败：" + errorMessage);
                                                                try {
                                                                    Context c3 = getContext();
                                                                    if (c3 != null && isAdded()) {
                                                                        showErrorDialog(c3, "天气获取失败",
                                                                                "天气接口返回错误：" + errorMessage, null);
                                                                    }
                                                                } catch (Throwable ignore) {
                                                                }
                                                            }
                                                        });
                                                    } catch (Throwable tW) {
                                                        Log.e(TAG, "fetchWeatherWithCode failed", tW);
                                                        Context c2 = getContext();
                                                        if (c2 != null && isAdded()) {
                                                            try {
                                                                showErrorDialog(c2, "天气解析失败",
                                                                        "解析城市编码/天气失败。", tW);
                                                            } catch (Throwable ignore) {
                                                            }
                                                        }
                                                    }
                                                }

                                                @Override
                                                public void onError(String message) {
                                                    Log.e("App Startup", "获取城市编码失败：" + message);
                                                    Context c2 = getContext();
                                                    if (c2 != null && isAdded()) {
                                                        try {
                                                            showErrorDialog(c2, "城市编码失败",
                                                                    "无法根据位置获取城市编码：" + message, null);
                                                        } catch (Throwable ignore) {
                                                        }
                                                    }
                                                }
                                            });
                                        } catch (Throwable tCode) {
                                            Log.e(TAG, "getCityCodeFromLatLng failed", tCode);
                                        }
                                    }

                                } catch (Throwable tAll) {
                                    Log.e(TAG, "onLocationReceived pipeline failed", tAll);
                                    Context c1 = getContext();
                                    if (c1 != null && isAdded()) {
                                        try { showErrorDialog(c1, "定位后处理异常",
                                                "定位成功但后续处理出错，相关功能已降级。", tAll); } catch (Throwable ignore) {}
                                    }
                                }
                            }

                            @Override
                            public void onLocationFailed(String error) {
                                Log.e("App Startup", "定位失败：" + error);
                                Context c1 = getContext();
                                if (c1 != null && isAdded()) {
                                    try { showErrorDialog(c1, "定位失败",
                                            "无法获取当前位置：" + error, null); } catch (Throwable ignore) {}
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.e("App Startup", "位置请求异常：" + t.getMessage(), t);
                        Context c = getContext();
                        if (c != null && isAdded()) {
                            try { showErrorDialog(c, "位置请求异常",
                                    "定位线程发生异常，定位功能已禁用。", t); } catch (Throwable ignore) {}
                        }
                    }
                }).start();
            } catch (Throwable t) {
                Log.e(TAG, "start location thread failed", t);
                if (canUseCtx) {
                    try { showErrorDialog(ctx, "定位初始化失败",
                            "无法启动定位线程，定位相关功能已禁用。", t); } catch (Throwable ignore) {}
                }
            }

            // —— 记录上下文检查（调试用）
            try {
                Log.e("err", "context=" + (canUseCtx ? ctx : "null or not added"));
            } catch (Throwable ignore) {}

        } catch (Throwable tOuter) {
            Log.e(TAG, "onCreateView fatal", tOuter);
            if (getContext() != null) {
                try { showErrorDialog(getContext(), "界面创建异常",
                        "onCreateView 发生未预期错误。", tOuter); } catch (Throwable ignore) {}
            }
        }

        return rootView;
    }

    // 放到同一个 Fragment 类里即可
    private void showErrorDialog(Context ctx, String title, String message, @Nullable Throwable t) {
        try {
            // 1) 兜底：上下文不可用时直接打日志返回
            if (ctx == null) {
                Log.e(TAG, title + " | " + message, t);
                return;
            }

            // 2) 组装可读的错误详情（含 Throwable 堆栈）
            StringBuilder sb = new StringBuilder();
            if (message != null) sb.append(message);
            if (t != null) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("Exception: ").append(t.getClass().getName());
                if (t.getMessage() != null) {
                    sb.append("\nMessage: ").append(t.getMessage());
                }
                // 堆栈
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                String stack = sw.toString();
                // 防止过长导致某些 ROM 渲染异常，截断到 4000 字符
                if (stack.length() > 4000) {
                    stack = stack.substring(0, 4000) + "\n... (truncated)";
                }
                sb.append("\n\nStacktrace:\n").append(stack);
            }
            final String detailText = sb.toString();

            // 3) 切回主线程显示对话框；若失败则降级 Toast
            Runnable showTask = () -> {
                try {
                    // 若 Fragment 已脱附或 Activity 正在结束，降级为 Toast
                    Activity act = null;
                    if (ctx instanceof Activity) {
                        act = (Activity) ctx;
                    }
                    if (act != null && (act.isFinishing() || (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()))) {
                        Toast.makeText(ctx, title + ": " + message, Toast.LENGTH_LONG).show();
                        Log.e(TAG, title + " | " + message, t);
                        return;
                    }

                    // 使用 AppCompat AlertDialog，避免主题问题
                    androidx.appcompat.app.AlertDialog.Builder builder =
                            new androidx.appcompat.app.AlertDialog.Builder(ctx);

                    builder.setTitle(title != null ? title : "错误")
                            .setMessage(detailText.length() == 0 ? "未知错误" : detailText)
                            .setCancelable(true)
                            .setPositiveButton("确定", (dialog, which) -> {
                                try { dialog.dismiss(); } catch (Throwable ignore) {}
                            })
                            .setNeutralButton("复制错误信息", (dialog, which) -> {
                                try {
                                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(ClipData.newPlainText("error_log", detailText));
                                        Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Throwable copyErr) {
                                    Log.e(TAG, "copy to clipboard failed", copyErr);
                                }
                            });

                    // 有些厂商机对超长消息的 TextView 渲染不稳，我们包一层可滚动容器
                    ScrollView scroll = new ScrollView(ctx);
                    TextView tv = new TextView(ctx);
                    int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
                    tv.setPadding(pad, pad, pad, pad);
                    tv.setTextIsSelectable(true);
                    tv.setText(detailText);
                    tv.setTextSize(14);
                    scroll.addView(tv);
                    builder.setView(scroll);

                    androidx.appcompat.app.AlertDialog dialog = builder.create();
                    // 防止某些窗口类型崩溃
                    if (act != null && !act.isFinishing()) {
                        dialog.show();
                    } else {
                        // 非 Activity 上下文或窗口不可用时，降级 Toast
                        Toast.makeText(ctx, title + ": " + message, Toast.LENGTH_LONG).show();
                        Log.e(TAG, title + " | " + message, t);
                    }
                } catch (Throwable showErr) {
                    // 最终兜底
                    Toast.makeText(ctx, title + ": " + message, Toast.LENGTH_LONG).show();
                    Log.e(TAG, "showErrorDialog show failed: " + title + " | " + message, showErr);
                    if (t != null) Log.e(TAG, "origin error:", t);
                }
            };

            if (Looper.myLooper() == Looper.getMainLooper()) {
                showTask.run();
            } else {
                try {
                    new Handler(Looper.getMainLooper()).post(showTask);
                } catch (Throwable hErr) {
                    // Handler 失败再兜底
                    Toast.makeText(ctx, title + ": " + message, Toast.LENGTH_LONG).show();
                    Log.e(TAG, "post to main failed", hErr);
                    Log.e(TAG, title + " | " + message, t);
                }
            }
        } catch (Throwable outer) {
            // 任何环节异常都不再让应用崩溃
            try {
                Log.e(TAG, "showErrorDialog outer failure", outer);
                if (ctx != null) {
                    Toast.makeText(ctx, title + ": " + message, Toast.LENGTH_LONG).show();
                }
            } catch (Throwable ignore) {}
        }
    }


    // 初始化 Google Fit 相关设置（如果有其他初始化操作，可在此处添加）





    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        // 其余业务逻辑（例如 loadTodayData() 等）可继续保持你原来的调用
    }




    @SuppressLint("SimpleDateFormat")
    private void loadTodayData() {

        /* —— 1. 生成今天日期字符串 "yyyy-MM-dd" —— */
        String todayStr = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date());



        /* —— 2. 后台线程读取数据库 —— */
        executorService.execute(() -> {

            Step rec = stepDao.getStepByDate(userKey, todayStr);
            final int    steps       = rec != null ? rec.getStepCount() : 0;
            final float  distanceKm  = rec != null ? rec.getDistance() : 0f;

            /* —— 3. 切回主线程刷新 UI —— */
            requireActivity().runOnUiThread(() -> {

                /* 步数 */
                stepTextView.setText(
                        String.format(Locale.getDefault(), "%,d", steps));

                /* 距离（km） */
                distanceTextView.setText(
                        String.format(Locale.getDefault(), "%.2f km", distanceKm));



                setProgressFromDistanceAndSteps(distanceKm, steps);


                /* 卡路里估算（示例公式） */
                double calories = steps * 0.04 * (userWeight / 70f);
                caloriesBurnedTextView.setText(
                        String.format(Locale.getDefault(), "%.2f calories", calories));


            });
        });
    }



    /** 读取日历选中的某一天数据（线程安全处理） */
    private void loadDataForDate(final String ymd) {



        /* —— 1. 后台线程读取数据库 —— */
        executorService.execute(() -> {

            Step rec = stepDao.getStepByDate(userKey, ymd);
            final int   steps      = rec != null ? rec.getStepCount() : 0;
            final float distanceKm = rec != null ? rec.getDistance()  : 0f;

            /* —— 2. 若本地无记录可在此处尝试网络同步（可选） —— */
            // TODO: 若需要网络拉取，调用 StepSyncManager.getLatest(...)

            /* —— 3. 更新 / 插入数据库（确保持久化） —— */
            if (rec == null) {
                rec = new Step(userKey, ymd, steps, distanceKm);
                stepDao.insertStep(rec);
            } else {
                rec.setStepCount(steps);
                rec.setDistance(distanceKm);
                stepDao.updateStep(rec);
            }

            /* —— 4. 回到主线程刷新 UI —— */
            requireActivity().runOnUiThread(() -> {
                updateUIWithStepsAndDistance(steps, distanceKm, ymd);

            });
        });
    }












    // 先从本地数据库加载指定日期数据，如果没有则从 Google Fit 获取数据




    // 先从数据库加载指定日期的数据
    private void loadDataFromDatabase(String selectedDate) {
        Log.e(TAG,"intodatabase");
        executorService.execute(() -> {
                    Step todayStep = stepDao.getStepByDate(userKey, selectedDate);
                    final int steps;
                    final float distance;
                    if (todayStep == null) {
                        todayStep = new Step(userKey, selectedDate, 0, 0f);
                        stepDao.insertStep(todayStep);
                        steps = 0;
                        distance = 0f;
                    } else {
                        steps = todayStep.getStepCount();
                        distance = todayStep.getDistance();
                    }
                    Log.e(TAG,"steps="+steps);
            updateUIWithStepsAndDistance(steps, distance,selectedDate);
        });
    }





    private void setProgressFromDistanceAndSteps(float distanceKm, int steps) {
        Log.e("PB-APPLY", "steps=" + steps + ", distKm=" + distanceKm);

        float dm = distanceKm;
        if (dm <= 0f && steps > 0) {
            SharedPreferences sp = requireContext().getSharedPreferences("user_prefs", MODE_PRIVATE);
            float strideM = sp.getFloat("strideMeters", 0.70f);
            dm = steps * strideM / 1000f; // km
        }

        float safeMax = (maxDistance > 0f) ? maxDistance : 3.0f;
        float ratio = dm / safeMax;
        if (Float.isNaN(ratio) || Float.isInfinite(ratio)) ratio = 0f;

        int percent = Math.max(0, Math.min(100, Math.round(ratio * 100f)));

        // 只有“有意义的数据”才更新进度；若两者都 0，就保持当前进度（不清零）
        if (dm > 0f || steps > 0) {
            setProgressSafely(percent);
        }
    }

    private void animateProgressTo(int targetPercent) {
        if (progressBar == null) return;

        int current = progressBar.getProgress();
        targetPercent = Math.max(0, Math.min(100, targetPercent));

        ObjectAnimator anim = ObjectAnimator.ofInt(progressBar, "progress", current, targetPercent);
        anim.setDuration(600); // 动画时长，可调整
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
    }
    private void setProgressSafely(int percent) {
        if (progressBar == null || !isAdded()) return;
        final int p = Math.max(0, Math.min(100, percent));

        // 切到 UI 线程更新，避免后台线程调用无效
        progressBar.post(() -> {
            try {
                // API 24+ 原生带动画；低版本走自定义属性动画
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(p, true);
                } else {
                    animateProgressTo(p); // 你已有的动画方法
                }
            } catch (Exception e) {
                // 兜底：直接设置
                progressBar.setIndeterminate(false);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(p);
            }
        });
    }




    // 更新 UI 并将数据存入数据库，注意此处使用传入的 date 参数，确保日期一致



    /**
     * 更新 UI 并把数据写入数据库 —— 已修正步数 / 距离对调和重复换算问题
     */
    private void updateUIWithStepsAndDistance(final int steps,
                                              final float distanceKm,
                                              final String date) {

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                /*--------- 1. UI 显示 ---------*/

                // 步数
                stepTextView.setText(String.format(Locale.getDefault(), "%,d", steps));

                // 距离（km）
                distanceTextView.setText(String.format(Locale.getDefault(),
                        "%.2f km", distanceKm));


                setProgressFromDistanceAndSteps(distanceKm, steps);

                // 卡路里（示例算法）
                double calories = steps * 0.04 * (userWeight / 70f);
                caloriesBurnedTextView.setText(String.format(Locale.getDefault(),
                        "%.2f calories", calories));
            });
        }

        /*--------- 2. 持久化 ---------*/
        executorService.execute(() -> {
            Step rec = stepDao.getStepByDate(userKey, date);

            if (rec == null) {
                rec = new Step(userKey, date, steps, distanceKm);
                stepDao.insertStep(rec);
            } else {
                rec.setStepCount(steps);
                rec.setDistance(distanceKm);
                stepDao.updateStep(rec);
            }
        });
    }





}




