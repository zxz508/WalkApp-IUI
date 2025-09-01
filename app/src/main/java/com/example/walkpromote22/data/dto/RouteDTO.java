package com.example.walkpromote22.data.dto;

import androidx.annotation.NonNull;
import androidx.room.PrimaryKey;

import java.time.Instant;

public class RouteDTO {


    private long id;


    private long createdAt;
    private String userKey;
    private String name; // 路线名称

    // 起点对应的 Location 的 id

    private String description;
    // 构造方法
    public RouteDTO(long id,String name,String description,String userKey,long createdAt) {
        this.createdAt=createdAt;
        this.userKey=userKey;
        this.id=id;
        this.description=description;
        this.name = name;

    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setUserKey(String userKey) {
        this.userKey = userKey;
    }

    public String getUserKey() {
        return userKey;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public RouteDTO(String name){
        this.name=name;
    }

    public RouteDTO() {

    }

    // Getter 和 Setter 方法
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


}
