package org.yaop.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import org.yaop.ui.theme.AppButtonStyle
import org.yaop.ui.theme.AppLabelStyle
import org.yaop.ui.theme.AppColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.yaop.R

const val LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

fun canAuthenticate(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(LOCK_AUTHENTICATORS) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** Opaque lock screen: shows no vault content and prompts for biometrics or the screen lock. */
@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    fun prompt() {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
            .build()
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            },
        ).authenticate(info)
    }
    val available = canAuthenticate(activity)
    // Without any screen lock set on the device the gate cannot work: do not trap the user out.
    LaunchedEffect(Unit) { if (available) prompt() else onUnlocked() }
    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lock_section).uppercase(),
                style = AppLabelStyle, color = AppColors.TextTertiary,
            )
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = { if (available) prompt() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent, contentColor = AppColors.AccentInk,
                ),
                modifier = Modifier.padding(top = 28.dp),
            ) {
                Text(stringResource(R.string.lock_unlock).uppercase(), style = AppButtonStyle)
            }
        }
    }
}
