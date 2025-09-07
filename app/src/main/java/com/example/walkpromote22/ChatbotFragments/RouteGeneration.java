package com.example.walkpromote22.ChatbotFragments;



import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
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
import com.example.walkpromote22.tool.MapTool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;


/**
 * 步行路线生成工具（支持 GPT 多途径点）
 */
public class RouteGeneration {
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
  /*  public static List<Location> generateRoute(@NonNull Context ctx,
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
        distanceOfRoute = computeAccurateDistance(sel.waypoints);
        Log.d("Route", "Total distance (waypoints only): " + distanceOfRoute + " meters");

        return result;
    }*/


    public static JSONArray getCoreLocationsFromRequirement(@NonNull Context ctx, String requirment) throws JSONException {
        final String TAG = "CoreLoc"; // 本方法专用 Tag，避免与全局 TAG 混淆
        final String trace = "trace=" + java.util.UUID.randomUUID().toString().substring(0, 8);
        final long t0 = System.currentTimeMillis();


        // 小工具：日志脱敏 & 截断
        java.util.function.Function<String, String> redactKey = (u) ->
                u == null ? "null" : u.replaceAll("([?&]key=)[^&]+", "$1***");
        java.util.function.BiFunction<String, Integer, String> cut = (s, n) -> {
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
                "你只能回复我{***}的格式，下面时具体要求：" +
                        "分析下面用户的语句中是否有提到要去到或者从其中出发的地名或者店名（只要是能在地图上查询到的都包括在内，请考虑同义词），" +
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


        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", sysPrompt))
                .put(new JSONObject().put("role", "user").put("content", fsUser1))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst1))
                .put(new JSONObject().put("role", "user").put("content", fsUser2))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst2))
                .put(new JSONObject().put("role", "user").put("content", fsUser3))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst3));
        hist = prependTranscript(hist, safeGetTranscript());

        final long tSys1 = System.currentTimeMillis();


        // ---------------- 2) LLM 请求 ----------------
        final String[] reply = new String[1];
        final String[] errMsg = new String[1];
        final long tLlm0 = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        ChatbotHelper helper = new ChatbotHelper();
        helper.sendMessage(requirment == null ? "" : requirment, hist, new ChatbotResponseListener() {
            @Override public void onResponse(String r) {
                reply[0] = r;
                Log.i(TAG, trace + " LLM_onResponse len=" + (r == null ? -1 : r.length()) +
                        " cost=" + (System.currentTimeMillis() - tLlm0) + "ms " +
                        " head=" + cut.apply(r, 120));
                latch.countDown();
            }
            @Override public void onFailure(String e) {
                errMsg[0] = e;
                Log.e(TAG, trace + " LLM_onFailure err=" + e);
                latch.countDown();
            }
        });

        try {
            boolean ok = latch.await(40, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                Log.e(TAG, trace + " LLM_TIMEOUT wait=25s");
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
        java.util.LinkedHashSet<String> nameSet = new java.util.LinkedHashSet<>();
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
        java.util.List<Location> out = new java.util.ArrayList<>();
        java.util.Set<String> dedup = new java.util.HashSet<>();
        final int RADIUS_M = 5000;
        final int PAGE_SZ = 25;
        final int MAX_PAGES = 5;

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger pagesOk = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger poisTotal = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.function.BiFunction<String, Boolean, Integer> runQuery = (kw, useAround) -> {
            int added = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                String url;
                try {
                    if (useAround && originLat != null && originLng != null) {
                        url = "https://restapi.amap.com/v3/place/around"
                                + "?location=" + originLng + "," + originLat
                                + "&radius=" + RADIUS_M
                                + "&keywords=" + java.net.URLEncoder.encode(kw, "UTF-8")
                                + "&sortrule=distance&output=json&extensions=base"
                                + "&offset=" + PAGE_SZ + "&page=" + page
                                + "&key=" + AMAP_KEY;
                    } else {
                        url = "https://restapi.amap.com/v3/place/text"
                                + "?keywords=" + java.net.URLEncoder.encode(kw, "UTF-8")
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
                out.sort(java.util.Comparator.comparingDouble(
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
   /* private static SelectedRoute chooseWaypointsWithGPT(String userUtter)
            throws Exception {

        String sysPrompt =
                "你是一名步行路线规划助手，收到用户需求和附近地点列表返回一个JSON。规则如下请遵守" +
                        "1.JSON的结构示例：{\"waypoints\":[{\"name\":\"\",\"lat\":0,\"lng\":0},...]}" +
                        "2.请根据用户需求和地点列表的经纬度来设计一个路线（排在前面的视为先前往)，每个相邻途径点之间的距离不要太远" +
                        "3.如果用户没有明确目的地且意图仅仅是在附近散步，请让终点接近起点。" +
                        "4.如果用户没有明确的起点，那默认起点为用户当前位置:" + location + "如果用户指定了起点则用其作为起点" +
                        "5.****如果用户指定了终点，则直接返回起点，终点两个地点组成的json（如果还指定了途径点，请加在中间。如果没有指定途径点就绝对不要添加多余的途径点)****" +
                        "6.请根据传输给你的经纬度计算大致的总路线长度，确保整个路线的长度不要太短（2-3公里比较合适），路线不要太曲折（经纬的变化是有规律的）。" +
                        "7.若包含“避开人多/想安静”等词，也请在选点时考虑如何能实现用户的需求。" +
                        "请必须返回且仅返回一个要求的JSON结构并仔细阅读下面全部的POI列表,越靠前的POI距离用户当前位置越近。" +
                        "JSON的结构示例：{\"waypoints\":[{\"name\":\"\",\"lat\":0,\"lng\":0},...]}";



        // === 真实 payload：加入事件/天气等（保留你原先的 fetchTrafficEventsOnce）===
        JSONArray eventInfo = fetchTrafficEventsOnce(location, 3000);
        int halfSideMeters = 3000, tileSizeMeters = (int) Math.floor(halfSideMeters / Math.sqrt(7));
        int perTileMax = 200, globalMax = 1200;
        JSONArray nearbyPOIs= fetchPOIs(location, halfSideMeters, tileSizeMeters, perTileMax, globalMax);
        JSONObject payload = new JSONObject()
                .put("user_request", userUtter)
                .put("nearby_pois", nearbyPOIs)
                .put("event_info", eventInfo);

        Log.e(TAG, "发给gpt的内容如下：" + payload);

        // === 组装对话历史：system -> few-shot -> real user（保留你的原顺序）===
        ChatbotHelper helper = new ChatbotHelper();
        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                        sysPrompt + "\n参考下面的示例输入输出对，严格按照相同的 JSON 结构返回结果。"))
                // Few-shot（示例输入/输出）
                .put(new JSONObject().put("role", "user").put("content", "I wanna walk around my apartment"))
                .put(new JSONObject().put("role", "assistant").put("content", buildSuccessExampleAnswer()))
                // 你原来的第二组 few-shot
                .put(new JSONObject().put("role", "user").put("content", "I wanna walk to a park"))
                .put(new JSONObject().put("role", "assistant").put("content", buildSuccessExampleAnswer2()))
                // 真实请求（只把 JSON 作为 user）
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
        String cleaned = extractBalancedJson(stripCodeFence(reply[0]));
        JSONObject res = new JSONObject(cleaned);
        response = cleaned;

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
*/







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
    private static JSONObject httpGet(String url) throws Exception {
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

    /**
     * 搜索附近 POI，并为每个 POI 追加街道 / 道路 / 行政区等 geo 信息
     * 返回格式示例：
     * {
     *   name:"星巴克",
     *   location:"116.416357,39.975368",
     *   distance_meter:230,
     *   geo:{
     *     street:"安外大街",
     *     number:"1号",
     *     roads:[ ... ],
     *     roadinters:[ ... ],
     *     township:"安贞街道",
     *     district:"朝阳区",
     *     city:"北京市",
     *     province:"北京市"
     *   }
     * }
     */
    // ============================================================================
// 仅返回「name / lat / lng」三字段的精简 POI 列表
// ============================================================================


    /* ============================================================================
       判断 POI 是否“有用”：必须有 name + typecode，且不属于低价值类型
       ========================================================================== */

    /* --------------------------------------------------------------- */
    /* 逆地理编码，把街道 / 道路 / 行政区等信息封装成 JSONObject        */



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
        synchronized (RouteGeneration.class) {
            long diff = System.currentTimeMillis() - lastRequestTime;
            if (diff < MIN_INTERVAL_MS) {
                try { Thread.sleep(MIN_INTERVAL_MS - diff); } catch (InterruptedException ignore) {}
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }


    private static final java.util.concurrent.ExecutorService POI_EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(4); // 并发度按需调

    private static <T> T runBlockingOnWorker(java.util.concurrent.Callable<T> job) {
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
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {

            return runBlockingOnWorker(() -> fetchPOIsByPolygonWorker(ctx.getApplicationContext(), poly));
        } else {
            return fetchPOIsByPolygonWorker(ctx.getApplicationContext(), poly);
        }
    }

    public static JSONArray fetchPOIsByPolygon(@NonNull Context ctx, @NonNull List<LatLng> queryPoly) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.e("POI_ALLF", "fetchPOIsByPolygon on MAIN → offload worker");
            return runBlockingOnWorker(() -> fetchPOIsByPolygonWorker(ctx.getApplicationContext(), queryPoly));
        } else {
            return fetchPOIsByPolygonWorker(ctx.getApplicationContext(), queryPoly);
        }
    }

    // ====== 主体：加入“对齐键 + 热区 TTL + 模糊覆盖 + 宽容读库” ======
    public static JSONArray fetchPOIsByPolygonWorker(@NonNull Context ctx,
                                               @NonNull List<LatLng> queryPoly) {
        final String TAG = "POI_ALLF";
        final long   STALE_MS         = 7L * 24 * 60 * 60 * 1000L;
        final int    PAGE_SZ          = 25, PAGE_CAP = 100, PER_BLOCK_MAX = 1000;
        final double GRID_MAX_SIDEM   = 1000.0;
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
            Log.e(TAG, String.format(java.util.Locale.US,
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
            Log.e(TAG, String.format(java.util.Locale.US,
                    "SUMMARY  ret_total=%d db=%d(%.1f%%) api=%d(%.1f%%) timeMs=%d",
                    0, 0, 0.0, 0, 0.0, (t1 - t0)));
            return new JSONArray();
        }

        // 状态与控制
        long now = System.currentTimeMillis();
        boolean hotFresh = false, masterFresh = false, fuzzyFresh = false;
        boolean skipApi = false;                     // true → 本次不打 API，直接 DB-only
        Set<String> apiTouchedIds = new java.util.HashSet<>(); // 本次 API 触达的 poiId

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
        java.util.Set<String> seenSlim = new java.util.HashSet<>();
        java.util.List<POI> locals = null;
        try {
            RectLL readBox = inflateRectByMeters(target, 30.0); // DB 读外扩 30m
            locals = poiDao.queryByBBox(readBox.minLat, readBox.maxLat, readBox.minLng, readBox.maxLng, LOCAL_READ_LIMIT);
        } catch (Throwable t) {
            Log.e(TAG, "poiDao.queryByBBox failed", t);
            locals = java.util.Collections.emptyList();
        }
        if (locals != null) {
            for (POI e : locals) {
                if (!(e.lat>=target.minLat && e.lat<=target.maxLat && e.lng>=target.minLng && e.lng<=target.maxLng)) continue;
                String slim = String.format(java.util.Locale.US, "%s,(%.6f,%.6f)", e.name==null?"":e.name, e.lat, e.lng);
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

        Log.e(TAG, String.format(java.util.Locale.US,
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
                + "?polygon=" + android.net.Uri.encode(polyStr)
                + (typesJoined.isEmpty() ? "" : "&types=" + android.net.Uri.encode(typesJoined))
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
                    + "?polygon=" + android.net.Uri.encode(polyStr)
                    + (typesJoined != null && !typesJoined.isEmpty() ? ("&types=" + android.net.Uri.encode(typesJoined)) : "")
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
    private static boolean pointInRect(double lat, double lng, RectLL r) {
        return lat>=r.minLat && lat<=r.maxLat && lng>=r.minLng && lng<=r.maxLng;
    }
    private static String makeSlim(String name, double lat, double lng) {
        return String.format(Locale.US, "%s,(%.6f,%.6f)", name==null?"":name, lat, lng);
    }
    private static String rectToPolygon(RectLL r) {
        return String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f|%.6f,%.6f|%.6f,%.6f|%.6f,%.6f",
                r.minLng, r.maxLat,  r.maxLng, r.maxLat,  r.maxLng, r.minLat,  r.minLng, r.minLat,  r.minLng, r.maxLat);
    }
    private static String sha1(String s) {
        try { java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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


    /* ======================= 轻探测（count） ======================= */

    private static int headCount(@NonNull Context ctx, @NonNull String polyStr, @NonNull String typesJoined) {
        // 速率限制
        synchronized (RouteGeneration.class) {
            long diff = System.currentTimeMillis() - lastRequestTime;
            if (diff < MIN_INTERVAL_MS) {
                try { Thread.sleep(MIN_INTERVAL_MS - diff); } catch (InterruptedException ignore) {}
            }
            lastRequestTime = System.currentTimeMillis();
        }
        if (AMAP_KEY == null || AMAP_KEY.isEmpty()) return 0;

        String url = "https://restapi.amap.com/v3/place/polygon"
                + "?polygon=" + android.net.Uri.encode(polyStr)
                + "&types=" + android.net.Uri.encode(typesJoined)
                + "&output=json&extensions=base"
                + "&offset=1&page=1"
                + "&key=" + AMAP_KEY;

        try {
            JSONObject resp = httpGet(url);
            if (resp == null || !"1".equals(resp.optString("status","0"))) return 0;
            String countStr = resp.optString("count","0");
            try { return Integer.parseInt(countStr); } catch (Exception ignore) { return 0; }
        } catch (Throwable t) {
            return 0;
        }
    }

    // —— 以“50 米”为量化步长，把矩形四边对齐到 50m 的网格上，保证 key 稳定 —— //
    private static RectLL canonicalizeForKey(RectLL r) {
        // 以矩形中纬度估算经度的米->度换算
        double midLat = 0.5 * (r.minLat + r.maxLat);
        double latDegPerM = 1.0 / 111_320.0;
        double lngDegPerM = 1.0 / (111_320.0 * Math.cos(Math.toRadians(Math.max(-85, Math.min(85, midLat)))));

        double quantumM = 50.0; // 50 米量化
        double qLat = latDegPerM * quantumM;
        double qLng = lngDegPerM * quantumM;

        RectLL c = new RectLL();
        c.minLat = Math.floor(r.minLat / qLat) * qLat;
        c.maxLat = Math.ceil (r.maxLat / qLat) * qLat;
        c.minLng = Math.floor(r.minLng / qLng) * qLng;
        c.maxLng = Math.ceil (r.maxLng / qLng) * qLng;
        return c;
    }

    private static String joinTypesStable(String[] types) {
        String[] cp = Arrays.copyOf(types, types.length);
        Arrays.sort(cp);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cp.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cp[i]);
        }
        return sb.toString();
    }



// ====== 下面是本方法私有的辅助实现 ======

    private interface PoiConsumer {
        // 返回 false 表示希望短路终止
        boolean accept(JSONObject poi);
    }
    private interface ContinueSupplier {
        boolean get();
    }

    private static void recursivelyDrainTypeGroup(
            @NonNull Context ctx,
            @NonNull String polyStr,
            @NonNull String[] typesGroup,
            int pageSz,
            int globalNeed,
            @NonNull PoiConsumer onPoi,
            @NonNull ContinueSupplier shouldContinue
    ) throws Exception {
        if (globalNeed <= 0 || !shouldContinue.get()) return;

        // 先做一次“探测”：offset=1, page=1，只看 count（文档：单次最多1000，超过需拆分）
        int cnt = headCount(ctx, polyStr, joinTypes(typesGroup));
        if (cnt <= 0) return;

        if (cnt > 1000 && typesGroup.length > 1) {
            // 二分此组，尽量把每次请求压到 ≤1000
            int mid = typesGroup.length / 2;
            String[] left  = java.util.Arrays.copyOfRange(typesGroup, 0, mid);
            String[] right = java.util.Arrays.copyOfRange(typesGroup, mid, typesGroup.length);
            recursivelyDrainTypeGroup(ctx, polyStr, left, pageSz, globalNeed, onPoi, shouldContinue);
            recursivelyDrainTypeGroup(ctx, polyStr, right, pageSz, globalNeed, onPoi, shouldContinue);
            return;
        }

        if (cnt > 1000 && typesGroup.length == 1) {
            // 单一大类仍然 >1000 —— 提示需要空间切割（由你的上层切图来做）
            Log.w("POI_POLY", "TYPE_OVERFLOW_NEED_SPATIAL_SPLIT type=" + typesGroup[0] + " count=" + cnt);
            // 也可在这里选择“更细的中类/小类表”再去拆；此处保持简单只打日志。
        }

        // 真正分页拉取这一组（最多翻 100 页；offset ≤25）
        int page = 1;
        int emptyPages = 0;
        while (shouldContinue.get()) {
            // 节流
            synchronized (RouteGeneration.class) {
                long diff = System.currentTimeMillis() - lastRequestTime;
                if (diff < MIN_INTERVAL_MS) try { Thread.sleep(MIN_INTERVAL_MS - diff); } catch (InterruptedException ignored) {}
                lastRequestTime = System.currentTimeMillis();
            }
            if (AMAP_KEY == null || AMAP_KEY.isEmpty()) {
                Log.e("POI_POLY", "AMAP_KEY is empty!");
                return;
            }

            String url = "https://restapi.amap.com/v3/place/polygon"
                    + "?polygon=" + Uri.encode(polyStr)
                    + "&types=" + Uri.encode(joinTypes(typesGroup))
                    + "&output=json&extensions=base"
                    + "&offset=" + pageSz  // 文档建议 ≤25
                    + "&page=" + page
                    + "&key=" + AMAP_KEY;

            JSONObject resp;
            try {
                resp = httpGet(url);
            } catch (Throwable netErr) {
                Log.e("POI_POLY", "httpGet error, page=" + page + ", url=" + url, netErr);
                break;
            }
            if (resp == null) break;

            String status = resp.optString("status", "0");
            if (!"1".equals(status)) {
                String info = resp.optString("info", "");
                Log.w("POI_POLY", "AMap response status=" + status + ", info=" + info + ", page=" + page);
                break;
            }

            JSONArray pois = resp.optJSONArray("pois");
            if (pois == null || pois.length() == 0) {
                emptyPages++;
                if (emptyPages >= 2) break; // 连续空页，收尾
                page++;
                if (page > 100) break; // 安全上限
                continue;
            }

            for (int i = 0; i < pois.length() && shouldContinue.get(); i++) {
                JSONObject p = pois.optJSONObject(i);
                if (p == null) continue;
                if (!onPoi.accept(p)) return; // 回调可短路
            }

            // AMap 某些情况下会在“临界页后”直接不给；保守上限 100 页
            page++;
            if (page > 100) break;
        }
    }




    // 多边形质心（简单平均法；可替换为更精确的多边形质心算法）
    private static LatLng polygonCentroid(List<LatLng> pts) {
        double sumLat = 0, sumLng = 0;
        for (LatLng p : pts) { sumLat += p.latitude; sumLng += p.longitude; }
        return new LatLng(sumLat / pts.size(), sumLng / pts.size());
    }

    // 你已有的排序：按中心点距离
    private static JSONArray sortByDistance(JSONArray arr, LatLng center) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i, ""));
        java.util.Collections.sort(list, (a, b) -> {
            LatLng la = parseSlim(a), lb = parseSlim(b);
            double da = distSq(la, center), db = distSq(lb, center);
            return Double.compare(da, db);
        });
        JSONArray out = new JSONArray();
        for (String s : list) out.put(s);
        return out;
    }
    private static LatLng parseSlim(String slim) {
        // 你的 makeSlim 反解析（若无，可简单兜底为 (0,0)）
        try {
            // 假定 slim = name|lat,lng 之类；这里示例仅演示，不影响主逻辑
            int i = slim.lastIndexOf('|');
            String ll = (i >= 0) ? slim.substring(i+1) : slim;
            String[] kv = ll.split(",");
            return new LatLng(Double.parseDouble(kv[0]), Double.parseDouble(kv[1]));
        } catch (Throwable ignore) {
            return new LatLng(0,0);
        }
    }
    private static double distSq(LatLng a, LatLng b) {
        double dlat = a.latitude - b.latitude, dlng = a.longitude - b.longitude;
        return dlat*dlat + dlng*dlng;
    }
    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] r = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : r) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    // ====== 自适应切割版：尽量少调 API，必要时再细分，抓更多 ======
   /*
    /** 去除面积≈0的矩形 */





    /** 单个子矩形：若无覆盖或过期 → 调 AMap 多边形搜索（矩形），最多抓 PER_RECT_MAX，入库 */





    /** 单个子矩形：若无覆盖或过期 → 调 AMap 多边形搜索（矩形），最多抓 PER_RECT_MAX，入库 */




    // ✅ 保留原签名，默认仅输出最终占比日志


    // 计算多边形的包围盒
    static class BBox { double minLat, maxLat, minLng, maxLng; }
    private static BBox bboxOf(List<LatLng> poly) {
        BBox b = new BBox();
        b.minLat = +90; b.maxLat = -90; b.minLng = +180; b.maxLng = -180;
        for (LatLng p : poly) {
            b.minLat = Math.min(b.minLat, p.latitude);
            b.maxLat = Math.max(b.maxLat, p.latitude);
            b.minLng = Math.min(b.minLng, p.longitude);
            b.maxLng = Math.max(b.maxLng, p.longitude);
        }
        return b;
    }
    // 判断两个包围盒在给定“米”边距下是否相交/相邻
    private static boolean bboxTouches(BBox a, BBox b, double gapMeters, double refLat) {
        double padLat = gapMeters / 111320.0;
        double padLng = gapMeters / (Math.cos(Math.toRadians(refLat)) * 111320.0 + 1e-12);
        return (a.minLat <= b.maxLat + padLat) && (a.maxLat >= b.minLat - padLat) &&
                (a.minLng <= b.maxLng + padLng) && (a.maxLng >= b.minLng - padLng);
    }

    /**
     * 把若干“未覆盖簇”的多边形，按邻近性合并成更少的大多边形（凸包）
     * @param clusterPolys 各簇的闭合多边形（首尾相同）
     * @param mergeGapMeters 相邻合并阈值（建议 200~500m）
     * @param refLat 用于经度米转角度的参考纬度
     */
    private static List<List<LatLng>> mergeClustersToSuperPolys(List<List<LatLng>> clusterPolys,
                                                                double mergeGapMeters,
                                                                double refLat) {
        int n = clusterPolys.size();
        if (n <= 1) return clusterPolys;

        // 预计算包围盒
        BBox[] boxes = new BBox[n];
        for (int i = 0; i < n; i++) boxes[i] = bboxOf(clusterPolys.get(i));

        // 基于包围盒近邻的并查集合并
        int[] uf = new int[n];
        for (int i = 0; i < n; i++) uf[i] = i;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            public int applyAsInt(int x) { return uf[x]==x ? x : (uf[x]=applyAsInt(uf[x])); }
        };
        java.util.function.BiConsumer<Integer,Integer> unite = (a,b)->{
            int ra = find.applyAsInt(a), rb = find.applyAsInt(b);
            if (ra != rb) uf[ra] = rb;
        };

        for (int i=0; i<n; i++) for (int j=i+1; j<n; j++) {
            if (bboxTouches(boxes[i], boxes[j], mergeGapMeters, refLat)) unite.accept(i,j);
        }

        // 收集分组
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i=0;i<n;i++){
            int r = find.applyAsInt(i);
            groups.computeIfAbsent(r, k->new ArrayList<>()).add(i);
        }

        // 每组做一次凸包，形成“超级多边形”
        List<List<LatLng>> supers = new ArrayList<>();
        for (List<Integer> g : groups.values()) {
            if (g.size()==1) { supers.add(clusterPolys.get(g.get(0))); continue; }
            List<LatLng> all = new ArrayList<>();
            for (int idx : g) {
                // 去掉重复的尾点
                List<LatLng> poly = clusterPolys.get(idx);
                int m = poly.size();
                for (int k=0; k<m; k++) {
                    // 最后一个点若与第一个相同则跳过
                    if (k==m-1 && poly.get(0).latitude==poly.get(k).latitude
                            && poly.get(0).longitude==poly.get(k).longitude) break;
                    all.add(poly.get(k));
                }
            }
            List<LatLng> hull = convexHull(all);
            if (hull.size()>=3) {
                // 闭合
                if (hull.get(0).latitude!=hull.get(hull.size()-1).latitude ||
                        hull.get(0).longitude!=hull.get(hull.size()-1).longitude) {
                    hull.add(hull.get(0));
                }
                supers.add(hull);
            }
        }
        return supers;
    }




    /*private static JSONArray fetchNearbyPOIs(@NonNull Context ctx,
                                             @NonNull LatLng origin,
                                             int radiusMeter,
                                             int maxCount,
                                             int tileSizeMeters) throws Exception {
        final String TAG = "POI_DEBUG";
        long t0 = System.currentTimeMillis();

        JSONArray slimArr = new JSONArray();
        if (origin == null) return slimArr;

        final AppDatabase db = AppDatabase.getDatabase(ctx.getApplicationContext());
        final POIDao poiDao = db.poiDao();
        final PoiTileCoverageDao covDao = db.poiTileCoverageDao();

        int page = 1;
        final int PAGE_SZ = 25; // AMap 单页最大 25

        String cellKey = buildCellKey(origin, tileSizeMeters);
        long now = System.currentTimeMillis();
        int apiAdded = 0;

        while (slimArr.length() < maxCount) {
            synchronized (RouteGeneration.class) {
                long diff = System.currentTimeMillis() - lastRequestTime;
                if (diff < MIN_INTERVAL_MS) Thread.sleep(MIN_INTERVAL_MS - diff);
                lastRequestTime = System.currentTimeMillis();
            }

            if (AMAP_KEY == null || AMAP_KEY.isEmpty()) {
                Log.e(TAG, "AMAP_KEY is empty!");
                break;
            }

            String url = "https://restapi.amap.com/v3/place/around"
                    + "?location=" + origin.longitude + "," + origin.latitude
                    + "&radius=" + radiusMeter
                    + "&output=json&extensions=base"
                    + "&offset=" + PAGE_SZ
                    + "&page=" + page
                    + "&key=" + AMAP_KEY;

            JSONObject resp;
            try {
                resp = httpGet(url);
            } catch (Throwable netErr) {
                Log.e(TAG, "httpGet error, page=" + page + ", url=" + url, netErr);
                break;
            }

            if (resp == null) {
                Log.e(TAG, "httpGet returned null, page=" + page);
                break;
            }

            String status = resp.optString("status", "0");
            if (!"1".equals(status)) {
                String info = resp.optString("info", "");
                Log.w(TAG, "AMap response status=" + status + ", info=" + info + ", page=" + page);
                break;
            }

            JSONArray pois = resp.optJSONArray("pois");
            if (pois == null || pois.length() == 0) break;

            for (int i = 0; i < pois.length() && slimArr.length() < maxCount; i++) {
                JSONObject p = pois.optJSONObject(i);
                if (p == null) continue;

                String loc = p.optString("location", "").trim();
                if (loc.isEmpty() || !loc.contains(",")) continue;
                String[] ll = loc.split(",");
                if (ll.length != 2) continue;

                double lng, lat;
                try {
                    lng = Double.parseDouble(ll[0]);
                    lat = Double.parseDouble(ll[1]);
                } catch (NumberFormatException nfe) { continue; }

                String name = p.optString("name", "");
                String type = p.optString("type", "");
                String poiId = p.optString("id", "");
                String address = p.optString("address", "");

                String slim = makeSlim(name, lat, lng);
                slimArr.put(slim);
                apiAdded++;

                // upsert
                POI entity = new POI();
                entity.geocell = cellKey;
                entity.poiId = (poiId == null || poiId.isEmpty()) ? (name + "@" + lat + "," + lng) : poiId;
                entity.name = name; entity.type = type; entity.address = address; entity.lat = lat; entity.lng = lng;
                entity.updatedAt = now;
                poiDao.insertReplace(entity);
            }

            if (pois.length() < PAGE_SZ) break;
            page++;
        }

        // 覆盖标记
        PoiTileCoverage cov = new PoiTileCoverage();
        cov.geocell = cellKey; cov.tileSizeMeters = tileSizeMeters; cov.coveredAt = now; covDao.upsert(cov);

        return slimArr;
    }*/







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














































    static double haversine(double lat1, double lon1,
                            double lat2, double lon2) {
        final double R = 6371000; // 地球半径 (m)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public static SelectedRoute GetPreferredRoute(){

        return null;
    }



    private static String polygonForAMap(List<LatLng> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            LatLng p = path.get(i);
            if (i > 0) sb.append("|");
            sb.append(String.format(Locale.US, "%.6f,%.6f", p.longitude, p.latitude));
        }
        return sb.toString();
    }

    private static List<LatLng> parseAMapPolygon(String polyStr) {
        List<LatLng> out = new ArrayList<>();
        if (polyStr == null || polyStr.isEmpty()) return out;
        String[] segs = polyStr.split("\\|");
        for (String s : segs) {
            String[] ll = s.split(",");
            if (ll.length != 2) continue;
            double lng = Double.parseDouble(ll[0]);
            double lat = Double.parseDouble(ll[1]);
            out.add(new LatLng(lat, lng));
        }
        return out;
    }



    private static boolean pointInPolygon(double lat, double lng, List<LatLng> poly) {
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double xi = poly.get(i).latitude, yi = poly.get(i).longitude;
            double xj = poly.get(j).latitude, yj = poly.get(j).longitude;
            boolean intersect = ((yi > lng) != (yj > lng)) &&
                    (lat < (xj - xi) * (lng - yi) / (yj - yi + 1e-12) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    // 单调链构造凸包（输入点集，输出外轮廓，lat 做 x，lng 做 y）
    private static List<LatLng> convexHull(List<LatLng> pts) {
        if (pts.size() <= 3) return new ArrayList<>(pts);
        pts.sort((a,b)-> Double.compare(a.latitude==b.latitude ? a.longitude-b.longitude : a.latitude-b.latitude, 0));
        List<LatLng> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (LatLng p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size()-2), lower.get(lower.size()-1), p) <= 0) lower.remove(lower.size()-1);
            lower.add(p);
        }
        for (int i = pts.size()-1; i>=0; --i) {
            LatLng p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size()-2), upper.get(upper.size()-1), p) <= 0) upper.remove(upper.size()-1);
            upper.add(p);
        }
        lower.remove(lower.size()-1); upper.remove(upper.size()-1);
        lower.addAll(upper);
        return lower;
    }
    private static double cross(LatLng a, LatLng b, LatLng c) {
        double x1 = b.latitude - a.latitude, y1 = b.longitude - a.longitude;
        double x2 = c.latitude - a.latitude, y2 = c.longitude - a.longitude;
        return x1*y2 - x2*y1;
    }

}

