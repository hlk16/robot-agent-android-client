package com.lhht.xiaozhi.activities;
//这是波奇酱
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.activities.BtThread.ConnectedThread;
import com.lhht.xiaozhi.services.BluetoothService;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import vip.inode.demo.opusaudiodemo.utils.OpusUtils;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import android.graphics.YuvImage;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VoiceCallActivity extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private VideoView videoView;
    //音频录制参数
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    //音频播放的缓冲区大小 - 使用系统最小缓冲区以降低延迟
    private static final int PLAY_BUFFER_SIZE = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 1;
    //Opus编码器的帧大小 (优化为1440)
    private static final int OPUS_FRAME_SIZE = 1440;

    private TextView aiMessageText;
    private TextView recognizedText;
    private TextView callStatusText;
    private TextView emojiText;
    //很可能是一个自定义的类，用于表示波形视图。这个类可能继承自Android中的View类或其他视图类，用于在界面上绘制波形数据
    private WaveformView aiWaveformView;
    private WaveformView userWaveformView;
    private ImageButton muteButton;
    private ImageButton hangupButton;
    private ImageButton speakerButton;
    private ImageButton previewButton;
    private PreviewView frontCameraPreview;
    private ProcessCameraProvider cameraProvider;
    private boolean isPreviewStarted = false;
    private ExecutorService cameraExecutor;

    private boolean isMuted = false;
    private boolean isSpeakerOn = false;
    private volatile boolean isRecording = false;
    private boolean isPlaying = false;

    //用于录制音频
    private AudioRecord audioRecord;
    //用于播放音频
    private AudioTrack audioTrack;
    private ExecutorService executorService;
    private ExecutorService audioExecutor;
    private SafeHandler mainHandler;
    private WebSocketManager webSocketManager;
    private OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private short[] decodedBuffer;
    private short[] recordBuffer;
    private ConnectedThread connectedThread;
    private BluetoothService btService;
    private boolean btServiceBound = false;
    public static char order='x';
    public static double roadDistance = 0.0; // 距离右侧车道线距离，用于蓝牙发送

    // 视频帧缓存
    private FrameCache frameCache;
    private long lastFrameCaptureTime = 0;
    private static final int FRAME_CAPTURE_INTERVAL_MS = 1000; // 帧间隔每秒捕获一帧

    // MCP 视觉识别
    private String visionUrl = null;
    private String visionToken = null;
    private volatile boolean isMcpInProgress = false;
    private static final long PING_INTERVAL_MS = 5000;
    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMcpInProgress) return;
            if (webSocketManager != null && webSocketManager.isConnected()) {
                webSocketManager.sendMessage("{\"type\":\"ping\"}");
            }
            mainHandler.postDelayed(this, PING_INTERVAL_MS);
        }
    };
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    // 回声消除相关
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    
    // 音频播放队列
    private BlockingQueue<byte[]> audioQueue;
    private volatile boolean isPlaybackThreadRunning = false;
    private ExecutorService playbackExecutor;

    private long speechStartTime = 0;

    private float[] waveformBuffer = new float[100];
    private long lastWaveformUpdate = 0;
    private float[] amplitudeBuffer = new float[OPUS_FRAME_SIZE];

    // 音频焦点管理
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    private final ServiceConnection btConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            btService = binder.getService();
            btServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            btServiceBound = false;
            btService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // 设置沉浸式状态栏和导航栏
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        setContentView(R.layout.activity_voice_call);

        mainHandler = new SafeHandler(this);

        // 初始化图像识别管理器


        initViews();
        initWebSocket();
        initAudio();
        setupListeners();

        bindService(new Intent(this, BluetoothService.class), btConnection, Context.BIND_AUTO_CREATE);

        // 初始化视频播放
        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.new_action);
        videoView.setVideoURI(videoUri);
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.start();
        });
    }

    private void initViews() {
        videoView = findViewById(R.id.videoBackground);
        aiMessageText = findViewById(R.id.aiMessageText);
        recognizedText = findViewById(R.id.recognizedText);
        callStatusText = findViewById(R.id.callStatusText);
        emojiText = findViewById(R.id.emojiText);
        aiWaveformView = findViewById(R.id.aiWaveformView);
        userWaveformView = findViewById(R.id.userWaveformView);
        muteButton = findViewById(R.id.muteButton);
        hangupButton = findViewById(R.id.hangupButton);
        speakerButton = findViewById(R.id.speakerButton);
        previewButton = findViewById(R.id.previewButton);
        frontCameraPreview = findViewById(R.id.frontCameraPreview);
        frontCameraPreview.setVisibility(View.GONE);
    }
    //WebSocket连接的Java方法。它通常用于Android应用程序中，用于建立与服务器的WebSocket通信
    private void initWebSocket() {
        // 从MainActivity获取WebSocket配置
//        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceId = "c0:3e:ba:2e:d5:97";
        SettingsManager settingsManager = new SettingsManager(this);
        String wsUrl = settingsManager.getWsUrl();
        String token = settingsManager.getToken();
        boolean enableToken = settingsManager.isTokenEnabled();

        webSocketManager = WebSocketManager.getInstance(deviceId);
        webSocketManager.setListener(this);

        // 连接WebSocket
        try {
            webSocketManager.connect(wsUrl, token, enableToken);
            updateCallStatus("正在连接...");
        } catch (Exception e) {
            Log.e("VoiceCall", "WebSocket连接失败", e);
            updateCallStatus("连接失败");
            Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initAudio() {
        executorService = Executors.newSingleThreadExecutor();
        audioExecutor = Executors.newSingleThreadExecutor();
        playbackExecutor = Executors.newSingleThreadExecutor();
        cameraExecutor = Executors.newSingleThreadExecutor();

        // 设置音频会话模式为通信模式，有助于回声消除
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        
        // 初始化音频焦点监听器
        initAudioFocusListener();
        
        // 请求音频焦点
        requestAudioFocus();
        
        // 初始化音频播放队列
        audioQueue = new LinkedBlockingQueue<>();
        startPlaybackThread();

        // 初始化Opus编解码器
        opusUtils = OpusUtils.getInstance();
        encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
        decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
        decodedBuffer = new short[OPUS_FRAME_SIZE];
        recordBuffer = new short[OPUS_FRAME_SIZE];

        // 初始化音频播放器
        initAudioTrack();
    }

    private void initAudioTrack() {
        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(PLAY_BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        } catch (Exception e) {
            Log.e("VoiceCall", "创建AudioTrack失败", e);
        }
    }

    private void setupListeners() {
        muteButton.setOnClickListener(v -> toggleMute());
        hangupButton.setOnClickListener(v -> endCall());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        previewButton.setOnClickListener(v -> toggleCameraPreview());

        // 点击屏幕打断AI回答
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> interruptAiResponse());
    }

    private void toggleCameraPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            return;
        }

        if (!isPreviewStarted) {
            startCameraPreview();
        } else {
            stopCameraPreview();
        }
        isPreviewStarted = !isPreviewStarted;
        frontCameraPreview.setVisibility(isPreviewStarted ? View.VISIBLE : View.GONE);
        previewButton.setImageResource(isPreviewStarted ? R.drawable.baseline_videocam_24 : R.drawable.baseline_videocam_24);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleCameraPreview();
            } else {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCameraPreview() {
        if (cameraProvider != null) {
            bindCameraPreview();
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                if (frameCache == null) {
                    frameCache = new FrameCache();
                }
                bindCameraPreview();
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraPreview", "Error getting camera provider: " + e.getMessage());
                Toast.makeText(this, "无法启动前置摄像头", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraPreview() {
        if (cameraProvider == null) return;

        startContinuousFrameCapture();
    }

    private void stopCameraPreview() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            isPreviewStarted = false;
        }
    }

//    @Override
//    protected void onPause() {
//        super.onPause();
//        stopCameraPreview();
//        isPreviewStarted = false;
//        if (frontCameraPreview != null) {
//            frontCameraPreview.setVisibility(View.GONE);
//        }
//        if (previewButton != null) {
//            previewButton.setImageResource(R.drawable.baseline_videocam_24);
//        }
//    }

    private void startCall() {
        if (!webSocketManager.isConnected()) {
            updateCallStatus("未连接");
            return;
        }

        try {
            // 发送开始通话消息
            JSONObject startMessage = new JSONObject();
            startMessage.put("type", "hello");
            startMessage.put("mode", "auto");
            startMessage.put("audio_params", new JSONObject()
                    .put("format", "opus")
                    .put("sample_rate", SAMPLE_RATE)
                    .put("channels", 1)
                    .put("frame_duration", 60));
            startMessage.put("features", new JSONObject()
                    .put("mcp", true));
            webSocketManager.sendMessage(startMessage.toString());

            // 开始录音
            isRecording = true;
            startRecording();
            updateCallStatus("正在通话中...");
        } catch (Exception e) {
            Log.e("VoiceCall", "开始通话失败", e);
            updateCallStatus("开始通话失败");
        }
    }
    //不知道为什么报错

    @SuppressLint("MissingPermission")
    private void startRecording() {
        if (audioRecord == null) {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
            );
            
            // 启用回声消除器
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                    Log.d("AudioDebug", "回声消除器已启用");
                }
            }
            
            // 启用噪声抑制器
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    Log.d("AudioDebug", "噪声抑制器已启用");
                }
            }
        }

        // 检查线程池状态
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            Log.w("VoiceCallActivity", "ExecutorService已关闭，无法启动录音");
            return;
        }
        
        executorService.execute(() -> {
            try {
                audioRecord.startRecording();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, BUFFER_SIZE);
                    if (read > 0 && !isMuted) {
                        // 发送音频数据
                        sendAudioData(buffer, read);
                        // 更新波形图
                        updateUserWaveform(buffer);
                    }
                }
            } catch (Exception e) {
                Log.e("VoiceCall", "录音失败", e);
            }
        });
    }
    //发音频
    private void sendAudioData(byte[] data, int size) {
        if (opusUtils == null || encoderHandle == 0) {
            Log.w("VoiceCall", "Opus编码器未初始化，跳过发送音频");
            return;
        }

        if (webSocketManager != null && webSocketManager.isConnected()) {
            try {
                // 将byte[]转换为short[]
                short[] samples = new short[size / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) ((data[i * 2] & 0xFF) | (data[i * 2 + 1] << 8));
                }

                // 编码音频数据
                byte[] encodedData = new byte[size];
                int encodedSize = opusUtils.encode(encoderHandle, samples, 0, encodedData);
                if (encodedSize > 0) {
                    // 直接发送编码后的音频数据
                    byte[] encodedBytes = new byte[encodedSize];
                    System.arraycopy(encodedData, 0, encodedBytes, 0, encodedSize);
                    webSocketManager.sendBinaryMessage(encodedBytes);
                }
            } catch (Exception e) {
                Log.e("VoiceCall", "发送音频数据失败", e);
            }
        }
    }
    //静音
    private void toggleMute() {
        isMuted = !isMuted;
        muteButton.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        updateCallStatus(isMuted ? "已静音" : "正在通话中...");
    }
    //有无声音
    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        speakerButton.setImageResource(isSpeakerOn ? R.drawable.ic_volume_up : R.drawable.ic_volume_off);

        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(isSpeakerOn);
        
        // 根据扬声器状态调整音频模式，优化回声抑制
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    }
    //挂断
    private void endCall() {
        try {
            isRecording = false;
            stopPlaybackThread();
            stopRecording();
            // 安全释放AudioTrack
            if (audioTrack != null) {
                try {
                    if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                        audioTrack.stop();
                    }
                    audioTrack.release();
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放AudioTrack失败", e);
                } finally {
                    audioTrack = null;
                }
            }
            
            // 释放摄像头资源
            stopCameraPreview();
            
            // 发送结束消息并断开WebSocket连接
            if (webSocketManager != null) {
                try {
                    if (webSocketManager.isConnected()) {
                        JSONObject endMessage = new JSONObject();
                        endMessage.put("type", "end");
                        webSocketManager.sendMessage(endMessage.toString());
                    }
                    webSocketManager.disconnect();
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "关闭WebSocket连接失败", e);
                }
            }
            
            // 恢复默认音频模式
            try {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception e) {
                Log.e("VoiceCallActivity", "恢复音频模式失败", e);
            }
            
            // 释放音频焦点
            abandonAudioFocus();
            
            // 关闭线程池
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
            }
            if (audioExecutor != null && !audioExecutor.isShutdown()) {
                audioExecutor.shutdown();
            }
            if (playbackExecutor != null && !playbackExecutor.isShutdown()) {
                playbackExecutor.shutdown();
            }
            if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
                cameraExecutor.shutdown();
            }
            
            // 释放Opus编解码器
            if (encoderHandle != 0) {
                try {
                    opusUtils.destroyEncoder(encoderHandle);
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放Opus编码器失败", e);
                } finally {
                    encoderHandle = 0;
                }
            }
            if (decoderHandle != 0) {
                try {
                    opusUtils.destroyDecoder(decoderHandle);
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放Opus解码器失败", e);
                } finally {
                    decoderHandle = 0;
                }
            }
            
        } catch (Exception e) {
            Log.e("VoiceCallActivity", "endCall执行失败", e);
        } finally {
            // 确保Activity能够正常结束
            finish();
        }
    }
    //打断
    private void interruptAiResponse() {
        if (webSocketManager != null && webSocketManager.isConnected()) {
            try {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("type", "interrupt");
                webSocketManager.sendMessage(jsonMessage.toString());
                updateCallStatus("已打断AI回答");
            } catch (Exception e) {
                Log.e("VoiceCall", "发送中断消息失败", e);
            }
        }
    }
    //更新通话状态的显示
    public void updateCallStatus(String status) {
        mainHandler.post(() -> {
            if (callStatusText != null) {
                callStatusText.setText(status);
            }
        });
    }
    //更新文字
    public void updateAiMessage(String message) {
        mainHandler.post(() -> {
            if (aiMessageText != null) {
                aiMessageText.setText(message);
            }
        });
    }
    //更新人文字
    public void updateRecognizedText(String text) {
        mainHandler.post(() -> {
            // 过滤掉图像识别信息，只显示用户真正说的话
            if (text != null && text.contains("[视觉]:")) {
                // 这是图像识别信息，不显示给用户
                Log.d("VoiceCall", "过滤图像识别信息: " + text);
                return;
            }
            
            if (recognizedText != null) {
                recognizedText.setText(text);
            }

            // 检测语音指令并处理图像识别
            if (text != null && text.contains("看到了什么")) {
                Toast.makeText(VoiceCallActivity.this, "图像识别功能请使用语音通话页面", Toast.LENGTH_SHORT).show();
            }
            else if(text != null && text.contains("向前走") ) {
                order='a';
                Toast.makeText(this, "发送前进", Toast.LENGTH_SHORT).show();

            }
            else if(text != null && text.contains("向后走") ) {
                order='b';
                Toast.makeText(this, "发送后退", Toast.LENGTH_SHORT).show();

            }
            else if(text != null && text.contains("向左转") ) {
                order='c';
                Toast.makeText(this, "发送左转", Toast.LENGTH_SHORT).show();

            }
            else if(text != null && text.contains("向右转") ) {
                order='d';
                Toast.makeText(this, "发送右转", Toast.LENGTH_SHORT).show();
            }
            else if(text != null && text.contains("停下来") ) {
                order='e';
                Toast.makeText(this, "发送停止", Toast.LENGTH_SHORT).show();
            }
             else if (text != null && text.contains("帮我取快递") )  {
                Toast.makeText(VoiceCallActivity.this, "正在打开远程导航页面", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(VoiceCallActivity.this, ChatActivity.class);
                startActivity(intent);
            }
            // // 检测关键词"哈喽"
            // else if (text.contains("你好")) {
            //     Toast.makeText(this, "哈喽哈喽", Toast.LENGTH_SHORT).show();
            //     order = 'f';
            // }
            // else if (text.contains("展示抱拳")) {
            //     Toast.makeText(this, "展示拳法", Toast.LENGTH_SHORT).show();
            //     order = 'g';
            // }
            // // 检测关键词"展示功夫"
            // else if (text.contains("展示功夫")) {
            //     Log.d("VoiceCall", "检测到展示功夫指令: " + text);
            //     Toast.makeText(this, "展示功夫", Toast.LENGTH_SHORT).show();
            //     order = 'i';
            // }


        });
    }

    private void updateUserWaveform(byte[] buffer) {
        if (userWaveformView == null) return;

        long now = System.currentTimeMillis();
        if (now - lastWaveformUpdate < 50) return;
        lastWaveformUpdate = now;

        int step = Math.max(1, (buffer.length / 2) / 100);
        for (int i = 0; i < 100; i++) {
            int idx = i * step * 2;
            if (idx + 1 < buffer.length) {
                short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
                waveformBuffer[i] = sample / 32768f;
            }
        }
        mainHandler.post(() -> userWaveformView.setAmplitudes(waveformBuffer));
    }

    //更新AI声音波形
    public void updateAiWaveform(float[] amplitudes) {
        mainHandler.post(() -> {
            if (aiWaveformView != null) {
                aiWaveformView.setAmplitudes(amplitudes);
            }
        });
    }

    @Override
    public void onConnected() {
        updateCallStatus("已连接");
        startCall();
    }

    @Override
    public void onDisconnected() {
        Log.e("VoiceCall-Connection", "WebSocket连接断开, isMcpInProgress=" + isMcpInProgress);
        if (isMcpInProgress) {
            Log.w("VoiceCall-Connection", "MCP正在进行中, 不关闭Activity, 等待重连...");
            updateCallStatus("连接断开(等待恢复)...");
            return;
        }
        updateCallStatus("连接已断开");
        endCall();
    }

    @Override
    public void onError(String error) {
        Log.e("VoiceCall-Connection", "WebSocket错误: " + error + ", isMcpInProgress=" + isMcpInProgress);
        if (isMcpInProgress) {
            Log.w("VoiceCall-Connection", "MCP进行中, 忽略WebSocket错误, 等待重连...");
            return;
        }
        updateCallStatus("错误: " + error);
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");

            switch (type) {
                case "stt":
                    String recognizedText = jsonMessage.getString("text");
                    if (recognizedText != null && !recognizedText.trim().isEmpty() && speechStartTime == 0) {
                        speechStartTime = System.currentTimeMillis();
                    }
                    updateRecognizedText(recognizedText);
                    stopCurrentAudio();
                    break;

                case "tts":
                    handleTTSMessage(jsonMessage);
                    break;

                case "mcp":
                    handleMcpMessage(jsonMessage);
                    break;

                case "action":
                    handleActionMessage(jsonMessage);
                    break;
            }
        } catch (Exception e) {
            Log.e("VoiceCall", "处理消息失败", e);
        }
    }

    private void stopCurrentAudio() {
        // 检查线程池状态
        if (audioExecutor == null || audioExecutor.isShutdown() || audioExecutor.isTerminated()) {
            Log.w("VoiceCallActivity", "AudioExecutor已关闭，无法停止音频");
            return;
        }
        
        audioExecutor.execute(() -> {
            try {
                if (audioTrack != null && isPlaying) {
                    audioTrack.pause();
                    audioTrack.flush();
                    isPlaying = false;
                    // 清空波形显示
                    updateAiWaveform(new float[0]);
                }
            } catch (Exception e) {
                Log.e("VoiceCall", "停止音频播放失败", e);
            }
        });
    }

    private void handleTTSMessage(JSONObject message) {
        try {
            String state = message.getString("state");
            switch (state) {
                case "start":
                    stopCurrentAudio();
                    updateCallStatus("AI正在说话...");
                    break;

                case "sentence_start":
                    String text = message.getString("text");
                    String[] parts = extractEmojiAndText(text);
                    String emoji = parts[0];
                    String cleanText = parts[1];

                    if (speechStartTime > 0) {
                        long totalResponseTime = System.currentTimeMillis() - speechStartTime;
                        Log.d("VoiceCall-ResponseTime", "用户说话到AI回复的完整时间: " + totalResponseTime + "ms (回复: '" + cleanText + "')");
                        speechStartTime = 0;
                    }

                    updateAiMessage(cleanText);

                    // 检测关键词"哈喽"
                    if (text.contains("你好")) {
                        Toast.makeText(this, "哈喽哈喽", Toast.LENGTH_SHORT).show();
                        order = 'f';
                    }
                    else if (text.matches(".*展示.*抱拳.*")) {
                        Toast.makeText(this, "展示拳法", Toast.LENGTH_SHORT).show();
                        order = 'g';
                    }
                    // 检测关键词"展示功夫"
                    else if (text.matches(".*展示.*功夫.*")) {
                        Log.d("VoiceCall", "检测到展示功夫指令: " + text);
                        Toast.makeText(this, "展示功夫", Toast.LENGTH_SHORT).show();
                        order = 'i';
                    }
                    // 检测关键词"给你鼓掌"
                    else if (text.matches(".*给.*鼓掌.*")) {
                        Toast.makeText(this, "给你鼓掌", Toast.LENGTH_SHORT).show();
                        order = 'h';
                    }

                    if (!emoji.isEmpty()) {
                        showEmoji(emoji);
                    } else {
                        hideEmoji();
                    }
                    updateCallStatus("AI正在说话...");
                    break;

                case "end":
                    updateCallStatus("正在通话中...");
                    hideEmoji();
                    break;

                case "error":
                    String error = message.optString("error", "未知错误");
                    updateCallStatus("TTS错误: " + error);
                    hideEmoji();
                    break;
            }
        } catch (Exception e) {
            Log.e("VoiceCall", "处理TTS消息失败", e);
        }
    }

    private void handleActionMessage(JSONObject message) {
        try {
            String action = message.getString("action");
            Log.d("VoiceCall-Action", "收到动作指令: " + action);

            if (btServiceBound && btService != null) {
                switch (action) {
                    case "NOACTION":
                        btService.sendChar('n');
                        break;
                    case "WAVE":
                        btService.sendChar('f');
                        break;
                }
            } else {
                Log.w("VoiceCall-Action", "蓝牙服务未绑定，无法发送动作指令");
            }
        } catch (Exception e) {
            Log.e("VoiceCall-Action", "处理动作消息失败", e);
        }
    }

    private String[] extractEmojiAndText(String text) {
        StringBuilder emoji = new StringBuilder();
        StringBuilder cleanText = new StringBuilder();

        int length = text.length();
        for (int i = 0; i < length; ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            if ((codePoint >= 0x1F300 && codePoint <= 0x1F9FF) ||
                    (codePoint >= 0x2600 && codePoint <= 0x26FF) ||
                    (codePoint >= 0x2700 && codePoint <= 0x27BF) ||
                    (codePoint >= 0xFE00 && codePoint <= 0xFE0F) ||
                    (codePoint >= 0x1F900 && codePoint <= 0x1F9FF)) {
                emoji.append(new String(Character.toChars(codePoint)));
            } else {
                cleanText.append(new String(Character.toChars(codePoint)));
            }
            i += charCount;
        }

        return new String[]{emoji.toString(), cleanText.toString().trim()};
    }

    private void showEmoji(String emoji) {
        mainHandler.post(() -> {
            if (emojiText != null) {
                emojiText.setText(emoji);
                emojiText.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideEmoji() {
        mainHandler.post(() -> {
            if (emojiText != null) {
                emojiText.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onBinaryMessage(byte[] data) {
        if (data == null || data.length == 0) return;

        if (audioExecutor == null || audioExecutor.isShutdown() || audioExecutor.isTerminated()) {
            Log.w("VoiceCall", "AudioExecutor已关闭，忽略音频数据");
            return;
        }

        audioExecutor.execute(() -> {
            try {
                if (opusUtils == null || decoderHandle == 0) {
                    Log.w("VoiceCall", "Opus解码器未初始化，忽略音频数据");
                    return;
                }

                int decodedSamples = opusUtils.decode(decoderHandle, data, decodedBuffer);

                if (decodedSamples > 0) {
                    byte[] pcmData = new byte[decodedSamples * 2];
                    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(decodedBuffer, 0, decodedSamples);

                    if (audioQueue != null) {
                        boolean offered = audioQueue.offer(pcmData);
                        if (!offered) {
                            Log.w("AudioDebug", "音频队列已满，丢弃数据");
                        }
                    }

                    float[] amplitudes = amplitudeBuffer.length >= decodedSamples
                        ? amplitudeBuffer
                        : new float[decodedSamples];
                    for (int i = 0; i < decodedSamples; i++) {
                        amplitudes[i] = decodedBuffer[i] / 32768f;
                    }
                    updateAiWaveform(amplitudes);
                }
            } catch (Exception e) {
                Log.e("VoiceCall", "处理音频数据失败", e);
            }
        });
    }

    // 启动播放线程
    private void startPlaybackThread() {
        if (isPlaybackThreadRunning) {
            return;
        }

        if (playbackExecutor == null || playbackExecutor.isShutdown() || playbackExecutor.isTerminated()) {
            Log.w("VoiceCall", "PlaybackExecutor已关闭，无法启动播放线程");
            return;
        }

        isPlaybackThreadRunning = true;
        playbackExecutor.execute(() -> {
            Log.d("PlaybackThread", "播放线程启动");

            while (isPlaybackThreadRunning) {
                try {
                    byte[] audioData = audioQueue.poll(10, TimeUnit.MILLISECONDS);
                    
                    if (audioData != null) {
                        Log.d("PlaybackThread", "从队列取出音频数据: " + audioData.length + " bytes");
                        
                        // 确保AudioTrack已初始化
                        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                            initAudioTrack();
                            Log.d("PlaybackThread", "重新初始化AudioTrack");
                        }
                        
                        // 开始播放
                        if (!isPlaying && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                            audioTrack.play();
                            isPlaying = true;
                            Log.d("PlaybackThread", "开始播放");
                        }
                        
                        // 写入音频数据
                        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                            int bytesWritten = audioTrack.write(audioData, 0, audioData.length, AudioTrack.WRITE_BLOCKING);
                            Log.d("PlaybackThread", "写入AudioTrack字节数: " + bytesWritten);
                        }
                    }
                } catch (InterruptedException e) {
                    Log.d("PlaybackThread", "播放线程被中断");
                    break;
                } catch (Exception e) {
                    Log.e("PlaybackThread", "播放线程异常", e);
                }
            }
            
            Log.d("PlaybackThread", "播放线程结束");
        });
    }
    
    // 停止播放线程
    private void stopPlaybackThread() {
        isPlaybackThreadRunning = false;
        if (audioQueue != null) {
            audioQueue.clear();
        }
        if (isPlaying && audioTrack != null) {
            audioTrack.stop();
            isPlaying = false;
        }
    }
    
    // 初始化音频焦点监听器
    private void initAudioFocusListener() {
        audioFocusChangeListener = new AudioFocusChangeListener(this);
    }

    private static class AudioFocusChangeListener implements AudioManager.OnAudioFocusChangeListener {
        private final WeakReference<VoiceCallActivity> activityRef;

        public AudioFocusChangeListener(VoiceCallActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @Override
        public void onAudioFocusChange(int focusChange) {
            VoiceCallActivity activity = activityRef.get();
            if (activity == null) {
                return;
            }

            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (activity.audioTrack != null && !activity.isPlaying) {
                        activity.audioTrack.play();
                        activity.isPlaying = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (activity.audioTrack != null && activity.isPlaying) {
                        activity.audioTrack.pause();
                        activity.isPlaying = false;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    break;
            }
        }
    }
    
    // 请求音频焦点
    private void requestAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN
        );
        
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d("AudioFocus", "音频焦点请求成功");
        } else {
            Log.w("AudioFocus", "音频焦点请求失败");
        }
    }
    
    // 释放音频焦点
    private void abandonAudioFocus() {
        if (audioFocusChangeListener != null) {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            audioManager.abandonAudioFocus(audioFocusChangeListener);
            Log.d("AudioFocus", "释放音频焦点");
        }
    }

    @Override
    protected void onDestroy() {
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();

        if (btServiceBound) {
            unbindService(btConnection);
            btServiceBound = false;
        }
        if (webSocketManager != null) {
            try {
                if (webSocketManager.isConnected()) {
                    JSONObject endMessage = new JSONObject();
                    endMessage.put("type", "end");
                    webSocketManager.sendMessage(endMessage.toString());
                    Thread.sleep(100);
                }
                webSocketManager.disconnect();
            } catch (Exception e) {
                Log.e("VoiceCall", "断开WebSocket失败", e);
            }
            webSocketManager.removeListener();
        }

        isRecording = false;
        isPlaybackThreadRunning = false;

        stopRecording();
        endCall();

        if (encoderHandle != 0) {
            opusUtils.destroyEncoder(encoderHandle);
            encoderHandle = 0;
        }
        if (decoderHandle != 0) {
            opusUtils.destroyDecoder(decoderHandle);
            decoderHandle = 0;
        }

        abandonAudioFocus();
        audioFocusChangeListener = null;

        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            Log.e("VoiceCall", "恢复音频模式失败", e);
        }

        shutdownExecutor(executorService);
        shutdownExecutor(audioExecutor);
        shutdownExecutor(playbackExecutor);
        shutdownExecutor(cameraExecutor);

        if (mainHandler != null) {
            mainHandler.removeCallbacks(pingRunnable);
        }
        isMcpInProgress = false;
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private void stopRecording() {
        if (echoCanceler != null) {
            echoCanceler.setEnabled(false);
            echoCanceler.release();
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.setEnabled(false);
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e("VoiceCall", "释放AudioRecord失败", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
        stopCameraPreview();
        isPreviewStarted = false;
        if (frontCameraPreview != null) {
            frontCameraPreview.setVisibility(View.GONE);
        }
        if (previewButton != null) {
            previewButton.setImageResource(R.drawable.baseline_videocam_24);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null && !videoView.isPlaying()) {
            videoView.start();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (videoView != null) {
            outState.putInt("VIDEO_POSITION", videoView.getCurrentPosition());
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (videoView != null) {
            videoView.seekTo(savedInstanceState.getInt("VIDEO_POSITION", 0));
        }
    }

    private void handleMcpMessage(JSONObject message) {
        try {
            Log.d("VoiceCall-MCP", "收到MCP消息: " + message.toString());
            JSONObject payload = message.getJSONObject("payload");
            String method = payload.optString("method", "");
            int mcpId = payload.optInt("id", 0);

            if ("initialize".equals(method)) {
                JSONObject capabilities = payload.optJSONObject("params")
                        .optJSONObject("capabilities");
                if (capabilities != null) {
                    JSONObject vision = capabilities.optJSONObject("vision");
                    if (vision != null) {
                        visionUrl = vision.optString("url", null);
                        visionToken = vision.optString("token", null);
                        Log.d("VoiceCall-MCP", "视觉URL: " + visionUrl);
                        Log.d("VoiceCall-MCP", "视觉Token: " + visionToken);
                    }
                }
                JSONObject initResult = new JSONObject();
                initResult.put("protocolVersion", "2024-11-05");
                initResult.put("capabilities", new JSONObject());
                JSONObject serverInfo = new JSONObject();
                serverInfo.put("name", "AndroidClient");
                serverInfo.put("version", "1.0.0");
                initResult.put("serverInfo", serverInfo);
                sendMcpJsonResult(mcpId, initResult);
            } else if ("tools/list".equals(method)) {
                JSONObject toolsResult = new JSONObject();
                toolsResult.put("tools", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "capture_photo")
                                .put("description", "拍摄照片进行视觉分析。当用户询问视觉问题（如\"看到了什么\"/\"描述一下\"/\"这是什么\"）时调用此工具")
                                .put("inputSchema", new JSONObject()
                                        .put("type", "object")
                                        .put("properties", new JSONObject()
                                                .put("question", new JSONObject()
                                                        .put("type", "string")
                                                        .put("description", "需要询问视觉大模型的问题")))
                                        .put("required", new JSONArray().put("question")))));
                Log.d("VoiceCall-MCP", "回复MCP工具列表: capture_photo");
                sendMcpJsonResult(mcpId, toolsResult);
            } else if ("tools/call".equals(method)) {
                JSONObject params = payload.optJSONObject("params");
                String question = params.optJSONObject("arguments").optString("question", "描述画面中有什么");
                sendVisionRequest(question, mcpId);
            }
        } catch (Exception e) {
            Log.e("VoiceCall-MCP", "处理MCP消息失败", e);
        }
    }

    private void startContinuousFrameCapture() {
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFrameCaptureTime < FRAME_CAPTURE_INTERVAL_MS) {
                imageProxy.close();
                return;
            }

            lastFrameCaptureTime = currentTime;
            byte[] yuvData = imageProxyToYuv420(imageProxy);
            if (yuvData != null) {
                int width = imageProxy.getWidth();
                int height = imageProxy.getHeight();
                frameCache.addFrame(yuvData, width, height);
            }

            imageProxy.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        cameraProvider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(frontCameraPreview.getSurfaceProvider());

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            isPreviewStarted = true;
        } catch (Exception e) {
            Log.e("CameraPreview", "绑定相机失败: " + e.getMessage());
            Toast.makeText(this, "无法启动前置摄像头", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] imageProxyToYuv420(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        if (planes.length < 3) return null;

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] yuv = new byte[ySize + uSize + vSize];

        yBuffer.get(yuv, 0, ySize);
        vBuffer.get(yuv, ySize, vSize);
        uBuffer.get(yuv, ySize + vSize, uSize);

        return yuv;
    }

    private byte[] yuvToJpeg(byte[] yuvData, int width, int height) {
        try {
            YuvImage yuvImage = new YuvImage(yuvData, android.graphics.ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 80, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e("VoiceCall", "YUV转JPEG失败", e);
            return null;
        }
    }

    private void sendVisionRequest(String question, int mcpId) {
        Log.d("VoiceCall-MCP", "sendVisionRequest开始, mcpId=" + mcpId + ", frameCache=" + (frameCache != null));
        if (visionUrl == null || visionToken == null) {
            Log.w("VoiceCall-MCP", "视觉URL或Token未初始化");
            sendMcpError(mcpId, "视觉服务未初始化");
            return;
        }
        if (frameCache == null) {
            Log.w("VoiceCall-MCP", "摄像头帧缓存未初始化");
            sendMcpError(mcpId, "摄像头未启动");
            return;
        }
        FrameCache.FrameData latestFrame = frameCache.getLatestFrame();
        if (latestFrame == null) {
            Log.w("VoiceCall-MCP", "暂无摄像头画面");
            sendMcpError(mcpId, "暂无摄像头画面");
            return;
        }
        Log.d("VoiceCall-MCP", "获取到帧, size=" + latestFrame.yuvData.length + ", age=" + (System.currentTimeMillis() - latestFrame.timestamp) + "ms");

        isMcpInProgress = true;
        mainHandler.post(pingRunnable);
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                byte[] jpegData = yuvToJpeg(latestFrame.yuvData, latestFrame.width, latestFrame.height);
                Log.d("VoiceCall-MCP", "JPEG编码完成, size=" + (jpegData != null ? jpegData.length : 0) + ", 耗时=" + (System.currentTimeMillis() - t0) + "ms");
                if (jpegData == null) {
                    sendMcpError(mcpId, "图像编码失败");
                    return;
                }

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("question", question)
                        .addFormDataPart("image", "frame.jpeg",
                                RequestBody.create(jpegData, MediaType.parse("image/jpeg")))
                        .build();

                Request request = new Request.Builder()
                        .url(visionUrl)
                        .header("Authorization", "Bearer " + visionToken)
                        .header("Device-Id", "c0:3e:ba:2e:d5:97")
                        .header("Client-Id", "android_client")
                        .post(requestBody)
                        .build();

                long t1 = System.currentTimeMillis();
                Log.d("VoiceCall-MCP", "HTTP POST开始: " + visionUrl);
                try (Response response = httpClient.newCall(request).execute()) {
                    long t2 = System.currentTimeMillis();
                    String body = response.body() != null ? response.body().string() : "";
                    Log.d("VoiceCall-MCP", "视觉响应, 耗时=" + (t2 - t1) + "ms, code=" + response.code() + ", body=" + body);
                    if (response.isSuccessful()) {
                        JSONObject result = new JSONObject(body);
                        String visionText = result.optString("response", result.toString());
                        sendMcpResult(mcpId, visionText);
                    } else {
                        sendMcpError(mcpId, "视觉服务返回错误: " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e("VoiceCall-MCP", "视觉请求失败, 耗时=" + (System.currentTimeMillis() - t0) + "ms", e);
                sendMcpError(mcpId, "视觉请求异常: " + e.getMessage());
            } finally {
                isMcpInProgress = false;
                mainHandler.removeCallbacks(pingRunnable);
                Log.d("VoiceCall-MCP", "sendVisionRequest结束, 总耗时=" + (System.currentTimeMillis() - t0) + "ms");
            }
        }).start();
    }

    private void sendMcpResult(int mcpId, String text) {
        if (webSocketManager == null || !webSocketManager.isConnected()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("id", mcpId);
            JSONObject result = new JSONObject();
            result.put("content", new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", text)));
            payload.put("result", result);

            JSONObject mcpMessage = new JSONObject();
            mcpMessage.put("type", "mcp");
            mcpMessage.put("payload", payload);
            Log.d("VoiceCall-MCP", "发送MCP结果 id=" + mcpId + ": " + text);
            webSocketManager.sendMessage(mcpMessage.toString());
        } catch (Exception e) {
            Log.e("VoiceCall-MCP", "发送MCP结果失败", e);
        }
    }

    private void sendMcpJsonResult(int mcpId, JSONObject result) {
        if (webSocketManager == null || !webSocketManager.isConnected()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("id", mcpId);
            payload.put("result", result);

            JSONObject mcpMessage = new JSONObject();
            mcpMessage.put("type", "mcp");
            mcpMessage.put("payload", payload);
            webSocketManager.sendMessage(mcpMessage.toString());
        } catch (Exception e) {
            Log.e("VoiceCall-MCP", "发送MCP JSON结果失败", e);
        }
    }

    private void sendMcpError(int mcpId, String errorMsg) {
        if (webSocketManager == null || !webSocketManager.isConnected()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("id", mcpId);
            JSONObject error = new JSONObject();
            error.put("code", -1);
            error.put("message", errorMsg);
            payload.put("error", error);

            JSONObject mcpMessage = new JSONObject();
            mcpMessage.put("type", "mcp");
            mcpMessage.put("payload", payload);
            webSocketManager.sendMessage(mcpMessage.toString());
        } catch (Exception e) {
            Log.e("VoiceCall-MCP", "发送MCP错误失败", e);
        }
    }

    private static class FrameCache {
        private static final int CACHE_SIZE = 5;
        private final FrameData[] frames;
        private int writeIndex = 0;
        private int count = 0;

        private static class FrameData {
            byte[] yuvData;
            int width;
            int height;
            long timestamp;

            FrameData(byte[] yuvData, int width, int height, long timestamp) {
                this.yuvData = yuvData;
                this.width = width;
                this.height = height;
                this.timestamp = timestamp;
            }
        }

        FrameCache() {
            frames = new FrameData[CACHE_SIZE];
        }

        synchronized void addFrame(byte[] yuvData, int width, int height) {
            long timestamp = System.currentTimeMillis();

            if (frames[writeIndex] == null) {
                frames[writeIndex] = new FrameData(yuvData, width, height, timestamp);
            } else {
                frames[writeIndex].yuvData = yuvData;
                frames[writeIndex].width = width;
                frames[writeIndex].height = height;
                frames[writeIndex].timestamp = timestamp;
            }

            writeIndex = (writeIndex + 1) % CACHE_SIZE;
            count = Math.min(count + 1, CACHE_SIZE);
        }

        synchronized FrameData getLatestFrame() {
            if (count == 0) return null;
            int latestIndex = (writeIndex - 1 + CACHE_SIZE) % CACHE_SIZE;
            return frames[latestIndex];
        }

        synchronized void clear() {
            for (int i = 0; i < CACHE_SIZE; i++) {
                frames[i] = null;
            }
            writeIndex = 0;
            count = 0;
        }
    }

    private static class SafeHandler extends Handler {
        private final WeakReference<VoiceCallActivity> activityRef;

        SafeHandler(VoiceCallActivity activity) {
            super(Looper.getMainLooper());
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            VoiceCallActivity activity = activityRef.get();
            if (activity == null) return;
            super.handleMessage(msg);
        }
    }
}