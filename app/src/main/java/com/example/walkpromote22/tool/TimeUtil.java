package com.example.walkpromote22.tool;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日期时间小工具喵♡
 * <p>
 * 默认返回 yyyy-MM-dd（设备本地语言和时区）
 */
public final class TimeUtil {

    /** 私有构造防止实例化 */
    private TimeUtil() {}

    /** 返回今天日期，格式 yyyy-MM-dd */
    public static String getCurrentDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date());
    }

    /** 自定义格式：例如传 "HH:mm:ss" */
    public static String getCurrentDate(String pattern) {
        return new SimpleDateFormat(pattern, Locale.getDefault())
                .format(new Date());
    }
}