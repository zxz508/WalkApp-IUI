package com.example.walkpromote22.ChatbotFragments;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ChartHelper {

    public static LineChart generateLineChart(Context context, Map<String, Integer> stepsMap, Map<String, Float> distanceMap, Map<String, Float> calorieMap) {
        LineChart lineChart = new LineChart(context);

        // 外观基础设定
        int minHeightPx = dp2px(context, 280);  // 统一Chart高度
        lineChart.setMinimumHeight(minHeightPx);
        lineChart.setBackgroundColor(Color.WHITE);
        lineChart.setPadding(dp2px(context,16), dp2px(context,12), dp2px(context,16), dp2px(context,12));
        lineChart.setExtraOffsets(10, 10, 20, 20);

        // 日期排序
        List<String> dates = new ArrayList<>(stepsMap.keySet());
        Collections.sort(dates);

        ArrayList<Entry> stepsEntries = new ArrayList<>();
        ArrayList<Entry> distanceEntries = new ArrayList<>();
        ArrayList<Entry> calorieEntries = new ArrayList<>();

        for (int i = 0; i < dates.size(); i++) {
            String date = dates.get(i);
            stepsEntries.add(new Entry(i, stepsMap.get(date)));
            distanceEntries.add(new Entry(i, distanceMap.get(date) / 1000f));
            calorieEntries.add(new Entry(i, calorieMap.get(date)));
        }

        // DataSets样式
        LineDataSet stepsDataSet = makeDataSet(stepsEntries, "Steps", "#3A8DFF", "#CCE3F2FF");
        LineDataSet distanceDataSet = makeDataSet(distanceEntries, "Distance (km)", "#43CD80", "#CCDCF7EC");
        LineDataSet calorieDataSet = makeDataSet(calorieEntries, "Calories", "#FF5656", "#FFFFEBEB");

        LineData lineData = new LineData(stepsDataSet, distanceDataSet, calorieDataSet);
        lineData.setDrawValues(false);

        lineChart.setData(lineData);

        // X轴美化
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(dates.size());
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.parseColor("#333333"));
        xAxis.setTextSize(14f);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(true);
        xAxis.setAxisLineWidth(1.2f);
        xAxis.setAxisLineColor(Color.parseColor("#B0B0B0"));
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                int idx = Math.round(value);
                if (idx >= 0 && idx < dates.size()) return dates.get(idx).substring(5);
                return "";
            }
        });

        // Y轴美化
        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setSpaceTop(15f); // 顶部多留15%空白
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#F0F0F0"));
        leftAxis.setTextColor(Color.parseColor("#666666"));
        leftAxis.setTextSize(13f);
        leftAxis.setAxisMinimum(0f);

        YAxis rightAxis = lineChart.getAxisRight();
        rightAxis.setEnabled(false);

        // 图例美化
        Legend legend = lineChart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.RIGHT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextSize(14f);
        legend.setFormSize(13f);
        legend.setTextColor(Color.parseColor("#444444"));

        // 去掉描述文本
        lineChart.getDescription().setEnabled(false);

        // 动画
        lineChart.animateY(900, Easing.EaseInCubic);

        // 其他
        lineChart.setNoDataText("暂无数据");
        lineChart.setNoDataTextColor(Color.parseColor("#B0B0B0"));

        // 刷新
        lineChart.invalidate();

        return lineChart;
    }

    // 统一曲线美化
    private static LineDataSet makeDataSet(List<Entry> entries, String label, String lineColor, String fillColor) {
        LineDataSet dataSet = new LineDataSet(entries, label);
        dataSet.setColor(Color.parseColor(lineColor));
        dataSet.setCircleColor(Color.parseColor(lineColor));
        dataSet.setLineWidth(2.8f);
        dataSet.setCircleRadius(5.2f);
        dataSet.setCircleHoleRadius(2.4f);
        dataSet.setDrawCircleHole(true);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(Color.parseColor(fillColor));
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 平滑曲线
        dataSet.setHighLightColor(Color.parseColor("#BDBDBD"));
        dataSet.setHighlightLineWidth(1.2f);
        dataSet.setDrawHighlightIndicators(true);
        return dataSet;
    }

    // dp转px
    private static int dp2px(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
