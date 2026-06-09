# 小智 Android 客户端 - 性能优化方案

## 一、音频子系统优化

### 1.1 录音线程优化

**当前问题：** `VoiceCallActivity.startRecording()` 中每次循环都调用 `audioRecord.read()`，但 `sendAudioData()` 内部做了 `short[]` 转换和 Opus 编码，这些计算在录音线程中执行会增加录音延迟。

```java
// 当前代码（VoiceCallActivity.java L474）
while (isRecording) {
    int read = audioRecord.read(buffer, 0, BUFFER_SIZE);
    if (read > 0 && !isMuted) {
        sendAudioData(buffer, read);  // 编码+发送在录音线程
        updateUserWaveform(buffer);
    }
}
```

**优化方案：** 将编码和发送放入独立线程，录音线程只负责采集。

```java
// 优化后
private BlockingQueue<byte[]> recordQueue = new LinkedBlockingQueue<>(10);

// 录音线程：只采集
executorService.execute(() -> {
    audioRecord.startRecording();
    byte[] buffer = new byte[BUFFER_SIZE];
    while (isRecording) {
        int read = audioRecord.read(buffer, 0, BUFFER_SIZE);
        if (read > 0 && !isMuted) {
            byte[] data = new byte[read];
            System.arraycopy(buffer, 0, data, 0, read);
            if (!recordQueue.offer(data)) {
                recordQueue.poll(); // 丢弃最旧的
                recordQueue.offer(data);
            }
        }
    }
});

// 编码线程：独立处理
audioEncoderExecutor.execute(() -> {
    while (isRecording) {
        byte[] data = recordQueue.poll(10, TimeUnit.MILLISECONDS);
        if (data != null) {
            sendAudioData(data, data.length);
            updateUserWaveform(data);
        }
    }
});
```

### 1.2 AudioTrack 缓冲区优化

**当前问题：** `PLAY_BUFFER_SIZE` 使用 `getMinBufferSize() * 1`，在部分设备上可能导致音频卡顿。

```java
// 当前代码（VoiceCallActivity.java L71）
private static final int PLAY_BUFFER_SIZE = AudioTrack.getMinBufferSize(
    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 1;
```

**优化方案：** 根据设备能力动态调整缓冲区大小。

```java
private static int calculatePlayBufferSize() {
    int minSize = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
    // 使用 2 倍最小缓冲区，平衡延迟和稳定性
    // 低端设备用 4 倍防止卡顿
    return minSize * (isLowEndDevice() ? 4 : 2);
}

private static boolean isLowEndDevice() {
    return Runtime.getRuntime().availableProcessors() <= 4
        && Runtime.getRuntime().maxMemory() < 512 * 1024 * 1024;
}
```

### 1.3 波形绘制优化

**当前问题：** `WaveformView.setAmplitudes()` 每次调用都触发 `invalidate()`，高频调用会导致过度重绘。

```java
// 当前代码（WaveformView.java L40）
public void setAmplitudes(float[] amplitudes) {
    this.amplitudes = amplitudes;
    invalidate();  // 每次都重绘
}
```

**优化方案：** 限制重绘频率，使用脏标记。

```java
private long lastInvalidateTime = 0;
private static final long MIN_INVALIDATE_INTERVAL = 16; // ~60fps

public void setAmplitudes(float[] amplitudes) {
    this.amplitudes = amplitudes;
    long now = SystemClock.uptimeMillis();
    if (now - lastInvalidateTime >= MIN_INVALIDATE_INTERVAL) {
        lastInvalidateTime = now;
        invalidate();
    } else if (!hasPendingInvalidate) {
        hasPendingInvalidate = true;
        postDelayed(() -> {
            hasPendingInvalidate = false;
            invalidate();
        }, MIN_INVALIDATE_INTERVAL);
    }
}
```

### 1.4 Opus 编解码器复用

**当前问题：** 每次进入 VoiceCallActivity 都创建新的 Opus 编解码器，退出时销毁。

**优化方案：** 使用单例管理编解码器生命周期。

```java
public class OpusCodecManager {
    private static OpusCodecManager instance;
    private long encoderHandle;
    private long decoderHandle;
    private int refCount = 0;

    public static synchronized OpusCodecManager getInstance() {
        if (instance == null) instance = new OpusCodecManager();
        return instance;
    }

    public synchronized void acquire() {
        if (refCount == 0) {
            OpusUtils opus = OpusUtils.getInstance();
            encoderHandle = opus.createEncoder(16000, 1, 10);
            decoderHandle = opus.createDecoder(16000, 1);
        }
        refCount++;
    }

    public synchronized void release() {
        refCount--;
        if (refCount == 0) {
            OpusUtils opus = OpusUtils.getInstance();
            opus.destroyEncoder(encoderHandle);
            opus.destroyDecoder(decoderHandle);
            encoderHandle = 0;
            decoderHandle = 0;
        }
    }
}
```

---

## 二、按键逻辑优化

### 2.1 指令检测硬编码问题

**当前问题：** `VoiceCallActivity.updateRecognizedText()` 中使用大量 `if-else` 硬编码检测语音指令，且指令分散在 `updateRecognizedText` 和 `handleTTSMessage` 两处。

```java
// 当前代码（VoiceCallActivity.java L836-888）
if (text.contains("向前走")) { order = 'a'; }
else if (text.contains("向后走")) { order = 'b'; }
// ... 更多 else if
```

**优化方案：** 使用命令注册表模式，统一管理。

```java
// 建议创建 CommandRegistry.java
public class CommandRegistry {
    private static final Map<String, Character> STT_COMMANDS = new LinkedHashMap<>();
    private static final Map<String, Character> TTS_COMMANDS = new LinkedHashMap<>();

    static {
        // STT 指令（用户语音识别结果触发）
        STT_COMMANDS.put("向前走", 'a');
        STT_COMMANDS.put("向后走", 'b');
        STT_COMMANDS.put("向左转", 'c');
        STT_COMMANDS.put("向右转", 'd');
        STT_COMMANDS.put("停下来", 'e');

        // TTS 指令（AI回复内容触发）
        TTS_COMMANDS.put("你好", 'f');
        TTS_COMMANDS.put("展示.*抱拳", 'g');
        TTS_COMMANDS.put("展示.*功夫", 'i');
        TTS_COMMANDS.put("给.*鼓掌", 'h');
    }

    public static Character matchSttCommand(String text) {
        return matchCommand(text, STT_COMMANDS);
    }

    public static Character matchTtsCommand(String text) {
        return matchCommand(text, TTS_COMMANDS);
    }

    private static Character matchCommand(String text, Map<String, Character> commands) {
        if (text == null) return null;
        for (Map.Entry<String, Character> entry : commands.entrySet()) {
            if (text.matches(".*" + entry.getKey() + ".*")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
```

### 2.2 按钮防抖

**当前问题：** 按钮点击没有防抖处理，快速点击可能导致重复操作。

**优化方案：** 添加通用防抖工具。

```java
public class DebounceClickListener implements View.OnClickListener {
    private static final long DEBOUNCE_MS = 300;
    private long lastClickTime = 0;
    private final View.OnClickListener listener;

    public DebounceClickListener(View.OnClickListener listener) {
        this.listener = listener;
    }

    @Override
    public void onClick(View v) {
        long now = SystemClock.uptimeMillis();
        if (now - lastClickTime >= DEBOUNCE_MS) {
            lastClickTime = now;
            listener.onClick(v);
        }
    }
}

// 使用
muteButton.setOnClickListener(new DebounceClickListener(v -> toggleMute()));
hangupButton.setOnClickListener(new DebounceClickListener(v -> endCall()));
```

### 2.3 按钮状态统一管理

**当前问题：** 静音、扬声器等状态通过独立 boolean 变量管理，容易出现状态不一致。

**优化方案：** 使用状态对象统一管理通话状态。

```java
public class CallState {
    public boolean isMuted = false;
    public boolean isSpeakerOn = false;
    public boolean isRecording = false;
    public boolean isPlaying = false;
    public boolean isPreviewStarted = false;

    public void toggleMute() { isMuted = !isMuted; }
    public void toggleSpeaker() { isSpeakerOn = !isSpeakerOn; }

    public void reset() {
        isMuted = false;
        isSpeakerOn = false;
        isRecording = false;
        isPlaying = false;
        isPreviewStarted = false;
    }
}
```

---

## 三、蓝牙优化

### 3.1 连接超时处理

**当前问题：** `BluetoothService.connect()` 中没有连接超时机制，如果设备无响应会一直阻塞。

```java
// 当前代码（BluetoothService.java L144）
socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
socket.connect();  // 可能永久阻塞
```

**优化方案：** 添加连接超时。

```java
public void connect(BluetoothDevice device, ConnectCallback callback) {
    disconnect();
    executorService.execute(() -> {
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);

            // 使用 CountDownLatch 实现超时
            final boolean[] connected = {false};
            Thread connectThread = new Thread(() -> {
                try {
                    socket.connect();
                    connected[0] = true;
                } catch (IOException e) {
                    Log.e(TAG, "蓝牙连接失败", e);
                }
            });
            connectThread.start();
            connectThread.join(10000); // 10秒超时

            if (!connected[0]) {
                connectThread.interrupt();
                closeSocket();
                if (callback != null) callback.onError("蓝牙连接超时");
                return;
            }

            // ... 后续初始化
        } catch (Exception e) {
            // ...
        }
    });
}
```

### 3.2 自动重连机制

**当前问题：** 蓝牙断开后没有自动重连逻辑。

**优化方案：** 添加指数退避重连。

```java
private int reconnectAttempts = 0;
private static final int MAX_RECONNECT_ATTEMPTS = 5;
private Handler reconnectHandler = new Handler(Looper.getMainLooper());

private Runnable reconnectRunnable = () -> {
    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && lastDevice != null) {
        reconnectAttempts++;
        long delay = (long) Math.pow(2, reconnectAttempts) * 1000; // 指数退避
        Log.d(TAG, "第 " + reconnectAttempts + " 次重连，延迟 " + delay + "ms");
        reconnectHandler.postDelayed(() -> connect(lastDevice, reconnectCallback), delay);
    }
};

private final ConnectCallback reconnectCallback = new ConnectCallback() {
    @Override
    public void onConnected() {
        reconnectAttempts = 0;
        Log.d(TAG, "蓝牙重连成功");
    }

    @Override
    public void onError(String error) {
        Log.w(TAG, "蓝牙重连失败: " + error);
        reconnectHandler.post(reconnectRunnable);
    }
};
```

### 3.3 蓝牙数据发送队列

**当前问题：** `ConnectedThread.write()` 直接写入输出流，高频调用可能丢失数据。

```java
// 当前代码（ConnectedThread.java L87）
public void write(byte[] data) {
    outputStream.write(data);  // 直接写，无缓冲
}
```

**优化方案：** 使用发送队列。

```java
private final BlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<>(50);

// 在 run() 方法中添加发送循环
@Override
public void run() {
    // 启动发送线程
    new Thread(() -> {
        while (running) {
            try {
                byte[] data = sendQueue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null && outputStream != null) {
                    outputStream.write(data);
                    outputStream.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "发送失败", e);
            }
        }
    }, "BT-Send").start();

    // 接收循环（原有代码）
    byte[] buffer = new byte[1024];
    while (running) {
        // ...
    }
}

public void write(byte[] data) {
    if (!sendQueue.offer(data)) {
        sendQueue.poll();
        sendQueue.offer(data);
    }
}
```

### 3.4 蓝牙指令发送优化

**当前问题：** `VoiceCallActivity` 中直接调用 `btService.sendChar()` 发送单字符指令，没有确认机制。

**优化方案：** 添加指令去重和确认。

```java
private char lastSentCommand = 0;
private long lastCommandTime = 0;
private static final long COMMAND_DEBOUNCE_MS = 200;

private void sendBtCommand(char cmd) {
    if (!btServiceBound || btService == null) return;
    long now = System.currentTimeMillis();
    if (cmd == lastSentCommand && now - lastCommandTime < COMMAND_DEBOUNCE_MS) {
        return; // 去重
    }
    lastSentCommand = cmd;
    lastCommandTime = now;
    btService.sendChar(cmd);
}
```

---

## 四、网络连接优化

### 4.1 WebSocket 消息队列优化

**当前问题：** `WebSocketManager` 的消息队列使用固定 50ms 节流，对实时音频数据来说太慢。

```java
// 当前代码（WebSocketManager.java L51）
private static final long MESSAGE_THROTTLE_MS = 50; // 50ms间隔
```

**优化方案：** 区分消息类型，音频数据不节流。

```java
public void sendMessage(String message) {
    if (client != null && client.isOpen()) {
        QueuedMessage queuedMessage = new QueuedMessage(message);
        if (!messageQueue.offer(queuedMessage)) {
            messageQueue.poll();
            messageQueue.offer(queuedMessage);
        }
    }
}

public void sendBinaryMessage(byte[] data) {
    if (client != null && client.isOpen()) {
        // 二进制音频数据直接发送，不经过队列
        try {
            client.send(data);
        } catch (Exception e) {
            Log.e(TAG, "发送音频数据失败", e);
        }
    }
}

// 消息处理器中区分处理
private void startMessageProcessor() {
    messageExecutor.submit(() -> {
        while (isProcessingQueue.get()) {
            try {
                QueuedMessage message = messageQueue.take();
                if (client != null && client.isOpen()) {
                    if (message.isBinary) {
                        client.send(message.binaryMessage);
                        // 音频数据不等待
                    } else {
                        client.send(message.textMessage);
                        Thread.sleep(MESSAGE_THROTTLE_MS);
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    });
}
```

### 4.2 心跳机制优化

**当前问题：** WebSocket 使用 `setConnectionLostTimeout(5)` 做超时检测，但没有主动心跳，服务器可能无法及时检测客户端掉线。

**优化方案：** 添加应用层心跳。

```java
private static final long HEARTBEAT_INTERVAL = 15000; // 15秒
private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
private Runnable heartbeatRunnable = new Runnable() {
    @Override
    public void run() {
        if (isConnected()) {
            try {
                JSONObject ping = new JSONObject();
                ping.put("type", "ping");
                sendMessage(ping.toString());
            } catch (JSONException e) {
                Log.e(TAG, "心跳发送失败", e);
            }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    }
};

// 在 onConnected 中启动
public void onConnected() {
    heartbeatHandler.removeCallbacks(heartbeatRunnable);
    heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
}

// 在 disconnect 中停止
public void disconnect() {
    heartbeatHandler.removeCallbacks(heartbeatRunnable);
    // ...
}
```

### 4.3 重连策略优化

**当前问题：** WebSocket 重连使用固定 3 秒延迟，网络波动时可能频繁重连。

**优化方案：** 指数退避 + 最大重试次数。

```java
private int reconnectAttempts = 0;
private static final int MAX_RECONNECT_ATTEMPTS = 10;
private static final long BASE_RECONNECT_DELAY = 1000; // 1秒基础延迟

// 在 onClose 中
if (!isReconnecting && remote && !isUserDisconnected) {
    isReconnecting = true;
    long delay = Math.min(
        BASE_RECONNECT_DELAY * (long) Math.pow(2, reconnectAttempts),
        30000 // 最大30秒
    );
    reconnectAttempts++;
    Log.d(TAG, "第 " + reconnectAttempts + " 次重连，延迟 " + delay + "ms");
    mainHandler.postDelayed(() -> {
        isReconnecting = false;
        connect(serverUrl, token, enableToken);
    }, delay);
}

// 在 onConnected 中重置
public void onConnected() {
    reconnectAttempts = 0;
    // ...
}
```

### 4.4 连接状态同步

**当前问题：** `VoiceCallActivity` 和 `Voice` 都实现了 `WebSocketListener`，但 `WebSocketManager` 是单例，切换 Activity 时可能出现状态不一致。

**优化方案：** 在 Activity 生命周期中正确管理监听器。

```java
@Override
protected void onResume() {
    super.onResume();
    webSocketManager.setListener(this);
    // 同步当前连接状态
    if (webSocketManager.isConnected()) {
        updateCallStatus("已连接");
    }
}

@Override
protected void onPause() {
    super.onPause();
    // 不要在这里移除监听器，避免后台时丢失消息
}

@Override
protected void onDestroy() {
    webSocketManager.removeListener();
    // ...
}
```

---

## 五、内存优化

### 5.1 Activity 内存泄漏防护

**当前问题：** `VoiceCallActivity` 使用了多个匿名内部类和 lambda，虽然 Handler 使用了 WeakReference，但 OkHttpClient 的 `pingRunnable` 是非静态内部类引用。

**优化方案：** 确保所有回调都使用弱引用。

```java
// pingRunnable 已经是 final 字段，不会泄漏
// 但 onDestroy 中需要确保清理
@Override
protected void onDestroy() {
    if (mainHandler != null) {
        mainHandler.removeCallbacksAndMessages(null); // 移除所有回调
    }
    // ...
}
```

### 5.2 帧缓存内存控制

**当前问题：** `FrameCache` 存储 5 帧 YUV 数据，每帧可能达到数百 KB。

**优化方案：** 使用更小的缓存或压缩存储。

```java
private static class FrameCache {
    private static final int CACHE_SIZE = 3; // 减少到3帧

    synchronized void addFrame(byte[] yuvData, int width, int height) {
        // 只存储 JPEG 压缩后的数据，减少内存占用
        byte[] jpegData = yuvToJpeg(yuvData, width, height);
        if (jpegData != null) {
            // 存储压缩数据
            frames[writeIndex] = new FrameData(jpegData, width, height, System.currentTimeMillis());
        }
    }
}
```

### 5.3 线程池统一管理

**当前问题：** `VoiceCallActivity` 创建了 4 个独立的 `ExecutorService`，每个都是单线程。

**优化方案：** 使用共享线程池。

```java
// 替代多个独立线程池
private ExecutorService sharedExecutor = Executors.newFixedThreadPool(3,
    new ThreadFactory() {
        private int count = 0;
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "VoiceCall-" + count++);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    });
```

---

## 六、UI 优化

### 6.1 VideoView 替换为 ExoPlayer

**当前问题：** `VideoView` 不支持硬件加速和低延迟播放，且在部分设备上兼容性差。

**优化方案：** 使用 ExoPlayer 或 MediaPlayer 替代。

```java
// 如果项目已引入 ExoPlayer
private PlayerView playerView;
private ExoPlayer player;

private void initVideoPlayer() {
    player = new ExoPlayer.Builder(this).build();
    playerView.setPlayer(player);

    MediaItem mediaItem = MediaItem.fromUri(
        "android.resource://" + getPackageName() + "/" + R.raw.new_action);
    player.setMediaItem(mediaItem);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    player.setVolume(0f); // 静音播放
    player.prepare();
    player.play();
}
```

### 6.2 沉浸式模式优化

**当前问题：** 使用已弃用的 `SYSTEM_UI_FLAG_*` 常量。

**优化方案：** 使用 WindowInsetsController（API 30+）。

```java
private void setupImmersiveMode() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    } else {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
```

---

## 七、启动优化

### 7.1 VoiceCallActivity 启动优化

**当前问题：** `onCreate()` 中同步执行了 View 初始化、WebSocket 连接、音频初始化等操作。

**优化方案：** 延迟初始化非关键组件（参考 Voice.java 的做法）。

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_voice_call);

    mainHandler = new SafeHandler(this);
    initViews();       // 必须立即执行
    setupListeners();  // 必须立即执行

    // 延迟初始化
    mainHandler.sendEmptyMessageDelayed(MSG_INIT_AUDIO, 100);
    mainHandler.sendEmptyMessageDelayed(MSG_INIT_WEBSOCKET, 200);
}
```

---

## 八、优先级排序

| 优先级 | 优化项 | 预期收益 | 实现难度 |
|--------|--------|----------|----------|
| P0 | 音频录制线程分离 | 降低录音延迟 | 中 |
| P0 | 蓝牙连接超时处理 | 防止 ANR | 低 |
| P0 | WebSocket 重连指数退避 | 提升连接稳定性 | 低 |
| P1 | 指令检测重构 | 代码可维护性 | 中 |
| P1 | 按钮防抖 | 防止误操作 | 低 |
| P1 | 波形绘制优化 | 降低 CPU 占用 | 低 |
| P1 | 蓝牙自动重连 | 用户体验 | 中 |
| P2 | Opus 编解码器复用 | 减少初始化耗时 | 低 |
| P2 | 线程池统一管理 | 减少线程开销 | 中 |
| P2 | VideoView 替换 | 播放性能 | 高 |
| P2 | 帧缓存优化 | 减少内存占用 | 低 |
| P3 | 启动延迟初始化 | 加快页面打开速度 | 低 |
| P3 | 沉浸式模式适配 | 兼容新版本 Android | 低 |
