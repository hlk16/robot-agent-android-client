package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Trace;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import androidx.appcompat.app.AlertDialog;
import android.content.SharedPreferences;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import vip.inode.demo.opusaudiodemo.utils.OpusUtils;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    
    private ActivityResultLauncher<String[]> requestPermissionLauncher;
        private static final String[] REQUIRED_PERMISSIONS = new String[]{// 必须请求的权限数组
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
    };
    private boolean hasPermissions() {// 检查所有权限是否已授予
        for (String permission : REQUIRED_PERMISSIONS) {// 遍历每一个权限
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    private void requestPermissions() {
        requestPermissionLauncher = registerForActivityResult(// 注册权限请求结果回调
                new ActivityResultContracts.RequestMultiplePermissions(),//告诉系统这是一个 多权限请求 操作
                permissions -> {//定义权限请求结果的回调函数
                    boolean allGranted = true;// 假设所有权限都被授予
                    for (Boolean isGranted : permissions.values()) {// 遍历每个权限的结果
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
        
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS);// 请求权限
    }
//    private static final int PERMISSION_REQUEST_CODE = 1;// 权限请求码，用于在回调中区分不同的权限请求。

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    private static final int PLAY_BUFFER_SIZE = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);  // 使用最小缓冲区降低延迟
    private static final int OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
    private static final int MAX_QUEUE_SIZE = 5; // 最大消息队列长度
    private static final int MESSAGE_TIMEOUT = 500; // 消息处理超时时间（毫秒）

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
    
    // SafeHandler 是基础的线程切换工具，用于在主线程安全地处理消息
    private static class SafeHandler extends Handler {//这个是给主线程的Handler，
        private final WeakReference<MainActivity> weakRef;
        
        SafeHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            this.weakRef = new WeakReference<>(activity);
        }
        
        @Override
        public void handleMessage(Message msg) {//必须重写一个这个方法，所有sendmessage方法要被这个方法处理
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

    // 定义一个TTS消息类，相当于消息队列一个数据包裹类（DTO/Data Class）
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

    // UI 线程设置了新消息，但 TTS 线程看到的还是旧值
    private volatile TTSMessage currentTTSMessage = null;
    private volatile String currentSessionId = null;

    // MessageHandler 类 - 静态内部类避免内存泄漏，解析消息内容并安全地更新UI
    private static class MessageHandler {
        private static final int MAX_TEXT_LENGTH = 100; // 长文本阈值
        private final WeakReference<MainActivity> weakRef;
        private final WeakReference<SafeHandler> handlerRef;
        
        MessageHandler(MainActivity activity, SafeHandler handler) {
            this.weakRef = new WeakReference<>(activity);
            this.handlerRef = new WeakReference<>(handler);
        }
        
        public synchronized void reset() {// 重置消息处理
            SafeHandler handler = handlerRef.get();
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        }
        //reset() 和 processMessage() 的互斥锁，确保线程安全处理
        public synchronized void processMessage(String text) {//子线程处理消息
            if (text == null || text.isEmpty()) return;
            //拿到弱引用中的activity和handler实例
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
        
        private void updateDisplay(String emoji, String text) {// 更新显示
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
    
    private void showFirstTimeDialog() {// 显示首次使用提示
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
        
        super.onCreate(savedInstanceState);
        
        // 1. 先显示UI，让用户感知启动快
        setContentView(R.layout.activity_main);
        
        // 2. 初始化视图（必须在主线程）
        initViews();
        
        // 3. 预加载Native库（后台线程，避免阻塞UI）
        new Thread(() -> {
            long sdkStart = System.currentTimeMillis();

            // 预加载Native库（在后台线程触发类加载，避免阻塞主线程）
            try {
                Class.forName("vip.inode.demo.opusaudiodemo.utils.OpusUtils");
                Log.d("XiaoZhiPerf", "OpusUtils类预加载完成");
            } catch (ClassNotFoundException e) {
                Log.e("XiaoZhiPerf", "OpusUtils类加载失败", e);
            }

            Log.d("XiaoZhiPerf", "Native库预加载耗时: " + (System.currentTimeMillis() - sdkStart) + "ms");
        }).start();
        
        // 4. 延迟初始化非关键组件  "延迟"是 语义上的延迟 （相对于UI初始化）
        mainHandler = new SafeHandler(this);
        messageHandler = new MessageHandler(this, mainHandler);
        settingsManager = new SettingsManager(this);
        
        String deviceId = "c0:3e:ba:2e:d5:97";
        webSocketManager = WebSocketManager.getInstance(deviceId);
        webSocketManager.setListener(this);
        
        // 5. 延迟初始化线程池和音频（100ms后，避免阻塞UI）
        mainHandler.postDelayed(() -> {
            executorService = Executors.newSingleThreadExecutor();//通用异步任务处理 录音任务、其他非音频的后台操作
            audioExecutor = Executors.newSingleThreadExecutor();//音频专用处理 音频播放、音频组件初始化、音频资源释放
            
            // 延迟初始化音频组件（懒加载模式）
            initAudioComponents();
            
        }, 100);
        
        // 6. 检查权限（异步）
        if (!hasPermissions()) {
            requestPermissions();
        }
//        checkPermissions();
        
        // 7. 初始化各种监听器（点击事件、连接状态变化等）  这些监听器会在主线程中执行
        setupListeners();
        
        // 8. 显示首次启动提示（延迟，避免阻塞）
        mainHandler.postDelayed(this::showFirstTimeDialog, 200);
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d("XiaoZhiPerf", "MainActivity.onCreate 总耗时: " + duration + "ms");
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
            Intent intent = new Intent(MainActivity.this, MenuActivity.class);
            startActivity(intent);
        });
    }
    
    /**
     * 懒加载音频组件 - 第一次使用时才初始化
     */
    private synchronized void initAudioComponents() {
        if (audioTrack != null) return;
        
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            );
            Log.i("MainActivity", "AudioTrack最小缓冲区: " + minBufferSize + " 字节");
            
            audioTrack = createAudioTrack();
            
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                Log.i("MainActivity", "AudioTrack初始化成功");
            }
            
            opusUtils = OpusUtils.getInstance();
            encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
            decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
            decodedBuffer = new short[OPUS_FRAME_SIZE];
            recordBuffer = new short[OPUS_FRAME_SIZE];
            
        } catch (Exception e) {
            Log.e("MainActivity", "音频组件初始化失败", e);
        }
    }

    private void toggleConnection() {//切换连接状态
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
                Toast.makeText(this, getFriendlyErrorMessage(e.getMessage()), Toast.LENGTH_SHORT).show();
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

    private void sendMessage() {//发送消息
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
        addLog("WebSocket", "已连接");
        mainHandler.post(() -> {//更新UI，用于将任务切换到**主线程（UI线程）**执行。更新连接状态和按钮文本
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_connected)));
            connectButton.setText(R.string.disconnect);
        });
    }

    @Override
    public void onDisconnected() {
        Log.d("WebSocket", "连接断开");
        addLog("WebSocket", "已断开");
        mainHandler.post(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_disconnected)));
            connectButton.setText(R.string.connect);
            stopAudioAndReset();
            Toast.makeText(MainActivity.this, "连接已断开", Toast.LENGTH_SHORT).show();
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
        String userMsg = getFriendlyErrorMessage(error);
        mainHandler.post(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_error)));
            connectButton.setText(R.string.connect);
            Toast.makeText(MainActivity.this, userMsg, Toast.LENGTH_SHORT).show();
        });
    }

    private String getFriendlyErrorMessage(String error) {
        if (error == null) return "连接异常，请重试";

        String lower = error.toLowerCase();

        if (lower.contains("timeout") || lower.contains("超时")) {
            return "连接超时，请检查网络是否正常";
        }
        if (lower.contains("refused") || lower.contains("拒绝")) {
            return "服务器拒绝连接，请检查地址是否正确";
        }
        if (lower.contains("unreachable") || lower.contains("noroutetohost") || lower.contains("noroute")) {
            return "无法访问服务器，请检查网络或服务器地址";
        }
        if (lower.contains("resolve") || lower.contains("unknownhost") || lower.contains("unknown host") || lower.contains("地址")) {
            return "服务器地址无法解析，请检查地址是否正确";
        }
        if (lower.contains("ssl") || lower.contains("certificate") || lower.contains("handshake") || lower.contains("证书")) {
            return "安全连接失败，请检查服务器证书配置";
        }
        if (lower.contains("interrupt") || lower.contains("中断")) {
            return "连接被中断，请重试";
        }
        if (lower.contains("empty") || lower.contains("为空")) {
            return "服务器地址未配置，请在设置中填写";
        }

        return "连接失败: " + error;
    }

    @Override
    public void onMessage(String message) {//异步接受消息
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            String state = jsonMessage.optString("state");
            
            // 记录消息接收时间和处理
            Log.d("XiaoZhi", "收到消息: " + message + " 时间: " + System.nanoTime());
            
            if ("tts".equals(type)) {
                String sessionId = jsonMessage.optString("session_id");
                switch (state) {
                    case "start"://新的一个对话
                        // 只重置显示，不清除文本
                        messageHandler.reset();
                        audioExecutor.execute(this::initAudioTrack);
                        break;
                        
                    case "sentence_start"://新的一个句子
                        if (jsonMessage.has("text")) {
                            String text = jsonMessage.getString("text");
                            // 直接在UI线程更新，跳过所有延迟和队列
                            mainHandler.post(() -> {
                                try {
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
    public void onBinaryMessage(byte[] data) {//异步接受二进制音频消息
        if (data == null || data.length == 0) {
            return;
        }

        final byte[] audioData = data.clone();//克隆数据，避免修改原始数组
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
                int decodedSamples = opusUtils.decode(decoderHandle, audioData, decodedBuffer);//解码音频数据，返回解码后的样本数
                if (decodedSamples <= 0) {
                    return;
                }

                byte[] pcmData = new byte[decodedSamples * 2];//创建PCM数据数组，每个样本2字节
                // 将解码后的样本数据转换为PCM格式
                java.nio.ByteBuffer.wrap(pcmData).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(decodedBuffer, 0, decodedSamples);
                //最终播放PCM音频
                audioTrack.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_BLOCKING);//将PCM数据写入AudioTrack
            } catch (Exception e) {
                Log.e("XiaoZhi-Audio", "处理音频数据失败", e);
            }
        });
    }

    private AudioTrack createAudioTrack() {
        return new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)//设置音频使用场景为媒体播放
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)//设置音频内容类型为语音
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)//设置音频编码格式为Opus
                .setSampleRate(SAMPLE_RATE)//设置采样率为16000Hz
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)//设置音频通道为单声道
                .build())
            .setBufferSizeInBytes(PLAY_BUFFER_SIZE)//设置播放缓冲区大小为16000字节
            .setTransferMode(AudioTrack.MODE_STREAM)//设置音频传输模式为流式
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)//设置音频性能模式为低延迟
            .build();
    }

    private void initAudioTrack() {
        try {
            if (audioTrack != null) {
                audioTrack.stop();
            }

            if (audioTrack == null) {
                audioTrack = createAudioTrack();
            }

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                audioTrack = createAudioTrack();
            }

            audioTrack.play();
            isAudioTrackPlaying = true;
            isAudioTrackPaused = false;
            isPlaying = true;
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
        // 注意：不在 onPause 中清空所有回调，避免误删合法的延迟任务
        // 清理工作应放在 onDestroy 中
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
        
        // 注意：不要移除 WebSocket 监听器，因为 Voice 可能还在使用
        // 只断开当前 Activity 的连接请求
        // webSocketManager.removeListener();  // 删除这行！
        
        // 安全释放音频资源（移到后台线程）
        if (audioExecutor != null && !audioExecutor.isShutdown()) {
            audioExecutor.execute(() -> {//在后台线程中释放音频资源
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

}