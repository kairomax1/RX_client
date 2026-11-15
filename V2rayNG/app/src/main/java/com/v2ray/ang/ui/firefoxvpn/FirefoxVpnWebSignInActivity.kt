package com.v2ray.ang.ui.firefoxvpn

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLog
import org.json.JSONObject

/**
 * Mozilla's own sign-in page inside the app.
 *
 * The page brings its own handling of the edge's challenge, the mailed code and
 * an authenticator, so the user simply signs in the way they would in a
 * browser. The page finishes by sending the window to a Mozilla address that
 * carries `code`; that code is what the app redeems for its tokens.
 *
 * Google's own sign-in refuses to run inside another app's window and shows a
 * warning instead, so those addresses are stopped here and the phone's own
 * accounts are offered: the address is typed into Mozilla's page, and the
 * password stays with Mozilla.
 */
class FirefoxVpnWebSignInActivity : AppCompatActivity() {

    private var expectedState: String = ""
    private var accountsOffered = false
    private lateinit var webView: WebView
    private lateinit var bar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        expectedState = intent.getStringExtra(EXTRA_STATE).orEmpty()
        if (startUrl.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        // The window draws under the clock and the battery; everything below is
        // pulled down by the height of that strip so no line hides under it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(buildToolbar())
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        val hint = TextView(this).apply {
            text = getString(R.string.firefox_vpn_web_hint)
            setTextColor(FADED)
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(BACKGROUND)
        }
        root.addView(
            hint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = USER_AGENT
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                bar.progress = progress
                bar.visibility = if (progress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (catchCode(url)) return true
                return leaveForAccounts(url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && catchCode(url)) {
                    view.stopLoading()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                offerAccountsOnce(url)
            }
        }

        AppLog.info(AppLog.Section.Connection, "FxA: opening Mozilla's sign-in page in the app")
        webView.loadUrl(startUrl)
    }

    // ── The strip at the top ─────────────────────────────────────

    private fun buildToolbar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(BACKGROUND)
        }

        val title = TextView(this).apply {
            text = getString(R.string.firefox_vpn_web_title)
            setTextColor(INK)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            flatButton(getString(R.string.firefox_vpn_local_accounts)) { chooseLocalAccount() }
        )
        row.addView(
            flatButton(getString(R.string.firefox_vpn_web_reload)) {
                webView.reload()
            }
        )
        row.addView(
            flatButton(getString(R.string.firefox_vpn_web_close)) {
                setResult(RESULT_CANCELED)
                finish()
            }
        )
        return row
    }

    private fun flatButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(ACCENT)
            setBackgroundColor(0x00000000)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

    // ── Google's addresses, and the phone's own accounts ─────────

    /**
     * Google will not sign in from inside another app and answers with a
     * warning, so the window is kept on Mozilla's page and the address is
     * taken from the accounts already on the phone instead.
     */
    private fun leaveForAccounts(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val isGoogleSignIn = host == "accounts.google.com" ||
            host == "accounts.youtube.com" ||
            (host.endsWith(".google.com") && url.contains("/signin")) ||
            (host.endsWith(".google.com") && url.contains("/o/oauth2"))
        if (!isGoogleSignIn) return false
        AppLog.info(AppLog.Section.Connection, "FxA: Google's own sign-in was skipped; using the phone's accounts")
        chooseLocalAccount()
        return true
    }

    private fun localAccounts(): List<String> {
        val manager = AccountManager.get(this)
        val found = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val lists = runCatching {
            listOf(
                manager.getAccountsByType("com.google"),
                manager.getAccountsByType("com.google.work")
            )
        }.getOrDefault(emptyList())
        for (accounts in lists) {
            for (account: Account in accounts) {
                val name = account.name.orEmpty()
                if (name.isNotEmpty() && seen.add(name)) found.add(name)
            }
        }
        if (found.isEmpty()) {
            val all = runCatching { manager.accounts }.getOrDefault(emptyArray())
            for (account: Account in all) {
                val name = account.name.orEmpty()
                if (name.contains('@') && seen.add(name)) found.add(name)
            }
        }
        return found
    }

    /**
     * On Mozilla's page the accounts on this phone are offered straight away,
     * once, so the address does not have to be typed.
     */
    private fun offerAccountsOnce(url: String?) {
        if (accountsOffered) return
        val host = runCatching { android.net.Uri.parse(url.orEmpty()).host.orEmpty() }.getOrDefault("")
        if (host != "accounts.firefox.com") return
        if (localAccounts().isEmpty()) return
        accountsOffered = true
        webView.postDelayed({ chooseLocalAccount() }, 700)
    }

    private fun chooseLocalAccount() {
        val accounts = localAccounts()
        if (accounts.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.firefox_vpn_no_local_accounts))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.firefox_vpn_local_accounts))
            .setItems(accounts.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                fillAddress(accounts[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Types the chosen address into Mozilla's page, which then asks its password. */
    private fun fillAddress(email: String) {
        val quoted = JSONObject.quote(email)
        webView.evaluateJavascript(FILL_SCRIPT.replace("__EMAIL__", quoted), null)
        AppLog.info(AppLog.Section.Connection, "FxA: the phone's account was put into Mozilla's page")
    }

    // ── The code that ends the sign-in ───────────────────────────

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

        private const val BACKGROUND = 0xFF101014.toInt()
        private const val INK = 0xFFF2F2F7.toInt()
        private const val FADED = 0xFFBFBFCA.toInt()
        private const val ACCENT = 0xFFFF8A3D.toInt()

        /** Mozilla shows the phone layout for this one, which fits the window. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"

        /**
         * Waits for Mozilla's address field, types the address the way the page
         * itself would, and asks it to carry on. React ignores a plain value
         * assignment, so the field's own setter is used and the events fired.
         */
        private const val FILL_SCRIPT = """
            (function () {
              var tries = 0;
              var timer = setInterval(function () {
                tries++;
                var field = document.querySelector('input[type=email], input[name=email]');
                if (field) {
                  var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value').set;
                  setter.call(field, __EMAIL__);
                  field.dispatchEvent(new Event('input', { bubbles: true }));
                  field.dispatchEvent(new Event('change', { bubbles: true }));
                  field.focus();
                  clearInterval(timer);
                  setTimeout(function () {
                    var button = field.form && field.form.querySelector('button[type=submit]');
                    if (!button) button = document.querySelector('button[type=submit]');
                    if (button) button.click();
                  }, 500);
                } else if (tries > 80) {
                  clearInterval(timer);
                }
              }, 250);
            })();
        """

        fun intent(context: Context, url: String, state: String): Intent =
            Intent(context, FirefoxVpnWebSignInActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_STATE, state)
    }
}
