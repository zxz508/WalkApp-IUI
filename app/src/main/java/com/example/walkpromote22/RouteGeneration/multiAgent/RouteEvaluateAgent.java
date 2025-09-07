package com.example.walkpromote22.RouteGeneration.multiAgent;

import android.util.Log;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;
import com.example.walkpromote22.ChatbotFragments.ChatbotResponseListener;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * RouteEvaluateAgent（评估者，带“回退理由”）
 *
 * 输入：Msg(Role.DETAIL, Role.EVALUATOR, routes) —— routes 在 Msg.waypoints（对象数组）
 * 输出两种其一：
 *  1) 接受：Msg(Role.EVALUATOR, Role.EVALUATOR, waypoints)  // 最终点序列（start + polyline）
 *  2) 要求回退（控制帧）：Msg(Role.EVALUATOR, Role.EVALUATOR,
 *         [ { "_decision":"revise", "route_index":<int>, "feedback":{ ...含详细理由... } } ])
 *
 * 说明：
 *  - “是否回退”由 Coordinator 解析控制帧决定；Evaluator 本身不再调用 DETAIL。
 *  - 当 decision=revise 时，强制包含 feedback.reasons（数组）与 feedback.notes（摘要），
 *    以及若干结构化诊断与改进建议（见 buildSystemPrompt() 中的 schema）。
 */
public class RouteEvaluateAgent implements Agent {

    public final RouteGenAgent genAgent; // 只读：提供 dialogForRoute / userlocation

    public RouteEvaluateAgent(RouteGenAgent genAgent) {
        this.genAgent = genAgent;
    }

    @Override public Role role() { return Role.EVALUATOR; }

    @Override
    public CompletableFuture<Msg> onMessage(Msg in, ChatbotHelper llm) {
        CompletableFuture<Msg> fut = new CompletableFuture<>();
        try {
            // 1) 读取 DETAIL 产出的 routes（在 Msg.waypoints）
            JSONArray routes = (in == null) ? null : in.waypoints;
            if (routes == null || routes.length() == 0) {
                fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, new JSONArray()));
                return fut;
            }

            // 2) 提取上下文
            String dialog  = (genAgent != null) ? genAgent.dialogForRoute : "";
            String input   = extractLastUserUtterance(dialog);
            String history = trimHistory(dialog, 6000);

            // 3) 组装给 LLM 的消息
            JSONObject payload = new JSONObject()
                    .put("user_input", input == null ? "" : input)
                    .put("history", history == null ? "" : history)
                    .put("routes", slimRoutesForLLM(routes));

            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", buildSystemPrompt()))
                    .put(new JSONObject().put("role", "user").put("content", payload.toString()));

            String turnId = "EVALUATOR-" + System.currentTimeMillis();

            Log.e("TAG","turnId3="+turnId);
            Log.e("TAG","message3="+messages);
            // 4) 调 LLM 做判定 + 生成“回退理由”
            llm.sendMessage(turnId, messages, new ChatbotResponseListener() {
                @Override public void onResponse(String content) {
                    try {
                        JSONObject out = firstJson(content);
                        JSONObject fin = out.optJSONObject("final");
                        if (fin == null) fin = out;

                        String decision = fin.optString("decision", "");
                        int ridx = fin.optInt("route_index", fallbackPickIndex(routes));

                        if ("revise".equalsIgnoreCase(decision)) {
                            JSONObject fb = fin.optJSONObject("feedback");
                            if (fb == null) fb = new JSONObject();

                            // 强制齐备 reasons / notes（notes 便于 Coordinator 直接回写 history）
                            ensureNotesFromReasons(fb);

                            JSONObject ctrl = new JSONObject()
                                    .put("_decision", "revise")
                                    .put("route_index", Math.max(0, ridx))
                                    .put("feedback", fb);
                            JSONArray controlFrame = new JSONArray().put(ctrl);
                            fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, controlFrame));
                            return;
                        }

                        // 默认：接受（物化最终路径）
                        JSONArray wps = buildFinalWaypoints(routes.optJSONObject(Math.max(0, ridx)));
                        fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, wps));

                    } catch (Throwable t) {
                        try {
                            JSONArray wps = buildFinalWaypoints(routes.optJSONObject(fallbackPickIndex(routes)));
                            fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, wps));
                        } catch (Throwable ignore) {
                            fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, new JSONArray()));
                        }
                    }
                }

                @Override public void onFailure(String error) {
                    try {
                        JSONArray wps = buildFinalWaypoints(routes.optJSONObject(fallbackPickIndex(routes)));
                        fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, wps));
                    } catch (Throwable ignore) {
                        fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, new JSONArray()));
                    }
                }
            });

        } catch (Throwable t) {
            fut.complete(Msg.send(Role.EVALUATOR, Role.EVALUATOR, new JSONArray()));
        }
        return fut;
    }

    // ===================== Prompt：强制“回退理由”与结构化反馈 =====================

    private String buildSystemPrompt() {
        return ""
                + "你是路线评估者（Evaluator）。" +
                " + \"输出（严格 JSON，仅输出 JSON）：\\n\"\n" +
                "                + \"{ \\\"final\\\": {\\n\"\n" +
                "                + \"   \\\"decision\\\": \\\"accept|revise\\\",\\n\"\n" +
                "                + \"   \\\"route_index\\\": <int>,\\n\"\n" +
                "                + \"   \\\"name\\\": \\\"string\\\",\\n\"\n" +
                "                + \"   \\\"reason\\\": \\\"string\\\",\\n\"\n" +
                "                + \"   \\\"feedback\\\": { ... 如上结构 ... }   // 当 decision=revise 必填\\n\"\n" +
                "                + \"}"
                + "输入：用户请求与历史、一条路线。\n"
                + "目标：在 ACCEPT 或 REVISE 中二选一。\n"
                + "评估准则：\n"
                + "  1) 线路应平滑（尽量减少急转），经纬度变化有规律；\n"
                + "  2) 未指定距离时，2~5km 通常适合步行；\n"
                + "  3) POI 仅在“有助于平滑且贴合用户需求/POI 类别与名称”时加入；\n"
                + "  4) 名称/类别与用户意图匹配度优先（例如用户想去 KFC）。\n"
                + "  5) 当用户表明要直接去往某地的时候，路线只因该包含起点和重点两个waypoins。\n"
                + "\n"
                +"优秀路线：userRequest:generate a route to the nearest KFC,"
                +" Route:[{\"name\":\"Beginning Point\",\"lat\":30.651018,\"lng\":114.191037},{\"name\":\"肯德基(东西湖万达店)\",\"lat\":30.637537,\"lng\":114.178229}]  "
                + "当你选择 REVISE 时（尽量当路线十分不合理的时候再选择），必须给出【回退理由】与【可执行建议】（用于 DETAIL 二次挑选 POI）：\n"
                + "feedback 结构（尽量填写，不清楚则省略字段）：\n"
                + "{\n"
                + "  \"reasons\": [\"理由1\",\"理由2\",...],   // 必填：清晰说明不满意的原因\n"
                + "  \"notes\": \"一句话摘要（便于直接写回对话历史）\", // 必填\n"
                + "  \"violations\": {                        // 诊断：哪里不满足准则\n"
                + "     \"missing_anchors\": [\"A1\", ...],   // 缺失/未覆盖的锚点名称或 ID\n"
                + "     \"off_intent_names\": [\"不贴合的POI名称\"],\n"
                + "     \"length_km\": 3.1,                   // 当前长度\n"
                + "     \"target_km\": 3.5,                   // 期望/建议目标\n"
                + "     \"length_delta_km\": -0.4,            // 与目标差异（可正可负）\n"
                + "     \"min_turn_angle_deg\": 52,           // 最小转角（越小越急）\n"
                + "     \"sharp_turns\": [ {\"lat\":...,\"lng\":...,\"angle_deg\":...,\"note\":\"...\"} ]\n"
                + "  },\n"
                + "  \"actions\": {                           // 建议的可执行方向（供 DETAIL 使用）\n"
                + "     \"add_poi_keywords\": [\"park\",\"green path\",...],\n"
                + "     \"avoid_poi_keywords\": [\"highway\",\"construction\",...],\n"
                + "     \"prefer_loop\": true,\n"
                + "     \"target_km\": 3.5,\n"
                + "     \"force_include_names\": [\"东湖绿道\",...],\n"
                + "     \"force_exclude_names\": [\"三环快速路\",...]\n"
                + "  }\n"
                + "}\n"
                + "\n"
                + "输出（严格 JSON，仅输出 JSON）：\n"
                + "{ \"final\": {\n"
                + "   \"decision\": \"accept|revise\",\n"
                + "   \"route_index\": <int>,\n"
                + "   \"name\": \"string\",\n"
                + "   \"reason\": \"string\",\n"
                + "   \"feedback\": { ... 如上结构 ... }   // 当 decision=revise 必填\n"
                + "} }";
    }

    // ===================== 工具：routes 裁剪 / 构造输出 =====================

    /** 给 LLM 的精简 routes，避免 prompt 过大，同时保留必要上下文。 */
    private JSONArray slimRoutesForLLM(JSONArray routes) throws JSONException {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            if (r == null) continue;
            JSONObject o = new JSONObject()
                    .put("idx", i)
                    .put("type", r.optString("type",""))
                    .put("estimated_length_km", r.optDouble("estimated_length_km", 0.0))
                    .put("notes", r.optString("notes",""))
                    .put("anchors", r.optJSONArray("anchors") == null ? new JSONArray() : r.optJSONArray("anchors"))
                    .put("stops",   r.optJSONArray("stops")   == null ? new JSONArray() : r.optJSONArray("stops"))
                    .put("polyline_preview", previewPolyline(r.optJSONArray("polyline"), 60));
            o.put("anchor_names", collectNames(o.optJSONArray("anchors")));
            o.put("stop_names",   collectNames(o.optJSONArray("stops")));
            arr.put(o);
        }
        return arr;
    }

    private JSONArray previewPolyline(JSONArray line, int maxN) throws JSONException {
        JSONArray kept = new JSONArray();
        if (line == null) return kept;
        int n = Math.min(maxN, line.length());
        for (int i = 0; i < n; i++) {
            JSONObject p = line.optJSONObject(i);
            if (p == null) continue;
            JSONObject lite = new JSONObject();
            if (p.has("lat")) lite.put("lat", p.optDouble("lat"));
            if (p.has("lng")) lite.put("lng", p.optDouble("lng"));
            kept.put(lite);
        }
        if (line.length() > n) kept.put(new JSONObject().put("_omitted_points", line.length() - n));
        return kept;
    }

    private JSONArray collectNames(JSONArray pts) {
        JSONArray names = new JSONArray();
        if (pts == null) return names;
        for (int i = 0; i < pts.length(); i++) {
            JSONObject p = pts.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "");
            if (!name.isEmpty()) names.put(name);
        }
        return names;
    }

    /** 选中 route → 最终 waypoints：start + polyline（尽量带名称） */
    private JSONArray buildFinalWaypoints(JSONObject route) throws JSONException {
        JSONArray wps = new JSONArray();
        if (route == null) return wps;

        // start
        JSONObject start = route.optJSONObject("start");
        if (start != null && start.has("lat") && start.has("lng")) {
            wps.put(new JSONObject().put("name","start")
                    .put("lat", start.optDouble("lat"))
                    .put("lng", start.optDouble("lng")));
        } else {
            LatLng loc = (genAgent != null) ? genAgent.userlocation : null;
            if (loc != null) {
                wps.put(new JSONObject().put("name","start")
                        .put("lat", loc.latitude)
                        .put("lng", loc.longitude));
            }
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

    // ===================== 解析/兜底 =====================

    private JSONObject firstJson(String content) throws JSONException {
        if (content == null) throw new JSONException("empty");
        int l = content.indexOf('{');
        int r = content.lastIndexOf('}');
        if (l >= 0 && r > l) content = content.substring(l, r + 1);
        return new JSONObject(content);
    }

    private int fallbackPickIndex(JSONArray routes) {
        // 1) 优先 type = "smooth"
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            if (r != null && "smooth".equalsIgnoreCase(r.optString("type",""))) return i;
        }
        // 2) 次选距离在 2~5.5km 内、接近 3.5km
        int best = 0;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            double d = (r == null) ? 0.0 : r.optDouble("estimated_length_km", 0.0);
            double score = Math.abs(d - 3.5);
            if (d >= 2.0 && d <= 5.5 && score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private String extractLastUserUtterance(String dialog) {
        if (dialog == null) return "";
        String[] lines = dialog.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (s.startsWith("USER:")) return s.substring(5).trim();
        }
        return "";
    }

    private String trimHistory(String dialog, int maxChars) {
        if (dialog == null) return "";
        if (dialog.length() <= maxChars) return dialog;
        return dialog.substring(dialog.length() - maxChars);
    }

    private void ensureNotesFromReasons(JSONObject fb) {
        try {
            if (fb == null) return;
            String notes = fb.optString("notes", "");
            if (notes != null && !notes.isEmpty()) return;

            JSONArray reasons = fb.optJSONArray("reasons");
            if (reasons != null && reasons.length() > 0) {
                StringBuilder sb = new StringBuilder("回退原因：");
                int n = Math.min(5, reasons.length());
                for (int i = 0; i < n; i++) {
                    String r = String.valueOf(reasons.opt(i));
                    if (r == null || r.isEmpty()) continue;
                    if (i > 0) sb.append("；");
                    sb.append(r);
                }
                fb.put("notes", sb.toString());
            }
        } catch (Throwable ignore) {}
    }

    // ===================== 名称匹配辅助 =====================

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
}
