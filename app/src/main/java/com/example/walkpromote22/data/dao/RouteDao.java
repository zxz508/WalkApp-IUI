package com.example.walkpromote22.data.dao;

import static androidx.room.OnConflictStrategy.REPLACE;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Route;

import java.util.List;

@Dao
public interface RouteDao {

    @Insert(onConflict = REPLACE)
    void insert(Route route);

    @Update
    void updateRoute(Route route); // 更新路线记录

    @Delete
    void deleteRoute(Route route); // 删除路线记录

    @Query("SELECT * FROM routes WHERE id = :id LIMIT 1")
    Route getRouteById(long id); // 根据 id 查询路线记录

    @Query("SELECT * FROM routes")
    List<Route> getAllRoutes(); // 查询所有路线记录

    @Query("SELECT * FROM locations WHERE routeId = :routeId")
    List<Location> getLocationsForRoute(long routeId);

    @Query("SELECT * FROM routes WHERE userKey = :userKey ORDER BY createdAt DESC LIMIT 10")
    List<Route> getRoutesByUserKey(String userKey);
    @Query("DELETE FROM routes")
    void deleteAll(); // 删除所有路线记录

    @Insert
    void insertAll(List<Route> routes); // 插入多条路线
}
