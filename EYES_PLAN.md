# 表情眼睛（Nomi 风格）实现计划

> 目标页面：`VoiceCallActivity` / `activity_voice_call.xml`
> 撰写日期：2026-10-02
> 状态：**Phase 1、Phase 2 已实现**；Phase 3（人脸追踪）、Phase 4（音频联动）未开工

---

## 实现记录（与计划的偏差）

- **Phase 1**：眼型最初用 `drawRoundRect` 画圆角矩形胶囊。计划 §5.1 里写的"圆角半径是关键参数"就是这个阶段。
- **Phase 2 改动**：圆角矩形**表达不了弯月眼**，所以改成了「上眼睑 + 下眼睑两条三次贝塞尔围成的闭合轮廓」。
  做法是让两条曲线在眼角处的切线都指向竖直方向，轮廓因此处处平滑、两端自然收成带点尖的眼角。
  好处是**所有形态参数连续**：弯月眼不是另一套画法，只是"高度压扁 + 两端下沉"的同一个轮廓，
  所以情绪之间可以平滑插值，不需要在两个图元之间做 cross-fade。
  计划 §5.2 的代码骨架是按圆角矩形写的，看代码以 [EyeView.java](app/src/main/java/com/lhht/xiaozhi/views/EyeView.java) 为准。
- **光晕**：因为形状变成了 Path，原来的"放大矩形"改成 `canvas.scale()` 后重绘同一个 Path。

---

## 一、要做什么

在视频通话页加一个「眼睛」控件，替代现在那个 200sp 的 emoji：

1. 一对白色发光眼，黑底、无眼白，形状能连续变形
2. 会**眨眼**（随机节奏，不是机械定时）
3. 会**追人脸** —— 用 OpenCV 的人脸级联分类器检测人脸位置，眼睛看向用户
4. 会**表达情绪** —— 复用服务端 TTS 消息里已经带下来的 emoji
5. （可选）**瞳孔随说话音量缩放** —— 复用已有的 AI 波形振幅

---

## 二、现状盘点

先把已有的家底盘清楚，避免重复造轮子。

### 2.1 OpenCV 已经接好了

| 项 | 位置 | 说明 |
|---|---|---|
| OpenCV 版本 | [sdk/build.gradle](sdk/build.gradle) | **4.5.0**，以本地模块 `:sdk` 接入 |
| 依赖声明 | [app/build.gradle.kts:106](app/build.gradle.kts#L106) | `implementation(project(":sdk"))` |
| 初始化方式 | [ChatActivity.java:1793](app/src/main/java/com/lhht/xiaozhi/activities/ChatActivity.java#L1793) | `OpenCVLoader.initDebug()`，懒加载 |
| 已有用例 | [CameraDetectionService.java](app/src/main/java/com/lhht/xiaozhi/services/CameraDetectionService.java) | 后置摄像头 + Camera2 + 车道线检测 |
| 级联文件 | [sdk/etc/haarcascades/](sdk/etc/haarcascades/) | **20+ 个已就位** |

现成的级联文件里，我们要的两个都在：

- `haarcascade_frontalface_default.xml` —— 正脸检测
- `lbpcascade_frontalface_improved.xml` —— LBP 版本，**比 Haar 快数倍**，见 §7.1

### 2.2 ⚠️ 级联文件不会进 APK（这是第一个拦路虎）

`sdk/build.gradle` 的 sourceSets 只声明了：

```groovy
jniLibs.srcDirs = ['native/libs']
java.srcDirs   = ['java/src']
res.srcDirs    = ['java/res']
```

**没有 `assets`，也没有 `resources`**。`etc/haarcascades/*.xml` 只是躺在源码目录里，打包时会被整个忽略。直接写 `new CascadeClassifier("haarcascade_frontalface_default.xml")` 一定加载失败。

解决办法见 §4.1。

### 2.3 相机流已经在跑了，不要另开一路

[VoiceCallActivity.java:1371](app/src/main/java/com/lhht/xiaozhi/activities/VoiceCallActivity.java#L1371) 的 `startContinuousFrameCapture()` 已经用 CameraX 开了**前置摄像头**的 `ImageAnalysis`，按 `FRAME_CAPTURE_INTERVAL_MS` 抽帧塞进 `FrameCache`，供视觉大模型的 `capture_photo` 用。

人脸检测**必须复用这条流**。再开一路相机会：抢摄像头（同设备多路会直接开失败）、多一份功耗、多一份权限风险。

### 2.4 情绪通道已经有了

服务端在 TTS 消息里带 emoji，客户端用 [extractEmojiAndText()](app/src/main/java/com/lhht/xiaozhi/activities/VoiceCallActivity.java#L977) 拆出来，再交给 [showEmoji()](app/src/main/java/com/lhht/xiaozhi/activities/VoiceCallActivity.java#L1001) 塞进 `emojiText`。

**这就是眼睛情绪状态的现成输入源**，不需要后端配合改造。

### 2.5 布局落点

[activity_voice_call.xml:33](app/src/main/res/layout/activity_voice_call.xml#L33) 的 `emojiText`，约束在 `aiResponseContainer` 和 `userInputContainer` 之间、水平居中。眼睛控件就放这个位置，**直接把 `emojiText` 换掉**（`showEmoji`/`hideEmoji` 改为驱动眼睛）。

---

## 三、架构

### 3.1 分层

```
┌─────────────────────────────────────────────────────┐
│  EyeView (View)                                     │
│  只负责「画」。不含检测、不含网络。                  │
│  内部维护 EyeState：注视点 / 眨眼相位 / 情绪 / 瞳孔  │
│  用 Choreographer 逐帧插值 + invalidate              │
└─────────────────────────────────────────────────────┘
        ▲ setGazeTarget(x, y)      ▲ setEmotion(e)
        │                          │
┌───────┴──────────┐   ┌───────────┴────────────────┐
│  FaceTracker     │   │  WebSocket TTS 消息         │
│  OpenCV 级联      │   │  （已有 emoji）             │
│  自己的单线程池   │   └────────────────────────────┘
└───────▲──────────┘
        │ 每隔 N 帧喂一张
┌───────┴──────────────────────────────────────────────┐
│  CameraX ImageAnalysis（已有，勿新建）                │
│  VoiceCallActivity.startContinuousFrameCapture()      │
└───────────────────────────────────────────────────────┘
```

### 3.2 新增文件

| 文件 | 职责 | 预估行数 |
|---|---|---|
| `app/src/main/java/com/lhht/xiaozhi/views/EyeView.java` | 绘制 + 动画状态机 | ~400 |
| `app/src/main/java/com/lhht/xiaozhi/views/EyeState.java` | 纯数据的状态模型（可单测） | ~80 |
| `app/src/main/java/com/lhht/xiaozhi/face/FaceTracker.java` | 封装 CascadeClassifier，吃 Mat 吐人脸矩形 | ~150 |
| `app/src/main/java/com/lhht/xiaozhi/face/FaceDetectorHolder.java` | 级联文件从 assets 释放到 filesDir + 单例加载 | ~60 |
| `app/src/main/assets/haarcascade_frontalface_default.xml` | 从 sdk 复制 | — |

**不动**：`CameraDetectionService`（那是后置摄像头的车道线检测，与人脸无关，共用 OpenCV 的 native 库不冲突）。

---

## 四、关键技术点

### 4.1 级联文件加载

**第一步先解决这个，否则后面全白搭。**

把 `sdk/etc/haarcascades/haarcascade_frontalface_default.xml` 复制到 `app/src/main/assets/`，运行时释放到 `filesDir`：

```java
public final class FaceDetectorHolder {
    private static final String CASCADE = "haarcascade_frontalface_default.xml";
    private static volatile CascadeClassifier instance;

    /** 首次调用会做一次 30KB 左右的文件拷贝，之后直接复用。 */
    public static CascadeClassifier get(Context ctx) {
        if (instance == null) {
            synchronized (FaceDetectorHolder.class) {
                if (instance == null) {
                    File f = new File(ctx.getFilesDir(), CASCADE);
                    if (!f.exists()) {
                        try (InputStream in = ctx.getAssets().open(CASCADE);
                             OutputStream out = new FileOutputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } catch (IOException e) {
                            Log.e("FaceDetector", "释放级联文件失败", e);
                            return null;
                        }
                    }
                    CascadeClassifier c = new CascadeClassifier(f.getAbsolutePath());
                    if (c.empty()) {           // ⚠️ 必须查，CascadeClassifier 加载失败不抛异常，只变 empty
                        Log.e("FaceDetector", "级联文件加载失败: " + f.getAbsolutePath());
                        return null;
                    }
                    instance = c;
                }
            }
        }
        return instance;
    }
}
```

> ⚠️ `CascadeClassifier` 加载失败**不抛异常**，只会让 `empty()` 返回 `true`。不检查的话会在 `detectMultiScale` 里静默返回空结果，很难查。

**备选方案**：给 `sdk/build.gradle` 加 `sourceSets.main.resources.srcDirs += ['etc']`，让 xml 进 aar/jar，再用 `ClassLoader.getResourceAsStream()` 读。不推荐 —— 路径不稳、还让 sdk 模块偏离上游 OpenCV 的原始结构，以后升级 SDK 会冲突。

### 4.2 性能预算 —— 最容易翻车的地方

**Haar 级联很慢。** 720p 单帧在中低端机上 `detectMultiScale` 可能吃 **30～80ms**。如果直接在 `cameraExecutor` 里同步跑，会：

- 拖慢整条 camera pipeline（预览掉帧、`capture_photo` 取到的帧变旧）
- 和视觉大模型的抽帧抢同一个线程
- 连带把 UI 卡顿（如果哪一步不小心 post 回主线程）

**四条硬性约束，一条都不能省：**

| 措施 | 做法 | 收益 |
|---|---|---|
| **降采样** | 先把帧缩到宽 320px 再检测，检测框按比例映射回原尺寸 | 耗时降一个数量级（像素数降到 1/5） |
| **限频** | 人脸位置 5～10fps 足够；平滑靠 EyeView 插值补 | 再降 3～6 倍 |
| **独立线程** | `FaceTracker` 用自己的 `newSingleThreadExecutor()` | 不占用 `cameraExecutor` |
| **跳帧不排队** | 上一帧还在检测就直接丢弃新帧（`AtomicBoolean` 标志位） | 防止队列堆积导致检测结果越来越滞后 |

推荐参数：

```java
Mat small = new Mat();
Imgproc.resize(gray, small, new Size(320, 320 * gray.rows() / gray.cols()));

MatOfRect faces = new MatOfRect();
classifier.detectMultiScale(
    small, faces,
    1.1,    // scaleFactor：1.1 是速度/召回率的标准折中，再小会明显变慢
    5,      // minNeighbors：5 能滤掉大部分误检
    0,      // flags
    new Size(40, 40)  // minSize：按降采样后的尺寸算，约等于原图 90x90
);
```

### 4.3 坐标系与镜像（容易搞反，必须单独验证）

前置摄像头有**两层**坐标系差异：

1. **Android 前置摄像头给的原始帧是未镜像的**（跟照镜子看到的方向相反）
2. **CameraX 的 `PreviewView` 默认会镜像显示**（自拍预览看起来才自然）

如果直接把 `ImageAnalysis` 的检测框当成屏幕坐标用，眼睛会**左右反向** —— 人往左移，眼睛往右看。这个 bug 肉眼一看就懂，但改的时候容易来回试。

**做法**：在 `FaceTracker` 输出时统一归一化到 `[-1, 1]`，并且**只在这一个地方**处理镜像：

```java
// 前置摄像头：原始帧未镜像，取负号翻到「用户视角」
float nx = -(cx / (float) frameWidth  * 2f - 1f);
float ny =  (cy / (float) frameHeight * 2f - 1f);
```

然后在 `EyeView` 里假设**已经是用户视角**，不再做任何翻转。一路只翻一次，出问题好定位。

### 4.4 平滑 —— 不做的话看起来像抽搐

原始检测结果帧间抖动很大（Haar 的框会跳几个像素）。而且**检测不到人脸时不能瞬间回正**，那会比不追踪还难看。

```java
// 1. 一阶低通，alpha 越小越"懒"
gazeX += (targetX - gazeX) * 0.15f;

// 2. 丢失容忍：连续 N 帧检测不到才开始回中，且回中要慢
if (faceFound) missCount = 0; else missCount++;
if (missCount > LOST_TOLERANCE) {   // 建议 15 帧 ≈ 0.5 秒
    targetX *= 0.92f;               // 缓慢回正，不是瞬间归零
    targetY *= 0.92f;
}
```

**眼球运动本身也有物理规律可循**：真眼球的 saccade（快速扫视）是**先快后慢**的，不是匀速。用 ease-out 曲线比线性插值自然得多。

### 4.5 眨眼 —— 不要用「上下缩放」偷懒

真人的眨眼：平均 **3～5 秒**一次，一次持续 **100～150ms**，而且**上眼睑下落比抬起快**。

最常见的错误实现是把整个眼型沿 Y 轴压扁。真眼睛不是这样 —— 上眼睑往下盖，下眼睑基本不动。所以眼型要用两条独立的曲线画：

```
上眼睑：二次贝塞尔，控制点决定弧度
下眼睑：二次贝塞尔，控制点决定弧度
眨眼：只动上眼睑的控制点，下眼睑保持

闭眼瞬间：上眼睑控制点压到下眼睑位置
```

另外**在情绪切换、视线大幅移动时可以主动触发一次眨眼**（真人就是这么做的），能明显提升"活着"的感觉。

---

## 五、EyeView 绘制方案

### 5.1 视觉风格

参照用户给的 Nomi 参考图：

- 纯黑底，**没有眼白**，眼睛本身就是一块白色发光形状
- 常态是圆角矩形 / 竖椭圆
- 高兴时变成**弯月形**（∩，参考图左边那只）
- 圆角半径是关键参数 —— 太大变圆眼，太小变方框，中间那段才有"表情"

### 5.2 骨架

```java
public class EyeView extends View {

    // ---- 外部输入 ----
    private float targetGazeX, targetGazeY;   // [-1, 1]，用户视角
    private Emotion emotion = Emotion.NEUTRAL;
    private float mouthAmplitude;             // 可选：说话音量 [0,1]

    // ---- 内部状态（逐帧演进）----
    private float gazeX, gazeY;               // 平滑后的实际注视点
    private float lidOpen = 1f;               // 1=睁眼 0=闭眼
    private float blinkPhase = -1f;           // <0 表示没在眨
    private long lastBlinkAt;
    private long nextBlinkDelayMs;

    private final Path lidPath = new Path();  // 复用，别在 onDraw 里 new

    // 逐帧驱动，不用 ValueAnimator 堆一堆对象
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        step(frameTimeNanos);
        invalidate();
        if (isAnimating()) Choreographer.getInstance().postFrameCallback(this.frameCallback);
    };

    @Override
    protected void onDraw(Canvas canvas) {
        // 1. 算左右眼的几何（位置、宽高、圆角半径、眼睑弧线）
        // 2. 瞳孔/高光偏移 = gaze * maxOffset
        // 3. 画左右眼
    }
}
```

**要点**：

- **不要在 `onDraw` 里 `new Path()` / `new Paint()`** —— 这个项目刚在 `WaveformView` 上踩过一模一样的坑（1440 段 Path 每帧重建把主线程拖垮）。`Path` 复用 + `reset()`。
- **动画用 `Choreographer` 而不是 `ValueAnimator`** —— 眼睛的状态是连续的（注视点、眼睑开合、情绪插值），用一堆并行的 `ValueAnimator` 会很难协调，而且每帧要跑多个 evaluator。
- **`onDetachedFromWindow` 里 `removeFrameCallback`**，否则页面退出后还在烧电。这个是硬性要求，LeakCanary 已经在依赖里了，会直接报。

### 5.3 情绪来源（已按实际协议修正）

**原计划写的是"解析服务端发的 emoji"，这是错的。** 服务端消息里本来就有结构化的
`emotion` 字段（`{"type":"llm", "text":"😊", "emotion":"happy"}`），
客户端完全不需要从正文里猜。

所以现在：

- **主路径**：`onMessage` 里读 `emotion` 字段，`EyeView.Emotion` 的**枚举名与服务端取值一一对应**（21 个对 21 个），直接 `valueOf`，不维护第二张映射表。
- **兜底**：这条消息没有 `emotion` 字段时，才退回解析正文里的 emoji。
- 认不出来的值一律回落 `NEUTRAL`，**不 crash、不卡住**。

服务端取值（21 个）：

| | | | |
|---|---|---|---|
| `neutral` 😶 | `happy` 🙂 | `laughing` 😆 | `funny` 😂 |
| `loving` 😍 | `kissy` 😘 | `confident` 😏 | `cool` 😎 |
| `winking` 😉 | `silly` 😜 | `sad` 😔 | `angry` 😠 |
| `crying` 😭 | `surprised` 😲 | `shocked` 😱 | `embarrassed` 😳 |
| `thinking` 🤔 | `confused` 🙄 | `relaxed` 😌 | `sleepy` 😴 |
| `delicious` 🤤 | | | |

具体的形态参数在 [EyeView.java](app/src/main/java/com/lhht/xiaozhi/views/EyeView.java) 的 `applyEmotion()` 里，
每种情绪五个数：`lidOpen` / `widthScale` / `heightScale` / `bow` / `tilt` / `offsetY`。

---

## 六、分阶段实施

按「每阶段都能单独编译、单独真机验收」来切，跟你一贯的节奏一致。

### Phase 1 —— 静态眼睛 + 眨眼（不追踪、不接情绪）

- 新建 `EyeView`，放进 `activity_voice_call.xml` 替换 `emojiText`
- 实现绘制、随机眨眼、idle 呼吸感
- **验收**：进页面能看到一对会眨的眼睛，退出页面不掉帧、不泄漏

**这一阶段不碰 OpenCV，风险为零。**

### Phase 2 —— 接情绪

- `showEmoji()` / `hideEmoji()` 改为驱动 `EyeView.setEmotion()`
- 实现 §5.3 映射表
- **验收**：让 AI 说几句带不同 emoji 的话，看眼睛表情跟着变

### Phase 3 —— 接人脸追踪

- 复制级联文件到 assets，实现 `FaceDetectorHolder` + `FaceTracker`
- 挂进 `startContinuousFrameCapture()` 的 `ImageAnalysis` 回调
- **验收**：开着摄像头预览，人脸左右移动，眼睛跟着看；人脸移出画面，眼睛缓慢回中

**这一阶段是风险集中区**，重点验证 §4.2 的性能和 §4.3 的镜像。

### Phase 4（可选）—— 音频联动

- 把 AI 波形的振幅喂给 `mouthAmplitude`，瞳孔随音量缩放
- 数据源已经有了：`updateAiWaveform()` 里算出来的峰值

---

## 七、风险与备选

### 7.1 Haar 级联的固有短板

| 场景 | Haar 表现 |
|---|---|
| 正脸、光线正常 | 好 |
| 侧脸 / 低头 | **差**，基本检不到 |
| 逆光、暗光 | 差 |
| 戴眼镜 | 一般（有反光时更差） |

**备选一（推荐先试）**：换 `lbpcascade_frontalface_improved.xml`。LBP 特征比 Haar 快数倍，准确率接近，**文件已经在 `sdk/etc/lbpcascades/` 里了**，零成本切换。

**备选二**：MediaPipe Face Detection（Google）。又快又准，正面/侧面都行，但**要引入新依赖**，且和你现在的 OpenCV 路线是两套东西。除非 Haar/LBP 实测真的不能用，否则不建议现在就上。

### 7.2 和视觉大模型抢资源

`ImageAnalysis` 这条流同时服务：
- `FrameCache` → 视觉大模型 `capture_photo`
- 人脸追踪（新增）

两者都吃 CPU。`FRAME_CAPTURE_INTERVAL_MS` 控制的是抽帧频率，人脸检测要**另设一个独立频率**，不要绑在一起 —— 否则调其中一个会影响另一个。

### 7.3 隐私

人脸检测**全程本地**，OpenCV 只输出人脸矩形坐标，不存图、不上传。这一点要在代码注释里写清楚，避免以后有人误加埋点。

### 7.4 前置摄像头被关掉时

用户点 `previewButton` 关掉摄像头预览后，`ImageAnalysis` 会跟着解绑（见 [toggleCameraPreview](app/src/main/java/com/lhht/xiaozhi/activities/VoiceCallActivity.java#L346)）。这时人脸追踪应该**优雅降级**：没有帧喂进来就自然走 §4.4 的「丢失容忍」路径，眼睛缓慢回中。**不要报错、不要留个空检测框。**

---

## 八、待确认

开工前需要你拍板的几件事：

1. **眼睛要不要完全替换 `emojiText`？** 还是两者并存（emoji 平时隐藏，只在某些场景露出）？
2. **眼睛放在页面什么位置？** 现在 `emojiText` 的位置（AI 回复区下方、输入区上方、居中），够不够大？参考图里眼睛占了整个设备面板，手机上可能想让它们更醒目（比如居中的一大块）。
3. **Phase 1 先做，还是一次把 Phase 1+2 一起做？** 建议先做 Phase 1，真机看一眼风格对不对，再往下走 —— 视觉的东西在真机上和想象中差别很大。
