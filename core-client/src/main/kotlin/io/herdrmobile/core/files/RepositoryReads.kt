package io.herdrmobile.core.files

import io.herdrmobile.core.HcClient
import io.herdrmobile.core.rpc.HcError

internal suspend fun readChanges(client:HcClient,cwd:String,on:String?):ChangeSet {
    // Read-only git options avoid index refresh/fsmonitor writes and external diff/textconv programs.
    val git=listOf("git","--no-pager","--no-optional-locks","-c","core.fsmonitor=false","-c","diff.autoRefreshIndex=false",
        "-c","core.hooksPath=/dev/null","-c","color.ui=false")
    val status=client.execRead(git+listOf("status","--porcelain=v2","-z","--untracked-files=normal"),cwd,on).requireSuccess()
    if(status.truncated) throw HcError("unsupported","Git status exceeds the response limit")
    val diff=client.execRead(git+listOf("diff","--no-ext-diff","--no-textconv","--"),cwd,on).requireSuccess()
    val staged=client.execRead(git+listOf("diff","--cached","--no-ext-diff","--no-textconv","--"),cwd,on).requireSuccess()
    return ChangeSet(cwd,parsePorcelain(status.stdout.decodeToString()),diff.stdout.decodeToString(),staged.stdout.decodeToString(),diff.truncated || staged.truncated)
}

/** NUL parsing preserves spaces/newlines in names and consumes rename's separate original path. */
internal fun parsePorcelain(text:String):List<ChangeEntry> {
    val fields=text.split('\u0000'); val entries=mutableListOf<ChangeEntry>(); var index=0
    while(index<fields.size) {
        val record=fields[index++]
        when(record.firstOrNull()) {
            '?' -> entries+=ChangeEntry(record.removePrefix("? "),indexStatus="?",worktreeStatus="?",untracked=true)
            '1','2','u' -> {
                val count=when(record[0]) { '1' -> 9; '2' -> 10; else -> 11 }
                val columns=record.split(' ',limit=count)
                if(columns.size!=count || columns[1].length!=2) throw HcError("io","Malformed porcelain v2 status")
                val previous=if(record[0]=='2') fields.getOrNull(index++) else null
                entries+=ChangeEntry(columns.last(),previous,columns[1][0].toString(),columns[1][1].toString())
            }
            '#','!',null -> Unit
            else -> throw HcError("unsupported","Unrecognized porcelain v2 record")
        }
    }
    return entries
}

internal suspend fun readDirectory(client:HcClient,cwd:String,path:String,on:String?):List<FileEntry> {
    require(path.isNotEmpty() && '\u0000' !in path)
    val script="""
        test -d "${'$'}1" || exit 2
        n=0
        for p in "${'$'}1"/* "${'$'}1"/.[!.]* "${'$'}1"/..?*; do
            test -e "${'$'}p" || test -L "${'$'}p" || continue
            name=${'$'}{p##*/}
            test "${'$'}name" = .git && continue
            n=${'$'}((n+1)); test "${'$'}n" -le 10000 || exit 75
            kind=f; test -d "${'$'}p" && kind=d
            link=0; test -L "${'$'}p" && link=1
            printf '%s\000%s\000%s\000' "${'$'}kind" "${'$'}link" "${'$'}name"
        done
        exit 0
    """.trimIndent()
    val result=client.execRead(listOf("sh","-c",script,"herdr-mobile-files",path),cwd,on)
    if(result.truncated || result.code==75) throw HcError("unsupported","Directory listing exceeds 10000 entries or the response limit")
    result.requireSuccess()
    val fields=result.stdout.decodeToString().split('\u0000').dropLast(1)
    if(fields.size%3!=0) throw HcError("io","Invalid directory response")
    return fields.chunked(3).map { (kind,link,name) ->
        FileEntry(name,if(path==".") name else path.trimEnd('/')+"/"+name,kind=="d",link=="1")
    }.sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name })
}
