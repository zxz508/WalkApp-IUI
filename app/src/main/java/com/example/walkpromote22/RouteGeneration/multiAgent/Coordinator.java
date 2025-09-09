package com.example.walkpromote22.RouteGeneration.multiAgent;

import android.util.Log;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

/**
 * 升级版协调器（引入 Evaluator 的一次性回退逻辑）
 *
 * - GEN 输入：Msg(cell) ；输出：Msg(cell)（已包含 Anchor/POIs/Tags 等）
 * - DETAIL 输入：Msg(cell)；输出：Msg(routes) —— routes 放在 Msg.waypoints（对象数组）
 * - EVALUATOR 输入：Msg(routes)；输出：
 *      a) 接受：Msg(waypoints) —— 最终点序列
 *      b) 回退：Msg(waypoints=[ { "_decision":"revise", "route_index":?, "feedback":{...} } ])
 *
 * Coordinator 根据 b) 控制帧决定是否回退 DETAIL 一次；第二次不会再回退。
 */
public class Coordinator {

    private static final String TAG = "MA-Coord";

    // Route-only logger 限制（保护 log 体积）
    private static final int ROUTE_LOG_CHUNK = 3000;
    private static final int ROUTE_LOG_MAX_ROUTES = 10;
    private static final int ROUTE_LOG_MAX_WPS_PER_ROUTE = 50;
    private static final int WAYPOINT_PREVIEW_MAX = 50;

    private final Agent gen, detail, evaluator;

    public Coordinator(Agent gen, Agent detail, Agent evaluator) {
        this.gen = gen;
        this.detail = detail;
        this.evaluator = evaluator;
    }

    /** 运行全链路：GEN → DETAIL → （EVALUATOR →[可选回退]→ DETAIL → EVALUATOR） */
    public CompletableFuture<Agent.Msg> run(Agent.Msg seed, ChatbotHelper llm) throws Exception {
        Log.i(TAG, "Coordinator.run() start");
        logSeed(seed);

        // ============= GEN =============
        CompletableFuture<Agent.Msg> genStage = gen.onMessage(seed, llm)
                .thenApply(mGen -> {
                    logStageDone("GEN", mGen);
                    // GEN→DETAIL 的日志在构造好 toDetail 后再打，确保与实际发送一致
                    return mGen;
                });

        // ============= DETAIL(1) =============
        CompletableFuture<Agent.Msg> detailStage = genStage.thenCompose(mGen -> {
            Log.i(TAG, "running DETAIL...");
            try {
                // 统一构造传给 DETAIL 的消息（JSONArray）
                Agent.Msg toDetail;
                if (mGen != null && mGen.waypoints != null && mGen.waypoints.length() > 0) {
                    // 新协议：GEN 已输出多 cell + cell_path
                    toDetail = Agent.Msg.send(Agent.Role.GEN, Agent.Role.DETAIL, mGen.waypoints);

                } else if (mGen != null && mGen.cell != null) {
                    // 兼容路径：只有一个 cell，封装为 DETAIL 期望结构
                    Grid.Cell c = mGen.cell;

                    JSONObject cellObj = new JSONObject()
                            .put("cell_id", c.id)
                            .put("center", new JSONObject().put("lat", c.centerLat).put("lng", c.centerLng))
                            .put("anchor", c.anchor != null ? c.anchor : new JSONArray())
                            .put("tags",   c.tags   != null ? c.tags   : new JSONArray())
                            .put("pois",   c.pois   != null ? c.pois   : new JSONArray());

                    JSONArray pack = new JSONArray()
                            .put(cellObj)
                            .put(new JSONObject().put("cell_path", new JSONArray().put(c.id))); // 简单路径

                    toDetail = Agent.Msg.send(Agent.Role.GEN, Agent.Role.DETAIL, pack);
                } else {
                    toDetail = Agent.Msg.send(Agent.Role.GEN, Agent.Role.DETAIL, new JSONArray());
                }

                // ★ GEN → DETAIL 日志（RAW/预览）
                try {
                    if (FULL_PIPE_LOG) {
                        pipeGenToDetailLogRaw(toDetail);
                    } else {
                        pipeGenToDetailLog(toDetail);
                    }
                } catch (Throwable ignore) {}

                return detail.onMessage(toDetail, llm)
                        .thenApply(mDet -> {
                            logStageDone("DETAIL", mDet);
                            // ★ DETAIL → EVALUATOR 日志（RAW/预览）
                            try {
                                if (FULL_PIPE_LOG) {
                                    pipeDetailToEvaluatorLogRaw(mDet);
                                } else {
                                    pipeDetailToEvaluatorLog(mDet);
                                }
                            } catch (Throwable ignore) {}
                            return mDet;
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // ============= EVALUATOR(1) -> (optional) DETAIL(2) -> FINAL FROM DETAIL =============
        CompletableFuture<Agent.Msg> finalizeStage = detailStage.thenCompose(mDet -> {
            Log.i(TAG, "running EVALUATOR(1)...");
            try {
                // 1) 取 DETAIL(1) 的产物
                JSONArray detPayload = (mDet != null) ? mDet.waypoints : null;

                // 2) 适配为 Evaluator 期望的 schema（routes[].waypoints[] + estimated_length_km）
                //    若 detPayload 已经是 routes 形状，则直接透传；否则包一层
                double targetKm = extractTargetKmFromDialog(gen); // 没有就返回 Double.NaN
                JSONArray evaInput = adaptForEvaluator(detPayload, targetKm);

                // ☆ 调试打印：看送评审的最终载荷
                try {
                    final String raw = evaInput != null ? evaInput.toString() : "[]";
                    final int CHUNK = 3000;
                    for (int i = 0; i < raw.length(); i += CHUNK) {
                        Log.i(TAG, "[DETAIL→EVALUATOR:ADAPTED] " + raw.substring(i, Math.min(raw.length(), i + CHUNK)));
                    }
                } catch (Throwable ignore) {}

                Agent.Msg toEva = Agent.Msg.send(Agent.Role.DETAIL, Agent.Role.EVALUATOR,
                        evaInput != null ? evaInput : new JSONArray());

                return evaluator.onMessage(toEva, llm)
                        .thenCompose(mEva1 -> {
                            logStageDone("EVALUATOR(1)", mEva1);
                            try {
                                if (FULL_PIPE_LOG) {
                                    pipeEvaluatorOutputLogRaw(mEva1);
                                } else {
                                    pipeEvaluatorOutputLog(mEva1);
                                }
                            } catch (Throwable ignore) {}

                            // —— 解析评审决策，仅决定是否再跑一次 Detail ——
                            boolean needsRevise = false;
                            JSONObject feedback = null;
                            try {
                                if (mEva1 != null && mEva1.waypoints != null && mEva1.waypoints.length() > 0) {
                                    JSONObject ctrl = mEva1.waypoints.optJSONObject(0);
                                    if (ctrl != null) {
                                        String decision = ctrl.optString("_decision", "");
                                        needsRevise = "revise".equalsIgnoreCase(decision);
                                        feedback = ctrl.optJSONObject("feedback");
                                    }
                                }
                            } catch (Throwable ignore) {}



                            appendFeedbackToDialog(gen, feedback);

                            if (!needsRevise) {
                                // ✅ 最终结果 === Detail(1) 的产物
                                return CompletableFuture.completedFuture(mDet);
                            }

                            // 需要改进：再跑 Detail(2) 完成定稿
                            Grid.Cell cell = resolveCurrentCell(detail, gen);
                            if (cell != null) {
                                Log.i(TAG, "running DETAIL(2) finalize after evaluator feedback...");
                                Agent.Msg req = Agent.Msg.send(Agent.Role.EVALUATOR, Agent.Role.DETAIL, cell);
                                try {
                                    return detail.onMessage(req, llm)
                                            .thenApply(mDet2 -> {
                                                logStageDone("DETAIL(2)-FINAL", mDet2);
                                                return mDet2 != null ? mDet2 : mDet;
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            } else {
                                Log.w(TAG, "resolveCurrentCell returned null; returning Detail(1) as final to avoid data loss.");
                                return CompletableFuture.completedFuture(mDet);
                            }
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        // ============= 失败兜底 + 结束 =============
        return finalizeStage.exceptionally(ex -> {
            String reason = (ex != null && ex.getMessage() != null) ? ex.getMessage() : "unknown-error";
            Log.e(TAG, "pipeline failed: " + reason, ex);
            // 失败时基于 seed 做一个最小可用的兜底（你的原有方法）
            return buildFallbackFinal(seed, reason);
        }).thenApply(msg -> {
            if (isEmptyFinal(msg)) {
                Log.w(TAG, "final waypoints empty, applying fallback...");
                return buildFallbackFinal(seed, "empty-final");
            }
            Log.i(TAG, "Coordinator.run() done");
            return msg;
        });
    }


    /** 将 Detail 的产物适配为 Evaluator 期望的 routes 结构；如果已是 routes 结构则透传 */
    /** 将 Detail 的产物适配为 Evaluator 期望的 routes 结构；如果已是 routes 结构则透传并补全缺省字段 */
    private static JSONArray adaptForEvaluator(JSONArray detPayload, double targetKm) {
        JSONArray routes = new JSONArray();
        if (detPayload == null) return routes;

        // 已是 routes 结构：透传并补 estimated_length_km / target_km
        JSONObject first = detPayload.optJSONObject(0);
        if (first != null && first.has("waypoints") && first.optJSONArray("waypoints") != null) {
            try {
                for (int i = 0; i < detPayload.length(); i++) {
                    JSONObject r = detPayload.optJSONObject(i);
                    JSONArray wps = r != null ? r.optJSONArray("waypoints") : null;
                    if (r != null && !r.has("estimated_length_km")) {
                        r.put("estimated_length_km", estimateLengthKm(wps));
                    }
                    if (r != null && !r.has("start")) {
                        JSONObject st = firstPointAsStart(wps);
                        if (st != null) r.put("start", st);
                    }
                    if (r != null && !r.has("polyline")) {
                        r.put("polyline", wps != null ? wps : new JSONArray());
                    }
                    if (r != null && !r.has("anchors")) {
                        r.put("anchors", anchorsFromWaypoints(wps));
                    }
                    if (!Double.isNaN(targetKm)) r.put("target_km", targetKm);
                }
            } catch (Throwable ignore) {}
            return detPayload;
        }

        // 否则：detPayload 是“扁平 waypoints 列表”，包成 1 条标准 route
        JSONArray wps = detPayload;
        JSONObject route = new JSONObject();
        try {
            route.put("type", "detail-final");
            route.put("notes", "adapted-from-waypoints");
            route.put("waypoints", wps);
            route.put("estimated_length_km", estimateLengthKm(wps));
            route.put("start", firstPointAsStart(wps));  // ★ 关键
            route.put("polyline", wps);                  // ★ 关键
            route.put("anchors", anchorsFromWaypoints(wps)); // ★ 关键
            if (!Double.isNaN(targetKm)) route.put("target_km", targetKm);
        } catch (Throwable ignore) {}

        routes.put(route);
        return routes;
    }

    private static JSONObject firstPointAsStart(JSONArray wps) {
        if (wps == null || wps.length() == 0) return null;
        JSONObject p0 = wps.optJSONObject(0);
        if (p0 == null) return null;
        double lat = optDouble(p0, "lat", "latitude", "y");
        double lng = optDouble(p0, "lng", "lon", "longitude", "x");
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        JSONObject st = new JSONObject();
        try {
            st.put("lat", lat).put("lng", lng);
        } catch (Throwable ignore) {}
        return st;
    }

    private static JSONArray anchorsFromWaypoints(JSONArray wps) {
        JSONArray arr = new JSONArray();
        if (wps == null) return arr;
        for (int i = 0; i < wps.length(); i++) {
            JSONObject wp = wps.optJSONObject(i);
            if (wp == null) continue;
            String name = optStringAny(wp, "name", "title", "poiName", "label");
            double lat = optDouble(wp, "lat", "latitude", "y");
            double lng = optDouble(wp, "lng", "lon", "longitude", "x");
            if (name != null && !name.isEmpty() && !Double.isNaN(lat) && !Double.isNaN(lng)) {
                try {
                    arr.put(new JSONObject().put("name", name).put("lat", lat).put("lng", lng));
                } catch (Throwable ignore) {}
            }
        }
        return arr;
    }


    /** 估算总长度（km）：相邻 waypoint 直线距离求和；至少两点才有长度 */
    private static double estimateLengthKm(JSONArray wps) {
        if (wps == null || wps.length() < 2) return 0.0;
        double sum = 0.0;
        JSONObject prev = wps.optJSONObject(0);
        double plat = optDouble(prev, "lat", "latitude", "y");
        double plng = optDouble(prev, "lng", "lon", "longitude", "x");
        for (int i = 1; i < wps.length(); i++) {
            JSONObject cur = wps.optJSONObject(i);
            double clat = optDouble(cur, "lat", "latitude", "y");
            double clng = optDouble(cur, "lng", "lon", "longitude", "x");
            if (isFinite(plat) && isFinite(plng) && isFinite(clat) && isFinite(clng)) {
                sum += haversineKm(plat, plng, clat, clng);
            }
            plat = clat; plng = clng;
        }
        return round1(sum); // 一位小数足够评审
    }

    private static boolean isFinite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private static double optDouble(JSONObject obj, String... keys) {
        if (obj == null) return Double.NaN;
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

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0088; // 平均地球半径 (km)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private static double round1(double x) { return Math.round(x * 10.0) / 10.0; }



    private static String optStringAny(JSONObject obj, String... keys) {
        if (obj == null) return null;
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

    /** 从对话中抽出用户目标长度（若无则返回 NaN）。你有现成方法就替换它 */
    private static double extractTargetKmFromDialog(Agent gen) {
        try {
            // TODO: 这里放你实际的解析；临时返回 NaN 表示“未知”
            return Double.NaN;
        } catch (Throwable ignore) {
            return Double.NaN;
        }
    }



    /** DETAIL → EVALUATOR：打印 routes 轻量快照（含 cell_path & waypoints 预览） */
    /** DETAIL → EVALUATOR：仅打印最终 waypoints 预览（不再包含 routes 等其他字段） */
    private void pipeDetailToEvaluatorLog(Agent.Msg mDet) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray wps = (mDet != null) ? mDet.waypoints : null;
        if (wps == null || wps.length() == 0) {
            out.put("_note", "no waypoints from DETAIL");
        } else {
            JSONArray kept = new JSONArray();
            int wlim = Math.min(WAYPOINT_PREVIEW_MAX, wps.length());
            for (int i = 0; i < wlim; i++) {
                JSONObject wp = wps.optJSONObject(i);
                if (wp == null) continue;
                JSONObject lite = new JSONObject();
                if (wp.has("lat"))  lite.put("lat", wp.optDouble("lat"));
                if (wp.has("lng"))  lite.put("lng", wp.optDouble("lng"));
                if (wp.has("name")) lite.put("name", wp.optString("name"));
                kept.put(lite);
            }
            if (wps.length() > wlim) {
                kept.put(new JSONObject().put("_omitted_waypoints", (wps.length() - wlim)));
            }
            out.put("waypoints", kept);
        }
        logChunked(TAG, "[DETAIL→EVALUATOR]\n" + pretty(out), ROUTE_LOG_CHUNK);
    }


    // ====================== 控制帧判定 & 反馈注入 ======================

    /** Evaluator 回退控制帧：waypoints 仅 1 个对象，且含 "_decision":"revise" */
    private static boolean isReviseControl(JSONArray wps) {
        if (wps == null || wps.length() != 1) return false;
        JSONObject o = wps.optJSONObject(0);
        return o != null && "revise".equalsIgnoreCase(o.optString("_decision",""));
    }

    /** 将 Evaluator 反馈写回 RouteGenAgent.dialogForRoute（若可用），供 DETAIL 的 GPT 感知 */
    /** 将 Evaluator 反馈写回 RouteGenAgent.dialogForRoute（若可用），供 DETAIL 的 GPT 感知 */
    private static void appendFeedbackToDialog(Agent gen, JSONObject fb) {
        try {
            if (!(gen instanceof RouteGenAgent) || fb == null) return;
            RouteGenAgent g = (RouteGenAgent) gen;

            StringBuilder sb = new StringBuilder();

            // --- 1) 回退理由（必填） ---
            JSONArray reasons = fb.optJSONArray("reasons");
            if (reasons != null && reasons.length() > 0) {
                sb.append(" reasons=").append(reasons);
            }
            String notes = fb.optString("notes", "");
            if (notes != null && !notes.isEmpty()) {
                sb.append(" notes=").append(notes);
            }

            // --- 2) 诊断信息（可选） ---
            JSONObject violations = fb.optJSONObject("violations");
            if (violations != null) {
                if (violations.has("missing_anchors")) sb.append(" missing_anchors=").append(violations.optJSONArray("missing_anchors"));
                if (violations.has("off_intent_names")) sb.append(" off_intent_names=").append(violations.optJSONArray("off_intent_names"));
                if (violations.has("length_km"))        sb.append(" length_km=").append(violations.optDouble("length_km"));
                if (violations.has("target_km"))        sb.append(" target_km=").append(violations.optDouble("target_km"));
                if (violations.has("length_delta_km"))  sb.append(" length_delta_km=").append(violations.optDouble("length_delta_km"));
                if (violations.has("min_turn_angle_deg")) sb.append(" min_turn_angle_deg=").append(violations.optDouble("min_turn_angle_deg"));
            }

            // --- 3) 可执行建议（供 DETAIL 参考，可选） ---
            JSONObject actions = fb.optJSONObject("actions");
            if (actions != null) {
                if (actions.has("add_poi_keywords"))    sb.append(" add=").append(actions.optJSONArray("add_poi_keywords"));
                if (actions.has("avoid_poi_keywords"))  sb.append(" avoid=").append(actions.optJSONArray("avoid_poi_keywords"));
                if (actions.has("prefer_loop"))         sb.append(" loop=").append(actions.optBoolean("prefer_loop"));
                if (actions.has("target_km"))           sb.append(" target_km=").append(actions.optDouble("target_km"));
                if (actions.has("force_include_names")) sb.append(" include=").append(actions.optJSONArray("force_include_names"));
                if (actions.has("force_exclude_names")) sb.append(" exclude=").append(actions.optJSONArray("force_exclude_names"));
            }

            String line = "\nUSER: [EVALUATOR_FEEDBACK]" + sb.toString();
            String old = g.dialogForRoute == null ? "" : g.dialogForRoute;
            g.dialogForRoute = old + line;
        } catch (Throwable ignore) {}
    }


    /** 从 DETAIL 或 GEN 实例上反射获取“同一个 cell” */
    private static Grid.Cell resolveCurrentCell(Agent detail, Agent gen) {
        Grid.Cell c = reflectCell(detail, "lastCell");
        if (c != null) return c;
        c = reflectCell(gen, "lastCell");
        if (c != null) return c;
        c = reflectCell(gen, "selectedCell");
        if (c != null) return c;
        c = reflectCell(gen, "bestCell");
        return c;
    }

    private static Grid.Cell reflectCell(Object obj, String field) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Grid.Cell) return (Grid.Cell) v;
        } catch (Throwable ignore) {}
        return null;
    }

    private LatLng extractUserLoc(Agent gen) {
        try {
            if (gen instanceof RouteGenAgent) {
                return ((RouteGenAgent) gen).userlocation;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // ====================== 物化：route → final waypoints ======================

    /** 将单条 route 转为最终 waypoints：start + polyline（尽量带上名称） */
    private static JSONArray buildFinalWaypointsFromRoute(JSONObject route, LatLng fallbackStart) throws JSONException {
        JSONArray wps = new JSONArray();
        if (route == null) return wps;

        // start
        JSONObject start = route.optJSONObject("start");
        if (start != null && start.has("lat") && start.has("lng")) {
            wps.put(new JSONObject().put("name","start")
                    .put("lat", start.optDouble("lat")).put("lng", start.optDouble("lng")));
        } else if (fallbackStart != null) {
            wps.put(new JSONObject().put("name","start")
                    .put("lat", fallbackStart.latitude).put("lng", fallbackStart.longitude));
        }

        // 名称索引（来自 anchors/stops）
        NameLookup lookup = new NameLookup();
        lookup.ingest(route.optJSONArray("anchors"));
        lookup.ingest(route.optJSONArray("stops"));

        // polyline
        JSONArray line = route.optJSONArray("polyline");
        if (line != null) {
            for (int i = 0; i < line.length(); i++) {
                JSONObject p = line.optJSONObject(i);
                if (p == null) continue;
                double lat = p.optDouble("lat", Double.NaN);
                double lng = p.optDouble("lng", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                String nm = lookup.nameFor(lat, lng);
                JSONObject wp = new JSONObject().put("lat", lat).put("lng", lng);
                if (nm != null && !nm.isEmpty()) wp.put("name", nm);
                wps.put(wp);
            }
        }
        return wps;
    }

    private static class NameLookup {
        private static final double EPS = 1e-5;
        private final java.util.List<Entry> entries = new java.util.ArrayList<>();
        void ingest(JSONArray pts) {
            if (pts == null) return;
            for (int i = 0; i < pts.length(); i++) {
                JSONObject o = pts.optJSONObject(i);
                if (o == null) continue;
                double lat = o.optDouble("lat", Double.NaN);
                double lng = o.optDouble("lng", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                String name = o.optString("name", "");
                if (!name.isEmpty()) entries.add(new Entry(lat, lng, name));
            }
        }
        String nameFor(double lat, double lng) {
            for (Entry e : entries) {
                if (Math.abs(e.lat - lat) < EPS && Math.abs(e.lng - lng) < EPS) return e.name;
            }
            return null;
        }
        static class Entry { final double lat,lng; final String name;
            Entry(double lat,double lng,String name){this.lat=lat;this.lng=lng;this.name=name;}
        }
    }

    // ====================== 日志 & 兜底 ======================

    private void logStageDone(String stage, Agent.Msg out) {
        try {
            int wpsLen = (out != null && out.waypoints != null) ? out.waypoints.length() : -1;
            int hasCell = (out != null && out.cell != null) ? 1 : 0;
            Log.i(TAG, stage + " ok | waypoints=" + wpsLen + " cell=" + hasCell);
        } catch (Throwable t) {
            Log.w(TAG, stage + " log failed: " + t.getMessage());
        }
    }

    private void logSeed(Agent.Msg seed) {
        try {
            if (seed == null) { Log.d(TAG, "seed: <null>"); return; }
            if (seed.cell != null) {
            } else if (seed.waypoints != null) {
                Log.d(TAG, "seed: waypoints count=" + seed.waypoints.length());
            } else {
                Log.d(TAG, "seed: <empty Msg>");
            }
        } catch (Throwable t) {
            Log.w(TAG, "logSeed failed: " + t.getMessage());
        }
    }

    /** GEN → DETAIL：打印 cell 的 anchors 预览与 tags 概览 */
    private void pipeGenToDetailLog(Agent.Msg mGen) throws JSONException {
        // 空入参
        if (mGen == null || (mGen.cell == null && (mGen.waypoints == null || mGen.waypoints.length() == 0))) {
            logChunked(TAG, "[GEN→DETAIL] <null cell>", ROUTE_LOG_CHUNK);
            return;
        }

        // 情况 A：GEN 以新的协议输出到了 waypoints（数组里包含若干个 cell 对象，和一个 {cell_path:[...]} 对象）
        if (mGen.waypoints != null && mGen.waypoints.length() > 0) {
            JSONArray path = null;
            for (int i = 0; i < mGen.waypoints.length(); i++) {
                JSONObject o = mGen.waypoints.optJSONObject(i);
                if (o == null) continue;

                // 打印 cell 对象
                if (o.has("cell_id") && o.has("center")) {
                    JSONObject out = new JSONObject()
                            .put("cell_id", o.optInt("cell_id", -1))
                            .put("center", o.optJSONObject("center"))
                            .put("anchor", o.optJSONArray("anchor") != null ? o.optJSONArray("anchor") : new JSONArray())
                            .put("tags",   o.optJSONArray("tags")   != null ? o.optJSONArray("tags")   : new JSONArray());
                    logChunked(TAG, "[GEN→DETAIL]\n" + pretty(out), ROUTE_LOG_CHUNK);
                }

                // 记录 cell_path（可选打印）
                if (o.has("cell_path")) path = o.optJSONArray("cell_path");
            }
            // 如需把 cell_path 也打印出来，解除注释：
            // if (path != null) logChunked(TAG, "[GEN→DETAIL]\n" + pretty(new JSONObject().put("cell_path", path)), ROUTE_LOG_CHUNK);
            return;
        }

        // 情况 B：兼容老协议：只有一个 cell
        Grid.Cell c = mGen.cell;
        JSONObject out = new JSONObject()
                .put("cell_id", c.id)
                .put("center", new JSONObject().put("lat", c.centerLat).put("lng", c.centerLng))
                .put("anchor", c.anchor != null ? c.anchor : new JSONArray())
                .put("tags",   c.tags   != null ? c.tags   : new JSONArray());
        logChunked(TAG, "[GEN→DETAIL]\n" + pretty(out), ROUTE_LOG_CHUNK);
    }


    // ★ 开关：是否打印“完整管道载荷（RAW）”
    private static final boolean FULL_PIPE_LOG = true; // 调试时设为 true；上线请改回 false
    private void pipeGenToDetailLogRaw(Agent.Msg toDetail) {
        JSONArray body = (toDetail != null) ? toDetail.waypoints : null;
        logChunked(TAG, "[GEN→DETAIL:RAW]\n" + (body != null ? body.toString() : "[]"), ROUTE_LOG_CHUNK);
    }

    private void pipeDetailToEvaluatorLogRaw(Agent.Msg mDet) {
        JSONArray body = (mDet != null) ? mDet.waypoints : null;
        logChunked(TAG, "[DETAIL→EVALUATOR:RAW]\n" + (body != null ? body.toString() : "[]"), ROUTE_LOG_CHUNK);
    }

    private void pipeEvaluatorOutputLogRaw(Agent.Msg mEva) {
        JSONArray body = (mEva != null) ? mEva.waypoints : null;
        logChunked(TAG, "[EVALUATOR→OUTPUT:RAW]\n" + (body != null ? body.toString() : "[]"), ROUTE_LOG_CHUNK);
    }




    /** EVALUATOR 输出的最终或控制帧预览 */
    /** EVALUATOR 输出的最终或控制帧预览（增强：回退理由） */
    private void pipeEvaluatorOutputLog(Agent.Msg mEva) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray wps = (mEva != null) ? mEva.waypoints : null;

        if (wps == null) {
            out.put("_note", "no final waypoints");
        } else if (isReviseControl(wps)) {
            JSONObject ctrl = wps.optJSONObject(0);
            JSONObject fb   = ctrl != null ? ctrl.optJSONObject("feedback") : null;

            JSONObject preview = new JSONObject()
                    .put("_decision", "revise")
                    .put("route_index", ctrl != null ? ctrl.optInt("route_index", -1) : -1);

            // 只取前 3 条理由，避免日志爆炸
            if (fb != null) {
                if (fb.has("notes")) preview.put("notes", fb.optString("notes", ""));
                JSONArray reasons = fb.optJSONArray("reasons");
                if (reasons != null && reasons.length() > 0) {
                    JSONArray top = new JSONArray();
                    int n = Math.min(3, reasons.length());
                    for (int i = 0; i < n; i++) top.put(reasons.opt(i));
                    preview.put("reasons", top);
                }
            }
            out.put("control", preview);
        } else {
            JSONArray kept = new JSONArray();
            int wlim = Math.min(WAYPOINT_PREVIEW_MAX, wps.length());
            for (int i = 0; i < wlim; i++) {
                JSONObject wp = wps.optJSONObject(i);
                if (wp == null) continue;
                JSONObject lite = new JSONObject();
                if (wp.has("lat"))  lite.put("lat", wp.optDouble("lat"));
                if (wp.has("lng"))  lite.put("lng", wp.optDouble("lng"));
                if (wp.has("name")) lite.put("name", wp.optString("name"));
                kept.put(lite);
            }
            if (wps.length() > wlim) {
                kept.put(new JSONObject().put("_omitted_waypoints", (wps.length() - wlim)));
            }
            out.put("final_waypoints", kept);
        }

        logChunked(TAG, "[EVALUATOR→OUTPUT]\n" + pretty(out), ROUTE_LOG_CHUNK);
    }


    private static JSONArray previewAnchors(JSONArray anchors, int maxN) throws JSONException {
        JSONArray arr = new JSONArray();
        if (anchors == null) return arr;
        int n = Math.min(maxN, anchors.length());
        for (int i = 0; i < n; i++) {
            JSONObject a = anchors.optJSONObject(i);
            if (a == null) continue;
            JSONObject lite = new JSONObject();
            if (a.has("name")) lite.put("name", a.optString("name",""));
            if (a.has("lat"))  lite.put("lat",  a.optDouble("lat"));
            if (a.has("lng"))  lite.put("lng",  a.optDouble("lng"));
            arr.put(lite);
        }
        if (anchors.length() > n) {
            arr.put(new JSONObject().put("_omitted_anchors", anchors.length() - n));
        }
        return arr;
    }

    private boolean isEmptyFinal(Agent.Msg msg) {
        try {
            if (msg == null) return true;
            JSONArray wps = msg.waypoints;
            return wps == null || wps.length() == 0 || isReviseControl(wps);
        } catch (Throwable t) {
            Log.w(TAG, "isEmptyFinal: " + t.getMessage());
            return true;
        }
    }

    /** 用 seed 或 GEN 的 cell 做最简路径兜底：start=cell.center，随后依次经过 anchors。 */
    private Agent.Msg buildFallbackFinal(Agent.Msg seed, String reason) {
        try {
            Grid.Cell c = (seed != null) ? seed.cell : null;
            JSONArray wps = new JSONArray();

            if (c != null) {
                // start = center
                wps.put(new JSONObject()
                        .put("name", "start")
                        .put("lat", c.centerLat)
                        .put("lng", c.centerLng));

                // 追加 anchors（按出现顺序）
                JSONArray a = c.anchor;
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject p = a.optJSONObject(i);
                        if (p == null) continue;
                        if (!p.has("lat") || !p.has("lng")) continue;
                        JSONObject wp = new JSONObject()
                                .put("lat", p.optDouble("lat"))
                                .put("lng", p.optDouble("lng"));
                        if (p.has("name")) wp.put("name", p.optString("name","anchor"));
                        wps.put(wp);
                    }
                }
            } // else 返回空 wps

            Log.w(TAG, "fallback final: " + reason);
            return Agent.Msg.send(Agent.Role.EVALUATOR, Agent.Role.EVALUATOR, wps);
        } catch (Throwable t) {
            Log.w(TAG, "buildFallbackFinal failed: " + t.getMessage());
            return Agent.Msg.send(Agent.Role.EVALUATOR, Agent.Role.EVALUATOR, new JSONArray());
        }
    }

    // ====================== 小工具 ======================

    private static String pretty(JSONObject o) {
        try { return o.toString(2); } catch (Exception e) { return String.valueOf(o); }
    }

    private static void logChunked(String tag, String msg, int chunkSize) {
        if (msg == null) { Log.e(tag, "null"); return; }
        int len = msg.length();
        if (len <= chunkSize) { Log.e(tag, msg); return; }
        int parts = (len + chunkSize - 1) / chunkSize;
        for (int i = 0; i < parts; i++) {
            int start = i * chunkSize, end = Math.min(start + chunkSize, len);
            Log.e(tag, String.format("(part %d/%d) %s", i + 1, parts, msg.substring(start, end)));
        }
    }
}
