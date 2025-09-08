package com.example.walkpromote22.RouteGeneration.multiAgent;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 10×10 网格的轻量容器；每个 cell 含你要求的属性：
 * id / center{lat,lng} / "Length of side":"1_km" / tags[] / avoidHint[] / Anchor[] / POIs[]
 */
public class Grid {
    public  int n;                // 每边格子数，固定 10
    public ArrayList<Cell> cells = new ArrayList<>();
    public double north;         // 左上角（北）
    public  double west;          // 左上角（西）
    public  double latStep;       // 每格纬度步长
    public  double lngStep;       // 每格经度步长
    public  double centerLat;     // 网格中心
    public  double centerLng;


    public Grid(int n, double north, double west, double latStep, double lngStep, double centerLat, double centerLng) {

        this.n = n;
        this.north = north;
        this.west = west;
        this.latStep = latStep;
        this.lngStep = lngStep;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
    }

    public Grid() {

    }

    public static class Cell {
        public int id;
        public double centerLat;
        public double centerLng;
        public JSONArray tags = new JSONArray();
        public JSONArray avoidHint = new JSONArray();
        public JSONArray anchor = new JSONArray();
        public JSONArray pois = new JSONArray(); // [{name,lat,lng},...]
        public double minLat, maxLat, minLng, maxLng;

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("center", new JSONObject().put("lat", centerLat).put("lng", centerLng))
                    .put("Length of side", "1_km")
                    .put("tags", tags)
                    .put("avoidHint", avoidHint)
                    .put("Anchor", anchor)
                    .put("POIs", pois);
        }
        public void addPOI(String name, double lat, double lng) {
            try {
                JSONObject poi = new JSONObject();
                poi.put("name", name);
                poi.put("lat", lat);
                poi.put("lng", lng);
                pois.put(poi);  // 将新的 POI 添加到 pois 数组中
            } catch (JSONException e) {
                Log.e("POI Debug", "Error adding POI to cell: " + e.getMessage());
            }
        }



    }



    public void addCell(Cell c) { cells.add(c); }

    public Cell getCellByRowCol(int row, int col) {
        return cells.get(row * n + col);
    }

    public Cell getCellById(int id) {
        int idx = id - 1;
        return (idx >= 0 && idx < cells.size()) ? cells.get(idx) : null;
    }

    public JSONArray toJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Cell c : cells) arr.put(c.toJson());
        return arr;
    }

    public int countAnchors() {
        int k = 0;
        for (Cell c : cells) k += c.anchor.length();
        return k;
    }

    // —— 实用换算 & 定位 —— //
    public static double metersToLatDeg(double meters){ return meters/111_320.0; }
    public static double metersToLngDeg(double meters, double atLatDeg){
        double cos = Math.cos(Math.toRadians(atLatDeg));
        if (cos < 1e-6) cos = 1e-6;
        return meters/(111_320.0 * cos);
    }
    public static double haversineKm(double lat1,double lng1,double lat2,double lng2){
        double R=6371.0088;
        double dLat=Math.toRadians(lat2-lat1), dLng=Math.toRadians(lng2-lng1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2);
        double c=2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R*c;
    }
    public int whichRow(double lat){
        double d = (north - lat);
        return (int)Math.floor(d / latStep);
    }
    public int whichCol(double lng){
        double d = (lng - west);
        return (int)Math.floor(d / lngStep);
    }
}
