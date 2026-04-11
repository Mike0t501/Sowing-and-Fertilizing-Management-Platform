package com.nx.vfremake.funClass

/**
 * 用户设置界面参数数据类
 * @note   和初始化资源值是一一对应的
 */
data class SPParamData(
    var rowNumber: Int = 0, // 行数 行
    var rowSize: Double = 0.0, // 行距，用户设置处单位为 cm，初始化时会转换为 m
    var gnssDistanceVertical: Double = 0.0, // GNSS距离所有排肥单体所在中心连线的垂直距离 cm -> m
    var gnssDistanceHorizontal: Double = 0.0, // GNSS距离对称线的水平距离 cm -> m
    var lagTime: Double = 0.0, // 滞后时间 s
    var fertApplied: Double = -1.0, // 应施肥量 -1时为根据当前位置查询处方图，否则用户自定 kg/亩
    var forwardSpeed: Double = -1.0, // 前进速度 -1时为根据GNSS信息，否则用户自定 km/h

    // --- 新增：保存单体电机的启停状态，默认全开 ---
    var activeMotors: BooleanArray = BooleanArray(8) { true }
)