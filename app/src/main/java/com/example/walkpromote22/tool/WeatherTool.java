package com.example.walkpromote22.tool;

import static com.example.walkpromote22.tool.MapTool.getAddressFromAPI;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.amap.api.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WeatherTool {

    public interface WeatherCallback {
        void onWeatherReceived(String weatherInfo);
        void onError(String errorMessage);
    }

    public interface CityCodeCallback {
        void onCodeResolved(String cityCode); // 匹配成功
        void onError(String message);         // 匹配失败
    }
    @SuppressLint("RestrictedApi")
    public static void getCityCodeFromLatLng(Context context, LatLng latLng, CityCodeCallback callback) {
        new Thread(() -> {
            try {
                String address = getAddressFromAPI(latLng.latitude, latLng.longitude);
                if (address == null) {
                    callback.onError("无法获取地址信息");
                    return;
                }

                // 打印调试信息
                Log.e("地址解析", "地址：" + address);

                // 简单地址解析（根据中文格式）
                String province = null;
                String city = null;

                if (address.contains("省")) {
                    int provinceEnd = address.indexOf("省");
                    province = address.substring(0, provinceEnd + 1);

                    int cityEnd = address.indexOf("市", provinceEnd + 1);
                    if (cityEnd > 0) {
                        city = address.substring(provinceEnd + 1, cityEnd + 1);
                    }
                } else if (address.contains("市")) { // 直辖市
                    int cityEnd = address.indexOf("市");
                    province = address.substring(0, cityEnd + 1);
                    city = province;
                }

                if (province == null || city == null) {
                    callback.onError("地址中缺失省市信息");
                    return;
                }

                // 去掉后缀增强匹配鲁棒性
                String cleanProvince = province.replace("省", "").replace("市", "");
                String cleanCity = city.replace("市", "").replace("区", "");

                InputStream is = context.getAssets().open("city_code.json");
                byte[] buffer = new byte[is.available()];
                is.read(buffer);
                is.close();

                String jsonStr = new String(buffer, StandardCharsets.UTF_8);
                JSONObject jsonObject = new JSONObject(jsonStr);
                JSONArray provinceArray = jsonObject.getJSONArray("城市代码");

                for (int i = 0; i < provinceArray.length(); i++) {
                    JSONObject provinceObj = provinceArray.getJSONObject(i);
                    String jsonProvince = provinceObj.getString("省").replace("省", "").replace("市", "");
                    if (cleanProvince.contains(jsonProvince)) {
                        JSONArray cities = provinceObj.getJSONArray("市");
                        for (int j = 0; j < cities.length(); j++) {
                            JSONObject cityObj = cities.getJSONObject(j);
                            String jsonCity = cityObj.getString("市名").replace("市", "").replace("区", "");
                            if (cleanCity.contains(jsonCity)) {
                                String code = cityObj.getString("编码");
                                callback.onCodeResolved(code);
                                return;
                            }
                        }
                    }
                }

                callback.onError("找不到匹配的城市编码");

            } catch (Exception e) {
                e.printStackTrace();
                callback.onError("处理异常：city_code.json → " + e.getMessage());
            }
        }).start();
    }


    public static void fetchWeatherWithCode(String cityCode, WeatherCallback callback) {
        String url = "http://t.weather.sojson.com/api/weather/city/" + cityCode;
        Log.e("天气请求", "请求地址：" + url); // ✅ 打印请求地址

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("天气请求", "失败：" + e.getMessage());
                callback.onError("天气请求失败：" + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("天气请求", "状态码：" + response.code());
                    callback.onError("天气请求失败：HTTP " + response.code());
                    return;
                }

                String result = response.body().string();
                Log.e("天气响应", result); // ✅ 打印响应结果

                try {
                    JSONObject obj = new JSONObject(result);
                    if (!"200".equals(obj.optString("status"))) {
                        callback.onError("返回状态错误：" + obj.optString("status"));
                        return;
                    }

                    JSONObject data = obj.getJSONObject("data");
                    String wendu = data.getString("wendu");
                    String quality = data.getString("quality");
                    JSONObject today = data.getJSONArray("forecast").getJSONObject(0);
                    String type = today.getString("type");
                    String notice = today.getString("notice");

                    String weatherInfo = "今天天气：" + type + "，" + wendu + "℃，空气质量：" + quality + "\n" + notice;
                    callback.onWeatherReceived(weatherInfo);

                } catch (Exception e) {
                    Log.e("天气解析", "异常：" + e.getMessage());
                    callback.onError("天气解析失败：" + e.getMessage());
                }
            }
        });
    }

}