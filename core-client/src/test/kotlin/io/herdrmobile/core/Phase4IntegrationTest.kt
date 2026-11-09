package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** All files, process evidence and JSONL are synthetic and confined to a temporary directory. */
@EnabledIfEnvironmentVariable(named="HC_IT",matches="1")
class Phase4IntegrationTest {
    @Test fun labeledWorkspaceSyntheticChatAndReadOnlyFiles():Unit=runBlocking {
        val socket=IntegrationCore.ensureRunning()
        val directory=Files.createTempDirectory("herdr-mobile-phase4-")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { UnixTransport(socket) },scope,autoReconnect=false)
        val created=mutableListOf<Terminal>()
        var workspace:WorkspaceStore?=null
        var source:TranscriptSource?=null
        try {
            withTimeout(30000) {
                git(directory,"init","--quiet")
                Files.writeString(directory.resolve("tracked space.txt"),"before fixture\n")
                git(directory,"add","--","tracked space.txt")
                Files.writeString(directory.resolve("tracked space.txt"),"after fixture\n")
                Files.writeString(directory.resolve("untracked.txt"),"fixture file\nsecond line\n")
                val script=directory.resolve("claude")
                Files.writeString(script,"""
                    import os,json,sys
                    root=os.environ['CLAUDE_CONFIG_DIR']; pid=os.getpid()
                    session='herdr-mobile-fixture'
                    project=os.path.join(root,'projects','synthetic'); os.makedirs(project,exist_ok=True)
                    os.makedirs(os.path.join(root,'sessions'),exist_ok=True)
                    start=open('/proc/%d/stat'%pid).read().rsplit(')',1)[1].split()[19]
                    with open(os.path.join(project,session+'.jsonl'),'w') as f:
                        f.write(json.dumps({'type':'user','uuid':'fixture-u','parentUuid':None,'sessionId':session,'message':{'content':'Synthetic integration prompt'}})+'\\n')
                        f.write(json.dumps({'type':'assistant','uuid':'fixture-a','parentUuid':'fixture-u','sessionId':session,'message':{'id':'fixture-m','content':[{'type':'text','text':'Synthetic integration answer'}]}})+'\\n')
                    with open(os.path.join(root,'sessions','%d.json'%pid),'w') as f:
                        json.dump({'pid':pid,'procStart':start,'cwd':os.getcwd(),'sessionId':session},f)
                    print('fixture-ready',flush=True)
                    while sys.stdin.readline(): pass
                """.trimIndent().replace("\\\\n","\\n"))
                client.connect()
                workspace=WorkspaceStore(client,scope)
                val spaceId="herdr-mobile-it-"+System.nanoTime()
                val primary=HcJson.decodeFromJsonElement<Terminal>(client.call("terminal.run",buildJsonObject {
                    put("argv",JsonArray(listOf("python3",script.toString()).map(::JsonPrimitive)))
                    put("cwd",directory.toString())
                    put("env",buildJsonObject { put("CLAUDE_CONFIG_DIR",directory.resolve("agent-state").toString()) })
                    put("labels",buildJsonObject { put("herdr-mobile-test","1"); put("herdr.space",spaceId); put("herdr.tab","fixture-tab"); put("herdr.seen","0") })
                }))
                created+=primary
                val child=HcJson.decodeFromJsonElement<Terminal>(client.call("terminal.run",buildJsonObject {
                    put("argv",JsonArray(listOf("/bin/cat").map(::JsonPrimitive))); put("cwd",directory.toString()); put("parent",primary.id)
                    put("labels",buildJsonObject { put("herdr-mobile-test","1") })
                }))
                created+=child
                val spaces=workspace!!.spaces.first { spaces -> spaces.any { space -> space.id==spaceId && space.tabs.any { it.panes.any { pane -> pane.subAgents.any { sub -> sub.terminal.id==child.id } } } } }
                val tab=spaces.first { it.id==spaceId }.tabs.single()
                assertEquals(primary.id,tab.panes.single().terminal.id)
                assertEquals(listOf(child.id),tab.panes.single().subAgents.map { it.terminal.id })
                source=TranscriptSource(client,primary.id,scope,pollIntervalMs=50)
                val chat=source!!.items.first { it.any { row -> row is ChatItem.AssistantText } }
                assertEquals("Synthetic integration answer",chat.filterIsInstance<ChatItem.AssistantText>().single().markdown)
                assertEquals("Synthetic integration prompt",chat.filterIsInstance<ChatItem.UserMessage>().single().text)
                assertFalse((source!!.state.value as TranscriptState.Ready).binding.confirmed)
                val changes=client.changes(directory.toString())
                assertTrue(changes.entries.any { it.path=="tracked space.txt" })
                assertContains(changes.diff,"+after fixture")
                assertContains(changes.stagedDiff,"+before fixture")
                val files=client.listFiles(directory.toString())
                assertTrue(files.any { it.name=="untracked.txt" })
                assertFalse(files.any { it.name==".git" })
                assertEquals("second line\n",client.readFile("untracked.txt",directory.toString(),offset=2,limit=1).text)
            }
        } finally {
            withContext(NonCancellable) {
                source?.close(); workspace?.close()
                try {
                    for(terminal in created.asReversed()) {
                        try { withTimeout(10000) { client.kill(terminal.id) } }
                        finally { withTimeout(10000) { client.rm(terminal.id) } }
                    }
                } finally {
                    client.close(); scope.cancel()
                    Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
                }
            }
        }
    }
    private fun git(directory:Path,vararg arguments:String) {
        val process=ProcessBuilder(listOf("git","-c","core.hooksPath=/dev/null","-c","core.fsmonitor=false","-C",directory.toString())+arguments)
            .apply { environment()["GIT_CONFIG_GLOBAL"]="/dev/null"; environment()["GIT_CONFIG_NOSYSTEM"]="1" }
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS))
            assertEquals(0,process.exitValue(),process.inputStream.bufferedReader().readText())
        } finally { process.destroyForcibly() }
    }
}
