package com.example.walkpromote22.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.walkpromote22.data.model.PathPoint;

import java.util.List;

@Dao
public interface PathPointDao {
    @Insert
    void insertPathPoint(PathPoint pathPoint);

    @Query("SELECT * FROM path_points WHERE pathId = :pathId ORDER BY timestamp ASC")
    List<PathPoint> getPathPointsByPathId(long pathId);
}
