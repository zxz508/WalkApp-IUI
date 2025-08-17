
package com.example.walkpromote22.TodayFragments;





import static android.content.Context.MODE_PRIVATE;


import static com.example.walkpromote22.tool.MapTool.getCurrentLocation;

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
import android.widget.CalendarView;
import android.widget.ProgressBar;
import android.widget.TextView;

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
    private final int maxDistance = 3000;

    private SharedPreferences sharedPreferences;
    private View progressContainer;

    private ProgressBar progressBar;



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


    private static Activity mActivity = null;
    private static Context mContext = null;

    private UserPreferences userPref;
    //text
// ★ 新增：主线程 Handler
    private final Handler mainH = new Handler(Looper.getMainLooper());



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        userPref = new UserPreferences(requireContext());
        String savedProv = userPref.getStepProvider();

        userKey=userPref.getUserKey();
        // ❶ 仅第一次进入时探测并写入偏好；之后直接复用
                    // 后续 → 直接用






        // 数据库
        appDatabase     = AppDatabase.getDatabase(requireContext());
        stepDao = appDatabase.stepDao();
        userDao = appDatabase.userDao();
    }



    private boolean loginOnce = false;


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {


        View rootView = inflater.inflate(R.layout.fragment_today, container, false);

        progressContainer = rootView.findViewById(R.id.progress_container);
        progressBar = rootView.findViewById(R.id.progressBar);
        distanceTextView = rootView.findViewById(R.id.distance_count_text);
        caloriesBurnedTextView = rootView.findViewById(R.id.calories_burned_text);
        stepTextView = rootView.findViewById(R.id.distance_text);


        appDatabase = AppDatabase.getDatabase(getContext());
        stepDao = appDatabase.stepDao();
        userDao = appDatabase.userDao();
        SharedPreferences sp = requireContext().getSharedPreferences("user_prefs", MODE_PRIVATE);
        userKey = sp.getString("USER_KEY", "default_user");


        loadTodayData();

        CalendarView calendarView = rootView.findViewById(R.id.calendar_view);
        calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getDefault());
            Calendar cal = Calendar.getInstance();
            cal.set(year, month, dayOfMonth);
            String selectedDate = sdf.format(cal.getTime());
            Log.e(TAG, "用户选择的日期：" + selectedDate);
            loadDataFromDatabase(selectedDate);
            loadDataForDate(selectedDate);
        });




 //---------- 5. 确保用户信息存在并回显 (db I/O) ----------


        executorService.execute(() -> {
            User user = userDao.getUserByKey(userKey);
            if (user == null) {                          // 新用户 → 插一条默认
                user = new User(userKey, "--", 0f, 0f, 0);
                userDao.insert(user);
            }
            userWeight = user.getWeight();

            // 同步 UI
            String todayStr = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.getDefault()).format(new Date());


            loadDataFromDatabase(todayStr);

        });

        Log.e("err","context="+requireContext());

        AMapLocationClient.updatePrivacyShow(requireContext(), true, true);
        AMapLocationClient.updatePrivacyAgree(requireContext(), true);



        final LatLng[] userLocation = new LatLng[1];
        final String[] weather = new String[1];

        // false表面调用固定测试地址（西交利物浦大学）
        try {
            getCurrentLocation(false,requireContext(), new ChatbotFragment.LocationCallback() {
                @Override
                public void onLocationReceived(LatLng location) {
                    SharedPreferences prefs = requireContext().getSharedPreferences("AppData", MODE_PRIVATE);
                    prefs.edit().putString("location_lat", String.valueOf(location.latitude)).apply();
                    prefs.edit().putString("location_long", String.valueOf(location.longitude)).apply();
                    userLocation[0] = location;
                    Log.e("App Startup", "定位成功：" + location.latitude + ", " + location.longitude);

                    // 请求天气
                    WeatherTool.getCityCodeFromLatLng(requireContext(), userLocation[0], new WeatherTool.CityCodeCallback() {
                        @Override
                        public void onCodeResolved(String cityCode) {
                            WeatherTool.fetchWeatherWithCode(cityCode, new WeatherTool.WeatherCallback() {
                                @Override
                                public void onWeatherReceived(String weatherInfo) {
                                    weather[0] = weatherInfo;
                                    // 存天气
                                    SharedPreferences prefs = requireContext().getSharedPreferences("AppData", MODE_PRIVATE);
                                    prefs.edit().putString("weather", weather[0]).apply();
                                    Log.e("App Startup", "天气信息获取成功：" + weather[0]);
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    Log.e("App Startup", "天气请求失败：" + errorMessage);
                                }
                            });
                        }

                        @Override
                        public void onError(String message) {
                            Log.e("App Startup", "获取城市编码失败：" + message);
                        }
                    });
                }

                @Override
                public void onLocationFailed(String error) {
                    Log.e("App Startup", "定位失败：" + error);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        return rootView;
    }







     // 初始化 Google Fit 相关设置（如果有其他初始化操作，可在此处添加）



    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);





    }

    @SuppressLint("SimpleDateFormat")
    private void loadTodayData() {

        /* —— 1. 生成今天日期字符串 "yyyy-MM-dd" —— */
        String todayStr = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date());

        progressBar.setVisibility(View.VISIBLE);

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

                /* 进度条 (目标 3 km，可改 maxDistance) */
                float ratio = distanceKm / maxDistance;   // maxDistance = 3.0 km
                int progress = Math.min(100, (int) (ratio * 100));
                progressBar.setProgress(progress);

                /* 卡路里估算（示例公式） */
                double calories = steps * 0.04 * (userWeight / 70f);
                caloriesBurnedTextView.setText(
                        String.format(Locale.getDefault(), "%.2f calories", calories));

                progressBar.setVisibility(View.GONE);
            });
        });
    }



    /** 读取日历选中的某一天数据（线程安全处理） */
    private void loadDataForDate(final String ymd) {

        progressBar.setVisibility(View.VISIBLE);

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
                progressBar.setVisibility(View.GONE);
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

                // 进度条：目标 3 km，可按需调整
                float ratio = distanceKm / maxDistance;      // maxDistance = 3 (km)
                int progressPercent = Math.min(100, (int) (ratio * 100));
                progressBar.setProgress(progressPercent);

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




