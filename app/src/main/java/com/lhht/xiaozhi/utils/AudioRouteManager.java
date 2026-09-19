package com.lhht.xiaozhi.utils;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 通话音频路由管理器。
 *
 * <p><b>为什么需要它：</b>本应用使用 {@link AudioManager#MODE_IN_COMMUNICATION} +
 * {@code USAGE_VOICE_COMMUNICATION}，属于"通话"场景。而蓝牙耳机在 Android 上
 * 分成两条独立的通道：
 * <ul>
 *   <li><b>A2DP</b>：媒体播放通道，单向、高音质，听歌走这里</li>
 *   <li><b>SCO</b>：双向通话通道，音质较低，打电话走这里</li>
 * </ul>
 * 通话场景必须把音频切到 SCO，否则系统找不到通话出口，会回落到本机扬声器 ——
 * 也就是"蓝牙显示已连接，但声音还是从扬声器出来"这个现象。
 *
 * <p><b>版本差异：</b>
 * <ul>
 *   <li>Android 12 (API 31) 起推荐用 {@code setCommunicationDevice()}，
 *       由系统自动选择最佳通信设备</li>
 *   <li>Android 10/11 需要用已废弃的 {@code startBluetoothSco()} /
 *       {@code setBluetoothScoOn(true)}，本类走这条兼容路径</li>
 * </ul>
 *
 * <p><b>用法：</b>
 * <pre>
 *   audioRouteManager = new AudioRouteManager(this);
 *   audioRouteManager.startForVoiceCall();   // 通话开始时
 *   audioRouteManager.setSpeakerOn(true);    // 用户点扬声器按钮
 *   audioRouteManager.stop();                // 挂断 / onDestroy
 * </pre>
 */
public class AudioRouteManager {

    private static final String TAG = "AudioRouteManager";

    /** SCO 异常断开后的最大重试次数，防止无限重连 */
    private static final int MAX_SCO_RETRY = 3;

    private final Context appContext;
    private final AudioManager audioManager;

    private boolean started = false;
    private boolean receiverRegistered = false;
    private int scoRetryCount = 0;

    /**
     * 是否希望把通话音频送到蓝牙耳机的 SCO 通道。
     * 与"扬声器是否开启"是两回事：SCO 生效时由系统决定出口。
     */
    private boolean wantSco = false;

    public AudioRouteManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    // ==================================================================
    //  对外接口
    // ==================================================================

    /**
     * 通话开始时调用：切到通信模式，并在检测到蓝牙耳机时启用 SCO。
     */
    public void startForVoiceCall() {
        if (audioManager == null || started) {
            return;
        }
        started = true;

        // 进入通信模式
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        // 先显式关闭扬声器。不调用这一句时，路由完全交给系统默认策略，
        // 各家 ROM 的默认值并不一致，这是"没插耳机却外放"的常见原因。
        audioManager.setSpeakerphoneOn(false);

        registerRouteReceiver();

        if (isBluetoothHeadsetConnected()) {
            startSco();
        } else {
            Log.d(TAG, "未检测到蓝牙耳机，使用本机听筒/扬声器");
        }
    }

    /**
     * 挂断或销毁时调用：关闭 SCO、注销广播、恢复正常音频模式。
     */
    public void stop() {
        if (audioManager == null || !started) {
            return;
        }
        started = false;

        stopSco();
        unregisterRouteReceiver();

        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            Log.e(TAG, "恢复音频模式失败", e);
        }
    }

    /**
     * 用户切换扬声器。
     *
     * <p>蓝牙 SCO 生效时直接忽略 —— 此时音频出口由 SCO 决定，
     * 再调 setSpeakerphoneOn(true) 会把声音从耳机抢回扬声器。
     */
    public void setSpeakerOn(boolean on) {
        if (audioManager == null) {
            return;
        }
        if (wantSco && isBluetoothHeadsetConnected()) {
            Log.d(TAG, "蓝牙 SCO 生效中，忽略扬声器切换");
            return;
        }
        audioManager.setSpeakerphoneOn(on);
    }

    /**
     * 当前是否有蓝牙耳机（HFP 免提/耳机 profile）处于已连接状态。
     */
    public boolean isBluetoothHeadsetConnected() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return false;
        }
        // Android 12 起读取蓝牙连接状态需要 BLUETOOTH_CONNECT 运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限，无法判断蓝牙耳机状态");
            return false;
        }
        try {
            return adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                    == BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException e) {
            Log.w(TAG, "查询蓝牙耳机状态失败", e);
            return false;
        }
    }

    // ==================================================================
    //  SCO 开关
    // ==================================================================

    /**
     * 请求接通 SCO 通道。
     *
     * <p>SCO 的连接是异步的，调用后需等待
     * {@link AudioManager#ACTION_SCO_AUDIO_STATE_UPDATED} 广播返回
     * {@link AudioManager#SCO_AUDIO_STATE_CONNECTED}，音频才真正切到耳机。
     */
    @SuppressWarnings("deprecation") // Android 12 起建议用 setCommunicationDevice()，此处兼容 10/11
    private void startSco() {
        if (audioManager == null || !started) {
            return;
        }
        wantSco = true;
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setBluetoothScoOn(true);
            audioManager.startBluetoothSco();
            Log.i(TAG, "已请求蓝牙 SCO 通道，等待连接回调...");
        } catch (Exception e) {
            wantSco = false;
            Log.e(TAG, "启动蓝牙 SCO 失败", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopSco() {
        wantSco = false;
        scoRetryCount = 0;
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            Log.d(TAG, "已关闭蓝牙 SCO 通道");
        } catch (Exception e) {
            Log.e(TAG, "停止蓝牙 SCO 失败", e);
        }
    }

    // ==================================================================
    //  广播监听：SCO 状态变化 + 蓝牙耳机插拔
    // ==================================================================

    private final BroadcastReceiver routeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(action)) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR);
                handleScoStateChanged(state);

            } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                Log.d(TAG, "蓝牙耳机连接状态变化: " + state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    // 通话中途戴上耳机，立即把音频接过去
                    startSco();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    // 通话中途摘下耳机，交还给本机出口
                    stopSco();
                }
            }
        }
    };

    private void handleScoStateChanged(int state) {
        switch (state) {
            case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                scoRetryCount = 0;
                Log.i(TAG, "蓝牙 SCO 已连接，通话音频走耳机");
                break;

            case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                if (!wantSco) {
                    // 主动关闭，正常流程
                    Log.d(TAG, "蓝牙 SCO 已断开（主动关闭）");
                    break;
                }
                // 非主动断开：可能是对端异常，重试若干次
                if (scoRetryCount < MAX_SCO_RETRY) {
                    scoRetryCount++;
                    Log.w(TAG, "蓝牙 SCO 意外断开，第 " + scoRetryCount + " 次重试");
                    startSco();
                } else {
                    Log.e(TAG, "蓝牙 SCO 重试 " + MAX_SCO_RETRY + " 次仍失败，放弃");
                    wantSco = false;
                }
                break;

            case AudioManager.SCO_AUDIO_STATE_ERROR:
                Log.e(TAG, "蓝牙 SCO 状态错误");
                break;

            default:
                break;
        }
    }

    private void registerRouteReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13 起注册非系统广播必须显式声明是否导出
                appContext.registerReceiver(routeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(routeReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册音频路由广播失败", e);
        }
    }

    private void unregisterRouteReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            appContext.unregisterReceiver(routeReceiver);
        } catch (Exception e) {
            Log.e(TAG, "注销音频路由广播失败", e);
        }
        receiverRegistered = false;
    }
}
