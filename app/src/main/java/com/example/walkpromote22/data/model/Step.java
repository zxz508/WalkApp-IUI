package com.example.walkpromote22.data.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

import androidx.room.ForeignKey;


@Entity(tableName = "steps",
        indices = {@Index("userKey")},
        foreignKeys = @ForeignKey(entity = User.class,
                parentColumns = "userKey",
                childColumns = "userKey",
                onDelete = ForeignKey.CASCADE))
public class Step {
    @PrimaryKey(autoGenerate = true)
    private long stepId;

    @NonNull
    private String userKey; // 外键，关联用户

    private String date;    // 格式：YYYY-MM-DD
    @androidx.annotation.IntRange(from = 0)
    private int stepCount;


    public float distance; // 新增字段，用于存储步行距离,km

    public Step(@NonNull String userKey, String date, int stepCount, float distance) {
        this.userKey = userKey;
        this.date = date;
        this.stepCount = stepCount;
        this.distance=distance;
    }

    public long getStepId() {
        return stepId;
    }

    public void setStepId(long stepId) {
        this.stepId = stepId;
    }

    public void setUserKey(@NonNull String userKey) {
        this.userKey = userKey;
    }

    @NonNull
    public String getUserKey() {
        return userKey;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public int getStepCount() {
        if(stepCount<0){
            setStepCount(0);
            return 0;
        }
        return stepCount;
    }

    public float getDistance(){

        if (distance<0){
            setDistance(0);
            return 0;
        }
        return distance;
    }

    public void setDistance(float distance){ this.distance=distance;}

    public void setStepCount(int stepCount) {
        if (stepCount < 0) {
            this.stepCount = 0; // 或者抛异常
        } else {
            this.stepCount = stepCount;
        }
    }
}

