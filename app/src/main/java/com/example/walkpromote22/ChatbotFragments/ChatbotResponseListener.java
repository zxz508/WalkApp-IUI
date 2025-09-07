package com.example.walkpromote22.ChatbotFragments;

import org.json.JSONException;

public interface ChatbotResponseListener {
    void onResponse(String reply) throws JSONException;
    void onFailure(String error) throws JSONException;
}
