# 小智智能助手 - Android 客户端

## 项目简介

小智智能助手是一个基于 Android 平台的智能语音交互应用，作为机器人 Agent 系统的前端客户端，负责与云端/本地 AI 服务进行通信，实现语音对话、硬件控制等功能。

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                     硬件层 (FreeRTOS)                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   传感器    │  │   执行器    │  │    蓝牙模块         │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │ BLE/蓝牙
┌──────────────────────────▼──────────────────────────────────┐
│                   客户端层 (Android)                         │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              小智 Android 应用                      │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌────────────┐   │    │
│  │  │  语音交互   │  │  蓝牙通信   │  │  视频通话  │   │    │
│  │  │  - 录音     │  │  - 指令发送 │  │  - 预览    │   │    │
│  │  │  - 播放     │  │  - 状态接收 │  │  - 识别    │   │    │
│  │  │  - Opus编解码│ │             │  │            │   │    │
│  │  └─────────────┘  └─────────────┘  └────────────┘   │    │
│  └────────────────────────┬────────────────────────────┘    │
└───────────────────────────┼─────────────────────────────────┘
                            │ WebSocket
┌───────────────────────────▼─────────────────────────────────┐
│                    服务端层 (Agent)                          │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              AI Agent 服务                            │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐  │  │
│  │  │  语音识别   │  │  大模型推理 │  │  语音合成    │  │  │
│  │  │  (ASR)      │  │  (LLM)      │  │  (TTS)       │  │  │
│  │  └─────────────┘  └─────────────┘  └──────────────┘  │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 功能特性

### 1. 语音交互
- **实时语音通话**：支持全双工语音对话，低延迟音频传输
- **Opus 音频编解码**：使用 Opus 编码格式进行音频压缩，降低网络带宽占用
- **智能打断**：支持点击屏幕打断 AI 当前回复
- **音频焦点管理**：自动处理音频焦点，避免与其他应用冲突
- **回声消除 & 噪声抑制**：集成 AEC 和 NS 提升通话质量

### 2. 硬件控制
- **蓝牙通信**：通过蓝牙与 FreeRTOS 硬件设备通信
- **指令下发**：支持发送控制指令到硬件层
- **状态同步**：实时接收硬件状态反馈

### 3. MCP 视觉识别
- **实时预览**：支持前置摄像头实时预览
- **MCP 协议集成**：通过 WebSocket MCP（Model Context Protocol）实现视觉问答
- **服务端调度**：服务端发送 `tools/call` 消息触发视觉请求，客户端拍照并回传结果
- **WebSocket 保活**：视觉请求期间定时发送 ping 消息，防止长 HTTP 请求导致 WebSocket 超时断开
- **多轮对话**：支持连续视觉问答，上下文由服务端维护

### 4. 网络通信
- **WebSocket 连接**：基于 WebSocket 的长连接通信
- **Token 认证**：支持可选的 Token 认证机制
- **自动重连**：连接断开后自动恢复
- **二进制数据传输**：支持音频二进制数据流传输

## 技术栈

| 模块 | 技术选型 |
|------|----------|
| 编程语言 | Java |
| 网络通信 | WebSocket (OkHttp) |
| 音频处理 | AudioRecord / AudioTrack / Opus |
| 蓝牙通信 | Android Bluetooth API |
| 相机 | Camera2 API |
| 视觉识别 | MCP 协议 (服务端调度) |
| 构建工具 | Gradle |

## 项目结构

```
app/src/main/java/com/lhht/xiaozhi/
├── activities/                 # Activity 界面层
│   ├── MainActivity.java       # 主界面（文字对话）
│   ├── Voice.java              # 语音通话界面
│   ├── ChatActivity.java       # 聊天界面
│   ├── SettingsActivity.java   # 设置界面
│   └── MenuActivity.java       # 菜单界面
├── websocket/                  # WebSocket 通信层
│   └── WebSocketManager.java   # WebSocket 连接管理
├── settings/                   # 配置管理
│   └── SettingsManager.java    # 用户配置持久化
├── views/                      # 自定义视图
│   └── WaveformView.java       # 音频波形显示
├── api/                        # 第三方 API 集成
│   └── ImageRecognitionManager.java  # 图像识别
└── BtThread/                   # 蓝牙通信线程
    └── ConnectedThread.java    # 蓝牙连接线程
```

## 核心流程

### 语音交互流程

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   用户说话   │────▶│  音频采集    │────▶│  Opus 编码   │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
┌──────────────┐     ┌──────────────┐     ┌──────▼───────┐
│   播放回复   │◀────│  Opus 解码   │◀────│  WebSocket   │
│   AudioTrack │     │  缓冲区队列  │     │  网络传输    │
└──────────────┘     └──────────────┘     └──────────────┘
```

### 音频数据处理流程

```
服务器 → WebSocket → 解码 → 格式转换 → 队列缓冲 → AudioTrack 播放

① 接收    onBinaryMessage(byte[] data)     WebSocket 接收 Opus 编码的二进制音频数据
② 线程分发 audioExecutor.execute()          将解码任务放入音频专用线程池异步处理
③ 解码    opusUtils.decode()               Opus 解码：压缩数据 → PCM short[]
④ 格式转换 short[] → byte[]                PCM 数据转换：short[] 转为 byte[] 供播放
⑤ 入队    audioQueue.offer(pcmData)        将 PCM 数据放入阻塞队列，实现播放缓冲
⑥ 出队    audioQueue.poll()                播放线程从队列取出数据（超时100ms）
⑦ 播放    audioTrack.write()               AudioTrack 将 PCM 数据写入硬件播放
```

### MCP 视觉识别流程

```
┌──────────────┐  WebSocket   ┌──────────────┐  HTTP POST  ┌──────────────┐
│   服务端     │───tools/call──▶│   客户端     │────────────▶│  视觉服务    │
│  (AI Agent)  │              │  (Voice.java) │◀────────────│  (Vision API)│
│              │◀──result------│              │             │              │
└──────────────┘              └──────────────┘             └──────────────┘
```

**实现细节：**

1. **握手阶段**：客户端发送 `hello` 消息时携带 `"mcp": true`，告知服务端支持 MCP 协议
2. **工具注册**：服务端返回 `tools/list`，客户端响应可用工具列表（如 `camera_capture`、`camera_stream`）
3. **视觉请求触发**：用户询问视觉相关问题时，服务端发送 `tools/call` 消息，包含工具名、参数和请求 ID
4. **帧捕获与编码**：客户端从前置摄像头预览中捕获最新 YUV 帧，转换为 JPEG 格式
5. **HTTP 上传**：通过 Multipart POST 将 JPEG 图像和问题文本发送到服务端视觉 API
6. **结果返回**：将视觉服务的响应封装为 MCP `result` 消息，通过 WebSocket 回传给服务端

**关键代码位置：** [Voice.java](app/src/main/java/com/lhht/xiaozhi/activities/Voice.java)

### WebSocket 保活机制

视觉请求期间，客户端会持续发送 `{"type":"ping"}` 消息防止 WebSocket 超时断开：

```
视觉请求开始
    │
    ├─→ 启动 ping 定时器（每 5 秒发送一次）
    ├─→ 发送 HTTP 请求到视觉服务（可能耗时 10-30 秒）
    │
    └─→ 收到响应后停止 ping 定时器
```

**实现方式：**

```java
private final Runnable pingRunnable = new Runnable() {
    @Override
    public void run() {
        if (!isMcpInProgress) return;
        if (webSocketManager != null && webSocketManager.isConnected()) {
            webSocketManager.sendMessage("{\"type\":\"ping\"}");
        }
        mainHandler.postDelayed(this, 5000);
    }
};
```

**为什么需要保活：**
- 视觉 HTTP 请求可能耗时 10-30 秒（服务端冷启动时更长）
- 期间 WebSocket 没有数据交互，服务端可能判定连接空闲而断开
- Ping 消息更新服务端的 `last_activity_time`，保持连接活跃

## 配置说明

### API 配置
在应用设置中配置以下参数：

- **WebSocket 地址**：服务端 WebSocket 连接地址
- **Token**（可选）：连接认证令牌

### 权限要求

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

## 开发计划

### 当前版本（v1.0）
- ✅ 基础语音对话功能
- ✅ WebSocket 通信
- ✅ 蓝牙硬件控制
- ✅ MCP 视觉识别（服务端调度）
- ✅ Opus 音频编解码
- ✅ WebSocket 保活机制

### 未来规划
- 🚧 本地推理支持（MNN 框架集成）
  - 本地语音识别模型
  - 本地大语言模型
  - 本地语音合成模型
- 🚧 多模态交互增强
- 🚧 离线模式支持

## 本地推理架构（规划中）

```
┌─────────────────────────────────────────────┐
│              Android 设备                    │
│  ┌───────────────────────────────────────┐  │
│  │           MNN 推理引擎                 │  │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ │  │
│  │  │  ASR    │ │  LLM    │ │  TTS    │ │  │
│  │  │  模型   │ │  模型   │ │  模型   │ │  │
│  │  └─────────┘ └─────────┘ └─────────┘ │  │
│  └───────────────────────────────────────┘  │
│              ↑ 离线推理                      │
│  ┌───────────────────────────────────────┐  │
│  │         小智应用层                     │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

## 开发者信息

- **作者**：电子裁缝-叫我康康
- **平台**：Bilibili
- **声明**：该项目目前免费开源，仅供学习交流使用

## 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

```
MIT License

Copyright (c) 2025 小智智能助手

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 致谢

- [Opus](https://opus-codec.org/) - 开源音频编解码器
- [MNN](https://github.com/alibaba/MNN) - 阿里开源推理引擎
