package com.lhht.xiaozhi.managers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.lhht.xiaozhi.services.NavigationBackgroundService;

/**
 * 导航服务管理器
 * 用于管理NavigationBackgroundService的启动、停止和数据获取
 * 支持自动重连机制
 */
public class NavigationServiceManager {
    
    private static final String TAG = "NavigationServiceManager";
    private static NavigationServiceManager instance;
    
    // 重连配置
    private static final int MAX_RECONNECT_ATTEMPTS = 5;  // 最大重连次数
    private static final long INITIAL_RECONNECT_DELAY = 1000;  // 初始重连延迟（毫秒）
    private static final long MAX_RECONNECT_DELAY = 30000;  // 最大重连延迟（毫秒）
    private static final double BACKOFF_MULTIPLIER = 2.0;  // 指数退避倍数
    
    private Context context;
    private NavigationBackgroundService navigationService;
    private boolean isServiceBound = false;
    private Handler mainHandler;
    
    // 重连相关状态
    private int reconnectAttempts = 0;
    private boolean isReconnecting = false;
    private boolean shouldReconnect = true;  // 是否应该自动重连
    private long currentReconnectDelay = INITIAL_RECONNECT_DELAY;
    private Runnable reconnectRunnable;
    
    // 连接监控
    private static final long CONNECTION_CHECK_INTERVAL = 10000;  // 连接检查间隔（毫秒）
    private Runnable connectionCheckRunnable;
    private boolean isMonitoringConnection = false;
    
    // 服务连接监听器
    public interface ServiceConnectionListener {
        void onServiceConnected();
        void onServiceDisconnected();
        void onReconnecting(int attempt, int maxAttempts);
        void onReconnectFailed(int totalAttempts);
        void onReconnectSuccess(int attempts);
        void onConnectionLost();  // 新增：连接丢失回调
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
            
            // 重连成功，重置重连状态
            if (isReconnecting) {
                isReconnecting = false;
                Log.i(TAG, "导航服务重连成功，尝试次数: " + reconnectAttempts);
                if (connectionListener != null) {
                    connectionListener.onReconnectSuccess(reconnectAttempts);
                }
                resetReconnectState();
            } else {
                Log.d(TAG, "导航服务已连接");
            }
            
            if (connectionListener != null) {
                connectionListener.onServiceConnected();
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "导航服务意外断开连接");
            navigationService = null;
            isServiceBound = false;
            
            if (connectionListener != null) {
                connectionListener.onServiceDisconnected();
            }
            
            // 如果应该重连且不是主动断开，则启动重连
            if (shouldReconnect && !isReconnecting) {
                startReconnect();
            }
        }
    };
    
    private NavigationServiceManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
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
        shouldReconnect = true;
        bindServiceInternal();
        startConnectionMonitoring();  // 启动连接监控
    }
    
    /**
     * 启动连接监控
     */
    private void startConnectionMonitoring() {
        if (isMonitoringConnection) {
            return;
        }
        
        isMonitoringConnection = true;
        Log.d(TAG, "启动连接监控");
        
        connectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (isMonitoringConnection) {
                    checkConnectionHealth();
                    // 继续下一次检查
                    mainHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL);
                }
            }
        };
        
        // 延迟开始第一次检查
        mainHandler.postDelayed(connectionCheckRunnable, CONNECTION_CHECK_INTERVAL);
    }
    
    /**
     * 停止连接监控
     */
    private void stopConnectionMonitoring() {
        isMonitoringConnection = false;
        if (connectionCheckRunnable != null) {
            mainHandler.removeCallbacks(connectionCheckRunnable);
            connectionCheckRunnable = null;
        }
        Log.d(TAG, "停止连接监控");
    }
    
    /**
     * 检查连接健康状态
     */
    private void checkConnectionHealth() {
        if (!shouldReconnect) {
            return;
        }
        
        // 检查服务是否仍然可用
        if (isServiceBound && navigationService != null) {
            try {
                // 尝试调用服务方法来验证连接
                navigationService.isNavigating();
                // 如果调用成功，连接正常
                return;
            } catch (Exception e) {
                Log.w(TAG, "连接健康检查失败，服务可能已断开", e);
            }
        }
        
        // 如果服务绑定状态显示已连接但实际不可用，触发重连
        if (isServiceBound && navigationService == null) {
            Log.w(TAG, "检测到连接异常：服务已绑定但实例为空");
            handleConnectionLost();
        } else if (!isServiceBound && !isReconnecting) {
            Log.w(TAG, "检测到连接丢失：服务未绑定且未在重连");
            handleConnectionLost();
        }
    }
    
    /**
     * 处理连接丢失
     */
    private void handleConnectionLost() {
        Log.w(TAG, "处理连接丢失");
        
        // 重置服务状态
        navigationService = null;
        isServiceBound = false;
        
        // 通知监听器
        if (connectionListener != null) {
            connectionListener.onConnectionLost();
        }
        
        // 如果应该重连且不在重连中，启动重连
        if (shouldReconnect && !isReconnecting) {
            startReconnect();
        }
    }
    
    /**
     * 内部绑定服务方法
     */
    private void bindServiceInternal() {
        try {
            Intent serviceIntent = new Intent(context, NavigationBackgroundService.class);
            
            // 启动前台服务
            context.startForegroundService(serviceIntent);
            
            // 绑定服务
            boolean bindResult = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            
            if (bindResult) {
                Log.d(TAG, "导航服务启动并绑定请求已发送");
            } else {
                Log.e(TAG, "导航服务绑定失败");
                if (shouldReconnect && !isReconnecting) {
                    startReconnect();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "启动导航服务时发生异常", e);
            if (shouldReconnect && !isReconnecting) {
                startReconnect();
            }
        }
    }
    
    /**
     * 开始重连流程
     */
    private void startReconnect() {
        if (isReconnecting || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "达到最大重连次数，停止重连");
                if (connectionListener != null) {
                    connectionListener.onReconnectFailed(reconnectAttempts);
                }
                resetReconnectState();
            }
            return;
        }
        
        isReconnecting = true;
        reconnectAttempts++;
        
        Log.i(TAG, "开始第 " + reconnectAttempts + " 次重连，延迟: " + currentReconnectDelay + "ms");
        
        if (connectionListener != null) {
            connectionListener.onReconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
        }
        
        // 取消之前的重连任务
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
        }
        
        // 创建新的重连任务
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldReconnect && isReconnecting) {
                    Log.d(TAG, "执行重连尝试 #" + reconnectAttempts);
                    
                    // 先尝试解绑之前的连接
                    try {
                        if (isServiceBound) {
                            context.unbindService(serviceConnection);
                            isServiceBound = false;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "解绑服务时发生异常", e);
                    }
                    
                    // 重新绑定服务
                    bindServiceInternal();
                    
                    // 计算下次重连延迟（指数退避）
                    currentReconnectDelay = Math.min(
                        (long) (currentReconnectDelay * BACKOFF_MULTIPLIER),
                        MAX_RECONNECT_DELAY
                    );
                }
            }
        };
        
        // 延迟执行重连
        mainHandler.postDelayed(reconnectRunnable, currentReconnectDelay);
    }
    
    /**
     * 重置重连状态
     */
    private void resetReconnectState() {
        reconnectAttempts = 0;
        isReconnecting = false;
        currentReconnectDelay = INITIAL_RECONNECT_DELAY;
        
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }
    
    /**
     * 停止并解绑导航服务
     */
    public void stopAndUnbindService() {
        shouldReconnect = false;  // 停止自动重连
        stopConnectionMonitoring();  // 停止连接监控
        resetReconnectState();
        
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                Log.w(TAG, "解绑服务时发生异常", e);
            }
        }
        
        Intent serviceIntent = new Intent(context, NavigationBackgroundService.class);
        context.stopService(serviceIntent);
        
        navigationService = null;
        Log.d(TAG, "导航服务停止并解绑");
    }
    
    /**
     * 手动触发重连
     */
    public void reconnect() {
        Log.i(TAG, "手动触发重连");
        shouldReconnect = true;
        resetReconnectState();
        
        // 先断开当前连接
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                Log.w(TAG, "手动重连时解绑服务发生异常", e);
            }
        }
        
        // 立即重连
        bindServiceInternal();
        
        // 确保连接监控正在运行
        if (!isMonitoringConnection) {
            startConnectionMonitoring();
        }
    }
    
    /**
     * 设置是否启用自动重连
     */
    public void setAutoReconnectEnabled(boolean enabled) {
        this.shouldReconnect = enabled;
        if (!enabled) {
            resetReconnectState();
            stopConnectionMonitoring();
        } else if (isServiceConnected()) {
            startConnectionMonitoring();
        }
    }
    
    /**
     * 检查服务是否已连接
     */
    public boolean isServiceConnected() {
        return isServiceBound && navigationService != null;
    }
    
    /**
     * 检查是否正在重连
     */
    public boolean isReconnecting() {
        return isReconnecting;
    }
    
    /**
     * 获取重连尝试次数
     */
    public int getReconnectAttempts() {
        return reconnectAttempts;
    }
    
    /**
     * 获取连接状态描述
     */
    public String getConnectionStatus() {
        if (isServiceConnected()) {
            return "已连接" + (isMonitoringConnection ? " (监控中)" : "");
        } else if (isReconnecting) {
            return "重连中 (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")";
        } else if (shouldReconnect) {
            return "等待连接";
        } else {
            return "已断开";
        }
    }
    
    /**
     * 获取详细的连接状态信息
     */
    public String getDetailedConnectionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("连接状态: ").append(getConnectionStatus()).append("\n");
        status.append("服务绑定: ").append(isServiceBound ? "是" : "否").append("\n");
        status.append("服务实例: ").append(navigationService != null ? "有效" : "无效").append("\n");
        status.append("自动重连: ").append(shouldReconnect ? "启用" : "禁用").append("\n");
        status.append("连接监控: ").append(isMonitoringConnection ? "运行中" : "已停止").append("\n");
        
        if (isReconnecting) {
            status.append("重连尝试: ").append(reconnectAttempts).append("/").append(MAX_RECONNECT_ATTEMPTS).append("\n");
            status.append("下次重连延迟: ").append(currentReconnectDelay).append("ms\n");
        }
        
        return status.toString();
    }
    
    /**
     * 获取连接健康状态
     */
    public boolean isConnectionHealthy() {
        if (!isServiceConnected()) {
            return false;
        }
        
        try {
            // 尝试调用服务方法验证连接
            navigationService.isNavigating();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "连接健康检查失败", e);
            return false;
        }
    }
    
    /**
     * 强制进行连接健康检查
     */
    public void forceHealthCheck() {
        Log.d(TAG, "强制执行连接健康检查");
        checkConnectionHealth();
    }
    
    /**
     * 获取重连配置信息
     */
    public String getReconnectConfig() {
        return String.format("重连配置 - 最大尝试: %d, 初始延迟: %dms, 最大延迟: %dms, 退避倍数: %.1f",
                MAX_RECONNECT_ATTEMPTS, INITIAL_RECONNECT_DELAY, MAX_RECONNECT_DELAY, BACKOFF_MULTIPLIER);
    }
    
    /**
     * 设置连接检查间隔（仅用于测试）
     */
    public void setConnectionCheckInterval(long intervalMs) {
        // 注意：这个方法主要用于测试，生产环境建议使用默认值
        Log.w(TAG, "连接检查间隔已修改为: " + intervalMs + "ms (仅建议测试时使用)");
    }
    
    /**
     * 启动导航到指定目的地
     */
    public boolean startNavigationToDestination(String destinationName) {
        if (!isServiceConnected()) {
            Log.w(TAG, "服务未连接，无法启动导航。当前状态: " + getConnectionStatus());
            return false;
        }
        
        navigationService.startNavigationToDestination(destinationName);
        Log.d(TAG, "启动导航到: " + destinationName);
        return true;
    }
    
    /**
     * 启动导航到指定坐标
     */
    public boolean startNavigationToCoordinates(double latitude, double longitude, String destinationName) {
        if (!isServiceConnected()) {
            Log.w(TAG, "服务未连接，无法启动导航。当前状态: " + getConnectionStatus());
            return false;
        }
        
        navigationService.startNavigationToCoordinates(latitude, longitude, destinationName);
        Log.d(TAG, "启动导航到坐标: " + latitude + ", " + longitude + " (" + destinationName + ")");
        return true;
    }
    
    /**
     * 停止导航
     */
    public boolean stopNavigation() {
        if (!isServiceConnected()) {
            Log.w(TAG, "服务未连接，无法停止导航。当前状态: " + getConnectionStatus());
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
     * 获取最后的导航错误信息
     */
    public String getLastNavigationError() {
        if (!isServiceConnected()) {
            return "导航服务未连接";
        }
        return navigationService.getLastNavigationError();
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
        stopConnectionMonitoring();
        stopAndUnbindService();
        connectionListener = null;
        instance = null;
    }
}