package com.example.walkpromote22.WalkFragments;

import static com.example.walkpromote22.ChatbotFragments.ChatbotFragment.conversationHistory;
import static com.example.walkpromote22.ChatbotFragments.SummaryAgent.generateSummaryTextOrFallback;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.AMap;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
// imports（根据你项目补齐）

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.example.walkpromote22.Activities.MainActivity;
import com.example.walkpromote22.ChatbotFragments.ChatbotResponseListener;
import com.example.walkpromote22.Manager.PathSyncManager;
import com.example.walkpromote22.data.dao.PathDao;
import com.example.walkpromote22.data.dao.PathPointDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Path;
import com.example.walkpromote22.R;
import com.example.walkpromote22.data.model.PathPoint;
import com.example.walkpromote22.tool.MapTool;
import com.example.walkpromote22.tool.UserPreferences;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONException;
import org.json.JSONObject;

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

    private List<Location> routeArg;  // Parcelable/Serializable均可，按你传入的来


    // 用户目标（你已经建好的 SmartGuide 类）
    @Nullable private AccompanyAgent accompanyAgent = null;
// WalkFragment 字段区


    private static final AtomicBoolean pendingTiredHint = new AtomicBoolean(false);


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
    private ScrollView scrollViewPaths;

    private long routeId;

    private PathSyncManager pathSyncManager;
    private final ExecutorService io = Executors.newSingleThreadExecutor();


    // 容器：显示数据的 ScrollView 内的 LinearLayout 和地图容器
    private LinearLayout fitnessContainer;
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
                routeId=(long)args.getSerializable("routeId");

            }catch (Exception e){
                Log.e(TAG,"route is null");
            }

        } else {
            Log.w(TAG, "No arguments passed to WalkFragment");
        }

        scrollViewPaths  = view.findViewById(R.id.scrollView_paths);
        AMapLocationClient.updatePrivacyShow(requireContext(), true, true);
        AMapLocationClient.updatePrivacyAgree(requireContext(), true);
        pathSyncManager = new PathSyncManager(requireContext().getApplicationContext());
        if (routeArg != null && !routeArg.isEmpty()) {
            plannedRouteDistanceMeters = computeRouteDistanceMeters(routeArg);
            halfwayEncouraged = false;
        }
        // 获取地图容器
        mapContainer = view.findViewById(R.id.map_container);
        Log.d(TAG, "onCreateView, mapContainer=" + mapContainer);

        fitnessContainer =view.findViewById(R.id.fitness_data_container);
        Context appCtx = requireContext().getApplicationContext();
        context=appCtx;
        appDatabase = AppDatabase.getDatabase(appCtx);
        pathDao = appDatabase.pathDao();
        userDao=appDatabase.userDao();
        // 按钮：一开始就作为“结束”按钮
        toggleRunButton = view.findViewById(R.id.btn_toggle_run);
        toggleRunButton.setVisibility(View.GONE);
        if (routeArg != null && !routeArg.isEmpty()) {
            plannedRouteDistanceMeters = computeRouteDistanceMeters(routeArg);
            halfwayEncouraged = false;

            // ✅ 延迟启动导航逻辑：等 mapContainer 完成布局后再执行
            mapContainer.post(() -> {
                Log.d(TAG, "✅ post-delayed startRunning: route size = " + routeArg.size());
                startRunning(routeArg);
            });
        }
        if (mapContainer == null) {
            Log.e(TAG, "mapContainer is NULL! Check fragment_walk.xml");
        } else {
            Log.d(TAG, "mapContainer found: " + mapContainer);
        }
        showHistoryList(true);
        renderHistory();  // ⬅️ 一进来就把历史渲染出来
        return view;
    }

    // ===== 核心：用 PathDao + PathPointDao 拉取并可视化（逐条 addPathCard）=====
    private void renderHistory() {

        if (fitnessContainer == null) return;

        Log.e(TAG,"渲染历史");
        ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        exec.execute(() -> {
            List<Path> paths = new ArrayList<>();
            try {
                AppDatabase db = AppDatabase.getDatabase(requireContext());
                PathDao pathDao = db.pathDao();
                PathPointDao pointDao = db.pathPointDao();

                UserPreferences pref = new UserPreferences(requireContext());
                String userKey = pref.getUserKey();

                // 取用户所有路径，按开始时间降序；可按需限制数量
                final int LIMIT = 30;
                List<Path> all = pathDao.getPathsByUserKey(userKey);
                if (all != null && !all.isEmpty()) {
                    int n = Math.min(LIMIT, all.size());
                    for (int i = 0; i < n; i++) {
                        Path p = all.get(i);

                        // —— 仅当缺省时才补齐（尽量少触库/少计算）——
                        boolean needUpdate = false;
                        long   startTs = p.getStartTimestamp();
                        long   endTs   = p.getEndTimestamp();
                        double distanceMeters = p.getDistance();        // 这里假设存的是“米”；若你存的是“公里”，把计算和判断相应调整

                        if (endTs <= 0 || distanceMeters <= 0 ) {
                            List<PathPoint> pts = pointDao.getPathPointsByPathId(p.getPathId());
                            if (pts != null && !pts.isEmpty()) {
                                if (endTs <= 0) {
                                    endTs = pts.get(pts.size() - 1).getTimestamp();
                                    p.setEndTimestamp(endTs);
                                    needUpdate = true;
                                }


                            }
                        }
                        if (needUpdate) {
                            try { pathDao.updatePath(p); } catch (Throwable ignore) {}
                        }
                        paths.add(p);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.e("WalkFragment", "加载路径失败", t);
            }

            // UI 渲染：清空后逐条 addPathCard(path)
            main.post(() -> {
                try { fitnessContainer.removeAllViews(); } catch (Throwable ignore) {}

                if (paths == null || paths.isEmpty()) {
                    // 空状态：加“空卡片”，隐藏“Get moving”按钮
                    addEmptyCard("还没有步行记录",
                            "开始一次短途步行，这里会自动展示你的路线与统计。");

                    showHistoryList(true); // 保证显示列表区
                    return;
                }

                // 有历史：逐条渲染，并显示按钮
                for (Path p : paths) {
                    try {
                        addPathCard(p); // 你现成的方法：addPathCard(Path)
                    } catch (Throwable e) {
                        android.util.Log.e("WalkFragment", "addPathCard 失败 pathId=" + p.getPathId(), e);
                    }
                }

            });
        });
    }

    private void addEmptyCard(@NonNull String title, @NonNull String subtitle) {
        Log.e(TAG,"空白卡");
        // 用 CardView 简单做一张占位卡
        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(requireContext());
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(8);
        lp.setMargins(m, m, m, m);
        card.setLayoutParams(lp);
        card.setUseCompatPadding(true);
        card.setPreventCornerOverlap(true);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        TextView t1 = new TextView(requireContext());
        t1.setText(title);
        t1.setTextSize(16f);
        t1.setTypeface(Typeface.DEFAULT_BOLD);

        TextView t2 = new TextView(requireContext());
        t2.setText(subtitle);
        t2.setTextSize(14f);
        t2.setTextColor(0xFF666666);

        box.addView(t1);
        box.addView(t2);
        card.addView(box);

        fitnessContainer.addView(card);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    private void showHistoryList(boolean showHistory) {
        if (scrollViewPaths != null) scrollViewPaths.setVisibility(showHistory ? View.VISIBLE : View.GONE);
        if (mapContainer     != null) mapContainer.setVisibility(showHistory ? View.GONE    : View.VISIBLE);
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
            checkTiredAndNotify(sgLastLatLng);
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
        showHistoryList(false);

        for (Location loc : routeLocations) {
            Log.e(TAG, "route点: " + loc.getLatitude() + ", " + loc.getLongitude());
        }

        // 在 TodayFragment / 你的导航入口处：
        ((MainActivity) requireActivity()).ensureBackgroundLocationIfNeeded();

        Log.e(TAG, "传入startRunning的路线size=" + routeLocations.size());

        Toast.makeText(getContext(), "Get moving", Toast.LENGTH_SHORT).show();

        if (fitnessContainer != null) fitnessContainer.setVisibility(View.GONE);
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

        final List<Location> safeRoute = routeLocations;

        Log.e(TAG, "safeRoute.size=" + safeRoute.size());

        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                currentPath = new Path(userKey, routeId, "", startTime, 0, 0, "");
                pathDao.insertPath(currentPath);
            } catch (Exception dbEx) {
                Log.e(TAG, "Insert Path failed: " + dbEx.getMessage(), dbEx);
                postShortToast("Save path failed");
                return;
            }

            requireActivity().runOnUiThread(() -> {
                try {
                    // 初始化 mapTool
                    if (mapTool == null) {
                        Log.e(TAG, "mapTool is null, initializing mapTool...");
                        mapTool = new MapTool(getContext());
                    }

                    // 让 mapTool 占满整个容器
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    mapTool.setLayoutParams(lp);

                    mapContainer.removeAllViews();
                    mapContainer.addView(mapTool);

                    // 等待地图加载完成后再开始绘制路线
                    mapContainer.post(() -> {
                        try {
                            // 确保 aMap 已加载
                            final com.amap.api.maps.AMap aMap = (mapTool != null) ? mapTool.getAMap() : null;
                            final java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean(false);

                            // 绘制并启动导航的操作
                            final Runnable drawAndStart = () -> {
                                if (!once.compareAndSet(false, true)) return; // 只执行一次
                                try {
                                    if (safeRoute.isEmpty()) {
                                        mapTool.startLocation(17f);
                                        Log.e(TAG, "输入凹startLocation的safeRoute.size=" + safeRoute.size());
                                    } else {
                                        Log.e(TAG, "输入凹startLocation的safeRoute.size=" + safeRoute.size());
                                        Log.e("TAG", "locationClient.startLocation 已调用");

                                        mapTool.startLocation(16f, safeRoute);  // 绘制路线

                                        attachMyLocationListener();  // 保持原有的位置信息监听
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "draw/start failed: " + e.getMessage(), e);
                                }
                            };

                            // 当地图加载完成后，才开始绘制路线
                            if (aMap != null) {
                                aMap.setOnMapLoadedListener(new com.amap.api.maps.AMap.OnMapLoadedListener() {
                                    @Override public void onMapLoaded() {
                                        // 地图 GL/瓦片已就绪，开始绘制
                                        aMap.setOnMapLoadedListener(null);
                                        drawAndStart.run();
                                    }
                                });
                            }

                            // 兜底：如果地图已加载，则延时 400ms 执行绘制操作
                            mapContainer.postDelayed(drawAndStart, 400);
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

        Toast.makeText(getContext(), "End", Toast.LENGTH_SHORT).show();

        try {
            if (mapTool != null && mapTool.getAMap() != null) {
                mapTool.getAMap().setMyLocationEnabled(false);
            }
        } catch (Throwable ignore) {}

        try {
            totalDistance = (mapTool != null ? mapTool.getTotalDistance() : totalDistance);
        } catch (Throwable ignore) {}

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

            // 封装截图逻辑（供 GPT 回调中调用）
            Runnable screenshotAndUpload = () -> requireActivity().runOnUiThread(() -> {
                if (mapTool != null && mapTool.getAMap() != null) {
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
                                            } finally {
                                                uploadPathAndPoints(); // ⬅️ ★ 最终上传动作
                                            }
                                        });
                                    } else {
                                        executorService.execute(this::uploadPathAndPoints);
                                    }
                                } else {
                                    Toast.makeText(getContext(), "截图失败", Toast.LENGTH_SHORT).show();
                                    executorService.execute(this::uploadPathAndPoints);
                                }
                            } catch (Throwable e) {
                                Log.w(TAG, "handle screenshot error: " + e.getMessage(), e);
                                executorService.execute(this::uploadPathAndPoints);
                            } finally {
                                cleanupMapAfterScreenshot();
                            }
                        }

                        private void uploadPathAndPoints() {
                            if (currentPath == null) return;
                            try {
                                PathSyncManager psm = new PathSyncManager(requireContext().getApplicationContext());
                                psm.uploadPath(currentPath);
                                psm.uploadAllPointsOf(currentPath.getPathId());
                            } catch (Throwable t) {
                                Log.w(TAG, "upload path/points failed: " + t.getMessage(), t);
                            }
                        }

                        @Override public void onMapScreenShot(Bitmap bitmap) { handle(bitmap, 0); }
                        @Override public void onMapScreenShot(Bitmap bitmap, int status) { handle(bitmap, status); }
                    });
                } else {
                    cleanupMapAfterScreenshot();
                    executorService.execute(() -> {
                        try {
                            PathSyncManager psm = new PathSyncManager(requireContext().getApplicationContext());
                            psm.uploadPath(currentPath);
                            psm.uploadAllPointsOf(currentPath.getPathId());
                        } catch (Throwable t) {
                            Log.w(TAG, "upload path/points failed (no map): " + t.getMessage(), t);
                        }
                    });
                }
            });

            // 💡关键：GPT 生成 summary 后再注入到 path，然后再调用上传截图逻辑
            generateSummaryTextOrFallback(conversationHistory, new ChatbotResponseListener() {
                @Override
                public void onResponse(String reply) throws JSONException {
                    if (currentPath != null) {
                        currentPath.setSummary(reply);
                        try {
                            pathDao.updatePath(currentPath);
                        } catch (Throwable t) {
                            Log.w(TAG, "Failed to save summary: " + t.getMessage(), t);
                        }
                    }
                    screenshotAndUpload.run(); // ✅ 最终上传入口
                }

                @Override
                public void onFailure(String error) throws JSONException {
                    Log.e("TAG", error);
                    if (currentPath != null) {
                        currentPath.setSummary("Walk completed. Summary unavailable.");
                    }
                    screenshotAndUpload.run(); // 即使失败也要继续上传
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
        fitnessContainer.setVisibility(View.VISIBLE);
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
            currentPath.setDistance(totalDistance/1000);

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
        Log.e(TAG,"添加卡片");
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View cardView = inflater.inflate(R.layout.item_path_data, fitnessContainer, false);

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
                    fitnessContainer.removeView(cardView);
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
        tvPathPace.setText("Average speed: " + (getAverageSpeed(path) > 0 ? String.format(Locale.getDefault(), "%.2f km/min", getAverageSpeed(path)) : "--"));
        tvPathCalories.setText("Calories: " + (getCalories(path) > 0 ? String.format(Locale.getDefault(), "%.2f calories", getCalories(path)) : "--"));
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
        if (fitnessContainer.findViewWithTag(path.getPathId()) == null) {
            fitnessContainer.addView(cardView, 0);  // 只有未添加的路径才会加入
        }


    }

    private double getCalories(Path path) {
        return 0;
    }

    private double getAverageSpeed(Path path) {
        return 0;
    }

    public void attachSmartGuide(org.json.JSONArray userInputs) {
        // 当前位置（可能此时还没定位好，允许为空）


        // 只用两份数据构造 SmartGuide
        accompanyAgent = new AccompanyAgent(userInputs, routeArg);

        sgRunning=true;
        setupSmartGuideBridge();
        // 开启定时 tick（WalkFragment 内管理位置与循环）
        stopSmartGuideTicker();
        startSmartGuideTicker();
    }

    private AccompanyAgent.ActionSink sgSink;
    private com.amap.api.maps.model.LatLng lastFix = null;
    private static final long SG_TICK_MS = 20000L;

    private final Runnable sgTickRunnable = new Runnable() {
        @Override public void run() {
            try {
                Log.e("TAG","sgRunning="+sgRunning);
                if (!sgRunning) return;

                final AccompanyAgent sg = accompanyAgent;
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
                        sg.processTick(loc, null, sgSink,executorService,context);
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
       // sgHandler.post(sgTickRunnable);//测试用！！！！！！！！！！
        sgHandler.postDelayed(sgTickRunnable, SG_TICK_MS); // 需要立刻触发就改为 post(sgTickRunnable)
    }
    private void stopSmartGuideTicker() {
        sgHandler.removeCallbacks(sgTickRunnable);
    }
    private final java.util.List<com.amap.api.maps.model.Marker> sgMarkers = new java.util.ArrayList<>();
    private void setupSmartGuideBridge() {
        sgSink = new AccompanyAgent.ActionSink() {
            @Override
            public void onAddMarker(@Nullable String poiName,double lat, double lng) {
                runOnUiThreadX(() -> {
                    AMap m = map();
                    if (m == null) return;

                    String title = (poiName == null || poiName.trim().isEmpty()) ? "SmartGuide" : poiName.trim();

                    com.amap.api.maps.model.Marker mk = m.addMarker(
                            new com.amap.api.maps.model.MarkerOptions()
                                    .position(new com.amap.api.maps.model.LatLng(lat, lng))
                                    .title(title) // 用 POI 名称做标题
                                    .snippet(String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng))
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
        };

        // 🔑 别忘了真正把 sink 注入给 SmartGuide
        accompanyAgent.setActionSink(sgSink);
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


        // 检查用户是否接近终点（可以设定一个距离阈值）
        if (totalDistance >= plannedRouteDistanceMeters * 0.99) {  // 例如：当接近95%的距离时触发
            if (!isWalking) return; // 如果已经停止，则不再触发
            stopRunning();
            isWalking = false; // 停止计时器
            long endTime = System.currentTimeMillis();
            long duration = (endTime - startTime) / 1000; // 计算总时间，单位秒

            // 将数据传递给父Fragment
            String payload = buildFinishPayload(totalDistance, duration);

            android.os.Bundle b = new android.os.Bundle();
            b.putString("payload", payload);
            getParentFragmentManager().setFragmentResult("APP_TOOL_EVENT", b);//由chatbotFragment的fragmentresult监听直接注入conversationHist

            Toast.makeText(getContext(), "Walk completed!", Toast.LENGTH_SHORT).show();
        }
    }
    // 生成结束事件的 payload 数据

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
    private String buildFinishPayload(double walked, long duration) {
        long w = Math.round(walked), t = Math.round(plannedRouteDistanceMeters);
        return "[APP_EVENT] WALK_FINISHED\n"
                + "walked_m=" + w + ", total_m=" + t
                + "\nTotal time: " + formatTime(duration)
                + "\nThe user has completed the walk!" +
                "You can ask user whether he is willing to upload the record to the social media, if yes , respond with {Media_API} to do so";
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

        // 延迟等待 mapContainer 布局完成后再初始化
        if (mapContainer != null) {
            mapContainer.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                int w = mapContainer.getWidth();
                int h = mapContainer.getHeight();
                Log.d(TAG, "mapContainer layout ready: " + w + "x" + h);

                if (w > 0 && h > 0) {
                    // 只有在尺寸有效时才初始化 mapTool
                    if (mapTool == null) {
                        createMapIfNeeded();  // 确保创建 mapTool 和地图
                    }
                }
            });
        }
    }




    private void createMapIfNeeded() {
        try {
            if (mapContainer == null) {
                View v = getView();
                if (v != null) mapContainer = v.findViewById(R.id.map_container);
            }
            Log.d(TAG, "createMapIfNeeded@" +" mapContainer=" + mapContainer);

            if (mapContainer == null) {
                Log.e(TAG, "createMapIfNeeded@" + " mapContainer is NULL");
                return;
            }

            if (mapTool == null) {
                Log.d(TAG, "createMapIfNeeded@" +  " -> new MapTool()");
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
                    Log.d(TAG, "createMapIfNeeded@" +  " mapTool.onResume()");
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
                Log.d(TAG, "createMapIfNeeded@" + " mapTool already exists");
            }
        } catch (Throwable t) {
            Log.e(TAG, "createMapIfNeeded@" +" failed", t);
        }
    }
    // 计算一条路线的总长度（米）
    public static double computeRouteDistanceMeters(java.util.List<com.example.walkpromote22.data.model.Location> pts) {
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


    // 放在 WalkFragment 类内部（字段区）
    /** ——【疲劳检测参数，可按需调整】—— */
    private static final long TIRED_WINDOW_MS = 2 * 60_000L;          // 观察窗口：2 分钟
    private static final float TIRED_DISTANCE_THRESHOLD_M = 20f;      // 总位移阈值：20 米
    private static final long TIRED_NOTIFY_COOLDOWN_MS = 10 * 60_000L;// 冷却时间：10 分钟，避免频繁提醒
    private static final int  TIRED_MIN_SAMPLES = 6;                  // 至少采样次数，避免抖动误判

    /** ——【内部状态】—— */
    private final Deque<LocSample> tiredWindow = new ArrayDeque<>();
    private long lastTiredNotifyAt = 0L;


    /** 可选：如果你已有 ChatbotFragment 实例，也可以直接在这里持有引用而不用回调 */
// private ChatbotFragment chatbotFragment;

    private static final class LocSample {
        final long t;          // monotonic 时间
        final LatLng latLng;
        LocSample(long t, LatLng ll) { this.t = t; this.latLng = ll; }
    }

    // 放在 WalkFragment 类内部（方法区）
    /** 对外暴露：设置把系统事件发往 ChatbotFragment 的通道 */


/** 若你不方便用 Consumer，可改成自定义接口：
 public interface ChatbotNotifier { void notifyChatbot(String eventJson); }
 并把字段/方法类型一起替换为 ChatbotNotifier。
 */

    /** 距离计算（米） */
    /** 使用 Haversine 公式计算两点间球面距离（米） */
    private static float distanceMeters(@NonNull LatLng a, @NonNull LatLng b) {
        final double R = 6371000.0; // 地球半径，单位：米

        double lat1 = Math.toRadians(a.latitude);
        double lon1 = Math.toRadians(a.longitude);
        double lat2 = Math.toRadians(b.latitude);
        double lon2 = Math.toRadians(b.longitude);

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double hav = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(hav), Math.sqrt(1 - hav));

        return (float) (R * c);
    }


    /**
     * 每次定位回调/每次 tick 调用本方法即可。
     * 逻辑：
     * 1) 将当前点加入滑动窗口（按 TIRED_WINDOW_MS 保留）
     * 2) 若采样数足够且窗口覆盖足够时间，并且窗口内最大位移 <= 阈值 => 触发“累了”事件
     * 3) 通过 chatbotEventSink 发送 JSON 事件到 ChatbotFragment
     */
    public void checkTiredAndNotify(@NonNull LatLng currentLoc) {
        final long now = SystemClock.elapsedRealtime();

        // 1) 记录样本
        tiredWindow.addLast(new LocSample(now, currentLoc));

        // 仅保留时间窗内的样本
        final long cut = now - TIRED_WINDOW_MS;
        while (!tiredWindow.isEmpty() && tiredWindow.peekFirst().t < cut) {
            tiredWindow.removeFirst();
        }

        // 样本不足，不判定
        if (tiredWindow.size() < TIRED_MIN_SAMPLES) return;

        // 窗口是否覆盖足够时长（起点~现在 >= 窗口）
        LocSample first = tiredWindow.peekFirst();
        if (first == null || (now - first.t) < TIRED_WINDOW_MS * 0.9) { // 给点余量
            return;
        }

        // 冷却中不重复发送
        if (now - lastTiredNotifyAt < TIRED_NOTIFY_COOLDOWN_MS) return;

        // 2) 核心判定：窗口内“最大半径位移”是否小于阈值
        // 以当前点为中心，计算窗口内所有点到当前点的最大距离
        float rMax = 0f;
        for (LocSample s : tiredWindow) {
            rMax = Math.max(rMax, distanceMeters(s.latLng, currentLoc));
        }

        if (rMax <= TIRED_DISTANCE_THRESHOLD_M) {
            lastTiredNotifyAt = now;
            pendingTiredHint.compareAndSet(false, true);
            // 3) 组织一个系统事件（JSON），发送给 ChatbotFragment
            try {
                JSONObject evt = new JSONObject();
                evt.put("type", "system_event");
                evt.put("event", "user_tired");
                evt.put("source", "WalkFragment");
                evt.put("window_sec", TIRED_WINDOW_MS / 1000);
                evt.put("radius_m_max", rMax);
                evt.put("threshold_m", TIRED_DISTANCE_THRESHOLD_M);
                evt.put("timestamp_ms", now);

                // 你也可以在这里加上用户最近路线/步数等上下文信息
                String eventJson = evt.toString();
                String payload="The user may get tired now, please give the user some care and encouragement." +
                        "Later, API will search for nearby POIs that can help users relieve tiredness and mark them on the map(Such as supermarkets, coffee shops, milk tea shops and so on)";
                android.os.Bundle b = new android.os.Bundle();
                b.putString("payload", payload);
                getParentFragmentManager().setFragmentResult("APP_TOOL_EVENT", b);
                // 如果你手里有 ChatbotFragment 实例，可直接调用它的方法：
                // if (chatbotFragment != null) chatbotFragment.appendSystemEvent(eventJson);

            } catch (Exception ignored) {}
        }
    }

    /**
     * 如果 pendingTiredHint 为 true，则把“用户疲惫了...”提示注入到发送给 AccompanyBot 的下一条消息中，
     * 然后自动清零（只生效一次）。
     * - 若原始 payload 是 JSON（对象/数组），优先在对象里加 system_hints 数组与 priority_task；
     * - 若不是 JSON，就把提示文本前置拼接。
     */
    public static String injectTiredHintIfNeeded(String originalPayload) {
        if (!pendingTiredHint.getAndSet(false)) return originalPayload; // 没有待注入，直接返回

        final String hint = "The user is tired, mark the nearby coffee shops, supermarkets, milk tea shops, etc. on the map to help the user relieve fatigue";

        try {
            // 尝试按 JSON 对象处理
            org.json.JSONObject obj = new org.json.JSONObject(originalPayload);
            org.json.JSONArray hints = obj.optJSONArray("system_hints");
            if (hints == null) {
                hints = new org.json.JSONArray();
                obj.put("system_hints", hints);
            }
            hints.put(hint);
            // 可选：给 AccompanyBot 一个轻量任务标签，便于你的 prompt 侧路由
            obj.put("priority_task", "relief_POI_marking");
            return obj.toString();
        } catch (Exception e1) {
            // 若不是对象，试试数组
            try {
                org.json.JSONArray arr = new org.json.JSONArray(originalPayload);
                // 将提示放到数组第一个元素，若你的协议是 messages[] 之类可按需改造
                arr.put(0, hint);
                return arr.toString();
            } catch (Exception e2) {
                // 都不是 JSON，就直接前置拼接
                return hint + "\n" + (originalPayload == null ? "" : originalPayload);
            }
        }
    }




}
