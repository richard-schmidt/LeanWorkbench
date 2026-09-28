import leanwb.*
import java.io.File

// Hand-rolled harness: no JUnit jar offline. Each check names itself; any failure exits 1.
var failures = 0
var passed = 0

fun check(name: String, cond: Boolean) {
    if (cond) passed++ else { failures++; println("FAIL: $name") }
}

inline fun <reified T : Throwable> throws(name: String, block: () -> Unit) {
    try { block(); failures++; println("FAIL (no ${T::class.simpleName}): $name") }
    catch (e: Throwable) { if (e is T) passed++ else { failures++; println("FAIL ($e): $name") } }
}

fun json() {
    val tricky = listOf("", "plain", "quote\" back\\slash", "line\nbreak\ttab", "⊢ ∀ e' ∈ {u | ∃ t}", "𝔽 surrogate", "\u0001ctl")
    for (s in tricky) check("quote/parse round trip: $s", Json.parse(Json.quote(s)) == s)
    check("nested", Json.parse("""{"a":[1,2.5,true,null,{"b":"c"}]}""") ==
        mapOf("a" to listOf(1L, 2.5, true, null, mapOf("b" to "c"))))
    check("unicode escape", Json.parse("\"\\u22a2\"") == "⊢")
    throws<Json.Error>("trailing garbage") { Json.parse("{} x") }
    throws<Json.Error>("unterminated") { Json.parse("\"abc") }
}

fun contract() {
    // Produced by the real bridge on SyntheticSystems/Systems/Category.lean, goals_at [10, 22].
    val r = Decode.check(File("fixtures/check_category.json").readText())
    check("file", r.file == "SyntheticSystems/Systems/Category.lean")
    check("declarations", r.declarations.map { it.label } == listOf("Hom.id", "Hom.comp", "(instance at line 28)"))
    check("all ok", r.declarations.all { it.status == DeclStatus.OK })
    check("ranges stop at the last code line", r.declarations.map { it.line to it.endLine } == listOf(7 to 14, 17 to 24, 28 to 38))
    val g22 = r.goalsAt(22)!!.goals!!.single()
    check("case", g22.case == "preserve")
    check("grouped names", g22.hyps.first() == Hyp(listOf("S", "T", "U"), "System"))
    check("target", g22.target == "U.entityDomain e' d'")
    check("wrapped type display", Hyp(listOf("h"), "\n  a = b ∧\n    c = d").displayType == "a = b ∧\n  c = d")
    check("plain type display", Hyp(listOf("x"), "Nat := 3").displayType == "Nat := 3")
    check("line 10 has a goal", r.goalsAt(10)!!.goals!!.single().hyps.size == 8)
    check("after rcases: new hypotheses", r.goalsAfter(22)!!.goals!!.single().hyps.takeLast(3).map { it.names } ==
        listOf(listOf("tE"), listOf("htE"), listOf("he'")))
    check("after the closing exact: no goals", r.goalsAfter(24)!!.goals == emptyList<Goal>())
    check("text is the file", r.text.lines()[6].startsWith("def Hom.id"))
    throws<Decode.ContractError>("missing field refused") { Decode.check("""{"file":"x"}""") }
    throws<Decode.ContractError>("unknown status refused") {
        Decode.check("""{"file":"f","seconds":1,"text":"","diagnostics":[],"goals":[],
            "declarations":[{"line":1,"end_line":1,"kind":"def","name":"x","status":"great"}],"goals_after":[]}""")
    }
    check("null goals decode as null", Decode.check("""{"file":"f","seconds":1,"text":"","diagnostics":[],
        "declarations":[],"goals":[{"line":3,"goals":null}],"goals_after":[]}""").goalsAt(3)!!.goals == null)
    check("error body", Decode.error("""{"error": "nope"}""") == "nope")
    check("non-error body", Decode.error("""{"files": []}""") == null && Decode.error("<html>") == null)
    check("files", Decode.files("""{"files":["A.lean","B/C.lean"]}""") == listOf("A.lean", "B/C.lean"))
    val req = Json.parse(Requests.check("A.lean", "x\n⊢", listOf(3, 7))) as Map<*, *>
    check("request", req == mapOf("file" to "A.lean", "text" to "x\n⊢", "goals_at" to listOf(3L, 7L),
        "goals_after" to listOf<Any?>()))
    val req2 = Json.parse(Requests.check("A.lean", null, listOf(3), listOf(3, 4))) as Map<*, *>
    check("request after", req2["goals_after"] == listOf(3L, 4L))
    throws<Decode.ContractError>("goals_after is required") {
        Decode.check("""{"file":"f","seconds":1,"text":"","diagnostics":[],"declarations":[],"goals":[]}""")
    }
    check("save request", Json.parse(Requests.save("A.lean", "new ⊢", "old\n")) ==
        mapOf("file" to "A.lean", "text" to "new ⊢", "base" to "old\n"))
    check("request without text", !Requests.check("A.lean", null, listOf()).contains("text"))
}

fun diagnostics() {
    val rfl = Diagnostic(23, 4, 23, "error",
        "Tactic `rfl` failed: The left-hand side\n  e'\nis not definitionally equal to the right-hand side\n  d'\n\ncase preserve\nS T U : System\n⊢ U.entityDomain e' d'")
    check("headline stops at the goal dump", rfl.headline.endsWith("right-hand side\n  d'") && rfl.hasMore)
    val unsolved = Diagnostic(154, 38, 155, "error", "unsolved goals\nG H : Generators\n⊢ ↑(generate G) ≤ ↑(generate H)")
    check("unsolved goals: headline only", unsolved.unsolvedGoals && unsolved.headline == "unsolved goals" && unsolved.hasMore)
    check("other errors are not unsolved goals", !rfl.unsolvedGoals &&
        !Diagnostic(1, 0, 1, "info", "unsolved goals\nx").unsolvedGoals)
    val short = Diagnostic(1, 0, 1, "warning", "declaration uses `sorry`\n")
    check("short message is whole", short.headline == "declaration uses `sorry`" && !short.hasMore)
}

fun pairing() {
    val tok = "Abc_def-0123456789xyz"
    check("valid", PairingLink.parse("leanwb://pair?port=8766&token=$tok") == Pairing(8766, tok))
    check("order free", PairingLink.parse("leanwb://pair?token=$tok&port=8766") == Pairing(8766, tok))
    for (bad in listOf("https://pair?port=8766&token=$tok", "leanwb://pair?port=80&token=$tok",
                       "leanwb://pair?port=x&token=$tok", "leanwb://pair?port=8766&token=short",
                       "leanwb://pair?port=8766&token=has%20space_and_more", "leanwb://pair?port=8766"))
        check("refused: $bad", PairingLink.parse(bad) == null)
}

fun edit() {
    val t = "a\nb\nc\nd"
    check("lines", Edit.lines(t, 2, 3) == listOf("b", "c"))
    check("replace grow", Edit.replace(t, 2, 3, "x\ny\nz") == "a\nx\ny\nz\nd")
    check("replace shrink", Edit.replace(t, 2, 3, "x") == "a\nx\nd")
    check("replace last", Edit.replace(t, 4, 4, "q") == "a\nb\nc\nq")
    check("replace identity", Edit.replace(t, 2, 3, Edit.lines(t, 2, 3).joinToString("\n")) == t)
    check("shift before", Edit.shift(1, 2, 3, 5) == 1)
    check("shift inside", Edit.shift(3, 2, 3, 5) == 3)
    check("shift inside clamped", Edit.shift(3, 2, 3, 1) == 2)
    check("shift after", Edit.shift(4, 2, 3, 5) == 7)
    throws<IllegalArgumentException>("out of range") { Edit.replace(t, 3, 9, "") }
    val p = "by\n  intro x\n    exact x"
    check("indent", Edit.indentOf("    exact x") == "    ")
    check("setLine keeps indent", Edit.setLine(p, 3, "  apply h ") == "by\n  intro x\n    apply h")
    check("setLine multi-line keeps indent", Edit.setLine(p, 3, "constructor\n· simp") ==
        "by\n  intro x\n    constructor\n    · simp")
    check("insertAfter is a sibling", Edit.insertAfter(p, 2, "simp") == "by\n  intro x\n  simp\n    exact x")
}

fun proof() {
    val r = Decode.check(File("fixtures/check_category.json").readText())
    // Real rcases on line 22: `he` is destructured into tE, htE, he'.
    val step = Step.of(r.goalsAt(22)!!.goals, r.goalsAfter(22)!!.goals) as Step.Changed
    val rows = step.diff.rows
    check("rcases: he gone", rows.single { it.mark == Mark.GONE }.names == listOf("he"))
    check("rcases: three new", rows.filter { it.mark == Mark.NEW }.flatMap { it.names } == listOf("tE", "htE", "he'"))
    check("rcases: rest kept", rows.filter { it.mark == Mark.KEPT }.flatMap { it.names }.containsAll(listOf("S", "T", "U", "h")))
    check("rcases: target unchanged", !step.diff.targetChanged && step.added.isEmpty() && step.remaining == 1)
    check("rcases: grouping kept", rows.first { it.mark == Mark.KEPT }.names == listOf("S", "T", "U"))
    // Invariant: every hypothesis before or after appears exactly once.
    val before = r.goalsAt(22)!!.goals!![0].hyps.flatMap { it.names }
    val after = r.goalsAfter(22)!!.goals!![0].hyps.flatMap { it.names }
    check("diff covers both sides once", rows.flatMap { it.names }.sorted() == (before + after).distinct().sorted())
    check("closing exact", Step.of(listOf(Goal(null, listOf(), "p")), r.goalsAfter(24)!!.goals) == Step.Closed(0))
    check("no state", Step.of(null, listOf()) == Step.Unknown && Step.of(listOf(Goal(null, listOf(), "p")), null) == Step.Unknown)
    val g = Goal(null, listOf(Hyp(listOf("x"), "Nat"), Hyp(listOf("h"), "x = 1")), "p x")
    val split = Step.of(listOf(g, Goal("other", listOf(), "q")),
        listOf(Goal("left", listOf(Hyp(listOf("x"), "Nat"), Hyp(listOf("h"), "x = 2")), "a"), Goal("right", listOf(), "b"), Goal("other", listOf(), "q")))
    split as Step.Changed
    check("split: one added goal", split.added.map { it.case } == listOf("right") && split.remaining == 3)
    check("split: target changed", split.diff.oldTarget == "p x" && split.diff.target == "a")
    check("split: type changed", split.diff.rows.single { it.mark == Mark.CHANGED }.let { it.names == listOf("h") && it.oldType == "x = 1" })
    check("closing one of two", Step.of(listOf(g, g), listOf(g)) == Step.Closed(1))

    val u = Undo(limit = 2)
    u.push("a"); u.push("a"); u.push("b"); u.push("c")
    check("undo: dedup and limit", u.size == 2 && u.pop() == "c" && u.pop() == "b" && u.pop() == null)

    val decls = r.declarations
    check("statement: body cut", Statement.of(r.text, decls[0]) == "def Hom.id (S : System) : Hom S S")
    check("statement: next decl's attribute ignored", Statement.of(r.text, decls[1]) ==
        "def Hom.comp {S T U : System} (f : Hom S T) (g : Hom T U) : Hom S U")
    check("statement: where", Statement.of(r.text, decls[2]) == "instance : Category System")
    val multi = "theorem foo\n    (h : a = b) :\n    b = a := by\n  simp [h]"
    check("statement: multi-line", Statement.of(multi, Declaration(1, 4, "theorem", "foo", DeclStatus.OK)) ==
        "theorem foo (h : a = b) : b = a")
    val errs = r.copy(diagnostics = listOf(Diagnostic(24, 4, 24, "error", "late"), Diagnostic(23, 9, 23, "error", "first"),
        Diagnostic(22, 0, 22, "warning", "w"), Diagnostic(3, 0, 3, "error", "outside")))
    check("first error in decl", errs.firstError(decls[1])?.message == "first")
    check("no error", r.firstError(decls[0]) == null)
    val onlyUnsolved = r.copy(diagnostics = listOf(Diagnostic(17, 60, 18, "error", "unsolved goals\n⊢ p"),
        Diagnostic(22, 4, 22, "error", "real")))
    check("unsolved goals skipped for the banner", onlyUnsolved.firstError(decls[1])?.message == "real")
}

fun typeEach(start: Typed, chars: String, extra: Map<String, String> = emptyMap()): Typed = chars.fold(start) { t, ch ->
    val typed = Typed(t.text.substring(0, t.cursor) + ch + t.text.substring(t.cursor), t.cursor + 1)
    Abbrev.expand(typed, extra) ?: typed
}

fun input() {
    check("caret placed", Snippet.insert("", 0, 0, "rcases ‸ with ⟨⟩") == Typed("rcases  with ⟨⟩", 7))
    check("no caret: end", Snippet.insert("ab", 1, 1, "⊢") == Typed("a⊢b", 2))
    check("replaces selection", Snippet.insert("exact foo", 6, 9, "bar") == Typed("exact bar", 9))
    check("space before a word", Snippet.insert("exact", 5, 5, "h") == Typed("exact h", 7))
    check("no space after ⟨", Snippet.insert("⟨", 1, 1, "h") == Typed("⟨h", 2))
    check("no space before symbol", Snippet.insert("a", 1, 1, "→") == Typed("a→", 2))
    check("reversed selection", Snippet.insert("abcd", 3, 1, "x") == Typed("axd", 2))

    check("\\and then space", typeEach(Typed("", 0), "p \\and q") == Typed("p ∧ q", 5))
    check("\\ne vs \\neg", typeEach(Typed("", 0), "\\ne \\neg ").text == "≠ ¬ ")
    check("\\in vs \\inf", typeEach(Typed("", 0), "\\in \\inf ").text == "∈ ⊓ ")
    check("\\< then a name", typeEach(Typed("", 0), "\\<h, \\> ").text == "⟨h, ⟩ ")
    check("typed char kept", typeEach(Typed("", 0), "(\\a)").text == "(α)")
    check("unknown name untouched", typeEach(Typed("", 0), "\\zzz ").text == "\\zzz ")
    check("mid-text, cursor after", typeEach(Typed("x y", 2), "\\to ") == Typed("x → y", 4))
    check("palette caret markers valid", Palette.tactics.all { it.count { c -> c == Snippet.CARET } <= 1 })
    check("full Lean table", Abbrev.table.size == 1855 && Abbrev.table["McF"] == "ℱ" && Abbrev.table["bigcup"] == "⋃")
    check("every abbreviation reachable by space", Abbrev.table.all { (k, v) ->
        typeEach(Typed("", 0), "\\$k ").text == if (Abbrev.CURSOR in v) v.replace(Abbrev.CURSOR, "") else "$v " })
    check("common names are in the table", Abbrev.common.all { it in Abbrev.table })
    check("\\<> puts the cursor inside", typeEach(Typed("", 0), "\\<> ") == Typed("⟨⟩", 1))
    check("\\<> then a name types inside", typeEach(Typed("", 0), "\\<>h") == Typed("⟨h⟩", 2))
    check("pending", Abbrev.pending(Typed("exact \\su", 9)) == (6 to "su") && Abbrev.pending(Typed("a \\", 3)) == (2 to "") &&
        Abbrev.pending(Typed("\\su x", 5)) == null)
    val su = Abbrev.search("su")
    check("search: exact, common first, one per symbol", su.first() == ("sub" to "⊆") && su[1] == ("sup" to "⊔") &&
        su.map { it.second }.toSet().size == su.size && su.size in 6..24)
    check("search: empty prefix lists common", Abbrev.search("").first() == ("and" to "∧"))
    check("search: exact name first", Abbrev.search("in").first() == ("in" to "∈"))
    val proj = mapOf("cmb" to "⋆", "sub" to "⊑")
    check("search: project names win", Abbrev.search("cm", proj).first() == ("cmb" to "⋆") && Abbrev.search("sub", proj).first() == ("sub" to "⊑"))
    check("project abbreviation expands", typeEach(Typed("", 0), "a \\cmb b", proj).text == "a ⋆ b")
    check("project name shadows a prefix", typeEach(Typed("", 0), "\\cm ", mapOf("cmx" to "!")).text == "\\cm ")
    check("accept replaces the pending name", Abbrev.accept(Typed("exact \\su x", 9), "⊆") == Typed("exact ⊆ x", 7))
    check("accept with cursor marker", Abbrev.accept(Typed("\\<", 2), "⟨\$CURSOR⟩") == Typed("⟨⟩", 1))
    check("accept without pending inserts", Abbrev.accept(Typed("ab", 2), "∧").text == "ab∧")

    val diags = (Json.parse(File("fixtures/suggestions.json").readText()) as Map<*, *>)["diagnostics"] as List<*>
    val ds = diags.map { it as Map<*, *> }.map {
        Diagnostic((it["line"] as Long).toInt(), (it["col"] as Long).toInt(), (it["end_line"] as Long).toInt(),
            it["severity"] as String, it["message"] as String)
    }
    check("exact? suggestion", Suggestions.of(ds.filter { it.line == 4 }) == listOf(Suggestion("exact Finset.union_comm s t", null)))
    check("simp? suggestion", Suggestions.of(ds.filter { it.line == 6 }).single().tactic == "simp only [add_zero]")
    val rw = Suggestions.of(ds.filter { it.line == 8 })
    check("rw? suggestions with notes, failed ones skipped", rw == listOf(
        Suggestion("rw [Finset.subset_iff]", "∀ ⦃x : ℕ⦄, x ∈ s → x ∈ s ∪ s"),
        Suggestion("rw [← Finset.mem_powerset]", "s ∈ (s ∪ s).powerset")))
    check("errors are not suggestions", Suggestions.of(ds.filter { it.severity == "error" }).isEmpty())
}

fun drafts() {
    val a = FileRef("pkg", "A.lean")
    val b = FileRef("pkg", "B.lean")
    var d = Drafts()
    d = d.withText(a, "disk", "edited ⊢ \"q\"\n")
    check("text equal to base is not a draft", d.withText(a, "disk", "disk").copies.isEmpty())
    check("resumed when base matches", d.open(a, "disk") == Drafts.Opened.Resumed("edited ⊢ \"q\"\n"))
    check("stale when disk moved", d.open(a, "disk2") is Drafts.Opened.Stale)
    check("clean when none", d.open(b, "x") == Drafts.Opened.Clean)
    check("same path in another package is another file", d.open(FileRef("other", "A.lean"), "disk") == Drafts.Opened.Clean)

    // A proof whose lines move when lines are added above it.
    val t = "import X\n\ntheorem t : p := by\n  intro h\n  simp\n  exact h\n"
    val decl = Declaration(3, 6, "theorem", "t", DeclStatus.OK)
    d = d.withPrompt(a, t, decl, 'L', 5, "simp [h]").withPrompt(a, t, decl, 'I', 6, "done").withPrompt(b, t, decl, 'B', 3, "block")
    check("prompt found where kept", d.prompt(a, t, decl, 'L', 5)?.text == "simp [h]")
    check("round trip", Drafts.decode(d.encode()) == d)
    check("empty round trip", Drafts.decode(Drafts().encode()) == Drafts())

    val t2 = "import X\nimport Y\n\n-- note\ntheorem t : p := by\n  intro h\n  simp\n  exact h\n"
    val decl2 = Declaration(5, 8, "theorem", "t", DeclStatus.OK)
    check("prompt follows its line when lines are added above", d.prompt(a, t2, decl2, 'L', 7)?.text == "simp [h]" &&
        d.prompt(a, t2, decl2, 'L', 5) == null)
    check("insert prompt follows too", d.prompt(a, t2, decl2, 'I', 8)?.text == "done")
    check("promptsIn lists resolved lines in order", d.promptsIn(a, t2, decl2).map { it.first to it.second } == listOf('L' to 7, 'I' to 8))
    val t3 = "theorem t : p := by\n  intro h\n  have := 1\n  simp\n  exact h\n"
    val decl3 = Declaration(1, 5, "theorem", "t", DeclStatus.OK)
    check("a line added inside the proof: the anchor text finds it", d.prompt(a, t3, decl3, 'L', 4)?.text == "simp [h]")
    val t4 = "theorem t : p := by\n  intro h\n  simp_all\n  exact h\n"
    check("anchor gone: the offset from the declaration decides",
        d.prompt(a, t4, Declaration(1, 4, "theorem", "t", DeclStatus.OK), 'L', 3)?.text == "simp [h]")
    check("another declaration sees none", d.promptsIn(a, t, Declaration(3, 6, "theorem", "u", DeclStatus.OK)).isEmpty())
    check("block prompt goes with its declaration", d.prompt(b, t2, decl2, 'B', 5)?.text == "block")
    check("keeping again replaces, not duplicates", d.withPrompt(a, t2, decl2, 'L', 7, "simp").prompts[a]!!.count { it.kind == 'L' } == 1)
    check("empty prompt removed", d.withPrompt(a, t2, decl2, 'L', 7, "").prompt(a, t2, decl2, 'L', 7) == null)
    val anon = Declaration(3, 6, "example", null, DeclStatus.OK)
    check("unnamed declaration keyed by its label", Drafts.declKey(anon) == anon.label)

    val w = d.without(a)
    check("without drops copy and its prompts only", w.copies.isEmpty() && w.prompts.keys == setOf(b))

    // Version 1 stores: no package, prompts by absolute line.
    val v1 = Drafts.decode("""{"version":1,"copies":{"A.lean":{"base":"x","text":"y"}},"prompts":{"A.lean#L4":"simp","A.lean#Q9":"junk"}}""")
    check("v1 copy has no package yet", v1.copies.keys == setOf(FileRef("", "A.lean")))
    check("v1 prompt kept by its absolute line", v1.prompt(FileRef("", "A.lean"), t, decl, 'L', 4)?.text == "simp")
    check("v1 prompt outside the declaration not shown", v1.promptsIn(FileRef("", "A.lean"), t, Declaration(7, 9, "theorem", "u", DeclStatus.OK)).isEmpty())
    check("v1 malformed prompt key dropped", v1.prompts.values.flatten().size == 1)
    val adopted = v1.adopt("pkg")
    check("adopt moves v1 drafts to the default package", adopted.copies.keys == setOf(a) && adopted.prompts.keys == setOf(a))
    val both = Drafts(mapOf(FileRef("", "A.lean") to WorkingCopy("x", "old"), a to WorkingCopy("x", "new"))).adopt("pkg")
    check("adopt: the package's own draft wins", both.copies == mapOf(a to WorkingCopy("x", "new")))
    check("adopt is idempotent", adopted.adopt("pkg") == adopted)

    throws<Decode.ContractError>("unknown version refused") { Drafts.decode("""{"version":3,"copies":{},"prompts":{}}""") }
    throws<Decode.ContractError>("bad copy refused") { Drafts.decode("""{"version":1,"copies":{"a":{"base":1}},"prompts":{}}""") }
    throws<Decode.ContractError>("bad prompt kind refused") {
        Drafts.decode("""{"version":2,"copies":{},"prompts":{"p":{"A.lean":[{"kind":"Q","decl":"t","offset":1,"anchor":"x","text":"y"}]}}}""")
    }
    throws<Decode.ContractError>("non-string anchor refused") {
        Drafts.decode("""{"version":2,"copies":{},"prompts":{"p":{"A.lean":[{"kind":"L","decl":"t","offset":1,"anchor":3,"text":"y"}]}}}""")
    }
}

fun steps() {
    val r = Decode.check(File("fixtures/check_category.json").readText())
    val comp = ProofSteps.of(r.text, 17)!!
    check("steps of Hom.comp", comp.map { it.line } == (18..24).toList())
    check("step text trimmed, indent kept", comp[3] == StepLine(21, "· intro e d h e' he d' hd", 2) && comp[4].indent == 4)
    check("steps stop at the next column-0 line", ProofSteps.of(r.text, 7)!!.last().line == 14)
    check("instance: first by block", ProofSteps.of(r.text, 28)!!.first().line == 33)
    check("term mode: none", ProofSteps.of("def one : Nat := 1\n\ntheorem t : True := by\n  trivial", 1) == null)
    check("by on a later header line", ProofSteps.of("theorem t\n    (h : p) :\n    p := by\n  -- note\n\n  exact h\n", 1) ==
        listOf(StepLine(6, "exact h", 2)))
    check("by with a trailing comment", ProofSteps.of("theorem t : p := by -- x\n  exact h", 1)!!.single().line == 2)
    check("empty by block", ProofSteps.of("theorem t : p := by\nend X", 1) == emptyList<StepLine>())
    check("by line", ProofSteps.byLine("theorem t\n    (h : p) :\n    p := by\n  exact h", 1) == 3)
    check("by line: none in term mode", ProofSteps.byLine("def one : Nat := 1\ntheorem t : True := by", 1) == null)
    check("first step into an empty block: indented past the by line",
        ProofSteps.insert("theorem t : p := by\nend X", 1, 1, "exact h") == "theorem t : p := by\n  exact h\nend X")
    check("first step under an indented by line",
        ProofSteps.insert("  theorem t : p := by\n", 1, 1, "trivial") == "  theorem t : p := by\n    trivial\n")
    check("a step after the by line of a non-empty block takes the first step's indent",
        ProofSteps.insert("theorem t : p := by\n    exact h", 1, 1, "skip") == "theorem t : p := by\n    skip\n    exact h")
    check("a step after a step is its sibling",
        ProofSteps.insert("theorem t : p := by\n  intro h\n  exact h", 1, 2, "skip") == "theorem t : p := by\n  intro h\n  skip\n  exact h")

    val rc = Step.of(r.goalsAt(22)!!.goals, r.goalsAfter(22)!!.goals)
    check("summary rcases", rc.summary() == "+ tE htE he' · − he")
    check("summary closed", Step.Closed(0).summary() == "closes the goal ✓" && Step.Closed(2).summary() == "closes the goal · 2 left")
    val g = Goal(null, listOf(Hyp(listOf("h"), "a")), "p")
    check("summary target", Step.of(listOf(g), listOf(g.copy(target = "q"))).summary() == "⊢ changed")
    check("summary nothing", Step.of(listOf(g), listOf(g)).summary() == "no visible change")
    check("summary split", Step.of(listOf(g), listOf(g, g.copy(case = "b"))).summary() == "+1 goal")

    check("accept dotted", Complete.accept(Typed("exact hD.tr", 11), "tr", "trans") == Typed("exact hD.trans", 14))
    check("accept namespaced", Complete.accept(Typed("exact Finset.union_co x", 21), "union_co", "union_comm") ==
        Typed("exact Finset.union_comm x", 23))
    check("accept full name for bare prefix", Complete.accept(Typed("exact h", 7), "h", "Nat.hmul").text == "exact Nat.hmul")
    check("accept after typing on: insert", Complete.accept(Typed("exact hD.trx", 12), "tr", "trans") == Typed("exact hD.trxtrans", 17))
    check("accept empty fragment after dot", Complete.accept(Typed("hD.", 3), "", "trans") == Typed("hD.trans", 8))
    check("wanted", Complete.wanted(Typed("exact hD.", 9)) && Complete.wanted(Typed("exact h₁", 8)) &&
        !Complete.wanted(Typed("exact ", 6)) && !Complete.wanted(Typed("", 0)) && !Complete.wanted(Typed("⟨", 1)))
    val (frag, items) = Decode.completions("""{"fragment":"tr","items":[{"label":"trans","kind":23},{"label":"h","kind":null}]}""")
    check("decode completions", frag == "tr" && items == listOf(Completion("trans", 23), Completion("h", null)))
    check("complete request", Json.parse(Requests.complete("A.lean", "x ⊢", 3, 7)) ==
        mapOf("file" to "A.lean", "text" to "x ⊢", "line" to 3L, "col" to 7L))
    check("deleteLine", Edit.deleteLine("a\nb\nc", 2) == "a\nc" && Edit.deleteLine("a", 1) == "")
}

fun create() {
    val th = NewDecl.skeleton(DeclKind.THEOREM)
    check("skeleton: caret on the name", th.text == "theorem  :  := by\n  sorry" && th.cursor == 8)
    check("every skeleton has a caret", DeclKind.values().all { it.skeleton.count { c -> c == Snippet.CARET } == 1 })
    check("finish names the end", NewDecl.finish("namespace Foo\n\nend ") == "namespace Foo\n\nend Foo")
    check("finish keeps a named end", NewDecl.finish("section S\nend T") == "section S\nend T")
    check("finish leaves others", NewDecl.finish("theorem t : p := by\n  sorry\n\n") == "theorem t : p := by\n  sorry")

    val file = "import A\n\nnamespace X\n\ntheorem a : p := by\n  sorry\n\ntheorem b : q := by\n  sorry\n\nend X\n"
    val a = Declaration(5, 6, "theorem", "a", DeclStatus.SORRY)
    val (t1, l1) = NewDecl.insert(file, a, "def c := 1")
    check("insert after a declaration", t1 == "import A\n\nnamespace X\n\ntheorem a : p := by\n  sorry\n\ndef c := 1\n\n" +
        "theorem b : q := by\n  sorry\n\nend X\n" && l1 == 8)
    val (t2, l2) = NewDecl.insert(file, null, "def c := 1")
    check("insert before the closing end", t2.endsWith("  sorry\n\ndef c := 1\n\nend X\n") && t2.split("\n")[l2 - 1] == "def c := 1")
    val nested = "namespace X\nnamespace Y\ndef a := 1\nend Y\n\nend X"
    check("insert inside nested namespaces", NewDecl.insert(nested, null, "def c := 1").first ==
        "namespace X\nnamespace Y\ndef a := 1\n\ndef c := 1\n\nend Y\n\nend X")
    check("insert at end without end", NewDecl.insert("import A\n\n\n", null, "def c := 1") == ("import A\n\ndef c := 1\n\n\n" to 3))
    check("insert into empty file", NewDecl.insert("", null, "def c := 1") == ("def c := 1\n" to 1))
    val last = Declaration(8, 9, "theorem", "b", DeclStatus.SORRY)
    val (t3, l3) = NewDecl.insert(file, last, "theorem n : r := by\n  sorry")
    check("insert after the last declaration stays inside", t3.endsWith("theorem n : r := by\n  sorry\n\nend X\n") &&
        t3.split("\n")[l3 - 1] == "theorem n : r := by")

    check("module", NewFile.module("Lib/Sub/New_2.lean") == "Lib.Sub.New_2")
    check("module refuses", listOf("Top.lean", "Lib/sub/A.lean", "Lib/A.txt", ".lake/A/B.lean", "Lib/../A.lean")
        .all { NewFile.module(it) == null })
    val ex = listOf("Lib.lean", "Lib/Sub/A.lean", "Lib/B.lean")
    check("problem: ok", NewFile.problem("Lib/Sub/New.lean", ex) == null && NewFile.problem("Lib/Fresh/New.lean", ex) == null)
    check("problem: exists", NewFile.problem("Lib/Sub/A.lean", ex) == "Lib/Sub/A.lean already exists")
    check("problem: other library", NewFile.problem("Other/A.lean", ex) == "New files go in Lib")
    check("problem: name", NewFile.problem("Lib/sub.lean", ex)?.startsWith("Use Library") == true)
    check("folders", NewFile.folders(ex) == listOf("Lib", "Lib/Sub"))
    check("file text", NewFile.text(listOf("Lib.Sub.A", "Mathlib.Order.Basic"), " Systems ") ==
        "import Lib.Sub.A\nimport Mathlib.Order.Basic\n\nnamespace Systems\n\nend Systems\n")
    check("file text bare", NewFile.text(emptyList(), "") == "")
    check("namespaceOf", NewFile.namespaceOf(file) == "X" && NewFile.namespaceOf("def a := 1") == "")
    check("create request", Json.parse(Requests.create("L/A.lean", "x\n")) == mapOf("file" to "L/A.lean", "text" to "x\n"))

    val n = NotationSpec(symbol = "⋆", target = "Systems.combine")
    check("notation text", n.text() == "scoped infixl:65 \" ⋆ \" => Systems.combine" && n.problem() == null)
    val pre = n.withFixity(Fixity.PREFIX).copy(symbol = " √ ", scoped = false)
    check("prefix: max, no padding", pre.text() == "prefix:max \"√\" => Systems.combine")
    check("fixity keeps an infix precedence", n.copy(precedence = "50").withFixity(Fixity.INFIXR).precedence == "50" &&
        pre.withFixity(Fixity.INFIX).precedence == "65")
    check("notation problems", NotationSpec(target = "f").problem() == "type a symbol" &&
        n.copy(symbol = "a\"b").problem() != null && n.copy(precedence = "high").problem() != null &&
        n.copy(target = " ").problem() != null && n.copy(precedence = "max").problem() == null)
    check("abbrev names", NotationSpec.abbrevProblem("") == null && NotationSpec.abbrevProblem("cmb") == null &&
        NotationSpec.abbrevProblem("a b") != null && NotationSpec.abbrevProblem("a\\b") != null)
    check("notation label", Declaration(3, 3, "infixl", "⋆", DeclStatus.OK).label == "infixl ⋆" &&
        Declaration(1, 2, "theorem", "t", DeclStatus.OK).label == "t")
    check("abbreviation request", Json.parse(Requests.abbreviation("cmb", "⋆")) == mapOf("name" to "cmb", "symbol" to "⋆"))
    check("decode abbreviations", Decode.abbreviations("""{"cmb":"⋆"}""") == mapOf("cmb" to "⋆"))
    throws<Decode.ContractError>("abbreviation not a string") { Decode.abbreviations("""{"cmb":1}""") }

    val ov = Decode.overview("""{"files":[
        {"path":"Lib/Sub/B.lean","title":null,"decls":0,"sorries":0,"empty":true,"role":"unimported"},
        {"path":"Lib.lean","title":null,"decls":0,"sorries":0,"empty":false,"role":"root"},
        {"path":"Lib/Sub/A.lean","title":"Generation (step 4)","decls":3,"sorries":1,"empty":false,"role":"imported"},
        {"path":"Lib/Top.lean","title":null,"decls":1,"sorries":0,"empty":false,"role":"imported"},
        {"path":"Scratch.lean","title":null,"decls":1,"sorries":1,"empty":false,"role":"other"}]}""")
    check("decode overview", ov.size == 5 && ov[2] == FileInfo("Lib/Sub/A.lean", "Generation (step 4)", 3, 1, false, "imported"))
    fun show(rows: List<TreeRow>) = rows.map { r -> "  ".repeat(r.depth) + when (r) {
        is TreeRow.Folder -> r.name + "/" + r.files + if (r.collapsed) "+" else ""
        is TreeRow.File -> r.info.name } }
    check("tree", show(FileTree.rows(ov, emptySet())) == listOf("Lib.lean", "Lib/3", "  Sub/2", "    A.lean", "    B.lean", "  Top.lean", "Scratch.lean"))
    check("tree collapsed", show(FileTree.rows(ov, setOf("Lib/Sub"))) == listOf("Lib.lean", "Lib/3", "  Sub/2+", "  Top.lean", "Scratch.lean"))
    check("tree collapsed at top", show(FileTree.rows(ov, setOf("Lib", "Lib/Sub"))) == listOf("Lib.lean", "Lib/3+", "Scratch.lean"))
    throws<Decode.ContractError>("overview without empty") { Decode.overview("""{"files":[{"path":"a","title":null,"decls":0,"sorries":0,"role":"x"}]}""") }
}

fun startup() {
    check("start args", BridgeStart.arguments(false) == listOf(BridgeStart.SCRIPT) &&
        BridgeStart.arguments(true) == listOf(BridgeStart.SCRIPT, "--pair"))
    check("script is lean-mcp's", BridgeStart.SCRIPT.endsWith("/LeanProjects/lean-mcp/start-bridge.sh") &&
        BridgeStart.shellCommand(false).startsWith("bash ~/LeanProjects/lean-mcp/start-bridge.sh"))
    check("shell command detaches", BridgeStart.shellCommand(false).endsWith(" --detach") &&
        BridgeStart.shellCommand(true).endsWith(" --detach --pair"))
    check("polls about 20 s", BridgeStart.POLL_GAPS_MS.sum() in 18_000L..22_000L && BridgeStart.POLL_GAPS_MS.first() <= 500L)
    check("auto start only when down, permitted, first time",
        BridgeStart.autoStart(up = false, permitted = true, alreadyTried = false) &&
        !BridgeStart.autoStart(up = true, permitted = true, alreadyTried = false) &&
        !BridgeStart.autoStart(up = false, permitted = false, alreadyTried = false) &&
        !BridgeStart.autoStart(up = false, permitted = true, alreadyTried = true))

    val S = LatticeSplash
    val start = S.frame(0f)
    check("splash starts empty and opaque", start.nodes.all { it == 0f } && start.edges.all { it == 0f } && start.alpha == 1f && start.scale == 1f)
    val drawn = S.frame(S.DRAW_MS)
    check("drawn at DRAW_MS", drawn.nodes.all { kotlin.math.abs(it - 1f) < 1e-3f } && drawn.edges.all { it == 1f })
    check("draw is slower than the old 850 ms", S.DRAW_MS >= 1_200f)
    // Bottom-up: at every instant a lower element is at least as far along as a higher one.
    val order = (0..130).map { S.frame(it * 10f) }.all { f ->
        f.edges[0] >= f.edges[2] && (f.nodes[0] > 0f || f.nodes[1] == 0f) && (f.nodes[1] > 0f || f.nodes[3] == 0f) }
    check("draws bottom-up, join last", order)
    check("join lights after the upper edges start", S.frame(990f).nodes[3] == 0f && S.frame(990f).edges[2] > 0f)
    val beat = (0..48).map { S.frame(S.DRAW_MS + it * 10f).scale }
    check("one heartbeat after drawing", beat.maxOrNull()!! in 1.05f..1.09f && beat.minOrNull()!! < 1f && beat.first() == 1f)
    check("glow only during the beat", S.frame(S.DRAW_MS).glow == 0f && S.frame(S.DRAW_MS + S.PULSE_MS / 2).glow > 0.99f &&
        S.frame(S.DRAW_MS + S.PULSE_MS + 1f).glow < 1e-3f)
    val linger = S.PULSE_MS + S.HOLD_MS
    check("lingers 0.5 to 1 s after the beat starts", linger in 500f..1_000f)
    val hold = S.frame(S.DRAW_MS + S.PULSE_MS + S.HOLD_MS / 2)
    check("still and opaque while holding", hold.scale == 1f && hold.alpha == 1f)
    check("fades out at the end", S.frame(S.TOTAL_MS).alpha == 0f && S.frame(S.TOTAL_MS + 500f).alpha == 0f &&
        S.frame(S.TOTAL_MS - S.FADE_MS / 2).alpha in 0.01f..0.99f)
    check("pop settles at 1 and overshoots a little", kotlin.math.abs(S.pop(1f) - 1f) < 1e-4f &&
        (0..100).map { S.pop(it / 100f) }.maxOrNull()!! in 1.0f..1.12f)
    check("heartbeat is continuous", (1 until 480).all { kotlin.math.abs(S.heartbeat(it.toFloat()) - S.heartbeat(it - 1f)) < 0.01f })
}

fun search() {
    check("forms", HitForm.values().map { it.tactic("sup_le") } == listOf("sup_le", "exact sup_le", "apply sup_le", "rw [sup_le]"))
    check("tactic form fills an empty field", HitUse.apply("  ", 0, 0, HitForm.EXACT, "sup_le") == Typed("exact sup_le", 12))
    check("name goes in at the cursor", HitUse.apply("exact  ha hb", 6, 6, HitForm.NAME, "sup_le") == Typed("exact sup_le ha hb", 12))
    check("a form never drops typed text", HitUse.apply("have h := ", 10, 10, HitForm.EXACT, "x").text == "have h := exact x")
    check("chip: exact into an empty field, the name into typed text",
        HitUse.chip("", 0, 0, "sup_le").text == "exact sup_le" && HitUse.chip("exact  ha", 6, 6, "sup_le").text == "exact sup_le ha")
    check("usable states", HitState.values().filter { it.usable } == listOf(HitState.OK, HitState.UNCHECKED))
    throws<Decode.ContractError>("unknown state") { HitState.of("maybe") }

    val file = "import Mathlib.Order.Lattice\n-- a comment\nimport Mathlib.Data.Finset.Basic\n\n/-! doc -/\nnamespace X\ntheorem t : True := trivial\nend X"
    val added = Imports.add(file, "Mathlib.Analysis.Real.Pi.Bounds")
    check("import after the last import", added.split("\n")[3] == "import Mathlib.Analysis.Real.Pi.Bounds")
    check("exactly one line added, the rest in order", added.split("\n").filterIndexed { i, _ -> i != 3 } == file.split("\n"))
    check("import already there: unchanged", Imports.add(file, "Mathlib.Order.Lattice") == file && Imports.has(file, "Mathlib.Data.Finset.Basic"))
    check("no imports: first line", Imports.add("namespace X\nend X", "M").split("\n") == listOf("import M", "namespace X", "end X"))
    check("a module named in a comment is not an import", !Imports.has("-- import M\nnamespace X", "M"))

    val req = Json.parse(SearchRequests.search(Engine.LOOGLE, "|- _ ⊔ _ ≤ _", "A.lean", "t\"x")) as Map<*, *>
    check("search request", req == mapOf("engine" to "loogle", "query" to "|- _ ⊔ _ ≤ _", "file" to "A.lean", "text" to "t\"x"))
    check("search request without file has no text", Json.parse(SearchRequests.search(Engine.LEANSEARCH, "q", null, "t")) ==
        mapOf("engine" to "leansearch", "query" to "q"))
    check("pin request", Json.parse(SearchRequests.pin(Pinned("sup_le", "Mathlib.Order.Lattice", "a ≤ c"), true)) ==
        mapOf("name" to "sup_le", "module" to "Mathlib.Order.Lattice", "type" to "a ≤ c", "pinned" to true))
    check("decode toolbox", SearchDecode.toolbox("""[{"name":"sup_le","module":"M","type":"t"}]""") == listOf(Pinned("sup_le", "M", "t")))
    check("decode source", SearchDecode.source("""{"module":"M","path":"p","text":"a\nb","line":2}""") == Source("M", "p", "a\nb", 2))
    throws<Decode.ContractError>("hit without state") {
        SearchDecode.result("""{"engine":"loogle","query":"q","count":1,"hits":[{"name":"a","type":"t","module":"M"}],"error":null}""")
    }
    val bad = SearchDecode.result("""{"engine":"loogle","query":"Finset.sum_comn","count":0,"hits":[],"error":"unknown identifier","suggestions":["x"]}""")
    // Produced by the real bridge (Loogle `|- _ ⊔ _ ≤ _`, in Generation.lean's scope), 2026-09-23.
    val real = SearchDecode.result(File("fixtures/search_loogle.json").readText())
    check("real search: 30 of 75", real.engine == "loogle" && real.count == 75 && real.hits.size == 30 && real.error == null)
    check("real search: states", real.hits.count { it.state == HitState.OK } == 24 && real.hits.count { it.state == HitState.IMPORT } == 6)
    check("real search: an import hit names its module", real.hits.first { it.name == "le_pow_sup" }.module == "Mathlib.Algebra.Order.Monoid.Unbundled.Pow")
    val g = Goal(null, listOf(Hyp(listOf("ha"), "a ≤ c"), Hyp(listOf("hb"), "b ≤ c")), "a ⊔ b ≤ c")
    fun cr(diags: List<Diagnostic>, after: List<Goal>?) =
        CheckResult("F.lean", 0.1, "t", diags, emptyList(), listOf(GoalsAt(5, listOf(g))), listOf(GoalsAt(5, after)))
    check("try: closes", TryResult.of(cr(emptyList(), emptyList()), 5) == TryOutcome(true, "closes the goal ✓", null))
    check("try: error headline", TryResult.of(cr(listOf(Diagnostic(5, 2, 5, "error", "type mismatch\n\nmore")), null), 5) ==
        TryOutcome(false, "type mismatch", null))
    check("try: unsolved goals is not an error", TryResult.of(cr(listOf(Diagnostic(5, 2, 5, "error", "unsolved goals\nx")),
        listOf(g.copy(target = "a ≤ c"))), 5) == TryOutcome(true, "⊢ changed", "a ≤ c"))
    check("try: an error on another line is not this one", TryResult.of(cr(listOf(Diagnostic(9, 0, 9, "error", "boom")), emptyList()), 5).ok)
    check("a bad query is data", bad.error == "unknown identifier" && bad.hits.isEmpty() && bad.suggestions == listOf("x"))
}

fun packages() {
    val files = listOf(FileInfo("Demo.lean", "Demo lib", 0, 0, false, "root"), FileInfo("Demo/A.lean", null, 3, 1, false, "imported"),
        FileInfo("Demo/B.lean", null, 2, 0, false, "imported"), FileInfo("Demo/C.lean", null, 0, 0, true, "unimported"))
    val lib = Library("Demo", "Demo.lean", files)
    check("library counts", lib.decls == 5 && lib.sorries == 1 && lib.notBuilt == 1 && lib.title == "Demo lib")
    check("library cells", lib.cells == listOf(Cell.ROOT, Cell.SORRY, Cell.PROVED, Cell.NOT_BUILT))
    check("membership", lib.contains("Demo.lean") && lib.contains("Demo/X/Y.lean") && !lib.contains("Demon.lean") && !lib.contains("Demo"))

    check("git text", PackageText.git(GitState("main", 0, 0, 0)) == "main · clean · ↑0" &&
        PackageText.git(GitState("wip", 3, 2, 1)) == "wip · 3 changed · ↑2 · ↓1" && PackageText.git(null) == "no git")
    check("iso millis", PackageText.isoMillis("2026-09-22T12:43:16Z") == 1790080996000L &&
        PackageText.isoMillis("2000-02-29T00:00:00Z") == 951782400000L && PackageText.isoMillis("1969-12-31T23:59:59Z") == -1000L &&
        PackageText.isoMillis("2026-09-22 12:43") == null)
    val run = CiRun("completed", "success", "2026-09-22T12:43:16Z", "c9a3c27", "Lean Action CI")
    check("ci text", PackageText.ci(run, 1790080996000L + 24 * 3_600_000L) == "CI ✓ 24 h ago" &&
        PackageText.ci(run.copy(conclusion = "failure"), 1790080996000L + 3 * 86_400_000L) == "CI ✕ failure 3 d ago" &&
        PackageText.ci(run.copy(status = "in_progress"), 0) == "CI running" && PackageText.ci(null, 0) == null)
    check("ago", PackageText.ago(10_000) == "just now" && PackageText.ago(59 * 60_000L) == "59 min ago" &&
        PackageText.ago(47 * 3_600_000L) == "47 h ago" && PackageText.ago(48 * 3_600_000L) == "2 d ago")

    // Produced by the real bridge's GET /v1/packages on 2026-09-23.
    val real = PackagesDecode.packages(File("fixtures/packages.json").readText())
    val ss = real.named(null)!!
    check("real packages: default and library", real.default == "synthetic-systems" && ss.libraries.map { it.name } == listOf("SyntheticSystems") &&
        ss.canAddLibrary && ss.toolchain == "v4.35.0-rc2")
    check("real packages: every source in its library", ss.libraries[0].files.all { ss.libraries[0].contains(it.path) } &&
        ss.libraries[0].cells.first() == Cell.ROOT && ss.libraries[0].cells.contains(Cell.NOT_BUILT))
    check("real packages: git and CI", ss.git?.branch == "main" && ss.ci?.conclusion == "success" && ss.ci?.sha == "c9a3c27")
    check("real packages: Mathlib", ss.dependencies.first { it.name == "mathlib" }.let { it.library == "Mathlib" && it.files > 8000 })
    val pk = Package("demo", "v4.35.0-rc2", "lakefile.toml", listOf(lib), emptyList(), null, null, emptyList())
    check("new library problems", PackageText.newLibraryProblem(pk, "Examples") == null &&
        PackageText.newLibraryProblem(pk, "Demo") != null && PackageText.newLibraryProblem(pk, "lower") != null &&
        PackageText.newLibraryProblem(pk, "") == "type a name" &&
        PackageText.newLibraryProblem(pk.copy(lakefile = "lakefile.lean"), "Examples")!!.contains("lakefile.toml"))
    check("library of a path", pk.libraryOf("Demo/A.lean") == lib && pk.libraryOf("Scratch.lean") == null)
    check("resume round trip", Resume.decode(Resume.encode(Place("Demo/A.lean", "a_b"))) == Place("Demo/A.lean", "a_b") &&
        Resume.decode(Resume.encode(Place("Demo/A.lean", null))) == Place("Demo/A.lean", null) && Resume.decode(null) == null &&
        Resume.decode("") == null)
}

/** The spans of [text] as (substring, kind), to read a colouring at a glance. */
fun painted(text: String, spans: List<Span>) = spans.map { text.substring(it.start, it.end) to it.kind }

/** Spans are sorted, in bounds, non-empty and never overlap: the invariant every renderer relies on. */
fun wellFormed(text: String, spans: List<Span>): Boolean =
    spans.all { it.start >= 0 && it.end <= text.length && it.start < it.end } &&
        spans.zipWithNext().all { (a, b) -> a.end <= b.start }

fun highlight() {
    val r = Decode.check(File("fixtures/check_category.json").readText())
    val lines = r.text.split("\n")

    // Layer 1, the scanner, on the real file.
    val l17 = painted(lines[16], Lexer.spans(lines[16]))
    check("command", l17.first() == ("def" to Code.KEYWORD))
    check("declared name, dotted whole", ("Hom.comp" to Code.DECL) in l17)
    check("binders stay plain without Lean", l17.none { it.first == "S" || it.first == "f" })
    check(":= is a symbol", (":=" to Code.SYMBOL) in l17)
    val l18 = painted(lines[17], Lexer.spans(lines[17]))
    check("tactic", l18.first() == ("refine" to Code.TACTIC))
    check("holes", l18.filter { it.second == Code.HOLE }.map { it.first } == listOf("?onEntity", "?onDomain", "?preserve"))
    check("anonymous constructor brackets", ("⟨" to Code.SYMBOL) in l18 && ("⟩" to Code.SYMBOL) in l18)
    check("attribute", painted(lines[15], Lexer.spans(lines[15])) == listOf("@[simp]" to Code.ATTR))
    check("unnamed instance names nothing", painted(lines[27], Lexer.spans(lines[27])).none { it.second == Code.DECL })

    fun lx(t: String) = painted(t, Lexer.spans(t))
    check("line comment to the end", lx("exact h -- why ⊢") == listOf("exact" to Code.TACTIC, "-- why ⊢" to Code.COMMENT))
    check("nested block comment", lx("/- a /- b -/ c -/ def") == listOf("/- a /- b -/ c -/" to Code.COMMENT, "def" to Code.KEYWORD))
    check("doc comment", lx("/-- The join. -/")[0].second == Code.COMMENT)
    check("unclosed comment runs to the end", lx("x /- open").last() == ("/- open" to Code.COMMENT))
    check("string with escaped quote", lx("\"a\\\"b\" x") == listOf("\"a\\\"b\"" to Code.LITERAL))
    check("sorry", lx("  sorry") == listOf("sorry" to Code.SORRY))
    check("exact? is a tactic", lx("exact?") == listOf("exact?" to Code.TACTIC))
    check("dotted tactic-like name is not a tactic", lx("Finset.simp") .isEmpty())
    check("h.1: name, then number", lx("h.1") == listOf("1" to Code.LITERAL))
    check("primes stay in the name", lx("theorem he' : x") .contains("he'" to Code.DECL))
    check("lone underscore is no name", Lexer.identifiers("f _ x").size == 2)
    val sample = r.text + "\n/- /- -/ \"s\" 3.14 ?_ ∀ x, x ∈ s → ⊢ exact? h.1 @[simp, ext] -- end"
    check("scanner invariant on the file", wellFormed(sample, Lexer.spans(sample)))

    // Layer 2, Lean's tokens from the real bridge payload.
    check("fixture carries tokens", r.tokens.size > 100)
    val all = Highlight.of(r.text, r.tokens)
    check("both layers well formed", wellFormed(r.text, all))
    val byLine = Highlight.byLine(r.text, all)
    check("one span list per line", byLine.size == lines.size)
    check("byLine equals slicing each line", byLine == Highlight.lineStarts(r.text).let { st ->
        st.indices.map { k -> Highlight.slice(all, st[k], if (k + 1 < st.size) st[k + 1] - 1 else r.text.length) } })
    val cm = "a /- one\ntwo\nthree -/ b"
    check("a block comment is split across its lines", Highlight.byLine(cm, Lexer.spans(cm)) ==
        listOf(listOf(Span(2, 8, Code.COMMENT)), listOf(Span(0, 3, Code.COMMENT)), listOf(Span(0, 8, Code.COMMENT))))
    val big = (1..3000).joinToString("\n") { "  exact foo.bar $it -- c" }
    val t0 = System.nanoTime()
    val bl = Highlight.byLine(big, Lexer.spans(big))
    check("3000 lines split in well under a second", bl.size == 3000 && (System.nanoTime() - t0) < 1_000_000_000L)
    val p14 = painted(lines[13], byLine[13])
    check("exact h: tactic stays, h is local", p14 == listOf("exact" to Code.TACTIC, "h" to Code.LOCAL))
    val p19 = painted(lines[18], byLine[18])
    check("f.onEntity: local then field", ("f" to Code.LOCAL) in p19 && ("onEntity" to Code.FIELD) in p19)
    check("binders are locals with Lean", ("S" to Code.LOCAL) in painted(lines[16], byLine[16]))
    check("simp in @[simp] stays an attribute", painted(lines[15], byLine[15]) == listOf("@[simp]" to Code.ATTR))

    val t = "exact h -- h\n  x"
    val lean = listOf(LeanToken(1, 6, 1, "variable"), LeanToken(1, 11, 1, "variable"), LeanToken(1, 0, 5, "keyword"),
        LeanToken(2, 2, 9, "variable"), LeanToken(3, 0, 1, "variable"), LeanToken(1, 0, 1, "function"))
    val ls = Highlight.leanSpans(t, lean)
    check("tokens past a line's end are dropped", ls.none { it.start >= 13 })
    check("unknown kinds are dropped", ls.size == 3)
    check("Lean cannot recolour a comment", painted(t, Highlight.of(t, lean)) ==
        listOf("exact" to Code.TACTIC, "h" to Code.LOCAL, "-- h" to Code.COMMENT))
    check("Lean keyword fills a plain word", painted("foo", Highlight.of("foo", listOf(LeanToken(1, 0, 3, "keyword")))) ==
        listOf("foo" to Code.KEYWORD))
    check("Lean sorry", painted("x", Highlight.of("x", listOf(LeanToken(1, 0, 1, "sorry")))) == listOf("x" to Code.SORRY))
    check("slice", Highlight.slice(listOf(Span(2, 8, Code.LOCAL)), 4, 6) == listOf(Span(0, 2, Code.LOCAL)))

    // Carrying Lean's tokens across an edit.
    val old = "a\nb\nc\nd"
    val toks = (1..4).map { LeanToken(it, 0, 1, "variable") }
    check("unchanged text keeps all", TokenShift.carry(toks, old, old) == toks)
    check("edited line loses its tokens only", TokenShift.carry(toks, old, "a\nB\nc\nd").map { it.line } == listOf(1, 3, 4))
    check("an inserted line moves the ones below", TokenShift.carry(toks, old, "a\nb\nnew\nc\nd").map { it.line } == listOf(1, 2, 4, 5))
    check("a deleted line moves them up", TokenShift.carry(toks, old, "a\nc\nd").map { it.line } == listOf(1, 2, 3))
    check("repeated lines: no token is carried twice", TokenShift.carry(listOf(toks[1], toks[2]), "a\nb\nb", "a\nb").map { it.line } == listOf(2))
    check("repeated lines: suffix never overlaps prefix", TokenShift.carry(toks.take(2), "x\nx", "x\nx\nx").map { it.line } == listOf(1, 2))
    check("carried tokens still point at their words", run {
        val edited = r.text.replace("  subst hd'\n", "  subst hd'\n  skip\n")
        val carried = TokenShift.carry(r.tokens, r.text, edited)
        val sp = Highlight.byLine(edited, Highlight.of(edited, carried))
        val el = edited.split("\n")
        el[13] == "  skip" && painted(el[13], sp[13]).isEmpty() &&
            painted(el[14], sp[14]) == listOf("exact" to Code.TACTIC, "h" to Code.LOCAL) &&
            painted(el[19], sp[19]).contains("f" to Code.LOCAL)
    })
    check("a declaration's own tokens", TokenShift.within(listOf(LeanToken(17, 0, 3, "keyword"), LeanToken(30, 0, 1, "x")), 17, 24) ==
        listOf(LeanToken(1, 0, 3, "keyword")))

    val decl = Edit.lines(r.text, 7, 14).joinToString("\n")
    val blk = painted(decl, Highlight.block(r, 7, 14, decl))
    check("block edit: Lean's locals line up with the draft", ("h" to Code.LOCAL) in blk && ("Hom.id" to Code.DECL) in blk)
    val typed = decl.replace("  exact h", "  exact h'")
    check("block edit: the edited line falls back to the scanner",
        painted(typed, Highlight.block(r, 7, 14, typed)).last() == ("exact" to Code.TACTIC))

    // Goals: colours from the goal's own structure.
    val g = r.goalsAt(22)!!.goals!!.single()
    val locals = GoalColour.locals(g)
    check("goal locals", "htE" !in locals && "f" in locals && "S" in locals)
    val ty = "tE ∈ f.onEntity e"
    check("goal type: locals, field, symbol", painted(ty, GoalColour.spans(ty, setOf("tE", "f", "e"))) ==
        listOf("tE" to Code.LOCAL, "∈" to Code.SYMBOL, "f" to Code.LOCAL, ".onEntity" to Code.FIELD, "e" to Code.LOCAL))
    check("a constant is not a local", GoalColour.spans("Set.univ", setOf("s")).isEmpty())
    check("goal spans well formed", wellFormed(g.target, GoalColour.spans(g.target, locals)))

    check("no tokens field decodes as none", Decode.check("""{"file":"f","seconds":1,"text":"","diagnostics":[],
        "declarations":[],"goals":[],"goals_after":[]}""").tokens.isEmpty())
    throws<Decode.ContractError>("malformed token refused") {
        Decode.check("""{"file":"f","seconds":1,"text":"","diagnostics":[],"declarations":[],"goals":[],"goals_after":[],
            "tokens":[[1,2,"x","keyword"]]}""")
    }
}

fun main() {
    json(); contract(); diagnostics(); pairing(); edit(); proof(); input(); drafts(); steps(); create(); startup(); search(); packages(); highlight()
    println("$passed passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
