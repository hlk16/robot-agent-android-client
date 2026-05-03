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

### 3. 视频理解
- **实时预览**：支持前置摄像头实时预览
- **图像识别**：集成讯飞图像识别能力，支持视觉问答
- **视频通话**：支持视频流传输（开发中）

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
| 相机 | CameraX |
| 图像识别 | 讯飞 SparkChain SDK |
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

## 配置说明

### API 配置
在应用设置中配置以下参数：

- **WebSocket 地址**：服务端 WebSocket 连接地址
- **Token**（可选）：连接认证令牌
- **讯飞 API**：AppID、API Key、API Secret（用于图像识别）

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
- ✅ 视频预览与图像识别
- ✅ Opus 音频编解码

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
- [讯飞开放平台](https://www.xfyun.cn/) - 语音识别与图像识别能力
