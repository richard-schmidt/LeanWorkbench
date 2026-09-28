package leanwb

/** The bridge's /v1/check payload (lean-mcp core.check_payload), as values. */
data class Hyp(val names: List<String>, val type: String) {
    /** The type for display: a type Lean moved below `h :` loses its leading break and indent. */
    val displayType: String get() = type.trimIndent()
}

data class Goal(val case: String?, val hyps: List<Hyp>, val target: String)

data class Diagnostic(val line: Int, val col: Int, val endLine: Int, val severity: String, val message: String) {
    /**
     * The message up to its first blank line. Lean follows a tactic error with the
     * full goal state, which the goal panel already shows.
     */
    val headline: String get() =
        if (unsolvedGoals) "unsolved goals" else message.split("\n\n", limit = 2)[0].trimEnd()

    /**
     * Lean's report that a `by` block ended with goals left. Mid-proof this is the normal
     * state, and the goal panel already shows those goals.
     */
    val unsolvedGoals: Boolean get() = severity == "error" && message.startsWith("unsolved goals")
    val hasMore: Boolean get() = headline.length < message.trimEnd().length
}

enum class DeclStatus { OK, SORRY, ERROR }

/** Declaration kinds that are notation commands; their name is the symbol, e.g. `⋆`. */
val notationKinds = setOf("notation", "infixl", "infixr", "infix", "prefix", "postfix", "macro", "macro_rules", "syntax")

data class Declaration(
    val line: Int, val endLine: Int, val kind: String, val name: String?, val status: DeclStatus,
) {
    /** What to call it in a list: anonymous instances and examples have no name. */
    val label: String get() = when {
        name == null -> "($kind at line $line)"
        kind in notationKinds -> "$kind $name"
        else -> name
    }
}

/** Goals before [line]; null when Lean has no tactic state there (e.g. outside a `by` block). */
data class GoalsAt(val line: Int, val goals: List<Goal>?)

data class CheckResult(
    val file: String,
    val seconds: Double,
    val text: String,
    val diagnostics: List<Diagnostic>,
    val declarations: List<Declaration>,
    /** Goals before each requested line. */
    val goals: List<GoalsAt>,
    /** Goals at the end of each requested line: the state after its tactic. */
    val goalsAfter: List<GoalsAt>,
    /** Lean's semantic tokens for [text]. A bridge from before them sends none. */
    val tokens: List<LeanToken> = emptyList(),
) {
    fun diagnosticsFor(line: Int): List<Diagnostic> = diagnostics.filter { it.line == line }
    fun goalsAt(line: Int): GoalsAt? = goals.firstOrNull { it.line == line }
    fun goalsAfter(line: Int): GoalsAt? = goalsAfter.firstOrNull { it.line == line }
}

/** Decoding refuses a payload that breaks the contract instead of filling in defaults. */
object Decode {
    class ContractError(message: String) : IllegalArgumentException(message)

    fun check(json: String): CheckResult {
        val m = obj(Json.parse(json), "payload")
        return CheckResult(
            file = str(m, "file"),
            seconds = (m["seconds"] as? Number)?.toDouble() ?: fail("seconds"),
            text = str(m, "text"),
            diagnostics = list(m, "diagnostics").map { d ->
                val o = obj(d, "diagnostic")
                Diagnostic(int(o, "line"), int(o, "col"), int(o, "end_line"), str(o, "severity"), str(o, "message"))
            },
            declarations = list(m, "declarations").map { d ->
                val o = obj(d, "declaration")
                Declaration(int(o, "line"), int(o, "end_line"), str(o, "kind"), o["name"] as String?,
                    when (str(o, "status")) {
                        "ok" -> DeclStatus.OK
                        "sorry" -> DeclStatus.SORRY
                        "error" -> DeclStatus.ERROR
                        else -> fail("status")
                    })
            },
            goals = list(m, "goals").map { goalsAt(it) },
            goalsAfter = list(m, "goals_after").map { goalsAt(it) },
            tokens = (m["tokens"] ?: emptyList<Any?>()).let { v -> (v as? List<*> ?: fail("tokens")).map { token(it) } },
        )
    }

    /** `[line, col, length, kind]`, as lean-mcp core.decode_tokens writes it. */
    private fun token(v: Any?): LeanToken {
        val a = v as? List<*> ?: fail("token")
        if (a.size != 4) fail("token")
        val n = a.take(3).map { (it as? Long)?.toInt() ?: fail("token") }
        return LeanToken(n[0], n[1], n[2], a[3] as? String ?: fail("token"))
    }

    private fun goalsAt(v: Any?): GoalsAt {
        val o = obj(v, "goals entry")
        return GoalsAt(int(o, "line"), (o["goals"] as List<*>?)?.map { goal(it) })
    }

    /** /v1/complete -> (fragment, completions). */
    fun completions(json: String): Pair<String, List<Completion>> {
        val m = obj(Json.parse(json), "payload")
        return str(m, "fragment") to list(m, "items").map {
            val o = obj(it, "completion")
            Completion(str(o, "label"), (o["kind"] as? Long)?.toInt())
        }
    }

    fun files(json: String): List<String> = list(obj(Json.parse(json), "payload"), "files").map { it as String }

    fun overview(json: String): List<FileInfo> = list(obj(Json.parse(json), "payload"), "files").map {
        val o = obj(it, "file")
        FileInfo(str(o, "path"), o["title"] as String?, int(o, "decls"), int(o, "sorries"),
            o["empty"] as? Boolean ?: throw ContractError("file: bad or missing `empty`"), str(o, "role"))
    }

    /** The project's abbreviations: an object of name -> symbol. */
    fun abbreviations(json: String): Map<String, String> = obj(Json.parse(json), "payload").entries.associate { (k, v) ->
        (k as String) to (v as? String ?: throw ContractError("abbreviations: `$k` is not a string"))
    }

    /** The bridge's `{"error": ...}` body, or null if this is not one. */
    fun error(json: String): String? =
        try { (Json.parse(json) as? Map<*, *>)?.get("error") as? String } catch (e: Json.Error) { null }

    private fun goal(v: Any?): Goal {
        val o = obj(v, "goal")
        return Goal(
            case = o["case"] as String?,
            hyps = list(o, "hyps").map { h ->
                val ho = obj(h, "hyp")
                Hyp(list(ho, "names").map { it as String }, str(ho, "type"))
            },
            target = str(o, "target"),
        )
    }

    private fun fail(what: String): Nothing = throw ContractError("bridge payload: bad or missing `$what`")

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, what: String) = v as? Map<String, Any?> ?: fail(what)
    private fun list(m: Map<String, Any?>, k: String) = m[k] as? List<*> ?: fail(k)
    private fun str(m: Map<String, Any?>, k: String) = m[k] as? String ?: fail(k)
    private fun int(m: Map<String, Any?>, k: String) = (m[k] as? Long)?.toInt() ?: fail(k)
}

object Requests {
    fun complete(file: String, text: String, line: Int, col: Int): String =
        "{\"file\":" + Json.quote(file) + ",\"text\":" + Json.quote(text) + ",\"line\":" + line + ",\"col\":" + col + "}"

    /** A save of [text], allowed only if the disk still holds [base] (the text as loaded). */
    fun save(file: String, text: String, base: String): String =
        "{\"file\":" + Json.quote(file) + ",\"text\":" + Json.quote(text) + ",\"base\":" + Json.quote(base) + "}"

    /** Set the project abbreviation [name] (empty [symbol]: remove it). */
    fun abbreviation(name: String, symbol: String): String =
        "{\"name\":" + Json.quote(name) + ",\"symbol\":" + Json.quote(symbol) + "}"

    /** A new file with [text]; the bridge refuses an existing one and registers the import. */
    fun create(file: String, text: String): String =
        "{\"file\":" + Json.quote(file) + ",\"text\":" + Json.quote(text) + "}"

    fun check(file: String, text: String?, goalsAt: List<Int>, goalsAfter: List<Int> = emptyList()): String = buildString {
        append("{\"file\":").append(Json.quote(file))
        if (text != null) append(",\"text\":").append(Json.quote(text))
        append(",\"goals_at\":[").append(goalsAt.joinToString(","))
        append("],\"goals_after\":[").append(goalsAfter.joinToString(",")).append("]}")
    }
}
