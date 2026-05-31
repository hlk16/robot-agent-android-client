/*┌─────────────────────────────────────────────────────────────────────────────┐
  │  服务器 → WebSocket下载 → 解码，格式转换 → 队列缓冲 → AudioTrack播放              │
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

import android.annotation.SuppressLint;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;


import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import vip.inode.demo.opusaudiodemo.utils.OpusUtils;

import org.json.JSONObject;
import org.json.JSONArray;
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

import android.graphics.YuvImage;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Voice extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private VideoView videoView;
    
    //采样率16000Hz
    private static final int SAMPLE_RATE = 16000;
    //声道配置 CHANNEL_IN_MONO 表示单声道输入。
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    //音频编码格式 ENCODING_PCM_16BIT 表示16位PCM编码格式。
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    //物理麦克风音频录制缓冲区大小
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);


    //物理扬声器音频播放的缓冲区大小 - 使用最小缓冲区以降低延迟
    private static final int PLAY_BUFFER_SIZE = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 1;
    //Opus编码器的帧大小
    private static final int OPUS_FRAME_SIZE = 1440;

    private static final int MSG_INIT_AUDIO = 1;
    private static final int MSG_INIT_WEBSOCKET = 2;

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
    
    // 音频焦点管理
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    
    // 音频播放队列
    /*
    * 启动播放时设为 true，播放线程会循环从 audioQueue 取数据。
    停止播放时设为 false，播放线程检测到状态变化后会退出循环，释放资源。
    * */
    private BlockingQueue<byte[]> audioQueue;//阻塞队列

    private volatile boolean isPlaybackThreadRunning = false;//volatile 修饰的布尔变量，保证多线程下的可见性。作用：作为播放线程的 “运行状态标记”，用于安全地启动、停止播放线程。
    private ExecutorService playbackExecutor;//线程池对象，用于管理播放线程的生命周期。

    //用于录制音频
    private AudioRecord audioRecord;
    //用于播放音频
    private AudioTrack audioTrack;//Android 系统提供的音频播放核心类，负责将 PCM 音频数据输出到扬声器。
    private ExecutorService executorService;//可以处理其他需要异步执行的任务。
    private ExecutorService audioExecutor;//通常专门处理音频相关的耗时任务，如播放、编码、解码。
    private SafeHandler mainHandler;
    //是管理 WebSocket 连接的核心类，负责与服务器建立长连接、收发音频 / 视频数据。它会把编码后的音频数据发送给服务器，同时接收服务器发来的音频数据。
    private WebSocketManager webSocketManager;
    private OpusUtils opusUtils;// 是封装了 Opus 编解码逻辑的工具类。
    private long encoderHandle;// Opus 编码器 / 解码器的句柄，是底层库的操作入口。充当指针
    private long decoderHandle;
    private short[] decodedBuffer;// 用来存放解码后的 PCM 音频数据，供 AudioTrack 播放。
    private short[] recordBuffer;//用来存放麦克风采集到的原始音频数据，供编码器使用。
    
    private long speechStartTime = 0; // 记录用户开始说话的时间
    
    // 复用的缓冲区，避免频繁创建数组
    // ⚠️ 注意：音频播放数据不能复用（会放入队列异步处理），波形显示数据可以复用
    private float[] waveformBuffer = new float[100];      // 用户波形显示
    private long lastWaveformUpdate = 0;
    private float[] amplitudeBuffer = new float[OPUS_FRAME_SIZE];  // AI波形显示
    
    // 回声消除和噪声抑制硬件AEC
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    
    // 视频帧缓存
    private FrameCache frameCache;//环形数组实现的帧缓存对象，固定容量 5 帧。它持续接收摄像头预览帧，自动覆盖最旧数据。
    private long lastFrameCaptureTime = 0;//上一帧捕获时间戳
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long startTime = System.currentTimeMillis();
        
        super.onCreate(savedInstanceState);

        // 1. 设置沉浸式状态栏（轻量操作）
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        // 2. 立即显示UI
        setContentView(R.layout.activity_voice);
        
        // 3. 初始化视图（主线程，必须）
        initViews();
        
        // 4. 初始化主线程Handler（静态内部类 + WeakReference，防止内存泄漏）
        mainHandler = new SafeHandler(this);
        
        // 5. 设置监听器（轻量，可立即执行）
        setupListeners();
        
        // 7. 延迟初始化音频组件（后台线程，避免阻塞UI）
        mainHandler.sendEmptyMessageDelayed(MSG_INIT_AUDIO, 100);
        
        // 8. 延迟初始化WebSocket连接（页面完全显示后再连接）
        mainHandler.sendEmptyMessageDelayed(MSG_INIT_WEBSOCKET, 200);
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d("XiaoZhiPerf", "Voice.onCreate 总耗时: " + duration + "ms");
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
        String deviceId = 	"c0:3e:ba:2e:d5:97";
        SettingsManager settingsManager = new SettingsManager(this);
        String wsUrl = settingsManager.getWsUrl();
        String token = settingsManager.getToken();
        boolean enableToken = settingsManager.isTokenEnabled();

        webSocketManager = WebSocketManager.getInstance(deviceId);
        webSocketManager.setListener(this);

        // 如果已经连接，onConnected() 已处理过，不需要重复发送 hello
        if (webSocketManager.isConnected()) {
            Log.d("VoiceCall", "WebSocket已连接，直接开始通话");
            updateCallStatus("已连接");
            startRecording();
            return;
        }

        // 连接WebSocket（如果未连接），连上后 onConnected() 会自动调用 startCall()
        try {
            webSocketManager.connect(wsUrl, token, enableToken);
            updateCallStatus("正在连接...");
        } catch (Exception e) {
            Log.e("VoiceCall", "WebSocket连接失败", e);
            updateCallStatus("连接失败: " + e.getMessage());
            Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initAudio() {
        //采集，发送
        executorService = Executors.newSingleThreadExecutor();//音频采集，编码和发送   从麦克风读取 PCM 数据 → Opus 编码 → WebSocket 发送（第474行）
        //接收，播放
        audioExecutor = Executors.newSingleThreadExecutor();//音频解码和播放控制   WebSocket 接收 Opus 数据 → 解码为 PCM（第974行）；暂停/停止播放（第845行）
        playbackExecutor = Executors.newSingleThreadExecutor();//音频播放
        //摄像头分析
        cameraExecutor = Executors.newSingleThreadExecutor();//摄像头分析

        // 设置音频会话模式为通信模式，有助于回声消除
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        
        // 初始化音频焦点监听器
        initAudioFocusListener();
        
        // 请求音频焦点
        requestAudioFocus();
        
        // 初始化音频播放队列
        audioQueue = new LinkedBlockingQueue<>();//音频播放队列，用于存储解码后的PCM数据
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

    private void setupListeners() {//设置按钮点击事件监听器
        muteButton.setOnClickListener(v -> toggleMute());
        hangupButton.setOnClickListener(v -> endCall());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        previewButton.setOnClickListener(v -> toggleCameraPreview());

        // 点击屏幕打断AI回答
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> interruptAiResponse());
    }

    private void toggleCameraPreview() {//切换开关摄像头预览
        // 检查摄像头权限，如果没授权就申请权限并返回
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
    //相机权限请求回调方法，重写父类方法
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
            bindCameraPreview();
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                if (frameCache == null) {
                    frameCache = new FrameCache();//创建帧缓存对象
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

        cameraProvider.unbindAll();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        Preview preview = new Preview.Builder()
                .build();

        preview.setSurfaceProvider(frontCameraPreview.getSurfaceProvider());

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            isPreviewStarted = true;
            
            if (frameCache != null) {
                startContinuousFrameCapture();//开始连续捕获视频帧
            }
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


    private void startCall() {//发送握手请求
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
            //发送开始通话请求
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


    /*
    * 本地录制编码发送音频
    * */
    @SuppressLint("MissingPermission")
    private void startRecording() {//开始一直录音
        if (audioRecord == null) {
            audioRecord = new AudioRecord(//创建录音对象
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
            );
            
            // 启用回声消除器
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());//创建绑定
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);//启用
                    Log.d("VoiceCall", "AcousticEchoCanceler enabled");
                }
            }

            // 启用噪声抑制器
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());//创建绑定
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);//启用
                    Log.d("VoiceCall", "NoiseSuppressor enabled");
                }
            }
        }

        // 检查线程池状态
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            Log.w("VoiceCall", "ExecutorService已关闭，无法开始录音");
            return;
        }

        executorService.execute(() -> {//告诉线程池：帮我跑一下后面这段代码。
            try {
                audioRecord.startRecording();
                byte[] buffer = new byte[BUFFER_SIZE];
                // 独占单线程池，循环这个任务，直到isRecording为false
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
    private void sendAudioData(byte[] data, int size) {//编码发送
        // 安全检查：确保opus已初始化
        if (opusUtils == null || encoderHandle == 0) {
            Log.w("VoiceCall", "Opus编码器未初始化，跳过发送音频");
            return;
        }
        
        if (webSocketManager != null && webSocketManager.isConnected()) {
            try {
                // 记录音频上传开始时间
                long uploadStartTime = System.currentTimeMillis();
                
                // 将byte[]转换为short[] Opus 需要 short[]
                short[] samples = new short[size / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) ((data[i * 2] & 0xFF) | (data[i * 2 + 1] << 8));
                }

                // 编码音频数据 Opus 编码：压缩音频数据
                byte[] encodedData = new byte[size];
                int encodedSize = opusUtils.encode(encoderHandle, samples, 0, encodedData);
                //如果编码成功，才发送音频数据
                if (encodedSize > 0) {
                    // 直接发送编码后的音频数据
                    byte[] encodedBytes = new byte[encodedSize];
                    System.arraycopy(encodedData, 0, encodedBytes, 0, encodedSize);
                    
                    // 记录编码完成时间
                    long encodeTime = System.currentTimeMillis();
                    
                    // 发送音频数据
                    webSocketManager.sendBinaryMessage(encodedBytes);
                    
                    // 记录发送完成时间
                    long sendTime = System.currentTimeMillis();
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
    private void toggleSpeaker() {//扬声器/听筒切换 方法
        isSpeakerOn = !isSpeakerOn;
        speakerButton.setImageResource(isSpeakerOn ? R.drawable.ic_volume_up : R.drawable.ic_volume_off);

        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(isSpeakerOn);
        // 音频模式，使用通信模式并启用回声抑制
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
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
    private void endCall() {//关闭各种资源
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
            
        } catch (Exception e) {
            Log.e("VoiceCall", "endCall执行失败", e);
        } finally {
            // 确保Activity能够正常结束
            finish();
        }
    }
    //打断AI回答 方法
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
        long connectTime = System.currentTimeMillis();
        Log.d("VoiceCall-Connection", "WebSocket连接成功, 时间戳: " + connectTime);
        updateCallStatus("已连接");
        startCall();
    }

    @Override
    public void onDisconnected() {
        long disconnectTime = System.currentTimeMillis();
        Log.e("VoiceCall-Connection", "WebSocket连接断开, 时间戳: " + disconnectTime + ", isMcpInProgress=" + isMcpInProgress);
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
        long errorTime = System.currentTimeMillis();
        Log.e("VoiceCall-Connection", "WebSocket错误: " + error + ", 时间戳: " + errorTime + ", isMcpInProgress=" + isMcpInProgress);
        if (isMcpInProgress) {
            Log.w("VoiceCall-Connection", "MCP进行中, 忽略WebSocket错误, 等待重连...");
            return;
        }
        updateCallStatus("错误: " + error);
    }

    @Override
    public void onMessage(String message) {
        // 记录消息接收时间
        long messageReceiveTime = System.currentTimeMillis();
        Log.d("VoiceCall-Message", "收到文本消息: " + message + ", 时间戳: " + messageReceiveTime);
        
        try {
            JSONObject jsonMessage = new JSONObject(message);//解析JSON字符串
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
                    handleTTSMessage(jsonMessage);//安全更新UI
                    break;

                case "mcp":
                    handleMcpMessage(jsonMessage);
                    break;
            }
        } catch (Exception e) {
            Log.e("VoiceCall", "处理消息失败", e);
        }
    }

    private void stopCurrentAudio() {//打断当前音频播放，当ai说话或者用户说话时调用
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

    private void handleTTSMessage(JSONObject message) {//安全更新UI，处理json消息
        try {
            String state = message.getString("state");
            long ttsProcessStart = System.currentTimeMillis();
            
            switch (state) {//state json的键，根据不同的状态进行处理
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

    private String[] extractEmojiAndText(String text) {//从文本中提取emoji和普通文本
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
        if (data == null || data.length == 0) {
            Log.w("VoiceCall", "Received empty audio data");
            return;
        }

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
                //decodedBuffer (short[])  ← 在这里！Opus 解码后的原始 PCM 采样点
                //decodedSamples (int)  ←decodedSamples 是"音频数据的颗粒数"，乘以 2 才是实际占用的字节大小。
                //这是因为代码中使用的是 16 位 PCM 音频格式 ：每个样本占用 2 个字节
                int decodedSamples = opusUtils.decode(decoderHandle, data, decodedBuffer);//解码音频数据，返回解码后的样本数
                //如果有数据
                if (decodedSamples > 0) {
                    byte[] pcmData = new byte[decodedSamples * 2];//创建一个字节数组，大小刚好用于存储解码后的PCM数据
                    java.nio.ByteBuffer.wrap(pcmData).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(decodedBuffer, 0, decodedSamples);
                    //将解码后的PCM数据放入阻塞队列
                    if (audioQueue != null) {
                        boolean offered = audioQueue.offer(pcmData);//尝试将PCM数据放入队列，返回是否成功放入
                        if (!offered) {
                            Log.w("AudioDebug", "音频队列已满，丢弃数据");
                        }
                    }
                    //更新AI波形图
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
    }
    
    @Override
    protected void onDestroy() {
        Log.d("Voice", "onDestroy 开始执行");
        
        // 1. 移除所有 Handler 回调和消息
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        // 2. 先发送结束消息再断开连接（同步操作）
        if (webSocketManager != null) {
            try {
                if (webSocketManager.isConnected()) {
                    JSONObject endMessage = new JSONObject();
                    endMessage.put("type", "end");
                    webSocketManager.sendMessage(endMessage.toString());
                    // 短暂等待消息发送
                    Thread.sleep(100);
                }
                webSocketManager.removeListener();
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
        
        // 10. 释放音频焦点 - 关键：防止内存泄漏
        abandonAudioFocus();
        audioFocusChangeListener = null;
        
        // 12. 恢复默认音频模式
        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            Log.e("Voice", "恢复音频模式失败", e);
        }
        
        Log.d("Voice", "onDestroy 执行完成");
        super.onDestroy();
    }
    
    /**
     * 安全关闭线程池这是一个 线程池优雅关闭 的工具方法
     * 播放完当前数据，又能及时释放资源，不会产生突兀的中断效果
     */
    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) return;//如果线程池为空或已关闭，直接返回
        //发起温和关闭：不再接受新任务，但会等待已提交的任务完成
        executor.shutdown();
        try {//等待已提交的任务完成
        //如果等待超过1秒，强制关闭线程池
            if (!executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
     

    /*
    * 启动播放线程
    * */
    private void startPlaybackThread() {//启动播放线程
         // 检查线程池状态
         if (playbackExecutor == null || playbackExecutor.isShutdown() || playbackExecutor.isTerminated()) {
             Log.w("VoiceCall", "PlaybackExecutor已关闭，无法启动播放线程");
             return;
         }
         
         isPlaybackThreadRunning = true;
         playbackExecutor.execute(() -> {//提交播放任务
             Log.d("AudioPlayback", "播放线程启动");
             while (isPlaybackThreadRunning) {
                 try {
                     // 从队列中取出音频数据，降低轮询超时以减少延迟  从队列取数据，10ms超时（非阻塞轮询）
                     //音频不能等
                    byte[] pcmData = audioQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
                     if (pcmData == null) {
                         continue; // 超时，继续循环检查
                     }
                     
                     // 记录音频开始播放时间
                     long playbackStartTime = System.currentTimeMillis();
                     Log.d("AudioPlayback", "从队列取出音频数据: " + pcmData.length + " bytes, 开始播放时间戳: " + playbackStartTime);
                     
                     // 确保AudioTrack已初始化
                     if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                         initAudioTrack();
                         Log.d("AudioTrack", "Reinitialized audio track");
                     }
                     
                     // 开始播放
                     if (!isPlaying && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                         audioTrack.play();//启动音频流播放，让 AudioTrack 进入可出声状态
                         isPlaying = true;
                         Log.d("AudioTrack", "Playback started");
                     }
                     
                     // 写入音频数据
                     if (isPlaying && audioTrack != null) {
                         int bytesWritten = audioTrack.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_BLOCKING);
                         Log.d("AudioDebug", "写入AudioTrack字节数: " + bytesWritten);
                         // 记录音频写入完成时间
                         long writeCompleteTime = System.currentTimeMillis();
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
        // 使用静态内部类 + WeakReference 防止内存泄漏
        audioFocusChangeListener = new AudioFocusChangeListener(this);
    }
    
    /**
     * 音频焦点管理
     * 自定义音频焦点监听器
     */
    private static class AudioFocusChangeListener implements AudioManager.OnAudioFocusChangeListener {
        private final WeakReference<Voice> activityRef;
        
        public AudioFocusChangeListener(Voice activity) {
            this.activityRef = new WeakReference<>(activity);
        }
        
        @Override
        public void onAudioFocusChange(int focusChange) {
            Voice activity = activityRef.get();
            if (activity == null) {
                // Activity 已被回收，直接返回
                return;
            }
            
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    // 重新获得音频焦点，恢复播放
                    if (activity.audioTrack != null && !activity.isPlaying) {
                        activity.audioTrack.play();
                        activity.isPlaying = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // 失去音频焦点，暂停播放
                    if (activity.audioTrack != null && activity.isPlaying) {
                        activity.audioTrack.pause();
                        activity.isPlaying = false;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // 可以降低音量继续播放
                    break;
            }
        }
    }
    
    private void requestAudioFocus() {//请求音频焦点
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN);
    }
    
    private void abandonAudioFocus() {//放弃音频焦点
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

    private void startContinuousFrameCapture() {//开始连续捕获视频帧
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()//创建图像分析器
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {//设置图像分析器的分析器
            // 检查是否需要捕获新帧
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFrameCaptureTime < FRAME_CAPTURE_INTERVAL_MS) {//如果距离上一次捕获时间不足100ms，直接返回
                imageProxy.close();
                return;
            }
            
            lastFrameCaptureTime = currentTime;
            //捕捉
            byte[] yuvData = imageProxyToYuv420(imageProxy);
            if (yuvData != null) {
                int width = imageProxy.getWidth();
                int height = imageProxy.getHeight();
                
                frameCache.addFrame(yuvData, width, height);
            }
            
            imageProxy.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()//创建相机选择器
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

    private static class FrameCache {//底层字节数组
        private static final int CACHE_SIZE = 5; // 缓存5帧（约3-5秒）
        private final FrameData[] frames;
        private int writeIndex = 0;//写入索引，指向下一个要写入的帧位置
        private int count = 0;//当前缓存中的帧数量
        
        private static class FrameData {
            byte[] yuvData;//YUV420格式的视频数据数组
            int width;//视频宽度
            int height;//视频高度
            long timestamp;//视频时间戳
            
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
            
            writeIndex = (writeIndex + 1) % CACHE_SIZE;//更新写入索引，指向下一个要写入的帧位置，取余覆盖旧帧
            count = Math.min(count + 1, CACHE_SIZE);
        }
        //获取最新的一帧
        synchronized FrameData getLatestFrame() {
            if (count == 0) return null;
            int latestIndex = (writeIndex - 1 + CACHE_SIZE) % CACHE_SIZE;
            return frames[latestIndex];
        }
        //根据时间戳获取最近的一帧
        synchronized FrameData getFrameNearTimestamp(long targetTimestamp) {
            if (count == 0) return null;
            
            FrameData closest = null;
            long minDiff = Long.MAX_VALUE;
            
            for (int i = 0; i < count; i++) {
                int idx = (writeIndex - 1 - i + CACHE_SIZE) % CACHE_SIZE;
                FrameData frame = frames[idx];
                if (frame != null) {
                    long diff = Math.abs(frame.timestamp - targetTimestamp);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closest = frame;
                    }
                }
            }
            
            if (minDiff > 2000) {
                return null;
            }
            
            return closest;
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
        private final WeakReference<Voice> activityRef;

        SafeHandler(Voice activity) {
            super(Looper.getMainLooper());
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Voice voice = activityRef.get();
            if (voice == null) return;

            long startTime;
            switch (msg.what) {
                case MSG_INIT_AUDIO:
                    startTime = System.currentTimeMillis();
                    voice.initAudio();
                    Log.d("XiaoZhiPerf", "Voice音频初始化耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                    break;
                case MSG_INIT_WEBSOCKET:
                    startTime = System.currentTimeMillis();
                    voice.initWebSocket();
                    Log.d("XiaoZhiPerf", "Voice WebSocket初始化耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                    break;
            }
        }
    }
}