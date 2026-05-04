package com.lhht.xiaozhi.activities.BtThread;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 蓝牙数据通信线程
 * 负责收发数据，不依赖任何 Activity
 */
public class ConnectedThread extends Thread {
    private static final String TAG = "ConnectedThread";

    private final BluetoothSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final OnDataReceivedListener listener;
    private volatile boolean running = true;

    public ConnectedThread(BluetoothSocket socket, OnDataReceivedListener listener) {
        this.socket = socket;
        this.listener = listener;

        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "获取流失败", e);
        }

        inputStream = tmpIn;
        outputStream = tmpOut;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        int bytes;

        Log.d(TAG, "数据通信线程已启动");

        while (running) {
            try {
                // 阻塞读取单片机发来的数据
                if (inputStream != null && (bytes = inputStream.read(buffer)) > 0) {
                    String data = new String(buffer, 0, bytes);
                    Log.d(TAG, "收到数据: " + data);

                    if (listener != null) {
                        listener.onDataReceived(data);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "读取失败，连接断开", e);
                running = false;
                break;
            }
        }

        Log.d(TAG, "数据通信线程已结束");
    }

    /**
     * 发送数据
     */
    public void write(byte[] data) {
        try {
            if (outputStream != null) {
                outputStream.write(data);
                outputStream.flush();
                Log.d(TAG, "发送数据: " + new String(data));
            }
        } catch (IOException e) {
            Log.e(TAG, "发送失败", e);
        }
    }

    /**
     * 发送字符串
     */
    public void write(String data) {
        write(data.getBytes());
    }

    /**
     * 停止线程
     */
    public void cancel() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            Log.e(TAG, "关闭socket失败", e);
        }
    }

    /**
     * 数据接收回调接口
     */
    public interface OnDataReceivedListener {
        void onDataReceived(String data);
    }
}
