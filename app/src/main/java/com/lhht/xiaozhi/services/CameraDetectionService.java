package com.lhht.xiaozhi.services;

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
import java.nio.ByteBuffer;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.CvType;
import org.opencv.core.Core;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import com.lhht.xiaozhi.managers.DataManager;

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
    
    // 道路检测参数
    private static final int SCAN_START_RATIO = 50; // 从图像50%高度开始扫描
    private static final int EDGE_CONTINUITY_THRESHOLD = 50; // 边缘连续性阈值
    private static final int WHITE_PIXEL_THRESHOLD = 128; // 白色像素阈值
    private static final int DISTANCE_THRESHOLD_MIN = 200; // 最小安全距离
    private static final int DISTANCE_THRESHOLD_MAX = 350; // 最大安全距离
    private static final double TARGET_DISTANCE = 275.0; // 目标距离（安全区间中心）
    
    // PID控制参数
    private static final double PID_KP = 0.8;  // 比例系数
    private static final double PID_KI = 0.1;  // 积分系数
    private static final double PID_KD = 0.3;  // 微分系数
    private PIDController pidController;
    private boolean pidControlEnabled = true; // PID控制开关
    
    // 方向预测相关变量已移除
    
    // 检测结果存储
    private int frameWidth = 0;
    private int frameHeight = 0;
    private double roadDistance = 0.0;
    private double pidOutput = 0.0;
    private String roadStatus = "未检测";
    
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
        
        // 初始化PID控制器
        pidController = new PIDController(PID_KP, PID_KI, PID_KD);
        pidController.setSetpoint(TARGET_DISTANCE);
        pidController.setOutputLimits(-100.0, 100.0);
        
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
     * 处理相机帧数据
     */
    private void processFrame(Image image) {
        try {
            // 将Image转换为OpenCV Mat
            Mat rgbaMat = imageToMat(image);
            if (rgbaMat == null) {
                return;
            }
            
            // 执行道路检测
            detectRoadLanes(rgbaMat);
            
            // 更新数据到DataManager
            updateDataManager();
            
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
     * 道路车道线检测方法
     */
    private void detectRoadLanes(Mat rgbaMat) {
        try {
            Mat grayMat = new Mat();
            Mat binaryMat = new Mat();
            
            // 1. 转换为灰度图像
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY);
            
            // 2. 高斯模糊，减少噪声
            Imgproc.GaussianBlur(grayMat, grayMat, new org.opencv.core.Size(7, 7), 2.0);
            
            // 3. 二值化处理
            Imgproc.threshold(grayMat, binaryMat, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            
            // 4. 创建ROI掩码
            Mat roiMask = createROIMask(rgbaMat.rows(), rgbaMat.cols());
            Mat maskedBinary = new Mat();
            Core.bitwise_and(binaryMat, roiMask, maskedBinary);
            
            // 5. 扫描车道线
            List<org.opencv.core.Point> leftLanePoints = new ArrayList<>();
            List<org.opencv.core.Point> rightLanePoints = new ArrayList<>();
            List<org.opencv.core.Point> centerLinePoints = new ArrayList<>();
            
            scanLaneLines(maskedBinary, leftLanePoints, rightLanePoints, centerLinePoints);
            
            // 6. 计算车辆与右车道线的距离和PID输出
            calculateDistanceAndPID(rightLanePoints, rgbaMat.cols(), rgbaMat.rows());
            
            // 释放临时Mat
            grayMat.release();
            binaryMat.release();
            roiMask.release();
            maskedBinary.release();
            
        } catch (Exception e) {
            Log.e(TAG, "道路检测出错: " + e.getMessage());
        }
    }
    
    /**
     * 创建六边形ROI掩码
     */
    private Mat createROIMask(int height, int width) {
        Mat mask = Mat.zeros(height, width, CvType.CV_8UC1);
        
        // 定义六边形顶点
        List<org.opencv.core.Point> roiPoints = new ArrayList<>();
        roiPoints.add(new org.opencv.core.Point(width * 0.1, height * 0.95));
        roiPoints.add(new org.opencv.core.Point(width * 0.4, height * 0.6));
        roiPoints.add(new org.opencv.core.Point(width * 0.6, height * 0.6));
        roiPoints.add(new org.opencv.core.Point(width * 0.9, height * 0.95));
        roiPoints.add(new org.opencv.core.Point(width * 0.9, height));
        roiPoints.add(new org.opencv.core.Point(width * 0.1, height));
        
        // 创建多边形掩码
        MatOfPoint roiPolygon = new MatOfPoint();
        roiPolygon.fromList(roiPoints);
        List<MatOfPoint> polygons = new ArrayList<>();
        polygons.add(roiPolygon);
        
        Imgproc.fillPoly(mask, polygons, new Scalar(255));
        
        return mask;
    }
    
    /**
     * 扫描车道线
     */
    private void scanLaneLines(Mat binary, List<org.opencv.core.Point> leftLanePoints, 
                              List<org.opencv.core.Point> rightLanePoints, 
                              List<org.opencv.core.Point> centerLinePoints) {
        
        int height = binary.rows();
        int width = binary.cols();
        int startY = height * SCAN_START_RATIO / 100;
        
        // 逐行扫描
        for (int y = startY; y < height; y += 5) {
            // 扫描左车道线（图像左半部分）
            for (int x = 0; x < width / 2; x++) {
                double[] pixel = binary.get(y, x);
                if (pixel != null && pixel[0] > WHITE_PIXEL_THRESHOLD) {
                    // 检查边缘连续性
                    if (checkEdgeContinuity(binary, x, y, true)) {
                        leftLanePoints.add(new org.opencv.core.Point(x, y));
                        break; // 找到左车道线后跳出
                    }
                }
            }
            
            // 扫描右车道线（图像右半部分）
            for (int x = width - 1; x >= width / 2; x--) {
                double[] pixel = binary.get(y, x);
                if (pixel != null && pixel[0] > WHITE_PIXEL_THRESHOLD) {
                    // 检查边缘连续性
                    if (checkEdgeContinuity(binary, x, y, false)) {
                        rightLanePoints.add(new org.opencv.core.Point(x, y));
                        break; // 找到右车道线后跳出
                    }
                }
            }
        }
        
        // 计算中心线点
        int minSize = Math.min(leftLanePoints.size(), rightLanePoints.size());
        for (int i = 0; i < minSize; i++) {
            org.opencv.core.Point leftPoint = leftLanePoints.get(i);
            org.opencv.core.Point rightPoint = rightLanePoints.get(i);
            
            double centerX = (leftPoint.x + rightPoint.x) / 2;
            double centerY = (leftPoint.y + rightPoint.y) / 2;
            centerLinePoints.add(new org.opencv.core.Point(centerX, centerY));
        }
    }
    
    /**
     * 检查边缘连续性
     */
    private boolean checkEdgeContinuity(Mat binary, int x, int y, boolean isLeft) {
        int continuityCount = 0;
        int checkRange = 10; // 检查范围
        
        // 检查垂直方向的连续性
        for (int dy = -checkRange; dy <= checkRange; dy++) {
            int checkY = y + dy;
            if (checkY >= 0 && checkY < binary.rows()) {
                double[] pixel = binary.get(checkY, x);
                if (pixel != null && pixel[0] > WHITE_PIXEL_THRESHOLD) {
                    continuityCount++;
                }
            }
        }
        
        return continuityCount >= 5; // 至少5个连续点
    }
    
    /**
     * 计算车辆与右车道线的距离和PID输出
     */
    private void calculateDistanceAndPID(List<org.opencv.core.Point> rightLanePoints, int imageWidth, int imageHeight) {
        // 小车位置：图像底部中心
        org.opencv.core.Point carPosition = new org.opencv.core.Point(imageWidth / 2.0, imageHeight - 1);
        
        // 查找最底部的右车道线点（y值最大）
        org.opencv.core.Point bottomRightPoint = null;
        double maxY = -1;
        
        for (org.opencv.core.Point point : rightLanePoints) {
            // 只考虑图像下半部分的点
            if (point.y > imageHeight * 0.7 && point.y > maxY) {
                maxY = point.y;
                bottomRightPoint = point;
            }
        }
        
        if (bottomRightPoint != null) {
            // 计算水平距离（只考虑x方向的距离）
            roadDistance = Math.abs(bottomRightPoint.x - carPosition.x);
            
            // PID控制计算
            if (pidControlEnabled && pidController != null) {
                pidOutput = pidController.calculate(roadDistance);
            } else {
                pidOutput = 0.0;
            }
            
            // 根据距离区间判断状态
            if (roadDistance >= DISTANCE_THRESHOLD_MIN && roadDistance <= DISTANCE_THRESHOLD_MAX) {
                roadStatus = "直行";
            } else if (roadDistance > DISTANCE_THRESHOLD_MAX) {
                roadStatus = "右偏";
            } else {
                roadStatus = "左偏";
            }
            
            Log.d(TAG, String.format("道路检测 - 距离: %.1f, PID输出: %.1f, 状态: %s", 
                roadDistance, pidOutput, roadStatus));
        } else {
            roadDistance = 0.0;
            pidOutput = 0.0;
            roadStatus = "未检测";
        }
    }
    

    
    /**
     * 更新数据到DataManager
     */
    private void updateDataManager() {
        DataManager dataManager = DataManager.getInstance(this);
        
        // 更新道路检测结果
        dataManager.setRoadDistance(roadDistance);
        dataManager.setPidOutput(pidOutput);
        dataManager.setRoadStatus(roadStatus);
        
        // 更新检测状态
        dataManager.setDetectionActive(isDetecting);
        
        Log.d(TAG, String.format("数据已更新到DataManager - 距离: %.1f, PID: %.1f, 状态: %s", 
            roadDistance, pidOutput, roadStatus));
    }
}