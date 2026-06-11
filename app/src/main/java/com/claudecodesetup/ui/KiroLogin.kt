package com.claudecodesetup.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Kiro (free Claude via AWS CodeWhisperer) login — AWS Builder ID device-code flow.
 *
 * Mirrors the bridge's `!kiro login` flow in Kotlin so a UI button can drive it:
 *   begin()  → register OIDC client + start device authorization (returns URL + user code)
 *   poll()   → poll the token endpoint until the user approves in their browser
 *
 * The returned creds JSON ({accessToken,refreshToken,clientId,clientSecret,expiresIn})
 * is stored as the Kiro provider's "API key" — the bridge KIRO engine reads it and
 * auto-refreshes. No Kiro desktop app needed; the user approves in any browser.
 * Constants mirror 9router KIRO_CONFIG. See memory project-kiro-integration.
 */
object KiroLogin {
    private const val REGISTER = "https://oidc.us-east-1.amazonaws.com/client/register"
    private const val DEVICE_AUTH = "https://oidc.us-east-1.amazonaws.com/device_authorization"
    private const val TOKEN = "https://oidc.us-east-1.amazonaws.com/token"
    private const val START_URL = "https://view.awsapps.com/start"
    private const val ISSUER = "https://identitycenter.amazonaws.com/ssoins-722374e8c3c8e6c6"
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class DeviceAuth(
        val clientId: String, val clientSecret: String, val deviceCode: String,
        val userCode: String, val verificationUri: String, val verificationUriComplete: String,
        val interval: Int, val expiresIn: Int
    )

    private fun post(url: String, body: JSONObject): Pair<Int, JSONObject?> {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            val json = try { JSONObject(txt) } catch (e: Exception) { null }
            return resp.code to json
        }
    }

    /** Register the OIDC client + start device authorization. Returns the URL + code to show. */
    suspend fun begin(): DeviceAuth = withContext(Dispatchers.IO) {
        val reg = JSONObject()
            .put("clientName", "kiro-oauth-client")
            .put("clientType", "public")
            .put("scopes", JSONArray(listOf("codewhisperer:completions", "codewhisperer:analysis", "codewhisperer:conversations")))
            .put("grantTypes", JSONArray(listOf("urn:ietf:params:oauth:grant-type:device_code", "refresh_token")))
            .put("issuerUrl", ISSUER)
        val (rc, rj) = post(REGISTER, reg)
        val clientId = rj?.optString("clientId") ?: ""
        val clientSecret = rj?.optString("clientSecret") ?: ""
        if (clientId.isEmpty()) throw Exception("client register failed (HTTP $rc)")

        val da = JSONObject().put("clientId", clientId).put("clientSecret", clientSecret).put("startUrl", START_URL)
        val (dc, dj) = post(DEVICE_AUTH, da)
        if (dj == null || dj.optString("deviceCode").isEmpty())
            throw Exception("device authorization failed (HTTP $dc)")

        DeviceAuth(
            clientId, clientSecret, dj.optString("deviceCode"),
            dj.optString("userCode"),
            dj.optString("verificationUri"),
            dj.optString("verificationUriComplete", dj.optString("verificationUri")),
            dj.optInt("interval", 5),
            dj.optInt("expiresIn", 600)
        )
    }

    /** Poll until the user approves. Returns the creds JSON string (engine-readable), or throws. */
    suspend fun poll(a: DeviceAuth): String = withContext(Dispatchers.IO) {
        var interval = a.interval * 1000L
        val deadline = System.currentTimeMillis() + a.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val body = JSONObject()
                .put("clientId", a.clientId).put("clientSecret", a.clientSecret)
                .put("deviceCode", a.deviceCode)
                .put("grantType", "urn:ietf:params:oauth:grant-type:device_code")
            val (code, j) = post(TOKEN, body)
            val at = j?.optString("accessToken") ?: ""
            if (j != null && at.isNotEmpty()) {
                val expIn = j.optInt("expiresIn", 900)
                return@withContext JSONObject()
                    .put("accessToken", at)
                    .put("refreshToken", j.optString("refreshToken"))
                    .put("clientId", a.clientId)
                    .put("clientSecret", a.clientSecret)
                    .put("expiresIn", expIn)
                    .put("expiresAt", System.currentTimeMillis() + expIn * 1000L)
                    .toString()
            }
            when (j?.optString("error") ?: "") {
                "authorization_pending", "" -> { /* keep polling */ }
                "slow_down" -> interval += 5000
                else -> throw Exception("login failed: ${j?.optString("error")}")
            }
        }
        throw Exception("login expired — try again")
    }
}
