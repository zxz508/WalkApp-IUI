package com.example.walkpromote22.ChatbotFragments;

import static android.view.View.GONE;


import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.generateRoute;

import static com.example.walkpromote22.tool.MapTool.rank;
import static com.example.walkpromote22.tool.MapTool.trimLocationName;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.maps.Projection;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.MarkerOptions;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.BusRouteResult;
import com.amap.api.services.route.DriveRouteResult;
import com.amap.api.services.route.RideRouteResult;
import com.amap.api.services.route.RouteSearch;
import com.amap.api.services.route.WalkRouteResult;
import com.example.walkpromote22.Activities.MainActivity;
import com.example.walkpromote22.tool.BaiduTranslateHelper;
import com.example.walkpromote22.tool.MapTool;
import com.example.walkpromote22.R;
import com.example.walkpromote22.WalkFragments.WalkFragment;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Step;
import com.example.walkpromote22.data.model.User;
import com.example.walkpromote22.tool.WeatherTool;
import com.github.mikephil.charting.charts.LineChart;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatbotFragment extends Fragment {

    private static final String TAG = "ChatbotFragment";
    private static final int REQUEST_IMAGE_CAPTURE = 1;
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    // 放在 ChatbotFragment 成员区


    private LinearLayout routeContainer; // 用于显示地图的容器
    // 控件声明
    private EditText userInput;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private List<Message> messageList;
    // 路线展示区域

    // 对话辅助类和图片 Uri
    private LatLng userLocation;
    private ChatbotHelper chatbotHelper;
    private Uri photoUri;

    private String weather;


    // 全局对话历史
    private JSONArray conversationHistory = new JSONArray();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chatbot, container, false);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        userInput = view.findViewById(R.id.user_input);
        Button sendButton = view.findViewById(R.id.send_arrow);
        recyclerView = view.findViewById(R.id.recycler_view_messages);
        routeContainer = view.findViewById(R.id.route_container);

        // 初始化 RecyclerView
        messageList = new ArrayList<>();
        chatAdapter = new ChatAdapter(messageList);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(chatAdapter);

        // 初始化对话历史
        try {
            conversationHistory.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You are a helpful assistant."));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        View initialDialog = view.findViewById(R.id.initial_dialog);
        CardView weatherCard = view.findViewById(R.id.weather_card);
        TextView weatherContent = view.findViewById(R.id.weather_content);


        // ✅ 提前获取定位信息（只获取一次并存入 userLocation）and 查询天气
     /*   prefs.edit().putString("location_lat", String.valueOf(location.latitude)).apply();
        prefs.edit().putString("location_long", String.valueOf(location.longitude)).apply();*/

        SharedPreferences prefs = requireContext().getSharedPreferences("AppData", Context.MODE_PRIVATE);
        weather = prefs.getString("weather", null);
        userLocation=new LatLng(Double.parseDouble(Objects.requireNonNull(prefs.getString("location_lat", null))),Double.parseDouble(Objects.requireNonNull(prefs.getString("location_long", null))));


        if (weather != null) {
            // 如需翻译
            BaiduTranslateHelper.translateToEnglish(weather, new BaiduTranslateHelper.TranslateCallback() {
                @Override
                public void onTranslated(String englishText) {
                    requireActivity().runOnUiThread(() -> {
                        weatherContent.setText(englishText);
                        weatherCard.setVisibility(View.VISIBLE);
                    });
                }
                @Override
                public void onError(String error) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "翻译失败：" + error, Toast.LENGTH_SHORT).show()
                    );
                }
            });
        } else {
            // 天气尚未获取到，可以显示加载中
            weatherContent.setText("Loading weather...");
            weatherCard.setVisibility(View.VISIBLE);
        }

    // 初始化 ChatbotHelper（请根据实际情况完善获取                addChatMessage(message, true); API key 的逻辑）
        String apiKey = getApiKeyFromSecureStorage();
        chatbotHelper = new ChatbotHelper(apiKey);
        sendButton.setOnClickListener(v -> {
            String userMessage = userInput.getText().toString().trim();
            if (!userMessage.isEmpty()) {
                addChatMessage(userMessage, true);

                initialDialog.setVisibility(View.GONE);
                weatherContent.setVisibility(View.GONE);
                weatherCard.setVisibility(View.GONE);
                userInput.setText("");

                // 准备 system promote
                String promote ="You are an route planing assistant for user and you can get extra information's from API rather than user. "+
                            "You should begin with questions like: To create the best route, I need a bit of info:" + "How long do you want to walk (time or distance)?" + "Do you prefer quiet streets, scenic spots, or lively areas?"+
                            "Following are very important:When you want a route from Map API designed according to user requests, you just respond: {Map_API_Route} and API will give you the information's in JSON" +
                            "When you want information's from Map API for certain name POIs (Like a name for shop or a name for location), you just respond: {Map_API_Certain}"+
                            "When you want to show user the route drawing in map, you just respond: {Drawing_API} and API will draw the route. " +
                            "When you want get user's walking data in this week and visualize it to user(Only step counts up to one week are supported),you just respond: {StepData_API}"+
                            "Here's a sample conversation1 I'd like you to have with your users:Sample Conversation1\n" +
                            "\n" +
                            "App: 👋 Hi there! Ready for a refreshing walk today?\n" +
                            "User: Generate a suitable route for me\n" +
                            "App: Great! To create the best route, I need a bit of info:\n" +
                            "App:How long do you want to walk (time or distance)?\n" +
                            "App:Do you prefer quiet streets, scenic spots, or lively areas?\n" +
                            "User: Maybe around 30 minutes. And I’d like a scenic route.\n" +
                            "App: Got it ✅ Checking nearby parks, riversides, and trails… {Map_API_Route}.(using Map API to get a route satisfying user requirement ).\n" +
                            "App: 🌿 I’ve found a peaceful riverside loop near you. It’s about 2.5 km and should take ~30 minutes. Currently, it’s not too crowded, and the sunset views are great right now.\n" +
                            "User: Sounds perfect.\n" +
                            "App: {Drawing_API} Awesome! I’ll guide you step by step. Let’s start at Oakwood Park entrance. Ready to begin?." +
                            "User: Yes.\n" +
                            "App: 🚶‍♂️ Let’s go! First, walk straight down Oakwood Lane for 300 meters. 🌟 You’re off to a strong start—did you know a 30-minute walk can boost your mood for up to 12 hours?\n" +
                            "User (midway): I’m getting a bit tired.\n" +
                            "App: You’re doing great! 💪 You’ve already covered 1.4 km—over halfway there. How about slowing down for a minute to enjoy the view by the lake?\n" +
                            "User (later): Okay, I’m back on track.\n" +
                            "App: Perfect! Only 500 meters to go. Imagine how good it’ll feel to finish strong. 🚀\n" +
                            "User (end): I’m done!\n" +
                            "App: 🎉 Congratulations! You walked 2.6 km in 31 minutes. That’s about 3,400 steps. I’ve saved your route in case you want to share it on your socials. Want me to post a highlight for you?\n" +
                            "User: Yes, post it.\n" +
                            "App: Done ✅ Shared your walk summary with today’s scenic photo. 🌄 Way to go—you made today healthier and brighter!"+
                            "Sample Conversation2:" +
                            "App: 👋 Hi there! Ready for a refreshing walk today?\n"+
                            "User:I wanna walk to a KFC\n"+
                            "App:Got it ✅ Checking nearby KFCs.{Map_API_Certain}"+
                            "App:OK, I have found several KFC around you. Which specifically you aim at?\n"+
                            "User:The one around my home"+
                            "App:Got it, generating a route to it.{Map_API_Route}"+
                            "App:{Drawing_API} I’ll guide you step by step.";


                // 把 promote 放到首位，且只插一次
                conversationHistory = ensureSystemPromote(conversationHistory, promote);

                // 发送并处理工具触发
                sendWithPromoteAndTooling(userMessage);
            }
        });
    }
    /* 1) 把 promote 放到最前面且只插一次（已存在则搬到首位） */
    private JSONArray ensureSystemPromote(JSONArray history, String promote) {
        if (promote == null || promote.isEmpty() || history == null) return history;

        int sysIdx = -1;
        for (int i = 0; i < history.length(); i++) {
            JSONObject it = history.optJSONObject(i);
            if (it != null && "system".equals(it.optString("role"))
                    && promote.equals(it.optString("content"))) {
                sysIdx = i;
                break;
            }
        }
        try {
            if (sysIdx == -1) {
                JSONArray nh = new JSONArray();
                nh.put(new JSONObject().put("role", "system").put("content", promote));
                for (int i = 0; i < history.length(); i++) nh.put(history.get(i));
                return nh;
            } else if (sysIdx != 0) {
                JSONObject sys = history.getJSONObject(sysIdx);
                JSONArray nh = new JSONArray();
                nh.put(sys);
                for (int i = 0; i < history.length(); i++) if (i != sysIdx) nh.put(history.get(i));
                return nh;
            } else {
                return history; // 已在首位
            }
        } catch (JSONException e) {
            e.printStackTrace();
            return history;
        }
    }

    /* 2) 发送消息并处理 GPT 的工具触发（Map_API / Drawing_API） */
    // 发送消息并处理 GPT 的工具触发（Map_API / Drawing_API）— 修复互相引用问题版
    // 发送消息并处理 GPT 的工具触发（Map_API_All / Map_API_Certain / Drawing_API）
// 注意：
// - Map_API_All → 我们示例继续用 generateRoute 产出一条“可行路线”（如果你有真正“附近POI全量”接口，可替换这里的实现为 place/around 聚合）。
// - Map_API_Certain → 调 getCoreLocationsFromRequirement(userMsg)，返回“匹配名字的POI集合（不是路线！）”，只回喂GPT，不绘制，不写入 lastRouteRef。
// - Drawing_API → 仅对“路线”进行绘制（需要 lastRouteRef 或先生成路线）。
    // 替换你现有的方法
    // 完全契合 {Map_API_Route}/{Map_API_Certain}/{Drawing_API}/{StepData_API} 的版本
    private void sendWithPromoteAndTooling(String userMessage) {
        final String lastUserMsg = userMessage; // 本轮用户输入

        // —— 聚合“历史所有对话（含 user & assistant） + 本轮用户输入”供 generateRoute 使用 ——
        // 需求：标注是谁说的，并清理助手里的令牌/回执/代码块/裸 JSON
        final String dialogForRoute;
        {
            StringBuilder sb = new StringBuilder();
            try {
                if (conversationHistory != null) {
                    for (int i = 0; i < conversationHistory.length(); i++) {
                        org.json.JSONObject it = conversationHistory.optJSONObject(i);
                        if (it == null) continue;
                        String role = it.optString("role", "");
                        if (!"user".equals(role) && !"assistant".equals(role)) continue; // 跳过 system
                        String c = it.optString("content", "");
                        if (c == null || c.isEmpty()) continue;

                        if ("assistant".equals(role)) {
                            // 清理助手文本中的技术性符号，避免干扰 generateRoute
                            c = c.replaceAll("(?is)```.*?```", " "); // 代码块
                            c = c.replaceAll("(?im)^API_(Result|Done)\\s*:\\s*\\{.*?\\}.*$", " "); // API 回执行
                            c = c.replaceAll("(?i)\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|StepData_API)\\s*\\}", " "); // 花括号令牌
                            c = c.replaceAll("(?i)Request\\s*:\\s*\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|StepData_API|Map_API(?:_All)?)\\s*\\}", " "); // 兼容旧令牌
                            c = c.replaceAll("(?m)^\\s*\\[.*\\]\\s*$", " "); // 裸 JSON 行
                            c = c.replaceAll("(?m)^\\s*\\{.*\\}\\s*$", " ");
                            c = c.replaceAll("\\s{2,}", " ").trim();
                        }
                        if (sb.length() > 0) sb.append('\n');
                        sb.append("USER".equalsIgnoreCase(role) ? "USER: " : "ASSISTANT: ").append(c);
                    }
                }
            } catch (Exception ignore) {}
            if (lastUserMsg != null && !lastUserMsg.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("USER: ").append(lastUserMsg); // 本轮还未入 history，手动补上并标注
            }
            dialogForRoute = sb.toString();
        }

        // 缓存：最近一条“路线”（用于绘制）& 最近一次“特定名字 POI 列表（带 label）”
        final java.util.concurrent.atomic.AtomicReference<List<Location>> lastRouteRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicReference<org.json.JSONArray> lastCertainListRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        // 自动调用上限，防止循环
        final java.util.concurrent.atomic.AtomicInteger apiHops = new java.util.concurrent.atomic.AtomicInteger(0);
        final int MAX_API_HOPS = 2;

        // ===== 触发令牌（支持两种写法：{Token} 与 Request:{Token}）=====
        final int CI = java.util.regex.Pattern.CASE_INSENSITIVE;
        // 新写法：花括号
        final java.util.regex.Pattern P_ROUTE_BRACE   = java.util.regex.Pattern.compile("\\{\\s*Map_API_Route\\s*\\}", CI);
        final java.util.regex.Pattern P_CERTAIN_BRACE = java.util.regex.Pattern.compile("\\{\\s*Map_API_Certain\\s*\\}", CI);
        final java.util.regex.Pattern P_DRAW_BRACE    = java.util.regex.Pattern.compile("\\{\\s*Drawing_API\\s*\\}", CI);
        final java.util.regex.Pattern P_STEP_BRACE    = java.util.regex.Pattern.compile("\\{\\s*StepData_API\\s*\\}", CI);
        // 兼容旧写法：Request:{...}
        final java.util.regex.Pattern P_ROUTE_REQ     = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API_Route\\s*\\}", CI);
        final java.util.regex.Pattern P_CERTAIN_REQ   = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API_Certain\\s*\\}", CI);
        final java.util.regex.Pattern P_DRAW_REQ      = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Drawing_API\\s*\\}", CI);
        final java.util.regex.Pattern P_STEP_REQ      = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*StepData_API\\s*\\}", CI);
        // 进一步兼容：老的 Map_API / Map_API_All 统一当作 Route
        final java.util.regex.Pattern P_ROUTE_OLD_REQ = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API(?:_All)?\\s*\\}", CI);
        final java.util.regex.Pattern P_ROUTE_OLD_BR  = java.util.regex.Pattern.compile("\\{\\s*Map_API(?:_All)?\\s*\\}", CI);

        // 互相引用的回调容器
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> handleRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> feedRef   = new java.util.concurrent.atomic.AtomicReference<>();

        // 把工具结果回喂给 GPT（再次走 LLM）
        feedRef.set((String toolPayload) -> {
            if (apiHops.incrementAndGet() > MAX_API_HOPS) {
                requireActivity().runOnUiThread(() -> addChatMessage("⚠️ 已达到自动调用上限。", false));
                return;
            }
            chatbotHelper.sendMessage(toolPayload, conversationHistory, new ChatbotResponseListener() {
                @Override public void onResponse(String reply2) {
                    java.util.function.Consumer<String> h = handleRef.get();
                    if (h != null) h.accept(reply2);
                }
                @Override public void onFailure(String error) {
                    requireActivity().runOnUiThread(() ->
                            addChatMessage("Failed to connect to Chatbot: " + error, false));
                }
            });
        });

        // 统一处理 GPT 回复（识别触发、清理令牌、调用 API、回喂）
        handleRef.set((String replyRaw) -> {
            if (replyRaw == null) {
                requireActivity().runOnUiThread(() -> addChatMessage("（空响应）", false));
                return;
            }

            // —— 是否包含各类触发 ——（支持两种写法）
            boolean needRoute   = P_ROUTE_BRACE.matcher(replyRaw).find()   || P_ROUTE_REQ.matcher(replyRaw).find()
                    || P_ROUTE_OLD_BR.matcher(replyRaw).find() || P_ROUTE_OLD_REQ.matcher(replyRaw).find();
            boolean needCertain = P_CERTAIN_BRACE.matcher(replyRaw).find() || P_CERTAIN_REQ.matcher(replyRaw).find();
            boolean needDraw    = P_DRAW_BRACE.matcher(replyRaw).find()    || P_DRAW_REQ.matcher(replyRaw).find();
            boolean needStep    = P_STEP_BRACE.matcher(replyRaw).find()    || P_STEP_REQ.matcher(replyRaw).find();

            // —— 展示给用户的可读文本：移除所有令牌（两种写法都清理） ——
            String visible = replyRaw;
            visible = P_ROUTE_BRACE.matcher(visible).replaceAll("");
            visible = P_CERTAIN_BRACE.matcher(visible).replaceAll("");
            visible = P_DRAW_BRACE.matcher(visible).replaceAll("");
            visible = P_STEP_BRACE.matcher(visible).replaceAll("");
            visible = P_ROUTE_REQ.matcher(visible).replaceAll("");
            visible = P_CERTAIN_REQ.matcher(visible).replaceAll("");
            visible = P_DRAW_REQ.matcher(visible).replaceAll("");
            visible = P_STEP_REQ.matcher(visible).replaceAll("");
            visible = P_ROUTE_OLD_BR.matcher(visible).replaceAll("");
            visible = P_ROUTE_OLD_REQ.matcher(visible).replaceAll("");
            visible = visible.replaceAll("\\n{3,}", "\n\n").trim();
            if (!visible.isEmpty()) {
                String finalVisible = visible;
                requireActivity().runOnUiThread(() -> addChatMessage(finalVisible, false));
            }

            // —— Map_API_Certain / Map_API_Route / Step 统一放后台线程顺序执行 ——
            // —— Map_API_Certain / Map_API_Route / Step 统一放后台线程顺序执行 ——
            if (needCertain || needRoute || needStep) {
                final boolean alsoDraw = needDraw; // 当前这条回复是否还要求绘制
                new Thread(() -> {
                    try {
                        // 1) {Map_API_Certain}：抽取“特定名字 POI 集合”（非路线）→ 直接缓存原始数组 → 回喂
                        if (needCertain) {
                            org.json.JSONArray poiArray;
                            try {
                                // ✅ 只用“上一句用户输入”
                                poiArray = RouteGeneration.getCoreLocationsFromRequirement(lastUserMsg);
                            } catch (Exception ex) {
                                Log.e(TAG, "Map_API_Certain 调用失败：", ex);
                                poiArray = new org.json.JSONArray();
                            }
                            // 直接缓存（不打标签）
                            lastCertainListRef.set(poiArray);

                            String payloadCertain = "API_Result:{Map_API_Certain}\n" + poiArray.toString();
                            java.util.function.Consumer<String> f = feedRef.get();
                            if (f != null) requireActivity().runOnUiThread(() -> f.accept(payloadCertain));
                        }

                        // 2) {Map_API_Route}：基于“上一步缓存 + 本轮输入中的名称匹配”确定目的地 → 生成路线
                        if (needRoute) {
                            org.json.JSONArray poiList = lastCertainListRef.get();
                            org.json.JSONObject chosen = null;

                            if (poiList != null && poiList.length() > 0) {
                                String msg = (lastUserMsg == null) ? "" : lastUserMsg;

                                // 2.1 名称包含（不区分大小写）
                                for (int i = 0; i < poiList.length(); i++) {
                                    org.json.JSONObject o = poiList.optJSONObject(i);
                                    if (o == null) continue;
                                    String nm = o.optString("name", "");
                                    if (!nm.isEmpty() && msg.toLowerCase(java.util.Locale.ROOT).contains(nm.toLowerCase(java.util.Locale.ROOT))) {
                                        chosen = o; break;
                                    }
                                }

                                // 2.2 模糊匹配：去空格/符号再比对
                                if (chosen == null && !msg.isEmpty()) {
                                    String normMsg = msg.replaceAll("[\\s\\p{Punct}]+","").toLowerCase(java.util.Locale.ROOT);
                                    for (int i = 0; i < poiList.length(); i++) {
                                        org.json.JSONObject o = poiList.optJSONObject(i);
                                        if (o == null) continue;
                                        String nm = o.optString("name", "");
                                        String normNm = nm.replaceAll("[\\s\\p{Punct}]+","").toLowerCase(java.util.Locale.ROOT);
                                        if (!normNm.isEmpty() && normMsg.contains(normNm)) { chosen = o; break; }
                                    }
                                }

                                // 2.3 仍无 → 默认取第一个
                                if (chosen == null) chosen = poiList.optJSONObject(0);
                            }

                            // 2.4 生成“路线”并保存 —— ✅ 用 dialogForRoute（历史全部对话）
                            List<Location> route = null;
                            try {
                                if (chosen != null) {
                                    String hint = String.format(java.util.Locale.US,
                                            "\n[ROUTE_TARGET] name=%s; lat=%.6f; lng=%.6f",
                                            chosen.optString("name",""),
                                            chosen.optDouble("latitude", 0d),
                                            chosen.optDouble("longitude", 0d));
                                    route = RouteGeneration.generateRoute(requireContext(), dialogForRoute + hint);
                                } else {
                                    route = RouteGeneration.generateRoute(requireContext(), dialogForRoute);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "generateRoute failed: " + e.getMessage());
                            }
                            lastRouteRef.set(route);

                            // 回喂“路线 JSON”
                            org.json.JSONArray routeArr = new org.json.JSONArray();
                            if (route != null) {
                                for (Location L : route) {
                                    org.json.JSONObject o = new org.json.JSONObject();
                                    o.put("name", L.getName());
                                    o.put("latitude", L.getLatitude());
                                    o.put("longitude", L.getLongitude());
                                    routeArr.put(o);
                                }
                            }
                            String payloadRoute = "API_Result:{Map_API_Route}\n" + routeArr.toString();
                            java.util.function.Consumer<String> f = feedRef.get();
                            if (f != null) requireActivity().runOnUiThread(() -> f.accept(payloadRoute));

                            // 同条要求绘制 → 直接画并告知完成
                            if (alsoDraw) {
                                List<Location> r = lastRouteRef.get();
                                if (r != null && !r.isEmpty()) {
                                    List<Location> finalR = r;
                                    requireActivity().runOnUiThread(() -> {
                                        try { addBotRouteMessage(Collections.singletonList(finalR)); } catch (Exception e) { throw new RuntimeException(e); }
                                        java.util.function.Consumer<String> f2 = feedRef.get();
                                        if (f2 != null) f2.accept("API_Done:{Drawing_API}");
                                    });
                                } else {
                                    requireActivity().runOnUiThread(() ->
                                            addChatMessage("绘制失败：暂无可用路线。", false));
                                }
                            }
                        }

                        // 3) {StepData_API}：返回过去一周步数（此处留空数组占位，等你接健康数据后替换）
                        if (needStep) {
                            org.json.JSONArray steps = new org.json.JSONArray();
                            // TODO: 接入你的步数来源（例如 Health Connect/服务端）
                            // 期望格式例：[{ "date":"2025-08-10", "steps":5234 }, ... 7 条]
                            String payloadStep = "API_Result:{StepData_API}\n" + steps.toString();
                            java.util.function.Consumer<String> f = feedRef.get();
                            if (f != null) requireActivity().runOnUiThread(() -> f.accept(payloadStep));
                        }
                    } catch (Exception e) {
                        requireActivity().runOnUiThread(() ->
                                addChatMessage("工具处理失败：" + e.getMessage(), false));
                    }
                }).start();
                return;
            }


            // 只请求绘制：需要已有“路线”；没有则先生成（✅ 用 dialogForRoute）
            if (needDraw) {
                List<Location> r = lastRouteRef.get();
                if (r == null || r.isEmpty()) {
                    new Thread(() -> {
                        try {
                            List<Location> route = RouteGeneration.generateRoute(requireContext(), dialogForRoute);
                            lastRouteRef.set(route);
                            requireActivity().runOnUiThread(() -> {
                                try { addBotRouteMessage(Collections.singletonList(route)); } catch (Exception e) { throw new RuntimeException(e); }
                                java.util.function.Consumer<String> f = feedRef.get();
                                if (f != null) f.accept("API_Done:{Drawing_API}");
                            });
                        } catch (Exception e) {
                            requireActivity().runOnUiThread(() ->
                                    addChatMessage("绘制失败（无可用路线）：" + e.getMessage(), false));
                        }
                    }).start();
                } else {
                    requireActivity().runOnUiThread(() -> {
                        try { addBotRouteMessage(Collections.singletonList(r)); } catch (Exception e) { throw new RuntimeException(e); }
                        java.util.function.Consumer<String> f = feedRef.get();
                        if (f != null) f.accept("API_Done:{Drawing_API}");
                    });
                }
                return;
            }

            // 无任何工具触发：只展示自然语言
        });

        // 首次把用户消息发给 GPT
        chatbotHelper.sendMessage(userMessage, conversationHistory, new ChatbotResponseListener() {
            @Override public void onResponse(String reply) {
                java.util.function.Consumer<String> h = handleRef.get();
                if (h != null) h.accept(reply);
            }
            @Override public void onFailure(String error) {
                requireActivity().runOnUiThread(() ->
                        addChatMessage("Failed to connect to Chatbot: " + error, false));
            }
        });
    }

    private void showWeekReport(){

            String message = "My exercise report for this week";
            addChatMessage(message, true);
            new Thread(() -> {
                // 获取用户标识
                String userKey = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        .getString("USER_KEY", null);
                AppDatabase db = AppDatabase.getDatabase(getContext());
                StepDao stepDao = db.stepDao();
                UserDao userDao = db.userDao();

                // 获取当前用户体重（kg），若获取失败则默认70kg
                float weight = 70f;
                try {
                    weight = userDao.getUserByKey(userKey).getWeight();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // 获取当前日期和过去7天（含今天）的时间范围
                Calendar calendar = Calendar.getInstance();
                Date today = calendar.getTime();
                calendar.add(Calendar.DAY_OF_YEAR, -6);  // 过去7天
                Date startDate = calendar.getTime();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

                // 使用 LinkedHashMap 保持日期顺序记录每天的步数、距离和卡路里
                LinkedHashMap<String, Integer> stepsMap = new LinkedHashMap<>();
                LinkedHashMap<String, Float> distanceMap = new LinkedHashMap<>();
                LinkedHashMap<String, Float> calorieMap = new LinkedHashMap<>();

                // 循环获取过去7天的记录
                Calendar tempCal = Calendar.getInstance();
                tempCal.setTime(startDate);
                while (!tempCal.getTime().after(today)) {
                    String dateStr = sdf.format(tempCal.getTime());
                    Step stepRecord = stepDao.getStepByDate(userKey, dateStr);
                    if (stepRecord != null) {
                        int steps = stepRecord.getStepCount();
                        float distance = stepRecord.getDistance(); // 单位：米
                        // 计算距离转换为公里
                        float distanceKm = distance / 1000f;
                        // 根据公式计算卡路里
                        float calories = distanceKm * weight * 1.036f;

                        stepsMap.put(dateStr, steps);
                        distanceMap.put(dateStr, distance);
                        calorieMap.put(dateStr, calories);
                    } else {
                        stepsMap.put(dateStr, 0);
                        distanceMap.put(dateStr, 0f);
                        calorieMap.put(dateStr, 0f);
                    }
                    tempCal.add(Calendar.DAY_OF_YEAR, 1);
                }



                // 在主线程更新 UI，展示汇总报告
                // 在主线程更新 UI，展示汇总报告
                requireActivity().runOnUiThread(() ->
                        addWeeklyExerciseChart(stepsMap, distanceMap, calorieMap)
                );


                // 拼接完整消息，发送给 GPT 进行数据分析（要求答案在 100 tokens 内）
                String fullMessage = "You are a helpful training assistant in a walking promoting application, based on the following weekly exercise data:\n"
                        + getTrainingData()
                        + "Please analyze my performance and suggest improvements.";
                requireActivity().runOnUiThread(() -> {
                    chatbotHelper.sendMessage(fullMessage, conversationHistory, new ChatbotResponseListener() {
                        @Override
                        public void onResponse(String reply) {
                            addChatMessage(reply, false);
                        }
                        @Override
                        public void onFailure(String error) {
                            addChatMessage("Failed to connect to Chatbot: " + error, false);
                        }
                    });
                });
            }).start();
    }






    private String getTrainingData(){
        String userKey = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                .getString("USER_KEY", null);
        AppDatabase db = AppDatabase.getDatabase(getContext());
        StepDao stepDao = db.stepDao();
        UserDao userDao = db.userDao();

        // 获取当前用户体重（kg），若获取失败则默认70kg
        float weight = 70f;
        try {
            weight = userDao.getUserByKey(userKey).getWeight();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 获取当前日期和过去7天（含今天）的时间范围
        Calendar calendar = Calendar.getInstance();
        Date today = calendar.getTime();
        calendar.add(Calendar.DAY_OF_YEAR, -6);  // 过去7天
        Date startDate = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

        // 使用 LinkedHashMap 保持日期顺序记录每天的步数、距离和卡路里
        LinkedHashMap<String, Integer> stepsMap = new LinkedHashMap<>();
        LinkedHashMap<String, Float> distanceMap = new LinkedHashMap<>();
        LinkedHashMap<String, Float> calorieMap = new LinkedHashMap<>();

        // 循环获取过去7天的记录
        Calendar tempCal = Calendar.getInstance();
        tempCal.setTime(startDate);
        while (!tempCal.getTime().after(today)) {
            String dateStr = sdf.format(tempCal.getTime());
            Step stepRecord = stepDao.getStepByDate(userKey, dateStr);
            if (stepRecord != null) {
                int steps = stepRecord.getStepCount();
                float distance = stepRecord.getDistance(); // 单位：米
                // 计算距离转换为公里
                float distanceKm = distance / 1000f;
                // 根据公式计算卡路里
                float calories = distanceKm * weight * 1.036f;

                stepsMap.put(dateStr, steps);
                distanceMap.put(dateStr, distance);
                calorieMap.put(dateStr, calories);
            } else {
                stepsMap.put(dateStr, 0);
                distanceMap.put(dateStr, 0f);
                calorieMap.put(dateStr, 0f);
            }
            tempCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // 构建汇总信息字符串
        StringBuilder summaryStr = new StringBuilder();
        summaryStr.append("<b>Weekly Exercise Summary</b><br>");
        summaryStr.append("<table border='1' cellspacing='0' cellpadding='4'>");
        summaryStr.append("<tr><th>Date</th><th>Steps</th><th>Distance (km)</th><th>Calories (cal)</th></tr>");
        for (String date : stepsMap.keySet()) {
            summaryStr.append("<tr>")
                    .append("<td>").append(date).append("</td>")
                    .append("<td>").append(stepsMap.get(date)).append("</td>")
                    .append("<td>").append(String.format("%.2f", distanceMap.get(date) / 1000.0)).append("</td>")
                    .append("<td>").append(String.format("%.2f", calorieMap.get(date))).append("</td>")
                    .append("</tr>");
        }
        summaryStr.append("</table>");


        return summaryStr.toString();
    }




    private void checkCameraPermissionAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            dispatchTakePictureIntent();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent();
            } else {
                Toast.makeText(getContext(), "相机权限被拒绝，无法拍照", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(requireActivity().getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                Log.e(TAG, "Error occurred while creating the image file", ex);
            }
            if (photoFile != null) {
                try {
                    photoUri = FileProvider.getUriForFile(requireContext(),
                            "com.example.myapp.fileprovider", photoFile);
                } catch (Exception e) {
                    Log.e(TAG, "FileProvider.getUriForFile exception", e);
                    return;
                }
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            } else {
                Log.e(TAG, "photoFile is null");
            }
        } else {
            Log.e(TAG, "No camera app available");
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = requireActivity().getExternalFilesDir(null);
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs();
        }
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            chatbotHelper.sendPhotoMessage(photoUri.toString(), requireContext(), new ChatbotResponseListener() {
                @Override
                public void onResponse(String reply) {
                    addChatMessage(reply, false);
                }
                @Override
                public void onFailure(String error) {
                    addChatMessage("Failed to connect to Chatbot (Photo): " + error, false);
                }
            });
        }
    }



    /**
     * 把 LatLng 在当前屏幕坐标系里上下挪几个像素后再转回地理坐标
     *
     * @param aMap       已经 ready 的 AMap 实例
     * @param src        要偏移的原始坐标
     * @param offsetYPx  像素偏移量（负数 = 往上，正数 = 往下）
     */
    static LatLng offsetOnScreen(AMap aMap, LatLng src, int offsetYPx) {
        Projection pj = aMap.getProjection();
        Point p = pj.toScreenLocation(src);
        p.y += offsetYPx;              // ↑往上就是减，↓往下就是加
        return pj.fromScreenLocation(p);
    }


    /** 画一个 POI 标签（可选像素偏移） */
    private void drawLabel(AMap aMap, Location loc, int offsetYPx) {
        String raw = loc.getName();
        if (TextUtils.isEmpty(raw)) return;

        String core = trimLocationName(raw);
        Consumer<String> paint = name -> {
            LatLng labelPos = offsetOnScreen(
                    aMap,
                    new LatLng(loc.getLatitude(), loc.getLongitude()),
                    offsetYPx);            // ↑负数往上，↓正数往下
            addPoiLabel(aMap, labelPos.latitude, labelPos.longitude, name);
        };

        if (core.matches(".*[\\u4E00-\\u9FA5]+.*")) {
            BaiduTranslateHelper.translateToEnglish(core,
                    new BaiduTranslateHelper.TranslateCallback() {
                        public void onTranslated(String en) { paint.accept(en); }
                        public void onError(String e)        { paint.accept(core); }
                    });
        } else paint.accept(core);
    }




    /* ---------- 主方法 ---------- */
    @SuppressLint("SetTextI18n")
    private void addBotRouteMessage(List<List<Location>> routes) throws Exception {

        List<List<Location>> ordered_routes=rank(routes);


        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.bot_route_message, recyclerView, false);
        LinearLayout container = root.findViewById(R.id.route_maps_container);
        int mapH = (int) (150 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < Math.min(ordered_routes.size(), 3); i++) {

            List<Location> route = ordered_routes.get(i);

            /* ——— Shell ——— */
            RelativeLayout card = new RelativeLayout(getContext());
            card.setLayoutParams(new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, mapH));
            card.setPadding(8, 8, 8, 8);

            /* ——— Map ——— */
            MapTool mapView = new MapTool(getContext());
            mapView.setLayoutParams(new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, mapH));
            mapView.onCreate();

            List<LatLng> latLngs = new ArrayList<>();
            for (Location l : route) latLngs.add(new LatLng(l.getLatitude(), l.getLongitude()));
            mapView.drawRoute(latLngs, Color.parseColor("#FF4081"));
            card.addView(mapView);

            /* ——— Distance chip ——— */
            TextView chip = new TextView(getContext());
            chip.setTextSize(12);
            chip.setTextColor(Color.WHITE);
            chip.setPadding(10, 4, 10, 4);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor(Color.parseColor("#66000000"));
            chipBg.setCornerRadius(6 * getResources().getDisplayMetrics().density);
            chip.setBackground(chipBg);

            RelativeLayout.LayoutParams chipLp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.setMargins(12, 12, 0, 0);
            chip.setLayoutParams(chipLp);
            card.addView(chip);

            mapdistance(route, userLocation,
                    r -> chip.setText(String.format(Locale.getDefault(), "%.0f m", r.getValue())));

            /* ——— “Navigate” button · smaller ——— */
            AppCompatButton nav = new AppCompatButton(getContext());
            nav.setText("Navigate");
            nav.setTextSize(12);
            nav.setAllCaps(false);
            nav.setTextColor(Color.WHITE);

            /* ① 清掉所有系统默认 padding / minSize */
            nav.setPadding(0, 0, 0, 0);
            nav.setMinWidth(0);           // remove AppCompat 48dp minWidth / minHeight
            nav.setMinHeight(0);
            nav.setMinimumWidth(0);
            nav.setMinimumHeight(0);
            nav.setIncludeFontPadding(false);  // remove extra top spacing in font

            /* ② 自定义窄背景（6dp 圆角、深灰填充）——无内部 inset */
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#424242"));         // 深灰
            bg.setCornerRadius(6 * getResources().getDisplayMetrics().density);
            nav.setBackground(bg);

            /* ③ 点击水波纹（Android 原生） */
            TypedValue tv = new TypedValue();
            getContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true);
            nav.setForeground(ContextCompat.getDrawable(getContext(), tv.resourceId));

            /* ④ LayoutParams：紧贴底部、水平居中、无外边距 */
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            lp.bottomMargin = 0;          // 紧贴底
            nav.setLayoutParams(lp);

            nav.setOnClickListener(v -> {
                if (isAdded() && getActivity() instanceof MainActivity) {
                    Bundle b = new Bundle();

                    long routeId=route.get(0).getRoute_id();
                    b.putLong("route_id", routeId);
                    WalkFragment wf = new WalkFragment();
                    wf.setArguments(b);
                    ((MainActivity) getActivity()).updateNavigationSelection(R.id.nav_walk, wf);
                }
            });
            card.addView(nav);

            /* ——— Map ready ——— */
            mapView.postDelayed(() -> {
                AMap aMap = mapView.getMapView() != null ? mapView.getMapView().getMap() : null;
                if (aMap == null) return;

                aMap.showMapText(false);

                Location north = Collections.max(route, Comparator.comparingDouble(Location::getLatitude));
                Location south = Collections.min(route, Comparator.comparingDouble(Location::getLatitude));

                Log.e("DEBUG", north.getName());
                Log.e("DEBUG", south.getName()); // 应该能获取 name

                final double LABEL_GAP_M = 50;                       // 阈值：<50 m 认为会重叠
                double gap = MapTool.distanceBetween(
                        new LatLng(north.getLatitude(), north.getLongitude()),
                        new LatLng(south.getLatitude(), south.getLongitude()));

                // ---------- 关键判断 ----------
                List<Location> picks;
                if (north.equals(south) || gap < LABEL_GAP_M) {
                    // 两点重合或太近 → 只留一个
                    picks = Collections.singletonList(north);        // 也可以改成 south
                } else {
                    // 距离足够远 → 两个都显示
                    picks = Arrays.asList(north, south);
                }
                ExecutorService pool = Executors.newSingleThreadExecutor();
                pool.execute(() -> {
                    /* north → 往上偏 60px  */
                    drawLabel(aMap, north, -60);

                    /* 如果 north ≠ south，则 south → 往下偏 60px */
                    if (!north.equals(south)) {
                        drawLabel(aMap, south, +60);
                    }
                });
                pool.shutdown();
                LatLng center = MapTool.calculateCenter(latLngs);
                float zoomLevel = getZoomLevel();

                aMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        new CameraPosition.Builder().target(center).zoom(zoomLevel).tilt(30f).build()));

                aMap.getUiSettings().setAllGesturesEnabled(false);
            }, 200);

            container.addView(card);
        }

        addChatMessage(root, false);
    }

    private static float getZoomLevel() {
        float distanceKm = (float) RouteGeneration.distanceOfRoute; // 单位假设是 km
        float zoomLevel;

        if (distanceKm <= 1.0f) {
            zoomLevel = 15f;
        } else if (distanceKm <= 3.0f) {
            zoomLevel = 14f;
        } else if (distanceKm <= 5.0f) {
            zoomLevel = 13.5f;
        } else if (distanceKm <= 8.0f) {
            zoomLevel = 13f;
        } else if (distanceKm <= 12.0f) {
            zoomLevel = 12.5f;
        } else {
            zoomLevel = 12f; // 更远的距离，缩小视角
        }
        return zoomLevel;
    }


    /** 自动按字数自适应字号 */
    private void addPoiLabel(AMap map, double lat, double lon, String txt) {
        requireActivity().runOnUiThread(() -> {
            /* === 1. 根据长度决定 sp === */
            /* ---------- 自适应字号 (22 → 16 → 12 → 10) ---------- */
            int len = txt.length();
            int sp;

            if (len <= 4) {                         // ≤4 字
                sp = 22;
            } else if (len <= 20) {                 // 4~20: 22→16
                float t = (len - 4f) / 16f;         // 0→1
                sp = Math.round(22 - (22 - 16) * t);
            } else if (len <= 40) {                 // 20~40: 16→12
                float t = (len - 20f) / 20f;        // 0→1
                sp = Math.round(16 - (16 - 12) * t);
            } else {                                // >40 字
                sp = 10;
            }



            /* === 2. 计算文本尺寸 === */
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(sp2px(sp));
            Rect bounds = new Rect();
            p.getTextBounds(txt, 0, txt.length(), bounds);

            int stroke = 4;                                     // 描边宽 px
            int w = bounds.width()  + stroke * 2;
            int h = bounds.height() + stroke * 2;

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            /* === 3. 描边（白） === */
            Paint strokePaint = new Paint(p);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStrokeWidth(stroke);
            strokePaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(txt, stroke, h - stroke, strokePaint);

            /* === 4. 填充（绿色 & 半透明） === */
            /* === 4. 填充（深绿色 & 半透明） === */
            int bodyColor = Color.argb(230, 0x2E, 0x7D, 0x32);  // #2E7D32
            Paint fillPaint = new Paint(p);
            fillPaint.setColor(bodyColor);
            c.drawText(txt, stroke, h - stroke, fillPaint);


            /* === 5. Marker === */
            BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(bmp);
            map.addMarker(new MarkerOptions()
                    .position(new LatLng(lat, lon))
                    .icon(icon)
                    .anchor(0.5f, 1f)   // 底部中心对准坐标
                    .zIndex(5));
        });
    }



    /* ---------- sp → px 工具 ---------- */
    private float sp2px(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }









    /**
     * 辅助方法：计算一组坐标的中心点
     */

// 返回结果为：Entry<locationId, distance>
    public interface MinWalkDistanceCallback {
        void onMinDistanceReady(Map.Entry<Long, Double> result);


    }
    private void mapdistance(List<Location> routeLocations, LatLng userLocation, MinWalkDistanceCallback callback) {
        if (routeLocations == null || routeLocations.isEmpty() || userLocation == null) {
            callback.onMinDistanceReady(new AbstractMap.SimpleEntry<>((long) -1, -1.0));
            return;
        }

        // Step 1: 找到直线距离最近的点
        Location nearestLocation = null;
        double minLineDistance = Double.MAX_VALUE;

        for (Location location : routeLocations) {
            double d = straightLineDistance(
                    userLocation.latitude, userLocation.longitude,
                    location.getLatitude(), location.getLongitude()
            );
            if (d < minLineDistance) {
                minLineDistance = d;
                nearestLocation = location;
            }
        }

        if (nearestLocation == null) {
            callback.onMinDistanceReady(new AbstractMap.SimpleEntry<>((long) -1, -1.0));
            return;
        }

        // Step 2: 使用高德 API 计算真实步行距离
        LatLonPoint startPoint = new LatLonPoint(userLocation.latitude, userLocation.longitude);
        LatLonPoint endPoint = new LatLonPoint(nearestLocation.getLatitude(), nearestLocation.getLongitude());
        long nearestId = nearestLocation.getId();

        RouteSearch routeSearch = new RouteSearch(getContext());
        RouteSearch.WalkRouteQuery query = new RouteSearch.WalkRouteQuery(
                new RouteSearch.FromAndTo(startPoint, endPoint),
                RouteSearch.WALK_DEFAULT
        );

        routeSearch.setRouteSearchListener(new RouteSearch.OnRouteSearchListener() {
            @Override
            public void onWalkRouteSearched(WalkRouteResult result, int errorCode) {
                if (errorCode == 1000 && result != null && result.getPaths() != null && !result.getPaths().isEmpty()) {
                    float dist = result.getPaths().get(0).getDistance();
                    callback.onMinDistanceReady(new AbstractMap.SimpleEntry<>(nearestId, (double) dist));
                } else {
                    callback.onMinDistanceReady(new AbstractMap.SimpleEntry<>((long) -1, -1.0));
                }
            }

            @Override public void onBusRouteSearched(BusRouteResult busRouteResult, int i) {}
            @Override public void onDriveRouteSearched(DriveRouteResult driveRouteResult, int i) {}
            @Override public void onRideRouteSearched(RideRouteResult rideRouteResult, int i) {}
        });

        routeSearch.calculateWalkRouteAsyn(query);
    }

    private double straightLineDistance(double lat1, double lng1, double lat2, double lng2) {
        float[] result = new float[1];
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, result);
        return result[0];
    }





    private void addChatMessage(View view, boolean isUser) {
        // 假设你的 Message 类支持存储一个 View 类型的消息内容
        Message msg = new Message(view, isUser); // 修改 Message 类构造方法支持 View 类型内容
        messageList.add(msg);
        chatAdapter.notifyItemInserted(messageList.size() - 1);
        recyclerView.smoothScrollToPosition(messageList.size() - 1);
    }
    /**
     * 将消息添加到 RecyclerView 并更新界面
     */
    private void addChatMessage(String text, boolean isUser) {
        text = text.replaceAll("\\d{5,}", "");

// 2. 去掉开头和结尾的空格（trim）
        text = text.trim();

// 3. 将多个连续空格变为一个空格
        text = text.replaceAll("\\s{2,}", " ");
        if (getActivity() == null) return;
        String finalText = text;
        getActivity().runOnUiThread(() -> {
            messageList.add(new Message(finalText, isUser));
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            recyclerView.smoothScrollToPosition(messageList.size() - 1);
        });
    }


    public interface LocationCallback {
        void onLocationReceived(LatLng location) throws Exception;
        void onLocationFailed(String error);
    }

    // 在 ChatbotFragment 内部添加 getCurrentLocation 方法





    /**
     * 模拟从安全存储中获取 API 密钥的逻辑（请根据实际情况修改）
     */
    private String getApiKeyFromSecureStorage() {
        return "sk-O62I7CQRETZ1dSFevmJWqdsJtsfWmg91sbBdWY8tJDRbgYTm";
    }

    public void addWeeklyExerciseChart(Map<String, Integer> stepsMap, Map<String, Float> distanceMap, Map<String, Float> calorieMap) {
        // 使用 ChartHelper 生成折线图
        LineChart lineChart = ChartHelper.generateLineChart(getContext(), stepsMap, distanceMap, calorieMap);

        // 创建 Message 对象并将折线图作为 customView
        Message chartMessage = new Message(lineChart, false);  // false 表示机器人消息

        // 添加到消息列表并更新 UI
        messageList.add(chartMessage);
        chatAdapter.notifyDataSetChanged();
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (routeContainer != null) {
            // 遍历容器内的所有子视图，找到 MapContainerView 并调用其 onDestroy 方法
            for (int i = 0; i < routeContainer.getChildCount(); i++) {
                View child = routeContainer.getChildAt(i);
                if (child instanceof MapTool) {
                    ((MapTool) child).onDestroy();
                }
            }
            routeContainer.removeAllViews();
        }
    }




}
