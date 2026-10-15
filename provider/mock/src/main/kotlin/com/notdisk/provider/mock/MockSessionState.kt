package com.notdisk.provider.mock

import java.util.concurrent.ConcurrentHashMap

/**
 * Mutable state produced by *this session's* mutations, layered over the immutable [MockTree].
 *
 * ### Why this exists
 * Without it, the Mock would be incoherent in a way that hides real bugs: `createFolder` would add
 * a folder that `listChildren` could not see, and `rename` would change a name that `getFile` still
 * reported as the old one. The application's cache-invalidation logic would then look correct while
 * being tested against a provider that never actually changes.
 *
 * Sharing one instance between [MockFileSystemProvider] and [MockFileOperationProvider] makes a
 * mutation immediately visible to every read path, which is what the real provider must also do.
 *
 * State is per-provider-instance (not global), so tests stay independent.
 */
internal class MockSessionState {

    private val createdFolders = ConcurrentHashMap<String, MockNode>()
    private val createdFiles = ConcurrentHashMap<String, MockNode>()
    private val renamed = ConcurrentHashMap<String, String>()
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val starred = ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------------------ mutations

    fun addFolder(node: MockNode) {
        createdFolders[node.id] = node
    }

    fun addFile(node: MockNode) {
        createdFiles[node.id] = node
    }

    fun markDeleted(nodeId: String) {
        deleted += nodeId
    }

    fun unmarkDeleted(nodeId: String) {
        deleted -= nodeId
    }

    fun rename(nodeId: String, newName: String) {
        renamed[nodeId] = newName
    }

    fun setStarred(nodeId: String, value: Boolean) {
        starred[nodeId] = value
    }

    // ------------------------------------------------------------------ reads

    /** Resolves a node id against the static tree plus everything created this session. */
    fun find(nodeId: String): MockNode? {
        if (nodeId in deleted) return null
        val base = MockTree.find(nodeId)
            ?: createdFolders[nodeId]
            ?: createdFiles[nodeId]
            ?: return null
        return applyOverlay(base)
    }

    /** Children of [folderId], including created items and excluding deleted ones. */
    fun childrenOf(folderId: String): List<MockNode> =
        (MockTree.childrenOf(folderId) +
            createdFolders.values.filter { it.parentId == folderId } +
            createdFiles.values.filter { it.parentId == folderId })
            .filter { it.id !in deleted }
            .map { applyOverlay(it) }
            .sortedWith(compareByDescending<MockNode> { it.isFolder }.thenBy { it.name })

    /** Every visible node, for search. */
    fun allVisible(): List<MockNode> =
        (MockTree.all + createdFolders.values + createdFiles.values)
            .filter { it.parentId != null && it.id !in deleted }
            .map { applyOverlay(it) }

    /** Root→node chain, walking the overlay-aware parent links. */
    fun pathTo(nodeId: String): List<MockNode> {
        val chain = ArrayDeque<MockNode>()
        var current = find(nodeId) ?: return emptyList()
        var guard = 0
        while (guard++ < 64) {
            chain.addFirst(current)
            val parentId = current.parentId ?: break
            current = find(parentId) ?: break
        }
        return chain.toList()
    }

    private fun applyOverlay(node: MockNode): MockNode {
        val newName = renamed[node.id]
        val star = starred[node.id]
        return when {
            newName != null && star != null -> node.copy(name = newName, starred = star)
            newName != null -> node.copy(name = newName)
            star != null -> node.copy(starred = star)
            else -> node
        }
    }
}
