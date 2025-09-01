package com.example.walkpromote22.data.model;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

import java.time.Instant;

@Entity(tableName = "routes")
public class Route {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private long createdAt;
    @NonNull
    private String userKey;
    @NonNull
    private String name; // 路线名称

    // 起点对应的 Location 的 id
    private String description;


    // 构造方法
    public Route(long id,@NonNull String userKey,@NonNull String name,String description,long createdAt) {
        this.userKey=userKey;
        this.createdAt=createdAt;
        this.description=description;
        this.name = name;
        this.id=id;
    }


    @Ignore
    public Route() {

    }


    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getDescription() {
        return description;
    }

    @NonNull
    public String getUserKey() {
        return userKey;
    }

    public void setUserKey(@NonNull String userKey) {
        this.userKey = userKey;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    // Getter 和 Setter 方法
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Route)) return false;
        Route other = (Route) o;
        return other.id==this.id;
    }
}
