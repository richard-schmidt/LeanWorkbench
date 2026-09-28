package com.leanworkbench.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import leanwb.Abbrev
import leanwb.BridgeStart
import leanwb.Complete
import leanwb.Completion
import leanwb.ProofSteps
import leanwb.StepLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import leanwb.CheckResult
import leanwb.Declaration
import leanwb.Drafts
import leanwb.Edit
import leanwb.FileRef
import leanwb.Pairing
import leanwb.Snippet
import leanwb.Typed
import leanwb.Undo
import leanwb.WorkingCopy
import java.io.File

sealed interface Screen {
    /** The landing page: packages as tabs, their libraries as cards. */
    object Libraries : Screen
    /** One library's folder tree. */
    object Files : Screen
    data class File(val file: String) : Screen
    /**
     * A declaration: by name when it has one (edits above it move its line), else by
     * its first line (edits inside it never move that line).
     */
    data class Decl(val file: String, val line: Int, val name: String?) : Screen
    /** A new declaration being typed, to go after [after] (null: before the file's closing `end`s). */
    data class NewDecl(val file: String, val after: Declaration?, val kind: leanwb.DeclKind) : Screen
}

/** How a declaration is shown: step cards, the goal filling the screen, or the source. */
enum class ProofView { STEPS, GOAL, SOURCE }

/** What is being edited: one line, a new line after one, or the whole declaration. */
sealed interface EditMode {
    data class Line(val line: Int) : EditMode
    data class Insert(val after: Int) : EditMode
    object Block : EditMode
}

const val REQUIRES = "@requires"
const val NEW_PACKAGE = "@new"

/**
 * All screen state. An edit made in the app lives in [workingText] and travels to
 * the bridge as `text` on every check. Only [save] writes to the project, and the
 * bridge refuses it if the file changed on disk since it was loaded ([diskText]).
 *
 * Unsaved work is never lost by navigating: edited file texts and typed-but-not-
 * checked prompts are kept in [drafts], stored in the app's own files (not the
 * project), and come back when the file or declaration is opened again.
 */
class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val ui = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val draftsFile = File(app.filesDir, "drafts.json")

    var pairing by mutableStateOf(loadPairing())
        private set
    var pendingPairing by mutableStateOf<Pairing?>(null)
        private set
    var bridgeUp by mutableStateOf<Boolean?>(null)
        private set
    var files by mutableStateOf<List<String>>(emptyList())
        private set
    var screen by mutableStateOf<Screen>(Screen.Libraries)
        private set
    var result by mutableStateOf<CheckResult?>(null)
        private set
    /** The file as on disk, from the last check without `text`. */
    var diskText by mutableStateOf<String?>(null)
        private set
    var selectedLine by mutableStateOf<Int?>(null)
        private set
    var editMode by mutableStateOf<EditMode?>(null)
        private set
    /** The text being typed, with its cursor: a line's content (without indent) or the whole block. */
    var draft by mutableStateOf(TextFieldValue(""))
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var drafts by mutableStateOf(loadDrafts())
        private set
    /** Unsaved edits of the open file, made before the file changed on disk. */
    var stale by mutableStateOf<WorkingCopy?>(null)
        private set
    /** Texts before each in-app edit, newest last, per file. */
    private val undos = mutableMapOf<String, Undo>()
    var undoDepth by mutableStateOf(0)
        private set
    /** Tap-to-result times of the checks this session, in seconds (per-tap latency, Phase 4). */
    val roundTrips = mutableListOf<Double>()
    var lastTiming by mutableStateOf<String?>(null)
        private set
    var view by mutableStateOf(ProofView.values().firstOrNull { it.name == ui.getString("view", null) } ?: ProofView.STEPS)
        private set
    /** Lean's completions for the name being typed: the fragment they replace, and the names. */
    /** The landing page's tree: per file its title, counts and role, from the bridge's /v1/overview. */
    var overview by mutableStateOf<List<leanwb.FileInfo>>(emptyList())

    /** Folders folded in the tree; remembered. */
    var collapsed by mutableStateOf(ui.getStringSet("collapsed", emptySet())!!.toSet())

    /** The project's own `\`-abbreviations (.leanwb/abbreviations.json through the bridge). */
    var projectAbbrevs by mutableStateOf<Map<String, String>>(emptyMap())

    /** `\`-abbreviations matching the `\name` being typed: shown in place of the other chips. */
    var abbrevMatches by mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** The notation form, when the new declaration is a notation. */
    var notation by mutableStateOf(leanwb.NotationSpec())
    var notationAbbrev by mutableStateOf("")

    /** The first namespace of the last file shown: the default for a new sibling file. */
    var lastNamespace by mutableStateOf("")
    var completions by mutableStateOf<Pair<String, List<Completion>>>("" to emptyList())
        private set
    private var completionJob: Job? = null

    val workingText: String? get() = result?.text
    val edited: Boolean get() = result != null && diskText != null && result!!.text != diskText

    private fun loadPairing(): Pairing? {
        val port = prefs.getInt("port", 0)
        val token = prefs.getString("token", null)
        return if (port > 0 && token != null) Pairing(port, token) else null
    }

    // ------------------------------------------------------------ drafts store

    private fun loadDrafts(): Drafts {
        if (!draftsFile.exists()) return Drafts()
        return try {
            Drafts.decode(draftsFile.readText())
        } catch (e: Exception) {
            // Never overwrite what could not be read: set it aside for inspection.
            val aside = File(draftsFile.parentFile, "drafts.unreadable-${System.currentTimeMillis()}.json")
            draftsFile.renameTo(aside)
            error = "Unsaved drafts could not be read (${e.message}); kept as ${aside.name}."
            Drafts()
        }
    }

    private fun persist(d: Drafts) {
        if (d == drafts) return
        drafts = d
        try {
            val tmp = File(draftsFile.parentFile, "drafts.json.tmp")
            tmp.writeText(d.encode())
            if (!tmp.renameTo(draftsFile)) throw IllegalStateException("rename failed")
        } catch (e: Exception) {
            error = "Could not keep unsaved drafts: ${e.message}"
        }
    }

    /** Every new result goes through here, so the working copy is always on record. */
    private fun show(r: CheckResult) {
        result = r
        diskText?.let { persist(drafts.withText(ref(r.file), it, r.text)) }
        leanwb.NewFile.namespaceOf(r.text).takeIf { it.isNotEmpty() }?.let { lastNamespace = it }
    }

    /** The package drafts are kept under: the one being acted on ("" until packages are known). */
    private fun pkgName(): String = currentPackage()?.name ?: pkg ?: ""

    private fun ref(file: String) = FileRef(pkgName(), file)

    /** Where [mode] puts its prompt in [d]: its kind and line. */
    private fun target(mode: EditMode?, d: Declaration): Pair<Char, Int>? = when (mode) {
        is EditMode.Line -> 'L' to mode.line
        is EditMode.Insert -> 'I' to mode.after
        EditMode.Block -> 'B' to d.line
        null -> null
    }

    /** Keep [text] as [mode]'s prompt (null drops it), placed in [on]'s text and declaration [d]. */
    private fun keepPrompt(mode: EditMode?, text: String?, on: CheckResult? = result, d: Declaration? = declaration()) {
        val r = on ?: return
        val decl = d ?: return
        val (kind, line) = target(mode, decl) ?: return
        persist(drafts.withPrompt(ref(r.file), r.text, decl, kind, line, text))
    }

    /** Keep what is being typed, anchored where it goes. */
    private fun stashPrompt() = keepPrompt(editMode, draft.text)

    private fun updateDraft(v: TextFieldValue) {
        draft = v
        stashPrompt()
    }

    // ------------------------------------------------------------ bridge

    fun offerPairing(p: Pairing) {
        pendingPairing = p
    }

    fun confirmPairing(accept: Boolean) {
        val p = pendingPairing ?: return
        pendingPairing = null
        if (!accept) return
        prefs.edit().putInt("port", p.port).putString("token", p.token).apply()
        pairing = p
        refresh()
    }

    private fun client(): BridgeClient? = pairing?.let { BridgeClient(it, pkg) }

    /** A check, timed from the tap to the decoded result. */
    private suspend fun timedCheck(c: BridgeClient, file: String, text: String?, at: List<Int>, after: List<Int>): CheckResult {
        val t0 = System.nanoTime()
        val r = withContext(Dispatchers.IO) { c.check(file, text, at, after) }
        val trip = (System.nanoTime() - t0) / 1e9
        roundTrips += trip
        val sorted = roundTrips.sorted()
        lastTiming = "Lean %.1f s · tap→result %.1f s · median %.1f s over %d".format(
            r.seconds, trip, sorted[sorted.size / 2], sorted.size)
        return r
    }

    private fun undo(): Undo? = result?.file?.let { undos.getOrPut(it) { Undo() } }

    private fun rememberForUndo(text: String) {
        val u = undo() ?: return
        u.push(text); undoDepth = u.size
    }

    private fun run(label: String, block: suspend (BridgeClient) -> Unit) {
        val c = client() ?: return
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                block(c)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    fun refresh() = run("Contacting the bridge…") { c ->
        var up = withContext(Dispatchers.IO) { c.health() }
        if (BridgeStart.autoStart(up, TermuxRunner.permitted(getApplication()), autoStartTried)) {
            autoStartTried = true
            up = launchAndWait(c)
        }
        bridgeUp = up
        if (up) {
            loadPackages(c)
            loadOverview(c)
        }
    }

    // ------------------------------------------------------------ packages and libraries

    var packages by mutableStateOf<leanwb.Packages?>(null)
        private set
    /** The package the file screens and the search act on (null: the bridge's default). */
    var pkg by mutableStateOf(ui.getString("pkg", null))
        private set
    /** The landing tab: a package's name, [REQUIRES] or [NEW_PACKAGE]. */
    var landingTab by mutableStateOf(ui.getString("tab", null))
        private set
    /** The library whose tree the files screen shows. */
    var library by mutableStateOf<String?>(null)
        private set
    private val resumes = app.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun currentPackage(): leanwb.Package? = packages?.named(pkg)

    private suspend fun loadPackages(c: BridgeClient) {
        val ps = withContext(Dispatchers.IO) { c.packages() }
        packages = ps
        persist(drafts.adopt(ps.default))
        if (ps.named(pkg) == null) pkg = null
        val tab = landingTab
        if (tab == null || (tab != REQUIRES && tab != NEW_PACKAGE && ps.packages.none { it.name == tab })) landingTab = ps.default
    }

    fun chooseTab(tab: String) {
        landingTab = tab
        ui.edit().putString("tab", tab).apply()
        // The requires tab shows the dependencies of the package chosen last.
        if (tab != REQUIRES && tab != NEW_PACKAGE && tab != pkg) switchPackage(tab)
    }

    /** Act on another package; [load] fetches its overview (callers that load it themselves pass false). */
    private fun switchPackage(name: String?, load: Boolean = true) {
        if (name == pkg) return
        pkg = name
        ui.edit().putString("pkg", name).apply()
        overview = emptyList(); files = emptyList(); toolbox = emptyList(); projectAbbrevs = emptyMap()
        searchResult = null
        if (load) client()?.let { c -> viewModelScope.launch { try { loadOverview(c) } catch (e: Exception) { error = e.message } } }
    }

    fun resumePlace(p: leanwb.Package, l: leanwb.Library): leanwb.Place? =
        leanwb.Resume.decode(resumes.getString(leanwb.Resume.key(p.name, l.name), null))

    /** Files of [l] with unsaved working copies. */
    fun draftsIn(p: leanwb.Package, l: leanwb.Library): Int = drafts.copies.keys.count { it.pkg == p.name && l.contains(it.file) }

    /** Whether [file] of the current package has an unsaved working copy. */
    fun hasDraft(file: String): Boolean = ref(file) in drafts.copies

    fun openLibrary(p: leanwb.Package, l: leanwb.Library) {
        switchPackage(p.name, load = false)
        library = l.name
        screen = Screen.Files
        run("Loading ${l.name}…") { c -> loadOverview(c) }
    }

    /** Open the last declaration opened in [l]. */
    fun resumeLibrary(p: leanwb.Package, l: leanwb.Library) {
        val place = resumePlace(p, l) ?: return openLibrary(p, l)
        switchPackage(p.name, load = false)
        library = l.name
        screen = Screen.Files
        run("Resuming ${place.file.substringAfterLast('/')}… (a cold start can take minutes)") { c ->
            loadOverview(c)
            load(c, place.file)
            val d = place.decl?.let { n -> result?.declarations?.firstOrNull { it.name == n } }
            if (d != null) enterDecl(c, d)
        }
    }

    private fun rememberPlace(file: String, decl: String?) {
        val lib = currentPackage()?.libraryOf(file) ?: return
        resumes.edit().putString(leanwb.Resume.key(currentPackage()!!.name, lib.name), leanwb.Resume.encode(leanwb.Place(file, decl))).apply()
    }

    fun addLibrary(name: String) = run("Adding library $name…") { c ->
        withContext(Dispatchers.IO) { c.addLibrary(name) }
        loadPackages(c)
        loadOverview(c)
    }

    // ------------------------------------------------------------ starting the bridge

    /** Why the last start through Termux failed; shown on the files screen. */
    var startError by mutableStateOf<String?>(null)
    private var autoStartTried = false

    /**
     * Start the bridge through Termux. Unpaired, the script also sends the pairing
     * link, which comes back through MainActivity as the usual confirmation.
     * The caller has checked the RUN_COMMAND permission.
     */
    fun startBridge(pair: Boolean) {
        if (pair) {
            startError = TermuxRunner.start(getApplication(), true)
            return
        }
        run("Starting the bridge in Termux…") { c ->
            val up = launchAndWait(c)
            bridgeUp = up
            if (up) loadOverview(c)
        }
    }

    private suspend fun launchAndWait(c: BridgeClient): Boolean {
        busy = "Starting the bridge in Termux…"
        startError = TermuxRunner.start(getApplication(), false)
        if (startError != null) return false
        for (gap in BridgeStart.POLL_GAPS_MS) {
            delay(gap)
            if (withContext(Dispatchers.IO) { c.health() }) return true
        }
        startError = "Termux took the start request, but the bridge did not answer within 20 s. Its log is \$TMPDIR/bridge.log in Termux."
        return false
    }

    private suspend fun loadOverview(c: BridgeClient) {
        overview = withContext(Dispatchers.IO) { c.overview() }
        files = overview.map { it.path }
        projectAbbrevs = withContext(Dispatchers.IO) { c.abbreviations() }
        toolbox = withContext(Dispatchers.IO) { c.toolbox() }
    }

    // ------------------------------------------------------------ API search

    var searchOpen by mutableStateOf(false)
        private set
    var searchEngine by mutableStateOf(leanwb.Engine.LOOGLE)
    var searchQuery by mutableStateOf(TextFieldValue(""))
        private set
    var searchResult by mutableStateOf<leanwb.SearchResult?>(null)
        private set
    /** How a hit goes into the tactic field. */
    var hitForm by mutableStateOf(leanwb.HitForm.EXACT)
    /** The last hit tried on the goal and what it did. */
    var tried by mutableStateOf<Pair<String, leanwb.TryOutcome>?>(null)
        private set
    var toolbox by mutableStateOf<List<leanwb.Pinned>>(emptyList())
        private set
    var sourceView by mutableStateOf<leanwb.Source?>(null)
        private set

    /** In a proof with a place for a new tactic: hits can be tried and inserted there. */
    val searchInProof: Boolean
        get() = screen is Screen.Decl && result != null && (editMode is EditMode.Line || editMode is EditMode.Insert)

    fun openSearch() {
        searchOpen = true
        tried = null
    }

    fun closeSearch() {
        searchOpen = false
    }

    fun closeSource() {
        sourceView = null
    }

    fun typeQuery(v: TextFieldValue) {
        searchQuery = expandTyped(searchQuery.text, v)
    }

    fun searchSuggestion(s: String) {
        searchQuery = TextFieldValue(s.trim('"'), TextRange(s.trim('"').length))
        runSearch()
    }

    /** Ask the bridge; in a proof, hits are checked against the file being edited, unsaved text included. */
    fun runSearch() {
        val q = searchQuery.text.trim()
        if (q.isEmpty()) return
        val r = result.takeIf { searchInProof }
        val engine = searchEngine
        run("Searching ${engine.label}… (the first search in a file waits for Lean)") { c ->
            searchResult = withContext(Dispatchers.IO) { c.search(engine, q, r?.file, r?.text) }
            tried = null
        }
    }

    /** The file text with [tactic] where the composer would put it, and that line. */
    private fun composed(r: CheckResult, mode: EditMode, tactic: String): Pair<String, Int>? = when (mode) {
        is EditMode.Line -> Edit.setLine(r.text, mode.line, tactic) to mode.line
        is EditMode.Insert -> Edit.insertAfter(r.text, mode.after, tactic) to mode.after + 1
        EditMode.Block -> null
    }

    /** Check what [insertHit] would produce, on the goal, without keeping it. */
    fun tryHit(hit: leanwb.SearchHit) {
        val r = result ?: return
        val mode = editMode ?: return
        if (!hit.state.usable) return
        val t = leanwb.HitUse.apply(draft.text, draft.selection.min, draft.selection.max, hitForm, hit.name)
        val (newText, line) = composed(r, mode, t.text) ?: return
        run("Trying ${hit.name}…") { c ->
            val ls = probes(newText, line)
            tried = hit.name to leanwb.TryResult.of(timedCheck(c, r.file, newText, ls, ls), line)
        }
    }

    /** Put the hit into the tactic field (in the chosen form) and go back to the proof. */
    fun insertHit(hit: leanwb.SearchHit) {
        if (!hit.state.usable || editMode == null) return
        val t = leanwb.HitUse.apply(draft.text, draft.selection.min, draft.selection.max, hitForm, hit.name)
        updateDraft(TextFieldValue(t.text, TextRange(t.cursor)))
        completions = "" to emptyList()
        searchOpen = false
    }

    fun usePinned(p: leanwb.Pinned) {
        val t = leanwb.HitUse.chip(draft.text, draft.selection.min, draft.selection.max, p.name)
        updateDraft(TextFieldValue(t.text, TextRange(t.cursor)))
        completions = "" to emptyList()
    }

    fun pinned(name: String) = toolbox.any { it.name == name }

    fun togglePin(hit: leanwb.SearchHit) = run(if (pinned(hit.name)) "Unpinning…" else "Pinning…") { c ->
        val p = leanwb.Pinned(hit.name, hit.module, hit.type)
        toolbox = withContext(Dispatchers.IO) { c.pin(p, !pinned(hit.name)) }
    }

    fun unpin(p: leanwb.Pinned) = run("Unpinning…") { c ->
        toolbox = withContext(Dispatchers.IO) { c.pin(p, false) }
    }

    fun openSource(module: String, name: String) = run("Opening $module…") { c ->
        sourceView = withContext(Dispatchers.IO) { c.source(module, name) }
    }

    /**
     * Add `import [hit.module]` to the file being edited, so the hit can be used. One line
     * goes in above every declaration: the selection and the composer move down by one.
     * Unsaved like any edit; Lean reloads the file's imports, which can take a while.
     */
    fun addImport(hit: leanwb.SearchHit) {
        val r = result ?: return
        val newText = leanwb.Imports.add(r.text, hit.module)
        if (newText == r.text) return
        val mode = editMode
        val typed = draft
        val oldDecl = declaration()
        run("Adding import ${hit.module}… (Lean loads it)") { c ->
            val first = timedCheck(c, r.file, newText, emptyList(), emptyList())
            rememberForUndo(r.text)
            show(first)
            keepPrompt(mode, null, r, oldDecl)
            (screen as? Screen.Decl)?.let { screen = it.copy(line = it.line + 1) }
            val sel = selectedLine?.plus(1)
            selectedLine = sel
            editMode = when (mode) {
                is EditMode.Line -> EditMode.Line(mode.line + 1)
                is EditMode.Insert -> EditMode.Insert(mode.after + 1)
                else -> mode
            }
            updateDraft(typed)
            val ls = probes(newText, sel)
            show(timedCheck(c, r.file, newText, ls, ls))
            searchResult = searchResult?.let { sr ->
                sr.copy(hits = sr.hits.map { if (it.module == hit.module && it.state == leanwb.HitState.IMPORT) it.copy(state = leanwb.HitState.OK) else it })
            }
        }
    }

    fun toggleFolder(path: String) {
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
        ui.edit().putStringSet("collapsed", collapsed).apply()
    }

    // ------------------------------------------------------------ navigation

    /** Open a file: its unsaved working copy if there is one, else the disk text. */
    fun openFile(file: String) = run("Checking $file… (a cold start can take minutes)") { c -> load(c, file) }

    private suspend fun load(c: BridgeClient, file: String) {
        val disk = timedCheck(c, file, null, emptyList(), emptyList())
        diskText = disk.text
        stale = null
        when (val o = drafts.open(ref(file), disk.text)) {
            Drafts.Opened.Clean -> show(disk)
            is Drafts.Opened.Resumed -> show(timedCheck(c, file, o.text, emptyList(), emptyList()))
            is Drafts.Opened.Stale -> { result = disk; stale = o.copy }
        }
        undoDepth = undo()?.size ?: 0
        selectedLine = null
        editMode = null
        screen = Screen.File(file)
    }

    /** The file changed on disk under unsaved edits: take the edits onto the new disk text. */
    fun keepStale() {
        val r = result ?: return
        val s = stale ?: return
        run("Restoring unsaved edits…") { c ->
            stale = null
            rememberForUndo(r.text)
            show(timedCheck(c, r.file, s.text, emptyList(), emptyList()))
        }
    }

    fun discardStale() {
        val r = result ?: return
        stale = null
        persist(drafts.withText(ref(r.file), r.text, r.text))
    }

    fun openDecl(d: Declaration) = run("Opening ${d.label}…") { c -> enterDecl(c, d) }

    /** Show [d], its first `sorry` (else its last step) selected, with goals for its steps. */
    private suspend fun enterDecl(c: BridgeClient, d: Declaration) {
        val r = result ?: return
        leavePrompt()
        screen = Screen.Decl(r.file, d.line, d.name)
        rememberPlace(r.file, d.name)
        val steps = ProofSteps.of(r.text, d.line)
        val first = steps?.firstOrNull { it.text == "sorry" }?.line ?: steps?.lastOrNull()?.line
            ?: ProofSteps.byLine(r.text, d.line) ?: minOf(d.line + 1, d.endLine)
        selectedLine = first
        val ls = probes(r.text, first)
        show(timedCheck(c, r.file, r.text, ls, ls))
        resetComposer()
    }

    // ------------------------------------------------------------ creating

    /** Start typing a new declaration of [kind], placed after [after] (null: at the end, inside the namespaces). */
    fun startNewDecl(after: Declaration?, kind: leanwb.DeclKind = leanwb.DeclKind.THEOREM) {
        val r = result ?: return
        leavePrompt()
        notation = leanwb.NotationSpec()
        notationAbbrev = ""
        screen = Screen.NewDecl(r.file, after, kind)
        val t = leanwb.NewDecl.skeleton(kind)
        draft = TextFieldValue(t.text, TextRange(t.cursor))
    }

    /** Another kind: a fresh skeleton, unless something was typed into the current one. */
    fun newDeclKind(kind: leanwb.DeclKind) {
        val s = screen as? Screen.NewDecl ?: return
        val typed = draft.text != leanwb.NewDecl.skeleton(s.kind).text
        screen = s.copy(kind = kind)
        if (!typed) leanwb.NewDecl.skeleton(kind).let { draft = TextFieldValue(it.text, TextRange(it.cursor)) }
    }

    fun newDeclAfter(after: Declaration?) {
        val s = screen as? Screen.NewDecl ?: return
        screen = s.copy(after = after)
    }

    /** Put the typed declaration in the working text, check it, and open it (unsaved until Save). */
    fun submitNewDecl() {
        val r = result ?: return
        val s = screen as? Screen.NewDecl ?: return
        val isNotation = s.kind == leanwb.DeclKind.NOTATION
        val block = if (isNotation) notation.text() else draft.text
        if (block.isBlank() || (isNotation && notation.problem() != null)) return
        val abbrev = notationAbbrev.trim().takeIf { isNotation && it.isNotEmpty() }
        val (newText, line) = leanwb.NewDecl.insert(r.text, s.after, block)
        run("Checking the new declaration…") { c ->
            val checked = timedCheck(c, r.file, newText, emptyList(), emptyList())
            rememberForUndo(r.text)
            show(checked)
            // The project abbreviation is written at once (its own file), the notation with the next Save.
            if (abbrev != null) projectAbbrevs = withContext(Dispatchers.IO) { c.setAbbreviation(abbrev, notation.symbol.trim()) }
            val d = checked.declarations.firstOrNull { it.line == line }
            if (d != null) enterDecl(c, d) else screen = Screen.File(r.file)
        }
    }

    /**
     * Create [file] on disk (the bridge refuses an existing one and adds its import to
     * the library root), then open it.
     */
    fun createFile(file: String, text: String) = run("Creating $file…") { c ->
        withContext(Dispatchers.IO) { c.create(file, text) }
        loadOverview(c)
        load(c, file)
    }

    fun selectLine(line: Int, then: () -> Unit = {}) {
        val r = result ?: return
        selectedLine = line
        run("Goals at line $line…") { c ->
            val ls = probes(r.text, line)
            show(timedCheck(c, r.file, r.text, ls, ls))
            then()
        }
    }

    /** The tactic lines of the open declaration, or null in term mode. */
    fun steps(): List<StepLine>? {
        val r = result ?: return null
        val d = declaration() ?: return null
        return ProofSteps.of(r.text, d.line)
    }

    /**
     * Lines to ask goals for with [text]: every step of the open declaration (so each
     * card has its before/after), windowed around [line] when there are many, plus [line].
     */
    private fun probes(text: String, line: Int?): List<Int> {
        val d = declaration()
        val all = d?.let { ProofSteps.of(text, it.line) }?.map { it.line } ?: emptyList()
        val window = if (all.size <= 24 || line == null) all.take(24) else {
            val i = all.indexOfFirst { it >= line }.let { if (it < 0) all.size - 1 else it }
            val from = (i - 12).coerceIn(0, all.size - 24)
            all.subList(from, from + 24)
        }
        return (window + listOfNotNull(line)).distinct().sorted()
    }

    fun chooseView(v: ProofView) {
        if (v == view) return
        view = v
        ui.edit().putString("view", v.name).apply()
        if (v == ProofView.SOURCE) leavePrompt() else resetComposer()
    }

    /**
     * In the step and goal views the composer is always open: it adds a step after the
     * selected one, or replaces the selected step when that is a `sorry` or has an error.
     */
    private fun resetComposer() {
        if (view == ProofView.SOURCE || screen !is Screen.Decl) return
        val r = result ?: return
        val steps = steps() ?: return
        if (steps.isEmpty()) {
            // An empty `by` block: the first step goes after the `by` line.
            val by = declaration()?.let { ProofSteps.byLine(r.text, it.line) } ?: return
            selectedLine = by
            startEdit(EditMode.Insert(by), "", cursorAtEnd = true)
            completions = "" to emptyList()
            return
        }
        val sel = selectedLine?.takeIf { l -> steps.any { it.line == l } } ?: steps.last().line
        selectedLine = sel
        val step = steps.first { it.line == sel }
        val broken = r.diagnosticsFor(sel).any { it.severity == "error" && !it.unsolvedGoals }
        when {
            step.text == "sorry" -> startEdit(EditMode.Line(sel), "", cursorAtEnd = true)
            broken -> startEdit(EditMode.Line(sel), step.text, cursorAtEnd = true)
            else -> startEdit(EditMode.Insert(sel), "", cursorAtEnd = true)
        }
        completions = "" to emptyList()
    }

    /** A step card or chip was tapped: its state becomes the one the composer acts on. */
    fun selectStep(line: Int) {
        stashPrompt()
        selectedLine = line
        resetComposer()
    }

    /** Put a step's tactic in the composer, to replace it. */
    fun editStep(line: Int) {
        val t = steps()?.firstOrNull { it.line == line } ?: return
        stashPrompt()
        selectedLine = line
        startEdit(EditMode.Line(line), t.text, cursorAtEnd = true)
    }

    fun deleteStep(line: Int) {
        val r = result ?: return
        val prev = steps()?.lastOrNull { it.line < line }?.line
        apply(r, Edit.deleteLine(r.text, line), prev ?: line - 1, reset = true)
    }

    /** Back to adding a step after the selected one, dropping a replace in progress. */
    fun composeAfter() {
        val sel = selectedLine ?: return
        stashPrompt()
        startEdit(EditMode.Insert(sel), "", cursorAtEnd = true)
    }

    fun declaration(): Declaration? {
        val s = screen as? Screen.Decl ?: return null
        val ds = result?.declarations ?: return null
        return s.name?.let { n -> ds.firstOrNull { it.name == n } } ?: ds.firstOrNull { it.line == s.line }
    }

    /** Close the prompt, keeping what was typed for when it is opened again. */
    private fun leavePrompt() {
        stashPrompt()
        editMode = null
    }

    fun back(): Boolean {
        when (val s = screen) {
            is Screen.Decl -> when {
                view != ProofView.SOURCE -> { leavePrompt(); screen = Screen.File(s.file) }
                editMode != null -> leavePrompt()
                else -> screen = Screen.File(s.file)
            }
            is Screen.File -> {
                screen = Screen.Files; result = null; diskText = null; stale = null
                refresh()
            }
            is Screen.NewDecl -> screen = Screen.File(s.file)
            Screen.Files -> { screen = Screen.Libraries; library = null; refresh() }
            Screen.Libraries -> return false
        }
        return true
    }

    // ------------------------------------------------------------ editing

    private fun startEdit(mode: EditMode, fresh: String, cursorAtEnd: Boolean) {
        val r = result
        val d = declaration()
        val kept = if (r == null || d == null) null
            else target(mode, d)?.let { (kind, line) -> drafts.prompt(ref(r.file), r.text, d, kind, line)?.text }
        val text = kept ?: fresh
        editMode = mode
        draft = TextFieldValue(text, TextRange(if (cursorAtEnd) text.length else 0))
    }

    fun startLineEdit() {
        val r = result ?: return
        val line = selectedLine ?: return
        startEdit(EditMode.Line(line), Edit.lines(r.text, line, line).single().trim(), cursorAtEnd = true)
    }

    fun startInsert() {
        val line = selectedLine ?: return
        startEdit(EditMode.Insert(line), "", cursorAtEnd = true)
    }

    fun startBlockEdit() {
        val r = result ?: return
        val d = declaration() ?: return
        startEdit(EditMode.Block, Edit.lines(r.text, d.line, d.endLine).joinToString("\n"), cursorAtEnd = false)
    }

    /** Prompts typed in this declaration and not checked yet: (mode, first line of the draft). */
    fun keptPrompts(): List<Pair<EditMode, String>> {
        val r = result ?: return emptyList()
        val d = declaration() ?: return emptyList()
        return drafts.promptsIn(ref(r.file), r.text, d).map { (kind, line, p) ->
            val mode = when (kind) {
                'L' -> EditMode.Line(line)
                'I' -> EditMode.Insert(line)
                else -> EditMode.Block
            }
            mode to p.text.lineSequence().first()
        }
    }

    fun resumePrompt(mode: EditMode) {
        when (mode) {
            is EditMode.Line -> { selectLine(mode.line); startLineEdit() }
            is EditMode.Insert -> { selectLine(mode.after); startInsert() }
            EditMode.Block -> startBlockEdit()
        }
    }

    /** Cancel drops what was typed; Back keeps it. */
    fun cancelEdit() {
        keepPrompt(editMode, null)
        editMode = null
    }

    /** Apply the draft in memory and re-check; the edited line becomes the selection. */
    fun submitEdit() {
        val r = result ?: return
        val d = declaration() ?: return
        val mode = editMode ?: return
        val (newText, line) = when (mode) {
            is EditMode.Line -> Edit.setLine(r.text, mode.line, draft.text) to mode.line
            is EditMode.Insert -> ProofSteps.insert(r.text, d.line, mode.after, draft.text) to mode.after + 1
            EditMode.Block -> Edit.replace(r.text, d.line, d.endLine, draft.text) to
                Edit.shift(selectedLine ?: d.line, d.line, d.endLine, draft.text.split("\n").size)
        }
        apply(r, newText, line, reset = true) { keepPrompt(mode, null, r, d) }
    }

    private fun apply(r: CheckResult, newText: String, line: Int, reset: Boolean = false, onDone: () -> Unit = {}) {
        run("Checking the edit…") { c ->
            val ls = probes(newText, line)
            val checked = timedCheck(c, r.file, newText, ls, ls)
            if (newText != r.text) rememberForUndo(r.text)
            show(checked)
            selectedLine = line
            editMode = null
            onDone()
            if (reset) resetComposer()
        }
    }

    /** Keyboard input. A single typed character may complete a `\`-abbreviation. */
    fun type(v: TextFieldValue) {
        val grewByOne = v.text.length == draft.text.length + 1 && v.selection.collapsed
        val expanded = if (grewByOne) Abbrev.expand(Typed(v.text, v.selection.start), projectAbbrevs) else null
        val textChanged = v.text != draft.text
        updateDraft(if (expanded == null) v else TextFieldValue(expanded.text, TextRange(expanded.cursor)))
        updateAbbrevMatches()
        if (textChanged) requestCompletions()
    }

    /**
     * Ask Lean what the name being typed can be, 250 ms after the last keystroke. The
     * draft is placed in the file where it will go, so Lean sees the real context.
     */
    private fun requestCompletions() {
        completionJob?.cancel()
        val r = result ?: return
        val c = client() ?: return
        val mode = editMode
        val cursor = draft.selection.start
        // While a `\name` is being typed the chips are its matches, not Lean's names.
        if (!draft.selection.collapsed || !Complete.wanted(Typed(draft.text, cursor)) ||
            Abbrev.pending(Typed(draft.text, cursor)) != null) {
            completions = "" to emptyList(); return
        }
        val (n, anchor) = when (mode) {
            is EditMode.Line -> mode.line to mode.line
            is EditMode.Insert -> mode.after + 1 to mode.after
            else -> { completions = "" to emptyList(); return }
        }
        val typed = draft.text
        val declLine = declaration()?.line ?: return
        val full = if (mode is EditMode.Line) Edit.setLine(r.text, n, typed) else ProofSteps.insert(r.text, declLine, anchor, typed)
        val indent = Edit.indentOf(Edit.lines(full, n, n).single()).length
        val lead = typed.length - typed.trimStart().length
        val col = indent + (cursor - lead).coerceAtLeast(0)
        completionJob = viewModelScope.launch {
            delay(250)
            try {
                val got = withContext(Dispatchers.IO) { c.complete(r.file, full, n, col) }
                if (draft.text == typed) completions = got
            } catch (e: Exception) {
                completions = "" to emptyList()  // completion is a convenience: never an error banner
            }
        }
    }

    private fun updateAbbrevMatches() {
        val p = if (draft.selection.collapsed) Abbrev.pending(Typed(draft.text, draft.selection.start)) else null
        abbrevMatches = p?.let { Abbrev.search(it.second, projectAbbrevs) } ?: emptyList()
    }

    /** A tap on a `\` match: the pending `\name` becomes the symbol. */
    fun acceptAbbrev(symbol: String) {
        val t = Abbrev.accept(Typed(draft.text, draft.selection.start), symbol)
        updateDraft(TextFieldValue(t.text, TextRange(t.cursor)))
        abbrevMatches = emptyList()
    }

    /** Typing in a small field (the notation's symbol): `\`-abbreviations expand as in the main one. */
    fun expandTyped(old: String, v: TextFieldValue): TextFieldValue {
        if (v.text.length != old.length + 1 || !v.selection.collapsed) return v
        val e = Abbrev.expand(Typed(v.text, v.selection.start), projectAbbrevs) ?: return v
        return TextFieldValue(e.text, TextRange(e.cursor))
    }

    fun acceptCompletion(label: String) {
        val (frag, _) = completions
        val t = Complete.accept(Typed(draft.text, draft.selection.start), frag, label)
        updateDraft(TextFieldValue(t.text, TextRange(t.cursor)))
        completions = "" to emptyList()
    }

    /** A palette, hypothesis or symbol tap: insert at the cursor. */
    fun insert(snippet: String) {
        val t = Snippet.insert(draft.text, draft.selection.start, draft.selection.end, snippet)
        updateDraft(TextFieldValue(t.text, TextRange(t.cursor)))
    }

    /** Replace [line]'s tactic with a `Try this` suggestion and re-check. */
    fun useSuggestion(line: Int, tactic: String) {
        val r = result ?: return
        apply(r, Edit.setLine(r.text, line, tactic), line, reset = true)
    }

    /** Names to offer while typing: the hypotheses of the goal the new tactic acts on. */
    fun hypNames(): List<String> {
        val r = result ?: return emptyList()
        val goals = when (val m = editMode) {
            is EditMode.Line -> r.goalsAt(m.line)?.goals
            is EditMode.Insert -> r.goalsAfter(m.after)?.goals
            EditMode.Block, null -> selectedLine?.let { r.goalsAt(it)?.goals }
        }
        // Inaccessible names (x✝) cannot be typed.
        return goals?.firstOrNull()?.hyps?.flatMap { it.names }?.filterNot { '✝' in it } ?: emptyList()
    }

    /** Drop in-app edits: check the file as it is on disk. Undo brings them back. */
    fun revert() {
        val r = result ?: return
        run("Reverting to disk…") { c ->
            val lines = probes(diskText ?: r.text, selectedLine)
            val fresh = timedCheck(c, r.file, null, lines, lines)
            if (fresh.text != r.text) rememberForUndo(r.text)
            diskText = fresh.text
            show(fresh)
            editMode = null
            resetComposer()
        }
    }

    /** Write the working text to disk. It becomes the new base; undo history is kept. */
    fun save() {
        val r = result ?: return
        val base = diskText ?: return
        if (r.text == base) return
        run("Saving ${r.file}…") { c ->
            withContext(Dispatchers.IO) { c.save(r.file, r.text, base) }
            diskText = r.text
            persist(drafts.withText(ref(r.file), r.text, r.text))
        }
    }

    /** Step back to the text before the last edit (or revert). */
    fun undoEdit() {
        val r = result ?: return
        if (busy != null) return
        val u = undo() ?: return
        val prev = u.pop() ?: return
        undoDepth = u.size
        val sel = selectedLine?.takeIf { it <= prev.split("\n").size }
        run("Undoing…") { c ->
            try {
                val lines = probes(prev, sel)
                show(timedCheck(c, r.file, prev, lines, lines))
                selectedLine = sel
                editMode = null
                resetComposer()
            } catch (e: Exception) {
                rememberForUndo(prev)  // not applied: keep it undoable
                throw e
            }
        }
    }
}
