package com.example.walkpromote22.RouteGeneration.multiAgent;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;
import com.example.walkpromote22.ChatbotFragments.ChatbotResponseListener;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * RouteDetailAgent（GPT 驱动：锚点必选，是否插入 POI 由 GPT 依据“平滑+类别+名字+用户需求”判断）
 *
 * 输入：Msg(Role.GEN, Role.DETAIL, Grid.Cell cell)
 * 输出：Msg(Role.DETAIL, Role.SELECTOR, JSONArray routes)
 *
 * 约定：
 * - 起点：直接读取 genAgent.userlocation（若为 null，则回退 cell.center）。
 * - GPT 输出严格 JSON：
 *   {
 *     "sequence":[
 *        {"type":"anchor","name":"...","lat":..,"lng":..},
 *        {"type":"poi","name":"...","lat":..,"lng":..},
 *        ...
 *     ],
 *     "notes":"..."
 *   }
 *   其中 sequence 必须覆盖所有锚点；POI 仅在“有助平滑且与需求/类别契合”时出现。
 */
public class RouteDetailAgent implements Agent {

    private final RouteGenAgent genAgent;

    public RouteDetailAgent(RouteGenAgent genAgent) {
        this.genAgent = genAgent;
    }

    @Override public Role role() { return Role.DETAIL; }

    @Override
    public CompletableFuture<Msg> onMessage(Msg in, ChatbotHelper llm) {
        CompletableFuture<Msg> fut = new CompletableFuture<>();
        try {
            // ===== A) 读取 GEN → DETAIL 的输入（cells + cell_path 或兼容单 cell） =====
            JSONArray cellsFromMsg = new JSONArray();
            JSONArray cellPath = new JSONArray();
            Grid.Cell legacyCell = (in == null) ? null : in.cell;

            if (in != null && in.waypoints != null && in.waypoints.length() > 0) {
                for (int i = 0; i < in.waypoints.length(); i++) {
                    JSONObject o = in.waypoints.optJSONObject(i);
                    if (o == null) continue;
                    if (o.has("cell_path")) cellPath = o.optJSONArray("cell_path");
                    else if (o.has("cell_id") && o.has("center")) cellsFromMsg.put(o);
                }
            } else if (legacyCell != null) {
                cellsFromMsg.put(cellToJson(legacyCell));
                cellPath.put(legacyCell.id);
            }

            if (cellsFromMsg.length() == 0) {
                fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, new JSONArray())); // 只发空 waypoints
                return fut;
            }

            // ===== B) 计算 start =====
            LatLng start = findBeginingPoint(cellsFromMsg);
            if (start == null && genAgent != null && genAgent.userlocation != null) start = genAgent.userlocation;
            if (start == null) {
                JSONObject c0 = cellsFromMsg.optJSONObject(0);
                if (c0 != null) {
                    JSONObject ctr = c0.optJSONObject("center");
                    if (ctr != null && ctr.has("lat") && ctr.has("lng")) {
                        start = new LatLng(ctr.optDouble("lat"), ctr.optDouble("lng"));
                    }
                }
            }
            if (start == null) {
                fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, new JSONArray()));
                return fut;
            }

            // ===== C) 组装给 GPT 的 payload（包含 cell_path + cells） =====
            String userInput = extractLastUserUtterance(genAgent != null ? genAgent.dialogForRoute : null);
            String history   = trimHistory(genAgent != null ? genAgent.dialogForRoute : "", 6000);

            JSONObject payload = new JSONObject()
                    .put("user_input", userInput == null ? "" : userInput)
                    .put("history", history == null ? "" : history)
                    .put("start", new JSONObject().put("lat", start.latitude).put("lng", start.longitude))
                    .put("cell_path", cellPath)
                    .put("cells", cellsFromMsg);

            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", buildSystemPrompt()))
                    .put(new JSONObject().put("role", "user").put("content", payload.toString())); // content 必须是字符串

            final String turnId = "DETAIL-" + java.util.UUID.randomUUID();

            // ===== D) 调 GPT：仅取 waypoints，回传时只发 waypoints =====
            LatLng finalStart = start;
            llm.sendMessage(turnId, messages, new ChatbotResponseListener() {
                @Override public void onResponse(String content) {
                    try {
                        JSONObject parsed = safeExtractJson(content);

                        // 1) 首选新契约：直接给出的 waypoints
                        JSONArray wps = parsed.optJSONArray("waypoints");

                        // 2) 兼容旧契约：sequence → waypoints
                        if ((wps == null || wps.length() == 0) && parsed.has("sequence")) {
                            JSONArray seq = parsed.optJSONArray("sequence");
                            wps = sequenceToWaypoints(seq);
                        }

                        // 3) 兜底：仅 anchors 串起来（包含 start）
                        if (wps == null || wps.length() == 0) {
                            wps = anchorsOnlyFromCells(cellsFromMsg, finalStart, "empty-waypoints");
                        }

                        // ★★ 只把 waypoints 传给 Evaluator ★★
                        fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, wps));

                    } catch (Throwable ex) {
                        // 解析失败兜底：anchors-only → waypoints
                        try {
                            JSONArray wps = anchorsOnlyFromCells(cellsFromMsg, finalStart, "detail-parse-fallback");
                            fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, wps));
                        } catch (Throwable ignore) {
                            fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, new JSONArray()));
                        }
                    }
                }

                @Override public void onFailure(String error) {
                    // 调用失败兜底：anchors-only → waypoints
                    try {
                        JSONArray wps = anchorsOnlyFromCells(cellsFromMsg, finalStart, "detail-llm-failure");
                        fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, wps));
                    } catch (Throwable ignore) {
                        fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, new JSONArray()));
                    }
                }
            });

        } catch (Throwable t) {
            // 前置异常兜底：发空 waypoints
            fut.complete(Msg.send(Role.DETAIL, Role.EVALUATOR, new JSONArray()));
        }
        return fut;
    }

    /** 在 cells 中寻找名为 "Begining Point" 的 anchor 作为起点；若找到返回其 LatLng，否则 null */
    private LatLng findBeginingPoint(JSONArray cells) {
        for (int i = 0; i < cells.length(); i++) {
            JSONObject c = cells.optJSONObject(i);
            if (c == null) continue;
            JSONArray as = c.optJSONArray("anchor");
            if (as == null) continue;
            for (int j = 0; j < as.length(); j++) {
                JSONObject a = as.optJSONObject(j);
                if (a == null) continue;
                if ("Begining Point".equalsIgnoreCase(a.optString("name",""))) {
                    if (a.has("lat") && a.has("lng")) {
                        return new com.amap.api.maps.model.LatLng(a.optDouble("lat"), a.optDouble("lng"));
                    }
                }
            }
        }
        return null;
    }

    /** 兼容旧契约：sequence[{type:anchor|poi,name,lat,lng}] → waypoints */
    private JSONArray sequenceToWaypoints(JSONArray seq) throws JSONException {
        JSONArray wps = new JSONArray();
        if (seq == null) return wps;
        for (int i = 0; i < seq.length(); i++) {
            JSONObject p = seq.optJSONObject(i);
            if (p == null) continue;
            if (!p.has("lat") || !p.has("lng")) continue;
            JSONObject wp = new JSONObject();
            if (p.has("name")) wp.put("name", p.optString("name"));
            wp.put("lat", p.optDouble("lat"));
            wp.put("lng", p.optDouble("lng"));
            wps.put(wp);
        }
        return wps;
    }

    /** 兜底：把所有 cell 的 anchors 串起来（去重），首点为 start */
    private JSONArray anchorsOnlyFromCells(JSONArray cells, LatLng start, String reason) {
        JSONArray wps = new JSONArray();
        try {
            if (start != null) {
                wps.put(new JSONObject().put("name","start").put("lat", start.latitude).put("lng", start.longitude));
            }
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (int i = 0; i < cells.length(); i++) {
                JSONObject c = cells.optJSONObject(i);
                if (c == null) continue;
                JSONArray as = c.optJSONArray("anchor");
                if (as == null) continue;
                for (int j = 0; j < as.length(); j++) {
                    JSONObject a = as.optJSONObject(j);
                    if (a == null) continue;
                    if (!a.has("lat") || !a.has("lng")) continue;
                    String key = a.optString("name","") + "@" + a.optDouble("lat") + "," + a.optDouble("lng");
                    if (seen.add(key)) {
                        JSONObject wp = new JSONObject().put("lat", a.optDouble("lat")).put("lng", a.optDouble("lng"));
                        if (a.has("name")) wp.put("name", a.optString("name"));
                        wps.put(wp);
                    }
                }
            }
            if (wps.length() == 0 && start != null) {
                wps.put(new JSONObject().put("lat", start.latitude).put("lng", start.longitude));
            }
            return wps;
        } catch (Throwable ignore) {
            return wps;
        }
    }

    /** 把 anchors-only 的点列封装成 route 对象，便于 Evaluator 评估与日志 */
    private JSONObject anchorsOnlyToRouteObj(JSONArray wps, JSONArray cellPath, String notes) throws JSONException {
        return new JSONObject()
                .put("type", "anchors-only")
                .put("cell_path", cellPath == null ? new JSONArray() : cellPath)
                .put("waypoints", wps == null ? new JSONArray() : wps)
                .put("estimated_length_km", Math.max(1, (cellPath == null ? 0 : cellPath.length())))
                .put("notes", notes);
    }


    // =============== Prompts ===============

    private String buildSystemPrompt() {
        return   "你是 DETAIL（路线细化者）。\n" +
                "输入 JSON：\n" +
                "【示例输入】:\n" +
                "{\n" +
                "  \"cell_id\": 1,\n" +
                "  \"center\": {\"lat\": ***, \"lng\": ***},\n" +
                "  \"pois\":[{\"name\":\"***\",\"lat\":***,\"lng\":***},{\"name\":\"***\",\"lat\":***,\"lng\":***},....]"+
                "  \"anchor\": [{\"name\":\"Begining Point\",\"lat\":***,\"lng\":***}\n" +
                "  ],\n" +
                "  \"tags\": []\n" +
                "}\n" +
                "{\n" +
                "  \"cell_id\": 45,\n" +
                "  \"center\": {\"lat\": 30.637429332375135, \"lng\": 114.17557905320324},\n" +
                "  \"pois\":[{\"name\":\"***\",\"lat\":***,\"lng\":***},{\"name\":\"***\",\"lat\":***,\"lng\":***},....]"+
                "  \"anchor\": [\n" +
                "    {\"name\":\"肯德基(东西湖万达店)\",\"lat\":30.637537,\"lng\":114.178229}\n" +
                "  ],\n" +
                "  \"tags\": []\n" +
                "}\n"+
                "请输出最终路线上要绘制的点序列 waypoints，规则：\n" +
                "  1) 锚点必须包含，且首个点必须是名称为 \"Begining Point\" 的起点；\n" +
                "  2) 仅当“有助于平滑/贴合用户需求与 POI 类别、名称”时，才从 POIs 中补点；\n" +
                "  3) 路线应平滑、经纬度变化有规律；避免穿越 avoidHint 所指范围；\n" +
                "  4) 输出坐标键一律为 lat/lng（不要使用 latitude/longitude）。\n" +
                "\n" +
                "仅输出严格 JSON（不得有多余文本）：\n" +
                "{\n" +
                "  \"waypoints\": [\n" +
                "    {\"name\":\"Begining Point\",\"lat\":...,\"lng\":...},\n" +
                "    {\"name\":\"...\",\"lat\":...,\"lng\":...}\n" +
                "  ]\n" +
                "}";
    }

    private JSONObject cellToJson(Grid.Cell cell) throws JSONException {
        // 直接使用你提供的结构
        return new JSONObject()
                .put("id", cell.id)
                .put("center", new JSONObject().put("lat", cell.centerLat).put("lng", cell.centerLng))
                .put("Length of side", "1_km")
                .put("tags", cell.tags == null ? new JSONArray() : cell.tags)
                .put("avoidHint", cell.avoidHint == null ? new JSONArray() : cell.avoidHint)
                .put("Anchor", cell.anchor == null ? new JSONArray() : cell.anchor)
                .put("POIs", cell.pois == null ? new JSONArray() : cell.pois);
    }

    // =============== Build route objects ===============

    private JSONObject buildRouteFromSequence(String type, Grid.Cell cell, LatLng start,
                                              JSONArray sequence, String notes) throws JSONException {
        JSONArray anchorsArr = new JSONArray();
        JSONArray stopsArr   = new JSONArray();
        JSONArray polyline   = new JSONArray();

        LatLng prev = start;
        double length = 0.0;

        for (int i = 0; i < sequence.length(); i++) {
            JSONObject node = sequence.optJSONObject(i);
            if (node == null) continue;
            String t = node.optString("type", "");
            String name = node.optString("name", "poi");
            double lat = node.optDouble("lat", Double.NaN);
            double lng = node.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;

            JSONObject out = new JSONObject()
                    .put("name", name)
                    .put("lat", lat)
                    .put("lng", lng)
                    .put("cell_id", cell.id)
                    .put("type", t);

            if ("anchor".equalsIgnoreCase(t)) anchorsArr.put(out);
            else                               stopsArr.put(out);

            polyline.put(new JSONObject().put("lat", lat).put("lng", lng));
            length += haversineKm(prev.latitude, prev.longitude, lat, lng);
            prev = new LatLng(lat, lng);
        }

        return new JSONObject()
                .put("type", type)
                .put("cell_id", cell.id)
                .put("start", new JSONObject().put("lat", start.latitude).put("lng", start.longitude))
                .put("anchors", anchorsArr)
                .put("stops", stopsArr)
                .put("polyline", polyline)
                .put("estimated_length_km", round1(length))
                .put("notes", notes == null ? "" : notes);
    }

    /** 兜底：仅按“距起点从近到远”的锚点顺序输出 anchors-only 路线 */
    private JSONObject buildAnchorsOnly(Grid.Cell cell, LatLng start, String notes) throws JSONException {
        List<JSONObject> anchors = new ArrayList<>();
        if (cell != null && cell.anchor != null) {
            for (int i = 0; i < cell.anchor.length(); i++) {
                JSONObject a = cell.anchor.optJSONObject(i);
                if (a != null && a.has("lat") && a.has("lng")) anchors.add(a);
            }
        }
        anchors.sort(Comparator.comparingDouble(a ->
                haversineKm(start.latitude, start.longitude,
                        a.optDouble("lat", cell.centerLat),
                        a.optDouble("lng", cell.centerLng))));

        JSONArray seq = new JSONArray();
        for (JSONObject a : anchors) {
            seq.put(new JSONObject()
                    .put("type", "anchor")
                    .put("name", a.optString("name","anchor"))
                    .put("lat",  a.optDouble("lat"))
                    .put("lng",  a.optDouble("lng")));
        }
        return buildRouteFromSequence("anchors-only", cell, start, seq, notes);
    }

    // =============== Dialog helpers ===============

    /** 取最后一条 USER: 内容作为 user_input */
    private String extractLastUserUtterance(String dialog) {
        if (dialog == null) return "";
        String[] lines = dialog.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (s.startsWith("USER:")) {
                return s.substring(5).trim();
            }
        }
        return "";
    }

    /** 限长 history */
    private String trimHistory(String dialog, int maxChars) {
        if (dialog == null) return "";
        if (dialog.length() <= maxChars) return dialog;
        return dialog.substring(dialog.length() - maxChars);
    }

    // =============== Utils ===============

    private JSONObject safeExtractJson(String content) throws JSONException {
        if (content == null) throw new JSONException("empty content");
        int l = content.indexOf('{');
        int r = content.lastIndexOf('}');
        if (l >= 0 && r > l) content = content.substring(l, r + 1);
        return new JSONObject(content);
    }

    private String keyOf(JSONObject o) {
        double lat = o.optDouble("lat", Double.NaN);
        double lng = o.optDouble("lng", Double.NaN);
        String name = o.optString("name", "");
        return name + "@" + lat + "," + lng;
    }

    private JSONObject findAnchorByKey(JSONArray anchors, String key) {
        for (int i = 0; i < anchors.length(); i++) {
            JSONObject a = anchors.optJSONObject(i);
            if (a == null) continue;
            if (keyOf(a).equals(key)) return a;
        }
        return null;
    }

    private static double haversineKm(double lat1,double lng1,double lat2,double lng2){
        double R=6371.0088;
        double dLat=Math.toRadians(lat2-lat1), dLng=Math.toRadians(lng2-lng1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2)*Math.sin(dLng/2);
        double c=2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R*c;
    }

    private static double round1(double x){ return Math.round(x*10.0)/10.0; }
}
