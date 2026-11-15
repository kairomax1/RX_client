package com.v2ray.ang.handler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * Mozilla's edge answers a non-browser client with an image CAPTCHA. There is
 * no way to read it automatically, so the picture is shown in the app and the
 * characters the user types travel back to the waiting request.
 *
 * The request thread blocks on [ask] while the screen is open; nothing else is
 * waiting on that thread.
 */
object FirefoxVpnCaptcha {

    private const val WAIT_SECONDS = 180L

    /** The image (a `data:image/...` URL) that is waiting for an answer, if any. */
    private val _pendingImage = MutableStateFlow<String?>(null)
    val pendingImage = _pendingImage.asStateFlow()

    private val answers = SynchronousQueue<String>()

    /** Blocks until the screen hands back what the user read, or the wait is over. */
    fun ask(imageDataUrl: String): String? {
        _pendingImage.value = imageDataUrl
        return try {
            val answer = answers.poll(WAIT_SECONDS, TimeUnit.SECONDS)
            if (answer.isNullOrBlank()) null else answer.trim()
        } finally {
            _pendingImage.value = null
            answers.clear()
        }
    }

    /** Called by the screen with the characters the user read. */
    fun submit(text: String) {
        answers.offer(text.trim())
    }

    /** Called when the screen is closed without an answer. */
    fun cancel() {
        answers.offer("")
    }
}
