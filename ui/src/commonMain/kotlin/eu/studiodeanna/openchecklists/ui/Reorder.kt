package eu.studiodeanna.openchecklists.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drag to reorder in a LazyColumn. The dragged row follows the pointer; once its leading edge passes
 * the middle of another row, [onMove] may move it there in the screen's own draft order, and [onDrop]
 * saves where it ended up. Near the top and bottom the list scrolls, while [canScroll] says the row can
 * go further that way.
 */
@Stable
class ReorderState internal constructor(private val list: LazyListState, private val scope: CoroutineScope) {
    /** The key of the row being dragged, if any. */
    var dragging: Any? by mutableStateOf(null)
        private set

    /** Where the dragged row is drawn: its place in the list when the drag started, plus the drag. */
    private var startOffset = 0f
    private var delta by mutableFloatStateOf(0f)

    /** The dragged row's index when it was last moved, until the list has laid it out at its new place. */
    private var movedFrom = -1
    private var scrolling: Job? = null

    internal var onMove: (from: Any, to: Any) -> Boolean = { _, _ -> false }
    internal var canScroll: (key: Any, direction: Int) -> Boolean = { _, _ -> false }
    internal var onDrop: (key: Any) -> Unit = {}

    private fun info(key: Any): LazyListItemInfo? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** How far row [key] is drawn from its place in the list. */
    fun offsetOf(key: Any): Float {
        if (key != dragging) return 0f
        val slot = info(key)?.offset ?: return 0f
        return startOffset + delta - slot
    }

    /** Rows slide aside while another one is dragged past them, and move instantly otherwise. */
    fun placement(): FiniteAnimationSpec<IntOffset>? =
        if (dragging != null) spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold) else null

    internal fun start(key: Any) {
        val item = info(key) ?: return
        dragging = key
        startOffset = item.offset.toFloat()
        delta = 0f
        movedFrom = -1
        scrolling = scope.launch { autoScroll(key) }
    }

    internal fun dragBy(amount: Float) {
        if (dragging == null) return
        delta += amount
        moveIfPassed()
    }

    internal fun stop() {
        val key = dragging ?: return
        scrolling?.cancel()
        onDrop(key)
        dragging = null
        delta = 0f
    }

    private fun moveIfPassed() {
        val key = dragging ?: return
        val item = info(key) ?: return
        if (item.index == movedFrom) return
        val top = startOffset + delta
        val bottom = top + item.size
        val passed = list.layoutInfo.visibleItemsInfo.filter {
            val middle = it.offset + it.size / 2f
            (it.index > item.index && bottom > middle) || (it.index < item.index && top < middle)
        }
        // The farthest row passed that the screen accepts; others (another section, say) are skipped.
        val target = passed.sortedByDescending { abs(it.index - item.index) }.firstOrNull { onMove(key, it.key) } ?: return
        movedFrom = item.index
        // Keep the list still when the first visible row moves, instead of letting it follow that row.
        if (item.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
    }

    private suspend fun autoScroll(key: Any) {
        while (true) {
            withFrameNanos {}
            val item = info(key) ?: continue
            val layout = list.layoutInfo
            val edge = (layout.viewportEndOffset - layout.viewportStartOffset) / 8f
            val top = startOffset + delta
            val bottom = top + item.size
            val overlap = when {
                top < layout.viewportStartOffset + edge && canScroll(key, -1) -> top - (layout.viewportStartOffset + edge)
                bottom > layout.viewportEndOffset - edge && canScroll(key, 1) -> bottom - (layout.viewportEndOffset - edge)
                else -> 0f
            }
            if (overlap != 0f) {
                list.scrollBy(overlap.coerceIn(-edge, edge) / 4)
                moveIfPassed()
            }
        }
    }
}

@Composable
fun rememberReorderState(
    list: LazyListState,
    onMove: (from: Any, to: Any) -> Boolean,
    canScroll: (key: Any, direction: Int) -> Boolean,
    onDrop: (key: Any) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val state = remember(list, scope) { ReorderState(list, scope) }
    state.onMove = onMove
    state.canScroll = canScroll
    state.onDrop = onDrop
    return state
}

/** A row that can be dragged by its [DragHandle]: while it is, it is drawn raised, under the pointer. */
@Composable
fun LazyItemScope.ReorderableRow(state: ReorderState, key: Any, content: @Composable () -> Unit) {
    val dragging = state.dragging == key
    // Keep the row composed while it is dragged, even if its place in the list scrolls out of view.
    val pinnable = LocalPinnableContainer.current
    DisposableEffect(dragging, pinnable) {
        val pinned = if (dragging) pinnable?.pin() else null
        onDispose { pinned?.release() }
    }
    Column(
        (if (dragging) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, placementSpec = state.placement(), fadeOutSpec = null))
            .graphicsLayer { translationY = state.offsetOf(key) }
            .shadow(if (dragging) 6.dp else 0.dp)
            .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
    ) {
        content()
    }
}

@Composable
fun DragHandle(state: ReorderState, key: Any) {
    Icon(
        DragHandleIcon,
        contentDescription = strings.dragToReorder,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(48.dp).dragHandle(state, key).padding(12.dp),
    )
}

private fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier =
    pointerHoverIcon(PointerIcon.Hand).pointerInput(state, key) {
        try {
            detectVerticalDragGestures(
                onDragStart = { state.start(key) },
                onDragEnd = { state.stop() },
                onDragCancel = { state.stop() },
            ) { change, amount ->
                change.consume()
                state.dragBy(amount)
            }
        } finally {
            // The row left the screen mid-drag: drop it where it is.
            if (state.dragging == key) state.stop()
        }
    }

/** Material's "drag handle" (two bars), which is not among the core icons. */
private val DragHandleIcon: ImageVector = materialIcon(name = "Filled.DragHandle") {
    materialPath {
        moveTo(20f, 9f)
        horizontalLineTo(4f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(16f)
        close()
        moveTo(4f, 15f)
        horizontalLineToRelative(16f)
        verticalLineToRelative(-2f)
        horizontalLineTo(4f)
        close()
    }
}
