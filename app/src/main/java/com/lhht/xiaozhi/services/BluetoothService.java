package com.lhht.xiaozhi.services;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;


import com.lhht.xiaozhi.activities.BtThread.ConnectedThread;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙后台服务
 * 管理蓝牙连接，提供发送指令接口
 */
public class BluetoothService extends Service {
    private static final String TAG = "BluetoothService";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    //IBinder 就是个"传话筒"，让 Activity 能拿到 Service 对象，然后调用它的方法。
    private final IBinder binder = new LocalBinder();
    private BluetoothSocket socket;
    private ConnectedThread connectedThread;
    //线程池
    private ExecutorService executorService;

    // 连接状态livedata，订阅者可以监听连接状态变化
    private final MutableLiveData<Boolean> connectionState = new MutableLiveData<>(false);

    // 收到的数据（用于接收单片机返回）
    private final MutableLiveData<String> receivedData = new MutableLiveData<>();

    public class LocalBinder extends Binder {//本地绑定器，用于 Activity 调用 Service 方法
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadExecutor();
        Log.d(TAG, "蓝牙服务已创建");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    /**
     * 连接蓝牙设备
     */
    public void connect(BluetoothDevice device, ConnectCallback callback) {
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少 BLUETOOTH_CONNECT 权限");
            if (callback != null) {
                callback.onError("缺少蓝牙权限");
            }
            return;
        }

        // 断开旧连接
        disconnect();

        executorService.execute(() -> {
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                // 启动通信线程
                connectedThread = new ConnectedThread(socket, data -> {
                    receivedData.postValue(data);
                });
                connectedThread.start();

                connectionState.postValue(true);

                // 安全获取设备名称
                String deviceName = "未知设备";
                try {
                    deviceName = device.getName();
                } catch (SecurityException e) {
                    Log.w(TAG, "获取设备名称失败", e);
                }
                Log.d(TAG, "蓝牙连接成功: " + deviceName);

                // 发送测试消息
                try {
                    Thread.sleep(500); // 等待连接稳定
                    sendCommand("HELLO");
                    Log.d(TAG, "已发送测试消息: HELLO");
                } catch (InterruptedException e) {
                    Log.w(TAG, "发送测试消息被中断", e);
                }

                if (callback != null) {
                    callback.onConnected();
                }

            } catch (SecurityException e) {
                Log.e(TAG, "蓝牙连接失败，权限被拒绝", e);
                connectionState.postValue(false);

                if (callback != null) {
                    callback.onError("蓝牙权限被拒绝");
                }
            } catch (Exception e) {
                Log.e(TAG, "蓝牙连接失败", e);
                connectionState.postValue(false);

                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    /**
     * 发送字符串指令
     */
    public void sendCommand(String command) {
        if (connectedThread != null && isConnected()) {
            connectedThread.write(command.getBytes());
            Log.d(TAG, "发送指令: " + command);
        } else {
            Log.w(TAG, "蓝牙未连接，无法发送: " + command);
        }
    }

    /**
     * 发送单个字符指令 (a,b,c,d,e,f,g,h,i...)
     */
    public void sendChar(char cmd) {
        sendCommand(String.valueOf(cmd));
    }

    /**
     * 发送距离数据
     */
    public void sendDistance(double distance) {
        String command = "DISTANCE:" + String.format("%.1f", distance);
        sendCommand(command);
    }

    /**
     * 检查连接状态
     */
    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    /**
     * 获取连接状态 LiveData
     */
    public LiveData<Boolean> getConnectionState() {
        return connectionState;
    }

    /**
     * 获取收到的数据 LiveData
     */
    public LiveData<String> getReceivedData() {
        return receivedData;
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭socket失败", e);
            }
            socket = null;
        }
        connectionState.postValue(false);
        Log.d(TAG, "蓝牙已断开");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        if (executorService != null) {
            executorService.shutdown();
        }
        Log.d(TAG, "蓝牙服务已销毁");
    }

    /**
     * 连接回调接口
     */
    public interface ConnectCallback {
        void onConnected();
        void onError(String error);
    }
}
