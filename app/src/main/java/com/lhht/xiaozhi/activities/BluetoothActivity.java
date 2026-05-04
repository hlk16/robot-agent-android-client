package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.services.BluetoothService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 蓝牙连接页面
 * 用于搜索、连接蓝牙设备
 */
public class BluetoothActivity extends AppCompatActivity {
    private static final String TAG = "BluetoothActivity";

    private ListView btList;
    private ArrayList<BluetoothDevice> readyDevices = new ArrayList<>();
    private ArrayAdapter<String> btNames;

    // 蓝牙服务
    private BluetoothService btService;
    private boolean serviceBound = false;

    // Service 连接回调
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            btService = binder.getService();
            serviceBound = true;
            Log.d(TAG, "蓝牙服务已绑定");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            btService = null;
            Log.d(TAG, "蓝牙服务已断开");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth);

        // 绑定蓝牙服务
        bindBluetoothService();

        // 初始化视图
        initViews();

        // 检查并申请权限
        checkPermissions();

        // 加载已配对设备
        loadPairedDevices();
    }

    /**
     * 绑定蓝牙服务
     */
    private void bindBluetoothService() {
        Intent intent = new Intent(this, BluetoothService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        btList = findViewById(R.id.btList);
        Button back = findViewById(R.id.back);
        Button btnGoToChat = findViewById(R.id.btnGoToChat);

        // 设备列表适配器
        btNames = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        btList.setAdapter(btNames);

        // 点击设备连接
        btList.setOnItemClickListener((parent, view, position, id) -> {
            if (!serviceBound) {
                Toast.makeText(this, "服务未绑定，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }

            BluetoothDevice device = readyDevices.get(position);
            connectDevice(device);
        });

        // 跳转到语音通话页面
        back.setOnClickListener(v -> navigateTo(VoiceCallActivity.class));

        // 跳转到聊天页面
        btnGoToChat.setOnClickListener(v -> navigateTo(ChatActivity.class));
    }

    /**
     * 连接蓝牙设备
     */
    private void connectDevice(BluetoothDevice device) {
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予蓝牙权限", Toast.LENGTH_SHORT).show();
            return;
        }

        // 安全获取设备名称
        String deviceName = "未知设备";
        try {
            deviceName = device.getName();
        } catch (SecurityException e) {
            Log.w(TAG, "获取设备名称失败", e);
        }

        String finalDeviceName = deviceName;
        Toast.makeText(this, "正在连接 " + finalDeviceName, Toast.LENGTH_SHORT).show();

        btService.connect(device, new BluetoothService.ConnectCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    Toast.makeText(BluetoothActivity.this,
                            "连接成功: " + finalDeviceName, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(BluetoothActivity.this,
                            "连接失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 跳转到其他页面
     */
    private void navigateTo(Class<?> targetActivity) {
        if (!serviceBound || !btService.isConnected()) {
            Toast.makeText(this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        startActivity(new Intent(this, targetActivity));
    }

    /**
     * 加载已配对设备
     */
    private void loadPairedDevices() {
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }

        // 打开蓝牙
        if (!adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            return;
        }

        // 获取已配对设备（再次检查权限，避免警告）
        Set<BluetoothDevice> pairedDevices;
        try {
            pairedDevices = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Log.e(TAG, "获取配对设备失败，权限被拒绝", e);
            Toast.makeText(this, "蓝牙权限被拒绝", Toast.LENGTH_SHORT).show();
            return;
        }

        readyDevices.clear();
        btNames.clear();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                readyDevices.add(device);
                // 安全获取设备名称和地址
                String deviceName = "未知设备";
                String deviceAddress = "未知地址";
                try {
                    deviceName = device.getName();
                    deviceAddress = device.getAddress();
                } catch (SecurityException e) {
                    Log.w(TAG, "获取设备信息失败", e);
                }
                btNames.add(deviceName + "\n" + deviceAddress);
            }
            btNames.notifyDataSetChanged();
        } else {
            Toast.makeText(this, "没有已配对的设备", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查并申请权限
     */
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要运行时申请
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                });
            }
        }
    }

    /**
     * 权限申请回调
     */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean connectGranted = result.get(Manifest.permission.BLUETOOTH_CONNECT);
                if (connectGranted != null && connectGranted) {
                    // 权限 granted，重新加载设备
                    loadPairedDevices();
                } else {
                    Toast.makeText(this, "需要蓝牙权限才能使用", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 解绑服务（不会断开蓝牙连接，只是解除绑定）
        if (serviceBound) {
            unbindService(connection);
            serviceBound = false;
        }
    }
}
