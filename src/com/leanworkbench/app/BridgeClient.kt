package com.leanworkbench.app

import leanwb.CheckResult
import leanwb.Decode
import leanwb.Pairing
import leanwb.Requests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Blocking HTTP to the Termux bridge. Call off the main thread. */
class BridgeClient(private val pairing: Pairing, private val pkg: String? = null) {
    class BridgeError(message: String) : Exception(message)

    fun health(): Boolean = try {
        request("GET", "/v1/health", null, auth = false); true
    } catch (e: Exception) {
        false
    }

    fun files(): List<String> = Decode.files(request("GET", "/v1/files", null))

    fun check(file: String, text: String?, goalsAt: List<Int>, goalsAfter: List<Int> = emptyList()): CheckResult =
        Decode.check(request("POST", "/v1/check", Requests.check(file, text, goalsAt, goalsAfter)))

    fun overview(): List<leanwb.FileInfo> = Decode.overview(request("GET", "/v1/overview", null))

    fun abbreviations(): Map<String, String> = Decode.abbreviations(request("GET", "/v1/abbreviations", null))

    /** Sets one project abbreviation (writes .leanwb/abbreviations.json); returns the whole table. */
    fun setAbbreviation(name: String, symbol: String): Map<String, String> =
        Decode.abbreviations(request("POST", "/v1/abbreviation", Requests.abbreviation(name, symbol)))

    fun complete(file: String, text: String, line: Int, col: Int): Pair<String, List<leanwb.Completion>> =
        Decode.completions(request("POST", "/v1/complete", Requests.complete(file, text, line, col)))

    /** Loogle or LeanSearch; every hit checked in [file]'s scope (with its unsaved [text]). */
    fun search(engine: leanwb.Engine, query: String, file: String?, text: String?): leanwb.SearchResult =
        leanwb.SearchDecode.result(request("POST", "/v1/search", leanwb.SearchRequests.search(engine, query, file, text)))

    fun toolbox(): List<leanwb.Pinned> = leanwb.SearchDecode.toolbox(request("GET", "/v1/toolbox", null))

    /** Pins or unpins one lemma (writes .leanwb/toolbox.json); returns the whole toolbox. */
    fun pin(p: leanwb.Pinned, pinned: Boolean): List<leanwb.Pinned> =
        leanwb.SearchDecode.toolbox(request("POST", "/v1/toolbox", leanwb.SearchRequests.pin(p, pinned)))

    fun source(module: String, name: String): leanwb.Source =
        leanwb.SearchDecode.source(request("POST", "/v1/source", leanwb.SearchRequests.source(module, name)))

    /** Every Lake package the bridge can open, with libraries, git, CI and dependencies. */
    fun packages(): leanwb.Packages = leanwb.PackagesDecode.packages(request("GET", "/v1/packages", null))

    /** + lean_lib: a new [[lean_lib]] in this package's lakefile.toml and its root file. */
    fun addLibrary(name: String): Pair<String, String> =
        leanwb.PackagesDecode.library(request("POST", "/v1/library", "{\"name\":" + leanwb.Json.quote(name) + "}"))

    /** Writes [text] if the file on disk still equals [base]; the bridge refuses otherwise. */
    fun save(file: String, text: String, base: String) {
        request("POST", "/v1/save", Requests.save(file, text, base))
    }

    /** Creates a new source; the bridge refuses an existing file and adds the import to the library root. */
    fun create(file: String, text: String) {
        request("POST", "/v1/create", Requests.create(file, text))
    }

    private fun request(method: String, path: String, body: String?, auth: Boolean = true): String {
        val c = URL("http://127.0.0.1:${pairing.port}$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 3_000
            c.readTimeout = 20 * 60_000 // a cold check of a Mathlib-heavy file takes minutes
            if (auth) c.setRequestProperty("Authorization", "Bearer ${pairing.token}")
            // The package every other endpoint acts on (default: the bridge's own project).
            if (pkg != null) c.setRequestProperty("X-Lean-Package", pkg)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw BridgeError(Decode.error(text) ?: "bridge answered HTTP $code")
            return text
        } catch (e: IOException) {
            throw BridgeError("bridge not reachable on port ${pairing.port}: ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}
