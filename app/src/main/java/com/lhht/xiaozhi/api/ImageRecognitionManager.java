package com.lhht.xiaozhi.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import com.iflytek.sparkchain.core.LLM;
import com.iflytek.sparkchain.core.LLMCallbacks;
import com.iflytek.sparkchain.core.LLMConfig;
import com.iflytek.sparkchain.core.LLMError;
import com.iflytek.sparkchain.core.LLMEvent;
import com.iflytek.sparkchain.core.LLMFactory;
import com.iflytek.sparkchain.core.LLMResult;
import com.iflytek.sparkchain.core.Memory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ImageRecognitionManager {
    private static final String TAG = "ImageRecognitionManager";
    private final Context context;
    private LLM llm;
    private int token = 0;
    private ImageRecognitionCallback callback;
    
    // 消息缓冲机制
    private StringBuilder messageBuffer = new StringBuilder();
    private Handler delayHandler = new Handler(Looper.getMainLooper());
    private Runnable sendBufferedMessageRunnable;
    private AtomicBoolean isReceivingMessage = new AtomicBoolean(false);
    private static final int MESSAGE_DELAY_MS = 1000; // 1秒延迟发送

    public interface ImageRecognitionCallback {
        void onRecognitionResult(String content);
        void onRecognitionError(String errorMessage);
    }

    public ImageRecognitionManager(Context context, ImageRecognitionCallback callback) {
        this.context = context;
        this.callback = callback;
        initLLM();
    }

    private void initLLM() {
        LLMConfig llmConfig = LLMConfig.builder()
                .maxToken(2048);
        Memory window_memory = Memory.windowMemory(5);
        llm = LLMFactory.imageUnderstanding(llmConfig, window_memory);
        llm.registerLLMCallbacks(createLLMCallbacks());
    }

    private LLMCallbacks createLLMCallbacks() {
        return new LLMCallbacks() {
            @Override
            public void onLLMResult(LLMResult llmResult, Object usrContext) {
                if (token == (int) usrContext) {
                    String content = llmResult.getContent();
                    if (content != null && !content.trim().isEmpty()) {
                        // 将内容添加到缓冲区
                        synchronized (messageBuffer) {
                            messageBuffer.append(content);
                        }
                        
                        // 标记正在接收消息
                        isReceivingMessage.set(true);
                        
                        // 取消之前的延迟发送任务
                        if (sendBufferedMessageRunnable != null) {
                            delayHandler.removeCallbacks(sendBufferedMessageRunnable);
                        }
                        
                        // 创建新的延迟发送任务
                        sendBufferedMessageRunnable = new Runnable() {
                            @Override
                            public void run() {
                                sendBufferedMessage();
                            }
                        };
                        
                        // 延迟发送消息
                        delayHandler.postDelayed(sendBufferedMessageRunnable, MESSAGE_DELAY_MS);
                    }
                    
                    // 检查是否完成
                    if (llmResult.getStatus() == 2) {
                        Log.d(TAG, String.format("Recognition completed - Tokens: completion=%d, prompt=%d, total=%d",
                                llmResult.getCompletionTokens(),
                                llmResult.getPromptTokens(),
                                llmResult.getTotalTokens()));
                        
                        // 立即发送缓冲的消息
                        delayHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                sendBufferedMessage();
                            }
                        });
                    }
                }
            }

            @Override
            public void onLLMEvent(LLMEvent event, Object o) {
                // 预留事件处理
            }

            @Override
            public void onLLMError(LLMError error, Object o) {
                if (callback != null) {
                    callback.onRecognitionError(String.format("错误: %d - %s",
                            error.getErrCode(),
                            error.getErrMsg()));
                }
            }
        };
    }
    
    /**
     * 发送缓冲的消息
     */
    private void sendBufferedMessage() {
        synchronized (messageBuffer) {
            if (messageBuffer.length() > 0 && callback != null) {
                String finalMessage = "[视觉]:" + messageBuffer.toString() + "（请用自然流畅的语言完整地复述这个视觉描述，保持内容的连贯性和完整性。）";
                
                // 使用优先级发送，确保图像识别结果能够及时处理
                Log.d(TAG, "Sending buffered vision message with priority: " + finalMessage);
                callback.onRecognitionResult(finalMessage);
                
                // 清空缓冲区
                messageBuffer.setLength(0);
            }
        }
        isReceivingMessage.set(false);
        
        // 清理延迟任务
        if (sendBufferedMessageRunnable != null) {
            delayHandler.removeCallbacks(sendBufferedMessageRunnable);
            sendBufferedMessageRunnable = null;
        }
    }

    public void processPreviewFrame(byte[] data, Camera camera) {
        try {
            byte[] processedImageData = convertPreviewFrameToJpeg(data, camera);
            if (processedImageData != null) {
                recognizeImage(processedImageData);
            }
        } catch (IOException e) {
            Log.e(TAG, "处理预览帧失败", e);
            if (callback != null) {
                callback.onRecognitionError("图像处理失败: " + e.getMessage());
            }
        }
    }

    // CameraX 版本：处理来自 CameraX 的 YUV 数据
    public void processPreviewFrameFromCameraX(byte[] data, int width, int height) {
        try {
            byte[] processedImageData = convertYuvToJpeg(data, width, height);
            if (processedImageData != null) {
                recognizeImage(processedImageData);
            }
        } catch (IOException e) {
            Log.e(TAG, "处理预览帧失败", e);
            if (callback != null) {
                callback.onRecognitionError("图像处理失败: " + e.getMessage());
            }
        }
    }

    private byte[] convertYuvToJpeg(byte[] data, int width, int height) throws IOException {
        YuvImage yuv = new YuvImage(data, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        yuv.compressToJpeg(new Rect(0, 0, width, height), 85, out);

        byte[] imageBytes = out.toByteArray();
        if (imageBytes.length > 2 * 1024 * 1024) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            out.reset();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
            imageBytes = out.toByteArray();

            if (imageBytes.length > 2 * 1024 * 1024) {
                out.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
                imageBytes = out.toByteArray();
                bitmap.recycle();
            }
        }

        return imageBytes;
    }

    private byte[] convertPreviewFrameToJpeg(byte[] data, Camera camera) throws IOException {
        Camera.Parameters parameters = camera.getParameters();
        int width = parameters.getPreviewSize().width;
        int height = parameters.getPreviewSize().height;

        YuvImage yuv = new YuvImage(data, parameters.getPreviewFormat(), width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 初始压缩，使用较高质量
        yuv.compressToJpeg(new Rect(0, 0, width, height), 85, out);

        byte[] imageBytes = out.toByteArray();
        if (imageBytes.length > 2 * 1024 * 1024) { // 2MB限制
            // 进一步压缩
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            out.reset();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
            imageBytes = out.toByteArray();

            // 如果仍然太大，最终压缩
            if (imageBytes.length > 2 * 1024 * 1024) {
                out.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
                imageBytes = out.toByteArray();
                bitmap.recycle();
            }
        }

        return imageBytes;
    }

    private void recognizeImage(byte[] imageData) {
        if (llm == null) {
            Log.e(TAG, "LLM未初始化");
            return;
        }
        token++;
        llm.clearHistory();
        // 优化提示词，要求完整描述并避免分段输出
        String prompt = "请用一段完整的话描述这张图片中的内容，包括人物、物体、场景、动作等细节。请确保描述完整连贯，不要分段输出。";
        int ret = llm.arun(prompt, imageData, token);
        if (ret != 0) {
            Log.e(TAG, "识别请求失败: " + ret);
            if (callback != null) {
                callback.onRecognitionError("识别请求失败，错误码: " + ret);
            }
        }
    }

    public void release() {
        // 清理延迟任务
        if (sendBufferedMessageRunnable != null) {
            delayHandler.removeCallbacks(sendBufferedMessageRunnable);
            sendBufferedMessageRunnable = null;
        }
        
        // 清空缓冲区
        synchronized (messageBuffer) {
            messageBuffer.setLength(0);
        }
        
        isReceivingMessage.set(false);
        llm = null;
        callback = null;
    }
}