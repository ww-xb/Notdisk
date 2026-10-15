package com.notdisk.domain.testing

import com.notdisk.core.model.AccountCapabilities
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.DownloadCapabilities
import com.notdisk.core.model.FileOperationCapabilities
import com.notdisk.core.model.FileSystemCapabilities
import com.notdisk.core.model.MediaCapabilities
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.PagingModel
import com.notdisk.core.model.PreviewCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.TrashCapabilities
import com.notdisk.core.model.UploadCapabilities
import com.notdisk.core.model.FileSortField

/**
 * Named capability declarations for tests.
 *
 * ### Why a permissive default and a fully-unsupported extreme, and nothing in between
 * The gating tests all have the same shape: start from a provider whose declaration would allow the
 * call, flip *one* field to [CapabilitySupport.UNSUPPORTED], assert the refusal, and assert the
 * fake was never reached. That shape only proves what it claims if the starting declaration is
 * permissive — otherwise the refusal could be coming from some *other* field the test did not mean
 * to configure, and the test would keep passing after the gate under test was deleted.
 *
 * So [allSupported] is genuinely everything (`SUPPORTED`, not `UNVERIFIED`, so a gate that wrongly
 * required verification would also be caught), and [allUnsupported] is the opposite extreme used to
 * check that a fully read-only provider degrades into typed refusals rather than crashes. The
 * individual tests then poke exactly one field.
 *
 * ### Why this is a hand-written declaration rather than a copy of a real provider's
 * Copying, say, the 115 declaration would couple this module's tests to another module's build
 * state: a legitimate change to 115's capabilities would break `core:domain` tests that have
 * nothing to do with 115. The domain layer's contract is with the *shape* of `CloudCapabilities`,
 * and these fixtures state that directly.
 */
internal object CapabilityFixtures {

    /** Everything verified-supported. The starting point for every gate test. */
    fun allSupported(id: ProviderId): CloudCapabilities = CloudCapabilities(
        providerId = id,
        account = AccountCapabilities(
            interactiveFlows = AuthFlowKind.entries.toSet(),
            silentRefresh = CapabilitySupport.SUPPORTED,
            multipleAccounts = true,
            logout = CapabilitySupport.SUPPORTED,
            storageQuota = CapabilitySupport.SUPPORTED,
        ),
        fileSystem = FileSystemCapabilities(
            listChildren = CapabilitySupport.SUPPORTED,
            getFile = CapabilitySupport.SUPPORTED,
            getPath = CapabilitySupport.SUPPORTED,
            search = CapabilitySupport.SUPPORTED,
            serverSideBreadcrumb = true,
            paging = PagingModel.CURSOR,
            serverSideSortFields = FileSortField.entries.toSet(),
            categoryFilter = CapabilitySupport.SUPPORTED,
            suffixFilter = CapabilitySupport.SUPPORTED,
        ),
        operations = FileOperationCapabilities(
            createFolder = CapabilitySupport.SUPPORTED,
            rename = CapabilitySupport.SUPPORTED,
            move = CapabilitySupport.SUPPORTED,
            copy = CapabilitySupport.SUPPORTED,
            delete = CapabilitySupport.SUPPORTED,
            batchOperations = CapabilitySupport.SUPPORTED,
            share = CapabilitySupport.SUPPORTED,
            upload = UploadCapabilities(
                supported = CapabilitySupport.SUPPORTED,
                maxFileSizeBytes = 1_000_000_000L,
                maxSingleRequestBytes = 100_000_000L,
                resumable = true,
                serverSideDuplicateDetection = true,
            ),
            download = DownloadCapabilities(
                supported = CapabilitySupport.SUPPORTED,
                directLink = true,
                directLinkExpires = true,
                folderArchive = CapabilitySupport.SUPPORTED,
            ),
            trash = TrashCapabilities(
                supported = CapabilitySupport.SUPPORTED,
                list = CapabilitySupport.SUPPORTED,
                restore = CapabilitySupport.SUPPORTED,
                purge = CapabilitySupport.SUPPORTED,
                purgeRequiresSecret = false,
            ),
            // The honest default from the model, kept so tests can assert retry behaviour is not
            // silently assumed.
            idempotentWrites = false,
        ),
        media = MediaCapabilities(
            videoStreaming = CapabilitySupport.SUPPORTED,
            audioStreaming = CapabilitySupport.SUPPORTED,
            protocols = setOf(MediaProtocol.PROGRESSIVE_HTTP, MediaProtocol.HLS),
            rangeRequests = true,
            urlExpires = true,
            urlRefresh = CapabilitySupport.SUPPORTED,
            subtitles = CapabilitySupport.SUPPORTED,
            multipleRenditions = CapabilitySupport.SUPPORTED,
            separateAudioTracks = CapabilitySupport.SUPPORTED,
            playbackProgress = CapabilitySupport.SUPPORTED,
            requiresRequestHeaders = true,
        ),
        preview = PreviewCapabilities(
            image = CapabilitySupport.SUPPORTED,
            thumbnail = CapabilitySupport.SUPPORTED,
            text = CapabilitySupport.SUPPORTED,
            pdf = CapabilitySupport.SUPPORTED,
            officeDocument = CapabilitySupport.SUPPORTED,
            supportedExtensions = setOf("jpg", "png", "txt", "pdf", "mp4"),
        ),
    )

    /**
     * A read-only provider: no writes, no media, no preview.
     *
     * Used to assert that a fully incapable provider produces *typed* refusals — never a crash,
     * never a provider call.
     */
    fun allUnsupported(id: ProviderId): CloudCapabilities = CloudCapabilities(
        providerId = id,
        account = AccountCapabilities(
            interactiveFlows = emptySet(),
            silentRefresh = CapabilitySupport.UNSUPPORTED,
            logout = CapabilitySupport.UNSUPPORTED,
            storageQuota = CapabilitySupport.UNSUPPORTED,
        ),
        fileSystem = FileSystemCapabilities(),
        operations = FileOperationCapabilities(),
        media = MediaCapabilities(),
        preview = PreviewCapabilities(),
    )
}
