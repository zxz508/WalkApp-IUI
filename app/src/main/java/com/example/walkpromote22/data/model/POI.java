package com.example.walkpromote22.data.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "poi",
        indices = {
                @Index(value = {"areaKey"}),
                @Index(value = {"lat","lng"})
        }
)
public class POI {
    @PrimaryKey @NonNull public String poiId;
    public String name;
    public String type;
    public String address;
    public double lat;
    public double lng;
    public String areaKey;       // 新增：归属覆盖域
    public long updatedAt;
}