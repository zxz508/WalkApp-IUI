package com.example.walkpromote22.WalkFragments;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
// imports（根据你项目补齐）

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.fetchPOIs;
import static com.example.walkpromote22.tool.MapTool.LocationToLatLng;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.example.walkpromote22.data.dao.PathDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Path;
import com.example.walkpromote22.R;
import com.example.walkpromote22.tool.MapTool;
import com.example.walkpromote22.tool.UserPreferences;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.util.concurrent.ScheduledExecutorService;

import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONArray;

public class WalkFragment extends Fragment {
    private long startTime;
    private boolean isWalking = false;
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable;
    private long totalTime = 0; // 单位秒

    private final ExecutorService executorService =
            Executors.newSingleThreadExecutor();
    private static final String TAG = "RunningFragment";

    // 统一使用成员变量来保存线程池和地图组件引用

    private MapTool mapTool;
    private Button toggleRunButton;

    // ==== SmartGuide runtime ====
    private ScheduledExecutorService sgExec;
    private volatile boolean sgRunning = false;
    private LatLng lastTickLoc = null;
    // ==== 缓存最近一次定位（来自 AMap 的 onMyLocationChange）====
    private LatLng sgLastLatLng = null;     // 当前经纬度
    private float sgLastBearing = Float.NaN; // 航向角（可选）


    // === SmartGuide 运行态 ===
              // 后台线程池（单线程足够）
    private final android.os.Handler sgHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Map<String, Marker> liveMarkers = new HashMap<>();

    // 位置缓存（来自 AMap 的 onMyLocationChange）
// WalkFragment 成员变量里加上：
    private com.example.walkpromote22.ChatbotFragments.ChatbotHelper chatbotHelper;

    // 地图上的动态标记缓存
    private String conversationHistoryArg; // 原始 JSON 字符串
    private List<Location> routeArg;  // Parcelable/Serializable均可，按你传入的来


    // 用户目标（你已经建好的 SmartGuide 类）
    @Nullable private SmartGuide smartGuide = null;


    private boolean halfwayEncouraged = false;           // 是否已在“过半”时鼓励过
    private double plannedRouteDistanceMeters = 0d;      //

    private double totalDistance = 0f;

    private boolean isRunning = false;
    private String userKey;

    private UserDao userDao;
    private PathDao pathDao;
    private Path currentPath; // 当前跑步记录
    private AppDatabase appDatabase;
    private Context context;

    // 容器：显示数据的 ScrollView 内的 LinearLayout 和地图容器
    private LinearLayout fitnessDataContainer;
    private FrameLayout mapContainer;
    private UserPreferences userPref;
    @SuppressLint("MissingInflatedId")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_walk, container, false);

        // 初始化传参
        Bundle args = getArguments();
        if (args != null) {
            try {
                routeArg = (List<Location>) args.getSerializable("route_points");
            }catch (Exception e){
                Log.e(TAG,"route is null");
            }
            Log.d(TAG, ", convHist.len=" + (conversationHistoryArg==null?0:conversationHistoryArg.length())
                    + ", routeSize=" + (routeArg==null?0:routeArg.size()));
        } else {
            Log.w(TAG, "No arguments passed to WalkFragment");
        }

        AMapLocationClient.updatePrivacyShow(requireContext(), true, true);
        AMapLocationClient.updatePrivacyAgree(requireContext(), true);

        if (routeArg != null && !routeArg.isEmpty()) {
            plannedRouteDistanceMeters = computeRouteDistanceMeters(routeArg);
            halfwayEncouraged = false;
        }
        // 获取地图容器
        mapContainer = view.findViewById(R.id.map_container);
        Log.d(TAG, "onCreateView, mapContainer=" + mapContainer);

        fitnessDataContainer=view.findViewById(R.id.fitness_data_container);
        Context appCtx = requireContext().getApplicationContext();
        context=appCtx;
        appDatabase = AppDatabase.getDatabase(appCtx);
        pathDao = appDatabase.pathDao();
        userDao=appDatabase.userDao();
        // 按钮：一开始就作为“结束”按钮
        toggleRunButton = view.findViewById(R.id.btn_toggle_run);
        if(routeArg!=null) {
            startRunning(routeArg);
            if (toggleRunButton != null) {
                toggleRunButton.setText("End Navigation"); // ✅ 初始就显示“结束”
                toggleRunButton.setOnClickListener(v -> {
                    stopRunning();  // ✅ 你已有的停止逻辑
                    Toast.makeText(getContext(), "Navigation Ended", Toast.LENGTH_SHORT).show();
                });
            }
        }
        if (mapContainer == null) {
            Log.e(TAG, "mapContainer is NULL! Check fragment_walk.xml");
        } else {
            Log.d(TAG, "mapContainer found: " + mapContainer);
        }
        return view;
    }

    @Nullable
    private AMap map() {
        try {
            if (mapTool != null && mapTool.getMapView() != null) {
                return mapTool.getMapView().getMap();
            }
        } catch (Throwable ignored) {}
        return null;
    }


    private void attachMyLocationListener() {
        if (mapTool == null || mapTool.getMapView() == null) {
            Log.e("TAG","当前地图工具仍为空");
            return;}
        AMap aMap = mapTool.getMapView().getMap();
        if (aMap == null) {
            Log.e("TAG","当前地图仍未空");
            return;}

        // 开启我的位置图层（如果 MapTool 没有已经开启的话）
        try { aMap.setMyLocationEnabled(true); } catch (Exception ignore) {}

        aMap.setOnMyLocationChangeListener(location -> {

            if (location == null) return; // android.location.Location
            sgLastLatLng = new LatLng(location.getLatitude(), location.getLongitude());
            sgLastBearing = location.hasBearing() ? location.getBearing() : Float.NaN;
            // 这里不要做重活（如网络请求），只做缓存即可
            checkHalfwayAndNotify();
            checkFinishAndNotify();
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("WalkFragment", "onCreate called");
    }

    /**
     * 开始跑步：
     * 1. 隐藏数据展示区域，显示地图容器
     * 2. 后台创建新的 Path 记录，并加载 MapContainerView 进行实时定位
     */
    @SuppressLint("SetTextI18n")
    private void startRunning(@Nullable List<Location> routeLocations) {
        isRunning = true;

        Log.e(TAG,"传入startRunning的路线size="+routeLocations.size());
        if (toggleRunButton != null) toggleRunButton.setText("Stop");
        Toast.makeText(getContext(), "Get moving", Toast.LENGTH_SHORT).show();

        if (fitnessDataContainer != null) fitnessDataContainer.setVisibility(View.GONE);
        if (mapContainer != null) mapContainer.setVisibility(View.VISIBLE);

        totalDistance = 0f;

        // --- userKey / DB / DAO 兜底 ---
        if (userKey == null || userKey.isEmpty()) {
            try {
                userKey = new UserPreferences(requireContext().getApplicationContext()).getUserKey();
            } catch (Exception ignore) {}
        }
        if (appDatabase == null) {
            appDatabase = AppDatabase.getDatabase(requireContext().getApplicationContext());
        }
        if (pathDao == null && appDatabase != null) {
            pathDao = appDatabase.pathDao();
        }
        if (pathDao == null || userKey == null || userKey.isEmpty()) {
            Log.e("RunningFragment", "startRunning: userKey is empty or pathDao null, abort to avoid NOT NULL violation.");
            Toast.makeText(getContext(), "User not ready or DB not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<Location> safeRoute =routeLocations;

        Log.e(TAG,"safeRoute.szie="+safeRoute.size());
        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                currentPath = new Path(userKey, startTime, 0, 0, 0, 0);
                long generatedId = pathDao.insertPath(currentPath);
                currentPath.setPathId(generatedId);
            } catch (Exception dbEx) {
                Log.e(TAG, "Insert Path failed: " + dbEx.getMessage(), dbEx);
                postShortToast("Save path failed");
                return;
            }

            requireActivity().runOnUiThread(() -> {
                try {
                    if (mapTool == null){
                        Log.e(TAG, "mapContainer is null in startRunning()!");
                        mapTool = new MapTool(getContext());
                    }
                    // 让 SurfaceView 铺满容器
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    mapTool.setLayoutParams(lp);

                    mapContainer.removeAllViews();
                    mapContainer.addView(mapTool);

                    // ★ 等布局稳定后再启动地图与导航，避免 rejecting buffer
                    mapContainer.post(() -> {
                        try {
                            // —— 修改开始：等待地图真正 loaded 再绘制路线（并加兜底定时）——
                            final com.amap.api.maps.AMap aMap = (mapTool != null) ? mapTool.getAMap() : null;
                            final java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean(false);

                            final Runnable drawAndStart = () -> {
                                if (!once.compareAndSet(false, true)) return; // 只执行一次
                                try {
                                    if (safeRoute.isEmpty()) {
                                        mapTool.startLocation(17f);
                                        Log.e(TAG,"输入凹startLocation的safeRoute.size="+safeRoute.size());
                                    } else {
                                        Log.e(TAG,"输入凹startLocation的safeRoute.size="+safeRoute.size());
                                        Log.e("TAG", "locationClient.startLocation 已调用");

                                        mapTool.startLocation(16f, safeRoute);

                                        attachMyLocationListener();  // ← 你原来的调用保留
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "draw/start failed: " + e.getMessage(), e);
                                }
                            };

                            if (aMap != null) {
                                aMap.setOnMapLoadedListener(new com.amap.api.maps.AMap.OnMapLoadedListener() {
                                    @Override public void onMapLoaded() {
                                        // 地图 GL/瓦片已就绪，安全绘制
                                        aMap.setOnMapLoadedListener(null);
                                        drawAndStart.run();
                                    }
                                });
                            }
                            // 兜底：若地图其实早已 loaded（监听不会触发），这条会在 400ms 后确保执行一次
                            mapContainer.postDelayed(drawAndStart, 400);
                            // —— 修改结束 ——

                        } catch (Exception e) {
                            Log.e(TAG, "Map init/start failed: " + e.getMessage(), e);
                            postShortToast("Map init failed");
                        }
                    });
                } catch (Exception uiEx) {
                    Log.e(TAG, "Map container setup failed: " + uiEx.getMessage(), uiEx);
                    postShortToast("Map view error");
                }
            });
        });
    }


    @SuppressLint("SetTextI18n")
    private void startRunning() {
        isRunning = true;

        if (toggleRunButton != null) toggleRunButton.setText("Stop");
        Toast.makeText(getContext(), "Get moving", Toast.LENGTH_SHORT).show();

        if (fitnessDataContainer != null) fitnessDataContainer.setVisibility(View.GONE);
        if (mapContainer != null) mapContainer.setVisibility(View.VISIBLE);

        totalDistance = 0f;

        // --- userKey / DB / DAO 兜底 ---
        if (userKey == null || userKey.isEmpty()) {
            try {
                userKey = new UserPreferences(requireContext().getApplicationContext()).getUserKey();
            } catch (Exception ignore) {}
        }
        if (appDatabase == null) {
            appDatabase = AppDatabase.getDatabase(requireContext().getApplicationContext());
        }
        if (pathDao == null && appDatabase != null) {
            pathDao = appDatabase.pathDao();
        }
        if (pathDao == null || userKey == null || userKey.isEmpty()) {
            Log.e("RunningFragment", "startRunning: userKey is empty or pathDao null, abort to avoid NOT NULL violation.");
            Toast.makeText(getContext(), "User not ready or DB not ready", Toast.LENGTH_SHORT).show();
            return;
        }


        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                currentPath = new Path(userKey, startTime, 0, 0, 0, 0);
                long generatedId = pathDao.insertPath(currentPath);
                currentPath.setPathId(generatedId);
            } catch (Exception dbEx) {
                Log.e(TAG, "Insert Path failed: " + dbEx.getMessage(), dbEx);
                postShortToast("Save path failed");
                return;
            }

            requireActivity().runOnUiThread(() -> {
                try {
                    if (mapTool == null){
                        Log.e(TAG, "mapContainer is null in startRunning()!");
                        mapTool = new MapTool(getContext());}
                    // 让 SurfaceView 铺满容器
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    mapTool.setLayoutParams(lp);

                    mapContainer.removeAllViews();
                    mapContainer.addView(mapTool);

                    // ★ 等布局稳定后再启动地图与导航，避免 rejecting buffer
                    mapContainer.post(() -> {
                        try {
                           // 如果 MapTool 内部有 SurfaceHolder 回调更好：在 surfaceCreated 里再 drawRoute

                            mapTool.startLocation(18f);

                            attachMyLocationListener();  // ← 加这一句

                        } catch (Exception e) {
                            Log.e(TAG, "Map init/start failed: " + e.getMessage(), e);
                            postShortToast("Map init failed");
                        }
                    });
                } catch (Exception uiEx) {
                    Log.e(TAG, "Map container setup failed: " + uiEx.getMessage(), uiEx);
                    postShortToast("Map view error");
                }
            });
        });
    }


    /** 小工具：安全在主线程弹 Toast */
    private void postShortToast(String msg) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show()
        );
    }


    /**
     * 结束跑步：
     * 1. 更新当前 Path 的结束时间
     * 2. 截图保存轨迹图，并将图片路径保存到 Path 中
     * 3. 移除 MapContainerView并隐藏地图容器
     * 4. 恢复显示数据展示区域
     * 5. 将最新跑步数据以卡片形式添加到数据容器最上方，同时显示轨迹图
     */
    @SuppressLint("SetTextI18n")
    private void stopRunning() {
        if (!isAdded()) return;

        isRunning = false;
        toggleRunButton.setText("Launch");
        Toast.makeText(getContext(), "End", Toast.LENGTH_SHORT).show();

        // 先尝试停止所有可能的周期任务（若不存在这些 handler，不会报错）


        // 先尽力停定位/监听，避免截图期间还在刷新地图
        try {
            if (mapTool != null) {

                try { if (mapTool.getAMap() != null) mapTool.getAMap().setMyLocationEnabled(false); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}

        // 保存总里程（保底）
        try { totalDistance = (mapTool != null ? mapTool.getTotalDistance() : totalDistance); } catch (Throwable ignore) {}

        executorService.execute(() -> {
            try {
                if (currentPath != null) {
                    long endTime = System.currentTimeMillis();
                    currentPath.setEndTimestamp(endTime);
                    pathDao.updatePath(currentPath);
                }
            } catch (Throwable dbEx) {
                Log.w(TAG, "updatePath on stop failed: " + dbEx.getMessage(), dbEx);
            }

            requireActivity().runOnUiThread(() -> {
                if (mapTool != null && mapTool.getAMap() != null) {
                    // 截图（成功或失败都进入 cleanup）
                    mapTool.getAMap().getMapScreenShot(new AMap.OnMapScreenShotListener() {
                        private void handle(Bitmap bitmap, int status) {
                            try {
                                if (bitmap != null && (status == 0 || status == 1)) {
                                    String imagePath = saveBitmapToFile(bitmap);
                                    if (imagePath != null && currentPath != null) {
                                        executorService.execute(() -> {
                                            try {
                                                currentPath.setRouteImagePath(imagePath);
                                                pathDao.updatePath(currentPath);
                                            } catch (Throwable e) {
                                                Log.w(TAG, "save screenshot path failed: " + e.getMessage(), e);
                                            }
                                        });
                                    }
                                } else {
                                    Toast.makeText(getContext(), "截图失败", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable e) {
                                Log.w(TAG, "handle screenshot error: " + e.getMessage(), e);
                            } finally {
                                cleanupMapAfterScreenshot();
                            }
                        }
                        @Override public void onMapScreenShot(Bitmap bitmap) { handle(bitmap, 0); }
                        @Override public void onMapScreenShot(Bitmap bitmap, int status) { handle(bitmap, status); }
                    });
                } else {
                    cleanupMapAfterScreenshot();
                }
            });
        });
    }

    /**
     * 在截图完成后清理地图视图并恢复其他 UI 状态
     */
    private void cleanupMapAfterScreenshot() {
        if (mapTool != null) {
            mapTool.onDestroy();
            mapContainer.removeAllViews();
            mapTool = null;
        }
        fitnessDataContainer.setVisibility(View.VISIBLE);
        mapContainer.setVisibility(View.GONE);
        final double[] calory = {0};
        calculateCalories(new CaloriesCallback() {
            @Override
            public void onCaloriesCalculated(double calories) {
                calory[0] =calories;

            }
        });
        //pathDao.updatePath();
        executorService.execute(() ->{
            currentPath.setAverageSpeed(calculatePace());
            currentPath.setDistance(totalDistance/1000);
            currentPath.setCalories(calory[0]);
            pathDao.updatePath(currentPath);
        });
        addPathCard(currentPath);
    }


    /**
     * 根据 Path 数据生成卡片视图，并将其插入到 fitnessDataContainer 顶部
     * 如果该 Path 有保存的轨迹图，则在卡片下方显示图片
     */
    @SuppressLint("SetTextI18n")
    private void addPathCard(Path path) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View cardView = inflater.inflate(R.layout.item_path_data, fitnessDataContainer, false);

        TextView tvPathInfo = cardView.findViewById(R.id.tv_path_info);
        TextView tvPathTime = cardView.findViewById(R.id.tv_path_time);
        TextView tvPathPace = cardView.findViewById(R.id.tv_path_pace);
        TextView tvPathCalories = cardView.findViewById(R.id.tv_path_calories);
        TextView tvPathDistance = cardView.findViewById(R.id.tv_path_distance);
        Button btnDelete = cardView.findViewById(R.id.btn_delete);  // 获取删除按钮

        // 设置按钮点击事件来删除路径
        btnDelete.setOnClickListener(v -> {
            // 删除数据库中的对应路径数据
            executorService.execute(() -> {
                pathDao.deletePath(path);  // 从数据库删除
                requireActivity().runOnUiThread(() -> {
                    // 删除UI上的卡片
                    fitnessDataContainer.removeView(cardView);
                    Toast.makeText(getContext(), "Path deleted", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // 计算时间（秒转小时：分钟：秒）
        long time = (path.getEndTimestamp() - path.getStartTimestamp()) / 1000;
        int hours = (int) (time / 3600);
        int minutes = (int) ((time % 3600) / 60);
        int seconds = (int) (time % 60);
        String formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

        // 设置其他TextView的内容
        tvPathInfo.setText("Record: " + path.getPathId());
        tvPathTime.setText("Time: " + formattedTime);
        tvPathPace.setText("Average speed: " + (path.getAverageSpeed() > 0 ? String.format(Locale.getDefault(), "%.2f km/min", path.getAverageSpeed()) : "--"));
        tvPathCalories.setText("Calories: " + (path.getCalories() > 0 ? String.format(Locale.getDefault(), "%.2f calories", path.getCalories()) : "--"));
        tvPathDistance.setText("Distance: " + (path.getDistance() > 0 ? String.format(Locale.getDefault(), "%.2f m", path.getDistance()) : "--"));

        // 如果该 Path 有保存的轨迹图，则在卡片下方显示图片
        if (path.getRouteImagePath() != null && !path.getRouteImagePath().isEmpty()) {
            ImageView routeImageView = new ImageView(getContext());
            Bitmap bitmap = BitmapFactory.decodeFile(path.getRouteImagePath());
            if (bitmap != null) {
                routeImageView.setImageBitmap(bitmap);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                routeImageView.setLayoutParams(params);
                LinearLayout imageContainer = cardView.findViewById(R.id.image_container);
                if (imageContainer != null) {
                    imageContainer.addView(routeImageView);
                }
            }
        }

        // 将卡片添加到 fitnessDataContainer 中
        // 在添加之前检查 fitnessDataContainer 是否已包含该卡片
        // 设置每个卡片的唯一标识
        cardView.setTag(path.getPathId());

// 检查是否已经添加过该卡片
        if (fitnessDataContainer.findViewWithTag(path.getPathId()) == null) {
            fitnessDataContainer.addView(cardView, 0);  // 只有未添加的路径才会加入
        }


    }

    public void attachSmartGuide(org.json.JSONArray userInputs) {
        // 当前位置（可能此时还没定位好，允许为空）


        // 只用两份数据构造 SmartGuide
        smartGuide = new SmartGuide(userInputs, routeArg);

        sgRunning=true;
        setupSmartGuideBridge();
        // 开启定时 tick（WalkFragment 内管理位置与循环）
        stopSmartGuideTicker();
        startSmartGuideTicker();
    }

    private SmartGuide.ActionSink sgSink;
    private com.amap.api.maps.model.LatLng lastFix = null;
    private static final long SG_TICK_MS = 40000L;

    private final Runnable sgTickRunnable = new Runnable() {
        @Override public void run() {
            try {
                Log.e("TAG","sgRunning="+sgRunning);
                if (!sgRunning) return;

                final SmartGuide sg = smartGuide;
                final LatLng loc = sgLastLatLng;
                if (sg == null || sgSink == null || loc == null) {
                    // 没就绪就下次再试
                    sgHandler.postDelayed(this, SG_TICK_MS);
                    return;
                }

                // —— 关键：放后台线程，避免主线程网络异常 ——
                executorService.execute(() -> {
                    try {
                        // 1) 后台线程：拉 POI（网络+计算）
                        sg.updatePoiList(context,loc);
                    } catch (Throwable t) {
                        Log.e(TAG, "updatePoiList failed (bg)", t);
                    }

                    try {
                        // 2) 后台线程：运行 SmartGuide 逻辑（可能还会联网）
                        sg.processTick(loc, null, sgSink,executorService);
                    } catch (Throwable t) {
                        Log.e(TAG, "processTick failed (bg)", t);
                    }
                });

            } finally {
                if (sgRunning) {
                    sgHandler.postDelayed(this, SG_TICK_MS);
                }
            }
        }
    };




    private void startSmartGuideTicker() {
        sgHandler.removeCallbacks(sgTickRunnable);
        sgHandler.post(sgTickRunnable);//测试用！！！！！！！！！！
        sgHandler.postDelayed(sgTickRunnable, SG_TICK_MS); // 需要立刻触发就改为 post(sgTickRunnable)
    }
    private void stopSmartGuideTicker() {
        sgHandler.removeCallbacks(sgTickRunnable);
    }
    private final java.util.List<com.amap.api.maps.model.Marker> sgMarkers = new java.util.ArrayList<>();
    private void setupSmartGuideBridge() {
        sgSink = new SmartGuide.ActionSink() {
            @Override
            public void onAddMarker(double lat, double lng) {
                runOnUiThreadX(() -> {
                    AMap m = map();
                    if (m == null) return;
                    com.amap.api.maps.model.Marker mk = m.addMarker(
                            new com.amap.api.maps.model.MarkerOptions()
                                    .position(new com.amap.api.maps.model.LatLng(lat, lng))
                                    .title("SmartGuide")
                                    .snippet(String.format("%.6f, %.6f", lat, lng))
                                    .anchor(0.5f, 1.0f)
                    );
                    sgMarkers.add(mk);
                });
            }

            @Override
            public void onClearAllMarkers() {
                runOnUiThreadX(() -> {
                    for (com.amap.api.maps.model.Marker mk : sgMarkers) {
                        try { mk.remove(); } catch (Throwable ignored) {}
                    }
                    sgMarkers.clear();
                });
            }

            @Override
            public void onClearMarker(double lat, double lng) {
                runOnUiThreadX(() -> {
                    AMap m = map();
                    if (m == null) return;
                    final double EPS = 1e-6;
                    java.util.Iterator<com.amap.api.maps.model.Marker> it = sgMarkers.iterator();
                    while (it.hasNext()) {
                        com.amap.api.maps.model.Marker mk = it.next();
                        com.amap.api.maps.model.LatLng p = mk.getPosition();
                        if (Math.abs(p.latitude - lat) < EPS && Math.abs(p.longitude - lng) < EPS) {
                            try { mk.remove(); } catch (Throwable ignored) {}
                            it.remove();
                            break;
                        }
                    }
                });
            }

            @Override
            public void onAddText(String text, double lat, double lng) {
                runOnUiThreadX(() -> addMessageOnMap(text, lat, lng));
            }

            @Override
            public void onAddChatMessage(String text) {
                sendMessageToChat(text);
            }
        };

        // 🔑 别忘了真正把 sink 注入给 SmartGuide
        smartGuide.setActionSink(sgSink);
    }

    private void sendMessageToChat(String text) {
        try {
            androidx.fragment.app.Fragment parent = getParentFragment();
            if (parent == null) return;

            // 先尝试 addchatMessage(String)
            try {
                java.lang.reflect.Method m = parent.getClass().getMethod("addchatMessage", String.class);
                m.invoke(parent, text);
                return;
            } catch (NoSuchMethodException ignore) {
                // 再尝试 addChatMessage(String)
                java.lang.reflect.Method m2 = parent.getClass().getMethod("addChatMessage", String.class);
                m2.invoke(parent, text);
            }
        } catch (Throwable e) {
            android.util.Log.w("WalkFragment", "sendMessageToChat failed", e);
        }
    }

    // ====== 你已有的 UI 线程执行器（若没有，可以用下面的一行替代）======
    private void runOnUiThreadX(Runnable r) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(r);
    }










    private void addMessageOnMap(@androidx.annotation.Nullable String text, double lat, double lng) {
        requireActivity().runOnUiThread(() -> {
            // 1) 聊天区输出
            String msg = (text == null ? "" : text.trim());


            // 2) 坐标兜底：不带坐标的 {Add_Text:message} 用当前定位
            LatLng pos = null;
            if (lat == 0d && lng == 0d) {
                if (sgLastLatLng != null) pos = sgLastLatLng;
            } else {
                pos = new LatLng(lat, lng);
            }
            if (pos == null) return; // 没有可用坐标就只显示文本

            // 3) 地图准备好？
            if (mapTool == null || mapTool.getMapView() == null) return;
            AMap aMap = mapTool.getMapView().getMap();
            if (aMap == null) return;

            // 4) 添加/更新 marker（title 用文本）
            Marker mk = addOrUpdateMarker(aMap, pos, msg.isEmpty() ? "Info" : msg);

            // 5) 展示 InfoWindow & 轻推相机
            try { mk.showInfoWindow(); } catch (Exception ignore) {}

        });
    }

    /** 在地图上按坐标添加或更新一个 marker，并登记到 liveMarkers */
    private Marker addOrUpdateMarker(AMap aMap, LatLng pos, String title) {
        String key = String.format(Locale.ROOT, "%.6f,%.6f", pos.latitude, pos.longitude);
        Marker mk = liveMarkers.get(key);
        if (mk == null) {
            mk = aMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(title == null ? "" : title)
                    .anchor(0.5f, 1.0f)
                    .zIndex(3000));
            liveMarkers.put(key, mk);
        } else {
            // 刷新标题与位置
            mk.setTitle(title == null ? "" : title);
            mk.setPosition(pos);
        }
        return mk;
    }


    private void checkHalfwayAndNotify() {
        if (plannedRouteDistanceMeters <= 0) return;
        if (halfwayEncouraged) return;
        if (totalDistance >= plannedRouteDistanceMeters * 0.5) {
            halfwayEncouraged = true;

            String payload = buildHalfwayPayload(
                    totalDistance,
                    plannedRouteDistanceMeters,
                    sgLastLatLng /* 你已有的最后位置对象 */
            );

            android.os.Bundle b = new android.os.Bundle();
            b.putString("payload", payload);
            getParentFragmentManager().setFragmentResult("APP_TOOL_EVENT", b);
        }
    }
    // 用于设置终点事件的函数
    public void checkFinishAndNotify() {
        if (plannedRouteDistanceMeters <= 0) return;
        stopRunning();

        // 检查用户是否接近终点（可以设定一个距离阈值）
        if (totalDistance >= plannedRouteDistanceMeters * 0.99) {  // 例如：当接近95%的距离时触发
            if (!isWalking) return; // 如果已经停止，则不再触发

            isWalking = false; // 停止计时器
            long endTime = System.currentTimeMillis();
            long duration = (endTime - startTime) / 1000; // 计算总时间，单位秒

            // 将数据传递给父Fragment
            String payload = buildFinishPayload(totalDistance, duration);

            android.os.Bundle b = new android.os.Bundle();
            b.putString("payload", payload);
            getParentFragmentManager().setFragmentResult("APP_TOOL_EVENT", b);

            Toast.makeText(getContext(), "Walk completed!", Toast.LENGTH_SHORT).show();
        }
    }
    // 生成结束事件的 payload 数据
    private String buildFinishPayload(double walked, long duration) {
        long w = Math.round(walked), t = Math.round(plannedRouteDistanceMeters);
        return "[APP_EVENT] WALK_FINISHED\n"
                + "walked_m=" + w + ", total_m=" + t
                + "\nTotal time: " + formatTime(duration)
                + "\nThe user has completed the walk!";
    }

    // 格式化时间为时:分:秒
    private String formatTime(long durationSeconds) {
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    // 在开始走路时启动计时器
    private void startWalking() {
        isWalking = true;
        startTime = System.currentTimeMillis();
        totalTime = 0;

        // 每秒更新一次
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isWalking) {
                    totalTime = (System.currentTimeMillis() - startTime) / 1000;  // 计算已步行时间
                    // 可以在这里更新 UI，显示步行时间等
                    Log.d("WalkFragment", "Walk time: " + formatTime(totalTime));
                    timerHandler.postDelayed(this, 1000);  // 每秒调用一次
                }
            }
        };

        timerHandler.post(timerRunnable);  // 启动计时器
    }

    // 停止计时器（例如：在结束时调用）
    private void stopWalking() {
        isWalking = false;
        timerHandler.removeCallbacks(timerRunnable);  // 停止计时器
    }



    // 生成中途事件的 payload 数据
    private String buildHalfwayPayload(double walked, double total, Object lastLatLng) {
        long w = Math.round(walked), t = Math.round(total);
        return "[APP_EVENT] HALF_WAY_REACHED\n"
                + "walked_m=" + w + ", total_m=" + t
                + (lastLatLng != null ? (", last=" + String.valueOf(lastLatLng)) : "")
                + "\nThe user is already halfway there, so you might want to give them some encouragement.";
    }


    // 事件负载（工具风格）：既让模型知道“状态”，也加上“只输出鼓励、禁止令牌”的限制






    // 示例方法：计算卡路里
    public interface CaloriesCallback {
        void onCaloriesCalculated(double calories);
    }

    // 计算热量消耗（单位：卡路里）
    public void calculateCalories(CaloriesCallback callback) {
        executorService.execute(() -> {
            try {


                long time= calculateDuration();
                double speed = totalDistance*3.6 /time;//time单位秒，距离单位是米
                // 获取用户体重
                float weight = userDao.getUserByKey(userKey).getWeight();
                double met;
                if (speed < 3.0) {//这里要求的是km/h
                    met = 2.5; // 慢速步行
                } else if (speed >= 3.0 && speed < 5.0) {
                    met = 3.8; // 中等步行
                } else {
                    met = 5.0; // 快速步行
                }
                // 计算热量消耗
                double calories = met * weight * time;
                // 计算完成后，通过回调传递结果
                requireActivity().runOnUiThread(() -> callback.onCaloriesCalculated(calories));
            } catch (Exception e) {

                requireActivity().runOnUiThread(() -> callback.onCaloriesCalculated(0));
            }
        });
    }



    // 示例方法：计算跑步距离


    // 示例方法：计算配速（分钟/公里）
    private double calculatePace() {
        double durationMinutes = calculateDuration() / 60.0;
        double distance = totalDistance;
        return distance > 0 ? durationMinutes / distance : 0.0;
    }

    // 计算跑步持续时间（秒）
    private long calculateDuration() {
        if (currentPath != null && currentPath.getEndTimestamp() > currentPath.getStartTimestamp()) {
            return (currentPath.getEndTimestamp() - currentPath.getStartTimestamp()) / 1000;
        }
        return 0;
    }


    /**
     * 将 Bitmap 保存为 PNG 文件，并返回保存的文件路径
     */
    private String saveBitmapToFile(Bitmap bitmap) {
        File storageDir = requireContext().getExternalFilesDir(null);
        String fileName = "running_path_" + System.currentTimeMillis() + ".png";
        File file = new File(storageDir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            Toast.makeText(getContext(), "轨迹图已保存到：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return file.getAbsolutePath();
        } catch (Exception e) {

            Toast.makeText(getContext(), "保存轨迹图失败", Toast.LENGTH_SHORT).show();
        }
        return null;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");

        if (mapTool != null) {
            Log.d(TAG, "mapTool.onResume() call");
            mapTool.onResume();
        } else {
            Log.w(TAG, "mapTool is null in onResume()");
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause()");
        if (mapTool != null) {
            Log.d(TAG, "mapTool.onPause() call");
            mapTool.onPause();
        }
    }

    @Override
    public void onDestroyView() {
        // 先清理地图再调父类，避免 NPE（你日志里出现过 removeAllViews 的 NPE）
        if (mapTool != null) {
            try {
                mapTool.onDestroy();
            } catch (Exception e) {
                Log.e(TAG, "mapTool.onDestroy() failed", e);
            } finally {
                if (mapContainer != null) {
                    try { mapContainer.removeAllViews(); } catch (Throwable ignore) {}
                }
                mapTool = null;
            }
        }
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated(), root=" + view);

        // 立刻尝试创建（不等待尺寸，不等待测量）
        createMapIfNeeded("onViewCreated-immediate");

        // 再安排一次 post（避免某些机型 attach 时序问题）
        view.post(() -> createMapIfNeeded("onViewCreated-post"));
    }
    private void createMapIfNeeded(String caller) {
        try {
            if (mapContainer == null) {
                View v = getView();
                if (v != null) mapContainer = v.findViewById(R.id.map_container);
            }
            Log.d(TAG, "createMapIfNeeded@" + caller + " mapContainer=" + mapContainer);

            if (mapContainer == null) {
                Log.e(TAG, "createMapIfNeeded@" + caller + " mapContainer is NULL");
                return;
            }

            if (mapTool == null) {
                Log.d(TAG, "createMapIfNeeded@" + caller + " -> new MapTool()");
                mapTool = new MapTool(getContext());

                // 🚩 关键顺序：先 onCreate，再把 mapTool 加到父容器（与 ChatbotFragment 一致）


                // attach 到容器
                mapContainer.removeAllViews();
                mapContainer.addView(mapTool);
                mapContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override public void onGlobalLayout() {
                                if (mapContainer.getWidth() > 0 && mapContainer.getHeight() > 0) {
                                    mapContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                    try {
                                        mapTool.onResume(); // 再保险唤醒一次渲染
                                        // 如果需要，定位或 animateCamera
                                    } catch (Throwable t) {
                                        Log.e(TAG, "post-resume failed", t);
                                    }
                                    Log.d(TAG, "map ready: "+mapContainer.getWidth()+"x"+mapContainer.getHeight());
                                }
                            }
                        }
                );


                // 再 onResume，确保渲染
                try {
                    Log.d(TAG, "createMapIfNeeded@" + caller + " mapTool.onResume()");
                    mapTool.onResume();
                } catch (Throwable t) {
                    Log.e(TAG, "mapTool.onResume failed", t);
                }

                mapContainer.post(() -> Log.d(
                        TAG,
                        "after add mapTool: container size = " +
                                mapContainer.getWidth() + "x" + mapContainer.getHeight()
                ));
            } else {
                Log.d(TAG, "createMapIfNeeded@" + caller + " mapTool already exists");
            }
        } catch (Throwable t) {
            Log.e(TAG, "createMapIfNeeded@" + caller + " failed", t);
        }
    }
    // 计算一条路线的总长度（米）
    private static double computeRouteDistanceMeters(java.util.List<com.example.walkpromote22.data.model.Location> pts) {
        if (pts == null || pts.size() < 2) return 0d;
        double sum = 0d;
        for (int i = 1; i < pts.size(); i++) {
            double lat1 = pts.get(i - 1).getLatitude();
            double lon1 = pts.get(i - 1).getLongitude();
            double lat2 = pts.get(i).getLatitude();
            double lon2 = pts.get(i).getLongitude();
            sum += distMeters(lat1, lon1, lat2, lon2);
        }
        return sum;
    }

    // 两点间球面距离（米）——用系统封装，避免哈弗辛误差实现
    private static double distMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] res = new float[1];
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, res);
        return res[0];
    }



}
