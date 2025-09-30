package com.example.walkpromote22.tool;



import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.BusPath;
import com.amap.api.services.route.BusRouteResult;
import com.amap.api.services.route.BusStep;
import com.amap.api.services.route.DriveRouteResult;
import com.amap.api.services.route.RideRouteResult;
import com.amap.api.services.route.RouteSearch;
import com.amap.api.services.route.RouteSearch.WalkRouteQuery;
import com.amap.api.services.route.WalkRouteResult;
import com.amap.api.services.route.WalkPath;
import com.amap.api.services.route.WalkStep;
import com.example.walkpromote22.ChatbotFragments.ChatbotFragment;
import com.example.walkpromote22.data.dao.LocationDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * MapContainerView 实现两种功能：
 * 1. 记录用户实际走过的轨迹（实时定位后采样，用 Polyline 绘制）
 * 2. 根据目标点规划推荐路线（导航规划），调用路线规划 API 得到沿路拐点后绘制
 */
public class MapTool extends LinearLayout {
    private static final String AMAP_KEY = "c544938179068737b29bcd4456a87428";

    private MapView mapView;
    // 在 MapContainerView 类中添加成员变量：
    private Marker currentLocationMarker;

    private boolean mapReady = false;
    private final java.util.List<Runnable> pendingMapTasks = new java.util.ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private AMap aMap;
    // 用于显示实际轨迹的 polyline
    private Polyline NavigateLine;
    private Polyline userTrackPolyline;
    // 用于显示规划路线的 polyline（可使用不同样式）
    private Polyline planPolyline;

    // 用于记录实际轨迹的 GPS 点
    private List<LatLng> realTimePath = new ArrayList<>();
    // 当前最新定位
    private LatLng currentLocation;

    private List<LatLng> destinRoute=new ArrayList<>();
    // 定位客户端
    private AMapLocationClient locationClient;
    // 路线规划对象


    private final String TAG="TAG";



    private boolean initialPositionUpdated = false;
    private double totalDistance = 0;  // Total walking distance in meters

    public MapTool(Context context) throws Exception {
        super(context);
        init(context);
    }

    public MapTool(Context context, AttributeSet attrs) throws Exception {
        super(context, attrs);
        init(context);
    }

    public MapTool(Context context, AttributeSet attrs, int defStyleAttr) throws Exception {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) throws Exception {
        // 1) MapView 基本初始化
        mapView = new MapView(context);
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        mapView.setLayoutParams(params);
        addView(mapView);
        mapView.onCreate(null);

        // 2) 取得 AMap
        aMap = mapView.getMap();
        aMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override public void onMapLoaded() {
                mapReady = true;
                if (!pendingMapTasks.isEmpty()) {
                    java.util.List<Runnable> copy = new java.util.ArrayList<>(pendingMapTasks);
                    pendingMapTasks.clear();
                    for (Runnable r : copy) runOnUiThreadX(r);
                }
            }
        });

        if (aMap == null) {
            Toast.makeText(context, "地图加载失败", Toast.LENGTH_SHORT).show();
            return;
        }
        aMap.getUiSettings().setZoomControlsEnabled(false);
        aMap.getUiSettings().setZoomGesturesEnabled(true);
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "缺少定位权限，请在设置中开启。", Toast.LENGTH_LONG).show()
            );
            return; // 直接返回，避免继续调 SDK 报 SecurityException
        }

        // 3) 提前创建用于“实时轨迹/导航轨迹”的 Polyline（避免定位刚到来时 NPE）
        if (NavigateLine == null) {
            NavigateLine = aMap.addPolyline(new PolylineOptions()
                    .width(10)
                    .color(0xD4AF37)
                    .geodesic(true));
        }
        if (userTrackPolyline == null) {
            userTrackPolyline = aMap.addPolyline(new PolylineOptions()
                    .width(10)
                    .color(0xFFFF0000)
                    .geodesic(true));
        }

        // 4) 地图加载完成 → 打开闸门，执行所有待办绘制任务
        aMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override public void onMapLoaded() {
                mapReady = true;
                if (!pendingMapTasks.isEmpty()) {
                    java.util.List<Runnable> copy = new java.util.ArrayList<>(pendingMapTasks);
                    pendingMapTasks.clear();
                    for (Runnable r : copy) runOnUiThreadX(r);
                }
            }
        });
    }


    public AMap getAMap() {
        return aMap;
    }



    public interface OnLocationChangedListener {
        void onLocationChanged(LatLng newLocation);
    }
    private OnLocationChangedListener locationChangedListener;

    // 设置监听器的公开方法

    public void startPureLocation(final float zoomLevel) throws Exception {
        if (locationClient == null) {
            locationClient = new AMapLocationClient(getContext().getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            // 使用连续定位模式，直到获得有效定位
            option.setOnceLocation(false);
            // 缩短超时时间，尽快获取定位结果
            option.setHttpTimeOut(3000);
            locationClient.setLocationOption(option);
            locationClient.setLocationListener(new AMapLocationListener() {
                @Override
                public void onLocationChanged(AMapLocation aMapLocation) {
                    if (aMapLocation != null && aMapLocation.getErrorCode() == 0) {
                        LatLng newLocation = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());
                        // 如果还未更新摄像头，进行更新并停止定位
                        if (!initialPositionUpdated) {
                            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLocation, zoomLevel));
                            initialPositionUpdated = true;
                            // 成功更新摄像头后停止定位
                            locationClient.stopLocation();
                        }
                        if (locationChangedListener != null) {
                            locationChangedListener.onLocationChanged(newLocation);
                        }
                    } else {
                        String err = aMapLocation != null ? aMapLocation.getErrorInfo() : "定位返回为空";
                        Toast.makeText(getContext(), "定位失败: " + err, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        locationClient.startLocation();
    }


    /**
     * 启动实时定位，更新 currentLocation，并调用 updateRealTimePath() 绘制实际轨迹
     */
    public void startLocation(float zoomLevel) throws Exception {
        if (locationClient == null) {
            locationClient = new AMapLocationClient(getContext().getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setInterval(2000); // 每2秒一次定位
            option.setOnceLocation(false);
            locationClient.setLocationOption(option);
            totalDistance=0;
            locationClient.setLocationListener(new AMapLocationListener() {
                @Override
                public void onLocationChanged(AMapLocation aMapLocation) {
                    if (aMapLocation != null && aMapLocation.getErrorCode() == 0) {
                        currentLocation = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());
                        Log.e("123",currentLocation+"");
                        if (!realTimePath.isEmpty()) {
                            totalDistance += distanceBetween(currentLocation, realTimePath.get(realTimePath.size() - 1));
                        } // Calculate distance between the points
                        updateRealTimePath(currentLocation, zoomLevel,aMapLocation.getBearing());
                    } else {
                        String err = aMapLocation != null ? aMapLocation.getErrorInfo() : "定位返回为空";
                        Toast.makeText(getContext(), "定位失败: " + err, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        locationClient.startLocation();
    }



    public void startLocation(final float zoomLevel, List<Location> routeLocations) throws Exception {
        final long enterTs = android.os.SystemClock.elapsedRealtime();


        final AMap map = getAMap();
        if (map == null) {
            Log.w(TAG, "[startLocation] getAMap()==null");
            return;
        }

        // 开启定位层（不会自动移动镜头）
        try {
            map.setMyLocationEnabled(true);
        } catch (Throwable e) {
            Log.e(TAG, "[startLocation] setMyLocationEnabled error: " + e.getMessage(), e);
        }

        // 绘制路线（不透明色，避免看不见）
        if (routeLocations != null && !routeLocations.isEmpty()) {
            java.util.List<LatLng> latLngList = new java.util.ArrayList<>();
            for (Location loc : routeLocations) {
                if (loc != null) latLngList.add(new LatLng(loc.getLatitude(), loc.getLongitude()));
            }
            try {
                drawRoute(latLngList, 0xFF6A8EB1);
            } catch (Throwable drawEx) {
                Log.e(TAG, "[startLocation] drawRoute error: " + drawEx.getMessage(), drawEx);
            }
        } else {
            Log.e(TAG, "[startLocation] no route to draw");
        }

        // ====== 新增：网络能力检测日志 ======
        String netLog = "unknown";
        boolean hasValidated = false, hasInetCap = false;
        boolean trWifi = false, trCell = false, trVpn = false;

        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network active = null;
                if (android.os.Build.VERSION.SDK_INT >= 23) active = cm.getActiveNetwork();
                if (active != null && android.os.Build.VERSION.SDK_INT >= 21) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            hasValidated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        }
                        hasInetCap = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        trWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                        trCell = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
                        trVpn  = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);

                    }
                }
            }
        } catch (Throwable ignore) { }


        // ✅ 宽松判断：只要有 INTERNET 能力且有 Wi-Fi/蜂窝，就认为“有网”
        boolean hasInternetLikely = hasInetCap && (trWifi || trCell);
        // ===================================

        final boolean[] centeredOnce = {false};

        if (locationClient == null) {
            locationClient = new AMapLocationClient(getContext().getApplicationContext());

            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setInterval(2000);      // 2s 一次
            option.setOnceLocation(false);
            option.setNeedAddress(false);
            option.setMockEnable(false);
            option.setLocationCacheEnable(true);

            // ✅ 如果系统误判 validated=false，但我们认为 hasInternetLikely=true，仍然走网络定位
            if (hasInternetLikely) {
                option.setOnceLocationLatest(true); // 尽快拿到最近一次/缓存结果
                option.setHttpTimeOut(3000);
            }

            locationClient.setLocationOption(option);
            totalDistance = 0f;

            locationClient.setLocationListener(new AMapLocationListener()  {
                @Override
                public void onLocationChanged(AMapLocation aMapLocation) {

                    if (aMapLocation != null && aMapLocation.getErrorCode() == 0) {
                        currentLocation = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());

                        if (!centeredOnce[0]) {
                            centeredOnce[0] = true;
                            try {
                                map.moveCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(currentLocation, zoomLevel));
                            } catch (Throwable camEx) {
                                Log.e(TAG, "[onOK] moveCamera error: " + camEx.getMessage(), camEx);
                            }
                        }

                        if (!realTimePath.isEmpty()) {
                            double inc = distanceBetween(currentLocation, realTimePath.get(realTimePath.size() - 1));
                            totalDistance += inc;
                        }
                        try {
                            updateRealTimePath(currentLocation, zoomLevel, aMapLocation.getBearing());
                        } catch (Throwable upEx) {
                            Log.e(TAG, "[onOK] updateRealTimePath error: " + upEx.getMessage(), upEx);
                        }

                    } else {
                        String err = (aMapLocation != null) ? aMapLocation.getErrorInfo() : "定位返回为空";
                        Log.e(TAG, "[onFail] " + err);
                        android.widget.Toast.makeText(getContext(), "定位失败: " + err, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        try {
            locationClient.startLocation();
        } catch (Throwable t) {
            Log.e(TAG, "[startLocation] startLocation exception: " + t.getMessage(), t);
            android.widget.Toast.makeText(getContext(), "启动定位异常: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }








    public double getTotalDistance(){
        return totalDistance;
    }

    /**
     * 更新实际轨迹：如果 recordTrack 为 true，则将当前定位点加入轨迹列表并刷新 trackPolyline；
     * 同时，如果 lockCameraToUser 为 true 或未更新过初始位置，则更新摄像头视角到当前点。跟新用户图标、镜头位置
     */

    // 定义一个 Handler，用于异步更新 Marker 和相机
    Handler handler = new Handler(Looper.getMainLooper());

    // 定义一个标志位，确保Marker和相机的更新不会重叠




    // 修改 onTouchEvent 方法，检测用户操作状态

    // 修改 updateRealTimePath 方法，加入用户交互判断
    // 新增成员变量：
    private boolean firstLocating=true;
    private boolean isUpdating=false;
    private LatLng lastCameraCenter = null;
    private long lastCenterUpdateTime = 0;
    private boolean trackingPaused = false;
    private final float CENTER_THRESHOLD = 50f; // 距离阈值，单位：米
    private Polyline dashedPolyline;


    // 修改 updateRealTimePath 方法：
    private void updateRealTimePath(final LatLng newLocation, final float zoomLevel, float bearing) {
        if (isUpdating) return;  // 如果正在更新，就不做任何操作
        realTimePath.add(newLocation);
        NavigateLine.setPoints(realTimePath);
        userTrackPolyline.setPoints(realTimePath);
        isUpdating = true;  // 设置为正在更新
        handler.post(new Runnable() {
            @Override
            public void run() {
                // 更新 Marker
                if (currentLocationMarker == null) {
                    MarkerOptions markerOptions = new MarkerOptions()
                            .position(newLocation)
                            .icon(createCustomMarker(bearing));
                    currentLocationMarker = aMap.addMarker(markerOptions);
                } else {
                    currentLocationMarker.setPosition(newLocation);
                }

                // 获取当前相机中心
                LatLng currentCenter = aMap.getCameraPosition().target;
                // 计算相机中心与最新定位的距离（单位：米）
                double distance = distanceBetween(currentCenter, newLocation);

                if (distance > CENTER_THRESHOLD&&!firstLocating) {
                    //user operating
                    trackingPaused = true;
                    if (lastCameraCenter == null || !currentCenter.equals(lastCameraCenter)) {
                        lastCameraCenter = currentCenter;
                        lastCenterUpdateTime = System.currentTimeMillis();
                    } else {
                        if (System.currentTimeMillis() - lastCenterUpdateTime >= 10000) {
                            trackingPaused = false; // 恢复追踪
                        }
                    }
                } else {
                    trackingPaused = false;
                    firstLocating=false;
                }

                // 如果没有暂停追踪，则更新相机位置到用户当前位置
                if (!trackingPaused) {
                    CameraPosition cameraPosition = new CameraPosition.Builder()
                            .target(newLocation)
                            .zoom(zoomLevel)
                            .tilt(30)
                            .build();
                    aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                }

                // 下面增加绘制从 newLocation 到 destinRoute 上最近点的虚线
                if (destinRoute != null && !destinRoute.isEmpty()) {
                    LatLng closestPoint = getClosestPointOnPolyline(newLocation, destinRoute);
                    List<LatLng> dashPoints = new ArrayList<>();
                    dashPoints.add(newLocation);
                    dashPoints.add(closestPoint);
                    if (dashedPolyline == null) {
                        PolylineOptions options = new PolylineOptions()
                                .addAll(dashPoints)
                                .width(10)
                                .color(Color.GRAY)
                                .setDottedLine(true); // 如果 API 支持虚线样式
                        dashedPolyline = aMap.addPolyline(options);
                    } else {
                        dashedPolyline.setPoints(dashPoints);
                    }
                }

                // 更新完成后，设置为可更新
                isUpdating = false;
            }
        });
    }

    // 辅助方法：计算点到 polyline 上的最近点
    private LatLng getClosestPointOnPolyline(LatLng point, List<LatLng> polyline) {
        LatLng closestPoint = null;
        double minDistance = Double.MAX_VALUE;
        for (int i = 0; i < polyline.size() - 1; i++) {
            LatLng segmentStart = polyline.get(i);
            LatLng segmentEnd = polyline.get(i + 1);
            LatLng projectedPoint = getProjection(point, segmentStart, segmentEnd);
            double d = distanceBetween(point, projectedPoint);
            if (d < minDistance) {
                minDistance = d;
                closestPoint = projectedPoint;
            }
        }
        return closestPoint;
    }

    // 辅助方法：计算点 p 在线段 a->b 上的投影点（近似处理，适用于较小距离）
    private LatLng getProjection(LatLng p, LatLng a, LatLng b) {
        double AToP_x = p.longitude - a.longitude;
        double AToP_y = p.latitude - a.latitude;
        double AToB_x = b.longitude - a.longitude;
        double AToB_y = b.latitude - a.latitude;
        double magSquared = AToB_x * AToB_x + AToB_y * AToB_y;
        double dot = AToP_x * AToB_x + AToP_y * AToB_y;
        double t = dot / magSquared;
        t = Math.max(0, Math.min(1, t)); // 限制 t 在 [0,1] 范围内
        double projLon = a.longitude + t * AToB_x;
        double projLat = a.latitude + t * AToB_y;
        return new LatLng(projLat, projLon);
    }





    // 绘制指示用户朝向的扇形
    public BitmapDescriptor createCustomMarker(float bearing) {
        Log.e("bear",bearing+"");
        // 创建一个 Bitmap，用来存储我们绘制的图形
        int size =50;  // 设置圆形的直径
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        // 创建 Canvas 对象
        Canvas canvas = new Canvas(bitmap);
        // 创建一个 Paint 对象，用于绘制圆形
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);  // 设置圆形颜色为蓝色
        paint.setAntiAlias(true);    // 设置抗锯齿
        // 画一个蓝色圆形
        canvas.drawCircle(size / 2, size / 2, size / 2, paint);

        // 返回 BitmapDescriptor 用于设置为 Marker 图标
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    public static List<List<Location>> rank(List<List<Location>> routes){
        List<List<Location>> ordered_routes=new ArrayList<>();

        for (List<Location> route : routes) {
            // 复制一份，避免直接修改原有列表
            List<Location> ordered = new ArrayList<>(route);
            // 按 order 升序排序
            Collections.sort(ordered, new Comparator<Location>() {
                @Override
                public int compare(Location o1, Location o2) {
                    // 假设 order 是 int，如果是 getOrder()，请改成 o1.getOrder() - o2.getOrder()
                    return Integer.compare(o1.getIndexNum(), o2.getIndexNum());
                }
            });
            ordered_routes.add(ordered);
        }
        return ordered_routes;
    }


    public void drawRoute(final List<LatLng> locations, int color) {
        if (locations == null || locations.size() < 2) {
            Toast.makeText(getContext(), "需要至少两个地点", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.e("MapTool", "drawRoute 被调用, size=" + locations.size());

        // 关键：不要再 setOnMapLoadedListener；统一走就绪
            Log.e("TAG","开始请求路线规划");
            final int totalSegments = locations.size() - 1;

            // 占位每段结果
            final java.util.List<java.util.List<LatLng>> segmentResults = new java.util.ArrayList<>();
            for (int i = 0; i < totalSegments; i++) segmentResults.add(null);
            final int[] completed = {0};

            for (int i = 0; i < totalSegments; i++) {
                final int index = i;
                final LatLng start = locations.get(i);
                final LatLng end   = locations.get(i + 1);

                RouteSearch routeSearch = new RouteSearch(getContext());
                RouteSearch.FromAndTo ft = new RouteSearch.FromAndTo(
                        new LatLonPoint(start.latitude, start.longitude),
                        new LatLonPoint(end.latitude,   end.longitude));
                WalkRouteQuery query = new WalkRouteQuery(ft, RouteSearch.WalkDefault);

                routeSearch.setRouteSearchListener(new RouteSearch.OnRouteSearchListener() {
                    @Override
                    public void onWalkRouteSearched(WalkRouteResult result, int errorCode) {
                        Log.e("MapTool", "onWalkRouteSearched 被触发, errorCode=" + errorCode);
                        java.util.List<LatLng> seg = new java.util.ArrayList<>();

                        if (errorCode == AMapException.CODE_AMAP_SUCCESS
                                && result != null
                                && result.getPaths() != null
                                && !result.getPaths().isEmpty()) {
                            WalkPath wp = result.getPaths().get(0);
                            seg = decodeWalkPath(wp); // 你已有的方法：把步行路径拆成 LatLng 列表
                            Log.e("TAG","drawRoute成功");
                        } else {
                            Log.e("TAG","drawRoute失败");
                            // 规划失败就直接端点直连，保证至少能看到线
                            seg.add(start);
                            seg.add(end);
                        }

                        segmentResults.set(index, seg);

                        if (++completed[0] == totalSegments) {
                            // 拼接完整路线，去掉相邻段的重复首尾点
                            java.util.List<LatLng> full = new java.util.ArrayList<>();
                            for (int k = 0; k < totalSegments; k++) {
                                java.util.List<LatLng> s = segmentResults.get(k);
                                if (s == null || s.isEmpty()) continue;
                                if (!full.isEmpty() && full.get(full.size() - 1).equals(s.get(0))) {
                                    full.addAll(s.subList(1, s.size()));
                                } else {
                                    full.addAll(s);
                                }
                            }

                            // 清理旧的规划线（如果有）
                            if (planPolyline != null) {
                                try { planPolyline.remove(); } catch (Throwable ignore) {}
                                planPolyline = null;
                            }

                            // 画整条路线（不移动相机）
                            planPolyline = aMap.addPolyline(new PolylineOptions()
                                    .addAll(full)
                                    .width(10)
                                    .color(color)
                                    .geodesic(true)
                                    .zIndex(1f));

                            // 回写给 destinRoute，供你的“虚线吸附到路线最近点”逻辑使用
                            destinRoute = full;

                        }
                    }

                    @Override public void onBusRouteSearched(com.amap.api.services.route.BusRouteResult r, int ec) {}
                    @Override public void onDriveRouteSearched(com.amap.api.services.route.DriveRouteResult r, int ec) {}
                    @Override public void onRideRouteSearched(com.amap.api.services.route.RideRouteResult r, int ec) {}
                });
                routeSearch.calculateWalkRouteAsyn(query);
            }
    }
    public void drawBusRoute(final LatLng start, final LatLng busEnd, final List<LatLng> locations, int color, String apiKey, String city) {
        if (start == null || busEnd == null || locations == null || locations.size() < 1) {
            Toast.makeText(getContext(), "需要有效的起点、公交终点和步行路径", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. 获取公交路线 JSON 数据
        String origin = start.longitude + "," + start.latitude;
        String destination = busEnd.longitude + "," + busEnd.latitude;

        // 异步请求公交路线数据
        new Thread(() -> {
            String jsonData = getBusRouteJson(origin, destination, city, apiKey);
            if (jsonData != null) {
                // 2. 解析公交路线 JSON 数据
                List<LatLng> busRoute = new ArrayList<>();
                parseBusRouteJson(jsonData);

                // 3. 获取步行路径的 JSON 数据（从公交终点到步行终点）
                String walkStart = busEnd.longitude + "," + busEnd.latitude;
                String walkEnd = locations.get(locations.size() - 1).longitude + "," + locations.get(locations.size() - 1).latitude;

                String walkJsonData = getBusRouteJson(walkStart, walkEnd, city, apiKey);
                if (walkJsonData != null) {
                    // 解析步行路径
                    List<LatLng> walkRoute = new ArrayList<>();
                    parseBusRouteJson(walkJsonData);

                    // 4. 拼接公交路径和步行路径
                    List<LatLng> fullRoute = new ArrayList<>();
                    fullRoute.addAll(busRoute); // 加入公交路径
                    fullRoute.addAll(walkRoute); // 加入步行路径

                        // 清理旧的规划线（如果有）
                        if (planPolyline != null) {
                            try {
                                planPolyline.remove();
                            } catch (Throwable ignore) {}
                            planPolyline = null;
                        }

                        // 绘制公交 + 步行路线
                        planPolyline = aMap.addPolyline(new PolylineOptions()
                                .addAll(fullRoute)
                                .width(10)
                                .color(color)
                                .geodesic(true)
                                .zIndex(1f));

                        destinRoute = fullRoute; // 供“虚线吸附到路线最近点”逻辑使用

                }
            }
        }).start();
    }

    public String getBusRouteJson(String origin, String destination, String city, String apiKey) {
        OkHttpClient client = new OkHttpClient();

        String url = "https://restapi.amap.com/v3/direction/transit?"
                + "origin=" + origin // 起点坐标
                + "&destination=" + destination // 终点坐标
                + "&city=" + city // 城市名称
                + "&key=" + apiKey; // 高德 API key

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                assert response.body() != null;
                return response.body().string(); // 返回原始 JSON 字符串
            } else {
                return "Request failed";
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "Error occurred";
        }
    }
    public void parseBusRouteJson(String jsonData) {
        try {
            JSONObject jsonObject = new JSONObject(jsonData);
            JSONObject route = jsonObject.getJSONObject("route");

            // 获取 transit 数据
            JSONArray transits = route.getJSONArray("transits");

            for (int i = 0; i < transits.length(); i++) {
                JSONObject transit = transits.getJSONObject(i);

                // 获取 segments 数据
                JSONArray segments = transit.getJSONArray("segments");

                for (int j = 0; j < segments.length(); j++) {
                    JSONObject segment = segments.getJSONObject(j);

                    // 解析公交线路信息
                    if (segment.has("bus")) {
                        JSONObject bus = segment.getJSONObject("bus");
                        JSONArray buslines = bus.getJSONArray("buslines");

                        for (int k = 0; k < buslines.length(); k++) {
                            JSONObject busline = buslines.getJSONObject(k);
                            String busName = busline.getString("name");
                            String polyline = busline.getString("polyline");
                            System.out.println("Bus Line: " + busName);
                            System.out.println("Polyline: " + polyline);
                        }
                    }

                    // 解析步行信息
                    if (segment.has("walking")) {
                        JSONObject walking = segment.getJSONObject("walking");
                        String walkingPolyline = walking.getString("polyline");
                        System.out.println("Walking Polyline: " + walkingPolyline);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private List<LatLng> decodePolyline(String polylineStr) {
        List<LatLng> result = new ArrayList<>();
        String[] points = polylineStr.split(";");
        for (String point : points) {
            String[] latLon = point.split(",");
            double lat = Double.parseDouble(latLon[1]);
            double lon = Double.parseDouble(latLon[0]);
            result.add(new LatLng(lat, lon));
        }
        return result;
    }








    /**
     * 辅助方法：将 WalkRoutePath 中所有步骤的坐标转换为 LatLng 集合
     */
    private List<LatLng> decodeWalkPath(WalkPath walkPath) {
        List<LatLng> latLngs = new ArrayList<>();
        if (walkPath == null) return latLngs;
        List<WalkStep> steps = walkPath.getSteps();
        if (steps != null) {
            for (WalkStep step : steps) {
                List<LatLonPoint> polyline = step.getPolyline();
                if (polyline != null) {
                    for (LatLonPoint lp : polyline) {
                        latLngs.add(new LatLng(lp.getLatitude(), lp.getLongitude()));
                    }
                }
            }
        }
        return latLngs;
    }

    public void onCreate() {
        //mapView.onCreate(null);
    }

    public void onResume() {
        mapView.onResume();
    }

    public void onPause() {
        mapView.onPause();
    }

    public void onDestroy() {
        mapView.onDestroy();
        if (locationClient != null) {
            locationClient.stopLocation();
            locationClient.onDestroy();
        }
    }

    public MapView getMapView() {
        return mapView;
    }


    public static LatLng calculateCenter(List<LatLng> points) {
        double sumLat = 0;
        double sumLng = 0;
        for (LatLng point : points) {
            sumLat += point.latitude;
            sumLng += point.longitude;
        }
        int count = points.size();
        return new LatLng(sumLat / count, sumLng / count);
    }
    public static double distanceBetween(LatLng point1, LatLng point2) {//返回单位为米
        double R = 6371000; // 地球半径，单位：米
        double latDistance = Math.toRadians(point2.latitude - point1.latitude);
        double lonDistance = Math.toRadians(point2.longitude - point1.longitude);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(point1.latitude)) * Math.cos(Math.toRadians(point2.latitude))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
    public static String getAddressFromAPI(double latitude, double longitude) {
        String address = null;
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            String urlStr = "https://restapi.amap.com/v3/geocode/regeo?location=" + longitude + "," + latitude
                    + "&key=" + AMAP_KEY + "&radius=200&extensions=base";
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                JSONObject jsonObject = new JSONObject(response.toString());
                if ("1".equals(jsonObject.optString("status"))) {
                    JSONObject regeocode = jsonObject.optJSONObject("regeocode");
                    if (regeocode != null) {
                        address = regeocode.optString("formatted_address");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return address;
    }


    public interface LocationQueryCallback {
        void onQueryComplete(List<Location> locations);
        void onQueryFailed(String error);
    }


    public static void getNearbyLocations(Context context, double latitude, double longitude, LocationQueryCallback callback) {
        new Thread(() -> {
            List<Location> nearbyLocations = new ArrayList<>();
            double threshold = 50f;

            try {
                // 获取数据库实例
                AppDatabase db = AppDatabase.getDatabase(context);
                LocationDao locationDao = db.locationDao();
                // 获取附近地点
                List<Location> locations = locationDao.getLocationsNear(latitude, longitude, threshold);

                // 查询到的地点计算距离并筛选接近地点
                for (Location location : locations) {
                    LatLng locationLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                    LatLng targetLatLng = new LatLng(latitude, longitude);
                    double distance = distanceBetween(locationLatLng, targetLatLng); // 计算两点之间的距离
                    if (distance <= threshold) {
                        nearbyLocations.add(location); // 如果在阈值范围内，则认为是接近地点
                    }
                }

                // 如果没有找到符合条件的地点
                if (nearbyLocations.isEmpty()) {
                    callback.onQueryFailed("No locations nearby");
                } else {
                    callback.onQueryComplete(nearbyLocations); // 查询成功
                }
            } catch (Exception e) {
                callback.onQueryFailed("Error querying locations: " + e.getMessage()); // 查询过程中出错
            }
        }).start();
    }

    public static List<LatLng> LocationToLatLng(List<Location> locations){
        List<LatLng> latLngs=new ArrayList<>();
        for(Location location:locations){
            latLngs.add(new LatLng(location.getLatitude(),location.getLongitude()));
        }
        return latLngs;
    }

    public static void getCurrentLocation(boolean b,Context context, ChatbotFragment.LocationCallback callback) throws Exception {

        if (!b) {
            LatLng currentLocation = new LatLng(31.2744759453053, 120.73822377078568);//西交利物浦经纬度
            callback.onLocationReceived(currentLocation);
            return;
        }

        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }

        // 在 getCurrentLocation 方法之前调用
       /* AMapLocationClient.updatePrivacyShow(context, true, true);
        AMapLocationClient.updatePrivacyAgree(context, true);*/


        AMapLocationClient locationClient = new AMapLocationClient(context);
        AMapLocationClientOption option = new AMapLocationClientOption();
        option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        option.setOnceLocation(true); // 一次定位
        locationClient.setLocationOption(option);
        locationClient.setLocationListener(aMapLocation -> {
            if (aMapLocation != null && aMapLocation.getErrorCode() == 0) {
                // 定位成功，构造当前经纬度
                LatLng currentLocation = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());
                try {
                    callback.onLocationReceived(currentLocation);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                String errMsg = aMapLocation != null ? aMapLocation.getErrorInfo() : "Unknown error";
                callback.onLocationFailed("定位失败：" + errMsg);
            }
            // 定位完成后停止并销毁定位客户端
            locationClient.stopLocation();
            locationClient.onDestroy();
        });
        locationClient.startLocation();
    }
    public static String trimLocationName(String src) {
        if (src == null) return "";

        // ① 括号
        String s = src.replaceAll("（.*?）|\\(.*?\\)", "").trim();

        // ② 行政区前缀（保留“区”）
        Pattern adminP = Pattern.compile("^(.*?)(省|市|县|特别行政区)(.+)$");
        Matcher mA = adminP.matcher(s);
        if (mA.find()) s = mA.group(3).trim();

        // ③ 循环裁掉 “街道 / 街 / 路” 前缀 —— 但必须保证裁后剩 ≥2 个中文字符
        while (true) {
            int lenBefore = s.length();
            boolean cut = false;

            int idx;
            if ((idx = s.indexOf("街道")) != -1 && idx + 3< s.length() - 1) {           // 裁后仍 >=2
                s = s.substring(idx + 2).trim();
                cut = true;
            } else if ((idx = s.indexOf("街")) != -1 && idx + 3 < s.length() - 1) {
                s = s.substring(idx + 1).trim();
                cut = true;
            } else if ((idx = s.indexOf("路")) != -1 && idx + 3 < s.length() - 1) {
                s = s.substring(idx + 1).trim();
                cut = true;
            }else if ((idx = s.indexOf("区")) != -1 && idx + 3 < s.length() - 1) {
                s = s.substring(idx + 1).trim();
                cut = true;
            }

            if (!cut || s.length() == lenBefore) break;     // 不能再裁 or 无变化
        }

        // ④ 清尾（保留“区”）
        s = s.replaceAll("(校区|路|街道|街)+$", "").trim();
        return s;
    }


    /** 自动按字数自适应字号 */




    /* ---------- sp → px 工具 ---------- */
    private float sp2px(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
    private void runOnUiThreadX(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    // 替换 runWhenMapReady，让它始终打印日志并确保任务执行
    private void runWhenMapReady(Runnable r) {
        if (mapReady && aMap != null) {
            Log.d("MapTool", "✅ 地图已就绪，立即执行任务");
            runOnUiThreadX(r);
        } else {
            Log.w("MapTool", "🕓 地图未就绪，加入待执行列表");
            pendingMapTasks.add(r);
        }
    }


}







