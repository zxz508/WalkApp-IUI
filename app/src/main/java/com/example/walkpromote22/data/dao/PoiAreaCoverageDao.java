package com.example.walkpromote22.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.walkpromote22.data.model.PoiAreaCoverage;

import java.util.List;

// PoiAreaCoverageDao.java
// PoiAreaCoverageDao.java  (Java 8 兼容)
@Dao
public interface PoiAreaCoverageDao {

    @Query("SELECT coveredAt FROM poi_area_coverage WHERE areaKey=:areaKey LIMIT 1")
    Long getCoveredAt(String areaKey);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PoiAreaCoverage cov);

    // ★ 用 LIKE :prefix || '%'，Room/SQLite 使用 '||' 作为字符串拼接
    @Query("SELECT * FROM poi_area_coverage " +
            "WHERE areaKey LIKE :prefix || '%' " +
            "AND coveredAt > :freshAfter " +
            "AND minLat <= :reqMinLat AND maxLat >= :reqMaxLat " +
            "AND minLng <= :reqMinLng AND maxLng >= :reqMaxLng " +
            "LIMIT 1")
    PoiAreaCoverage findFreshCoveringPrefix(String prefix,
                                            long freshAfter,
                                            double reqMinLat, double reqMaxLat,
                                            double reqMinLng, double reqMaxLng);
}

