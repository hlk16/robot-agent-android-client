# 小智 Android 客户端 — 代码质量与性能审查报告

> 审查日期：2026-09-19
> 审查范围：`app/src/main/java/com/lhht/xiaozhi/`、`app/src/main/cpp/`、`app/src/main/res/`、Gradle 构建脚本、`AndroidManifest.xml`
> 审查方式：逐文件通读源码 + 交叉验证调用链，所有问题均给出文件路径与行号

---

## 0. 项目概览

| 指标 | 数值 |
|---|---|
| 主包 Java 代码行数 | 约 13,000 行（27 个文件） |
| 最大单文件 | `ChatActivity.java` 2033 行 |
| 第二/第三 | `Voice.java` 1782 行 / `VoiceCallActivity.java` 1722 行 |
| `Log.*` 调用 | 610 处 |
| 仓库 `.git` 体积 | 301 MB |
| `res/raw` 视频资源 | 22 MB |
| 依赖的 OpenCV SDK | `sdk/` 目录 1.3 GB，842 个文件入库 |

这是一个功能覆盖很广的毕设级项目（语音通话、Opus 编解码、WebSocket/MCP、WebRTC 视频、OpenCV 车道线检测、腾讯地图导航、蓝牙/BLE、MQTT 物联网）。**功能实现思路是通的，问题主要出在工程规范、资源生命周期管理和线程安全三个方面。**

---

## 一、严重问题（建议优先修复）

### 1.1 【安全】API 密钥、Token、设备标识全部明文硬编码

这是本项目**最需要优先处理**的一类问题。目前已泄露进 Git 历史的真实凭据：

**（1）讯飞开放平台密钥 —— 明文写在 `strings.xml`**

`app/src/main/res/values/strings.xml:26-28`
```xml
<string name="appid">0890b414</string>
<string name="apikey">1331bfd56fa7f45b9e13f80ff54693da</string>
<string name="apiSecret">YTQ0ZjNmNmEzMWNjNzgzZWFiNmE2Mjk5</string>
```
注意 21-24 行还留着注释 `<!-- 改成自己的讯飞的appid和apikey和apiSecret -->`，说明作者知道这是私人凭据，但仍然直接提交了。

**（2）腾讯地图密钥 —— 两处硬编码**

`app/src/main/java/com/lhht/xiaozhi/activities/SearchNaviActivity.java:65-66`
```java
// 腾讯地图API密钥 - 请替换为您的实际API密钥
private static final String TENCENT_MAP_API_KEY = "R2RBZ-JY5WT-T6LX6-LD4XP-RV3D2-RBFHS";
```
`app/src/main/AndroidManifest.xml:63-65`
```xml
<meta-data android:name="TencentMapSDK"
           android:value="XIZBZ-KKKLW-PFLRJ-YVMLV-3JANQ-XLF4U" />
```

**（3）默认 Token 是假值**

`app/src/main/java/com/lhht/xiaozhi/settings/SettingsManager.java:47,51`
```java
public String getToken() { return mmkv.decodeString(KEY_TOKEN, "test-token"); }
public boolean isTokenEnabled() { return mmkv.decodeBool(KEY_ENABLE_TOKEN, true); }
```
默认 token 为 `test-token`，且默认**启用**。任何用户首次启动都会带着这个假 token 去连服务器。

**（4）设备 MAC 地址写死，5 处重复**

```
VoiceCallActivity.java:253   String deviceId = "c0:3e:ba:2e:d5:97";
VoiceCallActivity.java:1565  .header("Device-Id", "c0:3e:ba:2e:d5:97")
Voice.java:256               String deviceId = "c0:3e:ba:2e:d5:97";
Voice.java:1587              .header("Device-Id", "c0:3e:ba:2e:d5:97")
MainActivity.java:339        String deviceId = "c0:3e:ba:2e:d5:97";
```
`Voice.java:256` 前面还粘了一个制表符（`"\tc0:3e..."`），是明显的复制粘贴残留。
device-id 是设备唯一标识，写死后**所有安装此包的设备共用同一个身份**，服务端无法区分用户。应改用 `Settings.Secure.ANDROID_ID`，或首次启动生成 UUID 并持久化。

**（5）明文存储 API 凭据**

`SettingsManager.java:36-40`
```java
public void saveApiSettings(String appId, String apiKey, String apiSecret) {
    mmkv.encode(KEY_APP_ID, appId);
    mmkv.encode(KEY_API_KEY, apiKey);
    mmkv.encode(KEY_API_SECRET, apiSecret);
}
```
MMKV 文件位于 `/data/data/<pkg>/files/mmkv`，未加密，root 设备或备份可直接读出。应改用 Android Keystore 或 `EncryptedSharedPreferences`。

**（6）内网测试地址入库**

`ChatActivity.java:130-131`
```java
private static final String SERVER_URL = "ws://192.168.0.102:8009/ws/chat/"; // 真机测试地址
private static final String WEBRTC_SERVER_URL = "ws://192.168.0.102:8009/ws/webrtc/";
```

**建议**：把密钥移入 `local.properties` + `BuildConfig` 字段（`build.gradle.kts` 中通过 `buildConfigField` 注入），并把 `local.properties`（已在 `.gitignore` 中）作为唯一的注入点。**同时必须去各平台后台轮换（重置）上述已泄露的密钥** —— 因为 Git 历史里已经存在，仅从当前代码删除是不够的。

---

### 1.2 【功能失效】导航 Binder 返回 null，整个导航链路形同虚设

`NavigationBackgroundService.java:63-72`
```java
private static final IBinder binder = new NavigationBinder();

public static class NavigationBinder extends Binder {
    public NavigationBackgroundService getService() {
        // 返回 null 而不是持有引用，避免内存泄漏
        return null;
    }
}
```

`NavigationServiceManager.java:62-66`
```java
public void onServiceConnected(ComponentName name, IBinder service) {
    NavigationBackgroundService.NavigationBinder binder =
        (NavigationBackgroundService.NavigationBinder) service;
    navigationService = binder.getService();   // 永远是 null
    isServiceBound = true;
```

作者为了"避免内存泄漏"把 Binder 的通信能力直接废掉了。后果链：

- `navigationService` 恒为 `null`
- `isServiceConnected()`（`isServiceBound && navigationService != null`）恒为 `false`
- 所有 `startNavigationToDestination` / `stopNavigation` / `getDistanceToDestination` 全部直接返回 `false` / `-1`
- `checkConnectionHealth()` 每 10 秒检测到"服务已绑定但实例为空"，触发无意义的 `handleConnectionLost()` 重连循环

**正确做法**：改为非静态内部类，返回 `NavigationBackgroundService.this`。Binder 存活期与服务一致，本身不会造成泄漏（泄漏风险在于把 Service 引用存进静态变量，而不是 Binder 内部类本身）。

---

### 1.3 【内存泄漏】`Bitmap` 在多数分支下从未 `recycle()`

`ImageRecognitionManager.java:216-230`（`convertPreviewFrameToJpeg`）
```java
if (imageBytes.length > 2 * 1024 * 1024) {
    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    out.reset();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
    imageBytes = out.toByteArray();

    if (imageBytes.length > 2 * 1024 * 1024) {
        out.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
        imageBytes = out.toByteArray();
        bitmap.recycle();          // ← 只有走到内层 if 才会执行
    }
}
```
`bitmap.recycle()` 被塞进了内层 `if`。**只要 quality=70 压缩后已经小于 2MB（这是绝大多数情况），内层 `if` 不进入，Bitmap 永不回收。** `convertYuvToJpeg`（187-199 行）有一模一样的缺陷。

这段逻辑在摄像头预览帧路径上，持续累积 native 内存直到 OOM。

同时，187 / 218 行的 `BitmapFactory.decodeByteArray` 都是**对整帧 JPEG 全尺寸解码**，没有用 `inSampleSize` 降采样。1280×720 解出来就是约 3.6 MB 的 RGBA Bitmap，仅仅为了重新压缩一遍。应该直接用 `YuvImage.compressToJpeg(..., quality, out)` 的 quality 参数一次到位，完全不需要 decode → compress 的往返。

---

### 1.4 【崩溃风险】音频对象被多线程无同步访问

`AudioTrack` 本身不是线程安全的，但在 `VoiceCallActivity` 中它被至少 4 条线程同时读写：

| 线程 | 操作位置 |
|---|---|
| `playbackExecutor` 播放线程 | `write/play`（`VoiceCallActivity.java:1059-1069`） |
| `audioExecutor` 解码线程 | `pause/flush`（`:843-845`） |
| 主线程（音频焦点回调） | `play/pause`（`:1116-1126`） |
| 主线程 `onDestroy` | `stop/release` 并置 `null`（`:552-562`） |

而控制标志位没有 `volatile`：

`VoiceCallActivity.java:113-114`
```java
private volatile boolean isRecording = false;   // 这个加了 volatile
private boolean isPlaying = false;              // 这个没有
```

典型竞态：主线程 `endCall()` 正在 `audioTrack.release()` 并把字段置 `null`，播放线程同时执行 `audioTrack.write(...)` → 对已释放对象的 native 调用，可能直接崩溃。

同类问题还有：

- `VoiceCallActivity.java:175-177` 的 `waveformBuffer` / `amplitudeBuffer` 是**复用的数组**，后台线程持续写入的同时以同一引用 `post` 给主线程（`:761`、`:1022`）。主线程绘制时读到的可能是正在被改写的半更新数据 → 波形闪烁/撕裂。
- `CameraDetectionService` 的 `FrameCache` 复用 `FrameData` 对象原地改写（`VoiceCallActivity.java:1677-1697`），`getLatestFrame()` 同步块一结束锁就释放，相机线程再写 5 帧就会覆盖同一对象，而视觉线程此时才拿它做 JPEG 编码。
- `MainActivity.java:102-106` 的 `audioTrack` / `isAudioTrackPlaying` 同样是跨线程裸字段。

**建议**：音频相关状态收敛到单线程（或用一个锁保护整个音频段），`isPlaying` 加 `volatile`，跨线程传递波形数据时**拷贝一份再交给 UI**。

---

### 1.5 【ANR / 卡顿】主线程上的重量级操作

**（1）OpenCV native 库在主线程加载**

`ChatActivity.java:563` → `:1805-1807` → `:1792-1793`
```java
// handleChatMessage（主线程）检测到导航指令后
startCameraDetectionService();
    → iniLoadOpenCV();
        → boolean loaded = OpenCVLoader.initDebug();   // native 库加载，1~3 秒
```
`OpenCVLoader.initDebug()` 加载 native so 是耗时操作，在主线程执行会直接造成明显卡顿甚至 ANR。而且该方法命名有拼写错误（应为 `initLoadOpenCV`）。

**（2）`onDestroy` 里 `Thread.sleep(100)`**

`VoiceCallActivity.java:1176-1177`（`Voice.java:1183-1186` 同样）
```java
webSocketManager.sendMessage(endMessage.toString());
Thread.sleep(100);   // 主线程睡 100ms
```
`onDestroy` 跑在主线程，这个 sleep 纯粹拖慢页面销毁。而且它**对"消息已发出"没有任何保证**（发送是异步的），纯属无效等待。

**（3）`initAudio` 注释写着"后台线程"，实际在主线程**

`Voice.java:228-229`
```java
// 延迟初始化音频组件（后台线程，避免阻塞UI）
mainHandler.sendEmptyMessageDelayed(MSG_INIT_AUDIO, 100);
```
而 `SafeHandler` 构造时是 `super(Looper.getMainLooper())`（`Voice.java:1755-1761`），所以 `initAudio()` 全程在主线程执行 —— 里面包含创建 5 个线程池、native 的 `createEncoder/createDecoder`、`new AudioTrack.Builder().build()`。**注释与实现完全矛盾。**

**（4）`ChatActivity.updateNavigationInfo()` 每 2 秒在主线程跑一次约 165 行的方法**

`ChatActivity.java:1513-1523`
```java
navigationUpdateRunnable = new Runnable() {
    public void run() {
        updateNavigationInfo();
        if (isNavigationUpdating) navigationUpdateHandler.postDelayed(this, 2000);
    }
};
```
更糟的是 `ChatActivity.java:1653`：
```java
if (isNavigating && distanceToDestination > 0) {
    etMessage.setText("成功导航");   // 每 2 秒覆盖一次用户正在输入的输入框
}
```

---

### 1.6 【线程泄漏】裸线程与永不退出的 `HandlerThread`

**（1）每次视觉请求都 `new Thread`**

`VoiceCallActivity.java:1545-1592`（`Voice.java:1567` 同样）
```java
isMcpInProgress = true;
mainHandler.post(pingRunnable);
new Thread(() -> {
    ...
    try (Response response = httpClient.newCall(request).execute()) { ... }
    ...
}).start();
```
类里已经有 4 个线程池，这里却新建裸线程。HTTP `readTimeout` 是 30 秒，用户挂断/旋转销毁 Activity 后该线程仍会跑满，且持有 `this` → 短暂泄漏 + 无效网络请求。

**（2）WebSocket 每次 `connect()` 创建两层裸线程**

`WebSocketManager.java:287-299`
```java
new Thread(() -> {
    final boolean[] connected = {false};
    Thread connectThread = new Thread(() -> {
        connected[0] = client.connectBlocking();
    });
    connectThread.start();
    connectThread.join(5000);
```
外层线程 + 内层线程，每次重连都新建。`interrupt()` 后若 `connectBlocking()` 不响应中断，线程会滞留累积。

**（3）`HandlerThread` 局部变量，永不 `quit()`**

`CameraDetectionService.java:84-86`
```java
android.os.HandlerThread backgroundThread = new android.os.HandlerThread("CameraBackground");
backgroundThread.start();
backgroundHandler = new Handler(backgroundThread.getLooper());
```
`backgroundThread` 是 `onCreate` 的**局部变量**，方法返回后引用丢失。`onDestroy`（`:122-136`）只做了 `removeCallbacksAndMessages(null)`，从不调用 `quitSafely()` → Looper 线程永不终止。配合 `START_STICKY`，服务每次重启都会再泄漏一个线程。

---

### 1.7 【网络】重连策略不对称、无退避、无上限

`WebSocketManager.java:37,255-266`
```java
private static final int RECONNECT_DELAY = 3000; // 3秒后重连
...
if (!isReconnecting && remote && !isUserDisconnected) {
    isReconnecting = true;
    mainHandler.postDelayed(() -> {
        isReconnecting = false;
        WebSocketManager.this.connect(serverUrl, token, enableToken);
    }, RECONNECT_DELAY);
}
```
三个问题：

1. **固定 3 秒，无指数退避、无抖动、无最大次数**。若服务器接受连接后立即因认证失败关闭，会每 3 秒无限循环重连，持续耗电。
2. **只有 `remote == true`（远端主动关闭）才重连**。初始连接失败（`connectBlocking()` 超时分支，`:304-315` 里是自己 `client.close()`，`remote=false`）和 `onError` 都不会触发重连 —— "断线自动恢复"实际覆盖不全。
3. `isUserDisconnected` 在主线程写、在 WebSocket 回调线程读（`:245,258`），**没有 `volatile`** → 旧连接的 `onClose` 可能读到新连接的过期状态。

`NavigationServiceManager.java:255-265` 有同类问题，而且更隐蔽：
```java
private void startReconnect() {
    if (isReconnecting || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            connectionListener.onReconnectFailed(reconnectAttempts);
            resetReconnectState();      // ← 把 reconnectAttempts 归零
        }
        return;
    }
```
`resetReconnectState()` 把计数清零，而连接监控每 10 秒跑一次 → `onReconnectFailed` **每 10 秒触发一次，永不停止**，`MAX_RECONNECT_ATTEMPTS` 形同虚设。

---

### 1.8 【磁盘 I/O】按相机帧率写 `SharedPreferences`

`CameraDetectionService.java:583-593`
```java
private void updateDataManager() {
    DataManager dataManager = DataManager.getInstance(this);
    dataManager.setRoadDistance(roadDistance);   // 每次 edit()+apply()
    dataManager.setRoadStatus(roadStatus);       // 每次 edit()+apply()
    dataManager.setDetectionActive(isDetecting); // 每次 edit()+apply()
}
```
该方法在 `processFrame()` 中被调用，即**每个相机帧触发 3 次** `SharedPreferences.edit().apply()`。看 `DataManager.java:158-190`，每个 setter 内部都是独立的 `editor.putXxx(...); editor.apply();`，`setRoadDistance` 还会额外写一次 `KEY_LAST_UPDATE` 时间戳。

`apply()` 虽然是异步落盘，但每次都会做内存 `commitToMemory` 并调度一次磁盘写。相机帧率下就是每秒几十次磁盘写入 —— 严重耗电，且可能引起卡顿。

`DataManager` 的类注释写的是"用于在 Activity 之间共享**实时**数据"，但实现用了持久化存储。实时数据应该走内存单例 + `LiveData`/回调，`SharedPreferences` 只负责真正的持久化配置。

顺带一个逻辑缺陷：`isDataValid()`（`DataManager.java:123-127`）基于共用的 `KEY_LAST_UPDATE` 判断 5 秒有效性，而 `setRoadDistance` 也会刷新它 → **道路距离的更新会让"圆形检测"被误判为数据有效**，两类业务数据相互污染。

---

### 1.9 【性能】OpenCV 逐像素 JNI 调用，单帧数万次跨语言边界

`CameraDetectionService.java:480-504`
```java
for (int y = startY; y < height; y += 5) {
    for (int x = 0; x < width / 2; x++) {
        double[] pixel = binary.get(y, x);        // 每个像素一次 JNI 调用
        if (pixel != null && pixel[0] > WHITE_PIXEL_THRESHOLD) {
            if (checkEdgeContinuity(binary, x, y, true)) {
```
`binary.get(y, x)` 是逐像素的跨 JNI 边界调用，开销极高。在 1280×720 上，外层每 5 行扫一半宽度，每个命中点还要再进 `checkEdgeContinuity`（`:521-537`，21 次 `get`）→ **单帧可达数万次 JNI 调用**，处理速度远低于实时帧率。

正解：`binary.get(row, 0, rowBuffer)` 一次性取整行到 `byte[]`，然后纯 Java 循环判断，JNI 调用次数从数万降到 720 次。

同一文件 `:343-466` 每帧还会 `new Mat` × 若干、`new byte[ySize+uSize+vSize]`（1280×720×1.5 ≈ 1.38 MB）、`new ArrayList<Point>` × 若干。且 `detectRoadLanes` 等方法的 `Mat.release()` 都写在 try 正常路径上，**异常路径下全部泄漏**（OpenCV 的 Mat 是 native 内存，GC 管不到）。`createROIMask` 只依赖固定的 width/height，却每帧重建。

---

### 1.10 【正确性】YUV_420_888 → NV21 转换忽略 stride

`CameraDetectionService.java:369-384`（`VoiceCallActivity.java:1490-1509` 同款写法）
```java
int ySize = yBuffer.remaining();
byte[] nv21 = new byte[ySize + uSize + vSize];
yBuffer.get(nv21, 0, ySize);
vBuffer.get(nv21, ySize, vSize);
uBuffer.get(nv21, ySize + vSize, uSize);
```
直接按 `remaining()` 把三个 plane 拼成 NV21，**完全没有使用 `plane.getRowStride()` 和 `plane.getPixelStride()`**。

很多设备的 Y/U/V plane 带 padding（`rowStride > width`），且 U/V 可能是半平面交错（`pixelStride == 2`）。这样拼出来的图像会**错位、偏色** —— 这是 YUV_420_888 处理的经典错误。设备兼容性会因机型而异，很难定位。

---

## 二、性能问题汇总

### 2.1 音频子系统

| # | 位置 | 问题 |
|---|---|---|
| 1 | `VoiceCallActivity.java:1050-1068` | **实时音频循环里逐帧 `Log.d`**。播放线程每写一帧都打 4 条日志（取出/初始化/开始播放/写入字节数）。日志是同步 syscall，在实时音频路径上是明显开销。`Voice.java` 同样 |
| 2 | `VoiceCallActivity.java:480-497` | **编码仍在录音线程内联执行**。`sendAudioData()`（含 byte→short 转换 + Opus 编码）直接在 `audioRecord.read()` 的循环里跑。`PERFORMANCE_OPTIMIZATION.md` 正是针对此提出的拆分方案，但在本文件中尚未落地 |
| 3 | `VoiceCallActivity.java:996-1027` | `onBinaryMessage` 每来一帧就 `audioExecutor.execute(...)`，而 `audioExecutor` 是**单线程 + 无界队列**。解码慢于来帧时队列无限增长 → 内存增长 + 延迟持续拉大，最后表现为"AI 说话越来越延迟"。队列满的日志（`:1012`）是死代码 —— `audioQueue` 也是无界 `LinkedBlockingQueue`（`:291`），`offer()` 永远返回 true |
| 4 | `VoiceCallActivity.java:841-853` | **`stopCurrentAudio()` 排在 `audioExecutor` 队列尾部**。用户打断时要等前面所有解码任务跑完才生效，打断延迟不可控。打断这类高优先级操作应该有独立的执行路径 |
| 5 | `VoiceCallActivity.java:509-521` | 每帧分配 3 个数组（`short[size/2]`、`byte[size]`、`byte[encodedSize]`）+ 一次多余的 `System.arraycopy`。`encodedBytes` 是纯冗余的二次拷贝，直接发送 `encodedData` 的有效长度即可。16kHz 下每 20~60ms 一次持续分配，GC 停顿会直接表现为音频卡顿 |
| 6 | `VoiceCallActivity.java:509-512` | 手写的 byte→short 小端转换循环，可换成 `ByteBuffer.wrap(data).order(LITTLE_ENDIAN).asShortBuffer().get(samples)` |
| 7 | `VoiceCallActivity.java:89` | `BUFFER_SIZE` 用 `AudioRecord.getMinBufferSize(...)` 的**静态初始化**。该函数失败时返回 `ERROR_BAD_VALUE`（-2），没有任何校验就传给 `AudioRecord` 构造器 |
| 8 | `VoiceCallActivity.java:93` vs `:428` | **协议不一致**：`hello` 消息里声明 `frame_duration: 60`（即 960 samples @16kHz），但 `OPUS_FRAME_SIZE = 1440`（= 90ms @16kHz）。而 native 层的 `OPUS_APPLICATION_RESTRICTED_LOWDELAY`（`opus-lib.cpp:16`）**只支持 2.5/5/10/20ms 帧长**，16000Hz 下即最大 320 samples。声明的帧长与实际编码帧长对不上 |
| 9 | `VoiceCallActivity.java:226-231` | **用 `VideoView` 循环播放背景视频作为通话背景**（`mp.setLooping(true)`）。整通话期间持续占用硬件/软件视频解码器，与音频编码、OpenCV、WebRTC 抢 CPU。属于典型的"为视觉效果牺牲性能" |
| 10 | `app/src/main/cpp/opus-lib.cpp:49` | `opus_encode(pEnc, pSamples + offset, nSampleSize, ...)` —— 指针加了 `offset`，**但长度传的仍是 `nSampleSize` 而非 `nSampleSize - offset`**（第 47 行的守卫用的是后者）。Java 侧目前恒传 `offset=0` 所以没炸，但这是埋着的越界读隐患。另外 `:51,52` 的 `ReleaseShortArrayElements(..., 0)` 用了 mode 0（拷贝回写），编码场景输入数组只读，应该用 `JNI_ABORT` 省掉每帧一次无谓拷贝 |

**Native 层还有一处静默丢帧**：`opus-lib.cpp:47-48`
```c
if (nSampleSize - offset < 320 || nByteSize <= 0) return 0;
```
`AudioRecord.read()` 返回不满一帧是常态，返回 0 后 Java 侧 `if (encodedSize > 0)` 直接跳过 —— **音频数据被静默丢弃且无日志**，表现为偶发吞字。

### 2.2 相机 / OpenCV

- `CameraDetectionService.java:343-466`：每帧分配多个 `Mat` + 1.38 MB `byte[]` + 多个 `ArrayList<Point>`（见 1.9）
- `VoiceCallActivity.java:1502`：每帧 `new byte[ySize+uSize+vSize]`，`FrameCache` 缓存 5 帧
- `VoiceCallActivity.java:1511-1521`：`compressToJpeg` 质量固定 80，无降采样
- `CameraDetectionService.java:161-190,222-228`：`cameraId` 可能为 `null`（无后置摄像头时）→ `openCamera(null)` 抛 `IllegalArgumentException`；`frameWidth/frameHeight` 可能为 0 → `ImageReader.newInstance(0,0,...)` 抛异常；`sizes[0]` 在空数组时越界

### 2.3 UI

- `ChatActivity.java:831-836`：**`addMessage` 是 O(n²)**。每次新消息都把整个历史读出来重新拼接：
  ```java
  String currentText = tvMessages.getText().toString();
  tvMessages.setText(currentText + newMessage);
  ```
  消息越多越慢，长时间会话 CPU 和内存双增长。应改用 `TextView.append()` 并限制历史条数。
- `ChatActivity.java:845-848`：`getCurrentTime()` **每次调用都 `new SimpleDateFormat`**，而它在 `addMessage`、`handleChatMessage` 等高频路径上被反复调用。应提为 `static final`（配合锁）或 `ThreadLocal`。
- `WaveformView.java:59`：`stepX = width / (amplitudes.length - 1)` —— 长度为 1 时**除零得 Infinity**。调用方 `VoiceCallActivity.java:848` 有 `new float[0]` 的情况（有 `length==0` 判空保护），但长度恰为 1 时无保护。
- `ChatActivity.java:1878-1886,1938-1940`：**每 500ms 无条件向 BLE 写一次距离数据**，无论数值是否变化，可能饱和蓝牙链路。应仅在值变化时发送。

### 2.4 构建与包体

- `app/build.gradle.kts:29`：`isMinifyEnabled = false` —— **release 完全不混淆不裁剪**。结合 1.1 的密钥硬编码，等于把凭据直接送给反编译者。
- `app/src/main/res/raw/`：22 MB 视频，其中 **`boqijiang.mp4`（16 MB）和 `reacktion.mp4`（4 MB）全项目零引用**，是纯粹的死资源
- `app/src/main/assets/fonts/NotoEmoji-Regular.ttf`：2 MB，**从未被加载**（全项目搜不到 `Typeface.createFromAsset`），也是死资源
- `sdk/`（OpenCV）：1.3 GB，842 个文件入库。`.git` 已达 301 MB。OpenCV 应作为外部 SDK 路径引用（`settings.gradle` 里指向 SDK 根目录），而不是把整个 SDK 副本提交进仓库
- `gradle.properties:26-29`：`kotlin.incremental=true` 等 Kotlin 配置项，但**项目根本没有应用 Kotlin 插件**（见 3.1）
- `gradle/libs.versions.toml` 中 `constraintlayout` 已定义，`app/build.gradle.kts:71` 却又硬编码了同样的版本 —— 版本管理方式不统一

---

## 三、代码规范问题

### 3.1 【重要】Kotlin 文件全部不参与编译，是死代码

`app/src/main/java/` 下存在 8 个 `.kt` 文件：

```
com/lhht/xiaozhi/adapters/WsUrlAdapter.kt
com/lhht/xiaozhi/models/WsUrl.kt
vip/inode/demo/opusaudiodemo/MainActivity.kt
vip/inode/demo/opusaudiodemo/adapter/WsUrlAdapter.kt
vip/inode/demo/opusaudiodemo/model/WsUrl.kt
vip/inode/demo/opusaudiodemo/presenter/DecodeOpusPresenter.kt
vip/inode/demo/opusaudiodemo/utils/OpusPlayTask.kt
vip/inode/demo/opusaudiodemo/utils/OpusRecorderTask.kt
vip/inode/demo/opusaudiodemo/utils/OpusUtils.kt
```

但 `build.gradle.kts`、根 `build.gradle.kts`、`libs.versions.toml` 中**都没有 `org.jetbrains.kotlin.android` 插件**。这些文件根本不进编译流程。

更麻烦的是同包同名冲突：

```
app/src/main/java/com/lhht/xiaozhi/adapters/WsUrlAdapter.java   ← 被实际使用
app/src/main/java/com/lhht/xiaozhi/adapters/WsUrlAdapter.kt     ← 死代码，同名
```

`com.lhht.xiaozhi.adapters.WsUrlAdapter` 在同一个包里同时以 Java 和 Kotlin 两种形式存在。**一旦有人补上 Kotlin 插件，立刻就是 duplicate class 编译错误。**

同时项目却引入了 Kotlin 依赖：`app/build.gradle.kts:96` 的 `kotlinx-coroutines-android`，以及 `lifecycle-runtime-ktx` / `viewmodel-ktx` / `livedata-ktx` —— 在纯 Java 项目里这些 `.ktx` 扩展包几乎用不上，白白增大包体。

**建议**：要么补上 Kotlin 插件并清理重复类，要么删除全部 `.kt` 文件和 Kotlin 依赖。当前"半吊子"状态最危险。

### 3.2 【重要】`vip.inode.demo.opusaudiodemo` 是整包粘贴的第三方 Demo

`app/src/main/java/vip/inode/demo/opusaudiodemo/` 下有完整的 `MainActivity.kt`、`adapter/`、`model/`、`presenter/`、`utils/` —— 这是从网上某个 Opus 音频 Demo 整个包**连包名一起复制进项目**的。

其中唯一被真正使用的是 `utils/OpusUtils.java`（`VoiceCallActivity.java:41` 导入）。其余（含它自带的 `MainActivity.kt`、`WsUrlAdapter.kt`、`DecodeOpusPresenter.kt`）全是死代码。

规范做法是把用到的 native 封装下沉到自己的包（如 `com.lhht.xiaozhi.audio.opus`），或者作为一个独立 module 引依赖，而不是把 demo 的整个包结构原样留在主包下面。

### 3.3 【重要】约 58% 的代码在 `Voice.java` 与 `VoiceCallActivity.java` 之间重复

| 文件 | 行数 |
|---|---|
| `Voice.java` | 1782 |
| `VoiceCallActivity.java` | 1722 |
| 两者**完全相同**的行 | 约 1026 行 |

`diff` 显示 `Voice.java` 只有 756 行与 `VoiceCallActivity.java` 不同 —— **超过一半的代码是逐字节相同的**。两边都有完整的：音频采集/播放线程、Opus 编解码、`FrameCache`、`SafeHandler`、`AudioFocusChangeListener`、`extractEmojiAndText`、`updateAiWaveform`、MCP 处理、`sendVisionRequest`……

这意味着**任何一个 bug 都要修两遍，而且很可能只修一遍**。事实上确实如此：

- `VoiceCallActivity.java:351` 和 `Voice.java:367` 都有同一个复制粘贴 bug：
  ```java
  previewButton.setImageResource(isPreviewStarted ? R.drawable.baseline_videocam_24 : R.drawable.baseline_videocam_24);
  ```
  两个分支是**同一个 drawable**，开关摄像头图标永远不变。
- `Voice.java:861-863` 同一个条件写了两遍：
  ```java
  if (lower.contains("unreachable") || lower.contains("unreachable")
          || lower.contains("noroutetohost") || lower.contains("noroute")) {
  ```
- `ChatActivity.java:510-511` 四个条件两两重复：
  ```java
  if (content != null && (content.contains("自动导航") || content.contains("自动导航") ||
      content.contains("[控制指令] 自动导航") || content.contains("[控制指令] 自动导航"))) {
  ```

**建议**：把音频栈（采集/播放/Opus/WebSocket 协议/视觉请求）抽成一个独立的 `VoiceCallEngine` 类，两个 Activity 各自只保留 UI 差异。这是本项目**收益最高的重构**。

### 3.4 God Class —— 职责严重超载

| 文件 | 行数 | 承担职责 |
|---|---|---|
| `ChatActivity.java` | 2033 | 聊天 + WebRTC 视频 + 导航 + OpenCV 检测服务 + 蓝牙遥控 + 权限 |
| `Voice.java` | 1782 | 音频采集/播放 + Opus 编解码 + WebSocket + MCP 视觉 + 相机 + 帧缓存 + 音频焦点 |
| `VoiceCallActivity.java` | 1722 | 同上，几乎完全重叠 |
| `NavigationBackgroundService.java` | 1065 | 导航 + 定位 + TTS + 路线规划 |

单个 Activity 承担 7 种职责，无法单元测试，改动风险极高。

**超大方法**同样是重灾区：

- `NavigationBackgroundService.java:369-553` `startNavigationWithLocation()` 约 **184 行**，其中一半是逐字段 `Log.i` 打印坐标/POI/距离
- `ChatActivity.java:1614-1780` `updateNavigationInfo()` 约 **165 行**
- `WebRTCManager.java:119-266` `startLocalVideo()` 约 **150 行**，嵌套 3 层独立 try-catch，资源释放路径极易出错（事实上确实出错了，见下）
- `NavigationBackgroundService.java:461-489` 与 `:517-544` 三段错误处理几乎逐行重复
- `ChatActivity.java:256-329`（`SearchNaviActivity`）`searchInCity` / `searchNearby` 仅 URL 和文案不同

### 3.5 死代码清单

| 位置 | 内容 |
|---|---|
| `PIDController.java` | **整个文件 131 行从未被实例化**，全项目仅命中自身定义 |
| `Voice.java:163,314` | `recordBuffer` 声明并分配 `new short[OPUS_FRAME_SIZE]`，全文件无任何读写 |
| `Voice.java:1452-1468` | `onSaveInstanceState` / `onRestoreInstanceState` 方法体只有注释掉的代码 |
| `MainActivity.java:628-643` | `handleTTSStart()` / `handleTTSSentence()` 从未被调用 |
| `MainActivity.java:236-252` | `MessageHandler.updateDisplay()` 从未被调用 |
| `MainActivity.java:101-111` | `audioRecord` / `recordBuffer` / `isRecording` 只被置位和释放，录音实际在 `Voice` 里 |
| `MainActivity.java:132-148` | `SafeHandler.handleMessage` 方法体为空 |
| `NavigationBackgroundService.java:54` | `LOCATION_TIMEOUT` 定义后从未使用 |
| `NavigationBackgroundService.java:633-646` | `getNavigationErrorMessage()` 未被调用 |
| `NavigationServiceManager.java:484-487` | `setConnectionCheckInterval()` 只打日志，`CONNECTION_CHECK_INTERVAL` 是 `static final` |
| `SearchNaviActivity.java:187-190` | `initSearch()` 纯注释占位空方法 |
| `CameraManager2.java:47-58` | `captureStillImage()` 是桩实现 —— 注释写着"示例代码省略了 Camera2 的完整实现"，**实际从未拍照**，直接调 `callback.onPhotoTaken(photoFile)` 返回一个空文件 |
| `ImageRecognitionManager.java:29,47` | `private final Context context` 存入后从未使用 |
| `ImageRecognitionManager.java:38` | `AtomicBoolean isReceivingMessage` 只被 `set()`，从未被读取判断 |
| `CameraDetectionService.java:47` | 字段 `dataManager` 赋值后从未使用 |
| `WebRTCManager.java:847-866` | `checkNetworkEnvironment()` 只打印日志 |
| `MenuActivity.java:24` | `private Button navgation;` 拼写错误且从未 `findViewById` |

### 3.6 资源未释放 / 生命周期缺陷

**（1）WebRTC 的 `SurfaceTextureHelper` 永不释放**

`WebRTCManager.java:153,171`
```java
SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
...
videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
```
`surfaceTextureHelper` 是**局部变量**，创建后没有任何引用被保存，`close()`（`:584-666`）里也没有 `dispose()` 它 → native 层纹理/EGL 资源泄漏。

`startLocalVideo` 还有多条提前 return 路径，只释放了部分资源：
```java
if (surfaceTextureHelper == null) return;   // :154-159，已创建的 videoCapturer 未 dispose
if (videoSource == null) return;            // :163-168
if (localVideoTrack == null) return;        // :176-181
```

**（2）WebRTC 重复 `addSink`**

`WebRTCManager.java:735-760`（`onAddStream`）和 `:778-798`（`onAddTrack`）里都执行了 `remoteVideoTrack.addSink(remoteVideoView)`。unified plan 下同一轨道可能两个回调都触发 → 重复绑定。且 `onRemoveStream` 里**没有对应的 `removeSink`**。

**（3）`SearchNaviActivity` 的 Response body 未关闭**

`SearchNaviActivity.java:277-289`（`searchNearby` 的 `:315-328` 相同）
```java
if (response.isSuccessful()) {
    String responseBody = response.body().string();   // 这条路径会关闭
    ...
} else {
    Log.e(TAG, "搜索请求失败，响应码: " + response.code());   // ← 这条不会
}
```
失败分支既不读也不关 `response.body()`，连接无法回收到连接池，反复搜索会耗尽连接。

**（4）`MainActivity.initAudioTrack` 泄漏旧实例**

`MainActivity.java:718-730`
```java
if (audioTrack != null) { audioTrack.stop(); }
if (audioTrack == null) { audioTrack = createAudioTrack(); }
if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
    audioTrack = createAudioTrack();     // ← 旧实例只 stop() 未 release()，直接覆盖引用
}
```
native `AudioTrack` 泄漏。且 `stop()` 后没有 `flush()`，跨 TTS 会话可能残留旧数据。

**（5）`SearchNaviActivity` 匿名 Handler 超时任务持有 Activity**

`SearchNaviActivity.java:446-451`
```java
new android.os.Handler().postDelayed(() -> {
    if (isLocationRequested) { stopLocationUpdates(); Toast.makeText(this, ...).show(); }
}, 15000);
```
匿名 `Handler` 的 `postDelayed` 在 Activity 销毁后仍会触发，Runnable 捕获 `this` → 内存泄漏 + 对已销毁 Activity 弹 Toast。

**（6）`LocationListener` 重复注册**

`NavigationBackgroundService.java:95`（`onCreate`）和 `:160`（`onStartNavi`）**都会调用** `startContinuousLocationUpdates()`，没有防重入标记。`requestHighAccuracyLocation()` 先 `removeUpdates` 再重注册，`requestNetworkLocation()` 又给同一 listener 追加 NETWORK provider —— 注册/注销状态混乱。

另外 `startLocationForNavigation`（`:297-338`）的一次性监听器**没有任何超时控制** —— `LOCATION_TIMEOUT` 定义了却没用。GPS 长时间不 fix 时监听器一直挂着，`currentLocation` 永远拿不到，导航无法启动。

**（7）三个前台服务都不调用 `stopSelf()`**

`NavigationBackgroundService` 在 `onStopNavi` / `onArrivedDestination` 后只停定位，从不 `stopSelf()`；`CameraDetectionService` 无任何停止入口。配合 `START_STICKY`，系统杀服务后会自动重启并重新拉起前台通知 —— **用户无法真正关闭**。

### 3.7 硬编码与魔法数字

- `ChatActivity.java:130-131`：`ws://192.168.0.102:8009/...` 内网地址
- `NavigationBackgroundService.java:759-808`：硬编码"北京理工大学/天安门/家/故宫"等**固定经纬度**，其中"家"固定指向某坐标
- `NavigationBackgroundService.java:432,440,471-488`：`50000`（50 公里）、`10`（10 米）、错误码 `-1/-6/-9`、精度阈值 `100/50/30` 均未定义常量
- `ImageRecognitionManager.java:187,193,216,224`：`2 * 1024 * 1024` 重复出现 4 次，压缩质量 85/70/50 也是裸数字
- `WebRTCManager.java:172`：`videoCapturer.startCapture(1280, 720, 30)` 分辨率帧率硬编码不可配
- `VoiceCallActivity.java:1306`：`.logLevel(666)` —— 讯飞 SDK 的日志级别，`666` 是"打印全部日志"，release 包里应关掉
- `SearchNaviActivity.java:296,451,606,181`：半径 `5000`、超时 `15000`、结果上限 `10`、默认中心坐标
- `SearchNaviActivity.java:90,207,240`：`searchMethod` 用 `0/1` 而非枚举或常量

### 3.8 命名与注释

- `VoiceCallActivity.java:2`：`//这是波奇酱` —— 无意义注释
- `VoiceCallActivity.java:442`：`//不知道为什么报错` —— 遗留的调试注释
- `VoiceCallActivity.java:99`：`//很可能是一个自定义的类，用于表示波形视图。...` —— 对显而易见的代码写了 2 行长注释
- `ChatActivity.java:1792`：`iniLoadOpenCV` 拼写错误（应为 `initLoadOpenCV`）
- `MenuActivity.java:24`：`navgation` 拼写错误（应为 `navigation`）
- `Voice.java:6`：`onBinaryMessaopusUtils.decodege` —— 乱码/拼写错乱
- `WebRTCManager.java:409,457,505,562,668`：注释中出现 `$$$$$$$$$$$$$$$$$$$$$$$$` 垃圾字符
- `NBIOTActivity.java:57`：`textView = findViewById(R.id.test);` —— 布局 id 叫 `test`，字段名 `textView`，实际用于显示 MQTT 消息
- `MenuActivity.java:19-24`：`mtableRobot` / `mnbIot` / `manus` / `mtext` —— 命名风格混杂，无统一前缀规则
- `SearchNaviActivity.java:65`：`// 腾讯地图API密钥 - 请替换为您的实际API密钥` —— 注释留着，密钥也留着
- `VoiceCallActivity.java:400-411`：大段被注释掉的 `onPause` 旧实现
- `VoiceCallActivity.java:726-740`：大段被注释掉的关键词分支

### 3.9 异常处理

- `MainActivity.java:473-475`：`catch (Exception e) { e.printStackTrace(); }` —— 异常被吞，无用户提示，且 `printStackTrace` 不走 Logcat 统一收集
- `SettingsManager.java:59-68`：`getWsUrls()` 解析 JSON 异常直接 `return null`，异常原因丢失，且调用方可能 NPE
- `SettingsManager.java:31-34`：`saveWsUrls(Set<String> urls)` 中 `new JSONArray(urls)`，`urls == null` 时抛 NPE，无空值保护
- `VoiceCallActivity.java:1405-1407`：依赖 `catch (Exception)` 兜底链式空指针
  ```java
  JSONObject capabilities = payload.optJSONObject("params").optJSONObject("capabilities");
  ```
  `optJSONObject("params")` 返回 `null` 时直接 NPE。属于靠异常控制流程
- `NBIOTActivity.java:131,150`：断开分支吞掉异常后仍提示"已断开"
- `SearchNaviActivity.java:649`：把 JSON 解析错误、类型转换错误、IO 错误全部合并成一个 `catch (Exception)`

### 3.10 权限与清单

**（1）`VoiceCallActivity` / `Voice` 没有录音权限守卫**

两个类都用 `@SuppressLint("MissingPermission")` 压制告警后直接创建 `AudioRecord`：
```java
// VoiceCallActivity.java:444-453
@SuppressLint("MissingPermission")
private void startRecording() {
    if (audioRecord == null) {
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, ...);
```
只有 `MainActivity.java:46-52` 申请了 `RECORD_AUDIO`。若权限被拒绝、或将来有别的入口直接拉起 `VoiceCallActivity`，会在 `AudioRecord` 构造处抛 `SecurityException` 崩溃。应在本类内做 `checkSelfPermission` 守卫。

**（2）`BluetoothActivity.loadPairedDevices` 缺少 SDK 版本判断 —— 旧系统上设备列表永远空白**

`BluetoothActivity.java:177-183`
```java
private void loadPairedDevices() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限");
        return;   // ←
    }
```
而同一个文件的 `checkPermissions()`（`:235-243`）**是有** `Build.VERSION.SDK_INT >= S` 守卫的。`BLUETOOTH_CONNECT` 是 API 31 才引入的运行时权限，在 Android 6.0–11 设备上 `checkSelfPermission` 会返回 DENIED → `loadPairedDevices` 直接 return → **已配对设备永远加载不出来**。这是明确的兼容性 bug。

**（3）Manifest 权限重复声明**

`AndroidManifest.xml:4-13`
```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />  <!-- 出现 3 次 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />              <!-- 出现 3 次 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.INTERNET" />
```
另外 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`（`:28-29`）在 `minSdk = 29` 下已经**完全无效**（Android 10 起被分区存储取代），应删除或加 `android:maxSdkVersion`。

**（4）`android:usesCleartextTraffic="true"`**

`AndroidManifest.xml:55`
```xml
android:usesCleartextTraffic="true"
```
全局允许明文 HTTP。配合代码里的 `ws://` 和 `http://` 地址，语音数据、token、图片都会明文传输。应移除该开关，改用 `network_security_config.xml` 只对特定测试域名放行。

**（5）`allowBackup="true"`**

`AndroidManifest.xml:51`。结合 1.1 中 MMKV 明文存储 API 凭据，意味着**凭据可以通过 `adb backup` 导出**。

**（6）发布配置缺失**

`app/build.gradle.kts:9`：`versionCode = 1` / `versionName = "1.0"` 从未更新；`buildTypes` 中没有配置 `signingConfig`。

### 3.11 布局与资源

- **硬编码中文文本**：`activity_chat.xml` 中有 20+ 处 `android:text="🔗 连接设置"`、`android:text="user001"`、`android:text="张三"` 等直接写死，未走 `strings.xml`，无法做多语言
- **硬编码尺寸**：65 处 `android:layout_width="<数字>dp"`
- **无障碍缺失**：`activity_voice_call.xml` 中 5 个 `ImageButton` / `ImageView` 均无 `contentDescription`
- `activity_chat.xml:80,97,116`：`user001` / `张三` / `user002` 是明显的测试占位数据，未清理

### 3.12 其它规范问题

- **平行 `ArrayList` 按 position 取值**：`BluetoothActivity.java:104-111` 与 `:208-224`，`readyDevices` 和 `btNames` 是两份手工同步的列表，`onItemClick` 直接 `get(position)`，任何不同步都导致 `IndexOutOfBoundsException`。应封装为设备对象列表 + 自定义 Adapter
- **MQTT 配置自相矛盾**：`NBIOTActivity.java:80,97`
  ```java
  String clientId = "app" + System.currentTimeMillis();   // 每次都是全新 clientId
  options.setCleanSession(false);                          // 却关闭 clean session
  ```
  每次连接生成新 clientId 又禁用 clean session → broker 端遗留大量孤儿会话/遗嘱消息。且用 `MemoryPersistence`，进程被杀后会话状态丢失，与 `cleanSession(false)` 语义完全矛盾
- **未指定字符集解码**：`NBIOTActivity.java:117` `new String(message.getPayload())` 用平台默认字符集，应显式 `StandardCharsets.UTF_8`
- **`String.format` 未指定 Locale**：`SearchNaviActivity.java:477,723`，部分 Locale 下小数分隔符是逗号，显示异常
- **URL 未编码直接拼接**：`SearchNaviActivity.java:256-258,293-297`
  ```java
  String url = "https://apis.map.qq.com/ws/place/v1/search?boundary=region(" + city + ",0)&keyword=" + keyword + "&key=" + TENCENT_MAP_API_KEY;
  ```
  中文关键词、空格、`&`、`#` 会破坏请求。应改用 `HttpUrl.Builder().addQueryParameter(...)`（自动编码）
- **`require` 链式强转**：`SearchNaviActivity.java:351-352` `double lat = (Double) data.get("lat");` —— key 缺失返回 null 或类型不符时直接抛异常
- **`getSupportActionBar()` 未判空**：`SettingsActivity.java:44-45` 直接链式调用，主题未配 ActionBar 时 NPE
- **`ConnectedThread` 空转**：`ConnectedThread.java:48-64` 若 `inputStream` 为 null 或 `read` 返回 -1/0，`while(running)` 循环体什么都不做 → **单核 100% CPU 空转**
- **蓝牙字节流按 String 拼接**：`ConnectedThread.java:52` `new String(buffer, 0, bytes)` —— 每次 `read` 边界可能落在 UTF-8 多字节字符中间，产生乱码
- **蓝牙写入无同步**：`BluetoothService.java:41-43` 的 `socket` / `connectedThread` 在 executor 线程赋值、主线程读取，字段非 `volatile`；`ConnectedThread.write()` 的 `outputStream` 可被主线程和 executor 线程并发写，**无任何同步**，蓝牙数据帧可能交错损坏
- **`release` 时 `isPlaying` 未复位**：`WebRTCManager.java:676-685` 把 ICE 的 `DISCONNECTED`（通常是瞬时状态，网络抖动后可恢复）与 `FAILED` 一起处理并触发 `onDisconnected()` → 上层过早拆线。`restartIce()`（`:830-844`）定义了却从未调用
- **`render` 回调线程不一致**：`ImageRecognitionManager.java:116-121` 的 `onLLMError` 直接在 LLM 回调线程调用 `callback.onRecognitionError`，而 `onRecognitionResult` 走 main looper（`:129-136`）—— 若回调实现更新 UI，error 路径会崩溃
- **`onRequestPermissionsResult` 未校验长度**：`ChatActivity.java:882-883` 按 `permissions.length` 循环却直接索引 `grantResults[i]`，且整个回调无 try/catch

---

## 四、修复优先级建议

### P0 — 安全，应立即处理

| # | 问题 | 位置 |
|---|---|---|
| 1 | 讯飞 appid/apikey/apiSecret 明文入库 | `strings.xml:26-28` |
| 2 | 腾讯地图密钥 ×2 明文入库 | `SearchNaviActivity.java:66`、`AndroidManifest.xml:63` |
| 3 | 默认 token 为 `test-token` 且默认启用 | `SettingsManager.java:47,51` |
| 4 | **去各平台后台轮换上述已泄露的密钥**（Git 历史已含，删代码不够） | — |
| 5 | MAC 地址写死，5 处重复 | 见 1.1(4) |
| 6 | `allowBackup=true` + MMKV 明文存 API 凭据 | `AndroidManifest.xml:51`、`SettingsManager.java:36-40` |
| 7 | `usesCleartextTraffic=true` | `AndroidManifest.xml:55` |

### P1 — 功能失效 / 崩溃 / 内存泄漏

| # | 问题 | 位置 |
|---|---|---|
| 8 | 导航 Binder 返回 null，导航链路完全失效 | `NavigationBackgroundService.java:63-72` |
| 9 | `Bitmap` 未 recycle（OOM） | `ImageRecognitionManager.java:216-230,187-199` |
| 10 | 音频对象跨线程竞态，可能 native 崩溃 | 见 1.4 |
| 11 | `HandlerThread` 永不 quit，线程泄漏 | `CameraDetectionService.java:84-86` |
| 12 | `SurfaceTextureHelper` 永不 dispose | `WebRTCManager.java:153,171` |
| 13 | `ConnectedThread` 空转 100% CPU | `ConnectedThread.java:48-64` |
| 14 | 蓝牙旧系统上设备列表永远空白（缺 SDK 判断） | `BluetoothActivity.java:177-183` |
| 15 | `SearchNaviActivity` 失败分支不关 Response body | `SearchNaviActivity.java:277-289,315-328` |

### P2 — 性能

| # | 问题 | 位置 |
|---|---|---|
| 16 | OpenCV 逐像素 JNI 调用（单帧数万次） | `CameraDetectionService.java:480-537` |
| 17 | 按相机帧率写 SharedPreferences | `CameraDetectionService.java:583-593` |
| 18 | `addMessage` O(n²) 字符串拼接 | `ChatActivity.java:831-836` |
| 19 | OpenCV native 库在主线程加载 | `ChatActivity.java:563→1793` |
| 20 | 实时音频路径逐帧 `Log.d` | `VoiceCallActivity.java:1050-1068` |
| 21 | 无界音频队列 + 打断消息排在队尾 | `VoiceCallActivity.java:996-1027,841-853` |
| 22 | YUV 转换忽略 stride，图像错位偏色 | `CameraDetectionService.java:369-384` |
| 23 | 背景 `VideoView` 循环播放占解码器 | `VoiceCallActivity.java:226-231` |
| 24 | `Thread.sleep(100)` 在 `onDestroy` 主线程 | `VoiceCallActivity.java:1176` |
| 25 | 每 500ms 无条件写 BLE | `ChatActivity.java:1878-1886` |

### P3 — 工程规范 / 可维护性

| # | 问题 | 收益 |
|---|---|---|
| 26 | 抽取 `VoiceCallEngine`，消除 1026 行重复 | 收益最高的重构 |
| 27 | 删除全部 `.kt` 文件与 Kotlin 依赖（或补上插件） | 消除潜在编译冲突 |
| 28 | 清理 `vip.inode.demo.opusaudiodemo` 死代码包 | — |
| 29 | 删除 `PIDController.java` 等死代码（见 3.5） | — |
| 30 | 删除 `boqijiang.mp4`(16MB) / `reacktion.mp4`(4MB) / `NotoEmoji.ttf`(2MB) | 包体 -22MB |
| 31 | `sdk/` OpenCV 改为外部引用，不进仓库 | 仓库 -1.3GB |
| 32 | 开启 `isMinifyEnabled`，配置 `signingConfig` | 包体 + 安全 |
| 33 | 硬编码文本移入 `strings.xml`，补 `contentDescription` | 可维护性 + 无障碍 |
| 34 | 拆分 God Class 与 184/165/150 行的超长方法 | 可测试性 |

---

## 五、值得肯定的地方

问题写了很多，但也有不少设计是**对的**，重构时不要一起改掉：

1. **`SafeHandler` + `WeakReference`**（`VoiceCallActivity.java:1708-1722`、`Voice.java:1755`、`MainActivity.java:132`）—— 正确避免了 Handler 持有 Activity 导致的标准泄漏
2. **`WebSocketManager` 用 `WeakReference<WebSocketListener>`**（`WebSocketManager.java:31`）—— 单例持有监听器的正确做法
3. **`AudioFocusChangeListener` 用静态内部类 + 弱引用**（`VoiceCallActivity.java:1100-1132`）—— 音频焦点管理的规范实现
4. **`WsUrlAdapter` 的 `TextWatcher` 复用前先 remove**（`WsUrlAdapter.java:101-104`）—— RecyclerView 复用的经典坑，处理是正确的
5. **`shutdownExecutor()` 封装了 `awaitTermination` + `shutdownNow` 兜底**（`VoiceCallActivity.java:1225-1235`）—— 优雅关闭线程池的正确姿势
6. **地图 Marker 通过 `clearSearchMarkers()` 正确 `remove()`**（`SearchNaviActivity.java:413-418`）
7. **`DataManager` / `NavigationServiceManager` 单例内部用了 `getApplicationContext()`**（`DataManager.java:37`、`NavigationServiceManager.java:104`）—— 避免了单例持 Activity 的泄漏
8. **网络请求全部走 OkHttp `enqueue` 异步**（`SearchNaviActivity.java:266` 等），没有发现主线程同步网络调用
9. **`MqttConnectOptions` 设置了 `setAutomaticReconnect(true)`**，MQTT 层有断线自愈意识
10. **`AudioTrack` 用了 `PERFORMANCE_MODE_LOW_LATENCY` + `FLAG_LOW_LATENCY`**（`VoiceCallActivity.java:311,320`），并用 `WRITE_BLOCKING` 而非忙等 —— 低延迟音频的设计方向是对的
11. **`FrameCache` 用 `synchronized` 保护了索引读写**，环形缓冲的设计思路正确（问题只在于返回了共享可变对象）
12. **已经引入了 LeakCanary**（`app/build.gradle.kts:112`）—— 说明作者有内存意识，只是还没跑出一轮完整的修复

---

## 六、几点整体建议

1. **先做安全整改**（P0）。密钥已在 Git 历史中，无论仓库公开与否，都应视为已泄露并立即轮换。之后把密钥注入点统一收敛到 `local.properties` → `BuildConfig`。

2. **建立"一份代码"原则**。`Voice.java` 与 `VoiceCallActivity.java` 的 1026 行重复是当前最大的技术债 —— 它已经在实际产生"修一处漏一处"的 bug（摄像头图标、重复条件判断都是证据）。

3. **引入静态检查工具**。项目已有 LeakCanary，建议再补上 `androidx.lint`（`lintOptions { abortOnError true }`）和 `.editorconfig`。610 处 `Log.*` 和大量魔法数字都能被 linter 自动标出。

4. **音频/相机这类实时路径要单独对待**。实时路径上的 `Log.d`、数组分配、跨线程共享可变状态，都是会直接表现为"卡顿/杂音/闪烁"的问题，普通代码审查容易漏掉，建议加一轮 systrace / perfetto 实测。

5. **清理死代码和死资源应该先做**。删掉 `.kt` 文件、`vip.inode.demo` 包、`PIDController`、22MB 无用视频和 1.3GB 的 OpenCV 副本，能立刻降低后续所有改动的认知负担 —— 而且这些改动零风险。

---

*本报告基于对源码的逐文件通读，所有行号均对应审查时的代码版本（分支 `dev`）。*
