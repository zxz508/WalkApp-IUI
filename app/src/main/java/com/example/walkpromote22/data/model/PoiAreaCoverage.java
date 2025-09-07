package com.example.walkpromote22.data.model;


import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

// PoiAreaCoverage.java
@Entity(
        tableName = "poi_area_coverage",
        indices = {
                @Index("areaKey"),
                @Index({"minLat","maxLat","minLng","maxLng"}),
                @Index("coveredAt")
        }
)
public class PoiAreaCoverage {
    @PrimaryKey @NonNull public String areaKey;
    public String polygon;
    public double minLat, maxLat, minLng, maxLng;
    public long coveredAt;
}

