package leanwb

/** How a hypothesis fared across one tactic. */
enum class Mark { KEPT, GONE, NEW, CHANGED }

/** One row of the diff: consecutive names with the same mark and type, as Lean groups them. */
data class HypRow(val names: List<String>, val type: String, val mark: Mark, val oldType: String? = null) {
    val displayType: String get() = Hyp(names, type).displayType
}

/** What a tactic did to the goal it worked on. */
data class GoalDiff(val case: String?, val rows: List<HypRow>, val target: String, val oldTarget: String?) {
    val targetChanged: Boolean get() = oldTarget != null
}

/**
 * The effect of the tactic on one line, from the goals before it and at its end.
 *
 * Lean works on the first goal. If the goal count drops, that goal was closed;
 * otherwise the first goal after it is what became of it, and any extra goals
 * are the ones it split into.
 */
sealed interface Step {
    /** No tactic state on one side (outside a `by` block, or an error). */
    object Unknown : Step
    /** The first goal was closed; [remaining] goals are left. */
    data class Closed(val remaining: Int) : Step
    /** The first goal became [diff]; [added] more goals appeared beside it. */
    data class Changed(val diff: GoalDiff, val added: List<Goal>, val remaining: Int) : Step

    companion object {
        fun of(before: List<Goal>?, after: List<Goal>?): Step {
            if (before.isNullOrEmpty() || after == null) return Unknown
            if (after.size < before.size) return Closed(after.size)
            val split = after.size - before.size  // goals the first one split into, besides after[0]
            return Changed(diff(before[0], after[0]), after.subList(1, 1 + split), after.size)
        }

        fun diff(before: Goal, after: Goal): GoalDiff {
            val old = flatten(before.hyps).toMap()
            val new = flatten(after.hyps)
            val newNames = new.map { it.first }.toSet()
            // Kept, changed and new, in the order Lean shows them after the tactic;
            // gone hypotheses where they stood before it, i.e. listed first.
            val gone = flatten(before.hyps).filter { it.first !in newNames }
                .map { Triple(it.first, it.second, Mark.GONE to null as String?) }
            val rest = new.map { (n, t) ->
                val o = old[n]
                Triple(n, t, when {
                    o == null -> Mark.NEW to null
                    o == t -> Mark.KEPT to null
                    else -> Mark.CHANGED to o
                })
            }
            return GoalDiff(after.case, group(gone + rest), after.target,
                if (before.target != after.target) before.target else null)
        }

        private fun flatten(hyps: List<Hyp>): List<Pair<String, String>> =
            hyps.flatMap { h -> h.names.map { it to h.type } }

        private fun group(xs: List<Triple<String, String, Pair<Mark, String?>>>): List<HypRow> {
            val out = mutableListOf<HypRow>()
            for ((n, t, mo) in xs) {
                val last = out.lastOrNull()
                if (last != null && last.type == t && last.mark == mo.first && last.oldType == mo.second)
                    out[out.size - 1] = last.copy(names = last.names + n)
                else out += HypRow(listOf(n), t, mo.first, mo.second)
            }
            return out
        }
    }
}

/** Texts the user can step back to. The current text is not in the stack. */
class Undo(private val limit: Int = 50) {
    private val stack = ArrayDeque<String>()
    val size: Int get() = stack.size
    fun push(text: String) {
        if (stack.lastOrNull() == text) return
        stack.addLast(text)
        while (stack.size > limit) stack.removeFirst()
    }
    fun pop(): String? = stack.removeLastOrNull()
    fun clear() = stack.clear()
}

object Statement {
    /**
     * What a declaration claims: its header from the keyword up to the `:=` or
     * `where` that starts its body, whitespace collapsed. The bridge's range starts
     * at the keyword; a trailing attribute of the next declaration falls after the cut.
     */
    fun of(text: String, d: Declaration): String {
        val lines = Edit.lines(text, d.line, d.endLine)
        val flat = lines.joinToString(" ") { it.trim() }
        val cut = listOf(Regex(""":=(\s|$)"""), Regex("""\swhere(\s|$)""")).mapNotNull { it.find(flat)?.range?.first }.minOrNull()
        return (if (cut == null) flat else flat.substring(0, cut)).replace(Regex("""\s+"""), " ").trim()
    }
}

/**
 * The first error inside a declaration's lines: later ones often follow from it.
 * "unsolved goals" does not count: it is what an unfinished proof looks like.
 */
fun CheckResult.firstError(d: Declaration): Diagnostic? =
    diagnostics.filter { it.severity == "error" && !it.unsolvedGoals && it.line in d.line..d.endLine }
        .minByOrNull { it.line * 100000 + it.col }

/** One tactic line of a `by` block. */
data class StepLine(val line: Int, val text: String, val indent: Int)

object ProofSteps {
    private val endsWithBy = Regex("""(^|[\s(=])by\s*$""")

    /**
     * The tactic lines of the declaration starting at [declLine]: the lines after the
     * first line ending in `by`, up to the next line at column 0 (the declaration's end,
     * found in [text] itself so it holds for a text not yet checked). Blank and
     * comment-only lines are skipped. Null when the declaration has no `by` block.
     */
    fun of(text: String, declLine: Int): List<StepLine>? {
        val by = byLine(text, declLine) ?: return null
        val lines = text.split("\n")
        val out = mutableListOf<StepLine>()
        for (k in by until lines.size) {
            val l = lines[k]
            if (l.isNotBlank() && !l[0].isWhitespace()) break
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("--")) continue
            out += StepLine(k + 1, t, Edit.indentOf(l).length)
        }
        return out
    }

    /** The line (1-based) ending in the `by` that opens the declaration's block; null in term mode. */
    fun byLine(text: String, declLine: Int): Int? {
        val lines = text.split("\n")
        var i = declLine - 1
        while (i < lines.size) {
            if (i > declLine - 1 && lines[i].isNotBlank() && !lines[i][0].isWhitespace()) return null
            val code = lines[i].substringBefore("--").trimEnd()
            if (endsWithBy.containsMatchIn(code)) return i + 1
            i++
        }
        return null
    }

    /**
     * Insert [content] as a step after line [after] of the declaration at [declLine]. A step
     * goes in beside the one it follows; after the `by` line it takes the first step's
     * indent, or two more than the `by` line's when the block is empty.
     */
    fun insert(text: String, declLine: Int, after: Int, content: String): String {
        if (after != byLine(text, declLine)) return Edit.insertAfter(text, after, content)
        val indent = of(text, declLine)?.firstOrNull()?.indent
            ?: (Edit.indentOf(Edit.lines(text, after, after).single()).length + 2)
        return Edit.replace(text, after, after, Edit.lines(text, after, after).single() + "\n" + " ".repeat(indent) + content.trim())
    }
}

/** A one-line account of a step, for a card: "+ h1 h2 h3", "− he", "⊢ changed", "closes the goal". */
fun Step.summary(): String = when (this) {
    Step.Unknown -> ""
    is Step.Closed -> if (remaining == 0) "closes the goal ✓" else "closes the goal · $remaining left"
    is Step.Changed -> {
        val parts = mutableListOf<String>()
        val new = diff.rows.filter { it.mark == Mark.NEW }.flatMap { it.names }
        val gone = diff.rows.filter { it.mark == Mark.GONE }.flatMap { it.names }
        val changed = diff.rows.filter { it.mark == Mark.CHANGED }.flatMap { it.names }
        if (new.isNotEmpty()) parts += "+ " + new.joinToString(" ")
        if (gone.isNotEmpty()) parts += "− " + gone.joinToString(" ")
        if (changed.isNotEmpty()) parts += "~ " + changed.joinToString(" ")
        if (diff.targetChanged) parts += "⊢ changed"
        if (added.isNotEmpty()) parts += "+${added.size} goal" + if (added.size > 1) "s" else ""
        if (parts.isEmpty()) "no visible change" else parts.joinToString(" · ")
    }
}
