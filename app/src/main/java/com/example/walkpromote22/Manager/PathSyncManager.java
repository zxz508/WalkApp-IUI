package com.example.walkpromote22.Manager;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.walkpromote22.data.dao.PathDao;
import com.example.walkpromote22.data.dao.PathPointDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.dto.PathDTO;
import com.example.walkpromote22.data.dto.PathPointDTO;
import com.example.walkpromote22.data.model.Path;
import com.example.walkpromote22.data.model.PathPoint;
import com.example.walkpromote22.service.ApiService;
import com.example.walkpromote22.tool.UserPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 只负责 Path / PathPoint 的上传与下载（云 <-> 本地 Room）。
 * 注意：Path 在本地已创建并包含完整字段（含 pathId），上传时直接上送，无需云端分配 ID。
 * 不牵涉 Route；PathPoint 仅以 pathId 关联 Path。
 */
public final class PathSyncManager {

    private static final String TAG = "PathSyncManager";
    private static final String BASE_URL = "http://101.132.96.134:8080/"; // 末尾需有 /

    private final ApiService api;
    private final PathDao pathDao;
    private final PathPointDao pointDao;
    private final ExecutorService io;
    private final UserPreferences userPref;

    public PathSyncManager(@NonNull Context ctx) {
        // OkHttp + 日志
        HttpLoggingInterceptor log = new HttpLoggingInterceptor(message -> Log.d(TAG, message));
        log.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(log)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        this.api = retrofit.create(ApiService.class);
        AppDatabase db = AppDatabase.getDatabase(ctx.getApplicationContext());
        this.pathDao = db.pathDao();
        this.pointDao = db.pathPointDao();
        this.io = Executors.newSingleThreadExecutor();
        this.userPref = new UserPreferences(ctx.getApplicationContext());
    }

    // ========================================================================
    // 上传 —— Path（本地已是完整对象，包含 pathId；这里不做云端 create）
    // ========================================================================

    /** 上传单条 Path（必须已包含 pathId、userKey 等完整字段） */
    public void uploadPath(@NonNull Path path) {
        io.execute(() -> {
            try {
                List<PathDTO> one = Collections.singletonList(toDTO(path));
                Response<Void> r = api.uploadPaths(one).execute();
                if (!r.isSuccessful()) {
                    Log.e(TAG, "uploadPath failed, code=" + r.code() + ", msg=" + r.message());
                } else {
                    Log.i(TAG, "uploadPath success. pathId=" + path.getPathId());
                }
            } catch (IOException e) {
                Log.e(TAG, "uploadPath exception", e);
            }
        });
    }

    /** 批量上传多条 Path（均为本地已创建的完整对象） */
    public void uploadPaths(@NonNull List<Path> paths) {
        io.execute(() -> {
            if (paths.isEmpty()) return;
            try {
                List<PathDTO> list = new ArrayList<>(paths.size());
                for (Path p : paths) list.add(toDTO(p));
                Response<Void> r = api.uploadPaths(list).execute();
                if (!r.isSuccessful()) {
                    Log.e(TAG, "uploadPaths failed, code=" + r.code() + ", msg=" + r.message());
                } else {
                    Log.i(TAG, "uploadPaths success. size=" + list.size());
                }
            } catch (IOException e) {
                Log.e(TAG, "uploadPaths exception", e);
            }
        });
    }

    // ========================================================================
    // 上传 —— PathPoint（仅以 pathId 关联 Path）
    // ========================================================================

    /** 上传指定 pathId 的所有点（从本地 DB 读取） */
    public void uploadAllPointsOf(long pathId) {
        io.execute(() -> {
            List<PathPoint> points = pointDao.getPathPointsByPathId(pathId);
            if (points == null || points.isEmpty()) return;
            doUploadPoints(points);
        });
    }

    /** 直接上传传入的点位列表（需保证每个点的 pathId 已设置） */
    public void uploadPathPoints(@NonNull List<PathPoint> points) {
        io.execute(() -> doUploadPoints(points));
    }

    private void doUploadPoints(@NonNull List<PathPoint> points) {
        if (points.isEmpty()) return;
        try {
            List<PathPointDTO> list = new ArrayList<>(points.size());
            for (PathPoint p : points) list.add(toDTO(p));
            Response<Void> r = api.uploadPathPoints(list).execute();
            if (!r.isSuccessful()) {
                Log.e(TAG, "uploadPathPoints failed, code=" + r.code() + ", msg=" + r.message());
            } else {
                Log.i(TAG, "uploadPathPoints success. size=" + list.size());
            }
        } catch (IOException e) {
            Log.e(TAG, "uploadPathPoints exception", e);
        }
    }

    // ========================================================================
    // 下载 —— 云 => 本地
    // ========================================================================

    /** 拉取该用户的所有 Path（不含点），并写入本地（如有冲突请按你 DAO 的 upsert 处理） */
    public void pullPathsForCurrentUser() {
        io.execute(() -> {
            String userKey = userPref.getUserKey();
            if (userKey == null || userKey.isEmpty()) {
                Log.w(TAG, "pullPathsForCurrentUser: empty userKey");
                return;
            }
            try {
                Response<List<PathDTO>> r = api.getPathsByUserKey(userKey).execute();
                if (!r.isSuccessful() || r.body() == null) {
                    Log.e(TAG, "getPathsByUserKey failed, code=" + r.code() + ", msg=" + r.message());
                    return;
                }
                List<PathDTO> fromCloud = r.body();
                for (PathDTO dto : fromCloud) {
                    try { pathDao.insert(fromDTO(dto)); } catch (Throwable t) {
                        // 如果你有 upsert，这里改成 upsert；没有就 try-catch 忽略主键冲突
                        Log.d(TAG, "insert path local maybe exists, pathId=" + dto.getPathId());
                    }
                }
                Log.i(TAG, "pullPathsForCurrentUser done. size=" + fromCloud.size());
            } catch (IOException e) {
                Log.e(TAG, "pullPathsForCurrentUser exception", e);
            }
        });
    }

    /** 拉取指定 pathId 的所有点位，并写入本地 */
    public void pullPointsOf(long pathId) {
        io.execute(() -> {
            try {
                Response<List<PathPointDTO>> r = api.getPathPointsByPathId(pathId).execute();
                if (!r.isSuccessful() || r.body() == null) {
                    Log.e(TAG, "getPathPointsByPathId failed, code=" + r.code() + ", msg=" + r.message());
                    return;
                }
                List<PathPointDTO> fromCloud = r.body();
                List<PathPoint> entities = new ArrayList<>(fromCloud.size());
                for (PathPointDTO d : fromCloud) entities.add(fromDTO(d));
                try { pointDao.insertAll(entities); } catch (Throwable t) {
                    Log.d(TAG, "insert points local maybe exists, pathId=" + pathId);
                }
                Log.i(TAG, "pullPointsOf done. pathId=" + pathId + ", size=" + fromCloud.size());
            } catch (IOException e) {
                Log.e(TAG, "pullPointsOf exception", e);
            }
        });
    }

    /** 便捷：云端全量 -> 本地（按 userKey 拉 Path，再逐条拉 Points） */
    public void pullAllForCurrentUser() {
        io.execute(() -> {
            String userKey = userPref.getUserKey();
            if (userKey == null || userKey.isEmpty()) {
                Log.w(TAG, "pullAllForCurrentUser: empty userKey");
                return;
            }
            try {
                Response<List<PathDTO>> r = api.getPathsByUserKey(userKey).execute();
                if (!r.isSuccessful() || r.body() == null) {
                    Log.e(TAG, "getPathsByUserKey failed, code=" + r.code() + ", msg=" + r.message());
                    return;
                }
                List<PathDTO> paths = r.body();
                for (PathDTO dto : paths) {
                    Path p = fromDTO(dto);
                    try { pathDao.insert(p); } catch (Throwable ignore) {}
                    // 逐条拉点位
                    try {
                        Response<List<PathPointDTO>> r2 = api.getPathPointsByPathId(p.getPathId()).execute();
                        if (r2.isSuccessful() && r2.body() != null) {
                            List<PathPoint> pts = new ArrayList<>(r2.body().size());
                            for (PathPointDTO pd : r2.body()) pts.add(fromDTO(pd));
                            try { pointDao.insertAll(pts); } catch (Throwable ignore) {}
                        }
                    } catch (IOException e2) {
                        Log.e(TAG, "pull points failed, pathId=" + p.getPathId(), e2);
                    }
                }
                Log.i(TAG, "pullAllForCurrentUser done. paths=" + paths.size());
            } catch (IOException e) {
                Log.e(TAG, "pullAllForCurrentUser exception", e);
            }
        });
    }

    // ========================================================================
    // DTO <-> 实体 映射（不在 PathDTO 中嵌 points）
    // 按你的字段签名微调参数/顺序即可
    // ========================================================================

    private static PathDTO toDTO(@NonNull Path p) {
        // 假设 PathDTO: (pathId, userKey, startTimestamp, endTimestamp, distance, calories, averageSpeed, summary, routeImagePath)
        return new PathDTO(
                p.getUserKey(),
                p.getPathId(),
                p.getRouteImagePath(),
                p.getStartTimestamp(),
                p.getEndTimestamp(),
                p.getDistance(),
                p.getSummary()

        );

    }

    private static Path fromDTO(@NonNull PathDTO d) {
        // 如果你的 Path 没有这个构造，请改成 new + setXxx
        Path p = new Path(
                d.getUserKey(),
                d.getPathId(),
                d.getRouteImagePath(),
                d.getStartTimestamp(),
                d.getEndTimestamp(),
                d.getDistance(),
                d.getSummary()
        );
        p.setPathId(d.getPathId());
        p.setRouteImagePath(d.getRouteImagePath());
        return p;
    }

    private static PathPointDTO toDTO(@NonNull PathPoint pt) {
        // 假设 PathPointDTO: (pointId, pathId, timestamp, latitude, longitude)
        return new PathPointDTO(
                pt.getPathId(),
                pt.getTimestamp(),
                pt.getLatitude(),
                pt.getLongitude()
        );
    }

    private static PathPoint fromDTO(@NonNull PathPointDTO d) {
        PathPoint pt = new PathPoint(
                d.getPathId(),
                d.getTimestamp(),
                d.getLatitude(),
                d.getLongitude()
        );
        pt.setPointId(d.getPointId());
        return pt;
    }
}

