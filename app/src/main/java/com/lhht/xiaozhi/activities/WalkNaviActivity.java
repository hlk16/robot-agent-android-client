package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.lhht.xiaozhi.R;
import com.tencent.map.navi.TencentNavi;
import com.tencent.map.navi.TencentRouteSearchCallback;
import com.tencent.map.navi.TencentWalkNaviListener;
import com.tencent.map.navi.data.CalcRouteResult;
import com.tencent.map.navi.data.NaviPoi;
import com.tencent.map.navi.data.NaviTts;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.data.NavigationData;
import com.tencent.map.navi.data.AttachedLocation;

import com.tencent.map.navi.ui.car.CarNaviInfoPanel;
import com.tencent.map.navi.walk.TencentWalkNaviManager;

import com.tencent.map.navi.walk.WalkNaviView;
import com.tencent.navi.surport.utils.DeviceUtils;
import com.tencent.tencentmap.mapsdk.maps.TencentMap;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.tencent.tencentmap.mapsdk.maps.model.Marker;
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory;

import java.util.ArrayList;


public class WalkNaviActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final String TAG = "WalkNaviActivity";

    private TencentWalkNaviManager manager;
    private WalkNaviView mWalkNaviView;

    // 定位相关
    private LocationManager locationManager;
    private Location currentLocation;
    private Button btnUseCurrentLocation;
    private android.os.Handler locationTimeoutHandler;
    private Runnable locationTimeoutRunnable;

    // 导航中的持续定位
    private boolean isNavigating = false;

    // 地图和用户位置标记
    private TencentMap tencentMap;
    private Marker userLocationMarker;

    // UI控件
    private EditText etStartLocation;
    private EditText etDestLocation;
    private Button btnStartNavigation;
    private Button btnTogglePanel;
    private LinearLayout routeSettingPanel;
    private boolean isPanelVisible = true;

    // 导航信息显示控件
    private LinearLayout navigationInfoPanel;
    private TextView ivTurnIcon;
    private TextView tvNextRoad;
    private TextView tvCurrentRoad;
    private TextView tvDistanceToNext;
    private TextView tvTotalRemaining;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_walk_navi);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;

        });

        // 初始化控件
        initTencentNavi();
        // 初始化导航控制类
        manager = new TencentWalkNaviManager(getApplicationContext());

        // 导航视图 com.tencent.map.navi.walk.WalkNaviView
        mWalkNaviView = findViewById(R.id.tnk_walk_navi_view);

        // 将视图与控制器关联
        manager.addTencentNaviListener(mWalkNaviView);

        // 获取地图对象，用于显示用户位置标记
        tencentMap = mWalkNaviView.getMap();

        // 开启语音播报模块
        manager.setInternalTtsEnabled(true);

        // 导航UI类初始化，用于显示默认导航UI,包括导航面板,路线,车标,起终点等
        CarNaviInfoPanel carNaviInfoPanel = mWalkNaviView.showNaviInfoPanel();
        carNaviInfoPanel.setOnNaviInfoListener(() -> {
            // 默认面板退出按钮监听
            Log.d("NavActivity", "导航面板退出按钮被点击");
            finish();
        });

        // 添加导航监听器
        manager.addTencentNaviListener(new TencentWalkNaviListener() {
            @Override
            public void onRecalculateRouteSuccess(ArrayList<RouteData> arrayList) {

            }

            @Override
            public void onDirectionUpdateBySensor(float v) {

            }

            @Override
            public void onStartNavi() {
                Log.d("NavActivity", "开启导航");
                Toast.makeText(WalkNaviActivity.this, "导航开始", Toast.LENGTH_SHORT).show();

                // 启动导航时开始持续定位
                isNavigating = true;
                startContinuousLocationUpdates();

                // 如果已有当前位置，立即显示用户位置标记
                if (currentLocation != null) {
                    updateUserLocationMarker(currentLocation);
                }
            }

            @Override
            public void onStopNavi() {
                Log.d("NavActivity", "导航结束");
                Toast.makeText(WalkNaviActivity.this, "导航结束", Toast.LENGTH_SHORT).show();

                // 停止导航时停止持续定位
                isNavigating = false;
                stopContinuousLocationUpdates();

                // 移除用户位置标记
                removeUserLocationMarker();
            }

            @Override
            public void onArrivedDestination() {
                Log.d("NavActivity", "到达目的地");
                Toast.makeText(WalkNaviActivity.this, "已到达目的地", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onUpdateAttachedLocation(AttachedLocation attachedLocation) {

            }

            @Override
            public void onGpsRssiChanged(int i) {

            }

            @Override
            public void onOffRoute() {
                Log.d("NavActivity", "导航触发偏航");
                Toast.makeText(WalkNaviActivity.this, "偏离路线，正在重新规划", Toast.LENGTH_SHORT).show();
            }

            @Override
            public int onVoiceBroadcast(NaviTts tts) {
                // 语音播报的回调
                Log.d("NavActivity", "语音播报: " + tts.getText());
                return 1; // 表示播报成功
            }

            @Override
            public void onUpdateNavigationData(NavigationData data) {
                // 更新导航面板所需数据
                if (data != null) {
                    Log.d("NavActivity", "导航数据更新");

                    // 更新导航信息显示
                    runOnUiThread(() -> {
                        updateNavigationInfo(data);
                    });
                }
            }

            @Override
            public void onGpsWeakNotify() {

            }

            @Override
            public void onGpsStrongNotify() {

            }

            @Override
            public void onGpsStatusChanged(boolean b) {

            }

            @Override
            public void onUpdateCurrentRoute(RouteData routeData) {

            }

            @Override
            public void onChangeRes(boolean b) {

            }
        });

        // 初始化UI控件
        initViews();

        // 设置事件监听器
        setupListeners();
    }

    private void initViews() {
        etStartLocation = findViewById(R.id.et_start_location);
        etDestLocation = findViewById(R.id.et_dest_location);
        btnStartNavigation = findViewById(R.id.btn_start_navigation);
        btnTogglePanel = findViewById(R.id.btn_toggle_panel);
        btnUseCurrentLocation = findViewById(R.id.btn_use_current_location);
        routeSettingPanel = findViewById(R.id.route_setting_panel);

        // 初始化导航信息显示控件
        navigationInfoPanel = findViewById(R.id.navigation_info_panel);
        ivTurnIcon = findViewById(R.id.iv_turn_icon);
        tvNextRoad = findViewById(R.id.tv_next_road);
        tvCurrentRoad = findViewById(R.id.tv_current_road);
        tvDistanceToNext = findViewById(R.id.tv_distance_to_next);
        tvTotalRemaining = findViewById(R.id.tv_total_remaining);

        // 初始化定位管理器
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // 设置默认值
        etStartLocation.setText("家");
        // etDestLocation.setText("驿站"); // 已注释 - 用户反馈驿站输入框没什么用

        // 设置按钮文本
        btnUseCurrentLocation.setText("从当前位置导航");
    }

    private void setupListeners() {
        // 开始导航按钮点击事件
        btnStartNavigation.setOnClickListener(v -> startNavigation());

        // 收起/展开面板按钮点击事件
        btnTogglePanel.setOnClickListener(v -> togglePanel());

        // 使用当前位置按钮点击事件
        btnUseCurrentLocation.setOnClickListener(v -> {
            // 显示GPS定位提示
            Toast.makeText(this, "正在获取GPS位置，初次定位时间可能较长，请耐心等待...", Toast.LENGTH_LONG).show();
            getCurrentLocationAndStartNavi();
        });
    }

    /**
     * 根据地址名称获取坐标
     * 这里使用预设的地址坐标映射，实际项目中可以使用腾讯地图的地址解析API
     */
    private NaviPoi getLocationCoordinates(String locationName) {
        NaviPoi poi = null;

        // 检查是否为当前位置
        if (locationName.startsWith("当前位置") && currentLocation != null) {
            poi = new NaviPoi(currentLocation.getLatitude(), currentLocation.getLongitude());
            poi.setPoiName("当前位置");
            return poi;
        }

        // 预设的地址坐标映射
        switch (locationName) {
            case "北京理工大学":
                poi = new NaviPoi(39.926296, 116.309901);
                poi.setPoiName("北京理工大学");
                break;
            case "天安门广场":
                poi = new NaviPoi(39.917834, 116.397271);
                poi.setPoiId("8314157447236438749");
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
                // 如果没有匹配的地址，尝试使用默认坐标（天安门广场）
                Log.w("NavActivity", "未找到地址: " + locationName + "，使用默认坐标");
                poi = new NaviPoi(39.917834, 116.397271);
                poi.setPoiName(locationName);
                break;
        }

        return poi;
    }

    /**
     * 获取当前位置并启动导航
     */
    private void getCurrentLocationAndStartNavi() {
        // 检查终点是否已输入
        String destLocation = etDestLocation.getText().toString().trim();
        if (TextUtils.isEmpty(destLocation)) {
            Toast.makeText(this, "请先输入目的地地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查定位权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // 请求定位权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        Toast.makeText(this, "正在获取当前位置并启动导航...", Toast.LENGTH_SHORT).show();
        btnUseCurrentLocation.setText("定位中");
        btnUseCurrentLocation.setEnabled(false);

        // 初始化超时处理器
        if (locationTimeoutHandler == null) {
            locationTimeoutHandler = new android.os.Handler();
        }

        // 设置15秒超时
        locationTimeoutRunnable = () -> {
            Log.w(TAG, "GPS定位超时");
            Toast.makeText(WalkNaviActivity.this, "GPS定位超时，请确保在空旷地区并开启GPS", Toast.LENGTH_LONG).show();
            stopLocationUpdates();
            resetLocationButton();
        };
        locationTimeoutHandler.postDelayed(locationTimeoutRunnable, 15000);

        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            Log.d(TAG, "GPS启用状态: " + gpsEnabled + ", 网络定位启用状态: " + networkEnabled);

            // 只使用GPS定位，精度更高
            if (gpsEnabled) {
                Log.d(TAG, "开始GPS定位");
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0,
                        0,
                        naviLocationListener);
                Log.d(TAG, "GPS定位请求已发送，等待位置回调");
            } else {
                Log.w(TAG, "GPS未启用，请在设置中开启GPS");
                Toast.makeText(this, "请开启GPS以获得更精确的定位", Toast.LENGTH_LONG).show();
                if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
                    locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
                }
                resetLocationButton();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "定位权限被拒绝: " + e.getMessage());
            Toast.makeText(this, "定位权限被拒绝", Toast.LENGTH_SHORT).show();
            if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
                locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
            }
            resetLocationButton();
        }
    }

    /**
     * 获取当前位置（仅用于更新输入框）
     */
    private void getCurrentLocation() {
        // 检查定位权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // 请求定位权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        Toast.makeText(this, "正在获取当前位置...", Toast.LENGTH_SHORT).show();
        btnUseCurrentLocation.setText("定位中");
        btnUseCurrentLocation.setEnabled(false);

        try {
            // 只使用GPS获取位置
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0,
                        0,
                        locationListener);
            } else {
                Toast.makeText(this, "请开启GPS以获得精确定位", Toast.LENGTH_LONG).show();
                resetLocationButton();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "定位权限被拒绝: " + e.getMessage());
            Toast.makeText(this, "定位权限被拒绝", Toast.LENGTH_SHORT).show();
            resetLocationButton();
        }
    }

    /**
     * 停止定位更新
     */
    private void stopLocationUpdates() {
        try {
            if (locationManager != null && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.removeUpdates(naviLocationListener);
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "停止定位更新失败: " + e.getMessage());
        }
    }

    /**
     * 启动导航中的持续定位
     */
    private void startContinuousLocationUpdates() {
        if (!isNavigating) {
            return;
        }

        // 检查定位权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有定位权限，无法启动持续定位");
            return;
        }

        try {
            // 设置合理的时间间隔（5秒）和距离间隔（10米）
            long timeInterval = 5000; // 5秒
            float distanceInterval = 10.0f; // 10米

            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            Log.d(TAG, "启动导航持续定位 - GPS: " + gpsEnabled + ", Network: " + networkEnabled);

            // 只使用GPS进行持续定位
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        timeInterval,
                        distanceInterval,
                        continuousLocationListener);
                Log.d(TAG, "GPS持续定位已启动");
            } else {
                Log.w(TAG, "GPS未启用，无法进行持续定位");
                Toast.makeText(this, "请开启GPS以确保导航精度", Toast.LENGTH_SHORT).show();
            }

        } catch (SecurityException e) {
            Log.e(TAG, "启动持续定位失败: " + e.getMessage());
        }
    }

    /**
     * 停止导航中的持续定位
     */
    private void stopContinuousLocationUpdates() {
        try {
            if (locationManager != null && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.removeUpdates(continuousLocationListener);
                Log.d(TAG, "持续定位已停止");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止持续定位失败: " + e.getMessage());
        }
    }

    /**
     * 重置定位按钮状态
     */
    private void resetLocationButton() {
        btnUseCurrentLocation.setText("从当前位置导航");
        btnUseCurrentLocation.setEnabled(true);
    }

    /**
     * 导航定位监听器（获取位置后直接启动导航）
     */
    private final LocationListener naviLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            Log.d(TAG, "获取到位置: " + location.getLatitude() + ", " + location.getLongitude());

            // 取消超时任务
            if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
                locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
            }

            // 停止定位更新
            stopLocationUpdates();

            // 获取目的地
            String destLocation = etDestLocation.getText().toString().trim();

            // 创建起点POI（当前位置）
            NaviPoi startPoi = new NaviPoi(location.getLatitude(), location.getLongitude());
            startPoi.setPoiName("当前位置");

            // 获取终点坐标
            NaviPoi destPoi = getLocationCoordinates(destLocation);
            if (destPoi == null) {
                Toast.makeText(WalkNaviActivity.this, "无法识别目的地地址，请重新输入", Toast.LENGTH_SHORT).show();
                resetLocationButton();
                return;
            }

            Toast.makeText(WalkNaviActivity.this, "定位成功，开始导航规划...", Toast.LENGTH_SHORT).show();

            // 直接启动导航
            startNavigationWithPoi(startPoi, destPoi);
            resetLocationButton();
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "定位提供者启用: " + provider);
            Toast.makeText(WalkNaviActivity.this, "定位服务已启用: " + provider, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "定位提供者禁用: " + provider);
            Toast.makeText(WalkNaviActivity.this, "定位服务已禁用: " + provider, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "定位状态改变: " + provider + ", status: " + status);
            String statusText = "";
            switch (status) {
                case android.location.LocationProvider.AVAILABLE:
                    statusText = "可用";
                    break;
                case android.location.LocationProvider.OUT_OF_SERVICE:
                    statusText = "服务中断";
                    break;
                case android.location.LocationProvider.TEMPORARILY_UNAVAILABLE:
                    statusText = "暂时不可用";
                    break;
            }
            Log.d(TAG, provider + " 状态: " + statusText);
        }
    };

    /**
     * 定位监听器（仅用于更新输入框）
     */
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            Log.d(TAG, "获取到位置: " + location.getLatitude() + ", " + location.getLongitude());

            // 停止定位更新
            if (ActivityCompat.checkSelfPermission(WalkNaviActivity.this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.removeUpdates(this);
            }

            // 更新起点输入框
            String locationText = "当前位置(" +
                    String.format("%.6f", location.getLatitude()) + ", " +
                    String.format("%.6f", location.getLongitude()) + ")";
            etStartLocation.setText(locationText);

            Toast.makeText(WalkNaviActivity.this, "定位成功", Toast.LENGTH_SHORT).show();
            resetLocationButton();
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
     * 导航中的持续定位监听器
     */
    private final LocationListener continuousLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!isNavigating) {
                return;
            }

            currentLocation = location;
            Log.d(TAG, "导航中位置更新: " + location.getLatitude() + ", " + location.getLongitude() +
                    ", 精度: " + location.getAccuracy() + "m");

            // 更新地图上的用户位置标记
            updateUserLocationMarker(location);

            // 更新导航系统的当前位置
            if (manager != null) {
                // 这里可以调用腾讯导航SDK的位置更新方法
                // manager.updateLocation(location); // 根据实际SDK方法调用
            }

            // 可以在这里添加位置变化的业务逻辑
            // 比如检查是否偏离路线、更新UI显示等
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "导航定位提供者启用: " + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "导航定位提供者禁用: " + provider);
            if (isNavigating) {
                Toast.makeText(WalkNaviActivity.this, "定位服务已禁用，可能影响导航精度", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "导航定位状态改变: " + provider + ", status: " + status);
        }
    };

    /**
     * 权限请求结果处理
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，重新尝试获取位置
                getCurrentLocation();
            } else {
                Toast.makeText(this, "定位权限被拒绝，无法获取当前位置", Toast.LENGTH_LONG).show();
                resetLocationButton();
            }
        }
    }

    private void startNavigation() {
        String startLocation = etStartLocation.getText().toString().trim();
        String destLocation = etDestLocation.getText().toString().trim();

        if (TextUtils.isEmpty(startLocation)) {
            Toast.makeText(this, "请输入起点地址", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(destLocation)) {
            Toast.makeText(this, "请输入终点地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // 开始路线规划
        initRouteSearch(startLocation, destLocation);
    }

    private void togglePanel() {
        if (isPanelVisible) {
            routeSettingPanel.setVisibility(View.GONE);
            btnTogglePanel.setText("展开");
            isPanelVisible = false;
        } else {
            routeSettingPanel.setVisibility(View.VISIBLE);
            btnTogglePanel.setText("收起");
            isPanelVisible = true;
        }
    }

    /**
     * 使用POI对象直接启动导航
     */
    private void startNavigationWithPoi(NaviPoi startPoi, NaviPoi destPoi) {
        Log.d(TAG, "开始导航规划 - 起点: " + startPoi.getLatitude() + "," + startPoi.getLongitude() +
                " 终点: " + destPoi.getLatitude() + "," + destPoi.getLongitude());

        // 发起步行导航路线规划
        try {
            manager.searchRoute(startPoi, destPoi, new TencentRouteSearchCallback() {
                @Override
                public void onCalcRouteSuccess(CalcRouteResult calcRouteResult) {
                    Log.d(TAG, "路线计算成功");
                }

                @Override
                public void onCalcRouteFailure(CalcRouteResult calcRouteResult) {
                    Log.e(TAG, "路线计算失败: " + calcRouteResult.getErrorCode());
                    Toast.makeText(WalkNaviActivity.this, "路线计算失败", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> routeDataList) {
                    Log.d(TAG, "路线规划成功，路线数量: " + routeDataList.size());
                    Toast.makeText(WalkNaviActivity.this, "路线规划成功，开始导航", Toast.LENGTH_SHORT).show();

                    if (!routeDataList.isEmpty()) {
                        // 开始导航
                        try {
                            manager.startNavi(0);
                        } catch (Exception e) {
                            Log.e(TAG, "启动导航失败: " + e.getMessage());
                            Toast.makeText(WalkNaviActivity.this, "启动导航失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }

                @Override
                public void onRouteSearchFailure(int errorCode, String errorMsg) {
                    Log.e(TAG, "路线规划失败: " + errorCode + ", " + errorMsg);
                    Toast.makeText(WalkNaviActivity.this, "路线规划失败: " + errorMsg, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "导航启动异常: " + e.getMessage());
            Toast.makeText(this, "导航启动异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initRouteSearch(String startLocationName, String destLocationName) {
        // 获取起点坐标
        NaviPoi start = getLocationCoordinates(startLocationName);
        if (start == null) {
            Toast.makeText(this, "无法识别起点地址，请重新输入", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取终点坐标
        NaviPoi dest = getLocationCoordinates(destLocationName);
        if (dest == null) {
            Toast.makeText(this, "无法识别终点地址，请重新输入", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在规划路线...", Toast.LENGTH_SHORT).show();

        // 发起步行导航路线规划
        try {
            manager.searchRoute(start, dest, new TencentRouteSearchCallback() {
                @Override
                public void onCalcRouteSuccess(CalcRouteResult calcRouteResult) {
                    Log.d("NavActivity", "路线计算成功");
                }

                @Override
                public void onCalcRouteFailure(CalcRouteResult calcRouteResult) {
                    Log.e("NavActivity", "路线计算失败: " + calcRouteResult.getErrorCode());
                    Toast.makeText(WalkNaviActivity.this, "路线计算失败", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> routeDataList) {
                    Log.d("NavActivity", "路线规划成功，路线数量: " + routeDataList.size());
                    Toast.makeText(WalkNaviActivity.this, "路线规划成功", Toast.LENGTH_SHORT).show();

                    if (!routeDataList.isEmpty()) {
                        // 开始导航
                        try {
                            manager.startNavi(0);
                        } catch (Exception e) {
                            Log.e("NavActivity", "启动导航失败: " + e.getMessage());
                            Toast.makeText(WalkNaviActivity.this, "启动导航失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }

                @Override
                public void onRouteSearchFailure(int errorCode, String errorMsg) {
                    Log.e("NavActivity", "路线规划失败: " + errorCode + ", " + errorMsg);
                    Toast.makeText(WalkNaviActivity.this, "路线规划失败: " + errorMsg, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWalkNaviView != null) {
            mWalkNaviView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWalkNaviView != null) {
            mWalkNaviView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 取消超时任务
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }

        // 停止所有定位更新
        stopLocationUpdates();
        stopContinuousLocationUpdates();

        // 重置导航状态
        isNavigating = false;

        // 清理用户位置标记
        removeUserLocationMarker();

        if (mWalkNaviView != null) {
            mWalkNaviView.onDestroy();
        }
        if (manager != null) {
            manager.stopNavi();
        }
    }

    /**
     * 更新导航信息显示
     */
    private void updateNavigationInfo(NavigationData naviData) {
        if (naviData == null) {
            return;
        }

        // 显示导航信息面板
        if (navigationInfoPanel.getVisibility() != View.VISIBLE) {
            navigationInfoPanel.setVisibility(View.VISIBLE);
        }

        // 更新下一个转向道路
        String nextRoadName = naviData.getNextRoadName();
        if (nextRoadName != null && !nextRoadName.isEmpty()) {
            tvNextRoad.setText(nextRoadName);
        } else {
            tvNextRoad.setText("继续直行");
        }

        // 更新当前道路
        String currentRoadName = naviData.getCurrentRoadName();
        if (currentRoadName != null && !currentRoadName.isEmpty()) {
            tvCurrentRoad.setText(currentRoadName);
        } else {
            tvCurrentRoad.setText("当前道路");
        }

        // 更新距离下一个转向点的距离
        int distanceToNext = naviData.getDistanceToNextRoad();
        if (distanceToNext > 0) {
            if (distanceToNext >= 1000) {
                tvDistanceToNext.setText(String.format("%.1fkm", distanceToNext / 1000.0));
            } else {
                tvDistanceToNext.setText(distanceToNext + "m");
            }
        } else {
            tvDistanceToNext.setText("--");
        }

        // 更新剩余总距离和时间
        int remainingDistance = naviData.getLeftDistance();
        int remainingTime = naviData.getRemainTrafficLightCount();

        String remainingInfo = "";
        if (remainingDistance > 0) {
            if (remainingDistance >= 1000) {
                remainingInfo += String.format("%.1fkm", remainingDistance / 1000.0);
            } else {
                remainingInfo += remainingDistance + "m";
            }
        }

        if (remainingTime > 0) {
            int minutes = remainingTime / 60;
            if (minutes > 0) {
                remainingInfo += " | " + minutes + "分钟";
            } else {
                remainingInfo += " | <1分钟";
            }
        }

        if (!remainingInfo.isEmpty()) {
            tvTotalRemaining.setText(remainingInfo);
        } else {
            tvTotalRemaining.setText("计算中...");
        }

        // 更新转向文字显示
        int turnType = naviData.getTurnDirection();
        updateTurnText(turnType);
    }

    /**
     * 根据转向类型更新转向文字显示
     */
    private void updateTurnText(int turnType) {
        // 根据turnType设置不同的文字描述
        String turnText;
        switch (turnType) {
            case 1: // 左转
                turnText = "左转";
                break;
            case 2: // 右转
                turnText = "右转";
                break;
            case 3: // 左前方
                turnText = "左前方";
                break;
            case 4: // 右前方
                turnText = "右前方";
                break;
            case 5: // 掉头
                turnText = "掉头";
                break;
            case 0: // 直行
            default:
                turnText = "直行";
                break;
        }

        // 显示转向文字
        ivTurnIcon.setText(turnText);
    }

    /**
     * 手动控制持续定位开关
     * @param enable true为启用，false为禁用
     */
    public void setContinuousLocationEnabled(boolean enable) {
        if (enable && isNavigating) {
            startContinuousLocationUpdates();
            Log.d(TAG, "手动启用持续定位");
        } else {
            stopContinuousLocationUpdates();
            Log.d(TAG, "手动禁用持续定位");
        }
    }

    /**
     * 获取当前是否正在导航
     * @return true表示正在导航，false表示未导航
     */
    public boolean isNavigating() {
        return isNavigating;
    }

    /**
     * 创建或更新用户位置标记
     */
    private void updateUserLocationMarker(Location location) {
        if (tencentMap == null || location == null) {
            return;
        }

        LatLng userLatLng = new LatLng(location.getLatitude(), location.getLongitude());

        if (userLocationMarker == null) {
            // 创建用户位置标记
            MarkerOptions markerOptions = new MarkerOptions()
                    .position(userLatLng)
                    .title("我的位置")
                    .snippet("精度: " + String.format("%.1f", location.getAccuracy()) + "米")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    .anchor(0.5f, 0.5f); // 设置锚点为标记中心

            userLocationMarker = tencentMap.addMarker(markerOptions);
            Log.d(TAG, "创建用户位置标记: " + userLatLng.toString());
        } else {
            // 更新现有标记位置
            userLocationMarker.setPosition(userLatLng);
            userLocationMarker.setSnippet("精度: " + String.format("%.1f", location.getAccuracy()) + "米");
            Log.d(TAG, "更新用户位置标记: " + userLatLng.toString());
        }
    }

    /**
     * 移除用户位置标记
     */
    private void removeUserLocationMarker() {
        if (userLocationMarker != null) {
            userLocationMarker.remove();
            userLocationMarker = null;
            Log.d(TAG, "移除用户位置标记");
        }
    }

    private void initTencentNavi() {
        TencentNavi.Config config = new TencentNavi.Config();
        // 记录设备标识，反馈导航问题时请提供该设备标识以及发生问题的时间
        String deviceID = DeviceUtils.getImei(getApplicationContext());
        config.setDeviceId(deviceID);
        // 或者设置开发者自己的的设备号config.setDeviceId(xxxxxxxx);
        TencentNavi.init(this, config);
    }
}