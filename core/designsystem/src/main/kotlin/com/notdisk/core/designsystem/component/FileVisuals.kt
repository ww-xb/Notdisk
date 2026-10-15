package com.notdisk.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.notdisk.core.designsystem.theme.CloudTheme
import com.notdisk.core.designsystem.theme.Dimens
import java.util.Locale

/**
 * The visual category a file falls into, which decides its icon and tint.
 *
 * ### Why this exists
 * A file list is scanned, not read. Users find "the video" by recognising a shape, long before
 * they parse a filename. That only works if the *same* file gets the *same* icon everywhere — in
 * the list, the grid, the search results and the transfer queue. Deriving the category in one
 * place, from the two facts a provider actually reports (extension, MIME type), is what makes
 * that consistency possible; if each screen re-derived it, a `.tar.gz` would be an archive on one
 * screen and a generic file on another.
 *
 * The categories are deliberately coarse. Nine recognisable shapes beat forty accurate ones: a
 * user cannot distinguish a spreadsheet glyph from a presentation glyph at 24dp anyway.
 */
enum class CloudFileKind {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    DOCUMENT,
    ARCHIVE,
    TEXT,
    GENERIC,
    ;

    /** The word shown to a screen reader in place of the icon, and in tooltips. */
    val label: String
        get() = when (this) {
            FOLDER -> "文件夹"
            IMAGE -> "图片"
            VIDEO -> "视频"
            AUDIO -> "音频"
            PDF -> "PDF 文档"
            DOCUMENT -> "文档"
            ARCHIVE -> "压缩包"
            TEXT -> "文本文件"
            GENERIC -> "文件"
        }

    companion object {
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "tif", "tiff", "avif", "ico",
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts", "rmvb", "vob",
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "ape", "aiff", "mid",
        )
        private val DOCUMENT_EXTENSIONS = setOf(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "pages", "numbers", "key", "epub",
        )
        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "tgz", "cab", "lz", "zst", "apk", "jar",
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf", "csv", "kt", "java", "js", "ts",
            "py", "c", "cpp", "h", "html", "css", "sh", "sql", "properties", "gradle", "toml",
        )

        /**
         * Classifies a file from what the provider reported.
         *
         * Extension wins over MIME type: providers routinely send `application/octet-stream` for
         * anything unusual, but they almost always preserve the real name — and the name is what
         * the user is looking at, so it must be what drives the icon.
         *
         * @param name the display name; only its extension is inspected.
         * @param mimeType best-effort MIME type, used when the name has no usable extension.
         * @param isFolder when true, always [FOLDER] regardless of the other inputs.
         */
        fun resolve(name: String, mimeType: String? = null, isFolder: Boolean = false): CloudFileKind {
            if (isFolder) return FOLDER

            val extension = extensionOf(name)
            when (extension) {
                in IMAGE_EXTENSIONS -> return IMAGE
                in VIDEO_EXTENSIONS -> return VIDEO
                in AUDIO_EXTENSIONS -> return AUDIO
                in DOCUMENT_EXTENSIONS -> return DOCUMENT
                in ARCHIVE_EXTENSIONS -> return ARCHIVE
                in TEXT_EXTENSIONS -> return TEXT
                "pdf" -> return PDF
            }

            return resolveFromMimeType(mimeType ?: "")
        }

        /** Lower-case extension without the dot; `""` when the name has none. */
        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            // A leading dot (".gitignore") or a trailing dot is not an extension.
            if (dot <= 0 || dot == name.length - 1) return ""
            return name.substring(dot + 1).lowercase(Locale.US)
        }

        private fun resolveFromMimeType(mimeType: String): CloudFileKind {
            val mime = mimeType.lowercase(Locale.US)
            if (mime.isEmpty()) return GENERIC
            return when {
                mime == "application/pdf" -> PDF
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime.startsWith("text/") -> TEXT
                mime.startsWith("application/zip") ||
                    mime.startsWith("application/x-tar") ||
                    mime.startsWith("application/gzip") ||
                    mime.contains("compressed") ||
                    mime.contains("archive") -> ARCHIVE
                mime.startsWith("application/msword") ||
                    mime.contains("officedocument") ||
                    mime.contains("spreadsheet") ||
                    mime.contains("presentation") -> DOCUMENT
                else -> GENERIC
            }
        }
    }
}

/** The stock Material glyph for a category. Chosen so the silhouette alone is recognisable. */
internal val CloudFileKind.icon: ImageVector
    get() = when (this) {
        CloudFileKind.FOLDER -> Icons.Filled.Folder
        CloudFileKind.IMAGE -> Icons.Filled.Image
        CloudFileKind.VIDEO -> Icons.Filled.Movie
        CloudFileKind.AUDIO -> Icons.Filled.MusicNote
        CloudFileKind.PDF -> Icons.Filled.PictureAsPdf
        CloudFileKind.DOCUMENT -> Icons.Filled.Description
        CloudFileKind.ARCHIVE -> Icons.Filled.FolderZip
        CloudFileKind.TEXT -> Icons.AutoMirrored.Filled.Article
        CloudFileKind.GENERIC -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

/** The tint for a category, taken from the theme so it tracks light/dark. */
internal val CloudFileKind.tint: Color
    @Composable get() = with(CloudTheme.semanticColors) {
        when (this@tint) {
            CloudFileKind.FOLDER -> folderTint
            CloudFileKind.IMAGE -> imageTint
            CloudFileKind.VIDEO -> videoTint
            CloudFileKind.AUDIO -> audioTint
            CloudFileKind.PDF -> pdfTint
            CloudFileKind.DOCUMENT -> documentTint
            CloudFileKind.ARCHIVE -> archiveTint
            CloudFileKind.TEXT -> textTint
            CloudFileKind.GENERIC -> genericTint
        }
    }

/**
 * The leading visual of a file row or grid cell: a remote thumbnail when the provider has one,
 * with the file-type icon behind it as the permanent fallback.
 *
 * ### Why the icon sits *behind* the image rather than in an `error` slot
 * Thumbnails fail constantly in an aggregator — the token expired, the CDN is slow, the vendor
 * only generates previews above a size threshold. Using the icon as an always-present backdrop
 * means a failure degrades to something correct with no flash of empty box, and no separate
 * error/loading branch to maintain.
 *
 * The icon carries the [CloudFileKind.label] as its content description, because it is the only
 * element describing the item's *type*; the thumbnail itself is decorative (the filename beside
 * it already conveys identity) and is announced as `null` to avoid reading the kind twice.
 */
@Composable
internal fun FileLeadingVisual(
    kind: CloudFileKind,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    /**
     * Fixed square size for this visual, or `null` to let [modifier] decide — which is what the
     * grid cell needs, since its thumbnail is sized by the cell's own aspect ratio.
     */
    containerSize: Dp? = Dimens.ThumbnailSmall,
    iconSize: Dp = Dimens.IconMedium,
    cornerRadius: Dp = 6.dp,
    forceIconOnly: Boolean = false,
) {
    val tint = kind.tint
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .then(if (containerSize != null) Modifier.size(containerSize) else Modifier)
            .background(tint.copy(alpha = 0.16f), shape)
            .semantics { contentDescription = kind.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind.icon,
            // Decorative: the Box above already announced the type; repeating it here would make
            // TalkBack read "图片, 图片".
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )

        if (thumbnailUrl != null && !forceIconOnly) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(tint.copy(alpha = 0.16f), shape),
            )
        }
    }
}

/**
 * Turns a bare remote filename into something an image loader can actually fetch.
 *
 * Providers return thumbnail fields inconsistently: some give a full `https://…` URL, some give a
 * path relative to their CDN (`/thumb/abc.jpg`), and some give a bare file id. Only the first two
 * are loadable; treating the third as a relative path would fire a request at the app's own host
 * and log a spurious 404, so it is rejected instead.
 *
 * @param baseUrl the provider's CDN origin, used to complete a root-relative path.
 * @return a loadable URL, or `null` when [raw] cannot be turned into one.
 */
internal fun resolveThumbnailUrl(raw: String?, baseUrl: String? = null): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("content://", ignoreCase = true) ||
        trimmed.startsWith("file://", ignoreCase = true)
    ) {
        return trimmed
    }
    if (trimmed.startsWith("//")) return "https:$trimmed"
    if (trimmed.startsWith("/") && !baseUrl.isNullOrBlank()) {
        return baseUrl.trimEnd('/') + trimmed
    }
    // A bare id, or a relative path with no base to resolve against: nothing fetchable.
    return null
}

/** Joins non-blank metadata fragments with the separator file rows use. */
internal fun joinMetadata(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

/** Remembers the resolved thumbnail URL so a recomposition does not re-parse the string. */
@Composable
internal fun rememberThumbnailUrl(raw: String?, baseUrl: String?): String? =
    remember(raw, baseUrl) { resolveThumbnailUrl(raw, baseUrl) }
