package com.example.walkpromote22.data.dao;

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

    @Insert
    void insert(Route route); // 插入路线记录，并返回生成的 id

    @Update
    void updateRoute(Route route); // 更新路线记录

    @Delete
    void deleteRoute(Route route); // 删除路线记录

    @Query("SELECT * FROM routes WHERE id = :id LIMIT 1")
    Route getRouteById(long id); // 根据 id 查询路线记录

    @Query("SELECT * FROM routes")
    List<Route> getAllRoutes(); // 查询所有路线记录

    @Query("SELECT * FROM locations WHERE route_id = :routeId")
    List<Location> getLocationsForRoute(long routeId);


    @Query("DELETE FROM routes")
    void deleteAll(); // 删除所有路线记录

    @Insert
    void insertAll(List<Route> routes); // 插入多条路线
}
