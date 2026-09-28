package leanwb

/**
 * Code colouring in two layers: a lexical scanner, then Lean's semantic tokens
 * from the last check for what only Lean knows (locals, fields).
 */
enum class Code { KEYWORD, TACTIC, DECL, LOCAL, FIELD, HOLE, SYMBOL, ATTR, COMMENT, LITERAL, SORRY }

/** [start, end) in UTF-16 units of the text it was made for. */
data class Span(val start: Int, val end: Int, val kind: Code)

/** One of Lean's semantic tokens: 1-based line, UTF-16 column and length, and its kind. */
data class LeanToken(val line: Int, val col: Int, val length: Int, val kind: String)

object Lexer {
    val COMMANDS = setOf(
        "def", "theorem", "lemma", "abbrev", "example", "structure", "class", "inductive", "instance", "where",
        "extends", "deriving", "namespace", "section", "end", "open", "variable", "universe", "import",
        "noncomputable", "private", "protected", "partial", "mutual", "set_option", "attribute", "notation",
        "infix", "infixl", "infixr", "prefix", "postfix", "macro", "macro_rules", "syntax", "elab", "local",
        "scoped", "fun", "λ", "let", "have", "show", "from", "by", "at", "with", "using", "only", "match",
        "if", "then", "else", "do", "return", "calc", "suffices", "in", "for", "Type", "Prop", "Sort",
    )
    val TACTICS = setOf(
        "exact", "exacts", "apply", "intro", "intros", "rintro", "rw", "rwa", "rewrite", "simp", "simp_all", "simpa",
        "dsimp", "rcases", "obtain", "refine", "refine'", "constructor", "subst", "ext", "funext", "use", "exists",
        "cases", "induction", "linarith", "nlinarith", "omega", "aesop", "norm_num", "unfold", "exfalso",
        "contradiction", "assumption", "trivial", "rfl", "decide", "ring", "field_simp", "positivity", "gcongr",
        "specialize", "generalize", "by_contra", "by_cases", "push_neg", "split_ifs", "split", "congr", "convert",
        "change", "left", "right", "first", "repeat", "all_goals", "any_goals", "try", "tauto", "infer_instance",
        "classical", "nofun", "nomatch", "symm", "trans", "apply_fun", "lift", "set", "clear", "revert",
        "injection", "rename_i", "next", "case", "focus", "done", "absurd", "choose", "filter_upwards", "norm_cast",
        "push_cast", "exact_mod_cast", "fin_cases", "interval_cases", "bound", "grind",
    )
    private val DECLARES = setOf("def", "theorem", "lemma", "abbrev", "structure", "class", "inductive", "instance")
    private val SORRIES = setOf("sorry", "admit")

    /** Operators coloured as symbols; `:` and `|` only as whole tokens (see [symbolAt]). */
    private const val SYMBOL_CHARS = "→←↔∀∃∈∉∧∨¬⊢⟨⟩∘×≤≥≠⊆⊂⊇⊃∩∪⊔⊓⊤⊥≃≅↦∑∏⁻¹•∣≡⋆⬝∅"
    private val ASCII_SYMBOLS = listOf(":=", "=>", "->", "<-", "<;>", "|", ":", "·", "=", "<", ">", "+", "*", "^")

    /** Characters Lean allows to start an identifier (a simplified `isLetterLike`, as in the bridge). */
    fun identStart(c: Char): Boolean =
        c.isLetter() && c != 'λ' && c != 'Π' && c != 'Σ' || c == '_' || c in 'ℂ'..'℧' || c == 'ℕ' || c == 'ℤ' || c == 'ℚ' || c == 'ℝ'

    fun identRest(c: Char): Boolean =
        identStart(c) || c.isDigit() || c == '\'' || c == '!' || c == '?' || c in '₀'..'₉' || c in 'ₐ'..'ₜ'

    /** Coloured spans of [text], sorted, never overlapping. Unplaced identifiers get none. */
    fun spans(text: String): List<Span> = tokens(text).mapNotNull { (s, e, k) -> k?.let { Span(s, e, it) } }

    /** Identifier runs as (start, end), dotted names whole: what [GoalColour] recolours. */
    fun identifiers(text: String): List<Pair<Int, Int>> =
        tokens(text).filter { it.third == null }.map { it.first to it.second }

    private fun tokens(text: String): List<Triple<Int, Int, Code?>> {
        val out = mutableListOf<Triple<Int, Int, Code?>>()
        var i = 0
        var prevWord: String? = null
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                text.startsWith("--", i) -> {
                    val e = text.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Triple(i, e, Code.COMMENT); i = e
                }
                text.startsWith("/-", i) -> {
                    val e = blockCommentEnd(text, i)
                    out += Triple(i, e, Code.COMMENT); i = e
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < n && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                    val e = minOf(j + 1, n)
                    out += Triple(i, e, Code.LITERAL); i = e
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && (text[j].isDigit() || text[j] == '.' && j + 1 < n && text[j + 1].isDigit())) j++
                    out += Triple(i, j, Code.LITERAL); i = j
                }
                c == '@' && text.startsWith("@[", i) -> {
                    val e = text.indexOf(']', i).let { if (it < 0) n else it + 1 }
                    out += Triple(i, e, Code.ATTR); i = e
                }
                c == '?' && i + 1 < n && (text[i + 1] == '_' || identStart(text[i + 1])) -> {
                    var j = i + 1
                    while (j < n && identRest(text[j]) && text[j] != '?') j++
                    out += Triple(i, j, Code.HOLE); i = j
                }
                identStart(c) -> {
                    val e = identEnd(text, i)
                    val w = text.substring(i, e)
                    val base = w.trimEnd('?', '!')
                    val kind = when {
                        w in SORRIES -> Code.SORRY
                        w in COMMANDS -> Code.KEYWORD
                        base in TACTICS && '.' !in w -> Code.TACTIC
                        prevWord in DECLARES -> Code.DECL
                        else -> null
                    }
                    // A lone `_` is a placeholder, not a name.
                    if (w != "_") out += Triple(i, e, kind)
                    prevWord = w; i = e; continue
                }
                else -> {
                    val len = symbolAt(text, i)
                    if (len > 0) { out += Triple(i, i + len, Code.SYMBOL); i += len } else i++
                }
            }
            if (!c.isWhitespace()) prevWord = null
        }
        return out
    }

    private fun identEnd(text: String, start: Int): Int {
        var j = start
        while (true) {
            while (j < text.length && identRest(text[j])) j++
            // A dot joins the next segment only when a name follows: `f.onEntity`, not `h.1`.
            if (j + 1 < text.length && text[j] == '.' && identStart(text[j + 1])) j++ else return j
        }
    }

    /** Lean block comments nest. An unclosed one runs to the end. */
    private fun blockCommentEnd(text: String, start: Int): Int {
        var depth = 0
        var j = start
        while (j < text.length) {
            when {
                text.startsWith("/-", j) -> { depth++; j += 2 }
                text.startsWith("-/", j) -> { depth--; j += 2; if (depth == 0) return j }
                else -> j++
            }
        }
        return text.length
    }

    private fun symbolAt(text: String, i: Int): Int {
        if (text[i] in SYMBOL_CHARS) return 1
        return ASCII_SYMBOLS.firstOrNull { text.startsWith(it, i) }?.length ?: 0
    }
}

object Highlight {
    /** Lean's tokens as spans of [text]; tokens that fall outside it (a stale check) are dropped. */
    fun leanSpans(text: String, tokens: List<LeanToken>): List<Span> {
        if (tokens.isEmpty()) return emptyList()
        val starts = lineStarts(text)
        return tokens.mapNotNull { t ->
            val kind = when (t.kind) {
                "variable" -> Code.LOCAL
                "property" -> Code.FIELD
                "keyword" -> Code.KEYWORD
                "sorry" -> Code.SORRY
                else -> return@mapNotNull null
            }
            if (t.line < 1 || t.line > starts.size) return@mapNotNull null
            val lineEnd = if (t.line < starts.size) starts[t.line] - 1 else text.length
            val s = starts[t.line - 1] + t.col
            val e = s + t.length
            if (t.col < 0 || t.length <= 0 || e > lineEnd) null else Span(s, e, kind)
        }
    }

    /**
     * The two layers merged. Lean decides locals, fields and `sorry`; its keyword label
     * only fills a word the scanner left plain. Comments and literals are never recoloured.
     */
    fun overlay(n: Int, lex: List<Span>, lean: List<Span>): List<Span> {
        val kinds = arrayOfNulls<Code>(n)
        for (s in lex) for (i in s.start until minOf(s.end, n)) kinds[i] = s.kind
        for (s in lean) for (i in s.start until minOf(s.end, n)) {
            val under = kinds[i]
            if (under == Code.COMMENT || under == Code.LITERAL) continue
            if (s.kind == Code.KEYWORD && under != null) continue
            kinds[i] = s.kind
        }
        return runs(kinds)
    }

    /** Both layers for a whole text. */
    fun of(text: String, tokens: List<LeanToken> = emptyList()): List<Span> =
        overlay(text.length, Lexer.spans(text), leanSpans(text, tokens))

    /** A block [draft] of file lines [first]..[last], with the checked tokens carried onto it. */
    fun block(r: CheckResult, first: Int, last: Int, draft: String): List<Span> {
        val original = Edit.lines(r.text, first, last).joinToString("\n")
        return of(draft, TokenShift.carry(TokenShift.within(r.tokens, first, last), original, draft))
    }

    /** [spans] cut to [from, to) and shifted to start at 0: one line, or a step inside it. */
    fun slice(spans: List<Span>, from: Int, to: Int): List<Span> = spans.mapNotNull { s ->
        val a = maxOf(s.start, from); val b = minOf(s.end, to)
        if (a < b) Span(a - from, b - from, s.kind) else null
    }

    /** Per line of [text], its spans relative to the line; one pass over sorted [spans]. */
    fun byLine(text: String, spans: List<Span>): List<List<Span>> {
        val starts = lineStarts(text)
        val out = List(starts.size) { mutableListOf<Span>() }
        var k = 0
        for (sp in spans) {
            while (k + 1 < starts.size && starts[k + 1] <= sp.start) k++
            // A span may cross lines (a block comment): each line gets its part.
            var line = k
            while (line < starts.size && starts[line] < sp.end) {
                val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
                val a = maxOf(sp.start, starts[line]); val b = minOf(sp.end, end)
                if (a < b) out[line] += Span(a - starts[line], b - starts[line], sp.kind)
                line++
            }
        }
        return out
    }

    fun lineStarts(text: String): IntArray {
        val out = ArrayList<Int>().apply { add(0) }
        for ((i, c) in text.withIndex()) if (c == '\n') out += i + 1
        return out.toIntArray()
    }

    internal fun runs(kinds: Array<Code?>): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < kinds.size) {
            val k = kinds[i]
            if (k == null) { i++; continue }
            var j = i + 1
            while (j < kinds.size && kinds[j] == k) j++
            out += Span(i, j, k); i = j
        }
        return out
    }
}

/**
 * Lean's tokens across an edit: kept on untouched lines (common prefix and suffix, moved
 * by the line delta), dropped on edited ones until the next check.
 */
object TokenShift {
    fun carry(tokens: List<LeanToken>, old: String, new: String): List<LeanToken> {
        if (old == new) return tokens
        val a = old.split("\n"); val b = new.split("\n")
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val delta = b.size - a.size
        return tokens.mapNotNull { t ->
            when {
                t.line <= p -> t
                t.line > a.size - s -> t.copy(line = t.line + delta)
                else -> null
            }
        }
    }

    /** Tokens of file lines [first]..[last], renumbered so [first] is line 1: a declaration's own text. */
    fun within(tokens: List<LeanToken>, first: Int, last: Int): List<LeanToken> =
        tokens.filter { it.line in first..last }.map { it.copy(line = it.line - first + 1) }
}

/** Goals get no Lean tokens: hypothesis names are the locals, a name after a local's dot a field. */
object GoalColour {
    fun spans(text: String, locals: Set<String>): List<Span> {
        val kinds = arrayOfNulls<Code>(text.length)
        for (s in Lexer.spans(text)) for (i in s.start until s.end) kinds[i] = s.kind
        for ((s, e) in Lexer.identifiers(text)) {
            val w = text.substring(s, e)
            val head = w.substringBefore('.')
            if (head !in locals) continue
            for (i in s until s + head.length) kinds[i] = Code.LOCAL
            for (i in s + head.length until e) kinds[i] = Code.FIELD
        }
        return Highlight.runs(kinds)
    }

    fun locals(g: Goal): Set<String> = g.hyps.flatMap { it.names }.toSet()
}
