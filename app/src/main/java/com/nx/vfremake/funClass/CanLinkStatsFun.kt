/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年9月11日
 * @file    :CanLinkStatsFun.kt
 * @brief   :CAN 链路质量计数器（SDO 请求/应答配对、UART 帧错位、每节点收帧统计）
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 2026-09 现场"播深调不了"排查了数天，最终确认是工控平板 CAN 接口接触不良导致丢帧——
 * 软件层面完全看不出链路在丢帧，才走了很多弯路。本文件让链路质量在调试页上可见。
 ***********************************************************************************************************
 */

package com.nx.vfremake.funClass

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * CAN 链路质量计数器。
 *
 * 【只计数，不干预】所有方法都是原子自增，不挂起、不加锁、不改任何控制行为。
 * 挂钩位置：
 *   - [CanOpenFun.sendFrame]：每发出一帧 SDO 请求（0x600+ID）计一次；
 *   - CanReceiveCoroutine 帧解析循环：完整帧、帧头错位丢弃字节、帧尾校验失败、解析异常、串口 IO 异常；
 *   - CanReceiveCoroutine.dispatchFrame：按 CAN-ID 分类后计 SDO 应答 / TPDO1 / 心跳 / Boot-up。
 *
 * 【怎么看】
 *   - SDO 请求数 vs 应答数：每条 SDO 请求驱动器都应答一帧（成功 0x60/0x4x 或 abort 0x80），
 *     差值就是链路丢帧。健康链路丢失率应为 0%；接触不良时会明显 >0。
 *   - 帧头错位丢弃字节：UART 字节流里找不到 0x27 帧头而丢掉的字节数。正常应恒为 0；
 *     >0 说明 UART 层有字节丢失/错位——这是比 SDO 丢失更底层的信号。
 *   - 帧尾校验失败：帧头对、长度对、但结尾不是 0x39。同样应恒为 0。
 *
 * 【0x6084 例外】YZ 驱动器对 0x6084 的读写既不 ACK 也不 abort（EDS 未声明，实测静默丢弃，
 * 见 docs/CANframes2.txt），若计入请求会给每次运动下发/点动固定制造一条"丢失"，把丢失率
 * 抬到永远不为 0。所以对 0x6084 的请求不计入。
 *
 * 【线程模型】发送侧多协程调用、接收侧单协程调用、UI 轮询读快照——全部用 AtomicLong 与
 * ConcurrentHashMap，无需额外同步。时间用 System.nanoTime（单调、纯 JVM，单元测试可跑）。
 */
object CanLinkStats {

    /** 不计入请求数的对象索引（驱动器已知不应答） */
    private const val INDEX_NO_REPLY_EXPECTED = 0x6084

    // ── 全局（与节点无关）──────────────────────────────────────────────────
    private val rxFrames      = AtomicLong()   // 成功解析出的完整帧（含施肥电机帧）
    private val resyncBytes   = AtomicLong()   // 为寻找 0x27 帧头而丢弃的字节
    private val badTailFrames = AtomicLong()   // 帧头/长度对但帧尾不是 0x39
    private val parseErrors   = AtomicLong()   // dispatchFrame 抛异常
    private val ioErrors      = AtomicLong()   // 串口读 IOException
    private val txFrames      = AtomicLong()   // 经 CanOpenFun.sendFrame 发出的帧（所有 CANopen 帧）

    @Volatile
    private var sinceMs = monotonicMs()

    // ── 每节点 ─────────────────────────────────────────────────────────────
    class NodeCounters {
        val sdoRequests = AtomicLong()
        val sdoReplies  = AtomicLong()   // 含 abort
        val sdoAborts   = AtomicLong()
        val tpdo1       = AtomicLong()
        val heartbeats  = AtomicLong()
        val bootups     = AtomicLong()
    }

    private val nodes = ConcurrentHashMap<Int, NodeCounters>()

    private fun node(nodeId: Int): NodeCounters = nodes.getOrPut(nodeId) { NodeCounters() }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    // ── 发送侧挂钩 ─────────────────────────────────────────────────────────

    /**
     * 一帧 CANopen 已写入串口。从封装帧里识别 SDO 请求（0x600+ID）并按节点计数。
     * 帧格式：[0x27][len][0x00][idHi][idLo][cs][idxLo][idxHi][sub]…[0x39]
     */
    fun onFrameSent(frame: ByteArray) {
        txFrames.incrementAndGet()
        if (frame.size < 9) return
        val canId = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
        if (canId !in 0x601..0x67F) return
        val index = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        if (index == INDEX_NO_REPLY_EXPECTED) return
        node(canId - 0x600).sdoRequests.incrementAndGet()
    }

    // ── 接收侧挂钩 ─────────────────────────────────────────────────────────

    fun onFrameParsed() { rxFrames.incrementAndGet() }
    fun onResyncByte() { resyncBytes.incrementAndGet() }
    fun onBadTail() { badTailFrames.incrementAndGet() }
    fun onParseError() { parseErrors.incrementAndGet() }
    fun onIoError() { ioErrors.incrementAndGet() }

    /** SDO 应答（0x580+ID）。@param isAbort CS==0x80 */
    fun onSdoReply(nodeId: Int, isAbort: Boolean) {
        val n = node(nodeId)
        n.sdoReplies.incrementAndGet()
        if (isAbort) n.sdoAborts.incrementAndGet()
    }

    fun onTpdo1(nodeId: Int) { node(nodeId).tpdo1.incrementAndGet() }

    /** 心跳（0x700+ID）。@param isBootup 状态字节 == 0x00 */
    fun onHeartbeat(nodeId: Int, isBootup: Boolean) {
        val n = node(nodeId)
        n.heartbeats.incrementAndGet()
        if (isBootup) n.bootups.incrementAndGet()
    }

    // ── 快照 ───────────────────────────────────────────────────────────────

    data class NodeSnapshot(
        val nodeId: Int,
        val sdoRequests: Long,
        val sdoReplies: Long,
        val sdoAborts: Long,
        val tpdo1: Long,
        val heartbeats: Long,
        val bootups: Long
    ) {
        /** 未应答的 SDO 请求数（负值说明应答多于请求，例如请求早于计数器重置发出）。 */
        val sdoLost: Long get() = sdoRequests - sdoReplies

        /** SDO 丢失率 0~1；无请求时为 0。 */
        val sdoLossRatio: Double
            get() = if (sdoRequests <= 0) 0.0 else (sdoLost.coerceAtLeast(0).toDouble() / sdoRequests)
    }

    data class Snapshot(
        val atMs: Long,
        val uptimeMs: Long,
        val txFrames: Long,
        val rxFrames: Long,
        val resyncBytes: Long,
        val badTailFrames: Long,
        val parseErrors: Long,
        val ioErrors: Long,
        val nodes: Map<Int, NodeSnapshot>
    ) {
        /** UART 层是否干净：没有错位、没有坏尾、没有解析异常 */
        val uartClean: Boolean get() = resyncBytes == 0L && badTailFrames == 0L && parseErrors == 0L
    }

    fun snapshot(): Snapshot {
        val now = monotonicMs()
        return Snapshot(
            atMs          = now,
            uptimeMs      = now - sinceMs,
            txFrames      = txFrames.get(),
            rxFrames      = rxFrames.get(),
            resyncBytes   = resyncBytes.get(),
            badTailFrames = badTailFrames.get(),
            parseErrors   = parseErrors.get(),
            ioErrors      = ioErrors.get(),
            nodes         = nodes.entries
                .map { (id, c) ->
                    id to NodeSnapshot(
                        nodeId      = id,
                        sdoRequests = c.sdoRequests.get(),
                        sdoReplies  = c.sdoReplies.get(),
                        sdoAborts   = c.sdoAborts.get(),
                        tpdo1       = c.tpdo1.get(),
                        heartbeats  = c.heartbeats.get(),
                        bootups     = c.bootups.get()
                    )
                }
                .sortedBy { it.first }
                .toMap()
        )
    }

    /** 清零全部计数（调试页"重置"按钮；测试隔离）。 */
    fun reset() {
        rxFrames.set(0); resyncBytes.set(0); badTailFrames.set(0)
        parseErrors.set(0); ioErrors.set(0); txFrames.set(0)
        nodes.clear()
        sinceMs = monotonicMs()
    }
}
