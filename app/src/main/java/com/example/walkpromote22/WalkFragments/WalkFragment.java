package com.example.walkpromote22.WalkFragments;

import static com.example.walkpromote22.tool.MapTool.LocationToLatLng;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.amap.api.maps.AMap;
import com.example.walkpromote22.data.dao.PathDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Path;
import com.example.walkpromote22.data.model.User;
import com.example.walkpromote22.R;
import com.example.walkpromote22.tool.MapTool;

import android.os.Bundle;
import androidx.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public class WalkFragment extends Fragment {

    private static final String TAG = "RunningFragment";

    // 统一使用成员变量来保存线程池和地图组件引用
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private MapTool mapTool;
    private Button toggleRunButton;


    private double totalDistance = 0f;

    private boolean isRunning = false;
    private String userKey;

    private UserDao userDao;
    private PathDao pathDao;
    private Path currentPath; // 当前跑步记录

    // 容器：显示数据的 ScrollView 内的 LinearLayout 和地图容器
    private LinearLayout fitnessDataContainer;
    private FrameLayout mapContainer;

    @SuppressLint("MissingInflatedId")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 加载 fragment_running.xml 布局
        View view = inflater.inflate(R.layout.fragment_running, container, false);






        // 获取开始/结束跑步按钮、数据展示容器和地图容器

        toggleRunButton = view.findViewById(R.id.btn_toggle_run);
        fitnessDataContainer = view.findViewById(R.id.fitness_data_container);


        mapContainer = view.findViewById(R.id.map_fragment_container);

        // 默认显示数据区域，隐藏地图容器
        if (mapContainer != null) {
            mapContainer.setVisibility(View.GONE);
        }
        if (fitnessDataContainer != null) {
            fitnessDataContainer.setVisibility(View.VISIBLE);
        }

        // 初始化数据库及 DAO
        AppDatabase db = AppDatabase.getDatabase(getContext());
        userDao = db.userDao();
        pathDao = db.pathDao();

        // 获取或初始化 userKey
        initializeUserKey();
        Log.e("ar",getArguments()+"");







        executorService.execute(() -> {
            List<Path> paths = pathDao.getPathsByUserKey(userKey);
            if (paths != null && !paths.isEmpty()) {
                // 倒序排序，确保最新记录在上
                paths.sort((p2, p1) -> Long.compare(p2.getPathId(), p1.getPathId()));
                requireActivity().runOnUiThread(() -> {
                    for (Path path : paths) {
                        addPathCard(path);
                    }
                });
            }
        });




        if (getArguments() != null) {
            long routeId = getArguments().getLong("route_id", -1);  // -1 为默认值
            List<Location> routeLocations = db.locationDao().getLocationsByRouteId(routeId);
            try {
                navigation(routeLocations);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
        // 设置开始/结束按钮点击事件
        toggleRunButton.setOnClickListener(v -> toggleRunning(new ArrayList<>()));
        //toggleSelect.setOnClickListener(v -> selectRoute());

        // 使用成员线程池获取历史路径数据，不再新建局部 executorService


        return view;
    }

    private void navigation(List<Location> routeLocations) throws Exception {

        if (routeLocations == null || routeLocations.isEmpty()) {
            Toast.makeText(getContext(), "No route data available", Toast.LENGTH_SHORT).show();
            return;
        }

        toggleRunning(routeLocations);


    }






    private void initializeUserKey() {
        SharedPreferences sharedPreferences = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        userKey = sharedPreferences.getString("USER_KEY", null);
        if (userKey == null) {
            userKey = UUID.randomUUID().toString();
            User newUser = new User(userKey, "--", 0.0f, 0.0f, 0);
            executorService.execute(() -> {
                userDao.insert(newUser);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("USER_KEY", userKey);
                editor.apply();
            });
        }
    }

    private void toggleRunning(List<Location> routeLocations) {
        if (!isRunning) {
            startRunning(routeLocations);
        } else {
            stopRunning();
        }
    }

    /**
     * 开始跑步：
     * 1. 隐藏数据展示区域，显示地图容器
     * 2. 后台创建新的 Path 记录，并加载 MapContainerView 进行实时定位
     */
    @SuppressLint("SetTextI18n")
    private void startRunning(List<Location> routeLocations) {
        isRunning = true;
        toggleRunButton.setText("Stop");
        Toast.makeText(getContext(), "Get moving", Toast.LENGTH_SHORT).show();

        if (fitnessDataContainer != null) {
            fitnessDataContainer.setVisibility(View.GONE);
        }
        if (mapContainer != null) {
            mapContainer.setVisibility(View.VISIBLE);
        }

        totalDistance = 0f;
        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            currentPath = new Path(userKey, startTime, 0,0,0,0);
            long generatedId = pathDao.insertPath(currentPath);
            currentPath.setPathId(generatedId);

            requireActivity().runOnUiThread(() -> {
                // 使用成员变量 mapContainerView
                try {
                    mapTool = new MapTool(getContext());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                mapContainer.removeAllViews();
                mapContainer.addView(mapTool);
                // 启动定位
                mapTool.onCreate();


                try {
                    if (routeLocations.isEmpty()) mapTool.startLocation(18f);
                    else{
                        mapTool.startLocation(17f,routeLocations);
                     //   mapContainerView.Navigation(routeLocations);
                        mapTool.drawRoute(LocationToLatLng(routeLocations), Color.BLUE);

                    }

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    /**
     * 结束跑步：
     * 1. 更新当前 Path 的结束时间
     * 2. 截图保存轨迹图，并将图片路径保存到 Path 中
     * 3. 移除 MapContainerView并隐藏地图容器
     * 4. 恢复显示数据展示区域
     * 5. 将最新跑步数据以卡片形式添加到数据容器最上方，同时显示轨迹图
     */
    @SuppressLint("SetTextI18n")
    private void stopRunning() {
        isRunning = false;
        toggleRunButton.setText("Launch");
        Toast.makeText(getContext(), "End", Toast.LENGTH_SHORT).show();

        totalDistance= mapTool.getTotalDistance();
        executorService.execute(() -> {
            if (currentPath != null) {
                long endTime = System.currentTimeMillis();
                currentPath.setEndTimestamp(endTime);
                pathDao.updatePath(currentPath);
            }

            requireActivity().runOnUiThread(() -> {
                if (mapTool != null && mapTool.getAMap() != null) {
                    // 适当延时后调用截图，确保地图绘制完成
                    mapTool.getAMap().getMapScreenShot(new AMap.OnMapScreenShotListener() {
                        @Override
                        public void onMapScreenShot(Bitmap bitmap) {
                            if (bitmap != null) {
                                String imagePath = saveBitmapToFile(bitmap);
                                if (imagePath != null && currentPath != null) {
                                    currentPath.setRouteImagePath(imagePath);
                                    executorService.execute(() -> pathDao.updatePath(currentPath));
                                }
                            }
                            cleanupMapAfterScreenshot();
                        }

                        @Override
                        public void onMapScreenShot(Bitmap bitmap, int status) {
                            // 这里 status == 0 表示截图成功
                            if (status == 0 && bitmap != null) {
                                String imagePath = saveBitmapToFile(bitmap);
                                if (imagePath != null && currentPath != null) {
                                    currentPath.setRouteImagePath(imagePath);
                                    executorService.execute(() -> pathDao.updatePath(currentPath));
                                }
                            } else {
                                Toast.makeText(getContext(), "截图失败", Toast.LENGTH_SHORT).show();
                            }
                            cleanupMapAfterScreenshot();
                        }
                    });
                } else {
                    cleanupMapAfterScreenshot();
                }
            });
        });
    }

    /**
     * 在截图完成后清理地图视图并恢复其他 UI 状态
     */
    private void cleanupMapAfterScreenshot() {
        if (mapTool != null) {
            mapTool.onDestroy();
            mapContainer.removeAllViews();
            mapTool = null;
        }
        fitnessDataContainer.setVisibility(View.VISIBLE);
        mapContainer.setVisibility(View.GONE);
        final double[] calory = {0};
        calculateCalories(new CaloriesCallback() {
            @Override
            public void onCaloriesCalculated(double calories) {
                calory[0] =calories;

            }
        });
        //pathDao.updatePath();
        executorService.execute(() ->{
            currentPath.setAverageSpeed(calculatePace());
            currentPath.setDistance(totalDistance/1000);
            currentPath.setCalories(calory[0]);
            pathDao.updatePath(currentPath);
        });
        addPathCard(currentPath);
    }


    /**
     * 根据 Path 数据生成卡片视图，并将其插入到 fitnessDataContainer 顶部
     * 如果该 Path 有保存的轨迹图，则在卡片下方显示图片
     */
    @SuppressLint("SetTextI18n")
    private void addPathCard(Path path) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View cardView = inflater.inflate(R.layout.item_path_data, fitnessDataContainer, false);

        TextView tvPathInfo = cardView.findViewById(R.id.tv_path_info);
        TextView tvPathTime = cardView.findViewById(R.id.tv_path_time);
        TextView tvPathPace = cardView.findViewById(R.id.tv_path_pace);
        TextView tvPathCalories = cardView.findViewById(R.id.tv_path_calories);
        TextView tvPathDistance = cardView.findViewById(R.id.tv_path_distance);
        Button btnDelete = cardView.findViewById(R.id.btn_delete);  // 获取删除按钮

        // 设置按钮点击事件来删除路径
        btnDelete.setOnClickListener(v -> {
            // 删除数据库中的对应路径数据
            executorService.execute(() -> {
                pathDao.deletePath(path);  // 从数据库删除
                requireActivity().runOnUiThread(() -> {
                    // 删除UI上的卡片
                    fitnessDataContainer.removeView(cardView);
                    Toast.makeText(getContext(), "Path deleted", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // 计算时间（秒转小时：分钟：秒）
        long time = (path.getEndTimestamp() - path.getStartTimestamp()) / 1000;
        int hours = (int) (time / 3600);
        int minutes = (int) ((time % 3600) / 60);
        int seconds = (int) (time % 60);
        String formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

        // 设置其他TextView的内容
        tvPathInfo.setText("Record: " + path.getPathId());
        tvPathTime.setText("Time: " + formattedTime);
        tvPathPace.setText("Average speed: " + (path.getAverageSpeed() > 0 ? String.format(Locale.getDefault(), "%.2f km/min", path.getAverageSpeed()) : "--"));
        tvPathCalories.setText("Calories: " + (path.getCalories() > 0 ? String.format(Locale.getDefault(), "%.2f calories", path.getCalories()) : "--"));
        tvPathDistance.setText("Distance: " + (path.getDistance() > 0 ? String.format(Locale.getDefault(), "%.2f m", path.getDistance()) : "--"));

        // 如果该 Path 有保存的轨迹图，则在卡片下方显示图片
        if (path.getRouteImagePath() != null && !path.getRouteImagePath().isEmpty()) {
            ImageView routeImageView = new ImageView(getContext());
            Bitmap bitmap = BitmapFactory.decodeFile(path.getRouteImagePath());
            if (bitmap != null) {
                routeImageView.setImageBitmap(bitmap);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                routeImageView.setLayoutParams(params);
                LinearLayout imageContainer = cardView.findViewById(R.id.image_container);
                if (imageContainer != null) {
                    imageContainer.addView(routeImageView);
                }
            }
        }

        // 将卡片添加到 fitnessDataContainer 中
        // 在添加之前检查 fitnessDataContainer 是否已包含该卡片
        // 设置每个卡片的唯一标识
        cardView.setTag(path.getPathId());

// 检查是否已经添加过该卡片
        if (fitnessDataContainer.findViewWithTag(path.getPathId()) == null) {
            fitnessDataContainer.addView(cardView, 0);  // 只有未添加的路径才会加入
        }


    }



    // 示例方法：计算卡路里
    public interface CaloriesCallback {
        void onCaloriesCalculated(double calories);
    }

    // 计算热量消耗（单位：卡路里）
    public void calculateCalories(CaloriesCallback callback) {
        executorService.execute(() -> {
            try {

                long time= calculateDuration();
                double speed = totalDistance*3.6 /time;//time单位秒，距离单位是米
                // 获取用户体重
                float weight = userDao.getUserByKey(userKey).getWeight();
                double met;
                if (speed < 3.0) {//这里要求的是km/h
                    met = 2.5; // 慢速步行
                } else if (speed >= 3.0 && speed < 5.0) {
                    met = 3.8; // 中等步行
                } else {
                    met = 5.0; // 快速步行
                }
                // 计算热量消耗
                double calories = met * weight * time;
                // 计算完成后，通过回调传递结果
                requireActivity().runOnUiThread(() -> callback.onCaloriesCalculated(calories));
            } catch (Exception e) {

                requireActivity().runOnUiThread(() -> callback.onCaloriesCalculated(0));
            }
        });
    }



    // 示例方法：计算跑步距离


    // 示例方法：计算配速（分钟/公里）
    private double calculatePace() {
        double durationMinutes = calculateDuration() / 60.0;
        double distanceKm = totalDistance;
        return distanceKm > 0 ? durationMinutes / distanceKm : 0.0;
    }

    // 计算跑步持续时间（秒）
    private long calculateDuration() {
        if (currentPath != null && currentPath.getEndTimestamp() > currentPath.getStartTimestamp()) {
            return (currentPath.getEndTimestamp() - currentPath.getStartTimestamp()) / 1000;
        }
        return 0;
    }

    private String formatTime(long durationSeconds) {
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * 将 Bitmap 保存为 PNG 文件，并返回保存的文件路径
     */
    private String saveBitmapToFile(Bitmap bitmap) {
        File storageDir = requireContext().getExternalFilesDir(null);
        String fileName = "running_path_" + System.currentTimeMillis() + ".png";
        File file = new File(storageDir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            Toast.makeText(getContext(), "轨迹图已保存到：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return file.getAbsolutePath();
        } catch (Exception e) {

            Toast.makeText(getContext(), "保存轨迹图失败", Toast.LENGTH_SHORT).show();
        }
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 在 Fragment 销毁时关闭线程池

    }

}
