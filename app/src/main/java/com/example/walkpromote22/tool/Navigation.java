/*package com.example.myapp.tool;

import android.content.Context;
import android.util.Log;

import com.amap.api.maps.AMap;
import com.amap.api.maps.AMapException;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapNaviRouteNotifyData;
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo;
import com.amap.api.navi.model.AMapServiceAreaInfo;
import com.amap.api.navi.model.AimLessModeCongestionInfo;
import com.amap.api.navi.model.AimLessModeStat;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.BusRouteResult;
import com.amap.api.services.route.DriveRouteResult;
import com.amap.api.services.route.RideRouteResult;
import com.amap.api.services.route.RouteSearch;
import com.amap.api.services.route.WalkPath;
import com.amap.api.services.route.WalkRouteResult;
import com.example.myapp.MapTool.MapContainerView;
import com.example.myapp.data.model.Location;

import java.util.ArrayList;
import java.util.List;

public class Navigation {

    private AMapNavi mAMapNavi;
    private AMapNaviListener mAMapNaviListener;
    private Context mContext;
    private AMap aMap;
    private Marker currentLocationMarker;

    // 构造函数初始化



    private MapContainerView mapContainerView;

    public Navigation(Context context, AMap aMap, MapContainerView mapContainerView) throws AMapException {
        this.aMap = aMap;
        this.mapContainerView = mapContainerView;
        mAMapNavi = AMapNavi.getInstance(context);
        initNaviListener();
    }

    // 初始化导航监听器
    private void initNaviListener() {
        mAMapNaviListener = new AMapNaviListener() {
            @Override
            public void onInitNaviSuccess() {
                Log.d("AMapNavi", "Navi initialized successfully");
            }

            @Override
            public void onInitNaviFailure() {
                Log.d("AMapNavi", "Navi initialization failed");
            }

            @Override
            public void onStartNavi(int type) {
                Log.d("AMapNavi", "Navigation started");
            }

            @Override
            public void onTrafficStatusUpdate() {

            }



            @Override
            public void onGetNavigationText(int i, String s) {

            }

            @Override
            public void onGetNavigationText(String s) {

            }

            @Override
            public void onEndEmulatorNavi() {

            }

            @Override
            public void onArriveDestination() {

            }

            @Override
            public void onCalculateRouteSuccess(int[] routeIds) {
                Log.d("AMapNavi", "Route calculation success");
            }

            @Override
            public void notifyParallelRoad(int i) {

            }

            @Override
            public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {

            }

            @Override
            public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {

            }

            @Override
            public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {

            }

            @Override
            public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {

            }

            @Override
            public void onPlayRing(int i) {

            }

            @Override
            public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {

            }

            @Override
            public void onCalculateRouteFailure(AMapCalcRouteResult aMapCalcRouteResult) {

            }

            @Override
            public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {

            }

            @Override
            public void onGpsSignalWeak(boolean b) {

            }

            @Override
            public void onCalculateRouteFailure(int errorCode) {
                Log.d("AMapNavi", "Route calculation failed with error: " + errorCode);
            }

            @Override
            public void onReCalculateRouteForYaw() {

            }

            @Override
            public void onReCalculateRouteForTrafficJam() {

            }

            @Override
            public void onArrivedWayPoint(int i) {

            }

            @Override
            public void onGpsOpenStatus(boolean b) {

            }

            @Override
            public void onNaviInfoUpdate(NaviInfo naviInfo) {

            }

            @Override
            public void updateCameraInfo(AMapNaviCameraInfo[] aMapNaviCameraInfos) {

            }

            @Override
            public void updateIntervalCameraInfo(AMapNaviCameraInfo aMapNaviCameraInfo, AMapNaviCameraInfo aMapNaviCameraInfo1, int i) {

            }

            @Override
            public void onServiceAreaUpdate(AMapServiceAreaInfo[] aMapServiceAreaInfos) {

            }

            @Override
            public void showCross(AMapNaviCross aMapNaviCross) {

            }

            @Override
            public void hideCross() {

            }

            @Override
            public void showModeCross(AMapModelCross aMapModelCross) {

            }

            @Override
            public void hideModeCross() {

            }

            @Override
            public void showLaneInfo(AMapLaneInfo[] aMapLaneInfos, byte[] bytes, byte[] bytes1) {

            }

            @Override
            public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {

            }

            @Override
            public void hideLaneInfo() {

            }

            @Override
            public void onLocationChange(AMapNaviLocation location) {
                // Update the location on map
                LatLng currentLocation =new LatLng(location.getCoord().getLatitude(),location.getCoord().getLongitude());
                mapContainerView.updateUserLocationOnMap(currentLocation); // Update user location on map
            }

            // Implement other methods as needed
        };

        // Register the listener
        mAMapNavi.addAMapNaviListener(mAMapNaviListener);
    }

    // 计算并规划路线
    // 计算步行路线
    // 计算步行路线
    public void calculateRoute(List<Location> routeLocations) {
        if (routeLocations == null || routeLocations.size() < 2) {
            Log.d("AMapNavi", "Invalid route data");
            return;
        }

        // Convert Locations to LatLng
        List<NaviLatLng> startPoints = new ArrayList<>();
        List<NaviLatLng> endPoints = new ArrayList<>();

        for (Location location : routeLocations) {
            startPoints.add(new NaviLatLng(location.getLatitude(), location.getLongitude()));
        }

        // The last location is considered the destination
        endPoints.add(new NaviLatLng(routeLocations.get(routeLocations.size() - 1).getLatitude(),
                routeLocations.get(routeLocations.size() - 1).getLongitude()));

        // 使用 RouteSearch 计算步行路线
        RouteSearch routeSearch = new RouteSearch(mContext);
        RouteSearch.FromAndTo fromAndTo = new RouteSearch.FromAndTo(
                new LatLonPoint(routeLocations.get(0).getLatitude(), routeLocations.get(0).getLongitude()),
                new LatLonPoint(routeLocations.get(routeLocations.size() - 1).getLatitude(), routeLocations.get(routeLocations.size() - 1).getLongitude())
        );

        // 创建步行路线查询
        RouteSearch.WalkRouteQuery query = new RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WalkDefault);

        // 设置回调监听器
        routeSearch.setRouteSearchListener(new RouteSearch.OnRouteSearchListener() {
            @Override
            public void onWalkRouteSearched(WalkRouteResult result, int rCode) {
                if (rCode == 0) {
                    // 计算成功，处理步行路线
                    if (result != null && result.getPaths() != null && !result.getPaths().isEmpty()) {
                        WalkPath walkPath = result.getPaths().get(0);
                        // 在这里你可以做路线处理，更新地图等
                        Log.d("AMapNavi", "Walk route found, distance: " + walkPath.getDistance());
                    }
                } else {
                    // 计算失败，处理错误
                    Log.d("AMapNavi", "Walk route calculation failed, error code: " + rCode);
                }
            }

            @Override
            public void onBusRouteSearched(BusRouteResult result, int rCode) {
                // 不处理公交路线
            }

            @Override
            public void onDriveRouteSearched(DriveRouteResult result, int rCode) {
                // 不处理驾车路线
            }

            @Override
            public void onRideRouteSearched(RideRouteResult result, int rCode) {
                // 不处理骑行路线
            }
        });

        // 异步计算步行路线
        routeSearch.calculateWalkRouteAsyn(query);
    }



    // 启动导航并更新地图
    public void startNavigation(List<Location> routeLocations) {
        if (routeLocations == null || routeLocations.size() < 2) {
            return;
        }

        // Convert Locations to NaviLatLng for route
        List<NaviLatLng> startPoints = new ArrayList<>();
        for (Location location : routeLocations) {
            startPoints.add(new NaviLatLng(location.getLatitude(), location.getLongitude()));
        }

        // Use the last location as the destination
        NaviLatLng endPoint = new NaviLatLng(routeLocations.get(routeLocations.size() - 1).getLatitude(),
                routeLocations.get(routeLocations.size() - 1).getLongitude());

        // Start navigation with the pre-defined route (no driving strategy needed for walking)
        mAMapNavi.startNavi(NaviType.GPS);  // Use GPS navigation for walking
    }


    // Cleanup


    // 更新用户位置
    private void updateUserLocationOnMap(NaviLatLng currentLocation) {
        LatLng latLng=new LatLng(currentLocation.getLatitude(),currentLocation.getLongitude());
        if (aMap != null) {
            // Add or update marker for current location
            if (currentLocationMarker == null) {
                currentLocationMarker = aMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            } else {
                currentLocationMarker.setPosition(latLng);
            }

            // Move camera to user's location
            CameraPosition cameraPosition = new CameraPosition.Builder()
                    .target(latLng)
                    .zoom(18) // Set appropriate zoom level
                    .build();
            aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
        }
    }

    // 停止导航
    public void stopNavigation() {
        mAMapNavi.stopNavi();
        Log.d("AMapNavi", "Navigation stopped");
    }

    // 清理资源
    public void destroy() {
        if (mAMapNavi != null) {
            mAMapNavi.removeAMapNaviListener(mAMapNaviListener);
            AMapNavi.destroy();
        }
    }

    // 获取导航实例
    public AMapNavi getAMapNavi() {
        return mAMapNavi;
    }
}
*/