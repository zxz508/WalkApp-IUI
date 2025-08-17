/*
 * Copyright (C) 2014 Samsung Electronics Co., Ltd. All rights reserved.
 *
 * Mobile Communication Division,
 * Digital Media & Communications Business, Samsung Electronics Co., Ltd.
 *
 * This software and its documentation are confidential and proprietary
 * information of Samsung Electronics Co., Ltd.  No part of the software and
 * documents may be copied, reproduced, transmitted, translated, or reduced to
 * any electronic medium or machine-readable form without the prior written
 * consent of Samsung Electronics.
 *
 * Samsung Electronics makes no representations with respect to the contents,
 * and assumes no responsibility for any errors that might appear in the
 * software and documents. This publication and the contents hereof are subject
 * to change without notice.
 */

package com.example.walkpromote22.tool;

import static android.content.ContentValues.TAG;

import com.samsung.android.sdk.healthdata.HealthConstants.StepCount;
import com.samsung.android.sdk.healthdata.HealthData;
import com.samsung.android.sdk.healthdata.HealthDataObserver;
import com.samsung.android.sdk.healthdata.HealthDataResolver;
import com.samsung.android.sdk.healthdata.HealthDataResolver.AggregateRequest;
import com.samsung.android.sdk.healthdata.HealthDataResolver.AggregateRequest.AggregateFunction;
import com.samsung.android.sdk.healthdata.HealthDataResolver.AggregateResult;
import com.samsung.android.sdk.healthdata.HealthDataStore;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;



public class StepCountReporter {
    private final HealthDataStore mStore;
    private final StepCountObserver mStepCountObserver;
    private final HealthDataResolver mHealthDataResolver;
    private final HealthDataObserver mHealthDataObserver;

    public StepCountReporter(@NonNull HealthDataStore store, @NonNull StepCountObserver listener,
                             @Nullable Handler resultHandler) {

        mStore = store;
        mStepCountObserver = listener;

        mHealthDataResolver = new HealthDataResolver(mStore, resultHandler);
        mHealthDataObserver = new HealthDataObserver(resultHandler) {

            // Update the step count when a change event is received
            @Override
            public void onChange(String dataTypeName) {
                Log.d(TAG, "Observer receives a data changed event");
                readTodayStepCount();
            }
        };

    }


    public void start() {
        // Register an observer to listen changes of step count and get today step count

        HealthDataObserver.addObserver(mStore, StepCount.HEALTH_DATA_TYPE, mHealthDataObserver);

        readTodayStepCount();
    }

    public void stop() {
        HealthDataObserver.removeObserver(mStore, mHealthDataObserver);
    }

    // Read the today's step count on demand
    private void readTodayStepCount() {
        // Set time range from start time of today to the current time
        long startTime = getUtcStartOfDay(System.currentTimeMillis(), TimeZone.getDefault());
        long endTime = startTime + TimeUnit.DAYS.toMillis(1);

        AggregateRequest request = new AggregateRequest.Builder()
            .setDataType(StepCount.HEALTH_DATA_TYPE)
            .addFunction(AggregateFunction.SUM, StepCount.COUNT, "total_step")
            .setLocalTimeRange(StepCount.START_TIME, StepCount.TIME_OFFSET, startTime, endTime)
            .build();

        try {
            mHealthDataResolver.aggregate(request).setResultListener(aggregateResult -> {
                try (AggregateResult result = aggregateResult) {
                    Iterator<HealthData> iterator = result.iterator();
                    if (iterator.hasNext()) {
                        mStepCountObserver.onChanged(iterator.next().getInt("total_step"));
                    }

                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Getting step count fails.", e);
        }
    }

    private long getUtcStartOfDay(long time, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(time);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);
        int date = cal.get(Calendar.DATE);

        cal.setTimeZone(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month);
        cal.set(Calendar.DATE, date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        return cal.getTimeInMillis();
    }

    public interface StepCountObserver {
        void onChanged(int count);
    }
}
