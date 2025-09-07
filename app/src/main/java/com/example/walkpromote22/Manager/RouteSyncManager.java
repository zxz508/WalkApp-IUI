package com.example.walkpromote22.Manager;






import static android.content.ContentValues.TAG;
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

    private static final String TAG = "RouteSyncManager";

    // === 全局静态依赖 ===
    private static Context appContext;                // ApplicationContext
    private static ApiService api;
    private static UserPreferences userPref;
    private static RouteDao routeDao;
    private static LocationDao locationDao;

    // 单线程后台池：保证 DB/网络顺序执行
    private static final ExecutorService io = Executors.newSingleThreadExecutor();

    // uploadUserHistory/createRoute 预置的描述（例如对话历史 + 路线概要 JSON）
    private static volatile String pendingRouteDescription = null;

    private RouteSyncManager() { /* no instance */ }

    /** 在 Application 或首次使用前初始化一次 */
    public static void init(@NonNull Context ctx) {
        appContext = ctx.getApplicationContext();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://101.132.96.134:8080/")
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(ApiService.class);
        userPref = new UserPreferences(appContext);
        AppDatabase db = AppDatabase.getDatabase(appContext);
        routeDao = db.routeDao();
        locationDao = db.locationDao();

    }

    /** 由外部注入待提交的描述（放入 RouteDTO.description） */
    public static void setPendingRouteDescription(String description) {
        pendingRouteDescription = description;
    }

    // --------------------------------------------------------------------------------------------
    // 云端：创建路线（按你原有签名：只传 name），同时补齐 userKey/createdAt/description
    // --------------------------------------------------------------------------------------------


    // --------------------------------------------------------------------------------------------
    // 云端：创建路线（传实体 Route，便于本地 Room 同步写入前先补齐字段）
    // --------------------------------------------------------------------------------------------
    public static void createRoute(Route route, OnRouteCreated cb) {
        io.execute(() -> {
            ensureInitialized();

            try {
                if (route.getUserKey() == null || route.getUserKey().isEmpty()) {
                    String uk = userPref.getUserKey();
                    if (uk == null) { if (cb != null) cb.onFail(new IllegalStateException("not login")); return; }
                    route.setUserKey(uk);
                }
                if (route.getCreatedAt() == 0L) {
                    route.setCreatedAt(System.currentTimeMillis());
                }
                if (route.getName() == null || route.getName().isEmpty()) {
                    route.setName("Route " + route.getCreatedAt());
                }
                if (route.getDescription() == null) {
                    // 若外部已 setPendingRouteDescription，就优先用外部的
                    route.setDescription(pendingRouteDescription != null ? pendingRouteDescription : "");
                }

                RouteDTO dto = toDTO(route);
                Response<Long> r = api.createRoute(dto).execute();
                if (!r.isSuccessful() || r.body() == null) {
                    if (cb != null) cb.onFail(new IOException("createRoute status=" + r.code()));
                    return;
                }
                long routeId = r.body();

                // 回写 id 并本地入库
                route.setId(routeId);
                try {
                    routeDao.insert(route);
                } catch (Exception e) {
                    Log.e(TAG, "Room insert route failed", e);
                }

                if (cb != null) cb.onSuccess(routeId);

            } catch (Exception e) {
                if (cb != null) cb.onFail(e);
            } finally {
                pendingRouteDescription = null;
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // 云端：批量上传新地点（如需本地也存点位，可在成功后插入 Room）
    // --------------------------------------------------------------------------------------------
    public static void uploadLocations(List<LocationDTO> list) {
        io.execute(() -> {
            ensureInitialized();

            try {
                Response<Void> response = api.uploadLocations(list).execute();
                if (response.isSuccessful()) {
                    Log.i(TAG, "Upload locations successful");
                    // 若需要：把云端点位也落本地，请在此映射 DTO -> 你的本地点位实体后 insertAll
                } else {
                    Log.e(TAG, "Upload locations failed. Code=" + response.code()
                            + ", Msg=" + response.message());
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during uploadLocations", e);
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // 云端：查询（按 userKey 拉路线；按 routeId 拉点位）
    // --------------------------------------------------------------------------------------------
    public static void fetchRoutesByUserKey(String userKey, OnRoutesFetched callback) {
        io.execute(() -> {
            ensureInitialized();
            try {
                Response<List<RouteDTO>> resp = api.getRoutesByUserKey(userKey).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    callback.onSuccess(resp.body());
                } else {
                    callback.onFail(new IOException("getRoutesByUserKey status=" + resp.code()));
                }
            } catch (Exception e) {
                callback.onFail(e);
            }
        });
    }

    public static void fetchLocationsByRouteId(long routeId, OnLocationsFetched callback) {
        io.execute(() -> {
            ensureInitialized();
            try {
                Response<List<LocationDTO>> resp = api.getLocationsByRouteId(routeId).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    callback.onSuccess(resp.body());
                } else {
                    callback.onFail(new IOException("getLocationsByRouteId status=" + resp.code()));
                }
            } catch (Exception e) {
                callback.onFail(e);
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // 云 -> 本地 同步：路线与点位
    // 说明：
    //  1) 路线使用 routeDao.insert(...) 落库；
    //  2) 点位严格按你给的实体字段保存：
    //     id：使用 Location 实体默认的 generateRandomLong()（不覆写）
    //     indexNum：用 DTO 的 indexNum
    //     routeId：保存云端的 routeId
    //     name/lat/lng：直接映射
    // --------------------------------------------------------------------------------------------
    public static void syncFromCloudToLocal(String userKey) {
        fetchRoutesByUserKey(userKey, new OnRoutesFetched() {
            @Override
            public void onSuccess(List<RouteDTO> routes) {
                ensureInitialized();

                AppDatabase db = AppDatabase.getDatabase(appContext);

                // 1) 同步 routes（仅该 userKey）
                try {
                    db.runInTransaction(() -> {
                        for (RouteDTO dto : routes) {
                            Route route = new Route();
                            route.setId(dto.getId());
                            route.setUserKey(dto.getUserKey());
                            route.setName(dto.getName());
                            route.setDescription(dto.getDescription());
                            route.setCreatedAt(dto.getCreatedAt());
                            routeDao.insert(route); // 要求 @Insert(onConflict = REPLACE/IGNORE)
                        }
                    });
                    Log.i(TAG, "💾 Routes saved locally for userKey=" + userKey
                            + ", count=" + routes.size());
                } catch (Exception e) {
                    Log.e(TAG, "Save routes locally failed", e);
                }

                // 2) 拉每条 route 的 locations 并本地保存
                for (RouteDTO dto : routes) {
                    final long routeId = dto.getId();
                    fetchLocationsByRouteId(routeId, new OnLocationsFetched() {
                        @Override
                        public void onSuccess(List<LocationDTO> locations) {
                            ensureInitialized();
                            AppDatabase db2 = AppDatabase.getDatabase(appContext);
                            try {
                                db2.runInTransaction(() -> {
                                    for (LocationDTO ldto : locations) {
                                        // 使用实体的默认主键生成（不手动 setId，避免破坏你的 generateRandomLong() 语义）
                                        Location entity = new Location();
                                        entity.setIndexNum(ldto.getIndexNum());
                                        entity.setRouteId(routeId); // 绑定云端 routeId
                                        entity.setName(ldto.getName() == null ? "" : ldto.getName());
                                        entity.setLatitude(ldto.getLatitude());
                                        entity.setLongitude(ldto.getLongitude());
                                        locationDao.insert(entity); // @Insert(onConflict = REPLACE/IGNORE)
                                    }
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "Save locations locally failed: routeId=" + routeId, e);
                            }
                        }

                        @Override
                        public void onFail(Exception e) {
                            Log.e(TAG, "❌ Fetch locations failed: routeId=" + routeId, e);
                        }
                    });
                }
            }

            @Override
            public void onFail(Exception e) {
                Log.e(TAG, "❌ Fetch routes failed: userKey=" + userKey, e);
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // 辅助：实体 -> DTO
    // --------------------------------------------------------------------------------------------
    private static RouteDTO toDTO(Route route) {
        return new RouteDTO(
                route.getId(),
                route.getName(),
                route.getDescription(),
                route.getUserKey(),
                route.getCreatedAt()
        );
    }

    // --------------------------------------------------------------------------------------------
    // 辅助：初始化检查
    // --------------------------------------------------------------------------------------------
    public static void ensureInitialized() {
        if (appContext == null || api == null || routeDao == null || locationDao == null || userPref == null) {
            throw new IllegalStateException("RouteSyncManager not initialized. Call init(context) first.");
        }
    }

    // --------------------------------------------------------------------------------------------
    // 回调接口
    // --------------------------------------------------------------------------------------------
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