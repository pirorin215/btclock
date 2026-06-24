package com.pirorin215.btclockmob.ui.screen

import android.graphics.Paint
import android.graphics.Rect
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.pirorin215.btclockmob.ui.theme.DistanceColor1
import com.pirorin215.btclockmob.ui.theme.DistanceColor2
import com.pirorin215.btclockmob.ui.theme.DistanceColor3
import com.pirorin215.btclockmob.ui.theme.DistanceColor4
import com.pirorin215.btclockmob.ui.theme.DistanceColor5
import com.pirorin215.btclockmob.ui.theme.DistanceColor6
import androidx.compose.ui.res.stringResource
import com.pirorin215.btclockmob.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

// Column widths for DeviceHistoryScreen
private val DATE_TIME_COLUMN_WIDTH = 110.dp
private val TYPE_COLUMN_WIDTH = 60.dp
private val LOCATION_COLUMN_WIDTH = 120.dp

@Composable
fun ActivityCalendar(
    activeDates: Set<String>, // "yyyy/MM/dd" format
    onDateClick: (String) -> Unit = {}
) {
    var calendar by remember { mutableStateOf(Calendar.getInstance()) }
    val currentMonth = calendar.get(Calendar.MONTH)
    val currentYear = calendar.get(Calendar.YEAR)

    val monthName = SimpleDateFormat("yyyy年 M月", Locale.getDefault()).format(calendar.time)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val newCalendar = calendar.clone() as Calendar
                    newCalendar.add(Calendar.MONTH, -1)
                    calendar = newCalendar
                }) {
                    Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "前月")
                }

                Text(
                    text = monthName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = {
                    val newCalendar = calendar.clone() as Calendar
                    newCalendar.add(Calendar.MONTH, 1)
                    calendar = newCalendar
                }) {
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "次月")
                }
            }

            // 曜日ヘッダー
            Row(modifier = Modifier.fillMaxWidth()) {
                val daysOfWeek = listOf("日", "月", "火", "水", "木", "金", "土")
                daysOfWeek.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = when(day) {
                            "日" -> Color.Red.copy(alpha = 0.7f)
                            "土" -> Color.Blue.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // カレンダーの日付
            val firstDayOfMonth = calendar.clone() as Calendar
            firstDayOfMonth.set(Calendar.DAY_OF_MONTH, 1)
            val startDayOfWeek = firstDayOfMonth.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

            val rows = (daysInMonth + startDayOfWeek + 6) / 7
            val today = Calendar.getInstance()
            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val dayNum = row * 7 + col - startDayOfWeek + 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (dayNum in 1..daysInMonth) {
                                val dateCal = calendar.clone() as Calendar
                                dateCal.set(Calendar.DAY_OF_MONTH, dayNum)
                                val dateStr = sdf.format(dateCal.time)
                                val isActive = activeDates.contains(dateStr)
                                val isToday = dateCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                              dateCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onDateClick(dateStr) }
                                        .padding(2.dp)
                                ) {
                                    Text(
                                        text = dayNum.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center
                                    )
                                    if (isActive) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.Red.copy(alpha = 0.6f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RouteVisualizer(
    entries: List<DeviceHistoryEntry>,
    modifier: Modifier = Modifier,
    centerOffset: Float = 0f,
    initialFontSize: Float = 18f,
    onFontSizeChanged: (Float) -> Unit = {},
    onPointSelected: (DeviceHistoryEntry) -> Unit = {}
) {
    val points = entries.filter { it.latitude != null && it.longitude != null }
        .sortedBy { it.timestamp }
    
    if (points.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("データ不足", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val lats = points.map { it.latitude!! }
    val lons = points.map { it.longitude!! }
    
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()
    
    val latRange = (maxLat - minLat).coerceAtLeast(0.0001)
    val lonRange = (maxLon - minLon).coerceAtLeast(0.0001)
    val latScaleFactor = 1.2 
    val maxZoom = 10000f

    // ズームとパンの状態
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var labelFontSizeSp by remember(initialFontSize) { mutableStateOf(initialFontSize) }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        val oldScale = scale
        val newScale = (scale * zoomChange).coerceIn(1f, maxZoom)
        val scaleChange = newScale / oldScale
        
        scale = newScale
        offset = (offset + offsetChange) * scaleChange
    }

    val density = LocalDensity.current
    val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val textPaint = remember(onSurfaceColor, labelFontSizeSp) {
        Paint().apply {
            color = onSurfaceColor
            textSize = with(density) { labelFontSizeSp.sp.toPx() }
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.WHITE)
        }
    }

    // 各ポイントの画面上の座標を保持（タップ判定用）
    val screenPoints = remember(points, latRange, lonRange, scale, offset) { mutableListOf<Pair<Offset, DeviceHistoryEntry>>() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .transformable(state = state)
                .pointerInput(points, scale, offset) {
                    detectTapGestures { tapOffset ->
                        val center = Offset(size.width / 2f, size.height / 2f + centerOffset)
                        
                        // 最も近いポイントを探す
                        val threshold = 40f
                        var nearestEntry: DeviceHistoryEntry? = null
                        var minDistance = Float.MAX_VALUE

                        for (sp in screenPoints) {
                            val dx = sp.first.x - tapOffset.x
                            val dy = sp.first.y - tapOffset.y
                            val distance = sqrt(dx * dx + dy * dy)
                            if (distance < minDistance) {
                                minDistance = distance
                                nearestEntry = sp.second
                            }
                        }

                        if (nearestEntry != null && minDistance < threshold) {
                            onPointSelected(nearestEntry)
                            // タップした地点を中心に拡大
                            val nearestOffset = screenPoints.find { it.second == nearestEntry }?.first ?: Offset.Zero
                            
                            val oldScale = scale
                            val newScale = if (scale < 10f) 10f else scale
                            val scaleChange = newScale / oldScale
                            
                            offset = (center + offset - nearestOffset) * scaleChange
                            scale = newScale
                        } else {
                            // 地点以外をタップしたらその場所を中心に少し拡大
                            val oldScale = scale
                            val newScale = (scale * 2.0f).coerceAtMost(maxZoom)
                            val scaleChange = newScale / oldScale
                            
                            offset = (center + offset - tapOffset) * scaleChange
                            scale = newScale
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val canvasCenter = Offset(width / 2f, height / 2f + centerOffset)
            
            val baseScale = minOf(width / lonRange.toFloat(), height / (latRange.toFloat() * latScaleFactor.toFloat())) * 0.7f
            
            val xOffsetBase = -20f // 左端に寄せる（右側のラベルスペースを確保するため）
            val yOffsetBase = (height - latRange.toFloat() * latScaleFactor.toFloat() * baseScale) / 2 + centerOffset

            // 経路（パス）の描画
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, canvasCenter)
            }) {
                val path = Path()
                points.forEachIndexed { index, entry ->
                    val x = ((entry.longitude!! - minLon) / lonRange).toFloat() * lonRange.toFloat() * baseScale + xOffsetBase
                    val y = (1.0f - ((entry.latitude!! - minLat) / latRange).toFloat()) * latRange.toFloat() * latScaleFactor.toFloat() * baseScale + yOffsetBase
                    
                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = Color(0xFF2196F3).copy(alpha = 0.6f),
                    style = Stroke(width = 6f / scale)
                )
            }

            // ポイントとラベルの描画
            screenPoints.clear()
            val occupiedRects = mutableListOf<Rect>()
            
            val matrix = android.graphics.Matrix()
            matrix.postScale(scale, scale, width / 2f, height / 2f + centerOffset)
            matrix.postTranslate(offset.x, offset.y)

            points.forEachIndexed { index, entry ->
                val x = ((entry.longitude!! - minLon) / lonRange).toFloat() * lonRange.toFloat() * baseScale + xOffsetBase
                val y = (1.0f - ((entry.latitude!! - minLat) / latRange).toFloat()) * latRange.toFloat() * latScaleFactor.toFloat() * baseScale + yOffsetBase
                
                val pts = floatArrayOf(x, y)
                matrix.mapPoints(pts)
                val screenPos = Offset(pts[0], pts[1])

                // 移動方向の矢印を描画
                if (index > 0) {
                    val prevPos = screenPoints.last().first
                    val dx = screenPos.x - prevPos.x
                    val dy = screenPos.y - prevPos.y
                    val dist = sqrt(dx * dx + dy * dy)
                    
                    if (dist > 80f) { // 矢印が大きくなるため閾値を調整
                        val angle = atan2(dy, dx)
                        val midX = prevPos.x + dx * 0.5f
                        val midY = prevPos.y + dy * 0.5f
                        val arrowSize = 45f
                        val arrowColor = Color(0xFF2196F3).copy(alpha = 0.8f)
                        val arrowStroke = 8f

                        drawLine(
                            color = arrowColor,
                            start = Offset(midX, midY),
                            end = Offset(
                                midX - arrowSize * cos(angle - 0.5f).toFloat(),
                                midY - arrowSize * sin(angle - 0.5f).toFloat()
                            ),
                            strokeWidth = arrowStroke
                        )
                        drawLine(
                            color = arrowColor,
                            start = Offset(midX, midY),
                            end = Offset(
                                midX - arrowSize * cos(angle + 0.5f).toFloat(),
                                midY - arrowSize * sin(angle + 0.5f).toFloat()
                            ),
                            strokeWidth = arrowStroke
                        )
                    }
                }

                screenPoints.add(screenPos to entry)

                // 画面外の描画をスキップ
                if (screenPos.x < -50 || screenPos.x > width + 50 || screenPos.y < -50 || screenPos.y > height + 50) return@forEachIndexed

                val color = when {
                    index == 0 -> Color.Green
                    index == points.size - 1 -> Color.Red
                    entry.isPeriodic -> Color(0xFF2196F3)
                    entry.isDisconnection -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                }
                
                drawCircle(color = Color.White, radius = 8f, center = screenPos)
                drawCircle(color = color, radius = 6f, center = screenPos)

                // 時刻ラベルの描画
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                val addrPart = entry.address?.let { addr ->
                    addr.replace(Regex("^日本、?"), "")
                        .replace(Regex("^(?:東京都|北海道|(?:京都|大阪)府|.{2,3}県)"), "")
                        .split(Regex("[0-9０-９-ー]")).firstOrNull()?.trim()
                } ?: ""
                val labelStr = if (addrPart.isNotEmpty()) "$timeStr $addrPart" else timeStr
                
                val textBounds = Rect()
                textPaint.getTextBounds(labelStr, 0, labelStr.length, textBounds)
                
                var textX = screenPos.x + 12f
                var textY = screenPos.y + (textBounds.height() / 2f)

                // 始点と終点が重なる問題を解決するための位置調整
                if (index == 0) {
                    textY -= 20f // 始点は少し上に配置
                } else if (index == points.size - 1 && screenPoints.isNotEmpty()) {
                    val startPos = screenPoints[0].first
                    val dist = sqrt((screenPos.x - startPos.x).pow(2) + (screenPos.y - startPos.y).pow(2))
                    if (dist < 40f) {
                        textY += 25f // 始点と近い終点は少し下に配置（重なりを避けるため20fより少し多めに）
                    }
                }
                
                val currentRect = Rect(
                    textX.toInt() - 5,
                    (textY - textBounds.height()).toInt() - 5,
                    (textX + textBounds.width()).toInt() + 5,
                    textY.toInt() + 5
                )
                
                val isOverlapping = occupiedRects.any { Rect.intersects(it, currentRect) }
                val isImportant = index == 0 || index == points.size - 1
                
                if (!isOverlapping || isImportant) {
                    drawContext.canvas.nativeCanvas.drawText(labelStr, textX, textY, textPaint)
                    occupiedRects.add(currentRect)
                }
            }

            // 画面中心のインジケーター（十字 + ドット）
            val crosshairSize = 60f
            val crosshairColor = Color.Red.copy(alpha = 0.6f) // より目立つ赤色に変更
            val strokeWidth = 4f
            val crossCenter = Offset(width / 2f, height / 2f + centerOffset)
            
            drawLine(
                color = crosshairColor,
                start = Offset(crossCenter.x - crosshairSize / 2f, crossCenter.y),
                end = Offset(crossCenter.x + crosshairSize / 2f, crossCenter.y),
                strokeWidth = strokeWidth
            )
            drawLine(
                color = crosshairColor,
                start = Offset(crossCenter.x, crossCenter.y - crosshairSize / 2f),
                end = Offset(crossCenter.x, crossCenter.y + crosshairSize / 2f),
                strokeWidth = strokeWidth
            )
            drawCircle(
                color = crosshairColor,
                radius = 6f,
                center = crossCenter
            )
        }

        // 操作用UI
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = 80.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    MaterialTheme.shapes.medium
                )
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // フォントサイズ調整
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                        MaterialTheme.shapes.medium
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(onClick = { 
                    labelFontSizeSp = (labelFontSizeSp - 1f).coerceAtLeast(6f)
                    onFontSizeChanged(labelFontSizeSp)
                }, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Remove, contentDescription = "フォント縮小", modifier = Modifier.size(16.dp))
                    }
                    Text(
                    text = "${labelFontSizeSp.toInt()}sp",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    IconButton(onClick = {
                    labelFontSizeSp = (labelFontSizeSp + 1f).coerceAtMost(24f)
                    onFontSizeChanged(labelFontSizeSp)
                    }, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "フォント拡大", modifier = Modifier.size(16.dp))
                    }
                    }

                    SmallFloatingActionButton(
                    onClick = {
                    val oldScale = scale
                    val newScale = (scale * 2.0f).coerceAtMost(maxZoom)
                    if (oldScale != newScale) {
                        val scaleChange = newScale / oldScale
                        offset *= scaleChange
                        scale = newScale
                    }
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "拡大")
                    }
                    SmallFloatingActionButton(
                    onClick = {
                    val oldScale = scale
                    val newScale = (scale / 2.0f).coerceAtLeast(1f)
                    if (oldScale != newScale) {
                        val scaleChange = newScale / oldScale
                        offset *= scaleChange
                        scale = newScale
                    }
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                    Icon(imageVector = Icons.Default.Remove, contentDescription = "縮小")
                    }
                    SmallFloatingActionButton(
                    onClick = {
                    scale = 1f
                    offset = Offset.Zero
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "リセット")
                    }

        }
    }
}

private fun formatAddressForHeader(address: String?): String {
    return address?.let { addr ->
        addr.replace(Regex("^日本、?"), "")
            .replace(Regex("^(?:東京都|北海道|(?:京都|大阪)府|.{2,3}県)"), "")
            .split(Regex("[0-9０-９-ー]")).firstOrNull()?.trim()
    } ?: ""
}

@Composable
fun DateHeader(
    date: String,
    entries: List<DeviceHistoryEntry> = emptyList(),
    isExpanded: Boolean = true,
    onToggleExpand: () -> Unit = {},
    onShowRoute: () -> Unit = {},
    onDeleteDate: () -> Unit = {},
    canDelete: Boolean = true
) {
    val points = entries.filter { it.latitude != null && it.longitude != null }
        .sortedBy { it.timestamp }

    var startInfo = ""
    var furthestInfo = ""

    if (points.isNotEmpty()) {
        val startPoint = points.first()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val startAddr = formatAddressForHeader(startPoint.address)
        val startTime = timeFormat.format(Date(startPoint.timestamp))
        startInfo = if (startAddr.isNotEmpty()) "$startTime $startAddr〜" else "$startTime〜"

        if (points.size >= 2) {
            var maxDist = -1f
            var furthestPoint = points.first()

            val startLoc = Location("").apply {
                latitude = startPoint.latitude!!
                longitude = startPoint.longitude!!
            }

            points.forEach { point ->
                val currentLoc = Location("").apply {
                    latitude = point.latitude!!
                    longitude = point.longitude!!
                }
                val dist = startLoc.distanceTo(currentLoc)
                if (dist > maxDist) {
                    maxDist = dist
                    furthestPoint = point
                }
            }

            if (maxDist > 100) { // 100m以上離れている場合のみ表示
                val furthestAddr = formatAddressForHeader(furthestPoint.address)
                val furthestTime = timeFormat.format(Date(furthestPoint.timestamp))
                furthestInfo = if (furthestAddr.isNotEmpty()) " → $furthestTime $furthestAddr〜" else " → $furthestTime〜"
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggleExpand
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "閉じる" else "開く",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (startInfo.isNotEmpty()) {
                        Text(
                            text = "$startInfo$furthestInfo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (entries.count { it.latitude != null } >= 2) {
                IconButton(onClick = onShowRoute) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = "ルートを表示",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (canDelete) {
                IconButton(onClick = onDeleteDate) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "この日を削除",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceHistoryHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "日時",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(DATE_TIME_COLUMN_WIDTH)
        )
        Text(
            text = "種別",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(TYPE_COLUMN_WIDTH)
        )
        Text(
            text = "位置情報",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(LOCATION_COLUMN_WIDTH),
            textAlign = TextAlign.Start
        )
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceHistoryCard(
    entry: DeviceHistoryEntry,
    home: Location?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean = false,
    onClick: (DeviceHistoryEntry) -> Unit,
    onLongClick: (DeviceHistoryEntry) -> Unit
) {
    val locationTextColor = if (home != null && entry.latitude != null && entry.longitude != null) {
        val entryLocation = Location("").apply {
            latitude = entry.latitude
            longitude = entry.longitude
        }
        val distance = home.distanceTo(entryLocation) // distance in meters
        getDistanceColor(distance)
    } else {
        null
    }

    val typeText = when {
        isHighlighted && entry.isDisconnection -> stringResource(R.string.last_parked_label)
        entry.isPeriodic -> "記録"
        entry.isDisconnection -> "切断"
        else -> "接続"
    }
    val typeColor = when {
        isHighlighted && entry.isDisconnection -> Color.Red // Special highlight for last parked
        entry.isPeriodic -> Color(0xFF2196F3) // Blue for periodic
        entry.isDisconnection -> Color(0xFFFF9800) // Orange for disconnection
        else -> Color(0xFF4CAF50) // Green for connection
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(entry) },
                onLongClick = { onLongClick(entry) }
            )
            .then(
                if (isHighlighted) {
                    Modifier.border(3.dp, Color.Red)
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // チェックボックス（選択モード時のみ表示）
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null, // クリックは親で処理
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(entry.timestamp))
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(DATE_TIME_COLUMN_WIDTH)
                    )
                    Text(
                        text = typeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = typeColor,
                        modifier = Modifier.width(TYPE_COLUMN_WIDTH)
                    )
                    
                    if (entry.address != null) {
                        Text(
                            text = entry.address,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            color = locationTextColor ?: MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else {
                        entry.latitude?.let { lat ->
                            Text(
                                text = "Lat: %.5f".format(lat),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(LOCATION_COLUMN_WIDTH),
                                textAlign = TextAlign.Start,
                                color = locationTextColor ?: MaterialTheme.colorScheme.onSurface
                            )
                        } ?: Box(Modifier.width(LOCATION_COLUMN_WIDTH))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(DATE_TIME_COLUMN_WIDTH)
                    )
                    // 下段の種別カラムは空白
                    Box(Modifier.width(TYPE_COLUMN_WIDTH))
                    
                    if (entry.address == null) {
                        entry.longitude?.let { lon ->
                            Text(
                                text = "Lon: %.5f".format(lon),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(LOCATION_COLUMN_WIDTH),
                                textAlign = TextAlign.Start,
                                color = locationTextColor ?: MaterialTheme.colorScheme.onSurface
                            )
                        } ?: Box(Modifier.width(LOCATION_COLUMN_WIDTH))
                    }
                }
            }
        }
    }
}

private fun getDistanceColor(distance: Float): Color? {
    return when {
        distance <= 50 -> null // ~50m, use default color
        distance <= 2_000 -> DistanceColor1 // ~2km
        distance <= 5_000 -> DistanceColor2 // ~5km
        distance <= 10_000 -> DistanceColor3 // ~10km
        distance <= 50_000 -> DistanceColor4 // ~50km
        distance <= 100_000 -> DistanceColor5 // ~100km
        else -> DistanceColor6 // >100km
    }
}
