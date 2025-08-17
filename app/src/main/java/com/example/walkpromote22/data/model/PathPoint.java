package com.example.walkpromote22.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ForeignKey;

import androidx.room.Index;

@Entity(tableName = "path_points",
        indices = {@Index("pathId")},
        foreignKeys = @ForeignKey(entity = Path.class,
                parentColumns = "pathId",
                childColumns = "pathId",
                onDelete = ForeignKey.CASCADE))
public class PathPoint {

    @PrimaryKey(autoGenerate = true)
    private long pointId;

    // 外键，关联到 Path 表的 pathId
    private long pathId;

    private long timestamp;  // 记录采集时的时间戳
    private double latitude; // 纬度
    private double longitude; // 经度

    // 构造方法
    public PathPoint(long pathId, long timestamp, double latitude, double longitude) {
        this.pathId = pathId;
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    // Getters 和 Setters
    public long getPointId() {
        return pointId;
    }

    public void setPointId(long pointId) {
        this.pointId = pointId;
    }

    public long getPathId() {
        return pathId;
    }

    public void setPathId(long pathId) {
        this.pathId = pathId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }
}
