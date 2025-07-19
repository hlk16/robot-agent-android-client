package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.managers.NavigationServiceManager;

/**
 * 导航后台服务测试Activity
 * 演示如何使用NavigationBackgroundService
 */
public class NavigationTestActivity extends AppCompatActivity {
    
    private static final String TAG = "NavigationTestActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    
    // UI控件
    private EditText etDestination;
    private Button btnStartService;
    private Button btnStopService;
    private Button btnStartNavigation;
    private Button btnStopNavigation;
    private TextView tvServiceStatus;
    private TextView tvNavigationInfo;
    private TextView tvDistanceToDestination;
    private TextView tvCurrentDirection;
    private TextView tvDistanceToNext;
    private TextView tvCurrentRoad;
    private TextView tvNextRoad;
    
    // 导航服务管理器
    private NavigationServiceManager serviceManager;
    
    // 定时更新导航信息
    private Handler updateHandler;
    private Runnable updateRunnable;
    private boolean isUpdating = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation_test);
        
        initViews();
        setupListeners();
        
        // 初始化导航服务管理器
        serviceManager = NavigationServiceManager.getInstance(this);
        serviceManager.setServiceConnectionListener(new NavigationServiceManager.ServiceConnectionListener() {
            @Override
            public void onServiceConnected() {
                runOnUiThread(() -> {
                    tvServiceStatus.setText("服务状态: 已连接");
                    btnStartNavigation.setEnabled(true);
                    Toast.makeText(NavigationTestActivity.this, "导航服务已连接", Toast.LENGTH_SHORT).show();
                });
            }
            
            @Override
            public void onServiceDisconnected() {
                runOnUiThread(() -> {
                    tvServiceStatus.setText("服务状态: 已断开");
                    btnStartNavigation.setEnabled(false);
                    stopUpdatingNavigationInfo();
                    Toast.makeText(NavigationTestActivity.this, "导航服务已断开", Toast.LENGTH_SHORT).show();
                });
            }
        });
        
        // 初始化更新处理器
        updateHandler = new Handler();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNavigationInfo();
                if (isUpdating) {
                    updateHandler.postDelayed(this, 2000); // 每2秒更新一次
                }
            }
        };
        
        // 检查权限
        checkPermissions();
    }
    
    private void initViews() {
        etDestination = findViewById(R.id.et_destination);
        btnStartService = findViewById(R.id.btn_start_service);
        btnStopService = findViewById(R.id.btn_stop_service);
        btnStartNavigation = findViewById(R.id.btn_start_navigation);
        btnStopNavigation = findViewById(R.id.btn_stop_navigation);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvNavigationInfo = findViewById(R.id.tv_navigation_info);
        tvDistanceToDestination = findViewById(R.id.tv_distance_to_destination);
        tvCurrentDirection = findViewById(R.id.tv_current_direction);
        tvDistanceToNext = findViewById(R.id.tv_distance_to_next);
        tvCurrentRoad = findViewById(R.id.tv_current_road);
        tvNextRoad = findViewById(R.id.tv_next_road);
        
        // 设置默认目的地
        etDestination.setText("驿站");
        
        // 初始状态
        tvServiceStatus.setText("服务状态: 未连接");
        btnStartNavigation.setEnabled(false);
        btnStopNavigation.setEnabled(false);
    }
    
    private void setupListeners() {
        // 启动服务
        btnStartService.setOnClickListener(v -> {
            if (hasLocationPermission()) {
                serviceManager.startAndBindService();
                btnStartService.setEnabled(false);
                btnStopService.setEnabled(true);
                Toast.makeText(this, "正在启动导航服务...", Toast.LENGTH_SHORT).show();
            } else {
                requestLocationPermission();
            }
        });
        
        // 停止服务
        btnStopService.setOnClickListener(v -> {
            serviceManager.stopAndUnbindService();
            btnStartService.setEnabled(true);
            btnStopService.setEnabled(false);
            btnStartNavigation.setEnabled(false);
            btnStopNavigation.setEnabled(false);
            tvServiceStatus.setText("服务状态: 已停止");
            stopUpdatingNavigationInfo();
            clearNavigationInfo();
            Toast.makeText(this, "导航服务已停止", Toast.LENGTH_SHORT).show();
        });
        
        // 开始导航
        btnStartNavigation.setOnClickListener(v -> {
            String destination = etDestination.getText().toString().trim();
            if (destination.isEmpty()) {
                Toast.makeText(this, "请输入目的地", Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (serviceManager.startNavigationToDestination(destination)) {
                btnStartNavigation.setEnabled(false);
                btnStopNavigation.setEnabled(true);
                startUpdatingNavigationInfo();
                Toast.makeText(this, "开始导航到: " + destination, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "启动导航失败，请检查服务状态", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 停止导航
        btnStopNavigation.setOnClickListener(v -> {
            if (serviceManager.stopNavigation()) {
                btnStartNavigation.setEnabled(true);
                btnStopNavigation.setEnabled(false);
                stopUpdatingNavigationInfo();
                clearNavigationInfo();
                Toast.makeText(this, "导航已停止", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 开始更新导航信息
     */
    private void startUpdatingNavigationInfo() {
        isUpdating = true;
        updateHandler.post(updateRunnable);
    }
    
    /**
     * 停止更新导航信息
     */
    private void stopUpdatingNavigationInfo() {
        isUpdating = false;
        updateHandler.removeCallbacks(updateRunnable);
    }
    
    /**
     * 更新导航信息显示
     */
    private void updateNavigationInfo() {
        if (!serviceManager.isServiceConnected()) {
            return;
        }
        
        // 获取导航数据
        int distanceToDestination = serviceManager.getDistanceToDestination();
        String currentDirection = serviceManager.getCurrentDirection();
        int distanceToNext = serviceManager.getDistanceToNextTurn();
        String currentRoad = serviceManager.getCurrentRoadName();
        String nextRoad = serviceManager.getNextRoadName();
        boolean isNavigating = serviceManager.isNavigating();
        
        // 更新UI
        tvDistanceToDestination.setText("到目的地距离: " + 
            NavigationServiceManager.formatDistance(distanceToDestination));
        tvCurrentDirection.setText("当前方向: " + currentDirection);
        tvDistanceToNext.setText("下一转向距离: " + 
            NavigationServiceManager.formatDistance(distanceToNext));
        tvCurrentRoad.setText("当前道路: " + currentRoad);
        tvNextRoad.setText("下一道路: " + nextRoad);
        
        // 更新导航摘要
        if (isNavigating) {
            String summary = serviceManager.getNavigationSummary();
            tvNavigationInfo.setText(summary);
        } else {
            tvNavigationInfo.setText("导航未启动");
        }
        
        Log.d(TAG, "导航信息更新 - 距离: " + distanceToDestination + 
              "m, 方向: " + currentDirection + ", 下一转向: " + distanceToNext + "m");
    }
    
    /**
     * 清空导航信息显示
     */
    private void clearNavigationInfo() {
        tvNavigationInfo.setText("导航未启动");
        tvDistanceToDestination.setText("到目的地距离: --");
        tvCurrentDirection.setText("当前方向: --");
        tvDistanceToNext.setText("下一转向距离: --");
        tvCurrentRoad.setText("当前道路: --");
        tvNextRoad.setText("下一道路: --");
    }
    
    /**
     * 检查定位权限
     */
    private void checkPermissions() {
        if (!hasLocationPermission()) {
            requestLocationPermission();
        }
    }
    
    /**
     * 检查是否有定位权限
     */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * 请求定位权限
     */
    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                           Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                LOCATION_PERMISSION_REQUEST_CODE);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "定位权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "定位权限被拒绝，导航功能将无法使用", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 停止更新
        stopUpdatingNavigationInfo();
        
        // 清理服务管理器
        if (serviceManager != null) {
            serviceManager.cleanup();
        }
    }
}