package com.lhht.xiaozhi.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import java.util.Locale;

/**
 * Nomi 风格的表情眼睛。
 *
 * <p>没有眼白、没有瞳孔 —— 参考图里的机器人眼睛本身就是一块纯白形状，
 * 所有"表情"都靠这一个形状的变化表达。
 *
 * <p><b>眼型怎么画</b>：不是圆角矩形，而是「上眼睑一条三次贝塞尔 + 下眼睑一条三次贝塞尔」
 * 围成的闭合轮廓。关键是让两条曲线在眼角处的切线都指向竖直方向，
 * 于是轮廓在每个眼角都平滑过渡、不会出现尖角，
 * 而两端自然收成一个带点尖的眼角。
 *
 * <p>这么做的收益是<b>所有形态参数都连续</b>：弯月眼（∩）不是另一套画法，
 * 只是"高度压扁 + 两端下沉"的同一个轮廓，所以情绪之间可以平滑插值，不会跳。
 *
 * <p>动画用 {@link Choreographer} 逐帧驱动，而不是堆一堆 ValueAnimator：
 * 注视点、眼睑开合、情绪插值这些状态是连续演进的，用并行的动画器很难协调，
 * 而且每帧要跑多个 evaluator。
 */
public class EyeView extends View {

    // ================================================================
    //  眼睛形态
    // ================================================================

    /**
     * 情绪状态。由服务端消息里的 {@code emotion} 字段驱动。
     *
     * <p><b>枚举名与服务端取值刻意保持同名</b>，因此 {@link #emotionOfName} 可以直接
     * {@code valueOf}，不需要再维护第二张映射表 —— 两张表对不上是这类映射最容易出的问题。
     * 括号里是服务端那张表给的参考 emoji，纯注释用途。
     */
    public enum Emotion {
        NEUTRAL,      // 😶 中性 / 无表情
        HAPPY,        // 🙂 微笑（服务端默认值）
        LAUGHING,     // 😆 大笑
        FUNNY,        // 😂 笑 cry
        LOVING,       // 😍 喜爱
        KISSY,        // 😘 亲亲
        CONFIDENT,    // 😏 得意
        COOL,         // 😎 酷
        WINKING,      // 😉 眨眼
        SILLY,        // 😜 调皮
        SAD,          // 😔 失落
        ANGRY,        // 😠 生气
        CRYING,       // 😭 大哭
        SURPRISED,    // 😲 惊讶
        SHOCKED,      // 😱 震惊
        EMBARRASSED,  // 😳 害羞
        THINKING,     // 🤔 思考
        CONFUSED,     // 🙄 无语 / 翻白眼
        RELAXED,      // 😌 放松
        SLEEPY,       // 😴 困
        DELICIOUS     // 🤤 馋 / 流口水
    }

    private static final String TAG = "EyeView";

    // ---- 尺寸：全部按视图大小的比例算，换布局尺寸不用改代码 ----
    private static final float EYE_WIDTH_RATIO = 0.25f;   // 单只眼宽 / 视图宽
    private static final float EYE_HEIGHT_RATIO = 0.62f;  // 单只眼高 / 视图高
    private static final float EYE_GAP_RATIO = 2.40f;     // 两只眼中心距 / 单眼宽

    /**
     * 眼型控制点向内的水平收拢比例。
     * 0 = 上下眼睑接近平直（两端尖、中间鼓），越大顶/底越平。
     * 真实眼角本来就带点尖，所以这个值不宜太大。
     */
    private static final float CORNER_SHARPNESS = 0.35f;

    /** 三次贝塞尔在中点的高度只到控制点高度的 0.75，换算时要除掉。 */
    private static final float BEZIER_MID_RATIO = 0.75f;

    // ================================================================
    //  动画节奏
    // ================================================================

    /** 一次眨眼的完整时长（闭合 + 睁开）。真人约 100～150ms。 */
    private static final long BLINK_DURATION_MS = 140;
    /** 两次眨眼的间隔随机区间。真人平均 3～5 秒一次，不要做成机械定时。 */
    private static final long BLINK_MIN_GAP_MS = 2600;
    private static final long BLINK_MAX_GAP_MS = 5200;
    /** 闭眼闭合段占整个眨眼的比例。小于 0.5 即"落下快、抬起慢"。 */
    private static final float BLINK_CLOSE_RATIO = 0.4f;

    /** 呼吸周期。慢到几乎察觉不到，但能让眼睛不"死"在屏幕上。 */
    private static final long BREATH_PERIOD_MS = 4200;
    private static final float BREATH_AMPLITUDE = 0.025f;  // 尺寸浮动 ±2.5%

    /**
     * 情绪过渡的时间常数（毫秒）。
     * 约 3 倍时间常数后基本到位，即 270ms 左右完成一次表情切换 ——
     * 比眨眼慢、比"慢慢变"快，接近真人换表情的速度。
     */
    private static final float EMOTION_TAU_MS = 90f;

    // ---- 光晕 ----
    // 用「叠两层放大的形状」做辉光，不用 BlurMaskFilter / setShadowLayer ——
    // 那两个会把视图强制切到软件层渲染，代价比多画两遍路径高得多。
    private static final float[] HALO_SCALE = {1.30f, 1.14f};
    private static final int[] HALO_ALPHA = {16, 42};

    // ---- 默认尺寸（wrap_content 时用）----
    private static final float DEFAULT_WIDTH_DP = 220f;
    private static final float DEFAULT_HEIGHT_DP = 120f;

    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 复用，绝不在 onDraw 里 new —— 每帧重建 Path 会把主线程拖垮。 */
    private final Path eyePath = new Path();

    /** 闭眼时至少保留的厚度（半高）。归零的话眼睛会缩成一条看不见的线。 */
    private float minHalfHeight;

    // ---- 当前形态（逐帧向目标插值）----
    private final Expression leftExpr = new Expression();
    private final Expression rightExpr = new Expression();
    // ---- 目标形态（由情绪决定，切换情绪时整体换掉）----
    private final Expression targetLeft = new Expression();
    private final Expression targetRight = new Expression();

    private Emotion emotion = Emotion.NEUTRAL;

    /** 眨眼带来的额外开合：[0,1]，1 = 没在眨。会和情绪自身的开合相乘。 */
    private float blinkLid = 1f;
    /** 本次眨眼的起始时刻，负值表示当前没在眨。 */
    private long blinkStartedAt = -1L;
    /** 下一次该眨眼的时刻。 */
    private long nextBlinkAt;
    /** 呼吸相位，[0,1)。 */
    private float breathPhase;

    private long lastFrameMs;

    private boolean running;

    /**
     * 一只眼睛的形态参数。
     *
     * <p>全部是相对值（相对视图尺寸），这样换布局尺寸不用改任何数字。
     */
    private static final class Expression {
        /** 上眼睑开合 [0,1]，1 = 完全睁开。下眼睑不跟着动。 */
        float lidOpen = 1f;
        /** 整只眼的宽度缩放。 */
        float widthScale = 1f;
        /** 整只眼的高度缩放。 */
        float heightScale = 1f;
        /** 上弯程度：两端相对中间下沉的量（相对眼高）。弯月眼就靠它。 */
        float bow = 0f;
        /** 倾斜：正值 = 左端低、右端高（相对眼高）。 */
        float tilt = 0f;
        /** 整体上下偏移（相对眼高），正值向下。 */
        float offsetY = 0f;

        void copyFrom(Expression o) {
            lidOpen = o.lidOpen;
            widthScale = o.widthScale;
            heightScale = o.heightScale;
            bow = o.bow;
            tilt = o.tilt;
            offsetY = o.offsetY;
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            step(frameTimeNanos / 1_000_000L);
            invalidate();
            if (running) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public EyeView(Context context) {
        super(context);
        init();
    }

    public EyeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EyeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        eyePaint.setColor(Color.WHITE);
        eyePaint.setStyle(Paint.Style.FILL);
        eyePaint.setAntiAlias(true);

        minHalfHeight = dp(1.5f);

        setEmotionInternal(Emotion.NEUTRAL);
        // 初始就位，避免进场时从"零形态"展开
        leftExpr.copyFrom(targetLeft);
        rightExpr.copyFrom(targetRight);

        scheduleNextBlink(nowMs());
    }

    /**
     * 单调时钟，单位毫秒。
     *
     * <p>⚠️ 必须和 {@link Choreographer.FrameCallback#doFrame} 给的 frameTimeNanos 同源 ——
     * 后者基于 {@code System.nanoTime()}，不是墙上时间。混用 {@code System.currentTimeMillis()}
     * 的话两者差了近半个世纪，比较结果恒为 false，眼睛会一次都不眨。
     */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ================================================================
    //  对外接口
    // ================================================================

    /** 切换情绪。内部会平滑过渡，不是瞬间跳变。可在任意线程调用。 */
    public void setEmotion(Emotion emotion) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> setEmotion(emotion));
            return;
        }
        setEmotionInternal(emotion == null ? Emotion.NEUTRAL : emotion);
    }

    /**
     * 用服务端的 {@code emotion} 字段名切换情绪（如 "happy" / "laughing"）。
     *
     * <p>优先用这个，而不是从正文里猜 emoji —— 服务端已经给出明确答案了。
     */
    public void setEmotionByName(String name) {
        setEmotion(emotionOfName(name));
    }

    /** 服务端取值 → 枚举。认不出来一律回落 {@link Emotion#NEUTRAL}，不崩也不卡住。 */
    private static Emotion emotionOfName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Emotion.NEUTRAL;
        }
        try {
            return Emotion.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "未知的 emotion 值，回落 NEUTRAL: " + name);
            return Emotion.NEUTRAL;
        }
    }

    /**
     * 按 emoji 切换情绪。
     *
     * <p>只作为兜底：服务端没给 {@code emotion} 字段时才用。
     * 从正文里猜 emoji 本来就是不可靠的 —— LLM 不吐 emoji 就完全没表情，
     * 而且同一个 emoji 在不同语境下未必是同一种情绪。
     *
     * <p>emoji 串里可能有多个表情，取<b>第一个能识别</b>的。
     *
     * <p>⚠️ <b>没有可识别的表情时什么都不做</b>（不是回落 NEUTRAL）。
     * "没解析到表情"和"情绪是中性"是两回事：如果这里回落 NEUTRAL，
     * 调用方一个疏忽就会把服务端刚下发的情绪抹掉 —— 想主动复位请显式调
     * {@link #setEmotion(Emotion)} 传 {@link Emotion#NEUTRAL}。
     */
    public void setEmotionByEmoji(String emoji) {
        Emotion e = emotionOf(emoji);
        if (e != null) {
            setEmotion(e);
        }
    }

    public Emotion getEmotion() {
        return emotion;
    }

    private void setEmotionInternal(Emotion emotion) {
        this.emotion = emotion;
        applyEmotion(emotion, targetLeft, true);
        applyEmotion(emotion, targetRight, false);
    }

    // ================================================================
    //  emoji → 情绪映射表
    // ================================================================

    /**
     * 把 emoji 串映射成情绪。
     *
     * <p>按码点遍历而不是用 contains —— 一个 emoji 往往由两个 char 组成
     * （代理对），直接匹配字符串既慢又容易漏。
     */
    private static Emotion emotionOf(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return null;
        }
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            Emotion e = emotionOfCodePoint(cp);
            if (e != null) {
                return e;
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    private static Emotion emotionOfCodePoint(int cp) {
        switch (cp) {
            // ---- 微笑 ----
            case 0x1F600: // 😀
            case 0x1F603: // 😃
            case 0x1F604: // 😄
            case 0x1F60A: // 😊
            case 0x1F917: // 🤗
            case 0x1F63A: // 😺
            case 0x263A:  // ☺
                return Emotion.HAPPY;

            // ---- 大笑 ----
            case 0x1F601: // 😁
            case 0x1F606: // 😆
                return Emotion.LAUGHING;

            // ---- 笑到飙泪 ----
            case 0x1F602: // 😂
            case 0x1F923: // 🤣
                return Emotion.FUNNY;

            // ---- 喜爱 ----
            case 0x1F60D: // 😍
            case 0x1F929: // 🤩
            case 0x1F970: // 🥰
            case 0x2764:  // ❤
                return Emotion.LOVING;

            case 0x1F618: // 😘
            case 0x1F617: // 😗
                return Emotion.KISSY;

            case 0x1F60F: // 😏
                return Emotion.CONFIDENT;

            case 0x1F60E: // 😎
                return Emotion.COOL;

            case 0x1F609: // 😉
                return Emotion.WINKING;

            case 0x1F61C: // 😜
            case 0x1F61B: // 😛
            case 0x1F61D: // 😝
                return Emotion.SILLY;

            // ---- 失落 ----
            case 0x1F614: // 😔
            case 0x1F61E: // 😞
            case 0x1F622: // 😢
            case 0x1F641: // 🙁
            case 0x1F63F: // 😿
            case 0x1F494: // 💔
            case 0x2639:  // ☹
                return Emotion.SAD;

            case 0x1F62D: // 😭
                return Emotion.CRYING;

            // ---- 生气 ----
            case 0x1F620: // 😠
            case 0x1F621: // 😡
            case 0x1F624: // 😤
            case 0x1F92C: // 🤬
                return Emotion.ANGRY;

            // ---- 惊讶 ----
            case 0x1F62E: // 😮
            case 0x1F62F: // 😯
            case 0x203C:  // ‼
            case 0x2049:  // ⁉
            case 0x2757:  // ❗
            case 0x2753:  // ❓
                return Emotion.SURPRISED;

            // ---- 震惊 ----
            case 0x1F631: // 😱
            case 0x1F632: // 😲
            case 0x1F635: // 😵
                return Emotion.SHOCKED;

            case 0x1F633: // 😳
            case 0x1F605: // 😅
                return Emotion.EMBARRASSED;

            // ---- 思考 ----
            case 0x1F914: // 🤔
            case 0x1F928: // 🤨
            case 0x1F9D0: // 🧐
                return Emotion.THINKING;

            case 0x1F644: // 🙄
                return Emotion.CONFUSED;

            case 0x1F60C: // 😌
                return Emotion.RELAXED;

            // ---- 困 ----
            case 0x1F4A4: // 💤
            case 0x1F62A: // 😪
            case 0x1F634: // 😴
            case 0x1F971: // 🥱
                return Emotion.SLEEPY;

            // ---- 馋 ----
            case 0x1F924: // 🤤
            case 0x1F60B: // 😋
                return Emotion.DELICIOUS;

            default:
                return null;
        }
    }

    // ================================================================
    //  情绪 → 眼型
    // ================================================================

    /**
     * 把情绪翻译成一只眼睛的形态参数。
     *
     * @param isLeftEye 左右眼的 tilt 方向相反（"外眼角"在哪边不一样），
     *                  所以必须分开算，不能一套参数两只眼共用。
     */
    private static void applyEmotion(Emotion emotion, Expression out, boolean isLeftEye) {
        // 先归零，再按情绪改 —— 避免上一次情绪的参数残留
        out.lidOpen = 1f;
        out.widthScale = 1f;
        out.heightScale = 1f;
        out.bow = 0f;
        out.tilt = 0f;
        out.offsetY = 0f;

        // 左右眼的倾斜方向相反（"外眼角"在哪边不一样）。
        // tilt 正值 = 左端低右端高，所以下面统一用这两个符号，
        // 免得每行都写一遍 isLeftEye ? a : -a。
        final float droop = isLeftEye ? 1f : -1f;   // 外眼角下垂
        final float raise = isLeftEye ? -1f : 1f;   // 外眼角上挑

        switch (emotion) {
            // ================= 中性 =================
            case NEUTRAL:
                break;

            // ================= 笑 =================
            case HAPPY:
                // 只是"微笑"，眼睛基本不变，微微上弯一点
                out.heightScale = 0.95f;
                out.bow = 0.10f;
                break;

            case LAUGHING:
                // 真笑（Duchenne）时下眼睑往上顶，眼睛眯成向上弯的月牙。
                // 注意这个月牙不是"画了个月牙"，是高度压扁 + 两端下沉的同一个眼型。
                out.lidOpen = 0.50f;
                out.heightScale = 0.52f;
                out.bow = 0.40f;
                out.offsetY = -0.06f;
                break;

            case FUNNY:
                // 笑到睁不开眼，比 LAUGHING 更眯
                out.lidOpen = 0.40f;
                out.heightScale = 0.44f;
                out.bow = 0.46f;
                out.offsetY = -0.08f;
                break;

            case LOVING:
                // 弯月但眼睛还睁着 —— 靠"睁大 + 上弯"和 LAUGHING 区分
                out.lidOpen = 0.78f;
                out.widthScale = 1.08f;
                out.bow = 0.30f;
                out.offsetY = -0.05f;
                break;

            case KISSY:
                out.lidOpen = 0.70f;
                out.heightScale = 0.92f;
                out.bow = 0.34f;
                out.offsetY = -0.05f;
                break;

            // ================= 得意 / 酷 =================
            case CONFIDENT:
                out.lidOpen = 0.58f;
                out.heightScale = 0.90f;
                out.tilt = raise * 0.20f;
                out.offsetY = -0.03f;
                break;

            case COOL:
                // 压成一条缝，像戴了墨镜
                out.lidOpen = 0.42f;
                out.widthScale = 1.06f;
                out.heightScale = 0.82f;
                out.tilt = raise * 0.12f;
                break;

            // ================= 调皮（左右不对称）=================
            case WINKING:
                // 闭的是右眼（用户视角），留左眼 —— 顺序反了会像在抽筋
                out.lidOpen = isLeftEye ? 1f : 0f;
                break;

            case SILLY:
                out.lidOpen = isLeftEye ? 1f : 0.55f;
                out.tilt = isLeftEye ? 0f : droop * 0.18f;
                out.offsetY = -0.03f;
                break;

            // ================= 负面 =================
            case SAD:
                out.heightScale = 0.82f;
                out.bow = -0.12f;          // 负值 = 向下弯（∪），和笑眼的 ∩ 相反
                out.tilt = droop * 0.22f;
                out.offsetY = 0.10f;
                break;

            case CRYING:
                out.widthScale = 0.95f;
                out.heightScale = 0.72f;
                out.bow = -0.20f;
                out.tilt = droop * 0.30f;
                out.offsetY = 0.16f;
                break;

            case ANGRY:
                out.lidOpen = 0.55f;       // 上眼睑下压
                out.widthScale = 0.92f;
                out.tilt = raise * 0.26f;  // 内眼角下压 → 外眼角上挑
                break;

            // ================= 惊讶 =================
            case SURPRISED:
                out.widthScale = 1.12f;
                out.heightScale = 1.18f;
                break;

            case SHOCKED:
                out.widthScale = 1.20f;
                out.heightScale = 1.30f;
                out.offsetY = -0.03f;
                break;

            // ================= 其他 =================
            case EMBARRASSED:
                // 低头躲闪
                out.lidOpen = 0.55f;
                out.heightScale = 0.90f;
                out.offsetY = 0.14f;
                out.tilt = droop * 0.10f;
                break;

            case THINKING:
                // 一只眼半闭 + 那只眼外侧略微下垂，比两只眼一起眯更像"在想事情"
                out.lidOpen = isLeftEye ? 1f : 0.45f;
                out.tilt = isLeftEye ? 0f : droop * 0.14f;
                out.offsetY = -0.06f;
                break;

            case CONFUSED:
                // 翻白眼：眼睛整体上翻
                out.lidOpen = 0.70f;
                out.heightScale = 0.95f;
                out.offsetY = -0.14f;
                out.tilt = droop * 0.10f;
                break;

            case RELAXED:
                out.lidOpen = 0.55f;
                out.heightScale = 0.90f;
                out.bow = 0.16f;
                out.offsetY = -0.02f;
                break;

            case SLEEPY:
                out.lidOpen = 0.28f;
                out.offsetY = 0.08f;
                out.tilt = droop * 0.10f;
                break;

            case DELICIOUS:
                // 馋：眼睛上瞟，微微上弯
                out.widthScale = 1.05f;
                out.heightScale = 1.05f;
                out.bow = 0.18f;
                out.offsetY = -0.12f;
                break;

            default:
                break;
        }
    }

    // ================================================================
    //  生命周期：只在附着且可见时跑动画
    // ================================================================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        // 必须停：不停的话页面退出后 Choreographer 回调还在跑，白烧电。
        // 项目里已经接了 LeakCanary，泄漏会直接报出来。
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (running || !isAttachedToWindow()) return;
        running = true;
        lastFrameMs = 0L;
        // 回到前台时重新掷一次间隔，避免"一回来就眨眼"
        scheduleNextBlink(nowMs());
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    // ================================================================
    //  每帧演进
    // ================================================================

    private void step(long now) {
        // ---- 眨眼 ----
        if (blinkStartedAt < 0L && now >= nextBlinkAt) {
            blinkStartedAt = now;
        }
        if (blinkStartedAt >= 0L) {
            float p = (now - blinkStartedAt) / (float) BLINK_DURATION_MS;
            if (p >= 1f) {
                blinkLid = 1f;
                blinkStartedAt = -1L;
                scheduleNextBlink(now);
            } else {
                blinkLid = blinkCurve(p);
            }
        }

        // ---- 情绪插值 ----
        // 用指数逼近而不是线性插值：帧率变化时表现一致，
        // 而且永远不会有"过冲"（表情回弹一下看着很假）。
        float alpha = 1f;
        if (lastFrameMs > 0L) {
            long dt = Math.min(now - lastFrameMs, 100L);   // 卡顿时按 100ms 封顶，避免直接跳到位
            alpha = 1f - (float) Math.exp(-dt / EMOTION_TAU_MS);
        }
        lastFrameMs = now;

        approach(leftExpr, targetLeft, alpha);
        approach(rightExpr, targetRight, alpha);

        // ---- 呼吸 ----
        breathPhase = (now % BREATH_PERIOD_MS) / (float) BREATH_PERIOD_MS;
    }

    private static void approach(Expression cur, Expression target, float alpha) {
        cur.lidOpen += (target.lidOpen - cur.lidOpen) * alpha;
        cur.widthScale += (target.widthScale - cur.widthScale) * alpha;
        cur.heightScale += (target.heightScale - cur.heightScale) * alpha;
        cur.bow += (target.bow - cur.bow) * alpha;
        cur.tilt += (target.tilt - cur.tilt) * alpha;
        cur.offsetY += (target.offsetY - cur.offsetY) * alpha;
    }

    private void scheduleNextBlink(long now) {
        nextBlinkAt = now + BLINK_MIN_GAP_MS
                + (long) (Math.random() * (BLINK_MAX_GAP_MS - BLINK_MIN_GAP_MS));
    }

    /**
     * 眨眼的开合曲线，返回 [0,1] 的眼睑开度。
     *
     * <p>闭合段比睁开段短 —— 真人上眼睑落下来就是比抬起来快，
     * 用匀速或者对称曲线一眼就能看出是假的。
     */
    private static float blinkCurve(float p) {
        if (p < BLINK_CLOSE_RATIO) {
            return 1f - smoothStep(p / BLINK_CLOSE_RATIO);   // 1 → 0
        }
        return smoothStep((p - BLINK_CLOSE_RATIO) / (1f - BLINK_CLOSE_RATIO)); // 0 → 1
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    // ================================================================
    //  绘制
    // ================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize(Math.round(dp(DEFAULT_WIDTH_DP)), widthMeasureSpec);
        int h = resolveSize(Math.round(dp(DEFAULT_HEIGHT_DP)), heightMeasureSpec);
        // 两只眼睛是横向排布的，太窄会叠在一起，兜一个最小宽度。
        // 只在宽度不是硬性指定时才兜底，否则会跟布局里写死的尺寸打架。
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            w = Math.max(w, Math.round(dp(DEFAULT_WIDTH_DP) * 0.5f));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        // 呼吸：整体尺寸的极缓慢起伏
        float breath = 1f + BREATH_AMPLITUDE
                * (float) Math.sin(breathPhase * 2.0 * Math.PI);

        float eyeW = w * EYE_WIDTH_RATIO * breath;
        float eyeH = h * EYE_HEIGHT_RATIO * breath;
        float gap = eyeW * EYE_GAP_RATIO;

        float centerY = h / 2f;
        float leftCx = w / 2f - gap / 2f;
        float rightCx = w / 2f + gap / 2f;

        drawEye(canvas, leftCx, centerY, eyeW, eyeH, leftExpr);
        drawEye(canvas, rightCx, centerY, eyeW, eyeH, rightExpr);
    }

    private void drawEye(Canvas canvas, float cx, float cy,
                         float w, float h, Expression e) {
        float eyeW = w * e.widthScale;
        float eyeH = h * e.heightScale;

        // 上眼睑随开合收缩，下眼睑基本不动 —— 真眼是上睑往下盖，
        // 不是上下对称地压扁。眨眼（blinkLid）和情绪的开合在这里相乘，
        // 所以眯着眼的时候也能正常眨眼。
        float lid = e.lidOpen * blinkLid;
        float upperH = Math.max(eyeH * 0.5f * lid, minHalfHeight);
        float lowerH = Math.max(eyeH * 0.5f, minHalfHeight);

        // 闭合时整只眼略微下沉
        float centerY = cy + e.offsetY * eyeH + (eyeH * 0.5f - upperH) * 0.5f;

        float hw = eyeW * 0.5f;
        float dx = hw * CORNER_SHARPNESS;
        float kUpper = upperH / BEZIER_MID_RATIO;
        float kLower = lowerH / BEZIER_MID_RATIO;

        // 两个眼角。tilt 让左端低右端高（或反之）；bow 把两端一起压低，
        // 中间不动 —— 形状于是向上弯成月牙。这就是"高兴眼"的全部原理。
        float leftY = centerY + e.tilt * eyeH + e.bow * eyeH;
        float rightY = centerY - e.tilt * eyeH + e.bow * eyeH;

        eyePath.reset();
        eyePath.moveTo(cx - hw, leftY);
        // 上眼睑：左眼角 → 右眼角
        eyePath.cubicTo(cx - hw + dx, leftY - kUpper,
                cx + hw - dx, rightY - kUpper,
                cx + hw, rightY);
        // 下眼睑：右眼角 → 左眼角
        eyePath.cubicTo(cx + hw - dx, rightY + kLower,
                cx - hw + dx, leftY + kLower,
                cx - hw, leftY);
        eyePath.close();

        // 光晕：把同一个形状整体放大后叠两层。用 canvas.scale 而不是
        // setShadowLayer —— 后者会强制视图走软件层渲染。
        for (int i = 0; i < HALO_SCALE.length; i++) {
            float s = HALO_SCALE[i];
            eyePaint.setAlpha(HALO_ALPHA[i]);
            int save = canvas.save();
            canvas.scale(s, s, cx, centerY);
            canvas.drawPath(eyePath, eyePaint);
            canvas.restoreToCount(save);
        }

        eyePaint.setAlpha(255);
        canvas.drawPath(eyePath, eyePaint);
    }
}
