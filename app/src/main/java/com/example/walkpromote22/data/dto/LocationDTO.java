package com.example.walkpromote22.data.dto;

public class LocationDTO {
    private long id;
    private int index_num;
    private long route_id;    // 服务器返回的真 routeId
    private String name;
    private double latitude;
    private double longitude;
    private int features;
    public LocationDTO(){};
    public LocationDTO(long id, int index_num, long route_id, String name, double latitude, double longitude, int features){
        this.id=id;
        this.index_num = index_num;
        this.features=features;
        this.name=name;
        this.latitude=latitude;
        this.route_id = route_id;
        this.longitude=longitude;

    }

    public void setIndex_num(int index_num) {
        this.index_num = index_num;
    }

    public int getIndex_num() {
        return index_num;
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

    public long getRoute_id() {
        return route_id;
    }

    public void setRoute_id(long route_id) {
        this.route_id = route_id;
    }

    public String getName() {
        return name;
    }
}