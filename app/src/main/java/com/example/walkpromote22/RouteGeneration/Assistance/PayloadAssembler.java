package com.example.walkpromote22.RouteGeneration.Assistance;

import static com.example.walkpromote22.ChatbotFragments.RouteGeneration.getCoreLocationsFromRequirement;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

// ⚠️ 按你真实类名修改这一行的静态导入（如果不在 RouteGeneration）


/**
 * 作用：
 * 1) 安全获取偏好候选（JSONArray），并打入 LLM userTurn；
 * 2) 统一“协作契约”（contract），让 GEN/SELECTOR 严格用 ids（锚点 ID）交流；
 * 3) 兼容“有/无 spec”两种情况：
 *    - 若调用方已经用 EnvIndexer 构建了 spec（含 cells/anchors/events），传入即可；
 *    - 否则也能只靠 preferences + user_request 先跑（准确度会低，建议带 spec）。
 */
public final class PayloadAssembler {
    private PayloadAssembler(){}

    /** 生成 GEN 的 userTurn JSON 字符串。 */
    public static String makeGenTurn(@NonNull Context ctx, String userUtter, JSONObject specOrNull) throws JSONException {
        JSONObject turn = new JSONObject();
        turn.put("user_request", userUtter);
        // 1) 放入偏好候选（来自你现有方法）
        turn.put("preferences", safeGetPreferences(ctx,userUtter));
        // 2) 如果上层已经构建了紧凑环境 spec（推荐），放进去
        if (specOrNull != null) turn.put("spec", specOrNull);
        // 3) 协作契约（让 GPT 完成“偏好→锚点 IDs”的映射与约束满足）
        turn.put("contract", genContract());
        return turn.toString();
    }

    /** 生成 SELECTOR 的 userTurn：包含 candidates + （可选）reviews + 原始 spec + 偏好 */
    public static String makeSelectorTurn(JSONObject specOrNull, JSONArray preferencesOrNull,
                                          JSONArray candidates, JSONArray reviewsOrNull,
                                          Double targetKm, Boolean wantLoop) throws JSONException {
        JSONObject pack = new JSONObject();
        pack.put("candidates", candidates != null ? candidates : new JSONArray());
        if (reviewsOrNull != null) pack.put("reviews", reviewsOrNull);
        if (specOrNull != null)   pack.put("spec", specOrNull);
        if (preferencesOrNull != null) pack.put("preferences", preferencesOrNull);
        JSONObject prefs = new JSONObject();
        if (targetKm != null) prefs.put("target_km", targetKm);
        if (wantLoop != null) prefs.put("loop", wantLoop);
        pack.put("prefs", prefs);
        pack.put("contract", selectorContract());
        return pack.toString();
    }

    /** 安全包装 getCoreLocationsFromRequirement(userUtter) → JSONArray（可能为空） */
    public static JSONArray safeGetPreferences(Context ctx,String userUtter) {
        try {
            JSONArray arr = getCoreLocationsFromRequirement(ctx,userUtter);
            return (arr != null) ? arr : new JSONArray();
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** GEN 协作契约（要求生成 candidates，严格用 ids） */
    private static JSONObject genContract() throws JSONException {
        // 约束：输出 {"candidates":[{"name":"","reason":"","ids":["c12:a01","c18:a07",...],
        //                       "unmet_preferences":[index 或 name], "notes":[]}]} 纯 JSON
        JSONObject schema = new JSONObject()
                .put("candidates_schema", new JSONObject()
                        .put("ids", "array of anchor tokens; each token is 'cXX:aYY' or 'aYY'")
                        .put("unmet_preferences", "array; indices or names of preferences not mapped")
                        .put("name", "string").put("reason", "string").put("notes", "array"));
        JSONObject mapping = new JSONObject()
                .put("map_rule", "Map each preference item to the NEAREST anchor id within 250m by lat/lng or fuzzy name; include at least one preference if feasible.")
                .put("if_unmappable", "List it under unmet_preferences with reason.")
                .put("ids_are_required", true);
        return new JSONObject().put("output", schema).put("mapping", mapping);
    }

    /** SELECTOR 协作契约（选择唯一方案，仍严格 ids） */
    private static JSONObject selectorContract() throws JSONException {
        // 约束：输出 {"final":{"name":"","reason":"","ids":[...],"unmet_preferences":[...]}} 纯 JSON
        JSONObject schema = new JSONObject()
                .put("final_schema", new JSONObject()
                        .put("ids", "array of anchor tokens (same as GEN)")
                        .put("unmet_preferences", "array")
                        .put("name", "string").put("reason", "string"));
        JSONObject choose = new JSONObject()
                .put("criteria", "Prefer candidates that cover more preferences with fewer unmet items, higher safety/green/road scores based on spec, and closer to target_km if provided. Maintain loop if requested.");
        return new JSONObject().put("output", schema).put("selection", choose);
    }
    // 返回可直接追加到 LLM history 的 few-shot 对话：["user","assistant","user","assistant",...]
    public static org.json.JSONArray buildSuccessExample(String agentRole) throws JSONException {
        org.json.JSONArray shots = new org.json.JSONArray();

        if ("ALTER".equalsIgnoreCase(agentRole)) {
            // 例1：直接去往 → 输出起点+目标，删除多余点
            shots.put(new org.json.JSONObject().put("role","user")
                    .put("content","I want to go directly to KFC（东西湖万达店）"));
            shots.put(new org.json.JSONObject().put("role","assistant")
                    .put("content",
                            "{ \"routes\": [" +
                                    "  { \"name\":\"Direct-Route\"," +
                                    "    \"waypoints\":[" +
                                    "      {\"name\":\"起点\",\"lat\":30.647749,\"lng\":114.192875}," +
                                    "      {\"name\":\"KFC（东西湖万达店）\",\"lat\":30.637975,\"lng\":114.178425}" +
                                    "    ]," +
                                    "    \"changed\":true," +
                                    "    \"reason\":\"direct go simplification\"" +
                                    "  }" +
                                    "]}"));

            // 例2：必须经过某地 → 起点 → 必经点 → 目标
            shots.put(new org.json.JSONObject().put("role","user")
                    .put("content","Go to KFC（东西湖万达店） via Starbucks（泛海店）"));
            shots.put(new org.json.JSONObject().put("role","assistant")
                    .put("content",
                            "{ \"routes\": [" +
                                    "  { \"name\":\"Via-Point\"," +
                                    "    \"waypoints\":[" +
                                    "      {\"name\":\"起点\",\"lat\":30.647749,\"lng\":114.192875}," +
                                    "      {\"name\":\"Starbucks（泛海店）\",\"lat\":30.650500,\"lng\":114.185307}," +
                                    "      {\"name\":\"KFC（东西湖万达店）\",\"lat\":30.637975,\"lng\":114.178425}" +
                                    "    ]," +
                                    "    \"changed\":true," +
                                    "    \"reason\":\"must-pass via Starbucks satisfied\"" +
                                    "  }" +
                                    "]}"));

            // 例3：避让提示 → 最小改动绕行
            shots.put(new org.json.JSONObject().put("role","user")
                    .put("content","Go to the park but avoid 工地路段"));
            shots.put(new org.json.JSONObject().put("role","assistant")
                    .put("content",
                            "{ \"routes\": [" +
                                    "  { \"name\":\"Avoid-Construction\"," +
                                    "    \"waypoints\":[" +
                                    "      {\"name\":\"起点\",\"lat\":30.647749,\"lng\":114.192875}," +
                                    "      {\"name\":\"公园入口\",\"lat\":30.653944,\"lng\":114.182260}" +
                                    "    ]," +
                                    "    \"changed\":true," +
                                    "    \"reason\":\"detoured to avoid 工地路段\"" +
                                    "  }" +
                                    "]}"));

            return shots;
        }

        if ("SELECTOR".equalsIgnoreCase(agentRole)) {
            // 例1：偏好“直达”，在多个候选中选仅含起点+目标的那条
            shots.put(new org.json.JSONObject().put("role","user")
                    .put("content","Among the routes, pick the best direct route to KFC（东西湖万达店）"));
            shots.put(new org.json.JSONObject().put("role","assistant")
                    .put("content",
                            "{ \"final\": {" +
                                    "  \"name\":\"Direct-Route\"," +
                                    "  \"waypoints\":[" +
                                    "    {\"name\":\"起点\",\"lat\":30.647749,\"lng\":114.192875}," +
                                    "    {\"name\":\"KFC（东西湖万达店）\",\"lat\":30.637975,\"lng\":114.178425}" +
                                    "  ]," +
                                    "  \"reason\":\"shortest and satisfies 'direct' without extra waypoints\"" +
                                    "} }"));

            // 例2：优先选择 changed=false 且已满足需求的路线
            shots.put(new org.json.JSONObject().put("role","user")
                    .put("content","Prefer unchanged routes if they already satisfy the request"));
            shots.put(new org.json.JSONObject().put("role","assistant")
                    .put("content",
                            "{ \"final\": {" +
                                    "  \"name\":\"Apartment Loop\"," +
                                    "  \"waypoints\":[" +
                                    "    {\"name\":\"起点\",\"lat\":31.274476,\"lng\":120.738224}," +
                                    "    {\"name\":\"起点\",\"lat\":31.274476,\"lng\":120.738224}" +
                                    "  ]," +
                                    "  \"reason\":\"already satisfies request; no merge and minimal edits\"" +
                                    "} }"));

            return shots;
        }

        return shots; // 默认空
    }

}
