package com.leanworkbench.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import leanwb.BridgeStart

/** Asks Termux's RUN_COMMAND service to run start-bridge.sh. */
object TermuxRunner {
    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(BridgeStart.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Sends the start request; null when Termux accepted it, else why not. */
    fun start(ctx: Context, pair: Boolean): String? {
        val i = Intent(BridgeStart.ACTION)
            .setClassName(BridgeStart.TERMUX_PACKAGE, BridgeStart.SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", BridgeStart.BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", BridgeStart.arguments(pair).toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Lean bridge")
        return try {
            val started = try {
                ctx.startService(i)
            } catch (e: IllegalStateException) {
                // Background-start limits: RunCommandService goes foreground itself.
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else throw e
            }
            if (started == null) "Termux is not installed, or this app cannot see it." else null
        } catch (e: SecurityException) {
            "Termux refused: set allow-external-apps = true in ~/.termux/termux.properties, and allow " +
                "\"Run commands in Termux environment\" for this app. (${e.message})"
        } catch (e: Exception) {
            "Could not reach Termux: ${e.message ?: e}"
        }
    }
}
