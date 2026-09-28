package leanwb

/** Where the bridge listens and the token it expects. */
data class Pairing(val port: Int, val token: String)

object PairingLink {
    private val TOKEN = Regex("[A-Za-z0-9_-]{16,128}")

    /**
     * `leanwb://pair?port=8766&token=...` as sent by `bridge.py --pair`.
     * Anything else is refused (null): the link can come from any app on the device,
     * so it is also confirmed by the user before it replaces a stored pairing.
     */
    fun parse(uri: String): Pairing? {
        val prefix = "leanwb://pair?"
        if (!uri.startsWith(prefix)) return null
        val params = uri.removePrefix(prefix).split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val port = params["port"]?.toIntOrNull() ?: return null
        val token = params["token"] ?: return null
        if (port !in 1024..65535 || !TOKEN.matches(token)) return null
        return Pairing(port, token)
    }
}
