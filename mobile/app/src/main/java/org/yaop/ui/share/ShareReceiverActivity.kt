package org.yaop.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.yaop.YaopApp
import org.yaop.MainActivity
import org.yaop.R
import org.yaop.ui.common.extractUrlFromText
import org.yaop.ui.theme.YaopTheme

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as YaopApp
        val repository = app.repository
        val incomingShare = parseIncomingIntent(intent)

        setContent {
            YaopTheme {
                ShareReceiverSheet(
                    incomingShare = incomingShare,
                    repository = repository,
                    onDismiss = { finish() },
                    onSaved = { savedObj, openImmediately ->
                        Toast.makeText(this, R.string.share_saved_toast, Toast.LENGTH_SHORT).show()
                        if (openImmediately) {
                            val openIntent = Intent(this, MainActivity::class.java).apply {
                                putExtra(MainActivity.EXTRA_OBJECT_ID, savedObj.id)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(openIntent)
                        }
                        finish()
                    },
                )
            }
        }
    }

    private fun parseIncomingIntent(intent: Intent?): IncomingShare {
        if (intent == null) return IncomingShare.Unsupported

        val action = intent.action
        val type = intent.type.orEmpty()

        // 1. Process Highlighted Text (Floating context menu in Chrome, Books, etc.)
        if (action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            if (text.isBlank()) return IncomingShare.Unsupported
            val extractedUrl = extractUrlFromText(text)
            return if (extractedUrl != null) {
                IncomingShare.WebLink(url = extractedUrl)
            } else {
                IncomingShare.Text(text = text)
            }
        }

        // 2. Share Multiple Items
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                if (type.startsWith("image/")) {
                    return IncomingShare.MultipleImages(uris = uris)
                }
                return IncomingShare.Image(uri = uris.first())
            }
        }

        // 3. Share Single Item
        if (action == Intent.ACTION_SEND) {
            // Check text payload first (often contains web links or raw notes)
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()

            if (sharedText.isNotBlank()) {
                val extractedUrl = extractUrlFromText(sharedText)
                if (extractedUrl != null) {
                    val rawTitle = sharedSubject?.takeIf { it.isNotBlank() }
                        ?: sharedText.substringBefore(extractedUrl).trim().takeIf { it.isNotBlank() }
                    return IncomingShare.WebLink(url = extractedUrl, initialTitle = rawTitle)
                }
                if (type.startsWith("text/") || type.isBlank()) {
                    return IncomingShare.Text(text = sharedText)
                }
            }

            // Check stream URI payload
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (streamUri != null) {
                val effectiveMime = (type.takeIf { it.isNotBlank() }
                    ?: contentResolver.getType(streamUri).orEmpty()).lowercase()

                return when {
                    effectiveMime.startsWith("image/") -> IncomingShare.Image(streamUri)
                    effectiveMime == "application/pdf" || streamUri.toString().endsWith(".pdf", ignoreCase = true) ->
                        IncomingShare.Pdf(streamUri)
                    effectiveMime.startsWith("audio/") -> IncomingShare.Audio(streamUri)
                    else -> IncomingShare.Unsupported
                }
            }
        }

        return IncomingShare.Unsupported
    }
}
