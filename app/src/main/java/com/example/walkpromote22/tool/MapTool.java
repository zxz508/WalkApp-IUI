package com.example.walkpromote22.tool;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.Toast;

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
import com.amap.api.services.route.RouteSearch;
import com.amap.api.services.route.RouteSearch.WalkRouteQuery;
import com.amap.api.services.route.WalkRouteResult;
import com.amap.api.services.route.WalkPath;
import com.amap.api.services.route.WalkStep;
import com.example.walkpromote22.ChatbotFragments.ChatbotFragment;
import com.example.walkpromote22.data.dao.LocationDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;


import org.json.JSONObject;

import java.io.BufferedReader;
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

/**
 * MapContainerView 实现两种功能：
 * 1. 记录用户实际走过的轨迹（实时定位后采样，用 Polyline 绘制）
 * 2. 根据目标点规划推荐路线（导航规划），调用路线规划 API 得到沿路拐点后绘制
 */
public class MapTool extends LinearLayout {
    private static final String API_KEY = "9bc4bb77bf4088e3664bff35350f9c37";

    private MapView mapView;
    // 在 MapContainerView 类中添加成员变量：
    private Marker currentLocationMarker;


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

    @SuppressLint("ClickableViewAccessibility")
    private void init(Context context) throws Exception {
        // 初始化 MapView 并加入当前容器
        mapView = new MapView(context);
        // 在 Application 的 onCreate 或启动前进行隐私政策配置


        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        mapView.setLayoutParams(params);
        addView(mapView);

        // 调用 MapView 的 onCreate 方法（传入 null 或 Bundle）
        mapView.onCreate(null);
        // 为 MapView 设置 onTouchListener 来捕获触摸事件

        aMap = mapView.getMap();
        if (aMap != null) {
            aMap.getUiSettings().setZoomControlsEnabled(false);
            aMap.getUiSettings().setZoomGesturesEnabled(true);
        } else {
            Toast.makeText(context, "地图加载失败", Toast.LENGTH_SHORT).show();
        }

        // 初始化两个 Polyline，一个用于实际轨迹，一个用于规划路线
        NavigateLine = aMap.addPolyline(new PolylineOptions()
                .width(10)
                .color(0xD4AF37)
                .geodesic(true));


        // 用于绘制用户路径的 Polyline

// 在初始化方法中初始化 `userTrackPolyline`：
        userTrackPolyline = aMap.addPolyline(new PolylineOptions()
                .width(10) // 设置轨迹的宽度
                .color(0xFFFF0000) // 设置轨迹的颜色为红色，方便区分
                .geodesic(true)); // 设置为大圆路径
        // 规划路线用红色显示

        // 为 MapView 设置 onTouchListener 来捕获触摸事件


    }

    public AMap getAMap() {
        return aMap;
    }

    public void Navigation(List<Location> routeLocations) {
    }

    public void updateUserLocationOnMap(LatLng currentLocation) {
    }


    public interface OnLocationChangedListener {
        void onLocationChanged(LatLng newLocation);
    }
    private OnLocationChangedListener locationChangedListener;

    // 设置监听器的公开方法
    public void setOnLocationChangedListener(OnLocationChangedListener listener) {
        this.locationChangedListener = listener;
    }
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
        if (locationClient == null) {
            destinRoute=LocationToLatLng(routeLocations);

            locationClient = new AMapLocationClient(getContext().getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setInterval(2000); // 每2秒一次定位
            option.setOnceLocation(false);
            locationClient.setLocationOption(option);
            totalDistance = 0f;

            locationClient.setLocationListener(new AMapLocationListener()  {
                @Override
                public void onLocationChanged(AMapLocation aMapLocation) {
                    Log.e("!2","!!!!");
                    if (aMapLocation != null && aMapLocation.getErrorCode() == 0) {
                        currentLocation = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());
                        Log.e("12",currentLocation+"当前坐标");
                        if (!realTimePath.isEmpty()) {//如果没有这个就没法定位甚至不报错太坑了
                            totalDistance += distanceBetween(currentLocation, realTimePath.get(realTimePath.size() - 1));
                        }
                        updateRealTimePath(currentLocation, zoomLevel,aMapLocation.getBearing());
                    } else {
                        String err = aMapLocation != null ? aMapLocation.getErrorInfo() : "定位返回为空";
                        Toast.makeText(getContext(), "定位失败: " + err, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        locationClient.startLocation();
        // Once location tracking starts, also handle route preview and guidance
        if (routeLocations != null && !routeLocations.isEmpty()) {
            // Convert route locations into LatLng points for the map

            List<LatLng> latLngList = new ArrayList<>();
            for (Location location : routeLocations) {
                latLngList.add(new LatLng(location.getLatitude(), location.getLongitude()));
            }

            // Show route preview
            drawRoute(latLngList,0xADD8E6);
            // Start route guidance

            if (latLngList.size() > 1) {
                //指引方向


                // Calculate route distance and other navigation details
                // Optionally, you could use APIs like AMap's RouteSearch to calculate the walking route.
                // You could also implement real-time guidance by comparing user position with route waypoints.
            }

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
                        if (System.currentTimeMillis() - lastCenterUpdateTime >= 2000) {
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
                    return Integer.compare(o1.getIndex_num(), o2.getIndex_num());
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
        // 等待地图加载完成后再进行路线规划
        aMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                final int totalSegments = locations.size() - 1;
                // 用于保存每段路线的点，按顺序存储
                final List<List<LatLng>> segmentResults = new ArrayList<>();
                // 初始化占位
                for (int i = 0; i < totalSegments; i++) {
                    segmentResults.add(null);
                }
                // 使用数组来保存完成的段数
                final int[] completedCount = {0};

                // 针对每一段调用异步步行路线规划
                for (int i = 0; i < totalSegments; i++) {
                    final int index = i;
                    final LatLng start = locations.get(i);
                    final LatLng end = locations.get(i + 1);
                    final RouteSearch localRouteSearch = new RouteSearch(getContext());
                    LatLonPoint startPoint = new LatLonPoint(start.latitude, start.longitude);
                    LatLonPoint endPoint = new LatLonPoint(end.latitude, end.longitude);
                    RouteSearch.FromAndTo fromAndTo = new RouteSearch.FromAndTo(startPoint, endPoint);
                    WalkRouteQuery query = new WalkRouteQuery(fromAndTo, RouteSearch.WalkDefault);
                    localRouteSearch.calculateWalkRouteAsyn(query);
                    localRouteSearch.setRouteSearchListener(new RouteSearch.OnRouteSearchListener() {
                        @Override
                        public void onWalkRouteSearched(WalkRouteResult result, int errorCode) {
                            List<LatLng> segmentPoints = new ArrayList<>();
                            if (errorCode == AMapException.CODE_AMAP_SUCCESS && result != null &&
                                    result.getPaths() != null && !result.getPaths().isEmpty()) {
                                // 获取第一个规划方案并解码路径
                                WalkPath walkPath = result.getPaths().get(0);
                                segmentPoints = decodeWalkPath(walkPath);
                            } else {
                                // 如果规划失败，则直接使用直线连接（作为退化方案）
                                segmentPoints.add(start);
                                segmentPoints.add(end);
                            }
                            // 保存该段结果
                            segmentResults.set(index, segmentPoints);
                            completedCount[0]++;
                            // 如果所有段都返回了，拼接完整路线并绘制
                            if (completedCount[0] == totalSegments) {
                                List<LatLng> fullRoute = new ArrayList<>();
                                for (List<LatLng> seg : segmentResults) {
                                    // 去掉重复的起点（除第一段）
                                    if (!fullRoute.isEmpty() && !seg.isEmpty() && fullRoute.get(fullRoute.size()-1).equals(seg.get(0))) {
                                        fullRoute.addAll(seg.subList(1, seg.size()));
                                    } else {
                                        fullRoute.addAll(seg);
                                    }
                                }
                                aMap.addPolyline(new PolylineOptions()
                                        .addAll(fullRoute)
                                        .width(10)
                                        .color(color));
                            }
                        }

                        @Override
                        public void onRideRouteSearched(com.amap.api.services.route.RideRouteResult rideRouteResult, int i) {
                            // 不处理骑行路线
                        }

                        @Override
                        public void onBusRouteSearched(com.amap.api.services.route.BusRouteResult result, int errorCode) {
                            // 不处理公交路线
                        }

                        @Override
                        public void onDriveRouteSearched(com.amap.api.services.route.DriveRouteResult driveRouteResult, int i) {
                            // 不处理驾车路线
                        }
                    });
                }
            }
        });
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
        mapView.onCreate(null);
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
                    + "&key=" + API_KEY + "&radius=200&extensions=base";
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


}







