package com.example.walkpromote22.ProfileFragments;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;

import com.example.walkpromote22.DataCollection.CollectionFragment;
import com.example.walkpromote22.R;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.FragmentTransaction;
import androidx.fragment.app.FragmentManager;

public class ProfileFragment extends Fragment {

    private View buttonsLayout;
    private TextView navProfile;
    private Button btnPersonalInfo;

    public ProfileFragment() {
        // Required empty public constructor
    }

    public void triggerPersonalInfoButtonClick() {
        if (btnPersonalInfo != null) {
            btnPersonalInfo.performClick();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate 布局，包含按钮区域和 Fragment 容器
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }



    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        // 获取按钮区域的布局引用
        buttonsLayout = view.findViewById(R.id.profile_buttons);


        Button btnPersonalInfo = view.findViewById(R.id.btn_personal_info);
        Button btnHelpCenter = view.findViewById(R.id.btn_help_center);
        Button btnSettings = view.findViewById(R.id.btn_settings);
        Button btnLogout = view.findViewById(R.id.btn_logout);


        btnHelpCenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                buttonsLayout.setVisibility(View.GONE);
                CollectionFragment collectionFragment = new CollectionFragment();
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container_profile, collectionFragment);
                transaction.addToBackStack(null);
                transaction.commit();

            }
        });

        btnPersonalInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 隐藏按钮区域
                buttonsLayout.setVisibility(View.GONE);

                // 加载 PersonalInfoFragment 到 fragment_container_profile 中
                PersonalInfoFragment personalInfoFragment = new PersonalInfoFragment();
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container_profile, personalInfoFragment);
                transaction.addToBackStack(null);
                transaction.commit();
            }
        });

        // TODO: 其他按钮点击事件可根据需要实现

        // 监听子Fragment返回栈变化，当没有子Fragment时显示按钮区域
        getChildFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                if (getChildFragmentManager().getBackStackEntryCount() == 0) {
                    buttonsLayout.setVisibility(View.VISIBLE);
                }
            }
        });
    }





}
