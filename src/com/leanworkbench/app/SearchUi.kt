package com.leanworkbench.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import leanwb.Engine
import leanwb.HitForm
import leanwb.HitState
import leanwb.SearchHit

private val SMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
private val SearchKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
    imeAction = ImeAction.Search)

@Composable
private fun SRule() = HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

/**
 * Mathlib searched by statement (Loogle) or in plain English (LeanSearch).
 * Every hit says whether it can be used in the file being edited. In a proof, a hit can
 * be tried on the goal and inserted into the tactic field.
 */
@Composable
fun SearchSheet(vm: WorkbenchViewModel) {
    BackHandler(enabled = vm.sourceView == null) { vm.closeSearch() }
    val inProof = vm.searchInProof
    var open by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cmd("‹ back") { vm.closeSearch() }
                Text("search Mathlib", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
            }
            Text(if (inProof) "checked against ${vm.result?.file?.substringAfterLast('/')} (unsaved edits included)"
                 else "checked against the library root's imports",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SRule()
            vm.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant)
                Text("… $it", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.error?.let {
                Text("✕ $it", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable { vm.error = null }
                    .padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in Engine.values()) Token(e.label, on = vm.searchEngine == e) { vm.searchEngine = e }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(vm.searchQuery, { vm.typeQuery(it) }, Modifier.weight(1f), textStyle = SMono, singleLine = true,
                    placeholder = { Text(if (vm.searchEngine == Engine.LOOGLE) "|- _ ⊔ _ ≤ _" else "the join is below c iff …", style = SMono) },
                    keyboardOptions = SearchKeyboard, keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }))
                Spacer(Modifier.width(6.dp))
                Cmd("search", enabled = vm.busy == null && vm.searchQuery.text.isNotBlank(), strong = true) { vm.runSearch() }
            }
            Text(vm.searchEngine.hint + "  ·  \\name types a symbol", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (inProof) Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("use as", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                for (f in HitForm.values()) Token(f.label, on = vm.hitForm == f, style = SMono) { vm.hitForm = f }
            }
            val r = vm.searchResult
            if (r != null) {
                val shown = if (r.count > r.hits.size) "${r.hits.size} of ${r.count} hits" else "${r.hits.size} hits"
                Text(r.error?.let { "✕ $it" } ?: "$shown · ✓ usable here · ⇣ needs an import · ✕ not in your Mathlib",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (r.error != null) TermErr else MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.suggestions.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in r.suggestions) Token(s, style = SMono) { vm.searchSuggestion(s) }
                }
            }
            SRule()
            LazyColumn(Modifier.weight(1f)) {
                items(r?.hits ?: emptyList(), key = { it.name + "@" + it.module }) { h ->
                    HitRow(vm, h, inProof, open == h.name) { open = if (open == h.name) null else h.name }
                    SRule()
                }
            }
        }
    }
}

@Composable
private fun HitRow(vm: WorkbenchViewModel, h: SearchHit, inProof: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val (glyph, color, note) = when (h.state) {
        HitState.OK -> Triple("✓", TermOk, null)
        HitState.IMPORT -> Triple("⇣", TermWarn, "needs import ${h.module}")
        HitState.MISSING -> Triple("✕", TermErr, "not in your Mathlib (renamed or newer)")
        HitState.UNCHECKED -> Triple("?", cs.outline, "not checked")
    }
    Column(Modifier.fillMaxWidth().background(if (expanded) cs.surfaceContainerLow else cs.background)
        .clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = color, style = SMono, modifier = Modifier.width(18.dp))
            Text(h.name, style = SMono.copy(fontWeight = FontWeight.Bold,
                textDecoration = if (h.state == HitState.MISSING) TextDecoration.LineThrough else null))
            if (vm.pinned(h.name)) Text("  pinned", style = MaterialTheme.typography.labelSmall, color = cs.primary)
        }
        Text(coloured(h.type, leanwb.Lexer.spans(h.type), codePalette), Modifier.padding(start = 18.dp, top = 2.dp), style = SMono,
            maxLines = if (expanded) Int.MAX_VALUE else 2)
        Text(h.module, Modifier.padding(start = 18.dp, top = 2.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        (h.informal ?: h.doc)?.let {
            Text(it, Modifier.padding(start = 18.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 1)
        }
        note?.let { Text(it, Modifier.padding(start = 18.dp, top = 2.dp), style = MaterialTheme.typography.labelSmall, color = color) }
        if (expanded) {
            Row(Modifier.padding(start = 10.dp, top = 4.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val busy = vm.busy != null
                if (inProof && h.state.usable) {
                    Cmd("try", enabled = !busy) { vm.tryHit(h) }
                    Cmd("insert", enabled = !busy, strong = true) { vm.insertHit(h) }
                }
                if (inProof && h.state == HitState.IMPORT) Cmd("add import", enabled = !busy, strong = true) { vm.addImport(h) }
                if (!inProof && h.state != HitState.MISSING) Cmd("copy name") { clipboard.setText(AnnotatedString(h.name)) }
                if (h.state != HitState.MISSING) Cmd(if (vm.pinned(h.name)) "unpin" else "pin", enabled = !busy) { vm.togglePin(h) }
                Cmd("source", enabled = !busy && h.state != HitState.MISSING) { vm.openSource(h.module, h.name) }
            }
            vm.tried?.takeIf { it.first == h.name }?.second?.let { o ->
                Column(Modifier.padding(start = 18.dp, top = 4.dp).fillMaxWidth().background(cs.surfaceContainer).padding(8.dp)) {
                    Text((if (o.ok) "✓ " else "✕ ") + o.summary, style = SMono, color = if (o.ok) TermOk else TermErr)
                    o.goal?.let { g -> Text(coloured("⊢ $g", leanwb.Lexer.spans("⊢ $g"), codePalette), style = SMono) }
                    Text("tried as `${leanwb.HitUse.apply(vm.draft.text, vm.draft.selection.min, vm.draft.selection.max, vm.hitForm, h.name).text}`; nothing was changed",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** A lemma's module, read-only, scrolled to the declaration. */
@Composable
fun SourceSheet(vm: WorkbenchViewModel, s: leanwb.Source) {
    BackHandler { vm.closeSource() }
    val lines = remember(s) { s.text.split("\n") }
    // The scanner alone: opening a Mathlib module in Lean would re-elaborate it.
    val lineSpans = remember(s) { leanwb.Highlight.byLine(s.text, leanwb.Lexer.spans(s.text)) }
    val pal = codePalette
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (s.line - 4).coerceAtLeast(0))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cmd("‹ back") { vm.closeSource() }
                Text(s.module, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            Text("read-only · ${s.path}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
                color = TermWarn, maxLines = 1)
            SRule()
            LazyColumn(Modifier.weight(1f).horizontalScroll(rememberScrollState()), state = state) {
                itemsIndexed(lines) { i, l ->
                    val here = i + 1 == s.line
                    Row(Modifier.background(if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)) {
                        Text("%5d ".format(i + 1), style = SMono, color = MaterialTheme.colorScheme.outline)
                        Text(coloured(l, lineSpans.getOrElse(i) { emptyList() }, pal), style = SMono, softWrap = false)
                    }
                }
            }
        }
    }
}
