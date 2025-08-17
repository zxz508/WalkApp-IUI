package com.example.walkpromote22.tool;


import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BaiduTranslateHelper {

    private static final String APP_ID = "20250621002386898";
    private static final String APP_SECRET = "rhwlLW32xyeF2uVTRJps";
    private static final String TAG = "BaiduTranslate";

    public interface TranslateCallback {
        void onTranslated(String englishText);
        void onError(String error);
    }

    public static void translateToEnglish(String chineseText, TranslateCallback callback) {
        new Thread(() -> {
            try {
                String salt = String.valueOf(System.currentTimeMillis());
                String sign = md5(APP_ID + chineseText + salt + APP_SECRET);

                String url = String.format(Locale.US,
                        "https://fanyi-api.baidu.com/api/trans/vip/translate?q=%s&from=zh&to=en&appid=%s&salt=%s&sign=%s",
                        URLEncoder.encode(chineseText, "UTF-8"), APP_ID, salt, sign);

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(url).build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        callback.onError("翻译请求失败：" + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String res = response.body().string();
                        Log.e(TAG, "原始响应：" + res);
                        try {
                            JSONObject json = new JSONObject(res);
                            JSONArray arr = json.getJSONArray("trans_result");
                            String translated = arr.getJSONObject(0).getString("dst");
                            callback.onTranslated(translated);
                        } catch (Exception e) {
                            e.printStackTrace();
                            callback.onError("翻译解析失败：" + e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                callback.onError("翻译异常：" + e.getMessage());
            }
        }).start();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] array = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : array) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
