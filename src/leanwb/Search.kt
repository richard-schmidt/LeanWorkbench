package leanwb

/**
 * The API search: Loogle or LeanSearch through the bridge, every hit checked
 * against the project's own Mathlib, and what a hit can do in a proof.
 */
enum class Engine(val wire: String, val label: String, val hint: String) {
    LOOGLE("loogle", "Loogle", "name · constants · |- conclusion · patterns with _"),
    LEANSEARCH("leansearch", "LeanSearch", "plain English"),
}

/**
 * A hit against this project's Mathlib, in the scope of the file being edited:
 * in scope, in a module this file does not import, not in this Mathlib at all, or
 * not checked (a name the bridge could not put to Lean).
 */
enum class HitState(val wire: String) {
    OK("ok"), IMPORT("import"), MISSING("missing"), UNCHECKED("unchecked");

    /** Can go into a proof as it is. UNCHECKED is allowed: Lean's check of the step is the judge. */
    val usable get() = this == OK || this == UNCHECKED

    companion object {
        fun of(s: String) = values().firstOrNull { it.wire == s } ?: throw Decode.ContractError("hit: unknown state `$s`")
    }
}

data class SearchHit(
    val name: String, val type: String, val module: String,
    val doc: String?, val informal: String?, val state: HitState,
)

data class SearchResult(
    val engine: String, val query: String, val count: Int, val hits: List<SearchHit>,
    val error: String?, val suggestions: List<String>,
)

/** A lemma pinned to the package's toolbox. */
data class Pinned(val name: String, val module: String, val type: String)

/** A module's source, read-only, and the line of the declaration looked for. */
data class Source(val module: String, val path: String, val text: String, val line: Int)

/** How a hit goes into the tactic field. */
enum class HitForm(val label: String) {
    NAME("name"), EXACT("exact"), APPLY("apply"), RW("rw");

    fun tactic(name: String): String = when (this) {
        NAME -> name
        EXACT -> "exact $name"
        APPLY -> "apply $name"
        RW -> "rw [$name]"
    }
}

object HitUse {
    /**
     * The tactic field after using [name] in [form]. A bare name goes in at the cursor.
     * A tactic form fills an empty field; in a field that already holds text it goes in
     * at the cursor too, so nothing typed is lost.
     */
    fun apply(text: String, selStart: Int, selEnd: Int, form: HitForm, name: String): Typed =
        if (form != HitForm.NAME && text.isBlank()) form.tactic(name).let { Typed(it, it.length) }
        else Snippet.insert(text, selStart, selEnd, form.tactic(name))

    /** A toolbox chip: `exact name` into an empty field, else the name at the cursor. */
    fun chip(text: String, selStart: Int, selEnd: Int, name: String): Typed =
        apply(text, selStart, selEnd, if (text.isBlank()) HitForm.EXACT else HitForm.NAME, name)
}

/** Adding an import to a file's text, so a hit from an unimported module can be used. */
object Imports {
    private val IMPORT = Regex("^import\\s+(\\S+)\\s*$")

    fun has(text: String, module: String): Boolean =
        text.split("\n").any { IMPORT.matchEntire(it.trim())?.groupValues?.get(1) == module }

    /**
     * [text] with `import [module]` after its last leading import (or first, if it has
     * none). Exactly one line is added, above every declaration: every line number
     * below it moves down by one. Unchanged if the import is already there.
     */
    fun add(text: String, module: String): String {
        if (has(text, module)) return text
        val lines = text.split("\n")
        // Leading imports sit before any other code; comments and blank lines may interleave.
        var last = -1
        for ((i, l) in lines.withIndex()) {
            val t = l.trim()
            if (IMPORT.matches(t)) last = i
            else if (t.isNotEmpty() && !t.startsWith("--") && !t.startsWith("/-")) break
        }
        val at = last + 1
        return (lines.subList(0, at) + "import $module" + lines.subList(at, lines.size)).joinToString("\n")
    }
}

object SearchDecode {
    private fun fail(what: String): Nothing = throw Decode.ContractError("bridge payload: bad or missing `$what`")

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, what: String) = v as? Map<String, Any?> ?: fail(what)
    private fun list(m: Map<String, Any?>, k: String) = m[k] as? List<*> ?: fail(k)
    private fun str(m: Map<String, Any?>, k: String) = m[k] as? String ?: fail(k)

    fun result(json: String): SearchResult {
        val o = obj(Json.parse(json), "payload")
        return SearchResult(
            engine = str(o, "engine"), query = str(o, "query"),
            count = (o["count"] as? Long)?.toInt() ?: fail("count"),
            hits = list(o, "hits").map {
                val h = obj(it, "hit")
                SearchHit(str(h, "name"), str(h, "type"), str(h, "module"), h["doc"] as String?,
                    h["informal"] as String?, HitState.of(str(h, "state")))
            },
            error = o["error"] as String?,
            suggestions = (o["suggestions"] as? List<*>)?.map { it as? String ?: fail("suggestions") } ?: emptyList(),
        )
    }

    fun toolbox(json: String): List<Pinned> = (Json.parse(json) as? List<*> ?: fail("toolbox")).map {
        val p = obj(it, "pinned")
        Pinned(str(p, "name"), p["module"] as? String ?: "", p["type"] as? String ?: "")
    }

    fun source(json: String): Source {
        val o = obj(Json.parse(json), "payload")
        return Source(str(o, "module"), str(o, "path"), str(o, "text"), (o["line"] as? Long)?.toInt() ?: fail("line"))
    }
}

object SearchRequests {
    fun search(engine: Engine, query: String, file: String?, text: String?): String = buildString {
        append("{\"engine\":").append(Json.quote(engine.wire)).append(",\"query\":").append(Json.quote(query))
        if (file != null) append(",\"file\":").append(Json.quote(file))
        if (file != null && text != null) append(",\"text\":").append(Json.quote(text))
        append("}")
    }

    fun pin(p: Pinned, pinned: Boolean): String =
        "{\"name\":" + Json.quote(p.name) + ",\"module\":" + Json.quote(p.module) + ",\"type\":" + Json.quote(p.type) +
            ",\"pinned\":" + pinned + "}"

    fun source(module: String, name: String): String =
        "{\"module\":" + Json.quote(module) + ",\"name\":" + Json.quote(name) + "}"
}

/** What trying a hit on the goal did: an error, or the step's effect and the goal left. */
data class TryOutcome(val ok: Boolean, val summary: String, val goal: String?)

object TryResult {
    /** [r] is the check of the text with the hit's tactic on [line]; nothing was kept. */
    fun of(r: CheckResult, line: Int): TryOutcome {
        val err = r.diagnosticsFor(line).firstOrNull { it.severity == "error" && !it.unsolvedGoals }
        if (err != null) return TryOutcome(false, err.headline, null)
        val step = Step.of(r.goalsAt(line)?.goals, r.goalsAfter(line)?.goals)
        val left = if (step is Step.Changed) step.diff.target else null
        return TryOutcome(true, step.summary().ifEmpty { "no error" }, left)
    }
}
