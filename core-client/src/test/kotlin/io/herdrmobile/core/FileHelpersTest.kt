package io.herdrmobile.core

import io.herdrmobile.core.files.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.transcript.obj
import io.herdrmobile.core.transcript.text
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class FileHelpersTest {
    @Test fun changesClassifiesNonRepositoryStatusAndPreservesOtherFailures():Unit=runBlocking {
        data class Case(val code:Int?,val stderr:String,val timedOut:Boolean=false,val kind:String="io")
        val notRepository="fatal: not a git repository (or any of the parent directories): .git\n"
        for(case in listOf(
            Case(128,notRepository,kind="not_repository"),
            Case(1,notRepository),
            Case(128,"致命的: git リポジトリではありません（親ディレクトリも含む）: .git\n"),
            Case(128,"fatal: detected dubious ownership in repository at '/fixture'"),
            Case(128,"fatal: cannot change to '/missing': No such file or directory"),
            Case(null,notRepository,timedOut=true),
            Case(128,notRepository,timedOut=true),
            Case(null,notRepository),
        )) {
            val peer=FakeCoreTransport()
            peer.intercept={ request ->
                if(FakeCoreTransport.method(request)=="machine.exec") {
                    val stderr=Base64.getEncoder().encodeToString(case.stderr.toByteArray())
                    peer.respond(request,"""{"code":${case.code},"stdout":"","stderr":"$stderr","truncated":false,"timed_out":${case.timedOut}}""")
                    true
                } else false
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            try {
                client.connect()
                val error=assertFailsWith<HcError> { client.changes("/fixture",on="remote") }
                assertEquals(case.kind,error.kind,case.toString())
                val calls=peer.requests.filter { FakeCoreTransport.method(it)=="machine.exec" }
                assertEquals(1,calls.size,"Failed status must not run diff")
                val argv=calls.single()["params"].obj()["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(listOf("env","LC_ALL=C","LANGUAGE=","git"),argv.take(4))
                assertTrue("status" in argv)
            } finally { client.close(); scope.cancel() }
        }
    }
    @Test fun porcelainV2PreservesRenameAndUnmergedPathsIncludingNewlines() {
        val input="1 .M N... 100644 100644 100644 aaa bbb spaced name.txt\u0000"+
            "2 R. N... 100644 100644 100644 aaa bbb R100 renamed\nfile\u0000old\nfile\u0000"+
            "u UU N... 100644 100644 100644 100644 aaa bbb ccc conflict\u0000? new file\u0000"
        val files=parsePorcelain(input)
        assertEquals(listOf("spaced name.txt","renamed\nfile","conflict","new file"),files.map { it.path })
        assertEquals("old\nfile",files[1].oldPath)
        assertEquals("R",files[1].indexStatus)
        assertEquals("U",files[2].worktreeStatus)
        assertTrue(files.last().untracked)
    }
    @Test fun rpcHelpersPassMachineAndCwdAsDataAndDisableExecutableDiffFilters():Unit=runBlocking {
        val peer=FakeCoreTransport()
        peer.intercept={ request ->
            val p=request["params"].obj()
            when(FakeCoreTransport.method(request)) {
                "machine.exec" -> {
                    val args=p["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
                    val output=when {
                        "status" in args -> "? new file\u0000"
                        "--cached" in args -> "staged fixture"
                        "diff" in args -> "working fixture"
                        else -> "d\u00000\u0000folder\u0000f\u00001\u0000file\nname\u0000"
                    }
                    val b64=Base64.getEncoder().encodeToString(output.toByteArray())
                    peer.respond(request,"""{"code":0,"stdout":"$b64","stderr":"","truncated":false,"timed_out":false}"""); true
                }
                "file.read" -> { peer.respond(request,"""{"text":"second\n","first":2,"last":2,"lines":3}"""); true }
                else -> false
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
        try {
            client.connect()
            val cwd="/fixture/space '; literal"
            val changes=client.changes(cwd,on="remote")
            assertEquals("new file",changes.entries.single().path)
            assertEquals("working fixture",changes.diff)
            assertEquals("staged fixture",changes.stagedDiff)
            val path="folder '; literal"
            val files=client.listFiles(cwd,path,on="remote")
            assertTrue(files.first().directory)
            assertTrue(files.last().symlink)
            assertEquals(path+"/file\nname",files.last().path)
            assertEquals(FileChunk("second\n",2,2,3),client.readFile("text",cwd,2,1,"remote"))
            val calls=peer.requests.filter { FakeCoreTransport.method(it)=="machine.exec" }.map { it["params"].obj() }
            assertTrue(calls.all { it.text("cwd")==cwd && it.text("on")=="remote" })
            val git=calls.take(3).map { it["argv"]!!.jsonArray.map { a -> a.jsonPrimitive.content } }
            assertTrue(git.all { it.take(4)==listOf("env","LC_ALL=C","LANGUAGE=","git") })
            assertTrue(git.all { "--no-optional-locks" in it && "core.fsmonitor=false" in it })
            assertTrue(git.drop(1).all { "--no-ext-diff" in it && "--no-textconv" in it })
            assertEquals(path,calls.last()["argv"]!!.jsonArray.last().jsonPrimitive.content)
            assertFalse(peer.saw("doc.set"))
        } finally { client.close(); scope.cancel() }
    }
}
