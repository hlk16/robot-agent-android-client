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
import android.os.Looper;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import com.google.common.util.concurrent.ListenableFuture;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

public class VoiceCallActivity extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private VideoView videoView;
    //音频录制参数
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    //音频播放的缓冲区大小 (增加到4倍最小缓冲区大小)
    private static final int PLAY_BUFFER_SIZE = BUFFER_SIZE * 4;
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
    private boolean isRecording = false;
    private boolean isPlaying = false;

    //用于录制音频
    private AudioRecord audioRecord;
    //用于播放音频
    private AudioTrack audioTrack;
    private ExecutorService executorService;
    private ExecutorService audioExecutor;
    private Handler mainHandler;
    private WebSocketManager webSocketManager;
    private OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private short[] decodedBuffer;
    private short[] recordBuffer;
    private boolean isAuth = false;
    private ImageRecognitionManager imageRecognitionManager;
    private ConnectedThread connectedThread;
    public static char order='x';
    public static double roadDistance = 0.0; // 距离右侧车道线距离，用于蓝牙发送
    private boolean isVideoUnderstanding = false; // 标识是否正在进行视频理解
    
    // 回声消除相关
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    
    // 音频播放队列
    private BlockingQueue<byte[]> audioQueue;
    private volatile boolean isPlaybackThreadRunning = false;
    private ExecutorService playbackExecutor;
    
    // 音频焦点管理
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
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

        // 初始化图像识别管理器


//        initSDK();
        initViews();
        initWebSocket();
        initAudio();
        setupListeners();
        initImageRecognition();

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
        String deviceId = "3c:84:27:c8:45:10";
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
        } catch (Exception e) {
            Log.e("CameraPreview", "Error binding camera preview: " + e.getMessage());
            Toast.makeText(this, "无法启动前置摄像头", Toast.LENGTH_SHORT).show();
        }
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
            // 停止录音
            isRecording = false;
            
            // 停止播放线程
            stopPlaybackThread();
            
            // 释放回声消除器和噪声抑制器
            if (echoCanceler != null) {
                try {
                    echoCanceler.setEnabled(false);
                    echoCanceler.release();
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放回声消除器失败", e);
                } finally {
                    echoCanceler = null;
                }
            }
            if (noiseSuppressor != null) {
                try {
                    noiseSuppressor.setEnabled(false);
                    noiseSuppressor.release();
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放噪声抑制器失败", e);
                } finally {
                    noiseSuppressor = null;
                }
            }
            
            // 安全释放AudioRecord
            if (audioRecord != null) {
                try {
                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        audioRecord.stop();
                    }
                    audioRecord.release();
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放AudioRecord失败", e);
                } finally {
                    audioRecord = null;
                }
            }
            
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
            
            // 释放图像识别管理器
            if (imageRecognitionManager != null) {
                try {
                    imageRecognitionManager.release();
                } catch (Exception e) {
                    Log.e("VoiceCallActivity", "释放图像识别管理器失败", e);
                } finally {
                    imageRecognitionManager = null;
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
                    Toast.makeText(VoiceCallActivity.this, "未配置讯飞API，无法使用图像识别功能，请在设置中配置", Toast.LENGTH_SHORT).show();
                } else {
                    captureFrame();
                }
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

    //更新人声音波形
    private void updateUserWaveform(byte[] buffer) {
        if (userWaveformView != null) {
            float[] amplitudes = new float[buffer.length / 2];
            for (int i = 0; i < amplitudes.length; i++) {
                short sample = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
                amplitudes[i] = sample / 32768f;
            }
            runOnUiThread(() -> userWaveformView.setAmplitudes(amplitudes));
        }
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
        updateCallStatus("已连接");
        startCall();
    }

    @Override
    public void onDisconnected() {
        updateCallStatus("连接已断开");
        endCall();
    }

    @Override
    public void onError(String error) {
        updateCallStatus("错误: " + error);
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");

            switch (type) {
                case "stt":
                    // 处理语音识别结果
                    String recognizedText = jsonMessage.getString("text");
                    updateRecognizedText(recognizedText);
                    // 打断当前音频播放
                    stopCurrentAudio();
                    break;

                case "tts":
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
        if (data == null || data.length == 0) return;

        audioExecutor.execute(() -> {
            try {
                Log.d("AudioDebug", "收到音频数据长度: " + data.length + " bytes");
                int decodedSamples = opusUtils.decode(decoderHandle, data, decodedBuffer);
                Log.d("AudioDebug", "解码样本数: " + decodedSamples);

                if (decodedSamples > 0) {
                    byte[] pcmData = new byte[decodedSamples * 2];
                    for (int i = 0; i < decodedSamples; i++) {
                        short sample = decodedBuffer[i];
                        pcmData[i * 2] = (byte) (sample & 0xff);
                        pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                    }
                    
                    Log.d("AudioDebug", "PCM数据长度: " + pcmData.length + " bytes");
                    
                    // 将音频数据加入播放队列
                    boolean added = audioQueue.offer(pcmData);
                    Log.d("AudioDebug", "音频数据入队: " + added + ", 队列大小: " + audioQueue.size());
                    
                    if (!added) {
                        Log.w("AudioDebug", "音频队列已满，丢弃数据");
                    }

                    // 更新波形
                    float[] amplitudes = new float[decodedSamples];
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
        
        // 检查线程池状态
        if (playbackExecutor == null || playbackExecutor.isShutdown() || playbackExecutor.isTerminated()) {
            Log.w("VoiceCallActivity", "PlaybackExecutor已关闭，无法启动播放线程");
            return;
        }
        
        isPlaybackThreadRunning = true;
        playbackExecutor.execute(() -> {
            Log.d("PlaybackThread", "播放线程启动");
            
            while (isPlaybackThreadRunning) {
                try {
                    // 使用poll方法避免无限阻塞
                    byte[] audioData = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                    
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
        audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        Log.d("AudioFocus", "获得音频焦点");
                        if (audioTrack != null && !isPlaying) {
                            audioTrack.play();
                            isPlaying = true;
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        Log.d("AudioFocus", "失去音频焦点");
                        if (audioTrack != null && isPlaying) {
                            audioTrack.pause();
                            isPlaying = false;
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        Log.d("AudioFocus", "音频焦点降低音量");
                        // 可以选择降低音量而不是暂停
                        break;
                }
            }
        };
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
        super.onDestroy();
        if (webSocketManager != null) {
            try {
                JSONObject endMessage = new JSONObject();
                endMessage.put("type", "end");
                webSocketManager.sendMessage(endMessage.toString());
            } catch (Exception e) {
                Log.e("VoiceCall", "发送结束消息失败", e);
            }
            webSocketManager.disconnect();
            webSocketManager.removeListener();  // 移除监听，防止内存泄漏
        }
        endCall();
        if (encoderHandle != 0) {
            opusUtils.destroyEncoder(encoderHandle);
            encoderHandle = 0;
        }
        if (decoderHandle != 0) {
            opusUtils.destroyDecoder(decoderHandle);
            decoderHandle = 0;
        }
        if (imageRecognitionManager != null) {
            imageRecognitionManager.release();
            imageRecognitionManager = null;
        }
        executorService.shutdown();
        audioExecutor.shutdown();
        
        // 关闭播放线程池
        if (playbackExecutor != null) {
            playbackExecutor.shutdown();
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
            Log.w("VoiceCallActivity", "讯飞API未配置，图像识别功能不可用");
            return;
        }
        
        // API已配置，初始化图像识别管理器
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
                                Log.e("VoiceCallActivity", "发送识别消息失败", e);
                            }
                        }
                        // Toast.makeText(VoiceCallActivity.this, "识别结果: " + content, Toast.LENGTH_SHORT).show(); // 隐藏识别结果Toast
                        Log.d("ImageRecognition", "识别结果: " + content);
                    });
                }

                @Override
                public void onRecognitionError(String errorMessage) {
                    runOnUiThread(() -> {
                        Toast.makeText(VoiceCallActivity.this, "识别失败: " + errorMessage, Toast.LENGTH_SHORT).show();
                        Log.e("ImageRecognition", "识别失败: " + errorMessage);
                    });
                }
            });
        } catch (Exception e) {
            Log.e("VoiceCallActivity", "初始化图像识别管理器失败", e);
            imageRecognitionManager = null;
        }
    }
    private void captureFrame() {
        // 检查图像识别管理器是否已初始化
        if (imageRecognitionManager == null) {
            Toast.makeText(VoiceCallActivity.this, "未配置讯飞API，无法使用图像识别功能", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 检查相机是否可用
        if (cameraProvider == null || !isPreviewStarted) {
            Toast.makeText(VoiceCallActivity.this, "请先开启摄像头", Toast.LENGTH_SHORT).show();
            return;
        }

        // 设置视频理解状态为true，表示这是主动的视频理解请求
        isVideoUnderstanding = true;
        Toast.makeText(VoiceCallActivity.this, "正在识别图像...", Toast.LENGTH_SHORT).show();
    }
}