/** @author NIANXI @date 2026-11-04 @brief 检测节点电压→深度唯一换算入口。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.DepthNodeChannel

object DepthNodeCalib {
    /** 不夹紧负深度或把无效值替换成 0；浅端越界由质量层明确表示。 */
    fun depthFromMv(mv: Float, channel: DepthNodeChannel): Float? {
        if (!channel.fitValid || !mv.isFinite()) return null
        return (channel.c0 + channel.c1 * mv + channel.c2 * mv * mv + channel.offsetTrimMm)
            .toFloat().takeIf { it.isFinite() }
    }

    /** span 按 u_mean 处局部斜率折算，止点方向解释也使用同一导数。 */
    fun derivative(mv: Float, channel: DepthNodeChannel): Double = channel.c1 + 2 * channel.c2 * mv
}
