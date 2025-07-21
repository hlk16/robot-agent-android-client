package com.example.tablerobot;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.Manifest;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import android.hardware.camera2.*;
import android.media.ImageReader;
import android.media.Image;
import android.graphics.ImageFormat;
import android.view.Surface;
import android.content.Context;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import java.nio.ByteBuffer;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.CvType;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public class CameraDetectionService extends Service {
    
    private static final String TAG = "CameraDetectionService";
    private static final String CHANNEL_ID = "CameraDetectionChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private DataManager dataManager;
    private Handler handler;
    private Handler backgroundHandler;
    private boolean isDetecting = false;
    
    // Camera2 相关变量
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private String cameraId;
    private Size previewSize;
    
    // OpenCV 检测参数 (从ovoActivity迁移)
    private double cannyThreshold1 = 150;
    private double cannyThreshold2 = 300;
    private double minContourArea = 100;
    
    // 检测结果存储
    private org.opencv.core.Point circleCenter = null;
    private int frameWidth = 0;
    private int frameHeight = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CameraDetectionService onCreate");
        
        dataManager = DataManager.getInstance(this);
        handler = new Handler(Looper.getMainLooper());
        
        // 创建后台线程Handler用于Camera2操作
        android.os.HandlerThread backgroundThread = new android.os.HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        
        // 初始化OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed");
        } else {
            Log.d(TAG, "OpenCV initialization succeeded");
        }
        
        // 创建通知渠道
        createNotificationChannel();
        
        // 初始化Camera2
        initCamera();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "CameraDetectionService started");
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 开始相机检测
        startCameraDetection();
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不支持绑定
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "CameraDetectionService destroyed");
        
        stopCameraDetection();
        
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Camera Detection Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background camera detection service");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("检测服务运行中")
            .setContentText("后台检测服务正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void initCamera() {
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            
            // 选择后置摄像头
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    
                    // 获取支持的预览尺寸
                    StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                        previewSize = chooseOptimalSize(sizes);
                        frameWidth = previewSize.getWidth();
                        frameHeight = previewSize.getHeight();
                    }
                    break;
                }
            }
            
            Log.d(TAG, "Camera initialized with size: " + frameWidth + "x" + frameHeight);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera initialization failed: " + e.getMessage());
        }
    }
    
    private Size chooseOptimalSize(Size[] sizes) {
        // 选择合适的预览尺寸，优先选择1280x720或接近的尺寸
        for (Size size : sizes) {
            if (size.getWidth() == 1280 && size.getHeight() == 720) {
                return size;
            }
        }
        
        // 如果没有找到1280x720，选择第一个可用尺寸
        if (sizes.length > 0) {
            return sizes[0];
        }
        
        // 默认尺寸
        return new Size(1280, 720);
    }
    
    private void startCameraDetection() {
        if (isDetecting) {
            return;
        }
        
        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            return;
        }
        
        try {
            // 创建ImageReader用于接收相机数据
            imageReader = ImageReader.newInstance(frameWidth, frameHeight, 
                ImageFormat.YUV_420_888, 1);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);
            
            // 打开相机
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
            
            isDetecting = true;
            Log.d(TAG, "Started camera detection");
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start camera detection: " + e.getMessage());
        }
    }
    
    private void stopCameraDetection() {
        isDetecting = false;
        
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            
            Log.d(TAG, "Stopped camera detection");
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping camera detection: " + e.getMessage());
        }
    }
    
    // Camera2 回调处理器
    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCaptureSession();
            Log.d(TAG, "Camera opened successfully");
        }
        
        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            Log.d(TAG, "Camera disconnected");
        }
        
        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            Log.e(TAG, "Camera error: " + error);
        }
    };
    
    private void createCaptureSession() {
        try {
            List<Surface> surfaces = Arrays.asList(imageReader.getSurface());
            
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    startRepeatingCapture();
                    Log.d(TAG, "Capture session configured");
                }
                
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Capture session configuration failed");
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session: " + e.getMessage());
        }
    }
    
    private void startRepeatingCapture() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            
            CaptureRequest captureRequest = builder.build();
            captureSession.setRepeatingRequest(captureRequest, null, backgroundHandler);
            
            Log.d(TAG, "Started repeating capture");
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start repeating capture: " + e.getMessage());
        }
    }
    
    // 图像处理监听器
    private final ImageReader.OnImageAvailableListener imageAvailableListener = 
        new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    processFrame(image);
                    image.close();
                }
            }
        };
    
    /**
     * 处理相机帧数据 (从ovoActivity迁移的检测逻辑)
     */
    private void processFrame(Image image) {
        try {
            // 将Image转换为OpenCV Mat
            Mat rgbaMat = imageToMat(image);
            if (rgbaMat == null) {
                return;
            }
            
            // 执行轮廓检测
            detectContours(rgbaMat);
            
            // 更新数据到DataManager
            updateDataManager(rgbaMat);
            
            // 释放Mat资源
            rgbaMat.release();
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame: " + e.getMessage());
        }
    }
    
    /**
     * 将Camera2的Image转换为OpenCV Mat
     */
    private Mat imageToMat(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            
            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);
            
            Mat yuvMat = new Mat(frameHeight + frameHeight / 2, frameWidth, CvType.CV_8UC1);
            yuvMat.put(0, 0, nv21);
            
            Mat rgbaMat = new Mat();
            Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21);
            
            yuvMat.release();
            return rgbaMat;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting image to mat: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 物体轮廓检测方法 (从ovoActivity迁移)
     */
    private void detectContours(Mat rgbaMat) {
        try {
            // 重置圆心坐标
            circleCenter = null;
            
            Mat grayMat = new Mat();
            Mat cannyMat = new Mat();
            
            // 1. 转换为灰度图像
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY);
            
            // 2. 高斯模糊，减少噪声
            Imgproc.GaussianBlur(grayMat, grayMat, new org.opencv.core.Size(5, 5), 0);
            
            // 3. Canny边缘检测
            Imgproc.Canny(grayMat, cannyMat, cannyThreshold1, cannyThreshold2);
            
            // 4. 查找轮廓
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(cannyMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            
            // 5. 处理轮廓并寻找圆心
            processContours(contours, rgbaMat);
            
            // 释放临时Mat
            grayMat.release();
            cannyMat.release();
            hierarchy.release();
            
        } catch (Exception e) {
            Log.e(TAG, "轮廓检测出错: " + e.getMessage());
        }
    }
    
    /**
     * 处理轮廓并寻找圆心位置 (从ovoActivity迁移)
     */
    private void processContours(List<MatOfPoint> contours, Mat rgbaMat) {
        int validContours = 0;
        List<org.opencv.core.Rect> boundingRects = new ArrayList<>();
        
        for (int i = 0; i < contours.size(); i++) {
            // 计算轮廓面积，过滤小轮廓
            double area = Imgproc.contourArea(contours.get(i));
            
            if (area > minContourArea) {
                // 计算轮廓的边界矩形
                org.opencv.core.Rect boundingRect = Imgproc.boundingRect(contours.get(i));
                boundingRects.add(boundingRect);
                validContours++;
            }
        }
        
        // 在没有矩形的区域寻找圆心
        findCircleCenter(boundingRects, rgbaMat);
        
        Log.d(TAG, "Detected " + validContours + " valid contours");
    }
    
    /**
     * 在没有矩形的区域寻找圆心 (从ovoActivity迁移)
     */
    private void findCircleCenter(List<org.opencv.core.Rect> boundingRects, Mat rgbaMat) {
        if (rgbaMat == null) return;
        
        int imageWidth = rgbaMat.cols();
        int imageHeight = rgbaMat.rows();
        int circleRadius = 50;
        
        // 定义候选圆心位置，优先选择中心区域
        org.opencv.core.Point[] candidatePoints = {
            new org.opencv.core.Point(imageWidth * 0.5, imageHeight * 0.5),   // 正中心
            new org.opencv.core.Point(imageWidth * 0.4, imageHeight * 0.5),   // 中心偏左
            new org.opencv.core.Point(imageWidth * 0.6, imageHeight * 0.5),   // 中心偏右
            new org.opencv.core.Point(imageWidth * 0.5, imageHeight * 0.4),   // 中心偏上
            new org.opencv.core.Point(imageWidth * 0.5, imageHeight * 0.6),   // 中心偏下
            new org.opencv.core.Point(imageWidth * 0.3, imageHeight * 0.3),   // 左上中心区域
            new org.opencv.core.Point(imageWidth * 0.7, imageHeight * 0.3),   // 右上中心区域
            new org.opencv.core.Point(imageWidth * 0.3, imageHeight * 0.7),   // 左下中心区域
            new org.opencv.core.Point(imageWidth * 0.7, imageHeight * 0.7),   // 右下中心区域
        };
        
        // 检查每个候选位置是否与矩形重叠
        for (org.opencv.core.Point center : candidatePoints) {
            boolean isOverlapping = false;
            
            for (org.opencv.core.Rect rect : boundingRects) {
                // 检查圆心是否在矩形内或圆形是否与矩形重叠
                double distanceToRect = getDistanceToRect(center, rect);
                if (distanceToRect < circleRadius) {
                    isOverlapping = true;
                    break;
                }
            }
            
            // 如果没有重叠，设置为圆心
            if (!isOverlapping) {
                circleCenter = center;
                break; // 只选择一个圆心
            }
        }
    }
    
    /**
     * 计算点到矩形的最短距离 (从ovoActivity迁移)
     */
    private double getDistanceToRect(org.opencv.core.Point point, org.opencv.core.Rect rect) {
        double dx = Math.max(0, Math.max(rect.x - point.x, point.x - (rect.x + rect.width)));
        double dy = Math.max(0, Math.max(rect.y - point.y, point.y - (rect.y + rect.height)));
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * 更新数据到DataManager (从ovoActivity迁移)
     */
    private void updateDataManager(Mat rgbaMat) {
        try {
            // 计算画布中心坐标
            double centerX = rgbaMat.cols() / 2.0;
            double centerY = rgbaMat.rows() / 2.0;
            
            // 更新数据到DataManager
            if (circleCenter != null) {
                dataManager.updateData(centerX, centerY, circleCenter.x, circleCenter.y);
                
                Log.d(TAG, String.format("Detection - Canvas: (%.0f, %.0f), Circle: (%.0f, %.0f)", 
                    centerX, centerY, circleCenter.x, circleCenter.y));
            } else {
                dataManager.updateData(centerX, centerY, null, null);
                Log.d(TAG, "No circle detected");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating data manager: " + e.getMessage());
        }
    }

}