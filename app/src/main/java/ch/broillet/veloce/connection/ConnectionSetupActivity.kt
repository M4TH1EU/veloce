package ch.broillet.veloce.connection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ch.broillet.veloce.VeloceApplication
import kotlinx.coroutines.launch

/** Obtains a fresh audio token per drive, including companion-device starts with the phone locked. */
class ConnectionSetupActivity : ComponentActivity() {
    private val connections get() = (application as VeloceApplication).connections
    private val session get() = intent.getStringExtra("session")
    private var permissionsAsked = false
    private var busy = false
    private var prepared = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        busy = false
        advance()
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val state = connections.state.value
        if (session == state.sessionId && session == state.audioRequest && !state.audioCapturing) {
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startService(Intent(this, ConnectionService::class.java).setAction("audio")
                    .putExtra("session", session).putExtra("result", result.resultCode).putExtra("consent", data))
            } else connections.audio("Audio sharing declined · tap Enable audio to retry")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsAsked = savedInstanceState?.getBoolean("permissions_asked") ?: false
        busy = savedInstanceState?.getBoolean("busy") ?: false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { connections.state.collect { advance() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("session") != session) {
            finish()
            startActivity(intent)
        } else { setIntent(intent); advance() }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun advance() {
        if (isFinishing) return
        val state = connections.state.value
        if (!state.active || session == null || session != state.sessionId) { finish(); return }
        if (busy) return
        if (!permissionsAsked) {
            permissionsAsked = true
            val missing = buildList {
                if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (connections.forwardAudio.value && !granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            }
            if (missing.isNotEmpty()) {
                busy = true
                permissions.launch(missing.toTypedArray())
                return
            }
        }
        if (!prepared) {
            prepared = true
            startService(Intent(this, ConnectionService::class.java).setAction("prepare").putExtra("session", session))
        }
        if (!intent.getBooleanExtra("audio", true) || !connections.forwardAudio.value || state.audioCapturing) { finish(); return }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            connections.audio("Allow audio recording permission to capture media playback")
            finish()
            return
        }
        if (state.audioRequest != session) { finish(); return }
        busy = true
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            consent.launch(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
        } catch (error: Exception) {
            connections.audio("Could not request audio sharing: ${error.message}")
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissions_asked", permissionsAsked)
        outState.putBoolean("busy", busy)
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun intent(context: Context, session: String, audio: Boolean = true) = Intent(context, ConnectionSetupActivity::class.java)
            .putExtra("session", session).putExtra("audio", audio).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
