package com.lhht.xiaozhi.websocket;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketManager {
    private static final String TAG = "WebSocketManager";
    private WebSocketClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String deviceId;
    private WebSocketListener listener;
    private String serverUrl;
    private String token;
    private boolean enableToken;
    private boolean isReconnecting = false;
    private static final int RECONNECT_DELAY = 3000; // 3秒后重连
    
    // 消息队列机制
    private BlockingQueue<QueuedMessage> messageQueue;
    private ExecutorService messageExecutor;
    private AtomicBoolean isProcessingQueue = new AtomicBoolean(false);
    private static final int MAX_QUEUE_SIZE = 100;
    private static final long MESSAGE_THROTTLE_MS = 50; // 消息发送间隔
    
    // 消息队列项
    private static class QueuedMessage {
        final String textMessage;
        final byte[] binaryMessage;
        final long timestamp;
        final boolean isBinary;
        
        QueuedMessage(String textMessage) {
            this.textMessage = textMessage;
            this.binaryMessage = null;
            this.isBinary = false;
            this.timestamp = System.currentTimeMillis();
        }
        
        QueuedMessage(byte[] binaryMessage) {
             this.textMessage = null;
             this.binaryMessage = binaryMessage;
             this.isBinary = true;
             this.timestamp = System.currentTimeMillis();
         }
     }
     
     // 优先级消息发送（用于重要消息如图像识别结果）
     public void sendPriorityMessage(String message) {
         if (client != null && client.isOpen()) {
             try {
                 QueuedMessage queuedMessage = new QueuedMessage(message);
                 // 清空当前队列中的普通消息，优先发送重要消息
                 messageQueue.clear();
                 messageQueue.offer(queuedMessage);
                 Log.d(TAG, "Priority message queued, cleared previous messages");
             } catch (Exception e) {
                 Log.e(TAG, "Error queuing priority message", e);
                 // 降级到直接发送
                 client.send(message);
             }
         }
     }
     
     // 获取队列状态信息
     public int getQueueSize() {
         return messageQueue != null ? messageQueue.size() : 0;
     }
     
     public boolean isQueueFull() {
         return messageQueue != null && messageQueue.remainingCapacity() == 0;
     }

    public interface WebSocketListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onMessage(String message);
        void onBinaryMessage(byte[] data);
    }

    public WebSocketManager(String deviceId) {
        this.deviceId = deviceId;
        this.messageQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        this.messageExecutor = Executors.newSingleThreadExecutor();
        startMessageProcessor();
    }
    
    // 启动消息处理器
    private void startMessageProcessor() {
        if (isProcessingQueue.compareAndSet(false, true)) {
            messageExecutor.submit(() -> {
                while (isProcessingQueue.get()) {
                    try {
                        QueuedMessage message = messageQueue.take();
                        if (client != null && client.isOpen()) {
                            if (message.isBinary) {
                                client.send(message.binaryMessage);
                            } else {
                                client.send(message.textMessage);
                            }
                            // 控制发送频率
                            Thread.sleep(MESSAGE_THROTTLE_MS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing message queue", e);
                    }
                }
            });
        }
    }
    
    // 停止消息处理器
    private void stopMessageProcessor() {
        isProcessingQueue.set(false);
        if (messageExecutor != null && !messageExecutor.isShutdown()) {
            messageExecutor.shutdownNow();
        }
    }

    public void setListener(WebSocketListener listener) {
        this.listener = listener;
    }

    public void connect(String url, String token, boolean enableToken) {
        if (url == null || url.isEmpty()) {
            if (listener != null) {
                listener.onError("WebSocket地址不能为空");
            }
            return;
        }

        this.serverUrl = url;
        this.token = token;
        this.enableToken = enableToken;
        
        try {
            // 如果已经连接，先断开
            if (client != null && client.isOpen()) {
                client.close();
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("device-id", deviceId);
            if (enableToken && token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
            }

            URI uri = URI.create(url);
            client = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket Connected");
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onConnected();
                        }
                        sendHelloMessage();
                    });
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    Log.d(TAG, "Received binary message: " + bytes.remaining() + " bytes");
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onBinaryMessage(data);
                        }
                    });
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Received message: " + message);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onMessage(message);
                        }
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "WebSocket Closed: code=" + code + ", reason=" + reason + ", remote=" + remote);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onDisconnected();
                        }
                        if (!isReconnecting && remote) {
                            isReconnecting = true;
                            mainHandler.postDelayed(() -> {
                                isReconnecting = false;
                                WebSocketManager.this.connect(serverUrl, token, enableToken);
                            }, RECONNECT_DELAY);
                        }
                    });
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket Error: " + ex.getMessage(), ex);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onError(ex.getMessage());
                        }
                    });
                }
            };

            // 设置连接超时
            client.setConnectionLostTimeout(5); // 5秒超时检测
            
            // 使用异步连接并添加超时处理
            new Thread(() -> {
                try {
                    // 设置连接超时
                    final boolean[] connected = {false};
                    Thread connectThread = new Thread(() -> {
                        try {
                            connected[0] = client.connectBlocking();
                        } catch (InterruptedException e) {
                            Log.e(TAG, "连接中断", e);
                        }
                    });
                    connectThread.start();
                    
                    // 等待5秒
                    connectThread.join(5000);
                    
                    // 如果5秒后还没连接成功，就判定为超时
                    if (!connected[0]) {
                        connectThread.interrupt();
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onError("连接超时");
                            }
                        });
                        if (client != null) {
                            client.close();
                        }
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "连接中断", e);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onError("连接中断: " + e.getMessage());
                        }
                    });
                }
            }).start();
            
        } catch (Exception e) {
            Log.e(TAG, "创建WebSocket失败", e);
            if (listener != null) {
                listener.onError("创建WebSocket失败: " + e.getMessage());
            }
        }
    }

    private void sendHelloMessage() {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", 3);
            hello.put("transport", "websocket");
            
            JSONObject audioParams = new JSONObject();
            audioParams.put("format", "opus");
            audioParams.put("sample_rate", 16000);
            audioParams.put("channels", 1);
            audioParams.put("frame_duration", 60);
            
            hello.put("audio_params", audioParams);
            
            sendMessage(hello.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating hello message", e);
        }
    }

    public void disconnect() {
        // 停止消息处理器
        stopMessageProcessor();
        
        // 清空消息队列
        if (messageQueue != null) {
            messageQueue.clear();
        }
        
        if (client != null && client.isOpen()) {
            client.close();
        }
    }

    public boolean isConnected() {
        return client != null && client.isOpen();
    }

    public void sendMessage(String message) {
        if (client != null && client.isOpen()) {
            try {
                QueuedMessage queuedMessage = new QueuedMessage(message);
                if (!messageQueue.offer(queuedMessage)) {
                    // 队列满了，移除最旧的消息
                    messageQueue.poll();
                    messageQueue.offer(queuedMessage);
                    Log.w(TAG, "Message queue full, removed oldest message");
                }
                Log.d(TAG, "Message queued: " + message);
            } catch (Exception e) {
                Log.e(TAG, "Error queuing message", e);
                // 降级到直接发送
                client.send(message);
                Log.d(TAG, "Message sent directly: " + message);
            }
        } else {
            Log.w(TAG, "WebSocket not connected, cannot send message");
        }
    }

    public void sendBinaryMessage(byte[] data) {
        if (client != null && client.isOpen()) {
            try {
                QueuedMessage queuedMessage = new QueuedMessage(data);
                if (!messageQueue.offer(queuedMessage)) {
                    // 队列满了，移除最旧的消息
                    messageQueue.poll();
                    messageQueue.offer(queuedMessage);
                    Log.w(TAG, "Message queue full, removed oldest message");
                }
                Log.d(TAG, "Binary message queued, size: " + data.length);
            } catch (Exception e) {
                Log.e(TAG, "Error queuing binary message", e);
                // 降级到直接发送
                client.send(data);
                Log.d(TAG, "Binary message sent directly, size: " + data.length);
            }
        } else {
            Log.w(TAG, "WebSocket not connected, cannot send binary message");
        }
    }
}