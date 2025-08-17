package com.example.walkpromote22.tool;

public class UserInfo {
    private String id;
    private String name;
    private String photo;
    private String token;

    public UserInfo(String id, String name, String photo, String token) {
        this.id = id;
        this.name = name;
        this.photo = photo;
        this.token = token;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPhoto() { return photo; }
    public String getToken() { return token; }
}
