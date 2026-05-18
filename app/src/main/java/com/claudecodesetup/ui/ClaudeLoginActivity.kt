package com.claudecodesetup.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class ClaudeLoginActivity : ComponentActivity() {

    companion object {
        private const val CLIENT_ID     = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val AUTH_URL      = "https://claude.com/cai/oauth/authorize"
        private const val TOKEN_URL     = "https://platform.claude.com/v1/oauth/token"
        private const val REDIRECT_URI  = "https://platform.claude.com/oauth/code/callback"
        private const val SCOPES        = "org:create_api_key user:profile user:inference " +
                                          "user:sessions:claude_code user:mcp_servers user:file_upload"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (verifier, challenge) = generatePkce()
        val state    = generateState()
        val authUrl  = buildAuthUrl(challenge, state)

        setContent {
            var phase by remember { mutableStateOf("webview") } // webview | exchanging | error
            var errorMsg by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF08041A))
            ) {
                when (phase) {
                    "webview" -> {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled  = true
                                    settings.domStorageEnabled  = true
                                    settings.databaseEnabled    = true
                                    // Remove the "wv" WebView marker so Google OAuth doesn't block us
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/125.0.6422.165 Mobile Safari/537.36"
                                    // Allow cross-domain cookies (required for Google sign-in)
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    // Handle any popup windows Google might open
                                    webChromeClient = WebChromeClient()

                                    fun handleCallbackUrl(uri: Uri): Boolean {
                                        val url = uri.toString()
                                        if (url.startsWith(REDIRECT_URI) ||
                                            url.contains("oauth/code/success") ||
                                            url.contains("oauth/code/callback")
                                        ) {
                                            val code = uri.getQueryParameter("code")
                                            if (code != null && phase == "webview") {
                                                phase = "exchanging"
                                                scope.launch {
                                                    try {
                                                        val tokens = exchangeCode(code, verifier)
                                                        writeCredentials(tokens)
                                                        setResult(Activity.RESULT_OK)
                                                        finish()
                                                    } catch (e: Exception) {
                                                        errorMsg = e.message ?: "Token exchange failed"
                                                        phase = "error"
                                                    }
                                                }
                                                return true
                                            }
                                        }
                                        return false
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest
                                        ): Boolean = handleCallbackUrl(request.url)

                                        // onPageStarted catches redirects that shouldOverrideUrlLoading misses
                                        override fun onPageStarted(
                                            view: WebView, url: String, favicon: android.graphics.Bitmap?
                                        ) {
                                            super.onPageStarted(view, url, favicon)
                                            handleCallbackUrl(Uri.parse(url))
                                        }
                                    }
                                    loadUrl(authUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Back button overlay
                        Text(
                            "← Cancel",
                            color = Color(0xFF60A5FA),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(14.dp)
                                .clickable {
                                    setResult(Activity.RESULT_CANCELED)
                                    finish()
                                }
                        )
                    }

                    "exchanging" -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF60A5FA), strokeWidth = 2.dp)
                            Spacer(Modifier.height(16.dp))
                            Text("Completing login…", color = Color.White,
                                fontFamily = DmSansFamily, fontSize = 15.sp)
                        }
                    }

                    "error" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Login failed", color = Color(0xFFEF4444),
                                fontFamily = DmSansFamily, fontSize = 18.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(errorMsg, color = Color(0xFF9CA3AF),
                                fontFamily = DmSansFamily, fontSize = 13.sp)
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "← Try again",
                                color = Color(0xFF60A5FA),
                                fontFamily = DmSansFamily,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable {
                                    setResult(Activity.RESULT_CANCELED)
                                    finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildAuthUrl(challenge: String, state: String): String =
        Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
            .toString()

    private suspend fun exchangeCode(code: String, verifier: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("grant_type",    "authorization_code")
                put("code",          code)
                put("redirect_uri",  REDIRECT_URI)
                put("client_id",     CLIENT_ID)
                put("code_verifier", verifier)
            }.toString().toRequestBody("application/json".toMediaType())

            val response = httpClient.newCall(
                Request.Builder()
                    .url(TOKEN_URL)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()

            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful)
                throw Exception("HTTP ${response.code}: $bodyStr")
            JSONObject(bodyStr)
        }

    private fun writeCredentials(tokens: JSONObject) {
        val claudeDir = File(filesDir, ".claude").also { it.mkdirs() }
        val scopeStr  = tokens.optString("scope", SCOPES)
        val scopeArr  = JSONArray(scopeStr.trim().split(" "))
        val expiresIn = tokens.optLong("expires_in", 3600L)

        val credentials = JSONObject().apply {
            put("claudeAiOauth", JSONObject().apply {
                put("accessToken",       tokens.optString("access_token"))
                put("refreshToken",      tokens.optString("refresh_token", ""))
                put("expiresAt",         System.currentTimeMillis() + expiresIn * 1000L)
                put("scopes",            scopeArr)
                put("subscriptionType",  tokens.opt("subscription_type"))
                put("rateLimitTier",     tokens.opt("rate_limit_tier"))
            })
        }
        File(claudeDir, ".credentials.json").writeText(credentials.toString())
    }

    private fun generatePkce(): Pair<String, String> {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier  = Base64.encodeToString(
            verifierBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return verifier to challenge
    }

    private fun generateState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
