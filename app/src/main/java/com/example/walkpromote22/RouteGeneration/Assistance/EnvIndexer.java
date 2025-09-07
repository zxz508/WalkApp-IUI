package com.example.walkpromote22.RouteGeneration.Assistance;

import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.fetchPOIs;
import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.fetchTrafficEventsOnce;


import com.amap.api.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;

/**
 * EnvIndexer
 * - 在“中心点 + 环形走廊(innerKm ~ outerKm)”内，按网格(cellSizeMeters)离散出若干 cell
 * - 为每个 cell 统计：
 *    road_score（可走性近似：附近 POI 密度/道路相关关键词）
 *    green_score（公园/绿地相关 POI 占比）
 *    safe_score（根据路况事件热度与夜间惩罚）
 *    anchor_ids（从 POI 中挑 1~2 个代表性锚点，生成短ID，并把完整信息存入本地字典）
 * - 生成紧凑 payload（cells + anchors 索引 + 事件摘要 + 统计），用于传给 LLM
 * - 提供 id → 坐标/名称 的本地还原接口
 *
 * 注意：
 * 1) 这里不调用外部 fetch；请把“原始 POI / 事件”以 JSONArray 传入本类；
 * 2) 字段兼容：POI 既可包含 lat/lng，也可只有 "location":"lng,lat"；事件同理；
 * 3) 你可以把输出 payload 直接作为 Gen/Selector 的 userTurn 文本（toString()）；
 * 4) 多代理最终输出的 {"final":{"ids":["c08:a12","c11:a07",...]}} 可用 idsToLocations(...) 还原成坐标点序列；
 */
public final class EnvIndexer {

    private EnvIndexer() {}

    // ========= 公共静态API =========

    /**
     * 构建“紧凑 payload”（传给 LLM）：
     * {
     *   "user_request": "...",
     *   "center": {"lat":..,"lng":..},
     *   "Length of side": {"inner_km"},
     *   "tags": {"park","Green path","lake",....},
     *   "avoidHint": {"counts":{"construction":2,"accident":1}, "hotspots":[{"type":"construction","lat":..,"lng":..,"sev":"high"}]},
     *   "targetHint": [ {"name1":"lat","lng"},{"name2":"lat","lng"},{"name3":"lat","lng"}, ... ],
     * }
     */


    /** LLM 最终输出的 {"final":{"ids":["c08:a12","c11:a03",...]}} → 还原成 Location-like(轻量) */
    public static List<LatLng> idsToLatLngs(JSONArray ids) {
        List<LatLng> out = new ArrayList<>();
        if (ids == null) return out;
        for (int i = 0; i < ids.length(); i++) {
            String token = ids.optString(i, null);
            if (token == null) continue;
            String anchorId = parseAnchorId(token); // 支持 "cXX:aYY" 或 "aYY"
            if (anchorId == null) continue;
            Anchor a = AnchorDict.get(anchorId);
            if (a == null) continue;
            out.add(new LatLng(a.lat, a.lng));
        }
        return out;
    }

    /** 也可直接通过 anchor id 取完整信息（名称、类型、坐标） */
    public static JSONObject getAnchor(String anchorId) throws JSONException {
        Anchor a = AnchorDict.get(anchorId);
        if (a == null) return null;
        return new JSONObject()
                .put("id", a.id)
                .put("cell", a.cellId)
                .put("name", a.name)
                .put("type", a.type)
                .put("lat", a.lat)
                .put("lng", a.lng);
    }

    // ========= 实现细节 =========



    private static class Cell {
        final String id;
        final double lat, lng;
        Cell(String id, double lat, double lng) { this.id = id; this.lat = lat; this.lng = lng; }
    }

    private static class Poi {
        final double lat, lng;
        final String type, name;
        Poi(double lat, double lng, String type, String name) { this.lat = lat; this.lng = lng; this.type = type; this.name = name; }
    }

    private static class Evt {
        final double lat, lng;
        final String type, severity;
        Evt(double lat, double lng, String type, String severity) { this.lat = lat; this.lng = lng; this.type = type; this.severity = severity; }
    }

    private static class Anchor {
        final String id, cellId, name, type;
        final double lat, lng;
        Anchor(String id, String cellId, double lat, double lng, String name, String type) {
            this.id = id; this.cellId = cellId; this.lat = lat; this.lng = lng; this.name = name; this.type = type;
        }
    }

    private static class Score { final double road, green, safe; Score(double r, double g, double s){road=r;green=g;safe=s;} }

    /** 本地字典：anchor_id → Anchor（供 ids 还原用） */
    private static final class AnchorDict {
        private static final Map<String, Anchor> MAP = new HashMap<>();
        static void put(Anchor a){ MAP.put(a.id, a); }
        static Anchor get(String id){ return MAP.get(id); }
        static void clear(){ MAP.clear(); }
    }

    // 环形走廊中按方格生成 cells
    private static List<Cell> buildCorridorCells(double centerLat, double centerLng,
                                                 double innerKm, double outerKm,
                                                 int cellSizeMeters, int maxCells) {
        List<Cell> out = new ArrayList<>();
        double outerM = outerKm * 1000.0;
        double latStep = metersToLat(cellSizeMeters);
        double lngStep = metersToLng(cellSizeMeters, centerLat);

        // 计算外接方框范围
        double dLat = metersToLat(outerM);
        double dLng = metersToLng(outerM, centerLat);

        int idCounter = 0;
        for (double lat = centerLat - dLat; lat <= centerLat + dLat; lat += latStep) {
            for (double lng = centerLng - dLng; lng <= centerLng + dLng; lng += lngStep) {
                double distM = haversineMeters(centerLat, centerLng, lat, lng);
                if (distM < innerKm * 1000.0 || distM > outerM) continue; // 只保留落在环形带的格子
                String id = "c" + (idCounter++);
                out.add(new Cell(id, lat, lng));
                if (out.size() >= maxCells) return out;
            }
        }
        return out;
    }

    // 解析 POI
    private static List<Poi> parsePois(JSONArray arr) {
        List<Poi> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            double lat, lng;
            if (p.has("lat") && p.has("lng")) {
                lat = p.optDouble("lat", Double.NaN);
                lng = p.optDouble("lng", Double.NaN);
            } else {
                String loc = p.optString("location", "");
                String[] ll = loc.split(",");
                if (ll.length < 2) continue;
                try { lng = Double.parseDouble(ll[0]); lat = Double.parseDouble(ll[1]); }
                catch (Exception e) { continue; }
            }
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
            String type = p.optString("type", p.optString("tag", "unknown"));
            String name = p.optString("name", p.optString("pname",""));
            list.add(new Poi(lat, lng, type==null?"unknown":type.toLowerCase(Locale.ROOT), name));
        }
        return list;
    }

    // 解析事件
    private static List<Evt> parseEvents(JSONArray arr) {
        List<Evt> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            double lat = e.optDouble("lat", Double.NaN);
            double lng = e.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) {
                String loc = e.optString("location", "");
                String[] ll = loc.split(",");
                if (ll.length >= 2) {
                    try { lng = Double.parseDouble(ll[0]); lat = Double.parseDouble(ll[1]); } catch (Exception ignore) {}
                }
            }
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
            String type = e.optString("type", "unknown").toLowerCase(Locale.ROOT);
            String sev  = e.optString("severity", e.optString("level","")).toLowerCase(Locale.ROOT);
            list.add(new Evt(lat, lng, type, sev));
        }
        return list;
    }

    // 在半径 r 内筛 POI
    private static List<Poi> filterPoisWithin(List<Poi> src, double lat, double lng, double radiusM) {
        List<Poi> out = new ArrayList<>();
        double r2 = radiusM * radiusM;
        for (Poi p : src) {
            double d = fastApproxMeters2(lat, lng, p.lat, p.lng);
            if (d <= r2) out.add(p);
        }
        return out;
    }

    // 计算 cell 的 3 个分数
    private static Score scoreCell(List<Poi> nearPois, List<Evt> events, double lat, double lng, double evtRadiusM, boolean isNight) {
        // road_score：使用“周边 POI 密度 + 道路相关关键词”作为近似（0~1）
        int n = nearPois.size();
        int roadLike = 0;
        int greenLike = 0;
        for (Poi p : nearPois) {
            if (isRoadLike(p)) roadLike++;
            if (isGreenLike(p)) greenLike++;
        }
        // 密度归一化（经验参数：每 ~25 个 POI 视为密集）
        double density = Math.min(1.0, n / 25.0);
        double roadScore = clamp(0.3 * density + 0.7 * ratio(roadLike, Math.max(1, n)));

        // green_score：绿地相关占比
        double greenScore = ratio(greenLike, Math.max(1, n));

        // safe_score：1 - 事件热度（再叠加夜间惩罚）
        double heat = 0.0;
        for (Evt e : events) {
            double dm = haversineMeters(lat, lng, e.lat, e.lng);
            if (dm <= evtRadiusM) {
                double w = severityWeight(e.severity);
                // 距离衰减（线性）
                double fact = Math.max(0.0, 1.0 - dm / evtRadiusM);
                heat += w * fact;
            }
        }
        heat = Math.min(1.0, heat); // 归一化
        double nightPenalty = isNight ? 0.1 : 0.0; // 夜间稍微降低安全分
        double safeScore = clamp(1.0 - heat - nightPenalty);

        return new Score(roadScore, greenScore, safeScore);
    }

    private static boolean isGreenLike(Poi p) {
        String t = p.type == null ? "" : p.type;
        String nm = p.name == null ? "" : p.name.toLowerCase(Locale.ROOT);
        return containsAny(t, "park","trail","green","scenic","forest","garden","lake","river","wetland","belt","square")
                || containsAny(nm, "公园","绿道","绿地","森林","步道","滨河","湖","湿地","广场","花园","河");
    }

    private static boolean isRoadLike(Poi p) {
        String t = p.type == null ? "" : p.type;
        String nm = p.name == null ? "" : p.name.toLowerCase(Locale.ROOT);
        return containsAny(t, "road","street","avenue","bridge","cross","walk","sidewalk","footpath","path","lane")
                || containsAny(nm, "道路","街","桥","人行","过街","支路","巷","步行");
    }

    private static boolean containsAny(String s, String... keys) {
        if (s == null) return false;
        String low = s.toLowerCase(Locale.ROOT);
        for (String k : keys) if (low.contains(k)) return true;
        return false;
    }

    private static double severityWeight(String sev) {
        if (sev == null) return 0.4;
        String s = sev.toLowerCase(Locale.ROOT);
        if (s.contains("high") || s.contains("severe") || s.contains("严重")) return 1.0;
        if (s.contains("medium") || s.contains("moderate") || s.contains("中")) return 0.6;
        if (s.contains("low") || s.contains("minor") || s.contains("轻")) return 0.3;
        return 0.4;
    }

    // 在 nearPois 中挑 1~2 个 anchors：优先 green，再按距 cell 中心近
    private static List<Poi> pickAnchors(List<Poi> nearPois, int perCellMax) {
        List<Poi> greens = new ArrayList<>();
        List<Poi> others = new ArrayList<>();
        for (Poi p : nearPois) (isGreenLike(p) ? greens : others).add(p);

        List<Poi> out = new ArrayList<>(perCellMax);
        // 绿地先来
        int gKeep = Math.min(perCellMax, greens.size());
        for (int i = 0; i < gKeep; i++) out.add(greens.get(i));
        // 不足则用其他补齐
        for (int i = gKeep; i < perCellMax && i - gKeep < others.size(); i++) {
            out.add(others.get(i - gKeep));
        }
        return out;
    }

    // 事件摘要（counts + top hotspots）
    private static JSONObject buildEventSummary(List<Evt> evs, int maxKeep) throws JSONException {
        JSONObject counts = new JSONObject();
        JSONArray tops = new JSONArray();
        int kept = 0;
        for (Evt e : evs) {
            counts.put(e.type, counts.optInt(e.type, 0) + 1);
            if (kept < maxKeep) {
                tops.put(new JSONObject()
                        .put("type", e.type)
                        .put("lat", q(e.lat))
                        .put("lng", q(e.lng))
                        .put("sev", e.severity));
                kept++;
            }
        }
        return new JSONObject().put("counts", counts).put("hotspots", tops);
    }

    // ===== 地理&数学工具 =====

    private static double q(double v) { return Math.round(v * 1e4) / 1e4; }           // 保留 1e-4 度，约 11m
    private static double round2(double x) { return Math.round(x * 100.0) / 100.0; }  // 保留 2 位

    private static double clamp(double x) { return Math.max(0.0, Math.min(1.0, x)); }
    private static double ratio(int a, int b) { return b <= 0 ? 0.0 : clamp(a / (double) b); }

    // 粗略：1° 纬度 ≈ 111,320m
    private static double metersToLat(double m) { return m / 111_320.0; }
    // 粗略：1° 经度 ≈ 111,320 * cos(lat)
    private static double metersToLng(double m, double atLat) { return m / (111_320.0 * Math.cos(Math.toRadians(atLat))); }

    // Haversine 真距离（米）
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    // 近似平方距离（米^2），用于筛选
    private static double fastApproxMeters2(double lat1, double lng1, double lat2, double lng2) {
        // 小范围近似：把经纬差转换成米再平方和
        double dLatM = (lat2 - lat1) * 111_320.0;
        double dLngM = (lng2 - lng1) * 111_320.0 * Math.cos(Math.toRadians(lat1));
        return dLatM*dLatM + dLngM*dLngM;
    }

    // 提取 token 中的 anchor 部分：支持 "c12:a03" 或 "a03"
    private static String parseAnchorId(String token) {
        if (token == null) return null;
        int i = token.indexOf(':');
        if (i >= 0 && i + 1 < token.length()) return token.substring(i + 1);
        return token.startsWith("a") ? token : null;
    }

    // ========= 使用说明（示例） =========


}
