package com.example.walkpromote22.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;


import com.example.walkpromote22.data.model.POI;

import java.util.List;


@Dao
public interface POIDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertReplace(POI poi);

    @Query("SELECT * FROM poi WHERE areaKey=:areaKey LIMIT :limit")
    List<POI> queryByAreaKey(String areaKey, int limit);


    @Query("SELECT * FROM poi WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng LIMIT :limit")
    List<POI> queryByBBox(double minLat, double maxLat, double minLng, double maxLng, int limit);
}