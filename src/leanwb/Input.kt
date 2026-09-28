package leanwb

/** A text field's content and cursor, without Compose: what the input helpers take and return. */
data class Typed(val text: String, val cursor: Int)

/** Text inserted at the cursor. `‸` in a snippet marks where the cursor lands (default: its end). */
object Snippet {
    const val CARET = '‸'

    fun insert(text: String, selStart: Int, selEnd: Int, snippet: String): Typed {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val at = snippet.indexOf(CARET)
        val clean = snippet.replace(CARET.toString(), "")
        // Keep words apart: when inserting (not replacing), a space before a snippet
        // that would glue onto the previous word.
        val pad = if (a == b && a > 0 && !text[a - 1].isWhitespace() && text[a - 1] !in "([{⟨," &&
            clean.firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true) " " else ""
        val out = text.substring(0, a) + pad + clean + text.substring(b)
        return Typed(out, a + pad.length + if (at >= 0) at else clean.length)
    }
}

/**
 * Lean's `\`-abbreviations: the full table the VS Code extension uses ([AbbrevTable]),
 * plus the project's own (passed as `extra`, which wins on a clash). An abbreviation
 * is replaced when a character that cannot continue it is typed (that character is
 * kept), as in the VS Code extension. `$CURSOR` in a symbol marks where the cursor
 * lands (`\<>` gives `⟨|⟩`).
 */
object Abbrev {
    const val CURSOR = "\$CURSOR"

    val table: Map<String, String> by lazy {
        AbbrevTable.chunks.flatMap { it.split("\n") }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    /** The short names people reach for first: ranked ahead of longer matches in [search]. */
    val common: List<String> = listOf(
        "and", "or", "not", "to", "iff", "all", "ex", "in", "notin", "sub", "ssub", "cup", "cap", "sup", "inf",
        "bot", "top", "le", "ge", "ne", "empty", "<", ">", "fun", "circ", "x", ".", "|-", "N", "Z", "Q", "R",
        "a", "b", "g", "sigma", "t", "0", "1", "2", "3", "u", "-1", "l", "r", "==", "~",
    )

    private val name = Regex("""\\([^\s\\]*)$""")

    /** The `\name` being typed just before [t]'s cursor, as (index of the `\`, name); null if none. */
    fun pending(t: Typed): Pair<Int, String>? {
        val m = name.find(t.text.substring(0, t.cursor)) ?: return null
        return m.range.first to m.groupValues[1]
    }

    /**
     * If the character just typed (before [t].cursor) ends a known `\name`, the
     * text with it replaced; otherwise null. `\` then a space yields no change.
     */
    fun expand(t: Typed, extra: Map<String, String> = emptyMap()): Typed? {
        val c = t.cursor
        if (c < 2) return null
        val typed = t.text[c - 1]
        val (start, key) = pending(Typed(t.text, c - 1)) ?: return null
        if (key.isEmpty()) return null
        // The typed character may itself continue the name ("\ne" then "g" is "\neg"): only
        // expand when the longer name is not an abbreviation or a prefix of one.
        if (!typed.isWhitespace() && (table.keys.any { it.startsWith(key + typed) } ||
                extra.keys.any { it.startsWith(key + typed) })) return null
        val sym = extra[key] ?: table[key] ?: return null
        return place(t.text.substring(0, start), sym, if (typed.isWhitespace() && CURSOR in sym) "" else typed.toString(),
            t.text.substring(c))
    }

    /** Replace the pending `\name` before the cursor by [symbol] (a tap on a search result). */
    fun accept(t: Typed, symbol: String): Typed {
        val (start, _) = pending(t) ?: return Snippet.insert(t.text, t.cursor, t.cursor, symbol.replace(CURSOR, ""))
        return place(t.text.substring(0, start), symbol, "", t.text.substring(t.cursor))
    }

    /** before + symbol + rest, with [typed] and the cursor at `$CURSOR` (else after the symbol and [typed]). */
    private fun place(before: String, sym: String, typed: String, rest: String): Typed {
        val at = sym.indexOf(CURSOR)
        return if (at < 0) Typed(before + sym + typed + rest, before.length + sym.length + typed.length)
        else {
            val head = sym.substring(0, at) + typed
            Typed(before + head + sym.substring(at + CURSOR.length) + rest, before.length + head.length)
        }
    }

    /**
     * Abbreviations starting with [prefix], one per symbol, best first: an exact name,
     * then the project's, then the common short names, then shorter names. An empty
     * prefix lists the common ones.
     */
    fun search(prefix: String, extra: Map<String, String> = emptyMap(), limit: Int = 24): List<Pair<String, String>> {
        val rank = common.withIndex().associate { (i, k) -> k to i }
        val all = (extra.entries.map { Triple(it.key, it.value, true) } +
            table.entries.filter { it.key !in extra }.map { Triple(it.key, it.value, false) })
            .filter { it.first.startsWith(prefix) && (prefix.isNotEmpty() || it.first in rank) }
        val sorted = all.sortedWith(compareBy<Triple<String, String, Boolean>>(
            { it.first != prefix }, { !it.third }, { rank[it.first] ?: Int.MAX_VALUE }, { it.first.length }, { it.first }))
        val seen = HashSet<String>()
        return sorted.filter { seen.add(it.second) }.take(limit).map { it.first to it.second }
    }
}

/** What the palette offers. Order is the order on screen. */
object Palette {
    val tactics = listOf(
        "intro ‸", "exact ‸", "exact?", "apply ‸", "apply?", "simp", "simp?", "simp only [‸]", "simp_all",
        "rw [‸]", "rw?", "rcases ‸ with ⟨⟩", "obtain ⟨‸⟩ := ", "cases ‸", "constructor", "refine ⟨‸⟩",
        "use ‸", "ext ‸", "rfl", "aesop", "omega", "decide", "have ‸ :  := by", "subst ‸", "left",
        "right", "exfalso", "contradiction", "unfold ‸", "specialize ‸",
    )
    val symbols = listOf(
        "⟨", "⟩", "→", "←", "↔", "∀", "∃", "∧", "∨", "¬", "∈", "∉", "⊆", "∪", "∩", "⊔", "⊓", "⊥",
        "≤", "≠", "·", "∘", "×", "₁", "₂", "'", "|", "_", "[", "]", "(", ")", "{", "}", ":=", "<;>",
    )
}

/** A `Try this` from exact?, apply?, simp?, rw? and friends. [note] is the goal it leaves, if Lean says. */
data class Suggestion(val tactic: String, val note: String?)

object Suggestions {
    /** Every suggestion in [diags], in order. Messages that are not a `Try this` are ignored. */
    fun of(diags: List<Diagnostic>): List<Suggestion> = diags.flatMap { d ->
        val lines = d.message.lines()
        if (lines.firstOrNull()?.trim()?.startsWith("Try th") != true) return@flatMap emptyList()
        val out = mutableListOf<Suggestion>()
        for (l in lines.drop(1).map { it.trim() }) when {
            l.startsWith("[apply] ") -> out += Suggestion(l.removePrefix("[apply] ").trim(), null)
            l.startsWith("-- ") && out.isNotEmpty() ->
                out[out.size - 1] = out.last().copy(note = l.removePrefix("-- ").trim())
        }
        out
    }
}

/** A completion offered by the bridge (/v1/complete). kind 6 is a local hypothesis. */
data class Completion(val label: String, val kind: Int?)

object Complete {
    /**
     * [t] with the [fragment] just before the cursor replaced by [label] (the bridge's
     * fragment is the part after the last `.`, so `Finset.union_co` + `union_comm` gives
     * `Finset.union_comm`). If the text before the cursor does not end with [fragment]
     * (the user typed on meanwhile), the label is inserted at the cursor instead.
     */
    fun accept(t: Typed, fragment: String, label: String): Typed {
        val before = t.text.substring(0, t.cursor)
        val start = if (before.endsWith(fragment)) t.cursor - fragment.length else t.cursor
        return Typed(t.text.substring(0, start) + label + t.text.substring(t.cursor), start + label.length)
    }

    /** Whether the cursor is in or just after a name, i.e. worth asking for completions. */
    fun wanted(t: Typed): Boolean {
        if (t.cursor == 0) return false
        val c = t.text[t.cursor - 1]
        return c == '.' || c.isLetterOrDigit() || c == '_' || c == '\'' || c in "₀₁₂₃₄₅₆₇₈₉"
    }
}
