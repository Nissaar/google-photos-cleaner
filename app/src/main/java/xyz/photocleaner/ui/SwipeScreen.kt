package xyz.photocleaner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.ui.theme.VerdictColors
import xyz.photocleaner.vm.SwipeViewModel
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.getDefault())

@Composable
fun SwipeScreen(
    month: YearMonth,
    onBack: () -> Unit,
    onReview: () -> Unit,
    vm: SwipeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(month) { vm.load(month) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ---- Header -------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(month.format(MONTH_FMT), style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        state.loading -> "Loading ${state.loadedCount} photos…"
                        state.items.isEmpty() -> "Nothing to review"
                        else -> "${state.index.coerceAtMost(state.items.size)} of ${state.items.size}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.deletedThisSession > 0) {
                TextButton(onClick = onReview) { Text("Review ${state.deletedThisSession}") }
            }
        }

        if (state.items.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading -> LoadingBlock(state.loadedCount)
                state.error != null -> MessageBlock(state.error!!, "Go back", onBack)
                state.isEmpty -> MessageBlock(
                    "No photos left to review in ${month.format(MONTH_FMT)}.",
                    "Pick another month",
                    onBack,
                )
                state.finished -> MessageBlock(
                    "Done with ${month.format(MONTH_FMT)}.\n" +
                        "${state.keptThisSession} kept · ${state.deletedThisSession} marked for deletion.",
                    if (state.deletedThisSession > 0) "Review deletions" else "Pick another month",
                    if (state.deletedThisSession > 0) onReview else onBack,
                )
                else -> PhotoDeck(
                    current = state.current,
                    next = state.next,
                    onKeep = vm::keep,
                    onDelete = vm::delete,
                )
            }
        }

        // ---- Actions ------------------------------------------------------
        if (state.current != null) {
            ActionBar(
                canUndo = state.history.isNotEmpty(),
                onDelete = vm::delete,
                onUndo = vm::undo,
                onKeep = vm::keep,
            )
        }
    }
}

/**
 * The card stack. The next photo is rendered underneath so the transition never
 * shows an empty frame while the following image decodes.
 */
@Composable
private fun PhotoDeck(
    current: MediaItem?,
    next: MediaItem?,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    if (current == null) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val dismissThreshold = screenWidthPx * 0.28f

    // Keyed on the item so a new photo always starts centred.
    val offsetX = remember(current.dedupKey) { Animatable(0f) }

    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {

        next?.let { PhotoCard(it, Modifier.graphicsLayer { scaleX = 0.94f; scaleY = 0.94f }) }

        val progress = (offsetX.value / dismissThreshold).coerceIn(-1f, 1f)

        Box(
            Modifier
                .graphicsLayer {
                    translationX = offsetX.value
                    rotationZ = progress * 8f
                }
                .pointerInput(current.dedupKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val settled = offsetX.value
                            scope.launch {
                                if (abs(settled) > dismissThreshold) {
                                    val target = if (settled > 0) screenWidthPx * 1.6f
                                    else -screenWidthPx * 1.6f
                                    offsetX.animateTo(target, tween(220))
                                    if (settled > 0) onKeep() else onDelete()
                                } else {
                                    offsetX.animateTo(0f, tween(200))
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                    )
                },
        ) {
            PhotoCard(current, Modifier)
            VerdictOverlay(progress)
        }
    }
}

@Composable
private fun PhotoCard(item: MediaItem, modifier: Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (item.width > 0 && item.height > 0) {
                            (item.width.toFloat() / item.height).coerceIn(0.6f, 1.4f)
                        } else 0.8f,
                    )
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                // Surfaced rather than swallowed: a blank card gives nothing to debug.
                var loadError by remember(item.dedupKey) { mutableStateOf<String?>(null) }
                var loading by remember(item.dedupKey) { mutableStateOf(true) }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.previewUrl(authUser = xyz.photocleaner.Graph.session.authUser))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { loading = false; loadError = null },
                    onLoading = { loading = true },
                    onError = { state ->
                        loading = false
                        loadError = state.result.throwable.message ?: "Image failed to load"
                    },
                )

                if (loading && loadError == null) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                }

                loadError?.let { message ->
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Could not load this photo",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            color = Color(0xFFFF8A80),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (item.isVideo) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = "Video",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Instant.ofEpochMilli(item.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .format(DAY_FMT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                item.durationMs?.let { ms ->
                    Text(
                        "%d:%02d".format(ms / 60000, (ms / 1000) % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** KEEP / DELETE stamps that fade in as the card is dragged. */
@Composable
private fun BoxScope.VerdictOverlay(progress: Float) {
    if (abs(progress) < 0.05f) return
    val keeping = progress > 0
    val color = if (keeping) VerdictColors.keep else VerdictColors.delete

    Box(
        Modifier
            .align(if (keeping) Alignment.TopStart else Alignment.TopEnd)
            .padding(24.dp)
            .alpha(abs(progress))
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (keeping) "KEEP" else "DELETE",
            color = color,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ActionBar(
    canUndo: Boolean,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleAction(Icons.Default.Close, "Delete", VerdictColors.delete, 64.dp, onDelete)
        CircleAction(
            Icons.AutoMirrored.Filled.Undo,
            "Undo",
            MaterialTheme.colorScheme.onSurfaceVariant,
            48.dp,
            onUndo,
            enabled = canUndo,
        )
        CircleAction(Icons.Default.Check, "Keep", VerdictColors.keep, 64.dp, onKeep)
    }
}

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            2.dp,
            if (enabled) tint.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
        ),
        shadowElevation = 4.dp,
        modifier = Modifier.size(size).alpha(if (enabled) 1f else 0.4f),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

@Composable
private fun LoadingBlock(count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            if (count > 0) "Found $count photos…" else "Reading this month…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBlock(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
