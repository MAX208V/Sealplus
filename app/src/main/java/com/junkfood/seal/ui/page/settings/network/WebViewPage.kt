package com.junkfood.seal.ui.page.settings.network

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.USER_AGENT_STRING

private const val TAG = "WebViewPage"

private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/136.0.0.0 Safari/537.36"

// =============================================================================
// USER-AGENT POLICY (rewritten — the old "anti-detection" layer was the bug)
// =============================================================================
//
// The previous build tried to make Android WebView look like real Chrome in
// three places at once:
//
//   1. User-Agent string  — stripped the " wv" token  (LIE)
//   2. Sec-CH-UA header   — "Android WebView" rewritten to "Google Chrome"
//                                                    (LIE)
//   3. navigator.userAgentData — replaced with a hand-built plain object (LIE)
//
// Those three lies do not agree with each other or with the engine underneath.
// Sites that fingerprint the client (bilibili is one of them as of late 2025)
// do not need to detect the app — they only need to detect the *contradiction*:
//
//   * UA has no "wv" token, yet the client advertises the WebView-only
//     multi-window behaviour and the WebView callbacks.
//   * navigator.userAgentData is a plain Object with no UserAgentData
//     prototype, no getHighEntropyValues brand/version consistency.
//   * Sec-CH-UA claims "Google Chrome" while the JS half reports different
//     brands — real Chrome keeps the two in lockstep.
//
// A client that lies consistently enough to pass all of that still ends up on
// the wrong branch of the site's UA sniffing: bilibili serves its desktop
// layout (which then cannot finish booting inside WebView's memory budget) or
// its risk-control interstitial.
//
// So we no longer lie. We report the truth: the real WebView string, with the
// " wv" token intact, and the real navigator.userAgentData. WebView is a
// well-supported client and sites handle it correctly when told the truth.
// The only deliberate override left is the Douyin desktop session (issue #27),
// where the site itself redirects mobile clients to a download page.
// =============================================================================



// ---------------------------------------------------------------------------
// Cookie data model
// ---------------------------------------------------------------------------

data class Cookie(
    val domain: String = "",
    val name: String = "",
    val value: String = "",
    val includeSubdomains: Boolean = false,
    val path: String = "/",
    val secure: Boolean = true,
    val expiry: Long = 0L,
    val isHttpOnly: Boolean = false,
) {
    constructor(
        url: String,
        name: String,
        value: String,
    ) : this(domain = url.toDomain(), name = name, value = value)

    fun toNetscapeCookieString(): String {
        // Netscape HTTP Cookie File format: exactly 7 tab-delimited fields.
        return buildString {
            append(domain); append('\t')
            append(includeSubdomains.toNetscapeFlag()); append('\t')
            append(path); append('\t')
            append(secure.toNetscapeFlag()); append('\t')
            append(if (expiry > 0L) expiry.toString() else "0"); append('\t')
            append(name); append('\t')
            append(value) // preserved even when empty — empty value is valid
        }
    }

    fun isExpired(): Boolean = expiry > 0L && expiry < System.currentTimeMillis() / 1000L
}

private fun Boolean.toNetscapeFlag(): String = if (this) "TRUE" else "FALSE"

private fun String.toDomain(): String {
    val candidate = if (this.contains("://")) this else "https://$this"
    return runCatching { Uri.parse(candidate).host }.getOrNull()
        ?: this.substringAfter("://").substringBefore("/")
}

private val NAVIGABLE_SCHEMES = setOf("http", "https")

// ---------------------------------------------------------------------------
// WebView page composable
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(cookiesViewModel: CookiesViewModel, onDismissRequest: () -> Unit) {

    val state by cookiesViewModel.stateFlow.collectAsStateWithLifecycle()

    val cookieManager = remember { CookieManager.getInstance() }
    val rawUrl = state.editingCookieProfile.url
    val websiteUrl = remember(rawUrl) {
        when {
            rawUrl.isEmpty() -> rawUrl
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            else -> "https://$rawUrl"
        }
    }

    var pageTitle by remember { mutableStateOf("") }
    var mainWebView by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    cookieManager.setAcceptCookie(true)

    if (websiteUrl.isEmpty()) {
        onDismissRequest()
        return
    }

    BackHandler {
        val webView = mainWebView
        if (webView?.canGoBack() == true) {
            webView.goBack()
        } else {
            cookieManager.flush()
            onDismissRequest()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(pageTitle.ifEmpty { websiteUrl }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        cookieManager.flush()
                        onDismissRequest()
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(id = androidx.appcompat.R.string.abc_action_mode_done),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        cookieManager.flush()
                        onDismissRequest()
                    }) {
                        Text(text = stringResource(id = androidx.appcompat.R.string.abc_action_mode_done))
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        mainWebView = this
                        setBackgroundColor(AndroidColor.TRANSPARENT)

                        settings.run {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            setSupportMultipleWindows(true)
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            allowFileAccess = false
                            allowContentAccess = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            blockNetworkImage = false
                            loadsImagesAutomatically = true
                            javaScriptCanOpenWindowsAutomatically = true
                            mediaPlaybackRequiresUserGesture = false

                            // -----------------------------------------------------------------
                            // Mobile Chrome UA, single-layer change.
                            //
                            // Two earlier strategies both failed on bilibili:
                            //
                            //   A. stocks UA + fake window.chrome + fake Sec-CH-UA
                            //      -> contradictions detected, risk-control page.
                            //   B. stock WebView UA ("wv" token kept) + nothing faked
                            //      -> bilibili serves the page skeleton but withholds the
                            //         text/layout layer; feed shows cover images only.
                            //
                            // This variant changes exactly ONE thing: the User-Agent string
                            // loses the " wv" token, so the site takes its ordinary mobile-
                            // Chrome path. Everything else stays native and self-consistent:
                            //   * Sec-CH-UA metadata is NOT overridden (stock WebView value)
                            //   * navigator.userAgentData is NOT patched
                            //   * no JavaScript is injected on any origin
                            //
                            // Nothing is impersonated beyond the UA string itself, so the
                            // client cannot contradict itself the way strategy A did.
                            // -----------------------------------------------------------------
                            val mobileChromeUA = userAgentString.replace(Regex("\\swv\\b"), "")
                            // Douyin sends mobile clients straight to its download page, so it
                            // is opened as a desktop Chrome session instead (issue #27).
                            val desktopLogin = websiteUrl.contains("douyin.com", ignoreCase = true)
                            val effectiveUA = if (desktopLogin) DESKTOP_CHROME_UA else mobileChromeUA
                            setUserAgentString(effectiveUA)

                            // Persisted for yt-dlp so replayed requests use the same UA the
                            // cookies were issued to.
                            USER_AGENT_STRING.updateString(effectiveUA)
                        }

                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                loadError = null
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                cookieManager.flush()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url?.scheme?.lowercase()
                                if (scheme in NAVIGABLE_SCHEMES) return false
                                return try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW, request.url
                                    )
                                    view.context.startActivity(intent)
                                    true
                                } catch (e: Exception) {
                                    Log.w(TAG, "No handler for scheme=$scheme", e)
                                    true
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    isLoading = false
                                    loadError = error.description?.toString()
                                    Log.w(TAG, "Load error: ${error.errorCode} ${error.description}")
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                error: android.net.http.SslError,
                            ) {
                                Log.w(TAG, "SSL error: ${error.primaryError}")
                                loadError = "Connection is not secure"
                                handler.cancel()
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title ?: ""
                            }

                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                loadProgress = newProgress
                            }

                            override fun onPermissionRequest(request: PermissionRequest) {
                                request.deny()
                            }

                            // Handle window.open() / target="_blank" popups.
                            // Facebook/Instagram open their login flows in popup windows.
                            // Without this, those windows are silently dropped → blank page.
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?,
                            ): Boolean {
                                val helper = WebView(view.context)
                                helper.webViewClient = object : WebViewClient() {
                                    // Catches the FIRST URL (transport window).
                                    // shouldOverrideUrlLoading does NOT fire for the initial
                                    // transport URL — only subsequent navigations trigger it.
                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        if (!url.isNullOrEmpty() && url != "about:blank") {
                                            mainWebView?.loadUrl(url)
                                        }
                                        // Destroy helper after capturing URL — avoid leak.
                                        // post() defers until after this callback returns
                                        // (calling destroy() synchronously inside a WebViewClient
                                        // callback is undefined behaviour).
                                        view.post {
                                            view.stopLoading()
                                            view.destroy()
                                        }
                                    }

                                    // Catches subsequent navigations in the helper.
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        request.url?.toString()
                                            ?.let { mainWebView?.loadUrl(it) }
                                        view.post {
                                            view.stopLoading()
                                            view.destroy()
                                        }
                                        return true
                                    }
                                }
                                val transport =
                                    resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = helper
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        loadUrl(websiteUrl)
                    }
                },
                onRelease = {
                    mainWebView = null
                    it.destroy()
                },
            )

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            loadError?.let { message ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = message,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
    }
}
