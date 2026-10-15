package com.notdisk.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The shape ramp.
 *
 * ### Why this exists
 * Shape is the app's cheapest signal of *what a surface is*. In this product three distinctions
 * have to be readable at a glance:
 *
 *  - **Thumbnails** (small, `extraSmall`) stay nearly square so a grid of covers looks like a
 *    filmstrip rather than a bag of pills.
 *  - **List rows and cards** (`small`/`medium`) carry enough corner rounding to read as discrete,
 *    tappable objects even when separators are hidden.
 *  - **Dialogs and bottom sheets** (`large`/`extraLarge`) get a generous radius so modal surfaces
 *    never get confused with inline cards.
 *
 * A 4dp base unit keeps every radius a multiple of the spacing scale in [Dimens], so shapes line
 * up with padding instead of drifting against it.
 */
val CloudShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Named shapes for the surfaces the shape ramp alone cannot describe.
 *
 * Material's [Shapes] is a single five-step ladder, but a file grid needs a *thumbnail* radius
 * that is deliberately smaller than a card's, and a badge needs a pill. Rather than passing magic
 * [RoundedCornerShape] values at each call site — which is how design systems drift — the
 * component layer reads these.
 */
object CloudShapeTokens {
    /** Grid thumbnails and list leading icons. Nearly square on purpose. */
    val Thumbnail = RoundedCornerShape(6.dp)

    /** Fully rounded pill for badges, chips and capability labels. */
    val Pill = RoundedCornerShape(percent = 50)

    /** Circular avatar/account treatment. */
    val Circle = RoundedCornerShape(percent = 50)

    /** Selection/hover highlight behind a list row. */
    val RowHighlight = RoundedCornerShape(8.dp)
}
