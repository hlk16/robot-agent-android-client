package com.lhht.xiaozhi.managers;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 数据管理类，用于在Activity之间共享实时数据
 */
public class DataManager {
    private static final String PREF_NAME = "XiaozhiDetectionData";
    private static final String KEY_DELTA_X = "deltaX";
    private static final String KEY_DELTA_Y = "deltaY";
    private static final String KEY_CANVAS_CENTER_X = "canvasCenterX";
    private static final String KEY_CANVAS_CENTER_Y = "canvasCenterY";
    private static final String KEY_CIRCLE_CENTER_X = "circleCenterX";
    private static final String KEY_CIRCLE_CENTER_Y = "circleCenterY";
    private static final String KEY_CIRCLE_DETECTED = "circleDetected";
    private static final String KEY_LAST_UPDATE = "lastUpdate";
    
    // 道路检测相关键值
    private static final String KEY_ROAD_DISTANCE = "roadDistance";
    private static final String KEY_PID_OUTPUT = "pidOutput";
    private static final String KEY_ROAD_STATUS = "roadStatus";
    private static final String KEY_ROAD_DIRECTION = "roadDirection";
    private static final String KEY_DETECTION_ACTIVE = "detectionActive";
    
    private static DataManager instance;
    private SharedPreferences sharedPreferences;
    
    private DataManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public static synchronized DataManager getInstance(Context context) {
        if (instance == null) {
            instance = new DataManager(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 更新所有数据
     */
    public void updateData(double canvasCenterX, double canvasCenterY, 
                          Double circleCenterX, Double circleCenterY) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        
        editor.putFloat(KEY_CANVAS_CENTER_X, (float) canvasCenterX);
        editor.putFloat(KEY_CANVAS_CENTER_Y, (float) canvasCenterY);
        
        if (circleCenterX != null && circleCenterY != null) {
            editor.putFloat(KEY_CIRCLE_CENTER_X, circleCenterX.floatValue());
            editor.putFloat(KEY_CIRCLE_CENTER_Y, circleCenterY.floatValue());
            editor.putFloat(KEY_DELTA_X, (float) (circleCenterX - canvasCenterX));
            editor.putFloat(KEY_DELTA_Y, (float) (circleCenterY - canvasCenterY));
            editor.putBoolean(KEY_CIRCLE_DETECTED, true);
        } else {
            editor.putBoolean(KEY_CIRCLE_DETECTED, false);
        }
        
        editor.putLong(KEY_LAST_UPDATE, System.currentTimeMillis());
        editor.apply();
    }
    
    /**
     * 获取DeltaX值
     */
    public float getDeltaX() {
        return sharedPreferences.getFloat(KEY_DELTA_X, 0f);
    }
    
    /**
     * 获取DeltaY值
     */
    public float getDeltaY() {
        return sharedPreferences.getFloat(KEY_DELTA_Y, 0f);
    }
    
    /**
     * 获取画布中心X坐标
     */
    public float getCanvasCenterX() {
        return sharedPreferences.getFloat(KEY_CANVAS_CENTER_X, 0f);
    }
    
    /**
     * 获取画布中心Y坐标
     */
    public float getCanvasCenterY() {
        return sharedPreferences.getFloat(KEY_CANVAS_CENTER_Y, 0f);
    }
    
    /**
     * 获取圆心X坐标
     */
    public float getCircleCenterX() {
        return sharedPreferences.getFloat(KEY_CIRCLE_CENTER_X, 0f);
    }
    
    /**
     * 获取圆心Y坐标
     */
    public float getCircleCenterY() {
        return sharedPreferences.getFloat(KEY_CIRCLE_CENTER_Y, 0f);
    }
    
    /**
     * 是否检测到圆形
     */
    public boolean isCircleDetected() {
        return sharedPreferences.getBoolean(KEY_CIRCLE_DETECTED, false);
    }
    
    /**
     * 获取最后更新时间
     */
    public long getLastUpdateTime() {
        return sharedPreferences.getLong(KEY_LAST_UPDATE, 0);
    }
    
    /**
     * 数据是否有效（最近5秒内更新过）
     */
    public boolean isDataValid() {
        long currentTime = System.currentTimeMillis();
        long lastUpdate = getLastUpdateTime();
        return (currentTime - lastUpdate) < 5000; // 5秒内的数据认为有效
    }
    
    /**
     * 获取格式化的偏差信息
     */
    public String getFormattedDelta() {
        if (isCircleDetected() && isDataValid()) {
            return String.format("X偏差: %.1f, Y偏差: %.1f", getDeltaX(), getDeltaY());
        } else {
            return "未检测到目标";
        }
    }
    
    /**
     * 获取检测状态信息
     */
    public String getDetectionStatus() {
        if (!isDataValid()) {
            return "等待检测数据...";
        } else if (isCircleDetected()) {
            return "目标已检测";
        } else {
            return "未检测到目标";
        }
    }
    
    // ========== 道路检测相关方法 ==========
    
    /**
     * 设置道路距离
     */
    public void setRoadDistance(double distance) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat(KEY_ROAD_DISTANCE, (float) distance);
        editor.putLong(KEY_LAST_UPDATE, System.currentTimeMillis());
        editor.apply();
    }
    
    /**
     * 设置PID输出值
     */
    public void setPidOutput(double pidOutput) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat(KEY_PID_OUTPUT, (float) pidOutput);
        editor.apply();
    }
    
    /**
     * 设置道路状态
     */
    public void setRoadStatus(String status) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_ROAD_STATUS, status);
        editor.apply();
    }
    
    /**
     * 设置道路方向
     */
    public void setRoadDirection(String direction) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_ROAD_DIRECTION, direction);
        editor.apply();
    }
    
    /**
     * 设置检测激活状态
     */
    public void setDetectionActive(boolean active) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(KEY_DETECTION_ACTIVE, active);
        editor.apply();
    }
    
    /**
     * 获取道路距离
     */
    public float getRoadDistance() {
        return sharedPreferences.getFloat(KEY_ROAD_DISTANCE, 0f);
    }
    
    /**
     * 获取PID输出值
     */
    public float getPidOutput() {
        return sharedPreferences.getFloat(KEY_PID_OUTPUT, 0f);
    }
    
    /**
     * 获取道路状态
     */
    public String getRoadStatus() {
        return sharedPreferences.getString(KEY_ROAD_STATUS, "未检测");
    }
    
    /**
     * 获取道路方向
     */
    public String getRoadDirection() {
        return sharedPreferences.getString(KEY_ROAD_DIRECTION, "直行");
    }
    
    /**
     * 获取检测激活状态
     */
    public boolean isDetectionActive() {
        return sharedPreferences.getBoolean(KEY_DETECTION_ACTIVE, false);
    }
    
    /**
     * 获取格式化的道路检测信息
     */
    public String getFormattedRoadInfo() {
        if (isDataValid() && isDetectionActive()) {
            return String.format("距离: %.1f | PID: %.1f | %s | %s", 
                getRoadDistance(), getPidOutput(), getRoadStatus(), getRoadDirection());
        } else {
            return "道路检测未激活";
        }
    }
}