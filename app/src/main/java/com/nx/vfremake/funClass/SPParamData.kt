package com.nx.vfremake.funClass

import com.nx.vfremake.TOTAL_NODE_COUNT

data class SPParamData(
    var rowNumber: Int = 8,
    var rowSize: Double = 0.0,
    var gnssDistanceVertical: Double = 0.0,
    var gnssDistanceHorizontal: Double = 0.0,
    var lagTime: Double = 0.0,
    var fertApplied: Double = -1.0,
    var forwardSpeed: Double = -1.0,
    var seedTransmissionRatio: Double = 1.0,
    var seedHoleCount: Int = 12,
    var seedPlantSpacing: Double = 0.20,
    var activeMotors: BooleanArray = BooleanArray(TOTAL_NODE_COUNT) { true }
)
