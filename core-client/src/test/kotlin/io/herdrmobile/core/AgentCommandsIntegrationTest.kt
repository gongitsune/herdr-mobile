package io.herdrmobile.core

import io.herdrmobile.core.files.execRead
import io.herdrmobile.core.files.requireSuccess
import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.usage.*
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import kotlin.test.*

/** Real CLIs, via the dedicated core. The user's agent state is mounted read-only. */
@EnabledIfEnvironmentVariable(named = "HC_IT", matches = "1")
class AgentCommandsIntegrationTest {
    @Test fun claudeInitializeDiscoversCommandsInTheRequestedDirectory(): Unit = runBlocking {
        checkCatalog(AgentKind.CLAUDE_CODE)
    }

    @Test fun codexAppServerDiscoversEnabledSkillsInTheRequestedDirectory(): Unit = runBlocking {
        checkCatalog(AgentKind.CODEX)
    }

    private suspend fun checkCatalog(kind: AgentKind) {
        val directory = Files.createTempDirectory("herdr-mobile-command-it-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HcClient(TransportFactory { UnixTransport(IntegrationCore.ensureRunning()) }, scope, autoReconnect = false)
        try {
            val fixture = if (kind == AgentKind.CLAUDE_CODE) directory.resolve(".claude/commands/mobile-p11-fixture.md")
                else directory.resolve(".agents/skills/mobile-p11-fixture/SKILL.md")
            Files.createDirectories(fixture.parent)
            Files.writeString(fixture, """
                ---
                name: mobile-p11-fixture
                description: Herdr mobile catalog integration fixture
                argument-hint: <file>
                ---
                This fixture is listed only. Never execute it.
            """.trimIndent())
            val scriptName = if (kind == AgentKind.CLAUDE_CODE) "claude_account.py" else "codex_commands.py"
            val script = javaClass.getResource("/io/herdrmobile/core/usage/$scriptName")!!.readText()
            client.connect()
            // bubblewrap is a test-only dependency. A temporary Codex runtime avoids writes to the user's state DB.
            val wrapper = """
                import json,os,pathlib,shutil,subprocess,sys,tempfile
                kind,script,cwd=sys.argv[1:]
                def finish(error):
                    print(json.dumps(dict(commands=[],error=error))); sys.exit(0)
                if not shutil.which('bwrap'): finish('sandbox_missing')
                lookup=subprocess.run(['sh','-lc','command -v '+kind],capture_output=True,text=True,timeout=3)
                if lookup.returncode!=0 or not lookup.stdout.strip(): finish('program_missing')
                with tempfile.TemporaryDirectory(prefix='herdr-mobile-catalog-runtime-') as temporary:
                    env=os.environ.copy()
                    if kind=='codex':
                        original=pathlib.Path(env.get('CODEX_HOME',str(pathlib.Path.home()/'.codex')))
                        for name in ('config.toml','skills','plugins'):
                            source=original/name
                            if source.exists(): (pathlib.Path(temporary)/name).symlink_to(source)
                        env['CODEX_HOME']=temporary
                    argv=['python3','-c',script]+(['commands',cwd] if kind=='claude' else [cwd])
                    result=subprocess.run(['bwrap','--ro-bind','/','/','--dev-bind','/dev','/dev','--proc','/proc',
                                           '--bind','/tmp','/tmp','--ro-bind',cwd,cwd,'--']+argv,
                                          env=env,capture_output=True,text=True,timeout=23)
                    if result.returncode!=0: finish('sandbox_failed')
                    print(result.stdout,end='')
            """.trimIndent()
            val result = withTimeout(30_000) {
                client.execRead(listOf("python3", "-c", wrapper,
                    if (kind == AgentKind.CLAUDE_CODE) "claude" else "codex", script, directory.toString()),
                    cwd = directory.toString(), timeout = 28).requireSuccess()
            }
            assertFalse(result.truncated)
            val text = result.stdout.decodeToString()
            val root = HcJson.parseToJsonElement(text).jsonObject
            val error = root["error"]?.jsonPrimitive?.content
            assumeTrue(error != "program_missing", "Agent CLI is not installed")
            assumeTrue(error != "sandbox_missing", "Read-only integration sandbox requires bubblewrap")
            assertNull(error, "Read-only catalog exchange failed: $error")
            val commands = assertNotNull(parseAgentCommands(kind, text))
            val command = commands.single { it.name == "mobile-p11-fixture" }
            assertTrue(command.description.contains("Herdr mobile catalog integration fixture"))
            assertEquals(CommandRun.CHAT, command.run)
            assertEquals(if (kind == AgentKind.CODEX) '$' else '/', command.trigger)
            assertEquals(if (kind == AgentKind.CODEX) "skill" else "custom", command.source)
            assertEquals(CommandRun.TERMINAL, commands.single { it.name == "permissions" }.run)
            println("herdr-mobile-it $kind catalog: ${commands.size} commands, cwd fixture found")
        } finally {
            client.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }
}
