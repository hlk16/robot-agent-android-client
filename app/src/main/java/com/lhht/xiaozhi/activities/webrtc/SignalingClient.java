package com.lhht.xiaozhi.activities.webrtc;
//这个类的主要目的是通过WebSocket与信令服务器进行通信，处理WebRTC中的信令交换。

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;

public class SignalingClient extends WebSocketClient {
    
    public interface SignalingListener {
        void onConnected();
        void onDisconnected();
        void onMessageReceived(String message);
        void onError(Exception ex);
    }
    
    private SignalingListener listener;
    
    public SignalingClient(URI serverUri, SignalingListener listener) {
        super(serverUri);
        this.listener = listener;
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        if (listener != null) {
            listener.onConnected();
        }
    }
    
    @Override
    public void onMessage(String message) {
        if (listener != null) {
            listener.onMessageReceived(message);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (listener != null) {
            listener.onDisconnected();
        }
    }
    
    @Override
    public void onError(Exception ex) {
        if (listener != null) {
            listener.onError(ex);
        }
    }
    
    // 发送加入房间消息
    public void sendJoinRoom(String roomId, String clientId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "join_room");
            message.put("roomId", roomId);  // 修复：使用roomId而不是room_id
            message.put("clientId", clientId);  // 修复：使用clientId而不是client_id
            send(message.toString());
            Log.d("SignalingClient", "发送加入房间消息: " + roomId);
        } catch (JSONException e) {
            Log.e("SignalingClient", "构建加入房间消息失败", e);
        }
    }
    
    // 发送offer消息
    public void sendOffer(String sdp, String targetId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "offer");
            message.put("sdp", sdp);
            message.put("targetId", targetId);  // 添加targetId字段
            send(message.toString());
            Log.d("SignalingClient", "发送offer消息到: " + targetId);
        } catch (JSONException e) {
            Log.e("SignalingClient", "构建offer消息失败", e);
        }
    }
    
    // 发送answer消息
    public void sendAnswer(String sdp, String targetId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "answer");
            message.put("sdp", sdp);
            message.put("targetId", targetId);  // 添加targetId字段
            send(message.toString());
            Log.d("SignalingClient", "发送answer消息到: " + targetId);
        } catch (JSONException e) {
            Log.e("SignalingClient", "构建answer消息失败", e);
        }
    }
    
    // 发送ICE候选消息
    public void sendIceCandidate(String candidate, String sdpMid, int sdpMLineIndex, String targetId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "ice-candidate");  // 修改为连字符格式
            message.put("candidate", candidate);
            message.put("sdpMid", sdpMid);
            message.put("sdpMLineIndex", sdpMLineIndex);
            message.put("targetId", targetId);  // 添加targetId字段
            send(message.toString());
            Log.d("SignalingClient", "发送ICE候选消息到目标: " + targetId);
        } catch (JSONException e) {
            Log.e("SignalingClient", "构建ICE候选消息失败", e);
        }
    }
    
    // 发送结束通话消息
    public void sendEndCall(String targetId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "end_call");
            message.put("targetId", targetId);
            send(message.toString());
            Log.d("SignalingClient", "发送结束通话消息到: " + targetId);
        } catch (JSONException e) {
            Log.e("SignalingClient", "构建结束通话消息失败", e);
        }
    }
    
    // 发送广播offer消息（不指定targetId）
    public void sendBroadcastOffer(String sdp) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "offer");
            message.put("sdp", sdp);
            // 不添加targetId字段，让服务器广播给房间内所有其他客户端
            send(message.toString());
            Log.d("SignalingClient", "发送广播offer消息到房间内所有其他客户端");
        } catch (JSONException e) {
            Log.e("SignalingClient", "构建广播offer消息失败", e);
        }
    }
}