package com.example.walkpromote22.Manager;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.walkpromote22.data.dao.LocationDao;
import com.example.walkpromote22.data.dao.RouteDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.dto.LocationDTO;
import com.example.walkpromote22.data.dto.RouteDTO;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Route;
import com.example.walkpromote22.service.ApiService;
import com.example.walkpromote22.tool.UserPreferences;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RouteSyncManager {

    private final ApiService api;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final UserPreferences userPref;
    private final RouteDao routeDao;
    private final Context context;
    private final LocationDao locationDao;

    public RouteSyncManager(@NonNull Context ctx) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://101.132.96.134:8080/") // 末尾 /
                .client(new OkHttpClient())             // 省略日志拦截器
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        context=ctx;
        api          = retrofit.create(ApiService.class);
        userPref     = new UserPreferences(ctx);
        AppDatabase db = AppDatabase.getDatabase(ctx);
        routeDao     = db.routeDao();
        locationDao  = db.locationDao();
    }

    /** 向云端创建路线，回调云端生成的 id */
    public void createRoute(String name, OnRouteCreated cb) {
        io.execute(() -> {
            String userKey = userPref.getUserKey();
            if (userKey == null) { cb.onFail(new IllegalStateException("not login")); return; }

            try {
                RouteDTO dto = new RouteDTO();

                dto.setName(name);
                Response<Long> r = api.createRoute(dto).execute();
                if (r.isSuccessful() && r.body() != null) {
                    cb.onSuccess(r.body());
                } else {
                    cb.onFail(new IOException("createRoute status=" + r.code()));
                }
            } catch (Exception e) { cb.onFail(e); }
        });
    }

    /** 批量上传新地点 */
    public void uploadLocations(List<LocationDTO> list) {
        io.execute(() -> {
            try {
                Response<Void> response = api.uploadLocations(list).execute();
                if (response.isSuccessful()) {

                    Log.i("UPLOAD", "Upload successful: " + response.body());
                } else {
                    Log.e("UPLOAD", "Upload failed. Code: " + response.code() + ", Message: " + response.message());
                }
            } catch (Exception e) {
                Log.e("UPLOAD", "Exception during upload", e);
            }

        });
    }

    public void fetchAllRoutes(OnRoutesFetched callback) {
        io.execute(() -> {
            try {
                Response<List<RouteDTO>> response = api.getAllRoutes().execute();
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onFail(new IOException("Fetch all routes failed, status=" + response.code()));
                }
            } catch (Exception e) {
                callback.onFail(e);
            }
        });
    }

    public void fetchAllLocations(OnLocationsFetched callback) {
        io.execute(() -> {
            try {
                Response<List<LocationDTO>> response = api.getAllLocations().execute();
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onFail(new IOException("Fetch all locations failed, status=" + response.code()));
                }
            } catch (Exception e) {
                callback.onFail(e);
            }
        });
    }

    public void syncFromCloudToLocal() {
        fetchAllRoutes(new OnRoutesFetched() {
            @Override
            public void onSuccess(List<RouteDTO> routes) {
                AppDatabase.getDatabase(context).routeDao().deleteAll();  // 删除本地数据
                for (RouteDTO dto : routes) {
                    Route route = new Route(dto.getId(), dto.getName());
                    AppDatabase.getDatabase(context).routeDao().insert(route);
                }
            }

            @Override
            public void onFail(Exception e) {
                e.printStackTrace();
            }
        });

        fetchAllLocations(new OnLocationsFetched() {
            @Override
            public void onSuccess(List<LocationDTO> locations) {
                AppDatabase.getDatabase(context).locationDao().deleteAll();  // 删除本地数据
                for (LocationDTO dto : locations) {
                    Location location = new Location(dto.getIndex_num(),dto.getId(),dto.getRoute_id(), dto.getName(), dto.getLatitude(), dto.getLongitude(), dto.getFeatures());
                    AppDatabase.getDatabase(context).locationDao().insert(location);
                }
            }

            @Override
            public void onFail(Exception e) {
                e.printStackTrace();
            }
        });
    }






    public interface OnRoutesFetched {
        void onSuccess(List<RouteDTO> routes);
        void onFail(Exception e);
    }

    public interface OnLocationsFetched {
        void onSuccess(List<LocationDTO> locations);
        void onFail(Exception e);
    }



    public interface OnRouteCreated {

        void onSuccess(long routeId);

        void onFail(Exception e);
    }
}
