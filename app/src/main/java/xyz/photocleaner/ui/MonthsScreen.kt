package xyz.photocleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.photocleaner.vm.AppViewModel
import xyz.photocleaner.vm.MonthsViewModel
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SHORT_MONTH = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())

@Composable
fun MonthsScreen(
    appVm: AppViewModel,
    onMonthPicked: (YearMonth) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    vm: MonthsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val pending by appVm.pendingCount.collectAsState()

    LaunchedEffect(Unit) { vm.sync() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Choose a month", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        state.initialScan ->
                            "Scanning — ${state.totalPhotos} photos so far, safe to close"
                        state.scanning -> "Checking for new photos…"
                        state.counts.isEmpty() -> "No photos found"
                        else -> "${state.totalPhotos} photos across ${state.counts.size} months"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.sync(full = true) }, enabled = !state.scanning) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan")
            }
            BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
                IconButton(onClick = onReview) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Review deletions")
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        if (state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        state.error?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.sync(full = true) }) { Text("Try again") }
                }
            }
        }

        if (state.counts.isEmpty() && state.scanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // Grouped by year so long libraries stay navigable.
            val byYear = state.months.groupBy { it.first.year }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                byYear.forEach { (year, months) ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            "$year",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(months, key = { it.first.toString() }) { (month, count) ->
                        MonthTile(month, count) { onMonthPicked(month) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthTile(month: YearMonth, count: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(104.dp).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                month.format(SHORT_MONTH),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (count == 1) "1 photo" else "$count photos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
