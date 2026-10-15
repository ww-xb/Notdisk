package com.notdisk.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdisk.core.designsystem.theme.Dimens

/**
 * Placeholder rows shaped like the real file list, pulsing while a listing is in flight.
 *
 * ### Why this exists next to [LoadingState]
 * [LoadingState] answers "is anything happening?" with a centred spinner, which is the right
 * treatment for a screen whose *shape* is still unknown (the accounts screen, a one-shot read). A
 * file browser has a different problem: the shape is completely known in advance — a folder is a
 * list of rows, or a grid of tiles — so a spinner throws that knowledge away and replaces it with
 * a mostly empty screen. The result is the two-step paint users describe as "生硬": a blank frame,
 * then the whole list appearing at once, with the layout jumping when it lands.
 *
 * Rendering the *skeleton* of the content instead means:
 *  - something is on screen from the first frame, so the tab never looks dead;
 *  - the layout does not move when the real rows replace the placeholders, because the placeholder
 *    has the same height as the row it stands for;
 *  - the pulse is driven by the draw phase only (see [SkeletonBox]), so a 60 fps shimmer costs no
 *    recomposition of the list.
 *
 * ### Why the shimmer is one shared clock
 * Every placeholder reads the same [State] produced by a single `rememberInfiniteTransition` in the
 * container. Giving each box its own transition would start them at slightly different times, which
 * reads as noise rather than as one surface being refreshed.
 */
@Composable
fun FileListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = DEFAULT_SKELETON_ROWS,
    label: String? = null,
) {
    val progress = rememberShimmerProgress()
    Column(
        modifier = modifier
            .fillMaxWidth()
            // One node for a screen reader instead of dozens of anonymous boxes: the placeholders
            // are decoration, and announcing them would bury the one fact that matters.
            .semantics { contentDescription = SKELETON_CONTENT_DESCRIPTION },
    ) {
        SkeletonCaption(label)
        repeat(rows.coerceAtLeast(1)) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The same 64dp floor as `CloudFileListTile`, so the list does not jump when
                    // the real rows arrive.
                    .heightIn(min = SKELETON_ROW_MIN_HEIGHT)
                    .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
            ) {
                SkeletonBox(
                    progress = progress,
                    modifier = Modifier.size(Dimens.ThumbnailSmall),
                    shape = RoundedCornerShape(Dimens.XSmall),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Small),
                ) {
                    // Alternating widths: a column of identical bars reads as a rendering glitch,
                    // whereas varied lengths read as "names of different lengths".
                    SkeletonBox(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.62f else 0.48f)
                            .height(SKELETON_TITLE_HEIGHT),
                    )
                    SkeletonBox(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth(0.32f)
                            .height(SKELETON_SUBTITLE_HEIGHT),
                    )
                }
            }
        }
    }
}

/**
 * Placeholder tiles shaped like the grid layout's cells.
 *
 * Kept separate from [FileListSkeleton] rather than parameterised by "list or grid" because the two
 * layouts have genuinely different geometry (a full-width row versus a 3-column square), and a
 * component that had to branch internally would be harder to keep in step with the real tiles.
 */
@Composable
fun FileGridSkeleton(
    modifier: Modifier = Modifier,
    cells: Int = DEFAULT_SKELETON_CELLS,
    label: String? = null,
) {
    val progress = rememberShimmerProgress()
    val rows = (cells.coerceAtLeast(1) + SKELETON_GRID_COLUMNS - 1) / SKELETON_GRID_COLUMNS

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.Medium)
            .semantics { contentDescription = SKELETON_CONTENT_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        SkeletonCaption(label)
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Medium)) {
                repeat(SKELETON_GRID_COLUMNS) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Small),
                    ) {
                        SkeletonBox(
                            progress = progress,
                            // Square, exactly like `CloudFileGridTile`'s own thumbnail box. A fixed
                            // height here would be wrong at every width: the real tile is
                            // `aspectRatio(1f)`, so the list would still jump on arrival — which is
                            // the one thing a skeleton must not do.
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(Dimens.Small),
                        )
                        SkeletonBox(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(SKELETON_SUBTITLE_HEIGHT),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one animation clock every placeholder on screen shares.
 *
 * Deliberately returned as a [State] rather than as a `Float`: a caller that read `.value` during
 * composition would recompose the whole skeleton on every animation frame. Reading it inside a
 * `drawBehind` lambda instead (see [SkeletonBox]) keeps the per-frame work in the draw phase.
 */
@Composable
private fun rememberShimmerProgress(): State<Float> =
    rememberInfiniteTransition(label = SHIMMER_LABEL).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_PERIOD_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = SHIMMER_PROGRESS_LABEL,
    )

/**
 * The optional words above the placeholders.
 *
 * The skeleton alone says "something is coming" but not *what*, and this app's first load can be a
 * multi-second provider round trip. Naming the folder being read ("正在读取「我的文件」…") is the
 * difference between patience and a force-quit — the same reason [LoadingState] takes a label.
 */
@Composable
private fun SkeletonCaption(label: String?) {
    if (label.isNullOrBlank()) return
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Large, vertical = Dimens.Medium),
    )
}


/**
 * One shimmering placeholder block.
 *
 * The gradient's position is computed *inside* `drawBehind`, from the animated state and the node's
 * own measured size. That is what makes the highlight travel the exact width of each block — and,
 * more importantly, it is what keeps the animation off the composition: `progress` is read in the
 * draw phase, so a frame invalidates drawing only.
 *
 * @param progress the shared shimmer clock, read in the draw phase and never during composition.
 */
@Composable
private fun SkeletonBox(
    progress: State<Float>,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(Dimens.XSmall),
) {
    // The two tones are alphas over the existing surface rather than fixed greys, so the skeleton
    // reads correctly in both light and dark themes and under dynamic colour.
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = SKELETON_BASE_ALPHA)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = SKELETON_HIGHLIGHT_ALPHA)

    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                val band = size.width * SHIMMER_BAND_FRACTION
                val travel = size.width + band
                val start = -band + travel * progress.value
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(start, 0f),
                        end = Offset(start + band, size.height),
                    ),
                )
            },
    )
}

/** Rows drawn by default: enough to fill a phone screen without composing an unbounded list. */
private const val DEFAULT_SKELETON_ROWS = 10

/** Grid placeholders drawn by default: four full rows of three, which fills a phone viewport. */
private const val DEFAULT_SKELETON_CELLS = 12

/** The grid skeleton mirrors the real grid's minimum column count on a phone. */
private const val SKELETON_GRID_COLUMNS = 3

private val SKELETON_ROW_MIN_HEIGHT: Dp = 64.dp
private val SKELETON_TITLE_HEIGHT: Dp = 14.dp
private val SKELETON_SUBTITLE_HEIGHT: Dp = 10.dp

private const val SHIMMER_PERIOD_MILLIS = 1_100

/** Fraction of a block's width covered by the travelling highlight. */
private const val SHIMMER_BAND_FRACTION = 0.65f

private const val SKELETON_BASE_ALPHA = 0.07f
private const val SKELETON_HIGHLIGHT_ALPHA = 0.16f

private const val SHIMMER_LABEL = "skeleton-shimmer"
private const val SHIMMER_PROGRESS_LABEL = "skeleton-shimmer-progress"

/**
 * What a screen reader hears while placeholders are on screen.
 *
 * It is *not* a substitute for the visible label; it exists so the placeholders are not announced as
 * an unstructured pile of nodes, and so a blind user is told that a load is in progress rather than
 * hearing nothing at all.
 */
private const val SKELETON_CONTENT_DESCRIPTION = "正在加载列表"
