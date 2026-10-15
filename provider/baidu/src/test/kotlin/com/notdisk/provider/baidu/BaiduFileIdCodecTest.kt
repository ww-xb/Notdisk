package com.notdisk.provider.baidu

import com.notdisk.core.model.FileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the path-based [FileId] codec.
 *
 * ### Why this codec gets its own test file
 * Identity stability is the property the whole integration rests on: a `FileId` is a cache key, a
 * "recent files" key and the value the player refreshes from. The 115 integration shipped a real bug
 * here (a `pickcode` embedded in the id gave one file two identities), and the shared contract suite
 * caught it. These tests pin the equivalent invariants for Baidu *directly*, so a regression is
 * reported as "the codec broke identity" rather than as a vague downstream failure.
 */
class BaiduFileIdCodecTest {

    @Test
    fun `folder and file ids are distinguishable and round trip`() {
        val folder = BaiduFileIdCodec.forFolder("/我的文件")
        val file = BaiduFileIdCodec.forFile("/我的文件/a.txt")

        val decodedFolder = BaiduFileIdCodec.decode(folder)
        val decodedFile = BaiduFileIdCodec.decode(file)

        assertNotNull(decodedFolder)
        assertNotNull(decodedFile)
        assertEquals("/我的文件", decodedFolder.path)
        assertTrue(decodedFolder.isFolder)
        assertEquals("/我的文件/a.txt", decodedFile.path)
        assertFalse(decodedFile.isFolder)
    }

    /**
     * The invariant the contract suite asserts across the two endpoints, pinned here at the unit
     * level: the *same* path must always produce a byte-identical id.
     *
     * This is what makes `getFile` agree with `listChildren`. If the encoding ever started including
     * something response-dependent, this test fails immediately rather than the cache silently
     * growing a second row per file.
     */
    @Test
    fun `the same path always yields a byte identical id`() {
        val first = BaiduFileIdCodec.forFile("/我的文件/a.txt")
        val second = BaiduFileIdCodec.forFile("/我的文件/a.txt")
        assertEquals(first.value, second.value)
    }

    /**
     * A path is arbitrary user data, so the encoding must survive characters that would break a
     * `key=value` scheme. This is why the codec stores the path verbatim after a fixed prefix rather
     * than in named segments with a hand-rolled escaping rule.
     */
    @Test
    fun `paths containing separators and punctuation survive the round trip`() {
        val awkward = listOf(
            "/我的文件/a=b.txt",
            "/我的文件/a:b:c.txt",
            "/我的文件/a&b=c.txt",
            "/我的文件/文件名里有空格 和 中文.txt",
            "/我的文件/emoji-🎬-movie.mp4",
            "/a/b/c/d/e/deep.txt",
        )
        awkward.forEach { path ->
            val id = BaiduFileIdCodec.forFile(path)
            assertEquals(path, BaiduFileIdCodec.decode(id)?.path, "round trip lost: $path")
        }
    }

    @Test
    fun `decode rejects an id produced by another provider`() {
        // A 115-style id must not be silently reinterpreted. It has to become a typed failure at the
        // call site, not a request against a garbage path.
        assertNull(BaiduFileIdCodec.decode(FileId("file:115:fid=123:cid=456")))
        assertNull(BaiduFileIdCodec.decode(FileId("garbage")))
        assertFalse(BaiduFileIdCodec.matches(FileId("folder:115:cid=0")))
    }

    /**
     * A file id with no path after the prefix is not recoverable, and it must not be turned into the
     * root — that would make a corrupt id silently address the user's entire drive.
     */
    @Test
    fun `an empty path after the prefix is not fabricated into the root`() {
        // It decodes (the prefix matched) but to an empty path, which `normalise` never produces, so
        // callers see a path that cannot match a real entry rather than the root.
        val decoded = BaiduFileIdCodec.decode(FileId("file:file:baidu:"))
        assertEquals("", decoded?.path)
    }

    @Test
    fun `normalise canonicalises the shapes a caller may supply`() {
        assertEquals("/", BaiduFileIdCodec.normalise(""))
        assertEquals("/", BaiduFileIdCodec.normalise("/"))
        assertEquals("/a/b", BaiduFileIdCodec.normalise("a/b"))
        assertEquals("/a/b", BaiduFileIdCodec.normalise("/a/b/"))
        assertEquals("/a/b", BaiduFileIdCodec.normalise("  /a/b  "))
        // A trailing slash must not turn two spellings of one folder into two identities.
        assertEquals(
            BaiduFileIdCodec.forFolder("/a/b").value,
            BaiduFileIdCodec.forFolder("/a/b/").value,
        )
    }

    @Test
    fun `parent and name are derived correctly including at the root`() {
        val deep = BaiduRef(path = "/a/b/c.txt", isFolder = false)
        assertEquals("/a/b", deep.parentPath)
        assertEquals("c.txt", deep.name)

        val topLevel = BaiduRef(path = "/c.txt", isFolder = false)
        assertEquals("/", topLevel.parentPath)
        assertEquals("c.txt", topLevel.name)

        val root = BaiduRef(path = "/", isFolder = true)
        assertNull(root.parentPath)
    }

    @Test
    fun `an id never looks like a url`() {
        // The shared contract asserts this, and it is worth pinning locally too: an id that could be
        // mistaken for access material is how a signed URL ends up persisted as a file identity.
        val id = BaiduFileIdCodec.forFile("/我的文件/a.txt")
        assertFalse(id.value.startsWith("http://"))
        assertFalse(id.value.startsWith("https://"))
    }
}
