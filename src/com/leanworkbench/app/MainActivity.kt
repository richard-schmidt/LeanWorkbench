package com.leanworkbench.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import leanwb.PairingLink

class MainActivity : ComponentActivity() {
    private val vm: WorkbenchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            // The splash plays once per launch; rememberSaveable keeps it gone across a rotation.
            var splashDone by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                WorkbenchApp(vm)
                if (!splashDone) LatticeSplashOverlay { splashDone = true }
            }
        }
        if (savedInstanceState == null && vm.pairing != null) vm.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val p = PairingLink.parse(uri)
        if (p != null) vm.offerPairing(p) else vm.error = "Ignored a malformed pairing link."
    }
}
