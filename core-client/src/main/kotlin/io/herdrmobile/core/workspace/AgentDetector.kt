package io.herdrmobile.core.workspace

import io.herdrmobile.core.model.Terminal

object AgentDetector {
    private val shells = setOf("sh","bash","dash","zsh","fish","ksh","csh","tcsh","pwsh","powershell","cmd")
    private fun base(word: String) = word.replace('\\','/').substringAfterLast('/').removeSuffix(".exe").lowercase()
    internal fun kind(program: String): AgentKind? {
        val name=base(program).substringBefore('@').removeSuffix(".js").removeSuffix(".mjs").removeSuffix(".cjs")
        return when {
            program == "@anthropic-ai/claude-code" || name in setOf("claude","claude-code") || program.contains("/@anthropic-ai/claude-code/") -> AgentKind.CLAUDE_CODE
            program == "@openai/codex" || name == "codex" || program.contains("/@openai/codex/") -> AgentKind.CODEX
            name in setOf("pi","pi-coding-agent") -> AgentKind.PI
            name in setOf("omp","oh-my-pi") -> AgentKind.OH_MY_PI
            name == "opencode" -> AgentKind.OPENCODE
            name in setOf("gemini","gemini-cli") -> AgentKind.GEMINI
            name == "copilot" -> AgentKind.COPILOT
            name in setOf("cursor","cursor-agent","agent") -> AgentKind.CURSOR
            name == "amp" -> AgentKind.AMP
            name == "grok" -> AgentKind.GROK
            else -> null
        }
    }
    fun detect(terminal: Terminal): AgentInfo? {
        if (terminal.state == "finished" || (terminal.reason != null && terminal.pid == null)) return null
        val foreground=terminal.foreground
        val original=foreground?.argv?.takeIf { it.isNotEmpty() } ?: terminal.argv
        val argv=unwrap(original)
        val program=argv.firstOrNull() ?: foreground?.name ?: return null
        if (base(program) in shells) return null // A shell wins over a stale Program Status.
        val known=kind(program)
        val reported=terminal.status?.app?.takeIf { it.isNotBlank() }?.let(::kind)
        if (known == null && (terminal.status == null || reported != null)) return null
        return AgentInfo(known ?: AgentKind.OTHER,base(program),argv,foreground?.pid)
    }
    /** Recognizes launch forms only; it never executes or expands shell text. */
    fun unwrap(argv: List<String>): List<String> {
        var words=argv
        repeat(10) {
            if (words.isEmpty()) return words
            val first=base(words[0])
            val next=when {
                first in setOf("exec","command") -> words.drop(1).dropWhile { it == "--" }
                first == "env" -> {
                    var i=1
                    while (i<words.size) {
                        when {
                            words[i] == "--" -> { i++; break }
                            words[i] in setOf("-u","--unset") -> i+=2
                            words[i].startsWith('-') || '=' in words[i] -> i++
                            else -> break
                        }
                    }
                    words.drop(i)
                }
                first in shells && words.size>4 && words[1].startsWith('-') && words[1].endsWith('c') &&
                    (words[2].contains("\"\$@\"") || words[2].contains("\$argv[2..]")) -> words.drop(4)
                first in shells && words.size>=3 && words[1] in setOf("-c","-lc","-ic") -> shellWords(words[2]) ?: words
                first in setOf("npx","npm","node","nodejs","python","python3","bun","deno","uv") -> {
                    val at=words.indices.drop(1).firstOrNull { kind(words[it]) != null }
                    if (at != null) words.drop(at) else words
                }
                else -> words
            }
            if (next == words) return words
            words=next
        }
        return words
    }
    internal fun shellWords(line: String): List<String>? {
        val result=mutableListOf<String>(); val word=StringBuilder()
        var quote: Char?=null; var escaped=false; var started=false
        for (c in line) {
            when {
                escaped -> { word.append(c); escaped=false; started=true }
                c == '\\' && quote != '\'' -> { escaped=true; started=true }
                quote != null -> if(c==quote) quote=null else word.append(c)
                c == '\'' || c == '"' -> { quote=c; started=true }
                c in ";|&<>`$\n\r" -> return null
                c.isWhitespace() -> if(started) { result+=word.toString(); word.clear(); started=false }
                else -> { word.append(c); started=true }
            }
        }
        if(quote!=null || escaped) return null
        if(started) result+=word.toString()
        return result.takeIf { it.isNotEmpty() }
    }
}
