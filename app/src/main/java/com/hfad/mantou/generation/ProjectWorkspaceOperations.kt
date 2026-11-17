package com.hfad.mantou.generation

import com.hfad.mantou.utils.AppGenerator
import com.hfad.mantou.utils.project.WebAppProjectFileRole
import com.hfad.mantou.utils.project.WebAppProjectManifest
import com.hfad.mantou.utils.project.WebAppProjectResolver
import com.hfad.mantou.utils.project.WebAppProjectSnapshot
import com.hfad.mantou.utils.project.WebAppProjectSnapshotKind
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import com.hfad.mantou.utils.project.WebAppSpecPolicy
import com.hfad.mantou.utils.project.WebProjectFileTool
import com.hfad.mantou.utils.project.WebProjectPlanCodec
import java.io.File
import java.util.Locale

internal object ProjectWorkspaceOperations {
    fun prepareExistingWebProject(input: File): PreparedWebProject {
        val workspace = WebAppProjectWorkspace()
        val resolved = WebAppProjectResolver.resolve(input)
        val draft = when (resolved.kind) {
            WebAppProjectSnapshotKind.LEGACY -> {
                val adoptedDraft = workspace.adoptLegacy(resolved)
                workspace.publishDraft(
                    projectRoot = adoptedDraft.projectRoot,
                    draftVersion = requireNotNull(adoptedDraft.version),
                    manifest = adoptedDraft.manifest
                )
                workspace.createDraft(adoptedDraft.projectRoot)
            }
            WebAppProjectSnapshotKind.RELEASE -> workspace.createDraft(resolved.projectRoot)
            WebAppProjectSnapshotKind.DRAFT -> resolved
        }
        val synchronizedDraft = ensureVisibleProjectPlan(draft)
        return PreparedWebProject(workspace, synchronizedDraft)
    }

    fun ensureVisibleProjectPlan(draft: WebAppProjectSnapshot): WebAppProjectSnapshot {
        val fileTool = WebProjectFileTool(draft.contentRoot)
        val visiblePlan = File(draft.contentRoot, PROJECT_PLAN_FILE_NAME)
        val parsedManifest = visiblePlan
            .takeIf(File::isFile)
            ?.let { planFile ->
                runCatching {
                    WebProjectPlanCodec.parse(planFile.readText()).manifest.copy(
                        projectId = draft.manifest.projectId,
                        stateFile = draft.manifest.stateFile
                    )
                }.getOrNull()
            }
            ?.takeIf { manifest ->
                manifest.entryPoint == draft.manifest.entryPoint &&
                    manifest.files.any { it.role == WebAppProjectFileRole.STYLE } &&
                    manifest.files.any { it.role == WebAppProjectFileRole.SCRIPT } &&
                    preservesHostOwnedAppSpec(draft.manifest, manifest)
            }

        val manifest = parsedManifest
            ?: draft.manifest.takeIf { it.appSpec != null }
            ?: buildMigrationManifest(draft, fileTool)
        val planJson = renderVisibleProjectPlan(manifest)
        if (!visiblePlan.isFile || visiblePlan.readText() != planJson) {
            fileTool.write(PROJECT_PLAN_FILE_NAME, planJson)
        }
        return draft.copy(
            manifest = manifest,
            entryFile = File(draft.contentRoot, manifest.entryPoint).canonicalFile
        )
    }

    fun buildMigrationManifest(
        draft: WebAppProjectSnapshot,
        fileTool: WebProjectFileTool
    ): WebAppProjectManifest {
        val existingFiles = fileTool.list(".", recursive = true)
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .filterNot { it == PROJECT_PLAN_FILE_NAME }
            .distinct()
            .toMutableList()
        if (draft.manifest.entryPoint !in existingFiles) {
            existingFiles.add(0, draft.manifest.entryPoint)
        }
        if (existingFiles.none { it.endsWith(".css", ignoreCase = true) }) {
            existingFiles += DEFAULT_PROJECT_STYLE_PATH
        }
        if (existingFiles.none {
                it.endsWith(".js", ignoreCase = true) || it.endsWith(".mjs", ignoreCase = true)
            }
        ) {
            existingFiles += DEFAULT_PROJECT_SCRIPT_PATH
        }
        val prioritizedFiles = existingFiles
            .distinct()
            .sortedWith(
                compareBy<String> { it != draft.manifest.entryPoint }
                    .thenBy { projectRoleForPath(it).ordinal }
                    .thenBy(String::lowercase)
            )
            .take(WEB_PROJECT_MAX_PLANNED_FILES)
        val requiredPaths = buildList {
            add(draft.manifest.entryPoint)
            if (DEFAULT_PROJECT_STYLE_PATH in existingFiles) add(DEFAULT_PROJECT_STYLE_PATH)
            if (DEFAULT_PROJECT_SCRIPT_PATH in existingFiles) add(DEFAULT_PROJECT_SCRIPT_PATH)
            addAll(prioritizedFiles)
        }.distinct().take(WEB_PROJECT_MAX_PLANNED_FILES)
        return draft.manifest.copy(
            files = buildList {
                add(
                    com.hfad.mantou.utils.project.WebAppProjectFile(
                        PROJECT_PLAN_FILE_NAME,
                        WebAppProjectFileRole.OTHER
                    )
                )
                requiredPaths.forEach { path ->
                    add(
                        com.hfad.mantou.utils.project.WebAppProjectFile(
                            path = path,
                            role = if (path == draft.manifest.entryPoint) {
                                WebAppProjectFileRole.ENTRY
                            } else {
                                projectRoleForPath(path)
                            }
                        )
                    )
                }
            }
        )
    }

    fun toolPolicyMetadata(
        tool: String,
        path: String?,
        policyCode: String
    ): Map<String, String> = buildMap {
        put("tool", tool)
        path?.let { put("path", it) }
        put("error_type", "PolicyRejected")
        put("failure_stage", "policy")
        put("policy_code", policyCode)
    }

    fun updateVisibleProjectPlan(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool,
        arguments: Map<String, String>
    ): com.hfad.mantou.utils.project.WebProjectToolExecutionResult {
        val content = arguments[WebProjectFileTool.ARG_CONTENT]
            ?: return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = "write_file requires argument: content",
                diagnostics = listOf("PROJECT_PLAN_CONTENT_MISSING"),
                metadata = mapOf(
                    "tool" to WebProjectFileTool.TOOL_WRITE_FILE,
                    "path" to PROJECT_PLAN_FILE_NAME,
                    "error_type" to "MissingArgument",
                    "failure_stage" to "arguments",
                    "policy_code" to "PROJECT_PLAN_CONTENT_MISSING"
                )
            )
        val parsedManifest = runCatching {
            WebProjectPlanCodec.parse(content).manifest.copy(
                projectId = binding.manifest.projectId,
                stateFile = binding.manifest.stateFile
            )
        }.getOrElse { error ->
            return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = error.message ?: "project.json 格式无效",
                diagnostics = listOf("PROJECT_PLAN_INVALID: ${error.message.orEmpty()}"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PROJECT_PLAN_FILE_NAME,
                    policyCode = "PROJECT_PLAN_INVALID"
                ) + ("error_type" to error::class.java.simpleName)
            )
        }
        if (parsedManifest.entryPoint != binding.manifest.entryPoint) {
            return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = "当前任务不能改变项目入口 ${binding.manifest.entryPoint}",
                diagnostics = listOf("PROJECT_ENTRY_CHANGE_FORBIDDEN"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PROJECT_PLAN_FILE_NAME,
                    policyCode = "PROJECT_ENTRY_CHANGE_FORBIDDEN"
                )
            )
        }
        if (!preservesHostOwnedAppSpec(binding.manifest, parsedManifest)) {
            return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = "当前任务不能删除或改写宿主已有的 AppSpec",
                diagnostics = listOf("PROJECT_APP_SPEC_CHANGE_FORBIDDEN"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PROJECT_PLAN_FILE_NAME,
                    policyCode = "PROJECT_APP_SPEC_CHANGE_FORBIDDEN"
                )
            )
        }

        val writeResult = projectFileTool.execute(
            WebProjectFileTool.TOOL_WRITE_FILE,
            arguments + (WebProjectFileTool.ARG_CONTENT to renderVisibleProjectPlan(parsedManifest))
        )
        if (!writeResult.success) return writeResult
        binding.manifest = parsedManifest
        return writeResult
    }

    fun synchronizeProjectManifest(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool
    ) {
        val planContent = projectFileTool.read(PROJECT_PLAN_FILE_NAME).content
        val parsedManifest = WebProjectPlanCodec.parse(planContent).manifest.copy(
            projectId = binding.manifest.projectId,
            stateFile = binding.manifest.stateFile
        )
        require(parsedManifest.entryPoint == binding.manifest.entryPoint) {
            "项目入口在发布前发生变化"
        }
        require(preservesHostOwnedAppSpec(binding.manifest, parsedManifest)) {
            "宿主已有的 AppSpec 在发布前发生变化"
        }
        binding.manifest = parsedManifest
    }

    fun preservesHostOwnedAppSpec(
        baseline: WebAppProjectManifest,
        candidate: WebAppProjectManifest
    ): Boolean {
        return WebAppSpecPolicy.preservesBaseline(
            baseline = baseline.appSpec,
            candidate = candidate.appSpec
        )
    }

    fun ensureProjectEntryIdentity(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool
    ) {
        if (!binding.entryFile.isFile) return
        val current = projectFileTool.read(binding.manifest.entryPoint).content
        val identified = AppGenerator.ensureWebAppIdentity(current)
        if (identified != current) {
            projectFileTool.write(binding.manifest.entryPoint, identified)
        }
    }

    fun projectFilePaths(projectFileTool: WebProjectFileTool): List<String> {
        return projectFileTool.list(".", recursive = true)
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .sorted()
            .toList()
    }

    fun renderVisibleProjectPlan(manifest: WebAppProjectManifest): String {
        return WebProjectPlanCodec.render(manifest)
    }

    fun projectRoleForPath(path: String): WebAppProjectFileRole {
        return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> WebAppProjectFileRole.OTHER
            "css" -> WebAppProjectFileRole.STYLE
            "js", "mjs" -> WebAppProjectFileRole.SCRIPT
            "json" -> WebAppProjectFileRole.DATA
            "svg", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico" -> {
                WebAppProjectFileRole.ASSET
            }
            else -> WebAppProjectFileRole.OTHER
        }
    }
}

internal data class PreparedWebProject(
    val workspace: WebAppProjectWorkspace,
    val snapshot: WebAppProjectSnapshot
)

internal class MutableWebProjectBinding(
    val workspace: WebAppProjectWorkspace,
    val projectRoot: File,
    val contentRoot: File,
    manifest: WebAppProjectManifest,
    val draftVersion: Long
) {
    var manifest: WebAppProjectManifest = manifest

    val entryFile: File
        get() = File(contentRoot, manifest.entryPoint).absoluteFile

    val declaredPaths: Set<String>
        get() = manifest.files.mapTo(linkedSetOf()) { it.path }
}


private const val PROJECT_PLAN_FILE_NAME = "project.json"
private const val DEFAULT_PROJECT_STYLE_PATH = "styles/app.css"
private const val DEFAULT_PROJECT_SCRIPT_PATH = "scripts/app.js"
private const val WEB_PROJECT_MAX_PLANNED_FILES = 24
