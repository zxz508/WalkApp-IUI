package com.example.walkpromote22.ChatbotFragments;


import static com.example.walkpromote22.ChatbotFragments.GeographyAgent.fetchTrafficEventsOnce;
import static com.example.walkpromote22.ChatbotFragments.GeographyAgent.generateRoute;
import static com.example.walkpromote22.ChatbotFragments.GeographyAgent.getInterestingPoint;
import static com.example.walkpromote22.ChatbotFragments.SummaryAgent.postStatusToMastodonAsync;
import static com.example.walkpromote22.Manager.RouteSyncManager.createRoute;
import static com.example.walkpromote22.Manager.RouteSyncManager.ensureInitialized;

import static com.example.walkpromote22.Manager.RouteSyncManager.setPendingRouteDescription;
import static com.example.walkpromote22.Manager.RouteSyncManager.uploadLocations;
import static com.example.walkpromote22.WalkFragments.AccompanyAgent.buildUserInputs;
import static com.example.walkpromote22.WalkFragments.WalkFragment.computeRouteDistanceMeters;
import static com.example.walkpromote22.tool.MapTool.getCurrentLocation;
import static com.example.walkpromote22.tool.MapTool.rank;
import static com.example.walkpromote22.tool.MapTool.trimLocationName;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.example.walkpromote22.Manager.RouteSyncManager;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.dto.LocationDTO;
import com.example.walkpromote22.data.model.Route;
import com.example.walkpromote22.data.model.Step;
import com.example.walkpromote22.tool.BaiduTranslateHelper;
import com.example.walkpromote22.tool.MapTool;
import com.example.walkpromote22.R;
import com.example.walkpromote22.WalkFragments.WalkFragment;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.tool.UserPreferences;
import com.github.mikephil.charting.charts.LineChart;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



public class ChatbotFragment extends Fragment {
    private ActivityResultLauncher<String[]> requestPermsLauncher;   // 前台/多权限
    private ActivityResultLauncher<String>   requestBgLocLauncher;   // 后台定位

    private boolean debugAutoRouteOnce = false;


    // 最近一次用于上传的“最优路线”与其名称
    private List<Location> lastRouteForUpload = java.util.Collections.emptyList();
    private String lastRouteNameForUpload = "";

    private static final String TAG = "ChatbotFragment";
    private static final int REQUEST_IMAGE_CAPTURE = 1;
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    // 放在 ChatbotFragment 成员区

    private volatile String pendingRouteDescription = null;
    private LinearLayout routeContainer; // 用于显示地图的容器
    // 控件声明
    private EditText userInput;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private List<Message> messageList;
    private boolean startNav=false;
    // 路线展示区域

    // 对话辅助类和图片 Uri
    private LatLng userLocation;
    private ChatbotHelper chatbotHelper;
    private Uri photoUri;
    private static AppDatabase appDatabase;
    private String weather;
    private static String userKey;
    private RecyclerView rvNav;
    private EditText etNav;
    private View btnSendNav;
    private RecyclerView rvChat;
    private EditText etChat;
    private View btnSendChat;
    private boolean hascertain=false;
    LinearLayout chatModeContainer;
    LinearLayout navigationModeContainer;
    // ChatbotFragment 字段区
// 用 volatile 确保路线上一线程写、另一线程读可见；每次赋值成“新的不可变列表”
    private volatile List<Location> generatedRoute = java.util.Collections.emptyList();


    // 放在 ChatbotFragment 字段区



    // 当前活跃输入区（根据模式切换指向 etChat/etNav、btnSendChat/btnSendNav）
    private EditText activeInput;
    private View activeSend;
    // 全局对话历史
    public static JSONArray conversationHistory = new JSONArray();
    public static JSONArray localConversationHistory=new JSONArray();

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

        view.post(this::ensurePermissions);
        userInput = view.findViewById(R.id.user_input);
        Button sendButton = view.findViewById(R.id.send_arrow);
        recyclerView = view.findViewById(R.id.recycler_view_messages);


        // 初始化 RecyclerView
        messageList = new ArrayList<>();
        chatAdapter = new ChatAdapter(messageList);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(chatAdapter);


        chatModeContainer = view.findViewById(R.id.chat_mode_container);
        navigationModeContainer = view.findViewById(R.id.navigation_mode_container);
        chatModeContainer.setVisibility(View.VISIBLE);
        navigationModeContainer.setVisibility(View.GONE);
        rvChat = chatModeContainer.findViewById(R.id.recycler_view_messages);
        etChat = chatModeContainer.findViewById(R.id.user_input);
        btnSendChat = chatModeContainer.findViewById(R.id.send_arrow);
        rvNav = navigationModeContainer.findViewById(R.id.recycler_view_messages);
        etNav = navigationModeContainer.findViewById(R.id.user_input);
        btnSendNav = navigationModeContainer.findViewById(R.id.send_arrow);


        // onViewCreated 里，拿到控件之后立刻设置两套 LM（推荐做法）
        rvChat.setLayoutManager(new LinearLayoutManager(getContext()));
        rvChat.setAdapter(chatAdapter);
        rvChat.setItemAnimator(null);

        rvNav.setLayoutManager(new LinearLayoutManager(getContext())); // ★ 给导航区的 RV 也设置 LM
        rvNav.setItemAnimator(null);


        // 初始化对话历史
        if (chatAdapter == null) {
            chatAdapter = new ChatAdapter(messageList);
        }
        // 默认处于“聊天模式”
        rvChat.setAdapter(chatAdapter);
        rvChat.setItemAnimator(null); // 可选：避免频繁切换抖动
        activeInput = etChat;
        activeSend = btnSendChat;

        // 绑定发送逻辑到“当前活跃输入区”
        bindComposer(activeInput, activeSend);
        try {
            localConversationHistory.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You are a helpful assistant."));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        View initialDialog = view.findViewById(R.id.initial_dialog);
        CardView weatherCard = view.findViewById(R.id.weather_card);
        TextView weatherContent = view.findViewById(R.id.weather_content);
        appDatabase     = AppDatabase.getDatabase(requireContext());



        SharedPreferences prefs = requireContext().getSharedPreferences("AppData", Context.MODE_PRIVATE);
        weather = prefs.getString("weather", null);
        try {
            getCurrentLocation(true, requireContext(), new LocationCallback() {
                @Override
                public void onLocationReceived(LatLng location) throws Exception {
                    userLocation=location;
                }

                @Override
                public void onLocationFailed(String error) {

                    Log.e(TAG,"chatbotFragment内部调用getCurrentLocation失败");
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        UserPreferences userPref = new UserPreferences(requireContext());
        userKey=userPref.getUserKey();



        getParentFragmentManager().setFragmentResultListener(
                "APP_TOOL_EVENT",
                this,
                (requestKey, result) -> {
                    String payload = result.getString("payload", "");
                    if (payload != null && !payload.isEmpty()) {
                        try {
                            injectAppToolPayload(payload);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
        );


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
        chatbotHelper = new ChatbotHelper();
        sendButton.setOnClickListener(v -> {

                String userMessage = userInput.getText().toString().trim();
                if (!userMessage.isEmpty()) {
                    addChatMessage(userMessage, true);

                    final String[] Payload = {""};
                    loadStepWeeklyReport(new StepWeeklyReportCallback() {
                        @Override
                        public void onSuccess(StepWeeklyReport report) {
                            // Fragment 可能已被移除，先做生命周期防护
                            if (!isAdded() || getView() == null) return;

                            // 拼装要喂给 feed 的字符串
                            Payload[0] = "User step data\n" + report.stepsToString();
                        }

                        @Override
                        public void onError(Throwable t) {
                            android.util.Log.e(TAG, "loadStepWeeklyReport failed", t);
                            if (!isAdded() || getView() == null) return;
                            addChatMessage("获取运动周报失败：" + t.getMessage(), false);
                        }
                    });
                    initialDialog.setVisibility(View.GONE);
                    weatherContent.setVisibility(View.GONE);
                    weatherCard.setVisibility(View.GONE);
                    userInput.setText("");
                    ZonedDateTime now = ZonedDateTime.now();
                    String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
                    // 准备 system promote
                    String promote = "You are an route planing assistant in English for user and you can get extra information's from API rather than user, ****don't mention any longitude and latitude in your conversation**** " +
                            "Following are very important:*****" +
                            "When you want a route from Map API designed according to user requests, you just respond: {Map_API_Route} and API will give you the information's in JSON (respond {Map_API_Certain} before if user has a clear destination)" +
                            "When you want information's from Map API for certain name POIs (Like a name for shop or a name for location), you just respond: {Map_API_Certain} and API will give you correspond POIs name and locations FROM NEAR TO FAR" +
                            "When you want interesting POIs nearby for recommendation to user, respond: {Map_POI}" +
                            "When you want to get the user recent walking data and visualize it to user as report,you just respond: {StepReport_API}" +
                            "When you want to get user's history queries on route and results to refer to , just respond: {User_History}" +
                            "When you want to navigate user(using navigation after showing the route to user) and get user's permission, you can respond: {Navigation_API}" +


                            "The time now is" + formatted + ", and the weather now is" + weather + ",the user is at" + userLocation + ",the user recent walking steps are" + Payload[0] +
                            "You can only use token twice in a row without the user requesting it" +
                            "Don't just reply with a token,You should tell the user that you are looking for something or ask the user to wait while you invoke the token*****." +


                            "Here are some sample conversations I'd like you to have with your users(*****Only for sample,you should have different talk in different situations, weather,time and so on*****):" +
                            "Sample Conversation1\n" +
                            "User:I would like to have a walk now, but I have no specific destination, can you give me some advice?\n" +
                            "App: Based on the number of steps you have taken recently, I would like to recommend you a 6 kilometers of walking back and forth. And the weather outside is suitable for a walk now, what do you think?" +
                            "User: OK, give me some route recommendations" +
                            "App: Sure, I can find parks, lakes, squares, and other walkable places for you, and you can see if there are any that interest you on the list I'll give you later.Searching...{Map_POI}" +
                            "App: I found these places, and the round-trip exercise will be enough to meet our needs for today.:\n" +
                            "  1. **金银湖龙舟文化公园** - A cultural park known for dragon boat activities and very close by.\n" +
                            "  2. **金银湖** - A cultural plaza near the lake.\n" +
                            "  3. **金银湖国家城市湿地公园** - A wetland park featuring a bird island.\n" +
                            "    \n" +
                            "If you think any of the above places you are interested in, please let me know and I will generate a route for you and lead you there" +
                            "User:Ok, the nearest one looks nice, but I'd also like to pass a coffee shop on the way" +
                            "App: Got it, searching nearby coffee shop.{Map_API_Certain}" +
                            "App: I found several coffee shops. If you don't mind, I'll take you to the nearest coffee shop first and then to the park you want to go to.{Map_API_Route}" +
                            "User: OK, let's go" +
                            "App:{Navigation_API}" +
                            "Sample Conversation2:" +
                            "App: 👋 Hi there! Ready for a refreshing walk today?\n" +
                            "User:I wanna walk to a KFC\n" +
                            "App:Got it ✅ Checking nearby KFCs.{Map_API_Certain}" +
                            "App:OK, I have found several KFC around you. Which specifically you aim at?\n" +
                            "User:The one around my home" +
                            "App:Got it, generating a route to it.{Map_API_Route}" +
                            "App:{Drawing_API} I will show you the route on map now, please wait a second. " +
                            "App: Great! Your route to the KFC is visible on the map now, if you think the route is good I can start helping you with the navigation" +
                            "User:Yes, please" +
                            "App:{Navigation_API}" +
                            "Sample Conversation3:" +
                            "User:I would like to have a walk to a park, any suggestions?" +
                            "App:Got it ✅ Checking nearby parks.{Map_API_Certain}" +
                            "App:OK, I have found several parks around you. Which specifically you aim at?\n" +
                            "App:Got it, generating a route to it.{Map_API_Route}" +
                            "App:{Drawing_API} I will show you the route on map now, please wait a second. " +
                            "App: Great! Your route to the KFC is visible on the map now, if you think the route is good I can start helping you with the navigation" +
                            "User:Yes, please" +
                            "App:{Navigation_API}" +
                            "Sample Conversation4\n" +
                            "User:Generate a suitable route for me\n" +
                            "App:Great! To create the best route, I need a bit of info:\n" +
                            "App:Are there any places you would like to pass by or avoid?" +
                            "App:Do you have a specific destination?If not, how long do you want to walk (time or distance)?\n" +
                            "App:Do you prefer quiet streets, scenic spots, or lively areas?\n" +
                            "User: Maybe around 30 minutes. And I’d like a scenic route.\n" +
                            "App: Got it ✅ Checking nearby parks, riversides, and trails… please wait a second. {Map_API_Route}.\n" +
                            "App: {Drawing_API}🌿 I’ve found a peaceful riverside loop near you. I will show you the route on map now, please wait a second. It’s about 2.5 km and should take ~30 minutes. Currently, it’s not too crowded, and the sunset views are great right now.\n" +
                            "User: Sounds perfect.\n" +
                            "App:  Awesome! I’ll guide you step by step. Let’s start at Oakwood Park entrance. Ready to begin?." +
                            "User: Yes.\n" +
                            "App: {Navigation_API}" +
                            "User (midway): I’m getting a bit tired.\n" +
                            "App: You’re doing great! 💪 You’ve already covered 1.4 km—over halfway there. How about slowing down for a minute to enjoy the view by the lake?\n" +
                            "User (later): Okay, I’m back on track.\n" +
                            "App: Perfect! Only 500 meters to go. Imagine how good it’ll feel to finish strong. 🚀\n" +
                            "User (end): I’m done!\n" +
                            "App: 🎉 Congratulations! You walked 2.6 km in 31 minutes. That’s about 3,400 steps. I’ve saved your route in case you want to share it on your socials. Want me to post a highlight for you?\n" +
                            "User: Yes, post it.\n" +
                            "App: Done ✅ Shared your walk summary with today’s scenic photo. 🌄 Way to go—you made today healthier and brighter!";

                    // 把 promote 放到首位，且只插一次
                    // ==== 修改点 1：更新 localConversationHistory ====
                    localConversationHistory = ensureSystemPromote(localConversationHistory, promote);
                    try {
                        localConversationHistory.put(new JSONObject()
                                .put("role", "user")
                                .put("content", userMessage));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    // ==== 修改点 2：conversationHistory 仅保存全局上下文 ====
                    try {
                        conversationHistory.put(new JSONObject()
                                .put("role", "user")
                                .put("content", userMessage));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    // 发送并处理工具触发
                    try {
                        sendWithPromoteAndTooling(userMessage);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
        });

    }






    private void startSafeLocationStuff() {
        // 所有敏感调用前再做一次保护 & try/catch
        boolean locGranted =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        if (!locGranted) return;

        try {
            // TODO: 你的定位/地图调用（例如 AMap 的 myLocation、位置更新、路线规划等）
        } catch (SecurityException se) {
            se.printStackTrace();
            // 这里可以提示用户开启权限，而不是崩溃
        }
    }



    private void showExplainWhyDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("权限说明")
                .setMessage("我们需要定位来生成步行路线，并需要活动识别统计步数。")
                .setPositiveButton("知道了", (d, w) -> ensurePermissions())
                .show();
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

    private void sendWithPromoteAndTooling(String userMessage) throws JSONException {
        final String lastUserMsg = userMessage; // 本轮用户输入

        // —— 1) 构造“用于路线生成”的对话上下文（含 user & assistant；assistant 文本做清理）——
        final String dialogForRoute;
        {
            StringBuilder sb = new StringBuilder();
            try {
                if (localConversationHistory != null) {
                    for (int i = 0; i < localConversationHistory.length(); i++) {
                        org.json.JSONObject it = localConversationHistory.optJSONObject(i);
                        if (it == null) continue;
                        String role = it.optString("role", "");
                        if (!"user".equals(role) && !"assistant".equals(role)) continue; // 跳过 system
                        String c = it.optString("content", "");
                        if (c == null || c.isEmpty()) continue;

                        if ("assistant".equals(role)) {
                            // 清理助手文本中的技术性符号，避免干扰 generateRoute（展示/拼上下文时的轻量清洗即可）
                            c = c.replaceAll("(?is)```.*?```", " ");
                            c = c.replaceAll("(?im)^API_(Result|Done)\\s*:\\s*\\{.*?\\}.*$", " ");
                            c = c.replaceAll("(?i)\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|Media_API|StepReport_API|User_History|Navigation_API|Map_API(?:_All)?|MAP_POI|Map_POI(?:_All)?)\\s*\\}", " ");
                            c = c.replaceAll("(?i)Request\\s*:\\s*\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|Media_API|StepReport_API|User_History|Navigation_API|Map_API(?:_All)?|MAP_POI|Map_POI(?:_All)?)\\s*\\}", " ");
                            c = c.replaceAll("(?m)^\\s*\\[.*\\]\\s*$", " ");
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
                sb.append("USER: ").append(lastUserMsg);
            }
            dialogForRoute = sb.toString();
        }

        // —— 2) 最近的“路线/特定POI列表”缓存 —— //
        final java.util.concurrent.atomic.AtomicReference<List<Location>> lastRouteRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicReference<org.json.JSONArray> lastCertainListRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        // —— 3) 自动工具调用跳数上限 —— //
        final java.util.concurrent.atomic.AtomicInteger apiHops = new java.util.concurrent.atomic.AtomicInteger(0);
        final int MAX_API_HOPS = 2;

        // —— 4) 触发令牌（两种写法：{Token} / Request:{Token}）—— //
        final int CI = java.util.regex.Pattern.CASE_INSENSITIVE;
        final java.util.regex.Pattern P_ROUTE_BRACE    = java.util.regex.Pattern.compile("\\{\\s*Map_API_Route\\s*\\}", CI);
        final java.util.regex.Pattern P_CERTAIN_BRACE  = java.util.regex.Pattern.compile("\\{\\s*Map_API_Certain\\s*\\}", CI);
        final java.util.regex.Pattern P_DRAW_BRACE     = java.util.regex.Pattern.compile("\\{\\s*Drawing_API\\s*\\}", CI);
        final java.util.regex.Pattern P_STEP_BRACE     = java.util.regex.Pattern.compile("\\{\\s*StepData_API\\s*\\}", CI);
        final java.util.regex.Pattern P_STEP_REPORT    = java.util.regex.Pattern.compile("\\{\\s*StepReport_API\\s*\\}", CI);
        final java.util.regex.Pattern P_HISTORY_BRACE  = java.util.regex.Pattern.compile("\\{\\s*User_History\\s*\\}", CI);
        final java.util.regex.Pattern P_NAV_BRACE      = java.util.regex.Pattern.compile("\\{\\s*Navigation_API\\s*\\}", CI);
        final java.util.regex.Pattern P_POI_BRACE      = java.util.regex.Pattern.compile("\\{\\s*MAP_POI\\s*\\}", CI);
        final java.util.regex.Pattern P_MEDIA_BRACE    = java.util.regex.Pattern.compile("\\{\\s*Media_POI\\s*\\}", CI);

        final java.util.regex.Pattern P_ROUTE_REQ      = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API_Route\\s*\\}", CI);
        final java.util.regex.Pattern P_CERTAIN_REQ    = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API_Certain\\s*\\}", CI);
        final java.util.regex.Pattern P_DRAW_REQ       = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Drawing_API\\s*\\}", CI);
        final java.util.regex.Pattern P_STEP_REQ       = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*StepData_API\\s*\\}", CI);
        final java.util.regex.Pattern P_STEP_REPORT_REQ= java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*StepReport_API\\s*\\}", CI);
        final java.util.regex.Pattern P_HISTORY_REQ    = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*User_History\\s*\\}", CI);
        final java.util.regex.Pattern P_NAV_REQ        = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Navigation_API\\s*\\}", CI);
        final java.util.regex.Pattern P_ROUTE_OLD_REQ  = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API(?:_All)?\\s*\\}", CI);
        final java.util.regex.Pattern P_ROUTE_OLD_BR   = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_API(?:_All)?\\s*\\}", CI);
        final java.util.regex.Pattern P_POI_REQ        = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Map_POI(?:_All)?\\s*\\}", CI);
        final java.util.regex.Pattern P_MEDIA_REQ        = java.util.regex.Pattern.compile("Request\\s*:\\s*\\{\\s*Media_POI(?:_All)?\\s*\\}", CI);

        // —— 5) 清洗assistant文本并写入历史 的本地工具（lambda）—— //
        final java.util.regex.Pattern P_CODEBLOCK   = java.util.regex.Pattern.compile("(?is)```.*?```");
        final java.util.regex.Pattern P_JSON_LINE   = java.util.regex.Pattern.compile("(?m)^\\s*\\{.*\\}\\s*$|^\\s*\\[.*\\]\\s*$");
        final java.util.regex.Pattern P_API_TOKENS  = java.util.regex.Pattern.compile(
                "\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|Media_API|StepReport_API|User_History|Navigation_API|Map_API(?:_All)?|MAP_POI|Map_POI(?:_All)?)\\s*\\}"
                        + "|Request\\s*:\\s*\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|Media_API|StepReport_API|User_History|Navigation_API|Map_API(?:_All)?|MAP_POI|Map_POI(?:_All)?)\\s*\\}",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );

        java.util.function.Function<String, String> sanitizeAssistantForHistory = (String text) -> {
            if (text == null) return "";
            String s = P_CODEBLOCK.matcher(text).replaceAll(" ");
            s = P_API_TOKENS.matcher(s).replaceAll(" ");
            s = P_JSON_LINE.matcher(s).replaceAll(" ");
            s = s.replaceAll("\\s{2,}", " ").trim();
            return s;
        };

        java.util.function.Consumer<String> appendAssistantHistoryCleaned = (String cleaned) -> {
            try {
                org.json.JSONObject msg = new org.json.JSONObject()
                        .put("role", "assistant")
                        .put("content", cleaned == null ? "" : cleaned);
                if (conversationHistory != null) {
                    conversationHistory.put(new org.json.JSONObject(msg.toString())); // 深拷贝
                }
                if (localConversationHistory != null) {
                    localConversationHistory.put(msg);
                }
            } catch (Exception ignore) {}
        };

        // —— 6) 互相引用的回调容器 —— //
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> handleRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> feedRef   = new java.util.concurrent.atomic.AtomicReference<>();

        // —— 7) 位置注入（保持你现有逻辑）—— //
        try {
            getCurrentLocation(true, requireContext(), new LocationCallback() {
                @Override
                public void onLocationReceived(LatLng location) throws Exception {
                    userLocation = location;
                    if (shouldInjectAgain(null, userLocation, lastLocInjectedAt, /*cooldownMs*/ 5000L, /*minDeltaMeters*/ 5f)) {
                        injectUserLocationContext(userLocation, "UPDATE");
                        lastLocInjectedAt = System.currentTimeMillis();
                    }
                }
                @Override public void onLocationFailed(String error) {
                    Log.e(TAG,"chatbotFragment内部调用getCurrentLocation失败");
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // —— 8) 工具回执回喂 GPT（并把工具回执写入历史为 assistant）—— //
        feedRef.set((String toolPayload) -> {
            // 将工具回执写入 全局 & 本地 历史（作为 assistant）
            try {
                org.json.JSONObject toolMsg = new org.json.JSONObject()
                        .put("role", "assistant")
                        .put("content", toolPayload == null ? "" : toolPayload);
                if (conversationHistory != null) {
                    conversationHistory.put(new org.json.JSONObject(toolMsg.toString()));
                }
                if (localConversationHistory != null) {
                    localConversationHistory.put(toolMsg);
                }
            } catch (Exception ignore) {}

            final JSONArray historyToSend = localConversationHistory;

            // 控制自动调用链深度
            if (apiHops.incrementAndGet() == MAX_API_HOPS) {
                apiHops.set(0);
                String promote = "The method called by your token has been implemented. If you didn't just ask for the user's input on what to do next, ask for it; otherwise ignore these. "
                        + (toolPayload == null ? "" : toolPayload);

                try {
                    chatbotHelper.sendMessage(promote, historyToSend, new ChatbotResponseListener() {
                        @Override public void onResponse(String reply2) {
                            final String raw = (reply2 == null ? "" : reply2);
                            final String cleaned = sanitizeAssistantForHistory.apply(raw);
                            appendAssistantHistoryCleaned.accept(cleaned); // ✅ 存“清洗后”的

                            java.util.function.Consumer<String> h = handleRef.get();
                            if (h != null) h.accept(raw); // ✅ 用“原始”的做触发识别
                        }
                        @Override public void onFailure(String error) { }
                    });
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            } else {
                try {
                    Log.e("TAG","喂给聊天Agent的内容是:"+toolPayload);
                    chatbotHelper.sendMessage(toolPayload == null ? "" : toolPayload, historyToSend, new ChatbotResponseListener() {
                        @Override public void onResponse(String reply2) {
                            final String raw = (reply2 == null ? "" : reply2);
                            final String cleaned = sanitizeAssistantForHistory.apply(raw);
                            appendAssistantHistoryCleaned.accept(cleaned); // ✅ 存“清洗后”的

                            java.util.function.Consumer<String> h = handleRef.get();
                            if (h != null) h.accept(raw); // ✅ 用“原始”的做触发识别
                        }
                        @Override public void onFailure(String error) {
                            requireActivity().runOnUiThread(() ->
                                    addChatMessage("Failed to connect to Chatbot: " + error, false));
                        }
                    });
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // —— 9) 统一处理 GPT 回复（识别令牌、展示可见文本、调用工具）—— //
        handleRef.set((String replyRaw) -> {
            if (replyRaw == null) {
                requireActivity().runOnUiThread(() -> addChatMessage("（空响应）", false));
                return;
            }
            Log.e("tag","完整回复="+replyRaw);
            replyRaw = removeMultipleTokens(replyRaw); // 你已有的去重/规整方法（如无可去掉）

            boolean needRoute     = P_ROUTE_BRACE.matcher(replyRaw).find()   || P_ROUTE_REQ.matcher(replyRaw).find()
                    || P_ROUTE_OLD_BR.matcher(replyRaw).find() || P_ROUTE_OLD_REQ.matcher(replyRaw).find();
            boolean needCertain   = P_CERTAIN_BRACE.matcher(replyRaw).find() || P_CERTAIN_REQ.matcher(replyRaw).find();
            boolean needDraw      = P_DRAW_BRACE.matcher(replyRaw).find()    || P_DRAW_REQ.matcher(replyRaw).find();
            boolean needMedia      = P_MEDIA_BRACE.matcher(replyRaw).find()    || P_MEDIA_REQ.matcher(replyRaw).find();
            boolean needHistory   = P_HISTORY_BRACE.matcher(replyRaw).find() || P_HISTORY_REQ.matcher(replyRaw).find();
            boolean needNav       = P_NAV_BRACE.matcher(replyRaw).find()     || P_NAV_REQ.matcher(replyRaw).find();
            boolean needStepReport= P_STEP_REPORT.matcher(replyRaw).find()   || P_STEP_REPORT_REQ.matcher(replyRaw).find();
            boolean needPOI       = P_POI_BRACE.matcher(replyRaw).find()     || P_POI_REQ.matcher(replyRaw).find();

            // —— 用于展示的“可见文本”：去掉令牌/多余行 —— //
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
            visible = P_HISTORY_BRACE.matcher(visible).replaceAll("");
            visible = P_HISTORY_REQ.matcher(visible).replaceAll("");
            visible = P_NAV_BRACE.matcher(visible).replaceAll("");
            visible = P_NAV_REQ.matcher(visible).replaceAll("");
            visible = P_STEP_REPORT_REQ.matcher(visible).replaceAll("");
            visible = P_POI_REQ.matcher(visible).replaceAll("");
            visible = P_POI_BRACE.matcher(visible).replaceAll("");
            visible = P_MEDIA_REQ.matcher(visible).replaceAll("");
            visible =P_MEDIA_BRACE.matcher(visible).replaceAll("");
            visible = visible.replaceAll("\\n{3,}", "\n\n").trim();

            if (!visible.isEmpty()) {
                String finalVisible = visible;
                requireActivity().runOnUiThread(() -> addChatMessage(finalVisible, false));
            }

            // —— 工具触发 —— //
            if (needHistory) {
                handleHistoryRequest(appDatabase, userKey, feedRef);
                return;
            }
            if (needCertain) {
                handleCertainRequest(lastUserMsg, lastCertainListRef, feedRef);
                return;
            }
            if (needRoute) {
                handleRouteRequest(lastUserMsg, dialogForRoute, lastCertainListRef, feedRef);
                return;
            }
            if (needStepReport) {
                handleStepReport(feedRef);
                return;
            }
            if (needPOI) {
                try {
                    handelPOI(dialogForRoute, feedRef);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                return;
            }
            if (needNav) {
                Log.e(TAG, "Navigation_API triggered");
                new Thread(() -> {
                    final long deadline = android.os.SystemClock.uptimeMillis() + 1500; // 最多等 1.5s
                    List<Location> r;
                    do {
                        r = generatedRoute;  // 假设已声明为 volatile
                        if (r != null && !r.isEmpty()) break;
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    } while (android.os.SystemClock.uptimeMillis() < deadline);

                    final List<Location> finalRoute = r;
                    requireActivity().runOnUiThread(() -> {
                        if (finalRoute == null || finalRoute.isEmpty()) {
                            Log.e(TAG,"路线尚未生成就调用了导航");
                            return;
                        }
                        try {
                            handleNavigationRequest(finalRoute);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }).start();
                return;
            }
            if (needDraw) {
                handleDrawRequest(dialogForRoute, lastRouteRef, feedRef);
                return;
            }
            if(needMedia){
                try {
                    handleMediaRequest();
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
            // 无工具触发：只展示自然语言（上面已展示）
        });

        // —— 10) 首次把用户消息发给 GPT —— //
        chatbotHelper.sendMessage(userMessage, localConversationHistory, new ChatbotResponseListener() {
            @Override public void onResponse(String reply) {
                final String raw = (reply == null ? "" : reply);
                final String cleaned = sanitizeAssistantForHistory.apply(raw);
                appendAssistantHistoryCleaned.accept(cleaned); // ✅ 存清洗后的

                java.util.function.Consumer<String> h = handleRef.get();
                if (h != null) h.accept(raw); // ✅ 用原始的做触发识别
            }
            @Override public void onFailure(String error) {
                requireActivity().runOnUiThread(() ->
                        addChatMessage("Failed to connect to Chatbot: " + error, false));
            }
        });
    }





    // === 1) 工具风格注入：把 APP 事件当作“assistant”角色写入历史，然后把同样文本作为下一轮提示发给 LLM ===
    public void injectAppToolPayload(String toolPayload) throws JSONException {
        try {
            org.json.JSONObject toolMsg = new org.json.JSONObject()
                    .put("role", "assistant")
                    .put("content", toolPayload == null ? "" : toolPayload);

            if (conversationHistory != null) {
                conversationHistory.put(toolMsg); // 全局历史
            }
            if (localConversationHistory != null) {
                // 深拷贝写入本地历史，避免共享引用
                localConversationHistory.put(new org.json.JSONObject(toolMsg.toString()));
            }
        } catch (Exception ignore) {}

        final org.json.JSONArray historyToSend = localConversationHistory;

        // 给模型的“下一轮输入”就直接用 toolPayload；这与你 feedRef 的做法保持一致
        chatbotHelper.sendMessage(toolPayload == null ? "" : toolPayload, historyToSend,
                new ChatbotResponseListener() {
                    @Override public void onResponse(String reply) {
                        // 对回复做一次轻量清理（去掉可能出现的令牌、代码块）
                        String visible = cleanupVisible(reply);
                        if (visible != null && !visible.isEmpty()) {
                            requireActivity().runOnUiThread(() -> addChatMessage(visible, false));
                        }
                        // 注意：此路径不再触发 {Map_API_...} 等工具调用（鼓励话术用不到）
                    }
                    @Override public void onFailure(String error) {
                        requireActivity().runOnUiThread(() ->
                                addChatMessage("Failed to connect to Chatbot: " + error, false));
                    }
                });
    }

    // === 2) 轻量清理：把可能出现的令牌/代码块去掉，保持 UI 纯净 ===
    private String cleanupVisible(String raw) {
        if (raw == null) return "";
        String v = raw;
        v = v.replaceAll("(?is)```.*?```", " "); // 代码块
        // 花括号令牌 & Request:{令牌}
        v = v.replaceAll("(?i)\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|StepData_API|User_History|Navigation_API|Map_API(?:_All)?)\\s*\\}", " ");
        v = v.replaceAll("(?i)Request\\s*:\\s*\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|StepData_API|User_History|Navigation_API|Map_API(?:_All)?)\\s*\\}", " ");
        v = v.replaceAll("\\n{3,}", "\n\n").trim();
        return v;
    }

    private String removeMultipleTokens(String input) {
        // 查找第一个有效的令牌
        Pattern pattern = Pattern.compile("\\{\\s*(Map_API_Route|Map_API_Certain|Drawing_API|StepData_API|Navigation_API)\\s*\\}");
        Matcher matcher = pattern.matcher(input);

        if (!matcher.find()) {
            // 如果没有找到任何令牌，直接返回原始字符串
            return input;
        }

        // 获取第一个令牌的完整文本
        String firstToken = matcher.group();

        // 构建清理后的字符串
        StringBuffer cleaned = new StringBuffer();

        // 保留第一个令牌，移除所有后续令牌
        matcher.appendReplacement(cleaned, "FIRST_TOKEN_PLACEHOLDER");

        // 继续处理剩余部分，但不再替换任何令牌
        while (matcher.find()) {
            // 对于后续的令牌，不进行替换（即移除它们）
        }
        matcher.appendTail(cleaned);

        // 恢复第一个令牌为其原始格式
        String result = cleaned.toString().replace("FIRST_TOKEN_PLACEHOLDER", firstToken);

        Log.e(TAG, "token=" + result);
        return result;
    }



    private void handelPOI(String dialogForRoute, AtomicReference<Consumer<String>> feedRef) throws JSONException {
        Log.e("tag","传入的dialogForRoute内容是="+dialogForRoute);
        JSONArray POIs=getInterestingPoint(requireContext(),dialogForRoute);

        Log.e("tag","获取的POI是="+POIs);



        Consumer<String> f = feedRef.get();
        if (f != null) requireActivity().runOnUiThread(() -> f.accept(String.valueOf(POIs)));
    }

    private void handleHistoryRequest(AppDatabase appDatabase,
                                      String userKey,
                                      AtomicReference<Consumer<String>> feedRef) {
        new Thread(() -> {
            try {
                List<Route> historyRoutes = appDatabase.routeDao().getRoutesByUserKey(userKey);
                JSONArray historyArr = new JSONArray();
                int i = 0;
                for (Route r : historyRoutes) {
                    i++;
                    if (i >= 10) break;
                    JSONObject obj = new JSONObject();
                    obj.put("id", r.getId());
                    obj.put("name", r.getName());
                    obj.put("createdAt", r.getCreatedAt());
                    obj.put("description", r.getDescription());
                    historyArr.put(obj);
                }
                String payloadHistory = "API_Result:{User_History}\n" + historyArr.toString();
                Consumer<String> f = feedRef.get();

                if (f != null) requireActivity().runOnUiThread(() -> f.accept(payloadHistory));

            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        addChatMessage("获取历史失败：" + e.getMessage(), false));
            }
        }).start();
    }
    private void handleRouteRequest(String lastUserMsg,
                                    String dialogForRoute,
                                    AtomicReference<JSONArray> lastCertainListRef,
                                    AtomicReference<java.util.function.Consumer<String>> feedRef) {
        new Thread(() -> {
            try {


                Log.e("tag","1");
                JSONArray poiList = lastCertainListRef.get();
                JSONObject chosen = null;
                if(!hascertain){
                    org.json.JSONArray poiArray;
                    try {
                        Log.e("tag","2");
                        poiArray = GeographyAgent.getCoreLocationsFromRequirement(requireContext(),dialogForRoute);
                    } catch (Exception ex) {
                       // Log.e(TAG, "Map_API_Certain 调用失败：", ex);
                        poiArray = new org.json.JSONArray();
                        poiList=poiArray;
                    }
                }else poiList = lastCertainListRef.get();

                Log.e("tag","3");
                if (poiList != null && poiList.length() > 0) {
                    String msg = (lastUserMsg == null) ? "" : lastUserMsg;

                    // 2.1 名称包含（不区分大小写）
                    for (int i = 0; i < poiList.length(); i++) {
                        JSONObject o = poiList.optJSONObject(i);
                        if (o == null) continue;
                        String nm = o.optString("name", "");
                        if (!nm.isEmpty() && msg.toLowerCase(Locale.ROOT).contains(nm.toLowerCase(Locale.ROOT))) {
                            chosen = o; break;
                        }
                    }
                    Log.e("tag","4");
                    // 2.2 模糊匹配
                    if (chosen == null && !msg.isEmpty()) {
                        String normMsg = msg.replaceAll("[\\s\\p{Punct}]+","").toLowerCase(Locale.ROOT);
                        for (int i = 0; i < poiList.length(); i++) {
                            JSONObject o = poiList.optJSONObject(i);
                            if (o == null) continue;
                            String nm = o.optString("name", "");
                            String normNm = nm.replaceAll("[\\s\\p{Punct}]+","").toLowerCase(Locale.ROOT);
                            if (!normNm.isEmpty() && normMsg.contains(normNm)) { chosen = o; break; }
                        }
                    }

                    // 2.3 仍无 → 默认取第一个
                    if (chosen == null) chosen = poiList.optJSONObject(0);
                }
                Log.e("tag","5");
                JSONArray flagged = fetchTrafficEventsOnce(userLocation, 6000);
                String avoidHint = buildAvoidHint(flagged);

                // 2.4 生成“路线”
                List<Location> route;
                if (chosen != null) {
                    Log.e("tag","6");
                    String hint = String.format(Locale.US,
                            "\n[ROUTE] name=%s; lat=%.6f; lng=%.6f",
                            chosen.optString("name",""),
                            chosen.optDouble("latitude", 0d),
                            chosen.optDouble("longitude", 0d));
                    route = generateRoute(requireContext(), dialogForRoute + hint+avoidHint);
                } else {
                    route = generateRoute(requireContext(), dialogForRoute+avoidHint);
                }
                Log.e("tag","7");
                // === 写入全局 generatedRoute（用不可变副本，避免并发写） ===
                if (route == null) {
                    generatedRoute = java.util.Collections.emptyList();
                } else {
                    generatedRoute = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(route));
                }
                Log.e(TAG, "generatedRoute size=" + generatedRoute.size());
                Log.e("tag","8");


                JSONArray routeArr = new JSONArray();
                if (route != null) {
                    for (Location L : route) {
                        JSONObject o = new JSONObject();
                        o.put("name", L.getName());
                        o.put("latitude", L.getLatitude());   // 保持 latitude/longitude 键名
                        o.put("longitude", L.getLongitude());
                        routeArr.put(o);
                    }
                }
                Log.e("tag","9");
                String payloadRoute = "now you should respond with {Drawing_API} if you want to show user the route.Route generated from your request {Map_API_Route}\n,!!! ";
                Consumer<String> f = feedRef.get();
                if (f != null) requireActivity().runOnUiThread(() -> f.accept(payloadRoute));


            } catch (Exception e) {
                Log.e("TAG","Map_API_Route 调用失败：" + e.getMessage());
            }
        }).start();
    }
    // 新增一个数据模型类来封装返回的数据


    // 专门获取数据的方法
    // 处理步骤报告（读取数据 + 可视化）
    private void handleStepReport(java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> feedRef) {
        // 不要再包一层 new Thread(); loadStepWeeklyReport 本身是异步的
        loadStepWeeklyReport(new StepWeeklyReportCallback() {
            @Override
            public void onSuccess(StepWeeklyReport report) {
                // 若回调已经在主线程（推荐实现），这里直接更新 UI
                if (!isAdded() || getView() == null) return; // Fragment 已经被移除则不再更新

                try {
                    addWeeklyExerciseChart(report.stepsMap, report.distanceKmMap, report.calorieMap);
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "addWeeklyExerciseChart failed", t);
                    addChatMessage("图表渲染失败：" + t.getMessage(), false);
                }

                // 安全使用 feedRef
                try {
                    java.util.function.Consumer<String> feed = (feedRef != null) ? feedRef.get() : null;
                    sendStepAnalysisReport(report, feedRef); // 如果你的实现内部会判空，这行即可
                    // 或者： if (feed != null) feed.accept(...);
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "sendStepAnalysisReport failed", t);
                }
            }

            @Override
            public void onError(Throwable t) {
                // 同样假设回调在主线程
                android.util.Log.e(TAG, "loadStepWeeklyReportAsync failed", t);
                if (!isAdded() || getView() == null) return;
                addChatMessage("获取运动周报失败：" + t.getMessage(), false);
            }
        });
    }




    // 数据模型类
    private static class StepWeeklyReport {
        LinkedHashMap<String, Integer> stepsMap;
        LinkedHashMap<String, Float> distanceKmMap;
        LinkedHashMap<String, Float> calorieMap;
        float weight;
        String periodStart;
        String periodEnd;

        StepWeeklyReport(LinkedHashMap<String, Integer> stepsMap,
                         LinkedHashMap<String, Float> distanceKmMap,
                         LinkedHashMap<String, Float> calorieMap,
                         float weight, String periodStart, String periodEnd) {
            this.stepsMap = stepsMap;
            this.distanceKmMap = distanceKmMap;
            this.calorieMap = calorieMap;
            this.weight = weight;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
        }
        public String stepsToString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Step Weekly Report (")
                    .append(periodStart).append(" ~ ").append(periodEnd).append(")\n");

            for (String date : stepsMap.keySet()) {
                int steps = stepsMap.get(date);
                sb.append(date).append(" : ").append(steps).append(" steps\n");
            }

            return sb.toString();
        }
    }

    // 获取步骤周报数据的通用方法
    private final ExecutorService ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // 回调接口：主线程回调
    public interface StepWeeklyReportCallback {
        void onSuccess(StepWeeklyReport report);
        void onError(Throwable t);
    }

    // 对外异步方法：不会阻塞主线程
    public void loadStepWeeklyReport(@NonNull StepWeeklyReportCallback callback) {
        final android.content.Context appCtx = getContext().getApplicationContext();
        ioExecutor.execute(() -> {
            try {
                StepWeeklyReport report = getStepWeeklyReportInternal(appCtx);
                mainHandler.post(() -> callback.onSuccess(report));   // 回主线程
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));          // 回主线程
            }
        });
    }

    // 原同步逻辑抽到内部私有方法里：仅在 I/O 线程调用
    private StepWeeklyReport getStepWeeklyReportInternal(android.content.Context context) throws Exception {
        // === 1) 读取用户与数据库 ===
        String userKey = context
                .getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
                .getString("USER_KEY", null);

        AppDatabase db = AppDatabase.getDatabase(context);
        StepDao stepDao = db.stepDao();
        UserDao userDao = db.userDao();

        // 体重（kg），失败则默认 70kg
        float weight = 70f;
        try {
            weight = userDao.getUserByKey(userKey).getWeight();
        } catch (Exception ignore) {}

        // === 2) 计算过去7天（含今天）的日期范围 ===
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.util.Date today = cal.getTime();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -6);
        java.util.Date startDate = cal.getTime();
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());

        // 有序 Map（保持日期顺序）
        java.util.LinkedHashMap<String, Integer> stepsMap = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Float> distanceKmMap = new java.util.LinkedHashMap<>(); // 公里
        java.util.LinkedHashMap<String, Float> calorieMap = new java.util.LinkedHashMap<>();

        // === 3) 循环取 7 天数据 ===
        java.util.Calendar tmp = java.util.Calendar.getInstance();
        tmp.setTime(startDate);
        while (!tmp.getTime().after(today)) {
            String dateStr = sdf.format(tmp.getTime());
            Step stepRecord = stepDao.getStepByDate(userKey, dateStr);

            int steps = 0;
            float distanceM = 0f;
            if (stepRecord != null) {
                steps = Math.max(0, stepRecord.getStepCount());
                distanceM = Math.max(0f, stepRecord.getDistance()); // 米
            }
            float distanceKm = distanceM / 1000f;
            // 卡路里公式
            float calories = distanceKm * weight * 1.036f;

            stepsMap.put(dateStr, steps);
            distanceKmMap.put(dateStr, distanceKm);
            calorieMap.put(dateStr, calories);

            tmp.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }

        return new StepWeeklyReport(
                stepsMap, distanceKmMap, calorieMap, weight,
                sdf.format(startDate), sdf.format(today)
        );
    }
    // 发送步骤分析报告的通用方法
    private void sendStepAnalysisReport(StepWeeklyReport report,
                                        java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> feedRef) {
        try {
            org.json.JSONArray days = new org.json.JSONArray();
            for (String d : report.stepsMap.keySet()) {
                org.json.JSONObject one = new org.json.JSONObject()
                        .put("date", d)
                        .put("steps", report.stepsMap.get(d))
                        .put("distance_km", round2(report.distanceKmMap.get(d)))
                        .put("calorie_kcal", round1(report.calorieMap.get(d)));
                days.put(one);
            }

            org.json.JSONObject jsonReport = new org.json.JSONObject()
                    .put("period_start", report.periodStart)
                    .put("period_end", report.periodEnd)
                    .put("weight_kg", round1(report.weight))
                    .put("days", days);

            String payload = "StepData_Report " + jsonReport.toString() +
                    "\n请基于这些数据给出简短中文分析与鼓励（≤100字，避免使用任何 {Map_API_*} 令牌或代码块）。";

            java.util.function.Consumer<String> feeder = (feedRef != null) ? feedRef.get() : null;
            if (feeder != null) feeder.accept(payload);
        } catch (Exception e) {
            android.util.Log.e(TAG, "feedRef accept failed", e);
        }
    }

    private static float round1(float v) { return Math.round(v * 10f) / 10f; }
    private static float round2(float v) { return Math.round(v * 100f) / 100f; }



    private void handleDrawRequest(String dialogForRoute,
                                   AtomicReference<List<Location>> lastRouteRef,
                                   AtomicReference<java.util.function.Consumer<String>> feedRef) {
        List<Location> r = lastRouteRef.get();
        if (r == null || r.isEmpty()) {
            new Thread(() -> {
                try {
                    List<Location> route = generateRoute(requireContext(), dialogForRoute);
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

    private void handleCertainRequest(String lastUserMsg,
                                      AtomicReference<org.json.JSONArray> lastCertainListRef,
                                      AtomicReference<java.util.function.Consumer<String>> feedRef) {
        hascertain=true;
        new Thread(() -> {
            try {
                org.json.JSONArray poiArray;
                try {
                    // ✅ 只用“上一句用户输入”
                    poiArray = GeographyAgent.getCoreLocationsFromRequirement(requireContext(),lastUserMsg);
                } catch (Exception ex) {
                  //  Log.e(TAG, "Map_API_Certain 调用失败：", ex);
                    poiArray = new org.json.JSONArray();
                }

                lastCertainListRef.set(poiArray);

                String payloadCertain = "API_Result:{Map_API_Certain}，从近到远排序\n" + poiArray.toString();
                java.util.function.Consumer<String> f = feedRef.get();
                if (f != null) requireActivity().runOnUiThread(() -> f.accept(payloadCertain));

            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        addChatMessage("获取 POI 失败：" + e.getMessage(), false));
            }
        }).start();
    }




    @SuppressLint("SetTextI18n")
    private void handleNavigationRequest(List<Location> route) {
        if (route != null)
            Log.e(TAG, "喂给导航的路线点数量：" + route.size());
        else
            Log.e(TAG, "喂给导航的路线点数量:0");

        // 先切换 ChatbotFragment 自己的 UI（聊天区→导航区）
        startNav = true;
        chatModeContainer.setVisibility(View.GONE);
        navigationModeContainer.setVisibility(View.VISIBLE);
        rvNav.setAdapter(chatAdapter);
        rvNav.scrollToPosition(Math.max(0, messageList.size() - 1));
        activeInput = etNav;
        activeSend = btnSendNav;
        bindComposer(activeInput, activeSend);

        // ⚠️ 注意：上传成功回调里再创建并提交 WalkFragment
        uploadUserHistory(
                lastRouteForUpload,
                conversationHistory,
                new RouteSyncManager.OnRouteCreated() {
                    @Override
                    public void onSuccess(long routeId) {
                        Log.i("RouteSync", "✅ 上传成功 routeId=" + routeId);

                        requireActivity().runOnUiThread(() -> {
                            Bundle b = new Bundle();
                            b.putString("user_key", userKey);
                            b.putSerializable("routeId", routeId);
                            if (route != null && !route.isEmpty()) {
                                b.putSerializable("route_points", new ArrayList<>(route));
                            }

                            WalkFragment wf = new WalkFragment();
                            wf.setArguments(b);

                            getChildFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.navigation_container, wf)
                                    .commitNow();

                            WalkFragment current = (WalkFragment) getChildFragmentManager()
                                    .findFragmentById(R.id.navigation_container);

                            JSONArray userInputs = buildUserInputs(conversationHistory);
                            try {
                                if (current != null) {
                                    current.attachSmartGuide(userInputs);
                                }
                            } catch (Exception e) {
                                Log.e("ChatbotFragment", "attachSmartGuide failed", e);
                            }

                            View nav = requireView().findViewById(R.id.navigation_container);
                            nav.post(() -> Log.d("ChatbotFragment",
                                    "navigation_container size = " + nav.getWidth() + "x" + nav.getHeight()));
                        });
                    }

                    @Override
                    public void onFail(Exception e) {
                        Log.e("RouteSync", "❌ 上传失败", e);
                    }
                });

    }










    private static String buildAvoidHint(JSONArray flagged) {
        if (flagged == null || flagged.length() == 0) return "";

        StringBuilder sb = new StringBuilder("\n[AVOID_COORDS] ");
        java.util.Set<String> dedup = new java.util.HashSet<>();
        int kept = 0;

        for (int i = 0; i < flagged.length(); i++) {
            JSONObject ev = flagged.optJSONObject(i);
            if (ev == null) continue;

            String type = ev.optString("type", "event");
            double lat = ev.optDouble("lat", Double.NaN);
            double lng = ev.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;

            // ~11m 级别去重
            String key = Math.round(lat * 1e4) + "," + Math.round(lng * 1e4);
            if (!dedup.add(key)) continue;

            if (kept > 0) sb.append("; ");
            sb.append(String.format(java.util.Locale.US, "%s@%.6f,%.6f", type, lat, lng));

            kept++;
            if (kept >= 12) break; // 防止 hint 过长
        }
        return kept > 0 ? sb.toString() : "";
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




    /* ---------- 可视化地图 ---------- */
    @SuppressLint("SetTextI18n")
    private void addBotRouteMessage(List<List<Location>> routes) throws Exception {
        List<List<Location>> ordered_routes = rank(routes);

        // 取排序后第 1 条作为"最优路线"用于上传
        if (ordered_routes != null && !ordered_routes.isEmpty()) {
            lastRouteForUpload = new java.util.ArrayList<>(ordered_routes.get(0));
            lastRouteNameForUpload = buildRouteName(ordered_routes.get(0));
        } else {
            lastRouteForUpload = java.util.Collections.emptyList();
            lastRouteNameForUpload = "";
        }

        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.bot_route_message, recyclerView, false);
        LinearLayout container = root.findViewById(R.id.route_maps_container);
        int mapH = (int) (150 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < Math.min(ordered_routes.size(), 3); i++) {
            final List<Location> route = ordered_routes.get(i);

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
            mapView.onResume(); // ✅ 确保地图渲染

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
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.setMargins(12, 12, 0, 0);
            chip.setLayoutParams(chipLp);
            card.addView(chip);

            // 计算整个路线的总长度并转换为千米
            double totalRouteDistanceKm = computeRouteDistanceMeters(route) / 1000.0;

// 更新显示为整个路线的长度（以千米为单位）
            chip.setText(String.format(Locale.getDefault(), "%.2f km", totalRouteDistanceKm));

            /* ——— Map ready ——— */
            mapView.postDelayed(() -> {
                AMap aMap = mapView.getMapView() != null ? mapView.getMapView().getMap() : null;
                if (aMap == null) return;

                // 构建 LatLng 列表并检查 null
                List<LatLng> latLngs = new ArrayList<>();
                for (Location l : route) {
                    if (l != null) {
                        latLngs.add(new LatLng(l.getLatitude(), l.getLongitude()));
                    }
                }

                // 调用 drawRoute 绘制路线
                try {
                    mapView.drawRoute(latLngs, Color.parseColor("#FF4081"));
                } catch (Exception e) {
                    Log.e(TAG, "drawRoute error: " + e.getMessage(), e);
                }

                aMap.showMapText(false);

                // ====== 起点/终点 marker ======
                if (!route.isEmpty()) {
                    Location startLoc = route.get(0);
                    Location endLoc = route.get(route.size() - 1);

                    LatLng startLatLng = new LatLng(startLoc.getLatitude(), startLoc.getLongitude());
                    LatLng endLatLng = new LatLng(endLoc.getLatitude(), endLoc.getLongitude());

                    // 如果自定义图标不存在，可以先用默认绿色/红色 marker
                    BitmapDescriptor startIcon;
                    BitmapDescriptor endIcon;
                    try {
                        startIcon = BitmapDescriptorFactory.fromResource(R.drawable.ic_pin_start);
                    } catch (Exception e) {
                        startIcon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN);
                    }
                    try {
                        endIcon = BitmapDescriptorFactory.fromResource(R.drawable.ic_pin_end);
                    } catch (Exception e) {
                        endIcon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED);
                    }

                    MarkerOptions startMarker = new MarkerOptions()
                            .position(startLatLng)
                            .title(startLoc.getName() != null ? startLoc.getName() : "Start")
                            .anchor(0.5f, 1.0f)
                            .zIndex(1000)
                            .icon(startIcon);

                    MarkerOptions endMarker = new MarkerOptions()
                            .position(endLatLng)
                            .title(endLoc.getName() != null ? endLoc.getName() : "End")
                            .anchor(0.5f, 1.0f)
                            .zIndex(1000)
                            .icon(endIcon);

                    aMap.addMarker(startMarker);
                    aMap.addMarker(endMarker);
                }
                // ====== 起点/终点 marker 结束 ======

                // north/south 标签逻辑
                Location north = Collections.max(route, Comparator.comparingDouble(Location::getLatitude));
                Location south = Collections.min(route, Comparator.comparingDouble(Location::getLatitude));

                Log.e("DEBUG", north.getName());
                Log.e("DEBUG", south.getName()); // 应该能获取 name

                ExecutorService pool = Executors.newSingleThreadExecutor();
                pool.execute(() -> {
                    drawLabel(aMap, north, -60);
                    if (!north.equals(south)) {
                        drawLabel(aMap, south, +60);
                    }
                });
                pool.shutdown();

                // 计算中心点和缩放级别
                if (!latLngs.isEmpty()) {
                    LatLng center = MapTool.calculateCenter(latLngs);
                    float zoomLevel = getZoomLevel(route);
                    aMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                            new CameraPosition.Builder().target(center).zoom(zoomLevel).tilt(30f).build()));
                }

                aMap.getUiSettings().setAllGesturesEnabled(false);
            }, 200);

            container.addView(card);
        }

        addChatMessage(root, false);

    }





    private void bindComposer(EditText input, View sendBtn) {
        // 先清理旧的点击（防止重复 setOnClickListener 时触发多次）
        sendBtn.setOnClickListener(null);

        sendBtn.setOnClickListener(v -> {
            String userMessage = input.getText().toString().trim();
            if (userMessage.isEmpty()) return;

            addChatMessage(userMessage, true);       // 你的已有方法：把用户消息插入列表 & notify
            input.setText("");

            // —— 你现有的 LLM 发送逻辑（保留），例如：
            // conversationHistory = ensureSystemPromote(conversationHistory, promote);
            try {
                sendWithPromoteAndTooling(userMessage);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        });
    }


    /**
     * 根据路线中最远两点的直线距离来决定地图缩放级别
     * @param route 一条路线（点的列表）
     * @return 建议的缩放等级
     */
    private static float getZoomLevel(List<Location> route) {
        if (route == null || route.size() < 2) {
            return 16f; // 默认近距离
        }

        double maxDistanceMeters = 0;

        // 枚举两两点对，找出最大直线距离
        for (int i = 0; i < route.size(); i++) {
            for (int j = i + 1; j < route.size(); j++) {
                double d = MapTool.distanceBetween(
                        new LatLng(route.get(i).getLatitude(), route.get(i).getLongitude()),
                        new LatLng(route.get(j).getLatitude(), route.get(j).getLongitude())
                );
                if (d > maxDistanceMeters) {
                    maxDistanceMeters = d;
                }
            }
        }

        float distanceKm = (float) (maxDistanceMeters / 1000.0); // 转换成 km
        float zoomLevel;

        if (distanceKm <= 1.0f) {
            zoomLevel = 14f;
        } else if (distanceKm <= 3.0f) {
            zoomLevel = 13.5f;
        } else if (distanceKm <= 5.0f) {
            zoomLevel = 13f;
        } else if (distanceKm <= 8.0f) {
            zoomLevel = 12.5f;
        } else {
            zoomLevel = 12f;
        }

        Log.e(TAG,zoomLevel+"");
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


    private String buildRouteName(List<Location> r) {
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
        if (r == null || r.isEmpty()) return "Route " + ts;
        String s = trimLocationName(r.get(0).getName());
        String e = trimLocationName(r.get(r.size() - 1).getName());
        if (android.text.TextUtils.isEmpty(s) || android.text.TextUtils.isEmpty(e)) return "Route " + ts;
        return s + " → " + e + " · " + ts;
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

    // === 放在 RouteSyncManager 类内（与其它 static 方法同级）===

    /**
     * 一次性上传：Route（含对话历史） + 路线点位。
     * 会自动初始化（若尚未 init）。
     */
    public static void uploadUserHistory(
            @NonNull List<Location> routePoints,
            @NonNull org.json.JSONArray conversationHistory,
            @NonNull RouteSyncManager.OnRouteCreated cb
    ) {
        ensureInitialized();

        if (routePoints.isEmpty()) {
            cb.onFail(new IllegalArgumentException("routePoints empty"));
            return;
        }

        long now = System.currentTimeMillis();

        // 1) 构造 Route
        Route route = new Route();

        if (userKey == null || userKey.isEmpty()) {
            cb.onFail(new IllegalStateException("not login"));
            return;
        }
        route.setUserKey(userKey);
        route.setName(defaultRouteName(routePoints));
        route.setCreatedAt(now);
        route.setDescription(buildDescriptionJson(routePoints, conversationHistory, now));

        setPendingRouteDescription(route.getDescription());

        // 2) 云端创建路线（createRoute 内部会同步写本地 Route）
        createRoute(route, new RouteSyncManager.OnRouteCreated() {
            @Override public void onSuccess(long routeId) {
                // 3) 云端 + 本地 点位
                List<LocationDTO> dtoList = new ArrayList<>(routePoints.size());

                for (int i = 0; i < routePoints.size(); i++) {
                    Location loc = routePoints.get(i);



                    // DTO 给云端
                    // 云端 DTO 直接用本地的 id 和 routeId
                    dtoList.add(new LocationDTO(
                            loc.getId(),          // 用本地生成的随机 long
                            i,                    // indexNum
                            route.getId(),        // 用本地生成的 routeId
                            loc.getName(),
                            loc.getLatitude(),
                            loc.getLongitude()
                    ));


                    // 本地点位落库：回写 routeId
                    loc.setRouteId(routeId);
                    loc.setIndexNum(i);
                    try {
                        appDatabase.locationDao().insert(loc);
                    } catch (Exception e) {
                        Log.e("RouteSyncManager", "Local insert Location failed", e);
                    }
                }

                // 云端批量上传
                uploadLocations(dtoList);

                cb.onSuccess(routeId);
            }

            @Override public void onFail(Exception e) {
                cb.onFail(e);
            }
        });
    }

    private static String buildDescriptionJson(List<Location> pts,
                                               org.json.JSONArray history,
                                               long now) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("createdAt", now);
            root.put("pointCount", pts.size());
            if (history != null) root.put("conversationHistory", history);

            org.json.JSONObject start = new org.json.JSONObject()
                    .put("lat", pts.get(0).getLatitude())
                    .put("lng", pts.get(0).getLongitude());
            org.json.JSONObject end = new org.json.JSONObject()
                    .put("lat", pts.get(pts.size() - 1).getLatitude())
                    .put("lng", pts.get(pts.size() - 1).getLongitude());
            root.put("start", start);
            root.put("end", end);

            return root.toString();
        } catch (Exception e) {
            return "route&conversation captured at " + now;
        }
    }

    private static String defaultRouteName(List<Location> r) {
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
        return "Route · " + ts;
    }




    // === 生成 description：把“对话历史 + 路线概要”打包成字符串（落到 Route.description） ===





    // —— 辅助：服务器地址（支持从偏好读取），默认 10.0.2.2:8080 便于本机调试 ——




    @Override public void onResume() { super.onResume(); GeographyAgent.setTranscriptProvider(() -> buildFullTranscript(null)); }
    @Override public void onPause()  { super.onPause();  GeographyAgent.setTranscriptProvider(null); }



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

    // ChatbotFragment.java（类内新增）
    private @NonNull String buildFullTranscript(@Nullable String appendUserMessage) {
        StringBuilder sb = new StringBuilder(8 * 1024);
        try {
            if (conversationHistory != null) {
                for (int i = 0; i < conversationHistory.length(); i++) {
                    org.json.JSONObject it = conversationHistory.optJSONObject(i);
                    if (it == null) continue;
                    String role = it.optString("role", "");
                    String content = it.optString("content", "");
                    if (content == null) content = "";
                    if (!role.isEmpty()) {
                        sb.append(role.toUpperCase(java.util.Locale.ROOT))
                                .append(": ").append(content).append('\n');
                    } else {
                        sb.append(content).append('\n');
                    }
                }
            }
        } catch (Exception ignore) {}
        if (appendUserMessage != null && !appendUserMessage.isEmpty()) {
            sb.append("USER: ").append(appendUserMessage).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) 注册：多权限
        requestPermsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean fine  = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                    boolean coarse= Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                    boolean locOK = fine || coarse;

                    boolean actOK = (Build.VERSION.SDK_INT < 29)
                            || Boolean.TRUE.equals(result.get(Manifest.permission.ACTIVITY_RECOGNITION));

                    if (locOK && actOK) {
                        // 2) 如需后台定位，单独再申请（注意用另一个 launcher）
                        if (Build.VERSION.SDK_INT >= 29 &&
                                ContextCompat.checkSelfPermission(requireContext(),
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                        != PackageManager.PERMISSION_GRANTED) {
                            // 先做一段说明 UI 更友好，然后：
                            requestBgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                            return;
                        }
                        startSafeLocationStuff(); // 权限齐活，开始你的业务
                    } else {
                        // 这里按需处理“不再询问”等分支

                    }
                }
        );

        // 2) 注册：单权限（后台定位）
        requestBgLocLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startSafeLocationStuff();
                    } else {
                        showExplainWhyDialog();

                    }
                }
        );
    }
    private void ensurePermissions() {
        List<String> need = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!need.isEmpty()) {
            requestPermsLauncher.launch(need.toArray(new String[0]));
        } else {
            // 前台权限已就绪
            if (Build.VERSION.SDK_INT >= 29 &&
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                // 仅当缺“后台定位”时再单独申请
                requestBgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            } else {
                // 所有需要的权限都齐了 → 不再弹窗，直接开始你的业务 & 隐藏权限提示 UI（如有）
                // hidePermissionBanner(); // 如果你有这样的 UI
                startSafeLocationStuff();
            }
        }

    }

    private long lastLocInjectedAt = 0L;

    // 统一构造一条“位置上下文”的 assistant 工具消息并写入会话历史
    private void injectUserLocationContext(@androidx.annotation.Nullable com.amap.api.maps.model.LatLng loc, String tag) {
        long ts = System.currentTimeMillis();
        String payload;
        if (loc == null) {
            payload = "[APP_CONTEXT] " + tag + " USER_LOCATION: unknown; ts=" + ts;
        } else {
            payload = "[APP_CONTEXT] " + tag
                    + " USER_LOCATION lat=" + loc.latitude
                    + ", lng=" + loc.longitude
                    + "; ts=" + ts;
        }
        try {
            org.json.JSONObject toolMsg = new org.json.JSONObject()
                    .put("role", "assistant")
                    .put("content", payload);
            if (conversationHistory != null) conversationHistory.put(toolMsg);
            if (localConversationHistory != null) localConversationHistory.put(new org.json.JSONObject(toolMsg.toString()));
        } catch (Exception ignore) {}
    }

    // （可选）去重：仅当位置变化显著或超过冷却时间才再次注入
    private boolean shouldInjectAgain(@androidx.annotation.Nullable com.amap.api.maps.model.LatLng last,
                                      @androidx.annotation.Nullable com.amap.api.maps.model.LatLng now,
                                      long lastTs, long cooldownMs, float minDeltaMeters) {
        if (now == null) return (System.currentTimeMillis() - lastTs) > cooldownMs;
        if (last == null) return true;
        float[] out = new float[1];
        android.location.Location.distanceBetween(last.latitude, last.longitude, now.latitude, now.longitude, out);
        return out[0] >= minDeltaMeters || (System.currentTimeMillis() - lastTs) > cooldownMs;
    }


    private void handleMediaRequest() throws JSONException {

    // ✅ 用新增重载：history + GptClient 适配器
        SummaryAgent.postStatusToMastodonAsync(
                "ATwVkRP6ZXAq31iOOJ9cYcAwKtmgL7aekrToeYzLqN4",
                conversationHistory,

                new SummaryAgent.MastoPostListener() {
                    @Override public void onSuccess(@NonNull org.json.JSONObject resp) {
                        android.util.Log.i("Masto", "成功: " + resp.optString("url"));
                    }
                    @Override public void onFailure(@NonNull String err, @Nullable org.json.JSONObject resp) {
                        android.util.Log.e("Masto", "失败: " + err + " " + (resp == null ? "" : resp.toString()));
                    }
                }
        );

    }


}
