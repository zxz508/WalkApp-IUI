package com.example.walkpromote22.tool;

// ToolRouter.java
public class ToolRouter {
    private final java.util.Map<String, Tool> registry = new java.util.HashMap<>();

    public ToolRouter register(Tool t) { registry.put(t.name(), t); return this; }

    public org.json.JSONObject call(String name, org.json.JSONObject args) throws Exception {
        Tool t = registry.get(name);
        if (t == null) throw new IllegalArgumentException("No tool: " + name);
        return t.call(args);
    }
}
