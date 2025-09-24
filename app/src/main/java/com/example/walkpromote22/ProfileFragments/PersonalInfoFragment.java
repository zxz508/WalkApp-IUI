package com.example.walkpromote22.ProfileFragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;


import com.example.walkpromote22.R;
import com.example.walkpromote22.Activities.MainActivity;
import com.example.walkpromote22.TodayFragments.TodayFragment;
import com.example.walkpromote22.data.dao.PathDao;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.database.AppDatabase;
import com.example.walkpromote22.data.model.User;

import java.util.Calendar;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PersonalInfoFragment extends Fragment {

    private EditText etHeight, etWeight;
    private TextView tvHeight, tvWeight, tvAge, tvGender;
    private LinearLayout inputLayout, displayLayout;
    // 三个 NumberPicker 控件
    private NumberPicker npYear, npMonth, npDay;
    private Button btnMale, btnFemale;
    private Button btnSave, btnClear, btnModify;
    private SharedPreferences sharedPreferences;
    private String userKey;

    // 保存用户选择的出生日期
    private int selectedYear = 0, selectedMonth = 0, selectedDay = 1;
    // 记录性别选择
    private String gender = "";

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private UserDao userDao;
    private PathDao pathDao;
    private StepDao stepDao;

    public PersonalInfoFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 使用修改后的布局文件
        return inflater.inflate(R.layout.fragment_personal_info, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化数据库和 DAO
        db = AppDatabase.getDatabase(getContext());
        userDao = db.userDao();
        pathDao = db.pathDao();
        stepDao = db.stepDao();

        // 获取输入区域控件
        inputLayout = view.findViewById(R.id.input_layout);
        etHeight = view.findViewById(R.id.et_height);
        etWeight = view.findViewById(R.id.et_weight);
        // 获取三个 NumberPicker 控件
        npYear = view.findViewById(R.id.np_year);
        npMonth = view.findViewById(R.id.np_month);
        npDay = view.findViewById(R.id.np_day);

        btnMale = view.findViewById(R.id.btn_male);
        btnFemale = view.findViewById(R.id.btn_female);
        btnSave = view.findViewById(R.id.btn_save);

        // 获取展示区域控件
        displayLayout = view.findViewById(R.id.display_layout);
        tvHeight = view.findViewById(R.id.tv_height);
        tvWeight = view.findViewById(R.id.tv_weight);
        tvAge = view.findViewById(R.id.tv_age);
        tvGender = view.findViewById(R.id.tv_gender);
        btnClear = view.findViewById(R.id.btn_clear);
        btnModify = view.findViewById(R.id.btn_modify);

        // 获取 SharedPreferences 中的用户标识
        sharedPreferences = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        userKey = sharedPreferences.getString("USER_KEY", null);

        // 初始化 NumberPicker 设置
        Calendar c = Calendar.getInstance();
        int currentYear = c.get(Calendar.YEAR);
        npYear.setMinValue(1900);
        npYear.setMaxValue(currentYear);
        // 默认设置为当前年份减 30
        npYear.setValue(currentYear - 30);
        npMonth.setMinValue(1);
        npMonth.setMaxValue(12);
        npMonth.setValue(1);
        // 初始化 npDay 的值由当前 npYear 和 npMonth 决定
        updateDayPicker();

        // 当年份或月份改变时，动态更新日期选择器的最大值
        npYear.setOnValueChangedListener((picker, oldVal, newVal) -> updateDayPicker());
        npMonth.setOnValueChangedListener((picker, oldVal, newVal) -> updateDayPicker());

        // 若 userKey 不存在，则新建用户并保存默认信息
        if (userKey == null) {
            userKey = UUID.randomUUID().toString();
            User newUser = new User(userKey, "--", 0.0f, 0.0f, 0);
            executorService.execute(() -> {
                userDao.insert(newUser);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("USER_KEY", userKey);
                editor.apply();
                requireActivity().runOnUiThread(() -> showDisplayLayout(newUser));
            });
        } else {
            // 检查数据库中是否已存在用户数据
            executorService.execute(() -> {
                User existingUser = userDao.getUserByKey(userKey);
                if (existingUser != null) {
                    requireActivity().runOnUiThread(() -> showDisplayLayout(existingUser));
                } else {
                    requireActivity().runOnUiThread(() -> {
                        inputLayout.setVisibility(View.VISIBLE);
                        displayLayout.setVisibility(View.GONE);
                    });
                }
            });
        }

        // 性别选择事件
        btnMale.setOnClickListener(v -> {
            gender = "Male";
            btnMale.setSelected(true);
            btnFemale.setSelected(false);
            Toast.makeText(getActivity(), "Male chosen", Toast.LENGTH_SHORT).show();
        });
        btnFemale.setOnClickListener(v -> {
            gender = "Female";
            btnFemale.setSelected(true);
            btnMale.setSelected(false);
            Toast.makeText(getActivity(), "Female chosen", Toast.LENGTH_SHORT).show();
        });

        // 保存按钮事件：读取输入数据、计算年龄并保存到数据库
        btnSave.setOnClickListener(v -> {
            String heightStr = etHeight.getText().toString().trim();
            String weightStr = etWeight.getText().toString().trim();
            // 从 NumberPicker 中获取用户选择的出生日期
            selectedYear = npYear.getValue();
            selectedMonth = npMonth.getValue();
            selectedDay = npDay.getValue();

            if (TextUtils.isEmpty(heightStr) || TextUtils.isEmpty(weightStr)
                    || selectedYear == 0 || selectedMonth == 0 || selectedDay == 0 || TextUtils.isEmpty(gender)) {
                Toast.makeText(getActivity(), "请填写完整信息", Toast.LENGTH_SHORT).show();
                return;
            }

            float height = Float.parseFloat(heightStr);
            float weight = Float.parseFloat(weightStr);
            Calendar today = Calendar.getInstance();
            int age = today.get(Calendar.YEAR) - selectedYear;
            if ((today.get(Calendar.MONTH) + 1) < selectedMonth ||
                    ((today.get(Calendar.MONTH) + 1) == selectedMonth && today.get(Calendar.DAY_OF_MONTH) < selectedDay)) {
                age--;
            }
            int finalAge = age;

            executorService.execute(() -> {
                User user = userDao.getUserByKey(userKey);
                if (user != null) {
                    user.setGender(gender);
                    user.setHeight(height);
                    user.setWeight(weight);
                    user.setAge(finalAge);
                    userDao.updateUser(user);
                    User updatedUser = userDao.getUserByKey(userKey);
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getActivity(), "Success", Toast.LENGTH_SHORT).show();
                        showDisplayLayout(updatedUser);
                        MainActivity mainActivity = (MainActivity) getActivity();
                        if (mainActivity != null) {
                            mainActivity.updateNavigationSelection(R.id.nav_home, new TodayFragment());
                        }
                    });
                } else {
                    user = new User(userKey, gender, height, weight, finalAge);
                    userDao.insert(user);
                    User finalUser = user;
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getActivity(), "Success", Toast.LENGTH_SHORT).show();
                        showDisplayLayout(finalUser);
                    });
                }
            });
        });

        // 修改按钮事件：切换回输入区域，并将已有信息填入
        btnModify.setOnClickListener(v -> {
            executorService.execute(() -> {
                User user = userDao.getUserByKey(userKey);
                if (user != null) {
                    requireActivity().runOnUiThread(() -> {
                        etHeight.setText(user.getHeight() > 0 ? String.valueOf(user.getHeight()) : "10");
                        etWeight.setText(user.getWeight() > 0 ? String.valueOf(user.getWeight()) : "10");
                        // 重置 NumberPicker 为默认值（当前年份-30、1月、1日）
                        npYear.setValue(currentYear - 30);
                        npMonth.setValue(1);
                        updateDayPicker(); // 更新 npDay 也跟随重置
                        // 恢复性别按钮状态
                        if ("Male".equals(user.getGender())) {
                            gender = "Male";
                            btnMale.setSelected(true);
                            btnFemale.setSelected(false);
                        } else if ("Female".equals(user.getGender())) {
                            gender = "Female";
                            btnFemale.setSelected(true);
                            btnMale.setSelected(false);
                        } else {
                            gender = "Male";
                            btnMale.setSelected(false);
                            btnFemale.setSelected(false);
                        }
                        displayLayout.setVisibility(View.GONE);
                        inputLayout.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        // 清除按钮事件：删除用户数据
        btnClear.setOnClickListener(v -> {
            executorService.execute(() -> {
                User user = userDao.getUserByKey(userKey);
                if (user != null) {
                    userDao.deleteUser(user);
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getActivity(), "信息已清除", Toast.LENGTH_SHORT).show();
                        displayLayout.setVisibility(View.GONE);
                        inputLayout.setVisibility(View.VISIBLE);
                    });
                } else {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getActivity(), "清除失败", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
    }

    /**
     * 根据当前 npYear 和 npMonth 的值，更新 npDay 的最大值。
     */
    private void updateDayPicker() {
        int year = npYear.getValue();
        int month = npMonth.getValue();
        Calendar cal = Calendar.getInstance();
        // 月份减1，因为 Calendar 的月份从 0 开始
        cal.set(year, month - 1, 1);
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        npDay.setMaxValue(maxDay);
        // 如果当前选中的天数超过新的最大值，则将其设置为最大值
        if (npDay.getValue() > maxDay) {
            npDay.setValue(maxDay);
        }
    }

    // 展示用户数据到展示区域
    private void showDisplayLayout(User user) {
        String displayGender = !"--".equals(user.getGender()) ? user.getGender() : "--";
        String displayHeight = user.getHeight() > 0 ? user.getHeight() + " cm" : "--";
        String displayWeight = user.getWeight() > 0 ? user.getWeight() + " kg" : "--";
        // 当身高和体重有效时，即使年龄为 0 也显示 0 岁
        String displayAge = (user.getHeight() > 0 && user.getWeight() > 0) ? user.getAge() + " " : "--";

        tvGender.setText("Gender：" + displayGender);
        tvHeight.setText("Height：" + displayHeight);
        tvWeight.setText("Weight：" + displayWeight);
        tvAge.setText("Age：" + displayAge);

        displayLayout.setVisibility(View.VISIBLE);
        inputLayout.setVisibility(View.GONE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
