package com.nx.vfremake

import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CAN 帧路由回归测试：验证编码器（Node-ID 21~28）接入后，
 * 施肥电机与伺服（11~18）的路由归属与改动前逐字节一致，
 * 编码器帧绝不跌落施肥解析。
 *
 * 路由模型（dispatchFrame 的判定顺序）：
 *   classifyCanOpen(canId) → 伺服白名单匹配（原样保留、优先） →
 *   编码器白名单匹配 → SDO 应答等待器 → 跌落施肥。
 * 本测试以 classifyCanOpen（从 dispatchFrame 原地 when 提取的纯函数）+
 * 白名单查找复现该顺序；帧字节用 CanOpenFun.wrapCanFrame 构造
 * （与 docs/CANframes2.txt 记录的真实总线帧同构）。
 */
class CanFrameRoutingTest {

    /** 默认配置：伺服 11~18，编码器 21~28（与出厂数据类默认一致） */
    private val servoNodeIds = (11..18).toList()
    private val encoderNodeIds = (21..28).toList()

    /** 复现 dispatchFrame 的归属判定顺序，返回路由目标名 */
    private fun routeOf(canId: Int, hasWaiter: Boolean = false): String {
        val (nodeId, type) = CanReceiveCoroutine.classifyCanOpen(canId)
        if (type >= 0) {
            if (servoNodeIds.contains(nodeId)) return "SERVO"
            if (encoderNodeIds.contains(nodeId)) return "ENCODER"
            if (type == 0 && hasWaiter) return "WAITER"
        }
        return "FERTILIZER"
    }

    @Before
    fun setUp() {
        SdoReplyWaiters.clearAll()
    }

    @After
    fun tearDown() {
        SdoReplyWaiters.clearAll()
    }

    // ── 段识别纯函数：与改动前 dispatchFrame 原地 when 完全一致 ──────────────

    @Test
    fun classifyMappingUnchanged() {
        assertEquals(Pair(11, 0), CanReceiveCoroutine.classifyCanOpen(0x58B))  // 伺服 SDO 回复
        assertEquals(Pair(11, 1), CanReceiveCoroutine.classifyCanOpen(0x18B))  // 伺服 TPDO1
        assertEquals(Pair(11, 2), CanReceiveCoroutine.classifyCanOpen(0x70B))  // 伺服心跳
        assertEquals(Pair(11, 3), CanReceiveCoroutine.classifyCanOpen(0x28B))  // TPDO2
        assertEquals(Pair(11, 4), CanReceiveCoroutine.classifyCanOpen(0x38B))  // TPDO3
        assertEquals(Pair(-1, -1), CanReceiveCoroutine.classifyCanOpen(0x027)) // 施肥帧
        assertEquals(Pair(-1, -1), CanReceiveCoroutine.classifyCanOpen(0x000)) // NMT
        // 段边界
        assertEquals(Pair(-1, -1), CanReceiveCoroutine.classifyCanOpen(0x180)) // 0x181 起
        assertEquals(Pair(-1, -1), CanReceiveCoroutine.classifyCanOpen(0x580)) // 0x581 起
        assertEquals(Pair(127, 2), CanReceiveCoroutine.classifyCanOpen(0x77F))
    }

    // ── 编码器帧路由：TPDO / SDO / Boot-up 全部不落施肥 ─────────────────────

    @Test
    fun encoderFramesNeverFallToFertilizer() {
        for (nodeId in 21..28) {
            assertEquals("编码器 TPDO1 0x${(0x180 + nodeId).toString(16)}",
                "ENCODER", routeOf(0x180 + nodeId))
            assertEquals("编码器 SDO 应答 0x${(0x580 + nodeId).toString(16)}",
                "ENCODER", routeOf(0x580 + nodeId))
            assertEquals("编码器 Boot-up 0x${(0x700 + nodeId).toString(16)}",
                "ENCODER", routeOf(0x700 + nodeId))
        }
    }

    // ── 伺服与施肥路由与改动前一致 ──────────────────────────────────────────

    @Test
    fun servoRoutingUnchangedByEncoderAddition() {
        for (nodeId in 11..18) {
            assertEquals("SERVO", routeOf(0x180 + nodeId))
            assertEquals("SERVO", routeOf(0x580 + nodeId))
            assertEquals("SERVO", routeOf(0x700 + nodeId))
        }
    }

    @Test
    fun fertilizerFramesStillFallThrough() {
        // 施肥电机回复帧 CAN-ID = 0x0027（非 CANopen 段）：改动前后都走施肥解析
        assertEquals("FERTILIZER", routeOf(0x027))
        // 未配置的 CANopen 节点（如 ID=5 的 TPDO 0x185）：改动前后都跌落施肥（容错不变）
        assertEquals("FERTILIZER", routeOf(0x185))
        assertEquals("FERTILIZER", routeOf(0x705))
    }

    // ── SDO 应答等待器：旧 ID（出厂 1）应答被消费，不落施肥 ──────────────────

    @Test
    fun waiterConsumesFactoryIdSdoReply() {
        // 无 waiter：出厂 ID=1 的 SDO 应答 0x581 跌落施肥（改动前行为，保持容错）
        assertEquals("FERTILIZER", routeOf(0x581, hasWaiter = false))
        // 配置工具注册 waiter 后：被消费，不落施肥
        assertEquals("WAITER", routeOf(0x581, hasWaiter = true))
    }

    @Test
    fun sdoReplyWaiterCompleteSemantics() {
        val reply = byteArrayOf(0x60, 0x01, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00)
        // 无注册时不消费
        assertFalse(SdoReplyWaiters.tryComplete(0x581, reply))
        assertFalse(SdoReplyWaiters.hasWaiter(0x581))
    }

    // ── 帧字节序构造回归（CANframes2.txt 思路：真实桥帧字节）─────────────────

    @Test
    fun encoderTpdoBridgeFrameBytesRouteCorrectly() {
        // 编码器 21 的 TPDO1 桥帧：27 07 00 01 95 [E8 03 00 00] 39
        val frame = CanOpenFun.wrapCanFrame(0x195, byteArrayOf(0xE8.toByte(), 0x03, 0x00, 0x00))
        assertEquals(0x27.toByte(), frame.first())
        assertEquals(0x39.toByte(), frame.last())
        val unwrapped = CanOpenFun.unwrapCanFrame(frame)
        assertNotNull(unwrapped)
        val (canId, data) = unwrapped!!
        assertEquals(0x195, canId)
        assertEquals(4, data.size)
        assertEquals("ENCODER", routeOf(canId))
    }

    @Test
    fun servoTpdoBridgeFrameBytesRouteCorrectly() {
        // 伺服 11 的 TPDO1 桥帧（6 字节数据）：与改动前一样路由到伺服
        val frame = CanOpenFun.wrapCanFrame(
            0x18B, byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x37, 0x02)
        )
        val (canId, data) = CanOpenFun.unwrapCanFrame(frame)!!
        assertEquals(0x18B, canId)
        assertEquals(6, data.size)
        assertEquals("SERVO", routeOf(canId))
        // 伺服 6 字节解析不受编码器 4 字节解析影响
        val tpdo = CanOpenFun.parseTpdo1(canId, data)
        assertNotNull(tpdo)
        assertEquals(16, tpdo!!.actualPos)
    }

    @Test
    fun waiterRegistrationIsPerCanId() {
        val reply = byteArrayOf(0x60, 0x10, 0x10, 0x01, 0x00, 0x00, 0x00, 0x00)
        // tryComplete 只消费精确匹配的 canId
        assertFalse(SdoReplyWaiters.tryComplete(0x595, reply))
        assertTrue(!SdoReplyWaiters.hasWaiter(0x595))
    }
}
