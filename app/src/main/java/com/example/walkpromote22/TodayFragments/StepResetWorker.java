package com.example.walkpromote22.TodayFragments;


import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Step;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StepResetWorker extends Worker {

    public StepResetWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters) {
        super(context, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        SharedPreferences prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        String currentDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        editor.putString("STEP_DATE", currentDate);
        editor.putFloat("STEP_BASE_COUNT", 0);
        editor.putFloat("DISTANCE_BASE_COUNT", 0f); // 添加重置距离
        editor.apply();

        AppDatabase db = AppDatabase.getDatabase(context);
        StepDao stepDao = db.stepDao();
        String userKey = prefs.getString("USER_KEY", "default_user");
        Step todayStep = stepDao.getStepByDate(userKey, currentDate);

        if (todayStep == null) {
            todayStep = new Step(userKey, currentDate, 0, 0f); // 距离初始化为0
            stepDao.insertStep(todayStep);
        } else {
            todayStep.setStepCount(0);
            todayStep.setDistance(0f); // 每天重置距离
            stepDao.updateStep(todayStep);
        }

        return Result.success();
    }

}

