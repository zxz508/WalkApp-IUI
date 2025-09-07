package com.example.walkpromote22.data.model;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.annotation.NonNull;

import java.io.Serializable;
import java.security.SecureRandom;

@Entity(tableName = "locations",
        foreignKeys = @ForeignKey(entity = Route.class,
                parentColumns = "id",
                childColumns = "routeId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("routeId")})
public class Location implements Serializable {

    @PrimaryKey
    @NonNull
    private long id=generateRandomLong();

    private int indexNum;

    private Long routeId;  // 允许 routeId 为 null，表示该 Location 可能不绑定任何 Route
    @NonNull
    private String name; // 地点名称

    private double latitude;  // 纬度
    private double longitude; // 经度

    // 新增属性：个人感知安全性和行人基础设施（类型为 boolean）


    private int features;//第一个数字表示风景评分（1-9），第二个数字表示人流量（1-9），第三个数字表示车流量（1-9），第四个数字表示遮荫（1-9），第五个数字表示路面平整度（1-9）

    public Location() {}
    // 构造方法：允许 routeId 为 null，表示不绑定任何 Route
    @Ignore
    public Location(int indexNum, Long routeId, @NonNull String name, double latitude, double longitude, int features) {
        this.routeId = routeId;
        this.indexNum = indexNum;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.features=features;

    }
    @Ignore
    public Location(int indexNum, long id, Long routeId, @NonNull String name, double latitude, double longitude, int features) {
        this.routeId = routeId;
        this.id=id;
        this.indexNum = indexNum;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.features=features;

    }

    public void setIndexNum(int indexNum) {
        this.indexNum = indexNum;
    }

    public int getIndexNum() {
        return indexNum;
    }

    // Getters 和 Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public int getFeatures() {
        return features;
    }

    public int getScenery() {
        return (features / 10000) % 10;
    }

    public int getFootTraffic() {
        return (features / 1000) % 10;
    }

    public int getVehicleTraffic() {
        return (features / 100) % 10;
    }

    public int getShade() {
        return (features / 10) % 10;
    }

    public int getPavementQuality() {
        return features % 10;
    }


    public void setName(@NonNull String name) {
        this.name = name;
    }

    public void setFeatures(int features) {
        this.features = features;
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
    public void setScenery(int scenery) {
        this.features = replaceDigit(this.features, 4, scenery); // 万位
    }

    public void setFootTraffic(int footTraffic) {
        this.features = replaceDigit(this.features, 3, footTraffic); // 千位
    }

    public void setVehicleTraffic(int vehicleTraffic) {
        this.features = replaceDigit(this.features, 2, vehicleTraffic); // 百位
    }

    public void setShade(int shade) {
        this.features = replaceDigit(this.features, 1, shade); // 十位
    }

    public void setPavementQuality(int pavementQuality) {
        this.features = replaceDigit(this.features, 0, pavementQuality); // 个位
    }





    // 重写 equals 和 hashCode 方法，用于比较 Location 对象（比如比较地点是否相同）
    private static final double EPSILON = 1e-6;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Location)) return false;
        Location other = (Location) o;
        return Math.abs(this.latitude - other.latitude) < EPSILON &&
                Math.abs(this.longitude - other.longitude) < EPSILON;
    }
    private int replaceDigit(int number, int position, int newDigit) {
        int[] multipliers = {1, 10, 100, 1000, 10000};
        int lowerPart = number % multipliers[position];
        int upperPart = number / (multipliers[position] * 10) * (multipliers[position] * 10);
        return upperPart + newDigit * multipliers[position] + lowerPart;
    }


    @Override
    public int hashCode() {
        int result = 17;
        long latBits = Double.doubleToLongBits(latitude);
        long lonBits = Double.doubleToLongBits(longitude);
        result = 31 * result + Long.hashCode(latBits);
        result = 31 * result + Long.hashCode(lonBits);
        return result;
    }

    private static final SecureRandom random = new SecureRandom();

    public static long generateRandomLong() {
        return Math.abs(random.nextLong());
    }
}
