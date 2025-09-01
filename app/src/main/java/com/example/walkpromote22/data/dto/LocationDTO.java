package com.example.walkpromote22.data.dto;

public class LocationDTO {
    private long id;
    private int indexNum;
    private long routeId;    // 服务器返回的真 routeId
    private String name;
    private double latitude;
    private double longitude;
    private int features;
    public LocationDTO(){};
    public LocationDTO(long id, int indexNum, long routeId, String name, double latitude, double longitude, int features){
        this.id=id;
        this.indexNum = indexNum;
        this.features=features;
        this.name=name;
        this.latitude=latitude;
        this.routeId = routeId;
        this.longitude=longitude;

    }

    public LocationDTO(long id, int i, long routeId, String name, double latitude, double longitude) {
        this.id=id;
        this.indexNum=i;
        this.routeId=routeId;
        this.name=name;
        this.latitude=latitude;
        this.longitude=longitude;
    }

    public void setIndexNum(int indexNum) {
        this.indexNum = indexNum;
    }

    public int getIndexNum() {
        return indexNum;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }

    public void setFeatures(int features) {
        this.features = features;
    }

    public int getFeatures() {
        return features;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getRouteId() {
        return routeId;
    }

    public void setRouteId(long routeId) {
        this.routeId = routeId;
    }

    public String getName() {
        return name;
    }
}