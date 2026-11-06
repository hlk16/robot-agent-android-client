# 语音输入 → 语音 + 动作输出说明

> 面向：想了解或调试「机器人边说话边做动作」这条链路的人
> 关联文档：[CLIENT_API_ROBOT_ACTION.md](CLIENT_API_ROBOT_ACTION.md)（设备侧动作协议）、[DESIGN_STRUCTURED_OUTPUT.md](DESIGN_STRUCTURED_OUTPUT.md)（结构化输出设计）

---

## 1. 一句话概括

用户说话后，服务端把语音转成文字交给 LLM；LLM 按约定格式同时给出**要朗读的话**和**要做的动作**，服务端再拆成两条独立通道下发：语音走 TTS 合成，动作走 WebSocket `action` 消息。

当前约定的 LLM 输出格式（**方括号标记**）：

```
[语音回复:你好呀，我是小智][动作指令:WAVE]
```

---

## 2. 整体流程

```
设备麦克风
   │  Opus 音频帧（WebSocket 上行）
   ▼
VAD（Silero，本地）           判断这一帧有没有人声
   ▼
ASR（Doubao 流式，云端）      实时转成文字
   ▼
意图识别（function_call）      决定是否走工具（音乐、天气、退出…）
   ▼
LLM（DeepSeek）              按 prompt 约定输出：
   │                          [语音回复:你好呀][动作指令:WAVE]
   ▼
parse_robot_response()       拆成 voice_text 和 action_code
   │
   ├── voice_text ──▶ TTS（Edge TTS）──▶ Opus 音频 ──▶ 扬声器
   │
   └── action_code ─▶ WebSocket {"type":"action","action":"WAVE"} ─▶ 舵机/动画
```

另外有一条**情绪通道**：LLM 回复开头的 emoji 会被提取出来，单独下发给前端做表情。

```
LLM 回复里的 😊 ──▶ {"type":"llm","emotion":"happy","text":"😊"}
```

---

## 3. 设备会收到的消息

| 输出 | 触发时机 | WebSocket 消息 | 用途 |
|---|---|---|---|
| 识别文本 | ASR 出结果 | `{"type":"stt","text":"你好呀","session_id":"..."}` | 屏幕显示 |
| 合成语音 | 解析出 `voice_text` | `{"type":"tts","state":"start/sentence_start/stop","text":"..."}` + 二进制 Opus 帧 | 扬声器播放 |
| 动作 | 解析出 `action_code` | `{"type":"action","action":"WAVE","session_id":"..."}` | 舵机 / 动画 |
| 情绪 | 回复中检测到 emoji | `{"type":"llm","text":"😊","emotion":"happy","session_id":"..."}` | 表情 |

> 动作和语音是**两条独立通道**，客户端收到动作就执行，不要等语音播完。
> 完整设备协议见 [CLIENT_API_ROBOT_ACTION.md](CLIENT_API_ROBOT_ACTION.md)。

### 动作码表

| 代码 | 含义 |
|---|---|
| `NOACTION` | 无动作（当前实现会下发这个值） |
| `WAVE` | 挥手打招呼 |
| `CLAP` | 拍手鼓掌 |
| `THINK` | 思考动作（手托下巴） |
| `WELCOME` | 欢迎手势（双臂张开） |
| `POINT_LEFT` | 指向左 |
| `POINT_RIGHT` | 指向右 |
| `SALUTE` | 敬礼 |

---

## 4. 怎么让模型按格式输出

需要两处配合，缺一不可：

**① 角色 prompt 里写清动作清单和强制格式**

位置：[data/.config.yaml](data/.config.yaml) 的 `prompt:` 字段（约 196~226 行），例如：

```yaml
prompt: |
  你是小智……
  【动作指令 - 肢体动作】
  - [动作指令:NOACTION] 无动作
  - [动作指令:WAVE] 挥手打招呼
  ...
  【强制输出格式 - 必须严格遵守】
  [语音回复:你要说的语音内容][动作指令:动作代码]
  1. 必须以 [语音回复: 开头……
  2. 如果没有合适的动作，动作指令必须为 NOACTION
```

**② 系统级 prompt 里再次强调格式**

位置：[agent-base-prompt.txt:27](agent-base-prompt.txt#L27) 的 `<tts_format_constraints>` 第 4 条：

```
4. 【机器人输出格式】…… 必须严格按照 `[语音回复:内容][动作指令:代码]` 格式输出……
```

> 注意：角色 prompt 在 `data/.config.yaml` 里（**该文件被 gitignore**），换角色时如果漏写动作规范，模型就不会输出动作，动作功能相当于失效。

---

## 5. 代码位置

| 环节 | 文件 |
|---|---|
| 语音活动检测 | [core/providers/vad/silero.py](core/providers/vad/silero.py) |
| 流式语音识别 | [core/providers/asr/doubao_stream.py](core/providers/asr/doubao_stream.py) |
| LLM 调用与流式收集 | [core/connection.py:1002-1036](core/connection.py#L1002-L1036) |
| 情绪提取与下发 | [core/utils/textUtils.py:248](core/utils/textUtils.py#L248) |
| 回复解析（语音/动作拆分） | [core/utils/textUtils.py:111](core/utils/textUtils.py#L111) |
| 动作下发 | [core/handle/sendAudioHandle.py:352](core/handle/sendAudioHandle.py#L352) |
| 语音合成 | [core/providers/tts/edge.py](core/providers/tts/edge.py) |
| 动作码白名单 | [core/utils/textUtils.py:18](core/utils/textUtils.py#L18) `ACTION_DESCRIPTIONS` |

解析与下发的核心代码在 [core/connection.py:1151-1179](core/connection.py#L1151-L1179)：

```python
text_buff = "".join(response_message)
voice_text, action_code = textUtils.parse_robot_response(text_buff)

# 动作通道
asyncio.run_coroutine_threadsafe(
    send_action_message(self, action_code), self.loop
)

# 语音通道：按标点切句后送 TTS 队列
segments = re.split(r"([。！？!?；;\n])", voice_text)
...
```

---

## 6. 新增一个动作要改哪里

1. **[core/utils/textUtils.py:18](core/utils/textUtils.py#L18)** 的 `ACTION_DESCRIPTIONS` 里加一行（代码白名单）；
2. **[data/.config.yaml](data/.config.yaml)** 的 `prompt:` 动作清单里加一行（让模型知道这个动作）；
3. 设备端实现对应的舵机/动画；
4. 重启服务。

两处都要改，漏一处会出现「模型不会用」或「用了但被白名单拦掉」。

---

## 7. 当前实现的限制（重要）

| 限制 | 说明 |
|---|---|
| **一次回复只支持 1 个动作** | `parse_robot_response()` 只返回一个 `action_code` |
| **必须等 LLM 全部输出完才解析** | 先收完整段文本再拆标记，首音延迟受回复长度影响 |
| **格式不合规会静默降级** | 解析不到标记时：语音取原文，动作变 `NOACTION`，只有日志可查 |
| **多动作 / `delay_ms` 尚未生效** | [DESIGN_STRUCTURED_OUTPUT.md](DESIGN_STRUCTURED_OUTPUT.md) 里的 JSON 方案是设计稿；[core/utils/stream_json.py](core/utils/stream_json.py) 已实现解析器，但**尚未接入** [core/connection.py](core/connection.py) |
| **动作与语音不做时间对齐** | 动作在解析完成后立即下发，不等待语音播放进度 |

> 当前稳定可用的是**方括号标记方案**；如果看到文档里提到 JSON 结构化输出，那是下一步的改造目标，不是线上行为。

---

## 8. 快速验证

1. 启动服务：`python app.py`；
2. 设备或测试客户端连接后，说一句「你好呀」；
3. 观察服务端日志：
   - `识别文本: 你好呀`
   - `[RobotParser] 解析结果: voice_text=..., action_code=WAVE`
   - `[EMOTION] 下发成功 ... emoji=... emotion=...`
4. 客户端应先后收到：
   - `{"type":"stt",...}`
   - `{"type":"llm",...}`（情绪）
   - `{"type":"action","action":"WAVE",...}`
   - `{"type":"tts","state":"start",...}` 和音频帧

如果只听到声音、设备不动，优先检查：
- `data/.config.yaml` 的 `prompt` 里动作规范是否还在；
- LLM 原始输出是否真的带了 `[动作指令:...]`（看日志里的 `原始文本`）；
- 动作码是否在 `ACTION_DESCRIPTIONS` 白名单里。
