package com.example.walkpromote22.ChatbotFragments;

import android.view.View;

public class Message {
    private String text;       // 用于存储文本消息
    private View customView;   // 用于存储自定义 View 消息
    private boolean isUser;    // true：用户消息；false：机器人消息

    // 构造文本消息
    public Message(String text, boolean isUser) {
        this.text = text;
        this.isUser = isUser;
        this.customView = null;
    }

    // 构造自定义 View 消息
    public Message(View customView, boolean isUser) {
        this.customView = customView;
        this.isUser = isUser;
        this.text = null;
    }

    // 判断是否为文本消息
    public boolean isText() {
        return text != null;
    }

    // 判断是否为自定义 View 消息
    public boolean isCustomView() {
        return customView != null;
    }

    public String getText() {
        return text;
    }

    public View getCustomView() {
        return customView;
    }

    public boolean isUser() {
        return isUser;
    }
}
