package com.lhht.xiaozhi.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.activities.ChatActivity;
import com.tencent.map.navi.TencentRouteSearchCallback;
import com.tencent.map.navi.TencentWalkNaviListener;
import com.tencent.map.navi.data.CalcRouteResult;
import com.tencent.map.navi.data.NaviPoi;
import com.tencent.map.navi.data.NaviTts;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.data.NavigationData;
import com.tencent.map.navi.data.AttachedLocation;
import com.tencent.map.navi.walk.TencentWalkNaviManager;

import java.util.ArrayList;

public class NavigationBackgroundService extends Service {
    
    private static final String TAG = "NavigationBackgroundService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "navigation_channel";
    
    // 导航管理器
    private TencentWalkNaviManager naviManager;
    
    // 定位相关
    private LocationManager locationManager;
    private Location currentLocation;
    
    // 导航状态
    private boolean isNavigating = false;
    private NavigationData currentNaviData;
    
    // Binder用于与Activity通信
    private final IBinder binder = new NavigationBinder();
    
    public class NavigationBinder extends Binder {
        public NavigationBackgroundService getService() {
            return NavigationBackgroundService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "NavigationBackgroundService创建");
        
        // 初始化导航管理器
        naviManager = new TencentWalkNaviManager(getApplicationContext());
        
        // 初始化定位管理器
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        
        // 开启语音播报模块
        naviManager.setInternalTtsEnabled(true);
        
        // 添加导航监听器
        setupNavigationListener();
        
        // 创建通知渠道
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "NavigationBackgroundService启动");
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());
        
        return START_STICKY; // 服务被杀死后自动重启
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "NavigationBackgroundService销毁");
        
        // 停止导航
        stopNavigation();
        
        // 停止定位
        stopLocationUpdates();
        
        // 清理导航管理器
        if (naviManager != null) {
            naviManager.stopNavi();
        }
    }
    
    /**
     * 设置导航监听器
     */
    private void setupNavigationListener() {
        naviManager.addTencentNaviListener(new TencentWalkNaviListener() {
            @Override
            public void onRecalculateRouteSuccess(ArrayList<RouteData> arrayList) {
                Log.d(TAG, "路线重新计算成功");
            }
            
            @Override
            public void onDirectionUpdateBySensor(float v) {
                // 方向传感器更新
            }
            
            @Override
            public void onStartNavi() {
                Log.d(TAG, "后台导航开始");
                isNavigating = true;
                startContinuousLocationUpdates();
                updateNotification("导航进行中");
            }
            
            @Override
            public void onStopNavi() {
                Log.d(TAG, "后台导航结束");
                isNavigating = false;
                stopLocationUpdates();
                updateNotification("导航已停止");
            }
            
            @Override
            public void onArrivedDestination() {
                Log.d(TAG, "已到达目的地");
                updateNotification("已到达目的地");
            }
            
            @Override
            public void onUpdateAttachedLocation(AttachedLocation attachedLocation) {
                // 位置更新
            }
            
            @Override
            public void onGpsRssiChanged(int i) {
                // GPS信号强度变化
            }
            
            @Override
            public void onOffRoute() {
                Log.d(TAG, "偏离路线");
                updateNotification("偏离路线，重新规划中");
            }
            
            @Override
            public int onVoiceBroadcast(NaviTts tts) {
                Log.d(TAG, "语音播报: " + tts.getText());
                return 1;
            }
            
            @Override
            public void onUpdateNavigationData(NavigationData data) {
                // 更新导航数据 - 核心功能
                currentNaviData = data;
                if (data != null) {
                    Log.d(TAG, "导航数据更新 - 剩余距离: " + data.getLeftDistance() + 
                          "m, 转向: " + getTurnDirectionText(data.getTurnDirection()) +
                          ", 下一转向距离: " + data.getDistanceToNextRoad() + "m");
                }
            }
            
            @Override
            public void onGpsWeakNotify() {
                updateNotification("GPS信号弱");
            }
            
            @Override
            public void onGpsStrongNotify() {
                updateNotification("GPS信号强");
            }
            
            @Override
            public void onGpsStatusChanged(boolean b) {
                // GPS状态变化
            }
            
            @Override
            public void onUpdateCurrentRoute(RouteData routeData) {
                // 当前路线更新
            }
            
            @Override
            public void onChangeRes(boolean b) {
                // 资源变化
            }
        });
    }
    
    /**
     * 启动导航到指定目的地
     */
    public void startNavigationToDestination(String destinationName) {
        Log.d(TAG, "准备启动导航到: " + destinationName);
        
        // 获取目的地坐标
        NaviPoi destPoi = getLocationCoordinates(destinationName);
        if (destPoi == null) {
            Log.w(TAG, "无法识别目的地: " + destinationName);
            updateNotification("无法识别目的地: " + destinationName);
            return;
        }
        
        // 如果当前位置未知，先获取位置
        if (currentLocation == null) {
            Log.d(TAG, "当前位置未知，开始获取位置");
            updateNotification("正在获取当前位置...");
            startLocationForNavigation(destPoi);
        } else {
            // 直接使用当前位置启动导航
            startNavigationWithLocation(currentLocation, destPoi);
        }
    }
    
    /**
     * 启动导航到指定坐标
     */
    public void startNavigationToCoordinates(double latitude, double longitude, String destinationName) {
        Log.d(TAG, "准备启动导航到坐标: " + latitude + ", " + longitude + " (" + destinationName + ")");
        
        // 创建目的地POI
        NaviPoi destPoi = new NaviPoi(latitude, longitude);
        destPoi.setPoiName(destinationName != null ? destinationName : "目的地");
        
        // 如果当前位置未知，先获取位置
        if (currentLocation == null) {
            Log.d(TAG, "当前位置未知，开始获取位置");
            updateNotification("正在获取当前位置...");
            startLocationForNavigation(destPoi);
        } else {
            // 直接使用当前位置启动导航
            startNavigationWithLocation(currentLocation, destPoi);
        }
    }
    
    /**
     * 为导航获取当前位置
     */
    private void startLocationForNavigation(NaviPoi destPoi) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有定位权限");
            updateNotification("缺少定位权限");
            return;
        }
        
        try {
            // 创建一次性定位监听器
            LocationListener oneTimeLocationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    Log.d(TAG, "获取到位置用于导航: " + location.getLatitude() + ", " + location.getLongitude());
                    currentLocation = location;
                    
                    // 停止定位
                    try {
                        locationManager.removeUpdates(this);
                    } catch (SecurityException e) {
                        Log.e(TAG, "停止定位失败: " + e.getMessage());
                    }
                    
                    // 启动导航
                    startNavigationWithLocation(location, destPoi);
                }
                
                @Override
                public void onProviderEnabled(String provider) {}
                
                @Override
                public void onProviderDisabled(String provider) {
                    Log.w(TAG, "定位提供者被禁用: " + provider);
                    updateNotification("GPS已关闭，请开启GPS");
                }
                
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
            };
            
            // 优先使用GPS
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000, // 1秒
                        0,    // 0米
                        oneTimeLocationListener);
                Log.d(TAG, "开始GPS定位");
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000, // 1秒
                        0,    // 0米
                        oneTimeLocationListener);
                Log.d(TAG, "开始网络定位");
            } else {
                Log.w(TAG, "没有可用的定位提供者");
                updateNotification("无法获取位置，请检查GPS设置");
            }
            
        } catch (SecurityException e) {
            Log.e(TAG, "启动定位失败: " + e.getMessage());
            updateNotification("定位权限被拒绝");
        }
    }
    
    /**
     * 使用指定位置启动导航
     */
    private void startNavigationWithLocation(Location startLocation, NaviPoi destPoi) {
        // 创建起点POI
        NaviPoi startPoi = new NaviPoi(startLocation.getLatitude(), startLocation.getLongitude());
        startPoi.setPoiName("当前位置");
        
        Log.d(TAG, "开始导航规划 - 起点: " + startPoi.getLatitude() + "," + startPoi.getLongitude() +
              " 终点: " + destPoi.getLatitude() + "," + destPoi.getLongitude());
        
        updateNotification("正在规划路线...");
        
        // 发起路线规划
        try {
            naviManager.searchRoute(startPoi, destPoi, new TencentRouteSearchCallback() {
                @Override
                public void onCalcRouteSuccess(CalcRouteResult calcRouteResult) {
                    Log.d(TAG, "路线计算成功");
                }

                @Override
                public void onCalcRouteFailure(CalcRouteResult calcRouteResult) {
                    Log.e(TAG, "路线计算失败: " + calcRouteResult.getErrorCode());
                    updateNotification("路线计算失败");
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> routeDataList) {
                    Log.d(TAG, "路线规划成功，开始导航");
                    if (!routeDataList.isEmpty()) {
                        try {
                            naviManager.startNavi(0);
                            updateNotification("导航启动成功");
                        } catch (Exception e) {
                            Log.e(TAG, "启动导航失败: " + e.getMessage());
                            updateNotification("导航启动失败: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "没有找到可用路线");
                        updateNotification("没有找到可用路线");
                    }
                }

                @Override
                public void onRouteSearchFailure(int errorCode, String errorMsg) {
                    Log.e(TAG, "路线规划失败: " + errorCode + ", " + errorMsg);
                    updateNotification("路线规划失败: " + errorMsg);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "路线规划异常: " + e.getMessage());
            updateNotification("路线规划异常: " + e.getMessage());
        }
    }
    
    /**
     * 停止导航
     */
    public void stopNavigation() {
        if (naviManager != null && isNavigating) {
            naviManager.stopNavi();
            isNavigating = false;
            Log.d(TAG, "导航已停止");
        }
    }
    
    /**
     * 获取到目的地的距离（米）
     */
    public int getDistanceToDestination() {
        if (currentNaviData != null) {
            return currentNaviData.getLeftDistance();
        }
        return -1; // 表示无数据
    }
    
    /**
     * 获取当前前进方向
     */
    public String getCurrentDirection() {
        if (currentNaviData != null) {
            return getTurnDirectionText(currentNaviData.getTurnDirection());
        }
        return "未知";
    }
    
    /**
     * 获取到下一个转向点的距离（米）
     */
    public int getDistanceToNextTurn() {
        if (currentNaviData != null) {
            return currentNaviData.getDistanceToNextRoad();
        }
        return -1; // 表示无数据
    }
    
    /**
     * 获取当前道路名称
     */
    public String getCurrentRoadName() {
        if (currentNaviData != null) {
            String roadName = currentNaviData.getCurrentRoadName();
            return roadName != null && !roadName.isEmpty() ? roadName : "当前道路";
        }
        return "未知";
    }
    
    /**
     * 获取下一条道路名称
     */
    public String getNextRoadName() {
        if (currentNaviData != null) {
            String roadName = currentNaviData.getNextRoadName();
            return roadName != null && !roadName.isEmpty() ? roadName : "继续直行";
        }
        return "未知";
    }
    
    /**
     * 检查是否正在导航
     */
    public boolean isNavigating() {
        return isNavigating;
    }
    
    /**
     * 获取当前位置
     */
    public Location getCurrentLocation() {
        return currentLocation;
    }
    
    /**
     * 转换转向类型为文字描述
     */
    private String getTurnDirectionText(int turnType) {
        switch (turnType) {
            case 1:
                return "左转";
            case 2:
                return "右转";
            case 3:
                return "左前方";
            case 4:
                return "右前方";
            case 5:
                return "掉头";
            case 0:
            default:
                return "直行";
        }
    }
    
    /**
     * 根据地址名称获取坐标（复用WalkNaviActivity的逻辑）
     */
    private NaviPoi getLocationCoordinates(String locationName) {
        NaviPoi poi = null;
        
        // 预设的地址坐标映射
        switch (locationName) {
            case "北京理工大学":
                poi = new NaviPoi(39.926296, 116.309901);
                poi.setPoiName("北京理工大学");
                break;
            case "天安门广场":
                poi = new NaviPoi(39.917834, 116.397271);
                poi.setPoiName("天安门广场");
                break;
            case "家":
                poi = new NaviPoi(34.779973, 111.187150);
                poi.setPoiName("家");
                break;
            case "清华大学":
                poi = new NaviPoi(40.003054, 116.326264);
                poi.setPoiName("清华大学");
                break;
            case "故宫":
            case "故宫博物院":
                poi = new NaviPoi(39.916345, 116.397155);
                poi.setPoiName("故宫博物院");
                break;
            case "颐和园":
                poi = new NaviPoi(39.999748, 116.275020);
                poi.setPoiName("颐和园");
                break;
            case "鸟巢":
            case "国家体育场":
                poi = new NaviPoi(39.992806, 116.397144);
                poi.setPoiName("国家体育场(鸟巢)");
                break;
            case "水立方":
            case "国家游泳中心":
                poi = new NaviPoi(39.993000, 116.389000);
                poi.setPoiName("国家游泳中心(水立方)");
                break;
            case "王府井":
                poi = new NaviPoi(39.915119, 116.416357);
                poi.setPoiName("王府井");
                break;
            case "三里屯":
                poi = new NaviPoi(39.937967, 116.447857);
                poi.setPoiName("三里屯");
                break;
            case "驿站":
                poi = new NaviPoi(34.779570, 111.189615);
                poi.setPoiName("驿站");
                break;
            default:
                // 默认坐标（天安门广场）
                Log.w(TAG, "未找到地址: " + locationName + "，使用默认坐标");
                poi = new NaviPoi(39.917834, 116.397271);
                poi.setPoiName(locationName);
                break;
        }
        
        return poi;
    }
    
    /**
     * 启动持续定位
     */
    private void startContinuousLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有定位权限");
            return;
        }
        
        try {
            long timeInterval = 5000; // 5秒
            float distanceInterval = 10.0f; // 10米
            
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        timeInterval,
                        distanceInterval,
                        locationListener);
                Log.d(TAG, "持续定位已启动");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "启动持续定位失败: " + e.getMessage());
        }
    }
    
    /**
     * 停止定位更新
     */
    private void stopLocationUpdates() {
        try {
            if (locationManager != null && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.removeUpdates(locationListener);
                Log.d(TAG, "定位更新已停止");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止定位更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 定位监听器
     */
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            Log.d(TAG, "位置更新: " + location.getLatitude() + ", " + location.getLongitude() +
                  ", 精度: " + location.getAccuracy() + "m");
        }
        
        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "定位提供者启用: " + provider);
        }
        
        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "定位提供者禁用: " + provider);
        }
        
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "定位状态改变: " + provider + ", status: " + status);
        }
    };
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "导航服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台导航服务通知");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    /**
     * 创建通知
     */
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, ChatActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("导航服务")
                .setContentText("后台导航服务正在运行")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    /**
     * 更新通知内容
     */
    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        
        Intent notificationIntent = new Intent(this, ChatActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("导航服务")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        
        manager.notify(NOTIFICATION_ID, notification);
    }
}