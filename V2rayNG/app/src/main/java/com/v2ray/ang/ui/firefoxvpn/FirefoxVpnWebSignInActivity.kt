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

/**
 * Mozilla's own sign-in page inside the app.
 *
 * The page brings its own handling of the edge's challenge, the mailed code and
 * an authenticator, so the user simply signs in the way they would in a
 * browser. The page finishes by sending the window to a Mozilla address that
 * carries `code`; that code is what the app redeems for its tokens.
 *
 * Mozilla's page asks only for an address and Mozilla's own password, so the
 * phone's accounts are offered first and the chosen address travels in the
 * page's own address bar: no Google page is involved, and the password stays
 * where Mozilla types it.
 */
class FirefoxVpnWebSignInActivity : AppCompatActivity() {

    private var expectedState: String = ""
    private var startUrl: String = ""
    private lateinit var webView: WebView
    private lateinit var bar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
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
                return catchCode(request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && catchCode(url)) {
                    view.stopLoading()
                }
            }
        }

        AppLog.info(AppLog.Section.Connection, "FxA: opening Mozilla's sign-in page in the app")
        // Mozilla only needs an address and its own password, so the phone's
        // own mail accounts are offered first: no Google page is involved.
        if (!chooseLocalAccount()) webView.loadUrl(startUrl)
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
     * Asks the system for one of the phone's accounts. The system's own picker
     * shows every account even when this app is not allowed to list them.
     * Returns false when there is nothing to ask, so the page is loaded plainly.
     */
    private fun chooseLocalAccount(): Boolean {
        val systemPicker = try {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        } catch (_: Exception) {
            null
        }
        if (systemPicker != null) {
            try {
                startActivityForResult(systemPicker, REQUEST_ACCOUNT)
                return true
            } catch (_: Exception) {
                // Fall through to the plain list, then to the plain page.
            }
        }
        val accounts = localAccounts()
        if (accounts.isEmpty()) {
            AppLog.info(AppLog.Section.Connection, "FxA: no account on this phone; the address is typed on Mozilla's page")
            return false
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.firefox_vpn_local_accounts))
            .setItems(accounts.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                loadWithAddress(accounts[which])
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> webView.loadUrl(startUrl) }
            .show()
        return true
    }

    /** Loads Mozilla's page with that address already in the form. */
    private fun loadWithAddress(email: String) {
        val separator = if (startUrl.contains('?')) '&' else '?'
        val url = startUrl + separator + "email=" +
            java.net.URLEncoder.encode(email, "UTF-8").replace("+", "%20")
        AppLog.info(AppLog.Section.Connection, "FxA: Mozilla's page opens with the phone's account ready")
        webView.loadUrl(url)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ACCOUNT) return
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (resultCode == RESULT_OK && !name.isNullOrEmpty()) {
            loadWithAddress(name)
        } else {
            webView.loadUrl(startUrl)
        }
    }

    // ── The code that ends the sign-in ───────────────────────────

    /** True when the address is Mozilla coming back with the authorization code. */
    private fun catchCode(url: String): Boolean {
        // The code may arrive in the query or after a fragment, and the address
        // may not even be one this view can open: the whole string is read.
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host != "accounts.firefox.com") return false
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
        private const val REQUEST_ACCOUNT = 4101

        private const val BACKGROUND = 0xFF101014.toInt()
        private const val INK = 0xFFF2F2F7.toInt()
        private const val FADED = 0xFFBFBFCA.toInt()
        private const val ACCENT = 0xFFFF8A3D.toInt()

        /** Mozilla shows the phone layout for this one, which fits the window. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"

        fun intent(context: Context, url: String, state: String): Intent =
            Intent(context, FirefoxVpnWebSignInActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_STATE, state)
    }
}
