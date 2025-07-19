package com.lhht.xiaozhi.managers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.IBinder;
import android.util.Log;

import com.lhht.xiaozhi.services.NavigationBackgroundService;

/**
 * 导航服务管理器
 * 用于管理NavigationBackgroundService的启动、停止和数据获取
 */
public class NavigationServiceManager {
    
    private static final String TAG = "NavigationServiceManager";
    private static NavigationServiceManager instance;
    
    private Context context;
    private NavigationBackgroundService navigationService;
    private boolean isServiceBound = false;
    
    // 服务连接监听器
    public interface ServiceConnectionListener {
        void onServiceConnected();
        void onServiceDisconnected();
    }
    
    private ServiceConnectionListener connectionListener;
    
    // 服务连接
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NavigationBackgroundService.NavigationBinder binder = 
                (NavigationBackgroundService.NavigationBinder) service;
            navigationService = binder.getService();
            isServiceBound = true;
            Log.d(TAG, "导航服务已连接");
            
            if (connectionListener != null) {
                connectionListener.onServiceConnected();
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            navigationService = null;
            isServiceBound = false;
            Log.d(TAG, "导航服务已断开");
            
            if (connectionListener != null) {
                connectionListener.onServiceDisconnected();
            }
        }
    };
    
    private NavigationServiceManager(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized NavigationServiceManager getInstance(Context context) {
        if (instance == null) {
            instance = new NavigationServiceManager(context);
        }
        return instance;
    }
    
    /**
     * 设置服务连接监听器
     */
    public void setServiceConnectionListener(ServiceConnectionListener listener) {
        this.connectionListener = listener;
    }
    
    /**
     * 启动并绑定导航服务
     */
    public void startAndBindService() {
        Intent serviceIntent = new Intent(context, NavigationBackgroundService.class);
        
        // 启动前台服务
        context.startForegroundService(serviceIntent);
        
        // 绑定服务
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        
        Log.d(TAG, "导航服务启动并绑定");
    }
    
    /**
     * 停止并解绑导航服务
     */
    public void stopAndUnbindService() {
        if (isServiceBound) {
            context.unbindService(serviceConnection);
            isServiceBound = false;
        }
        
        Intent serviceIntent = new Intent(context, NavigationBackgroundService.class);
        context.stopService(serviceIntent);
        
        navigationService = null;
        Log.d(TAG, "导航服务停止并解绑");
    }
    
    /**
     * 检查服务是否已连接
     */
    public boolean isServiceConnected() {
        return isServiceBound && navigationService != null;
    }
    
    /**
     * 启动导航到指定目的地
     */
    public boolean startNavigationToDestination(String destinationName) {
        if (!isServiceConnected()) {
            Log.w(TAG, "服务未连接，无法启动导航");
            return false;
        }
        
        navigationService.startNavigationToDestination(destinationName);
        Log.d(TAG, "启动导航到: " + destinationName);
        return true;
    }
    
    /**
     * 停止导航
     */
    public boolean stopNavigation() {
        if (!isServiceConnected()) {
            Log.w(TAG, "服务未连接，无法停止导航");
            return false;
        }
        
        navigationService.stopNavigation();
        Log.d(TAG, "停止导航");
        return true;
    }
    
    /**
     * 获取到目的地的距离（米）
     * @return 距离（米），-1表示无数据
     */
    public int getDistanceToDestination() {
        if (!isServiceConnected()) {
            return -1;
        }
        return navigationService.getDistanceToDestination();
    }
    
    /**
     * 获取当前前进方向
     * @return 方向描述（如"直行"、"左转"等）
     */
    public String getCurrentDirection() {
        if (!isServiceConnected()) {
            return "未知";
        }
        return navigationService.getCurrentDirection();
    }
    
    /**
     * 获取到下一个转向点的距离（米）
     * @return 距离（米），-1表示无数据
     */
    public int getDistanceToNextTurn() {
        if (!isServiceConnected()) {
            return -1;
        }
        return navigationService.getDistanceToNextTurn();
    }
    
    /**
     * 获取当前道路名称
     */
    public String getCurrentRoadName() {
        if (!isServiceConnected()) {
            return "未知";
        }
        return navigationService.getCurrentRoadName();
    }
    
    /**
     * 获取下一条道路名称
     */
    public String getNextRoadName() {
        if (!isServiceConnected()) {
            return "未知";
        }
        return navigationService.getNextRoadName();
    }
    
    /**
     * 检查是否正在导航
     */
    public boolean isNavigating() {
        if (!isServiceConnected()) {
            return false;
        }
        return navigationService.isNavigating();
    }
    
    /**
     * 获取导航服务实例（用于调试）
     */
    public NavigationBackgroundService getNavigationService() {
        return navigationService;
    }
    
    /**
     * 获取当前位置
     */
    public Location getCurrentLocation() {
        if (!isServiceConnected()) {
            return null;
        }
        return navigationService.getCurrentLocation();
    }
    
    /**
     * 获取导航信息摘要
     */
    public String getNavigationSummary() {
        if (!isServiceConnected() || !isNavigating()) {
            return "导航未启动";
        }
        
        int distanceToDestination = getDistanceToDestination();
        String direction = getCurrentDirection();
        int distanceToNext = getDistanceToNextTurn();
        String currentRoad = getCurrentRoadName();
        
        StringBuilder summary = new StringBuilder();
        summary.append("当前道路: ").append(currentRoad).append("\n");
        summary.append("前进方向: ").append(direction).append("\n");
        
        if (distanceToNext > 0) {
            if (distanceToNext >= 1000) {
                summary.append("下一转向: ").append(String.format("%.1fkm", distanceToNext / 1000.0)).append("\n");
            } else {
                summary.append("下一转向: ").append(distanceToNext).append("m\n");
            }
        }
        
        if (distanceToDestination > 0) {
            if (distanceToDestination >= 1000) {
                summary.append("剩余距离: ").append(String.format("%.1fkm", distanceToDestination / 1000.0));
            } else {
                summary.append("剩余距离: ").append(distanceToDestination).append("m");
            }
        }
        
        return summary.toString();
    }
    
    /**
     * 格式化距离显示
     */
    public static String formatDistance(int distanceInMeters) {
        if (distanceInMeters < 0) {
            return "--";
        }
        
        if (distanceInMeters >= 1000) {
            return String.format("%.1fkm", distanceInMeters / 1000.0);
        } else {
            return distanceInMeters + "m";
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stopAndUnbindService();
        connectionListener = null;
        instance = null;
    }
}