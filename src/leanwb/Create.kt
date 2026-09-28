package leanwb

/**
 * A new declaration: a skeleton the user completes before it is checked. The
 * statement is always typed by the user (Lean refuses `sorry` as a theorem's type),
 * so the skeleton leaves it empty and puts the cursor (`‸`) on the name.
 */
enum class DeclKind(val label: String, val skeleton: String) {
    THEOREM("theorem", "theorem ‸ :  := by\n  sorry"),
    LEMMA("lemma", "lemma ‸ :  := by\n  sorry"),
    DEF("def", "def ‸ :  :=\n  sorry"),
    STRUCTURE("structure", "structure ‸ where\n  "),
    CLASS("class", "class ‸ where\n  "),
    INDUCTIVE("inductive", "inductive ‸ where\n  | "),
    NAMESPACE("namespace", "namespace ‸\n\nend "),
    SECTION("section", "section ‸\n\nend "),
    /** Filled from a [NotationSpec] form rather than typed; the skeleton is the fallback. */
    NOTATION("notation", "scoped infixl:65 \" ‸ \" => "),
}

enum class Fixity(val keyword: String) { INFIXL("infixl"), INFIXR("infixr"), INFIX("infix"), PREFIX("prefix"), POSTFIX("postfix") }

/**
 * A notation as the form describes it: `scoped infixl:65 " ⋆ " => Systems.combine`.
 * Infix symbols are written with a space either side, as Lean prints them.
 */
data class NotationSpec(
    val fixity: Fixity = Fixity.INFIXL,
    val symbol: String = "",
    val precedence: String = "65",
    val target: String = "",
    val scoped: Boolean = true,
) {
    val infix: Boolean get() = fixity in setOf(Fixity.INFIXL, Fixity.INFIXR, Fixity.INFIX)

    fun text(): String {
        val sym = symbol.trim().let { if (infix) " $it " else it }
        return (if (scoped) "scoped " else "") + fixity.keyword + ":" + precedence.trim() + " \"" + sym + "\" => " + target.trim()
    }

    /** What stops it from being a notation, or null. */
    fun problem(): String? = when {
        symbol.isBlank() -> "type a symbol"
        '"' in symbol || '\n' in symbol -> "the symbol cannot hold a quote or a line break"
        precedence.isBlank() || !(precedence.trim() == "max" || precedence.trim().all { it.isDigit() }) -> "precedence: a number or max"
        target.isBlank() -> "name the function it stands for"
        else -> null
    }

    /** A copy with [f], keeping a precedence that suits it (prefix/postfix default to max). */
    fun withFixity(f: Fixity): NotationSpec {
        val nowInfix = f in setOf(Fixity.INFIXL, Fixity.INFIXR, Fixity.INFIX)
        val prec = if (nowInfix == infix) precedence else if (nowInfix) "65" else "max"
        return copy(fixity = f, precedence = prec)
    }

    companion object {
        /** Precedences of familiar operators (Lean core and Mathlib), to choose by likeness. */
        val infixPresets = listOf("→" to "25", "∨" to "30", "∧" to "35", "= ≤" to "50", "+" to "65", "⊔" to "68", "⊓" to "69", "*" to "70")
        val prefixPresets = listOf("max" to "max", "¬" to "40", "-" to "75")

        /** Why [name] cannot be a `\`-abbreviation, or null. Empty means none (allowed). */
        fun abbrevProblem(name: String): String? = when {
            name.isEmpty() -> null
            name.length > 32 || name.any { it.isWhitespace() || it == '\\' } -> "an abbreviation name has no spaces or backslash"
            else -> null
        }
    }
}

object NewDecl {
    /** The skeleton as field content: its text without the caret, and where the caret was. */
    fun skeleton(kind: DeclKind): Typed {
        val at = kind.skeleton.indexOf(Snippet.CARET)
        return Typed(kind.skeleton.replace(Snippet.CARET.toString(), ""), at)
    }

    private val openers = Regex("""^(namespace|section)(\s+(\S+))?\s*$""")

    /**
     * The block as typed, with a `namespace`/`section` block's `end` given the opener's
     * name when it was left without one (the name is typed once, on the first line).
     */
    fun finish(block: String): String {
        val lines = block.trimEnd().split("\n").toMutableList()
        val m = openers.find(lines.first().trim()) ?: return lines.joinToString("\n")
        val name = m.groupValues[3]
        val last = lines.last().trim()
        if (lines.size > 1 && last == "end" && name.isNotEmpty()) lines[lines.size - 1] = "end $name"
        return lines.joinToString("\n")
    }

    private val endLine = Regex("""^end(\s|$)""")

    /**
     * [text] with [block] placed after the declaration [after], or, with none to
     * follow, before the file's closing `end` lines (inside its namespaces), else at
     * the end. Blank lines separate it from its neighbours. Returns the new text and
     * the block's first line (1-based).
     */
    fun insert(text: String, after: Declaration?, block: String): Pair<String, Int> {
        val lines = text.split("\n")
        val at = if (after != null) after.endLine.coerceIn(0, lines.size) else {
            var k = lines.size
            while (k > 0 && (lines[k - 1].isBlank() || endLine.containsMatchIn(lines[k - 1]))) k--
            // No closing `end`: after the last line with content.
            if ((k until lines.size).none { endLine.containsMatchIn(lines[it]) }) {
                k = lines.size
                while (k > 0 && lines[k - 1].isBlank()) k--
            }
            k
        }
        val before = lines.subList(0, at)
        val rest = lines.subList(at, lines.size)
        val gapBefore = if (before.lastOrNull()?.isNotBlank() == true) listOf("") else emptyList()
        val gapAfter = if (rest.firstOrNull()?.isNotBlank() == true) listOf("") else emptyList()
        val out = before + gapBefore + finish(block).split("\n") + gapAfter + rest
        return out.joinToString("\n") to before.size + gapBefore.size + 1
    }
}

object NewFile {
    private val part = Regex("""^[A-Z][A-Za-z0-9_]*$""")

    /** `A/B/C.lean` to `A.B.C`, or null if it is not an importable module path (as the bridge checks). */
    fun module(rel: String): String? {
        if (!rel.endsWith(".lean")) return null
        val parts = rel.removeSuffix(".lean").split("/")
        return if (parts.size >= 2 && parts.all { part.matches(it) }) parts.joinToString(".") else null
    }

    /** Why [rel] cannot be created given the [existing] sources, or null if it can. */
    fun problem(rel: String, existing: List<String>): String? {
        val libs = existing.filter { '/' in it }.map { it.substringBefore('/') }.toSet()
        return when {
            module(rel) == null -> "Use Library/…/Name.lean with capitalised names (letters, digits, _)"
            rel in existing -> "$rel already exists"
            rel.substringBefore('/') !in libs -> "New files go in ${libs.sorted().joinToString(", ")}"
            else -> null
        }
    }

    /** The folders new files can go in: every folder holding a source, below a library. */
    fun folders(existing: List<String>): List<String> =
        existing.filter { '/' in it }.map { it.substringBeforeLast('/') }.distinct().sorted()

    /** A new file's text: its imports, then a namespace block if [namespace] is given. */
    fun text(imports: List<String>, namespace: String): String = buildString {
        for (m in imports) append("import ").append(m).append('\n')
        if (imports.isNotEmpty()) append('\n')
        val ns = namespace.trim()
        if (ns.isNotEmpty()) append("namespace ").append(ns).append("\n\nend ").append(ns).append('\n')
    }

    private val namespaceLine = Regex("""(?m)^namespace\s+(\S+)""")

    /** The first namespace [text] opens: a default for a sibling file. */
    fun namespaceOf(text: String): String = namespaceLine.find(text)?.groupValues?.get(1) ?: ""
}

/** A source as the landing page shows it (the bridge's /v1/overview, read from the text, no Lean run). */
data class FileInfo(val path: String, val title: String?, val decls: Int, val sorries: Int, val empty: Boolean, val role: String) {
    val name: String get() = path.substringAfterLast('/')
}

sealed interface TreeRow {
    val depth: Int
    data class Folder(val path: String, override val depth: Int, val files: Int, val collapsed: Boolean) : TreeRow {
        val name: String get() = path.substringAfterLast('/')
    }
    data class File(val info: FileInfo, override val depth: Int) : TreeRow
}

object FileTree {
    /**
     * The files as an indented tree, in path order (a library root `Lib.lean` just
     * above its `Lib/` folder). Folders in [collapsed] hide everything below them.
     */
    fun rows(files: List<FileInfo>, collapsed: Set<String>): List<TreeRow> {
        val out = mutableListOf<TreeRow>()
        val shown = HashSet<String>()
        for (f in files.sortedBy { it.path }) {
            val parts = f.path.split("/")
            var hidden = false
            for (i in 1 until parts.size) {
                val folder = parts.subList(0, i).joinToString("/")
                if (hidden) break
                if (shown.add(folder)) out += TreeRow.Folder(folder, i - 1,
                    files.count { it.path.startsWith("$folder/") }, folder in collapsed)
                if (folder in collapsed) hidden = true
            }
            if (!hidden) out += TreeRow.File(f, parts.size - 1)
        }
        return out
    }
}
