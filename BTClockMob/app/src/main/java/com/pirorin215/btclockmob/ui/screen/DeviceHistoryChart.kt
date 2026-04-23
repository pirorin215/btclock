package com.pirorin215.btclockmob.ui.screen

import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.axisLabelComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.core.chart.DefaultPointConnector
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.marker.Marker
import com.patrykandpatrick.vico.core.component.shape.shader.DynamicShader
import com.patrykandpatrick.vico.core.context.DrawContext
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.pirorin215.btclockmob.battery.BatteryCycleAnalyzer
import com.pirorin215.btclockmob.battery.BatteryPredictor
import com.pirorin215.btclockmob.battery.BatteryPrediction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// パブリックAPIとして公開
typealias BatteryPredictionResult = BatteryPrediction

/**
 * 最新の放電サイクルの経過時間を計算（公開関数）
 */
fun calculateCurrentCycleElapsedTime(history: List<DeviceHistoryEntry>): Long? {
    return BatteryPredictor.calculateCurrentCycleElapsedTime(history)
}

/**
 * 電池切れまでの残り時間を予測（公開関数）
 */
fun predictBatteryLifeFromHistory(history: List<DeviceHistoryEntry>): BatteryPrediction {
    return BatteryPredictor.predictBatteryLifeFromHistory(history)
}

/**
 * カスタムDynamicShader: 縦方向グラデーション
 */
private class VerticalGradientShader(
    private val topColor: Int,
    private val bottomColor: Int
) : DynamicShader {
    override fun provideShader(
        context: DrawContext,
        bounds: RectF
    ): Shader {
        return LinearGradient(
            bounds.left,
            bounds.top,
            bounds.left,
            bounds.bottom,
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP
        )
    }

    override fun provideShader(
        context: DrawContext,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Shader {
        return LinearGradient(
            left,
            top,
            left,
            bottom,
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP
        )
    }
}

/**
 * カスタムマーカー
 */
@Composable
private fun rememberChartMarker(
    history: List<DeviceHistoryEntry>
): Marker {
    val labelBackgroundColor = MaterialTheme.colorScheme.surface
    val labelTextColor = MaterialTheme.colorScheme.onSurface
    val timeFormat = remember { SimpleDateFormat("M/d HH:mm", Locale.getDefault()) }

    val labelComponent = textComponent(
        color = labelTextColor,
        textSize = 12.sp,
        background = shapeComponent(
            shape = Shapes.roundedCornerShape(allPercent = 25),
            color = labelBackgroundColor
        ),
        padding = com.patrykandpatrick.vico.core.dimensions.emptyDimensions()
    )

    return remember(history, labelComponent) {
        object : Marker {
            override fun draw(
                context: DrawContext,
                bounds: RectF,
                markedEntries: List<Marker.EntryModel>
            ): Unit = with(context) {
                val entry = markedEntries.firstOrNull() ?: return
                val index = entry.entry.x.toInt()

                if (index >= 0 && index < history.size) {
                    val dataEntry = history[index]
                    val timeStr = timeFormat.format(Date(dataEntry.timestamp))
                    val valueStr = String.format(Locale.getDefault(), "%.2fV", entry.entry.y)

                    val label = "$timeStr\n$valueStr"

                    labelComponent.drawText(
                        context = this,
                        text = label,
                        textX = entry.location.x,
                        textY = bounds.top - 8f
                    )
                }
            }
        }
    }
}

/**
 * デバイス履歴チャート
 */
@Composable
fun DeviceHistoryChart(
    history: List<DeviceHistoryEntry>,
    onDataPointClicked: ((Long) -> Unit)? = null,
    onTouchEnd: (() -> Unit)? = null
) {
    // クロスヘアラインの位置を保持
    var crosshairPosition by remember { mutableStateOf<Offset?>(null) }

    val dateFormat = remember { SimpleDateFormat("E", Locale.getDefault()) }
    val chartScrollSpec = rememberChartScrollSpec<ChartEntryModel>(isScrollEnabled = false)

    // データ処理
    val dateRanges = remember(history) {
        ChartDataProcessor.calculateDateRanges(history)
    }

    // 日付あたりの平均データ数に基づいてspacingを計算
    val dateSpacing = remember(history, dateRanges) {
        if (dateRanges.isNotEmpty()) {
            (history.size / dateRanges.size).coerceAtLeast(1)
        } else {
            1
        }
    }

    // 各日付範囲の中央に最も近いラベル位置を特定し、その位置で表示する曜日をマップ
    val labelPositionToDate = remember(dateRanges, dateSpacing, history) {
        val result = mutableMapOf<Int, String>()
        for (range in dateRanges) {
            // この日付範囲の中央インデックス
            val centerIndex = (range.startIndex + range.endIndex) / 2
            // 中央に最も近いラベル位置を見つける
            val labelPos = ((centerIndex + dateSpacing / 2) / dateSpacing) * dateSpacing
            // ラベル位置がデータ範囲内にある場合のみ登録
            if (labelPos < history.size) {
                result[labelPos] = dateFormat.format(Date(history[range.startIndex].timestamp))
            }
        }
        result.toMap()
    }

    // ダークモード対応の色設定
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fillColor1 = if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF42A5F5) // 明るい青
    val fillColor2 = if (isDarkTheme) Color(0xFFCE93D8) else Color(0xFFAB47BC) // 紫系
    val axisLabelColor = MaterialTheme.colorScheme.onSurface
    val axisLabel = axisLabelComponent(
        color = axisLabelColor,
        textSize = 14.sp
    )

    if (history.isNotEmpty() && dateRanges.isNotEmpty()) {
        // 実データのみを日ごとに分割
        val realEntriesByDay = remember(history, dateRanges) {
            ChartDataProcessor.splitRealEntriesByDate(history, dateRanges) { it.batteryVoltage }
        }

        // 補間データのみを抽出
        val interpolatedEntries = remember(history) {
            ChartDataProcessor.extractInterpolatedEntries(history) { it.batteryVoltage }
        }

        val allRealEntries = realEntriesByDay.flatten()
        val allEntriesForStats = allRealEntries + interpolatedEntries

        val minVoltage = allEntriesForStats.minOfOrNull { it.y } ?: 0f
        val maxVoltage = allEntriesForStats.maxOfOrNull { it.y } ?: 5f

        // 充電サイクルを取得
        val cycles = remember(history) {
            BatteryCycleAnalyzer.splitIntoBatteryCycles(history)
        }

        // 各サイクルのトレンドラインを計算（放電中のみ表示）
        val trendLinesPerCycle = remember(history, cycles) {
            cycles.mapNotNull { cycle ->
                // サイクル内の実データのみを抽出
                val cycleRealEntries = cycle.entries
                    .mapIndexedNotNull { relativeIndex, entry ->
                        if (!entry.isInterpolated) {
                            entry.batteryVoltage?.let {
                                // 全体のインデックスを使用（グラフ上の正しい位置に表示するため）
                                val globalIndex = cycle.startIndex + relativeIndex
                                entryOf(globalIndex.toFloat(), it)
                            }
                        } else null
                    }

                if (cycleRealEntries.size < 2) return@mapNotNull null

                // 線形回帰を計算
                val regression = BatteryCycleAnalyzer.calculateLinearRegression(cycleRealEntries)
                    ?: return@mapNotNull null

                // 傾きが0以上（右肩上がりまたは水平）の場合は表示しない
                if (regression.slope >= 0) {
                    Log.d("BatteryData", "Cycle ${cycle.startIndex}-${cycle.endIndex}: Positive slope (${regression.slope}), trend line not displayed")
                    return@mapNotNull null
                }

                // トレンドラインエントリを作成
                BatteryCycleAnalyzer.createTrendLineEntries(cycleRealEntries, regression)
            }
        }

        // シリーズの構成: 実データ日ごと + 補間データ + 各サイクルのトレンドライン
        val allSeriesData = remember(realEntriesByDay, interpolatedEntries, trendLinesPerCycle) {
            val series = mutableListOf<List<FloatEntry>>()
            series.addAll(realEntriesByDay)
            if (interpolatedEntries.isNotEmpty()) {
                series.add(interpolatedEntries)
            }
            // 各サイクルのトレンドラインを追加
            series.addAll(trendLinesPerCycle)
            series
        }

        val axisOverrider = remember(minVoltage, maxVoltage) {
            val padding = (maxVoltage - minVoltage) * 0.1f
            AxisValuesOverrider.fixed(
                minY = (minVoltage - padding).coerceAtLeast(0f),
                maxY = maxVoltage + padding
            )
        }

        val modelProducer = remember(allSeriesData) {
            ChartEntryModelProducer(allSeriesData)
        }

        // 実データの日ごとlineSpec（ポイントを強調した棒グラフ風）
        val realDataLineSpecs = realEntriesByDay.mapIndexed { index, _ ->
            val fillColor = if (index % 2 == 0) fillColor1 else fillColor2
            lineSpec(
                lineColor = Color.Transparent,
                lineBackgroundShader = VerticalGradientShader(
                    fillColor.copy(alpha = 0.8f).toArgb(),
                    fillColor.copy(alpha = 0.3f).toArgb()
                ),
                point = shapeComponent(
                    shape = Shapes.pillShape,
                    color = fillColor
                ),
                pointSize = 5.dp
            )
        }

        // 補間データのlineSpec（灰色、ポイントのみ）
        val interpolatedGray = Color.Gray
        val interpolatedLineSpecs = if (interpolatedEntries.isNotEmpty()) {
            listOf(
                lineSpec(
                    lineColor = Color.Transparent,
                    lineBackgroundShader = null,
                    point = shapeComponent(
                        shape = Shapes.pillShape,
                        color = interpolatedGray.copy(alpha = 0.5f)
                    ),
                    pointSize = 1.dp
                )
            )
        } else {
            emptyList()
        }

        // 各サイクルのトレンドライン用のlineSpec（緑と黄色の交互）
        val trendLineColors = listOf(
            Color(0xFF00FF00),  // 緑
            Color(0xFFFFFF00)   // 黄色
        )
        val trendLineSpecs = trendLinesPerCycle.mapIndexed { index, _ ->
            val color = trendLineColors[index % trendLineColors.size]
            lineSpec(
                lineColor = color,
                lineBackgroundShader = null,
                lineThickness = 2.dp,
                pointConnector = DefaultPointConnector(cubicStrength = 0f)
            )
        }

        // すべてのlineSpecを結合: 実データ + 補間 + 各サイクルのトレンド
        val allLineSpecs = realDataLineSpecs + interpolatedLineSpecs + trendLineSpecs

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .then(
                    if (onDataPointClicked != null) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    event.changes.firstOrNull()?.let { change ->
                                        val position = change.position
                                        Log.d("DeviceHistoryChart", "Pointer event at position: $position, pressed: ${change.pressed}")

                                        if (change.pressed) {
                                            // タッチ中はクロスヘアの位置を更新
                                            crosshairPosition = position

                                            // ポインター位置のX座標から対応するデータポイントのインデックスを計算
                                            val chartWidth = size.width.toFloat()
                                            val dataPointCount = history.size

                                            // Y軸ラベルの幅を推定（ピクセル単位）
                                            val yAxisWidth =  80f // 調整可能な値

                                            Log.d("DeviceHistoryChart", "Chart width: $chartWidth, data point count: $dataPointCount, Y-axis offset: $yAxisWidth")
                                            if (dataPointCount > 0) {
                                                // Y軸の幅を除いた実際のプロット領域で計算
                                                val adjustedX = (position.x - yAxisWidth).coerceAtLeast(0f)
                                                val plotWidth = (chartWidth - yAxisWidth).coerceAtLeast(1f)
                                                val ratio = (adjustedX / plotWidth).coerceIn(0f, 1f)
                                                // インデックスを計算（0からdataPointCount-1）
                                                val index = (ratio * dataPointCount).toInt().coerceIn(0, dataPointCount - 1)
                                                // 対応するエントリのタイムスタンプを取得
                                                val timestamp = history[index].timestamp
                                                Log.d("DeviceHistoryChart", "Adjusted X: $adjustedX, plot width: $plotWidth, ratio: $ratio, index: $index, timestamp: $timestamp")
                                                onDataPointClicked(timestamp)
                                            }
                                        } else {
                                            // タッチが終了したらクロスヘアをクリア
                                            crosshairPosition = null
                                            onTouchEnd?.invoke()
                                        }
                                        // イベントを消費しない（チャートのマーカーも動作させるため）
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Chart(
                chart = lineChart(
                    lines = allLineSpecs,
                    axisValuesOverrider = axisOverrider
                ),
                model = modelProducer.getModel() ?: return,
                startAxis = rememberStartAxis(
                    title = "Voltage (V)",
                    label = axisLabel,
                    valueFormatter = { value, _ -> String.format(Locale.getDefault(), "%.2f", value) }
                ),
                bottomAxis = rememberBottomAxis(
                    label = axisLabel,
                    itemPlacer = AxisItemPlacer.Horizontal.default(spacing = dateSpacing),
                    valueFormatter = { value, _ ->
                        labelPositionToDate[value.toInt()] ?: ""
                    }
                ),
                marker = rememberChartMarker(history),
                chartScrollSpec = chartScrollSpec,
                modifier = Modifier.fillMaxSize()
            )

            // クロスヘアラインを描画
            crosshairPosition?.let { position ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    // 破線のエフェクト
                    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                    // 垂直線（X軸）
                    drawLine(
                        color = Color.Red,
                        start = Offset(position.x, 0f),
                        end = Offset(position.x, canvasHeight),
                        strokeWidth = 2f,
                        pathEffect = pathEffect
                    )

                    // 水平線（Y軸）
                    drawLine(
                        color = Color.Red,
                        start = Offset(0f, position.y),
                        end = Offset(canvasWidth, position.y),
                        strokeWidth = 2f,
                        pathEffect = pathEffect
                    )
                }
            }
        }
    }
}
