package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.lhht.xiaozhi.services.BluetoothService;
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
    
    private EditText etUserId, etNickname, etTargetUser, etMessage, etRoomId, etServerUrl, etWebrtcServerUrl;
    private TextView tvStatus, tvOnlineUsers, tvMessages;
    private Button btnConnect, btnSend, btnGetUsers, btnClear;
    private ScrollView chatScrollView;
    private Button btnForward, btnBackward, btnLeft, btnRight, btnStop;
    private Button btnNavigation; // 自动导航按钮
    private Button btnsearch; // 搜索按钮
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
    private Switch switchImageDetection; // 图像检测开关
    
    // 蓝牙服务对象
    private BluetoothService btService;
    private boolean btServiceBound = false;
    // POI目的地坐标信息
    private double destinationLat = 0.0;
    private double destinationLng = 0.0;
    private boolean hasDestinationCoordinates = false;
    
    // 实现系统提供的服务连接回调  绑定服务必须实现服务连接连接回调，监听服务还活着不
    private final ServiceConnection btConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            //获取蓝牙服务对象
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            // 蓝牙服务对象赋值给成员变量
            btService = binder.getService();
            btServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            btServiceBound = false;
            btService = null;
        }
    };

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
        setupClickListeners();
        checkPermissions();
        //拿到主线程的handler
        mainHandler = new Handler(Looper.getMainLooper());

        // 绑定式启动蓝牙服务  向系统申请蓝牙服务
        bindService(new Intent(this, BluetoothService.class), btConnection, Context.BIND_AUTO_CREATE);

        // 生成唯一的客户端ID
        clientId = "client_" + UUID.randomUUID().toString().substring(0, 8);
        Log.d(TAG, "客户端ID: " + clientId);

        // 初始化检测数据管理器
        dataManager = DataManager.getInstance(this);

        // 初始化检测信息更新处理器
        initDetectionUpdateHandler();

        // 处理从SearchNaviActivity传递过来的POI信息
        handlePoiFromSearch();
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
        switchImageDetection = findViewById(R.id.switchImageDetection); // 图像检测开关
        
        // 服务器地址配置输入框
        etServerUrl = findViewById(R.id.etServerUrl);
        etWebrtcServerUrl = findViewById(R.id.etWebrtcServerUrl);
        btnsearch = findViewById(R.id.search_button);
        
        // 聊天消息ScrollView
        chatScrollView = findViewById(R.id.chatScrollView);
        setupChatScrollView();
    }
    //子view 事件分发拦截
    @SuppressLint("ClickableViewAccessibility")
    private void setupChatScrollView() {
        if (chatScrollView != null) {
            chatScrollView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {//返回true表示拦截事件，false表示不拦截事件
                    // 请求父视图不要拦截触摸事件 目标区域触摸开始时：告诉父 View "别抢，让我自己处理"
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    
                    switch (event.getAction() & MotionEvent.ACTION_MASK) {
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL://划出目标区域时，允许父视图重新拦截触摸事件
                            // 释放触摸时，允许父视图重新拦截触摸事件
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            break;
                    }
                    
                    // 返回false让ScrollView正常处理滑动事件
                    return false;
                }
            });
        }
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
        btnClear.setOnClickListener(v -> clearMessages());
        btnsearch.setOnClickListener(view -> {
            Intent intent = new Intent(ChatActivity.this, SearchNaviActivity.class);
            startActivity(intent);
        });
        
        // 快捷控制按钮点击事件
        btnForward.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sendQuickCommand("前进");
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sendQuickCommand("停止");
                    break;
            }
            return true;
        });
        btnBackward.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sendQuickCommand("后退");
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sendQuickCommand("停止");
                    break;
            }
            return true;
        });
        btnLeft.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sendQuickCommand("左转");
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sendQuickCommand("停止");
                    break;
            }
            return true;
        });
        btnRight.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sendQuickCommand("右转");
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sendQuickCommand("停止");
                    break;
            }
            return true;
        });
        btnStop.setOnClickListener(v -> sendQuickCommand("停止"));
        btnNavigation.setOnClickListener(v -> sendNavigationCommand()); // 自动导航按钮点击事件
        
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
        
        // 图像检测开关点击事件
        switchImageDetection.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 开启图像检测服务
                startCameraDetectionService();
            } else {
                // 关闭图像检测服务
                stopCameraDetectionService();
            }
        });
    }

    private void connect() {
        String userId = etUserId.getText().toString().trim();
        String nickname = etNickname.getText().toString().trim();
        String serverUrl = etServerUrl.getText().toString().trim();

        if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(nickname)) {
             Toast.makeText(this, "请输入用户ID和昵称", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 如果用户没有输入服务器地址，使用默认地址
        if (TextUtils.isEmpty(serverUrl)) {
            serverUrl = SERVER_URL;
        } else if (!serverUrl.endsWith("/")) {
            serverUrl += "/";
        }

        try {
            String url = serverUrl + userId + "?nickname=" + nickname;
            addMessage("系统", "正在连接到: " + url, getCurrentTime());
            Request request = new Request.Builder().url(url).build();

            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> {
                        isConnected = true;
                        updateConnectionStatus("已连接", true);
                        addMessage("系统", "连接成功", getCurrentTime());
                    });
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> handleMessage(text));
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> handleMessage(bytes.utf8()));
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> {
                        isConnected = false;
                        updateConnectionStatus("连接关闭中...", false);
                        addMessage("系统", "连接关闭: " + reason, getCurrentTime());
                    });
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> {
                        isConnected = false;
                        updateConnectionStatus("已断开", false);
                        addMessage("系统", "连接已断开", getCurrentTime());
                    });
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    if (mainHandler == null) return;
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
    }

    private void handleMessage(String message) {//处理json消息类型
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
            
            // 检查消息内容中是否包含"目的地坐标"
            if (content.contains("目的地坐标")) {
                try {
                    // 提取坐标信息
                    String coordinateText = content.substring(content.indexOf("目的地坐标") + "目的地坐标".length()).trim();
                    
                    // 处理括号格式的坐标
                    if (coordinateText.startsWith("(") && coordinateText.endsWith(")")) {
                        coordinateText = coordinateText.substring(1, coordinateText.length() - 1);
                    }
                    
                    String[] coordinates = coordinateText.split("[,，]");
                    
                    if (coordinates.length >= 2) {
                        double parsedLat = Double.parseDouble(coordinates[0].trim());
                        double parsedLng = Double.parseDouble(coordinates[1].trim());
                        
                        // 将坐标保存到类成员变量中，供后台服务使用
                        this.destinationLat = parsedLat;
                        this.destinationLng = parsedLng;
                        this.hasDestinationCoordinates = true;
                        
                        // 使用提取到的坐标信息启动导航
                         Toast.makeText(this, "收到导航指令：开始导航到指定坐标 (" + this.destinationLat + ", " + this.destinationLng + ")", Toast.LENGTH_LONG).show();
                         Log.d(TAG, "从消息内容提取到坐标并保存到成员变量: " + this.destinationLat + ", " + this.destinationLng);
                        
                        // 启动导航服务并导航到指定坐标
                        startNavigationToCoordinatesWithService(this.destinationLat, this.destinationLng, "目的地");
                    } else {
                        Log.e(TAG, "坐标格式错误: " + coordinateText);
                        Toast.makeText(this, "坐标格式错误，请检查消息格式", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析坐标时出错: " + e.getMessage());
                    Toast.makeText(this, "解析坐标失败，请检查消息格式", Toast.LENGTH_SHORT).show();
                }
            } else if (hasDestinationCoordinates && this.destinationLat != 0.0 && this.destinationLng != 0.0) {
                // 使用之前保存的POI坐标启动导航
                Toast.makeText(this, "收到导航指令：开始导航到已选择的目的地", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "检测到导航命令，使用已保存的POI坐标: " + content + ", 坐标: " + this.destinationLat + ", " + this.destinationLng);
                
                // 启动导航服务并导航到保存的POI坐标
                startNavigationToCoordinatesWithService(this.destinationLat, this.destinationLng, "已选择目的地");
            } else {
                // 没有可用的坐标信息，提示用户先选择目的地
                Toast.makeText(this, "请先选择目的地或发送包含坐标的导航指令", Toast.LENGTH_LONG).show();
                Log.d(TAG, "检测到导航命令但没有可用的目的地坐标: " + content);
            }
            
            // 启动OpenCV图像检测服务
            startCameraDetectionService();
        }

        // 检测其他命令 - 通过蓝牙服务发送指令
        if (content != null && content.contains("前进")) {
            if (btServiceBound) btService.sendChar('a');
//            Toast.makeText(this, "收到前进指令", Toast.LENGTH_SHORT).show();
        } else if (content != null && content.contains("后退")) {
            if (btServiceBound) btService.sendChar('b');
//            Toast.makeText(this, "收到后退指令", Toast.LENGTH_SHORT).show();
        } else if (content != null && content.contains("左转")) {
            if (btServiceBound) btService.sendChar('c');
//            Toast.makeText(this, "收到左转指令", Toast.LENGTH_SHORT).show();
        } else if (content != null && content.contains("右转")) {
            if (btServiceBound) btService.sendChar('d');
//            Toast.makeText(this, "收到右转指令", Toast.LENGTH_SHORT).show();
        }else if (content != null && content.contains("停止")){
            if (btServiceBound) btService.sendChar('e');
//             Toast.makeText(this, "收到停止指令", Toast.LENGTH_SHORT).show();
        }else if (tvCurrentDirection.getText().toString().equals("当前方向：左转") && content != null){
            if (btServiceBound) btService.sendChar('c');
//            Toast.makeText(this, "收到左转指令", Toast.LENGTH_SHORT).show();
        }else if (tvCurrentDirection.getText().toString().equals("当前方向：右转") && content != null){
            if (btServiceBound) btService.sendChar('d');
//            Toast.makeText(this, "收到右转指令", Toast.LENGTH_SHORT).show();
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
     
     /**
      * 启动导航服务并导航到指定坐标
      */
    private void startNavigationToCoordinatesWithService(double latitude, double longitude, String destinationName) {
        Log.d(TAG, "准备启动导航服务并导航到坐标: " + latitude + ", " + longitude);
        
        // 检查定位权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "缺少定位权限");
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
        
        // 懒加载导航服务管理器（仅首次需要时初始化）
        if (navigationServiceManager == null) {
            initNavigationService();
            initNavigationUpdateHandler();
        }
        
        // 启动并绑定导航服务
         if (!navigationServiceManager.isServiceConnected()) {
             // 设置待启动标志和坐标信息，等待服务连接回调
             pendingNavigationStart = true;
             destinationLat = latitude;
             destinationLng = longitude;
             hasDestinationCoordinates = true;
             navigationServiceManager.startAndBindService();
             Toast.makeText(this, "正在启动导航服务...", Toast.LENGTH_SHORT).show();
             Log.d(TAG, "导航服务未连接，正在启动服务并设置待启动标志");
             
             // 设置超时检查，如果5秒后服务仍未连接，则提示失败
             mainHandler.postDelayed(() -> {
                 if (pendingNavigationStart && !navigationServiceManager.isServiceConnected()) {
                     pendingNavigationStart = false;
                     hasDestinationCoordinates = false;
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
             startNavigationToCoordinates(latitude, longitude, destinationName);
         }
     }
     
     /**
     * 发送自动导航指令，如果有目的地坐标信息则一起发送
     */
    private void sendNavigationCommand() {
        if (!isConnected || webSocket == null) {
             Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String targetUser = etTargetUser.getText().toString().trim();
            
            JSONObject message = new JSONObject();
            message.put("type", "chat");
            
            String commandContent = "[控制指令] 自动导航";
            
            // 如果有目的地坐标信息，添加到消息中
            if (hasDestinationCoordinates) {
                commandContent += " 目的地坐标(" + destinationLat + "," + destinationLng + ")";
                message.put("destination_lat", destinationLat);
                message.put("destination_lng", destinationLng);
                Log.d(TAG, "发送自动导航指令，包含目的地坐标: (" + destinationLat + ", " + destinationLng + ")");
            } else {
                Log.d(TAG, "发送自动导航指令，无目的地坐标信息");
            }
            
            message.put("content", commandContent);
            
            // 添加控制指令标识
            message.put("command", "自动导航");
            message.put("is_control", true);
            
            // 与文本消息使用相同的发送逻辑
            if (!TextUtils.isEmpty(targetUser)) {
                message.put("target_user_id", targetUser);
                addMessage("我[控制]", "发送给 " + targetUser + ": 自动导航" + 
                    (hasDestinationCoordinates ? " (" + destinationLat + "," + destinationLng + ")" : ""), getCurrentTime());
            } else {
                addMessage("我[控制]", "自动导航" + 
                    (hasDestinationCoordinates ? " (" + destinationLat + "," + destinationLng + ")" : ""), getCurrentTime());
            }

            webSocket.send(message.toString());
        } catch (JSONException e) {
             Toast.makeText(this, "发送导航指令失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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



    private void clearMessages() {
        tvMessages.setText("");
        // 同时清除目的地坐标信息
        clearDestinationCoordinates();
    }
    
    /**
     * 清除已保存的目的地坐标信息
     */
    private void clearDestinationCoordinates() {
        destinationLat = 0.0;
        destinationLng = 0.0;
        hasDestinationCoordinates = false;
        Log.d(TAG, "已清除目的地坐标信息");
        Toast.makeText(this, "已清除目的地坐标信息和聊天消息", Toast.LENGTH_SHORT).show();
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
    //权限申请结果回调，一定要写
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
            
            String webrtcServerUrl = etWebrtcServerUrl.getText().toString().trim();
            // 如果用户没有输入WebRTC服务器地址，使用默认地址
            if (TextUtils.isEmpty(webrtcServerUrl)) {
                webrtcServerUrl = WEBRTC_SERVER_URL;
            } else if (!webrtcServerUrl.endsWith("/")) {
                webrtcServerUrl += "/";
            }
            
            // 构建包含客户端ID和nickname的WebRTC端点URL
            String nickname = etNickname.getText().toString().trim();
            if (nickname.isEmpty()) {
                nickname = "Android客户端";
            }
            String webrtcUrl = webrtcServerUrl + clientId + "?nickname=" + nickname;
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
        if (layoutNoRemoteSignal != null) {
            layoutNoRemoteSignal.setVisibility(View.VISIBLE);
        }
        
        // 重新初始化WebRTC
        initializeWebRTC();
        
        isInCall = false;
        remoteClientId = null;
        // 移除摄像头状态重置，保持用户设置的摄像头状态
        updateWebRTCButtonStates();
        
        // 根据用户设置的摄像头状态显示或隐藏本地无信号提示
        if (layoutNoLocalSignal != null) {
            layoutNoLocalSignal.setVisibility(isCameraEnabled ? View.GONE : View.VISIBLE);
        }
        
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
                    
                case "room_joined":
                    // 处理加入房间后收到的现有用户列表
                    if (jsonMessage.has("existingClients")) {
                        try {
                            org.json.JSONArray existingClients = jsonMessage.getJSONArray("existingClients");
                            if (existingClients.length() > 0) {
                                // 设置第一个现有客户端为远程客户端ID
                                remoteClientId = existingClients.getString(0);
                                Toast.makeText(this, "房间内已有用户: " + remoteClientId, Toast.LENGTH_SHORT).show();
                                Log.d(TAG, "设置远程客户端ID: " + remoteClientId);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析现有客户端列表失败: " + e.getMessage());
                        }
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
        if (signalingClient != null && signalingClient.isOpen()) {
            if (sdp.type == SessionDescription.Type.OFFER) {
                if (remoteClientId != null) {
                    // 如果有指定的远程客户端，直接发送给该客户端
                    signalingClient.sendOffer(sdp.description, remoteClientId);
                } else {
                    // 如果没有指定远程客户端，广播给房间内所有其他客户端
                    signalingClient.sendBroadcastOffer(sdp.description);
                }
            } else if (sdp.type == SessionDescription.Type.ANSWER && remoteClientId != null) {
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
                
                // 根据用户当前设置的摄像头状态来控制摄像头
                if (webRTCManager != null) {
                    if (isCameraEnabled) {
                        webRTCManager.enableCamera();
                    } else {
                        webRTCManager.disableCamera();
                    }
                }
                
                // 根据摄像头状态显示或隐藏本地无信号提示
                if (layoutNoLocalSignal != null) {
                    layoutNoLocalSignal.setVisibility(isCameraEnabled ? View.GONE : View.VISIBLE);
                }
                if (surfaceViewLocal != null) {
                    surfaceViewLocal.setVisibility(View.VISIBLE);
                }
                
                // 更新按钮状态
                updateWebRTCButtonStates();
                
                // Toast.makeText(ChatActivity.this, "本地视频已准备就绪", Toast.LENGTH_SHORT).show();
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
        
        // 先断开 WebSocket 连接，防止回调时 mainHandler 已为 null
        disconnect();
        
        // ===== 清理 Handler，防止内存泄漏 =====
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler = null;
        }
        if (navigationUpdateHandler != null) {
            navigationUpdateHandler.removeCallbacksAndMessages(null);
            navigationUpdateHandler = null;
        }
        if (detectionUpdateHandler != null) {
            detectionUpdateHandler.removeCallbacksAndMessages(null);
            detectionUpdateHandler = null;
        }
        
        // 清理 WebRTC 监听器引用
        webRTCListener = null;
        
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
        
        // 清理WebRTC资源
        if (webRTCManager != null) {
            webRTCManager.close();
            webRTCManager = null;
        }
        if (signalingClient != null) {
            signalingClient.close();
            signalingClient = null;
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

        // 解绑蓝牙服务
        if (btServiceBound) {
            unbindService(btConnection);
            btServiceBound = false;
            btService = null;
        }
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
                        
                        // 根据是否有坐标信息选择启动方式
                        if (hasDestinationCoordinates) {
                            startNavigationToCoordinates(destinationLat, destinationLng, "目的地");
                            // 重置坐标信息
                            hasDestinationCoordinates = false;
                            destinationLat = 0.0;
                            destinationLng = 0.0;
                        } else {
                            startNavigationToDestination();
                        }
                    }
                });
            }
            
            @Override
            public void onServiceDisconnected() {
                runOnUiThread(() -> {
                    Log.d(TAG, "导航服务已断开");
                    String disconnectedMessage = "导航服务已断开连接";
                    // 将断开连接信息添加到聊天窗口
                    addMessage("导航系统", disconnectedMessage, getCurrentTime());
                    stopNavigationUpdates();
                    clearNavigationInfo();
                });
            }
            
            @Override
            public void onReconnecting(int currentAttempt, int maxAttempts) {
                runOnUiThread(() -> {
                    Log.d(TAG, "导航服务重连中... (" + currentAttempt + "/" + maxAttempts + ")");
                    String reconnectingMessage = "导航服务重连中... (" + currentAttempt + "/" + maxAttempts + ")";
                    Toast.makeText(ChatActivity.this, reconnectingMessage, Toast.LENGTH_SHORT).show();
                    // 将重连信息添加到聊天窗口
                    addMessage("导航系统", reconnectingMessage, getCurrentTime());
                });
            }

            @Override
            public void onReconnectFailed(int totalAttempts) {
                runOnUiThread(() -> {
                    Log.e(TAG, "导航服务重连失败，总尝试次数: " + totalAttempts);
                    String failureMessage = "导航服务重连失败，已尝试 " + totalAttempts + " 次";
                    Toast.makeText(ChatActivity.this, failureMessage, Toast.LENGTH_LONG).show();
                    // 将失败信息添加到聊天窗口
                    addMessage("导航系统", failureMessage, getCurrentTime());
                });
            }

            @Override
            public void onReconnectSuccess(int attempts) {
                runOnUiThread(() -> {
                    Log.i(TAG, "导航服务重连成功，尝试次数: " + attempts);
                    String successMessage = "导航服务重连成功，尝试了 " + attempts + " 次";
                    Toast.makeText(ChatActivity.this, successMessage, Toast.LENGTH_SHORT).show();
                    // 将成功信息添加到聊天窗口
                    addMessage("导航系统", successMessage, getCurrentTime());
                });
            }

            @Override
            public void onConnectionLost() {
                runOnUiThread(() -> {
                    Log.w(TAG, "导航服务连接丢失");
                    String lostMessage = "导航服务连接丢失，正在尝试重连...";
                    Toast.makeText(ChatActivity.this, lostMessage, Toast.LENGTH_SHORT).show();
                    // 将连接丢失信息添加到聊天窗口
                    addMessage("导航系统", lostMessage, getCurrentTime());
                    stopNavigationUpdates();
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
     * 启动导航到目的地
     */
    private void startNavigationToDestination() {
        Log.d(TAG, "准备启动导航到驿站，服务连接状态: " + navigationServiceManager.isServiceConnected());
        
        if (navigationServiceManager.startNavigationToDestination("驿站")) {
//            Toast.makeText(this, "开始导航到驿站", Toast.LENGTH_SHORT).show();
            
            // 发送导航启动成功消息到聊天
            etMessage.setText("导航启动成功，正在前往驿站");
            sendChatMessage();
            etMessage.setText("");
            
            // 延迟启动更新，给导航服务一些时间初始化
            mainHandler.postDelayed(() -> {
                startNavigationUpdates();
                Log.d(TAG, "延迟启动导航信息更新");
            }, 3000); // 延迟3秒
        } else {
            Toast.makeText(this, "导航启动失败", Toast.LENGTH_SHORT).show();
            
            // 发送导航启动失败消息到聊天
            etMessage.setText("导航启动失败，请检查导航服务状态");
            sendChatMessage();
            etMessage.setText("");
            
            Log.e(TAG, "导航启动失败");
        }
    }
    
    /**
     * 启动导航到指定坐标
     */
    private void startNavigationToCoordinates(double latitude, double longitude, String destinationName) {
        Log.d(TAG, "准备启动导航到坐标: " + latitude + ", " + longitude + ", 服务连接状态: " + navigationServiceManager.isServiceConnected());
        
        if (navigationServiceManager.startNavigationToCoordinates(latitude, longitude, destinationName)) {
            Toast.makeText(this, "开始导航到" + destinationName, Toast.LENGTH_SHORT).show();
            
            // 发送导航启动成功消息到聊天
            etMessage.setText("导航启动成功，正在前往" + destinationName + "(" + latitude + ", " + longitude + ")");
            sendChatMessage();
            etMessage.setText("");
            
            // 延迟启动更新，给导航服务一些时间初始化
             mainHandler.postDelayed(() -> {
                 startNavigationUpdates();
                 Log.d(TAG, "延迟启动导航信息更新");
             }, 3000); // 延迟3秒
             
             Log.d(TAG, "导航已启动到坐标: " + latitude + ", " + longitude);
         } else {
             Toast.makeText(this, "导航启动失败", Toast.LENGTH_SHORT).show();
             
             // 发送导航启动失败消息到聊天
             etMessage.setText("导航启动失败，请检查导航服务状态");
             sendChatMessage();
             etMessage.setText("");
             
             Log.e(TAG, "导航启动失败");
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
        // 首先检查服务连接状态
        if (!navigationServiceManager.isServiceConnected()) {
            String connectionStatus = navigationServiceManager.getConnectionStatus();
            String detailedStatus = navigationServiceManager.getDetailedConnectionStatus();
            
            Log.w(TAG, "导航服务未连接，停止更新 - 状态: " + connectionStatus);
            Log.w(TAG, "详细连接状态: " + detailedStatus);
            
            // 显示详细的连接失败原因
            Toast.makeText(this, "导航失败：服务未连接 - " + connectionStatus, Toast.LENGTH_LONG).show();
            addMessage("导航调试", "服务连接失败: " + connectionStatus, getCurrentTime());
            addMessage("导航调试", detailedStatus, getCurrentTime());
            return;
        }
        
        try {
            // 获取导航数据
            int distanceToDestination = navigationServiceManager.getDistanceToDestination();
            String currentDirection = navigationServiceManager.getCurrentDirection();
            int distanceToNext = navigationServiceManager.getDistanceToNextTurn();
            boolean isNavigating = navigationServiceManager.isNavigating();
            
            // 检查导航服务的详细状态
            android.location.Location currentLoc = null;
            if (navigationServiceManager.getNavigationService() != null) {
                currentLoc = navigationServiceManager.getNavigationService().getCurrentLocation();
                Log.d(TAG, "当前位置: " + (currentLoc != null ? 
                    currentLoc.getLatitude() + "," + currentLoc.getLongitude() : "null"));
            }
            
            // 详细的状态调试信息
            String statusDebug = String.format("导航状态检查 - isNavigating: %s, 距离: %d, 方向: %s, 下一转向: %d, 当前位置: %s", 
                isNavigating, distanceToDestination, currentDirection, distanceToNext, 
                currentLoc != null ? (currentLoc.getLatitude() + "," + currentLoc.getLongitude()) : "null");
            Log.d(TAG, statusDebug);

            if (isNavigating && distanceToDestination > 0) {
                // 更新UI
                etMessage.setText("成功导航");
                tvDestinationDistance.setText(NavigationServiceManager.formatDistance(distanceToDestination));
                tvCurrentDirection.setText(currentDirection);
                tvNextTurnDistance.setText(NavigationServiceManager.formatDistance(distanceToNext));
                
                // 检测是否到达目的地（距离小于等于50米）
                if (distanceToDestination <= 50) {
                    if (btServiceBound) btService.sendChar('e');
//                    Log.d(TAG, "已到达目的地，距离: " + distanceToDestination + "m");

                    // 发送"已到达"消息给另外一个客户端
                    etMessage.setText("已到达");
                    sendChatMessage();
                    etMessage.setText("");
                    
                    // 停止导航更新
                    stopNavigationUpdates();
                    Toast.makeText(this, "已到达目的地", Toast.LENGTH_SHORT).show();
                } else {
                    // 根据导航方向通过蓝牙服务发送指令
                    if (currentDirection != null) {
                        if (currentDirection.contains("直行") || currentDirection.contains("前进")) {
                            if (btServiceBound) btService.sendChar('a');
//                            Log.d(TAG, "导航方向：直行，发送前进指令");
                        } else if (currentDirection.contains("左转") || currentDirection.contains("左拐")) {
                            if (btServiceBound) btService.sendChar('c');
//                            Log.d(TAG, "导航方向：左转，发送左转指令");
                        } else if (currentDirection.contains("右转") || currentDirection.contains("右拐")) {
                            if (btServiceBound) btService.sendChar('d');
//                            Log.d(TAG, "导航方向：右转，发送右转指令");
                        }
                    }
                }
            } else {
                // 导航结束或无效数据，停止更新
                String reason;
                String detailedReason;
                String specificError = "";
                
                // 获取腾讯地图的具体错误信息
                if (navigationServiceManager != null) {
                    specificError = navigationServiceManager.getLastNavigationError();
                }
                
                if (!isNavigating) {
                    reason = "导航未启动";
                    // 优先显示腾讯地图的具体错误信息
                    if (specificError != null && !specificError.isEmpty() && !specificError.equals("导航服务未连接")) {
                        detailedReason = "导航未启动 - 腾讯地图错误：" + specificError;
                        Toast.makeText(this, "导航失败：" + specificError, Toast.LENGTH_LONG).show();
                    } else {
                        // 检查可能的原因
                        if (currentLoc == null) {
                            detailedReason = "导航未启动 - 原因：当前位置未获取到，可能是GPS信号弱或定位服务异常";
                            Toast.makeText(this, "导航失败：无法获取当前位置，请检查GPS信号和定位服务", Toast.LENGTH_LONG).show();
                        } else {
                            detailedReason = "导航未启动 - 原因：导航服务状态异常，可能是路线规划失败或目的地无效";
                            Toast.makeText(this, "导航失败：导航服务异常，请重新启动导航", Toast.LENGTH_LONG).show();
                        }
                    }
                } else {
                    reason = "距离无效(" + distanceToDestination + ")";
                    // 优先显示腾讯地图的具体错误信息
                    if (specificError != null && !specificError.isEmpty() && !specificError.equals("导航服务未连接")) {
                        detailedReason = "导航中但距离无效 - 腾讯地图错误：" + specificError;
                        Toast.makeText(this, "导航异常：" + specificError, Toast.LENGTH_LONG).show();
                    } else {
                        detailedReason = "导航中但距离无效 - 原因：可能是导航数据更新异常或已偏离路线";
                        Toast.makeText(this, "导航异常：距离数据无效(" + distanceToDestination + ")，请重新规划路线", Toast.LENGTH_LONG).show();
                    }
                }
                
                String debugInfo = String.format("导航失败调试信息 - 状态: %s, 距离: %d, 方向: %s, 下一转向: %d, 位置: %s, 腾讯地图错误: %s", 
                    isNavigating ? "导航中" : "未导航", distanceToDestination, currentDirection, distanceToNext,
                    currentLoc != null ? (currentLoc.getLatitude() + "," + currentLoc.getLongitude()) : "null",
                    specificError != null ? specificError : "无");
                
                Log.d(TAG, "停止更新导航信息 - 原因: " + reason);
                Log.d(TAG, detailedReason);
                Log.d(TAG, debugInfo);
                
                // 将调试信息添加到聊天窗口
                addMessage("导航调试", reason, getCurrentTime());
                addMessage("导航调试", detailedReason, getCurrentTime());
                addMessage("导航调试", debugInfo, getCurrentTime());
                
                stopNavigationUpdates();
                clearNavigationInfo();
                
                if (isNavigationUpdating) {
                    // 根据具体原因发送不同的消息
                    if (!isNavigating) {
                        // 发送导航失败消息到聊天
                        String failureMessage = "导航失败：" + detailedReason;
                        addMessage("导航系统", failureMessage, getCurrentTime());
                        etMessage.setText(failureMessage);
                        sendChatMessage();
                        etMessage.setText("");
                    } else if (distanceToDestination <= 0) {
                        Toast.makeText(this, "导航数据异常，导航已停止", Toast.LENGTH_SHORT).show();
                        // 发送导航失败消息到聊天
                        String failureMessage = "导航失败：无法获取有效的导航数据，请检查GPS信号";
                        addMessage("导航系统", failureMessage, getCurrentTime());
                        etMessage.setText(failureMessage);
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
//            Toast.makeText(this, "OpenCV 加载成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "OpenCV 加载失败", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "OpenCV 加载失败");
        }
    }
    
    /**
     * 启动OpenCV图像检测服务
     */
    private void startCameraDetectionService() {
        // 懒加载 OpenCV（仅首次需要时加载）
        iniLoadOpenCV();

        // 检查摄像头权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要摄像头权限才能启动图像检测", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    PERMISSION_REQUEST_CODE);
            // 权限被拒绝时，将开关状态重置为关闭
            if (switchImageDetection != null) {
                switchImageDetection.setChecked(false);
            }
            return;
        }
        
        if (!isDetectionServiceRunning) {
            Intent serviceIntent = new Intent(this, CameraDetectionService.class);
            startForegroundService(serviceIntent);
            isDetectionServiceRunning = true;
            
            // 开始更新检测信息显示
            startDetectionUpdates();
            
            // 更新开关状态
            if (switchImageDetection != null) {
                switchImageDetection.setChecked(true);
            }
            
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
            
            // 更新开关状态
            if (switchImageDetection != null) {
                switchImageDetection.setChecked(false);
            }
            
            // 清空检测信息显示
            if (tvDetectionStatus != null) {
                tvDetectionStatus.setText("等待距离参数...");
            }
            if (tvDetectionData != null) {
                tvDetectionData.setText("未检测到道路");
            }
            
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
            // 更新距离状态显示
            if (dataManager.isDataValid() && dataManager.isDetectionActive()) {
                String distanceStatus = String.format("距离右侧车道线: %.1f | %s", 
                    dataManager.getRoadDistance(), dataManager.getRoadStatus());
                tvDetectionStatus.setText(distanceStatus);
            } else {
                tvDetectionStatus.setText("等待检测数据...");
            }
            
            // 更新道路检测数据显示
            if (dataManager.isDataValid() && dataManager.isDetectionActive()) {
                String roadInfo = String.format("距离: %.1f", 
                    dataManager.getRoadDistance());
                tvDetectionData.setText(roadInfo);
            } else {
                tvDetectionData.setText("道路检测未激活");
            }
            
            // 日志输出道路检测信息
            if (dataManager.isDataValid() && dataManager.isDetectionActive()) {
                Log.d(TAG, String.format("道路检测 - 距离: %.1f, 状态: %s", 
                    dataManager.getRoadDistance(), dataManager.getRoadStatus()));
                
                // 通过蓝牙服务发送距离数据
                if (btServiceBound) {
                    btService.sendDistance(dataManager.getRoadDistance());
                }
            }
        }
    }
    
    /**
     * 处理从SearchNaviActivity传递过来的POI信息
     */
    private void handlePoiFromSearch() {
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("from_search", false)) {
            // 获取POI信息
            String poiName = intent.getStringExtra("poi_name");
            String poiAddress = intent.getStringExtra("poi_address");
            double poiLat = intent.getDoubleExtra("poi_lat", 0.0);
            double poiLng = intent.getDoubleExtra("poi_lng", 0.0);
            String poiCategory = intent.getStringExtra("poi_category");
            String poiType = intent.getStringExtra("poi_type");
            String poiTel = intent.getStringExtra("poi_tel");
            String poiProvince = intent.getStringExtra("poi_province");
            String poiCity = intent.getStringExtra("poi_city");
            String poiDistrict = intent.getStringExtra("poi_district");
            
            // 获取当前位置信息
            double currentLat = intent.getDoubleExtra("current_lat", 0.0);
            double currentLng = intent.getDoubleExtra("current_lng", 0.0);
            
            Log.d(TAG, "接收到POI信息: " + poiName + " (" + poiLat + ", " + poiLng + ")");
             
             // 清除之前的目的地坐标信息
             destinationLat = 0.0;
             destinationLng = 0.0;
             hasDestinationCoordinates = false;
             
             // 保存新的目的地坐标信息
             if (poiLat != 0.0 && poiLng != 0.0) {
                 destinationLat = poiLat;
                 destinationLng = poiLng;
                 hasDestinationCoordinates = true;
                 Log.d(TAG, "已保存新的目的地坐标: (" + destinationLat + ", " + destinationLng + ")");
             } else {
                 Log.d(TAG, "接收到的POI坐标无效，已清除目的地坐标信息");
             }
             
             // 构建POI信息消息
            StringBuilder poiMessage = new StringBuilder();
            poiMessage.append("已选择目的地:\n");
            poiMessage.append("名称: ").append(poiName != null ? poiName : "未知").append("\n");
            poiMessage.append("地址: ").append(poiAddress != null ? poiAddress : "未知").append("\n");
            poiMessage.append("坐标: ").append(poiLat).append(", ").append(poiLng).append("\n");
            
            if (poiCategory != null && !poiCategory.isEmpty()) {
                poiMessage.append("类别: ").append(poiCategory).append("\n");
            }
            if (poiType != null && !poiType.isEmpty()) {
                poiMessage.append("类型: ").append(poiType).append("\n");
            }
            if (poiTel != null && !poiTel.isEmpty()) {
                poiMessage.append("电话: ").append(poiTel).append("\n");
            }
            if (poiProvince != null && !poiProvince.isEmpty()) {
                poiMessage.append("省份: ").append(poiProvince).append("\n");
            }
            if (poiCity != null && !poiCity.isEmpty()) {
                poiMessage.append("城市: ").append(poiCity).append("\n");
            }
            if (poiDistrict != null && !poiDistrict.isEmpty()) {
                poiMessage.append("区域: ").append(poiDistrict).append("\n");
            }
            
            if (currentLat != 0.0 && currentLng != 0.0) {
                poiMessage.append("当前位置: ").append(currentLat).append(", ").append(currentLng);
            }
            
            // 在消息框中显示POI信息
             if (tvMessages != null) {
                 String currentMessages = tvMessages.getText().toString();
                 String newMessage = "[系统] " + poiMessage.toString() + "\n\n" + currentMessages;
                 tvMessages.setText(newMessage);
             }
             
             // 显示提示信息
             Toast.makeText(this, "已接收目的地信息: " + (poiName != null ? poiName : "未知位置"), Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 更新Intent，以便handlePoiFromSearch能够获取到新的数据
        setIntent(intent);
        // 处理从SearchNaviActivity传递过来的POI信息
        handlePoiFromSearch();
    }
}