package com.nx.vfremake.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nx.vfremake.R
import com.nx.vfremake.funClass.ArrayStatsData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * 弹出确认框
 */
@Composable
fun ShowConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit = {},
    showDialog: MutableState<Boolean>,
    showDismiss: Boolean = true
) {
    AlertDialog(
        onDismissRequest = {
            showDialog.value = false
        },
        title = {
            Text(text = title)
        },
        text = {
            Text(text = text)
        },
        confirmButton = {
            Button(onClick = {
                onConfirm() // 执行确认按钮的回调
                showDialog.value = false
            }) {
                Text("确认")
            }
        },
        dismissButton = {
            if (showDismiss) {
                Button(onClick = {
                    showDialog.value = false // 取消操作，关闭对话框
                }) {
                    Text("取消")
                }
            }
        }
    )
}

/**
 * topbar
 */
@Composable
fun MyTopBar(title: String, onClickBack: () -> Unit = {}) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = TextStyle(
                    fontWeight = FontWeight.W600,
                    fontSize = 24.sp,
                    letterSpacing = 6.sp
                ),
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onClickBack
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
        },
        backgroundColor = Color.White
    )
}

/**
 * 轮廓线按钮样式模版
 */
@Composable
fun MyOutlinedButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    cornerRadius: Dp = dimensionResource(id = R.dimen.big_roundedCornerShape),
    content: @Composable () -> Unit
) {
    val outlineColor = LocalContext.current.getColor(R.color.outline_color_gray)
    OutlinedButton(
        modifier = modifier,
        onClick = onClick,
        border = BorderStroke(dimensionResource(id = R.dimen.outline_border), Color(outlineColor)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(cornerRadius)
    ) {
        content()
    }
}

/**
 * TextField自定义，轮廓样式
 */
@Composable
fun MyDiyTextField(
    modifier: Modifier = Modifier,
    label: String? = null,
    value: String,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit = {},
    fontFamily: FontFamily = FontFamily(Font(R.font.youshehaoshenti))
) {
    val outlineColor = Color(LocalContext.current.getColor(R.color.outline_color_gray))
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        BasicTextField(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .height(40.dp)
                .border(
                    border = BorderStroke(
                        dimensionResource(id = R.dimen.outline_border),
                        outlineColor
                    ),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape))
                ),
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontFamily = fontFamily,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
            ),
            readOnly = readOnly,
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = dimensionResource(id = R.dimen.middle_view_padding))
                ) {
                    label?.let {
                        Text(
                            modifier = Modifier.padding(end = 20.dp),
                            text = it,
                            style = TextStyle(
                                fontWeight = FontWeight.W600,
                                color = Color.LightGray,
                                letterSpacing = 2.sp,
                                textAlign = TextAlign.Start
                            ),
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/**
 * 左侧信息栏模版
 */
@Composable
fun TemplatesLeftNavTextValueUint(
    title: String, value: Double, decimalPlaces: Int, unit: String
) {
    val formattedValue = String.format(Locale.CHINA, "%.${decimalPlaces}f", value)
    Text(
        text = title,
        fontSize = 18.sp,
        letterSpacing = 1.sp,
    )
    Text(
        text = "$formattedValue$unit",
        fontSize = 16.sp,
        fontFamily = FontFamily(Font(R.font.youshehaoshenti)),
        modifier = Modifier
            .background(Color.White)
            .padding(2.dp)
    )
}

/**
 * 右侧信息栏模板
 */
@Composable
fun TemplatesRightMsgTextValue(
    title: String,
    values: DoubleArray,
    decimalPlaces: Int,
) {
    val lineHeight = 20
    val lineNum = if (values.size <= 4) values.size else 4
    val valuesList = values.toList()
    val textStyleTitle = TextStyle(
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
    val textStyle = TextStyle(
        fontSize = 16.sp,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Center,
    )
    Text(
        text = title, style = textStyleTitle
    )

    val dantiScrollState = rememberLazyListState()
    LazyColumn(
        state = dantiScrollState,
        modifier = Modifier.height((lineHeight * lineNum + 8).dp)
    ) {
        itemsIndexed(valuesList) { index, value ->
            Text(
                modifier = Modifier
                    .background(Color.White)
                    .padding(
                        start = 4.dp,
                        top = if (index == 0) 2.dp else 0.dp,
                        end = 4.dp,
                        bottom = if (index == values.lastIndex) 2.dp else 0.dp
                    ),
                text = "${index + 1}: ${
                    String.format(Locale.CHINA, "%.${decimalPlaces}f", value)
                }",
                style = textStyle,
                fontFamily = FontFamily(Font(R.font.youshehaoshenti))
            )
        }
    }
}

/**
 * 用户设置界面 标题-文本框-单位
 */
@Composable
fun TemplatesTextFieldUint(
    title: String,
    tooltipText: String,
    value: String,
    uint: String,
    onValueChange: (String) -> Unit = {}
) {
    val textStyle = TextStyle(
        fontSize = 16.sp,
        letterSpacing = 4.sp,
        color = Color.Black,
    )

    var tooltipShown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.middle_view_padding))
    ) {
        Box(modifier = Modifier
            .weight(2f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        tooltipShown = true
                        scope.launch {
                            delay(1500)
                            tooltipShown = false
                        }
                    }
                )
            }
        ) {
            Text(
                text = title, style = textStyle, modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        MyDiyTextField(
            modifier = Modifier.weight(2f),
            value = value,
            onValueChange = onValueChange,
        )
        Text(
            text = uint,
            style = textStyle,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .weight(2f)
        )
        if (tooltipShown) {
            Dialog(onDismissRequest = { tooltipShown = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = dimensionResource(id = R.dimen.small_view_padding) * 2)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(dimensionResource(id = R.dimen.big_roundedCornerShape)))
                            .background(Color.White.copy(alpha = 0.8f))
                            .fillMaxWidth(0.6f)
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = tooltipText,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设置页面行模版
 */
@Composable
fun TemplatesSettingsRow(
    text: String,
    content: @Composable () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val myLightGray = LocalContext.current.getColor(R.color.light_gray)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimensionResource(id = R.dimen.middle_view_padding))
            .background(
                color = Color(myLightGray),
                shape = RoundedCornerShape(dimensionResource(id = R.dimen.big_roundedCornerShape))
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            letterSpacing = 2.sp,
            modifier = Modifier
                .padding(start = dimensionResource(id = R.dimen.middle_view_padding))
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            onLongClick()
                        }
                    )
                }
        )
        content()
    }
}

@Composable
fun TemplatesText_FieldUint(
    title: String = "行距R",
    value: String = "60",
    uint: String = "cm",
    onValueChange: (String) -> Unit = {}
) {
    val textStyle = TextStyle(
        fontSize = 16.sp,
        letterSpacing = 4.sp,
        color = Color.Black,
    )

    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .padding(dimensionResource(id = R.dimen.middle_view_padding))
                .width(150.dp)
                .clip(RoundedCornerShape(dimensionResource(id = R.dimen.big_roundedCornerShape)))
                .border(
                    width = 2.dp,
                    color = Color(context.getColor(R.color.light_gray)),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.big_roundedCornerShape))
                )
                .background(Color.White)
        ) {
            Column(
                modifier = Modifier.padding(bottom = dimensionResource(id = R.dimen.middle_view_padding)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(Color(context.getColor(R.color.light_gray))),
                ) {
                    Text(
                        modifier = Modifier.align(Alignment.Center),
                        text = title,
                        style = textStyle,
                    )
                }
                Row {
                    MyDiyTextField_transparent(
                        modifier = Modifier.weight(5f),
                        value = value,
                        onValueChange = onValueChange,
                    )
                    Text(
                        text = uint,
                        style = textStyle,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .weight(3f)
                    )
                }
            }
        }
    }
}

@Composable
fun MyDiyTextField_transparent(
    modifier: Modifier = Modifier,
    label: String? = null,
    value: String,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit = {},
    fontFamily: FontFamily = FontFamily(Font(R.font.youshehaoshenti))
) {
    val outlineColor = Color.Transparent
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        BasicTextField(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .height(40.dp)
                .border(
                    border = BorderStroke(
                        dimensionResource(id = R.dimen.outline_border),
                        outlineColor
                    ),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape))
                ),
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontFamily = fontFamily,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.W600,
                fontSize = 24.sp,
                letterSpacing = 1.sp,
            ),
            readOnly = readOnly,
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = dimensionResource(id = R.dimen.middle_view_padding))
                ) {
                    label?.let {
                        Text(
                            modifier = Modifier.padding(end = 20.dp),
                            text = it,
                            style = TextStyle(
                                fontWeight = FontWeight.W600,
                                color = Color.LightGray,
                                letterSpacing = 2.sp,
                                textAlign = TextAlign.Start
                            ),
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
fun TemplatesText_Uint(
    value: Double = 9.00,
    decimalPlaces: Int = 2,
    uint: String = "km/h",
) {
    val formattedValue = String.format(Locale.CHINA, "%.${decimalPlaces}f", value)
    Box(
        modifier = Modifier.padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = formattedValue,
                style = TextStyle(
                    fontSize = 24.sp,
                    letterSpacing = 1.sp,
                ),
            )

            Text(
                text = uint,
                style = TextStyle(
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                ),
            )
        }
    }
}

@Composable
fun TemplatesRightMsgText_Value(
    title: String = "施肥量（kg/亩）",
    doubleArray: DoubleArray = doubleArrayOf(10.0, 9.9, 9.5, 10.2),
    setValue: Double? = null,
) {
    val conArrayState = calculateArrayStats(doubleArray)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.Yellow, shape = RoundedCornerShape(8.dp)),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = Color.Black,
            modifier = Modifier.padding(top = 8.dp)
        )

        Text(
            text = conArrayState.meanValue.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            color = Color.Black
        )

        GradientBar(
            modifier = Modifier.padding(horizontal = 16.dp),
            minValue = conArrayState.minValue,
            maxValue = conArrayState.maxValue,
            meanValue = conArrayState.meanValue,
            dataPoints = doubleArray.toList(),
            setValue = setValue
        )

        // Bottom values
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ValueText(text = "↓ ${conArrayState.sizeOfMin}")
            ValueText(text = "${conArrayState.minValue}")
            ValueText(text = "${conArrayState.maxValue}")
            ValueText(text = "${conArrayState.sizeOfMax} ↑")
        }
    }
}

@Composable
fun GradientBar(
    modifier: Modifier = Modifier,
    minValue: Double,
    maxValue: Double,
    meanValue: Double,
    dataPoints: List<Double>,
    setValue: Double?,
) {
    val gradientColors = listOf(
        Color(LocalContext.current.getColor(R.color.start_color_green)),
        Color(LocalContext.current.getColor(R.color.end_color_yellow)),
    )

    fun calculateOffset(minValue: Double, maxValue: Double, value: Double, width: Float): Float {
        val range = maxValue - minValue
        val position = ((value - minValue) / range).toFloat()
        return position * width
    }
    Box(
        modifier = modifier
            .height(16.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
        ) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = gradientColors,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f)
                )
            )
            if (setValue != null) {
                val fertAppliedView = if (setValue > maxValue) maxValue else setValue
                val fertAppliedOffsetX =
                    calculateOffset(minValue, maxValue, fertAppliedView, size.width)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(fertAppliedOffsetX, 0f),
                    size = Size(5.dp.toPx(), size.height)
                )
            }
            dataPoints.forEach { dataPoint ->
                val offsetX = calculateOffset(minValue, maxValue, dataPoint, size.width)
                drawCircle(
                    color = Color.Black,
                    radius = 5.dp.toPx(),
                    center = Offset(offsetX, size.height / 2)
                )
            }

            val meanOffsetX = calculateOffset(minValue, maxValue, meanValue, size.width)
            drawCircle(
                color = Color.Blue,
                radius = 6.dp.toPx(),
                center = Offset(meanOffsetX, size.height / 2)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "$minValue", fontSize = 14.sp, color = Color.Black)
            Text(text = "$maxValue", fontSize = 14.sp, color = Color.Black)
        }
    }
}

@Composable
fun ValueText(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = Color.Black
    )
}

fun calculateArrayStats(values: DoubleArray): ArrayStatsData {
    return if (values.isNotEmpty()) {
        val maxValue = values.maxOrNull() ?: Double.NaN
        val minValue = values.minOrNull() ?: Double.NaN
        val sizeOfMax = values.indexOfFirst { it == maxValue } + 1
        val sizeOfMin = values.indexOfFirst { it == minValue } + 1
        val mean = values.sum() / values.size

        ArrayStatsData(
            maxValue = maxValue,
            minValue = minValue,
            meanValue = mean,
            sizeOfMax = sizeOfMax,
            sizeOfMin = sizeOfMin
        )
    } else {
        ArrayStatsData(
            maxValue = Double.NaN,
            minValue = Double.NaN,
            meanValue = Double.NaN,
            sizeOfMax = -1,
            sizeOfMin = -1
        )
    }
}

@Composable
fun TemplatesNav_LatiLonti(
    value: Double = 0.0,
    value1: Double = 0.0,
    decimalPlaces: Int = 8,
    unit: String = "°"
) {
    val formattedValue = String.format(Locale.CHINA, "%.${decimalPlaces}f", value)
    val formattedValue1 = String.format(Locale.CHINA, "%.${decimalPlaces}f", value1)
    Box(modifier = Modifier.padding(start = 10.dp, top = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = "Location Icon"
            )
            Column(modifier = Modifier.padding(horizontal = 5.dp)) {
                Text(
                    text = "$formattedValue$unit",
                    fontSize = 16.sp,
                    fontFamily = FontFamily(Font(R.font.youshehaoshenti)),
                    modifier = Modifier
                        .background(Color.Transparent)
                        .padding(start = 2.dp)
                )
                Text(
                    text = "$formattedValue1$unit",
                    fontSize = 16.sp,
                    fontFamily = FontFamily(Font(R.font.youshehaoshenti)),
                    modifier = Modifier
                        .background(Color.Transparent)
                        .padding(start = 2.dp)
                )
            }
        }
    }
}

// ==========================================================
// 核心修复图表：加入四周边距裁剪防溢出，动态跳动+处方图对应颜色
// ==========================================================
@Composable
fun BarChartWithTarget(
    barValues: DoubleArray?,
    targetValue: Double?,
    modifier: Modifier = Modifier,
    zoom: Double = 1.0,
) {
    if (barValues == null || targetValue == null) return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {

            val leftMargin = 35.dp.toPx()
            val rightMargin = 15.dp.toPx()
            val topMargin = 20.dp.toPx()
            val bottomMargin = 25.dp.toPx()

            val chartWidth = size.width - leftMargin - rightMargin
            val chartHeight = size.height - topMargin - bottomMargin

            val currentMax = barValues.maxOrNull()?.toFloat() ?: 1f
            val maxValue = maxOf(currentMax, targetValue.toFloat() * 1.2f, 60f)
            val minValue = 0f
            val range = if (maxValue > minValue) (maxValue - minValue) else 1f

            if (barValues.isNotEmpty()) {
                val scalePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    textSize = 12.sp.toPx()
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
                for (i in 0..4) {
                    val scaleValue = minValue + (range * i / 4f)
                    val yPos = topMargin + chartHeight - (chartHeight * i / 4f)

                    drawContext.canvas.nativeCanvas.drawText(
                        String.format("%.0f", scaleValue),
                        leftMargin - 10f,
                        yPos + 5f,
                        scalePaint
                    )
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.3f),
                        start = androidx.compose.ui.geometry.Offset(leftMargin, yPos),
                        end = androidx.compose.ui.geometry.Offset(leftMargin + chartWidth, yPos),
                        strokeWidth = 1f
                    )
                }

                val barWidth = chartWidth / (barValues.size * 2)
                barValues.forEachIndexed { index, value ->
                    var rawBarHeight = ((value.toFloat() - minValue) / range) * chartHeight
                    if (rawBarHeight < 0f) rawBarHeight = 0f
                    if (rawBarHeight > chartHeight) rawBarHeight = chartHeight

                    val barX = leftMargin + barWidth + index * (barWidth * 2)
                    val barY = topMargin + chartHeight - rawBarHeight

                    // 颜色依然严格按照处方图的阶梯逻辑
                    val barColor = when {
                        value <= 0.0  -> androidx.compose.ui.graphics.Color.Gray           // 0 或未作业：灰色
                        value <= 10.0 -> androidx.compose.ui.graphics.Color(0xFFE53935)    // 阶段1 (<=10): 红色
                        value <= 20.0 -> androidx.compose.ui.graphics.Color(0xFFFF9800)    // 阶段2 (<=20): 橙色
                        value <= 30.0 -> androidx.compose.ui.graphics.Color(0xFFFFEB3B)    // 阶段3 (<=30): 黄色
                        value <= 40.0 -> androidx.compose.ui.graphics.Color(0xFF8BC34A)    // 阶段4 (<=40): 浅绿
                        value <= 50.0 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)    // 阶段5 (<=50): 深绿
                        else          -> androidx.compose.ui.graphics.Color(0xFF2196F3)    // 阶段6 (>50) : 蓝色
                    }

                    drawRect(
                        color = barColor.copy(alpha = 0.9f),
                        topLeft = androidx.compose.ui.geometry.Offset(barX, barY),
                        size = androidx.compose.ui.geometry.Size(barWidth, rawBarHeight)
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        "${index + 1}",
                        barX + barWidth / 2f,
                        topMargin + chartHeight + 18.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 14.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }
}

// 以下为原文件自带保留的其他备用图表模板
@Preview
@Composable
fun BarChartWithTarget2(
    modifier: Modifier = Modifier,
    barValues: DoubleArray = doubleArrayOf(9.8, 10.0, 10.5, 11.0),
    zoom: Double = 0.1,
    targetValue: Double = 10.0,
) {
    val maxValue = targetValue * (1.0 + zoom)
    val minValue = targetValue * (1.0 - zoom)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(200.dp), contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val chartHeight = size.height
                val chartWidth = size.width
                drawLine(
                    color = Color.Gray,
                    start = Offset(75f, 0f),
                    end = Offset(75f, chartHeight),
                    strokeWidth = 1.dp.toPx()
                )

                val tickLength = 1.dp.toPx()
                val minorTickLength = 20.dp.toPx()
                val minorStep = chartHeight / 10
                for (i in 0..2) {
                    val y = chartHeight - (i * chartHeight / 2)
                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, y),
                        end = Offset(tickLength, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    if (i < 2) {
                        for (j in 1..4) {
                            val minorY = y - j * minorStep
                            drawLine(
                                color = Color.Gray,
                                start = Offset(75f, minorY),
                                end = Offset(minorTickLength, minorY),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    val value = minValue + i * (maxValue - minValue) / 2.0
                    val label = "%.1f".format(value)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        -20f,
                        y - 4.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 14.sp.toPx()
                            textAlign = android.graphics.Paint.Align.LEFT
                        }
                    )
                }
                val barWidth = chartWidth / (barValues.size * 2)
                barValues.forEachIndexed { index, value ->
                    val barHeight = ((value - minValue) / (maxValue - minValue)) * chartHeight
                    val barX = barWidth + index * (barWidth * 2)
                    val barY = chartHeight - barHeight.toFloat()

                    val barColor = if (100 - value <= 10) {
                        Color.Green
                    } else {
                        Color.Yellow
                    }
                    drawRect(

                        color = barColor.copy(alpha = 0.90f),
                        topLeft = Offset(barX.toFloat(), barY),
                        size = Size(barWidth, barHeight.toFloat())
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        "${index + 1}",
                        barX + barWidth / 2,
                        chartHeight + 16.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 14.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun BarChartWithTarget3(
    modifier: Modifier = Modifier,
    barValues: DoubleArray = doubleArrayOf(9.8, 10.0, 10.5, 9.9),
    targetValue: Double = 10.0,
) {
    val maxValue = targetValue * 1.2
    val minValue = targetValue * 0.8
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()

                .height(220.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {

                val chartHeight = size.height - 20.dp.toPx()
                val chartWidth = size.width

                drawLine(
                    color = Color.Gray,
                    start = Offset(75f, 0f),
                    end = Offset(75f, chartHeight),
                    strokeWidth = 1.dp.toPx()
                )

                val tickLength = 4.dp.toPx()
                val minorTickLength = 5.dp.toPx()
                val minorStep = chartHeight / 10

                for (i in 0..2) {
                    val y = chartHeight - (i * chartHeight / 2)
                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, y),
                        end = Offset(tickLength, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    if (i < 2) {
                        for (j in 1..4) {
                            val minorY = y - j * minorStep
                            drawLine(
                                color = Color.Gray,
                                start = Offset(75f, minorY),
                                end = Offset(minorTickLength, minorY),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    val value = minValue + i * (maxValue - minValue) / 2.0
                    val label = "%.1f".format(value)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        0f,
                        y - 4.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 14.sp.toPx()
                            textAlign = android.graphics.Paint.Align.LEFT
                        }
                    )
                }
                val barWidth = chartWidth / (barValues.size * 2)
                barValues.forEachIndexed { index, value ->
                    val barHeight = ((value - minValue) / (maxValue - minValue)) * chartHeight
                    val barX = barWidth + index * (barWidth * 2)
                    val barY = chartHeight - barHeight.toFloat()

                    val barColor = if (value >= targetValue) {
                        Color.Green
                    } else {
                        Color.Yellow
                    }
                    drawRect(

                        color = barColor.copy(alpha = 0.9f),
                        topLeft = Offset(barX.toFloat(), barY),
                        size = Size(barWidth, barHeight.toFloat())
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        "${index + 1}",
                        barX + barWidth / 2,
                        chartHeight + 16.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 14.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }
}