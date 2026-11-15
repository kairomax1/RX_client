package com.v2ray.ang.ui.firefoxvpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.FirefoxVpnAuth

/**
 * Mozilla's own sign-in page inside the app.
 *
 * The page brings its own handling of the edge's challenge, the mailed code and
 * an authenticator, so the user simply signs in the way they would in a
 * browser. The page finishes by sending the window to a Mozilla address that
 * carries `code`; that code is what the app redeems for its tokens.
 */
class FirefoxVpnWebSignInActivity : AppCompatActivity() {

    private var expectedState: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        expectedState = intent.getStringExtra(EXTRA_STATE).orEmpty()
        if (startUrl.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
        }

        val hint = TextView(this).apply {
            text = getString(R.string.firefox_vpn_web_hint)
            setTextColor(0xFFBFBFCA.toInt())
            textSize = 13f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = USER_AGENT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return catchCode(request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && catchCode(url)) {
                    view.stopLoading()
                }
            }
        }

        AppLog.info(AppLog.Section.Connection, "FxA: opening Mozilla's sign-in page in the app")
        webView.loadUrl(startUrl)
    }

    /** True when the address is Mozilla coming back with the authorization code. */
    private fun catchCode(url: String): Boolean {
        // The code may arrive in the query or after a fragment, and the address
        // may not even be one this view can open: the whole string is read.
        val code = parameterOf(url, "code").orEmpty()
        if (code.isEmpty()) return false
        val state = parameterOf(url, "state").orEmpty()
        if (expectedState.isNotEmpty() && state.isNotEmpty() && state != expectedState) {
            AppLog.error(AppLog.Section.Connection, "FxA: the sign-in answer belongs to another request")
            return false
        }
        AppLog.info(AppLog.Section.Connection, "FxA: the sign-in page came back with a code")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
        return true
    }

    /** Reads a parameter whether it sits in the query or in the fragment. */
    private fun parameterOf(url: String, name: String): String? {
        val match = Regex("""[?&#]""" + name + "=([^&#]+)").find(url) ?: return null
        return try {
            java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            match.groupValues[1]
        }
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_STATE = "state"
        const val EXTRA_CODE = "code"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"

        fun intent(context: Context, url: String, state: String): Intent =
            Intent(context, FirefoxVpnWebSignInActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_STATE, state)
    }
}
