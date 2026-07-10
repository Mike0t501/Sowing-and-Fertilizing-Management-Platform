# 播种深度控制功能 - Claude Code 实施指南

## 一、背景与目标

在现有变量施肥控制系统基础上，新增播种深度控制功能。使用 YZ-AIM 系列 CANopen 伺服电机 + 丝杆升降机，将电机旋转运动转换为升降机直线运动，控制播种深度。支持最多 8 个独立的播种深度电机。

**关键差异：现有施肥电机使用自定义字节CAN协议（简单RPM指令），新伺服电机使用 CANopen 协议（CiA301 + DS402），通信复杂度完全不同，需新建 CANopen 通信层。**

---

## 一½. 当前实现状态（2026-05-08）

> 本节供新 Claude Code session 快速定位现状，避免重复分析已完成的工作。

### 已完成（均在 `feature/depthcontrol` 分支）

| 文件 | 状态 | 关键说明 |
|------|------|---------|
| `funClass/CanOpenFun.kt` | ✅ 完成 | CSM100T 帧格式封装；SDO/NMT/TPDO；`buildMotorInitSequence`；`buildAbsoluteMoveFrames`（含 Bit4 0→1 强制切换）；两阶段 jog 停止帧 |
| `data/SowingDepthData.kt` | ✅ 完成 | `ServoCalibration`（含 `lastHeardMs`）+ `SowingDepthState`（含 `masterEnabled`） |
| `coroutine/SowingDepthCoroutine.kt` | ✅ 完成 | 500ms 主循环，Phase 1–5，时间戳离线检测，`motorInitCooldown` 重试机制 |
| `ui/SowingDepthScreen.kt` | ✅ 完成 | 8路状态卡片、总开关（红/绿双色 Card + Switch）、全局目标、单独设置弹窗 |
| `ui/DepthCalibrationScreen.kt` | ✅ 完成 | 步骤1限位设置 + 步骤2五点直接标定；jog 两阶段减速停止 |
| `coroutine/CanReceiveCoroutine.kt` | ✅ 完成 | TPDO → `isOnline=true` + `currentPosition` + `lastHeardMs`；心跳 → `isOnline=true` + `lastHeardMs` |
| `ViewModelAndPublic.kt` | ✅ 完成 | `sowingDepthState` LiveData、`updateMasterEnabled`、`updateServoCalibration` |
| `MySharedPreFun.kt` | ✅ 完成 | 限位、拟合系数、Node-ID 的持久化读写 |

### 关键实现决策（新 session 须知，勿重复踩坑）

1. **离线检测（已重构）**：`CanReceiveCoroutine` 每次收到 TPDO/心跳时更新 `cal.lastHeardMs = System.currentTimeMillis()`。`SowingDepthCoroutine` Phase 3 检查 `nowMs - lastHeardMs > 5000ms` → 标离线。原来的"位置变化"检测已**完全删除**（静止电机会被误判）。

2. **初始化后首次位置命令不响应（已修复）**：Phase 2 init 后设 `motorInitCooldown[i]=6`，Phase 4 在冷却期内每次清空 `lastSentTargetDepth[i]=Float.NaN`，强制重发 3s（6×500ms），解决 DS402 状态机 Enable Operation 过渡期内首条命令被忽略的问题。

3. **Bit4 切换（DS402 setpoint 触发）**：`buildAbsoluteMoveFrames` 先发 `0x6040=0x000F`（清 Bit4），再发 `0x6040=0x002F`（置 Bit4），确保 0→1 跳变。连续多次设定同一目标时必须有此切换，否则驱动器拒绝接受。

4. **Jog 启停（2026-07 硬件调试后重构，勿回退）**：
   - **启动序列 5 帧**：`0x6040=0x0006 → 0x0007 → 0x6060=3 → 0x60FF=±v → 0x6040=0x000F`。前置 `0x0006`（Shutdown）是关键——冷上电处于 Switch On Disabled 的驱动器按 DS402 状态机直接忽略 `0x0007`，缺此帧则点动"按了没反应"，且点动被迫依赖总开关先跑 Phase 2 初始化。使能帧 `0x000F` 必须放最后（前缀安全：序列被取消只发出前缀时电机不可能启动）。
   - **停止（两阶段 + 校验）**：① `0x60FF=0` **连发 3 次**（间隔 50ms，幂等冗余抗单帧丢失——现场曾因单发 v=0 被总线并发挤掉导致电机失控转到机械限位）→ ② 等 `jogSpeed×1000/accel + 200ms`（上限 4s，期间可被新命令抢占）→ ③ 停车校验（位置仍在变则补发 v=0，最多 2 轮）→ ④ 发 `0x6040=0x0007 → 0x6060=1`（Disable Operation + 预切位置模式，**故意不带 0x000F**：退出 Operation Enabled 后即使 v=0 全部丢失电机也物理上转不了）。后续位置控制经 `buildAbsoluteMoveFrames` 的 `0x000F → 0x002F` 从 Switched On 重新使能。
   - **UI 层单消费者命令队列**：按下/松开只向 `Channel<JogCommand>` 投递事件，唯一消费协程串行执行启停序列（旧 JobHolder 手工接力在快速连点时会让停止的 v=0 落在新启动序列之后）。Step 2 到限监控也改投 `Stop` 命令，与手动松开同路幂等。
   - **点动会话 `JogSession`**（`CanOpenFun.kt` 顶层 object，AtomicInteger CAS）：点动期间 `SowingDepthCoroutine` 对该节点跳过 Phase 2 初始化与 Phase 4 位置下发（位置模式帧会掐掉速度模式点动）；Phase 1 位置轮询与 Phase 5 限位急停不跳过（标定页依赖位置刷新；限位报警是安全项）。
   - **全局 SDO 串行化**：所有 CANopen 发送方统一走 `CanOpenFun.sendFrameSequenced / sendSequence`（Mutex + 全局 ≥20ms 帧间步调，多帧序列持锁原子发送）。跨协程 SDO 背靠背落到同一节点被驱动器单 SDO 服务端丢帧，是点动时灵时不灵的另一主因。施肥帧不走此步调，仅共享字节级 `MySerialPortFun.CAN_TX_LOCK`。

5. **总开关 `masterEnabled`**：不持久化，启动默认 `false`。ON→OFF 跳变：对所有已初始化电机发 `0x6040=0x0006`（Shutdown），清零 `motorInitialized[i]`、`motorInitCooldown[i]`、`lastSentTargetDepth[i]`。

6. **`SowingDepthScreen` 与 `DepthCalibrationScreen` 均各自启动 `CanReceiveCoroutine` + `SowingDepthCoroutine`**（各自的 `DisposableEffect`），离开界面时 `onDispose` 停止。两界面之间切换会重新创建协程实例，已通过时间戳离线检测的 5s 窗口避免冷启动误判。

7. **YZ EDS 审查（2026-07-10）**：`YZ_MOTOR_SN2.eds` 共 104 个可 expedited SDO 访问的数字 VAR/子对象，已逐项录入 `ServoCanOpenFun.EDS_OBJECTS`。TPDO1 的 6 字节 `[6064h实际位置 I32][6041h状态字 U16]` 解析正确。已修正四类不一致：① 已配置伺服的 SDO 回复同时投递 `SdoReplyWaiters`，调试请求不再超时；② `6041h` Bit14/15 仅作厂商位，报警改用 Bit3 Fault / Bit11 Internal limit；③ `6040h=010Fh` 明确为 Halt，真正 Quick Stop 使用 `000Bh`；④ 删除 EDS 不存在的 `261Fh/2620h` 写入，只保留 App 软件限位。`6084h` 也未列入 EDS，因既有现场减速行为暂保留兼容写入，并在伺服调试工具提供只读探测。EDS 身份默认值为 Vendor=817、Product=1、Revision=256，电子齿轮默认分子/分母为 32768/8192。

### 当前已知待开发项

- `DepthCalibrationScreen` 步骤2 当前只有**直接测量模式**（5点等分，人工测量深度值）。下一步新增**间接测量模式**（挡块法），见第七节详细规格。

---

## 二、CANopen 伺服电机通信协议摘要

### 2.1 CAN帧基础
- 标准帧格式，11位标识符
- 设备地址范围：1~127（Node-ID）

### 2.2 SDO（服务数据对象）- 用于配置参数
```
发送 CAN-ID: 0x600 + Node-ID
接收 CAN-ID: 0x580 + Node-ID
```

**SDO 写命令：**
| 数据长度 | CS命令符 | 说明 |
|---------|---------|------|
| 1字节   | 0x2F    | 写1字节 |
| 2字节   | 0x2B    | 写2字节 |
| 4字节   | 0x23    | 写4字节 |
| 回复成功 | 0x60    | 写成功应答 |

**SDO 读命令：**
| 操作    | CS命令符 | 说明 |
|---------|---------|------|
| 读取    | 0x40    | 发起读取 |
| 回复1字节 | 0x4F  | 读回复 |
| 回复2字节 | 0x4B  | 读回复 |
| 回复4字节 | 0x43  | 读回复 |

**SDO 帧格式（8字节）：**
```
字节: [CS命令符] [索引低字节] [索引高字节] [子索引] [数据0] [数据1] [数据2] [数据3]
```
数据为小端序（Little-Endian）。

### 2.3 关键对象字典地址

| 名称 | CANopen地址 | 数据长度 | 读写 | 说明 |
|------|-----------|---------|------|------|
| 控制字 | 0x6040-00 | 2字节 | RWM | 状态机控制 |
| 状态字 | 0x6041-00 | 2字节 | RM | 状态反馈 |
| 工作模式 | 0x6060-00 | 1字节 | RWM | 1=位置, 3=速度, 6=找原点, 7=插补 |
| 实际位置 | 0x6064-00 | 4字节(有符号) | RM | 编码器计数值 |
| 实际速度 | 0x606C-00 | 4字节(有符号) | RM | TPDO3 默认映射对象之一 |
| 实际电流 | 0x6078-00 | 2字节(有符号) | RM | 电流反馈 |
| 目标位置缓存 | 0x607A-00 | 4字节(有符号) | RW | 目标位置 |
| 梯形速度 | 0x6081-00 | 4字节 | RW | 位置模式最大速度，单位RPM，范围0~3000 |
| 电机加速度 | 0x6083-00 | 4字节 | RW | 单位(RPM/s)，<60000用内部曲线 |
| 速度模式速度 | 0x60FF-00 | 4字节(有符号) | RWM | 速度模式目标转速，范围-3000~3000 |
| Modbus使能 | 0x2600-00 | 2字节 | RW | 0=禁止, 1=使能 |
| 电子齿轮分子 | 0x260A-00 | 2字节 | RW | 默认32768 |
| 电子齿轮分母 | 0x260B-00 | 2字节 | RW | EDS 默认8192 |
| 厂商错误码 | 0x260E-00 | 2字节 | RO | EDS 声明的错误码对象 |
| 参数保存标志 | 0x2614-00 | 2字节 | RW | 写1=保存中, 读到2=保存完毕 |
| 特殊功能 | 0x2619-00 | 2字节 | RW | 0=脉冲+方向 |
| 心跳产生间隔 | 0x1017-00 | 2字节 | RWM | 单位ms, 0=不产生 |

### 2.4 控制字 (0x6040) 位定义
```
Bit0: 启动（置1后外部脉冲控制无效）
Bit1: Enable voltage
Bit2: Quick stop（清零触发 DS402 Quick Stop）
Bit3: 允许操作
Bit4: 执行新设置点（写1后运行到新位置，自动清零）
Bit5: 位置立即生效
Bit6: 0=绝对位置, 1=相对位置
Bit7: 故障复位
Bit8: Halt（位置/速度模式受控暂停，不等同于 Quick Stop）
```

常用控制字值：
- `0x000F`: 启动+电压输出+允许急停+允许操作
- `0x002F`: 绝对位置 + 新位置立即执行
- `0x004F`: 相对位置控制模式
- `0x005F`: 相对位置 + 执行新位置点
- `0x000B`: DS402 Quick Stop（从 0x000F 清 Bit2）
- `0x010F`: Halt（Bit8=1）

### 2.5 状态字 (0x6041) 位定义
```
Bit0: Ready to switch on
Bit1: Switched on
Bit2: Operation enabled
Bit3: Fault
Bit5: Quick stop（1=未激活）
Bit6: Switch on disabled
Bit7: Warning
Bit10: 目标达到（位置模式=到达目标位置，速度模式=到达给定速度）
Bit11: Internal limit active
Bit12~13: 工作模式相关（位置模式 Bit12=Set-point acknowledge）
Bit14~15: 厂商自定义；YZ EDS 未给出正/负限位定义，禁止据此判断限位方向
```

### 2.6 编码器参数
- 15位绝对编码器，一圈 = 32768 脉冲
- EDS 电子齿轮默认：分子32768, 分母8192

### 2.7 限位策略

早期草案曾把 `261Fh/2620h` 当作本机硬件限位寄存器，但它们不在
`YZ_MOTOR_SN2.eds` 中，也没有经 SDO ACK 验证，现已撤销该流程。当前只记录 App
软件限位，位置命令下发前使用 `coerceIn(minOf(limitMin, limitMax), maxOf(...))` 限幅。

### 2.8 SDO 绝对位置控制流程
```kotlin
// 1. 使能驱动器
SDO_Write(0x6040, 0x00, 2, 0x000F)  // Enable Operation

// 2. 设置位置模式
SDO_Write(0x6060, 0x00, 1, 0x01)    // 工作模式=位置模式

// 3. 读取当前位置（重要！上电后需读取）
position = SDO_Read(0x6064, 0x00)    // 实际位置

// 4. 设置速度和加速度（可选，使用默认值可跳过）
SDO_Write(0x6081, 0x00, 4, 1000)    // 梯形速度=1000RPM
SDO_Write(0x6083, 0x00, 4, 20000)   // 加速度=20000RPM/s

// 5. 写入目标位置
SDO_Write(0x607A, 0x00, 4, target)  // 目标位置

// 6. Bit4 必须 0→1 跳变：先清 new set-point，再置位并立即执行
SDO_Write(0x6040, 0x00, 2, 0x000F)
SDO_Write(0x6040, 0x00, 2, 0x002F)

// 7. 读状态字判断是否到达
status = SDO_Read(0x6041, 0x00)
// 检查 Bit10 是否为1 → 到达目标
```

### 2.9 SDO 速度模式控制流程（用于点动）
```kotlin
// 1. 设置速度模式
SDO_Write(0x6060, 0x00, 1, 0x03)    // 工作模式=速度模式

// 2. 设置目标速度（有符号，正=正转，负=反转）
SDO_Write(0x60FF, 0x00, 4, speed)   // 例如 500 或 -500

// 3. 启动
SDO_Write(0x6040, 0x00, 2, 0x000F)

// 4. 正常松开：先将速度归零；当前实现重复发送并在减速后 Disable Operation
SDO_Write(0x60FF, 0x00, 4, 0)

// 安全 Quick Stop：从 000Fh 清 Bit2
SDO_Write(0x6040, 0x00, 2, 0x000B)
```

### 2.10 PDO（过程数据对象）- 用于实时控制
```
RPDO1 (0x200+ID): [控制字2B] [工作模式1B] [目标位置4B] = 7字节
RPDO2 (0x300+ID): [目标位置4B] [梯形速度4B] = 8字节
RPDO3 (0x400+ID): [控制字2B] [工作模式1B] [目标速度4B] = 7字节
TPDO1 (0x180+ID): [实际位置4B] [状态字2B] = 6字节 (电机主动上报，默认100ms)
TPDO2 (0x280+ID): [实际位置4B] [状态字2B] = 6字节
```

**注意：初期开发建议先用 SDO 模式，调试稳定后再切换到 PDO 以提高实时性。**

### 2.11 心跳设置
```
心跳产生间隔(0x1017) = 1000  // 默认每秒产生一次心跳
心跳消费(0x1016-01): 默认0x7F07D0 = 2秒内必须收到CAN指令否则停机
```
**重要：必须定期发送心跳或CAN指令，否则电机2秒后报警停机（报警代码0x20）。**

---

## 三、CAN总线硬件注意事项

1. **同一CAN总线**：伺服电机与施肥电机共用同一个 CAN 串口，但协议不同。需要在接收端根据 CAN-ID 区分消息来源。
2. **120Ω终端电阻**：电机内部没有120Ω电阻，需在CAN总线最远端并联一个120Ω电阻。
3. **设备地址**：每个伺服电机需设置不同的 Node-ID（1~127），避免与施肥电机地址冲突。建议施肥电机用地址 1~8，伺服电机用地址 11~18。
4. **CANL/CANH 内置隔离电源**。

---

## 四、功能实现规划

### 4.1 新增文件清单

```
funClass/
  CanOpenFun.kt          — CANopen协议底层：SDO读写、PDO收发、帧组装/解析
  SowingDepthFun.kt      — 播种深度业务逻辑：限位管理、拟合曲线、深度↔位置转换

coroutine/
  SowingDepthCoroutine.kt — 播种深度控制协程：实时位置控制循环、心跳维持

ui/
  SowingDepthScreen.kt   — 播种深度控制主界面
  DepthCalibrationScreen.kt — 限位标定 + 5挡位拟合校准界面

data/（新建或在SPParamData.kt中扩展）
  SowingDepthData.kt     — 数据类：限位值、校准点、拟合系数、8个电机的独立状态
```

### 4.2 需修改的现有文件

```
ViewModelAndPublic.kt    — 新增播种深度相关的 ViewModel 状态
CanReceiveCoroutine.kt   — 在CAN接收中增加CANopen帧的识别和分发（根据CAN-ID范围区分）
MySerialPortFun.kt       — 无需修改（共用CAN串口）
MySharedPreFun.kt        — 新增播种深度参数的持久化方法
MainActivity.kt          — 新增导航路由
SettingsScreen.kt        — 新增伺服电机地址配置入口
```

### 4.3 CanOpenFun.kt 设计

```kotlin
/**
 * CANopen 协议底层通信类
 * 
 * 注意：本应用通过 android-serialport 库访问CAN串口，
 * 串口接收的是完整CAN帧的原始字节。
 * 需要根据现有 CanReceiveCoroutine.kt 中的帧格式来适配。
 * 
 * 发送帧格式需要与现有施肥电机的帧格式一致（看现有代码中CAN发送的字节结构）
 */
object CanOpenFun {

    // ============ SDO 写入 ============
    
    /**
     * 构建SDO写入帧
     * @param nodeId   设备地址 1~127
     * @param index    对象索引 如 0x6040
     * @param subIndex 子索引 如 0x00
     * @param dataLen  数据长度 1/2/4
     * @param value    写入的值（有符号也用Int/Long传入，内部处理）
     * @return 完整的CAN帧字节数组（需要适配你现有的CAN串口帧格式）
     */
    fun buildSdoWriteFrame(nodeId: Int, index: Int, subIndex: Int, dataLen: Int, value: Long): ByteArray {
        val canId = 0x600 + nodeId
        val cs = when (dataLen) {
            1 -> 0x2F
            2 -> 0x2B
            4 -> 0x23
            else -> throw IllegalArgumentException("dataLen must be 1, 2, or 4")
        }
        // 8字节数据段
        val data = ByteArray(8)
        data[0] = cs.toByte()
        data[1] = (index and 0xFF).toByte()        // 索引低字节
        data[2] = ((index shr 8) and 0xFF).toByte() // 索引高字节
        data[3] = (subIndex and 0xFF).toByte()
        // 小端序写入数据
        for (i in 0 until dataLen) {
            data[4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        // TODO: 包装成你的CAN串口帧格式（参考现有代码中的CAN发送实现）
        return wrapCanFrame(canId, data)
    }

    // ============ SDO 读取 ============
    
    fun buildSdoReadFrame(nodeId: Int, index: Int, subIndex: Int): ByteArray {
        val canId = 0x600 + nodeId
        val data = ByteArray(8)
        data[0] = 0x40.toByte()
        data[1] = (index and 0xFF).toByte()
        data[2] = ((index shr 8) and 0xFF).toByte()
        data[3] = (subIndex and 0xFF).toByte()
        return wrapCanFrame(canId, data)
    }

    /**
     * 解析SDO读取回复
     * 回复帧 CAN-ID = 0x580 + nodeId
     * @return 解析后的数值，null表示解析失败
     */
    fun parseSdoReadResponse(frameData: ByteArray): Long? {
        if (frameData.size < 8) return null
        val cs = frameData[0].toInt() and 0xFF
        return when (cs) {
            0x4F -> frameData[4].toLong() and 0xFF  // 1字节
            0x4B -> (frameData[4].toLong() and 0xFF) or
                    ((frameData[5].toLong() and 0xFF) shl 8)  // 2字节
            0x43 -> (frameData[4].toLong() and 0xFF) or
                    ((frameData[5].toLong() and 0xFF) shl 8) or
                    ((frameData[6].toLong() and 0xFF) shl 16) or
                    ((frameData[7].toLong() and 0xFF) shl 24)  // 4字节
            0x80 -> null  // 错误
            else -> null
        }
    }

    // ============ 便捷高层方法 ============
    
    /** 使能驱动器 */
    fun buildEnableFrame(nodeId: Int) = 
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000F)
    
    /** 设置位置模式 */
    fun buildSetPositionMode(nodeId: Int) = 
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x01)
    
    /** 设置速度模式 */
    fun buildSetVelocityMode(nodeId: Int) = 
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x03)
    
    /** 绝对位置运动（立即执行） */
    fun buildMoveAbsolute(nodeId: Int, position: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x002F),  // 绝对+立即执行
        buildSdoWriteFrame(nodeId, 0x607A, 0x00, 4, position.toLong())  // 目标位置
    )
    
    /** 速度模式运动（点动用） */
    fun buildMoveVelocity(nodeId: Int, rpm: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x03),  // 速度模式
        buildSdoWriteFrame(nodeId, 0x60FF, 0x00, 4, rpm.toLong()),  // 目标速度
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000F)  // 启动
    )
    
    /** DS402 Quick Stop */
    fun buildEmergencyStop(nodeId: Int) = 
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000B)
    
    /** 读取当前位置 */
    fun buildReadPosition(nodeId: Int) = 
        buildSdoReadFrame(nodeId, 0x6064, 0x00)
    
    /** 读取状态字 */
    fun buildReadStatus(nodeId: Int) = 
        buildSdoReadFrame(nodeId, 0x6041, 0x00)
    
    // 限位只保存在 App 状态；YZ EDS 未声明 261Fh/2620h，不构造未知对象写入帧。

    // ============ 心跳 ============
    
    /** NMT启动命令（让节点进入操作状态）*/
    fun buildNmtStart(nodeId: Int): ByteArray {
        val data = byteArrayOf(0x01, nodeId.toByte())
        return wrapCanFrame(0x000, data)
    }

    // ============ 帧封装 ============
    
    /**
     * TODO: 此方法需要适配你现有CAN串口的帧格式
     * 参考 DB9reCANseCoroutine.kt 或 CanReceiveCoroutine.kt 中的发送实现
     */
    private fun wrapCanFrame(canId: Int, data: ByteArray): ByteArray {
        // 你需要根据实际串口CAN适配器的协议来封装
        // 常见格式: [帧头] [CAN-ID高] [CAN-ID低] [DLC] [data0~data7] [校验/帧尾]
        TODO("根据现有CAN串口帧格式实现")
    }
}
```

### 4.4 SowingDepthData.kt 设计

```kotlin
/**
 * 单个伺服电机的播种深度校准数据
 */
data class ServoCalibration(
    val motorIndex: Int,          // 电机编号 0~7
    val nodeId: Int = 11 + motorIndex,  // CAN Node-ID，建议11~18
    
    // 限位（编码器脉冲数，有符号32位整数）
    var limitMin: Int = 0,        // 最浅位置对应的编码器值
    var limitMax: Int = 0,        // 最深位置对应的编码器值
    var limitsSet: Boolean = false,  // 限位是否已设置
    
    // 5个校准点 [编码器位置, 实际深度mm]
    var calibrationPoints: MutableList<Pair<Int, Float>> = mutableListOf(),
    
    // 线性拟合系数 depth_mm = a * encoderPosition + b
    var fitA: Float = 0f,
    var fitB: Float = 0f,
    var fitValid: Boolean = false,
    
    // 运行时状态
    var currentPosition: Int = 0,   // 当前编码器位置
    var targetDepth: Float = 0f,    // 目标深度(mm)
    var currentDepth: Float = 0f,   // 当前深度(mm)
    var isEnabled: Boolean = false,
    var isOnline: Boolean = false,
    var alarmCode: Int = 0
)

/**
 * 全局播种深度状态
 */
data class SowingDepthState(
    val motors: List<ServoCalibration> = List(8) { ServoCalibration(it) },
    val globalTargetDepth: Float = 50f,  // 全局目标深度(mm)
    val jogSpeed: Int = 200,             // 点动速度(RPM)
    val positionSpeed: Int = 500,        // 位置运动速度(RPM)
    val acceleration: Int = 10000        // 加速度(RPM/s)
)
```

### 4.5 SowingDepthFun.kt 设计

```kotlin
object SowingDepthFun {
    
    /**
     * 根据限位范围计算5个等分标定位置（编码器值）
     */
    fun calculateCalibrationPositions(limitMin: Int, limitMax: Int): List<Int> {
        val range = limitMax - limitMin
        return (0..4).map { i ->
            limitMin + (range * i) / 4
        }
        // 产生5个位置: min, min+25%, min+50%, min+75%, max
    }
    
    /**
     * 最小二乘法线性拟合 depth = a * position + b
     * @param points 校准点列表 [(encoderPos, depthMm), ...]
     * @return Pair(a, b)
     */
    fun linearFit(points: List<Pair<Int, Float>>): Pair<Float, Float> {
        require(points.size >= 2) { "至少需要2个校准点" }
        val n = points.size.toFloat()
        val sumX = points.sumOf { it.first.toDouble() }.toFloat()
        val sumY = points.sumOf { it.second.toDouble() }.toFloat()
        val sumXY = points.sumOf { it.first.toDouble() * it.second.toDouble() }.toFloat()
        val sumX2 = points.sumOf { it.first.toDouble() * it.first.toDouble() }.toFloat()
        
        val a = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val b = (sumY - a * sumX) / n
        return Pair(a, b)
    }
    
    /**
     * 深度(mm) → 编码器目标位置
     */
    fun depthToPosition(depthMm: Float, fitA: Float, fitB: Float): Int {
        // depth = a * position + b  =>  position = (depth - b) / a
        if (fitA == 0f) return 0
        return ((depthMm - fitB) / fitA).toInt()
    }
    
    /**
     * 编码器位置 → 深度(mm)
     */
    fun positionToDepth(position: Int, fitA: Float, fitB: Float): Float {
        return fitA * position + fitB
    }
    
    /**
     * 安全检查：目标位置是否在限位范围内
     */
    fun isPositionSafe(position: Int, limitMin: Int, limitMax: Int): Boolean {
        return position in limitMin..limitMax
    }
}
```

### 4.6 SowingDepthCoroutine.kt 设计

```kotlin
/**
 * 播种深度控制协程
 * 
 * 主要职责：
 * 1. 定期发送心跳/读取状态（防止2秒掉线报警）
 * 2. 当目标深度变化时，计算目标编码器位置并发送位置指令
 * 3. 监测电机状态、报警处理
 * 
 * 参考现有 DB9reCANseCoroutine.kt 的协程写法和CAN发送方式
 */
class SowingDepthCoroutine {
    
    // 主循环：约每 500ms 执行一次
    // 1. 遍历已使能的8个伺服电机
    // 2. 对每个电机读取实际位置（SDO Read 0x6064）
    // 3. 对比目标位置，如果差距超过阈值则发送位置指令
    // 4. 读取状态字检查报警
    // 5. 更新 ViewModel 中的状态
    
    // 心跳维持：每800ms对所有在线电机发一次读取指令
    // （读取指令本身就算CAN通信，可以防止2秒心跳超时）
}
```

### 4.7 UI界面设计

#### SowingDepthScreen.kt - 主控制界面
```
┌──────────────────────────────────────────────┐
│  播种深度控制                                  │
├──────────────────────────────────────────────┤
│  目标深度: [  50  ] mm    [全部应用]           │
├──────────────────────────────────────────────┤
│  #1  ●在线  深度:48mm  目标:50mm  [单独设置]   │
│  #2  ●在线  深度:51mm  目标:50mm  [单独设置]   │
│  #3  ○离线  ---                   [单独设置]   │
│  #4  ●在线  深度:49mm  目标:50mm  [单独设置]   │
│  #5  ●报警  代码:0x12             [复位]       │
│  #6  ○离线  ---                   [单独设置]   │
│  #7  ○离线  ---                   [单独设置]   │
│  #8  ○离线  ---                   [单独设置]   │
├──────────────────────────────────────────────┤
│  [标定设置]        [全部停止]                   │
└──────────────────────────────────────────────┘
```

#### DepthCalibrationScreen.kt - 标定校准界面
```
┌──────────────────────────────────────────────┐
│  电机 #1 标定                                  │
├──────────────────────────────────────────────┤
│  步骤1: 限位设置                               │
│  当前位置: 12345                               │
│                                               │
│  [▲ Deep]   [▼ Shallow]   (点动控制)          │
│  点动速度: [  200  ] RPM                       │
│                                               │
│  [设为最深限位]  最深: 未设置                    │
│  [设为最浅限位]  最浅: 未设置                    │
│                                               │
│  ✅ 限位已设置 → [下一步: 深度校准]              │
├──────────────────────────────────────────────┤
│  步骤2: 深度校准 (限位设置后才可见)              │
│                                               │
│  挡位1 (最浅): 位置 0     实际深度: [  20  ] mm │
│  挡位2:       位置 8192  实际深度: [  35  ] mm │
│  挡位3 (中间): 位置 16384 实际深度: [  50  ] mm │
│  挡位4:       位置 24576 实际深度: [  65  ] mm │
│  挡位5 (最深): 位置 32768 实际深度: [  80  ] mm │
│                                               │
│  [移动到挡位1] [移动到挡位2] ... [移动到挡位5]   │
│                                               │
│  [计算拟合]                                    │
│  拟合结果: depth = 0.00183 * pos + 20.0       │
│  R² = 0.998                                   │
│                                               │
│  [保存校准]                                    │
└──────────────────────────────────────────────┘
```

---

## 五、分阶段开发指令（给 Claude Code 的 Prompt）

此节不再需要，省去。

## 六、关键实现注意事项

### 6.1 CAN发送时序
SDO 是请求-回复模式。发送一条SDO写入后，需要等待回复（0x580+ID）确认成功再发下一条。建议每条SDO命令之间至少间隔 20ms。不要在同一时刻对同一个电机发多条SDO。

### 6.2 软件限位 vs 硬件限位
- **软件限位**：在发送位置指令前，先调用 `isPositionSafe()` 检查
- **EDS 结论**：`YZ_MOTOR_SN2.eds` 没有 `0x261F/0x2620`，不得把它们当成本机已确认的硬件限位寄存器
- 当前实现只保存 App 软件限位，并在目标位置下发前执行限幅；若要启用驱动器硬限位，必须先取得本机厂商对象定义并验证 SDO ACK

### 6.3 点动控制实现
点动使用速度模式。按住按钮时发送速度命令，松开按钮时发送速度=0或急停命令。
Compose 中用 `pointerInput` 的 `detectTapGestures` 或使用 `Modifier.pointerInput` 的 press/release 事件实现。

### 6.4 编码器值与圈数关系
- 1圈 = 32768 编码器脉冲（15位绝对编码器）
- 如果丝杆螺距为 P mm，则每圈升降 P mm
- 例如螺距5mm时：32768个脉冲 = 5mm行程

### 6.5 8个电机独立控制
每个电机有独立的 Node-ID、独立的限位、独立的校准曲线、独立的目标深度。
ViewModel 中用 `List<ServoCalibration>(8)` 管理。
控制循环中顺序轮询每个电机。

### 6.6 与施肥功能的隔离
初期播种深度功能完全独立于施肥控制逻辑。
它们共用同一个CAN串口，但通过不同的 CAN-ID 区分。
施肥电机用现有的自定义协议，伺服电机用 CANopen 协议。

---

## 七、下一阶段开发：间接测量标定模式

### 7.1 背景与动机

现有"步骤2：深度校准"要求用户驱动电机到5个等分位置，然后用卷尺手动测量每个位置的实际播种深度。**痛点**：限深轮在地面以下作业，卷尺测量困难，需要反复弯腰操作。

**间接测量模式**的思路：用户预备一组标准挡块（2/4/6/8/10 cm），将挡块放在限深轮下方，用点动控制缓慢下压限深轮至刚好接触挡块顶面，软件记录此时的编码器位置。编码器位置对应已知的播种深度（挡块高度），无需人工测量。

现有"直接测量模式"（5点等分法）**保留不变**，两种模式可切换。

### 7.2 交互流程

步骤2区域顶部增加模式切换控件：

```
步骤2：深度校准
┌────────────────────────────────────────────┐
│  [直接测量模式]  [间接测量模式（挡块法）]       │  ← SegmentedButton 或两个 FilterChip
└────────────────────────────────────────────┘
```

**间接测量模式界面：**

```
间接测量模式（挡块法）
┌──────────────────────────────────────────────────────────┐
│  说明：将标准挡块放于限深轮正下方，点动下压至刚好接触挡块，   │
│  点击对应行的 [记录位置]。至少记录 2 个挡块后可计算拟合。    │
├──────────────────────────────────────────────────────────┤
│  当前位置：12345    [▲ 上升]  [▼ 下降]  （复用步骤1点动）  │
├──────────────────────────────────────────────────────────┤
│  挡块  深度   编码器位置        操作                        │
│  1号   2 cm  ——               [记录当前位置]               │
│  2号   4 cm  ——               [记录当前位置]               │
│  3号   6 cm  8765  ✅          [清除]                      │
│  4号   8 cm  ——               [记录当前位置]               │
│  5号  10 cm  ——               [记录当前位置]               │
├──────────────────────────────────────────────────────────┤
│  已记录 1/5 个挡块（至少需要 2 个）                         │
│  [计算拟合曲线]（需 ≥2 个挡块）                             │
│  拟合结果：depth = 0.00152 × pos + 1.83   R² = 0.999     │
│  [保存校准]                                                │
└──────────────────────────────────────────────────────────┘
```

**操作步骤引导：**
1. 取 2cm 挡块，放于限深轮正下方。
2. 用点动（`▼ 下降`）缓慢下压，至限深轮刚好接触挡块（不要压弯挡块）。
3. 点击第 1 行 **[记录当前位置]** → 行变绿，显示编码器值。
4. 点动抬起限深轮，取出挡块，换下一块，重复步骤 2–3。
5. 记录至少 2 个（建议全部 5 个）→ **[计算拟合曲线]** → 确认 R² ≥ 0.99 → **[保存校准]**。

### 7.3 需新增的数据字段

在 `data/SowingDepthData.kt` 的 `ServoCalibration` 中新增：

```kotlin
// 标定模式：DIRECT = 直接测量，INDIRECT = 间接测量（挡块法）
val calibrationMode: CalibrationMode = CalibrationMode.DIRECT,

// 间接测量：5个标准挡块的记录点（encoderPos=null 表示尚未记录）
val indirectPoints: List<IndirectCalibPoint> = STANDARD_BLOCK_DEPTHS_MM.map {
    IndirectCalibPoint(depthMm = it, encoderPos = null)
},
```

新增枚举与数据类（可放在 `SowingDepthData.kt` 末尾）：

```kotlin
enum class CalibrationMode { DIRECT, INDIRECT }

data class IndirectCalibPoint(
    val depthMm: Float,          // 挡块对应深度，固定值（mm）
    val encoderPos: Int? = null  // 记录的编码器位置；null = 尚未记录
)

// 标准挡块深度序列（mm）
val STANDARD_BLOCK_DEPTHS_MM = listOf(20f, 40f, 60f, 80f, 100f)
```

`SowingDepthState` 无需改动，`masterEnabled` 等字段保持不变。

### 7.4 持久化（MySharedPreFun.kt）

在已有的深度标定持久化代码旁边新增：

```kotlin
// 保存：calibrationMode、5个 indirectPoints.encoderPos（-1 表示 null）
fun saveCalibrationMode(motorIndex: Int, mode: CalibrationMode)
fun loadCalibrationMode(motorIndex: Int): CalibrationMode

fun saveIndirectPoints(motorIndex: Int, points: List<IndirectCalibPoint>)
fun loadIndirectPoints(motorIndex: Int): List<IndirectCalibPoint>
```

### 7.5 UI 实现（DepthCalibrationScreen.kt）

**改动范围极小，只在步骤2区域内修改：**

1. 步骤2容器顶部加模式切换：

```kotlin
// 在步骤2的 Column 顶部
var calibMode by remember { mutableStateOf(cal.calibrationMode) }
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(selected = calibMode == CalibrationMode.DIRECT,
        onClick = { calibMode = CalibrationMode.DIRECT },
        label = { Text("直接测量") })
    FilterChip(selected = calibMode == CalibrationMode.INDIRECT,
        onClick = { calibMode = CalibrationMode.INDIRECT },
        label = { Text("间接测量（挡块法）") })
}
```

2. 根据 `calibMode` 显示两个子 composable：
   - `calibMode == DIRECT` → 已有的直接测量 UI（5点等分，不改动）
   - `calibMode == INDIRECT` → 新的 `IndirectCalibSection(cal, viewModel, motorIndex)`

3. `IndirectCalibSection` 要点：
   - 顶部复用步骤1的点动控件（`onJogStart`/`onJogStop` 逻辑完全相同，可直接复用变量）
   - 5行挡块列表，每行一个 `[记录当前位置]` 按钮：
     ```kotlin
     viewModel.updateServoCalibration(motorIndex) { c ->
         val newPts = c.indirectPoints.mapIndexed { idx, pt ->
             if (idx == blockIdx) pt.copy(encoderPos = c.currentPosition) else pt
         }
         c.copy(indirectPoints = newPts)
     }
     ```
   - `[计算拟合曲线]` 按钮（需 ≥2 个有效点）：
     ```kotlin
     val points = cal.indirectPoints
         .filter { it.encoderPos != null }
         .map { Pair(it.encoderPos!!, it.depthMm) }
     val (a, b) = SowingDepthFun.linearFit(points)  // 复用已有函数
     // 显示公式和 R²，等用户确认
     ```
   - `[保存校准]` 调用已有的 `viewModel.updateServoCalibration` 写入 `fitA`/`fitB`/`fitValid`/`calibrationPoints`（与直接模式一致，向下兼容控制循环）

### 7.6 与现有控制循环的衔接

`SowingDepthCoroutine` Phase 4 的深度→脉冲转换只用 `fitA`/`fitB`/`fitValid`，**不关心标定模式**。两种模式最终都写入相同的 `fitA`/`fitB`，控制循环无需任何改动。

| 文件 | 改动量 |
|------|--------|
| `data/SowingDepthData.kt` | 新增 `CalibrationMode`、`IndirectCalibPoint`、2个字段 |
| `MySharedPreFun.kt` | 新增 4 个持久化方法 |
| `ui/DepthCalibrationScreen.kt` | 步骤2区域加模式切换 + `IndirectCalibSection` composable |
| `coroutine/SowingDepthCoroutine.kt` | **无需改动** |
| `funClass/CanOpenFun.kt` | **无需改动**（点动帧复用） |
| `funClass/SowingDepthFun.kt` | **无需改动**（`linearFit` 复用） |

---

## 八、摆臂编码器实测深度反馈子系统（2026-07 已实现）

### 8.1 背景与范围

现有深度控制是"半开环"的：伺服反馈量是电机自身编码器位置，无法反映真实入土深度。
每行压种轮处加装机械改造：**带弹簧张力的摆臂式压种轮，摆臂转轴处安装布瑞特单圈绝对值
编码器（CANopen DS406 行规）**。摆臂角度与实际播深单调相关，标定拟合后可实时测量真实播深。

本子系统只做**测量、标定、显示、记录**四件事。**闭环修正明确不做**（实测深度不写回
`targetDepth`、不参与 Phase 4 决策）；将来做闭环时读 `EncoderCalibration.measuredDepth` 即可。

协议手册：`docs/005 CANOPEN说明书通信协议 V2.6.pdf`（布瑞特，DS301+DS406）。

### 8.2 Node-ID 分配表

| 设备 | Node-ID | TPDO1 | SDO 请求/应答 | Boot-up/心跳 |
|------|---------|-------|---------------|--------------|
| 施肥电机（自有协议） | 1~8 | —（CAN-ID 0x0027 段） | — | — |
| 深度伺服（DS402） | 11~18 | 0x18B~0x192 | 0x60B~0x612 / 0x58B~0x592 | 0x70B~0x712 |
| **摆臂编码器（DS406）** | **21~28（= 21+motorIndex，与伺服一一对应）** | 0x195~0x19C | 0x615~0x61C / 0x595~0x59C | 0x715~0x71C |

编码器出厂 Node-ID=1、波特率 500K（与总线一致，**禁改**）。出厂 TPDO 0x181 落在
TPDO 路由段但不在任何白名单内，会跌落施肥解析造成数据污染——**必须先经配置工具
（§8.6）分配 ID 后才能上总线**。

### 8.3 帧路由（CanReceiveCoroutine.dispatchFrame）

```
CAN 帧 → classifyCanOpen(canId)（纯函数，段识别映射与旧版逐字节一致）
  ├─ 非 CANopen 段（如 0x0027）────────────────→ 施肥解析（完全未改动）
  └─ CANopen 段（SDO回复/TPDO1/心跳/TPDO2/3）
       ├─ nodeId ∈ 伺服白名单（motors[].nodeId）──→ 伺服处理（完全未改动，优先级最高）
       ├─ nodeId ∈ 编码器白名单（encoders[].nodeId）
       │    ├─ TPDO1（4字节）→ onEncoderTpdo1：符号展开→限幅→滑动均值→拟合换算
       │    ├─ SDO 应答     → onEncoderSdoResponse：叫醒 SdoReplyWaiters + 刷新在线
       │    └─ Boot-up/心跳/TPDO2/3 → 吸收（无业务处理，防跌落施肥）
       ├─ SDO 应答且 SdoReplyWaiters 有 waiter（如出厂 ID=1 的 0x581）→ 消费，不落施肥
       └─ 其余 ─────────────────────────────────→ 施肥解析（容错，与旧版一致）
```

关键实现文件：

| 文件 | 职责 |
|------|------|
| `funClass/EncoderCanOpenFun.kt` | 协议层纯函数：TPDO 4 字节解析、EDS 60 个数值对象目录、结构化 SDO/abort 解析、SDO 语义化构帧、NMT/SYNC 调试支持、`toSignedPosition` 符号展开、`EncoderFilter` 两级滤波、`depthFromEncoder` 唯一换算入口 |
| `funClass/CanOpenFun.kt` → `SdoReplyWaiters` | 请求-应答等待器：先注册后发送、按应答 canId 精确匹配（覆盖改 ID 后旧 ID 应答场景）、超时返回 null |
| `data/EncoderFeedbackData.kt` | `EncoderCalibration`（持久化：nodeId/zeroSet/分辨率/标定点/fit；运行时：raw/filtered/measuredDepth/isOnline/lastHeardMs）+ `EncoderFeedbackState` |
| `ViewModelAndPublic.kt` | `encoderFeedbackStateRef`（AtomicReference 原子真源）+ CAS 更新 + LiveData 投影；后台读取一律 `currentEncoderFeedbackState()` |
| `funClass/MySharedPreFun.kt` | `enc_N_*` 键持久化（同 `sowing_depth_prefs` 文件） |
| `coroutine/CanReceiveCoroutine.kt` | 路由分支、`encoderLastSeen` 看门狗（2000ms 与伺服同阈值）、测试模式模拟数据 |

### 8.4 测量链与滤波

```
TPDO1 原始值（U32） → toSignedPosition（单圈回绕符号展开，需分辨率，配置工具读 6501h 持久化）
                   → 限幅野值剔除（单帧跳变 > 分辨率5% 丢弃；连续 3 帧超限视为真实快速变化，接受并重建窗口）
                   → 窗口 8 滑动均值（50ms × 8 = 400ms 平滑窗，摊平压种轮过垄沟/残茬弹跳）
                   → depth_mm = fitA × filteredPos + fitB（与伺服 fittingCoefficient 体系同构；
                     将来升级二阶多项式只改 depthFromEncoder 与拟合处）
```

在线判定：不启用编码器心跳，沿用 `servoLastSeen` 模式——TPDO/SDO 应答到达刷新
`encoderLastSeen`，超 2000ms 置离线。带宽预算：配置工具把 1800-05 写为 **50ms**
（出厂 20ms × 8 台 ≈ 400 帧/s 会逼近 115200bps 桥容量）；软件不假设固定上报周期。

### 8.5 标定流程（DepthCalibrationScreen 步骤 3）

1. **零位预设**：机具落基准态（压种轮触平整地面）→ 置零 → SDO 写 `6003-00=0`
   → 等 0x60 应答 → 写 `1010-01="save"` → 等应答 → 提示断电重启。每步经
   `SdoReplyWaiters` 超时报错中止；save 帧在序列最后（前缀安全）。成功后 `zeroSet=true` 持久化。
2. **多点拟合**：机具压到某实际深度稳定后，卡尺量真实播深输入 → 记录当前【滤波后】
   编码值配对（2~5 点）→ 共用 `buildLinearFit`（`data/SowingDepthData.kt`）最小二乘
   → `fitValid=true`。支持删点/清空重标。
3. 标定页实时显示原始值/滤波值/换算深度，便于现场判断信号是否正常。

### 8.6 软件内调试与一次性配置工具（EncoderProvisioningScreen，设置页入口）

页面上半部分的 `EncoderDiagnosticsPanel` 提供：

- 扫描出厂 ID 1、工作 ID 21~28 和用户指定 ID；
- 一键读取身份、错误、通信、位置、分辨率、报警/警告等 EDS 快照，并校验 BRT 身份；
- 浏览 EDS 全部 60 个 expedited SDO 数值对象，读取 RO/RW、二次确认写 RW、显式保存；
- 解析并显示常见 CiA301 SDO 中止码，不再只报“设备错误”；
- NMT 启动/停止/预运行/复位节点/复位通信、SYNC 与 `6004h` 连续采样统计；
- `3000h` 波特率和 `3001h` Node-ID 通用写保护（后者只走下方专用配号流程）。

EDS 的 `1008h/1009h/100Ah` 为可见字符串并可能需要分段 SDO，当前不在数值控制台开放；
设备身份由 `1018h` 四个数值子项核验。

推荐现场调试顺序：确认 500 kbit/s 与接线 → 扫描 ID 1/21~28 → 一键诊断并核对
Vendor `0x0000FFFF`、Product `0x00000010`、波特率代码 6 → NMT 启动 → 连续采样
`6004h` 并转动编码器检查方向/量程/回绕 → 按需读取或二次确认写入 EDS RW 对象 →
显式保存。扫描失败优先检查供电、CAN-H/L、终端电阻、串口和当前 Node-ID；位置可用但
App 离线时检查 TPDO1 COB-ID、映射与 NMT 状态。

下半部分保留新设备的一次性配置流程。⚠ **同一时刻总线上只能接入一台未配置的编码器
（出厂 ID=1）**。逐台：

1. 读 `6501-00` 验证在线并暂存物理分辨率
2. 写 `3001-00` = 目标 ID（21+行号）——**应答仍按旧 ID（0x580+旧ID）匹配**（手册明确）
3. 写 `1800-05` = 50（0x32，U16）
4. 写 `1010-01` = "save"（`23 10 10 01 73 61 76 65`，序列最后）
5. 断电重启 → 按新 ID 读 `6004-00` 验证 → 成功才把 nodeId/分辨率持久化到该行

### 8.7 显示与记录

- `SowingDepthScreen` 每行卡片："实测深度"与当前/目标深度并列；离线显示"离线"、
  未标定显示"未标定"，**不显示 0 值**（0 是合法深度，会误导操作员）。
- CSV（`DepthRecordFun`，手动 `depthRec_` 与一键测试 `depthTest_` 共用）：表头尾部追加
  `measured_depth_mm / enc_position / enc_online` 3 列，旧 9 列不改动不重排（向后兼容）；
  离线/未标定写空串而非 0。
- 测试模式（`testMode_Switch`）：编码器 8 路置在线 + 200ms 模拟数据（跟随伺服
  targetDepth ± 正弦波动），无硬件时 UI 可开发调试。

### 8.8 单元测试

`EncoderCanOpenFunTest.kt`：TPDO 4 字节解析、EDS 60 个数值地址/核心属性、结构化
expedited SDO 与 abort、RO 写保护、SYNC、手册示例帧逐字节核对（读 6004 应答
`43 04 60 00 E8 03 00 00`→1000、save/1800-05/3001 帧）、滤波、符号展开、最小二乘。
`CanFrameRoutingTest.kt`：编码器帧不落施肥、伺服/施肥/未配置节点路由与改动前逐字节一致、
SdoReplyWaiters 消费语义。

变更摘要详见 `docs/ENCODER_FEEDBACK_CHANGES.md`。
