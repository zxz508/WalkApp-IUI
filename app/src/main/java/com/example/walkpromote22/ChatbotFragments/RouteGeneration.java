package com.example.walkpromote22.ChatbotFragments;



import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.data.model.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;




/**
 * 步行路线生成工具（支持 GPT 多途径点）
 */
public class RouteGeneration {
    private static LatLng location;
    public static String response;

    private static final String TAG = "RouteGeneration";

    public static double distanceOfRoute;
    /** 高德 Web 服务 Key */
    private static final String AMAP_KEY = "9bc4bb77bf4088e3664bff35350f9c37";

    /** OpenAI Key（上线前移出源码） */
    private static final String OPENAI_KEY = "sk-O62I7CQRETZ1dSFevmJWqdsJtsfWmg91sbBdWY8tJDRbgYTm";

    /* =========================================================
     *  工具：去掉 GPT ```json``` 代码块
     * ========================================================= */
    private static String stripCodeFence(String s) {
        if (s == null) return null;
        return s.replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
    }

    /** 提取字符串中首个 {...} 区块；若找不到抛 JSONException */
    private static String extractJsonObject(String raw) throws JSONException {
        int s = raw.indexOf('{');
        int e = raw.lastIndexOf('}');
        if (s == -1 || e == -1 || s > e)
            throw new JSONException("NO_JSON_OBJECT_FOUND");
        return raw.substring(s, e + 1);
    }

    /* =========================================================
     *  1. GPT 初步解析：暂时只拿 constraints 等文本信息
     * ========================================================= */

    /* =========================================================
     *  2. 获取缓存定位
     * ========================================================= */
    private static LatLng getUserLocation(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("AppData", Context.MODE_PRIVATE);
        String lat = sp.getString("location_lat", null);
        String lon = sp.getString("location_long", null);

        if (lat == null || lon == null) return null;
        try {
            location= new LatLng(Double.parseDouble(lat), Double.parseDouble(lon));
            return new LatLng(Double.parseDouble(lat), Double.parseDouble(lon));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* =========================================================
     *  3. 高德地理编码 / POI 查询
     * ========================================================= */


    /* =========================================================
     *  4. 步行路径：只要第 1 条
     * ========================================================= */
    private static JSONObject requestWalking(LatLng o, LatLng d,
                                             LatLng w) throws Exception {

        StringBuilder sb = new StringBuilder("https://restapi.amap.com/v3/direction/walking")
                .append("?origin=").append(o.longitude).append(',').append(o.latitude)
                .append("&destination=").append(d.longitude).append(',').append(d.latitude)
                .append("&output=json&key=").append(AMAP_KEY);

        if (w != null)
            sb.append("&waypoints=").append(w.longitude).append(',').append(w.latitude);
        // 120 ms 节流锁
        synchronized (RouteGeneration.class) {
            long diff = System.currentTimeMillis() - lastRequestTime;
            if (diff < MIN_INTERVAL_MS) Thread.sleep(MIN_INTERVAL_MS - diff);
            lastRequestTime = System.currentTimeMillis();
        }

        JSONObject resp = callWalkingApi(sb.toString());
        JSONObject route = resp.optJSONObject("route");
        if (route == null) return null;
        JSONArray paths = route.optJSONArray("paths");
        if (paths == null || paths.length() == 0) return null;

        // 如有“避开人多”用最短距离
        JSONObject best = paths.getJSONObject(0);

        return best;
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
        LatLng center = location;
        int halfSideMeters = 3000, tileSizeMeters =(int) Math.floor(halfSideMeters / Math.sqrt(7)); ;
        int perTileMax = 200, globalMax = 1200;
        boolean dedupById = true;

       // List<Location> coreLocations=getCoreLocationsFromRequirement(userUtter);
       // if (coreLocations.isEmpty()) {
            JSONArray nearby = fetchPOIs(center, halfSideMeters, tileSizeMeters,
                    perTileMax, globalMax, dedupById);

            Log.e("POI_DEBUG", "一共获取到的POI数量 nearby size = " + (nearby != null ? nearby.length() : "null"));

            // 直接用 GPT 选点
            SelectedRoute sel = chooseWaypointsWithGPT(userUtter, nearby);
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
                loc.setIndex_num(idx++);

                // 从 waypointNames 取对应名字（防止越界）
                if (sel.waypointNames != null && i < sel.waypointNames.size()) {
                    loc.setName(sel.waypointNames.get(i));
                } else {
                    loc.setName(""); // 或者给个默认值
                }

                result.add(loc);
            }


            // 计算总距离（直线方式）
            distanceOfRoute = computeAccurateDistance(sel.waypoints);
            Log.d("Route", "Total distance (waypoints only): " + distanceOfRoute + " meters");

            return result;
       // }
       // else {
         //   Log.e("POI_DEBUG", "一共获取到的POI数量core POI size = " + (coreLocations.size()));

      //  }
    }

    public static JSONArray getCoreLocationsFromRequirement(String requirment) throws JSONException {
        // 1) system 规则：仅返回 {A1,A2,...} 或 {0}
        String sysPrompt =
                "分析下面用户的语句中是否有提到要去到或者从其中出发的地名或者店名（只要是能在地图上查询到的都包括在内），" +
                        "如果有请返回{A1,A2,B1,B2,...}的格式，其中A1中文名、A2英文名（若无可留空），多个POI依次列出；" +
                        "如果没有提到任何POI名称或者地名等信息，则返回{0}。" +
                        "注意：只输出大括号包裹的内容，不能包含其他说明或前后缀；大括号使用半角{}（若误用全角｛｝也可以）。";

        // 2) few-shot
        String fsUser1 = "I wanna walk around my apartment";
        String fsAsst1 = "{0}";
        String fsUser2 = "从独墅湖邻里中心走到苏州中心，再去诚品生活喝咖啡";
        String fsAsst2 = "{独墅湖邻里中心, Dushu Lake Neighborhood Center, 苏州中心, Suzhou Center, 诚品生活, ESlite Life}";
        String fsUser3 = "I wanna eat KFC";
        String fsAsst3 = "{肯德基, KFC}";
        String fsUser4 = "I wanna go to KFC";
        String fsAsst4 = "{肯德基, KFC}";

        // 3) 组装对话历史
        ChatbotHelper helper = new ChatbotHelper(OPENAI_KEY);
        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", sysPrompt))
                .put(new JSONObject().put("role", "user").put("content", fsUser1))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst1))
                .put(new JSONObject().put("role", "user").put("content", fsUser2))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst2))
                .put(new JSONObject().put("role", "user").put("content", fsUser3))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst3))
                .put(new JSONObject().put("role", "user").put("content", fsUser4))
                .put(new JSONObject().put("role", "assistant").put("content", fsAsst4));

        // 4) 发送 requirment，等待回复
        final String[] reply = new String[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        helper.sendMessage(requirment == null ? "" : requirment, hist, new ChatbotResponseListener() {
            @Override public void onResponse(String r) { reply[0] = r; latch.countDown(); }
            @Override public void onFailure(String e)   { reply[0] = null; latch.countDown(); }
        });

        try {
            if (!latch.await(25, java.util.concurrent.TimeUnit.SECONDS)) throw new JSONException("TIMEOUT_WAITING_GPT");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new JSONException("INTERRUPTED_WAITING_GPT");
        }
        if (reply[0] == null) throw new JSONException("GPT_RESPONSE_NULL");

        // 5) 解析 {A,B,...} —— 兼容全角｛｝
        String raw = reply[0].trim();
        raw = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        raw = raw.replace('｛','{').replace('｝','}');
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        if (s == -1 || e == -1 || s > e) throw new JSONException("NO_BRACED_BLOCK_FOUND: " + raw);
        String inner = raw.substring(s + 1, e).trim();
        if (inner.isEmpty() || "0".equals(inner)) return new JSONArray();

        String[] toks = inner.split("[,，、;；\\s]+");
        java.util.LinkedHashSet<String> nameSet = new java.util.LinkedHashSet<>();
        for (String t : toks) {
            if (t == null) continue;
            String name = t.trim();
            if ((name.startsWith("\"") && name.endsWith("\"")) ||
                    (name.startsWith("“") && name.endsWith("”")) ||
                    (name.startsWith("'") && name.endsWith("'"))) {
                name = name.substring(1, name.length()-1).trim();
            }
            name = name.replaceAll("\\s*[\\(（【][^\\)）】]*[\\)）】]\\s*", " ").trim().replaceAll("\\s{2,}"," ");
            if (name.isEmpty()) continue;
            nameSet.add(name);
        }
        if (nameSet.isEmpty()) return new JSONArray();

        // 6) 同义词扩展
        java.util.Map<String,String[]> synonyms = new java.util.HashMap<>();
        synonyms.put("KFC", new String[]{"肯德基","KFC"});
        synonyms.put("肯德基", new String[]{"肯德基","KFC"});
        synonyms.put("McDonald's", new String[]{"麦当劳","McDonald's","Mcdonalds"});
        synonyms.put("麦当劳", new String[]{"麦当劳","McDonald's","Mcdonalds"});
        synonyms.put("Starbucks", new String[]{"星巴克","Starbucks"});
        synonyms.put("星巴克", new String[]{"星巴克","Starbucks"});
        synonyms.put("Pizza Hut", new String[]{"必胜客","Pizza Hut"});
        synonyms.put("必胜客", new String[]{"必胜客","Pizza Hut"});
        synonyms.put("Burger King", new String[]{"汉堡王","Burger King"});
        synonyms.put("汉堡王", new String[]{"汉堡王","Burger King"});

        java.util.LinkedHashSet<String> expanded = new java.util.LinkedHashSet<>(nameSet);
        for (String k : nameSet) {
            if (synonyms.containsKey(k)) {
                for (String alt : synonyms.get(k)) expanded.add(alt);
            } else if (k.matches("(?i)[a-z0-9\\-'. ]+")) {
                expanded.add(k.toUpperCase());
                expanded.add(k.substring(0,1).toUpperCase() + (k.length()>1 ? k.substring(1) : ""));
            }
        }

        // 7) AMap 检索
        java.util.List<Location> out = new java.util.ArrayList<>();
        java.util.Set<String> dedup = new java.util.HashSet<>();
        Double originLat, originLng;
        if (location != null) { originLat = location.latitude; originLng = location.longitude; } else {
            originLng = null;
            originLat = null;
        }

        final int RADIUS_M = 4000;
        final int PAGE_SZ  = 25;
        final int MAX_PAGES = 3;

        // 小工具：执行一次请求并收集（加入中文日志）
        java.util.function.BiFunction<String, Boolean, Integer> runQuery = (kw, useAround) -> {
            int added = 0;
            // —— 关键字日志：搜索关键词
            Log.d(TAG, "搜索关键词：" + kw + (useAround ? "（around）" : "（text）"));
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
                } catch (Exception enc) { break; }

                JSONObject resp;
                try { resp = httpGet(url); } catch (Exception e1) { break; }
                if (resp == null) break;

                String status = resp.optString("status","0");
                if (!"1".equals(status)) {
                    String info = resp.optString("info","");
                    Log.w(TAG, "AMap status!=1 kw=" + kw + " useAround=" + useAround + " info=" + info);
                    break;
                }

                JSONArray pois = resp.optJSONArray("pois");
                if (pois == null || pois.length() == 0) break;

                for (int i = 0; i < pois.length(); i++) {
                    JSONObject p = pois.optJSONObject(i);
                    if (p == null) continue;
                    String loc = p.optString("location", "");
                    if (!loc.contains(",")) continue;
                    String[] ll = loc.split(",");
                    if (ll.length != 2) continue;

                    double lat, lng;
                    try { lat = Double.parseDouble(ll[1]); lng = Double.parseDouble(ll[0]); }
                    catch (NumberFormatException nfe) { continue; }

                    String id = p.optString("id","");
                    if (!dedup.add(id + "#" + loc)) continue;

                    String cleanName = p.optString("name","")
                            .replaceAll("\\s*[\\(（【][^\\)）】]*[\\)）】]\\s*", " ")
                            .trim().replaceAll("\\s{2,}"," ");
                    if (cleanName.isEmpty()) continue;

                    Location L = new Location();
                    L.setName(cleanName);
                    L.setLatitude(lat);
                    L.setLongitude(lng);
                    out.add(L);
                    added++;
                    if (out.size() >= 100) {
                        // —— 关键字日志：搜索结果（截断）
                        Log.d(TAG, "搜索结果：新增 " + added + " 条（已达上限，提前结束）");
                        return added;
                    }
                }
            }
            // —— 关键字日志：搜索结果
            Log.d(TAG, "搜索结果：新增 " + added + " 条");
            return added;
        };

        // 7.1 先试原始/扩展关键词
        for (String kw : expanded) {
            if (originLat != null && originLng != null) {
                int a1 = runQuery.apply(kw, true);     // around
                if (a1 == 0) {
                    runQuery.apply(kw, false);         // around 无则 text 兜底
                }
            } else {
                runQuery.apply(kw, false);             // 无定位 → 直接 text
            }
        }

        // 7.2 仍为空：强制中文同义词再跑一轮
        if (out.isEmpty()) {
            java.util.List<String> forceZh = new java.util.ArrayList<>();
            for (String kw : expanded) {
                if (kw.equalsIgnoreCase("KFC")) forceZh.add("肯德基");
                if (kw.equalsIgnoreCase("Starbucks")) forceZh.add("星巴克");
                if (kw.equalsIgnoreCase("McDonald's") || kw.equalsIgnoreCase("Mcdonalds")) forceZh.add("麦当劳");
                if (kw.equalsIgnoreCase("Pizza Hut")) forceZh.add("必胜客");
                if (kw.equalsIgnoreCase("Burger King")) forceZh.add("汉堡王");
            }
            for (String kw : forceZh) {
                if (originLat != null && originLng != null) {
                    int a1 = runQuery.apply(kw, true);
                    if (a1 == 0) runQuery.apply(kw, false);
                } else {
                    runQuery.apply(kw, false);
                }
            }
        }

        // —— 汇总日志：总搜索结果（列出前若干名称）
        if (!out.isEmpty()) {
            StringBuilder names = new StringBuilder();
            int preview = Math.min(out.size(), 10);
            for (int i = 0; i < preview; i++) {
                if (i > 0) names.append("，");
                names.append(out.get(i).getName());
            }
            if (out.size() > preview) names.append("，…");
            Log.d(TAG, "搜索结果：共 " + out.size() + " 条；示例：" + names);
        } else {
            Log.d(TAG, "搜索结果：空（未命中任何POI）");
        }
        JSONArray result = new JSONArray();
        for (Location L : out) {
            JSONObject o = new JSONObject();
            o.put("name", L.getName());
            o.put("latitude", L.getLatitude());
            o.put("longitude", L.getLongitude());
            result.put(o);
        }

        // 汇总日志
        Log.d(TAG, "搜索结果：共 " + result.length() + " 条；示例：" +
                (result.length() > 0 ? result.optJSONObject(0).optString("name","") : "—"));

        return result;


    }


    // 计算真实步行距离（分段调用路线规划，再相加）
    private static double computeAccurateDistance(List<LatLng> wps) {
        if (wps == null || wps.size() < 2) return 0;
        double sum = 0;
        for (int i = 0; i < wps.size() - 1; i++) {
            LatLng a = wps.get(i), b = wps.get(i + 1);
            try {
                // 你已有的步行路线接口：origin -> target
                JSONObject seg = requestWalking(a, b, null);
                if (seg == null) continue;

                // 1) 优先用每段总距离（如果你的接口有）
                double segDist = seg.optDouble("distance", -1);

                // 2) 否则回退：累加 steps 的 distance
                if (segDist < 0 && seg.has("steps")) {
                    segDist = 0;
                    JSONArray steps = seg.getJSONArray("steps");
                    for (int j = 0; j < steps.length(); j++) {
                        segDist += steps.getJSONObject(j).optDouble("distance", 0);
                    }
                }

                // 3) 还不行就用直线距离兜底
                if (segDist <= 0) {
                    segDist = haversine(a.latitude, a.longitude, b.latitude, b.longitude);
                }
                sum += segDist;
            } catch (Exception ignore) {
                // 失败兜底
                sum += haversine(a.latitude, a.longitude, b.latitude, b.longitude);
            }
        }
        return sum; // 单位：米
    }

    /**
     * 在一段文本里扫描首个「配平」的大括号块，返回其子串。
     * 规则：遇到 '{' 计数+1，'}' 计数-1，当计数归零即完成。
     * 支持嵌套，忽略代码块前缀、自然语言、换行。
     */
    private static String extractBalancedJson(String raw) throws JSONException {
        Log.d("GPTRaw", "reply=" + raw);

        int start = raw.indexOf('{');
        if (start == -1) throw new JSONException("NO_JSON_START");
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        throw new JSONException("NO_MATCHING_BRACE");
    }


    /* =========================================================
     *  8. fetchNearbyPOIs / distance / httpGet 与旧实现一致
     * ========================================================= */
    // ...（保持你原来的实现）

    /* =========================================================
     *  9. GPT 选目的地 + 多途径点
     * ========================================================= */
    // 可选：把你的“参考回答路线 JSON”放到这里（和 buildSuccessExampleInput 配套）
    private static String buildSuccessExampleAnswer() {
        // 返回形如：{"waypoints":[{"name":"...","lat":..,"lng":..}, ...]}
        // TODO: 用你的真实 few-shot 参考答案替换
        return "{\"waypoints\":[{\"name\":\"起点\",\"lat\":31.2744759453053,\"lng\":120.73822377078568},{\"name\":\"西交科创园B区5幢\",\"lat\":31.274297,\"lng\":120.740088},{\"name\":\"营火工作室\",\"lat\":31.274225,\"lng\":120.741675},{\"name\":\"顺路咖啡(独墅湖店)\",\"lat\":31.273545,\"lng\":120.745959},{\"name\":\"瑞幸咖啡(苏州文荟广场店)\",\"lat\":31.269254,\"lng\":120.748849},{\"name\":\"西交利物浦大学宏愿餐厅\",\"lat\":31.270156,\"lng\":120.740486},{\"name\":\"起点\",\"lat\":31.2744759453053,\"lng\":120.73822377078568}]}";
    }

    private static String buildSuccessExampleAnswer2() {

        return " return \"{\\\"waypoints\\\":[{\\\"name\\\":\\\"起点\\\",\\\"lat\\\":31.26835,\\\"lng\\\":120.73525},{\\\"name\\\":\\\"湖畔花园\\\",\\\"lat\\\":31.26688,\\\"lng\\\":120.73777}]};";

    }
    private static SelectedRoute chooseWaypointsWithGPT(String userUtter,
                                                        JSONArray nearbyPOIs)
            throws InterruptedException, JSONException {

        String sysPrompt =
                "你是一名步行路线规划助手，收到用户需求和附近地点列表返回一个JSON。规则如下请遵守" +
                        "1.JSON的结构示例：{\"waypoints\":[{\"name\":\"\",\"lat\":0,\"lng\":0},...]}" +
                        "2.请根据用户需求和地点列表的经纬度来设计一个路线（排在前面的视为先前往)，每个相邻途径点之间的距离不要太远" +
                        "3.如果用户没有明确目的地且意图仅仅是在附近散步，请让终点接近起点。" +
                        "4.如果用户没有明确的起点，那默认起点为用户当前位置:"+location+"如果用户指定了起点则用其作为起点" +
                        "5.如果用户指定了终点，则直接返回起点，终点两个地点组成的json（如果还指定了途径点，请加在中间。如果没有指定途径点就绝对不要添加多余的途径点）"+
                        "6.请根据传输给你的经纬度计算大致的总路线长度，确保整个路线的长度不要太短（2-3公里比较合适），路线不要太曲折（经纬的变化是有规律的）。" +
                        "7.若包含“避开人多/想安静”等词，也请在选点时考虑如何能实现用户的需求。" +
                        "请必须返回且仅返回一个要求的JSON结构并仔细阅读下面全部的POI列表,越靠前的POI距离用户当前位置越近。" +
                        "JSON的结构示例：{\"waypoints\":[{\"name\":\"\",\"lat\":0,\"lng\":0},...]}";

        // === 1) few-shot 示例：长版示例输入（含长 POI 列表 + 用户需求）与其参考答案 ===

        String fewShotUser      = "I wanna walk around my apartment";   // 长版示例用户输入（含长 POI+需求）
        // 2) 本次真实 payload
        JSONArray eventInfo = fetchTrafficEventsOnce(location, 3000);
        JSONObject payload = new JSONObject()
                .put("user_request", userUtter)
                .put("nearby_pois", nearbyPOIs)
                .put("event_info", eventInfo);

        Log.e(TAG, "发给gpt的内容如下：" + payload);

        // 3) 组装对话历史：system -> few-shot(user, assistant) -> real user
        ChatbotHelper helper = new ChatbotHelper(OPENAI_KEY);
        JSONArray hist = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                        sysPrompt + "\n参考下面的示例输入输出对，严格按照相同的 JSON 结构返回结果。"))
                // Few-shot
                .put(new JSONObject().put("role", "user").put("content", fewShotUser)) // 示例输入
                .put(new JSONObject().put("role", "assistant").put("content", buildSuccessExampleAnswer())) // 示例输出
                // Real request
                .put(new JSONObject().put("role", "user").put("content", "I wanna walk to a park"))
                .put(new JSONObject().put("role", "assistant").put("content", buildSuccessExampleAnswer2()))

                .put(new JSONObject().put("role", "user").put("content", payload.toString()));


        // 4) 发送并等待
        CountDownLatch latch = new CountDownLatch(1);
        final String[] reply = new String[1];

        helper.sendMessage(payload.toString(), hist, new ChatbotResponseListener() {
            @Override public void onResponse(String r) { reply[0] = r; latch.countDown(); }
            @Override public void onFailure(String e)   { reply[0] = null; latch.countDown(); }
        });

        latch.await(40, TimeUnit.SECONDS);
        if (reply[0] == null) return null;

        // 5) 解析仅包含 waypoints 的 JSON
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
                InputStream is = "gzip".equalsIgnoreCase(enc) ? new java.util.zip.GZIPInputStream(raw) : raw;

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
            } catch (java.io.InterruptedIOException e) {
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
    private static JSONArray fetchTrafficEventsOnce(LatLng origin, int radiusMeter) {
        JSONArray flagged = new JSONArray();
        try {
            String url = "https://restapi.amap.com/v4/traffic/event/around"
                    + "?location=" + origin.longitude + "," + origin.latitude
                    + "&radius=" + radiusMeter                 // 建议 800~1000 m
                    + "&key=" + AMAP_KEY;

            JSONObject resp = httpGet(url);
            JSONArray evs = resp.optJSONObject("data")
                    .optJSONArray("event_list");
            if (evs == null) return flagged;

            for (int i = 0; i < evs.length(); i++) {
                JSONObject ev = evs.getJSONObject(i);
                String type = ev.optString("type");
                // 只把真正会阻塞步行的事件挑出来
                if ("closure".equals(type) || "construction".equals(type) || "accident".equals(type)) {
                    flagged.put(ev);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "traffic event fetch fail: " + e);
        }
        return flagged;
    }

    private static JSONObject callWalkingApi(String url) throws Exception {
        synchronized (RouteGeneration.class) {       // 复用同一把锁
            long diff = System.currentTimeMillis() - lastRequestTime;
            if (diff < MIN_INTERVAL_MS) Thread.sleep(MIN_INTERVAL_MS - diff);
            lastRequestTime = System.currentTimeMillis();
        }
        return httpGet(url);
    }
    public static double haversineDistance(Location loc1, Location loc2) {
        final int R = 6371000; // 地球半径，单位：米

        double lat1Rad = Math.toRadians(loc1.getLatitude());
        double lat2Rad = Math.toRadians(loc2.getLatitude());
        double deltaLat = Math.toRadians(loc2.getLatitude() - loc1.getLatitude());
        double deltaLon = Math.toRadians(loc2.getLongitude() - loc1.getLongitude());

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }
    public static double calculateTotalDistance(List<Location> route) {
        double total = 0.0;
        for (int i = 1; i < route.size(); i++) {
            total += haversineDistance(route.get(i - 1), route.get(i));
        }
        return total;
    }


    // ① 批量抓取（不按type分桶；不做30m距离去重；仅可选按id去重）
//   - center: 中心点
//   - halfSideMeters: 半边长（覆盖区域是 2*halfSide 的正方形）
//   - tileSizeMeters: 每个tile边长（200~300m较好）
//   - perTileMax: 每个tile最多抓多少条（分页累加，受AMap上限影响）
//   - globalMax: 全局上限（比如 5000）
    // 以“中心优先（方案A）”顺序抓取POI
    private static JSONArray fetchPOIs(
            LatLng center,
            int halfSideMeters,
            int tileSizeMeters,
            int perTileMax,
            int globalMax,
            boolean dedupById
    ) throws Exception {

        // 生成所有 tile 的中心点
        java.util.List<LatLng> tiles = buildTileCenters(center, halfSideMeters, tileSizeMeters);

        // 按“距离中心点的米制平方距离”升序排序，实现从中心向外检索
        java.util.Collections.sort(tiles, new java.util.Comparator<LatLng>() {
            @Override public int compare(LatLng a, LatLng b) {
                double da = squaredMetersDistance(center, a);
                double db = squaredMetersDistance(center, b);
                return Double.compare(da, db);
            }
        });

        // 每个 tile 使用“外接圆半径”，确保完整覆盖该 tile
        final int radius = (int) Math.ceil(Math.sqrt(2) * tileSizeMeters / 2.0*1.1);

        JSONArray out = new JSONArray();
        java.util.Set<String> seenIds = dedupById ? new java.util.HashSet<>() : null;

        for (LatLng t : tiles) {
            JSONArray arr = fetchNearbyPOIs(t, radius, perTileMax);
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.get(i);
                if (v instanceof JSONObject) {
                    JSONObject jo = (JSONObject) v;

                    if (dedupById) {
                        String id = jo.optString("id", "");
                        if (!id.isEmpty()) {
                            if (seenIds.contains(id)) continue;
                            seenIds.add(id);
                        }
                    }
                    String s = String.format( java.util.Locale.US,
                            "%s,(%.6f,%.6f)",
                            jo.optString("name"),
                            jo.optDouble("lat"),
                            jo.optDouble("lng")
                    );
                    out.put(s);
                } else if (v instanceof String) {
                    out.put((String) v);
                }

                if (out.length() >= globalMax) {
                    break; // 达到全局上限立即返回
                }
            }
        }



        JSONArray result=sortByDistance(out);
        // 假设你想调试输出 out
        String outStr = result.toString();

// 每条最多 3000 字符（留点余量，避免其他信息超长）
        int maxLogSize = 3000;
        for (int i = 0; i < outStr.length(); i += maxLogSize) {
            int end = Math.min(outStr.length(), i + maxLogSize);
            Log.e("POI_DEBUG", outStr.substring(i, end));
        }

        return result;
    }

    private static JSONArray sortByDistance(JSONArray out) throws JSONException {
        if (location != null) {
            java.util.List<Object> tempList = new java.util.ArrayList<>();
            for (int i = 0; i < out.length(); i++) {
                tempList.add(out.get(i));
            }

            java.util.Collections.sort(tempList, new java.util.Comparator<Object>() {
                @Override
                public int compare(Object o1, Object o2) {
                    double d1 = Double.MAX_VALUE;
                    double d2 = Double.MAX_VALUE;

                    try {
                        if (o1 instanceof String) {
                            // 从字符串里提取 (lat,lng)
                            String str = (String) o1;
                            int idx = str.indexOf('(');
                            int idx2 = str.indexOf(')');
                            if (idx >= 0 && idx2 > idx) {
                                String[] parts = str.substring(idx + 1, idx2).split(",");
                                double lat = Double.parseDouble(parts[0]);
                                double lng = Double.parseDouble(parts[1]);
                                d1 = squaredMetersDistance(location, new LatLng(lat, lng));
                            }
                        } else if (o1 instanceof JSONObject) {
                            JSONObject jo = (JSONObject) o1;
                            double lat = jo.optDouble("lat", Double.NaN);
                            double lng = jo.optDouble("lng", Double.NaN);
                            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                                d1 = squaredMetersDistance(location, new LatLng(lat, lng));
                            }
                        }

                        if (o2 instanceof String) {
                            String str = (String) o2;
                            int idx = str.indexOf('(');
                            int idx2 = str.indexOf(')');
                            if (idx >= 0 && idx2 > idx) {
                                String[] parts = str.substring(idx + 1, idx2).split(",");
                                double lat = Double.parseDouble(parts[0]);
                                double lng = Double.parseDouble(parts[1]);
                                d2 = squaredMetersDistance(location, new LatLng(lat, lng));
                            }
                        } else if (o2 instanceof JSONObject) {
                            JSONObject jo = (JSONObject) o2;
                            double lat = jo.optDouble("lat", Double.NaN);
                            double lng = jo.optDouble("lng", Double.NaN);
                            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                                d2 = squaredMetersDistance(location, new LatLng(lat, lng));
                            }
                        }

                    } catch (Exception ignore) {}

                    return Double.compare(d1, d2);
                }
            });

            // 重新写回 JSONArray
            out = new JSONArray();
            for (Object obj : tempList) {
                out.put(obj);
            }
        }

        return out;

    }

    // —— 辅助：快速米制近似的平方距离（用于排序，不参与精确计算）——
    private static double squaredMetersDistance(LatLng a, LatLng b) {
        double midLatRad = Math.toRadians((a.latitude + b.latitude) / 2.0);
        double dLatMeters = (b.latitude - a.latitude) * 110_540.0;                  // 每度纬度 ≈ 110.54 km
        double dLonMeters = (b.longitude - a.longitude) * 111_320.0 * Math.cos(midLatRad); // 每度经度 ≈ 111.32 km × cosφ
        return dLatMeters * dLatMeters + dLonMeters * dLonMeters;
    }


    // ② 单tile拉取（不带types），直接返回 "名称,(lat,lng)" 的字符串数组
    private static JSONArray fetchNearbyPOIs(
            LatLng origin, int radiusMeter, int maxCount) throws Exception {

        JSONArray slimArr = new JSONArray();
        int page = 1;
        final int PAGE_SZ = 25; // AMap place/around 单页最大25

        while (slimArr.length() < maxCount) {

            // —— 全局节流，同你原实现 —— //
            synchronized (RouteGeneration.class) {
                long diff = System.currentTimeMillis() - lastRequestTime;
                if (diff < MIN_INTERVAL_MS) Thread.sleep(MIN_INTERVAL_MS - diff);
                lastRequestTime = System.currentTimeMillis();
            }

            String url = "https://restapi.amap.com/v3/place/around"
                    + "?location=" + origin.longitude + "," + origin.latitude
                    + "&radius="   + radiusMeter
                    + "&output=json&extensions=base"
                    + "&offset="   + PAGE_SZ
                    + "&page="     + page
                    + "&key="      + AMAP_KEY;

            JSONObject resp = httpGet(url);
            JSONArray pois = resp.optJSONArray("pois");
            if (pois == null || pois.length() == 0) break;  // 真正无结果才退出

            for (int i = 0; i < pois.length(); i++) {
                if (slimArr.length() >= maxCount) return slimArr;

                JSONObject p = pois.getJSONObject(i);
                String loc = p.optString("location");
                if (loc == null || !loc.contains(",")) continue;
                String[] ll = loc.split(",");
                if (ll.length != 2) continue;

                String name = p.optString("name", "");
                // 去除中英文括号及内容


                double lat = Double.parseDouble(ll[1]);
                double lng = Double.parseDouble(ll[0]);

                String poiString = String.format(java.util.Locale.US, "%s,(%.6f,%.6f)", name, lat, lng);
                slimArr.put(poiString);
            }

            page++; // 下一页
        }
        return slimArr;
    }

    // 反射判断一下是否存在 isUsefulPoi，避免你没有这个方法时报错

    // ③ 生成tile中心（与你之前思路一致，近似换算，适用于城市小范围）
    // ③ 生成tile中心（与你之前思路一致，近似换算，适用于城市小范围）
    private static java.util.List<LatLng> buildTileCenters(LatLng c, int halfSideMeters, int tileSizeMeters) {
        double mPerDegLat = 111000.0;
        double mPerDegLng = 111000.0 * Math.cos(Math.toRadians(c.latitude));

        int steps = (int) Math.ceil((halfSideMeters * 2.0) / tileSizeMeters);
        double latStepDeg = tileSizeMeters / mPerDegLat;
        double lngStepDeg = tileSizeMeters / mPerDegLng;

        double minLat = c.latitude  - (halfSideMeters / mPerDegLat);
        double minLng = c.longitude - (halfSideMeters / mPerDegLng);

        java.util.List<LatLng> centers = new java.util.ArrayList<>();
        for (int r = 0; r < steps; r++) {
            for (int q = 0; q < steps; q++) {
                double lat = minLat + (r + 0.5) * latStepDeg;
                double lng = minLng + (q + 0.5) * lngStepDeg;
                centers.add(new LatLng(lat, lng));
            }
        }
        return centers;
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


}

