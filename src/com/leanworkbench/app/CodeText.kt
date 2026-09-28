package com.leanworkbench.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import leanwb.Code
import leanwb.Span

// The code colours, muted to sit in the terminal palettes. Constants stay plain
// ink: only Lean could name them, and it does not.

class CodePalette(
    val keyword: Color, val tactic: Color, val decl: Color, val local: Color, val field: Color, val hole: Color,
    val symbol: Color, val attr: Color, val comment: Color, val literal: Color, val sorry: Color, val sorryBg: Color,
) {
    fun style(k: Code): SpanStyle = when (k) {
        Code.KEYWORD -> SpanStyle(color = keyword)
        Code.TACTIC -> SpanStyle(color = tactic)
        Code.DECL -> SpanStyle(color = decl, fontWeight = FontWeight.Bold)
        Code.LOCAL -> SpanStyle(color = local)
        Code.FIELD -> SpanStyle(color = field)
        Code.HOLE -> SpanStyle(color = hole, textDecoration = TextDecoration.Underline)
        Code.SYMBOL -> SpanStyle(color = symbol)
        Code.ATTR -> SpanStyle(color = attr)
        Code.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
        Code.LITERAL -> SpanStyle(color = literal)
        Code.SORRY -> SpanStyle(color = sorry, background = sorryBg)
    }
}

private val PaperCode = CodePalette(
    keyword = Color(0xFF7A3E9D), tactic = Color(0xFF1F5FA8), decl = Color(0xFF1F6B3A), local = Color(0xFF8A5200),
    field = Color(0xFF2A7470), hole = Color(0xFFA3367A), symbol = Color(0xFF6B6A63), attr = Color(0xFF6B6A63),
    comment = Color(0xFF8C897F), literal = Color(0xFFB0571E), sorry = Color(0xFFB0281E), sorryBg = Color(0xFFF5DFDA),
)

private val PhosphorCode = CodePalette(
    keyword = Color(0xFFC792EA), tactic = Color(0xFF82AAFF), decl = Color(0xFF7FD196), local = Color(0xFFE0A84E),
    field = Color(0xFF7FCFC4), hole = Color(0xFFF07AB8), symbol = Color(0xFF9A9D93), attr = Color(0xFF8C8F85),
    comment = Color(0xFF72766C), literal = Color(0xFFF0A070), sorry = Color(0xFFFF8A7A), sorryBg = Color(0xFF3B1C18),
)

val codePalette: CodePalette @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) PhosphorCode else PaperCode

/** [text] with [spans] painted; what the spans leave out keeps the surrounding text colour. */
fun coloured(text: String, spans: List<Span>, p: CodePalette): AnnotatedString {
    val b = AnnotatedString.Builder(text)
    for (s in spans) if (s.end <= text.length) b.addStyle(p.style(s.kind), s.start, s.end)
    return b.toAnnotatedString()
}

/** Colours an editable field as it is typed; the cursor and selection are untouched (identity offsets). */
class CodeColouring(private val p: CodePalette, private val spansOf: (String) -> List<Span>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(coloured(text.text, spansOf(text.text), p), OffsetMapping.Identity)

    // Recomposition makes a new instance; equal palettes and rules colour alike.
    override fun equals(other: Any?) = other is CodeColouring && other.p === p && other.spansOf === spansOf
    override fun hashCode() = System.identityHashCode(p) * 31 + System.identityHashCode(spansOf)
}

/** The scanner alone: for text Lean has not checked (typed tactics, sources, search hits). */
val lexOnly: (String) -> List<Span> = { leanwb.Lexer.spans(it) }
