package com.example.walkpromote22.RouteGeneration.multiAgent;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;
import com.example.walkpromote22.data.model.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RouteOutput {

    private static final String TAG = "RouteOutput";

    private RouteOutput() {}

    // ========================= 对外同步入口 =========================

    // ========== 顶部常量/状态（类内） ==========
    private static final long TIMEOUT_SEC = 25L;
    private static final long MIN_INTERVAL_MS = 1200L; // 去抖：1.2s 内重复请求直接复用上次结果
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long lastRunAt = 0L;
    // 记住上一条成功路线，用于兜底返回
    private static final java.util.concurrent.atomic.AtomicReference<List<Location>> LAST_GOOD =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.Collections.emptyList());

    // ========== 保留的原接口（同步） ==========
    public static List<Location> generateRoute(@NonNull Context ctx, @NonNull String dialogForRoute) {
        final long now = android.os.SystemClock.uptimeMillis();

        // 去抖：短时间重复触发直接返回上一次的成功结果
        if (now - lastRunAt < MIN_INTERVAL_MS) {
            Log.w(TAG, "generateRoute: debounced (interval < " + MIN_INTERVAL_MS + "ms), return LAST_GOOD");
            List<Location> cached = LAST_GOOD.get();
            return (cached != null) ? cached : java.util.Collections.emptyList();
        }
        lastRunAt = now;

        // 单飞：已有在跑的任务则不重复执行（返回上次结果）
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            Log.w(TAG, "generateRoute: already in flight, return LAST_GOOD");
            List<Location> cached = LAST_GOOD.get();
            return (cached != null) ? cached : java.util.Collections.emptyList();
        }

        // 主线程警告（同步阻塞会卡 UI；如需无阻塞，改用你已有的异步链）
        if (android.os.Looper.getMainLooper() == android.os.Looper.myLooper()) {
            Log.w(TAG, "generateRoute: called on MAIN thread; this will block until pipeline finishes.");
        }

        try {
            // 仍走异步流水线，但这里阻塞等待
            java.util.concurrent.CompletableFuture<List<Location>> fut = planToLocations(ctx, dialogForRoute);

            List<Location> result;
            try {
                result = fut.get(TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                Log.e(TAG, "generateRoute: timeout after " + TIMEOUT_SEC + "s", te);
                result = java.util.Collections.emptyList();
            } catch (Exception e) {
                Log.e(TAG, "generateRoute: pipeline error", e);
                result = java.util.Collections.emptyList();
            }

            if (result != null && !result.isEmpty()) {
                LAST_GOOD.set(result);
                return result;
            }

            // 结果为空则兜底返回上一次的成功结果
            List<Location> cached = LAST_GOOD.get();
            return (cached != null) ? cached : java.util.Collections.emptyList();

        } finally {
            IN_FLIGHT.set(false);
        }
    }


    // ========================= 内部编排（异步） =========================

    /** 从 ctx + dialogForRoute(hist) + LLM → List<Location> */
    // ============= 内部编排（异步） =============

    /** 从 ctx + dialogForRoute(hist) + LLM → List<Location> */
    private static CompletableFuture<List<Location>> planToLocations(Context ctx, String hist) {
        CompletableFuture<List<Location>> fut = new CompletableFuture<>();
        try {
            // 1) 实例化三段 Agent（GEN/DETAIL/EVALUATOR）
            RouteGenAgent gen = new RouteGenAgent(ctx, hist);
            RouteDetailAgent detail = new RouteDetailAgent(gen);
            RouteEvaluateAgent selector = new RouteEvaluateAgent(gen);

            // 2) Coordinator
            Coordinator coord = new Coordinator(gen, detail, selector);

            // 3) 构造 seed（尽量从对话里取 APP_CONTEXT 的用户位置）
            Grid.Cell seedCell = new Grid.Cell();
            seedCell.id = 0;
            LatLng guess = extractUserLocFromDialog(hist);
            if (guess == null) guess = new LatLng(0, 0);
            seedCell.centerLat = guess.latitude;
            seedCell.centerLng = guess.longitude;

            Agent.Msg seed = Agent.Msg.send(Agent.Role.GEN, Agent.Role.GEN, seedCell);

            // 4) LLM 句柄
            ChatbotHelper llm = new ChatbotHelper();

            // 5) 运行：GEN → DETAIL → EVALUATOR(仅反馈)；最终一定来自 DETAIL
            coord.run(seed, llm)
                    .thenApply(finalMsg -> {
                        JSONArray wps = (finalMsg != null) ? finalMsg.waypoints : null;

                        // —— RAW 打印，确认到底收到了啥 ——（避免再被“只预览一部分”坑）
                        try {
                            String raw = (wps != null) ? wps.toString() : "[]";
                            // 分片打印，防 4KB 限制；你项目里若已有 logChunked 也可直接用
                            final int CHUNK = 3000;
                            for (int i = 0; i < raw.length(); i += CHUNK) {
                                int end = Math.min(raw.length(), i + CHUNK);
                                Log.i(TAG, "[ROUTE_OUTPUT:RAW_FINAL] " + raw.substring(i, end));
                            }
                        } catch (Throwable ignore) {}

                        // —— 防呆：如果不小心拿到 Evaluator 的控制帧（_decision），直接判为无效 ——
                        if (wps != null && wps.length() > 0) {
                            JSONObject first = wps.optJSONObject(0);
                            if (first != null && first.has("_decision")) {
                                Log.w(TAG, "Final payload looks like an evaluator control-frame; refusing to parse. Check Coordinator.run().");
                                return new ArrayList<Location>(); // 返回空，交由上层兜底/重试
                            }
                        }

                        List<Location> list = toLocations(wps);
                        if (wps != null && wps.length() > 0 && list.size() == 0) {
                            Log.w(TAG, "Final payload had " + wps.length()
                                    + " items but parsed 0 locations. Check waypoint keys (lat/lng/name).");
                        } else if (list.size() <= 1) {
                            Log.w(TAG, "Parsed " + list.size() + " location(s). A proper route usually needs >= 2.");
                        }
                        return list;
                    })
                    .whenComplete((list, err) -> {
                        if (err != null) fut.completeExceptionally(err);
                        else fut.complete(list != null ? list : new ArrayList<>());
                    });

        } catch (Throwable t) {
            fut.completeExceptionally(t);
        }
        return fut;
    }


    // ========================= 工具：对话解析 =========================

    /** 从对话文本中解析 APP_CONTEXT 的用户位置。 */
    private static LatLng extractUserLocFromDialog(String dialog) {
        if (dialog == null) return null;
        // 例：ASSISTANT: [APP_CONTEXT] UPDATE USER_LOCATION lat=30.650884, lng=114.191247; ts=...
        Pattern p = Pattern.compile("USER_LOCATION\\s+lat=([+-]?\\d+(?:\\.\\d+)?),\\s*lng=([+-]?\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(dialog);
        LatLng last = null;
        while (m.find()) {
            try {
                double lat = Double.parseDouble(m.group(1));
                double lng = Double.parseDouble(m.group(2));
                last = new LatLng(lat, lng);
            } catch (Throwable ignore) {}
        }
        return last;
    }

    // ========================= 工具：JSON → List<Location> =========================

    /** 将 SELECTOR 最终输出的 waypoints（JSONArray of {lat,lng,name?}）转换为 List<Location> */
    // ============= 工具：JSONArray(waypoints) → List<Location> =============

    private static List<Location> toLocations(JSONArray waypoints) {
        List<Location> out = new ArrayList<>();
        if (waypoints == null) return out;

        for (int i = 0; i < waypoints.length(); i++) {
            JSONObject wp = waypoints.optJSONObject(i);
            if (wp == null) continue;

            // 1) 更鲁棒的坐标解析：兼容 lat/lng, latitude/longitude, lon, x/y；也兼容字符串数值
            double lat = optDoubleAny(wp, "lat", "latitude", "y");
            double lng = optDoubleAny(wp, "lng", "lon", "longitude", "x");
            if (Double.isNaN(lat) || Double.isNaN(lng)) {
                // 再试一个常见嵌套：{ "location": { "lat": ..., "lng": ... } }
                JSONObject loc = wp.optJSONObject("location");
                if (loc != null) {
                    lat = optDoubleAny(loc, "lat", "latitude", "y");
                    lng = optDoubleAny(loc, "lng", "lon", "longitude", "x");
                }
            }
            if (Double.isNaN(lat) || Double.isNaN(lng)) {
                Log.w(TAG, "Skip waypoint without valid coords: " + wp);
                continue;
            }

            // 2) 名称兼容：name / title / poiName / label
            String name = optStringAny(wp, "name", "title", "poiName", "label");

            Location locObj = buildLocation(lat, lng, name);
            if (locObj != null) out.add(locObj);
        }
        return out;
    }

    /** 兼容多字段名 & 字符串数值的 double 读取 */
    private static double optDoubleAny(JSONObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                try {
                    Object v = obj.get(k);
                    if (v instanceof Number) return ((Number) v).doubleValue();
                    if (v instanceof String) return Double.parseDouble(((String) v).trim());
                } catch (Throwable ignore) {}
            }
        }
        return Double.NaN;
    }

    /** 兼容多字段名的字符串读取 */
    private static String optStringAny(JSONObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                try {
                    String s = obj.getString(k);
                    if (s != null && !s.trim().isEmpty()) return s;
                } catch (Throwable ignore) {}
            }
        }
        return null;
    }


    /**
     * 尽量鲁棒地把 (lat,lng,name?) 转为项目里的 Location：
     * 优先尝试常见构造：
     *   new Location(double lat, double lng, String name)
     *   new Location(double lat, double lng)
     *   new Location() + setLatitude/setLongitude/(lat/lng 字段赋值)
     * 若都失败则返回 null（调用方会跳过该点）。
     */
    private static Location buildLocation(double lat, double lng, String name) {
        try {
            // 1) (double,double,String)
            try {
                Constructor<?> c = Location.class.getDeclaredConstructor(double.class, double.class, String.class);
                c.setAccessible(true);
                return (Location) c.newInstance(lat, lng, name);
            } catch (NoSuchMethodException ignore) {}

            // 2) (double,double)
            try {
                Constructor<?> c = Location.class.getDeclaredConstructor(double.class, double.class);
                c.setAccessible(true);
                return (Location) c.newInstance(lat, lng);
            } catch (NoSuchMethodException ignore) {}

            // 3) () + setter
            try {
                Constructor<?> c = Location.class.getDeclaredConstructor();
                c.setAccessible(true);
                Object obj = c.newInstance();
                boolean ok = false;

                // setLatitude / setLongitude
                try {
                    Method m1 = Location.class.getMethod("setLatitude", double.class);
                    m1.invoke(obj, lat);
                    ok = true;
                } catch (NoSuchMethodException ignore) {}

                try {
                    Method m2 = Location.class.getMethod("setLongitude", double.class);
                    m2.invoke(obj, lng);
                    ok = true;
                } catch (NoSuchMethodException ignore) {}

                // setName
                if (name != null) {
                    try {
                        Method m3 = Location.class.getMethod("setName", String.class);
                        m3.invoke(obj, name);
                    } catch (NoSuchMethodException ignore) {}
                }

                // 直写字段 lat/lng（如果存在）
                if (!ok) {
                    try {
                        Field f1 = Location.class.getDeclaredField("lat");
                        f1.setAccessible(true);
                        f1.set(obj, lat);
                        ok = true;
                    } catch (NoSuchFieldException ignore) {}
                    try {
                        Field f2 = Location.class.getDeclaredField("lng");
                        f2.setAccessible(true);
                        f2.set(obj, lng);
                        ok = true;
                    } catch (NoSuchFieldException ignore) {}
                    if (name != null) {
                        try {
                            Field f3 = Location.class.getDeclaredField("name");
                            f3.setAccessible(true);
                            f3.set(obj, name);
                        } catch (NoSuchFieldException ignore) {}
                    }
                }

                return ok ? (Location) obj : null;
            } catch (Throwable ignore) {
                return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "buildLocation failed: " + t.getMessage());
            return null;
        }
    }
}
