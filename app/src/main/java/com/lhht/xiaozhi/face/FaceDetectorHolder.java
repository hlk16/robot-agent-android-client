package com.lhht.xiaozhi.face;

import android.content.Context;
import android.util.Log;

import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 人脸级联分类器的加载与持有。
 *
 * <p>模型文件放在 {@code app/src/main/assets/}，原因有两个：
 * <ul>
 *   <li>OpenCV 的 sdk 模块只声明了 jniLibs / java / res，{@code etc/} 下的级联文件
 *       根本不会被打进 APK；</li>
 *   <li>{@link CascadeClassifier} 没有 InputStream 构造器，只接受文件系统路径，
 *       所以即使能读到 assets 流也必须先落成真实文件。</li>
 * </ul>
 *
 * <p>⚠️ 加载失败时 {@link CascadeClassifier} <b>不会抛异常</b>，只会让 {@code empty()}
 * 返回 true，随后 {@code detectMultiScale} 永远返回空结果。所以这里必须显式检查。
 */
public final class FaceDetectorHolder {

    private static final String TAG = "FaceDetector";

    /**
     * 用 LBP 而不是 Haar：两者都是正脸检测器，准确率接近，
     * 但 LBP 模型只有 54 KB 且快 3～5 倍。
     */
    private static final String ASSET_NAME = "lbpcascade_frontalface_improved.xml";

    private static volatile CascadeClassifier classifier;
    /** 失败过一次就不再重试，避免每帧都去碰 IO。 */
    private static volatile boolean failed;

    private FaceDetectorHolder() {
    }

    /**
     * 取得分类器；不可用时返回 null。
     *
     * <p>会做一次 54 KB 的资产释放（仅首次），之后直接复用。
     */
    public static CascadeClassifier get(Context context) {
        if (failed) return null;
        CascadeClassifier local = classifier;
        if (local != null) return local;

        synchronized (FaceDetectorHolder.class) {
            if (classifier != null) return classifier;
            if (failed) return null;

            classifier = load(context.getApplicationContext());
            if (classifier == null) {
                failed = true;
            }
            return classifier;
        }
    }

    private static CascadeClassifier load(Context ctx) {
        File target = new File(ctx.getFilesDir(), ASSET_NAME);

        if (!target.exists() || target.length() == 0) {
            // 先写临时文件再改名：中途被打断也不会留下一个"看起来存在、其实是半截"的模型
            File tmp = new File(ctx.getFilesDir(), ASSET_NAME + ".tmp");
            try (InputStream in = ctx.getAssets().open(ASSET_NAME);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                Log.e(TAG, "释放模型文件失败", e);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            if (!tmp.renameTo(target)) {
                Log.e(TAG, "模型文件改名失败: " + tmp.getAbsolutePath());
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
        }

        CascadeClassifier c = new CascadeClassifier(target.getAbsolutePath());
        if (c.empty()) {
            Log.e(TAG, "模型加载失败（CascadeClassifier.empty()）：" + target.getAbsolutePath());
            return null;
        }

        Log.d(TAG, "模型加载成功：" + target.getAbsolutePath());
        return c;
    }
}
