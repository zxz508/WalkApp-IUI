package com.example.walkpromote22.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.walkpromote22.data.model.Path;

import java.util.List;

@Dao
public interface PathDao {
    @Insert
    long insertPath(Path path);  // 插入路径记录，并返回生成的 pathId

    @Update
    void updatePath(Path path);

    @Query("SELECT * FROM paths WHERE pathId = :pathId LIMIT 1")
    Path getPathById(long pathId);

    @Query("SELECT * FROM paths WHERE userKey = :userKey ORDER BY startTimestamp DESC")
    List<Path> getPathsByUserKey(String userKey);

    @Query("DELETE FROM paths WHERE userKey = :userKey")
    void clearUserPath(String userKey);


    @Delete
    void deletePath(Path path);  // 删除路径

}

