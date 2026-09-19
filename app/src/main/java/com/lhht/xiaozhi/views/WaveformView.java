package com.lhht.xiaozhi.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class WaveformView extends View {
    private float[] amplitudes;
    /** 有效点数。amplitudes 通常是复用缓冲区，尾部可能残留上一帧数据，不能按 length 画 */
    private int amplitudeCount;
    private Paint paint;
    private Path path;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setAntiAlias(true);

        path = new Path();
    }

    public void setAmplitudes(float[] amplitudes) {
        setAmplitudes(amplitudes, amplitudes == null ? 0 : amplitudes.length);
    }

    /**
     * @param count 本次真正有效的点数。调用方传进来的往往是复用缓冲区，
     *              尾部还是上一帧的旧数据，按 length 画会多描一大段残影。
     */
    public void setAmplitudes(float[] amplitudes, int count) {
        this.amplitudes = amplitudes;
        this.amplitudeCount = amplitudes == null ? 0 : Math.min(count, amplitudes.length);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float[] data = amplitudes;
        int count = amplitudeCount;
        // 少于两个点连不成线；且 count - 1 会让下面的 stepX 除零
        if (data == null || count < 2) {
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float centerY = height / 2;
        float maxAmplitude = 0.5f; // 最大振幅为视图高度的一半

        path.reset();
        float stepX = width / (count - 1);

        // 绘制波形
        path.moveTo(0, centerY);
        for (int i = 0; i < count; i++) {
            float x = i * stepX;
            float y = centerY + (data[i] * height * maxAmplitude);
            path.lineTo(x, y);
        }

        canvas.drawPath(path, paint);
    }
} 