package com.idvb.android.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AccountHttpApi(private val credentials: AccountCredentials) : AccountApi {
    override suspend fun authenticate(operation: String, fields: Map<String, String>): LoginResult {
        val path = OnlineEndpoints.authPaths[operation] ?: error("不支持的账户操作")
        val body = withContext(Dispatchers.IO) { credentials.prove(operation, fields) }
        val response = request(path, "POST", body)
        if (response.has("token")) {
            val token = response.getString("token")
            require(Regex("[A-Za-z0-9_-]{43}").matches(token)) { "服务器返回了无效会话。" }
            return LoginResult(token, "登录成功")
        }
        val expected = when (operation) {
            "register.start", "password_reset.request" -> "code_sent"
            "login" -> "verification_required"
            else -> null
        }
        if (operation == "password_reset.confirm") {
            require(response.optBoolean("reset")) { "密码重置未确认成功。" }
            return LoginResult(message = "密码已更改，请使用新密码登录。")
        }
        require(expected != null && response.optString("status") == expected) { "服务器未确认账户操作，请重试。" }
        return LoginResult(message = if (expected == "verification_required") "verification_required" else "code_sent")
    }

    override suspend fun validate(token: String): ValidatedAccount {
        val body = request(OnlineEndpoints.TOKEN, "GET", token = token)
        require(body.getBoolean("valid")) { "账户未通过校验。" }
        val user = body.getJSONObject("user")
        val subject = user.getString("subject")
        require(Regex("u_[0-9a-f]{32}").matches(subject))
        val remaining = Instant.parse(body.getString("expiresAt")).toEpochMilli() - System.currentTimeMillis()
        return ValidatedAccount(AccountIdentity(subject, user.getString("displayName")), remaining)
    }

    override suspend fun revoke(token: String) { request(OnlineEndpoints.TOKEN, "DELETE", token = token) }

    private suspend fun request(path: String, method: String, body: JSONObject? = null, token: String? = null): JSONObject = try {
        withTimeout(20_000) { suspendCancellableCoroutine { continuation ->
            val connection = URL(OnlineEndpoints.ORIGIN + path).openConnection() as HttpURLConnection
            // Disconnect on cancellation, including activity destruction during a login request.
            val worker = Thread({
                try {
                    connection.requestMethod = method
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 10_000
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    body?.let {
                        val bytes = it.toString().toByteArray(Charsets.UTF_8)
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { output -> output.write(bytes) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { it.readBytesBounded(64 * 1024) } ?: byteArrayOf()
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (status !in 200..299) throw AccountFailure(status, json?.optString("error").orEmpty(),
                        json?.optString("message")?.takeIf { it.isNotBlank() } ?: "账户服务返回 HTTP $status，请稍后重试。")
                    requireNotNull(json) { "账户服务响应无效。" }
                    if (continuation.isActive) continuation.resume(json)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { connection.disconnect() }
            }, "idvb-account-http").apply { isDaemon = true }
            continuation.invokeOnCancellation { connection.disconnect(); worker.interrupt() }
            worker.start()
        } }
    } catch (_: TimeoutCancellationException) {
        throw AccountFailure(0, "request_timeout", "账户验证超时，请检查网络后重试。")
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "账户响应过大。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
