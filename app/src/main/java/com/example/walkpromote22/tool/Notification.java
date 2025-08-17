package com.example.walkpromote22.tool;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.example.walkpromote22.R;

public class Notification {

    /**
     * 显示自定义的 Toast
     *
     * @param context 上下文
     * @param message 显示的消息
     */
    public static void showCustomToast(Context context, String message) {
        // 获取布局填充器

        LayoutInflater inflater = LayoutInflater.from(context);

        // 加载自定义Toast布局
        View layout = inflater.inflate(R.layout.custom_toast, null);

        // 获取TextView并设置消息
        TextView text = layout.findViewById(R.id.toast_text);
        text.setText(message);

        // 创建Toast对象
        Toast toast = new Toast(context);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setView(layout);
        toast.setGravity(android.view.Gravity.CENTER, 0, 0); // 居中显示

        // 显示Toast
        toast.show();
    }
}
