package com.lhht.xiaozhi.activities.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WebRTCManager {
    private static final String TAG = "WebRTCManager";
    private static final String LOCAL_STREAM_ID = "local_stream"; // 用于标识本地媒体流
    private static final String VIDEO_TRACK_ID = "video_track";   // 用于标识视频轨道
    private static final String AUDIO_TRACK_ID = "audio_track";   // 用于标识音频轨道
    
    public interface WebRTCListener {
        //本地端生成 SDP 用于描述媒体流信息和连接信息，然后通过此方法传递给接口的实现者
        void onLocalDescription(SessionDescription sdp);
        //ICE 是一种用于在两个网络终端之间找到最佳路径的技术，它通过候选者（candidate）进行协商，
        void onIceCandidate(IceCandidate candidate);
        //当本地和远端的 SDP 和 ICE 候选者交换并连接成功后，触发此回调。
        void onConnected();
        //当连接断开时，触发此回调。
        void onDisconnected();
        //当发生错误时，触发此回调。
        void onError(String error);
        //当本地媒体流创建成功时，触发此回调。
        void onLocalStreamReady();
        //当远程媒体流准备就绪时，触发此回调。
        void onRemoteStreamReady();
        //当ICE候选者收集完成时，触发此回调，特别适用于局域网连接
        void onIceGatheringComplete();
    }
    
    private Context context;
    private WebRTCListener listener;
    //这是WebRTC中的一个核心类，用于创建PeerConnection实例
    private PeerConnectionFactory peerConnectionFactory;
    //表示一个点对点连接，用于管理媒体流和信令。
    private PeerConnection peerConnection;
    //用于管理OpenGL上下文在WebRTC中，视频渲染是通过OpenGL ES来实现的。
    private EglBase eglBase;
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private VideoCapturer videoCapturer;
    //视频源，从VideoCapturer获取视频帧，并将其传递给VideoTrack
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private MediaStream localStream;
    //WebRTCManager的构造函数
    public WebRTCManager(Context context, WebRTCListener listener) {
        this.context = context;
        this.listener = listener;
        initializePeerConnectionFactory();
    }
    //PeerConnectionFactory，它是WebRTC库中的一个核心组件，
    // 用于创建和管理对等连接（PeerConnection），以实现实时通信功能，如视频通话和实时视频流。
    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);
        
        eglBase = EglBase.create();
        
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
        
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();
    }
    //PeerConnectionFactory，它是WebRTC库中的一个核心组件，
    // 用于创建和管理对等连接（PeerConnection），以实现实时通信功能，如视频通话和实时视频流。
    public void initializeViews(SurfaceViewRenderer localVideoView, SurfaceViewRenderer remoteVideoView) {
        this.localVideoView = localVideoView;
        this.remoteVideoView = remoteVideoView;
        
        localVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        
        localVideoView.setMirror(true);
        remoteVideoView.setMirror(false);
    }
    //启动本地视频捕获和音频捕获的方法
    public void startLocalVideo() {
        try {
            Log.d(TAG, "开始创建本地视频流");
            
            // 检查必要的组件是否已初始化
            if (peerConnectionFactory == null) {
                Log.e(TAG, "PeerConnectionFactory未初始化");
                if (listener != null) {
                    listener.onError("PeerConnectionFactory未初始化");
                }
                return;
            }
            
            if (eglBase == null) {
                Log.e(TAG, "EglBase未初始化");
                if (listener != null) {
                    listener.onError("EglBase未初始化");
                }
                return;
            }
            
            // 创建摄像头捕获器
            videoCapturer = createCameraCapturer();
            if (videoCapturer == null) {
                Log.e(TAG, "无法创建摄像头捕获器 - 可能是权限问题或设备不可用");
                if (listener != null) {
                    listener.onError("无法访问摄像头，请检查权限设置");
                }
                return;
            }
            Log.d(TAG, "摄像头捕获器创建成功");
            
            // 创建视频源和轨道
            try {
                SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
                if (surfaceTextureHelper == null) {
                    Log.e(TAG, "SurfaceTextureHelper创建失败");
                    if (listener != null) {
                        listener.onError("视频渲染初始化失败");
                    }
                    return;
                }
                
                videoSource = peerConnectionFactory.createVideoSource(false);
                if (videoSource == null) {
                    Log.e(TAG, "VideoSource创建失败");
                    if (listener != null) {
                        listener.onError("视频源创建失败");
                    }
                    return;
                }
                
                videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
                videoCapturer.startCapture(1280, 720, 30);
                Log.d(TAG, "视频捕获启动成功");
                
                localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
                if (localVideoTrack == null) {
                    Log.e(TAG, "VideoTrack创建失败");
                    if (listener != null) {
                        listener.onError("视频轨道创建失败");
                    }
                    return;
                }
                
                if (localVideoView != null) {
                    localVideoTrack.addSink(localVideoView);
                    Log.d(TAG, "本地视频显示设置成功");
                } else {
                    Log.w(TAG, "localVideoView为空，跳过视频显示设置");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "视频初始化失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError("视频初始化失败: " + e.getMessage());
                }
                return;
            }
            
            // 创建音频源和轨道
            try {
                MediaConstraints audioConstraints = new MediaConstraints();
                audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
                if (audioSource == null) {
                    Log.e(TAG, "AudioSource创建失败");
                    if (listener != null) {
                        listener.onError("音频源创建失败");
                    }
                    return;
                }
                
                localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
                if (localAudioTrack == null) {
                    Log.e(TAG, "AudioTrack创建失败");
                    if (listener != null) {
                        listener.onError("音频轨道创建失败");
                    }
                    return;
                }
                Log.d(TAG, "音频轨道创建成功");
                
            } catch (Exception e) {
                Log.e(TAG, "音频初始化失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError("音频初始化失败: " + e.getMessage());
                }
                return;
            }
            
            // 创建本地媒体流
            try {
                localStream = peerConnectionFactory.createLocalMediaStream(LOCAL_STREAM_ID);
                if (localStream == null) {
                    Log.e(TAG, "LocalMediaStream创建失败");
                    if (listener != null) {
                        listener.onError("本地媒体流创建失败");
                    }
                    return;
                }
                
                localStream.addTrack(localVideoTrack);
                localStream.addTrack(localAudioTrack);
                Log.d(TAG, "本地媒体流创建成功，包含视频和音频轨道");
                
                if (listener != null) {
                    listener.onLocalStreamReady();
                }
                
                // 如果PeerConnection已经创建，立即添加本地媒体流
                if (peerConnection != null) {
                    addLocalStreamToPeerConnection();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "本地媒体流创建失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError("本地媒体流创建失败: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "startLocalVideo发生未预期异常: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError("本地视频初始化异常: " + e.getMessage());
            }
        }
    }
    //从摄像头捕获视频流
    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(context)) {
            enumerator = new Camera2Enumerator(context);
        } else {
            enumerator = new Camera1Enumerator(true);
        }
        
        for (String deviceName : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        
        for (String deviceName : enumerator.getDeviceNames()) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        
        return null;
    }
    //用于建立和管理点对点连接，支持音视频通信和数据传输
    //优化局域网配置，支持多种ICE候选者类型，提高连接成功率
    public void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        
        // 局域网环境下可以添加本地STUN服务器或使用空列表
        // 如果需要跨网络连接，可以取消注释以下STUN服务器
        // iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        // iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        
        // 局域网优化配置 - 允许所有类型的ICE候选者
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        
        // TCP候选者策略 - 局域网环境下启用TCP以提高连接成功率
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        
        // ICE候选者池大小 - 增加候选者数量以提高连接成功率
        rtcConfig.iceCandidatePoolSize = 4;
        
        // 启用IPv6支持（如果网络环境支持）
        // rtcConfig.enableIPv6 = true; // 根据需要启用
        
        Log.d(TAG, "ICE配置 - 传输类型: ALL, TCP策略: ENABLED, 候选者池大小: 4");
        
        try {
            Log.d(TAG, "创建PeerConnection - 局域网模式（无STUN服务器）");
            
            // 检查peerConnectionFactory是否有效
            if (peerConnectionFactory == null) {
                Log.e(TAG, "PeerConnectionFactory为空，无法创建PeerConnection");
                if (listener != null) {
                    listener.onError("PeerConnectionFactory未初始化");
                }
                return;
            }
            
            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnectionObserver());
            
            if (peerConnection == null) {
                Log.e(TAG, "PeerConnection创建失败");
                if (listener != null) {
                    listener.onError("PeerConnection创建失败");
                }
                return;
            }
            
            Log.d(TAG, "PeerConnection创建成功");
            
            // 不在这里立即添加本地媒体流，而是在需要时单独调用addLocalStream方法
            Log.d(TAG, "PeerConnection已准备就绪，等待添加媒体流");
            
        } catch (Exception e) {
            Log.e(TAG, "创建PeerConnection时发生异常: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError("PeerConnection创建异常: " + e.getMessage());
            }
        }
    }
    
    // 添加本地媒体流到PeerConnection
    public void addLocalStreamToPeerConnection() {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection为空，无法添加媒体流");
            if (listener != null) {
                listener.onError("PeerConnection未创建");
            }
            return;
        }
        
        if (localStream == null) {
            Log.e(TAG, "本地媒体流为空，无法添加到PeerConnection");
            if (listener != null) {
                listener.onError("本地媒体流未创建");
            }
            return;
        }
        
        try {
            // 使用现代的addTrack()方法替代已废弃的addStream()
            // 创建stream IDs列表
            List<String> streamIds = Arrays.asList(LOCAL_STREAM_ID);
            
            // 添加视频轨道
            if (localVideoTrack != null) {
                peerConnection.addTrack(localVideoTrack, streamIds);
                Log.d(TAG, "视频轨道已成功添加到PeerConnection");
            }
            
            // 添加音频轨道
            if (localAudioTrack != null) {
                peerConnection.addTrack(localAudioTrack, streamIds);
                Log.d(TAG, "音频轨道已成功添加到PeerConnection");
            }
            
            Log.d(TAG, "本地媒体轨道已成功添加到PeerConnection");
        } catch (Exception e) {
            Log.e(TAG, "添加本地媒体轨道到PeerConnection失败: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError("添加媒体轨道失败: " + e.getMessage());
            }
        }
    }
    
    //主要用途是在WebRTC连接中发起一个呼叫(Offer)$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$
    public void createOffer() {
        if (peerConnection != null) {
            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            
            peerConnection.createOffer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sessionDescription) {}
                        
                        @Override
                        public void onSetSuccess() {
                            if (listener != null) {
                                listener.onLocalDescription(sessionDescription);
                            }
                        }
                        
                        @Override
                        public void onCreateFailure(String s) {}
                        
                        @Override
                        public void onSetFailure(String s) {
                            if (listener != null) {
                                listener.onError("设置本地描述失败: " + s);
                            }
                        }
                    }, sessionDescription);
                }
                
                @Override
                public void onSetSuccess() {}
                
                @Override
                public void onCreateFailure(String s) {
                    if (listener != null) {
                        listener.onError("创建Offer失败: " + s);
                    }
                }
                
                @Override
                public void onSetFailure(String s) {}
            }, constraints);
        }
    }
    //当接收到一个邀请（Offer）SDP后，需要创建一个应答（Answer）SDP来响应邀请$$$$$$$$$$$$$$$$$$$$$$$$
    public void createAnswer() {
        if (peerConnection != null) {
            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            
            peerConnection.createAnswer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sessionDescription) {}
                        
                        @Override
                        public void onSetSuccess() {
                            if (listener != null) {
                                listener.onLocalDescription(sessionDescription);
                            }
                        }
                        
                        @Override
                        public void onCreateFailure(String s) {}
                        
                        @Override
                        public void onSetFailure(String s) {
                            if (listener != null) {
                                listener.onError("设置本地描述失败: " + s);
                            }
                        }
                    }, sessionDescription);
                }
                
                @Override
                public void onSetSuccess() {}
                
                @Override
                public void onCreateFailure(String s) {
                    if (listener != null) {
                        listener.onError("创建Answer失败: " + s);
                    }
                }
                
                @Override
                public void onSetFailure(String s) {}
            }, constraints);
        }
    }
    //主要用途是在WebRTC连接过程中，接收并设置来自对方的会话描述，以便建立或更新连接参数$$$$$$$$$$$$$$
    public void setRemoteDescription(SessionDescription sessionDescription) {
        if (peerConnection != null) {
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {}
                
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "远程描述设置成功");
                }
                
                @Override
                public void onCreateFailure(String s) {}
                
                @Override
                public void onSetFailure(String s) {
                    if (listener != null) {
                        listener.onError("设置远程描述失败: " + s);
                    }
                }
            }, sessionDescription);
        }
    }
    //用于在WebRTC连接过程中，添加ICE候选者，以便建立或更新连接参数，支持局域网优化
    public void addIceCandidate(IceCandidate iceCandidate) {
        if (peerConnection != null) {
            try {
                Log.d(TAG, "添加ICE候选者: " + iceCandidate.sdp + " (" + iceCandidate.sdpMid + ")");
                peerConnection.addIceCandidate(iceCandidate);
                Log.d(TAG, "ICE候选者添加成功");
            } catch (Exception e) {
                Log.e(TAG, "添加ICE候选者失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError("添加ICE候选者失败: " + e.getMessage());
                }
            }
        } else {
            Log.w(TAG, "PeerConnection为空，无法添加ICE候选者");
            if (listener != null) {
                listener.onError("PeerConnection未创建，无法添加ICE候选者");
            }
        }
    }
    
    //移除ICE候选者的方法，用于连接优化
    public void removeIceCandidates(IceCandidate[] iceCandidates) {
        if (peerConnection != null && iceCandidates != null) {
            try {
                Log.d(TAG, "移除 " + iceCandidates.length + " 个ICE候选者");
                peerConnection.removeIceCandidates(iceCandidates);
                Log.d(TAG, "ICE候选者移除成功");
            } catch (Exception e) {
                Log.e(TAG, "移除ICE候选者失败: " + e.getMessage(), e);
            }
        }
    }
    //用于在WebRTC连接过程中，移除ICE候选者，以便建立或更新连接参数$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$
    public void close() {
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "停止视频捕获失败", e);
            }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        
        if (localVideoView != null) {
            localVideoView.release();
        }
        
        if (remoteVideoView != null) {
            remoteVideoView.release();
        }
        
        if (eglBase != null) {
            eglBase.release();
        }
    }
    //用于在WebRTC连接过程中，处理信令状态变化，ICE连接状态变化，
    // ICE收集状态变化，ICE候选被移除，添加远程流，移除远程流等事件$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$
    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "信令状态变化: " + signalingState);
        }
        
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "ICE连接状态变化: " + iceConnectionState);
            if (listener != null) {
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    listener.onConnected();
                } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                          iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    listener.onDisconnected();
                }
            }
        }
        
        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Log.d(TAG, "ICE连接接收状态变化: " + b);
        }
        
        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "ICE收集状态变化: " + iceGatheringState);
            
            // 根据ICE收集状态进行相应处理
            switch (iceGatheringState) {
                case NEW:
                    Log.d(TAG, "开始ICE候选者收集");
                    break;
                case GATHERING:
                    Log.d(TAG, "正在收集ICE候选者...");
                    break;
                case COMPLETE:
                    Log.d(TAG, "ICE候选者收集完成");
                    // 通知应用层ICE收集已完成，特别适用于局域网连接
                    if (listener != null) {
                        listener.onIceGatheringComplete();
                    }
                    break;
            }
        }
        
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "发现新的ICE候选者: " + iceCandidate.sdp + " (类型: " + getIceCandidateType(iceCandidate.sdp) + ")");
            if (listener != null) {
                listener.onIceCandidate(iceCandidate);
            }
        }
        
        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "ICE候选被移除，数量: " + (iceCandidates != null ? iceCandidates.length : 0));
            // 通知上层应用ICE候选者被移除，可以进行相应的清理工作
            if (iceCandidates != null) {
                for (IceCandidate candidate : iceCandidates) {
                    Log.d(TAG, "移除的候选者: " + candidate.sdp);
                }
            }
        }
        
        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "添加远程流");
            if (mediaStream.videoTracks.size() > 0 && remoteVideoView != null) {
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                // 在主线程中更新UI
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        remoteVideoTrack.addSink(remoteVideoView);
                        Log.d(TAG, "远程视频轨道已添加到视图");
                        // 通知远程视频流准备就绪
                        if (listener != null) {
                            listener.onRemoteStreamReady();
                        }
                    });
                } else {
                    remoteVideoTrack.addSink(remoteVideoView);
                    Log.d(TAG, "远程视频轨道已添加到视图");
                    // 通知远程视频流准备就绪
                    if (listener != null) {
                        listener.onRemoteStreamReady();
                    }
                }
            } else {
                Log.w(TAG, "无远程视频轨道或remoteVideoView为空");
            }
        }
        
        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "移除远程流");
        }
        
        @Override
        public void onDataChannel(org.webrtc.DataChannel dataChannel) {
            Log.d(TAG, "数据通道创建");
        }
        
        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "需要重新协商");
        }
        
        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            Log.d(TAG, "添加轨道: " + rtpReceiver.track().kind());
            
            if (rtpReceiver.track() != null && rtpReceiver.track().kind().equals("video")) {
                VideoTrack remoteVideoTrack = (VideoTrack) rtpReceiver.track();
                if (remoteVideoView != null) {
                    // 在主线程中更新UI
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(() -> {
                            remoteVideoTrack.addSink(remoteVideoView);
                            Log.d(TAG, "远程视频轨道已通过onAddTrack添加到视图");
                        });
                    } else {
                        remoteVideoTrack.addSink(remoteVideoView);
                        Log.d(TAG, "远程视频轨道已通过onAddTrack添加到视图");
                    }
                } else {
                    Log.w(TAG, "remoteVideoView为空，无法显示远程视频");
                }
            }
        }
    }
    
    // 辅助方法：识别ICE候选者类型，用于调试和局域网优化
    private String getIceCandidateType(String sdp) {
        if (sdp == null) return "unknown";
        
        if (sdp.contains("typ host")) {
            return "host(本地)";  // 主机候选者，通常是局域网IP
        } else if (sdp.contains("typ srflx")) {
            return "srflx(服务器反射)";  // 服务器反射候选者，通过STUN获得
        } else if (sdp.contains("typ prflx")) {
            return "prflx(对等反射)";  // 对等反射候选者
        } else if (sdp.contains("typ relay")) {
            return "relay(中继)";  // 中继候选者，通过TURN服务器
        } else {
            return "unknown";
        }
    }
    
    // 获取ICE连接统计信息，用于调试
    public void getIceConnectionStats() {
        if (peerConnection != null) {
            Log.d(TAG, "当前ICE连接状态: " + peerConnection.iceConnectionState());
            Log.d(TAG, "当前ICE收集状态: " + peerConnection.iceGatheringState());
            Log.d(TAG, "当前信令状态: " + peerConnection.signalingState());
        } else {
            Log.w(TAG, "PeerConnection为空，无法获取统计信息");
        }
    }
    
    // 强制重新收集ICE候选者，用于网络环境变化时
    public void restartIce() {
        if (peerConnection != null) {
            try {
                Log.d(TAG, "重新启动ICE收集");
                peerConnection.restartIce();
            } catch (Exception e) {
                Log.e(TAG, "重新启动ICE失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError("重新启动ICE失败: " + e.getMessage());
                }
            }
        } else {
            Log.w(TAG, "PeerConnection为空，无法重新启动ICE");
        }
    }
    
    // 检查网络环境并提供局域网连接建议
    public void checkNetworkEnvironment() {
        Log.d(TAG, "=== 局域网WebRTC连接环境检查 ===");
        Log.d(TAG, "当前配置:");
        Log.d(TAG, "- ICE传输类型: ALL (支持UDP/TCP)");
        Log.d(TAG, "- TCP候选者策略: ENABLED (提高局域网连接成功率)");
        Log.d(TAG, "- ICE候选者池大小: 4 (增加连接选项)");
        Log.d(TAG, "- STUN服务器: 已禁用 (适用于局域网)");
        Log.d(TAG, "");
        Log.d(TAG, "局域网连接建议:");
        Log.d(TAG, "1. 确保两台设备在同一网络中");
        Log.d(TAG, "2. 检查防火墙设置，允许WebRTC通信");
        Log.d(TAG, "3. 如需跨网络连接，请启用STUN服务器");
        Log.d(TAG, "4. 监听onIceGatheringComplete()回调确认ICE收集完成");
        Log.d(TAG, "5. 观察ICE候选者类型，host类型适用于局域网");
        Log.d(TAG, "=================================");
        
        if (peerConnection != null) {
            getIceConnectionStats();
        }
    }
}