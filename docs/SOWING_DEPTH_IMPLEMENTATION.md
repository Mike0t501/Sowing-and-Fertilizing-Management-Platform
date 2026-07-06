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
| 目标位置缓存 | 0x607A-00 | 4字节(有符号) | RW | 目标位置 |
| 梯形速度 | 0x6081-00 | 4字节 | RW | 位置模式最大速度，单位RPM，范围0~3000 |
| 电机加速度 | 0x6083-00 | 4字节 | RW | 单位(RPM/s)，<60000用内部曲线 |
| 速度模式速度 | 0x60FF-00 | 4字节(有符号) | RWM | 速度模式目标转速，范围-3000~3000 |
| Modbus使能 | 0x2600-00 | 2字节 | RW | 0=禁止, 1=使能 |
| 电子齿轮分子 | 0x260A-00 | 2字节 | RW | 默认32768 |
| 电子齿轮分母 | 0x260B-00 | 2字节 | RW | 默认1 |
| 位置限位最小值 | 0x261F-00 | 4字节(有符号) | RW | 限位功能需开启 |
| 位置限位最大值 | 0x2620-00 | 4字节(有符号) | RW | 限位功能需开启 |
| 参数保存标志 | 0x2614-00 | 2字节 | RW | 写1=保存中, 读到2=保存完毕 |
| 特殊功能 | 0x2619-00 | 2字节 | RW | 0=脉冲+方向 |
| 心跳产生间隔 | 0x1017-00 | 2字节 | RWM | 单位ms, 0=不产生 |

### 2.4 控制字 (0x6040) 位定义
```
Bit0: 启动（置1后外部脉冲控制无效）
Bit1: 允许急停
Bit2: 电压输出
Bit3: 允许操作
Bit4: 执行新设置点（写1后运行到新位置，自动清零）
Bit5: 位置立即生效
Bit6: 0=绝对位置, 1=相对位置
Bit7: 故障复位
Bit8: 停止（值为1时电机急停但仍自锁）
```

常用控制字值：
- `0x000F`: 启动+电压输出+允许急停+允许操作
- `0x002F`: 绝对位置 + 新位置立即执行
- `0x004F`: 相对位置控制模式
- `0x005F`: 相对位置 + 执行新位置点
- `0x010F`: 停止

### 2.5 状态字 (0x6041) 位定义
```
Bit10: 目标达到（位置模式=到达目标位置，速度模式=到达给定速度）
Bit12: 找原点完成
Bit14: 到达正限位
Bit15: 到达负限位
```

### 2.6 编码器参数
- 15位绝对编码器，一圈 = 32768 脉冲
- 电子齿轮默认：分子32768, 分母1（即1:1映射编码器原始值）

### 2.7 限位功能开启步骤（通过SDO）
```
步骤1: 写 电机加速度(0x6083) = 1      // 开启限位功能
步骤2: 写 弱磁角度(0x2604) = 131
步骤3: 写 Modbus使能(0x2600) = 506    // 特殊保存命令
步骤4: 重新上电

步骤5: 写 限位最小值(0x261F) = min_value  // 有符号32位
步骤6: 写 限位最大值(0x2620) = max_value  // 有符号32位
步骤7: 写 参数保存标志(0x2614) = 1
步骤8: 重新上电
```

### 2.8 SDO 绝对位置控制流程
```kotlin
// 1. 使能驱动器
SDO_Write(0x6040, 0x00, 2, 0x000F)  // 控制字=启动+电压+急停+操作

// 2. 设置位置模式
SDO_Write(0x6060, 0x00, 1, 0x01)    // 工作模式=位置模式

// 3. 读取当前位置（重要！上电后需读取）
position = SDO_Read(0x6064, 0x00)    // 实际位置

// 4. 设置速度和加速度（可选，使用默认值可跳过）
SDO_Write(0x6081, 0x00, 4, 1000)    // 梯形速度=1000RPM
SDO_Write(0x6083, 0x00, 4, 20000)   // 加速度=20000RPM/s

// 5. 设置绝对位置+立即执行
SDO_Write(0x6040, 0x00, 2, 0x002F)  // 控制字=绝对+立即执行

// 6. 写入目标位置
SDO_Write(0x607A, 0x00, 4, target)  // 目标位置

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

// 4. 停止
SDO_Write(0x6040, 0x00, 2, 0x010F)  // Bit8=1 急停
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
    
    /** 急停 */
    fun buildEmergencyStop(nodeId: Int) = 
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x010F)
    
    /** 读取当前位置 */
    fun buildReadPosition(nodeId: Int) = 
        buildSdoReadFrame(nodeId, 0x6064, 0x00)
    
    /** 读取状态字 */
    fun buildReadStatus(nodeId: Int) = 
        buildSdoReadFrame(nodeId, 0x6041, 0x00)
    
    /** 写入限位值 */
    fun buildSetLimits(nodeId: Int, minPos: Int, maxPos: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x261F, 0x00, 4, minPos.toLong()),
        buildSdoWriteFrame(nodeId, 0x2620, 0x00, 4, maxPos.toLong()),
        buildSdoWriteFrame(nodeId, 0x2614, 0x00, 2, 1)  // 保存参数
    )

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
- **硬件限位**：通过SDO写入电机的限位寄存器（0x261F/0x2620），电机内部也会限制
- **两层都要做**，软件限位是第一道防线，硬件限位是保底

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
