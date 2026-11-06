/*
 * ============================================================================
 *  小智机器人 ESP32 固件  ——  XZ-Link/1 协议
 * ============================================================================
 *
 *  协议规范：docs/BLUETOOTH_PROTOCOL_V2.md
 *  传输：经典蓝牙 SPP（BluetoothSerial），US-ASCII，帧以 '\n' 结尾
 *
 *  与上一版固件的区别：
 *    1. 加了帧定界，多字节指令可以可靠切分
 *    2. 加了握手 HELLO/READY，带协议版本和固件版本
 *    3. 加了心跳 PING/PONG，链路断了能被发现
 *    4. 加了 5 秒无帧自动复位，防止手机断开车还在动
 *    5. 动作动画改成非阻塞状态机 —— 原来 wave() 会阻塞 6 秒，
 *       期间读不到蓝牙，心跳必然超时，App 会误判断线
 *    6. 旧版单字符指令（'f' 挥手、'g' 鼓掌）仍然兼容，见 on_byte()
 *
 *  -- 用蓝牙串口助手手工测试（记得开"发送换行"）--
 *      HELLO,1        握手，应回 READY,1,2.0.0
 *      PING           应回 PONG
 *      ACT,WAVE       挥手
 *      ACT,CLAP       鼓掌
 *      ACT,SALUTE     敬礼
 *      RESET          回中位
 *
 *  注意：本板只有 4 路舵机，没有电机驱动。MOVE,FWD/BCK/LEFT/RIGHT
 *        会返回 NAK,MOVE,xxx,7（不支持该能力）。接上电机后，
 *        把 HAS_MOTORS 改成 1 并实现 motor_* 函数即可。
 * ============================================================================
 */

#include "BluetoothSerial.h"
#include <ESP32Servo.h>
#include <stdarg.h>
#include <string.h>

// ===================== 协议常量 =====================
#define PROTO_VERSION   "1"
#define FW_VERSION      "2.0.0"
#define FRAME_MAX       96                  // 单帧最大字节数（含 '\n'）
#define LINK_TIMEOUT_MS 5000UL              // 超过这个时间没收到任何帧 -> 安全复位

// ===================== 硬件能力 =====================
// 本板没有电机驱动，MOVE 指令一律回 NAK。接上电机后改成 1。
#define HAS_MOTORS      0

// ===================== 硬件对象 =====================
BluetoothSerial SerialBT;

enum { IDX_33 = 0, IDX_32, IDX_27, IDX_26, SERVO_COUNT };

static const uint8_t SERVO_PIN[SERVO_COUNT] = {33, 32, 27, 26};
// 中位角（沿用上一版固件的初值，不要随意改，改了可能顶到机械限位）
static const uint8_t HOME_ANGLE[SERVO_COUNT] = {90, 180, 0, 90};

Servo servos[SERVO_COUNT];

// ===================== 动画引擎 =====================
// 每一步：把某个舵机转到某个角度，然后保持 holdMs 毫秒。
// 原来的 delay(1000) 全部拆成这里的 holdMs，主循环因此不会被堵住。
struct AnimStep {
    uint8_t  servo;
    uint8_t  angle;
    uint16_t holdMs;
};

// 角度数值与原固件完全一致
static const AnimStep CLAP_STEPS[] = {
    {IDX_32,  90,    0}, {IDX_27,  90, 1000},
    {IDX_33, 110,    0}, {IDX_26,  70, 1000},
    {IDX_33,  90,    0}, {IDX_26,  90, 1000},
    {IDX_33, 110,    0}, {IDX_26,  70, 1000},
    {IDX_33,  90,    0}, {IDX_26,  90, 1000},
};

static const AnimStep WAVE_STEPS[] = {
    {IDX_27, 120, 1000},
    {IDX_26,  80, 1000},
    {IDX_26, 100, 1000},
    {IDX_26,  80, 1000},
    {IDX_26,  90, 1000},
    {IDX_27,   0, 1000},
};

static const AnimStep SALUTE_STEPS[] = {
    {IDX_27, 120, 1000},
    {IDX_26,  70, 3000},
    {IDX_26,  90, 1000},
    {IDX_27,   0, 1000},
};

#define STEP_COUNT(a) ((uint8_t)(sizeof(a) / sizeof((a)[0])))

struct ActionDef {
    const char     *name;
    const AnimStep *steps;
    uint8_t         count;
    bool            homeAfter;   // 动作结束后是否回中位
};

static const ActionDef ACTIONS[] = {
    {"WAVE",   WAVE_STEPS,   STEP_COUNT(WAVE_STEPS),   false},
    {"CLAP",   CLAP_STEPS,   STEP_COUNT(CLAP_STEPS),   true },
    {"SALUTE", SALUTE_STEPS, STEP_COUNT(SALUTE_STEPS), false},
};

static const ActionDef *find_action(const char *name) {
    for (uint8_t i = 0; i < sizeof(ACTIONS) / sizeof(ACTIONS[0]); i++) {
        if (strcmp(ACTIONS[i].name, name) == 0) return &ACTIONS[i];
    }
    return NULL;
}

// 当前正在跑的动作；NULL 表示空闲
static const ActionDef *curAction = NULL;
static uint8_t  curIdx  = 0;
static uint32_t stepAt  = 0;

// ===================== 链路状态 =====================
static char     rxBuf[FRAME_MAX];
static uint8_t  rxLen        = 0;
static uint32_t rxStartMs    = 0;
static bool     handshaken   = false;
static uint32_t lastRxMs     = 0;
static bool     safetyLatched = false;   // 安全动作是否已执行过，避免每圈重复
static bool     btWasConnected = false;  // 用于检测 hasClient() 的下降沿
// 帧结束符缺失时的兜底：200 ms 没有新字节，就把缓冲区当成一帧。
// 思路同 Modbus RTU 的"3.5 字符静默间隔"。
// 规范仍然要求发送方带结束符，这里只是让不会配换行的串口助手也能联调。
#define FRAME_IDLE_GAP_MS 200UL

// 收到任何数据都刷新链路活跃时间，并解除安全动作的锁存
static void mark_rx(uint32_t now) {
    lastRxMs      = now;
    safetyLatched = false;
}

// 去掉首尾的空格和制表符（原地修改，返回新的起点）。
// 中文输入法在英文后面很容易多打一个空格，严格报错会让联调非常难受。
static char *trim(char *s) {
    while (*s == ' ' || *s == '\t') s++;
    char *e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t')) *--e = '\0';
    return s;
}

// ===================== 前向声明 =====================
static void on_byte(uint8_t b);
static void handle_frame(const char *raw);
static void handle_v1(char c);
static void send_frame(const char *fmt, ...);

// ===================== 舵机 =====================
static void home() {
    for (uint8_t i = 0; i < SERVO_COUNT; i++) {
        servos[i].write(HOME_ANGLE[i]);
    }
}

static void anim_start(const ActionDef *a) {
    curAction = a;
    curIdx    = 0;
    servos[a->steps[0].servo].write(a->steps[0].angle);
    stepAt = millis();
    Serial.print(F("[ANIM] 开始 "));
    Serial.println(a->name);
}

static void anim_abort() {
    if (curAction) {
        Serial.print(F("[ANIM] 中止 "));
        Serial.println(curAction->name);
        curAction = NULL;
    }
}

// 非阻塞推进动画，主循环每圈调一次
static void anim_tick(uint32_t now) {
    if (!curAction) return;

    const AnimStep &s = curAction->steps[curIdx];
    if (now - stepAt < s.holdMs) return;      // 这一步还没到时间

    curIdx++;
    if (curIdx >= curAction->count) {         // 动作结束
        const ActionDef *done = curAction;
        curAction = NULL;
        if (done->homeAfter) home();
        send_frame("ACT_DONE,%s", done->name);
        Serial.print(F("[ANIM] 完成 "));
        Serial.println(done->name);
        return;
    }

    servos[curAction->steps[curIdx].servo].write(curAction->steps[curIdx].angle);
    stepAt = now;
}

// ===================== 发送 =====================
static void send_frame(const char *fmt, ...) {
    char out[FRAME_MAX + 16];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(out, sizeof(out) - 2, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof(out) - 2) n = (int)sizeof(out) - 2;

    out[n]     = '\n';
    out[n + 1] = '\0';

    SerialBT.write((const uint8_t *)out, n + 1);

    Serial.print(F("[TX] "));
    Serial.print(out);                        // out 里已经带 '\n'
}

// ===================== 接收 =====================
// 逐字节喂入。v1 的单字符指令没有换行，必须当场处理；
// v2 的帧一定是大写字母开头（规范 §4.1），所以用小写字母区分不会冲突。
//
// ⚠️ 小写字母的判定【不能】加 rxLen == 0 的前提：
//    旧版 App 连接后会发一条没有换行的 "HELLO"，只认空缓冲区的话它会把
//    缓冲区永久占住，之后所有单字符指令都被当成帧的一部分吞掉 ——
//    结果就是烧了新固件后旧 App 完全失灵。
static void on_byte(uint8_t b) {
    if (b >= 'a' && b <= 'z') {                 // v1 兼容路径
        rxLen = 0;                              // 丢弃残留半帧（含旧 App 的 "HELLO"）
        mark_rx(millis());
        handle_v1((char)b);
        return;
    }

    // 帧结束符：'\r'、'\n'、"\r\n" 三种都认。
    // 不能只认 '\n' —— 很多串口助手默认行尾是 CR，只认 '\n' 会让它
    // 发出来的每条指令都变成"半帧"。
    if (b == '\r' || b == '\n') {
        if (rxLen > 0) {
            mark_rx(millis());
            rxBuf[rxLen] = '\0';
            handle_frame(rxBuf);
            rxLen = 0;
        }
        return;
    }

    if (rxLen < FRAME_MAX - 1) {
        if (rxLen == 0) rxStartMs = millis();
        rxBuf[rxLen++] = (char)b;
    } else {
        Serial.println(F("[RX] 帧超长，丢弃"));
        rxLen = 0;                              // 溢出：整帧丢弃并复位
    }
}

// 空闲判帧：结束符迟迟不来时，把缓冲区当成完整一帧处理，而不是丢掉。
// 这样串口助手不配换行也能用，同时结束符丢失也不会让缓冲区永久卡死。
static void rx_idle_tick(uint32_t now) {
    if (rxLen > 0 && (now - rxStartMs > FRAME_IDLE_GAP_MS)) {
        rxBuf[rxLen] = '\0';
        mark_rx(now);
        Serial.print(F("[RX] 空闲判帧: \""));
        Serial.print(rxBuf);
        Serial.println(F("\""));
        handle_frame(rxBuf);
        rxLen = 0;
    }
}

// 上一版固件的单字符指令，过渡期保留
static void handle_v1(char c) {
    Serial.print(F("[RX-v1] "));
    Serial.println(c);

    switch (c) {
        case 'f': {                              // 挥手
            const ActionDef *a = find_action("WAVE");
            if (a && !curAction) anim_start(a);
            break;
        }
        case 'g': {                              // 鼓掌
            const ActionDef *a = find_action("CLAP");
            if (a && !curAction) anim_start(a);
            break;
        }
        case 'a': case 'b': case 'c': case 'd': case 'e':
            Serial.println(F("[RX-v1] 本板无电机，忽略"));
            break;
        default:
            Serial.println(F("[RX-v1] 未实现"));
            break;
    }
}

static void handle_frame(const char *raw) {
    // 先去掉整帧首尾空白，再逐段去掉每段首尾空白。
    // 中文输入法输完英文常自动带一个空格（"ACT,WAVE "），不处理的话
    // strcmp 永远匹配不上，只会得到一句莫名其妙的 NAK。
    char clean[FRAME_MAX];
    strncpy(clean, raw, sizeof(clean) - 1);
    clean[sizeof(clean) - 1] = '\0';
    const char *frame = trim(clean);

    // 保留原始帧用于 ACK/NAK 回显（后面的切分会破坏缓冲区）
    char echo[FRAME_MAX];
    strncpy(echo, frame, sizeof(echo) - 1);
    echo[sizeof(echo) - 1] = '\0';

    char work[FRAME_MAX];
    strncpy(work, frame, sizeof(work) - 1);
    work[sizeof(work) - 1] = '\0';

    Serial.print(F("[RX] "));
    Serial.println(echo);

    char *argv[4];
    uint8_t argc = 0;
    char *save = NULL;
    for (char *t = strtok_r(work, ",", &save); t && argc < 4;
         t = strtok_r(NULL, ",", &save)) {
        argv[argc++] = trim(t);            // 每段也去空白："WAVE " -> "WAVE"
    }
    if (argc == 0) return;

    const char *verb = argv[0];

    // ---- 握手（握手前只接受 HELLO）----
    if (strcmp(verb, "HELLO") == 0) {
        if (argc < 2) {
            send_frame("NAK,%.90s,2", echo);
            return;
        }
        if (strcmp(argv[1], PROTO_VERSION) != 0) {
            send_frame("NAK,%.90s,5", echo);
            return;
        }
        handshaken = true;
        send_frame("READY,%s,%s", PROTO_VERSION, FW_VERSION);
        Serial.println(F("[LINK] 握手完成"));
        return;
    }

    if (!handshaken) {
        send_frame("NAK,%.90s,3", echo);
        return;
    }

    // ---- 心跳 ----
    if (strcmp(verb, "PING") == 0) {
        send_frame("PONG");
        return;
    }

    // ---- 运动 ----
    if (strcmp(verb, "MOVE") == 0) {
        if (argc < 2) {
            send_frame("NAK,%.90s,2", echo);
            return;
        }
        // STOP 必须永远有效，即使没有电机也接受
        if (strcmp(argv[1], "STOP") == 0) {
            anim_abort();
#if HAS_MOTORS
            motor_stop();
#endif
            send_frame("ACK,%.90s", echo);
            return;
        }
#if HAS_MOTORS
        if (strcmp(argv[1], "FWD") == 0)   { motor_forward();  send_frame("ACK,%.90s", echo); return; }
        if (strcmp(argv[1], "BCK") == 0)   { motor_backward(); send_frame("ACK,%.90s", echo); return; }
        if (strcmp(argv[1], "LEFT") == 0)  { turn_left();      send_frame("ACK,%.90s", echo); return; }
        if (strcmp(argv[1], "RIGHT") == 0) { turn_right();     send_frame("ACK,%.90s", echo); return; }
        send_frame("NAK,%.90s,2", echo);
#else
        // 本板没有电机驱动，如实拒绝，不要假装执行
        send_frame("NAK,%.90s,7", echo);
#endif
        return;
    }

    // ---- 动作 ----
    if (strcmp(verb, "ACT") == 0) {
        if (argc < 2) {
            send_frame("NAK,%.90s,2", echo);
            return;
        }
        if (strcmp(argv[1], "NONE") == 0) {      // 无动作
            send_frame("ACK,%.90s", echo);
            return;
        }
        if (curAction) {                          // 上一个动作还没做完
            send_frame("NAK,%.90s,4", echo);
            return;
        }
        const ActionDef *a = find_action(argv[1]);
        if (!a) {
            // THINK / WELCOME / POINT_LEFT / POINT_RIGHT 本固件尚未实现
            send_frame("NAK,%.90s,7", echo);
            return;
        }
        anim_start(a);
        send_frame("ACK,%.90s", echo);
        return;
    }

    // ---- 车道距离遥测（手机算好发下来的，10 Hz，不回 ACK 避免塞爆链路）----
    if (strcmp(verb, "DIST") == 0) {
        if (argc >= 2) {
            Serial.print(F("[DIST] "));
            Serial.println(argv[1]);
        }
        return;
    }

    // ---- 紧急复位 ----
    if (strcmp(verb, "RESET") == 0) {
        anim_abort();
#if HAS_MOTORS
        motor_stop();
#endif
        home();
        send_frame("ACK,%.90s", echo);
        return;
    }

    send_frame("NAK,%.90s,1", echo);              // 未知命令
}

// ===================== 安全保护 =====================
// 超过 5 秒收不到任何帧 -> 立刻停车、舵机回中。
// 这是 v1 完全没有的东西：车还在动、手机已经断开，是最危险的场景。
//
// ⚠️ 这里【绝对不能】清 handshaken。
//    心跳超时只说明"暂时没人管我"，不代表链路断了。清掉握手的话，
//    客户端恢复发送后每条指令都会收到 NAK,3，而客户端不会因此重新握手 ——
//    链路就永久死了，只能人工重发 HELLO 才能救回来。
//    真正断链由 link_tick() 通过 hasClient() 的下降沿来判断。
static void safety_tick(uint32_t now) {
    if (handshaken && !safetyLatched && (now - lastRxMs > LINK_TIMEOUT_MS)) {
        safetyLatched = true;
        Serial.println(F("[SAFE] 链路超时：停车（握手保留）"));

        // 电机必须立刻停 —— 它是无界的，不停就会一直开下去
#if HAS_MOTORS
        motor_stop();
#endif

        if (curAction) {
            // 动作是有界的（最长 6 秒），而且每条动作的最后一帧本来就停在
            // 中位附近。让它自然做完，比中途急停再瞬间回中更不容易打坏舵机。
            Serial.println(F("[SAFE] 动作进行中，允许做完"));
        } else {
            home();
        }
    }
}

// 真正的断链检测：SPP 客户端消失。
// 只有这种情况才把会话打回未握手状态。
static void link_tick() {
    const bool connected = SerialBT.hasClient();

    if (btWasConnected && !connected) {
        Serial.println(F("[LINK] 蓝牙已断开，握手状态清零"));
        handshaken    = false;
        safetyLatched = false;
        anim_abort();
        home();
    }

    btWasConnected = connected;
}

// ===================== 生命周期 =====================
void setup() {
    // 必须先开 Serial，否则下面的打印会丢（上一版固件就是反的）
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println(F("=== 小智机器人 ESP32  XZ-Link/1 ==="));
    Serial.print(F("固件版本: "));
    Serial.println(F(FW_VERSION));

    for (uint8_t i = 0; i < SERVO_COUNT; i++) {
        servos[i].attach(SERVO_PIN[i], 500, 2500);
    }
    home();

    SerialBT.begin("ESP32-ROBOT");
    Serial.println(F("蓝牙已启动，设备名 ESP32-ROBOT，等待配对/连接"));

    lastRxMs = millis();
}

void loop() {
    const uint32_t now = millis();

    // 蓝牙：来多少读多少，绝不阻塞
    while (SerialBT.available() > 0) {
        on_byte((uint8_t)SerialBT.read());
    }

    anim_tick(now);      // 推进动画
    rx_idle_tick(now);   // 没等到结束符时，按空闲间隔判帧
    safety_tick(now);    // 心跳超时 -> 停车回中（保留握手）
    link_tick();         // 客户端断开 -> 握手状态清零
}
