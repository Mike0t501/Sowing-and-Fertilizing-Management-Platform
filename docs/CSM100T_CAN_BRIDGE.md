## CSM100T CAN-UART Bridge Configuration

### Hardware
- Module: 致远电子 CSM100T (CAN-UART converter)
- UART side: connected to Android tablet via `ttyuw1` serial port, baud rate 115200 bps
- CAN side: connected to CAN bus (shared by fertilizer motors and sowing depth servo motors), CAN baud rate 500 kbps
- 120Ω termination resistor required on the CAN bus (module and motors have no built-in terminator)

### Serial Frame Format (UART ↔ CSM100T)

Both sending and receiving use the same frame structure:

```
[0x27] [帧长度] [帧信息] [帧ID高] [帧ID低] [数据域 0~8字节] [0x39]
  帧头    长度     0x00    CAN-ID高  CAN-ID低    CAN数据          帧尾
```

- **帧头**: 固定 `0x27`
- **帧长度**: 从帧信息到数据域最后一字节的总字节数 = 1(帧信息) + 2(帧ID) + N(数据长度) = N + 3
- **帧信息**: `0x00` = 标准数据帧
- **帧ID**: 2字节，高字节在前。即 CAN-ID `0x060B` 写成 `06 0B`
- **数据域**: 0~8字节，即 CAN 帧的数据段内容
- **帧尾**: 固定 `0x39`

### Two Protocols on One CAN Bus

The CAN bus carries two completely different protocols. Software must distinguish them by CAN-ID:

#### Protocol 1: Fertilizer Motors (existing custom protocol)
- CAN-ID: `0x0027` (fixed, for all fertilizer motors)
- Direction: Tablet → Motor controller
- Serial frame example (send RPM to motor #1):
  ```
  27 08 00 00 27 00 01 01 01 28 04 39
  帧头 长度 信息 --帧ID-- 标识 电机ID 启停 转速整 转速小 帧尾
  ```
- Direction: Motor controller → Tablet
- Serial frame example (status return):
  ```
  27 07 帧信息 帧ID高 帧ID低 电机ID 转速整 转速小 电压高 电压低 39
  ```

#### Protocol 2: Sowing Depth Servo Motors (CANopen CiA301 + DS402)
- CAN-ID: Variable, based on function code + Node-ID
- Node-ID allocation: 11~18 (for 8 sowing depth motors, avoiding conflict with fertilizer motor IDs 1~8)
- Key CAN-IDs for Node-ID=11:

| Function | CAN-ID | 帧ID bytes | Direction |
|----------|--------|-----------|-----------|
| SDO Send (config/command) | 0x060B | `06 0B` | Tablet → Motor |
| SDO Reply | 0x058B | `05 8B` | Motor → Tablet |
| TPDO1 (position+status) | 0x018B | `01 8B` | Motor → Tablet |
| TPDO2 | 0x028B | `02 8B` | Motor → Tablet |
| TPDO3 (position+velocity) | 0x038B | `03 8B` | Motor → Tablet |
| Heartbeat | 0x070B | `07 0B` | Motor → Tablet |
| NMT | 0x0000 | `00 00` | Tablet → All motors |
| SYNC | 0x0080 | `00 80` | Tablet → All motors |

For Node-ID=12: SDO Send=`060C`, SDO Reply=`058C`, TPDO1=`018C`, etc.

### Building Serial Frames for CANopen SDO

**SDO Write example** (write controlword 0x6040 = 0x000F to Node 11):
```kotlin
// CAN-ID = 0x600 + 11 = 0x060B
// SDO data: 2B 40 60 00 0F 00 00 00 (write 2 bytes to index 0x6040 subindex 0x00)
val frame = byteArrayOf(
    0x27,                   // 帧头
    0x0B,                   // 帧长度 = 3 + 8 = 11
    0x00,                   // 帧信息 (标准数据帧)
    0x06, 0x0B,             // 帧ID (0x060B)
    0x2B, 0x40, 0x60, 0x00, // SDO: write 2 bytes, index 0x6040, subindex 0x00
    0x0F, 0x00, 0x00, 0x00, // value: 0x000F (little-endian)
    0x39                    // 帧尾
)
```

**SDO Read example** (read actual position 0x6064 from Node 11):
```kotlin
val frame = byteArrayOf(
    0x27,
    0x0B,
    0x00,
    0x06, 0x0B,
    0x40, 0x64, 0x60, 0x00, // SDO: read, index 0x6064, subindex 0x00
    0x00, 0x00, 0x00, 0x00,
    0x39
)
// Expected reply on UART (from CSM100T):
// 27 0B 00 05 8B 43 64 60 00 [pos0] [pos1] [pos2] [pos3] 39
//                             ^^ 0x43 = read reply 4 bytes
```

**NMT Start example** (start Node 11):
```kotlin
// CAN-ID = 0x0000, DLC = 2
val frame = byteArrayOf(
    0x27,
    0x05,                   // 帧长度 = 3 + 2 = 5
    0x00,
    0x00, 0x00,             // 帧ID (0x0000)
    0x01, 0x0B,             // NMT start, Node-ID 11
    0x39
)
```

### Parsing Received Serial Frames

When CSM100T receives a CAN frame from the bus, it wraps it in the same serial frame format and sends to UART. The software must:

1. Buffer incoming UART bytes
2. Find frame boundaries: starts with `0x27`, ends with `0x39`
3. Extract CAN-ID from bytes [3] and [4]: `canId = (byte[3].toInt() shl 8) or byte[4].toInt()`
4. Route by CAN-ID range:

```kotlin
fun processReceivedFrame(frame: ByteArray) {
    if (frame.size < 6 || frame[0] != 0x27.toByte() || frame.last() != 0x39.toByte()) return
    
    val canId = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
    val data = frame.sliceArray(5 until frame.size - 1) // data between 帧ID and 帧尾
    
    when {
        // Fertilizer motor response (existing custom protocol)
        canId == 0x0027 -> handleFertilizerResponse(data)
        
        // CANopen SDO reply (0x580 + NodeID, NodeID 11~18 → 0x058B~0x0592)
        canId in 0x058B..0x0592 -> {
            val nodeId = canId - 0x0580
            handleSdoReply(nodeId, data)
        }
        
        // CANopen TPDO1 (0x180 + NodeID → 0x018B~0x0192)
        canId in 0x018B..0x0192 -> {
            val nodeId = canId - 0x0180
            handleTpdo1(nodeId, data) // data = [pos0 pos1 pos2 pos3 status0 status1]
        }
        
        // CANopen TPDO3 (0x380 + NodeID → 0x038B~0x0392)
        canId in 0x038B..0x0392 -> {
            val nodeId = canId - 0x0380
            handleTpdo3(nodeId, data) // data = [pos0 pos1 pos2 pos3 vel0 vel1 vel2 vel3]
        }
        
        // CANopen Heartbeat (0x700 + NodeID → 0x070B~0x0712)
        canId in 0x070B..0x0712 -> {
            val nodeId = canId - 0x0700
            handleHeartbeat(nodeId, data[0]) // data[0]: 0x05=operational, 0x04=alarm
        }
    }
}
```

### Key Implementation Notes for CanOpenFun.kt

The existing fertilizer code sends frames using a fixed CAN-ID `0x0027` and distinguishes motors by a motor-ID byte inside the data field. CANopen is fundamentally different — each motor has a unique CAN-ID based on its Node-ID, and the data field contains object dictionary operations.

**The `wrapCanFrame()` function in CanOpenFun.kt should be implemented as:**

```kotlin
fun wrapCanFrame(canId: Int, data: ByteArray): ByteArray {
    val frameLen = 3 + data.size  // 帧信息(1) + 帧ID(2) + data(N)
    val frame = ByteArray(4 + data.size + 1) // 帧头(1) + 帧长度(1) + 帧信息(1) + 帧ID(2) + data(N) + 帧尾(1)
    // Actually: 1(帧头) + 1(帧长度) + 1(帧信息) + 2(帧ID) + N(数据) + 1(帧尾) = N + 6
    val result = ByteArray(data.size + 6)
    result[0] = 0x27                              // 帧头
    result[1] = frameLen.toByte()                  // 帧长度
    result[2] = 0x00                              // 帧信息 (标准数据帧)
    result[3] = ((canId shr 8) and 0xFF).toByte()  // 帧ID高字节
    result[4] = (canId and 0xFF).toByte()          // 帧ID低字节
    data.copyInto(result, 5)                       // 数据域
    result[result.size - 1] = 0x39                 // 帧尾
    return result
}
```

**Frame parsing for incoming data should be added to CanReceiveCoroutine.kt:**
The existing code reads bytes from the CAN serial port and parses fertilizer motor responses. The modification should:
1. Keep all existing fertilizer frame parsing logic intact
2. Add CAN-ID extraction from bytes [3][4] of each received frame
3. Route CANopen frames (CAN-ID ≠ 0x0027) to new CANopen handler
4. Use a thread-safe queue or callback to pass parsed SDO replies back to SowingDepthCoroutine

### UART Port Configuration
- Serial port path: `ttyuw1` (configured in SettingsScreen, stored in SharedPreferences)
- Baud rate: 115200
- Data bits: 8, Stop bits: 1, Parity: None
- The same serial port is shared for both fertilizer and sowing depth communication
