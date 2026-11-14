package com.v2ray.ang.ui.xboxdns

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.DnsProxy
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxRisingNote
import com.v2ray.ang.ui.compose.rxScreenBrush
import kotlinx.coroutines.delay

/**
 * Microsoft's own Xbox site, opened inside the app with every name looked up at the DNS under
 * test. The user signs in on Microsoft's page; the app sees neither the password nor the page
 * content (it is encrypted between the page and Microsoft).
 */
class XboxWebActivity : BaseComponentActivity() {

    private var proxy: DnsProxy? = null
    private var overridden = false

    @Composable
    override fun ScreenContent() {
        val primary = intent.getStringExtra(EXTRA_PRIMARY).orEmpty()
        val secondary = intent.getStringExtra(EXTRA_SECONDARY).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        XboxWebScreen(
            title = name.ifEmpty { primary },
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
            startProxy = { onReady -> startProxy(listOf(primary, secondary).filter { it.isNotEmpty() }, onReady) },
            counts = { (proxy?.resolved?.get() ?: 0) to (proxy?.failed?.get() ?: 0) },
            onBack = { finish() },
        )
    }

    private fun startProxy(servers: List<String>, onReady: () -> Unit) {
        try {
            val started = DnsProxy(servers)
            val port = started.start()
            proxy = started
            val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
            ProxyController.getInstance().setProxyOverride(config, { it.run() }) { runOnUiThread(onReady) }
            overridden = true
            AppLog.info(AppLog.Section.Other, "Xbox page opened with DNS ${servers.joinToString()}")
        } catch (e: Exception) {
            AppLog.error(AppLog.Section.Other, "Xbox page could not be set up: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (overridden) runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }) {} }
        proxy?.let {
            AppLog.info(AppLog.Section.Other, "Xbox page closed: ${it.resolved.get()} names answered, ${it.failed.get()} not")
            it.stop()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PRIMARY = "primary"
        const val EXTRA_SECONDARY = "secondary"
        const val EXTRA_NAME = "name"
        const val START_URL = "https://www.xbox.com/"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun XboxWebScreen(
    title: String,
    supported: Boolean,
    startProxy: (onReady: () -> Unit) -> Unit,
    counts: () -> Pair<Int, Int>,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var answered by remember { mutableIntStateOf(0) }
    var unanswered by remember { mutableIntStateOf(0) }
    var noteTick by remember { mutableIntStateOf(0) }
    val signedOutNote = stringResource(R.string.xbox_web_signed_out)

    LaunchedEffect(Unit) {
        if (supported) startProxy { ready = true }
        while (true) {
            val (yes, no) = counts()
            answered = yes
            unanswered = no
            delay(800)
        }
    }
    BackHandler {
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = stringResource(R.string.log_back),
                        tint = colors.onSurface,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = stringResource(R.string.xbox_web_title, title),
                        style = TextStyle(fontFamily = RxPromptFont, fontSize = 18.sp),
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.xbox_web_counts, answered.toString(), unanswered.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.xbox_web_sign_out),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, colors.onSurface.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .clickable {
                            CookieManager.getInstance().removeAllCookies(null)
                            web?.clearCache(true)
                            web?.loadUrl(XboxWebActivity.START_URL)
                            noteTick++
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.onSurface.copy(alpha = 0.08f))) {
                if (progress < 1f) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(3.dp).background(colors.onSurface.copy(alpha = 0.8f)))
                }
            }
            when {
                !supported -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    RxPromptMessage(stringResource(R.string.xbox_web_unsupported), fontSize = 19)
                }
                !ready -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    RxPromptMessage(stringResource(R.string.xbox_web_preparing), fontSize = 19)
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportMultipleWindows(false)
                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                }
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            loadUrl(XboxWebActivity.START_URL)
                            web = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
        RxRisingNote(text = signedOutNote, tick = noteTick, calm = true)
    }
}
