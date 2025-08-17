package com.example.walkpromote22.data.dto;

public class UserDTO {
    private String userKey;
    private String username;
    private String password;
    private String gender;

    private float height;
    private float weight;
    private int age;

    public UserDTO(String userKey,String username,String password){
        this.username=username;
        this.userKey = userKey;
        this.password = password;
    }
    public UserDTO(String userKey, String username, String password, String genger, float height, float weight, int age) {
        this.username=username;
        this.userKey = userKey;
        this.password = password;
        this.gender = genger;
        this.age=age;
        this.height=height;
        this.weight=weight;
    }

    // Getter & Setter（必须有）
    public String getUserKey() { return userKey; }
    public void setUserKey(String userKey) { this.userKey = userKey; }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public void setWeight(float weight) {
        this.weight = weight;
    }

    public float getHeight() {
        return height;
    }

    public float getWeight() {
        return weight;
    }

    public int getAge() {
        return age;
    }

    public void setGenger(String genger) {
        this.gender = genger;
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }


    public String getGender() {
        return gender;
    }
}

