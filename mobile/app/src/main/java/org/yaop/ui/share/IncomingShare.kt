package org.yaop.ui.share

import android.net.Uri

sealed interface IncomingShare {
    data class WebLink(val url: String, val initialTitle: String? = null) : IncomingShare
    data class Image(val uri: Uri) : IncomingShare
    data class MultipleImages(val uris: List<Uri>) : IncomingShare
    data class Pdf(val uri: Uri) : IncomingShare
    data class Audio(val uri: Uri) : IncomingShare
    data class Text(val text: String) : IncomingShare
    data object Unsupported : IncomingShare
}
