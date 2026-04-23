package com.pirorin215.btclockmob.ui.screen

import android.location.Location
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.pirorin215.btclockmob.ui.theme.DistanceColor1
import com.pirorin215.btclockmob.ui.theme.DistanceColor2
import com.pirorin215.btclockmob.ui.theme.DistanceColor3
import com.pirorin215.btclockmob.ui.theme.DistanceColor4
import com.pirorin215.btclockmob.ui.theme.DistanceColor5
import com.pirorin215.btclockmob.ui.theme.DistanceColor6
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Column widths for DeviceHistoryScreen
private val DATE_TIME_COLUMN_WIDTH = 100.dp
private val LOCATION_COLUMN_WIDTH = 100.dp
private val BATTERY_COLUMN_WIDTH = 60.dp
private val VOLTAGE_COLUMN_WIDTH = 60.dp

@Composable
fun DeviceHistoryHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start, // Use Start for fixed width columns
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "日時",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(DATE_TIME_COLUMN_WIDTH)
        )
        Text(
            text = "位置情報",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(LOCATION_COLUMN_WIDTH),
            textAlign = TextAlign.Start
        )
        Text(
            text = "残量",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(BATTERY_COLUMN_WIDTH),
            textAlign = TextAlign.Start
        )
        Text(
            text = "電圧",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(VOLTAGE_COLUMN_WIDTH),
            textAlign = TextAlign.End
        )
    }
    Divider()
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // チェックボックス（選択モード時のみ表示）
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null, // クリックは親で処理
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(entry.timestamp))
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start // Use Start for fixed width columns
            ) {
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(DATE_TIME_COLUMN_WIDTH) // Fixed width
                )
                entry.latitude?.let { lat ->
                    Text(
                        text = "%.5f".format(lat),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(LOCATION_COLUMN_WIDTH), // Fixed width
                        textAlign = TextAlign.Start,
                        color = locationTextColor ?: MaterialTheme.colorScheme.onSurface
                    )
                } ?: Box(Modifier.width(LOCATION_COLUMN_WIDTH))

                entry.batteryLevel?.let { level ->
                    Text(
                        text = "${String.format("%.1f", level)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(BATTERY_COLUMN_WIDTH), // Fixed width
                        textAlign = TextAlign.Start
                    )
                } ?: Box(Modifier.width(BATTERY_COLUMN_WIDTH))

                entry.batteryVoltage?.let { voltage ->
                    Text(
                        text = "${String.format("%.2f", voltage)}V",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(VOLTAGE_COLUMN_WIDTH), // Fixed width
                        textAlign = TextAlign.End // Keep End alignment for voltage
                    )
                } ?: Box(Modifier.width(VOLTAGE_COLUMN_WIDTH))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start // Use Start for fixed width columns
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(DATE_TIME_COLUMN_WIDTH) // Fixed width
                )
                entry.longitude?.let { lon ->
                    Text(
                        text = "%.5f".format(lon),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(LOCATION_COLUMN_WIDTH), // Fixed width
                        textAlign = TextAlign.Start,
                        color = locationTextColor ?: MaterialTheme.colorScheme.onSurface
                    )
                } ?: Box(Modifier.width(LOCATION_COLUMN_WIDTH))

                // Placeholder for battery and voltage for the second row, maintaining fixed width
                Box(Modifier.width(BATTERY_COLUMN_WIDTH))
                Box(Modifier.width(VOLTAGE_COLUMN_WIDTH))
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
