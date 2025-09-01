package com.example.walkpromote22.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Delete;

import com.example.walkpromote22.data.model.Location;

import java.util.List;

@Dao
public interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Location location);


    @Update
    void updateLocation(Location location); // 更新地图记录

    @Delete
    void deleteLocation(Location location); // 删除地图记录

    @Query("DELETE FROM locations")
    void deleteAll();



    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    Location getLocationById(long id); // 根据 id 查询地图记录

    @Query("SELECT * FROM locations WHERE routeId = :routeId ORDER BY id ASC")
    List<Location> getLocationsByRouteId(long routeId);

    @Query("SELECT * FROM locations")
    List<Location> getAllLocation(); // 查询所有地图记录


    @Query("SELECT * FROM locations ORDER BY longitude ASC")
    List<Location> getLocationsSortedByLongitude();

    @Query("SELECT * FROM locations ORDER BY latitude ASC")
    List<Location> getLocationsSortedByLatitude();

    @Query("SELECT * FROM locations WHERE ABS(latitude - :latitude) < :threshold AND ABS(longitude - :longitude) < :threshold")
    List<Location> getLocationsNear(double latitude, double longitude, double threshold);

}
