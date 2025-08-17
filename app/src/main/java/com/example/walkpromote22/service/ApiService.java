package com.example.walkpromote22.service;

import com.example.walkpromote22.data.dto.LocationDTO;
import com.example.walkpromote22.data.dto.RouteDTO;
import com.example.walkpromote22.data.dto.StepDTO;
import com.example.walkpromote22.data.dto.UserDTO;
import com.example.walkpromote22.data.model.User;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface ApiService {
    @POST("auth/login")
    Call<String> login(@Body UserDTO user);

    @POST("auth/register")
    Call<String> register(@Body UserDTO dto);
    @GET("auth/getUserByKey/{userKey}")
    Call<UserDTO> getUserByKey(@Path("userKey") String userKey);



    @POST("api/steps/upload")
    Call<Void> uploadSteps(@Body List<StepDTO> steps);

    /** 首次登录：拉取云端全部历史步数 */
    @GET("api/steps/{userKey}/history")
    Call<List<StepDTO>> getAllSteps(@Path("userKey") String userKey);



    @POST("routes")
    Call<Long> createRoute(@Body RouteDTO body);        // 返回服务器生成的 routeId

    @POST("locations/upload")
    Call<Void> uploadLocations(@Body List<LocationDTO> list);

    @GET("routes/AllRoutes")
    Call<List<RouteDTO>> getAllRoutes();

    @GET("locations/AllLocations")
    Call<List<LocationDTO>> getAllLocations();


}
