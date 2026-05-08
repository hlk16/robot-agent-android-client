package com.lhht.xiaozhi.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.activities.BtThread.ConnectedThread;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙前台服务
 * 以通知栏保活方式管理蓝牙连接，提供发送指令接口
 */
public class BluetoothService extends Service {
    private static final String TAG = "BluetoothService";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String CHANNEL_ID = "bluetooth_channel";
    private static final int NOTIFICATION_ID = 1001;
    //binder对象就是BluetoothService.this
    private final IBinder binder = new LocalBinder();
    private BluetoothSocket socket;
    private ConnectedThread connectedThread;
    private ExecutorService executorService;

    private final MutableLiveData<Boolean> connectionState = new MutableLiveData<>(false);
    private final MutableLiveData<String> receivedData = new MutableLiveData<>();
    //activity 调用服务实例方法。要写localBinder
    public class LocalBinder extends Binder {//绑定类，用于在 Activity 中获取服务实例
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadExecutor();
        createNotificationChannel();
        startForegroundNotification("蓝牙服务已启动");
        Log.d(TAG, "蓝牙服务已创建（前台服务）");
    }

    /**
     * 创建通知渠道（Android 8.0+ 必须）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "蓝牙连接",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持蓝牙连接不被系统杀死");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 启动前台通知（Service 不会被系统杀掉）
     */
    private void startForegroundNotification(String text) {
        Intent notificationIntent = new Intent(this, com.lhht.xiaozhi.activities.BluetoothActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    //创建通知对象
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("蓝牙连接")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    /**
     * 更新通知内容
     */
    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("蓝牙连接")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setOngoing(true)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {//绑定服务
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少 BLUETOOTH_CONNECT 权限");
            if (callback != null) {
                callback.onError("缺少蓝牙权限");
            }
            return;
        }

        disconnect();

        executorService.execute(() -> {
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                connectedThread = new ConnectedThread(socket, data -> {
                    receivedData.postValue(data);
                });
                connectedThread.start();

                connectionState.postValue(true);
                updateNotification("蓝牙已连接");

                String deviceName = "未知设备";
                try {
                    deviceName = device.getName();
                } catch (SecurityException e) {
                    Log.w(TAG, "获取设备名称失败", e);
                }
                Log.d(TAG, "蓝牙连接成功: " + deviceName);

                // 发送测试消息
                try {
                    Thread.sleep(500);
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
                updateNotification("蓝牙连接失败");

                if (callback != null) {
                    callback.onError("蓝牙权限被拒绝");
                }
            } catch (Exception e) {
                Log.e(TAG, "蓝牙连接失败", e);
                connectionState.postValue(false);
                updateNotification("蓝牙连接失败");

                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void sendCommand(String command) {
        if (connectedThread != null && isConnected()) {
            connectedThread.write(command.getBytes());
            Log.d(TAG, "发送指令: " + command);
        } else {
            Log.w(TAG, "蓝牙未连接，无法发送: " + command);
        }
    }

    public void sendChar(char cmd) {
        sendCommand(String.valueOf(cmd));
    }

    public void sendDistance(double distance) {
        String command = "DISTANCE:" + String.format("%.1f", distance);
        sendCommand(command);
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public LiveData<Boolean> getConnectionState() {
        return connectionState;
    }

    public LiveData<String> getReceivedData() {
        return receivedData;
    }

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
        updateNotification("蓝牙已断开");
        Log.d(TAG, "蓝牙已断开");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        stopForeground(true); // 移除通知
        if (executorService != null) {
            executorService.shutdown();
        }
        Log.d(TAG, "蓝牙服务已销毁");
    }

    public interface ConnectCallback {
        void onConnected();
        void onError(String error);
    }
}
