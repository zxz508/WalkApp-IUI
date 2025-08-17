package com.example.walkpromote22.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.walkpromote22.data.model.Step;

import java.util.List;
@Dao
public interface StepDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)   // ★ 关键
    void insertStep(Step step);

    @Update
    void updateStep(Step step);

    @Delete
    void deleteStep(Step step);

    @Query("SELECT * FROM steps WHERE userKey = :userId AND date = :date LIMIT 1")
    Step getStepByDate(String userId, String date);

    // 新增 LiveData 查询方法

    @Query("DELETE FROM steps")
    void deleteAllSteps();


    @Query("SELECT * FROM steps WHERE userKey = :userId ORDER BY date ASC")
    List<Step> getAllStepsByUserId(String userId);
}
