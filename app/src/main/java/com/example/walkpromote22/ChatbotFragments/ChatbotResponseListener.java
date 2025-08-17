package com.example.walkpromote22.ChatbotFragments;

public interface ChatbotResponseListener {
    void onResponse(String reply);
    void onFailure(String error);
}
