package com.lhht.xiaozhi.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ChatActivity extends AppCompatActivity {

    private EditText etUserId, etNickname, etTargetUser, etMessage, etRoomId;
    private TextView tvStatus, tvOnlineUsers, tvMessages;
    private Button btnConnect, btnSend, btnGetUsers, btnPing, btnClear;
    private Button btnForward, btnBackward, btnLeft, btnRight, btnStop;
    private Button btnJoinRoom, btnCameraToggle, btnCall;
    private SurfaceView surfaceViewRemote, surfaceViewLocal;
    private LinearLayout layoutNoRemoteSignal, layoutNoLocalSignal;

    private WebSocket webSocket;
    private OkHttpClient client;
    private Handler mainHandler;
    private boolean isConnected = false;

    // 真机测试配置 - 根据您的网络信息配置
    private static final String SERVER_URL = "ws://192.168.0.102:8000/ws/chat/"; // 真机测试地址
    // 模拟器测试请使用: "ws://10.0.2.2:8000/ws/chat/"
    // 当前配置基于您的IPv4地址: 192.168.0.102

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        initViews();
        initWebSocket();
        setupClickListeners();

        mainHandler = new Handler(Looper.getMainLooper());
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
        
        // WebRTC相关按钮
        btnJoinRoom = findViewById(R.id.btnJoinRoom);
        btnCameraToggle = findViewById(R.id.btnCameraToggle);
        btnCall = findViewById(R.id.callbutton);
        
        // 视频预览控件
        surfaceViewRemote = findViewById(R.id.surfaceViewRemote);
        surfaceViewLocal = findViewById(R.id.surfaceViewLocal);
        layoutNoRemoteSignal = findViewById(R.id.layoutNoRemoteSignal);
        layoutNoLocalSignal = findViewById(R.id.layoutNoLocalSignal);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
    }
}