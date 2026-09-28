package leanwb

/** Line arithmetic for editing one declaration inside a file. Lines are 1-based. */
object Edit {
    fun lines(text: String, first: Int, last: Int): List<String> {
        val all = text.split("\n")
        require(first in 1..all.size && last in first..all.size) { "lines $first..$last outside 1..${all.size}" }
        return all.subList(first - 1, last)
    }

    /** [text] with lines first..last replaced by [block] (which may have a different line count). */
    fun replace(text: String, first: Int, last: Int, block: String): String {
        val all = text.split("\n")
        require(first in 1..all.size && last in first..all.size) { "lines $first..$last outside 1..${all.size}" }
        return (all.subList(0, first - 1) + block.split("\n") + all.subList(last, all.size)).joinToString("\n")
    }

    /**
     * Where a selected line lands after lines first..last became [newCount] lines:
     * before the block it is unchanged, inside it keeps its offset (clamped to the
     * new block), after it shifts by the difference.
     */
    fun shift(line: Int, first: Int, last: Int, newCount: Int): Int = when {
        line < first -> line
        line > last -> line + newCount - (last - first + 1)
        else -> minOf(line, first + newCount - 1)
    }

    /** The leading whitespace of a line. */
    fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /**
     * [text] with line [n]'s content (after its indent) replaced by [content]; the indent
     * is kept, and applied to every line of a multi-line [content].
     */
    fun setLine(text: String, n: Int, content: String): String {
        val indent = indentOf(lines(text, n, n).single())
        return replace(text, n, n, content.trim().lines().joinToString("\n") { indent + it })
    }

    /** [text] without line [n]. */
    fun deleteLine(text: String, n: Int): String {
        val all = text.split("\n")
        require(n in 1..all.size) { "line $n outside 1..${all.size}" }
        return (all.subList(0, n - 1) + all.subList(n, all.size)).joinToString("\n")
    }

    /** [text] with a new line after line [n], indented like line [n] (a sibling tactic). */
    fun insertAfter(text: String, n: Int, content: String): String {
        val old = lines(text, n, n).single()
        return replace(text, n, n, old + "\n" + indentOf(old) + content.trim())
    }
}
