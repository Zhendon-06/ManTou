package com.hfad.mantou.utils.project

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class WebAppProjectFileRole {
    ENTRY,
    STYLE,
    SCRIPT,
    DATA,
    ASSET,
    OTHER
}

data class WebAppProjectFile(
    val path: String,
    val role: WebAppProjectFileRole = WebAppProjectFileRole.OTHER,
    val required: Boolean = true,
    val sha256: String? = null,
    val description: String = "",
    val dependsOn: List<String> = emptyList(),
    val ownsCriteria: List<String> = emptyList()
)

data class WebAppProjectManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: String,
    val displayName: String,
    val entryPoint: String = DEFAULT_ENTRY_POINT,
    val stateFile: String? = null,
    val files: List<WebAppProjectFile> = emptyList(),
    val appSpec: WebAppSpec? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_ENTRY_POINT = "index.html"
    }
}

enum class WebAppProjectSnapshotKind {
    DRAFT,
    RELEASE,
    LEGACY
}

data class WebAppProjectSnapshot(
    val projectRoot: File,
    val contentRoot: File,
    val entryFile: File,
    val manifest: WebAppProjectManifest,
    val kind: WebAppProjectSnapshotKind,
    val version: Long? = null
) {
    val artifactPath: String
        get() = entryFile.absolutePath
}

data class WebAppProjectVersions(
    val latestDraftVersion: Long,
    val latestReleaseVersion: Long,
    val activeReleaseVersion: Long?
)

enum class WebAppProjectSelection {
    PREFER_RELEASE,
    LATEST_DRAFT,
    ACTIVE_RELEASE
}

enum class WebProjectDiagnosticSeverity {
    WARNING,
    ERROR
}

data class WebProjectValidationDiagnostic(
    val severity: WebProjectDiagnosticSeverity,
    val code: String,
    val message: String,
    val path: String? = null
)

data class WebProjectValidationReport(
    val diagnostics: List<WebProjectValidationDiagnostic>,
    val fileCount: Int = 0,
    val totalBytes: Long = 0L
) {
    val passed: Boolean
        get() = diagnostics.none { it.severity == WebProjectDiagnosticSeverity.ERROR }

    fun requirePassed() {
        if (!passed) throw WebAppProjectValidationException(this)
    }
}

class WebAppProjectValidationException(
    val report: WebProjectValidationReport
) : IllegalStateException(
    report.diagnostics
        .filter { it.severity == WebProjectDiagnosticSeverity.ERROR }
        .joinToString("; ") { diagnostic ->
            buildString {
                append(diagnostic.code).append(": ").append(diagnostic.message)
                diagnostic.path?.let { append(" (").append(it).append(')') }
            }
        }
        .ifBlank { "Web project validation failed" }
)

class WebAppProjectException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

object WebAppProjectManifestCodec {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun read(file: File): WebAppProjectManifest {
        if (!file.isFile) throw WebAppProjectException("Project manifest does not exist: ${file.path}")
        val decoded = runCatching {
            gson.fromJson(file.readText(), WebAppProjectManifest::class.java)
        }.getOrElse { error ->
            throw WebAppProjectException("Unable to read project manifest: ${file.path}", error)
        } ?: throw WebAppProjectException("Project manifest is empty: ${file.path}")
        val manifest = runCatching {
            decoded.copy(
                files = decoded.files.orEmpty().map { projectFile ->
                    projectFile.copy(
                        description = projectFile.description.orEmpty(),
                        dependsOn = projectFile.dependsOn.orEmpty(),
                        ownsCriteria = projectFile.ownsCriteria.orEmpty()
                    )
                }
            )
        }.getOrElse { error ->
            throw WebAppProjectException("Project manifest is invalid: ${file.path}", error)
        }
        val diagnostics = runCatching { WebAppProjectValidator.validateManifest(manifest) }
            .getOrElse { error ->
                throw WebAppProjectException("Project manifest is invalid: ${file.path}", error)
            }
        if (diagnostics.any { it.severity == WebProjectDiagnosticSeverity.ERROR }) {
            throw WebAppProjectValidationException(WebProjectValidationReport(diagnostics))
        }
        return manifest
    }

    fun write(file: File, manifest: WebAppProjectManifest) {
        val diagnostics = WebAppProjectValidator.validateManifest(manifest)
        if (diagnostics.any { it.severity == WebProjectDiagnosticSeverity.ERROR }) {
            throw WebAppProjectValidationException(WebProjectValidationReport(diagnostics))
        }
        WebProjectPaths.writeTextAtomically(file, gson.toJson(manifest) + "\n")
    }
}

class WebAppProjectWorkspace(
    private val validator: WebAppProjectValidator = WebAppProjectValidator()
) {
    fun create(projectRoot: File, manifest: WebAppProjectManifest): WebAppProjectSnapshot =
        synchronized(WORKSPACE_LOCK) {
            val root = projectRoot.absoluteFile
            if (metadataDirectory(root).exists()) {
                throw WebAppProjectException("Project workspace already exists: ${root.path}")
            }
            if (root.isDirectory && root.listFiles().orEmpty().isNotEmpty()) {
                throw WebAppProjectException(
                    "Project root is not empty; resolve and adopt it as a legacy project instead"
                )
            }
            if (!root.exists() && !root.mkdirs()) {
                throw WebAppProjectException("Unable to create project root: ${root.path}")
            }
            if (!root.isDirectory) {
                throw WebAppProjectException("Project root must be a directory: ${root.path}")
            }
            try {
                metadataDirectory(root).mkdirsOrThrow()
                draftsDirectory(root).mkdirsOrThrow()
                releasesDirectory(root).mkdirsOrThrow()
                WebAppProjectManifestCodec.write(manifestFile(root), manifest)
                writeState(root, WebAppProjectWorkspaceState())
                WebProjectPaths.writeTextAtomically(runtimeStateFile(root), "{}")
                createDraftLocked(root, copyFromActiveRelease = false)
            } catch (error: Exception) {
                metadataDirectory(root).deleteRecursively()
                draftsDirectory(root).deleteRecursively()
                releasesDirectory(root).deleteRecursively()
                throw error
            }
        }

    fun adoptLegacy(
        legacy: WebAppProjectSnapshot,
        manifest: WebAppProjectManifest = legacy.manifest
    ): WebAppProjectSnapshot = synchronized(WORKSPACE_LOCK) {
        if (legacy.kind != WebAppProjectSnapshotKind.LEGACY) {
            throw WebAppProjectException("Only legacy projects can be adopted")
        }
        val root = legacy.projectRoot.canonicalFile
        val contentRoot = legacy.contentRoot.canonicalFile
        if (root != contentRoot || !root.isDirectory) {
            throw WebAppProjectException("Legacy project content root must match its project root")
        }
        val reservedPaths = listOf(
            metadataDirectory(root),
            draftsDirectory(root),
            releasesDirectory(root)
        )
        reservedPaths.firstOrNull(File::exists)?.let { reserved ->
            throw WebAppProjectException("Legacy project contains reserved managed path: ${reserved.name}")
        }
        val manifestDiagnostics = WebAppProjectValidator.validateManifest(manifest)
        if (manifestDiagnostics.any { it.severity == WebProjectDiagnosticSeverity.ERROR }) {
            throw WebAppProjectValidationException(WebProjectValidationReport(manifestDiagnostics))
        }
        val sourceEntry = WebProjectPaths.resolve(root, manifest.entryPoint)
        if (!sourceEntry.isFile) {
            throw WebAppProjectException("Legacy entry point does not exist: ${manifest.entryPoint}")
        }
        val legacyRuntimeState = manifest.stateFile
            ?.let { statePath -> WebProjectPaths.resolve(root, statePath) }
            ?.takeIf(File::isFile)
            ?.readText()
            ?: "{}"

        val parent = root.parentFile
            ?: throw WebAppProjectException("Legacy project root has no parent directory")
        val stagingRoot = File(parent, ".${root.name}.adopt-${UUID.randomUUID()}")
        var managedPathsCreated = false
        try {
            WebProjectPaths.copyDirectory(root, stagingRoot)
            val stagedEntry = WebProjectPaths.resolve(stagingRoot, manifest.entryPoint)
            if (!stagedEntry.isFile) {
                throw WebAppProjectException("Copied legacy entry point is missing: ${manifest.entryPoint}")
            }

            managedPathsCreated = true
            metadataDirectory(root).mkdirsOrThrow()
            draftsDirectory(root).mkdirsOrThrow()
            releasesDirectory(root).mkdirsOrThrow()
            val firstDraftRoot = versionDirectory(draftsDirectory(root), 1L)
            WebProjectPaths.moveAtomically(stagingRoot, firstDraftRoot)
            WebAppProjectManifestCodec.write(manifestFile(root), manifest)
            writeState(
                root,
                WebAppProjectWorkspaceState(latestDraftVersion = 1L)
            )
            WebProjectPaths.writeTextAtomically(runtimeStateFile(root), legacyRuntimeState)
            snapshot(
                projectRoot = root,
                contentRoot = firstDraftRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.DRAFT,
                version = 1L
            )
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            if (managedPathsCreated) {
                metadataDirectory(root).deleteRecursively()
                draftsDirectory(root).deleteRecursively()
                releasesDirectory(root).deleteRecursively()
            }
            throw error
        }
    }

    fun createDraft(
        projectRoot: File,
        copyFromActiveRelease: Boolean = true
    ): WebAppProjectSnapshot = synchronized(WORKSPACE_LOCK) {
        createDraftLocked(projectRoot.absoluteFile, copyFromActiveRelease)
    }

    fun publishDraft(projectRoot: File, draftVersion: Long): WebAppProjectSnapshot =
        synchronized(WORKSPACE_LOCK) {
            val root = projectRoot.absoluteFile
            publishDraftLocked(
                projectRoot = root,
                draftVersion = draftVersion,
                manifest = readManifest(root),
                replaceManifest = false
            )
        }

    fun publishDraft(
        projectRoot: File,
        draftVersion: Long,
        manifest: WebAppProjectManifest
    ): WebAppProjectSnapshot = synchronized(WORKSPACE_LOCK) {
        val root = projectRoot.absoluteFile
        val currentManifest = readManifest(root)
        if (manifest.projectId != currentManifest.projectId) {
            throw WebAppProjectException("Project ID cannot change during publish")
        }
        publishDraftLocked(
            projectRoot = root,
            draftVersion = draftVersion,
            manifest = manifest,
            replaceManifest = manifest != currentManifest,
            previousManifest = currentManifest
        )
    }

    fun latestDraft(projectRoot: File): WebAppProjectSnapshot? = synchronized(WORKSPACE_LOCK) {
        val root = projectRoot.absoluteFile
        val state = readState(root)
        state.latestDraftVersion.takeIf { it > 0L }?.let { version ->
            snapshot(
                projectRoot = root,
                contentRoot = versionDirectory(draftsDirectory(root), version),
                manifest = readManifest(root),
                kind = WebAppProjectSnapshotKind.DRAFT,
                version = version
            )
        }
    }

    fun activeRelease(projectRoot: File): WebAppProjectSnapshot? = synchronized(WORKSPACE_LOCK) {
        val root = projectRoot.absoluteFile
        val state = readState(root)
        state.activeReleaseVersion?.let { version ->
            snapshot(
                projectRoot = root,
                contentRoot = versionDirectory(releasesDirectory(root), version),
                manifest = readManifest(root),
                kind = WebAppProjectSnapshotKind.RELEASE,
                version = version
            )
        }
    }

    fun versions(projectRoot: File): WebAppProjectVersions = synchronized(WORKSPACE_LOCK) {
        readState(projectRoot.absoluteFile).toVersions()
    }

    fun readManifest(projectRoot: File): WebAppProjectManifest =
        WebAppProjectManifestCodec.read(manifestFile(projectRoot.absoluteFile))

    fun saveManifest(projectRoot: File, manifest: WebAppProjectManifest) =
        synchronized(WORKSPACE_LOCK) {
            val root = projectRoot.absoluteFile
            readState(root)
            WebAppProjectManifestCodec.write(manifestFile(root), manifest)
        }

    private fun createDraftLocked(
        projectRoot: File,
        copyFromActiveRelease: Boolean
    ): WebAppProjectSnapshot {
        val manifest = readManifest(projectRoot)
        val state = readState(projectRoot)
        val draftVersion = state.latestDraftVersion + 1L
        val finalRoot = versionDirectory(draftsDirectory(projectRoot), draftVersion)
        if (finalRoot.exists()) {
            throw WebAppProjectException("Draft version already exists: $draftVersion")
        }
        val stagingRoot = File(
            draftsDirectory(projectRoot),
            ".staging-${versionName(draftVersion)}-${UUID.randomUUID()}"
        )
        try {
            val activeReleaseRoot = state.activeReleaseVersion
                ?.takeIf { copyFromActiveRelease }
                ?.let { versionDirectory(releasesDirectory(projectRoot), it) }
                ?.takeIf(File::isDirectory)
            if (activeReleaseRoot != null) {
                WebProjectPaths.copyDirectory(activeReleaseRoot, stagingRoot)
            } else {
                stagingRoot.mkdirsOrThrow()
            }
            WebProjectPaths.moveAtomically(stagingRoot, finalRoot)
            writeState(
                projectRoot,
                state.copy(latestDraftVersion = draftVersion)
            )
            return snapshot(
                projectRoot = projectRoot,
                contentRoot = finalRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.DRAFT,
                version = draftVersion
            )
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            throw error
        }
    }

    private fun publishDraftLocked(
        projectRoot: File,
        draftVersion: Long,
        manifest: WebAppProjectManifest,
        replaceManifest: Boolean,
        previousManifest: WebAppProjectManifest? = null
    ): WebAppProjectSnapshot {
        val state = readState(projectRoot)
        val draftRoot = versionDirectory(draftsDirectory(projectRoot), draftVersion)
        if (!draftRoot.isDirectory) {
            throw WebAppProjectException("Draft version does not exist: $draftVersion")
        }
        val draftSnapshot = snapshot(
            projectRoot = projectRoot,
            contentRoot = draftRoot,
            manifest = manifest,
            kind = WebAppProjectSnapshotKind.DRAFT,
            version = draftVersion
        )
        validator.validate(draftSnapshot).requirePassed()

        val releaseVersion = state.latestReleaseVersion + 1L
        val finalRoot = versionDirectory(releasesDirectory(projectRoot), releaseVersion)
        if (finalRoot.exists()) {
            throw WebAppProjectException("Release version already exists: $releaseVersion")
        }
        val stagingRoot = File(
            releasesDirectory(projectRoot),
            ".staging-${versionName(releaseVersion)}-${UUID.randomUUID()}"
        )
        var finalRootCreated = false
        var manifestWriteAttempted = false
        var stateWriteAttempted = false
        try {
            WebProjectPaths.copyDirectory(draftRoot, stagingRoot)
            val stagedSnapshot = snapshot(
                projectRoot = projectRoot,
                contentRoot = stagingRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.RELEASE,
                version = releaseVersion
            )
            validator.validate(stagedSnapshot).requirePassed()
            WebProjectPaths.moveAtomically(stagingRoot, finalRoot)
            finalRootCreated = true
            val releaseSnapshot = snapshot(
                projectRoot = projectRoot,
                contentRoot = finalRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.RELEASE,
                version = releaseVersion
            )

            if (replaceManifest) {
                manifestWriteAttempted = true
                WebAppProjectManifestCodec.write(manifestFile(projectRoot), manifest)
            }
            stateWriteAttempted = true
            writeState(
                projectRoot,
                state.copy(
                    latestReleaseVersion = releaseVersion,
                    activeReleaseVersion = releaseVersion
                )
            )
            return releaseSnapshot
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            if (stateWriteAttempted) {
                runCatching { writeState(projectRoot, state) }
                    .onFailure(error::addSuppressed)
            }
            if (manifestWriteAttempted && previousManifest != null) {
                runCatching {
                    WebAppProjectManifestCodec.write(manifestFile(projectRoot), previousManifest)
                }.onFailure(error::addSuppressed)
            }
            if (finalRootCreated && finalRoot.exists() && !finalRoot.deleteRecursively()) {
                error.addSuppressed(
                    WebAppProjectException("Unable to roll back release directory: ${finalRoot.path}")
                )
            }
            throw error
        }
    }

    private fun snapshot(
        projectRoot: File,
        contentRoot: File,
        manifest: WebAppProjectManifest,
        kind: WebAppProjectSnapshotKind,
        version: Long
    ): WebAppProjectSnapshot {
        val entryFile = WebProjectPaths.resolve(contentRoot, manifest.entryPoint)
        return WebAppProjectSnapshot(
            projectRoot = projectRoot.canonicalFile,
            contentRoot = contentRoot.canonicalFile,
            entryFile = entryFile,
            manifest = manifest,
            kind = kind,
            version = version
        )
    }

    private fun readState(projectRoot: File): WebAppProjectWorkspaceState {
        val file = workspaceStateFile(projectRoot)
        if (!file.isFile) {
            throw WebAppProjectException("Project workspace state does not exist: ${file.path}")
        }
        val state = runCatching {
            WORKSPACE_GSON.fromJson(file.readText(), WebAppProjectWorkspaceState::class.java)
        }.getOrElse { error ->
            throw WebAppProjectException("Unable to read project workspace state: ${file.path}", error)
        } ?: throw WebAppProjectException("Project workspace state is empty: ${file.path}")
        if (state.schemaVersion != WORKSPACE_SCHEMA_VERSION ||
            state.latestDraftVersion < 0L ||
            state.latestReleaseVersion < 0L ||
            state.activeReleaseVersion?.let { it <= 0L || it > state.latestReleaseVersion } == true
        ) {
            throw WebAppProjectException("Project workspace state is invalid: ${file.path}")
        }
        return state
    }

    private fun writeState(projectRoot: File, state: WebAppProjectWorkspaceState) {
        WebProjectPaths.writeTextAtomically(
            workspaceStateFile(projectRoot),
            WORKSPACE_GSON.toJson(state) + "\n"
        )
    }

    private data class WebAppProjectWorkspaceState(
        val schemaVersion: Int = WORKSPACE_SCHEMA_VERSION,
        val latestDraftVersion: Long = 0L,
        val latestReleaseVersion: Long = 0L,
        val activeReleaseVersion: Long? = null
    ) {
        fun toVersions() = WebAppProjectVersions(
            latestDraftVersion = latestDraftVersion,
            latestReleaseVersion = latestReleaseVersion,
            activeReleaseVersion = activeReleaseVersion
        )
    }

    companion object {
        const val METADATA_DIRECTORY_NAME = ".mantou"
        const val MANIFEST_FILE_NAME = "project.json"
        const val WORKSPACE_STATE_FILE_NAME = "workspace.json"
        const val RUNTIME_STATE_FILE_NAME = "app-state.json"
        const val DRAFTS_DIRECTORY_NAME = "drafts"
        const val RELEASES_DIRECTORY_NAME = "releases"
        private const val WORKSPACE_SCHEMA_VERSION = 1
        private val WORKSPACE_GSON = GsonBuilder().setPrettyPrinting().create()
        private val WORKSPACE_LOCK = Any()

        fun manifestFile(projectRoot: File): File =
            File(metadataDirectory(projectRoot), MANIFEST_FILE_NAME)

        fun workspaceStateFile(projectRoot: File): File =
            File(metadataDirectory(projectRoot), WORKSPACE_STATE_FILE_NAME)

        fun runtimeStateFile(projectRoot: File): File =
            File(metadataDirectory(projectRoot), RUNTIME_STATE_FILE_NAME)

        fun metadataDirectory(projectRoot: File): File =
            File(projectRoot, METADATA_DIRECTORY_NAME)

        fun draftsDirectory(projectRoot: File): File =
            File(projectRoot, DRAFTS_DIRECTORY_NAME)

        fun releasesDirectory(projectRoot: File): File =
            File(projectRoot, RELEASES_DIRECTORY_NAME)

        fun versionDirectory(parent: File, version: Long): File = File(parent, versionName(version))

        fun versionName(version: Long): String {
            require(version > 0L) { "Version must be positive" }
            return "v" + version.toString().padStart(6, '0')
        }

        fun parseVersion(directoryName: String): Long? {
            if (!VERSION_DIRECTORY_REGEX.matches(directoryName)) return null
            return directoryName.drop(1).toLongOrNull()?.takeIf { it > 0L }
        }

        private val VERSION_DIRECTORY_REGEX = Regex("^v[0-9]{6,}$")
    }
}

object WebAppProjectResolver {
    fun resolve(
        input: File,
        selection: WebAppProjectSelection = WebAppProjectSelection.PREFER_RELEASE
    ): WebAppProjectSnapshot {
        val target = input.absoluteFile
        if (!target.exists()) {
            throw WebAppProjectException("Project path does not exist: ${target.path}")
        }
        val managedRoot = findManagedProjectRoot(target)
        if (managedRoot != null) {
            val selectedContent = detectManagedContentRoot(managedRoot, target)
            if (selectedContent != null) {
                return managedSnapshot(managedRoot, selectedContent)
            }
            return selectManagedSnapshot(managedRoot, selection)
        }
        return resolveLegacy(target)
    }

    private fun selectManagedSnapshot(
        projectRoot: File,
        selection: WebAppProjectSelection
    ): WebAppProjectSnapshot {
        val workspace = WebAppProjectWorkspace()
        return when (selection) {
            WebAppProjectSelection.PREFER_RELEASE ->
                workspace.activeRelease(projectRoot) ?: workspace.latestDraft(projectRoot)

            WebAppProjectSelection.LATEST_DRAFT -> workspace.latestDraft(projectRoot)
            WebAppProjectSelection.ACTIVE_RELEASE -> workspace.activeRelease(projectRoot)
        } ?: throw WebAppProjectException("Project has no selectable draft or release: ${projectRoot.path}")
    }

    private fun managedSnapshot(
        projectRoot: File,
        selected: ManagedContentRoot
    ): WebAppProjectSnapshot {
        val manifest = WebAppProjectManifestCodec.read(WebAppProjectWorkspace.manifestFile(projectRoot))
        return WebAppProjectSnapshot(
            projectRoot = projectRoot.canonicalFile,
            contentRoot = selected.root.canonicalFile,
            entryFile = WebProjectPaths.resolve(selected.root, manifest.entryPoint),
            manifest = manifest,
            kind = selected.kind,
            version = selected.version
        )
    }

    private fun findManagedProjectRoot(input: File): File? {
        var current = if (input.isDirectory) input else input.parentFile
        while (current != null) {
            if (WebAppProjectWorkspace.manifestFile(current).isFile &&
                WebAppProjectWorkspace.workspaceStateFile(current).isFile
            ) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun detectManagedContentRoot(
        projectRoot: File,
        input: File
    ): ManagedContentRoot? {
        val rootPath = projectRoot.canonicalFile.toPath()
        val inputPath = input.canonicalFile.toPath()
        if (!inputPath.startsWith(rootPath)) return null
        val relative = rootPath.relativize(inputPath)
        if (relative.nameCount < 2) return null
        val kind = when (relative.getName(0).toString()) {
            WebAppProjectWorkspace.DRAFTS_DIRECTORY_NAME -> WebAppProjectSnapshotKind.DRAFT
            WebAppProjectWorkspace.RELEASES_DIRECTORY_NAME -> WebAppProjectSnapshotKind.RELEASE
            else -> return null
        }
        val versionName = relative.getName(1).toString()
        val version = WebAppProjectWorkspace.parseVersion(versionName) ?: return null
        val contentRoot = File(projectRoot, "${relative.getName(0)}/$versionName")
        if (!contentRoot.isDirectory) return null
        return ManagedContentRoot(contentRoot, kind, version)
    }

    private fun resolveLegacy(input: File): WebAppProjectSnapshot {
        val entry = when {
            input.isFile && isHtmlFile(input) -> input
            input.isDirectory -> chooseLegacyEntry(input)
            else -> null
        } ?: throw WebAppProjectException("Legacy project has no HTML entry point: ${input.path}")
        val root = entry.parentFile?.canonicalFile
            ?: throw WebAppProjectException("Legacy entry point has no parent directory: ${entry.path}")
        val projectId = "legacy-${WebProjectPaths.sha256(root.absolutePath.toByteArray()).take(16)}"
        val stateFile = File(root, "${entry.nameWithoutExtension}.json")
            .takeIf(File::isFile)
            ?.name
        val manifest = WebAppProjectManifest(
            projectId = projectId,
            displayName = root.name.ifBlank { entry.nameWithoutExtension },
            entryPoint = entry.name,
            stateFile = stateFile,
            files = listOf(
                WebAppProjectFile(entry.name, WebAppProjectFileRole.ENTRY),
                *stateFile?.let {
                    arrayOf(WebAppProjectFile(it, WebAppProjectFileRole.DATA))
                }.orEmpty()
            )
        )
        return WebAppProjectSnapshot(
            projectRoot = root,
            contentRoot = root,
            entryFile = entry.canonicalFile,
            manifest = manifest,
            kind = WebAppProjectSnapshotKind.LEGACY
        )
    }

    private fun chooseLegacyEntry(directory: File): File? {
        val htmlFiles = directory.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.filter(::isHtmlFile)
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toList()
            .orEmpty()
        return htmlFiles.firstOrNull { it.nameWithoutExtension == directory.name }
            ?: htmlFiles.firstOrNull { it.name.equals("index.html", ignoreCase = true) }
            ?: htmlFiles.firstOrNull()
    }

    private fun isHtmlFile(file: File): Boolean =
        file.extension.equals("html", ignoreCase = true) ||
            file.extension.equals("htm", ignoreCase = true)

    private data class ManagedContentRoot(
        val root: File,
        val kind: WebAppProjectSnapshotKind,
        val version: Long
    )
}

data class WebAppProjectValidationPolicy(
    val maxFiles: Int = 512,
    val maxFileBytes: Long = 8L * 1024L * 1024L,
    val maxTotalBytes: Long = 32L * 1024L * 1024L,
    val forbidRemoteResources: Boolean = true,
    val allowedExtensions: Set<String> = DEFAULT_ALLOWED_EXTENSIONS
) {
    init {
        require(maxFiles > 0)
        require(maxFileBytes > 0L)
        require(maxTotalBytes > 0L)
        require(allowedExtensions.isNotEmpty())
    }

    companion object {
        val DEFAULT_ALLOWED_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "json", "svg", "txt", "md",
            "png", "jpg", "jpeg", "gif", "webp", "avif", "ico",
            "woff", "woff2", "ttf", "otf", "wasm", "mp3", "wav", "ogg", "mp4", "webm"
        )
    }
}

class WebAppProjectValidator(
    private val policy: WebAppProjectValidationPolicy = WebAppProjectValidationPolicy()
) {
    fun validate(snapshot: WebAppProjectSnapshot): WebProjectValidationReport =
        validate(snapshot.manifest, snapshot.contentRoot)

    fun validate(
        manifest: WebAppProjectManifest,
        contentRoot: File
    ): WebProjectValidationReport {
        val diagnostics = validateManifest(manifest).toMutableList()
        if (!contentRoot.isDirectory) {
            diagnostics += error(
                code = "CONTENT_ROOT_MISSING",
                message = "Project content root does not exist",
                path = contentRoot.path
            )
            return WebProjectValidationReport(diagnostics)
        }

        val root = contentRoot.canonicalFile
        val files = mutableListOf<File>()
        var totalBytes = 0L
        Files.walk(root.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root.toPath()) continue
                val relativePath = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                if (Files.isSymbolicLink(path)) {
                    diagnostics += error(
                        code = "SYMLINK_NOT_ALLOWED",
                        message = "Symbolic links are not allowed in web projects",
                        path = relativePath
                    )
                    continue
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                val file = path.toFile()
                files += file
                val size = file.length()
                totalBytes += size
                if (size > policy.maxFileBytes) {
                    diagnostics += error(
                        code = "FILE_TOO_LARGE",
                        message = "File exceeds ${policy.maxFileBytes} bytes",
                        path = relativePath
                    )
                }
                val extension = file.extension.lowercase(Locale.US)
                if (extension !in policy.allowedExtensions) {
                    diagnostics += error(
                        code = "FILE_TYPE_NOT_ALLOWED",
                        message = "File extension .$extension is not allowed",
                        path = relativePath
                    )
                }
            }
        }

        if (files.size > policy.maxFiles) {
            diagnostics += error(
                code = "FILE_COUNT_LIMIT",
                message = "Project has ${files.size} files; limit is ${policy.maxFiles}"
            )
        }
        if (totalBytes > policy.maxTotalBytes) {
            diagnostics += error(
                code = "PROJECT_SIZE_LIMIT",
                message = "Project uses $totalBytes bytes; limit is ${policy.maxTotalBytes}"
            )
        }

        val entry = resolveManifestPath(root, manifest.entryPoint, "entryPoint", diagnostics)
        if (entry == null || !entry.isFile) {
            diagnostics += error(
                code = "ENTRY_POINT_MISSING",
                message = "HTML entry point does not exist",
                path = manifest.entryPoint
            )
        } else {
            validateEntryHtml(root, entry, diagnostics)
            validateStructuredProjectEntry(manifest, root, entry, diagnostics)
        }

        manifest.stateFile?.let { statePath ->
            val stateFile = resolveManifestPath(root, statePath, "stateFile", diagnostics)
            if (stateFile == null || !stateFile.isFile) {
                diagnostics += error(
                    code = "STATE_FILE_MISSING",
                    message = "Declared state file does not exist",
                    path = statePath
                )
            }
        }

        manifest.files.forEach { declared ->
            val declaredFile = resolveManifestPath(root, declared.path, "files.path", diagnostics)
            if (declared.required && (declaredFile == null || !declaredFile.isFile)) {
                diagnostics += error(
                    code = "DECLARED_FILE_MISSING",
                    message = "Required manifest file does not exist",
                    path = declared.path
                )
            }
            val expectedHash = declared.sha256
            if (declaredFile?.isFile == true && expectedHash != null &&
                WebProjectPaths.sha256(declaredFile.readBytes()) != expectedHash.lowercase(Locale.US)
            ) {
                diagnostics += error(
                    code = "DECLARED_FILE_HASH_MISMATCH",
                    message = "File SHA-256 does not match the manifest",
                    path = declared.path
                )
            }
        }

        files.filter { it.extension.equals("json", ignoreCase = true) }.forEach { jsonFile ->
            val relativePath = WebProjectPaths.relativePath(root, jsonFile)
            runCatching { JsonParser.parseString(jsonFile.readText()) }
                .onFailure { parseError ->
                    diagnostics += error(
                        code = "JSON_INVALID",
                        message = parseError.message ?: "JSON is invalid",
                        path = relativePath
                    )
                }
        }

        files.filter { it.extension.equals("css", ignoreCase = true) }.forEach { cssFile ->
            validateCssReferences(root, cssFile, diagnostics)
        }
        val documentEntry = entry?.takeIf(File::isFile)
        files.filter {
            it.extension.equals("js", ignoreCase = true) ||
                it.extension.equals("mjs", ignoreCase = true)
        }.forEach { scriptFile ->
            validateJavaScriptReferences(
                contentRoot = root,
                scriptFile = scriptFile,
                documentEntry = documentEntry,
                diagnostics = diagnostics
            )
        }

        return WebProjectValidationReport(
            diagnostics = diagnostics.distinct(),
            fileCount = files.size,
            totalBytes = totalBytes
        )
    }

    private fun validateEntryHtml(
        contentRoot: File,
        entry: File,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val relativePath = WebProjectPaths.relativePath(contentRoot, entry)
        val html = runCatching { entry.readText() }.getOrElse { readError ->
            diagnostics += error(
                code = "ENTRY_POINT_UNREADABLE",
                message = readError.message ?: "Unable to read HTML entry point",
                path = relativePath
            )
            return
        }
        if (!HTML_ROOT_REGEX.containsMatchIn(html) || !HTML_END_REGEX.containsMatchIn(html)) {
            diagnostics += error(
                code = "ENTRY_HTML_INCOMPLETE",
                message = "Entry point must contain a complete HTML document",
                path = relativePath
            )
        }
        HTML_RESOURCE_REGEX.findAll(html).forEach { match ->
            validateReference(
                contentRoot = contentRoot,
                owner = entry,
                rawReference = match.groupValues[4],
                diagnostics = diagnostics
            )
        }
    }

    private fun validateStructuredProjectEntry(
        manifest: WebAppProjectManifest,
        contentRoot: File,
        entry: File,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val declaredStyles = manifest.files.filter { it.role == WebAppProjectFileRole.STYLE }
        val declaredScripts = manifest.files.filter { it.role == WebAppProjectFileRole.SCRIPT }
        if (declaredStyles.isEmpty() && declaredScripts.isEmpty()) return

        if (declaredStyles.isEmpty()) {
            diagnostics += error(
                code = "PROJECT_STYLESHEET_DECLARATION_MISSING",
                message = "Structured web projects must declare at least one stylesheet"
            )
        }
        if (declaredScripts.isEmpty()) {
            diagnostics += error(
                code = "PROJECT_SCRIPT_DECLARATION_MISSING",
                message = "Structured web projects must declare at least one JavaScript file"
            )
        }

        declaredStyles.filterNot { it.path.endsWith(".css", ignoreCase = true) }
            .forEach { declared ->
                diagnostics += error(
                    code = "STYLESHEET_FILE_TYPE_INVALID",
                    message = "STYLE files must use the .css extension",
                    path = declared.path
                )
            }
        declaredScripts.filterNot {
            it.path.endsWith(".js", ignoreCase = true) ||
                it.path.endsWith(".mjs", ignoreCase = true)
        }.forEach { declared ->
            diagnostics += error(
                code = "SCRIPT_FILE_TYPE_INVALID",
                message = "SCRIPT files must use the .js or .mjs extension",
                path = declared.path
            )
        }

        val html = runCatching { entry.readText() }.getOrNull() ?: return
        val declaredStylePaths = declaredStyles.mapNotNullTo(mutableSetOf()) { declared ->
            runCatching { WebProjectPaths.resolve(contentRoot, declared.path).canonicalPath }.getOrNull()
        }
        val declaredScriptPaths = declaredScripts.mapNotNullTo(mutableSetOf()) { declared ->
            runCatching { WebProjectPaths.resolve(contentRoot, declared.path).canonicalPath }.getOrNull()
        }
        val referencedStyles = HTML_LINK_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                val tag = match.value
                val rel = htmlAttribute(tag, "rel")
                    ?.split(Regex("\\s+"))
                    ?.any { it.equals("stylesheet", ignoreCase = true) }
                    ?: false
                if (!rel) return@mapNotNull null
                htmlAttribute(tag, "href")
                    ?.let { resolveLocalReference(contentRoot, entry, it) }
            }
            .filter { it.canonicalPath in declaredStylePaths }
            .distinctBy(File::getCanonicalPath)
            .toList()
        val referencedScripts = HTML_SCRIPT_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                htmlAttribute(match.value, "src")
                    ?.let { resolveLocalReference(contentRoot, entry, it) }
            }
            .filter { it.canonicalPath in declaredScriptPaths }
            .distinctBy(File::getCanonicalPath)
            .toList()

        if (declaredStyles.isNotEmpty() && referencedStyles.isEmpty()) {
            diagnostics += error(
                code = "ENTRY_STYLESHEET_REFERENCE_MISSING",
                message = "Entry HTML must load a declared local stylesheet with <link rel=\"stylesheet\">",
                path = manifest.entryPoint
            )
        } else if (referencedStyles.isNotEmpty() && referencedStyles.none(::hasNonBlankText)) {
            diagnostics += error(
                code = "REFERENCED_STYLESHEET_EMPTY",
                message = "Entry HTML must load at least one non-empty declared stylesheet",
                path = manifest.entryPoint
            )
        }

        if (declaredScripts.isNotEmpty() && referencedScripts.isEmpty()) {
            diagnostics += error(
                code = "ENTRY_SCRIPT_REFERENCE_MISSING",
                message = "Entry HTML must load a declared local JavaScript file with <script src=\"...\">",
                path = manifest.entryPoint
            )
        } else if (referencedScripts.isNotEmpty() && referencedScripts.none(::hasNonBlankText)) {
            diagnostics += error(
                code = "REFERENCED_SCRIPT_EMPTY",
                message = "Entry HTML must load at least one non-empty declared JavaScript file",
                path = manifest.entryPoint
            )
        }
    }

    private fun htmlAttribute(tag: String, name: String): String? {
        return HTML_ATTRIBUTE_REGEX.findAll(tag)
            .firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.groupValues
            ?.drop(2)
            ?.firstOrNull(String::isNotEmpty)
    }

    private fun resolveLocalReference(contentRoot: File, owner: File, rawReference: String): File? {
        val reference = rawReference.trim()
        if (reference.isEmpty() || IGNORED_REFERENCE_PREFIXES.any(reference::startsWith) ||
            reference.startsWith("//") || SCHEME_REGEX.containsMatchIn(reference)
        ) {
            return null
        }
        val cleanReference = decodeReferencePath(reference)?.takeIf(String::isNotBlank) ?: return null
        val candidate = if (cleanReference.startsWith('/')) {
            File(contentRoot, cleanReference.removePrefix("/"))
        } else {
            File(owner.parentFile, cleanReference)
        }
        val rootPath = contentRoot.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        return candidate.takeIf {
            candidatePath.startsWith(rootPath) &&
                Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS)
        }?.canonicalFile
    }

    private fun hasNonBlankText(file: File): Boolean {
        return runCatching { file.readText().isNotBlank() }.getOrDefault(false)
    }

    private fun validateCssReferences(
        contentRoot: File,
        cssFile: File,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val css = runCatching { cssFile.readText() }.getOrNull() ?: return
        CSS_URL_REGEX.findAll(css).forEach { match ->
            validateReference(contentRoot, cssFile, match.groupValues[2], diagnostics)
        }
        CSS_IMPORT_REGEX.findAll(css).forEach { match ->
            validateReference(contentRoot, cssFile, match.groupValues[1], diagnostics)
        }
    }

    private fun validateJavaScriptReferences(
        contentRoot: File,
        scriptFile: File,
        documentEntry: File?,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val script = runCatching { scriptFile.readText() }.getOrNull() ?: return
        JS_MODULE_REGEX.findAll(script).forEach { match ->
            val reference = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()
            if (reference.isBlank()) return@forEach
            if (!reference.startsWith(".") && !reference.startsWith("/") &&
                !SCHEME_REGEX.containsMatchIn(reference)
            ) {
                diagnostics += error(
                    code = "BARE_MODULE_UNSUPPORTED",
                    message = "Bare module imports require a bundler",
                    path = "${WebProjectPaths.relativePath(contentRoot, scriptFile)} -> $reference"
                )
            } else {
                validateReference(contentRoot, scriptFile, reference, diagnostics)
            }
        }
        JS_RUNTIME_RESOURCE_REGEX.findAll(script).forEach { match ->
            validateReference(
                contentRoot,
                documentEntry ?: scriptFile,
                match.groupValues[3],
                diagnostics
            )
        }
    }

    private fun validateReference(
        contentRoot: File,
        owner: File,
        rawReference: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val reference = rawReference.trim()
        if (reference.isEmpty() || IGNORED_REFERENCE_PREFIXES.any(reference::startsWith)) return
        if (reference.startsWith("//") || SCHEME_REGEX.containsMatchIn(reference)) {
            if (policy.forbidRemoteResources) {
                diagnostics += error(
                    code = "REMOTE_RESOURCE_FORBIDDEN",
                    message = "Remote project resources are not allowed",
                    path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
                )
            }
            return
        }
        val cleanReference = decodeReferencePath(reference) ?: run {
            diagnostics += error(
                code = "RESOURCE_PATH_INVALID",
                message = "Resource path is invalid",
                path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
            )
            return
        }
        if (cleanReference.isBlank()) return
        val candidate = if (cleanReference.startsWith('/')) {
            File(contentRoot, cleanReference.removePrefix("/"))
        } else {
            File(owner.parentFile, cleanReference)
        }
        val rootPath = contentRoot.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        if (!candidatePath.startsWith(rootPath)) {
            diagnostics += error(
                code = "RESOURCE_OUTSIDE_PROJECT",
                message = "Resource path escapes the project root",
                path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
            )
            return
        }
        if (!Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS)) {
            diagnostics += error(
                code = "LOCAL_RESOURCE_MISSING",
                message = "Referenced local resource does not exist",
                path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
            )
        }
    }

    private fun resolveManifestPath(
        root: File,
        path: String,
        field: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ): File? {
        return runCatching { WebProjectPaths.resolve(root, path) }
            .getOrElse { pathError ->
                diagnostics += error(
                    code = "MANIFEST_PATH_INVALID",
                    message = "$field: ${pathError.message}",
                    path = path
                )
                null
            }
    }

    private fun decodeReferencePath(reference: String): String? {
        return runCatching {
            val withoutFragment = reference.substringBefore('#').substringBefore('?')
            URI(withoutFragment).path ?: withoutFragment
        }.getOrNull()
    }

    companion object {
        private val PROJECT_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val HTML_ROOT_REGEX = Regex("<html(?:\\s|>)", RegexOption.IGNORE_CASE)
        private val HTML_END_REGEX = Regex("</html\\s*>", RegexOption.IGNORE_CASE)
        private val HTML_RESOURCE_REGEX = Regex(
            """<(script|link|img|source|audio|video|iframe)\b[^>]*?\b(src|href|poster)\s*=\s*(["'])([^"']+)\3""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_LINK_TAG_REGEX = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_SCRIPT_TAG_REGEX = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_ATTRIBUTE_REGEX = Regex(
            """\b([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"""
        )
        private val CSS_URL_REGEX = Regex(
            """url\(\s*(["']?)([^"')]+)\1\s*\)""",
            RegexOption.IGNORE_CASE
        )
        private val CSS_IMPORT_REGEX = Regex(
            """@import\s+(?:url\(\s*)?["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val JS_MODULE_REGEX = Regex(
            """(?:\b(?:import|export)\s+(?:[^;]*?\s+from\s+)?["']([^"']+)["']|\bimport\s*\(\s*["']([^"']+)["']\s*\))"""
        )
        private val JS_RUNTIME_RESOURCE_REGEX = Regex(
            """\b(fetch|Worker)\s*\(\s*(["'])([^"']+)\2"""
        )
        private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private val IGNORED_REFERENCE_PREFIXES = listOf(
            "#", "data:", "blob:", "javascript:", "mailto:", "tel:", "about:", "${'$'}{", "{{"
        )

        fun validateManifest(manifest: WebAppProjectManifest): List<WebProjectValidationDiagnostic> {
            val diagnostics = mutableListOf<WebProjectValidationDiagnostic>()
            if (manifest.schemaVersion != WebAppProjectManifest.CURRENT_SCHEMA_VERSION) {
                diagnostics += error(
                    code = "MANIFEST_SCHEMA_UNSUPPORTED",
                    message = "Unsupported manifest schema version ${manifest.schemaVersion}"
                )
            }
            if (!PROJECT_ID_REGEX.matches(manifest.projectId)) {
                diagnostics += error(
                    code = "PROJECT_ID_INVALID",
                    message = "projectId must contain 1-128 ASCII letters, digits, dots, underscores, or hyphens"
                )
            }
            if (manifest.displayName.isBlank() || manifest.displayName.length > 120) {
                diagnostics += error(
                    code = "DISPLAY_NAME_INVALID",
                    message = "displayName must contain 1-120 characters"
                )
            }
            validateManifestRelativePath(manifest.entryPoint, "entryPoint", diagnostics)
            if (!manifest.entryPoint.substringAfterLast('.', "").let {
                    it.equals("html", true) || it.equals("htm", true)
                }
            ) {
                diagnostics += error(
                    code = "ENTRY_POINT_TYPE_INVALID",
                    message = "entryPoint must be an HTML file",
                    path = manifest.entryPoint
                )
            }
            manifest.stateFile?.let { path ->
                validateManifestRelativePath(path, "stateFile", diagnostics)
                if (!path.substringAfterLast('.', "").equals("json", true)) {
                    diagnostics += error(
                        code = "STATE_FILE_TYPE_INVALID",
                        message = "stateFile must be a JSON file",
                        path = path
                    )
                }
            }
            val duplicatePaths = manifest.files
                .groupBy { it.path }
                .filterValues { it.size > 1 }
                .keys
            duplicatePaths.forEach { path ->
                diagnostics += error(
                    code = "MANIFEST_FILE_DUPLICATE",
                    message = "Manifest file path is duplicated",
                    path = path
                )
            }
            manifest.files.forEach { file ->
                validateManifestRelativePath(file.path, "files.path", diagnostics)
                file.sha256?.takeUnless(SHA256_REGEX::matches)?.let {
                    diagnostics += error(
                        code = "MANIFEST_FILE_HASH_INVALID",
                        message = "Manifest file SHA-256 must contain 64 hexadecimal characters",
                        path = file.path
                    )
                }
            }
            val entryDeclarations = manifest.files.filter { it.role == WebAppProjectFileRole.ENTRY }
            if (entryDeclarations.size > 1 ||
                entryDeclarations.singleOrNull()?.path?.let { it != manifest.entryPoint } == true
            ) {
                diagnostics += error(
                    code = "MANIFEST_ENTRY_CONFLICT",
                    message = "ENTRY file role must match entryPoint exactly"
                )
            }
            diagnostics += WebAppSpecValidator.validate(manifest)
            return diagnostics
        }

        private fun validateManifestRelativePath(
            path: String,
            field: String,
            diagnostics: MutableList<WebProjectValidationDiagnostic>
        ) {
            runCatching { WebProjectPaths.normalizeRelativePath(path) }
                .onFailure { error ->
                    diagnostics += error(
                        code = "MANIFEST_PATH_INVALID",
                        message = "$field: ${error.message}",
                        path = path
                    )
                }
        }

        private fun error(
            code: String,
            message: String,
            path: String? = null
        ) = WebProjectValidationDiagnostic(
            severity = WebProjectDiagnosticSeverity.ERROR,
            code = code,
            message = message,
            path = path
        )
    }
}

internal object WebProjectPaths {
    private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("^[A-Za-z]:[/\\\\].*")

    fun normalizeRelativePath(path: String, allowRoot: Boolean = false): String {
        if (allowRoot && (path.isBlank() || path == ".")) return ""
        if (path.isBlank()) throw WebAppProjectException("Path must not be blank")
        if (path.indexOf('\u0000') >= 0) throw WebAppProjectException("Path contains a NUL byte")
        if (path.startsWith('/') || path.startsWith('\\') || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path)) {
            throw WebAppProjectException("Path must be relative")
        }
        if (path.contains('\\')) throw WebAppProjectException("Path must use forward slashes")
        val segments = path.split('/')
        if (segments.any { it.isBlank() }) throw WebAppProjectException("Path contains an empty segment")
        if (segments.any { it == "." || it == ".." }) {
            throw WebAppProjectException("Path contains a traversal segment")
        }
        if (segments.any { it == WebAppProjectWorkspace.METADATA_DIRECTORY_NAME }) {
            throw WebAppProjectException("Path targets reserved project metadata")
        }
        return segments.joinToString("/")
    }

    fun resolve(root: File, relativePath: String): File {
        val normalized = normalizeRelativePath(relativePath)
        val canonicalRoot = root.canonicalFile
        val unresolved = File(canonicalRoot, normalized)
        ensureNoSymlinkSegments(canonicalRoot, unresolved)
        val canonicalTarget = unresolved.canonicalFile
        if (!canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) {
            throw WebAppProjectException("Path escapes the project root")
        }
        return canonicalTarget
    }

    fun relativePath(root: File, file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath())
            .joinToString("/") { it.toString() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    fun writeTextAtomically(target: File, content: String) {
        target.parentFile?.mkdirsOrThrow()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                stream.write(bytes)
                stream.fd.sync()
            }
            moveAtomically(temporary, target, replaceExisting = true)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun copyDirectory(source: File, target: File) {
        if (!source.isDirectory) throw WebAppProjectException("Copy source is not a directory: ${source.path}")
        if (target.exists()) throw WebAppProjectException("Copy target already exists: ${target.path}")
        target.mkdirsOrThrow()
        Files.walk(source.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val sourcePath = iterator.next()
                if (sourcePath == source.toPath()) continue
                val relative = source.toPath().relativize(sourcePath)
                val targetPath = target.toPath().resolve(relative)
                if (Files.isSymbolicLink(sourcePath)) {
                    throw WebAppProjectException("Symbolic links cannot be copied into a project version")
                }
                if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targetPath)
                } else if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    fun moveAtomically(source: File, target: File, replaceExisting: Boolean = false) {
        target.parentFile?.mkdirsOrThrow()
        val options = mutableListOf<StandardCopyOption>()
        if (replaceExisting) options += StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                *(options + StandardCopyOption.ATOMIC_MOVE).toTypedArray()
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), *options.toTypedArray())
        }
    }

    private fun ensureNoSymlinkSegments(root: File, target: File) {
        val rootPath = root.toPath()
        val unresolvedPath = target.absoluteFile.toPath().normalize()
        if (!unresolvedPath.startsWith(rootPath)) {
            throw WebAppProjectException("Path escapes the project root")
        }
        var current = rootPath
        for (segment in rootPath.relativize(unresolvedPath)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw WebAppProjectException("Symbolic links are not allowed in project paths")
            }
        }
    }
}

private fun File.mkdirsOrThrow() {
    if (!exists() && !mkdirs()) throw WebAppProjectException("Unable to create directory: $path")
    if (!isDirectory) throw WebAppProjectException("Path must be a directory: $path")
}
