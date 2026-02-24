/*┌─────────────────────────────────────────────────────────────────────────────┐
        │  服务器 → WebSocket → 解码 → 格式转换 → 队列缓冲 → AudioTrack播放          │
        └─────────────────────────────────────────────────────────────────────────────┘*/
/*
* 步骤	函数	处理内容
① 接收	onBinaryMessaopusUtils.decodege(byte[] data) (1062行)	WebSocket 接收 Opus 编码的二进制音频数据
② 线程分发	audioExecutor.execute() (1078行)	将解码任务放入音频专用线程池异步处理
③ 解码	opusUtils.decode() (1084行)	Opus 解码：压缩数据 → PCM short[]
④ 格式转换	short[] → byte[] (1091-1097行)	PCM 数据转换：short[] 转为 byte[] 供播放short[]两字节
⑤ 入队	audioQueue.offer(pcmData) (1128行)	将 PCM 数据放入阻塞队列，实现播放缓冲
⑥ 出队	audioQueue.poll() (1180行)	播放线程从队列取出数据（超时100ms）
⑦ 播放	audioTrack.write() (1211行)	AudioTrack 将 PCM 数据写入硬件播放
* 
* */



package com.lhht.xiaozhi.activities;
//这是波奇酱
import android.annotation.SuppressLint;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;
import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.activities.BtThread.ConnectedThread;
import com.lhht.xiaozhi.api.ImageRecognitionManager;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import vip.inode.demo.opusaudiodemo.utils.OpusUtils;

import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.ref.WeakReference;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.camera.core.ImageProxy;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import android.view.Choreographer;
import android.os.Build;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.view.Display;
import android.view.WindowManager;

public class Voice extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private VideoView videoView;
    
    // 性能监控相关
    private PerformanceMonitor performanceMonitor;
    private long lastFrameTimeNanos = 0;
    private long frameCount = 0;
    private long fpsUpdateTimeNanos = 0;
    //音频录制参数Vertex16000
    private static final int SAMPLE_RATE = 16000;
    //声道配置 CHANNEL_IN_MONO 表示单声道输入。
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    //音频编码格式 ENCODING_PCM_16BIT 表示16位PCM编码格式。
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    //音频录制缓冲区大小
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);


    //音频播放的缓冲区大小 - 增加缓冲区大小以减少卡顿
    private static final int PLAY_BUFFER_SIZE = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 4;
    //Opus编码器的帧大小
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
    private boolean isRecording = false;
    private boolean isPlaying = false;
    
    // 音频焦点管理
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    
    // 音频播放队列
    private BlockingQueue<byte[]> audioQueue;//阻塞队列
    /*
    *
    * 启动播放时设为 true，播放线程会循环从 audioQueue 取数据。
    停止播放时设为 false，播放线程检测到状态变化后会退出循环，释放资源。
    * */
    private volatile boolean isPlaybackThreadRunning = false;//volatile 修饰的布尔变量，保证多线程下的可见性。作用：作为播放线程的 “运行状态标记”，用于安全地启动、停止播放线程。
    private ExecutorService playbackExecutor;//线程池对象，用于管理播放线程的生命周期。

    //用于录制音频
    private AudioRecord audioRecord;
    //用于播放音频
    private AudioTrack audioTrack;//Android 系统提供的音频播放核心类，负责将 PCM 音频数据输出到扬声器。
    private ExecutorService executorService;//可以处理其他需要异步执行的任务。
    private ExecutorService audioExecutor;//通常专门处理音频相关的耗时任务，如播放、编码、解码。
    private Handler mainHandler;
    //是管理 WebSocket 连接的核心类，负责与服务器建立长连接、收发音频 / 视频数据。它会把编码后的音频数据发送给服务器，同时接收服务器发来的音频数据。
    private WebSocketManager webSocketManager;
    private OpusUtils opusUtils;// 是封装了 Opus 编解码逻辑的工具类。
    private long encoderHandle;// Opus 编码器 / 解码器的句柄，是底层库的操作入口。充当指针
    private long decoderHandle;
    private short[] decodedBuffer;// 用来存放解码后的 PCM 音频数据，供 AudioTrack 播放。
    private short[] recordBuffer;//用来存放麦克风采集到的原始音频数据，供编码器使用。
    private boolean isAuth = false;//一个布尔标记，用来标识用户是否已经完成了身份验证。
    private ImageRecognitionManager imageRecognitionManager;
    private boolean isVideoUnderstanding = false; // 标识是否正在进行视频理解
    
    // 用于计算端到端延迟的时间记录
    private long lastAudioUploadTime = 0;
    private long lastLogTime = 0;
    private long speechStartTime = 0; // 记录用户开始说话的时间
//    private int frameCount = 0;
    private long totalLatencySum = 0;
    private int latencySampleCount = 0;
    
    // 复用的缓冲区，避免频繁创建数组
    // ⚠️ 注意：音频播放数据不能复用（会放入队列异步处理），波形显示数据可以复用
    private float[] waveformBuffer = new float[100];      // 用户波形显示
    private long lastWaveformUpdate = 0;
    private float[] amplitudeBuffer = new float[OPUS_FRAME_SIZE];  // AI波形显示
    
    // 回声消除和噪声抑制
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    
    // 性能监控类 - 使用 WeakReference 防止内存泄漏
    private static class PerformanceMonitor {
        private Choreographer.FrameCallback frameCallback;
        private long lastFrameTimeNanos = 0;
        private long frameCount = 0;
        private long fpsUpdateTimeNanos = 0;
        private float currentFPS = 0;
        private boolean isMonitoring = false;
        private WeakReference<Voice> activityRef;  // 使用弱引用
        private Handler handler;
        
        public PerformanceMonitor(Voice activity) {
            this.activityRef = new WeakReference<>(activity);
            this.handler = new Handler(Looper.getMainLooper());
        }
        
        public void startMonitoring() {
            if (isMonitoring) return;
            
            isMonitoring = true;
            lastFrameTimeNanos = System.nanoTime();
            fpsUpdateTimeNanos = lastFrameTimeNanos;
            frameCount = 0;
            
            frameCallback = new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!isMonitoring) return;
                    
                    frameCount++;
                    
                    // 每秒更新一次FPS
                    long elapsedNanos = frameTimeNanos - fpsUpdateTimeNanos;
                    if (elapsedNanos >= 1_000_000_000) { // 1秒
                        currentFPS = (frameCount * 1_000_000_000.0f) / elapsedNanos;
                        frameCount = 0;
                        fpsUpdateTimeNanos = frameTimeNanos;
                        
                        // 在主线程中记录FPS和其他性能指标
                        handler.post(() -> {
                            logPerformanceMetrics(frameTimeNanos);
                        });
                    }
                    
                    lastFrameTimeNanos = frameTimeNanos;
                    
                    // 注册下一帧
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };
            
            Choreographer.getInstance().postFrameCallback(frameCallback);
            Log.d("VoicePerformanceMonitor", "性能监控已启动");
        }
        
        public void stopMonitoring() {
            if (!isMonitoring) return;
            isMonitoring = false;
            
            if (frameCallback != null) {
                Choreographer.getInstance().removeFrameCallback(frameCallback);
            }
            
            Log.d("VoicePerformanceMonitor", "性能监控已停止");
        }
        
        private void logPerformanceMetrics(long frameTimeNanos) {
            Voice activity = activityRef.get();
            if (activity == null) {
                // Activity 已被回收，停止监控
                stopMonitoring();
                return;
            }
            
            // 记录FPS
            Log.d("VoicePerformanceMonitor", String.format("当前FPS: %.2f", currentFPS));
            
            // 记录内存使用情况
            ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            
            // 获取应用内存使用
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            float memoryUsagePercent = (usedMemory * 100.0f) / maxMemory;
            
            Log.d("VoicePerformanceMonitor", String.format("内存使用: %dMB / %dMB (%.1f%%)", 
                usedMemory / (1024 * 1024), maxMemory / (1024 * 1024), memoryUsagePercent));
            
            // 记录系统可用内存
            Log.d("VoicePerformanceMonitor", String.format("系统可用内存: %dMB", 
                memoryInfo.availMem / (1024 * 1024)));
            
            // 记录屏幕刷新率（如果API 23+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    float refreshRate = display.getRefreshRate();
                    Log.d("VoicePerformanceMonitor", String.format("屏幕刷新率: %.1f Hz", refreshRate));
                }
            }
            
            // 记录应用是否处于低内存状态
            if (memoryInfo.lowMemory) {
                Log.w("VoicePerformanceMonitor", "系统处于低内存状态");
            }
        }
        
        public float getCurrentFPS() {
            return currentFPS;
        }
        
        public boolean isMonitoring() {
            return isMonitoring;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long startTime = System.currentTimeMillis();
        Trace.beginSection("Voice.onCreate");
        
        super.onCreate(savedInstanceState);

        // 1. 设置沉浸式状态栏（轻量操作）
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        // 2. 立即显示UI
        Trace.beginSection("Voice.setContentView");
        setContentView(R.layout.activity_voice);
        Trace.endSection();
        
        // 3. 初始化视图（主线程，必须）
        Trace.beginSection("Voice.initViews");
        initViews();
        Trace.endSection();
        
        // 4. 初始化主线程Handler
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 5. 延迟初始化性能监控
        mainHandler.postDelayed(() -> {
            performanceMonitor = new PerformanceMonitor(this);
            performanceMonitor.startMonitoring();
        }, 50);
        
        // 6. 设置监听器（轻量，可立即执行）
        setupListeners();
        
        // 7. 延迟初始化音频组件（后台线程，避免阻塞UI）
        mainHandler.postDelayed(() -> {
            Trace.beginSection("Voice.initAudio");
            long audioStart = System.currentTimeMillis();
            initAudio();
            Log.d("XiaoZhiPerf", "Voice音频初始化耗时: " + (System.currentTimeMillis() - audioStart) + "ms");
            Trace.endSection();
        }, 100);
        
        // 8. 延迟初始化WebSocket连接（页面完全显示后再连接）
        mainHandler.postDelayed(() -> {
            Trace.beginSection("Voice.initWebSocket");
            long wsStart = System.currentTimeMillis();
            initWebSocket();
            Log.d("XiaoZhiPerf", "Voice WebSocket初始化耗时: " + (System.currentTimeMillis() - wsStart) + "ms");
            Trace.endSection();
        }, 200);
        
        // 9. 延迟初始化图像识别（非关键功能，最后初始化）
        mainHandler.postDelayed(() -> {
            Trace.beginSection("Voice.initImageRecognition");
            long imgStart = System.currentTimeMillis();
            initImageRecognition();
            Log.d("XiaoZhiPerf", "Voice图像识别初始化耗时: " + (System.currentTimeMillis() - imgStart) + "ms");
            Trace.endSection();
        }, 300);
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d("XiaoZhiPerf", "Voice.onCreate 总耗时: " + duration + "ms");
        Trace.endSection();
    }

    private void initViews() {
        videoView = findViewById(R.id.video);
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
        String deviceId = 	"c0:3e:ba:2e:d5:97";
        SettingsManager settingsManager = new SettingsManager(this);
        String wsUrl = settingsManager.getWsUrl();
        String token = settingsManager.getToken();
        boolean enableToken = settingsManager.isTokenEnabled();

        webSocketManager = WebSocketManager.getInstance(deviceId);
        webSocketManager.setListener(this);

        // 如果已经连接，直接开始通话，不需要重新连接
        if (webSocketManager.isConnected()) {
            Log.d("VoiceCall", "WebSocket已连接，直接开始通话");
            updateCallStatus("已连接");
            startCall();
            return;
        }

        // 连接WebSocket（如果未连接）
        if (!webSocketManager.isConnected()) {
            try {
                webSocketManager.connect(wsUrl, token, enableToken);
                updateCallStatus("正在连接...");
            } catch (Exception e) {
                Log.e("VoiceCall", "WebSocket连接失败", e);
                updateCallStatus("连接失败: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                // 不关闭页面，让用户可以选择重试或返回
            }
        } else {
            Log.d("VoiceCall", "WebSocket已连接，直接开始通话");
            updateCallStatus("已连接");
            startCall();
        }
    }

    private void initAudio() {
        executorService = Executors.newSingleThreadExecutor();
        audioExecutor = Executors.newSingleThreadExecutor();
        playbackExecutor = Executors.newSingleThreadExecutor();
        cameraExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

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
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // 改为语音通信
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(AudioAttributes.FLAG_LOW_LATENCY) // 添加低延迟标志
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
    //相机权限请求回调方法
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
    //相机启动摄像头预览
    private void startCameraPreview() {
        if (cameraProvider != null) {
            // 已经初始化过相机，直接绑定并启动
            bindCameraPreview();
            return;
        }

        // 首次获取 CameraProvider
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraPreview();
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraPreview", "Error getting camera provider: " + e.getMessage());
                Toast.makeText(this, "无法启动前置摄像头", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraPreview() {
        if (cameraProvider == null) return;

        // 取消之前绑定的所有用例
        cameraProvider.unbindAll();

        // 设置前置摄像头
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        // 创建预览用例
        Preview preview = new Preview.Builder()
                .build();

        // 将预览画面连接到 PreviewView
        preview.setSurfaceProvider(frontCameraPreview.getSurfaceProvider());

        try {
            // 绑定用例到生命周期
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            isPreviewStarted = true;
        } catch (Exception e) {
            Log.e("CameraPreview", "Error binding camera preview: " + e.getMessage());
            Toast.makeText(this, "无法启动前置摄像头", Toast.LENGTH_SHORT).show();
        }
    }

    //停止摄像头预览
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
//===
    private void startCall() {
        if (!webSocketManager.isConnected()) {
            updateCallStatus("未连接");
            return;
        }

        try {
            // 发送开始通话消息
            JSONObject startMessage = new JSONObject();
            startMessage.put("type", "start");
            startMessage.put("mode", "auto");
            startMessage.put("audio_params", new JSONObject()
                    .put("format", "opus")
                    .put("sample_rate", SAMPLE_RATE)
                    .put("channels", 1)
                    .put("frame_duration", 60));
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
                    Log.d("VoiceCall", "AcousticEchoCanceler enabled");
                }
            }

            // 启用噪声抑制器
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    Log.d("VoiceCall", "NoiseSuppressor enabled");
                }
            }
        }

        // 检查线程池状态
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            Log.w("VoiceCall", "ExecutorService已关闭，无法开始录音");
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
        // 安全检查：确保opus已初始化
        if (opusUtils == null || encoderHandle == 0) {
            Log.w("VoiceCall", "Opus编码器未初始化，跳过发送音频");
            return;
        }
        
        if (webSocketManager != null && webSocketManager.isConnected()) {
            try {
                // 记录音频上传开始时间
                long uploadStartTime = System.currentTimeMillis();
                // 记录这次上传时间用于计算端到端延迟
                lastAudioUploadTime = uploadStartTime;
                Log.d("VoiceCall-Audio", "开始上传音频数据: " + size + " bytes, 时间戳: " + uploadStartTime);
                
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
                    
                    // 记录编码完成时间
                    long encodeTime = System.currentTimeMillis();
                    Log.d("VoiceCall-Audio", "音频编码完成: 原始" + size + " bytes -> 编码后" + encodedSize + " bytes, 耗时: " + (encodeTime - uploadStartTime) + "ms");
                    
                    // 发送音频数据
                    webSocketManager.sendBinaryMessage(encodedBytes);
                    
                    // 记录发送完成时间
                    long sendTime = System.currentTimeMillis();
                    Log.d("VoiceCall-Audio", "音频数据发送完成, 总耗时: " + (sendTime - uploadStartTime) + "ms");
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
        
        // 根据扬声器状态调整音频模式以优化回声抑制
        if (isSpeakerOn) {
            // 扬声器模式，使用通信模式并启用回声抑制
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } else {
            // 听筒模式，使用通信模式
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }
    }
    private void stopRecording() {
        isRecording = false;
        
        // 释放回声消除器
        if (echoCanceler != null) {
            echoCanceler.setEnabled(false);
            echoCanceler.release();
            echoCanceler = null;
        }
        
        // 释放噪声抑制器
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
                Log.e("VoiceCall", "Error stopping recording", e);
            }
        }
    }

    //挂断
    private void endCall() {
        try {
            // 停止录音
            isRecording = false;
            stopRecording();
            
            // 停止播放线程
            stopPlaybackThread();
            
            // 安全释放AudioTrack
            if (audioTrack != null) {
                try {
                    if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                        audioTrack.stop();
                    }
                    audioTrack.release();
                } catch (Exception e) {
                    Log.e("VoiceCall", "释放AudioTrack失败", e);
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
                    Log.e("VoiceCall", "关闭WebSocket连接失败", e);
                }
            }
            
            // 释放音频焦点
            abandonAudioFocus();
            
            // 恢复默认音频模式
            try {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception e) {
                Log.e("VoiceCall", "恢复音频模式失败", e);
            }
            
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
            
            // 释放Opus编解码器
            if (encoderHandle != 0) {
                try {
                    opusUtils.destroyEncoder(encoderHandle);
                } catch (Exception e) {
                    Log.e("VoiceCall", "释放Opus编码器失败", e);
                } finally {
                    encoderHandle = 0;
                }
            }
            if (decoderHandle != 0) {
                try {
                    opusUtils.destroyDecoder(decoderHandle);
                } catch (Exception e) {
                    Log.e("VoiceCall", "释放Opus解码器失败", e);
                } finally {
                    decoderHandle = 0;
                }
            }
            
            // 释放图像识别管理器
            if (imageRecognitionManager != null) {
                try {
                    imageRecognitionManager.release();
                } catch (Exception e) {
                    Log.e("VoiceCall", "释放图像识别管理器失败", e);
                } finally {
                    imageRecognitionManager = null;
                }
            }
            
        } catch (Exception e) {
            Log.e("VoiceCall", "endCall执行失败", e);
        } finally {
            // 确保Activity能够正常结束
            finish();
        }
    }
    //打断===
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
        runOnUiThread(() -> {
            if (callStatusText != null) {
                callStatusText.setText(status);
            }
        });
    }
    //更新文字
    public void updateAiMessage(String message) {
        runOnUiThread(() -> {
            if (aiMessageText != null) {
                aiMessageText.setText(message);
            }
        });
    }
    //更新人文字
    public void updateRecognizedText(String text) {
        runOnUiThread(() -> {
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
            if (text != null && text.contains("看到了什么") && cameraProvider != null && isPreviewStarted) {
                // 检查图像识别管理器是否已初始化
                if (imageRecognitionManager == null) {
                    // 未配置讯飞API，显示提示信息
                    Toast.makeText(Voice.this, "未配置讯飞API，无法使用图像识别功能，请在设置中配置", Toast.LENGTH_SHORT).show();
                    // 可以选择跳转到设置页面
                    // Intent intent = new Intent(Voice.this, SettingsActivity.class);
                    // startActivity(intent);
                } else {
                    captureFrame();
                }
            }
        });
    }

    //更新人声音波形 - 使用复用缓冲区，减少内存分配
    private void updateUserWaveform(byte[] buffer) {
        if (userWaveformView == null) return;
        
        // 降频：每 50ms 更新一次 UI，避免过度渲染
        long now = System.currentTimeMillis();
        if (now - lastWaveformUpdate < 50) return;
        lastWaveformUpdate = now;
        
        // 采样：只取 100 个点，避免大量计算
        int step = Math.max(1, (buffer.length / 2) / 100);
        for (int i = 0; i < 100; i++) {
            int idx = i * step * 2;
            if (idx + 1 < buffer.length) {
                short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
                waveformBuffer[i] = sample / 32768f;
            }
        }
        runOnUiThread(() -> userWaveformView.setAmplitudes(waveformBuffer));
    }

    //更新AI声音波形
    public void updateAiWaveform(float[] amplitudes) {
        runOnUiThread(() -> {
            if (aiWaveformView != null) {
                aiWaveformView.setAmplitudes(amplitudes);
            }
        });
    }

    @Override
    public void onConnected() {
        long connectTime = System.currentTimeMillis();
        Log.d("VoiceCall-Connection", "WebSocket连接成功, 时间戳: " + connectTime);
        updateCallStatus("已连接");
        startCall();
    }

    @Override
    public void onDisconnected() {
        long disconnectTime = System.currentTimeMillis();
        Log.d("VoiceCall-Connection", "WebSocket连接断开, 时间戳: " + disconnectTime);
        updateCallStatus("连接已断开");
        endCall();
    }

    @Override
    public void onError(String error) {
        long errorTime = System.currentTimeMillis();
        Log.e("VoiceCall-Connection", "WebSocket错误: " + error + ", 时间戳: " + errorTime);
        updateCallStatus("错误: " + error);
    }

    @Override
    public void onMessage(String message) {
        // 记录消息接收时间
        long messageReceiveTime = System.currentTimeMillis();
        Log.d("VoiceCall-Message", "收到文本消息: " + message + ", 时间戳: " + messageReceiveTime);
        
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");

            switch (type) {
                case "stt":
                    // 处理语音识别结果
                    String recognizedText = jsonMessage.getString("text");
                    long sttProcessTime = System.currentTimeMillis() - messageReceiveTime;
                    Log.d("VoiceCall-STT", "语音识别结果: " + recognizedText + ", 处理耗时: " + sttProcessTime + "ms");
                    
                    // 如果是有效的语音识别结果（非空且不是噪声），记录说话开始时间
                    if (recognizedText != null && !recognizedText.trim().isEmpty() && speechStartTime == 0) {
                        // 这里使用消息接收时间近似作为用户开始说话的时间
                        speechStartTime = messageReceiveTime;
                        Log.d("VoiceCall-ResponseTime", "记录用户说话开始时间: " + speechStartTime + "ms (识别文本: '" + recognizedText + "')");
                    }
                    
                    updateRecognizedText(recognizedText);
                    // 打断当前音频播放
                    stopCurrentAudio();
                    break;

                case "tts":
                    long ttsProcessTime = System.currentTimeMillis() - messageReceiveTime;
                    Log.d("VoiceCall-TTS", "收到TTS消息, 处理耗时: " + ttsProcessTime + "ms");
                    handleTTSMessage(jsonMessage);
                    break;
            }
        } catch (Exception e) {
            Log.e("VoiceCall", "处理消息失败", e);
        }
    }

    private void stopCurrentAudio() {
        // 检查线程池状态
        if (audioExecutor == null || audioExecutor.isShutdown() || audioExecutor.isTerminated()) {
            Log.w("VoiceCall", "AudioExecutor已关闭，无法停止音频");
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
            long ttsProcessStart = System.currentTimeMillis();
            
            switch (state) {
                case "start":
                    Log.d("VoiceCall-TTS", "TTS开始, 时间戳: " + ttsProcessStart);
                    stopCurrentAudio();
                    updateCallStatus("AI正在说话...");
                    break;

                case "sentence_start":
                    String text = message.getString("text");
                    String[] parts = extractEmojiAndText(text);
                    String emoji = parts[0];
                    String cleanText = parts[1];
                    long sentenceStartTime = System.currentTimeMillis();
                    Log.d("VoiceCall-TTS", "TTS句子开始: '" + cleanText + "', 时间戳: " + sentenceStartTime);
                    
                    // 计算从用户开始说话到AI开始回复的完整时间
                    if (speechStartTime > 0) {
                        long totalResponseTime = sentenceStartTime - speechStartTime;
                        Log.d("VoiceCall-ResponseTime", "用户说话到AI回复的完整时间: " + totalResponseTime + "ms (回复: '" + cleanText + "')");
                        // 重置说话时间，避免重复计算
                        speechStartTime = 0;
                    }

                    updateAiMessage(cleanText);

                    if (!emoji.isEmpty()) {
                        showEmoji(emoji);
                    } else {
                        hideEmoji();
                    }
                    updateCallStatus("AI正在说话...");
                    break;

                case "end":
                    long ttsEndTime = System.currentTimeMillis();
                    Log.d("VoiceCall-TTS", "TTS结束, 时间戳: " + ttsEndTime + ", 总处理时间: " + (ttsEndTime - ttsProcessStart) + "ms");
                    updateCallStatus("正在通话中...");
                    hideEmoji();
                    break;

                case "error":
                    String error = message.optString("error", "未知错误");
                    Log.e("VoiceCall-TTS", "TTS错误: " + error + ", 时间戳: " + System.currentTimeMillis());
                    updateCallStatus("TTS错误: " + error);
                    hideEmoji();
                    break;
            }
        } catch (Exception e) {
            Log.e("VoiceCall", "处理TTS消息失败", e);
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
        runOnUiThread(() -> {
            if (emojiText != null) {
                emojiText.setText(emoji);
                emojiText.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideEmoji() {
        runOnUiThread(() -> {
            if (emojiText != null) {
                emojiText.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onBinaryMessage(byte[] data) {
        if (data == null || data.length == 0) {
            Log.w("VoiceCall", "Received empty audio data");
            return;
        }

        // 记录音频接收开始时间
        long receiveStartTime = System.currentTimeMillis();
        Log.d("VoiceCall-Audio", "开始接收服务器音频数据: " + data.length + " bytes, 时间戳: " + receiveStartTime);

        // 检查线程池状态，避免在已关闭的线程池中执行任务
        if (audioExecutor == null || audioExecutor.isShutdown() || audioExecutor.isTerminated()) {
            Log.w("VoiceCall", "AudioExecutor已关闭，忽略音频数据");
            return;
        }

        audioExecutor.execute(() -> {
            try {
                // 安全检查：确保opus已初始化
                if (opusUtils == null || decoderHandle == 0) {
                    Log.w("VoiceCall", "Opus解码器未初始化，忽略音频数据");
                    return;
                }
                
                Log.d("AudioDebug", "收到音频数据长度: " + data.length + " bytes");
                
                // 记录解码开始时间
                long decodeStartTime = System.currentTimeMillis();
                int decodedSamples = opusUtils.decode(decoderHandle, data, decodedBuffer);
                long decodeEndTime = System.currentTimeMillis();
                Log.d("VoiceCall-Audio", "音频解码完成: " + data.length + " bytes -> " + decodedSamples + " samples, 耗时: " + (decodeEndTime - decodeStartTime) + "ms");

                if (decodedSamples > 0) {
                    // ⚠️ 音频数据必须创建新数组，因为会被放入队列异步播放
                    // 复用缓冲区会导致数据被覆盖，播放异常
                    byte[] pcmData = new byte[decodedSamples * 2];
                    
                    for (int i = 0; i < decodedSamples; i++) {
                        short sample = decodedBuffer[i];
                        pcmData[i * 2] = (byte) (sample & 0xff);
                        pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                    }
                    
                    Log.d("AudioDebug", "PCM数据长度: " + pcmData.length + " bytes");
                    
                    // 记录PCM数据准备完成时间
                    long pcmReadyTime = System.currentTimeMillis();
                    Log.d("VoiceCall-Audio", "PCM数据准备完成: " + pcmData.length + " bytes, 从接收到现在总耗时: " + (pcmReadyTime - receiveStartTime) + "ms");
                    
                    // 计算从音频上传到接收的总延迟
                    long totalLatency = pcmReadyTime - lastAudioUploadTime;
                    
                    // 累计延迟数据用于统计
                    totalLatencySum += totalLatency;
                    latencySampleCount++;
                    
                    // 每秒输出一次延迟统计，而不是每一帧都输出
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime >= 1000) {
                        if (latencySampleCount > 0) {
                            long avgLatency = totalLatencySum / latencySampleCount;
                            Log.d("VoiceCall-Latency", String.format("平均延迟统计: %dms (基于%d个音频帧，采样周期1秒)", avgLatency, latencySampleCount));
                            
                            // 重置统计
                            totalLatencySum = 0;
                            latencySampleCount = 0;
                            lastLogTime = currentTime;
                        }
                    }
                    
                    // 将音频数据放入队列，由专门的播放线程处理
                    if (audioQueue != null) {
                        boolean offered = audioQueue.offer(pcmData);
                        Log.d("AudioDebug", "音频数据入队: " + offered + ", 队列大小: " + audioQueue.size());
                        if (!offered) {
                            Log.w("AudioDebug", "音频队列已满，丢弃数据");
                        }
                    }

                    // 波形显示数据可以复用缓冲区（立即使用，不入队列）
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

    @Override
    protected void onPause() {
        super.onPause();
        // 停止性能监控
        if (performanceMonitor != null) {
            performanceMonitor.stopMonitoring();
        }
        // 停止摄像头预览（释放相机资源）
        stopCameraPreview();
        isPreviewStarted = false;
        // 隐藏预览并恢复按钮状态
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
        // 重新启动性能监控
        if (performanceMonitor != null) {
            performanceMonitor.startMonitoring();
        }
    }
    
    @Override
    protected void onDestroy() {
        Log.d("Voice", "onDestroy 开始执行");
        
        // 1. 移除所有 Handler 回调和消息
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        // 2. 停止性能监控
        if (performanceMonitor != null) {
            performanceMonitor.stopMonitoring();
            performanceMonitor = null;
        }
        
        // 3. 先发送结束消息再断开连接（同步操作）
        if (webSocketManager != null) {
            try {
                if (webSocketManager.isConnected()) {
                    JSONObject endMessage = new JSONObject();
                    endMessage.put("type", "end");
                    webSocketManager.sendMessage(endMessage.toString());
                    // 短暂等待消息发送
                    Thread.sleep(100);
                }
                webSocketManager.disconnect();
            } catch (Exception e) {
                Log.e("Voice", "断开WebSocket失败", e);
            }
        }
        
        // 4. 停止录音和播放
        isRecording = false;
        isPlaybackThreadRunning = false;
        
        // 5. 安全释放音频资源
        stopRecording();
        
        if (audioTrack != null) {
            try {
                if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.stop();
                }
                audioTrack.release();
                audioTrack = null;
            } catch (Exception e) {
                Log.e("Voice", "释放AudioTrack失败", e);
            }
        }
        
        // 6. 清空音频队列
        if (audioQueue != null) {
            audioQueue.clear();
        }
        
        // 7. 释放摄像头
        stopCameraPreview();
        
        // 8. 关闭线程池（等待完成）
        shutdownExecutor(executorService);
        shutdownExecutor(audioExecutor);
        shutdownExecutor(playbackExecutor);
        
        // 9. 释放编解码器
        if (encoderHandle != 0) {
            try {
                opusUtils.destroyEncoder(encoderHandle);
                encoderHandle = 0;
            } catch (Exception e) {
                Log.e("Voice", "释放Encoder失败", e);
            }
        }
        if (decoderHandle != 0) {
            try {
                opusUtils.destroyDecoder(decoderHandle);
                decoderHandle = 0;
            } catch (Exception e) {
                Log.e("Voice", "释放Decoder失败", e);
            }
        }
        
        // 10. 释放图像识别管理器
        if (imageRecognitionManager != null) {
            try {
                imageRecognitionManager.release();
            } catch (Exception e) {
                Log.e("Voice", "释放图像识别管理器失败", e);
            }
            imageRecognitionManager = null;
        }
        
        Log.d("Voice", "onDestroy 执行完成");
        super.onDestroy();
    }
    
    /**
     * 安全关闭线程池
     */
    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) return;
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
     
     private void startPlaybackThread() {
         // 检查线程池状态
         if (playbackExecutor == null || playbackExecutor.isShutdown() || playbackExecutor.isTerminated()) {
             Log.w("VoiceCall", "PlaybackExecutor已关闭，无法启动播放线程");
             return;
         }
         
         isPlaybackThreadRunning = true;
         playbackExecutor.execute(() -> {
             Log.d("AudioPlayback", "播放线程启动");
             while (isPlaybackThreadRunning) {
                 try {
                     // 从队列中取出音频数据，使用poll避免无限阻塞
                     byte[] pcmData = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                     if (pcmData == null) {
                         continue; // 超时，继续循环检查
                     }
                     
                     // 记录音频开始播放时间
                     long playbackStartTime = System.currentTimeMillis();
                     Log.d("AudioPlayback", "从队列取出音频数据: " + pcmData.length + " bytes, 开始播放时间戳: " + playbackStartTime);
                     
                     // 计算从音频上传到开始播放的总延迟
                     long totalLatencyToPlayback = playbackStartTime - lastAudioUploadTime;
                     // 只记录异常高的延迟（超过200ms）
                     if (totalLatencyToPlayback > 200) {
                         Log.w("VoiceCall-Latency", "高延迟检测: " + totalLatencyToPlayback + "ms (从上传开始到实际播放)");
                     }
                     
                     // 确保AudioTrack已初始化
                     if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                         initAudioTrack();
                         Log.d("AudioTrack", "Reinitialized audio track");
                     }
                     
                     // 开始播放
                     if (!isPlaying && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                         audioTrack.play();
                         isPlaying = true;
                         Log.d("AudioTrack", "Playback started");
                     }
                     
                     // 写入音频数据
                     if (isPlaying && audioTrack != null) {
                         int bytesWritten = audioTrack.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_BLOCKING);
                         Log.d("AudioDebug", "写入AudioTrack字节数: " + bytesWritten);
                         
                         // 记录音频写入完成时间
                         long writeCompleteTime = System.currentTimeMillis();
                         long finalLatency = writeCompleteTime - lastAudioUploadTime;
                         frameCount++;
                         // 每100帧输出一次最终延迟，减少日志量
                         if (frameCount % 100 == 0) {
                             Log.d("VoiceCall-Latency", "最终延迟采样: " + finalLatency + "ms (从上传开始到写入AudioTrack完成)");
                         }
                     }
                 } catch (InterruptedException e) {
                     Log.d("AudioPlayback", "播放线程被中断");
                     break;
                 } catch (Exception e) {
                     Log.e("AudioPlayback", "播放音频数据失败", e);
                 }
             }
             Log.d("AudioPlayback", "播放线程结束");
         });
     }
     
     private void stopPlaybackThread() {
         Log.d("AudioPlayback", "停止播放线程");
         isPlaybackThreadRunning = false;
         if (audioQueue != null) {
             audioQueue.clear();
         }
     }
    
    private void initAudioFocusListener() {
        audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        // 重新获得音频焦点，恢复播放
                        if (audioTrack != null && !isPlaying) {
                            audioTrack.play();
                            isPlaying = true;
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // 失去音频焦点，暂停播放
                        if (audioTrack != null && isPlaying) {
                            audioTrack.pause();
                            isPlaying = false;
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        // 可以降低音量继续播放
                        break;
                }
            }
        };
    }
    
    private void requestAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN);
    }
    
    private void abandonAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioFocusChangeListener != null) {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 视频播放已禁用
        // if (videoView != null) {
        //     outState.putInt("VIDEO_POSITION", videoView.getCurrentPosition());
        // }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // 视频播放已禁用
        // if (videoView != null) {
        //     videoView.seekTo(savedInstanceState.getInt("VIDEO_POSITION", 0));
        // }
    }

    private void initSDK() {
        Log.d("SDK", "正在初始化SDK...");
        // 初始化SDK，使用链式调用简化代码
        SparkChainConfig sparkChainConfig = SparkChainConfig.builder()
                .appID(getResources().getString(R.string.appid))
                .apiKey(getResources().getString(R.string.apikey))
                .apiSecret(getResources().getString(R.string.apiSecret))
                .logLevel(666);

        int ret = SparkChain.getInst().init(getApplicationContext(), sparkChainConfig);
        isAuth = (ret == 0);
        Log.d("SDK", isAuth ? "SDK初始化成功" : "SDK初始化失败,错误码: " + ret);
        if (isAuth) {
            Toast.makeText(this, "SDK初始化成功", Toast.LENGTH_SHORT).show();
        }
    }
    //相机数据返回后端服务器
    private void initImageRecognition() {
        // 获取讯飞API配置
        SettingsManager settingsManager = new SettingsManager(this);
        String appId = settingsManager.getAppId();
        String apiKey = settingsManager.getApiKey();
        String apiSecret = settingsManager.getApiSecret();
        
        // 检查API配置是否为空
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
            // API未配置，不初始化图像识别管理器
            imageRecognitionManager = null;
            Log.w("VoiceCall", "讯飞API未配置，图像识别功能不可用");
            return;
        }
        
        

        
        // API已配置，初始化图像识别管理器===
        try {
            imageRecognitionManager = new ImageRecognitionManager(this, new ImageRecognitionManager.ImageRecognitionCallback() {
                @Override
                public void onRecognitionResult(String content) {
                    runOnUiThread(() -> {
                        // 直接发送文本消息
                        if (webSocketManager != null && webSocketManager.isConnected()) {
                            try {
                                JSONObject jsonMessage = new JSONObject();
                                jsonMessage.put("type", "listen");
                                jsonMessage.put("state", "detect");
                                // 根据视频理解状态和内容长度动态调整提示词，优化响应速度
                                String prompt;
                                if (isVideoUnderstanding || content.length() > 100) {
                                    // 主动视频理解或长内容使用完整复述
                                    prompt = "[视觉]:" + content + "。请用自然流畅的语言完整地复述这个视觉描述，保持内容的连贯性和完整性。";
                                } else {
                                    // 普通图像识别场景使用简洁回复，提高响应速度
                                    prompt = "[视觉]:" + content + "。请简洁地描述看到的内容。";
                                }
                                jsonMessage.put("text", prompt);
                                
                                // 重置视频理解状态
                                isVideoUnderstanding = false;
                                jsonMessage.put("source", "text");
                                // 使用优先级发送确保图像识别结果及时处理
                                webSocketManager.sendPriorityMessage(jsonMessage.toString());
                            } catch (Exception e) {
                                Log.e("VoiceCall", "发送识别消息失败", e);
                            }
                        }
                        // Toast.makeText(Voice.this, "识别结果: " + content, Toast.LENGTH_SHORT).show(); // 隐藏识别结果Toast，只保留用户语音输入
                        Log.d("ImageRecognition", "识别结果: " + content);
                    });
                }

                @Override
                public void onRecognitionError(String errorMessage) {
                    runOnUiThread(() -> {
                        Toast.makeText(Voice.this, "识别失败: " + errorMessage, Toast.LENGTH_SHORT).show();
                        Log.e("ImageRecognition", "识别失败: " + errorMessage);
                    });
                }
            });
        } catch (Exception e) {
            Log.e("VoiceCall", "初始化图像识别管理器失败", e);
            runOnUiThread(() -> Toast.makeText(this, "初始化图像识别失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            imageRecognitionManager = null;
        }
    }
    //相机识别
    private void captureFrame() {
        if (cameraProvider == null) {
            Toast.makeText(this, "相机未启动", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查图像识别管理器是否已初始化
        if (imageRecognitionManager == null) {
            // 未配置讯飞API，显示提示信息
            Toast.makeText(Voice.this, "未配置讯飞API，无法使用图像识别功能", Toast.LENGTH_SHORT).show();
            return;
        }

        // 设置视频理解状态为true，表示这是主动的视频理解请求
        isVideoUnderstanding = true;

        // 使用 ImageAnalysis 获取预览帧
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            // 转换为 YUV 格式
            byte[] yuvData = imageProxyToYuv420(imageProxy);

            if (yuvData != null) {
                // 调用处理方法（需要 Camera 参数来获取尺寸）
                // 由于 CameraX 不再使用 Camera 对象，我们创建一个简单的包装
                final int width = imageProxy.getWidth();
                final int height = imageProxy.getHeight();

                // 在主线程调用图像识别
                runOnUiThread(() -> {
                    // 使用反射或直接创建一个虚拟 Camera 对象来获取尺寸
                    // 这里我们直接处理
                    try {
                        // 将 ImageProxy 转换为兼容格式
                        imageRecognitionManager.processPreviewFrameFromCameraX(yuvData, width, height);
                        Toast.makeText(Voice.this, "正在识别图像...", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e("CameraPreview", "图像处理失败: " + e.getMessage());
                    }
                });
            }

            // 关闭 ImageProxy 以释放资源
            imageProxy.close();

            // 取消分析，只处理一帧
            imageAnalysis.clearAnalyzer();
        });

        // 重新绑定用例，添加 ImageAnalysis
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        cameraProvider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(frontCameraPreview.getSurfaceProvider());

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        } catch (Exception e) {
            Log.e("CameraPreview", "绑定相机失败: " + e.getMessage());
        }
    }

    // 将 ImageProxy 转换为 YUV420 格式
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
}