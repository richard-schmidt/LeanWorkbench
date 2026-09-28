package leanwb

/** Unsaved work on one file: the disk text it started from, and the text as edited in the app. */
data class WorkingCopy(val base: String, val text: String)

/** A file of a package. [pkg] is "" only for drafts kept before packages existed, until [Drafts.adopt]. */
data class FileRef(val pkg: String, val file: String)

/**
 * A typed-but-not-checked prompt, anchored where it goes so that it follows its line
 * when lines above it are added or removed (in the app or on disk):
 * - [kind]: L replaces a line, I inserts after a line, B edits the whole declaration;
 * - [decl]: the declaration's key ([Drafts.declKey]);
 * - [offset]: the line's distance from the declaration's first line;
 * - [anchor]: that line's text, trimmed, when the prompt was kept.
 * A prompt kept before anchors existed has [decl] and [anchor] null and [offset] the absolute line.
 */
data class KeptPrompt(val kind: Char, val decl: String?, val offset: Int, val anchor: String?, val text: String) {

    /** The line this prompt targets in [text], if [d] is its declaration; null otherwise. */
    fun lineIn(text: String, d: Declaration): Int? {
        if (decl == null) return offset.takeIf { it in d.line..d.endLine }
        if (decl != Drafts.declKey(d)) return null
        if (kind == 'B') return d.line
        val nominal = d.line + offset
        val lines = text.split("\n")
        val found = (d.line..minOf(d.endLine, lines.size)).filter { lines[it - 1].trim() == anchor }
        return found.minByOrNull { Math.abs(it - nominal) } ?: nominal.takeIf { it in d.line..d.endLine }
    }
}

/**
 * Everything unsaved, kept by the app (never written to the project) so that
 * leaving a theorem, a file or the app loses nothing:
 * - [copies]: edited-but-not-saved file texts;
 * - [prompts]: typed-but-not-checked tactic drafts.
 */
data class Drafts(val copies: Map<FileRef, WorkingCopy> = emptyMap(), val prompts: Map<FileRef, List<KeptPrompt>> = emptyMap()) {

    /** Record [text] for [f]; a text equal to its base is no longer a draft. */
    fun withText(f: FileRef, base: String, text: String): Drafts =
        copy(copies = if (text == base) copies - f else copies + (f to WorkingCopy(base, text)))

    fun without(f: FileRef): Drafts = copy(copies = copies - f, prompts = prompts - f)

    /** The prompt of [kind] kept for [line] of [d] in [text], if any. */
    fun prompt(f: FileRef, text: String, d: Declaration, kind: Char, line: Int): KeptPrompt? =
        prompts[f]?.firstOrNull { it.kind == kind && it.lineIn(text, d) == line }

    /** Keep [draft] as the prompt of [kind] for [line] of [d] in [text]; null or empty drops it. */
    fun withPrompt(f: FileRef, text: String, d: Declaration, kind: Char, line: Int, draft: String?): Drafts {
        val rest = (prompts[f] ?: emptyList()).filterNot { it.kind == kind && it.lineIn(text, d) == line }
        val anchor = text.split("\n").getOrNull(line - 1)?.trim() ?: ""
        val kept = if (draft.isNullOrEmpty()) rest else rest + KeptPrompt(kind, declKey(d), line - d.line, anchor, draft)
        return copy(prompts = if (kept.isEmpty()) prompts - f else prompts + (f to kept))
    }

    /** Prompts of [d] in [text]: (kind, line, prompt), in line order. */
    fun promptsIn(f: FileRef, text: String, d: Declaration): List<Triple<Char, Int, KeptPrompt>> =
        (prompts[f] ?: emptyList()).mapNotNull { p -> p.lineIn(text, d)?.let { Triple(p.kind, it, p) } }.sortedBy { it.second }

    /** Hand drafts kept before packages existed to [pkg] (the bridge's default); a newer draft for the same file wins. */
    fun adopt(pkg: String): Drafts {
        fun <V> move(m: Map<FileRef, V>): Map<FileRef, V> {
            val (old, cur) = m.entries.partition { it.key.pkg == "" }
            return old.associate { FileRef(pkg, it.key.file) to it.value } + cur.associate { it.key to it.value }
        }
        return Drafts(move(copies), move(prompts))
    }

    /**
     * What opening [f] should show, given its text on disk: the working copy if it
     * was made from this disk text, the disk text if there is none, or [Opened.Stale]
     * if the file changed on disk underneath unsaved edits (a save would be refused).
     */
    fun open(f: FileRef, disk: String): Opened {
        val wc = copies[f] ?: return Opened.Clean
        return if (wc.base == disk) Opened.Resumed(wc.text) else Opened.Stale(wc)
    }

    sealed interface Opened {
        object Clean : Opened
        data class Resumed(val text: String) : Opened
        data class Stale(val copy: WorkingCopy) : Opened
    }

    fun encode(): String = buildString {
        fun <V> byPkg(m: Map<FileRef, V>, value: (V) -> String) =
            m.entries.groupBy { it.key.pkg }.entries.joinToString(",") { (pkg, es) ->
                Json.quote(pkg) + ":{" + es.joinToString(",") { Json.quote(it.key.file) + ":" + value(it.value) } + "}"
            }
        append("{\"version\":2,\"copies\":{")
        append(byPkg(copies) { wc -> "{\"base\":" + Json.quote(wc.base) + ",\"text\":" + Json.quote(wc.text) + "}" })
        append("},\"prompts\":{")
        append(byPkg(prompts) { ps -> ps.joinToString(",", "[", "]") { p ->
            "{\"kind\":" + Json.quote(p.kind.toString()) + ",\"decl\":" + (p.decl?.let(Json::quote) ?: "null") +
                ",\"offset\":" + p.offset + ",\"anchor\":" + (p.anchor?.let(Json::quote) ?: "null") + ",\"text\":" + Json.quote(p.text) + "}"
        } })
        append("}}")
    }

    companion object {
        /** A declaration's identity for kept prompts: its name, else its label (which holds its line). */
        fun declKey(d: Declaration): String = d.name ?: d.label

        /** Throws [Decode.ContractError] on anything but a store this code (or version 1 of it) wrote. */
        fun decode(json: String): Drafts {
            val m = Json.parse(json) as? Map<*, *> ?: bad("store")
            return when (m["version"]) {
                1L -> decodeV1(m)
                2L -> decodeV2(m)
                else -> bad("version")
            }
        }

        private fun copyOf(v: Any?): WorkingCopy {
            val o = v as? Map<*, *> ?: bad("copy")
            return WorkingCopy(o["base"] as? String ?: bad("base"), o["text"] as? String ?: bad("text"))
        }

        /** Version 1: copies by file, prompts by "file#K<line>"; the package is not known yet (""). */
        private fun decodeV1(m: Map<*, *>): Drafts {
            val copies = (m["copies"] as? Map<*, *> ?: bad("copies")).entries.associate { (f, v) ->
                FileRef("", f as String) to copyOf(v)
            }
            val prompts = (m["prompts"] as? Map<*, *> ?: bad("prompts")).entries.mapNotNull { (k, v) ->
                val key = k as String
                val tag = key.substringAfterLast('#')
                val line = tag.drop(1).toIntOrNull()
                if (tag.isEmpty() || tag[0] !in "LIB" || line == null) return@mapNotNull null
                FileRef("", key.substringBeforeLast('#')) to KeptPrompt(tag[0], null, line, null, v as? String ?: bad("prompt"))
            }.groupBy({ it.first }, { it.second })
            return Drafts(copies, prompts)
        }

        private fun decodeV2(m: Map<*, *>): Drafts {
            fun <V> byPkg(key: String, value: (Any?) -> V): Map<FileRef, V> =
                (m[key] as? Map<*, *> ?: bad(key)).entries.flatMap { (pkg, files) ->
                    (files as? Map<*, *> ?: bad(key)).entries.map { (f, v) -> FileRef(pkg as String, f as String) to value(v) }
                }.toMap()
            val copies = byPkg("copies", ::copyOf)
            val prompts = byPkg("prompts") { v ->
                (v as? List<*> ?: bad("prompts")).map { pv ->
                    val o = pv as? Map<*, *> ?: bad("prompt")
                    val kind = (o["kind"] as? String)?.singleOrNull()?.takeIf { it in "LIB" } ?: bad("kind")
                    KeptPrompt(kind, o["decl"]?.let { it as? String ?: bad("decl") }, (o["offset"] as? Long ?: bad("offset")).toInt(),
                        o["anchor"]?.let { it as? String ?: bad("anchor") }, o["text"] as? String ?: bad("text"))
                }
            }
            return Drafts(copies, prompts)
        }

        private fun bad(what: String): Nothing = throw Decode.ContractError("drafts store: bad or missing `$what`")
    }
}
