package com.lhht.xiaozhi.activities;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.lhht.xiaozhi.R;
import com.tencent.map.navi.TencentNavi;
import com.tencent.map.navi.TencentRouteSearchCallback;
import com.tencent.map.navi.TencentWalkNaviListener;
import com.tencent.map.navi.data.AttachedLocation;
import com.tencent.map.navi.data.CalcRouteResult;
import com.tencent.map.navi.data.NaviPoi;
import com.tencent.map.navi.data.NaviTts;
import com.tencent.map.navi.data.NavigationData;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.ui.car.CarNaviInfoPanel;
import com.tencent.map.navi.walk.TencentWalkNaviManager;
import com.tencent.map.navi.walk.WalkNaviView;
import com.tencent.navi.surport.utils.DeviceUtils;

import java.util.ArrayList;

public class NavActivity extends AppCompatActivity {
    private TencentWalkNaviManager manager;
    private WalkNaviView mWalkNaviView;

    // UI控件
    private EditText etStartLocation;
    private EditText etDestLocation;
    private Button btnStartNavigation;
    private Button btnTogglePanel;
    private LinearLayout routeSettingPanel;
    private boolean isPanelVisible = true;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_nav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;

        });
        initTencentNavi();
        Toast.makeText(this, "初始化成功", Toast.LENGTH_SHORT).show();
        // 初始化导航控制类
        manager = new TencentWalkNaviManager(getApplicationContext());

        // 导航视图 com.tencent.map.navi.walk.WalkNaviView
        mWalkNaviView = findViewById(R.id.tnk_walk_navi_view);

        // 将视图与控制器关联
        manager.addTencentNaviListener(mWalkNaviView);

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
                Toast.makeText(NavActivity.this, "导航开始", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onStopNavi() {
                Log.d("NavActivity", "导航结束");
                Toast.makeText(NavActivity.this, "导航结束", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onArrivedDestination() {
                Log.d("NavActivity", "到达目的地");
                Toast.makeText(NavActivity.this, "已到达目的地", Toast.LENGTH_LONG).show();
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
                Toast.makeText(NavActivity.this, "偏离路线，正在重新规划", Toast.LENGTH_SHORT).show();
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
    private void initTencentNavi() {
        TencentNavi.Config config = new TencentNavi.Config();
        // 记录设备标识，反馈导航问题时请提供该设备标识以及发生问题的时间
        String deviceID = DeviceUtils.getImei(getApplicationContext());
        config.setDeviceId(deviceID);
        // 或者设置开发者自己的的设备号config.setDeviceId(xxxxxxxx);
        TencentNavi.init(this, config);
    }
    private void initViews() {
        etStartLocation = findViewById(R.id.et_start_location);
        etDestLocation = findViewById(R.id.et_dest_location);
        btnStartNavigation = findViewById(R.id.btn_start_navigation);
        btnTogglePanel = findViewById(R.id.btn_toggle_panel);
        routeSettingPanel = findViewById(R.id.route_setting_panel);

        // 设置默认值
        etStartLocation.setText("北京理工大学");
        etDestLocation.setText("天安门广场");
    }

    private void setupListeners() {
        // 开始导航按钮点击事件
        btnStartNavigation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startNavigation();
            }
        });

        // 收起/展开面板按钮点击事件
        btnTogglePanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel();
            }
        });
    }

    /**
     * 根据地址名称获取坐标
     * 这里使用预设的地址坐标映射，实际项目中可以使用腾讯地图的地址解析API
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
                poi.setPoiId("8314157447236438749");
                poi.setPoiName("天安门广场");
                break;
            case "北京大学":
                poi = new NaviPoi(39.998877, 116.316833);
                poi.setPoiName("北京大学");
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
            default:
                // 如果没有匹配的地址，尝试使用默认坐标（天安门广场）
                Log.w("NavActivity", "未找到地址: " + locationName + "，使用默认坐标");
                poi = new NaviPoi(39.917834, 116.397271);
                poi.setPoiName(locationName);
                break;
        }

        return poi;
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
                    Toast.makeText(NavActivity.this, "路线计算失败", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> routeDataList) {
                    Log.d("NavActivity", "路线规划成功，路线数量: " + routeDataList.size());
                    Toast.makeText(NavActivity.this, "路线规划成功", Toast.LENGTH_SHORT).show();

                    if (!routeDataList.isEmpty()) {
                        // 开始导航
                        try {
                            manager.startNavi(0);
                        } catch (Exception e) {
                            Log.e("NavActivity", "启动导航失败: " + e.getMessage());
                            Toast.makeText(NavActivity.this, "启动导航失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }

                @Override
                public void onRouteSearchFailure(int errorCode, String errorMsg) {
                    Log.e("NavActivity", "路线规划失败: " + errorCode + ", " + errorMsg);
                    Toast.makeText(NavActivity.this, "路线规划失败: " + errorMsg, Toast.LENGTH_LONG).show();
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
        if (mWalkNaviView != null) {
            mWalkNaviView.onDestroy();
        }
        if (manager != null) {
            manager.stopNavi();
        }
    }
}