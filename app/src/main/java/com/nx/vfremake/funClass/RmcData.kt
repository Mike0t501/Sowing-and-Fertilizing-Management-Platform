package com.nx.vfremake.funClass

/**
 * RMC语句解析存储数据类
 * @note
 */
data class RmcData(
    var timeStamp: String? = "", // RMC语句中的时间戳，格式通常为"hhmmss.ss"
    var status: String? = "", // RMC语句中的有效性标志，"A"表示有效，"V"表示无效
    var latitude: Double = 0.0, // 纬度，以十进制度表示
    var longitude: Double = 0.0, // 经度，以十进制度表示
    var forwardSpeed: Double = 0.0, // 前进速度，单位 节，存储时转换为 km/h
    var forwardSpeedCalculate: Double = 0.0, // 用于计算的前进速度，km/h
    var gnssRawSpeed: Double = 0.0, // RMC原始对地速度(km/h)，始终来自GNSS，不受"固定前进速度"设置覆盖；仅用于停车/低速判定
    var directionRad: Double = 0.0, // 航向角，单位 弧度，用于数学计算
    var directionDeg: Double = 0.0, // 航向角，单位 °
    var date: String? = "", // RMC语句中的日期，格式通常为"ddmmyy"
    var magneticVariation: Double = 0.0, // 磁偏角，表示磁北与真北之间的偏差
    var checksum: String? = "" // 用于验证RMC语句完整性的校验和
) {
    // 滑动窗口速度均值所用环形缓冲区，由 MyGNSSFun 维护，不参与 data class 的 equals/copy
    var speedBuffer: DoubleArray = DoubleArray(0)
    var speedBufHead: Int = 0   // 下一个写入位置
    var speedBufCount: Int = 0  // 已有有效样本数（上限 = 窗口大小）

    companion object {
        // 停车/低速判定阈值(km/h)。低于此值视为停车：冻结航向角、暂停绘制已施肥带。
        // 原因：RTK 航向角(RMC course)在近零速时无定义/乱跳，幅宽横排会绕定位点旋转，
        // 逐帧缝合会把条形带画成向四周发散的"圆疙瘩"。
        const val STANDSTILL_SPEED_KMH = 0.5
    }
}
