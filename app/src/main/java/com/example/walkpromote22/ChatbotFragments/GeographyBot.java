package com.example.walkpromote22.ChatbotFragments;



import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.data.dao.POIDao;

import com.example.walkpromote22.data.dao.PoiAreaCoverageDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.POI;

import com.example.walkpromote22.data.model.PoiAreaCoverage;
import com.example.walkpromote22.data.model.Route;
import com.example.walkpromote22.tool.MapTool;
import com.example.walkpromote22.tool.UserPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;


/**
 * 步行路线生成工具（支持 GPT 多途径点）
 */
public class GeographyBot {
    public static LatLng location;
    public static String response;


    private static final String TAG = "RouteGeneration";

    public static double distanceOfRoute;
    /** 高德 Web 服务 Key */
    private static final String AMAP_KEY = "03f8248595264720386231fad6739bb8";

    /** OpenAI Key（上线前移出源码） */




    // === 统一对话历史 Transcript 注入（新增） ===
    public interface TranscriptProvider {
        /** 返回需要附带给 GPT 的完整对话历史（包含 user/assistant/API_Result 等文本） */
        String getTranscript();
    }

    private static volatile TranscriptProvider transcriptProvider;

    /** 上层（如 ChatbotFragment）在 onResume 时调用注册，onPause/onDestroyView 注销 */
    public static void setTranscriptProvider(TranscriptProvider provider) {
        transcriptProvider = provider;
    }

    /** 安全拿 transcript（provider 可能为 null 或抛异常） */
    public static String safeGetTranscript() {
        try {
            if (transcriptProvider == null) return "";
            String t = transcriptProvider.getTranscript();
            return (t == null) ? "" : t;
        } catch (Throwable ignore) {
            return "";
        }
    }

    /** 把 transcript 作为一条 system 消息插到历史最前面 */
    public static JSONArray prependTranscript(JSONArray hist, String transcript) throws JSONException {
        if (transcript == null || transcript.trim().isEmpty()) return hist;
        JSONArray merged = new JSONArray();
        merged.put(new JSONObject().put("role", "system")
                .put("content",  "/"+transcript+"/"));
        for (int i = 0; i < hist.length(); i++) merged.put(hist.get(i));
        return merged;
    }

    public static LatLng getUserLocation(Context ctx) {

        SharedPreferences sp = ctx.getSharedPreferences("AppData", Context.MODE_PRIVATE);

        String lat = sp.getString("location_lat", null);
        String lon = sp.getString("location_long", null);

        Log.e(TAG,"存储的经纬度是："+lat+","+lon);
        if (lat == null || lon == null) return null;
        try {
            location= new LatLng(Double.parseDouble(lat), Double.parseDouble(lon));
            return new LatLng(Double.parseDouble(lat), Double.parseDouble(lon));
        } catch (NumberFormatException e) {
            return null;
        }
    }






    /* =========================================================
     *  7. 核心入口
     * ========================================================= */
    public static List<Location> generateRoute(@NonNull Context ctx,
                                               @NonNull String userUtter) throws Exception {

        Log.e(TAG, "1");
        LatLng origin = getUserLocation(ctx);
        if (origin == null) throw new IllegalStateException("尚未获取到当前位置");


        Log.e(TAG, "3");


        // 取周边 POI（你原有逻辑）



        // 直接用 GPT 选点 —— chooseWaypointsWithGPT 内部会自动注入 Transcript
        SelectedRoute sel = chooseWaypointsWithGPT(userUtter);

        if (sel == null || sel.waypoints == null || sel.waypoints.isEmpty())
            throw new IllegalStateException("GPT 未能选出 waypoints");

        // 直接把 waypoints 转成 List<Location>
        List<Location> result = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < sel.waypoints.size(); i++) {
            LatLng wp = sel.waypoints.get(i);
            Location loc = new Location();
            loc.setLatitude(wp.latitude);
            loc.setLongitude(wp.longitude);
            loc.setIndexNum(idx++);

            // 从 waypointNames 取对应名字（防止越界）
            if (sel.waypointNames != null && i < sel.waypointNames.size()) {
                loc.setName(sel.waypointNames.get(i));
            } else {
                loc.setName("");
            }
            result.add(loc);
        }

        // 计算总距离（直线方式）
        Log.d("Route", "Total distance (waypoints only): " + distanceOfRoute + " meters");

        return result;
    }


    public static JSONArray getInterestingPoint(@NonNull Context ctx, String requirement)  throws JSONException {
        final String TAG = "CoreLoc"; // 本方法专用 Tag，避免与全局 TAG 混淆
        final String trace = "trace=" + UUID.randomUUID().toString().substring(0, 8);
        final long t0 = System.currentTimeMillis();
        String payloadHistory = "";
        try {
            UserPreferences userPref = new UserPreferences(ctx);
            String userKey=userPref.getUserKey();
            List<Route> historyRoutes = AppDatabase.getDatabase(ctx).routeDao().getRoutesByUserKey(userKey);
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
             payloadHistory = "API_Result:{User_History}\n" + historyArr.toString();


        } catch (Exception e) {
            Log.e("tag","getInterestingPoint获取历史失败");
        }
        // 小工具：日志脱敏 & 截断
        Function<String, String> redactKey = (u) ->
                u == null ? "null" : u.replaceAll("([?&]key=)[^&]+", "$1***");
        BiFunction<String, Integer, String> cut = (s, n) -> {
            if (s == null) return "null";
            s = s.replaceAll("\\s+", " ").trim();
            return s.length() > n ? s.substring(0, n) + "…" : s;
        };

        // ---------------- 0) 位置初始化 ----------------
        getUserLocation(ctx);
        final Double originLat = location != null ? location.latitude : null;
        final Double originLng = location != null ? location.longitude : null;


        assert location != null;
        fetchPOIs(ctx,location,5000);

        // ---------------- 1) system + few-shot ----------------
        final long tSys0 = System.currentTimeMillis();
        String sysPrompt =
                "你是一个POI挑选助手，你只能回复我{***}的格式，下面时具体要求：" +
                        "你会接收到一个POI表格和用户完整input和用户历史请求偏好，从POI表格中根据用户input分析有哪些是用户可能感兴趣的点，" +
                        "请返回如下格式[\n" +
                        "用户当前位置在"+originLat+","+originLng+
                        "  {\n" +
                        "    \"name\": \"Park\",\n" +
                        "    \"latitude\": 40.748817,\n" +
                        "    \"longitude\": -73.985428\n" +
                        "  },\n" +
                        "  {\n" +
                        "    \"name\": \"Museum\",\n" +
                        "    \"latitude\": 34.052235,\n" +
                        "    \"longitude\": -118.243683\n" +
                        "  }\n" +
                        "]" +
                        "如公园、绿道、商场、湖泊等等有优先级,距离用户更近的有优先级。用户历史请求中的偏好地点有优先级";




        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", sysPrompt))
                .put(new JSONObject().put("content", "history").put("content", payloadHistory));

        hist = prependTranscript(hist, safeGetTranscript());




        // ---------------- 2) LLM 请求 ----------------
        final String[] reply = new String[1];
        final String[] errMsg = new String[1];

        CountDownLatch latch = new CountDownLatch(1);
        ChatbotHelper helper = new ChatbotHelper();
        Log.e(TAG, "Finding request="+requirement );
        helper.sendMessage(requirement == null ? "" : requirement, hist, new ChatbotResponseListener() {
            @Override public void onResponse(String r) {
                reply[0] = r;
                Log.e(TAG,"兴趣列表poi="+r);
                latch.countDown();
            }
            @Override public void onFailure(String e) {
                errMsg[0] = e;
                Log.e(TAG, trace + " LLM_onFailure err=" + e);
                latch.countDown();
            }
        });

        try {
            boolean ok = latch.await(100, TimeUnit.SECONDS);
            if (!ok) {
                Log.e(TAG, trace + " LLM_TIMEOUT wait=100s");
                throw new JSONException("TIMEOUT_WAITING_GPT");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.e(TAG, trace + " LLM_INTERRUPTED " + ie);
            throw new JSONException("INTERRUPTED_WAITING_GPT");
        }
        if (reply[0] == null) {
            Log.e(TAG, trace + " LLM_NULL_REPLY err=" + errMsg[0]);
            throw new JSONException("GPT_RESPONSE_NULL");
        }
        final long tLlm1 = System.currentTimeMillis();

        // ---------------- 3) 解析 {A,B,...} ----------------


        return toLocations(reply[0]);
    }

    public static JSONArray toLocations(String r) throws JSONException {
        // 空与清理
        if (r == null) return new JSONArray();
        String s = r.trim();
        if (s.isEmpty()) return new JSONArray();
        if (s.charAt(0) == '\uFEFF') s = s.substring(1);               // 去BOM
        // 若整体被引号包住（数组被当成字符串），去引号并反转义
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\'", "'");
        }
        // 抠出真正的 [ ... ] 片段（容忍外层噪声/括号）
        int l = s.indexOf('['), rgt = s.lastIndexOf(']');
        if (l >= 0 && rgt > l) s = s.substring(l, rgt + 1);

        // 解析成 JSONArray；若失败则尝试单对象包成数组；仍失败则给空数组
        JSONArray arr;
        try {
            arr = new JSONArray(s);
        } catch (Exception e) {
            try {
                arr = new JSONArray().put(new JSONObject(s));
            } catch (Exception e2) {
                return new JSONArray();
            }
        }

        // 仅提取 name / latitude / longitude，并做数值兜底
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            Object it = arr.opt(i);
            if (!(it instanceof JSONObject)) continue;
            JSONObject src = (JSONObject) it;

            String name = src.optString("name", "");
            Object latV = src.opt("latitude");
            Object lngV = src.opt("longitude");
            double lat = (latV instanceof Number) ? ((Number) latV).doubleValue() :
                    (latV != null ? parseDoubleSafe(String.valueOf(latV)) : Double.NaN);
            double lng = (lngV instanceof Number) ? ((Number) lngV).doubleValue() :
                    (lngV != null ? parseDoubleSafe(String.valueOf(lngV)) : Double.NaN);

            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("latitude", lat);
                o.put("longitude", lng);
                out.put(o);
            }
        }
        return out;
    }

    // 仅为上面的方法服务的内联式小函数（仍在同一处，保持“一个方法块”的简洁使用体验）
    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }


    public static JSONArray getCoreLocationsFromRequirement(@NonNull Context ctx, String requirement) throws JSONException {
        final String TAG = "CoreLoc"; // 本方法专用 Tag，避免与全局 TAG 混淆
        final String trace = "trace=" + UUID.randomUUID().toString().substring(0, 8);
        final long t0 = System.currentTimeMillis();


        // 小工具：日志脱敏 & 截断
        Function<String, String> redactKey = (u) ->
                u == null ? "null" : u.replaceAll("([?&]key=)[^&]+", "$1***");
        BiFunction<String, Integer, String> cut = (s, n) -> {
            if (s == null) return "null";
            s = s.replaceAll("\\s+", " ").trim();
            return s.length() > n ? s.substring(0, n) + "…" : s;
        };

        // ---------------- 0) 位置初始化 ----------------
        getUserLocation(ctx);
        final Double originLat = location != null ? location.latitude : null;
        final Double originLng = location != null ? location.longitude : null;


        // ---------------- 1) system + few-shot ----------------
        final long tSys0 = System.currentTimeMillis();
        String sysPrompt =
                "你是一个POI名词提取助手，你只能回复我{***}的格式，下面时具体要求：" +
                        "尽力分析，你可以多花一点时间思考。下面用户的语句中是否包含任何可能是地名或者地点名的名词（只要是能在地图上查询到相关词的都包括在内，请考虑同义词），" +
                        "如果有请返回{A1,A2,B1,B2,...}的格式，其中A1中文名、A2英文名（若无可留空），多个POI依次列出；" +
                        "如果没有提到任何POI名称或者地名等信息，则返回{0}。" +
                        "注意：只输出大括号包裹的内容，不能包含其他说明或前后缀；大括号使用半角{}（若误用全角｛｝也可以）。" +
                        "下面是一些参考输出";

        String fsUser1 = "I wanna walk around my apartment";
        String fsAsst1 = "标准输出:{0}";
        String fsUser2 = "从独墅湖邻里中心走到苏州中心，再去诚品生活喝咖啡";
        String fsAsst2 = "标准输出:{独墅湖邻里中心, Dushu Lake Neighborhood Center, 苏州中心, Suzhou Center, 诚品生活, ESlite Life}";
        String fsUser3 = "I wanna eat KFC";
        String fsAsst3 = "标准输出:{肯德基, KFC}";
        String fsUser4 = "I would like to have a walk to a park, any suggestions?";
        String fsAsst4 = "标准输出:{公园, park}";


        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", sysPrompt))
                .put(new JSONObject().put("role", "user").put("content", fsUser1))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst1))
                .put(new JSONObject().put("role", "user").put("content", fsUser2))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst2))
                .put(new JSONObject().put("role", "user").put("content", fsUser3))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst3))
                .put(new JSONObject().put("role", "user").put("content", fsUser4))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst4));;
        hist = prependTranscript(hist, safeGetTranscript());

        final long tSys1 = System.currentTimeMillis();


        // ---------------- 2) LLM 请求 ----------------
        final String[] reply = new String[1];
        final String[] errMsg = new String[1];
        final long tLlm0 = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        ChatbotHelper helper = new ChatbotHelper();
        Log.e(TAG, "Finding request="+requirement );
        helper.sendMessage(requirement == null ? "" : requirement, hist, new ChatbotResponseListener() {
            @Override public void onResponse(String r) {
                reply[0] = r;
               Log.e(TAG,"地名提取agent提取结果="+r);
                latch.countDown();
            }
            @Override public void onFailure(String e) {
                errMsg[0] = e;
                Log.e(TAG, trace + " LLM_onFailure err=" + e);
                latch.countDown();
            }
        });

        try {
            boolean ok = latch.await(100, TimeUnit.SECONDS);
            if (!ok) {
                Log.e(TAG, trace + " LLM_TIMEOUT wait=100s");
                throw new JSONException("TIMEOUT_WAITING_GPT");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.e(TAG, trace + " LLM_INTERRUPTED " + ie);
            throw new JSONException("INTERRUPTED_WAITING_GPT");
        }
        if (reply[0] == null) {
            Log.e(TAG, trace + " LLM_NULL_REPLY err=" + errMsg[0]);
            throw new JSONException("GPT_RESPONSE_NULL");
        }
        final long tLlm1 = System.currentTimeMillis();

        // ---------------- 3) 解析 {A,B,...} ----------------
        String raw = reply[0].trim();

        raw = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        raw = raw.replace('｛', '{').replace('｝', '}');
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        if (s == -1 || e == -1 || s > e) {

            throw new JSONException("NO_BRACED_BLOCK_FOUND: " + cut.apply(raw, 300));
        }
        String inner = raw.substring(s + 1, e).trim();
        if (inner.isEmpty() || "0".equals(inner)) {

            return new JSONArray();
        }

        String[] toks = inner.split("[,，、;；\\s]+");
        LinkedHashSet<String> nameSet = new LinkedHashSet<>();
        for (String t : toks) {
            if (t == null) continue;
            String name = t.trim();
            if ((name.startsWith("\"") && name.endsWith("\"")) ||
                    (name.startsWith("“") && name.endsWith("”")) ||
                    (name.startsWith("'") && name.endsWith("'"))) {
                name = name.substring(1, name.length() - 1).trim();
            }
            name = name.replaceAll("\\s+", " ").trim().replaceAll("\\s{2,}", " ");
            if (!name.isEmpty()) nameSet.add(name);
        }


        if (nameSet.isEmpty()) return new JSONArray();

        // ---------------- 4) AMap 检索 ----------------
        final long tApi0 = System.currentTimeMillis();
        List<Location> out = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        final int RADIUS_M = 5000;
        final int PAGE_SZ = 25;
        final int MAX_PAGES = 5;

        AtomicInteger calls = new AtomicInteger(0);
        AtomicInteger pagesOk = new AtomicInteger(0);
        AtomicInteger poisTotal = new AtomicInteger(0);

        BiFunction<String, Boolean, Integer> runQuery = (kw, useAround) -> {
            int added = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                String url;
                try {
                    if (useAround && originLat != null && originLng != null) {
                        url = "https://restapi.amap.com/v3/place/around"
                                + "?location=" + originLng + "," + originLat
                                + "&radius=" + RADIUS_M
                                + "&keywords=" + URLEncoder.encode(kw, "UTF-8")
                                + "&sortrule=distance&output=json&extensions=base"
                                + "&offset=" + PAGE_SZ + "&page=" + page
                                + "&key=" + AMAP_KEY;
                    } else {
                        url = "https://restapi.amap.com/v3/place/text"
                                + "?keywords=" + URLEncoder.encode(kw, "UTF-8")
                                + "&citylimit=false&output=json&extensions=base"
                                + "&offset=" + PAGE_SZ + "&page=" + page
                                + "&key=" + AMAP_KEY;
                    }
                } catch (Exception enc) {
                    Log.e(TAG, trace + " URL_ENCODE_FAIL kw=" + kw + " page=" + page + " err=" + enc);
                    break;
                }

                calls.incrementAndGet();
                JSONObject resp;
                try {
                    Log.d(TAG, trace + " AMap_CALL url=" + redactKey.apply(url));
                    resp = httpGet(url);
                } catch (Exception e1) {
                    Log.e(TAG, trace + " AMap_HTTP_FAIL page=" + page + " err=" + e1);
                    break;
                }
                if (resp == null) {
                    Log.e(TAG, trace + " AMap_NULL_RESP page=" + page);
                    break;
                }

                String status = resp.optString("status", "0");
                if (!"1".equals(status)) {
                    Log.e(TAG, trace + " AMap_BAD_STATUS status=" + status +
                            " info=" + resp.optString("info") +
                            " infocode=" + resp.optString("infocode"));
                    break;
                }

                JSONArray pois = resp.optJSONArray("pois");
                int len = pois == null ? 0 : pois.length();
                if (len == 0) {
                    Log.i(TAG, trace + " AMap_EMPTY page=" + page + " kw=" + kw);
                    break;
                }
                pagesOk.incrementAndGet();
                poisTotal.addAndGet(len);

                for (int i = 0; i < len; i++) {
                    JSONObject p = pois.optJSONObject(i);
                    if (p == null) continue;
                    String loc = p.optString("location", "");
                    if (!loc.contains(",")) continue;
                    String[] ll = loc.split(",");
                    if (ll.length != 2) continue;
                    double lat, lng;
                    try {
                        lat = Double.parseDouble(ll[1]);
                        lng = Double.parseDouble(ll[0]);
                    } catch (NumberFormatException nfe) { continue; }

                    String id = p.optString("id", "");
                    String name = p.optString("name", "");
                    String dedupKey = id.isEmpty() ? (name + "#" + loc) : id;
                    if (!dedup.add(dedupKey)) continue;

                    String cleanName = name.replaceAll("\\s+", " ").trim().replaceAll("\\s{2,}", " ");
                    if (cleanName.isEmpty()) continue;

                    Location L = new Location();
                    L.setName(cleanName);
                    L.setLatitude(lat);
                    L.setLongitude(lng);
                    out.add(L);
                    added++;
                    if (out.size() >= 150) {
                        Log.w(TAG, trace + " AMap_TRUNCATE cap=150");
                        return added;
                    }
                }
            }
            Log.i(TAG, trace + " AMap_QUERY_SUM kw=" + kw + " useAround=" + useAround +
                    " added=" + added);
            return added;
        };

        // 依次查询：先使用 around，再不行用全局 text
        for (String kw : nameSet) {
            int a1 = runQuery.apply(kw, true);
            if (a1 == 0) runQuery.apply(kw, false);
        }

        final long tApi1 = System.currentTimeMillis();


        // ---------------- 5) 排序 ----------------

        if (originLat != null && originLng != null) {
            try {
                out.sort(Comparator.comparingDouble(
                        L -> MapTool.distanceBetween(
                                new LatLng(originLat, originLng),
                                new LatLng(L.getLatitude(), L.getLongitude())
                        )
                ));
            } catch (Exception sortEx) {
                Log.e(TAG, trace + " SORT_FAIL " + sortEx);
            }
        }

        // 打印前几条样例
        for (int i = 0; i < Math.min(5, out.size()); i++) {
            Location L = out.get(i);
            double dist = (originLat != null && originLng != null)
                    ? MapTool.distanceBetween(new LatLng(originLat, originLng), new LatLng(L.getLatitude(), L.getLongitude()))
                    : -1;

        }

        // ---------------- 6) 转 JSONArray & 退出 ----------------
        JSONArray result = new JSONArray();
        for (Location L : out) {
            JSONObject o = new JSONObject();
            o.put("name", L.getName());
            o.put("latitude", L.getLatitude());
            o.put("longitude", L.getLongitude());
            result.put(o);
        }
        final long t1 = System.currentTimeMillis();

        return result;
    }







    /* =========================================================
     *  8. fetchNearbyPOIs / distance / httpGet 与旧实现一致
     * ========================================================= */
    // ...（保持你原来的实现）

    /* =========================================================
     *  9. GPT 选目的地 + 多途径点
     * ========================================================= */
    // 可选：把你的“参考回答路线 JSON”放到这里（和 buildSuccessExampleInput 配套）


    // ====== 多代理 Planner（Gen → Selector），最终仍输出 SelectedRoute ======
    private static SelectedRoute chooseWaypointsWithGPT(String userUtter)
            throws Exception {

        String sysPrompt =
                "你是一名步行路线规划助手，收到用户需求和用户可能要去的地点列表（地点数量可能是1到多个）以及一个TrafficEvent" +
                        "1.JSON的结构示例：[{\"waypoints\":[{\"name\":\"\",\"lat\":0,\"lng\":0},...]}]" +
                        "2.如果用户没有明确的起点，那默认起点为用户当前位置:" + location + "如果用户指定了起点则用其作为起点" +
                        "3.请根据用户需求和地点列表直接返回上述结构的json路线" +
                        "4.如果用户没有明确哪个地点是终点哪个地点是途径点，则默认举例用户近到远排序"+
                        "5.尽量避开trafficEvent";



        // === 真实 payload：加入事件/天气等（保留你原先的 fetchTrafficEventsOnce）===
        JSONArray eventInfo = fetchTrafficEventsOnce(location, 3000);

        JSONObject payload = new JSONObject()
                .put("Assistant",sysPrompt)
                .put("user_request", userUtter)
                .put("event_info", eventInfo);

        Log.e(TAG, "发给gpt的内容如下：" + payload);

        // === 组装对话历史：system -> few-shot -> real user（保留你的原顺序）===
        ChatbotHelper helper = new ChatbotHelper();
        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", payload.toString()));

        // === 新增：把 ChatbotFragment 的完整对话历史当作 system 插到最前（若你已实现 provider）===
        // 若你已添加 setTranscriptProvider/safeGetTranscript/prependTranscript，则下面两行会把完整 history 注入给 GPT
        String transcript = safeGetTranscript();  // 若未设置 provider，会返回空字符串
        hist = prependTranscript(hist, transcript);

        // === 发送并等待 ===
        CountDownLatch latch = new CountDownLatch(1);
        final String[] reply = new String[1];
        Log.e(TAG,"hist="+hist);
        Log.e(TAG,"payload="+payload.toString());
        helper.sendMessage(payload.toString(), hist, new ChatbotResponseListener() {
            @Override public void onResponse(String r) { reply[0] = r; latch.countDown(); }
            @Override public void onFailure(String e)   { reply[0] = null; latch.countDown(); }
        });

        latch.await(40, TimeUnit.SECONDS);
        if (reply[0] == null) return null;

        // === 解析仅包含 waypoints 的 JSON（保留你原本的解析流程，不使用 parseSelectedRoute）===
        Log.e(TAG, reply[0]);
        String raw = reply[0];
        String stripped = stripCodeFence(raw);
        String cleaned = extractBalancedJson(stripped);

// 用 JSONTokener 先探测顶层类型
        Object top = new JSONTokener(cleaned).nextValue();
        JSONObject res;

        if (top instanceof JSONObject) {
            res = (JSONObject) top;
        } else if (top instanceof JSONArray) {
            // 顶层是数组，按你的协议把它当成 waypoints 数组包一层
            res = new JSONObject().put("waypoints", (JSONArray) top);
        } else if (top instanceof String) {
            // 顶层还是字符串，再尝试二次反序列化
            Object top2 = new JSONTokener((String) top).nextValue();
            if (top2 instanceof JSONObject) {
                res = (JSONObject) top2;
            } else if (top2 instanceof JSONArray) {
                res = new JSONObject().put("waypoints", (JSONArray) top2);
            } else {
                throw new JSONException("无法从回复中解析出有效的 JSON 对象或数组");
            }
        } else {
            throw new JSONException("无法从回复中解析出有效的 JSON");
        }

        response = (cleaned != null ? cleaned : raw);


        JSONArray wpArr = res.optJSONArray("waypoints");
        if (wpArr == null || wpArr.length() == 0) return null;

        List<LatLng> wps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < wpArr.length(); i++) {
            JSONObject w = wpArr.getJSONObject(i);
            String name = w.optString("name", "");
            names.add(name);
            wps.add(new LatLng(w.getDouble("lat"), w.getDouble("lng")));
        }
        return new SelectedRoute(wps, names);
    }

    private static String stripCodeFence(String s) {
        if (s == null) return null;
        String t = s.trim();

        // 形如 ```json ... ``` 或 ``` ... ```
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                // 跳过第一行的 ``` 或 ```json
                String body = t.substring(firstNl + 1);
                int fence = body.lastIndexOf("```");
                if (fence >= 0) {
                    return body.substring(0, fence).trim();
                }
            }
            // 不规范但以 ``` 开头，尽量去掉首行的 ```
            t = t.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            t = t.replaceFirst("\\s*```\\s*$", "");
            return t.trim();
        }
        return t;
    }

    /**
     * 从任意字符串中“抠出”首个平衡的 JSON（对象或数组）。
     * - 优先找第一个 '{' 或 '['（谁先出现用谁）
     * - 用栈深度法匹配到成对的 '}' 或 ']'
     * - 忽略字符串字面量中的括号（处理转义字符）
     * - 若整体是被引号包住的 JSON 字符串，则先反转义后再尝试
     * 失败则返回原串的 trim。
     */
    private static String extractBalancedJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return t;

        // 若整体用引号包住，尝试当作被转义的 JSON 字符串
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            String unq = unquoteAndUnescape(t);
            String inner = tryExtractBalanced(unq);
            if (inner != null) return inner.trim();
            // 退一步：原样再试
        }

        String extracted = tryExtractBalanced(t);
        return extracted != null ? extracted.trim() : t;
    }

    /* ======== helpers ======== */

    /** 实际的“抠 JSON”逻辑：从第一个 '{' 或 '[' 开始扫描到匹配的 '}' 或 ']'。*/
    private static String tryExtractBalanced(String s) {
        int braceStart = s.indexOf('{');
        int bracketStart = s.indexOf('[');
        int start;
        char open, close;

        if (braceStart == -1 && bracketStart == -1) return null;
        if (braceStart == -1 || (bracketStart != -1 && bracketStart < braceStart)) {
            start = bracketStart; open = '['; close = ']';
        } else {
            start = braceStart; open = '{'; close = '}';
        }

        int depth = 0;
        boolean inString = false;
        char strQuote = 0;
        boolean escape = false;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false; // 跳过被转义的字符
                } else if (c == '\\') {
                    escape = true;
                } else if (c == strQuote) {
                    inString = false;
                }
                continue;
            } else {
                if (c == '"' || c == '\'') {
                    inString = true;
                    strQuote = c;
                    continue;
                }
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                }
            }
        }
        return null; // 没匹配上，返回 null 让上层决定怎么兜底
    }

    /** 去掉首尾同类引号，并把常见转义还原（\" -> "，\\n 等不处理成真实换行，只保留原字符）。*/
    private static String unquoteAndUnescape(String s) {
        if (s == null || s.length() < 2) return s;
        char q = s.charAt(0);
        if (s.charAt(s.length() - 1) != q || (q != '"' && q != '\'')) return s;
        String inner = s.substring(1, s.length() - 1);
        // 常见反转义：先处理反斜杠自身，再处理引号
        inner = inner.replace("\\\\", "\\");
        if (q == '"') inner = inner.replace("\\\"", "\"");
        else inner = inner.replace("\\'", "'");
        return inner;
    }







    /* =========================================================
     *  内部数据类
     * ========================================================= */

    // ① 新增：构造“成功路线生成案例”的示例输入（user）


    private static class SelectedRoute {
        List<LatLng> waypoints;
        List<String> waypointNames; // 为了拼 JSON 带上 name
        SelectedRoute(List<LatLng> w, List<String> names) {
            waypoints = w; waypointNames = names;
        }
    }


    /* =========================================================
     *  HTTP GET
     * ========================================================= */
    public static JSONObject httpGet(String url) throws Exception {
        final int maxRetry = 2; // 允许超时重试 2 次（共最多 3 次）
        int attempt = 0;

        while (true) {
            HttpURLConnection c = null;
            InputStream raw = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);     // 连接超时
                c.setReadTimeout(20_000);        // 读超时（按需要再调）
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept-Encoding", "gzip");
                c.setRequestProperty("Connection", "Keep-Alive");
                c.setRequestProperty("User-Agent", "walkpromote/1.0");

                int code = c.getResponseCode();
                raw = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
                if (raw == null) throw new IOException("No response body, code=" + code);

                String enc = c.getContentEncoding();
                InputStream is = "gzip".equalsIgnoreCase(enc) ? new GZIPInputStream(raw) : raw;

                try (BufferedInputStream bis = new BufferedInputStream(is);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8 * 1024];
                    int n;
                    while ((n = bis.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                    String body = baos.toString("UTF-8");
                    if (code < 200 || code >= 300) {
                        throw new IOException("HTTP " + code + ": " + body);
                    }
                    return new JSONObject(body);
                }
            } catch (InterruptedIOException e) {
                // 仅对超时做有限重试
                if (attempt < maxRetry) {
                    attempt++;
                    try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
                    continue;
                }
                throw e;
            } finally {
                // 先关输入流，再断开连接，避免 "socket is closed" 干扰
                if (raw != null) try { raw.close(); } catch (Exception ignore) {}
                if (c != null) c.disconnect();
            }
        }
    }

    private static double distance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }
    /* ------------------------------------------------------------------
     * 获取 origin 周边 POI，带缓存 + QPS 限流，最多 maxCount 条
     * ------------------------------------------------------------------ */
    private static final Map<String, CacheEntry> POI_CACHE = new ConcurrentHashMap<>();
    private static final long POI_CACHE_TTL_MS  = 3 * 60 * 1000;   // 3 分钟
    private static final long MIN_INTERVAL_MS   = 120;             // ≥120 ms/次 ≈ 8 QPS
    private static long lastRequestTime = 0;                       // 全局节流锁




    /* --------------------------------------------------
     * 简单缓存实体
     * -------------------------------------------------- */
    private static class CacheEntry {
        long timestamp;
        JSONArray payload;
        CacheEntry(long ts, JSONArray pl) { timestamp = ts; payload = pl; }
    }


    /** 查询 origin 半径 radius(m) 内的全部封路/施工/事故事件 */
    public static JSONArray fetchTrafficEventsOnce(LatLng origin, int radiusMeter) {
        JSONArray flagged = new JSONArray();
        try {
            String url = "https://restapi.amap.com/v4/traffic/event/around"
                    + "?location=" + origin.longitude + "," + origin.latitude   // AMap: lng,lat
                    + "&radius=" + radiusMeter
                    + "&key=" + AMAP_KEY;

            JSONObject resp = httpGet(url);
            JSONObject data = resp.optJSONObject("data");
            if (data == null) return flagged;

            JSONArray evs = data.optJSONArray("event_list");
            if (evs == null) return flagged;

            for (int i = 0; i < evs.length(); i++) {
                JSONObject ev = evs.getJSONObject(i);
                String type = ev.optString("type");  // AMap 通常有字符串类型: closure/construction/accident 等

                // 只把真正会阻塞步行的事件挑出来
                if (!"closure".equals(type) && !"construction".equals(type) && !"accident".equals(type)) {
                    continue;
                }

                // 提取坐标：AMap 常见是 "location": "lng,lat"
                double lat = Double.NaN, lng = Double.NaN;
                String loc = ev.optString("location", "");
                if (loc.contains(",")) {
                    String[] parts = loc.split(",");
                    if (parts.length >= 2) {
                        try {
                            lng = Double.parseDouble(parts[0].trim());
                            lat = Double.parseDouble(parts[1].trim());
                        } catch (Exception ignore) {}
                    }
                }
                // 兜底：某些返回也可能有 latitude/longitude 字段
                if (Double.isNaN(lat)) lat = ev.optDouble("latitude", Double.NaN);
                if (Double.isNaN(lng)) lng = ev.optDouble("longitude", Double.NaN);

                // 只有拿到有效坐标才纳入
                if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                    ev.put("lat", lat);
                    ev.put("lng", lng);
                    flagged.put(ev);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "traffic event fetch fail: " + e);
        }
        return flagged;
    }


    // —— 辅助：快速米制近似的平方距离（用于排序，不参与精确计算）——


    // ===== 可开关的节流配置 =====
    private static volatile boolean ENABLE_THROTTLE = false; // ← 你要最快：设为 false


    private static void throttleGate() {
        if (!ENABLE_THROTTLE) return;
        synchronized (GeographyBot.class) {
            long diff = System.currentTimeMillis() - lastRequestTime;
            if (diff < MIN_INTERVAL_MS) {
                try { Thread.sleep(MIN_INTERVAL_MS - diff); } catch (InterruptedException ignore) {}
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }


    private static final ExecutorService POI_EXEC =
            Executors.newFixedThreadPool(4); // 并发度按需调

    private static <T> T runBlockingOnWorker(Callable<T> job) {
        try { return POI_EXEC.submit(job).get(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // ====== 可调容错参数 ======
    private static final double SNAP_M              = 100.0;   // 键对齐网格步长（不改变抓取范围，仅用于 areaKey）
    private static final long   HOT_TTL_MS          = 10 * 60 * 1000L; // 10 分钟内重复调用 → 强制 DB-only
    private static final double COVER_WRITE_MARGINM = 80.0;    // 写覆盖时，记录的 BBox 适度外扩（便于后续模糊命中）
    private static final double FUZZY_MARGIN_M      = 120.0;   // 模糊覆盖判定：把历史覆盖 BBox 外扩这么多后看是否完全覆盖
    private static final double DB_READ_MARGIN_M    = 30.0;    // 读库查询 BBox 外扩，最后再严格过滤

    // ====== 入口：同名签名保持不变；自动后台线程执行（避免主线程 Network/Room 异常） ======


    public static JSONArray fetchPOIs(@NonNull Context ctx, @NonNull LatLng center, int halfSideMeters) {
        final List<LatLng> poly = buildSquarePolygon(center, halfSideMeters);
        if (Looper.myLooper() == Looper.getMainLooper()) {

            return runBlockingOnWorker(() -> fetchPOIsByPolygonWorker(ctx.getApplicationContext(), poly));
        } else {
            return fetchPOIsByPolygonWorker(ctx.getApplicationContext(), poly);
        }
    }


    // ====== 主体：加入“对齐键 + 热区 TTL + 模糊覆盖 + 宽容读库” ======
    public static JSONArray fetchPOIsByPolygonWorker(@NonNull Context ctx,
                                               @NonNull List<LatLng> queryPoly) {
        final String TAG = "POI_ALLF";
        final long   STALE_MS         = 7L * 24 * 60 * 60 * 1000L;
        final int    PAGE_SZ          = 25, PAGE_CAP = 100, PER_BLOCK_MAX = 1000;
        final double GRID_MAX_SIDEM   = 100.0;
        final int    LOCAL_READ_LIMIT = 80000;

        final String[][] TYPE_GROUPS = new String[][]{
                {"010000","020000","030000","040000"},
                {"050000"},
                {"060000","070000","080000"},
                {"090000","100000"},
                {"110000","120000","130000","140000"},
                {"150000","160000","170000","180000","190000","200000","210000","220000","230000"}
        };

        long t0 = System.currentTimeMillis();

        // 结果与统计累积器
        JSONArray out = new JSONArray();
        int retDb = 0, retApi = 0;            // 最终统计（最后统一计算）
        int groupsDone = 0, groupsSplit = 0;  // 过程统计（如果你需要保留）
        int apiCallsTotal = 0, apiInsertedTotal = 0;

        // 关键上下文
        RectLL target = boundingRectOf(queryPoly);
        if (target == null) {
            // 统一在末尾打印 SUMMARY（这里先走到 finally 区）
            long t1 = System.currentTimeMillis();
            Log.e(TAG, String.format(Locale.US,
                    "SUMMARY  ret_total=%d db=%d(%.1f%%) api=%d(%.1f%%) timeMs=%d",
                    0, 0, 0.0, 0, 0.0, (t1 - t0)));
            return new JSONArray();
        }

        // —— 键对齐 + key/hash ——（仅用于覆盖键/热区键，不改变抓取/读库范围）
        RectLL keyRect = snapRectToGrid(target, 100.0); // 100m 对齐
        final String keyPolyStr = rectToPolygon(keyRect);
        final String blockHash  = sha1("rect|" + keyPolyStr);
        final String masterKey  = "ALLF:MASTER|" + blockHash;
        final String hotKey     = "ALLF:HOT|"    + blockHash;



        // DB/DAO
        final AppDatabase db;
        final POIDao poiDao;
        final PoiAreaCoverageDao covDao;
        try {
            db = AppDatabase.getDatabase(ctx.getApplicationContext());
            poiDao = db.poiDao();
            covDao = db.poiAreaCoverageDao();
        } catch (Throwable t) {
            Log.e(TAG, "FATAL: getDatabase/DAO failed", t);
            long t1 = System.currentTimeMillis();
            Log.e(TAG, String.format(Locale.US,
                    "SUMMARY  ret_total=%d db=%d(%.1f%%) api=%d(%.1f%%) timeMs=%d",
                    0, 0, 0.0, 0, 0.0, (t1 - t0)));
            return new JSONArray();
        }

        // 状态与控制
        long now = System.currentTimeMillis();
        boolean hotFresh = false, masterFresh = false, fuzzyFresh = false;
        boolean skipApi = false;                     // true → 本次不打 API，直接 DB-only
        Set<String> apiTouchedIds = new HashSet<>(); // 本次 API 触达的 poiId

        // 1) 热区 TTL 命中？
        try {
            Long hotAt = covDao.getCoveredAt(hotKey);
            hotFresh = (hotAt != null && (now - hotAt) < 10 * 60 * 1000L); // 10min
        } catch (Throwable t) { Log.e(TAG, "getCoveredAt(hotKey) failed", t); }
        if (hotFresh) Log.e(TAG, "HOT_TTL hit → DB-only");

        // 2) 精准 master 覆盖？
        try {
            Long mAt = covDao.getCoveredAt(masterKey);
            masterFresh = (mAt != null && (now - mAt) < STALE_MS);
        } catch (Throwable t) { Log.e(TAG, "getCoveredAt(masterKey) failed", t); }

        // 3) 模糊覆盖（前缀法）
        if (!hotFresh && !masterFresh) {
            try {
                fuzzyFresh = fuzzyCoveredAllGroupsPrefix(covDao, TYPE_GROUPS, target, now, STALE_MS, 120.0);

            } catch (Throwable t) {
                Log.e(TAG, "fuzzyCoveredAllGroupsPrefix failed", t);
            }
        }

        if (hotFresh || masterFresh || fuzzyFresh) {
            skipApi = true;
        }

        // ======== 主流程：不再早退。若 skipApi=true 则跳过 API 流程，最后统一读库 ========
        int groupsSkippedFresh = 0;

        if (!skipApi) {
            // 逐组：探测 → 分页 → 需要时网格切割；完成才写覆盖
            for (String[] group : TYPE_GROUPS) {
                final String typesJoined = joinTypes(group);
                final String groupKey = "ALLF:GROUP|" + sha1("types|" + typesJoined) + "|" + blockHash;

                boolean gFresh = false;
                try {
                    Long gAt = covDao.getCoveredAt(groupKey);
                    gFresh = (gAt != null && (now - gAt) < STALE_MS);
                } catch (Throwable t) { Log.e(TAG, "getCoveredAt(groupKey) failed", t); }

                if (gFresh) { groupsSkippedFresh++; groupsDone++; continue; }

                HeadProbe probe = headProbe(ctx, rectToPolygon(target), typesJoined);
                if (!probe.ok) {
                    Log.e(TAG, "probe fail: types=" + typesJoined + " (skip once, no coverage write)");
                    continue;
                }
                if (probe.count <= 0) {
                    upsertCoverageSafe(covDao, groupKey, keyRect, typesJoined, 80.0); // 覆盖写入：键矩形 + 外扩 BBox
                    groupsDone++;
                    continue;
                }

                // 真正抓取该组（内部用 fetchRectOnce/切块），统计 API 命中新 id 到 apiTouchedIds
                FetchGroupResult fg = drainTypeGroupALLF(
                        ctx, target, rectToPolygon(target),
                        group, PAGE_SZ, PAGE_CAP, PER_BLOCK_MAX, GRID_MAX_SIDEM,
                        poiDao, apiTouchedIds);
                apiCallsTotal    += fg.apiCalls;
                apiInsertedTotal += fg.inserted;
                groupsSplit      += fg.splits;

                if (fg.completed) {
                    upsertCoverageSafe(covDao, groupKey, keyRect, typesJoined, 80.0);
                    groupsDone++;
                } else {
                    Log.e(TAG, "GROUP NOT_DONE keep uncovered: " + typesJoined);
                }
            }

            if (groupsDone == TYPE_GROUPS.length) {
                upsertCoverageSafe(covDao, masterKey, keyRect, "ALL", 80.0);
            }
        }

        // 每次调用结尾都刷新 HOT
        touchHotCoverage(covDao, hotKey, keyRect);

        // ======== 统一最终读库（外扩查询 + 精确过滤）并统计来源占比 ========
        // 这里你可以继续使用你已有的 readFromDbTrimTo；为了统计 DB/API 占比，这里直接展开：
        Set<String> seenSlim = new HashSet<>();
        List<POI> locals = null;
        try {
            RectLL readBox = inflateRectByMeters(target, 30.0); // DB 读外扩 30m
            locals = poiDao.queryByBBox(readBox.minLat, readBox.maxLat, readBox.minLng, readBox.maxLng, LOCAL_READ_LIMIT);
        } catch (Throwable t) {
            Log.e(TAG, "poiDao.queryByBBox failed", t);
            locals = Collections.emptyList();
        }
        if (locals != null) {
            for (POI e : locals) {
                if (!(e.lat>=target.minLat && e.lat<=target.maxLat && e.lng>=target.minLng && e.lng<=target.maxLng)) continue;
                String slim = String.format(Locale.US, "%s,(%.6f,%.6f)", e.name==null?"":e.name, e.lat, e.lng);
                if (!seenSlim.add(slim)) continue;
                out.put(slim);
                if (apiTouchedIds.contains(e.poiId)) retApi++; else retDb++;
            }
        }

        // ======== 只在这里打印一次 SUMMARY ========
        int retTotal = retDb + retApi;
        double dbPct  = retTotal == 0 ? 0 : (100.0 * retDb  / retTotal);
        double apiPct = retTotal == 0 ? 0 : (100.0 * retApi / retTotal);
        long t1 = System.currentTimeMillis();

        Log.e(TAG, String.format(Locale.US,
                "SUMMARY  ret_total=%d db=%d(%.1f%%) api=%d(%.1f%%) timeMs=%d",
                retTotal, retDb, dbPct, retApi, apiPct, (t1 - t0)));

        return out;
    }


    /* ======================= 容错辅助：对齐、外扩、模糊覆盖、DB 读取 ======================= */

    // 只用于“键”：把矩形对齐到 N 米网格（不改变抓取/读库范围）
    private static RectLL snapRectToGrid(RectLL r, double stepM) {
        double refLat = (r.minLat + r.maxLat) * 0.5;
        double dLat = metersToLatDeg(stepM);
        double dLng = metersToLngDeg(stepM, refLat);
        double minLat = Math.floor(r.minLat / dLat) * dLat;
        double maxLat = Math.ceil (r.maxLat / dLat) * dLat;
        double minLng = Math.floor(r.minLng / dLng) * dLng;
        double maxLng = Math.ceil (r.maxLng / dLng) * dLng;
        return new RectLL(minLat, maxLat, minLng, maxLng);
    }

    // BBox 外扩/收缩（m>0 外扩，m<0 收缩）
    private static RectLL inflateRectByMeters(RectLL r, double meters) {
        double refLat = (r.minLat + r.maxLat) * 0.5;
        double dLat = metersToLatDeg(Math.abs(meters));
        double dLng = metersToLngDeg(Math.abs(meters), refLat);
        if (meters >= 0) {
            return new RectLL(r.minLat - dLat, r.maxLat + dLat, r.minLng - dLng, r.maxLng + dLng);
        } else {
            return new RectLL(r.minLat + dLat, r.maxLat - dLat, r.minLng + dLng, r.maxLng - dLng);
        }
    }


    private static RectLL shrinkRectByMeters(RectLL r, double meters) {
        if (meters <= 0) return r;
        double refLat = (r.minLat + r.maxLat) * 0.5;
        double dLat = metersToLatDeg(meters);
        double dLng = metersToLngDeg(meters, refLat);
        double minLat = r.minLat + dLat, maxLat = r.maxLat - dLat;
        double minLng = r.minLng + dLng, maxLng = r.maxLng - dLng;
        if (minLat > maxLat) { double c=(r.minLat+r.maxLat)/2; minLat=maxLat=c; }
        if (minLng > maxLng) { double c=(r.minLng+r.maxLng)/2; minLng=maxLng=c; }
        return new RectLL(minLat, maxLat, minLng, maxLng);
    }

    // 模糊覆盖：用覆盖表里“新鲜行”的 BBox 外扩后判断能否完整覆盖 target，且类型组能覆盖全部
    private static boolean fuzzyCoveredAllGroupsPrefix(PoiAreaCoverageDao covDao,
                                                       String[][] TYPE_GROUPS,
                                                       RectLL target,
                                                       long now, long STALE_MS,
                                                       double marginM) {
        long freshAfter = now - STALE_MS;
        RectLL shr = shrinkRectByMeters(target, marginM);

        // 1) 先看有没有任何 MASTER 覆盖（任意块），能“包含”本次 target（我们把 target 收缩了）
        try {
            PoiAreaCoverage m = covDao.findFreshCoveringPrefix(
                    "ALLF:MASTER|", freshAfter,
                    shr.minLat, shr.maxLat, shr.minLng, shr.maxLng);
            if (m != null) return true;
        } catch (Throwable t) {
            Log.e("POI_ALLF", "fuzzy MASTER prefix query failed", t);
        }

        // 2) 各类型组分别判断：只要每组都能命中“某个块”的覆盖，就视为全部覆盖
        for (String[] group : TYPE_GROUPS) {
            String typesJoined = joinTypes(group);
            String groupPrefix = "ALLF:GROUP|" + sha1("types|" + typesJoined) + "|"; // 注意 '|' 结尾用于前缀匹配
            try {
                PoiAreaCoverage g = covDao.findFreshCoveringPrefix(
                        groupPrefix, freshAfter,
                        shr.minLat, shr.maxLat, shr.minLng, shr.maxLng);
                if (g == null) return false; // 该组没命中 → 不算全覆盖
            } catch (Throwable t) {
                Log.e("POI_ALLF", "fuzzy GROUP prefix query failed: " + typesJoined, t);
                return false;
            }
        }
        return true;
    }



    // 写覆盖（对齐键 + BBox 外扩，polygon 字段写上 types=... 以便后续模糊识别）
    private static void upsertCoverageSafe(PoiAreaCoverageDao covDao,
                                           String areaKey, RectLL keyRect,
                                           String typesMeta, double writeMarginM) {
        try {
            RectLL wr = inflateRectByMeters(keyRect, writeMarginM);
            PoiAreaCoverage cov = new PoiAreaCoverage();
            cov.areaKey  = areaKey;
            cov.polygon  = rectToPolygon(keyRect) + "&types=" + typesMeta; // 记录键矩形 + types
            cov.minLat   = wr.minLat; cov.maxLat = wr.maxLat;
            cov.minLng   = wr.minLng; cov.maxLng = wr.maxLng;
            cov.coveredAt = System.currentTimeMillis();
            covDao.upsert(cov);
        } catch (Throwable t) {
            Log.e("POI_ALLF", "ERROR: upsertCoverageSafe fail key=" + areaKey, t);
        }
    }

    // HOT TTL 记录/刷新
    private static void touchHotCoverage(PoiAreaCoverageDao covDao, String hotKey, RectLL keyRect) {
        upsertCoverageSafe(covDao, hotKey, keyRect, "HOT", 0); // HOT 不必外扩
    }


    /* ======================= 组抓取：探测→分页→必要时空间切割 ======================= */

    private static class FetchGroupResult {
        boolean completed;  // 是否认为该组“已完成”（可写覆盖）
        int splits;         // 发生了几次“二分组”（此实现里仅统计空间切割次数）
        int apiCalls;       // 此组的 API 调用数
        int inserted;       // 此组新触达的 poiId 数
    }

    private static FetchGroupResult drainTypeGroupALLF(@NonNull Context ctx,
                                                       @NonNull RectLL block,
                                                       @NonNull String blockPolyStr,
                                                       @NonNull String[] typesGroup,
                                                       int pageSz, int pageCap, int perBlockMax, double gridMaxSideM,
                                                       @NonNull POIDao poiDao,
                                                       @NonNull Set<String> apiTouchedIds) {
        final String TAG = "POI_ALLF";
        FetchGroupResult fg = new FetchGroupResult();

        String typesJoined = joinTypes(typesGroup);
        int cnt = headProbe(ctx, blockPolyStr, typesJoined).count; // 这里已在上层检查过 ok


        if (cnt <= perBlockMax || typesGroup.length == 1) {
            if (typesGroup.length == 1 && cnt > perBlockMax) {
                // 单一大类仍超量 → 空间切割
                List<RectLL> grid = gridSplit(block, gridMaxSideM);
                if (grid.size() == 1) grid = split2x2(block);
                int apiCalls = 0, inserted = 0, splits = 1;
                for (RectLL sub : grid) {
                    HeadProbe probeSub = headProbe(ctx, rectToPolygon(sub), typesJoined);
                    Log.e(TAG, "  SUB head types=" + typesJoined + " ok=" + probeSub.ok
                            + " count=" + probeSub.count + " status=" + probeSub.status + " info=" + probeSub.info);
                    if (!probeSub.ok || probeSub.count <= 0) continue;
                    FetchResult frSub = fetchRectOnce(ctx, sub, pageSz, pageCap, perBlockMax,
                            "ALLF:MASTER|" + sha1("rect|" + blockPolyStr),
                            poiDao, apiTouchedIds, typesJoined);
                    apiCalls += frSub.apiCalls;
                    inserted += frSub.inserted;
                    Log.e(TAG, "  SUB fetch calls=" + frSub.apiCalls + " inserted=" + frSub.inserted + " trunc=" + frSub.maybeTruncated);
                }
                fg.completed = true; // 子块都按探测抓了（即便有个别失败也不影响整体向前）
                fg.apiCalls = apiCalls; fg.inserted = inserted; fg.splits = splits;
                return fg;
            } else {
                // 不需要切割：直接分页抓
                FetchResult fr = fetchRectOnce(ctx, block, pageSz, pageCap, perBlockMax,
                        "ALLF:MASTER|" + sha1("rect|" + blockPolyStr),
                        poiDao, apiTouchedIds, typesJoined);

                fg.completed = !fr.maybeTruncated; // 未截断则认为完成
                fg.apiCalls = fr.apiCalls; fg.inserted = fr.inserted; fg.splits = 0;
                return fg;
            }
        }

        // cnt > perBlockMax 且组内类数>1：二分组（为了减少截断；这里简化为递归两边都跑）
        int mid = Math.max(1, typesGroup.length / 2);
        String[] left  = Arrays.copyOfRange(typesGroup, 0, mid);
        String[] right = Arrays.copyOfRange(typesGroup, mid, typesGroup.length);

        FetchGroupResult L = drainTypeGroupALLF(ctx, block, blockPolyStr, left,  pageSz, pageCap, perBlockMax, gridMaxSideM, poiDao, apiTouchedIds);
        FetchGroupResult R = drainTypeGroupALLF(ctx, block, blockPolyStr, right, pageSz, pageCap, perBlockMax, gridMaxSideM, poiDao, apiTouchedIds);
        fg.completed = L.completed && R.completed;
        fg.apiCalls  = L.apiCalls + R.apiCalls;
        fg.inserted  = L.inserted + R.inserted;
        fg.splits    = L.splits + R.splits + 1;
        return fg;
    }

    /* ======================= 带状态的轻探测（只在 ok==true 且 count==0 时才当“真零”） ======================= */

    private static class HeadProbe {
        boolean ok;     // status==1 才为 true
        int count;      // 解析到的 count；失败时为 0
        String status;  // 原始 status
        String info;    // 原始 info/infocode
    }
    private static HeadProbe headProbe(@NonNull Context ctx, @NonNull String polyStr, @NonNull String typesJoined) {
        final String TAG = "POI_HEAD";
        HeadProbe hp = new HeadProbe();
        hp.ok = false; hp.count = 0; hp.status = "0"; hp.info = "";

        throttleGate();
        if (AMAP_KEY == null || AMAP_KEY.isEmpty()) {
            Log.e(TAG, "AMAP_KEY is EMPTY");
            return hp;
        }

        String url = "https://restapi.amap.com/v3/place/polygon"
                + "?polygon=" + Uri.encode(polyStr)
                + (typesJoined.isEmpty() ? "" : "&types=" + Uri.encode(typesJoined))
                + "&output=json&extensions=base"
                + "&offset=1&page=1"
                + "&key=" + AMAP_KEY;

        try {
            JSONObject resp = httpGet(url);
            if (resp == null) {
                Log.e(TAG, "httpGet NULL url=" + url);
                return hp;
            }
            hp.status = resp.optString("status", "0");
            hp.info   = resp.optString("info", "");
            if (!"1".equals(hp.status)) {
                Log.e(TAG, "status!=1 info=" + hp.info + " url=" + url);
                return hp;
            }
            try { hp.count = Integer.parseInt(resp.optString("count","0")); } catch (Exception ignore){}
            hp.ok = true;
            return hp;
        } catch (Throwable t) {
            Log.e(TAG, "httpGet EX url=" + url, t);
            return hp;
        }
    }

    /* ======================= 基础抓取（分页 + upsert + 关键日志） ======================= */

    private static class FetchResult {
        int inserted, apiCalls; boolean maybeTruncated;
        FetchResult(int ins, int calls, boolean trunc){ inserted=ins; apiCalls=calls; maybeTruncated=trunc; }
    }
    private static FetchResult fetchRectOnce(@NonNull Context ctx,
                                             @NonNull RectLL r,
                                             int pageSz, int pageCap, int perBlockMax,
                                             @NonNull String areaKeyForPOI,
                                             @NonNull POIDao poiDao,
                                             @NonNull Set<String> apiTouchedIds,
                                             @Nullable String typesJoined) {
        final String TAG = "POI_FETCH";
        int inserted = 0, calls = 0, page = 1, lastPageSize = 0, fetched = 0, totalCount = -1;
        boolean hitCap = false;

        final String polyStr = rectToPolygon(r);

        while (fetched < perBlockMax && page <= pageCap) {
            throttleGate();
            if (AMAP_KEY == null || AMAP_KEY.isEmpty()) {
                Log.e(TAG, "AMAP_KEY EMPTY → break");
                break;
            }

            String url = "https://restapi.amap.com/v3/place/polygon"
                    + "?polygon=" + Uri.encode(polyStr)
                    + (typesJoined != null && !typesJoined.isEmpty() ? ("&types=" + Uri.encode(typesJoined)) : "")
                    + "&output=json&extensions=base"
                    + "&offset=" + Math.max(1, Math.min(25, pageSz))
                    + "&page=" + page
                    + "&key=" + AMAP_KEY;

            JSONObject resp;
            try { resp = httpGet(url); } catch (Throwable e) {

                break;
            }
            calls++;
            if (resp == null) {

                break;
            }

            String status = resp.optString("status","0");
            String info   = resp.optString("info","");
            if (!"1".equals(status)) {
                break;
            }

            try { totalCount = Integer.parseInt(resp.optString("count","-1")); } catch (Exception ignore){}
            JSONArray pois = resp.optJSONArray("pois");
            lastPageSize = (pois==null?0:pois.length());

            if (lastPageSize == 0) break;

            long now = System.currentTimeMillis();
            for (int i=0; i<lastPageSize && fetched<perBlockMax; i++) {
                JSONObject p = pois.optJSONObject(i); if (p==null) continue;

                String loc = p.optString("location","");
                if (!loc.contains(",")) continue;
                String[] ll = loc.split(",");
                double lng, lat;
                try { lng = Double.parseDouble(ll[0]); lat = Double.parseDouble(ll[1]); }
                catch (Exception ex) { continue; }

                if (!(lat>=r.minLat && lat<=r.maxLat && lng>=r.minLng && lng<=r.maxLng)) continue;

                String name    = p.optString("name","");
                String type    = p.optString("type","");
                String poiId   = p.optString("id","");
                String address = p.optString("address","");

                if (poiId == null || poiId.isEmpty()) poiId = name + "@" + lat + "," + lng;

                POI entity = new POI();
                entity.areaKey   = areaKeyForPOI;
                entity.poiId     = poiId;
                entity.name      = name;
                entity.type      = type;
                entity.address   = address;
                entity.lat       = lat;
                entity.lng       = lng;
                entity.updatedAt = now;
                poiDao.insertReplace(entity);

                if (apiTouchedIds.add(poiId)) inserted++;
                fetched++;
            }

            if (lastPageSize < pageSz) { Log.e(TAG, "TAIL page=" + page); break; }
            page++;
        }

        if (fetched >= perBlockMax) hitCap = true;
        boolean pageCeiling       = (lastPageSize == pageSz && page-1 >= 9);
        boolean countSuggestsMore = (totalCount > 0 && fetched < totalCount);
        boolean maybeTruncated    = (pageCeiling || countSuggestsMore || hitCap);

        return new FetchResult(inserted, calls, maybeTruncated);
    }



    /* ======================= 几何/通用工具（保持不变） ======================= */

    private static class RectLL { double minLat,maxLat,minLng,maxLng;
        RectLL(double a,double b,double c,double d){minLat=a;maxLat=b;minLng=c;maxLng=d;}

        public RectLL() {

        }
    }
    private static RectLL boundingRectOf(List<LatLng> poly) { /* 与你原来的实现相同 */
        if (poly == null || poly.size() < 3) return null;
        double minLat=+90,maxLat=-90,minLng=+180,maxLng=-180;
        for (LatLng p : poly) {
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLng = Math.min(minLng, p.longitude);
            maxLng = Math.max(maxLng, p.longitude);
        }
        return new RectLL(minLat,maxLat,minLng,maxLng);
    }

    private static String rectToPolygon(RectLL r) {
        return String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f|%.6f,%.6f|%.6f,%.6f|%.6f,%.6f",
                r.minLng, r.maxLat,  r.maxLng, r.maxLat,  r.maxLng, r.minLat,  r.minLng, r.minLat,  r.minLng, r.maxLat);
    }
    private static String sha1(String s) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
    private static String joinTypes(String[] arr) {
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(); for (String s : arr) { if (sb.length() > 0) sb.append("|"); sb.append(s); }
        return sb.toString();
    }
    private static List<RectLL> gridSplit(RectLL r, double maxSideM) { /* 与你原来的实现相同 */
        List<RectLL> out = new ArrayList<>();
        double refLat = (r.minLat + r.maxLat) * 0.5;
        double widthM  = (r.maxLng - r.minLng) * (Math.cos(Math.toRadians(refLat)) * 111320.0);
        double heightM = (r.maxLat - r.minLat) * 111320.0;
        int cols = Math.max(1, (int)Math.ceil(widthM  / maxSideM));
        int rows = Math.max(1, (int)Math.ceil(heightM / maxSideM));
        double colSizeLng = metersToLngDeg(widthM  / cols, refLat);
        double rowSizeLat = metersToLatDeg(heightM / rows);
        double latStart = r.minLat;
        for (int ri = 0; ri < rows; ri++) {
            double latEnd = (ri == rows - 1) ? r.maxLat : (latStart + rowSizeLat);
            double lngStart = r.minLng;
            for (int ci = 0; ci < cols; ci++) {
                double lngEnd = (ci == cols - 1) ? r.maxLng : (lngStart + colSizeLng);
                out.add(new RectLL(latStart, latEnd, lngStart, lngEnd));
                lngStart = lngEnd;
            }
            latStart = latEnd;
        }
        return out;
    }
    private static List<RectLL> split2x2(RectLL r) {
        List<RectLL> out = new ArrayList<>();
        double midLat = (r.minLat + r.maxLat)/2.0, midLng = (r.minLng + r.maxLng)/2.0;
        out.add(new RectLL(midLat, r.maxLat, r.minLng, midLng));
        out.add(new RectLL(midLat, r.maxLat, midLng, r.maxLng));
        out.add(new RectLL(r.minLat, midLat, r.minLng, midLng));
        out.add(new RectLL(r.minLat, midLat, midLng, r.maxLng));
        return out;
    }
    private static double metersToLatDeg(double meters) { return meters / 111320.0; }
    private static double metersToLngDeg(double meters, double lat) {
        return meters / (Math.cos(Math.toRadians(lat)) * 111320.0 + 1e-12);
    }

    public static List<LatLng> buildSquarePolygon(LatLng center, int halfSideMeters) {
        final double latMeter = 111320.0;
        final double lngMeter = Math.cos(Math.toRadians(center.latitude)) * 111320.0;
        double dLat = halfSideMeters / latMeter;
        double dLng = halfSideMeters / lngMeter;
        double minLat = center.latitude - dLat, maxLat = center.latitude + dLat;
        double minLng = center.longitude - dLng, maxLng = center.longitude + dLng;
        // 顺时针 + 首尾闭合
        return Arrays.asList(
                new LatLng(maxLat, minLng),
                new LatLng(maxLat, maxLng),
                new LatLng(minLat, maxLng),
                new LatLng(minLat, minLng),
                new LatLng(maxLat, minLng)
        );
    }
    private static double cross(LatLng a, LatLng b, LatLng c) {
        double x1 = b.latitude - a.latitude, y1 = b.longitude - a.longitude;
        double x2 = c.latitude - a.latitude, y2 = c.longitude - a.longitude;
        return x1*y2 - x2*y1;
    }

}

