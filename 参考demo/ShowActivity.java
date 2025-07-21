package com.example.tablerobot;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ShowActivity extends AppCompatActivity {
    
    private DataManager dataManager;
    private Handler handler;
    private Runnable updateRunnable;
    
    // UI控件
    private TextView tvCanvasCenter;
    private TextView tvCircleCenter;
    private TextView tvDeltaX;
    private TextView tvDeltaY;
    private TextView tvStatus;
    private Button btnStartService;
    private Button btnStopService;
    
    // Service状态
    private boolean isServiceRunning = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_show);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        // 初始化数据管理器
        dataManager = DataManager.getInstance(this);
        
        // 初始化UI控件
        initViews();
        
        // 初始化定时更新
        initUpdateTimer();
    }
    
    private void initViews() {
        tvCanvasCenter = findViewById(R.id.tv_canvas_center);
        tvCircleCenter = findViewById(R.id.tv_circle_center);
        tvDeltaX = findViewById(R.id.tv_delta_x);
        tvDeltaY = findViewById(R.id.tv_delta_y);
        tvStatus = findViewById(R.id.tv_status);
        btnStartService = findViewById(R.id.btn_start_service);
        btnStopService = findViewById(R.id.btn_stop_service);
        
        // 设置按钮点击事件
        btnStartService.setOnClickListener(v -> startDetectionService());
        btnStopService.setOnClickListener(v -> stopDetectionService());
        
        // 初始化按钮状态
        updateServiceButtons();
    }
    
    private void initUpdateTimer() {
        handler = new Handler(Looper.getMainLooper());
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDisplay();
                handler.postDelayed(this, 100); // 每100ms更新一次
            }
        };
    }
    
    private void updateDisplay() {
        if (dataManager.isDataValid()) {
            // 更新画布中心坐标
            String canvasCenter = String.format(Locale.getDefault(), 
                "X: %.0f, Y: %.0f", 
                dataManager.getCanvasCenterX(), 
                dataManager.getCanvasCenterY());
            tvCanvasCenter.setText(canvasCenter);
            
            // 更新圆心坐标和Delta值
            if (dataManager.isCircleDetected()) {
                String circleCenter = String.format(Locale.getDefault(), 
                    "X: %.0f, Y: %.0f", 
                    dataManager.getCircleCenterX(), 
                    dataManager.getCircleCenterY());
                tvCircleCenter.setText(circleCenter);
                
                String deltaX = String.format(Locale.getDefault(), "%.1f", dataManager.getDeltaX());
                String deltaY = String.format(Locale.getDefault(), "%.1f", dataManager.getDeltaY());
                tvDeltaX.setText(deltaX);
                tvDeltaY.setText(deltaY);
                
                // 更新状态
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String currentTime = sdf.format(new Date());
                tvStatus.setText("Data updated at " + currentTime);
            } else {
                tvCircleCenter.setText("No Circle Detected");
                tvDeltaX.setText("0.0");
                tvDeltaY.setText("0.0");
                tvStatus.setText("Circle not detected");
            }
        } else {
            // 数据过期或无效
            tvStatus.setText("Waiting for data...");
            tvCanvasCenter.setText("X: 0, Y: 0");
            tvCircleCenter.setText("No Circle Detected");
            tvDeltaX.setText("0.0");
            tvDeltaY.setText("0.0");
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (handler != null && updateRunnable != null) {
            handler.post(updateRunnable);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }
    
    /**
     * 启动摄像头检测服务
     */
    private void startDetectionService() {
        Intent serviceIntent = new Intent(this, CameraDetectionService.class);
        startService(serviceIntent);
        isServiceRunning = true;
        updateServiceButtons();
        Toast.makeText(this, "后台检测服务已启动", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 停止摄像头检测服务
     */
    private void stopDetectionService() {
        Intent serviceIntent = new Intent(this, CameraDetectionService.class);
        stopService(serviceIntent);
        isServiceRunning = false;
        updateServiceButtons();
        Toast.makeText(this, "后台检测服务已停止", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 更新服务控制按钮状态
     */
    private void updateServiceButtons() {
        if (btnStartService != null && btnStopService != null) {
            btnStartService.setEnabled(!isServiceRunning);
            btnStopService.setEnabled(isServiceRunning);
            
            if (isServiceRunning) {
                btnStartService.setText("检测服务运行中");
                btnStopService.setText("停止检测服务");
            } else {
                btnStartService.setText("启动检测服务");
                btnStopService.setText("服务已停止");
            }
        }
    }
}