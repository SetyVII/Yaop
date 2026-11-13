package org.yaop

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import org.yaop.ui.lock.LockScreen
import org.yaop.core.debug.PhoneFrameIf
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import org.yaop.ui.common.privacyToggleGesture
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.yaop.ui.onboarding.OnboardingScreen
import org.yaop.ui.main.MainAppContainer
import org.yaop.ui.theme.YaopTheme

class MainActivity : FragmentActivity() {

    private val targetObjectIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as YaopApp
        val repository = app.repository
        val appLock = app.appLock
        maybeSeedDemo(app)
        org.yaop.ui.common.ImagePrivacy.init(this)
        org.yaop.ui.settings.HomePrefs.init(this)
        applySecureFlag()
        targetObjectIdState.value = intent?.getStringExtra(EXTRA_OBJECT_ID)

        val phoneFrame = isDebuggable() && intent?.getBooleanExtra("phone_frame", false) == true
        setContent {
            YaopTheme {
                PhoneFrameIf(phoneFrame) {
                    // Recents preview must not show images while the privacy mode is on (§7.2).
                    val imagesPrivate = org.yaop.ui.common.ImagePrivacy.isPrivate
                    LaunchedEffect(imagesPrivate) { applySecureFlag() }
                    val locked by appLock.locked.collectAsState()
                    if (locked) {
                        LockScreen(this@MainActivity, onUnlocked = appLock::unlock)
                        return@PhoneFrameIf
                    }
                    // null = still checking; no vault key yet -> first-run onboarding (spec §21.1).
                    var hasVault by remember { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(Unit) { hasVault = repository.vaultKeyStore.hasVaultKey() }
                    when (hasVault) {
                        null -> Unit
                        false -> OnboardingScreen(onDone = { hasVault = true })
                        true -> androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxSize().privacyToggleGesture(),
                        ) {
                            MainAppContainer(
                                repository = repository,
                                initialObjectId = targetObjectIdState.value,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as YaopApp).appLock.onForegrounded()
        applySecureFlag()
    }

    override fun onStop() {
        super.onStop()
        (application as YaopApp).appLock.onBackgrounded(isChangingConfigurations)
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) org.yaop.ui.common.ImagePrivacy.onAppExit()
        super.onDestroy()
    }

    private fun isDebuggable() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Debug builds only: `--ez seed_demo true` fills a throwaway vault with demo content (see DemoSeeder). */
    private fun maybeSeedDemo(app: YaopApp) {
        if (!isDebuggable() || intent?.getBooleanExtra("seed_demo", false) != true) return
        kotlinx.coroutines.runBlocking { org.yaop.core.debug.DemoSeeder.seed(app) }
    }

    private fun applySecureFlag() {
        val lock = (application as YaopApp).appLock
        // Debug builds only: `--ez allow_screenshots true` keeps screencap working for tools/phone-preview.sh.
        val devShots = isDebuggable() && intent?.getBooleanExtra("allow_screenshots", false) == true
        if (!devShots && ((lock.enabled && lock.secureScreen) || org.yaop.ui.common.ImagePrivacy.isPrivate)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetId = intent.getStringExtra(EXTRA_OBJECT_ID)
        if (!targetId.isNullOrBlank()) {
            targetObjectIdState.value = targetId
        }
    }

    companion object {
        const val EXTRA_OBJECT_ID = "org.yaop.extra.OBJECT_ID"
    }
}
