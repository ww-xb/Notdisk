package com.notdisk.feature.files

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.notdisk.core.designsystem.component.FileGridSkeleton
import com.notdisk.core.designsystem.component.FileListSkeleton
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the loading skeleton to a PNG so the *visual* result can be reviewed, not only asserted,
 * and smoke-tests that the skeleton composes and draws.
 *
 * ### Why this does not use `captureToImage()`
 * `captureToImage()` calls `waitForIdle()` internally, and the shimmer is an **infinite** animation:
 * the clock never becomes idle, so that call times out (observed, not assumed — the first version of
 * this test failed with `ComposeTimeoutException`). The frame is therefore drawn straight from the
 * Activity's decor view, which is what the display would show, with the animation clock advanced by
 * hand so the captured frame is deterministic.
 *
 * ### Where the PNGs go
 * `-Dnotdisk.screenshot.dir=<path>` when set, otherwise a directory under the JVM's temp dir. The
 * default is deliberately *not* a repository path: a hardcoded absolute path would make this test
 * fail on a CI runner, and a test that only passes on one machine is worse than no test.
 * `./gradlew :feature:files:testDebugUnitTest --tests '*SkeletonScreenshotTest' \
 *   -Dnotdisk.screenshot.dir=$PWD/dist/screens` is how the reviewable copies are produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeletonScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A point inside the shimmer cycle where the highlight sits mid-block, not at an edge. */
    private val shimmerSampleMs = 400L

    private fun dump(name: String) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            view.draw(Canvas(bitmap))
            val out = File(outputDir())
            check(out.isDirectory || out.mkdirs()) { "cannot create screenshot dir: $out" }
            File(out, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            println("WROTE ${out.absolutePath}/$name.png ${bitmap.width}x${bitmap.height}")
        }
    }

    @Test
    fun list_skeleton_renders_and_is_dumped_for_review() {
        // The shimmer never idles, so the clock is pinned and advanced rather than awaited.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                FileListSkeleton(modifier = Modifier.fillMaxSize(), label = "正在读取「我的文件」…")
            }
        }
        composeRule.mainClock.advanceTimeBy(shimmerSampleMs)
        dump("list-skeleton")
    }

    @Test
    fun grid_skeleton_renders_and_is_dumped_for_review() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                FileGridSkeleton(modifier = Modifier.fillMaxSize(), label = "正在读取「我的文件」…")
            }
        }
        composeRule.mainClock.advanceTimeBy(shimmerSampleMs)
        dump("grid-skeleton")
    }

    private companion object {
        /** Explicit property wins; otherwise a writable temp directory that exists on any host. */
        fun outputDir(): String = System.getProperty("notdisk.screenshot.dir")
            ?: (System.getProperty("java.io.tmpdir") + File.separator + "notdisk-screenshots")
    }
}
