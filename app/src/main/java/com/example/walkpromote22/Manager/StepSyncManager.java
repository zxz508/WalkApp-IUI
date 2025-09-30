package com.example.walkpromote22.Manager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.dto.StepDTO;
import com.example.walkpromote22.data.model.Step;
import com.example.walkpromote22.service.ApiService;
import com.example.walkpromote22.tool.UserPreferences;


import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 负责「本地 Room ↔ 云端」步数同步的小助手。
 * 1) importRemoteStepsIfFirstLogin()  —— 首次登录时拉取云端全部历史步数
 * 2) syncLocalToCloud()               —— 把本地全部（或增量）步数批量上传
 */
// ① import 语句增加一行


public class StepSyncManager {

    private static final String BASE_URL = "http://101.132.96.134:8080/";   // 末尾 /
    private static final String KEY_FIRST = "IMPORT_DONE";

    private final ApiService       api;
    private final StepDao          stepDao;
    private final ExecutorService  io;
    private final SharedPreferences sp;          // 只存 “第一次导入” 标记
    private final UserPreferences  userPref;     // ★ 统一管理 userKey

    public StepSyncManager(@NonNull Context ctx) {

        /* ---------- 1. OkHttp + 日志 ---------- */
        HttpLoggingInterceptor log = new HttpLoggingInterceptor(
                msg -> Log.d("OkHttp", msg));
        log.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient okHttp = new OkHttpClient.Builder()
                .addInterceptor(log)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        /* ---------- 2. Retrofit ---------- */
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttp)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        api      = retrofit.create(ApiService.class);
        stepDao  = AppDatabase.getDatabase(ctx).stepDao();
        io       = Executors.newSingleThreadExecutor();

        /* ---------- 3. 本地持久化 ---------- */
        sp       = ctx.getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        userPref = new UserPreferences(ctx);                       // ★ 和 LoginActivity 一致
    }


    /* ---------------- ① 首次登录：拉全部历史 ---------------- */
    public void importHistorySteps() {


        String userKey = userPref.getUserKey();
        if (userKey == null) return;

        io.execute(() -> {
            try {
                Response<List<StepDTO>> r = api.getAllSteps(userKey).execute();
                if (!r.isSuccessful() || r.body() == null) return;

                stepDao.deleteAllSteps();
                for (StepDTO dto : r.body()) {
                    stepDao.insertStep(new Step(dto.getUserKey(), dto.getDate(),
                            dto.getStepCount(), dto.getDistance()));
                }
                sp.edit().putBoolean(KEY_FIRST, true).apply();
                Log.e("tag","成功拉取云端步数数据");
            } catch (IOException ignored) { }
        });
    }

    /* ---------------- ② 上传今日单条记录 ---------------- */
    public void uploadToday() {
        String userKey = userPref.getUserKey();
        if (userKey == null) return;

        @SuppressLint("SimpleDateFormat")
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

        io.execute(() -> {
            Step s = stepDao.getStepByDate(userKey, today);
            if (s == null) return;
            if (s.getStepCount() < 0) s.setStepCount(0);

            List<StepDTO> one = Collections.singletonList(
                    new StepDTO(s.getUserKey(), s.getDate(),
                            s.getStepCount(), s.getDistance()));

            try { api.uploadSteps(one).execute(); } catch (IOException ignored) { }
        });
    }

}

