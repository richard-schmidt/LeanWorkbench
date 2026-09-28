package com.leanworkbench.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import leanwb.Cell
import leanwb.Dependency
import leanwb.Library
import leanwb.PackageText

private val LMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
private val CARD_WIDTH = 300.dp
private val CARD_HEIGHT = 280.dp

/**
 * Package tabs (chips), then the package's header (path, Lean, git, CI)
 * and a pager of its libraries; a read-only "requires" tab of dependencies, whose cards
 * open the Mathlib search; and "+ package" (not built yet).
 */
@Composable
fun LibrariesScreen(vm: WorkbenchViewModel) {
    val cs = MaterialTheme.colorScheme
    if (vm.pairing == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("not paired with a bridge", style = MaterialTheme.typography.titleMedium)
            Text("Start the bridge in Termux and send this app the pairing link:")
            BridgeStarter(vm, pair = true)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val (mark, msg) = when (vm.bridgeUp) {
                true -> "●" to "bridge up"
                false -> "✕" to "bridge not reachable"
                null -> "○" to "bridge not contacted yet"
            }
            Text("$mark $msg", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (vm.bridgeUp == false) TermErr else cs.onSurfaceVariant)
            Cmd("refresh") { vm.refresh() }
        }
        if (vm.bridgeUp == false) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { BridgeStarter(vm, pair = false) }
        LRule()
        val ps = vm.packages ?: return
        val tab = vm.landingTab ?: ps.default
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in ps.packages) Token(p.name, on = tab == p.name) { vm.chooseTab(p.name) }
            Token("requires", on = tab == REQUIRES, color = TermWarn) { vm.chooseTab(REQUIRES) }
            Token("+ package", on = tab == NEW_PACKAGE, color = cs.primary) { vm.chooseTab(NEW_PACKAGE) }
        }
        when (tab) {
            REQUIRES -> RequiresTab(vm)
            NEW_PACKAGE -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("new package", style = MaterialTheme.typography.titleMedium)
                Text("Creating a Lake package from the app (lake new with Mathlib, then cache get) is not built yet. " +
                    "Until then, make it in Termux under ${ps.base}; it shows here as a tab.", style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant)
            }
            else -> PackageTab(vm, ps.packages.firstOrNull { it.name == tab } ?: return@Column)
        }
    }
}

@Composable
private fun LRule() = HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun PackageTab(vm: WorkbenchViewModel, p: leanwb.Package) {
    val cs = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(p.name, style = MaterialTheme.typography.titleSmall)
        Text("${vm.packages?.base?.substringAfterLast('/') ?: ""}/${p.name}" + (p.toolchain?.let { " · Lean $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row {
            Text(PackageText.git(p.git), style = MaterialTheme.typography.bodySmall,
                color = if ((p.git?.changed ?: 0) > 0) TermWarn else cs.onSurfaceVariant)
            PackageText.ci(p.ci, System.currentTimeMillis())?.let {
                Text("  ·  $it", style = MaterialTheme.typography.bodySmall,
                    color = if (p.ci?.conclusion == "success") TermOk else if (p.ci?.conclusion == null) cs.onSurfaceVariant else TermErr)
            }
        }
        if (p.loose.isNotEmpty()) Text("${p.loose.size} file(s) outside any library: ${p.loose.joinToString { it.path }}",
            style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(10.dp))
    val count = p.libraries.size + 1
    Pager(count) { i ->
        if (i < p.libraries.size) LibraryCard(vm, p, p.libraries[i])
        else NewLibraryCard(p) { adding = true }
    }
    if (adding) NewLibraryDialog(vm, p) { adding = false }
}

/** Cards centred one at a time; the neighbours peek. PageSize.Fixed + symmetric padding. */
@Composable
private fun Pager(count: Int, card: @Composable (Int) -> Unit) {
    val state = rememberPagerState { count }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = if (maxWidth - 48.dp < CARD_WIDTH) maxWidth - 48.dp else CARD_WIDTH
        HorizontalPager(state = state, pageSize = PageSize.Fixed(w), pageSpacing = 10.dp,
            contentPadding = PaddingValues(horizontal = (maxWidth - w) / 2)) { i -> card(i) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val on = i == state.currentPage
            Box(Modifier.padding(horizontal = 3.dp).size(7.dp)
                .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                .border(1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline))
        }
    }
}

@Composable
private fun CardBox(dashed: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().height(CARD_HEIGHT)
        .border(BorderStroke(1.dp, if (dashed) cs.outline else cs.outlineVariant), Sq)
        .background(cs.background, Sq)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCard(vm: WorkbenchViewModel, p: leanwb.Package, l: Library) {
    val cs = MaterialTheme.colorScheme
    val place = vm.resumePlace(p, l)
    val drafts = vm.draftsIn(p, l)
    CardBox(onClick = { vm.openLibrary(p, l) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.name, Modifier.weight(1f), style = LMono.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text("lean_lib", Modifier.border(1.dp, cs.outlineVariant, Sq).padding(horizontal = 5.dp),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Text(l.title ?: "${l.root} · no module doc", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (c in l.cells) {
                val m = Modifier.size(13.dp)
                Box(when (c) {
                    Cell.ROOT -> m.background(cs.onSurface)
                    Cell.PROVED -> m.background(TermOk)
                    Cell.SORRY -> m.background(TermWarn)
                    Cell.NOT_BUILT -> m.border(1.dp, cs.outline)
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${l.files.size} files", style = LMono)
            Text("${l.decls} decls", style = LMono)
            Text("${l.sorries} sorry", style = LMono, color = if (l.sorries > 0) TermWarn else Color.Unspecified)
            Text("${l.notBuilt} not built", style = LMono, color = if (l.notBuilt > 0) TermWarn else Color.Unspecified)
        }
        HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
        Text(place?.let { "▸ " + it.file.substringAfterLast('/') + (it.decl?.let { d -> " › $d" } ?: "") } ?: "▸ nothing opened yet",
            style = LMono, color = if (place != null) cs.onSurface else cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (drafts > 0) "$drafts file(s) with unsaved edits" else "no unsaved edits", style = MaterialTheme.typography.bodySmall,
            color = if (drafts > 0) TermWarn else cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cmd("open", strong = true, enabled = vm.busy == null) { vm.openLibrary(p, l) }
            Cmd("resume", enabled = place != null && vm.busy == null) { vm.resumeLibrary(p, l) }
        }
    }
}

@Composable
private fun NewLibraryCard(p: leanwb.Package, onNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardBox(dashed = true, onClick = if (p.canAddLibrary) onNew else null) {
        Spacer(Modifier.weight(1f))
        Text("+ lean_lib", style = LMono.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = cs.primary)
        Text(if (p.canAddLibrary) "Declares a new [[lean_lib]] in ${p.name}'s lakefile.toml (and as a default target, so lake build and CI " +
            "build it), and creates its root file." else "${p.name} has a ${p.lakefile}: libraries are added in Termux.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (p.canAddLibrary) Cmd("new library", strong = true, onClick = onNew)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NewLibraryDialog(vm: WorkbenchViewModel, p: leanwb.Package, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val problem = PackageText.newLibraryProblem(p, name.trim())
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("new library in ${p.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, textStyle = LMono,
                    label = { Text("name, e.g. Examples") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                Text(problem ?: "Writes lakefile.toml and ${name.trim()}.lean; nothing is committed.",
                    style = MaterialTheme.typography.bodySmall, color = if (problem != null) TermWarn else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Cmd("create", strong = true, enabled = problem == null && vm.busy == null) { vm.addLibrary(name.trim()); onClose() } },
        dismissButton = { Cmd("cancel", onClick = onClose) },
    )
}

@Composable
private fun RequiresTab(vm: WorkbenchViewModel) {
    val cs = MaterialTheme.colorScheme
    val p = vm.currentPackage() ?: return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("requires  ·  read-only", style = MaterialTheme.typography.titleSmall, color = TermWarn)
        Text("pinned by ${p.name}'s lake-manifest.json · search covers all of them", style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
    if (p.dependencies.isEmpty()) {
        Text("no dependencies in .lake/packages", Modifier.padding(16.dp), color = cs.outline)
        return
    }
    Pager(p.dependencies.size) { i -> DependencyCard(vm, p.dependencies[i]) }
}

@Composable
private fun DependencyCard(vm: WorkbenchViewModel, d: Dependency) {
    val cs = MaterialTheme.colorScheme
    CardBox(onClick = { vm.openSearch() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.library ?: d.name, Modifier.weight(1f), style = LMono.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text("read-only", Modifier.border(1.dp, TermWarn, Sq).padding(horizontal = 5.dp),
                style = MaterialTheme.typography.labelSmall, color = TermWarn)
        }
        Text("package ${d.name} · pinned " + listOfNotNull(d.inputRev, d.rev.ifEmpty { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
        Text("%,d files".format(d.files), style = LMono)
        HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
        Text("Find its lemmas by statement (Loogle) or in plain English (LeanSearch); never edited.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Cmd("search", strong = true) { vm.openSearch() }
    }
}
