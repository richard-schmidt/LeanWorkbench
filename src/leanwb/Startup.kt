package leanwb

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Starting the bridge from the app. An app cannot run Termux's binaries, but
 * Termux's RUN_COMMAND service runs a command for an app that holds its permission,
 * when `allow-external-apps = true` is set in ~/.termux/termux.properties.
 * The command is lean-mcp's start-bridge.sh, which does nothing if the bridge is up.
 */
object BridgeStart {
    const val TERMUX_PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val SCRIPT = "/data/data/com.termux/files/home/LeanProjects/lean-mcp/start-bridge.sh"

    /**
     * Arguments to [BASH]. The script runs in the foreground: the Termux task, and so
     * Termux itself, stays alive as long as the bridge runs.
     */
    fun arguments(pair: Boolean): List<String> = if (pair) listOf(SCRIPT, "--pair") else listOf(SCRIPT)

    /** The same start, typed or pasted into a Termux shell: detached so the shell stays usable. */
    fun shellCommand(pair: Boolean): String =
        "bash ~/LeanProjects/lean-mcp/start-bridge.sh --detach" + if (pair) " --pair" else ""

    /** Gaps between health polls after a start request: the bridge answers about 1 s after launch; 20 s in all. */
    val POLL_GAPS_MS: List<Long> = List(6) { 500L } + List(17) { 1_000L }

    /** On launch the app starts the bridge itself once, only if it is down and Termux lets it. */
    fun autoStart(up: Boolean, permitted: Boolean, alreadyTried: Boolean): Boolean = !up && permitted && !alreadyTried
}

/**
 * The launch splash: the join lattice draws bottom-up, the join lights last,
 * the whole mark gives one heartbeat, lingers, then fades into the app.
 * Everything is a function of milliseconds since the start, so the timing is tested
 * here and the Canvas only draws a [Frame].
 */
object LatticeSplash {
    const val DRAW_MS = 1_300f
    const val PULSE_MS = 480f
    const val HOLD_MS = 520f
    const val FADE_MS = 360f
    const val TOTAL_MS = DRAW_MS + PULSE_MS + HOLD_MS + FADE_MS

    /**
     * [nodes]: scale of bottom, left, right, join. [edges]: drawn fraction of
     * bottom-left, bottom-right, left-join, right-join. [scale]: the heartbeat on the
     * whole mark. [glow]: the halo around the join during the beat. [alpha]: the splash.
     */
    data class Frame(val nodes: List<Float>, val edges: List<Float>, val scale: Float, val glow: Float, val alpha: Float)

    fun frame(ms: Float): Frame {
        val t = ms.coerceIn(0f, TOTAL_MS)
        val pulse = seg(t, DRAW_MS, DRAW_MS + PULSE_MS)
        val fade = seg(t, DRAW_MS + PULSE_MS + HOLD_MS, TOTAL_MS)
        val lower = ease(seg(t, 200f, 580f))
        val upper = ease(seg(t, 700f, 1_060f))
        val middle = pop(seg(t, 540f, 760f))
        return Frame(
            nodes = listOf(pop(seg(t, 0f, 240f)), middle, middle, pop(seg(t, 1_000f, DRAW_MS))),
            edges = listOf(lower, lower, upper, upper),
            scale = heartbeat(pulse * PULSE_MS),
            glow = sin(pulse * PI.toFloat()).coerceAtLeast(0f),
            alpha = 1f - ease(fade),
        )
    }

    /** Scale keyframes of one "ba-dum" (ms into the pulse to scale), eased between. */
    private val BEAT = listOf(0f to 1f, 130f to 1.07f, 260f to 0.985f, 370f to 1.035f, PULSE_MS to 1f)

    fun heartbeat(ms: Float): Float {
        if (ms <= 0f || ms >= PULSE_MS) return 1f
        val i = BEAT.indexOfLast { it.first <= ms }
        val (t0, v0) = BEAT[i]
        val (t1, v1) = BEAT[i + 1]
        val k = (ms - t0) / (t1 - t0)
        return v0 + (v1 - v0) * (k * k * (3 - 2 * k))
    }

    private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun ease(k: Float): Float = 1f - (1f - k).pow(2.2f)

    /** Ease-out to 1 with a small overshoot that settles by the end. */
    fun pop(p: Float): Float {
        val e = p.coerceIn(0f, 1f)
        return 1f - (1f - e).pow(3) + sin(e * PI.toFloat()) * 0.16f * (1f - e * 0.6f)
    }
}
