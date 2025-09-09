package com.example.walkpromote22.RouteGeneration.multiAgent;

import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.getUserLocation;
import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.fetchPOIs;

import android.content.Context;
import android.util.Log;

import com.amap.api.maps.model.LatLng;
import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;
import com.example.walkpromote22.ChatbotFragments.ChatbotResponseListener;
import com.example.walkpromote22.data.model.POI;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RouteGenAgent（GPT 驱动 Anchor 选择；使用 Context 与 String dialogForRoute；仅使用 Grid.Cell 既有字段）
 *
 * 构造：
 *   public RouteGenAgent(Context ctx, String dialogForRoute)
 *
 * 输入：
 *   Agent.Msg.send(Role.GEN, Role.GEN, seedCell)  // seedCell.center 仅作兜底
 *
 * 输出：
 *   Agent.Msg.send(Role.GEN, Role.DETAIL, bestCell) // bestCell.anchor 已由 GPT 选出并回填
 */
public class RouteGenAgent implements Agent {

    private final Context ctx;
    String dialogForRoute;
    public LatLng userlocation;

    public RouteGenAgent(Context ctx, String dialogForRoute) {
        this.ctx = ctx;
        this.dialogForRoute = dialogForRoute == null ? "" : dialogForRoute;
    }

    @Override public Role role() { return Role.GEN; }

    @Override
    public CompletableFuture<Msg> onMessage(Msg in, ChatbotHelper llm) {
        final CompletableFuture<Msg> fut = new CompletableFuture<>();

        try {
            // ================= 1) 解析 dialogForRoute =================
            String userInput = extractLastUserUtterance(dialogForRoute);
            String history =dialogForRoute;
            LatLng userLoc = extractUserLocation();

            // 如果对话未给定位，尝试系统定位；再兜底 seed.center
            if (userLoc == null) {
                try {
                    userLoc = getUserLocation(ctx);
                } catch (Throwable ignore) {
                }
            }
            this.userlocation = userLoc;
            if (userLoc == null && in != null && in.cell != null) {
                userLoc = new LatLng(in.cell.centerLat, in.cell.centerLng);
            }
            if (userLoc == null) {
                fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, (Grid.Cell) null));
                return fut;
            }

            // === 不再解析/传递 POIs ===
            // JSONArray apiPois = extractApiPois(dialogForRoute); // ⛔️ 删除
            // JSONArray targetHint = apiPois;                     // ⛔️ 删除
            String targetHintStr = deriveTargetHint(userInput, history); // ✅ 目标提示改为字符串


            // ================= 2) 构建 10×10 网格（1km/格） =================
            // ...前略（解析 dialogForRoute / userLoc 等相同）...

// ================= 2) 按“以 userlocation 为第一个 cell 的 center”构建 10×10 网格 =================
            Grid grid = buildGridAroundUserAsCenter(userLoc, /*gridSize*/20, /*cellSizeMeters*/500);

// ================= 3) 注入 POIs 到 cell（仅为 DETAIL 使用；不传给 GPT） =================
            JSONArray allPois = safeFetchPois(userLoc, /*radiusMeters*/5200); // 覆盖±5km 网格，留余量
            bucketPOIsToGrid(allPois, grid);    // ➜ 写入 cell.pois
            if (allPois == null) {
                Log.e("POI Debug", "No POIs found in this cell.");
            } else {
                Log.e("POI Debug", "Found " + allPois.length() + " POIs to process.");
            }

            extractTagsForGrid(grid);           // ➜ 用 POI 名称抽 tags（如 "park","lake","green path"...）

// 可选：分发 avoid 提示到 cell
            JSONArray avoidHint = new JSONArray();
            if (avoidHint != null) distributeAvoids(grid, avoidHint);

// ================= 4) 调 GPT 选择 Anchor（不给 POIs） =================
            JSONObject userPayload = buildUserPromptNoPOIs(userInput, history, targetHintStr, userLoc, grid);

            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", buildSystemPrompt()))
                    // ⚠️ 关键：这里用 userPayload.toString()，而不是直接传 JSONObject
                    .put(new JSONObject().put("role", "user").put("content", userPayload.toString()));

            final String turnId = "GEN-" + java.util.UUID.randomUUID();

// ... LLM 回调保持不变，但在 fallback 里强制改名为 "Begining Point"

            LatLng finalUserLoc = userLoc;
            // ================= 4) 调 GPT 选择 Anchor + 生成 cell_path（不给 POIs） =================

           ;
           Log.e("TAG","genAgent message="+messages);

            llm.sendMessage(turnId, messages, new ChatbotResponseListener() {
                @Override
                public void onResponse(String content) {
                    try {
                        // ====== 逐个提取“多段 JSON 对象” ======
                        String txt = (content == null) ? "" : content.trim();
                        JSONArray outCells = new JSONArray();
                        JSONArray cellPath = null;

                        int idx = 0;
                        while (idx < txt.length()) {
                            int start = txt.indexOf('{', idx);
                            if (start < 0) break;
                            int depth = 0, end = -1;
                            for (int i = start; i < txt.length(); i++) {
                                char ch = txt.charAt(i);
                                if (ch == '{') depth++;
                                else if (ch == '}') {
                                    depth--;
                                    if (depth == 0) {
                                        end = i;
                                        break;
                                    }
                                }
                            }
                            if (end < 0) break;
                            String jsonStr = txt.substring(start, end + 1);
                            idx = end + 1;

                            JSONObject obj;
                            try {
                                obj = new JSONObject(jsonStr);
                            } catch (Throwable ignore) {
                                continue;
                            }

                            if (obj.has("cell_path")) {
                                JSONArray p = obj.optJSONArray("cell_path");
                                if (p != null) cellPath = p;
                                continue;
                            }
                            if (obj.has("cell_id") && obj.has("center")) {
                                // 规范化字段
                                if (!obj.has("anchor")) obj.put("anchor", new JSONArray());
                                if (!obj.has("tags")) obj.put("tags", new JSONArray());

                                // 补充 pois（不给 GPT，但要给 DETAIL）
                                Grid.Cell cRef = grid.getCellById(obj.optInt("cell_id", -1));
                                if (cRef != null && !obj.has("pois")) {
                                    obj.put("pois", cRef.pois != null ? cRef.pois : new JSONArray());
                                }

                                // 统一锚点名（如果是兜底名）
                                JSONArray as = obj.optJSONArray("anchor");
                                if (as != null) {
                                    for (int j = 0; j < as.length(); j++) {
                                        JSONObject a = as.optJSONObject(j);
                                        if (a == null) continue;
                                        // 将任何“anchor-center”改名为 Begining Point（并保持坐标）
                                        if ("anchor-center".equalsIgnoreCase(a.optString("name", ""))) {
                                            a.put("name", "Begining Point");
                                        }
                                    }
                                }
                                outCells.put(obj);

                                // 同步写回 grid 里的 anchors（便于老日志/老逻辑预览）
                                if (cRef != null && as != null) {
                                    for (int j = 0; j < as.length(); j++) {
                                        JSONObject a = as.optJSONObject(j);
                                        if (a == null) continue;
                                        if (!a.has("lat") || !a.has("lng")) continue;
                                        cRef.anchor.put(new JSONObject()
                                                .put("name", a.optString("name"))
                                                .put("lat", a.optDouble("lat"))
                                                .put("lng", a.optDouble("lng")));
                                    }
                                }
                            }
                        }

                        // ====== 确保“用户所在 cell”一定存在且第一个，且含 Begining Point ======
                        // 假设 id=1 是以 user 为中心构建的第一个 cell；若不是，可以在此根据坐标匹配你存的边界
                        Grid.Cell userCell = grid.getCellById(1);
                        if (userCell != null) {
                            boolean present = false;
                            for (int i = 0; i < outCells.length(); i++) {
                                if (outCells.optJSONObject(i).optInt("cell_id", -1) == userCell.id) {
                                    present = true;
                                    break;
                                }
                            }
                            if (!present) {
                                JSONObject startCell = new JSONObject()
                                        .put("cell_id", userCell.id)
                                        .put("center", new JSONObject().put("lat", userCell.centerLat).put("lng", userCell.centerLng))
                                        .put("anchor", new JSONArray().put(new JSONObject()
                                                .put("name", "Begining Point")
                                                .put("lat", finalUserLoc.latitude)
                                                .put("lng", finalUserLoc.longitude)))
                                        .put("tags", userCell.tags != null ? userCell.tags : new JSONArray())
                                        .put("pois", userCell.pois != null ? userCell.pois : new JSONArray());
                                // 放到第一个
                                JSONArray newArr = new JSONArray().put(startCell);
                                for (int i = 0; i < outCells.length(); i++)
                                    newArr.put(outCells.get(i));
                                outCells = newArr;
                            }
                        }

                        // ====== 若未提供 cell_path，则用 outCells 顺序拼一个 ======
                        if (cellPath == null) {
                            cellPath = new JSONArray();
                            for (int i = 0; i < outCells.length(); i++) {
                                cellPath.put(outCells.optJSONObject(i).optInt("cell_id", -1));
                            }
                        }

                        // ====== 打包：多 cell + cell_path 传给 DETAIL ======
                        JSONArray pack = new JSONArray();
                        for (int i = 0; i < outCells.length(); i++) pack.put(outCells.get(i));
                        pack.put(new JSONObject().put("cell_path", cellPath));

                        // ★★ 关键：不再发单个 cell，而是以 waypoints(JSONArray) 发送多 cell + cell_path
                        fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, pack));
                        // 在 onResponse 方法中添加
                        Log.d("CellPath", "Generated cell_path: " + cellPath.toString());

                    } catch (Throwable ex) {
                        // 解析失败兜底：至少保证用户 cell 存在，并命名 Begining Point
                        try {
                            Grid.Cell userCell = grid.getCellById(1); // 以 user 为中心的首格
                            if (userCell != null) {
                                JSONObject cellObj = new JSONObject()
                                        .put("cell_id", userCell.id)
                                        .put("center", new JSONObject().put("lat", userCell.centerLat).put("lng", userCell.centerLng))
                                        .put("anchor", new JSONArray().put(new JSONObject()
                                                .put("name", "Begining Point")
                                                .put("lat", finalUserLoc.latitude)
                                                .put("lng", finalUserLoc.longitude)))
                                        .put("tags", userCell.tags != null ? userCell.tags : new JSONArray())
                                        .put("pois", userCell.pois != null ? userCell.pois : new JSONArray());

                                JSONArray pack = new JSONArray()
                                        .put(cellObj)
                                        .put(new JSONObject().put("cell_path", new JSONArray().put(userCell.id)));

                                fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, pack));
                            } else {
                                fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, new JSONArray()));
                            }
                        } catch (Throwable ignore) {
                            fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, new JSONArray()));
                        }
                    }
                }

                @Override
                public void onFailure(String error) {
                    // LLM 失败兜底：与上面解析失败相同
                    try {
                        Grid.Cell userCell = grid.getCellById(1);
                        if (userCell != null) {
                            JSONObject cellObj = new JSONObject()
                                    .put("cell_id", userCell.id)
                                    .put("center", new JSONObject().put("lat", userCell.centerLat).put("lng", userCell.centerLng))
                                    .put("anchor", new JSONArray().put(new JSONObject()
                                            .put("name", "Begining Point")
                                            .put("lat", finalUserLoc.latitude)
                                            .put("lng", finalUserLoc.longitude)))
                                    .put("tags", userCell.tags != null ? userCell.tags : new JSONArray())
                                    .put("pois", userCell.pois != null ? userCell.pois : new JSONArray());

                            JSONArray pack = new JSONArray()
                                    .put(cellObj)
                                    .put(new JSONObject().put("cell_path", new JSONArray().put(userCell.id)));

                            fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, pack));
                        } else {
                            fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, new JSONArray()));
                        }
                    } catch (Throwable ignore) {
                        fut.complete(Agent.Msg.send(Role.GEN, Role.DETAIL, new JSONArray()));
                    }
                }
            });

            return fut;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
    /** 以 userlocation 为第一个 cell 的 center，按 1km 步长棋盘式向外扩展，构建 N×N cells。 */
    private Grid buildGridAroundUserAsCenter(LatLng userLoc, int gridSize, int cellSizeMeters) throws JSONException {
        Grid g = new Grid();
        g.cells = new java.util.ArrayList<>();

        // 每 1km 对应的经纬度偏移（近似）：lat 固定；lng 随纬度修正
        final double dLatPerKm = 1.0 / 111.32; // ~度/公里
        final double dLngPerKm = 1.0 / (111.32 * Math.cos(Math.toRadians(userLoc.latitude)));
        final double stepLat = dLatPerKm * (cellSizeMeters / 1000.0);
        final double stepLng = dLngPerKm * (cellSizeMeters / 1000.0);

        // 轴向取值范围（10×10：x∈[-4..5], y∈[-4..5]；(0,0) 就是用户所在 cell）
        final int halfNeg = (gridSize / 2) - 1;  // 10 -> 4
        final int halfPos = (gridSize / 2);      // 10 -> 5

        // 生成所有格点（相对索引 ix,iy），并按“由近到远（环形）”排序，确保 (0,0) 第一
        java.util.List<int[]> coords = new java.util.ArrayList<>();
        for (int iy = -halfNeg; iy <= halfPos; iy++) {
            for (int ix = -halfNeg; ix <= halfPos; ix++) {
                coords.add(new int[]{ix, iy});
            }
        }
        coords.sort((a,b) -> {
            int ra = Math.max(Math.abs(a[0]), Math.abs(a[1])); // Chebyshev 半径
            int rb = Math.max(Math.abs(b[0]), Math.abs(b[1]));
            if (ra != rb) return Integer.compare(ra, rb);
            // 次序：按行列稳定排序
            if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
            return Integer.compare(a[0], b[0]);
        });

        int id = 0;
        for (int[] c : coords) {
            int ix = c[0], iy = c[1];
            double centerLat = userLoc.latitude + iy * stepLat;
            double centerLng = userLoc.longitude + ix * stepLng;

            Grid.Cell cell = new Grid.Cell();
            cell.id = (++id);                    // id=1 保证是 (0,0) 也就是 userlocation 对应的 cell
            cell.centerLat = centerLat;
            cell.centerLng = centerLng;
            cell.tags = new JSONArray();
            cell.avoidHint = new JSONArray();
            cell.anchor = new JSONArray();
            cell.pois = new JSONArray();        // 将在 bucketPOIsToGrid 中写入

            g.cells.add(cell);
        }
        return g;
    }

    /** 将 allPois 按“落在哪个 cell 的 1km 正方形（center±0.5km）”分桶写入 cell.pois。 */
    public void bucketPOIsToGrid(JSONArray allPois, Grid grid) {
        // 遍历所有 POI
        for (int i = 0; i < allPois.length(); i++) {
            // 获取 POI 数据（以字符串形式存在）
            String poiItem = allPois.optString(i);


            // 假设 POI 格式为 "名称,(lat,lng)"
            String[] parts = poiItem.split(",", 2);  // 分隔名称和坐标部分
            if (parts.length == 2) {
                String name = parts[0];  // POI 名称
                String[] coords = parts[1].replace("(", "").replace(")", "").split(",");  // 提取坐标并去除括号
                if (coords.length == 2) {
                    try {
                        double lat = Double.parseDouble(coords[0].trim());
                        double lng = Double.parseDouble(coords[1].trim());

                        // 创建一个有效的 JSONObject
                        JSONObject p = new JSONObject();
                        try {
                            p.put("name", name);
                            p.put("lat", lat);
                            p.put("lng", lng);

                            // 将 POI 添加到网格中的相应格子
                            for (Grid.Cell cell : grid.cells) {
                                // 以 cell center 为参考计算 0.5km 的经纬度偏移
                                double dLat05 = 0.25 / 111.32;  // 0.5 km 的经度偏移量
                                double dLng05 = 0.25 / (111.32 * Math.cos(Math.toRadians(cell.centerLat)));  // 经度偏移

                                double minLat = cell.centerLat - dLat05;
                                double maxLat = cell.centerLat + dLat05;
                                double minLng = cell.centerLng - dLng05;
                                double maxLng = cell.centerLng + dLng05;

                                cell.minLat = minLat;
                                cell.maxLat = maxLat;
                                cell.minLng = minLng;
                                cell.maxLng = maxLng;

                                // 检查 POI 是否位于当前格子范围内
                                if (lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng) {
                                    // 如果 POI 位于格子内，则将其添加到该格子的 POI 列表中
                                    cell.addPOI(name, lat, lng);
                                }
                            }

                        } catch (JSONException e) {
                            Log.e("POI Debug", "Error creating JSON object: " + e.getMessage());
                        }
                    } catch (NumberFormatException e) {
                        Log.e("POI Debug", "Invalid coordinates format at index " + i + ": " + e.getMessage());
                    }
                } else {
                    Log.e("POI Debug", "Invalid coordinates at index " + i);
                }
            } else {
                Log.e("POI Debug", "Invalid POI format at index " + i);
            }
        }


    }






    /** 仅给 LLM 基本信息：user_input/history/userlocation/targetHint + cells_lite（无 POIs） */
    private JSONObject buildUserPromptNoPOIs(String userInput,
                                             String history,
                                             String targetHintStr,
                                             LatLng userLoc,
                                             Grid grid) throws JSONException {
        JSONObject in = new JSONObject();
        in.put("user_input", userInput == null ? "" : userInput);
        in.put("history", history == null ? "" : history);
        if (userLoc != null) {
            in.put("userlocation", new JSONObject()
                    .put("lat", userLoc.latitude)
                    .put("lng", userLoc.longitude));
        } else {
            in.put("userlocation", JSONObject.NULL);
        }
        in.put("targetHint", targetHintStr == null ? "" : targetHintStr);
        in.put("avoidHint", new JSONArray()); // 目前为空；以后可扩展

        // cells_lite：不带 POIs
        JSONArray cells = new JSONArray();
        if (grid != null) {
            for (Grid.Cell c : grid.cells) {
                JSONObject o = new JSONObject()
                        .put("id", c.id)
                        .put("center", new JSONObject().put("lat", c.centerLat).put("lng", c.centerLng))
                        .put("tags", c.tags == null ? new JSONArray() : c.tags)
                        .put("avoidHint", c.avoidHint == null ? new JSONArray() : c.avoidHint);
                // 不包含：Anchor/POIs（Anchor 由 LLM 生成；POIs 明确不提供）
                cells.put(o);
            }
        }
        in.put("cells", cells);

        return in;
    }

    /** 从 userInput/history 粗提“目标关键词”（例：KFC/公园/绿道），缺省返回空串 */
    private String deriveTargetHint(String userInput, String history) {
        try {
            String src = (userInput == null ? "" : userInput) + " " + (history == null ? "" : history);
            src = src.toLowerCase(java.util.Locale.ROOT);
            // 简单示例：你可以替换成更完整的关键词提取
            if (src.contains("kfc") || src.contains("肯德基")) return "KFC";
            if (src.contains("麦当劳")) return "McDonald's";
            if (src.contains("公园") || src.contains("park")) return "park";
            if (src.contains("绿道") || src.contains("green path")) return "green path";
            if (src.contains("湖") || src.contains("lake")) return "lake";
            return "";
        } catch (Throwable ignore) {
            return "";
        }
    }

    // ========================= 解析 dialogForRoute =========================

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



    private LatLng extractUserLocation() {

        LatLng loc= getUserLocation(ctx);
        Log.e("TAG","loc="+loc);
        return loc;

    }

    /** 从对话中提取 API_Result 后面的 JSON 数组（POIs） */


    // ========================= GPT prompt =========================

    private String buildSystemPrompt() {
        // 强制把“用户当前位置”作为 Anchor[0] = {name:"start", lat, lng}
// 仅返回 JSON；坐标用高德 LatLng（小数，不要字符串）；不允许输出多余文本。
        return "你是 GEN（Anchor 与 Cell-Level Route Planner）。\n" +
                "输入 JSON：包含 user_input, history, userlocation, targetHint, avoidHint, cells。\n" +
                "\n" +
                "【关于 cell】:\n" +
                "  - 每个 cell 是一个边长 500m的正方形区域。\n" +
                "  - center 表示 cell 的几何中心点坐标。\n" +
                "  - cell 覆盖的范围是：center ±50m。\n" +
                "  - 任何锚点（anchor）的坐标必须落在某个 cell 的范围内，才能放入该 cell 的 anchor 数组。\n" +
                "\n" +
                "【任务1：锚点识别】\n" +
                "  1) 必须把 userlocation 放入它所在的 cell，作为 Anchor，名称固定为 \"Beginning Point\"。\n" +
                "  2) 从 history(API_Result) + user_input 中解析是否存在用户必须前往的 POI（如 KFC、公园），将其作为锚点 anchor，放入对应的 cell。\n" +
                "  3) 每个 cell 的输出 JSON 格式：\n" +
                "     {\n" +
                "       \"cell_id\": <int>,\n" +
                "       \"center\": {\"lat\":<number>,\"lng\":<number>},\n" +
                "       \"anchor\": [ {\"name\":<string>,\"lat\":<number>,\"lng\":<number>} ],\n" +
                "       \"tags\": [<string>,...]\n" +
                "     }\n" +
                "  4) 所有坐标必须用高德经纬度，键名是 lat/lng（不要写 latitude/longitude）。\n" +
                "  5) 输出时，逐 cell 给出多个 JSON 对象，每行一个对象，不要数组，不要额外文本。\n" +
                "\n" +
                "【任务2：Cell-Level 路径规划】\n" +
                "  - 在给部分cell赋予锚点之后，你还必须输出一个 cell 路径作为最终输出（数组形式）。\n" +
                "  - 规则：\n" +
                "    1) 路径由一系列 cell_id 构成，必须相邻（共享边或角）。\n" +
                "    2) cell的顺序就是行走的顺序，"+
                "    3) 路径起点必须是包含 \"Beginning Point\" 的 cell。\n" +
                "    4) 如果用户要求去某个 POI（如 KFC），则路径必须覆盖该 cell。\n" +
                "    5) 如果用户要求“散步 N 公里”，则路径总长度（cell 数 × 500m）应尽量接近 N 公里，必要时形成闭环（回到起点）cell之间最好是不存在斜对关系。\n" +
                "    6) ***当用户明确提到了要一个环形路线或者最终要回到起点，那么你给出的cell路线就必须是一个口字型的路线（中间应该是空的，不包含cell，外围围成一个口字型）***"+
                "    7) 尽量经过标签更符合用户偏好的 cell（如散步=park/green path，休闲=lake）。\n" +
                "\n" +
                "【示例输出】:\n" +
                "{\n" +
                "  \"cell_id\": 1,\n" +
                "  \"center\": {\"lat\": 30.655501555874956, \"lng\": 114.18584801201044},\n" +
                "  \"anchor\": [{\"name\":\"Begining Point\",\"lat\":30.655501555874956,\"lng\":114.18584801201044}\n" +
                "  ],\n" +
                "  \"tags\": []\n" +
                "}\n" +
                "{\n" +
                "  \"cell_id\": 45,\n" +
                "  \"center\": {\"lat\": 30.637429332375135, \"lng\": 114.17557905320324},\n" +
                "  \"anchor\": [\n" +
                "    {\"name\":\"肯德基(东西湖万达店)\",\"lat\":30.637537,\"lng\":114.178229}\n" +
                "  ],\n" +
                "  \"tags\": []\n" +
                "}\n";




    }

    private JSONObject safeExtractJson(String content) throws JSONException {
        if (content == null) throw new JSONException("empty content");
        int l = content.indexOf('{'), r = content.lastIndexOf('}');
        if (l >= 0 && r > l) content = content.substring(l, r + 1);
        return new JSONObject(content);
    }

    // ========================= 网格构建与填充 =========================

    /** 以用户位置为中心构建 sideMeters×sideMeters 的 n×n 网格。 */
    private Grid buildGridAround(LatLng center, int sideMeters, int n) {
        double lat = center.latitude, lng = center.longitude;
        double half = sideMeters / 2.0;
        double latHalfDeg = metersToLatDeg(half);
        double lngHalfDeg = metersToLngDeg(half, lat);

        double north = lat + latHalfDeg;
        double west  = lng - lngHalfDeg;
        double latStep = metersToLatDeg(sideMeters / (double) n);
        double lngStep = metersToLngDeg(sideMeters / (double) n, lat);

        Grid g = new Grid(n, north, west, latStep, lngStep, lat, lng);

        int id = 1;
        for (int r = 0; r < n; r++) {
            double clat = north - (r + 0.5) * latStep;
            for (int c = 0; c < n; c++) {
                double clng = west + (c + 0.5) * lngStep;
                Grid.Cell cell = new Grid.Cell();
                cell.id = id++;
                cell.centerLat = clat;
                cell.centerLng = clng;
                // 清空初始化
                cell.tags      = new JSONArray();
                cell.avoidHint = new JSONArray();
                cell.anchor    = new JSONArray(); // 交给 GPT 决策
                cell.pois      = new JSONArray();
                g.addCell(cell);
            }
        }
        return g;
    }

    /** 若对话没有 POIs，则调用地图 API 兜底抓取 5km 内 POIs。 */
    private JSONArray safeFetchPois(LatLng center, int radiusMeters) {
        try { return fetchPOIs(ctx, center, radiusMeters); }
        catch (Throwable t) { return new JSONArray(); }
    }

    /** 归桶 POIs 到各 cell（要求 {name,lat,lng}） */


    /** 从每个 cell 的 POI 名称抽标签 */
    public void extractTagsForGrid(Grid grid) {
        // 获取 POIs 数据
        ArrayList<Grid.Cell> cells = grid.cells; // 如果是 Grid 类的实例
        for (Grid.Cell gridCell : cells) {
            JSONArray pois = gridCell.pois; // 获取当前格子中的 POIs

            // 检查 POIs 是否为空
            if (pois == null || pois.length() == 0) {
                Log.d("POI Debug", "No POIs found in this cell.");
                gridCell.tags = new JSONArray();
                String tag = "Extremely dangerous";
                // 将这个 String 添加到 JSONArray 中
                gridCell.tags.put(tag);

                continue; // 跳过没有 POI 的格子
            } else {
                Log.d("POI Debug", "Found " + pois.length() + " POIs to process.");
            }

            // 初始化更丰富的标签列表
            String[] tags = {
                    "公园", "湖", "商场", "学校", "医院", "餐馆", "绿道", "博物馆", "咖啡馆", "步道",
                    "购物中心", "电影院", "超市", "书店", "体育馆", "停车场", "旅游景点", "艺术中心", "夜市"
            };

            // 使用 Set 来去重标签
            Set<String> uniqueTags = new HashSet<>();

            // 遍历每个 POI
            for (int i = 0; i < pois.length(); i++) {
                try {
                    JSONObject poi = pois.getJSONObject(i); // 获取每个 POI
                    String poiName = poi.optString("name"); // 获取 POI 的名称，假设 POI 存储有 `name` 字段

                    if (poiName != null && !poiName.isEmpty()) {


                        // 遍历标签，检查 POI 名称是否包含标签关键词
                        for (String tag : tags) {
                            // 在 POI 名称中查找标签，中文匹配
                            if (poiName.contains(tag)) {

                                // 如果匹配，则将标签加入 Set（会自动去重）
                                uniqueTags.add(tag);
                            }
                        }
                    }
                } catch (JSONException e) {
                    Log.d("POI Debug", "Error processing POI: " + e.getMessage());
                }
            }

            // 将去重后的标签集合转回 JSONArray 并赋值给 gridCell
            gridCell.tags = new JSONArray(uniqueTags);

            // 输出标签赋值结果
            if (gridCell.tags.length() > 0) {
                Log.d("POI Debug", "Tags successfully assigned: " + gridCell.tags.toString());
            } else {
                Log.d("POI Debug", "No tags assigned to this cell.");
            }
        }
    }




    /** 分发 avoidHint（当前为空；未来如有可直接传入） */
    private void distributeAvoids(Grid g, JSONArray avoid) throws JSONException {
        if (avoid == null) return;
        for (int i = 0; i < avoid.length(); i++) {
            JSONObject a = avoid.optJSONObject(i);
            if (a == null) continue;
            double lat = a.optDouble("lat", Double.NaN);
            double lng = a.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
            int row = whichRow(g, lat);
            int col = whichCol(g, lng);
            if (row < 0 || col < 0 || row >= g.n || col >= g.n) continue;
            g.getCellByRowCol(row, col).avoidHint.put(a);
        }
    }




    private int whichRow(Grid g, double lat) {
        double d = g.north - lat;
        return (int) Math.floor(d / g.latStep);
    }

    private int whichCol(Grid g, double lng) {
        double d = lng - g.west;
        return (int) Math.floor(d / g.lngStep);
    }

    private void addTagIf(String name, Set<String> set, String tag, String... keys) {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String k : keys) { if (low.contains(k)) { set.add(tag); return; } }
    }

    // ========================= 选择输出 cell =========================

    private Grid.Cell pickBestAnchoredCell(Grid g, LatLng userLoc) {
        double bestD = Double.POSITIVE_INFINITY;
        Grid.Cell best = null;
        for (Grid.Cell c : g.cells) {
            if (c.anchor == null || c.anchor.length() == 0) continue;
            JSONObject a0 = c.anchor.optJSONObject(0);
            double lat = (a0 != null) ? a0.optDouble("lat", c.centerLat) : c.centerLat;
            double lng = (a0 != null) ? a0.optDouble("lng", c.centerLng) : c.centerLng;
            double d = haversineKm(userLoc.latitude, userLoc.longitude, lat, lng);
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    /** 兜底：若没有任何 Anchor，选择最近（或最贴近 target）的 cell，用其中心作为 Anchor。 */
    private Grid.Cell fallbackPickCellAndAnchor(Grid g, LatLng userLoc, JSONArray targetHint) throws JSONException {
        // 如果有 targetHint，优先靠近它
        if (targetHint != null && targetHint.length() > 0) {
            double best = Double.POSITIVE_INFINITY;
            Grid.Cell chosen = null;
            for (int i = 0; i < targetHint.length(); i++) {
                JSONObject t = targetHint.optJSONObject(i);
                if (t == null) continue;
                double lat = t.optDouble("lat", Double.NaN);
                double lng = t.optDouble("lng", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                int row = whichRow(g, lat), col = whichCol(g, lng);
                if (row >= 0 && col >= 0 && row < g.n && col < g.n) {
                    Grid.Cell c = g.getCellByRowCol(row, col);
                    double d = haversineKm(userLoc.latitude, userLoc.longitude, c.centerLat, c.centerLng);
                    if (d < best) { best = d; chosen = c; }
                }
            }
            if (chosen != null) {
                chosen.anchor.put(new JSONObject()
                        .put("name", "anchor-center")
                        .put("lat", chosen.centerLat)
                        .put("lng", chosen.centerLng));
                return chosen;
            }
        }
        // 否则：直接用户最近 cell
        double best = Double.POSITIVE_INFINITY;
        Grid.Cell nearest = null;
        for (Grid.Cell c : g.cells) {
            double d = haversineKm(userLoc.latitude, userLoc.longitude, c.centerLat, c.centerLng);
            if (d < best) { best = d; nearest = c; }
        }
        nearest.anchor.put(new JSONObject()
                .put("name", "anchor-center")
                .put("lat", nearest.centerLat)
                .put("lng", nearest.centerLng));
        return nearest;
    }

    // ========================= 数学工具 =========================

    private static double metersToLatDeg(double meters) { return meters / 111_320.0; }

    private static double metersToLngDeg(double meters, double atLatDeg) {
        double cos = Math.cos(Math.toRadians(atLatDeg));
        if (cos < 1e-6) cos = 1e-6;
        return meters / (111_320.0 * cos);
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R=6371.0088;
        double dLat=Math.toRadians(lat2-lat1), dLng=Math.toRadians(lng2-lng1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2)*Math.sin(dLng/2);
        double c=2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R*c;
    }
}
