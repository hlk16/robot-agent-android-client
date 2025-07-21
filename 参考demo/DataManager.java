package com.example.tablerobot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 数据管理类，用于在Activity之间共享实时数据
 */
public class DataManager {
    private static final String PREF_NAME = "TableRobotData";
    private static final String KEY_DELTA_X = "deltaX";
    private static final String KEY_DELTA_Y = "deltaY";
    private static final String KEY_CANVAS_CENTER_X = "canvasCenterX";
    private static final String KEY_CANVAS_CENTER_Y = "canvasCenterY";
    private static final String KEY_CIRCLE_CENTER_X = "circleCenterX";
    private static final String KEY_CIRCLE_CENTER_Y = "circleCenterY";
    private static final String KEY_CIRCLE_DETECTED = "circleDetected";
    private static final String KEY_LAST_UPDATE = "lastUpdate";
    
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
}