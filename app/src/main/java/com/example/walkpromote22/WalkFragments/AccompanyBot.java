package com.example.walkpromote22.WalkFragments;

import static com.example.walkpromote22.ChatbotFragments.ChatbotFragment.localConversationHistory;
import static com.example.walkpromote22.ChatbotFragments.GeographyBot.fetchPOIs;
import static com.example.walkpromote22.WalkFragments.WalkFragment.injectTiredHintIfNeeded;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;
import com.example.walkpromote22.ChatbotFragments.ChatbotResponseListener;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Route;
import com.example.walkpromote22.tool.UserPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 如果 OPENAI_KEY 定义在 RouteGeneration：取消注释下面一行，并确保该常量是 public static
// import static com.example.walkpromote22.RouteGeneration.OPENAI_KEY;

/**
 * 极简 SmartGuide（仅两份数据：userInputs, poiList）
 * 不管理地图/定时；WalkFragment 每次拿到 currentLoc 后调用 processTick(...)
 * 内部直接用 OPENAI_KEY 创建 ChatbotHelper 进行一次对话，解析令牌并通过回调返回动作。
 */
public class AccompanyBot {

    private static final String TAG = "SmartGuideMini";
    private static final double DEFAULT_RADIUS_M = 500.0;
    // 在类里加上
    private static AppDatabase appDatabase;

    public static String EventInWalk;
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
        void onAddMarker(String poi_name,double lat, double lng);
        void onClearAllMarkers();
        void onClearMarker(double lat, double lng);
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

    public AccompanyBot(JSONArray userInputs, List<Location> routeList) {
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
    /** WalkFragment 拿到当前位置后调用 */
// === 替换 SmartGuide.java 中的 processTick ===
    public void processTick(
            LatLng currentLoc,
            @Nullable Double radiusMeters,
            ActionSink sink,
            ExecutorService executorService,
            Context ctx) {
        if (currentLoc == null || sink == null) {
            return;
        }
        final double radius = (radiusMeters == null || radiusMeters <= 0) ? DEFAULT_RADIUS_M : radiusMeters;

        // 用来保存历史对话字符串
        final AtomicReference<String> payloadHistory = new AtomicReference<>("");

        // 1) 先异步加载历史
        Callable<Void> loadHistoryTask = () -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(ctx);
                UserPreferences userPref = new UserPreferences(ctx);
                String userKey = userPref.getUserKey();
                List<Route> historyRoutes = db.routeDao().getRoutesByUserKey(userKey);

                JSONArray historyArr = new JSONArray();
                int i = 0;
                for (Route r : historyRoutes) {
                    if (i >= 10) break;
                    JSONObject obj = new JSONObject();
                    obj.put("id", r.getId());
                    obj.put("name", r.getName());
                    obj.put("createdAt", r.getCreatedAt());
                    obj.put("description", r.getDescription());
                    historyArr.put(obj);
                    i++;
                }
                payloadHistory.set("User_Conversation_History\n" + historyArr.toString());
            } catch (Exception e) {
                Log.e("ProcessTick", "smartGuide 获取历史失败", e);
                payloadHistory.set("");
            }
            return null;
        };

        Future<Void> future = executorService.submit(loadHistoryTask);

        // 2) 等待历史加载完后继续
        executorService.submit(() -> {
            try {
                future.get();

                JSONObject payload = new JSONObject()
                        .put("type", "sg_tick")
                        .put("loc", new JSONObject()
                                .put("lat", currentLoc.latitude)
                                .put("lng", currentLoc.longitude))
                        .put("radius_m", radius)
                        .put("poi_list", POIList)
                        .put("user_inputs", userInputs)
                        .put("Route_for_user", routeList)
                        .put("ConversationHistory", payloadHistory.get());

                Log.e("ProcessTick", "进入 1111");

                String sys = "You are a POI marker. You can only reply with CONTROL TOKENS \n" +
                        "Supported tokens (do NOT wrap them in code blocks):\n" +
                        "{Add_Marker:POI_name,lat,lng}\n" +
                        "I will send you the conversation of the user(with the agent's reply removed, " +
                        "telling you all the user's needs and the route the user eventually took)." +
                        "And user history conversation can show user's preference, please refer to these historical inputs" +
                        " Every once in a while I will send you the current location of the user and all pois within 500m of user." +
                        " If it contains a POI that the user has mentioned or that the user may be interested in(beautiful views,parks,lakes or something like that), " +
                        "you can use {Add_Marker:POI_name,lat,lng} to mark the point with the name and lat,lng." +
                        "You should not respond any location that is further than 500m, don't add marker or text in user's current location," +
                        "you can just send {} if you think there is nothing user would be interested in in all POIs that sent to you."+
                        "Sample"
                        ;

                JSONArray hist = new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", sys))
                        .put(new JSONObject().put("role", "user").put("content", payload.toString()))
                        .put(new JSONObject().put("role", "system").put("content", EventInWalk));
                        ;


                AtomicReference<Consumer<String>> handleRef = new AtomicReference<>();
                AtomicReference<Consumer<String>> feedRef = new AtomicReference<>();

                ChatbotHelper helper = new ChatbotHelper();
                Log.e("ProcessTick", "进入 2222");

                feedRef.set(toolPayload -> {
                    toolPayload = injectTiredHintIfNeeded(toolPayload);

                    // 2) 再打印日志（现在是已注入版本）
                    Log.e("ProcessTick", "发送给动态地图助手的消息是:" + toolPayload);


                    Log.e("ProcessTick", "发送给动态地图助手的消息是:" + toolPayload);

                    try {
                        helper.sendMessage(toolPayload == null ? "" : toolPayload, hist, new ChatbotResponseListener() {
                            @Override public void onResponse(String reply2) {
                                Log.e("ProcessTick", "动态地图助手的回复是：" + reply2);
                                Consumer<String> h = handleRef.get();
                                if (h != null) {
                                    h.accept(reply2);
                                }
                                String clean = sanitizeReplyForHistory(reply2);
                                if (!TextUtils.isEmpty(clean)) {
                                    try {
                                        JSONObject responseMessage = new JSONObject();
                                        responseMessage.put("role", "assistant");
                                        responseMessage.put("content", clean); // 已去掉{}边框
                                        localConversationHistory.put(responseMessage);
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                }

                            }

                            @Override public void onFailure(String error) {
                            }
                        });
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                });

                handleRef.set(replyRaw -> {
                    if (replyRaw == null) {
                        return;
                    }
                    boolean anyTokenExecuted = false;
                    StringBuilder receipt = new StringBuilder();

                    if (P_TOKEN_BARE_CLEAR.matcher(replyRaw).find()) {
                        runOnUiThreadSafe(() -> sink.onClearAllMarkers());
                        anyTokenExecuted = true;
                        receipt.append("{Clear_Markers} -> OK\n");
                    }

                    Matcher m = P_TOKEN.matcher(replyRaw);
                    while (m.find()) {
                        String token = safe(m.group(1)).toLowerCase(Locale.ROOT);
                        String payloadTxt = safe(m.group(2)).trim();

                        switch (token) {
                            case "add_marker": {
                                // 期望格式：POI_name,lat,lng
                                // 为兼容旧格式（仅 lat,lng），做降级解析
                                int lastComma = payloadTxt.lastIndexOf(',');
                                int secondLastComma = (lastComma > 0) ? payloadTxt.lastIndexOf(',', lastComma - 1) : -1;

                                if (secondLastComma > 0 && lastComma > secondLastComma) {
                                    String name = payloadTxt.substring(0, secondLastComma).trim();
                                    String latStr = payloadTxt.substring(secondLastComma + 1, lastComma).trim();
                                    String lngStr = payloadTxt.substring(lastComma + 1).trim();

                                    // 去掉可选引号
                                    if ((name.startsWith("\"") && name.endsWith("\"")) ||
                                            (name.startsWith("'") && name.endsWith("'"))) {
                                        name = name.substring(1, Math.max(1, name.length() - 1));
                                    }

                                    try {
                                        final double lat = Double.parseDouble(latStr);
                                        final double lng = Double.parseDouble(lngStr);
                                        final String poiName = name;

                                        runOnUiThreadSafe(() -> {
                                            // 如果你的 ActionSink 已提供三参版本，请改为：
                                            // sink.onAddMarker(lat, lng, poiName);
                                            sink.onAddMarker(poiName,lat, lng);

                                        });
                                        anyTokenExecuted = true;
                                        receipt.append("{Add_Marker:\"")
                                                .append(TextUtils.isEmpty(poiName) ? "(未命名)" : poiName)
                                                .append("\",").append(lat).append(",").append(lng).append("} -> OK\n");
                                    } catch (NumberFormatException nfe) {
                                        // 回退到旧格式 lat,lng

                                    }
                                }
                                break;
                            }
                            default:
                                // 忽略未知 token
                                break;
                        }
                    }

                    if (anyTokenExecuted) {
                        Consumer<String> f = feedRef.get();
                        if (f != null) {
                            f.accept(receipt.toString());
                        }
                    }
                });

                Log.e("ProcessTick", "进入 3333");
                Log.e("ProcessTick", "第一次发送的内容是：" + payload);
                Log.e("ProcessTick", "hist 内容是 " + hist);

                executorService.execute(() -> {
                    try {
                        helper.sendMessage(payload.toString(), hist, new ChatbotResponseListener() {
                            @Override public void onResponse(String reply1) {
                                Log.e("ProcessTick", "第一次回复是=" + reply1);
                                Consumer<String> h = handleRef.get();
                                if (h != null) {
                                    h.accept(reply1);
                                }
                            }

                            @Override public void onFailure(String error) {
                                Log.e("ProcessTick", "Chatbot 失败: " + error);
                            }
                        });
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                });

            } catch (Exception e) {
                Log.e("ProcessTick", "processTick 内部错误", e);
            }
        });
    }


    /** 辅助方法：在主线程执行 Runnable（如果当前就是主线程则直接，否则切换） */
    private void runOnUiThreadSafe(Runnable r) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(r);
    }


    // 去掉外层花括号 / 代码块围栏，并逐行剥离每行首尾的一对{}
    private static String sanitizeReplyForHistory(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        // 去掉 ```...``` 代码围栏（若有）
        if (s.startsWith("```")) {
            s = s.replaceFirst("(?s)^\\s*```[a-zA-Z]*\\s*", "");
            s = s.replaceFirst("(?s)\\s*```\\s*$", "");
            s = s.trim();
        }

        // 如果整段就是 { ... }，剥一层
        if (s.startsWith("{") && s.endsWith("}") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }

        // 多行时，每行如果是 {...} 也剥一层
        String[] lines = s.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("{") && t.endsWith("}") && t.length() >= 2) {
                t = t.substring(1, t.length() - 1).trim();
            }
            if (!t.isEmpty()) out.append(t).append('\n');
        }
        return out.toString().trim();
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
