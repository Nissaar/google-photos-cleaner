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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Check
import xyz.photocleaner.vm.MonthEntry
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

    // Long-pressing a month you have started offers to forget those verdicts.
    var resetTarget by remember { mutableStateOf<MonthEntry?>(null) }

    LaunchedEffect(Unit) { vm.sync() }

    resetTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("Review ${entry.month.format(SHORT_MONTH)} ${entry.month.year} again?") },
            text = {
                Text(
                    "Forgets the ${entry.reviewed} decisions you made for this month so " +
                        "its photos come back up for review.\n\n" +
                        "Photos you already deleted stay deleted — this only affects what " +
                        "is stored on this phone, never your Google Photos library.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetMonth(entry.month)
                    resetTarget = null
                }) { Text("Review again") }
            },
            dismissButton = {
                TextButton(onClick = { resetTarget = null }) { Text("Cancel") }
            },
        )
    }

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
            val byYear = state.months.groupBy { it.month.year }
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
                    items(months, key = { it.month.toString() }) { entry ->
                        MonthTile(
                            entry = entry,
                            onClick = { onMonthPicked(entry.month) },
                            onLongClick = { if (entry.started) resetTarget = entry },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthTile(
    entry: MonthEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // A finished month recedes rather than disappearing: it stays reachable, and the
    // long-press that resets it has to have something to land on.
    val done = entry.fullyReviewed
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (done) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .size(104.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                entry.month.format(SHORT_MONTH),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = if (done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    done -> "All reviewed"
                    entry.started -> "${entry.remaining} left of ${entry.total}"
                    entry.total == 1 -> "1 photo"
                    else -> "${entry.total} photos"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
