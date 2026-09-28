package leanwb

/**
 * The landing page's library level: Lake packages as tabs, each with its
 * libraries (lakefile [[lean_lib]] targets) as cards, and its dependencies read-only.
 */
data class GitState(val branch: String?, val changed: Int, val ahead: Int, val behind: Int)

data class CiRun(val status: String?, val conclusion: String?, val at: String?, val sha: String, val workflow: String?)

/** One cell per file on a library card. */
enum class Cell { ROOT, PROVED, SORRY, NOT_BUILT }

data class Library(val name: String, val root: String, val files: List<FileInfo>) {
    val decls: Int get() = files.sumOf { it.decls }
    val sorries: Int get() = files.sumOf { it.sorries }
    val notBuilt: Int get() = files.count { it.role == "unimported" }

    /** Root first, then the files in path order: what a glance at the card shows. */
    val cells: List<Cell> get() = files.map {
        when {
            it.role == "root" -> Cell.ROOT
            it.role == "unimported" -> Cell.NOT_BUILT
            it.sorries > 0 -> Cell.SORRY
            else -> Cell.PROVED
        }
    }

    /** The root file's module-doc title, if it has one. */
    val title: String? get() = files.firstOrNull { it.path == root }?.title

    fun contains(path: String): Boolean = path == root || path.startsWith("$name/")
}

data class Dependency(val name: String, val library: String?, val rev: String, val inputRev: String?, val files: Int)

data class Package(
    val name: String, val toolchain: String?, val lakefile: String?,
    val libraries: List<Library>, val loose: List<FileInfo>,
    val git: GitState?, val ci: CiRun?, val dependencies: List<Dependency>,
) {
    /** Only a lakefile.toml can gain a library from the app. */
    val canAddLibrary: Boolean get() = lakefile == "lakefile.toml"

    fun libraryOf(path: String): Library? = libraries.firstOrNull { it.contains(path) }
}

data class Packages(val base: String, val default: String, val packages: List<Package>) {
    fun named(name: String?): Package? = packages.firstOrNull { it.name == (name ?: default) }
}

object PackagesDecode {
    private fun fail(what: String): Nothing = throw Decode.ContractError("bridge payload: bad or missing `$what`")

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, what: String) = v as? Map<String, Any?> ?: fail(what)
    private fun list(m: Map<String, Any?>, k: String) = m[k] as? List<*> ?: fail(k)
    private fun str(m: Map<String, Any?>, k: String) = m[k] as? String ?: fail(k)
    private fun int(m: Map<String, Any?>, k: String) = (m[k] as? Long)?.toInt() ?: fail(k)

    private fun file(v: Any?): FileInfo {
        val o = obj(v, "file")
        return FileInfo(str(o, "path"), o["title"] as String?, int(o, "decls"), int(o, "sorries"),
            o["empty"] as? Boolean ?: fail("empty"), str(o, "role"))
    }

    fun packages(json: String): Packages {
        val o = obj(Json.parse(json), "payload")
        return Packages(str(o, "base"), str(o, "default"), list(o, "packages").map { pv ->
            val p = obj(pv, "package")
            Package(
                name = str(p, "name"), toolchain = p["toolchain"] as String?, lakefile = p["lakefile"] as String?,
                libraries = list(p, "libraries").map { lv ->
                    val l = obj(lv, "library")
                    Library(str(l, "name"), str(l, "root"), list(l, "files").map(::file))
                },
                loose = list(p, "loose").map(::file),
                git = (p["git"] as? Map<*, *>)?.let { g ->
                    @Suppress("UNCHECKED_CAST") val gm = g as Map<String, Any?>
                    GitState(gm["branch"] as String?, int(gm, "changed"), int(gm, "ahead"), int(gm, "behind"))
                },
                ci = (p["ci"] as? Map<*, *>)?.let { c ->
                    @Suppress("UNCHECKED_CAST") val cm = c as Map<String, Any?>
                    CiRun(cm["status"] as String?, cm["conclusion"] as String?, cm["at"] as String?, cm["sha"] as? String ?: "", cm["workflow"] as String?)
                },
                dependencies = list(p, "dependencies").map { dv ->
                    val d = obj(dv, "dependency")
                    Dependency(str(d, "name"), d["library"] as String?, d["rev"] as? String ?: "", d["inputRev"] as String?, int(d, "files"))
                },
            )
        })
    }

    fun library(json: String): Pair<String, String> {
        val o = obj(Json.parse(json), "payload")
        return str(o, "lakefile") to str(o, "root")
    }
}

object PackageText {
    fun git(g: GitState?): String {
        if (g == null) return "no git"
        val parts = mutableListOf(g.branch ?: "detached", if (g.changed == 0) "clean" else "${g.changed} changed", "↑${g.ahead}")
        if (g.behind > 0) parts += "↓${g.behind}"
        return parts.joinToString(" · ")
    }

    /** "CI ✓ 24 h ago", "CI ✕ 3 d ago", "CI running", or null when there is no run. */
    fun ci(c: CiRun?, nowMillis: Long): String? {
        c ?: return null
        val mark = when {
            c.status != null && c.status != "completed" -> return "CI running"
            c.conclusion == "success" -> "✓"
            c.conclusion == null -> "?"
            else -> "✕ ${c.conclusion}"
        }
        val age = c.at?.let { isoMillis(it) }?.let { ago(nowMillis - it) }
        return "CI $mark" + (age?.let { " $it" } ?: "")
    }

    fun ago(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} d ago"
        }
    }

    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?Z$")

    /** A UTC ISO-8601 time (as GitHub gives it) to epoch millis; no java.time (API 26+). */
    fun isoMillis(s: String): Long? {
        val g = ISO.matchEntire(s)?.groupValues?.drop(1)?.map { it.toLong() } ?: return null
        val (y, mo, d) = Triple(g[0], g[1], g[2])
        // Days from civil (Howard Hinnant's algorithm).
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468
        return ((days * 24 + g[3]) * 60 + g[4]) * 60_000 + g[5] * 1000
    }

    private val LIB_NAME = Regex("^[A-Z][A-Za-z0-9_]{0,63}$")

    /** Why [name] cannot be a new library of [p], or null if it can. */
    fun newLibraryProblem(p: Package, name: String): String? = when {
        !p.canAddLibrary -> "${p.name} has a ${p.lakefile ?: "missing lakefile"}; only a lakefile.toml can be extended from the app"
        name.isBlank() -> "type a name"
        !LIB_NAME.matches(name) -> "a capitalised module name: letters, digits, _"
        p.libraries.any { it.name == name } -> "$name is already a library of ${p.name}"
        else -> null
    }
}

/** Where to resume a library: the last declaration opened in it. Kept per package/library. */
data class Place(val file: String, val decl: String?)

object Resume {
    fun key(pkg: String, lib: String) = "$pkg/$lib"

    fun encode(p: Place): String = p.file + "\t" + (p.decl ?: "")

    fun decode(s: String?): Place? {
        s ?: return null
        val (f, d) = s.split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return if (f.isEmpty()) null else Place(f, d.ifEmpty { null })
    }
}
