package com.example.walkpromote22.data.dto;

import androidx.annotation.NonNull;
import androidx.room.PrimaryKey;

public class RouteDTO {


    private long id;


    private String name; // 路线名称

    // 起点对应的 Location 的 id

    // 构造方法
    public RouteDTO(long id,String name) {
        this.id=id;
        this.name = name;

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
