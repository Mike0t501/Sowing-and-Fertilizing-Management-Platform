package com.nx.vfremake.funClass

import java.text.DecimalFormat

data class ArrayStatsData(
    var maxValue: Double,
    var minValue: Double,
    var meanValue: Double,
    var sizeOfMax: Int,
    var sizeOfMin: Int,
) {
    init {
        val decimalFormat = DecimalFormat("#.00")
        maxValue = decimalFormat.format(maxValue).toDouble()
        minValue = decimalFormat.format(minValue).toDouble()
        meanValue = decimalFormat.format(meanValue).toDouble()
    }
}
