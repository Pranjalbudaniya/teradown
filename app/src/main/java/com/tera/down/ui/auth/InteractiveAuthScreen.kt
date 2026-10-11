package com.tera.down.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tera.down.ui.theme.StatusError

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveAuthScreen(
    url: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InteractiveAuthViewModel = viewModel(factory = InteractiveAuthViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(url) {
        viewModel.initialize(url)
    }

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            onNavigateBack()
        }
    }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    val navigationGuard = remember { NavigationGuardPolicy() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Browser Verification",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.isValidating) "Validating session..." else "Log in or solve challenge to continue",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.onCancel()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel verification"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            webViewInstance?.evaluateJavascript(
                                """
                                (function() {
                                    var loginBtn = document.querySelector('.login-btn, [class*="login"], [class*="sign-in"], [class*="signin"], a[href*="login"], button[class*="login"], .header-login');
                                    if (loginBtn) {
                                        loginBtn.click();
                                    } else {
                                        window.scrollTo({ left: 1280, top: 0, behavior: 'smooth' });
                                    }
                                })();
                                """.trimIndent(),
                                null
                            )
                        },
                        enabled = webViewInstance != null && !uiState.isValidating
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Sign In",
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(
                        onClick = { webViewInstance?.zoomOut() },
                        enabled = webViewInstance != null
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Zoom out"
                        )
                    }
                    IconButton(
                        onClick = { webViewInstance?.zoomIn() },
                        enabled = webViewInstance != null
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom in"
                        )
                    }
                    TextButton(
                        onClick = {
                            val wv = webViewInstance
                            val current = wv?.url ?: uiState.currentUrl

                            // Requirement 2 & 4: Inspect cookies for the validated Terabox URL and active mirrors
                            val targetUri = try { Uri.parse(current) } catch (_: Exception) { null }
                            val targetHost = targetUri?.host?.lowercase() ?: "www.terabox.app"
                            val teraboxDomainUrl = if (targetHost.contains("terabox") || targetHost.contains("1024tera") || targetHost.contains("dubox")) {
                                "https://$targetHost"
                            } else {
                                "https://www.terabox.app"
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.flush()

                            val candidateUrls = listOfNotNull(
                                teraboxDomainUrl,
                                current.takeIf { it.isNotBlank() },
                                "https://www.terabox.app",
                                "https://1024tera.com",
                                "https://www.terabox.com",
                                "https://terabox.com"
                            ).distinct()

                            val allCookiePairs = candidateUrls.flatMap { url ->
                                cookieManager.getCookie(url)?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                            }.distinctBy { it.substringBefore("=").trim().lowercase() }
                            val cookies = allCookiePairs.joinToString("; ").ifBlank {
                                cookieManager.getCookie(current) ?: cookieManager.getCookie(teraboxDomainUrl)
                            }

                            val ua = wv?.settings?.userAgentString ?: ""
                            viewModel.onDone(teraboxDomainUrl, cookies, ua)
                        },
                        enabled = !uiState.isValidating
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (uiState.isValidating) "Checking..." else "Done",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Loading or Validating Progress Bar
            if (uiState.isLoading || uiState.isValidating) {
                LinearProgressIndicator(
                    progress = { if (uiState.isValidating) 0.8f else uiState.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Info banner explaining verification state
            Card(
                shape = RoundedCornerShape(0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (uiState.isValidating) {
                            "Running session validation check with Terabox servers..."
                        } else {
                            uiState.infoMessage ?: "Complete login or human verification below, then tap Done."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Requirement 4 & 5: Clear Google Sign-In Policy Notice without bypassing Google restrictions
            if (uiState.isGoogleSignInBlocked) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Google Sign-In Policy Notice",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Google restricts sign-in inside embedded app WebViews for security. To continue in TeraDown, please log in with Terabox Email/Password, Phone number, or QR Code.\n\nYou may also open the page in your normal browser, though external browser cookies cannot be imported into TeraDown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.currentUrl))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Open in Browser", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = {
                                    webViewInstance?.loadUrl("https://www.terabox.com/main")
                                    viewModel.onDismissGoogleSignInNotice()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Terabox Login", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Error banner with Retry action (Requirement 7 & 8)
            if (uiState.errorMessage != null && !uiState.isGoogleSignInBlocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusError.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.isNetworkError) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        tint = StatusError,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusError,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.canRetry) {
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { viewModel.retryValidation() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (uiState.isNetworkError || uiState.canRetry) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { viewModel.forceAcceptSession() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Use Anyway", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Top-level Android WebView with Floating Zoom Controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // 1. Desktop mode configuration (Layers A per section 18.3)
                            DesktopWebViewConfigurator.configureWebView(this)

                            // 2. Navigation guard (Layer B per section 18.4)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    return when (val action = navigationGuard.evaluateUrl(reqUrl)) {
                                        is NavigationAction.Allow -> false
                                        is NavigationAction.BlockAndStay -> true
                                        is NavigationAction.LoadFallback -> {
                                            view?.loadUrl(action.fallbackUrl)
                                            true
                                        }
                                        is NavigationAction.MaxPromoHitsExceeded -> {
                                            viewModel.onMaxPromoHits()
                                            true
                                        }
                                    }
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    url?.let {
                                        viewModel.onUrlChanged(it)
                                        if (it.contains("accounts.google.com", ignoreCase = true)) {
                                            viewModel.onGoogleSignInBlocked()
                                        }
                                    }
                                    viewModel.onProgressChanged(10)
                                    DesktopWebViewConfigurator.enableUnrestrictedZoom(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    url?.let {
                                        viewModel.onUrlChanged(it)
                                        if (it.contains("accounts.google.com", ignoreCase = true)) {
                                            viewModel.onGoogleSignInBlocked()
                                        }
                                    }
                                    viewModel.onProgressChanged(100)
                                    DesktopWebViewConfigurator.enableUnrestrictedZoom(view)

                                    // Check for Google blocked browser message (Requirement 4 & 5)
                                    view?.evaluateJavascript(
                                        "(function(){ try { var t = document.body ? document.body.innerText : ''; return (t.includes('disallowed_useragent') || t.includes('This browser or app may not be secure')) ? 'blocked' : ''; } catch(e) { return ''; } })()"
                                    ) { res ->
                                        if (res?.contains("blocked") == true) {
                                            viewModel.onGoogleSignInBlocked()
                                        }
                                    }

                                    // Bounded read-only JS inspection to detect app promo overlays (Section 18.5)
                                    view?.evaluateJavascript(
                                        "(function(){ try { return document.body ? document.body.innerText.substring(0, 1000) : ''; } catch(e) { return ''; } })()"
                                    ) { result ->
                                        if (webViewInstance != null && !result.isNullOrBlank()) {
                                            val cleanText = result.removeSurrounding("\"").lowercase()
                                            val hasPromo = com.tera.down.feature.resolver.classification.TeraboxResponseClassifier.DEFAULT_APP_PROMO_MARKERS.any { cleanText.contains(it) }
                                            val hasFileList = listOf("server_filename", "dlink", "shareid", "share_id", "fs_id", "category").any { cleanText.contains(it) }
                                            if (hasPromo && !hasFileList) {
                                                viewModel.onPromoDetected()
                                            }
                                        }
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    viewModel.onProgressChanged(newProgress)
                                    if (newProgress > 50) {
                                        DesktopWebViewConfigurator.enableUnrestrictedZoom(view)
                                    }
                                }
                            }

                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating Zoom Controls Overlay
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        IconButton(
                            onClick = { webViewInstance?.zoomIn() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = "Zoom in",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { webViewInstance?.zoomOut() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomOut,
                                contentDescription = "Zoom out",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript(
                                    "window.scrollBy({ left: 350, behavior: 'smooth' });",
                                    null
                                )
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Pan right",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript(
                                    "window.scrollBy({ left: -350, behavior: 'smooth' });",
                                    null
                                )
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "Pan left",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript(
                                    "window.scrollTo({ left: 0, top: 0, behavior: 'smooth' });",
                                    null
                                )
                                var count = 0
                                while (webViewInstance?.zoomOut() == true && count < 10) {
                                    count++
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitScreen,
                                contentDescription = "Reset zoom / overview",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Lifecycle clean up
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.stopLoading()
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
}
