package com.hfad.mantou.utils.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hfad.mantou.utils.harness.HarnessSingleActionProtocol
import com.hfad.mantou.utils.project.ConversationWorkspace
import com.hfad.mantou.utils.project.WebProjectFileTool
import com.hfad.mantou.utils.project.WorkspaceFileActionProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class AndroidLinuxWorkspaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireRuntime() {
        val available = AndroidLinuxWorkspace.isAvailable(context)
        if (InstrumentationRegistry.getArguments().getString("requireLinuxRuntime") == "true") {
            assertTrue("Linux assets must be packaged in the APK", available)
        } else {
            assumeTrue(available)
        }
    }

    @Test
    fun runsModelGeneratedScriptWithOnlyTheNamedFiles() = runBlocking {
        requireRuntime()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "linux-workspace-").toFile()
        try {
            File(root, "input.csv").writeText("id,name\n1,甲\n1,甲\n2,乙\n")
            val privateFile = File(root, "private.txt").apply { writeText("outside") }
            val body = Gson().toJson(mapOf(
                "language" to "python",
                "inputs" to listOf("input.csv"),
                "output" to "clean.csv",
                "script" to """
                    import csv, os, sqlite3
                    from pathlib import Path
                    assert os.uname().sysname == 'Linux' and os.getuid() == 65534
                    assert not Path('private.txt').exists()
                    assert not Path(${Gson().toJson(privateFile.absolutePath)}).exists()
                    assert set(os.listdir('/sys/class/net')) == {'lo'}
                    with open('input.csv', encoding='utf-8') as source:
                        rows = list(dict.fromkeys(tuple(row) for row in csv.reader(source)))
                    with open('clean.csv', 'w', encoding='utf-8') as target:
                        csv.writer(target, lineterminator='\n').writerows(rows)
                    print(sqlite3.connect(':memory:').execute('select 1').fetchone()[0])
                """.trimIndent()
            ))
            val turn = HarnessSingleActionProtocol.parse(
                "<mantou-run-linux>$body</mantou-run-linux>", "linux-test", linuxEnabled = true
            )
            val call = turn.toolCalls.single()
            assertEquals(RUN_LINUX_TOOL, call.name)
            val job = LinuxWorkspaceJob.parse(call.arguments.getValue("content"))
            val result = AndroidLinuxWorkspace.run(context, root, job)
            assertEquals(result.log, 0, result.exitCode)
            assertEquals("1", result.log.trim())
            assertFalse(File(root, "clean.csv").exists())
            WebProjectFileTool(root).writeLinuxOutput("clean.csv", checkNotNull(result.output))
            assertEquals("id,name\n1,甲\n2,乙\n", File(root, "clean.csv").readText())
            assertEquals("outside", privateFile.readText())
            assertTrue(File(root, "input.csv").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normalWorkspaceCanSaveShellOutputAndTheGuestCannotPersistOtherChanges() = runBlocking {
        requireRuntime()
        val parent = Files.createTempDirectory(context.cacheDir.toPath(), "linux-chat-").toFile()
        try {
            val workspaces = ConversationWorkspace(parent)
            val scope = workspaces.create(null, "测试")
            val root = checkNotNull(workspaces.linuxDirectory(scope))
            val body = Gson().toJson(mapOf(
                "language" to "shell",
                "inputs" to emptyList<String>(),
                "output" to "result.txt",
                "script" to "set -e; printf 'local shell' >/tmp/scratch; cat /tmp/scratch > result.txt; touch temporary.txt"
            ))
            val call = WorkspaceFileActionProtocol.parse(
                "<mantou-run-linux>$body</mantou-run-linux>", "shell-test", linuxEnabled = true
            )
            assertEquals(RUN_LINUX_TOOL, call.name)
            val job = LinuxWorkspaceJob.parse(call.arguments.getValue("content"))
            val result = AndroidLinuxWorkspace.run(context, root, job)
            assertEquals(result.log, 0, result.exitCode)
            workspaces.saveLinuxOutput(root, "result.txt", checkNotNull(result.output))
            assertEquals("local shell", File(root, "result.txt").readText())
            assertFalse(File(root, "temporary.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }
}
