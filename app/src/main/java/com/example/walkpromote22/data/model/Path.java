package com.example.walkpromote22.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;




@Entity(tableName = "paths")
public class Path {

    @PrimaryKey(autoGenerate = true)
    private long pathId;

    private String routeImagePath;
    @NonNull
    private String userKey; // 关联用户

    private long startTimestamp; // 路线开始时间
    private long endTimestamp;   // 路线结束时间
    private double distance;

    private double calories;

    private double averageSpeed;

    public Path(@NonNull String userKey, long startTimestamp, long endTimestamp,double distance,double calories, double averageSpeed) {
        this.userKey = userKey;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.averageSpeed=averageSpeed;
        this.calories=calories;
        this.distance=distance;
    }

    // Getters 和 Setters
    public long getPathId() {
        return pathId;
    }

    public void setPathId(long pathId) {
        this.pathId = pathId;
    }

    @NonNull
    public String getUserKey() {
        return userKey;
    }

    public void setUserKey(@NonNull String userKey) {
        this.userKey = userKey;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public void setAverageSpeed(double averageSpeed) {
        this.averageSpeed = averageSpeed;
    }

    public void setCalories(double calories) {
        this.calories = calories;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public double getAverageSpeed() {
        return averageSpeed;
    }

    public double getCalories() {
        return calories;
    }

    public double getDistance() {
        return distance;
    }

    public String getRouteImagePath() {
        return routeImagePath;
    }

    public void setRouteImagePath(String routeImagePath) {
        this.routeImagePath = routeImagePath;
    }
}
