package com.example.walkpromote22.WalkFragments;

import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.fetchPOIs;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;
import com.example.walkpromote22.ChatbotFragments.ChatbotResponseListener;
import com.example.walkpromote22.data.model.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 如果 OPENAI_KEY 定义在 RouteGeneration：取消注释下面一行，并确保该常量是 public static
// import static com.example.walkpromote22.RouteGeneration.OPENAI_KEY;

/**
 * 极简 SmartGuide（仅两份数据：userInputs, poiList）
 * 不管理地图/定时；WalkFragment 每次拿到 currentLoc 后调用 processTick(...)
 * 内部直接用 OPENAI_KEY 创建 ChatbotHelper 进行一次对话，解析令牌并通过回调返回动作。
 */
public class SmartGuide {

    private static final String TAG = "SmartGuideMini";
    private static final double DEFAULT_RADIUS_M = 500.0;
    // 在类里加上


    private JSONArray POIList;

    // ===== 仅两个持久属性 =====
    private JSONArray userInputs;   // 只存 role=user 的消息（JSONArray）
    private List<Location> routeList;


    // 统一 {Token:Payload} 匹配 + 无参 Clear
    private static final Pattern P_TOKEN = Pattern.compile("\\{\\s*([A-Za-z_]+)\\s*:(.*?)\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TOKEN_BARE_CLEAR = Pattern.compile("\\{\\s*Clear_Markers\\s*\\}", Pattern.CASE_INSENSITIVE);

    private volatile ActionSink sink;
    private final AtomicLong tickCounter = new AtomicLong(0);
    public void setActionSink(ActionSink sink) {
        this.sink = sink;
    }
    public interface ActionSink {
        void onAddMarker(double lat, double lng);
        void onClearAllMarkers();
        void onClearMarker(double lat, double lng);
        void onAddText(String text,double lat, double lng);
        void onAddChatMessage(String text);
    }

    public static org.json.JSONArray buildUserInputs(@androidx.annotation.Nullable org.json.JSONArray history) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (history == null) return arr;
        for (int i = 0; i < history.length(); i++) {
            org.json.JSONObject it = history.optJSONObject(i);
            if (it == null) continue;
            if ("user".equalsIgnoreCase(it.optString("role",""))) {
                // 直接把 {role:"user", content:"..."} 放进去
                arr.put(it);
            }
        }
        return arr;
    }

    /** WalkFragment 每 40s 调一次，把“最新位置”喂过来 */

    public SmartGuide(JSONArray userInputs, List<Location> routeList) {
        this.userInputs = (userInputs == null ? new JSONArray() : userInputs);
        this.routeList    = (routeList    == null ? new ArrayList<Location>()    : routeList);
    }

    public void updateUserInputs(JSONArray inputs) { this.userInputs = (inputs == null ? new JSONArray() : inputs); }
    public void updatePoiList(Context ctx, LatLng center) {
        try {
            Log.e(TAG,"进入updatePoiList");
            // 取 500m 左右半径，tileSize 和 perTileMax 看你需求设
            JSONArray arr = fetchPOIs(ctx,center, 500);
            Log.e(TAG, "fetchPOIs result=" + (arr == null ? "null" : arr.toString()));

            this.POIList=arr;
        } catch (Exception e) {
            Log.e(TAG, "updatePoiList failed: " + e.getMessage(), e);
            this.POIList = new JSONArray();
        }
    }

    /** WalkFragment 拿到当前位置后调用 */
    // === 替换 SmartGuide.java 中的 processTick ===
    public void processTick(LatLng currentLoc,
                            @Nullable Double radiusMeters,
                            ActionSink sink,
                            ExecutorService executorService) {
        if (currentLoc == null || sink == null) return;
        final double radius = (radiusMeters == null || radiusMeters <= 0) ? DEFAULT_RADIUS_M : radiusMeters;

        Log.e("TAG","进入processTick");
        try {
            // 1) 组装最小负载
            final org.json.JSONObject payload = new org.json.JSONObject()
                    .put("type", "sg_tick")
                    .put("loc", new org.json.JSONObject()
                            .put("lat", currentLoc.latitude)
                            .put("lng", currentLoc.longitude))
                    .put("radius_m", radius)
                    .put("poi_list", POIList)          // 假定为 SmartGuide 成员
                    .put("user_inputs", userInputs)
                    .put("Route_for_user",routeList);   // 假定为 SmartGuide 成员
            Log.e("TAG","进入1111");
            // 2) system + payload：允许“令牌 + 自然语言”
            final String sys =
                    "You are a navigation guide. You can only reply with CONTROL TOKENS \n" +
                            "Supported tokens (do NOT wrap them in code blocks):\n" +
                            "{Add_Marker:lat,lng}\n" +
                            "{Add_Text:message,lat,lng}\n+" +
                            "I will send you the history of the user(with the agent's reply removed, " +
                            "telling you all the user's needs and the route the user eventually took)." +
                            " Every once in a while I will send you the current location of the user and all pois within 500m of user." +
                            " If it contains a POI that the user has mentioned or that the user may be interested in(beautiful views,parks,lakes or something like that), " +
                            "you can use {Add_Marker:lat,lng} to mark the point." +
                            "You can also use {Add_Text:message,lat,lng} to write a short note around the point to let the user know that it is not far away."+
                            "You should not respond any location that is further than 500m,don't add marker or text in user's current location," +
                            "you can just send {} if you think there is nothing user would be interested in in all POIs that sent to you.";

            final org.json.JSONArray hist = new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("role", "system").put("content", sys))
                    .put(new org.json.JSONObject().put("role", "user").put("content", payload.toString()));

            // 3) 参考代码风格：apiHops + feedRef/handleRef，避免阻塞
            final java.util.concurrent.atomic.AtomicInteger apiHops = new java.util.concurrent.atomic.AtomicInteger(0);


            final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> handleRef = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> feedRef   = new java.util.concurrent.atomic.AtomicReference<>();

            final ChatbotHelper helper = new ChatbotHelper();
            Log.e("TAG","进入2222");
            // ——把“工具回执”回喂给 LLM（受 hop 限制）——
            feedRef.set((String toolPayload) -> {
                try {
                    org.json.JSONObject toolMsg = new org.json.JSONObject()
                            .put("role", "assistant")
                            .put("content", toolPayload == null ? "" : toolPayload);
                    // 回写到当前会话历史（本地）
                    hist.put(new org.json.JSONObject(toolMsg.toString()));
                } catch (Exception ignore) {}

                 {
                     Log.e("TAG","发送给动态地图助手的消息是:"+toolPayload);
                     try {
                         helper.sendMessage(toolPayload == null ? "" : toolPayload, hist, new ChatbotResponseListener() {
                             @Override public void onResponse(String reply2) {
                                 Log.e("TAG","动态地图助手的回复是："+reply2);
                                 java.util.function.Consumer<String> h = handleRef.get();
                                 if (h != null) h.accept(reply2);
                             }
                             @Override public void onFailure(String error) {
                                 sink.onAddChatMessage("Failed to connect to Chatbot: " + error);
                             }
                         });
                     } catch (JSONException e) {
                         throw new RuntimeException(e);
                     }
                 }
            });

            // ——统一处理 GPT 回复：先显示纯文本，再执行令牌，最后（如有）回喂工具回执——
            handleRef.set((String replyRaw) -> {
                if (replyRaw == null) {
                    sink.onAddChatMessage("（空响应）");
                    return;
                }
                boolean anyTokenExecuted = false;
                StringBuilder receipt = new StringBuilder();

                // Clear_Markers 无参裸触发
                if (P_TOKEN_BARE_CLEAR.matcher(replyRaw).find()) {
                    sink.onClearAllMarkers();
                    anyTokenExecuted = true;
                    receipt.append("{Clear_Markers} -> OK\n");
                }

                // 逐个解析 {Token:payload}
                java.util.regex.Matcher m = P_TOKEN.matcher(replyRaw);
                while (m.find()) {
                    String token = safe(m.group(1)).toLowerCase(java.util.Locale.ROOT);
                    String payloadTxt = safe(m.group(2)).trim();

                    switch (token) {
                        case "add_marker": {
                            double[] ll = parseLatLng(payloadTxt); // 期望 "lat,lng"
                            if (ll != null) {
                                sink.onAddMarker(ll[0], ll[1]);
                                anyTokenExecuted = true;
                                receipt.append("{Add_Marker:").append(ll[0]).append(",").append(ll[1]).append("} -> OK\n");
                            }
                            break;
                        }
                        case "add_text": {
                            String msg = payloadTxt;
                            Double lat = null, lng = null;

                            String[] parts = payloadTxt.split(",");
                            if (parts.length == 3) {

                                try {
                                    double latA = Double.parseDouble(parts[parts.length - 2].trim());
                                    double lngA = Double.parseDouble(parts[parts.length - 1].trim());
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < parts.length - 2; i++) {
                                        sb.append(parts[i]);
                                    }
                                    msg = sb.toString().trim();
                                    lat = latA; lng = lngA;
                                } catch (NumberFormatException ignoreA) {

                                    try {
                                        double latB = Double.parseDouble(parts[0].trim());
                                        double lngB = Double.parseDouble(parts[1].trim());
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 2; i < parts.length; i++) {
                                            if (i > 2) sb.append(",");
                                            sb.append(parts[i]);
                                        }
                                        msg = sb.toString().trim();
                                        lat = latB; lng = lngB;
                                    } catch (NumberFormatException ignoreB) {
                                        // 都不是 → 当作纯文本
                                        lat = null; lng = null;
                                    }
                                }
                            } else if (parts.length == 2) {
                                // 可能是纯坐标 "lat,lng"
                                try {
                                    lat = Double.parseDouble(parts[0].trim());
                                    lng = Double.parseDouble(parts[1].trim());
                                    msg = "";
                                } catch (NumberFormatException ignore) {
                                    lat = null; lng = null;
                                }
                            }

                            if (lat != null && lng != null) {
                                sink.onAddText(msg, lat, lng);
                                anyTokenExecuted = true;
                                String msgShort = msg == null ? "" : (msg.length() > 60 ? msg.substring(0, 60) + "..." : msg);
                                receipt.append("{Add_Text:\"").append(msgShort.replace("\n"," ")).append("\",")
                                        .append(lat).append(",").append(lng).append("} -> OK\n");
                            } else {
                                // 仅文本（按你原逻辑放在 0,0；如需忽略可改为直接 break）

                                anyTokenExecuted = true;
                                String msgShort = msg == null ? "" : (msg.length() > 60 ? msg.substring(0, 60) + "..." : msg);
                                receipt.append("{Add_Text:\"").append(msgShort.replace("\n"," ")).append("\"} -> OK\n");
                            }
                            break;
                        }
                        default:
                            // 其他未知令牌忽略
                    }
                }

                // 如果这轮执行过令牌，把“工具回执”回喂给 LLM（允许其继续生成下一步）
                if (anyTokenExecuted) {
                    java.util.function.Consumer<String> f = feedRef.get();
                    if (f != null) f.accept(receipt.toString());
                }
            });

            Log.e("TAG","进入3333");

            Log.e(TAG,"第一次发送的内容是："+payload);
            Log.e(TAG,"hist内容是"+hist);
            // 4) 首次发起：发送 payload，收到即由 handleRef 进入解析 → 显示 → 执行令牌 →（可选）继续回喂
            executorService.execute(() -> {
                try {
                    helper.sendMessage(payload.toString(), hist, new ChatbotResponseListener() {
                        @Override public void onResponse(String reply1) {
                            Log.e(TAG, "第一次回复是="+reply1);
                            java.util.function.Consumer<String> h = handleRef.get();
                            if (h != null) h.accept(reply1);
                        }
                        @Override public void onFailure(String error) {
                            Log.e(TAG, "Chatbot失败: " + error);
                            sink.onAddChatMessage("Failed: " + error);
                        }
                    });
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            });


        } catch (Throwable ignore) {
            // 安静失败，避免打断 UI
        }
    }

    public void alertMainAgent(){

    }

    private static String safe(String s) { return s == null ? "" : s; }

    @Nullable
    private static double[] parseLatLng(String payload) {
        String[] parts = payload.split(",");
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            return new double[]{lat, lng};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
