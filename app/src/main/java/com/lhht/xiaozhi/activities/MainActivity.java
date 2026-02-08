package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.google.android.material.navigation.NavigationView;
import androidx.appcompat.app.AlertDialog;
import android.content.SharedPreferences;
import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;
import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import vip.inode.demo.opusaudiodemo.utils.OpusUtils;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;
import android.view.Choreographer;
import android.os.Build;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.view.Display;
import android.view.WindowManager;

public class MainActivity extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    
    // 性能监控相关
    private PerformanceMonitor performanceMonitor;
    private long lastFrameTimeNanos = 0;
    private long frameCount = 0;
    private long fpsUpdateTimeNanos = 0;
    
    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    
    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    private void requestPermissions() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Boolean isGranted : permissions.values()) {
                        if (!isGranted) {
                            allGranted = false;
                            break;
                        }
                    }
                    
                    if (!allGranted) {
                        Toast.makeText(this, "部分权限被拒绝，视频通话、语音录制等功能可能无法使用", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "权限已授予，可以正常使用所有功能", Toast.LENGTH_SHORT).show();
                    }
                });
        
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS);
    }
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
    };
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    private static final int PLAY_BUFFER_SIZE = 65536;  // 增大缓冲区到64KB
    private static final int OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
    private static final int MAX_QUEUE_SIZE = 5; // 最大消息队列长度
    private static final int MESSAGE_TIMEOUT = 500; // 消息处理超时时间（毫秒）
    //改掉之前static关键字防止泄露
    private WebSocketManager webSocketManager;
    private SettingsManager settingsManager;
    private TextView connectionStatus;
    private Button connectButton;
    private ImageButton recordButton;
    private EditText messageInput;
    private Button sendButton;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private ExecutorService executorService;
    private boolean isPlaying = false;
    private byte[] audioBuffer;
    private OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private short[] decodedBuffer;
    private short[] recordBuffer;
    private TextView callStatusText;
    private WaveformView waveformView;
    private View voiceContainer;
    private ExecutorService audioExecutor;  // 音频处理线程池
    private TextView emojiText;
    private TextView messageText;
    private String lastEmoji = "";
    private String lastMessage = "";
    private String currentEmoji = "";
    private String currentMessage = "";
    private String nextEmoji = "";
    private String nextMessage = "";
    private boolean isFirstMessage = true;
    private boolean isAudioTrackPlaying = false;
    private boolean isAudioTrackPaused = false;

    private SafeHandler mainHandler;
    private final Object messageLock = new Object();
    
    // 静态Handler，避免内存泄漏
    private static class SafeHandler extends Handler {
        private final WeakReference<MainActivity> weakRef;
        
        SafeHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            this.weakRef = new WeakReference<>(activity);
        }
        
        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = weakRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // 处理消息（如有需要）
        }
    }
    private volatile String currentText = "";
    private volatile boolean isProcessingMessage = false;
    private long lastMessageTime = 0;
    private String pendingText = null;
    private volatile String pendingAudioText = null;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private ImageButton menuButton;

    private boolean isAuth = false;
    
    // 性能监控类 - 使用WeakReference避免内存泄漏
    private static class PerformanceMonitor {
        private Choreographer.FrameCallback frameCallback;
        private long lastFrameTimeNanos = 0;
        private long frameCount = 0;
        private long fpsUpdateTimeNanos = 0;
        private float currentFPS = 0;
        private boolean isMonitoring = false;
//添加了WeakReference关键字
        private final WeakReference<MainActivity> weakRef;
        private Handler handler;
        private final Runnable logRunnable;
        private long currentFrameTimeNanos;
        
        public PerformanceMonitor(MainActivity activity) {
            this.weakRef = new WeakReference<>(activity);
            this.handler = new Handler(Looper.getMainLooper());
            // 只创建一次Runnable，避免重复分配
            this.logRunnable = new Runnable() {
                @Override
                public void run() {
                    MainActivity activity = weakRef.get();
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    logPerformanceMetrics(activity, currentFrameTimeNanos);
                }
            };
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
                        
                        // 在主线程中记录FPS和其他性能指标，复用同一个Runnable
                        currentFrameTimeNanos = frameTimeNanos;
                        handler.post(logRunnable);
                    }
                    
                    lastFrameTimeNanos = frameTimeNanos;
                    
                    // 注册下一帧
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };
            
            Choreographer.getInstance().postFrameCallback(frameCallback);
            Log.d("PerformanceMonitor", "性能监控已启动");
        }
        
        public void stopMonitoring() {
            if (!isMonitoring) return;
            isMonitoring = false;
            
            if (frameCallback != null) {
                Choreographer.getInstance().removeFrameCallback(frameCallback);
            }
            
            Log.d("PerformanceMonitor", "性能监控已停止");
        }
        
        private void logPerformanceMetrics(MainActivity activity, long frameTimeNanos) {
            // 记录FPS
            Log.d("PerformanceMonitor", String.format("当前FPS: %.2f", currentFPS));
            
            // 记录内存使用情况
            ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            
            // 获取应用内存使用
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            float memoryUsagePercent = (usedMemory * 100.0f) / maxMemory;
            
            Log.d("PerformanceMonitor", String.format("内存使用: %dMB / %dMB (%.1f%%)", 
                usedMemory / (1024 * 1024), maxMemory / (1024 * 1024), memoryUsagePercent));
            
            // 记录系统可用内存
            Log.d("PerformanceMonitor", String.format("系统可用内存: %dMB", 
                memoryInfo.availMem / (1024 * 1024)));
            
            // 记录屏幕刷新率（如果API 23+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    float refreshRate = display.getRefreshRate();
                    Log.d("PerformanceMonitor", String.format("屏幕刷新率: %.1f Hz", refreshRate));
                }
            }
            
            // 记录应用是否处于低内存状态
            if (memoryInfo.lowMemory) {
                Log.w("PerformanceMonitor", "系统处于低内存状态");
            }
        }
        
        public float getCurrentFPS() {
            return currentFPS;
        }
        
        public boolean isMonitoring() {
            return isMonitoring;
        }
    }
    
    // 添加一个消息队列类来处理消息顺序
    private static class TTSMessage {
        final String text;
        final long timestamp;
        final String sessionId;

        TTSMessage(String text, String sessionId) {
            this.text = text;
            this.timestamp = System.nanoTime(); // 使用纳秒级时间戳
            this.sessionId = sessionId;
        }
    }

    // 在类成员变量中添加
    private volatile TTSMessage currentTTSMessage = null;
    private volatile String currentSessionId = null;

    // 修改 MessageHandler 类 - 静态内部类避免内存泄漏
    private static class MessageHandler {
        private static final int MAX_TEXT_LENGTH = 100; // 长文本阈值
        private final WeakReference<MainActivity> weakRef;
        private final WeakReference<SafeHandler> handlerRef;
        
        MessageHandler(MainActivity activity, SafeHandler handler) {
            this.weakRef = new WeakReference<>(activity);
            this.handlerRef = new WeakReference<>(handler);
        }
        
        public synchronized void reset() {
            SafeHandler handler = handlerRef.get();
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        }
        
        public synchronized void processMessage(String text) {
            if (text == null || text.isEmpty()) return;
            
            MainActivity activity = weakRef.get();
            SafeHandler handler = handlerRef.get();
            if (activity == null || handler == null) return;
            
            // 直接在当前线程处理，避免线程切换开销
            String[] parts = extractEmojiAndText(text);
            String emoji = parts[0];
            String cleanText = parts[1];
            
            // 使用 postAtFrontOfQueue 确保最高优先级
            handler.postAtFrontOfQueue(() -> {
                MainActivity act = weakRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) return;
                try {
                    act.updateEmojiView(emoji);
                    act.updateTextView(cleanText);
                    Log.d("XiaoZhi", "UI更新完成: " + text + " 时间: " + System.nanoTime());
                } catch (Exception e) {
                    Log.e("XiaoZhi", "更新显示失败", e);
                }
            });
        }
        
        // 静态工具方法，提取emoji和文本
        static String[] extractEmojiAndText(String text) {
            String emoji = "";
            String cleanText = text;
            
            if (text.length() > 0) {
                int firstCodePoint = text.codePointAt(0);
                if (Character.getType(firstCodePoint) == Character.SURROGATE || 
                    Character.getType(firstCodePoint) == Character.OTHER_SYMBOL) {
                    emoji = new String(Character.toChars(firstCodePoint));
                    cleanText = text.substring(Character.charCount(firstCodePoint)).trim();
                }
            }
            
            return new String[]{emoji, cleanText};
        }
        
        private void updateDisplay(String emoji, String text) {
            MainActivity activity = weakRef.get();
            SafeHandler handler = handlerRef.get();
            if (activity == null || handler == null) return;
            
            // 使用 post 而不是 postDelayed，减少延迟
            handler.post(() -> {
                MainActivity act = weakRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) return;
                try {
                    act.updateEmojiView(emoji);
                    act.updateTextView(text);
                } catch (Exception e) {
                    Log.e("XiaoZhi", "更新显示失败", e);
                }
            });
        }
    }

    // 创建消息处理器实例
    private MessageHandler messageHandler;
    
    // UI更新方法，供MessageHandler调用
    void updateEmojiView(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            emojiText.setVisibility(View.GONE);
        } else {
            emojiText.setText(emoji);
            emojiText.setVisibility(View.VISIBLE);
        }
    }
    
    void updateTextView(String text) {
        if (text == null || text.isEmpty()) {
            messageText.setVisibility(View.GONE);
        } else {
            messageText.setText(text);
            messageText.setVisibility(View.VISIBLE);
        }
    }
    
    private void showFirstTimeDialog() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean isFirstTime = prefs.getBoolean("is_first_time", true);
        
        if (isFirstTime) {
            new AlertDialog.Builder(this)
                .setTitle("欢迎使用小智助手")
                .setMessage("欢迎使用小智智能助手！\n\n使用前请注意：\n1. 首次使用需要在设置中配置API信息\n2. 自己部署小智后端可以享受完整功能\n3. 连接虾哥服务器只能文字对话(bug)\n4. 应用需要摄像头、麦克风权限用于视频通话功能\n5. 该项目作者b站电子裁缝-叫我康康，大学生作品bug多，更新慢。请谅解后续会慢慢更新\n◉6. 该软件目前免费，如果付费可能被骗，可以点点举报\n\n点击确定开始使用！")
                .setPositiveButton("确定", (dialog, which) -> {
                    // 标记已显示过首次提示
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("is_first_time", false);
                    editor.apply();
                    dialog.dismiss();
                })
                .setNegativeButton("前往设置", (dialog, which) -> {
                    // 标记已显示过首次提示
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("is_first_time", false);
                    editor.apply();
                    // 打开设置页面
                    openSettings();
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
        }
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long startTime = System.currentTimeMillis();
        Trace.beginSection("MainActivity.onCreate");
        
        super.onCreate(savedInstanceState);
        
        // 1. 先显示UI，让用户感知启动快
        Trace.beginSection("MainActivity.setContentView");
        setContentView(R.layout.activity_main);
        Trace.endSection();
        
        // 2. 初始化视图（必须在主线程）
        Trace.beginSection("MainActivity.initViews");
        initViews();
        Trace.endSection();
        
        // 3. 延迟初始化SDK（后台线程）
        new Thread(() -> {
            Trace.beginSection("MainActivity.initSDK");
            long sdkStart = System.currentTimeMillis();
            initSDK();
            Log.d("XiaoZhiPerf", "SDK初始化耗时: " + (System.currentTimeMillis() - sdkStart) + "ms");
            Trace.endSection();
        }).start();
        
        // 4. 延迟初始化非关键组件
        mainHandler = new SafeHandler(this);
        messageHandler = new MessageHandler(this, mainHandler);
        settingsManager = new SettingsManager(this);
        
        String deviceId = "c0:3e:ba:2e:d5:97";
        webSocketManager = WebSocketManager.getInstance(deviceId);
        webSocketManager.setListener(this);
        
        // 5. 延迟初始化线程池和音频（100ms后，避免阻塞UI）
        mainHandler.postDelayed(() -> {
            Trace.beginSection("MainActivity.initBackground");
            executorService = Executors.newSingleThreadExecutor();
            audioExecutor = Executors.newSingleThreadExecutor();
            
            // 延迟初始化音频组件（懒加载模式）
            initAudioComponents();
            
            performanceMonitor = new PerformanceMonitor(this);
            performanceMonitor.startMonitoring();
            Trace.endSection();
        }, 100);
        
        // 6. 检查权限（异步）
        if (!hasPermissions()) {
            requestPermissions();
        }
        checkPermissions();
        
        // 7. 设置监听器
        setupListeners();
        
        // 8. 显示首次启动提示（延迟，避免阻塞）
        mainHandler.postDelayed(this::showFirstTimeDialog, 200);
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d("XiaoZhiPerf", "MainActivity.onCreate 总耗时: " + duration + "ms");
        Trace.endSection();
    }
    
    private void initViews() {
        connectionStatus = findViewById(R.id.connectionStatus);
        connectButton = findViewById(R.id.connectButton);
        recordButton = findViewById(R.id.recordButton);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        emojiText = findViewById(R.id.emojiText);
        messageText = findViewById(R.id.messageText);
        menuButton = findViewById(R.id.more);
    }
    
    private void setupListeners() {
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        if (connectButton != null) connectButton.setOnClickListener(v -> toggleConnection());
        if (recordButton != null) recordButton.setOnClickListener(v -> startVoiceCall());
        if (sendButton != null) sendButton.setOnClickListener(v -> sendMessage());
        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());
        menuButton.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, menu.class);
            startActivity(intent);
        });
    }
    
    /**
     * 懒加载音频组件 - 第一次使用时才初始化
     */
    private synchronized void initAudioComponents() {
        if (audioTrack != null) return; // 已初始化则跳过
        
        Trace.beginSection("MainActivity.initAudioComponents");
        long start = System.currentTimeMillis();
        
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            );
            Log.i("MainActivity", "AudioTrack最小缓冲区: " + minBufferSize + " 字节");
            
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
            
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                Log.i("MainActivity", "AudioTrack初始化成功");
            }
            
            // 初始化 Opus 编解码器
            opusUtils = OpusUtils.getInstance();
            encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
            decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
            decodedBuffer = new short[OPUS_FRAME_SIZE];
            recordBuffer = new short[OPUS_FRAME_SIZE];
            
        } catch (Exception e) {
            Log.e("MainActivity", "音频组件初始化失败", e);
        }
        
        Log.d("XiaoZhiPerf", "音频组件初始化耗时: " + (System.currentTimeMillis() - start) + "ms");
        Trace.endSection();
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void toggleConnection() {
        boolean isConnected = webSocketManager.isConnected();
        Log.d("WebSocket", "切换连接状态，当前状态: " + (isConnected ? "已连接" : "未连接"));
        
        if (!isConnected) {
            String wsUrl = settingsManager.getWsUrl();
            String token = settingsManager.getToken();
            boolean enableToken = settingsManager.isTokenEnabled();
            
            // 添加日志和空值检查
            Log.d("WebSocket", "正在连接: " + wsUrl + ", token启用: " + enableToken);
            if (wsUrl == null || wsUrl.isEmpty()) {
                Toast.makeText(this, "WebSocket地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                // 重新设置监听器，防止断开连接后被移除
                webSocketManager.setListener(this);
                webSocketManager.connect(wsUrl, token, enableToken);
                Log.d("WebSocket", "连接请求已发送");
            } catch (Exception e) {
                Log.e("WebSocket", "连接失败: " + e.getMessage());
                Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.d("WebSocket", "执行断开连接");
            webSocketManager.disconnect();
            // 立即更新UI，不等待回调（提升响应速度）
            onDisconnected();
        }
    }

    private void startVoiceCall() {
        // 直接跳转到语音通话页面，连接状态在页面内处理
        Intent intent = new Intent(MainActivity.this, Voice.class);
        startActivity(intent);
    }
//===
    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (!message.isEmpty() && webSocketManager.isConnected()) {
            try {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("type", "listen");
                jsonMessage.put("state", "detect");
                jsonMessage.put("text", message);
                jsonMessage.put("source", "text");
                webSocketManager.sendMessage(jsonMessage.toString());
                messageInput.setText("");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    public void onConnected() {
//        Log.d("WebSocket", "连接成功");
        addLog("WebSocket", "已连接");
        runOnUiThread(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_connected)));
            connectButton.setText(R.string.disconnect);
//            Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDisconnected() {
        Log.d("WebSocket", "连接断开");
        addLog("WebSocket", "已断开");
        runOnUiThread(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_disconnected)));
            connectButton.setText(R.string.connect);
            stopAudioAndReset();
            Toast.makeText(this, "连接已断开", Toast.LENGTH_SHORT).show();
        });
    }

    private void stopAudioAndReset() {
        isRecording = false;
        isPlaying = false;
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e("MainActivity", "停止录音失败", e);
            }
            audioRecord = null;
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e("MainActivity", "停止播放失败", e);
            }
            audioTrack = null;
        }
        
        // 重置UI状态
        messageHandler.reset();
    }

    @Override
    public void onError(String error) {
        Log.e("WebSocket", "错误: " + error);
        addLog("Error", error);
        runOnUiThread(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_error)));
            connectButton.setText(R.string.connect);
            Toast.makeText(this, "错误: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            String state = jsonMessage.optString("state");
            
            // 记录消息接收时间和处理
            Log.d("XiaoZhi", "收到消息: " + message + " 时间: " + System.nanoTime());
            
            if ("tts".equals(type)) {
                String sessionId = jsonMessage.optString("session_id");
                switch (state) {
                    case "start":
                        // 只重置显示，不清除文本
                        messageHandler.reset();
                        audioExecutor.execute(this::initAudioTrack);
                        break;
                        
                    case "sentence_start":
                        if (jsonMessage.has("text")) {
                            String text = jsonMessage.getString("text");
                            // 直接在UI线程更新，跳过所有延迟和队列
                            runOnUiThread(() -> {
                                try {
                                    // 直接更新UI，不经过MessageHandler的队列
                                    String[] parts = MessageHandler.extractEmojiAndText(text);
                                    if (!parts[0].isEmpty()) {
                                        emojiText.setText(parts[0]);
                                        emojiText.setVisibility(View.VISIBLE);
                                    }
                                    if (!parts[1].isEmpty()) {
                                        messageText.setText(parts[1]);
                                        messageText.setVisibility(View.VISIBLE);
                                    }
                                    Log.d("XiaoZhi", "直接更新UI: " + text + " 时间: " + System.nanoTime());
                                } catch (Exception e) {
                                    Log.e("XiaoZhi", "更新显示失败", e);
                                }
                            });
                        }
                        break;
                        
                    case "stop":
                        // 不清除文本，只停止音频
                        handleTTSStop();
                        break;
                }
            }
        } catch (Exception e) {
            Log.e("XiaoZhi", "处理消息失败", e);
        }
    }

    private void handleTTSStart() {
        messageHandler.reset();
        audioExecutor.execute(this::initAudioTrack);
    }

    private void handleTTSSentence(JSONObject jsonMessage) {
        try {
            if (jsonMessage.has("text")) {
                String text = jsonMessage.getString("text");
                // 立即处理文本，不等待 sentence_start
                messageHandler.processMessage(text);
            }
        } catch (Exception e) {
            Log.e("XiaoZhi", "处理句子失败", e);
        }
    }

    private void handleTTSStop() {
        audioExecutor.execute(() -> {
            try {
                if (audioTrack != null && isAudioTrackPlaying) {
                    audioTrack.flush();
                    audioTrack.stop();
                    isAudioTrackPlaying = false;
                    isPlaying = false;
                }
            } catch (Exception e) {
                Log.e("XiaoZhi-Audio", "停止音频播放失败", e);
            }
        });
    }

    @Override
    public void onBinaryMessage(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        final byte[] audioData = data.clone();
        audioExecutor.execute(() -> {
            try {
                // 懒加载：确保音频组件已初始化
                if (audioTrack == null || opusUtils == null) {
                    initAudioComponents();
                }
                
                if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    initAudioTrack();
                }

                if (!isAudioTrackPlaying || isAudioTrackPaused) {
                    audioTrack.play();
                    isAudioTrackPlaying = true;
                    isAudioTrackPaused = false;
                }

                // 解码和播放音频...
                int decodedSamples = opusUtils.decode(decoderHandle, audioData, decodedBuffer);
                if (decodedSamples <= 0) {
                    return;
                }

                byte[] pcmData = new byte[decodedSamples * 2];
                for (int i = 0; i < decodedSamples; i++) {
                    short sample = decodedBuffer[i];
                    pcmData[i * 2] = (byte) (sample & 0xff);
                    pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                }

                audioTrack.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_BLOCKING);
            } catch (Exception e) {
                Log.e("XiaoZhi-Audio", "处理音频数据失败", e);
            }
        });
    }

    private void initAudioTrack() {
        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
            
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play();
                isAudioTrackPlaying = true;
                isAudioTrackPaused = false;
                isPlaying = true;
            } else {
                throw new IllegalStateException("AudioTrack初始化失败");
            }
        } catch (Exception e) {
            Log.e("XiaoZhi-Audio", "初始化AudioTrack失败: " + e.getMessage());
            isAudioTrackPlaying = false;
            isPlaying = false;
        }
    }

    private void addLog(String tag, String message) {
        Log.i("XiaoZhi-" + tag, message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 重新设置监听器并刷新连接状态
        if (webSocketManager != null) {
            webSocketManager.setListener(this);
            updateConnectionStatus();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 移除所有 Handler 回调，防止内存泄漏
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
    
    /**
     * 根据当前连接状态更新UI
     */
    private void updateConnectionStatus() {
        boolean isConnected = webSocketManager.isConnected();
        Log.d("WebSocket", "onResume 刷新状态，当前: " + (isConnected ? "已连接" : "未连接"));
        
        if (isConnected) {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_connected)));
            connectButton.setText(R.string.disconnect);
        } else {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_disconnected)));
            connectButton.setText(R.string.connect);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 移除所有 Handler 回调
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        // 停止性能监控
        if (performanceMonitor != null) {
            performanceMonitor.stopMonitoring();
            performanceMonitor = null;
        }
        
        // 注意：不要移除 WebSocket 监听器，因为 Voice 可能还在使用
        // 只断开当前 Activity 的连接请求
        // webSocketManager.removeListener();  // 删除这行！
        
        // 安全释放音频资源（移到后台线程）
        if (audioExecutor != null && !audioExecutor.isShutdown()) {
            audioExecutor.execute(() -> {
                if (audioRecord != null) {
                    try {
                        audioRecord.stop();
                        audioRecord.release();
                    } catch (Exception e) {
                        Log.e("MainActivity", "释放AudioRecord失败", e);
                    }
                }
                if (audioTrack != null) {
                    try {
                        audioTrack.stop();
                        audioTrack.release();
                    } catch (Exception e) {
                        Log.e("MainActivity", "释放AudioTrack失败", e);
                    }
                }
                if (encoderHandle != 0) {
                    try {
                        opusUtils.destroyEncoder(encoderHandle);
                        encoderHandle = 0;
                    } catch (Exception e) {
                        Log.e("MainActivity", "释放Encoder失败", e);
                    }
                }
                if (decoderHandle != 0) {
                    try {
                        opusUtils.destroyDecoder(decoderHandle);
                        decoderHandle = 0;
                    } catch (Exception e) {
                        Log.e("MainActivity", "释放Decoder失败", e);
                    }
                }
            });
        }
        
        // 关闭线程池（等待任务完成）
        shutdownExecutor(executorService);
        shutdownExecutor(audioExecutor);
    }
    
    /**
     * 安全关闭线程池，等待任务完成
     */
    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) return;
        
        executor.shutdown();
        try {
            // 等待最多 2 秒
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private void initSDK() {
        Log.d("SDK", "正在初始化SDK...");
        
        // 从设置管理器获取用户输入的API配置
        SettingsManager settingsManager = new SettingsManager(this);
        String appId = settingsManager.getAppId();
        String apiKey = settingsManager.getApiKey();
        String apiSecret = settingsManager.getApiSecret();
        
        // 检查API配置是否完整
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
            Log.w("SDK", "API配置不完整，请在设置中配置appID、apiKey和apiSecret");
            runOnUiThread(() -> Toast.makeText(this, "请先在设置中配置API信息", Toast.LENGTH_LONG).show());
            return;
        }
        
        // 初始化SDK，使用用户配置的API数据
        SparkChainConfig sparkChainConfig = SparkChainConfig.builder()
                .appID(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .logLevel(666);

        int ret = SparkChain.getInst().init(getApplicationContext(), sparkChainConfig);
        isAuth = (ret == 0);
        Log.d("SDK", isAuth ? "SDK初始化成功" : "SDK初始化失败,错误码: " + ret);
        if (isAuth) {
//            runOnUiThread(() -> Toast.makeText(this, "SDK初始化成功", Toast.LENGTH_SHORT).show());
        } else {
            runOnUiThread(() -> Toast.makeText(this, "SDK初始化失败，请检查API配置", Toast.LENGTH_LONG).show());
        }
    }
}