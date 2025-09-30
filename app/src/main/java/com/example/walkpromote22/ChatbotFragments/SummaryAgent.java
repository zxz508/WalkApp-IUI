package com.example.walkpromote22.ChatbotFragments;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
// 在文件顶部 import 增加：
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SummaryAgent {
    /** 发帖结果回调（已切回主线程） */
    public interface MastoPostListener {
        void onSuccess(@NonNull JSONObject resp);                 // 含 id、url、created_at、content 等
        void onFailure(@NonNull String error, @Nullable JSONObject resp);
    }

    public static void postStatusToMastodonAsync(
            @NonNull String accessToken,
            @NonNull String statusText,
            @NonNull MastoPostListener listener
    ) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            JSONObject json = null;
            try {
                final String url = "https://mastodon.social/api/v1/statuses";
                final String body = "status=" + URLEncoder.encode(statusText, "UTF-8")
                        + "&visibility=public";

                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                final int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                String resp = sb.toString();
                json = new JSONObject(resp);

                if (code < 200 || code >= 300) {
                    final JSONObject j = json;
                    main.post(() -> listener.onFailure("HTTP_" + code, j));
                    return;
                }
                if (json.opt("id") == null) {
                    final JSONObject j = json;
                    main.post(() -> listener.onFailure("MASTO_BAD_RESP", j));
                    return;
                }
                final JSONObject j = json;
                main.post(() -> listener.onSuccess(j));
            } catch (Throwable t) {
                final JSONObject j = json;
                main.post(() -> listener.onFailure("EXCEPTION:" + t.getClass().getSimpleName(), j));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }


// 在 SummaryAgent 类内新增：

    /** 适配你现有 chatbotHelper.sendMessage(...) 的极简接口，避免 SummaryAgent 直接依赖 ChatbotHelper 类型 */
    // ========== 新增：适配器接口（避免直接依赖 ChatbotHelper 类型） ==========
    public interface GptClient {
        void sendMessage(@NonNull String prompt,
                         @NonNull org.json.JSONArray history,
                         @NonNull GptListener listener) throws JSONException;
    }
    public interface GptListener {
        void onResponse(@NonNull String text);
        void onFailure(@NonNull String error);
    }


    public static void postStatusToMastodonAsync(
            @NonNull String accessToken,
            @NonNull org.json.JSONArray conversationHistory,
            @NonNull MastoPostListener listener
    ) throws JSONException {
        postStatus(accessToken, conversationHistory, listener);
    }


    public static void postStatus(
            @NonNull String accessToken,
            @NonNull org.json.JSONArray conversationHistory,
            @NonNull MastoPostListener listener
    ) throws JSONException {
        final org.json.JSONArray historyExcerpt = buildHistoryExcerpt(conversationHistory, 20);
        final String prompt =buildSummaryPrompt(conversationHistory);

        ChatbotHelper helper = new ChatbotHelper();
        helper.sendMessage(prompt, historyExcerpt, new ChatbotResponseListener() {
            @Override public void onResponse(@NonNull String text) {
                final String cleaned = sanitizeForPosting(text);
                // 复用你的“原方法”发帖（保持你原有的 HTTP/错误处理逻辑）
                postStatusToMastodonAsync(accessToken, cleaned, listener);
            }
            @Override public void onFailure(@NonNull String error) {
                listener.onFailure("GPT_ERROR:" + error, null);
            }
        });
    }

// ========== 新增：工具函数 ==========
    /** 截取最近 lastN 条历史，避免上下文过长 */
    private static org.json.JSONArray buildHistoryExcerpt(@Nullable org.json.JSONArray full, int lastN) {
        if (full == null) return new org.json.JSONArray();
        final int len = full.length();
        final int start = Math.max(0, len - Math.max(1, lastN));
        final org.json.JSONArray out = new org.json.JSONArray();
        for (int i = start; i < len; i++) out.put(full.opt(i));
        return out;
    }

    /** 基于对话历史的生成指令（可根据 visibility 微调“语气/是否加话题”等） */


    /** 基础清洗 + 截断（Mastodon 500 字限制，预留安全余量） */
    private static String sanitizeForPosting(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = s.replaceAll("```[\\s\\S]*?```", "");     // 去代码块
        s = s.replace("{", "（").replace("}", "）");  // 防止花括号
        s = s.replaceFirst("^(?i)PROMOTE[_\\- ]?RESPONSE[:：]\\s*", "");
        if (s.length() > 480) s = s.substring(0, 480);
        return s;
    }

    public static @NonNull String buildSummaryPrompt(@NonNull org.json.JSONArray conversationHistory) {
        // 要求输出“纯文本一段”，尽量短，适合对外发布
        return "You are SummaryAgent. Summarize the finished conversation(Contains a walking assistant, third-party api call results, and user requirements) for a walk in 2–5 sentences. " +
                "Use a friendly, encouraging tone. No code block, no JSON, plain text only.\n" +
                "Walk info (JSON):\n" + conversationHistory.toString();
    }

    /** 本地兜底总结（GPT 不可用时依然能产出） */


    /** 生成总结文本：优先用 GPT（传入 gpt!=null + history），失败则用本地兜底 */
    public static void generateSummaryTextOrFallback(
            @Nullable org.json.JSONArray history,
            @NonNull  ChatbotResponseListener cb
    ) {
        assert history != null;
        String prompt = buildSummaryPrompt(history);



        try {

            ChatbotHelper helper = new ChatbotHelper();
            helper.sendMessage(prompt, null, new ChatbotResponseListener() {
                @Override public void onResponse(@NonNull String text) throws JSONException {
                    String t = sanitizeForPosting(text); // 你文件里已有的去花括号/截断
                    if (t == null || t.trim().isEmpty()) {
                        cb.onResponse(history.toString());
                    } else {
                        cb.onResponse(t.trim());
                    }
                }
                @Override public void onFailure(@NonNull String error) {
                    Log.e("TAG",error);
                }
            });
        } catch (Throwable e) {
            Log.e("TAG", String.valueOf(e));
        }
    }


}
