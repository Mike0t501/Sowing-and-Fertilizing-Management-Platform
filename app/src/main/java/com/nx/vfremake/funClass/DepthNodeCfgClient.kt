/** @author NIANXI @date 2026-11-04 @brief dingo 参数请求应答，不复用 CANopen SDO 匹配。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.DepthNodeFilterParams
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 完整请求事务串行；按四元组匹配，旧应答及 InfoMsg 不能串步。 */
class DepthNodeCfgWaiters {
    data class Key(val canId: Int, val cmd: Int, val index: Int, val subIndex: Int)
    private val pending = ConcurrentHashMap<Key, CompletableDeferred<P.CfgReply>>()
    private val transactions = Mutex()

    /** 必须先注册再调用 send；超时从发送结束后计时，等待不超过 ICD 的 200ms。 */
    suspend fun sendAndAwait(
        frame: P.Frame, replyBase: Int, send: suspend (P.Frame) -> Unit, timeoutMs: Long = 200
    ): P.CfgReply? = transactions.withLock {
        require(timeoutMs in 1..200)
        val request = P.parseCfgReply(frame.data) ?: error("非法 CFG 请求")
        val key = Key(replyBase, request.cmd, request.index, request.subIndex)
        val deferred = CompletableDeferred<P.CfgReply>()
        pending[key] = deferred
        try {
            send(frame)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally { pending.remove(key, deferred) }
    }

    /** Read 不存在参数的 cmd=5 只匹配同地址的 Read，不得匹配 Write。 */
    fun tryComplete(canId: Int, data: ByteArray): Boolean {
        val reply = P.parseCfgReply(data) ?: return false
        val key = Key(canId, if (reply.cmd == P.NOT_FOUND) P.READ else reply.cmd, reply.index, reply.subIndex)
        val deferred = pending.remove(key) ?: return false
        deferred.complete(reply)
        return true
    }

    /** 测试和诊断使用；取消会通过 finally 移除等待对象。 */
    fun pendingCount(): Int = pending.size
}

/** transport 的生产实现必须经过共享 20ms 发送调度，单元测试可注入协议对端。 */
class DepthNodeCfgClient(
    private val runtime: DepthNodeRuntime,
    private val send: suspend (P.Frame) -> Unit,
    private val timeoutMs: Long = 200
) {
    data class Readback(val baseId: Int, val words: Map<Int, Long>, val errors: List<String>, val readAtMs: Long)
    private val operations = Mutex()

    /** 读取原始线上值；未知参数与超时明确失败。 */
    suspend fun read(base: Int, index: Int = P.PARAM_INDEX, sub: Int): Long {
        val reply = runtime.waiters.sendAndAwait(P.cfgRequest(base, P.READ, index, sub), base, send, timeoutMs)
            ?: error("节点 0x${base.toString(16)} 读取 $sub 超时")
        check(reply.cmd == P.READ) { "节点不支持参数 0x${index.toString(16)}:$sub" }
        return reply.value
    }

    /** 写入应答丢失时必须 Read 确认，拒绝与丢帧不能被误报为成功。 */
    suspend fun write(base: Int, sub: Int, value: Long, index: Int = P.PARAM_INDEX, replyBase: Int = base) {
        validate(index, sub, value)
        val reply = runtime.waiters.sendAndAwait(P.cfgRequest(base, P.WRITE, index, sub, value), replyBase, send, timeoutMs)
        val actual = reply?.value ?: read(replyBase, index, sub)
        check(actual == value) { "参数 $sub 未生效（节点拒绝或丢帧）；期望=$value，回读=$actual" }
    }

    /** 采集完整参数快照；失败项保留为空，不能用默认值填充元数据。 */
    suspend fun readAll(base: Int): Readback = operations.withLock { readAllUnlocked(base) }

    private suspend fun readAllUnlocked(base: Int): Readback {
        val words = linkedMapOf<Int, Long>(); val errors = mutableListOf<String>()
        for (sub in 0..17) {
            try { words[sub] = read(base, sub = sub) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add(e.message ?: "读取失败") }
        }
        val at = runtime.nowMs()
        val params = (0..8).mapNotNull { words[it] }.takeIf { it.size == 9 }?.let { DepthNodeFilterParams.fromWords(it) }
        runtime.store.updateChannel(P.rowOf(base) ?: -1) { channel -> channel.copy(
            parameterWords = words.toMap(), filterParams = params,
            reportMs = words[9]?.toInt()?.let { P.actualReportMs(it) } ?: channel.reportMs,
            configReadAtMs = at, configError = errors.takeIf { it.isNotEmpty() }?.joinToString("；")
        ) }
        return Readback(base, words.toMap(), errors, at)
    }

    /** 九项全写、逐项回读，最后等待真实 STATUS CRC；失败不自动保存到 Flash。 */
    suspend fun applyFilter(base: Int, params: DepthNodeFilterParams) = operations.withLock {
        require(params.isValid())
        try {
            for ((sub, word) in params.wireWords().withIndex()) {
                write(base, sub, word)
                check(read(base, sub = sub) == word) { "滤波参数 $sub 回读不一致" }
            }
            val channel = runtime.channel(P.rowOf(base) ?: -1)
            val deadline = runtime.nowMs() + maxOf(1000, 3 * channel.reportMs)
            while (runtime.nowMs() < deadline && runtime.channel(channel.row).cfgCrc != params.crc) kotlinx.coroutines.delay(20)
            check(runtime.channel(channel.row).cfgCrc == params.crc) { "参数已写入，但 STATUS CRC 尚未确认" }
        } finally {
            // 失败也回读实际状态，禁止 UI 继续把期望预设当作当前预设。
            readAllUnlocked(base)
        }
    }

    /** Flash 保存只在明确 rr=1 后确认；无应答不得承诺已保存。 */
    suspend fun burn(base: Int) {
        val reply = runtime.waiters.sendAndAwait(P.burnRequest(base), base, send, timeoutMs)
            ?: error("保存无应答，Flash 状态未知，请重试确认")
        check(reply.value == 1L) { "节点 Flash 保存失败" }
    }

    /** 配号写应答已在新基址，后续 Read、Burn 和 INFO 验证全部使用新地址。 */
    suspend fun provision(row: Int) = operations.withLock {
        val newBase = P.baseForRow(row)
        val target = runtime.channel(row)
        check(!target.isOnline && runtime.nowMs() - target.lastInfoMs > 1500 || !target.hasStatus && target.icdVer == null) {
            "目标行已有在线节点，请先移除或选择空闲行"
        }
        val started = runtime.nowMs()
        write(P.FACTORY_BASE, 0, newBase.toLong(), index = 0, replyBase = newBase)
        check(read(newBase, 0, 0) == newBase.toLong()) { "新地址回读不一致" }
        burn(newBase)
        val deadline = runtime.nowMs() + 2200
        while (runtime.nowMs() < deadline && runtime.channel(row).lastInfoMs <= started) kotlinx.coroutines.delay(20)
        check(runtime.channel(row).lastInfoMs > started && runtime.channel(row).versionCompatible) { "新地址 INFO 验证失败" }
    }

    companion object {
        /** ICD 参数范围预检；线上超时回读仍需执行，不能假定固件支持全部滤波器。 */
        fun validate(index: Int, sub: Int, word: Long) {
            require(word in 0..0xFFFFFFFFL)
            if (index == 0) {
                require(when (sub) { 0 -> P.isValidBase(word.toInt()) && word <= 0x7FF; 1 -> word == 1L; 3 -> word == 0L; else -> false })
                return
            }
            require(index == P.PARAM_INDEX)
            val valid = when (sub) {
                0 -> word in 1..31; 1 -> word in 0..100; 2 -> word in 0..4; 3 -> word in 1..64
                4 -> P.wordFloat(word).isFinite() && P.wordFloat(word) in 0.01f..5f
                5 -> P.wordFloat(word).isFinite() && P.wordFloat(word) in 0.1f..20f
                6, 7 -> P.wordFloat(word).isFinite() && P.wordFloat(word) in 0.001f..1e6f
                8 -> P.wordFloat(word).isFinite() && P.wordFloat(word) in 0f..10f
                9 -> word in 10..1000; 10 -> word in 0..4; 11 -> word in 0..3600
                12, 13, 14, 15 -> word in 0..5000
                16 -> P.wordFloat(word).isFinite() && P.wordFloat(word) in 0.9f..1.1f
                17 -> P.wordFloat(word).isFinite() && P.wordFloat(word) in -100f..100f
                else -> false
            }
            require(valid) { "参数 $sub 超出 ICD 范围" }
        }
    }
}
