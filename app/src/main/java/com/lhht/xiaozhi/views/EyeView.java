package com.lhht.xiaozhi.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

/**
 * Nomi 风格的表情眼睛。
 *
 * <p>第一阶段：静态眼睛 + 随机眨眼 + 呼吸感，不追踪、不接情绪。
 *
 * <p>画法说明：每只眼就是一个白色实心「胶囊」（stadium），没有眼白、没有瞳孔 ——
 * 参考图里的机器人眼睛本身就是一块纯白形状，所有"表情"都靠形状变化表达。
 * 圆角半径取 {@code min(宽, 高) / 2}，这样同一份代码在任意长宽比下都能画出
 * 正确的胶囊：瘦高时是竖胶囊，压扁后自动变成横胶囊，不需要分支判断。
 *
 * <p>动画用 {@link Choreographer} 逐帧驱动，而不是堆一堆 ValueAnimator：
 * 注视点、眼睑开合、呼吸这些状态是连续演进的，用并行的动画器很难协调，
 * 而且每帧要跑多个 evaluator。
 */
public class EyeView extends View {

    // ---- 尺寸：全部按视图大小的比例算，换布局尺寸不用改代码 ----
    private static final float EYE_WIDTH_RATIO = 0.25f;   // 单只眼宽 / 视图宽
    private static final float EYE_HEIGHT_RATIO = 0.62f;  // 单只眼高 / 视图高
    private static final float EYE_GAP_RATIO = 1.85f;     // 两只眼中心距 / 单眼宽

    // ---- 眨眼节奏 ----
    /** 一次眨眼的完整时长（闭合 + 睁开）。真人约 100～150ms。 */
    private static final long BLINK_DURATION_MS = 140;
    /** 两次眨眼的间隔随机区间。真人平均 3～5 秒一次，不要做成机械定时。 */
    private static final long BLINK_MIN_GAP_MS = 2600;
    private static final long BLINK_MAX_GAP_MS = 5200;
    /** 闭眼闭合段占整个眨眼的比例。小于 0.5 即"落下快、抬起慢"。 */
    private static final float BLINK_CLOSE_RATIO = 0.4f;

    // ---- 呼吸 ----
    /** 呼吸周期。慢到几乎察觉不到，但能让眼睛不"死"在屏幕上。 */
    private static final long BREATH_PERIOD_MS = 4200;
    private static final float BREATH_AMPLITUDE = 0.025f;  // 尺寸浮动 ±2.5%

    // ---- 光晕 ----
    // 用「叠几层放大的低透明度形状」做辉光，不用 BlurMaskFilter / setShadowLayer ——
    // 那两个会把视图强制切到软件层渲染，代价比多画两个圆角矩形高得多。
    private static final float[] HALO_INFLATE_DP = {4f, 2f};
    private static final int[] HALO_ALPHA = {16, 42};

    // ---- 默认尺寸（wrap_content 时用）----
    private static final float DEFAULT_WIDTH_DP = 220f;
    private static final float DEFAULT_HEIGHT_DP = 120f;

    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 闭眼时至少保留的厚度（半高）。归零的话眼睛会缩成一条看不见的线。 */
    private float minHalfHeight;

    private float[] haloInflate;

    /** 眼睑开合：[0,1]，1 = 完全睁开。 */
    private float lidOpen = 1f;
    /** 本次眨眼的起始时刻，负值表示当前没在眨。 */
    private long blinkStartedAt = -1L;
    /** 下一次该眨眼的时刻。 */
    private long nextBlinkAt;
    /** 呼吸相位，[0,1)。 */
    private float breathPhase;

    private boolean running;

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
        haloInflate = new float[HALO_INFLATE_DP.length];
        for (int i = 0; i < HALO_INFLATE_DP.length; i++) {
            haloInflate[i] = dp(HALO_INFLATE_DP[i]);
        }
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
        if (blinkStartedAt < 0L && now >= nextBlinkAt) {
            blinkStartedAt = now;
        }
        if (blinkStartedAt >= 0L) {
            float p = (now - blinkStartedAt) / (float) BLINK_DURATION_MS;
            if (p >= 1f) {
                lidOpen = 1f;
                blinkStartedAt = -1L;
                scheduleNextBlink(now);
            } else {
                lidOpen = blinkCurve(p);
            }
        }
        breathPhase = (now % BREATH_PERIOD_MS) / (float) BREATH_PERIOD_MS;
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

        drawEye(canvas, leftCx, centerY, eyeW, eyeH);
        drawEye(canvas, rightCx, centerY, eyeW, eyeH);
    }

    private void drawEye(Canvas canvas, float cx, float cy, float w, float h) {
        float halfOpen = Math.max(h * 0.5f * lidOpen, minHalfHeight);

        // 真眼是上眼睑往下盖，不是以中心对称地压扁，
        // 所以闭合过程中整只眼要略微下沉一点
        float centerY = cy + (h * 0.5f - halfOpen) * 0.6f;

        float left = cx - w / 2f;
        float right = cx + w / 2f;
        float top = centerY - halfOpen;
        float bottom = centerY + halfOpen;

        // min(宽,高)/2 —— 瘦高时是竖胶囊，压扁后自动变横胶囊
        float radius = Math.min(w, halfOpen * 2f) / 2f;

        for (int i = 0; i < haloInflate.length; i++) {
            float inflate = haloInflate[i];
            eyePaint.setAlpha(HALO_ALPHA[i]);
            canvas.drawRoundRect(left - inflate, top - inflate,
                    right + inflate, bottom + inflate,
                    radius + inflate, radius + inflate, eyePaint);
        }

        eyePaint.setAlpha(255);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, eyePaint);
    }
}
