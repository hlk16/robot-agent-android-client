package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.activities.webrtc.SignalingClient;
import com.lhht.xiaozhi.activities.webrtc.WebRTCManager;
import com.lhht.xiaozhi.managers.NavigationServiceManager;
import com.lhht.xiaozhi.managers.DataManager;
import com.lhht.xiaozhi.services.CameraDetectionService;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ChatActivity extends AppCompatActivity implements SignalingClient.SignalingListener {

    private static final String TAG = "ChatActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    private EditText etUserId, etNickname, etTargetUser, etMessage, etRoomId;
    private TextView tvStatus, tvOnlineUsers, tvMessages;
    private Button btnConnect, btnSend, btnGetUsers, btnPing, btnClear;
    private Button btnForward, btnBackward, btnLeft, btnRight, btnStop;
    private Button btnNavigation; // 自动导航按钮
    private Button btnJoinRoom, btnCameraToggle, btnCall, btnEndCall;
    private SurfaceViewRenderer surfaceViewRemote, surfaceViewLocal;
    private LinearLayout layoutNoRemoteSignal, layoutNoLocalSignal;
    
    // 导航信息显示UI元素
    private TextView tvDestinationDistance, tvCurrentDirection, tvNextTurnDistance;

    private WebSocket webSocket;
    private OkHttpClient client;
    private Handler mainHandler;
    private boolean isConnected = false;
    
    // WebRTC相关变量
    private SignalingClient signalingClient;
    private WebRTCManager webRTCManager;
    private String clientId;
    private String remoteClientId;
    private boolean isWebRTCConnected = false;
    private boolean isInCall = false;
    private boolean isCameraEnabled = false; // 摄像头状态，默认关闭
    
    // 导航服务相关
    private NavigationServiceManager navigationServiceManager;
    private Handler navigationUpdateHandler;
    private Runnable navigationUpdateRunnable;
    private boolean isNavigationUpdating = false;
    private boolean pendingNavigationStart = false;
    
    // OpenCV检测服务相关
    private DataManager dataManager;
    private boolean isDetectionServiceRunning = false;
    private Handler detectionUpdateHandler;
    private Runnable detectionUpdateRunnable;
    private TextView tvDetectionStatus, tvDetectionData;

    // 真机测试配置 - 根据您的网络信息配置
    private static final String SERVER_URL = "ws://192.168.0.102:8009/ws/chat/"; // 真机测试地址
    private static final String WEBRTC_SERVER_URL = "ws://192.168.0.102:8009/ws/webrtc/"; // WebRTC专用端点
    // 模拟器测试请使用: "ws://10.0.2.2:8000/ws/chat/"
    // 当前配置基于您的IPv4地址: 192.168.0.102

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        initViews();
        initWebSocket();
        iniLoadOpenCV();
        setupClickListeners();
        checkPermissions();

        mainHandler = new Handler(Looper.getMainLooper());
        
        // 生成唯一的客户端ID
        clientId = "client_" + UUID.randomUUID().toString().substring(0, 8);
        Log.d(TAG, "客户端ID: " + clientId);
        
        // 初始化导航服务管理器
        initNavigationService();
        
        // 初始化导航信息更新处理器
        initNavigationUpdateHandler();
        
        // 初始化检测数据管理器
        dataManager = DataManager.getInstance(this);
        
        // 初始化检测信息更新处理器
        initDetectionUpdateHandler();
        
        // 预启动导航服务以减少延迟
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            navigationServiceManager.startAndBindService();
            Log.d(TAG, "预启动导航服务");
        }
    }

    private void initViews() {
        etUserId = findViewById(R.id.etUserId);
        etNickname = findViewById(R.id.etNickname);
        etTargetUser = findViewById(R.id.etTargetUser);
        etMessage = findViewById(R.id.etMessage);
        etRoomId = findViewById(R.id.etRoomId);

        tvStatus = findViewById(R.id.tvStatus);
        tvOnlineUsers = findViewById(R.id.tvOnlineUsers);
        tvMessages = findViewById(R.id.tvMessages);

        btnConnect = findViewById(R.id.btnConnect);
        btnSend = findViewById(R.id.btnSend);
        btnGetUsers = findViewById(R.id.btnGetUsers);
        btnPing = findViewById(R.id.btnPing);
        btnClear = findViewById(R.id.btnClear);
        
        // 快捷控制按钮
        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnStop = findViewById(R.id.btnStop);
        btnNavigation = findViewById(R.id.NavButten); // 自动导航按钮
        
        // WebRTC相关按钮
        btnJoinRoom = findViewById(R.id.btnJoinRoom);
        btnCameraToggle = findViewById(R.id.btnCameraToggle);
        btnCall = findViewById(R.id.callbutton);
        btnEndCall = findViewById(R.id.btnEndCall);
        
        // 视频预览控件 - 转换为SurfaceViewRenderer
        surfaceViewRemote = findViewById(R.id.surfaceViewRemote);
        surfaceViewLocal = findViewById(R.id.surfaceViewLocal);
        layoutNoRemoteSignal = findViewById(R.id.layoutNoRemoteSignal);
        layoutNoLocalSignal = findViewById(R.id.layoutNoLocalSignal);
        
        // 导航信息显示UI元素
        tvDestinationDistance = findViewById(R.id.tvDestinationDistance);
        tvCurrentDirection = findViewById(R.id.tvCurrentDirection);
        tvNextTurnDistance = findViewById(R.id.tvNextTurnDistance);
        
        // 检测信息显示UI元素
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus);
        tvDetectionData = findViewById(R.id.tvDetectionData);
    }

    private void initWebSocket() {
        client = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private void setupClickListeners() {
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnect();
            } else {
                connect();
            }
        });

        btnSend.setOnClickListener(v -> sendChatMessage());
        btnGetUsers.setOnClickListener(v -> getOnlineUsers());
        btnPing.setOnClickListener(v -> sendPing());
        btnClear.setOnClickListener(v -> clearMessages());
        
        // 快捷控制按钮点击事件
        btnForward.setOnClickListener(v -> sendQuickCommand("前进"));
        btnBackward.setOnClickListener(v -> sendQuickCommand("后退"));
        btnLeft.setOnClickListener(v -> sendQuickCommand("左转"));
        btnRight.setOnClickListener(v -> sendQuickCommand("右转"));
        btnStop.setOnClickListener(v -> sendQuickCommand("停止"));
        btnNavigation.setOnClickListener(v -> sendQuickCommand("自动导航")); // 自动导航按钮点击事件
        
        // WebRTC相关按钮点击事件
        btnJoinRoom.setOnClickListener(v -> {
            if (!isWebRTCConnected) {
                connectToWebRTCServer();
            } else {
                disconnectFromWebRTCServer();
            }
        });
        
        btnCall.setOnClickListener(v -> {
            if (!isInCall) {
                startCall();
            }
        });
        
        btnEndCall.setOnClickListener(v -> {
            if (isInCall) {
                endCall();
            }
        });
        
        btnCameraToggle.setOnClickListener(v -> {
            toggleCamera();
        });
    }

    private void connect() {
        String userId = etUserId.getText().toString().trim();
        String nickname = etNickname.getText().toString().trim();

        if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(nickname)) {
             Toast.makeText(this, "请输入用户ID和昵称", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String url = SERVER_URL + userId + "?nickname=" + nickname;
            addMessage("系统", "正在连接到: " + url, getCurrentTime());
            Request request = new Request.Builder().url(url).build();

            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    mainHandler.post(() -> {
                        isConnected = true;
                        updateConnectionStatus("已连接", true);
                        addMessage("系统", "连接成功", getCurrentTime());
                    });
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    mainHandler.post(() -> handleMessage(text));
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    mainHandler.post(() -> handleMessage(bytes.utf8()));
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    mainHandler.post(() -> {
                        isConnected = false;
                        updateConnectionStatus("连接关闭中...", false);
                        addMessage("系统", "连接关闭: " + reason, getCurrentTime());
                    });
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    mainHandler.post(() -> {
                        isConnected = false;
                        updateConnectionStatus("已断开", false);
                        addMessage("系统", "连接已断开", getCurrentTime());
                    });
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    mainHandler.post(() -> {
                        isConnected = false;
                        updateConnectionStatus("连接失败", false);
                        addMessage("系统", "连接失败: " + t.getMessage(), getCurrentTime());
                    });
                }
            });
        } catch (Exception e) {
            addMessage("系统", "连接异常: " + e.getMessage(), getCurrentTime());
             Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "用户主动断开");
            webSocket = null;
        }
        isConnected = false;
        updateConnectionStatus("未连接", false);
    }

    private void updateConnectionStatus(String status, boolean connected) {
        tvStatus.setText(status);
        tvStatus.setTextColor(connected ?
                ContextCompat.getColor(this, android.R.color.holo_green_dark) :
                ContextCompat.getColor(this, android.R.color.holo_red_dark));

        // 更新状态指示器
        View statusIndicator = findViewById(R.id.statusIndicator);
        if (statusIndicator != null) {
            statusIndicator.setBackgroundResource(connected ?
                    R.drawable.status_indicator_green :
                    R.drawable.status_indicator_red);
        }

        btnConnect.setText(connected ? "断开" : "连接");
        btnSend.setEnabled(connected);
        btnGetUsers.setEnabled(connected);
        btnPing.setEnabled(connected);
    }

    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");

            switch (type) {
                case "system":
                    handleSystemMessage(json);
                    break;
                case "chat":
                    handleChatMessage(json);
                    break;
                case "message_sent":
                    handleMessageSent(json);
                    break;
                case "users_list":
                    handleUsersList(json);
                    break;
                case "pong":
                    handlePong(json);
                    break;
                default:
                    addMessage("未知消息", message, getCurrentTime());
                    break;
            }
        } catch (JSONException e) {
            addMessage("解析错误", message, getCurrentTime());
        }
    }

    private void handleSystemMessage(JSONObject json) throws JSONException {
        String message = json.getString("message");
        String timestamp = json.optString("timestamp", getCurrentTime());
        addMessage("系统", message, timestamp);
    }

    private void handleChatMessage(JSONObject json) throws JSONException {
        String fromUserId = json.getString("from_user_id");
        String fromNickname = json.getString("from_nickname");
        String content = json.getString("content");
        String timestamp = json.optString("timestamp", getCurrentTime());

        // 检测导航命令
        if (content != null && (content.contains("自动导航") || content.contains("自动导航") ||
            content.contains("[控制指令] 自动导航") || content.contains("[控制指令] 自动导航"))) {
            // 显示Toast提示
            Toast.makeText(this, "收到导航指令：开始导航", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "检测到导航命令: " + content);
            
            // 启动导航服务并导航到驿站
            startNavigationToYizhan();
            
            // 启动OpenCV图像检测服务
            startCameraDetectionService();
            
        }

        // 检测其他命令
        if (content != null && content.contains("前进")) {
            VoiceCallActivity.order = 'a';
            Toast.makeText(this, "收到前进指令", Toast.LENGTH_SHORT).show();
        } else if (content != null && content.contains("后退")) {
            VoiceCallActivity.order = 'b';
            Toast.makeText(this, "收到后退指令", Toast.LENGTH_SHORT).show();
        } else if (content != null && content.contains("左转")) {
            VoiceCallActivity.order = 'c';
            Toast.makeText(this, "收到左转指令", Toast.LENGTH_SHORT).show();
        } else if (content != null && content.contains("右转")) {
            VoiceCallActivity.order = 'd';
            Toast.makeText(this, "收到右转指令", Toast.LENGTH_SHORT).show();
        }else if (content != null && content.contains("停下来")){
            VoiceCallActivity.order = 'e';
             Toast.makeText(this, "收到停止指令", Toast.LENGTH_SHORT).show();
        }



        addMessage(fromNickname + "(" + fromUserId + ")", content, timestamp);
    }

    private void handleMessageSent(JSONObject json) throws JSONException {
        String message = json.getString("message");
        addMessage("发送确认", message, getCurrentTime());
    }

    private void handleUsersList(JSONObject json) throws JSONException {
        JSONArray users = json.getJSONArray("users");
        StringBuilder userList = new StringBuilder();

        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.getJSONObject(i);
            String userId = user.getString("user_id");
            String nickname = user.getString("nickname");
            userList.append(nickname).append("(").append(userId).append(")\n");
        }

        if (userList.length() == 0) {
            tvOnlineUsers.setText("暂无在线用户");
        } else {
            tvOnlineUsers.setText(userList.toString());
        }
    }

    private void handlePong(JSONObject json) throws JSONException {
        String timestamp = json.optString("timestamp", getCurrentTime());
        addMessage("心跳响应", "服务器响应时间: " + timestamp, getCurrentTime());
    }

    private void sendChatMessage() {
        String targetUser = etTargetUser.getText().toString().trim();
        String content = etMessage.getText().toString().trim();

        if (TextUtils.isEmpty(content)) {
             Toast.makeText(this, "请输入消息内容", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isConnected || webSocket == null) {
             Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject message = new JSONObject();
            message.put("type", "chat");
            message.put("content", content);
            if (!TextUtils.isEmpty(targetUser)) {
                message.put("target_user_id", targetUser);
            }

            webSocket.send(message.toString());
            etMessage.setText("");
            addMessage("我", content, getCurrentTime());
        } catch (JSONException e) {
             Toast.makeText(this, "发送消息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 发送快捷控制指令
     * @param command 控制指令（前进、后退、左转、右转、停止）
     */
    private void sendQuickCommand(String command) {
        if (!isConnected || webSocket == null) {
             Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String targetUser = etTargetUser.getText().toString().trim();
            
            JSONObject message = new JSONObject();
            message.put("type", "chat");
            message.put("content", "[控制指令] " + command);
            
            // 添加控制指令标识
            message.put("command", command);
            message.put("is_control", true);
            
            // 与文本消息使用相同的发送逻辑
            if (!TextUtils.isEmpty(targetUser)) {
                message.put("target_user_id", targetUser);
                addMessage("我[控制]", "发送给 " + targetUser + ": " + command, getCurrentTime());
            } else {
                addMessage("我[控制]", command, getCurrentTime());
            }

            webSocket.send(message.toString());
        } catch (JSONException e) {
             Toast.makeText(this, "发送控制指令失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void getOnlineUsers() {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "get_users");

            if (webSocket != null) {
                webSocket.send(message.toString());
            }
        } catch (JSONException e) {
             Toast.makeText(this, "请求格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendPing() {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "ping");

            if (webSocket != null) {
                webSocket.send(message.toString());
                addMessage("心跳", "发送心跳检测", getCurrentTime());
            }
        } catch (JSONException e) {
             Toast.makeText(this, "心跳格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearMessages() {
        tvMessages.setText("");
    }

    private void addMessage(String sender, String content, String timestamp) {
        String currentText = tvMessages.getText().toString();
        String newMessage = String.format("[%s] %s: %s\n", timestamp, sender, content);
        tvMessages.setText(currentText + newMessage);

        // 滚动到底部
        tvMessages.post(() -> {
            View parent = (View) tvMessages.getParent();
            if (parent instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    // WebRTC相关方法开始
    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            initializeWebRTC();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            StringBuilder deniedPermissions = new StringBuilder();
            
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) ||
                        permissions[i].equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                        deniedPermissions.append("定位权限 ");
                    } else if (permissions[i].equals(Manifest.permission.CAMERA)) {
                        deniedPermissions.append("摄像头权限 ");
                    } else if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)) {
                        deniedPermissions.append("麦克风权限 ");
                    }
                }
            }

            if (allGranted) {
                initializeWebRTC();
                Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                String message = "缺少权限: " + deniedPermissions.toString() + "\n部分功能可能无法使用";
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                // 即使部分权限被拒绝，也尝试初始化WebRTC（如果有摄像头和麦克风权限）
                boolean hasWebRTCPermissions = 
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (hasWebRTCPermissions) {
                    initializeWebRTC();
                }
            }
        }
    }

    private void initializeWebRTC() {
        runOnUiThread(() -> {
            try {
                Log.d(TAG, "Initializing WebRTC on UI thread");
                
                // 检查视图是否为空
                if (surfaceViewLocal == null || surfaceViewRemote == null) {
                    Log.e(TAG, "SurfaceView is null - local: " + (surfaceViewLocal == null) + ", remote: " + (surfaceViewRemote == null));
                    return;
                }
                
                // 显示无信号提示
                if (layoutNoLocalSignal != null) {
                    layoutNoLocalSignal.setVisibility(View.VISIBLE);
                }
                if (layoutNoRemoteSignal != null) {
                    layoutNoRemoteSignal.setVisibility(View.VISIBLE);
                }
                
                webRTCManager = new WebRTCManager(this, webRTCListener);
                webRTCManager.initializeViews(surfaceViewLocal, surfaceViewRemote);
                webRTCManager.startLocalVideo();
                
                Log.d(TAG, "WebRTC initialized successfully");
                // Toast.makeText(this, "WebRTC 初始化成功", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error initializing WebRTC: " + e.getMessage(), e);
                 Toast.makeText(this, "WebRTC 初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void connectToWebRTCServer() {
        try {
            String roomId = etRoomId.getText().toString().trim();
            if (roomId.isEmpty()) {
                roomId = "test_room"; // 默认房间号
            }
            
            // 构建包含客户端ID和nickname的WebRTC端点URL
            String nickname = etNickname.getText().toString().trim();
            if (nickname.isEmpty()) {
                nickname = "Android客户端";
            }
            String webrtcUrl = WEBRTC_SERVER_URL + clientId + "?nickname=" + nickname;
            signalingClient = new SignalingClient(new URI(webrtcUrl), this);
            signalingClient.connect();
        } catch (Exception e) {
             Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "连接WebRTC服务器失败", e);
        }
    }

    private void disconnectFromWebRTCServer() {
        if (signalingClient != null) {
            signalingClient.close();
        }
        isWebRTCConnected = false;
        updateWebRTCButtonStates();
    }

    private void updateWebRTCButtonStates() {
        btnJoinRoom.setText(isWebRTCConnected ? "离开房间" : "加入房间");
        btnCall.setEnabled(isWebRTCConnected && !isInCall);
        btnEndCall.setEnabled(isInCall);
        btnCameraToggle.setEnabled(isWebRTCConnected);
        
        // 更新摄像头按钮文本
        if (isCameraEnabled) {
            btnCameraToggle.setText("关闭摄像头");
        } else {
            btnCameraToggle.setText("开启摄像头");
        }
    }
    
    private void toggleCamera() {
        if (webRTCManager == null) {
             Toast.makeText(this, "WebRTC未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (isCameraEnabled) {
            // 关闭摄像头
            webRTCManager.disableCamera();
            isCameraEnabled = false;
            layoutNoLocalSignal.setVisibility(View.VISIBLE);
            // Toast.makeText(this, "摄像头已关闭", Toast.LENGTH_SHORT).show();
        } else {
            // 开启摄像头
            webRTCManager.enableCamera();
            isCameraEnabled = true;
            layoutNoLocalSignal.setVisibility(View.GONE);
            // Toast.makeText(this, "摄像头已开启", Toast.LENGTH_SHORT).show();
        }
        
        updateWebRTCButtonStates();
    }

    private void startCall() {
        if (webRTCManager != null && isWebRTCConnected) {
            // Toast.makeText(this, "开始呼叫", Toast.LENGTH_SHORT).show();
            webRTCManager.createPeerConnection();
            // 创建PeerConnection后，添加本地媒体流
            webRTCManager.addLocalStreamToPeerConnection();
            webRTCManager.createOffer();
            isInCall = true;
            updateWebRTCButtonStates();
        }
    }

    private void endCall() {
        if (webRTCManager != null) {
            webRTCManager.close();
        }
        if (signalingClient != null && signalingClient.isOpen()) {
            signalingClient.sendEndCall(remoteClientId);
        }
        
        // 显示无信号提示
        if (layoutNoLocalSignal != null) {
            layoutNoLocalSignal.setVisibility(View.VISIBLE);
        }
        if (layoutNoRemoteSignal != null) {
            layoutNoRemoteSignal.setVisibility(View.VISIBLE);
        }
        
        // 重新初始化WebRTC
        initializeWebRTC();
        
        isInCall = false;
        remoteClientId = null;
        isCameraEnabled = false; // 重置摄像头状态
        updateWebRTCButtonStates();
        
        // Toast.makeText(this, "通话已结束", Toast.LENGTH_SHORT).show();
    }

    private void handleSignalingMessage(String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            Log.d(TAG, "收到WebRTC消息类型: " + type);

            switch (type) {
                case "user_joined":
                    String joinedClientId = jsonMessage.getString("clientId");
                    if (!joinedClientId.equals(clientId)) {
                        remoteClientId = joinedClientId;
                         Toast.makeText(this, "用户 " + joinedClientId + " 加入房间", Toast.LENGTH_SHORT).show();
                    }
                    break;

                case "offer":
                    handleOffer(jsonMessage);
                    break;

                case "answer":
                    handleAnswer(jsonMessage);
                    break;

                case "ice-candidate":
                    handleIceCandidate(jsonMessage);
                    break;

                case "user_left":
                    String leftClientId = jsonMessage.getString("clientId");
                     Toast.makeText(this, "用户 " + leftClientId + " 离开房间", Toast.LENGTH_SHORT).show();
                    if (isInCall) {
                        endCall();
                    }
                    break;
                    
                case "end_call":
                    String endCallClientId = jsonMessage.getString("senderId");
                     Toast.makeText(this, "用户 " + endCallClientId + " 结束了通话", Toast.LENGTH_SHORT).show();
                    if (isInCall) {
                        endCall();
                    }
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "处理WebRTC消息失败: " + e.getMessage());
        }
    }

    private void handleOffer(JSONObject message) {
        try {
            String sdp = message.getString("sdp");
            String fromClientId = message.getString("senderId");

            // 设置远程客户端ID
            remoteClientId = fromClientId;

            runOnUiThread(() -> {
                if (webRTCManager != null) {
                    webRTCManager.createPeerConnection();
                    // 关键修复：在设置远程描述之前添加本地媒体流
                    webRTCManager.addLocalStreamToPeerConnection();
                    SessionDescription remoteSdp = new SessionDescription(SessionDescription.Type.OFFER, sdp);
                    webRTCManager.setRemoteDescription(remoteSdp);
                    webRTCManager.createAnswer();
                    isInCall = true;
                    updateWebRTCButtonStates();
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "处理offer失败: " + e.getMessage());
        }
    }

    private void handleAnswer(JSONObject message) {
        try {
            String sdp = message.getString("sdp");
            String fromClientId = message.getString("senderId");

            // 设置远程客户端ID（如果还没有设置）
            if (remoteClientId == null) {
                remoteClientId = fromClientId;
            }

            runOnUiThread(() -> {
                if (webRTCManager != null) {
                    SessionDescription remoteSdp = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    webRTCManager.setRemoteDescription(remoteSdp);
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "处理answer失败: " + e.getMessage());
        }
    }

    private void handleIceCandidate(JSONObject message) {
        try {
            String candidate = message.getString("candidate");
            String sdpMid = message.getString("sdpMid");
            int sdpMLineIndex = message.getInt("sdpMLineIndex");

            runOnUiThread(() -> {
                if (webRTCManager != null) {
                    IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
                    webRTCManager.addIceCandidate(iceCandidate);
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "处理ICE候选失败: " + e.getMessage());
        }
    }

    // SignalingClient.SignalingListener接口实现
    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            isWebRTCConnected = true;
            updateWebRTCButtonStates();
            String roomId = etRoomId.getText().toString().trim();
            if (roomId.isEmpty()) {
                roomId = "test_room"; // 默认房间号
            }
            signalingClient.sendJoinRoom(roomId, clientId);
            // Toast.makeText(ChatActivity.this, "连接成功，加入房间: " + roomId, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            isWebRTCConnected = false;
            updateWebRTCButtonStates();
            // Toast.makeText(ChatActivity.this, "WebRTC连接断开", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onMessageReceived(String message) {
        runOnUiThread(() -> handleSignalingMessage(message));
    }

    @Override
    public void onError(Exception ex) {
        runOnUiThread(() -> {
             Toast.makeText(ChatActivity.this, "WebRTC错误: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "WebRTC错误", ex);
        });
    }

    // WebRTC监听器实现
    public void onLocalDescription(SessionDescription sdp) {
        if (signalingClient != null && signalingClient.isOpen() && remoteClientId != null) {
            if (sdp.type == SessionDescription.Type.OFFER) {
                signalingClient.sendOffer(sdp.description, remoteClientId);
            } else if (sdp.type == SessionDescription.Type.ANSWER) {
                signalingClient.sendAnswer(sdp.description, remoteClientId);
            }
        }
    }

    public void onIceCandidate(IceCandidate candidate) {
        if (signalingClient != null && signalingClient.isOpen() && remoteClientId != null) {
            signalingClient.sendIceCandidate(
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex,
                    remoteClientId
            );
        }
    }

    // WebRTC连接状态回调（与信令回调分开）
    public void onWebRTCConnected() {
        runOnUiThread(() -> {
            // Toast.makeText(this, "视频通话已连接", Toast.LENGTH_SHORT).show();
        });
    }

    public void onWebRTCDisconnected() {
        runOnUiThread(() -> {
            // Toast.makeText(this, "视频通话已断开", Toast.LENGTH_SHORT).show();
            if (isInCall) {
                endCall();
            }
        });
    }

    public void onWebRTCError(String error) {
        runOnUiThread(() -> {
             Toast.makeText(this, "WebRTC错误: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    // WebRTCManager.WebRTCListener接口实现
    private WebRTCManager.WebRTCListener webRTCListener = new WebRTCManager.WebRTCListener() {
        @Override
        public void onLocalDescription(SessionDescription description) {
            ChatActivity.this.onLocalDescription(description);
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            ChatActivity.this.onIceCandidate(candidate);
        }

        @Override
        public void onConnected() {
            onWebRTCConnected();
        }

        @Override
        public void onDisconnected() {
            onWebRTCDisconnected();
        }

        @Override
        public void onError(String error) {
            onWebRTCError(error);
        }

        @Override
        public void onLocalStreamReady() {
            runOnUiThread(() -> {
                Log.d(TAG, "本地媒体流创建成功");
                
                // 默认禁用摄像头
                if (webRTCManager != null) {
                    webRTCManager.disableCamera();
                    isCameraEnabled = false;
                }
                
                // 显示本地无信号提示，隐藏本地视频（因为摄像头默认关闭）
                if (layoutNoLocalSignal != null) {
                    layoutNoLocalSignal.setVisibility(View.VISIBLE);
                }
                if (surfaceViewLocal != null) {
                    surfaceViewLocal.setVisibility(View.VISIBLE);
                }
                
                // 更新按钮状态
                updateWebRTCButtonStates();
                
                // Toast.makeText(ChatActivity.this, "本地视频已准备就绪（摄像头默认关闭）", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onRemoteStreamReady() {
            runOnUiThread(() -> {
                Log.d(TAG, "远程媒体流准备就绪");
                
                // 隐藏远程无信号提示，显示远程视频
                if (layoutNoRemoteSignal != null) {
                    layoutNoRemoteSignal.setVisibility(View.GONE);
                }
                if (surfaceViewRemote != null) {
                    surfaceViewRemote.setVisibility(View.VISIBLE);
                }
                
                // Toast.makeText(ChatActivity.this, "远程视频已连接", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onIceGatheringComplete() {
            runOnUiThread(() -> {
                Log.d(TAG, "ICE候选者收集完成");
                // Toast.makeText(ChatActivity.this, "网络连接准备就绪", Toast.LENGTH_SHORT).show();
            });
        }
    };
    // WebRTC相关方法结束

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
        
        // 清理WebRTC资源
        if (webRTCManager != null) {
            webRTCManager.close();
        }
        if (signalingClient != null) {
            signalingClient.close();
        }
        
        // 清理导航服务
        stopNavigationUpdates();
        if (navigationServiceManager != null) {
            navigationServiceManager.cleanup();
        }
        
        // 停止检测服务
        if (isDetectionServiceRunning) {
            stopCameraDetectionService();
        }
        
        // 清理检测更新处理器
        stopDetectionUpdates();
    }
    
    // ==================== 导航相关方法 ====================
    
    /**
     * 初始化导航服务管理器
     */
    private void initNavigationService() {
        navigationServiceManager = NavigationServiceManager.getInstance(this);
        navigationServiceManager.setServiceConnectionListener(new NavigationServiceManager.ServiceConnectionListener() {
            @Override
            public void onServiceConnected() {
                runOnUiThread(() -> {
                    Log.d(TAG, "导航服务已连接");
                    Toast.makeText(ChatActivity.this, "导航服务已连接", Toast.LENGTH_SHORT).show();
                    
                    // 如果有待启动的导航，现在启动
                    if (pendingNavigationStart) {
                        pendingNavigationStart = false;
                        startNavigationToDestination();
                    }
                });
            }
            
            @Override
            public void onServiceDisconnected() {
                runOnUiThread(() -> {
                    Log.d(TAG, "导航服务已断开");
                    stopNavigationUpdates();
                    clearNavigationInfo();
                });
            }
        });
    }
    
    /**
     * 初始化导航信息更新处理器
     */
    private void initNavigationUpdateHandler() {
        navigationUpdateHandler = new Handler(Looper.getMainLooper());
        navigationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNavigationInfo();
                if (isNavigationUpdating) {
                    navigationUpdateHandler.postDelayed(this, 2000); // 每2秒更新一次
                }
            }
        };
    }
    
    /**
     * 启动导航到驿站
     */
    private void startNavigationToYizhan() {
        Log.d(TAG, "开始启动导航到驿站");
        
        // 检查定位权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要定位权限才能启动导航", Toast.LENGTH_LONG).show();
            
            // 发送权限缺失消息到聊天
            etMessage.setText("导航启动失败：缺少定位权限，请在设置中授予定位权限后重试");
            sendChatMessage();
            etMessage.setText("");
            
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    PERMISSION_REQUEST_CODE);
            return;
        }
        
        // 启动并绑定导航服务
        if (!navigationServiceManager.isServiceConnected()) {
            // 设置待启动标志，等待服务连接回调
            pendingNavigationStart = true;
            navigationServiceManager.startAndBindService();
            Toast.makeText(this, "正在启动导航服务...", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "导航服务未连接，正在启动服务并设置待启动标志");
            
            // 设置超时检查，如果5秒后服务仍未连接，则提示失败
            mainHandler.postDelayed(() -> {
                if (pendingNavigationStart && !navigationServiceManager.isServiceConnected()) {
                    pendingNavigationStart = false;
                    Toast.makeText(this, "导航服务启动超时，请重试", Toast.LENGTH_LONG).show();
                    
                    // 发送服务启动超时消息到聊天
                    etMessage.setText("导航服务启动超时，请检查网络连接后重试");
                    sendChatMessage();
                    etMessage.setText("");
                    
                    Log.w(TAG, "导航服务启动超时");
                }
            }, 5000);
        } else {
            // 服务已连接，直接启动导航
            startNavigationToDestination();
        }
    }
    
    /**
     * 启动导航到目的地
     */
    private void startNavigationToDestination() {
        Log.d(TAG, "准备启动导航到驿站，服务连接状态: " + navigationServiceManager.isServiceConnected());
        
        if (navigationServiceManager.startNavigationToDestination("驿站")) {
            Toast.makeText(this, "开始导航到驿站", Toast.LENGTH_SHORT).show();
            
            // 发送导航启动成功消息到聊天
            etMessage.setText("导航启动成功，正在前往驿站");
            sendChatMessage();
            etMessage.setText("");
            
            // 延迟启动更新，给导航服务一些时间初始化
            mainHandler.postDelayed(() -> {
                startNavigationUpdates();
                Log.d(TAG, "延迟启动导航信息更新");
            }, 3000); // 延迟3秒
            
            Log.d(TAG, "导航已启动到驿站");
        } else {
            Toast.makeText(this, "启动导航失败，请检查服务状态", Toast.LENGTH_SHORT).show();
            
            // 发送导航启动失败消息到聊天
            etMessage.setText("导航启动失败，请检查GPS设置和网络连接");
            sendChatMessage();
            etMessage.setText("");
            
            Log.w(TAG, "启动导航失败");
        }
    }
    
    /**
     * 开始更新导航信息
     */
    private void startNavigationUpdates() {
        isNavigationUpdating = true;
        navigationUpdateHandler.post(navigationUpdateRunnable);
        Log.d(TAG, "开始更新导航信息");
    }
    
    /**
     * 停止更新导航信息
     */
    private void stopNavigationUpdates() {
        isNavigationUpdating = false;
        if (navigationUpdateHandler != null) {
            navigationUpdateHandler.removeCallbacks(navigationUpdateRunnable);
        }
        Log.d(TAG, "停止更新导航信息");
    }
    
    /**
     * 更新导航信息显示
     */
    private void updateNavigationInfo() {
        if (!navigationServiceManager.isServiceConnected()) {
            Log.w(TAG, "导航服务未连接，停止更新");
            return;
        }
        
        try {
            // 获取导航数据
            int distanceToDestination = navigationServiceManager.getDistanceToDestination();
            String currentDirection = navigationServiceManager.getCurrentDirection();
            int distanceToNext = navigationServiceManager.getDistanceToNextTurn();
            boolean isNavigating = navigationServiceManager.isNavigating();
            
            // 检查导航服务的详细状态
            if (navigationServiceManager.getNavigationService() != null) {
                android.location.Location currentLoc = navigationServiceManager.getNavigationService().getCurrentLocation();
                Log.d(TAG, "当前位置: " + (currentLoc != null ? 
                    currentLoc.getLatitude() + "," + currentLoc.getLongitude() : "null"));
            }
            
            Log.d(TAG, "导航信息更新 - 距离: " + distanceToDestination + 
                  "m, 方向: " + currentDirection + ", 下一转向: " + distanceToNext + "m, 导航状态: " + isNavigating);
            
            if (isNavigating && distanceToDestination > 0) {
                // 更新UI
                tvDestinationDistance.setText(NavigationServiceManager.formatDistance(distanceToDestination));
                tvCurrentDirection.setText(currentDirection);
                tvNextTurnDistance.setText(NavigationServiceManager.formatDistance(distanceToNext));
            } else {
                // 导航结束或无效数据，停止更新
                String reason = isNavigating ? "距离无效(" + distanceToDestination + ")" : "导航未启动";
                etMessage.setText("位置未到可导航点，请遥控到可导航区域重启导航");
                sendChatMessage();
                Log.d(TAG, "停止更新导航信息 - 原因: " + reason);
                stopNavigationUpdates();
                clearNavigationInfo();
                
                if (isNavigationUpdating) {
                    // 根据具体原因发送不同的消息
                    if (!isNavigating) {
                        Toast.makeText(this, "导航服务异常，导航已停止", Toast.LENGTH_SHORT).show();
                        // 发送导航失败消息到聊天
                        etMessage.setText("导航失败：导航服务异常，请重新启动导航");
                        sendChatMessage();
                        etMessage.setText("");
                    } else if (distanceToDestination <= 0) {
                        Toast.makeText(this, "导航数据异常，导航已停止", Toast.LENGTH_SHORT).show();
                        // 发送导航失败消息到聊天
                        etMessage.setText("导航失败：无法获取有效的导航数据，请检查GPS信号");
                        sendChatMessage();
                        etMessage.setText("");
                    } else {
                        Toast.makeText(this, "导航已结束", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "更新导航信息失败: " + e.getMessage());
            
            // 发生异常时停止导航更新并发送失败消息
            if (isNavigationUpdating) {
                stopNavigationUpdates();
                clearNavigationInfo();
                Toast.makeText(this, "导航数据获取异常", Toast.LENGTH_SHORT).show();
                
                // 发送导航异常消息到聊天
                etMessage.setText("导航失败：导航数据获取异常，请重新启动导航");
                sendChatMessage();
                etMessage.setText("");
            }
        }
    }
    
    /**
     * 清空导航信息显示
     */
    private void clearNavigationInfo() {
        tvDestinationDistance.setText("-- 米");
        tvCurrentDirection.setText("--");
        tvNextTurnDistance.setText("-- 米");
        Log.d(TAG, "清空导航信息显示");
    }

    private  void  iniLoadOpenCV(){
        boolean loaded = OpenCVLoader.initDebug();
        if (loaded) {
            Toast.makeText(this, "OpenCV 加载成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "OpenCV 加载失败", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "OpenCV 加载失败");
        }
    }
    
    /**
     * 启动OpenCV图像检测服务
     */
    private void startCameraDetectionService() {
        // 检查摄像头权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要摄像头权限才能启动图像检测", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    PERMISSION_REQUEST_CODE);
            return;
        }
        
        if (!isDetectionServiceRunning) {
            Intent serviceIntent = new Intent(this, CameraDetectionService.class);
            startForegroundService(serviceIntent);
            isDetectionServiceRunning = true;
            
            // 开始更新检测信息显示
            startDetectionUpdates();
            
            Toast.makeText(this, "图像检测服务已启动", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "OpenCV图像检测服务已启动");
        } else {
            Toast.makeText(this, "图像检测服务已在运行", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 停止OpenCV图像检测服务
     */
    private void stopCameraDetectionService() {
        if (isDetectionServiceRunning) {
            Intent serviceIntent = new Intent(this, CameraDetectionService.class);
            stopService(serviceIntent);
            isDetectionServiceRunning = false;
            
            // 停止更新检测信息显示
            stopDetectionUpdates();
            
            Toast.makeText(this, "图像检测服务已停止", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "OpenCV图像检测服务已停止");
        }
    }
    
    /**
     * 初始化检测信息更新处理器
     */
    private void initDetectionUpdateHandler() {
        detectionUpdateHandler = new Handler(Looper.getMainLooper());
        detectionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDetectionServiceRunning) {
                    updateDetectionInfo();
                    detectionUpdateHandler.postDelayed(this, 500); // 每500ms更新一次
                }
            }
        };
    }
    
    /**
     * 开始更新检测信息
     */
    private void startDetectionUpdates() {
        if (detectionUpdateHandler != null && detectionUpdateRunnable != null) {
            detectionUpdateHandler.post(detectionUpdateRunnable);
            Log.d(TAG, "开始更新检测信息");
        }
    }
    
    /**
     * 停止更新检测信息
     */
    private void stopDetectionUpdates() {
        if (detectionUpdateHandler != null && detectionUpdateRunnable != null) {
            detectionUpdateHandler.removeCallbacks(detectionUpdateRunnable);
            Log.d(TAG, "停止更新检测信息");
        }
    }
    
    /**
     * 更新检测信息显示
     */
    private void updateDetectionInfo() {
        if (dataManager != null && tvDetectionStatus != null && tvDetectionData != null) {
            // 更新检测状态
            tvDetectionStatus.setText(dataManager.getDetectionStatus());
            
            // 更新检测数据
            tvDetectionData.setText(dataManager.getFormattedDelta());
            
            // 如果有有效数据，可以在这里添加更多的UI更新逻辑
            if (dataManager.isDataValid() && dataManager.isCircleDetected()) {
                Log.d(TAG, "检测到目标 - X偏差: " + dataManager.getDeltaX() + 
                      ", Y偏差: " + dataManager.getDeltaY());
            }
        }
    }
}