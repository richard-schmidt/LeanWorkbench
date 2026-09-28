package com.leanworkbench.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import leanwb.DeclStatus
import leanwb.Declaration
import leanwb.Diagnostic
import leanwb.Goal
import leanwb.GoalsAt
import leanwb.GoalDiff
import leanwb.HypRow
import leanwb.Mark
import leanwb.Statement
import leanwb.Step
import leanwb.firstError
import leanwb.summary
import leanwb.Palette
import leanwb.Suggestions
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 19.sp)

@Composable
private fun statusColor(s: DeclStatus) = when (s) {
    DeclStatus.OK -> TermOk
    DeclStatus.SORRY -> TermWarn
    DeclStatus.ERROR -> TermErr
}

/** A declaration's state as one character: proved, sorry, error. */
private fun statusGlyph(s: DeclStatus) = when (s) {
    DeclStatus.OK -> "✓"
    DeclStatus.SORRY -> "…"
    DeclStatus.ERROR -> "✕"
}

@Composable
fun WorkbenchApp(vm: WorkbenchViewModel) {
    TermTheme {
        Box(Modifier.fillMaxSize()) {
            WorkbenchMain(vm)
            if (vm.searchOpen) SearchSheet(vm)
            vm.sourceView?.let { SourceSheet(vm, it) }
        }
    }
}

@Composable
private fun WorkbenchMain(vm: WorkbenchViewModel) {
    run {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val inDecl = vm.screen is Screen.Decl
        // Entering a declaration never opens the drawer: it opens on ≡ or a swipe only.
        LaunchedEffect(inDecl) { if (drawer.currentValue != DrawerValue.Closed) drawer.snapTo(DrawerValue.Closed) }
        // The drawer lists the file's declarations, to move between them without going back.
        // Its sheet is always composed: an empty one has no width, so its closed and open
        // positions coincide, and it came up open the moment it gained content.
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = inDecl && (vm.view != ProofView.SOURCE || vm.editMode == null), drawerContent = {
            ModalDrawerSheet(Modifier.safeDrawingPadding(), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                if (inDecl) DeclDrawer(vm, onNew = { scope.launch { drawer.close() }; vm.startNewDecl(vm.declaration()) }) { d ->
                    scope.launch { drawer.close() }; vm.openDecl(d)
                }
            }
        }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                BackHandler(enabled = vm.screen != Screen.Libraries) {
                    if (drawer.isOpen) scope.launch { drawer.close() } else vm.back()
                }
                Header(vm, onMenu = if (inDecl) ({ scope.launch { drawer.open() } }) else null)
                vm.busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant)
                    Text("… " + it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.error?.let {
                    Text("✕ $it", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable { vm.error = null }
                        .padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.weight(1f)) {
                    when (vm.screen) {
                        Screen.Libraries -> LibrariesScreen(vm)
                        Screen.Files -> FilesScreen(vm)
                        is Screen.File -> FileScreen(vm)
                        is Screen.Decl -> DeclScreen(vm)
                        is Screen.NewDecl -> NewDeclScreen(vm)
                    }
                }
            }
            vm.pendingPairing?.let { p ->
                AlertDialog(
                    onDismissRequest = { vm.confirmPairing(false) },
                    title = { Text("pair with the Lean bridge?") },
                    text = { Text("Use the bridge on 127.0.0.1:${p.port}. Only accept if you just tapped [start + pair] or ran `start-bridge.sh --pair` in Termux.") },
                    confirmButton = { Cmd("pair", strong = true) { vm.confirmPairing(true) } },
                    dismissButton = { Cmd("cancel") { vm.confirmPairing(false) } },
                )
            }
        }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DeclDrawer(vm: WorkbenchViewModel, onNew: () -> Unit, onOpen: (Declaration) -> Unit) {
    val r = vm.result ?: return
    val current = vm.declaration()
    Text(r.file, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    Rule()
    LazyColumn(Modifier.weight(1f)) {
        items(r.declarations) { d ->
            val here = d == current
            val cs = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().background(if (here) cs.onSurface else Color.Transparent)
                .clickable { onOpen(d) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(statusGlyph(d.status), Modifier.width(22.dp), color = if (here) cs.surface else statusColor(d.status),
                    style = MaterialTheme.typography.bodyLarge)
                Text(d.label, style = MaterialTheme.typography.bodyLarge, color = if (here) cs.surface else cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Rule()
    Cmd("+ new after this", modifier = Modifier.padding(8.dp), onClick = onNew)
}

/** A hairline: the only separator the terminal look uses. */
@Composable
private fun Rule() = HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Header(vm: WorkbenchViewModel, onMenu: (() -> Unit)?) {
    // A path, as a prompt would show it: file › declaration.
    val (dim, title) = when (val s = vm.screen) {
        Screen.Libraries -> "~/" to (vm.packages?.base?.substringAfterLast('/') ?: "lean-workbench")
        Screen.Files -> "${vm.currentPackage()?.name ?: ""}/" to (vm.library ?: "")
        is Screen.File -> s.file.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } to s.file.substringAfterLast('/')
        is Screen.Decl -> s.file.substringAfterLast('/').removeSuffix(".lean") + " › " to (vm.declaration()?.name ?: "line ${s.line}")
        is Screen.NewDecl -> s.file.substringAfterLast('/').removeSuffix(".lean") + " › " to "new ${s.kind.label}"
    }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onMenu != null) Text("≡", Modifier.clickable(onClick = onMenu).padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleLarge)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Normal)) { append(dim) }
            append(title)
        }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1, overflow = TextOverflow.StartEllipsis)
        // Unsaved edits: the two ways out, in the one place visible from every screen.
        if (vm.edited) {
            Cmd("revert") { vm.revert() }
            Cmd("save", enabled = vm.busy == null, strong = true) { vm.save() }
        }
    }
    Rule()
}

@Composable
private fun FilesScreen(vm: WorkbenchViewModel) {
    if (vm.pairing == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("not paired with a bridge", style = MaterialTheme.typography.titleMedium)
            Text("Start the bridge in Termux and send this app the pairing link:")
            BridgeStarter(vm, pair = true)
        }
        return
    }
    var creating by remember { mutableStateOf(false) }
    var creatingIn by remember { mutableStateOf<String?>(null) }
    Column {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val (mark, msg) = when (vm.bridgeUp) {
                true -> "●" to "bridge up"
                false -> "✕" to "bridge not reachable"
                null -> "○" to "bridge not contacted yet"
            }
            Text("$mark $msg", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (vm.bridgeUp == false) TermErr else MaterialTheme.colorScheme.onSurfaceVariant)
            Cmd("refresh") { vm.refresh() }
            Cmd("+ file", enabled = vm.bridgeUp == true) { creatingIn = vm.library; creating = true }
            Cmd("search", enabled = vm.bridgeUp == true) { vm.openSearch() }
        }
        if (vm.bridgeUp == false) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { BridgeStarter(vm, pair = false) }
        Rule()
        // One library's files; its root file and folder, as the lakefile declares it.
        val lib = vm.currentPackage()?.libraries?.firstOrNull { it.name == vm.library }
        val rows = leanwb.FileTree.rows(vm.overview.filter { lib == null || lib.contains(it.path) }, vm.collapsed)
        LazyColumn {
            items(rows) { row ->
                when (row) {
                    is leanwb.TreeRow.Folder -> FolderRow(row, { vm.toggleFolder(row.path) }) { creatingIn = row.path; creating = true }
                    is leanwb.TreeRow.File -> FileRow(row, vm.hasDraft(row.info.path)) { vm.openFile(row.info.path) }
                }
            }
        }
    }
    if (creating) NewFileDialog(vm, creatingIn) { creating = false }
}

/**
 * Start the bridge through Termux's RUN_COMMAND (asking for that permission the
 * first time), or copy the command to paste into a Termux shell.
 */
@Composable
internal fun BridgeStarter(vm: WorkbenchViewModel, pair: Boolean) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startBridge(pair)
        else vm.startError = "Permission refused. Allow \"Run commands in Termux environment\" in this app's settings, or copy the command."
    }
    val command = leanwb.BridgeStart.shellCommand(pair)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cmd(if (pair) "start + pair" else "start bridge", enabled = vm.busy == null, strong = true) {
                if (TermuxRunner.permitted(ctx)) vm.startBridge(pair) else ask.launch(leanwb.BridgeStart.PERMISSION)
            }
            Cmd("copy command") {
                clipboard.setText(AnnotatedString(command))
                vm.startError = null
            }
        }
        Text("$ $command", style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.startError?.let { Text("✕ $it", style = MaterialTheme.typography.bodySmall, color = TermErr) }
    }
}

private const val INDENT = 18

/** A folder: ▾/▸ folds it (remembered); + makes a file in it. */
@Composable
private fun FolderRow(row: leanwb.TreeRow.Folder, onToggle: () -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = (16 + row.depth * INDENT).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text((if (row.collapsed) "▸ " else "▾ ") + row.name + "/", Modifier.weight(1f).padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text("${row.files}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Cmd("+", onClick = onNew)
    }
}

/**
 * A file: its state from the text alone (… with a count of `sorry`s, ○ when empty),
 * name, module-doc title, declaration count, and whether the build includes it.
 */
@Composable
private fun FileRow(row: leanwb.TreeRow.File, unsaved: Boolean, onOpen: () -> Unit) {
    val f = row.info
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = (16 + row.depth * INDENT).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top) {
        val (glyph, gc) = when {
            f.empty -> "○" to cs.outline
            f.sorries > 0 -> "…" to TermWarn
            else -> " " to cs.outline
        }
        Text(glyph, Modifier.width(18.dp), color = gc, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unsaved) Text("* ", style = MaterialTheme.typography.labelMedium, color = TermWarn)
                Text(when {
                    f.empty -> "empty"
                    f.sorries > 0 -> "${f.decls} decl · ${f.sorries} sorry"
                    else -> "${f.decls} decl"
                }, style = MaterialTheme.typography.labelSmall, color = cs.outline)
            }
            val notes = listOfNotNull(f.title, when (f.role) {
                "root" -> "library root"
                "unimported" -> "not in the build"
                else -> null
            }, if (unsaved) "unsaved edits" else null)
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = if (f.role == "unimported") TermWarn else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A new file: folder, name, imports and namespace. The bridge writes it and registers its import. */
@Composable
private fun NewFileDialog(vm: WorkbenchViewModel, initialFolder: String?, onClose: () -> Unit) {
    val folders = leanwb.NewFile.folders(vm.files)
    var folder by remember { mutableStateOf(initialFolder?.takeIf { it in folders } ?: folders.lastOrNull() ?: "") }
    var name by remember { mutableStateOf("") }
    var ns by remember { mutableStateOf(vm.lastNamespace) }
    var extra by remember { mutableStateOf("") }
    val modules = vm.files.mapNotNull { leanwb.NewFile.module(it) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val rel = "$folder/${name.trim().removeSuffix(".lean")}.lean"
    val problem = if (name.isBlank()) null else leanwb.NewFile.problem(rel, vm.files)
    AlertDialog(onDismissRequest = onClose,
        title = { Text("new file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("folder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (fo in folders) Token(fo, on = fo == folder) { folder = fo }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Mono,
                    label = { Text("name (a/b/Name for new folders)") }, keyboardOptions = CodeKeyboard,
                    isError = problem != null, supportingText = { Text(problem ?: rel, maxLines = 2) })
                Text("imports", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in modules) Token(m.substringAfterLast('.'), on = m in picked, style = MaterialTheme.typography.labelMedium) {
                        picked = if (m in picked) picked - m else picked + m
                    }
                }
                OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth(), textStyle = Mono, keyboardOptions = CodeKeyboard,
                    label = { Text("other imports (e.g. Mathlib.Order.Basic)") })
                OutlinedTextField(ns, { ns = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Mono,
                    keyboardOptions = CodeKeyboard, label = { Text("namespace (empty: none)") })
            }
        },
        confirmButton = {
            Cmd("create", enabled = name.isNotBlank() && problem == null, strong = true) {
                val imports = modules.filter { it in picked } + extra.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
                vm.createFile(rel, leanwb.NewFile.text(imports, ns)); onClose()
            }
        },
        dismissButton = { Cmd("cancel", onClick = onClose) })
}

@Composable
private fun FileScreen(vm: WorkbenchViewModel) {
    val r = vm.result ?: return
    // Unsaved edits made before the file changed on disk: decide before anything else,
    // since a new edit would replace them.
    vm.stale?.let {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("this file changed on disk after you edited it in the app", style = MaterialTheme.typography.titleMedium)
            Text("Your unsaved edits are kept. Use them to replace the disk version once you save, or discard them.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cmd("use my edits", strong = true) { vm.keepStale() }
                Cmd("discard them") { vm.discardStale() }
            }
        }
        return
    }
    Column {
        val errors = r.diagnostics.count { it.severity == "error" }
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.declarations.size} decls · $errors err · ${"%.1f".format(r.seconds)}s",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Cmd("+ declaration") { vm.startNewDecl(r.declarations.lastOrNull()) }
        }
        Rule()
        LazyColumn {
            items(r.declarations) { d -> DeclRow(d, Statement.of(r.text, d)) { vm.openDecl(d) }; Rule() }
        }
    }
}

@Composable
private fun DeclRow(d: Declaration, statement: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top) {
        Text(statusGlyph(d.status), Modifier.width(22.dp), color = statusColor(d.status), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(coloured(statement, leanwb.Lexer.spans(statement), codePalette), style = Mono.copy(fontSize = 12.sp, lineHeight = 16.sp), maxLines = 4,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A new declaration: pick a kind, type it from its skeleton (name, then statement),
 * choose where it goes. Check puts it in the working text and opens it; it is saved
 * with the file, like any edit.
 */
@Composable
private fun NewDeclScreen(vm: WorkbenchViewModel) {
    val s = vm.screen as? Screen.NewDecl ?: return
    val r = vm.result ?: return
    val focus = remember { FocusRequester() }
    var placing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in leanwb.DeclKind.values()) Token(k.label, on = k == s.kind) { vm.newDeclKind(k) }
        }
        Box {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append("goes ") }
                append(s.after?.let { "after ${it.label}" } ?: "at the end")
                append(" ▾")
            }, Modifier.clickable { placing = true }.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.DropdownMenu(placing, { placing = false }) {
                androidx.compose.material3.DropdownMenuItem({ Text("at the end") }, { vm.newDeclAfter(null); placing = false })
                for (d in r.declarations) androidx.compose.material3.DropdownMenuItem({ Text("after ${d.label}", maxLines = 1) },
                    { vm.newDeclAfter(d); placing = false })
            }
        }
        if (s.kind == leanwb.DeclKind.NOTATION) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { NotationForm(vm) }
        } else {
            OutlinedTextField(vm.draft, { vm.type(it) }, Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
                textStyle = Mono, keyboardOptions = CodeKeyboard, visualTransformation = CodeColouring(codePalette, lexOnly))
            InputBar(vm, InputTab.SYMBOLS)
        }
        val ready = if (s.kind == leanwb.DeclKind.NOTATION) vm.notation.problem() == null &&
            leanwb.NotationSpec.abbrevProblem(vm.notationAbbrev.trim()) == null else vm.draft.text.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cmd("check", enabled = vm.busy == null && ready, strong = true) { vm.submitNewDecl() }
            Cmd("cancel") { vm.back() }
        }
    }
    LaunchedEffect(s.kind) { if (s.kind != leanwb.DeclKind.NOTATION) focus.requestFocus() }
}

/**
 * A notation from a form: fixity, symbol (`\`-abbreviations expand), precedence by
 * likeness to a familiar operator, the function it stands for, scoped or not, and
 * an optional `\`-name for typing the symbol (kept in .leanwb/abbreviations.json).
 */
@Composable
private fun NotationForm(vm: WorkbenchViewModel) {
    val n = vm.notation
    val label = @Composable { t: String -> Text(t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline) }
    var symbol by remember { mutableStateOf(TextFieldValue(n.symbol)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label("fixity")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in leanwb.Fixity.values()) Token(f.keyword, on = f == n.fixity) { vm.notation = n.withFixity(f) }
        }
        OutlinedTextField(symbol, { v -> symbol = vm.expandTyped(symbol.text, v); vm.notation = vm.notation.copy(symbol = symbol.text) },
            Modifier.fillMaxWidth(), singleLine = true, textStyle = MonoBig, keyboardOptions = CodeKeyboard,
            label = { Text("symbol (\\ names work, e.g. \\star)") })
        label("precedence, like")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((like, p) in if (n.infix) leanwb.NotationSpec.infixPresets else leanwb.NotationSpec.prefixPresets)
                Token("$like $p", on = n.precedence == p, style = Mono) { vm.notation = n.copy(precedence = p) }
        }
        OutlinedTextField(n.target, { vm.notation = vm.notation.copy(target = it) }, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = Mono, keyboardOptions = CodeKeyboard, label = { Text("stands for (a function, e.g. Systems.combine)") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Token(if (n.scoped) "scoped: on" else "scoped: off", on = n.scoped) { vm.notation = n.copy(scoped = !n.scoped) }
            Text("  only where the namespace is open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        val ap = leanwb.NotationSpec.abbrevProblem(vm.notationAbbrev.trim())
        OutlinedTextField(vm.notationAbbrev, { vm.notationAbbrev = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Mono,
            keyboardOptions = CodeKeyboard, isError = ap != null, label = { Text("type it as \\… (optional)") },
            supportingText = { Text(ap ?: "saved for this project in .leanwb/abbreviations.json") })
        Rule()
        Text(n.text(), style = MonoBig)
        n.problem()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TermWarn) }
    }
}

@Composable
private fun SourceView(vm: WorkbenchViewModel) {
    val r = vm.result ?: return
    val d = vm.declaration() ?: return
    val sel = vm.selectedLine
    when (val mode = vm.editMode) {
        // Whole-declaration edit: the editor gets the height, the goal shrinks to one line.
        EditMode.Block -> Column(Modifier.fillMaxSize().padding(12.dp)) {
            sel?.let { ln -> r.goalsAt(ln)?.goals?.firstOrNull()?.let {
                Text("⊢ " + it.target, style = Mono, maxLines = 2, color = MaterialTheme.colorScheme.secondary)
            } }
            InputBar(vm)
            val pal = codePalette
            val blockSpans = remember(r, d) { { draft: String -> leanwb.Highlight.block(r, d.line, d.endLine, draft) } }
            OutlinedTextField(vm.draft, { vm.type(it) }, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp),
                textStyle = Mono, keyboardOptions = CodeKeyboard, visualTransformation = CodeColouring(pal, blockSpans))
            EditButtons(vm)
        }
        // No edit, or a one-line prompt: goals first. While typing, the source list
        // hides and the goals take all the height the keyboard leaves.
        else -> Column(Modifier.fillMaxSize()) {
            val goalMod = if (mode != null) Modifier.weight(1f) else Modifier.heightIn(max = 420.dp)
            Column(goalMod.fillMaxWidth()) {
                // The before-goals scroll; the after-row is pinned under them so it is
                // never below the fold (a long hypothesis list filled the panel).
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mode == null) r.firstError(d)?.let { fe ->
                        if (fe.line != sel) FirstErrorBanner(fe) { vm.selectLine(fe.line) }
                    }
                    when {
                        sel == null -> Text("Tap a line to see what its tactic does", style = MaterialTheme.typography.labelLarge)
                        // Editing a line: the state its new tactic will act on.
                        mode is EditMode.Line -> { Label("Goals before line $sel"); GoalPanel(r.goalsAt(sel)) }
                        // Adding a line after: the state it starts from.
                        mode is EditMode.Insert -> { Label("Goals after line $sel"); GoalPanel(r.goalsAfter(sel)) }
                        else -> {
                            StepPanel(sel, r.goalsAt(sel), r.goalsAfter(sel), r.diagnosticsFor(sel).any { it.severity == "error" })
                            SuggestionList(Suggestions.of(r.diagnosticsFor(sel))) { vm.useSuggestion(sel, it) }
                        }
                    }
                    if (mode != null && sel != null) for (dg in r.diagnosticsFor(sel)) DiagnosticBox(dg)
                }
                vm.lastTiming?.let {
                    Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            HorizontalDivider()
            if (mode != null) {
                PromptBar(vm, mode)
            } else {
                Row(Modifier.padding(horizontal = 4.dp).horizontalScroll(rememberScrollState())) {
                    Cmd("edit", enabled = sel != null) { vm.startLineEdit() }
                    Cmd("insert", enabled = sel != null) { vm.startInsert() }
                    Cmd("block") { vm.startBlockEdit() }
                    Cmd("undo", enabled = vm.undoDepth > 0) { vm.undoEdit() }
                }
                val kept = vm.keptPrompts()
                if (kept.isNotEmpty()) LazyRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(kept) { (m, text) ->
                        val where = when (m) {
                            is EditMode.Line -> "line ${m.line}"
                            is EditMode.Insert -> "after ${m.after}"
                            EditMode.Block -> "block"
                        }
                        Token("draft $where: $text", style = Mono.copy(fontSize = 12.sp),
                            modifier = Modifier.widthIn(max = 280.dp)) { vm.resumePrompt(m) }
                    }
                }
                val lines = r.text.split("\n")
                val lineSpans = remember(r) { leanwb.Highlight.byLine(r.text, leanwb.Highlight.of(r.text, r.tokens)) }
                val first = r.firstError(d)
                LazyColumn(Modifier.weight(1f)) {
                    items((d.line..d.endLine).toList()) { ln ->
                        // Errors after the first often follow from it: shown on one line.
                        SourceLine(ln, lines.getOrElse(ln - 1) { "" }, lineSpans.getOrElse(ln - 1) { emptyList() }, ln == sel, r.diagnosticsFor(ln),
                            compact = { dg -> dg.unsolvedGoals || (first != null && dg !== first && dg.severity == "error") }) { vm.selectLine(ln) }
                    }
                }
            }
        }
    }
}

/** Code is not prose: no autocorrect, no capitalisation. */
private val CodeKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

@Composable
private fun PromptBar(vm: WorkbenchViewModel, mode: EditMode) {
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(when (mode) {
            is EditMode.Line -> "Edit line ${mode.line}"
            is EditMode.Insert -> "New line after ${mode.after}"
            EditMode.Block -> ""
        }, style = MaterialTheme.typography.labelMedium)
        InputBar(vm)
        OutlinedTextField(vm.draft, { vm.type(it) }, Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = Mono, singleLine = true, visualTransformation = CodeColouring(codePalette, lexOnly),
            keyboardOptions = CodeKeyboard.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.submitEdit() }))
        EditButtons(vm)
    }
    LaunchedEffect(mode) { focus.requestFocus() }
}

private enum class InputTab(val label: String) { TACTICS("Tactics"), HYPS("Names"), SYMBOLS("Symbols") }

/** One row of chips above the field: tactics, the goal's hypothesis names, or symbols. */
@Composable
private fun InputBar(vm: WorkbenchViewModel, start: InputTab = InputTab.TACTICS) {
    var tab by remember { mutableStateOf(start) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (t in InputTab.values()) {
            Text(t.label.lowercase(), Modifier.clickable { tab = t }.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (t == tab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                textDecoration = if (t == tab) TextDecoration.Underline else null)
        }
    }
    if (vm.abbrevMatches.isNotEmpty()) {
        AbbrevRow(vm); return
    }
    val items = when (tab) {
        InputTab.TACTICS -> Palette.tactics
        InputTab.HYPS -> vm.hypNames()
        InputTab.SYMBOLS -> Palette.symbols
    }
    if (items.isEmpty()) {
        Text("no names in this goal", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    } else LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items) { item -> Token(item.replace(leanwb.Snippet.CARET.toString(), "…"), style = Mono) { vm.insert(item) } }
    }
}

/** The `\`-abbreviations matching the name being typed: symbol, then its name. */
@Composable
private fun AbbrevRow(vm: WorkbenchViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(vm.abbrevMatches) { (k, sym) ->
            Token("${sym.replace(leanwb.Abbrev.CURSOR, "")} $k", style = Mono) { vm.acceptAbbrev(sym) }
        }
    }
}

/** `Try this` results from exact?/apply?/simp?/rw?: tap one to put it on the line. */
@Composable
private fun SuggestionList(suggestions: List<leanwb.Suggestion>, onUse: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    var all by remember(suggestions) { mutableStateOf(false) }
    Label("Suggestions (tap to use)")
    for (sg in if (all) suggestions else suggestions.take(3)) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, Sq)
            .clickable { onUse(sg.tactic) }.padding(8.dp)) {
            Text(coloured(sg.tactic, leanwb.Lexer.spans(sg.tactic), codePalette), style = Mono, color = MaterialTheme.colorScheme.onSecondaryContainer)
            sg.note?.let { Text("⊢ $it", style = Mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (suggestions.size > 3) Cmd(if (all) "fewer" else "${suggestions.size - 3} more") { all = !all }
}

@Composable
private fun EditButtons(vm: WorkbenchViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Cmd("check", enabled = vm.busy == null, strong = true) { vm.submitEdit() }
        Cmd("cancel") { vm.cancelEdit() }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun FirstErrorBanner(dg: Diagnostic, onClick: () -> Unit) {
    Text("First error · line ${dg.line}: " + dg.headline.lineSequence().first() + "  →",
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, Sq)
            .clickable(onClick = onClick).padding(8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private val Gone: Color @Composable get() = TermErr
private val New: Color @Composable get() = TermOk
private val Changed: Color @Composable get() = TermWarn

/** What the selected line's tactic did: one goal, annotated, instead of before and after side by side. */
@Composable
private fun StepPanel(line: Int, before: GoalsAt?, after: GoalsAt?, lineHasError: Boolean, mono: TextStyle = Mono) {
    if (before == null || after == null) { Text("…", style = mono); return }
    if (lineHasError) {
        Text("Line $line has an error. Goals before it:", style = MaterialTheme.typography.labelLarge, color = Gone)
        GoalPanel(before, mono); return
    }
    when (val step = Step.of(before.goals, after.goals)) {
        Step.Unknown -> { Label("Line $line"); GoalPanel(if (before.goals == null) after else before, mono) }
        is Step.Closed -> {
            Text(if (step.remaining == 0) "Line $line closes the goal · no goals left ✓"
                 else "Line $line closes the goal · ${step.remaining} left", style = MaterialTheme.typography.labelLarge,
                color = New)
            GoalPanel(after, mono)
        }
        is Step.Changed -> {
            val extra = if (step.added.isNotEmpty()) " · +${step.added.size} new goal(s)" else ""
            Label("Line $line · ${step.remaining} goal${if (step.remaining == 1) "" else "s"}$extra")
            DiffCard(step.diff, mono)
            step.added.forEachIndexed { i, g -> GoalCard(g, i + 2, step.remaining, mono) }
        }
    }
}

@Composable
private fun DiffCard(d: GoalDiff, mono: TextStyle = Mono) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, Sq)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            d.case?.let { Text("case $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
            val locals = d.rows.filter { it.mark != Mark.GONE }.flatMap { it.names }.toSet()
            for (row in d.rows) HypLine(row, mono, locals)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            d.oldTarget?.let {
                Text("⊢ $it", style = mono.copy(textDecoration = TextDecoration.LineThrough), color = Gone)
            }
            // A changed target keeps its diff colour: what moved matters more than its syntax.
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("⊢ ") }
                if (d.targetChanged) append(d.target) else append(goalText(d.target, locals, codePalette))
            }, style = mono, color = if (d.targetChanged) New else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun HypLine(row: HypRow, mono: TextStyle = Mono, locals: Set<String> = emptySet()) {
    val pal = codePalette
    val accent = pal.local
    val (sign, color) = when (row.mark) {
        Mark.KEPT -> "  " to MaterialTheme.colorScheme.onSurface
        Mark.GONE -> "− " to Gone
        Mark.NEW -> "+ " to New
        Mark.CHANGED -> "~ " to Changed
    }
    val style = if (row.mark == Mark.GONE) mono.copy(textDecoration = TextDecoration.LineThrough) else mono
    Text(buildAnnotatedString {
        append(sign)
        withStyle(SpanStyle(color = if (row.mark == Mark.KEPT) accent else color, fontWeight = FontWeight.SemiBold)) {
            append(row.names.joinToString(" "))
        }
        append(" : ")
        // Only an unchanged hypothesis takes syntax colours; a new, changed or gone one keeps its diff colour.
        if (row.mark == Mark.KEPT) append(goalText(row.displayType, locals, pal)) else append(row.displayType)
    }, style = style, color = color)
    row.oldType?.let {
        Text("    was: " + it.trimIndent(), style = mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun DiagnosticBox(dg: Diagnostic) {
    var full by remember(dg) { mutableStateOf(false) }
    val error = dg.severity == "error"
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (full || !dg.hasMore) dg.message.trimEnd() else dg.headline + "\n… (tap for the full message)"
    Text(text, Modifier.fillMaxWidth().padding(top = 4.dp).background(bg, Sq)
        .clickable(enabled = dg.hasMore) { full = !full }.padding(8.dp),
        style = Mono.copy(fontSize = 12.sp, lineHeight = 16.sp), color = fg)
}

@Composable
private fun SourceLine(ln: Int, text: String, spans: List<leanwb.Span>, selected: Boolean, diags: List<Diagnostic>,
                       compact: (Diagnostic) -> Boolean = { false }, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text("$ln".padStart(3), style = Mono, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            Text(coloured(text, spans, codePalette), style = Mono)
        }
        // Outside the highlight, in its own box, so it reads on any selection colour.
        if (diags.isNotEmpty()) Column(Modifier.padding(start = 36.dp, end = 8.dp, bottom = 4.dp)) {
            for (dg in diags) if (compact(dg)) {
                Text("✕ " + dg.headline.lineSequence().first(), style = Mono.copy(fontSize = 12.sp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = statusColor(DeclStatus.ERROR))
            } else DiagnosticBox(dg)
        }
    }
}

@Composable
private fun GoalPanel(at: GoalsAt?, mono: TextStyle = Mono) {
    val goals = at?.goals
    when {
        at == null -> Text("…", style = mono)
        goals == null -> Text("No tactic state at this line.", style = MaterialTheme.typography.bodyMedium)
        goals.isEmpty() -> Text("No goals.", style = MaterialTheme.typography.bodyMedium, color = statusColor(DeclStatus.OK))
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            goals.forEachIndexed { i, g -> GoalCard(g, i + 1, goals.size, mono) }
        }
    }
}

@Composable
private fun GoalCard(g: Goal, index: Int, total: Int, mono: TextStyle = Mono) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, Sq)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val head = listOfNotNull(if (total > 1) "goal $index of $total" else null, g.case?.let { "case $it" })
            if (head.isNotEmpty()) Text(head.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary)
            val pal = codePalette
            val locals = leanwb.GoalColour.locals(g)
            for (h in g.hyps) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = pal.local, fontWeight = FontWeight.SemiBold)) { append(h.names.joinToString(" ")) }
                    append(" : ")
                    append(goalText(h.displayType, locals, pal))
                }, style = mono)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("⊢ ") }
                append(goalText(g.target, locals, pal))
            }, style = mono)
        }
    }
}

private val MonoBig = Mono.copy(fontSize = 16.sp, lineHeight = 22.sp)

/** A declaration: the view toggle, then the chosen view; the composer under the step and goal views. */
@Composable
private fun DeclScreen(vm: WorkbenchViewModel) {
    Column(Modifier.fillMaxSize()) {
        ViewToggle(vm)
        if (vm.view == ProofView.SOURCE) { SourceView(vm); return@Column }
        val steps = vm.steps()
        if (steps == null) {
            Text("This declaration has no `by` block. Use the Source view to edit it.", Modifier.padding(16.dp))
            return@Column
        }
        Box(Modifier.weight(1f)) {
            if (vm.view == ProofView.STEPS) StepsView(vm, steps) else GoalView(vm, steps)
        }
        vm.lastTiming?.let {
            Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        Composer(vm)
    }
}

@Composable
private fun ViewToggle(vm: WorkbenchViewModel) {
    // Tabs as in a terminal multiplexer: the current one in reverse video.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        for ((v, label) in listOf(ProofView.STEPS to "steps", ProofView.GOAL to "goal", ProofView.SOURCE to "source")) {
            val on = vm.view == v
            val cs = MaterialTheme.colorScheme
            Text(label, Modifier.background(if (on) cs.onSurface else Color.Transparent)
                .clickable { vm.chooseView(v) }.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = if (on) cs.surface else cs.outline)
        }
    }
    Rule()
}

private fun stepError(r: leanwb.CheckResult, line: Int): Diagnostic? =
    r.diagnosticsFor(line).firstOrNull { it.severity == "error" && !it.unsolvedGoals }

/** A: the proof as cards. The selected card shows the state after it; the composer adds after it. */
@Composable
private fun StepsView(vm: WorkbenchViewModel, steps: List<leanwb.StepLine>) {
    val r = vm.result ?: return
    val sel = vm.selectedLine
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(sel, steps.size) {
        val i = steps.indexOfFirst { it.line == sel }
        if (i >= 0) list.animateScrollToItem(i)
    }
    if (steps.isEmpty()) { FirstStep(r, sel); return }
    val base = steps.minOf { it.indent }
    val lines = remember(r) { r.text.split("\n") }
    val lineSpans = remember(r) { leanwb.Highlight.byLine(r.text, leanwb.Highlight.of(r.text, r.tokens)) }
    val pal = codePalette
    LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(steps) { st ->
            val selected = st.line == sel
            val err = stepError(r, st.line)
            val step = Step.of(r.goalsAt(st.line)?.goals, r.goalsAfter(st.line)?.goals)
            val border = when {
                err != null -> TermErr
                selected -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Column(Modifier.fillMaxWidth().padding(start = ((st.indent - base) * 6).dp)
                .border(1.dp, border, Sq).clickable { vm.selectStep(st.line) }.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("${steps.indexOf(st) + 1}".padStart(2), Modifier.width(28.dp), style = Mono,
                        color = MaterialTheme.colorScheme.outline)
                    Text(coloured(st.text, stepSpans(lines, lineSpans, st), pal), style = Mono)
                }
                val line2 = err?.headline?.lineSequence()?.first() ?: step.summary()
                if (line2.isNotEmpty()) Text(line2, Modifier.padding(start = 28.dp), style = Mono.copy(fontSize = 12.sp),
                    color = if (err != null) statusColor(DeclStatus.ERROR) else MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected) {
                    if (err != null) DiagnosticBox(err)
                    else Column { StepPanel(st.line, r.goalsAt(st.line), r.goalsAfter(st.line), false) }
                    SuggestionList(Suggestions.of(r.diagnosticsFor(st.line))) { vm.useSuggestion(st.line, it) }
                    Row {
                        Cmd("edit") { vm.editStep(st.line) }
                        Cmd("delete") { vm.deleteStep(st.line) }
                    }
                }
            }
        }
    }
}

/** An empty `by` block: the goal the first step starts from ([by] is the `by` line). */
@Composable
private fun FirstStep(r: leanwb.CheckResult, by: Int?, mono: TextStyle = Mono) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("No steps yet: type the first tactic below.")
        by?.let { r.goalsAfter(it) }?.let { GoalPanel(it, mono) }
    }
}

/** B: the goal after the selected step fills the screen; the steps are a strip of chips. */
@Composable
private fun GoalView(vm: WorkbenchViewModel, steps: List<leanwb.StepLine>) {
    val r = vm.result ?: return
    val sel = vm.selectedLine
    Column(Modifier.fillMaxSize()) {
        val strip = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(sel, steps.size) {
            val i = steps.indexOfFirst { it.line == sel }
            if (i >= 0) strip.animateScrollToItem(i)
        }
        LazyRow(state = strip, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(steps) { st ->
                val on = st.line == sel
                val err = stepError(r, st.line) != null
                Token("${steps.indexOf(st) + 1} " + st.text, on = on, style = Mono.copy(fontSize = 12.sp),
                    color = if (err) TermErr else Color.Unspecified, modifier = Modifier.widthIn(max = 220.dp)) { vm.selectStep(st.line) }
            }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sel == null || steps.isEmpty()) { FirstStep(r, sel, MonoBig); return@Column }
            val err = stepError(r, sel)
            if (err != null) DiagnosticBox(err)
            StepPanel(sel, r.goalsAt(sel), r.goalsAfter(sel), err != null, MonoBig)
            SuggestionList(Suggestions.of(r.diagnosticsFor(sel))) { vm.useSuggestion(sel, it) }
            Row {
                Cmd("edit step") { vm.editStep(sel) }
                Cmd("delete step") { vm.deleteStep(sel) }
            }
        }
    }
}

/**
 * Always-open input for the step and goal views: Lean's completions for the name being
 * typed (hypothesis names when there are none), the tactic field, and a symbol row
 * that can switch to tactic snippets.
 */
@Composable
private fun Composer(vm: WorkbenchViewModel) {
    val mode = vm.editMode ?: return
    val steps = vm.steps() ?: return
    fun num(line: Int) = steps.indexOfFirst { it.line == line } + 1
    var tactics by remember { mutableStateOf(false) }
    Rule()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (mode) {
                is EditMode.Line -> "replace step ${num(mode.line)}"
                is EditMode.Insert -> if (steps.isEmpty()) "first step" else "after step ${num(mode.after)}"
                EditMode.Block -> ""
            }, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline)
            if (mode is EditMode.Line) Cmd("add after instead") { vm.composeAfter() }
            Cmd("search", enabled = vm.busy == null) { vm.openSearch() }
        }
        // The package's pinned lemmas; a tap puts `exact name` (or the name) in the field.
        if (vm.toolbox.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            item { Text("toolbox", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            items(vm.toolbox) { p ->
                Token(p.name, style = Mono.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.primary) { vm.usePinned(p) }
            }
        }
        val (_, items) = vm.completions
        val chips: List<Pair<String, () -> Unit>> = if (vm.abbrevMatches.isNotEmpty())
            vm.abbrevMatches.map { (k, sym) -> "${sym.replace(leanwb.Abbrev.CURSOR, "")} $k" to { vm.acceptAbbrev(sym) } }
        else if (items.isNotEmpty())
            items.map { c -> c.label to { vm.acceptCompletion(c.label) } }
        else vm.hypNames().map { n -> n to { vm.insert(n) } }
        if (chips.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chips) { (label, act) ->
                Token(label, style = Mono.copy(fontSize = 13.sp), onClick = act)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.draft, { vm.type(it) }, Modifier.weight(1f), textStyle = Mono, singleLine = true,
                visualTransformation = CodeColouring(codePalette, lexOnly),
                placeholder = { Text("› tactic", style = Mono) },
                keyboardOptions = CodeKeyboard.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submitEdit() }))
            Spacer(Modifier.width(6.dp))
            Cmd("run", enabled = vm.busy == null && vm.draft.text.isNotBlank(), strong = true) { vm.submitEdit() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Cmd(if (tactics) "symbols" else "tactics") { tactics = !tactics }
            val row = if (tactics) Palette.tactics else Palette.symbols
            for (item in row) {
                Text(item.replace(leanwb.Snippet.CARET.toString(), "…"), Modifier.clickable { vm.insert(item) }
                    .padding(horizontal = if (tactics) 8.dp else 7.dp, vertical = 10.dp),
                    style = if (tactics) Mono.copy(fontSize = 13.sp) else MonoBig)
            }
        }
    }
}

/** Goal text coloured from the goal's own structure. */
private fun goalText(text: String, locals: Set<String>, pal: CodePalette) =
    coloured(text, leanwb.GoalColour.spans(text, locals), pal)

/** A step's colours: its slice of the line's spans; the scanner alone if the text is not in the line. */
private fun stepSpans(lines: List<String>, lineSpans: List<List<leanwb.Span>>, st: leanwb.StepLine): List<leanwb.Span> {
    val line = lines.getOrNull(st.line - 1) ?: return leanwb.Lexer.spans(st.text)
    val at = line.indexOf(st.text)
    if (at < 0) return leanwb.Lexer.spans(st.text)
    return leanwb.Highlight.slice(lineSpans.getOrElse(st.line - 1) { emptyList() }, at, at + st.text.length)
}
