package com.example.walkpromote22.data.model;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "routes")
public class Route {

    @PrimaryKey
    private long id;

    @NonNull
    private String name; // 路线名称

    // 起点对应的 Location 的 id


    // 构造方法
    public Route(long id,@NonNull String name) {
        this.name = name;
        this.id=id;
    }
    @Ignore
    public Route(@NonNull String name){
        this.name=name;
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


}
