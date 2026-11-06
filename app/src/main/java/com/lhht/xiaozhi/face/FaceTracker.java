package com.lhht.xiaozhi.face;

import android.content.Context;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 OpenCV 级联分类器的人脸追踪。
 *
 * <p>只负责"人脸在哪 → 归一化成视线方向"，不碰 UI、不碰相机生命周期。
 *
 * <p><b>性能约束</b>（三条都不能省）：
 * <ul>
 *   <li><b>独立线程</b>：不能占用 CameraX 的分析线程，那条流还要给视觉大模型抽帧；</li>
 *   <li><b>降采样</b>：检测前缩到 320px 宽，耗时能降一个数量级；</li>
 *   <li><b>忙时丢帧</b>：上一帧还没检测完就直接丢弃新帧，绝不排队 ——
 *       排队会让检测结果越来越滞后于画面。</li>
 * </ul>
 */
public class FaceTracker {

    /** 检测结果的回调。始终在检测线程上调用，调用方自行切主线程。 */
    public interface Callback {
        /**
         * @param nx    归一化视线 X，[-1, 1]，已转成"用户视角"（前置镜像已处理）
         * @param ny    归一化视线 Y，[-1, 1]，正值向下
         * @param found 本帧是否检测到人脸
         */
        void onGaze(float nx, float ny, boolean found);
    }

    private static final String TAG = "FaceTracker";

    /**
     * 检测间隔约 70ms（≈14fps）。
     *
     * <p>之前是 120ms，加上平滑延迟后眼睛要落后 200～300ms，主观上就是"不跟手"。
     * 检测本身在 320px 图上只要 5～15ms，提到 14fps 对相机线程压力很小。
     */
    private static final long INTERVAL_MS = 70;

    /** 检测前缩到这个宽度。这是性能的关键，不要随手调大。 */
    private static final int DETECT_WIDTH = 320;

    /** 缩到 320 宽后，人脸至少要这么大才认（约等于原图 80px）。 */
    private static final int MIN_FACE_PX = 32;

    private final CascadeClassifier classifier;
    private final Callback callback;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "face-tracker");
        t.setDaemon(true);
        return t;
    });

    /** 上一帧还没处理完时为 true —— 新帧直接丢。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private long lastSubmitAt;

    // 以下 Mat 全部复用，避免每帧分配 native 内存
    private final Mat grayMat = new Mat();
    private final Mat smallMat = new Mat();
    private final MatOfRect faces = new MatOfRect();

    private boolean lastFound;
    /** 调试用：限制坐标日志频率，避免 8fps 刷屏。 */
    private long lastLogAt;

    public FaceTracker(Context context, Callback callback) {
        this.classifier = FaceDetectorHolder.get(context);
        this.callback = callback;
    }

    /** 模型是否可用。不可用时上层应该直接跳过追踪，别每帧都试。 */
    public boolean isAvailable() {
        return classifier != null && !classifier.empty();
    }

    /** 是否到了该处理下一帧的时间。 */
    public boolean shouldProcess(long nowMs) {
        return isAvailable() && nowMs - lastSubmitAt >= INTERVAL_MS;
    }

    /**
     * 提交一帧。
     *
     * <p>⚠️ 返回 true 表示<b>本类接管了 ImageProxy 的生命周期</b>，调用方不要再 close；
     * 返回 false 表示没接（不可用或上一帧还没处理完），调用方需自行 close。
     */
    public boolean submit(ImageProxy image) {
        if (!isAvailable()) return false;
        if (!busy.compareAndSet(false, true)) return false;

        lastSubmitAt = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                detect(image);
            } catch (Throwable t) {
                Log.e(TAG, "人脸检测失败", t);
            } finally {
                // ImageProxy 必须关闭，否则相机管线拿不到后续帧
                image.close();
                busy.set(false);
            }
        });
        return true;
    }

    private void detect(ImageProxy image) {
        final int w = image.getWidth();
        final int h = image.getHeight();

        // ---- 1. 只取 Y 平面：YUV_420_888 的 Y 分量本身就是灰度图，不需要颜色转换 ----
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer yBuf = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();

        byte[] gray = new byte[w * h];
        // ⚠️ 必须显式复位：抽帧给视觉模型时已经把 Y 平面的 Buffer position 读到了末尾，
        // 不复位的话这里会从末尾开始读，直接 BufferUnderflowException。
        yBuf.position(0);
        if (rowStride == w) {
            yBuf.get(gray, 0, w * h);
        } else {
            // 行尾可能有 padding，必须按 rowStride 逐行取
            for (int r = 0; r < h; r++) {
                yBuf.position(r * rowStride);
                yBuf.get(gray, r * w, w);
            }
        }
        grayMat.create(h, w, CvType.CV_8UC1);
        grayMat.put(0, 0, gray);

        // ---- 2. 降采样后再检测 ----
        int sw = Math.min(DETECT_WIDTH, w);
        int sh = Math.max(1, Math.round(h * (sw / (float) w)));
        Imgproc.resize(grayMat, smallMat, new Size(sw, sh), 0, 0, Imgproc.INTER_AREA);

        // ---- 3. 检测 ----
        classifier.detectMultiScale(smallMat, faces, 1.1, 5, 0, new Size(MIN_FACE_PX, MIN_FACE_PX));
        Rect[] found = faces.toArray();

        if (found.length == 0) {
            if (lastFound) {
                Log.d(TAG, "人脸丢失");
                lastFound = false;
            }
            callback.onGaze(0f, 0f, false);
            return;
        }

        // ---- 4. 取面积最大的那张脸（离镜头最近、最可能是交互对象）----
        Rect best = found[0];
        for (Rect r : found) {
            if (r.area() > best.area()) best = r;
        }

        // 小图坐标 → 原始缓冲区坐标
        float bcx = (best.x + best.width * 0.5f) * w / (float) sw;
        float bcy = (best.y + best.height * 0.5f) * h / (float) sh;

        // ---- 5. 按旋转角转正 ----
        // 前置摄像头在横屏下的 rotationDegrees 往往是 90 或 270，
        // 不处理的话 X/Y 会互换 —— 人左右移动、眼睛却上下看。
        int rot = image.getImageInfo().getRotationDegrees();
        float ux, uy, uw, uh;
        switch (rot) {
            case 90:
                ux = h - bcy; uy = bcx; uw = h; uh = w;
                break;
            case 180:
                ux = w - bcx; uy = h - bcy; uw = w; uh = h;
                break;
            case 270:
                ux = bcy; uy = w - bcx; uw = h; uh = w;
                break;
            case 0:
            default:
                ux = bcx; uy = bcy; uw = w; uh = h;
                break;
        }

        // ---- 6. 归一化到 [-1, 1] ----
        float nx = ux / uw * 2f - 1f;
        float ny = uy / uh * 2f - 1f;

        // 前置摄像头原始帧是未镜像的，而预览是镜像的。
        // 这里翻一次，之后 EyeView 全程按"用户视角"处理，一路只翻一次。
        nx = -nx;

        if (!lastFound) {
            lastFound = true;
            Log.d(TAG, "检测到人脸");
        }

        // 每秒最多一条：能看出坐标到底有没有随人脸移动而变化
        long now = System.currentTimeMillis();
        if (now - lastLogAt >= 1000) {
            lastLogAt = now;
            Log.d(TAG, String.format("gaze nx=%.2f ny=%.2f rot=%d", nx, ny, rot));
        }

        callback.onGaze(clamp(nx), clamp(ny), true);
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    /** 页面销毁时调用，释放线程与 native 资源。 */
    public void shutdown() {
        executor.shutdownNow();
        grayMat.release();
        smallMat.release();
        faces.release();
        busy.set(false);
    }
}
