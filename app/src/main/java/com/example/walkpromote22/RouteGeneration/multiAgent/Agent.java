package com.example.walkpromote22.RouteGeneration.multiAgent;

import com.example.walkpromote22.ChatbotFragments.ChatbotHelper;

import org.json.JSONArray;

import java.util.concurrent.CompletableFuture;

// 按你的实际类名修改这两个静态导入



public interface Agent {

    enum Role { GEN, DETAIL, EVALUATOR}

    class Msg {
        public final Role from, to;


        public Grid.Cell cell;
        public JSONArray waypoints;
        public Msg(Role from, Role to,  Grid.Cell cell) {
            this.from = from; this.to = to;  this.cell = cell;
        }
        public Msg(Role from, Role to, JSONArray waypoints) {
            this.from = from; this.to = to;

            this.waypoints=waypoints;
        }



        public static Msg send(Role from, Role to,  Grid.Cell cell) {
            return new Msg(from, to, cell);
        }
        public static Msg send(Role from, Role to,  JSONArray jsonArray) {
            return new Msg(from, to , jsonArray);
        }

    }

    Role role();

    // ✨ 去掉 Context
    CompletableFuture<Msg> onMessage(Msg in, ChatbotHelper llm) throws Exception;


}

