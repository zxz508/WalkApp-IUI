package com.example.walkpromote22.data.dto;

import androidx.annotation.NonNull;

public class PathDTO {


    private long pathId;

    private String routeImagePath;

    private String userKey; // 关联用户

    private long startTimestamp; // 路线开始时间
    private long endTimestamp;   // 路线结束时间
    private double distance;

    private String summary;




    public PathDTO(@NonNull String userKey, long pathId, String routeImagePath, long startTimestamp, long endTimestamp, double distance, String summary) {
        this.userKey = userKey;
        this.pathId=pathId;
        this.routeImagePath=routeImagePath;
        this.summary=summary;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.distance=distance;
    }


    // Getters 和 Setters
    public long getPathId() {
        return pathId;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSummary() {
        return summary;
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


    public void setDistance(double distance) {
        this.distance = distance;
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
