package com.example.openrouteragent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private enum class UsageView(val label: String) {
    Trend("Trend"), Calendar("Calendar"), Rankings("Rankings"), Records("Records")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsageExplorer(rows: List<UsageActivity>, today: LocalDate) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    var metric by rememberSaveable { mutableStateOf(UsageMetric.Cost) }
    var view by rememberSaveable { mutableStateOf(UsageView.Trend) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var recordDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val models = remember(rows) { rows.map { it.model }.distinct().sorted() }
    val providers = remember(rows) { rows.map { it.provider }.distinct().sorted() }
    val activeModel = model?.takeIf { it in models }
    val activeProvider = provider?.takeIf { it in providers }
    val filtered = remember(rows, today, days, activeModel, activeProvider) {
        filterUsageActivity(rows, today, days, activeModel, activeProvider)
    }
    val daily = remember(filtered, today, days) { dailyUsage(filtered, today, days) }
    val total = remember(filtered) { filtered.usageTotals() }
    val selectedIndex = daily.indexOfFirst { it.first.toEpochDay() == selectedDay }
        .takeIf { it >= 0 } ?: daily.indexOfLast { it.second.requests > 0 }.takeIf { it >= 0 } ?: daily.lastIndex
    fun selectDay(index: Int) { selectedDay = daily[index].first.toEpochDay() }

    OutlinedSection(title = "Activity explorer", highlighted = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { length ->
                UsageChip("$length days", days == length) { days = length }
            }
        }
        Text("${usageDate(daily.first().first)} – ${usageDate(daily.last().first)} · UTC",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageFilter("All models", activeModel, models, onSelect = { model = it })
                UsageFilter("All providers", activeProvider, providers, onSelect = { provider = it })
            }
        }
        UsageTotalsGrid(total)
    }
    if (filtered.isEmpty()) {
        OutlinedSection(title = "No activity in this view") {
            Text(if (rows.isEmpty()) "OpenRouter returned no activity for the last 30 completed UTC days."
                else "Choose a longer date range or clear the model and provider filters.")
            if (activeModel != null || activeProvider != null) {
                TextButton(onClick = { model = null; provider = null }) { Text("Clear filters") }
            }
        }
        return
    }
    OutlinedSection(title = "Display") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageView.entries.forEach { item ->
                UsageChip(item.label, view == item) {
                    view = item
                    recordDay = null
                }
            }
        }
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageMetric.entries.forEach { item -> UsageChip(item.label, metric == item) { metric = item } }
        }
    }
    when (view) {
        UsageView.Trend, UsageView.Calendar -> OutlinedSection(
            title = if (view == UsageView.Trend) "Daily ${metric.label.lowercase()}" else "Activity calendar",
        ) {
            if (view == UsageView.Trend) {
                val peak = daily.maxBy { metric.value(it.second) }.second
                Text("Peak · ${metric.format(peak)}", style = MaterialTheme.typography.labelLarge)
                UsageTrend(daily.map { metric.value(it.second) }, selectedIndex, ::selectDay)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shortUsageDate(daily.first().first), style = MaterialTheme.typography.bodySmall)
                    Text(shortUsageDate(daily.last().first), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                UsageCalendar(daily, metric, selectedIndex, ::selectDay)
            }
            DayUsageDetails(daily, selectedIndex, ::selectDay)
            val day = daily[selectedIndex].first
            if (filtered.any { it.date == day }) {
                TextButton(onClick = { recordDay = day.toEpochDay(); view = UsageView.Records }) {
                    Text("View this day’s records")
                }
            }
        }
        UsageView.Rankings -> UsageRankings(filtered, metric, onModel = { model = it }, onProvider = { provider = it })
        UsageView.Records -> {
            val date = recordDay?.let(LocalDate::ofEpochDay)
            val records = remember(filtered, date) { if (date == null) filtered else filtered.filter { it.date == date } }
            key(days, activeModel, activeProvider, date) {
                UsageRecords(records, metric, date, onClearDay = { recordDay = null })
            }
        }
    }
}

@Composable
private fun UsageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
private fun UsageFilter(
    allLabel: String,
    selected: String?,
    choices: List<String>,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(selected ?: allLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 4.dp))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { open = false; onSelect(null) })
            choices.forEach { label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(label) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageTotalsGrid(totals: UsageTotals, detailed: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageValue("OpenRouter spend", usageMoney(totals.cost))
        UsageValue("Requests", usageCount(totals.requests))
        UsageValue("Tokens", usageCount(totals.tokens))
        if (detailed) {
            UsageValue("Input", usageCount(totals.prompt))
            UsageValue("Output", usageCount(totals.completion))
            UsageValue("Cached input", usageCount(totals.cached))
            UsageValue("Reasoning", usageCount(totals.reasoning))
            UsageValue("External spend (BYOK)", usageMoney(totals.byok))
        }
    }
}

@Composable
private fun UsageTrend(values: List<Double>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val select by rememberUpdatedState(onSelect)
    Canvas(
        Modifier.fillMaxWidth().height(148.dp).pointerInput(values.size, rtl) {
            detectTapGestures { tap ->
                val inset = 8.dp.toPx()
                val fraction = ((tap.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                select(((if (rtl) 1f - fraction else fraction) * values.lastIndex).roundToInt())
            }
        },
    ) {
        val peak = values.max().takeIf { it > 0 } ?: 1.0
        val inset = 8.dp.toPx()
        val bottom = size.height - inset
        repeat(4) { row ->
            val y = inset + (bottom - inset) * row / 3
            drawLine(colors.outlineVariant, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
        }
        val points = values.mapIndexed { index, value ->
            val progress = (if (rtl) values.lastIndex - index else index).toFloat() / values.lastIndex
            Offset(inset + progress * (size.width - 2 * inset), bottom - (value / peak).toFloat() * (bottom - inset))
        }
        val line = Path().apply {
            val first = points.first()
            moveTo(first.x, first.y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
        }
        val area = Path().apply {
            moveTo(points.first().x, bottom)
            for (point in points) lineTo(point.x, point.y)
            lineTo(points.last().x, bottom)
            lineTo(points.first().x, bottom)
            close()
        }
        drawPath(area, colors.primary.copy(alpha = 0.16f))
        drawPath(line, colors.primary, style = Stroke(2.dp.toPx()))
        val point = points[selected]
        drawLine(colors.primary.copy(alpha = 0.55f), Offset(point.x, inset), Offset(point.x, bottom), 1.dp.toPx())
        drawCircle(colors.surface, radius = 6.dp.toPx(), center = point)
        drawCircle(colors.primary, radius = 4.dp.toPx(), center = point)
    }
}

@Composable
private fun UsageCalendar(
    daily: List<Pair<LocalDate, UsageTotals>>,
    metric: UsageMetric,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val peak = daily.maxOf { metric.value(it.second) }.takeIf { it > 0 } ?: 1.0
    val first = daily.first().first
    val calendarStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val totalCells = first.dayOfWeek.value - 1 + daily.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.values().forEach { day ->
            Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        }
    }
    repeat((totalCells + 6) / 7) { week ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { weekday ->
                val date = calendarStart.plusDays((week * 7 + weekday).toLong())
                val index = (week * 7 + weekday) - (first.dayOfWeek.value - 1)
                if (index in daily.indices) {
                    val totals = daily[index].second
                    val fraction = (metric.value(totals) / peak).toFloat().coerceIn(0f, 1f)
                    val shade = if (fraction > 0) {
                        lerp(colors.surfaceContainerHigh, colors.primary, 0.18f + 0.82f * fraction)
                    } else colors.surfaceContainerHigh
                    val foreground = if (shade.luminance() > 0.179f)
                        Color.Black else Color.White
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .selectable(selected = selected == index, onClick = { onSelect(index) })
                            .semantics { contentDescription = "${usageDate(date)}, ${metric.format(totals)} ${metric.label.lowercase()}" },
                        shape = RoundedCornerShape(10.dp),
                        color = shade,
                        contentColor = foreground,
                        border = if (selected == index) BorderStroke(2.dp, foreground) else null,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    Text("Darker or brighter cells show more ${metric.label.lowercase()}.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

@Composable
private fun DayUsageDetails(
    daily: List<Pair<LocalDate, UsageTotals>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val (date, totals) = daily[selected]
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(usageDate(date), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onSelect(selected - 1) }, enabled = selected > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day")
        }
        IconButton(onClick = { onSelect(selected + 1) }, enabled = selected < daily.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
        }
    }
    UsageTotalsGrid(totals, detailed = true)
}

@Composable
private fun UsageRankings(
    rows: List<UsageActivity>,
    metric: UsageMetric,
    onModel: (String) -> Unit,
    onProvider: (String) -> Unit,
) {
    var byModel by rememberSaveable { mutableStateOf(true) }
    var shown by rememberSaveable(byModel) { mutableIntStateOf(8) }
    val ranking = remember(rows, metric, byModel) {
        usageRanking(rows, metric) { if (byModel) it.model else it.provider }
    }
    val total = remember(rows) { rows.usageTotals() }
    val maxValue = metric.value(ranking.first().second)
    OutlinedSection(title = "${metric.label} by ${if (byModel) "model" else "provider"}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageChip("Models", byModel) { byModel = true }
            UsageChip("Providers", !byModel) { byModel = false }
        }
        Text("Tap a row to filter all displays.", style = MaterialTheme.typography.bodySmall)
        ranking.take(shown).forEach { (label, totals) ->
            val fraction = if (maxValue > 0) (metric.value(totals) / maxValue).toFloat() else 0f
            val share = metric.value(total).takeIf { it > 0 }?.let {
                NumberFormat.getPercentInstance().format(metric.value(totals) / it)
            }
            Surface(
                onClick = { if (byModel) onModel(label) else onProvider(label) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(metric.format(totals), share).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (ranking.size > shown) TextButton(onClick = { shown += 8 }) { Text("Show more") }
    }
}

@Composable
private fun UsageRecords(
    rows: List<UsageActivity>,
    metric: UsageMetric,
    date: LocalDate?,
    onClearDay: () -> Unit,
) {
    var newest by rememberSaveable { mutableStateOf(true) }
    var shown by rememberSaveable { mutableIntStateOf(10) }
    var expanded by remember(rows, metric) { mutableIntStateOf(-1) }
    val sorted = remember(rows, newest, metric) {
        if (newest) rows.sortedWith(compareByDescending<UsageActivity> { it.date }.thenBy { it.model }.thenBy { it.provider })
        else rows.sortedWith(compareByDescending<UsageActivity> { metric.value(it.totals) }.thenByDescending { it.date })
    }
    OutlinedSection(title = "Endpoint records") {
        if (date != null) {
            Text(usageDate(date), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClearDay) { Text("Show all days") }
        }
        if (rows.isEmpty()) {
            Text("No records match this day and these filters.")
            return@OutlinedSection
        }
        Text("${usageCount(rows.size.toLong())} daily records · tap a row for details", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageChip("Newest", newest) { newest = true; expanded = -1 }
            UsageChip("Highest ${metric.label.lowercase()}", !newest) { newest = false; expanded = -1 }
        }
        sorted.take(shown).forEachIndexed { index, row ->
            Surface(
                onClick = { expanded = if (expanded == index) -1 else index },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.model, style = MaterialTheme.typography.bodyMedium)
                            Text("${shortUsageDate(row.date)} · ${row.provider}", style = MaterialTheme.typography.bodySmall)
                            Text("${metric.format(row.totals)} · ${metric.label}", style = MaterialTheme.typography.labelLarge)
                        }
                        Icon(if (expanded == index) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded == index) "Collapse details" else "Expand details")
                    }
                    if (expanded == index) {
                        HorizontalDivider()
                        UsageTotalsGrid(row.totals, detailed = true)
                        Text("Version · ${row.version}", style = MaterialTheme.typography.bodySmall)
                        CopyableText("Endpoint · ${row.endpoint}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (sorted.size > shown) TextButton(onClick = { shown += 10 }) { Text("Show more records") }
    }
}

private fun usageDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
private fun shortUsageDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d"))
