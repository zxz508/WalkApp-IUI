package com.example.walkpromote22.tool;

// Tool.java
public interface Tool {
    String name();
    org.json.JSONObject call(org.json.JSONObject args) throws Exception;
}
