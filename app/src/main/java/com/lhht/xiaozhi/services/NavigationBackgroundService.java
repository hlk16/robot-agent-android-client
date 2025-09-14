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
import android.location.LocationProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
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
    private int locationRetryCount = 0;
    private static final int MAX_LOCATION_RETRY = 3;
    private static final float MIN_ACCURACY = 50.0f; // 最小精度要求50米
    private static final long LOCATION_TIMEOUT = 30000; // 30秒超时
    
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
                    
                    // 详细输出获取到的位置信息
                    Log.i(TAG, "=== 获取到的当前位置详细信息 ===");
                    Log.i(TAG, "纬度: " + location.getLatitude());
                    Log.i(TAG, "经度: " + location.getLongitude());
                    Log.i(TAG, "精度: " + location.getAccuracy() + "米");
                    Log.i(TAG, "提供者: " + location.getProvider());
                    Log.i(TAG, "时间: " + new java.util.Date(location.getTime()));
                    Log.i(TAG, "海拔: " + (location.hasAltitude() ? location.getAltitude() + "米" : "未知"));
                    Log.i(TAG, "速度: " + (location.hasSpeed() ? location.getSpeed() + "m/s" : "未知"));
                    Log.i(TAG, "方向: " + (location.hasBearing() ? location.getBearing() + "度" : "未知"));
                    Log.i(TAG, "=================================");
                    
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
        Log.i(TAG, "开始导航: 起点(" + startLocation.getLatitude() + ", " + startLocation.getLongitude() + ") -> 终点(" + destPoi.getLatitude() + ", " + destPoi.getLongitude() + ")");
        Log.i(TAG, "起点定位信息: 精度=" + startLocation.getAccuracy() + "m, 提供者=" + startLocation.getProvider() + ", 时间=" + new java.util.Date(startLocation.getTime()));
        
        // 检查起点定位质量
        if (startLocation.getAccuracy() > 100) {
            Log.w(TAG, "起点定位精度较差(" + startLocation.getAccuracy() + "m)，可能影响导航效果");
            updateNotification("定位精度较差，正在优化...");
        }
        
        // 创建起点POI
        NaviPoi startPoi = new NaviPoi(startLocation.getLatitude(), startLocation.getLongitude());
        startPoi.setPoiName("当前位置");
        
        // 详细输出当前位置的腾讯POI信息
        Log.i(TAG, "=== 当前位置腾讯POI详细信息 ===");
        Log.i(TAG, "起点POI - 纬度: " + startPoi.getLatitude());
        Log.i(TAG, "起点POI - 经度: " + startPoi.getLongitude());
        Log.i(TAG, "起点POI - 名称: " + startPoi.getPoiName());
        Log.i(TAG, "目标POI - 纬度: " + destPoi.getLatitude());
        Log.i(TAG, "目标POI - 经度: " + destPoi.getLongitude());
        Log.i(TAG, "目标POI - 名称: " + destPoi.getPoiName());
        Log.i(TAG, "原始Location - 纬度: " + startLocation.getLatitude());
        Log.i(TAG, "原始Location - 经度: " + startLocation.getLongitude());
        Log.i(TAG, "=================================");
        
        // 显示Toast提示当前位置和目的地坐标
        String toastMessage = "导航信息:\n" +
                "当前位置: (" + String.format("%.6f", startLocation.getLatitude()) + ", " + 
                String.format("%.6f", startLocation.getLongitude()) + ")\n" +
                "目的地: (" + String.format("%.6f", destPoi.getLatitude()) + ", " + 
                String.format("%.6f", destPoi.getLongitude()) + ")\n" +
                "目的地名称: " + destPoi.getPoiName();
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        Log.i(TAG, "Toast显示: " + toastMessage.replace("\n", " | "));
        
        // 计算起终点距离
        float[] results = new float[1];
        Location.distanceBetween(startLocation.getLatitude(), startLocation.getLongitude(), destPoi.getLatitude(), destPoi.getLongitude(), results);
        float distance = results[0];
        
        // 详细的距离计算调试信息
        Log.i(TAG, "=== 距离计算详细信息 ===");
        Log.i(TAG, "起点坐标: (" + startLocation.getLatitude() + ", " + startLocation.getLongitude() + ")");
        Log.i(TAG, "终点坐标: (" + destPoi.getLatitude() + ", " + destPoi.getLongitude() + ")");
        Log.i(TAG, "计算得到的距离: " + String.format("%.1f", distance) + "米 (" + String.format("%.2f", distance/1000) + "公里)");
        
        // 检查坐标是否合理
        if (Math.abs(startLocation.getLatitude()) > 90 || Math.abs(startLocation.getLongitude()) > 180) {
            Log.e(TAG, "起点坐标异常: 纬度=" + startLocation.getLatitude() + ", 经度=" + startLocation.getLongitude());
        }
        if (Math.abs(destPoi.getLatitude()) > 90 || Math.abs(destPoi.getLongitude()) > 180) {
            Log.e(TAG, "终点坐标异常: 纬度=" + destPoi.getLatitude() + ", 经度=" + destPoi.getLongitude());
        }
        
        // 计算纬度和经度差值
        double latDiff = Math.abs(startLocation.getLatitude() - destPoi.getLatitude());
        double lonDiff = Math.abs(startLocation.getLongitude() - destPoi.getLongitude());
        Log.i(TAG, "纬度差值: " + String.format("%.6f", latDiff) + "度");
        Log.i(TAG, "经度差值: " + String.format("%.6f", lonDiff) + "度");
        Log.i(TAG, "===============================");
        
        // 检查距离是否过长（超过50公里认为不合理）
        if (distance > 50000) { // 50公里
            String errorMsg = "目的地距离过远(" + String.format("%.1f", distance/1000) + "公里)，建议选择较近的目的地";
            Log.w(TAG, errorMsg);
            updateNotification(errorMsg);
            return;
        }
        
        // 检查距离是否过近（小于10米认为已到达）
        if (distance < 10) {
            String msg = "您已在目的地附近(" + String.format("%.0f", distance) + "米)";
            Log.i(TAG, msg);
            updateNotification(msg);
            return;
        }
        
        Log.d(TAG, "开始导航规划 - 起点: " + startPoi.getLatitude() + "," + startPoi.getLongitude() +
              " 终点: " + destPoi.getLatitude() + "," + destPoi.getLongitude());
        
        updateNotification("正在规划路线...");
        
        // 发起路线规划
        try {
            naviManager.searchRoute(startPoi, destPoi, new TencentRouteSearchCallback() {
                @Override
                public void onCalcRouteSuccess(CalcRouteResult calcRouteResult) {
                    Log.i(TAG, "路线计算成功: 错误码=" + calcRouteResult.getErrorCode());
                }

                @Override
                public void onCalcRouteFailure(CalcRouteResult calcRouteResult) {
                    String errorMsg = getRouteCalculationErrorMessage(calcRouteResult.getErrorCode());
                    Log.e(TAG, "路线计算失败: 错误码=" + calcRouteResult.getErrorCode() + " " + errorMsg);
                    updateNotification("路线计算失败: " + errorMsg);
                    
                    // 特别处理不同类型的错误
                    int errorCode = calcRouteResult.getErrorCode();
                    if (errorCode == -1 || errorMsg.contains("可导航区域")) {
                        Log.w(TAG, "检测到'未到可导航区域'错误，进行详细诊断:");
                        Log.w(TAG, "- 当前位置: (" + startLocation.getLatitude() + ", " + startLocation.getLongitude() + ")");
                        Log.w(TAG, "- 定位精度: " + startLocation.getAccuracy() + "米");
                        Log.w(TAG, "- 定位提供者: " + startLocation.getProvider());
                        Log.w(TAG, "- 建议: 1)移动到更开阔的区域 2)等待GPS信号稳定 3)检查网络连接");
                        updateNotification("未到可导航区域，请移动到开阔地带");
                    } else if (errorCode == -6 || errorMsg.contains("路径太长")) {
                        Log.w(TAG, "检测到'路径太长'错误，进行详细诊断:");
                        Log.w(TAG, "- 起终点距离: " + String.format("%.1f", distance/1000) + "公里");
                        Log.w(TAG, "- 建议: 1)选择较近的目的地 2)分段导航 3)使用其他交通方式");
                        updateNotification("目的地距离过远，建议选择较近的目的地");
                    } else if (errorCode == -9 || errorMsg.contains("定位精度")) {
                        Log.w(TAG, "检测到'定位精度不足'错误:");
                        Log.w(TAG, "- 当前定位精度: " + startLocation.getAccuracy() + "米");
                        Log.w(TAG, "- 建议: 1)移动到空旷区域 2)等待GPS信号稳定 3)重新获取定位");
                        updateNotification("定位精度不足，请移动到空旷区域");
                    }
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> routeDataList) {
                    Log.i(TAG, "路线规划成功，路线数量: " + (routeDataList != null ? routeDataList.size() : 0));
                    if (!routeDataList.isEmpty()) {
                        RouteData route = routeDataList.get(0);
                        Log.i(TAG, "选择路线信息: 距离=" + route.getDistance() + "米, 时间=" + route.getRouteId() + "秒");
                        try {
                            naviManager.startNavi(0);
                            updateNotification("导航启动成功 - 距离" + route.getDistance() + "米");
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
                    String detailedErrorMsg = getRouteSearchErrorMessage(errorCode, errorMsg);
                    Log.e(TAG, "路线规划失败: 错误码=" + errorCode + " " + detailedErrorMsg);
                    updateNotification("路线规划失败: " + detailedErrorMsg);
                    
                    // 特别处理不同类型的错误
                    if (errorCode == -1 || errorMsg.contains("可导航区域") || detailedErrorMsg.contains("可导航区域")) {
                        Log.w(TAG, "检测到'未到可导航区域'错误，进行详细诊断:");
                        Log.w(TAG, "- 当前位置: (" + startLocation.getLatitude() + ", " + startLocation.getLongitude() + ")");
                        Log.w(TAG, "- 定位精度: " + startLocation.getAccuracy() + "米");
                        Log.w(TAG, "- 定位提供者: " + startLocation.getProvider());
                        Log.w(TAG, "- 建议: 1)移动到更开阔的区域 2)等待GPS信号稳定 3)检查网络连接");
                        updateNotification("未到可导航区域，请移动到开阔地带");
                    } else if (errorCode == -6 || errorMsg.contains("路径太长") || detailedErrorMsg.contains("路径太长")) {
                        Log.w(TAG, "检测到'路径太长'错误，进行详细诊断:");
                        Log.w(TAG, "- 起终点距离: " + String.format("%.1f", distance/1000) + "公里");
                        Log.w(TAG, "- 建议: 1)选择较近的目的地 2)分段导航 3)使用其他交通方式");
                        updateNotification("目的地距离过远，建议选择较近的目的地");
                    } else if (errorCode == -9 || errorMsg.contains("定位精度") || detailedErrorMsg.contains("定位精度")) {
                        Log.w(TAG, "检测到'定位精度不足'错误:");
                        Log.w(TAG, "- 当前定位精度: " + startLocation.getAccuracy() + "米");
                        Log.w(TAG, "- 建议: 1)移动到空旷区域 2)等待GPS信号稳定 3)重新获取定位");
                        updateNotification("定位精度不足，请移动到空旷区域");
                    }
                }
            });
            Log.i(TAG, "路线规划请求已发送，等待回调...");
        } catch (Exception e) {
            Log.e(TAG, "路线规划异常: " + e.getMessage());
            updateNotification("路线规划异常: " + e.getMessage());
        }
    }
    
    /**
     * 获取路线计算错误信息
     */
    private String getRouteCalculationErrorMessage(int errorCode) {
        switch (errorCode) {
            case -1:
                return "未到可导航区域";
            case -2:
                return "网络连接失败";
            case -3:
                return "参数错误";
            case -4:
                return "起点或终点无效";
            case -5:
                return "路线计算超时";
            case -6:
                return "路径太长，超出导航范围";
            case -7:
                return "无法规划路线";
            case -8:
                return "服务器繁忙，请稍后重试";
            case -9:
                return "定位精度不足";
            case -10:
                return "起终点距离过近";
            default:
                return "未知错误(" + errorCode + ")";
        }
    }
    
    /**
     * 获取路线搜索错误信息
     */
    private String getRouteSearchErrorMessage(int errorCode, String originalMsg) {
        String detailedMsg = originalMsg;
        switch (errorCode) {
            case -1:
                detailedMsg = "未到可导航区域";
                break;
            case -2:
                detailedMsg = "网络连接失败";
                break;
            case -3:
                detailedMsg = "参数错误";
                break;
            case -4:
                detailedMsg = "起点或终点无效";
                break;
            case -5:
                detailedMsg = "路线搜索超时";
                break;
            case -6:
                detailedMsg = "路径太长，超出导航范围";
                break;
            case -7:
                detailedMsg = "无法规划路线";
                break;
            case -8:
                detailedMsg = "服务器繁忙，请稍后重试";
                break;
            case -9:
                detailedMsg = "定位精度不足";
                break;
            case -10:
                detailedMsg = "起终点距离过近";
                break;
            default:
                if (originalMsg == null || originalMsg.isEmpty()) {
                    detailedMsg = "未知错误(" + errorCode + ")";
                }
                break;
        }
        return detailedMsg;
    }
    
    /**
     * 获取导航错误信息
     */
    private String getNavigationErrorMessage(int errorCode) {
        switch (errorCode) {
            case -1:
                return "导航初始化失败";
            case -2:
                return "路线数据无效";
            case -3:
                return "导航引擎异常";
            case -4:
                return "权限不足";
            default:
                return "未知错误(" + errorCode + ")";
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
//            case "驿站":
//                poi = new NaviPoi(34.779570, 111.189615);
//                poi.setPoiName("驿站");
//                break;

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
            Log.d(TAG, "位置更新: " + location.getLatitude() + ", " + location.getLongitude() +
                  ", 精度: " + location.getAccuracy() + "m, 提供者: " + location.getProvider());
            
            // 检查定位精度
            if (location.getAccuracy() > MIN_ACCURACY) {
                Log.w(TAG, "定位精度不足: " + location.getAccuracy() + "m > " + MIN_ACCURACY + "m");
                
                // 如果精度不够且重试次数未达上限，尝试重新定位
                if (locationRetryCount < MAX_LOCATION_RETRY) {
                    locationRetryCount++;
                    Log.i(TAG, "重新获取高精度定位，第" + locationRetryCount + "次尝试");
                    requestHighAccuracyLocation();
                    return;
                } else {
                    Log.w(TAG, "已达最大重试次数，使用当前定位: 精度" + location.getAccuracy() + "m");
                }
            } else {
                Log.i(TAG, "定位精度良好: " + location.getAccuracy() + "m");
                locationRetryCount = 0; // 重置重试计数
            }
            
            // 检查GPS信号质量
            checkGpsSignalQuality(location);
            
            currentLocation = location;
            updateNotification("当前位置: 精度" + String.format("%.1f", location.getAccuracy()) + "m");
        }
        
        @Override
        public void onProviderEnabled(String provider) {
            Log.i(TAG, "定位提供者启用: " + provider);
            updateNotification("定位服务已启用: " + provider);
        }
        
        @Override
        public void onProviderDisabled(String provider) {
            Log.w(TAG, "定位提供者禁用: " + provider);
            updateNotification("定位服务已禁用: " + provider);
            
            // 如果GPS被禁用，尝试使用网络定位
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                Log.i(TAG, "GPS被禁用，尝试使用网络定位");
                requestNetworkLocation();
            }
        }
        
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            String statusText = getProviderStatusText(status);
            Log.d(TAG, "定位状态改变: " + provider + ", status: " + statusText);
            
            if (status == LocationProvider.OUT_OF_SERVICE || status == LocationProvider.TEMPORARILY_UNAVAILABLE) {
                Log.w(TAG, "定位服务不可用: " + provider + " - " + statusText);
                updateNotification("定位服务异常: " + statusText);
            }
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
    
    /**
     * 请求高精度定位
     */
    private void requestHighAccuracyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        
        try {
            // 停止当前定位
            locationManager.removeUpdates(locationListener);
            
            // 请求更高精度的GPS定位
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000, // 2秒间隔
                        5.0f, // 5米距离
                        locationListener);
                Log.i(TAG, "已请求高精度GPS定位");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "请求高精度定位失败: " + e.getMessage());
        }
    }
    
    /**
     * 请求网络定位
     */
    private void requestNetworkLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000, // 5秒间隔
                        10.0f, // 10米距离
                        locationListener);
                Log.i(TAG, "已启用网络定位作为备选");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "请求网络定位失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查GPS信号质量
     */
    private void checkGpsSignalQuality(Location location) {
        if (location.getProvider().equals(LocationManager.GPS_PROVIDER)) {
            float accuracy = location.getAccuracy();
            String quality;
            
            if (accuracy <= 10) {
                quality = "优秀";
            } else if (accuracy <= 20) {
                quality = "良好";
            } else if (accuracy <= 50) {
                quality = "一般";
            } else {
                quality = "较差";
            }
            
            Log.d(TAG, "GPS信号质量: " + quality + " (精度: " + accuracy + "m)");
            
            // 如果信号质量较差，记录详细信息
            if (accuracy > 30) {
                Log.w(TAG, "GPS信号质量较差，可能影响导航准确性。建议移动到开阔区域。");
            }
        }
    }
    
    /**
     * 获取定位提供者状态文本
     */
    private String getProviderStatusText(int status) {
        switch (status) {
            case LocationProvider.AVAILABLE:
                return "可用";
            case LocationProvider.OUT_OF_SERVICE:
                return "服务中断";
            case LocationProvider.TEMPORARILY_UNAVAILABLE:
                return "暂时不可用";
            default:
                return "未知状态(" + status + ")";
        }
    }
}