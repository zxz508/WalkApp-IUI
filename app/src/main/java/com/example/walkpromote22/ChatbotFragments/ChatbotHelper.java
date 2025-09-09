package com.example.walkpromote22.ChatbotFragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;




public class ChatbotHelper {
    private static final String TAG = "ChatbotHelper";
    // 基础 URL，后续需拼接具体接口路径
    private static final String OPENAI_API_URL = "https://xiaoai.plus/v1";
    private final String apiKey="sk-O62I7CQRETZ1dSFevmJWqdsJtsfWmg91sbBdWY8tJDRbgYTm";;
    private OkHttpClient client;

    public ChatbotHelper() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        client = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)  // 可适当增加写超时
                .readTimeout(60, TimeUnit.SECONDS)   // 增加读取超时时间
                .retryOnConnectionFailure(true)
                .build();

    }

    /**
     * 发送聊天消息，支持传入任意 messages（包括文本、图片类型混合消息），并允许自定义模型、max_tokens、temperature
     */

    private static final int MAX_HISTORY_MESSAGES = 20; // 截断阈值

    /**
     * 发送用户消息，并传递整个对话历史给 GPT。
     * @param userMessage 用户输入的消息文本
     * @param conversationHistory 对话历史（JSONArray），由调用方维护
     * @param listener 回调接口，返回 GPT 回复或错误信息
     */
    public void sendMessage(String userMessage, JSONArray conversationHistory, ChatbotResponseListener listener) throws JSONException {

        // 将用户消息添加到对话历史
        try {
            conversationHistory.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userMessage));
        } catch (Exception e) {
            listener.onFailure("Error updating conversation history: " + e.getMessage());
            return;
        }


        // 传递整个对话历史给 GPT
        JSONArray finalConversationHistory = conversationHistory;
        sendChatMessages("gpt-4o-2024-08-06", conversationHistory, 500, 0.6, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                try {
                    listener.onFailure("Failed to connect to Chatbot: " + e.getMessage());
                } catch (JSONException ex) {
                    throw new RuntimeException(ex);
                }
            }
            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response) throws IOException {
                Log.e("TAG","发送给GPT的消息长度为:"+conversationHistory.toString().length());
                String responseData = response.body() != null ? response.body().string() : null;
                if (response.isSuccessful() && responseData != null) {
                    try {
                        JSONObject jsonObject = new JSONObject(responseData);
                        String chatbotReply = jsonObject.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content").trim();
                        // 将 GPT 回复添加到对话历史

                        listener.onResponse(chatbotReply);
                    } catch (Exception e) {
                        try {
                            listener.onFailure("Failed to get response from Chatbot.");
                        } catch (JSONException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                } else {
                    try {
                        listener.onFailure("Failed to get response from Chatbot.");
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }







    public void sendChatMessages(String model, JSONArray messages, int maxTokens, double temperature, Callback callback) {
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("model", model);
            requestJson.put("messages", messages);
            requestJson.put("max_tokens", maxTokens);
            requestJson.put("temperature", temperature);
            Log.d(TAG, "Request JSON: " + requestJson.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating JSON request body", e);
            callback.onFailure(null, new IOException("Invalid JSON request body"));
            return;
        }
        RequestBody body = RequestBody.create(
                requestJson.toString(),
                MediaType.get("application/json; charset=utf-8")
        );
        // 拼接聊天接口的正确路径 /chat/completions
        Request request = new Request.Builder()
                .url(OPENAI_API_URL + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to connect to ChatGPT", e);
                callback.onFailure(call, e);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Response received successfully");
                    callback.onResponse(call, response);
                } else {
                    String errorMessage = "Unsuccessful response: Code = " + response.code();
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    Log.e(TAG, errorMessage);
                    Log.e(TAG, "Response body: " + errorBody);
                    callback.onFailure(call, new IOException(errorMessage + ", " + errorBody));
                }
            }
        });
    }




    /**
     * 调用 embeddings 接口
     */
    public void createEmbeddings(String input, Callback callback) {
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("model", "text-embedding-ada-002");
            requestJson.put("input", input);
            requestJson.put("encoding_format", "float");
            Log.d(TAG, "Embeddings Request JSON: " + requestJson.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating JSON for embeddings", e);
            callback.onFailure(null, new IOException("Invalid JSON request body"));
            return;
        }
        RequestBody body = RequestBody.create(
                requestJson.toString(),
                MediaType.get("application/json; charset=utf-8")
        );
        // 拼接 embeddings 接口路径
        Request request = new Request.Builder()
                .url(OPENAI_API_URL + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to get embeddings", e);
                callback.onFailure(call, e);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Embeddings response received");
                    callback.onResponse(call, response);
                } else {
                    String errorMessage = "Unsuccessful embeddings response: Code = " + response.code();
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    Log.e(TAG, errorMessage);
                    Log.e(TAG, "Response body: " + errorBody);
                    callback.onFailure(call, new IOException(errorMessage + ", " + errorBody));
                }
            }
        });
    }

    /**
     * 调用图像生成接口
     */
    public void generateImage(String prompt, int n, String size, Callback callback) {
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("model", "dall-e-3");
            requestJson.put("prompt", prompt);
            requestJson.put("n", n);
            requestJson.put("size", size);
            Log.d(TAG, "Image Generation Request JSON: " + requestJson.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating JSON for image generation", e);
            callback.onFailure(null, new IOException("Invalid JSON request body"));
            return;
        }
        RequestBody body = RequestBody.create(
                requestJson.toString(),
                MediaType.get("application/json; charset=utf-8")
        );
        // 拼接图像生成接口路径 /images/generate
        Request request = new Request.Builder()
                .url(OPENAI_API_URL + "/images/generate")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to generate image", e);
                callback.onFailure(call, e);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Image generation response received");
                    callback.onResponse(call, response);
                } else {
                    String errorMessage = "Unsuccessful image generation response: Code = " + response.code();
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    Log.e(TAG, errorMessage);
                    Log.e(TAG, "Response body: " + errorBody);
                    callback.onFailure(call, new IOException(errorMessage + ", " + errorBody));
                }
            }
        });
    }
    public void sendPhoto(String base64Image, Callback callback) {
        try {
            // 包装成 data URL 格式
            String dataUrl = "data:image/jpeg;base64," + base64Image;

            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            JSONArray contentArray = new JSONArray();

            // 可选：发送一个文本说明
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", "What's in this image?");
            contentArray.put(textPart);

            // 添加图片部分，图片数据为 data URL 格式
            JSONObject imagePart = new JSONObject();
            imagePart.put("type", "image_url");
            JSONObject imageUrlObj = new JSONObject();
            imageUrlObj.put("url", dataUrl);
            imagePart.put("image_url", imageUrlObj);
            contentArray.put(imagePart);

            userMsg.put("content", contentArray);
            messages.put(userMsg);

            sendChatMessages("gpt-5-chat-latest", messages, 500, 0.7, callback);
        } catch (Exception e) {
            callback.onFailure(null, new IOException("Error creating photo message JSON", e));
        }
    }
    private String compressImageToBase64(String imageUrl, Context context) throws IOException {
        InputStream is;
        if (imageUrl.startsWith("content://")) {
            is = context.getContentResolver().openInputStream(Uri.parse(imageUrl));
            if (is == null) {
                throw new IOException("无法打开内容URI: " + imageUrl);
            }
        } else {
            java.net.URL url = new java.net.URL(imageUrl);
            is = url.openStream();
        }
        long decodeStart = System.currentTimeMillis();
        Bitmap bitmap = BitmapFactory.decodeStream(is);
        long decodeEnd = System.currentTimeMillis();
        Log.d(TAG, "图片解码耗时：" + (decodeEnd - decodeStart) + " ms");
        is.close();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long compressStart = System.currentTimeMillis();
        // 压缩图片，质量50（可根据需要调整）
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
        long compressEnd = System.currentTimeMillis();
        Log.d(TAG, "图片压缩耗时：" + (compressEnd - compressStart) + " ms");

        byte[] compressedBytes = baos.toByteArray();
        String base64Str = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.NO_WRAP);
        Log.d(TAG, "Base64 字符串长度：" + base64Str.length());
        return base64Str;
    }
    public void sendPhotoMessage(String imageUrl, Context context, ChatbotResponseListener listener) {
        // 通常建议在后台线程中执行图片压缩和网络请求
        new Thread(() -> {
            try {
                // 将图片压缩为 Base64 字符串
                String base64Image = compressImageToBase64(imageUrl, context);
                // 调用已有的 sendPhoto 方法，该方法内部会包装成 data URL 格式并发送请求
                sendPhoto(base64Image, new okhttp3.Callback() {
                    @Override
                    public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                        // 通过回调通知 UI 层失败信息
                        try {
                            listener.onFailure("Failed to connect to Chatbot (Photo): " + e.getMessage());
                        } catch (JSONException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                    @Override
                    public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response) throws IOException {
                        String responseData = response.body() != null ? response.body().string() : null;
                        if (response.isSuccessful() && responseData != null) {
                            try {
                                JSONObject jsonObject = new JSONObject(responseData);
                                String chatbotReply = jsonObject.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("message")
                                        .getString("content");
                                // 通过回调返回 GPT 回复
                                listener.onResponse(chatbotReply.trim());
                            } catch (Exception e) {
                                try {
                                    listener.onFailure("Error processing response.");
                                } catch (JSONException ex) {
                                    throw new RuntimeException(ex);
                                }
                            }
                        } else {
                            try {
                                listener.onFailure("Failed to get response from Chatbot (Photo).");
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                try {
                    listener.onFailure("Error fetching or encoding image: " + e.getMessage());
                } catch (JSONException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }).start();
    }



}
