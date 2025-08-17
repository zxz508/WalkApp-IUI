package com.example.walkpromote22.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "users")
public class User {

    @PrimaryKey
    @NonNull
    private String userKey; // 唯一标识符，不可更改

    private String gender;
    private float height;
    private float weight;
    private int age;

    // 构造方法
    public User(@NonNull String userKey, String gender, float height, float weight, int age) {
        this.userKey = userKey;
        this.gender = gender;
        this.height = height;
        this.weight = weight;
        this.age = age;
    }

    public User(@NonNull String newKey) {
        this.userKey=newKey;
    }

    // Getters 和 Setters
    @NonNull
    public String getUserKey() {
        return userKey;
    }

    public void setUserKey(@NonNull String userKey) {
        this.userKey = userKey;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public float getWeight() {
        return weight;
    }

    public void setWeight(float weight) {
        this.weight = weight;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public boolean hasNull(){
        if (gender == null || gender.isEmpty()) return true;
        if (height <= 0) return true;
        if (weight <= 0) return true;
        if (age <= 0) return true;
        return false;

    }

}
