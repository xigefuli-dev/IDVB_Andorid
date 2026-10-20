package com.idvb.android.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface TokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

internal data class AccountIdentity(val subject: String, val displayName: String)
internal data class ValidatedAccount(val identity: AccountIdentity, val remainingMillis: Long)
internal data class LoginResult(val token: String? = null, val message: String = "")
internal interface AccountApi {
    suspend fun authenticate(operation: String, fields: Map<String, String>): LoginResult
    suspend fun validate(token: String): ValidatedAccount
    suspend fun revoke(token: String)
}
internal class AccountFailure(val status: Int, val code: String, message: String) : Exception(message)

data class AccountState(
    val authorized: Boolean = false,
    val displayName: String = "",
    val message: String = "请登录 IDVB 账户。手机与 PC 共用账户及封禁状态。",
    val hasSession: Boolean = false,
)

/** Only live server validation grants a short, monotonic lease. Disk state never grants access. */
internal class AccountSession(
    private val api: AccountApi,
    private val store: TokenStore,
    private val elapsed: () -> Long,
    private val onLocked: () -> Unit,
) {
    private var token: String? = store.load()
    private var generation = 0L
    private var deadline = 0L
    private var validationAttempt = 0L
    private val mutable = MutableStateFlow(AccountState(hasSession = token != null))
    val state = mutable.asStateFlow()

    @Synchronized fun isAuthorized(): Boolean = mutable.value.authorized && elapsed() < deadline
    @Synchronized fun currentToken(): String {
        check(isAuthorized()) { "请先登录并验证有效账户。" }
        return requireNotNull(token)
    }
    @Synchronized fun hasSession() = token != null

    suspend fun authenticate(operation: String, fields: Map<String, String>): String {
        val revision = synchronized(this) {
            ++generation
            token = null
            lock("正在验证账户…")
            store.clear()
            generation
        }
        val response = api.authenticate(operation, fields)
        synchronized(this) {
            check(revision == generation) { "账户操作已取消，请重试。" }
            response.token?.let {
                store.save(it) // A failed persistence operation never unlocks the app.
                token = it
                mutable.value = mutable.value.copy(hasSession = true)
            }
        }
        if (response.token != null) refresh()
        return response.message
    }

    suspend fun refresh() {
        val snapshot = synchronized(this) {
            val value = token ?: return
            Triple(generation, value, ++validationAttempt)
        }
        try {
            val account = api.validate(snapshot.second)
            synchronized(this) {
                if (snapshot.first != generation || snapshot.second != token || snapshot.third != validationAttempt) return
                require(account.remainingMillis > 0) { "登录已过期，请重新登录。" }
                deadline = elapsed() + minOf(LEASE_MILLIS, account.remainingMillis)
                mutable.value = AccountState(true, account.identity.displayName, "账户有效", true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(this) {
                if (snapshot.first != generation || snapshot.second != token || snapshot.third != validationAttempt) return
                val rejected = error is AccountFailure && error.status in listOf(401, 403)
                if (rejected) { token = null; generation++ }
                lock(if (rejected) "登录已失效或账户已被封禁。解封后请重新登录。"
                    else "无法验证账户，请检查网络后重试。")
                if (rejected) store.clear()
            }
        }
    }

    /** A rejected old upload cannot clear a later login. */
    @Synchronized fun reject(expectedToken: String) {
        if (token != expectedToken) return
        token = null
        generation++
        lock("登录已失效或账户已被封禁。解封后请重新登录。")
        store.clear()
    }

    @Synchronized fun expireIfNeeded() {
        if (mutable.value.authorized && elapsed() >= deadline) lock("账户校验已过期，请联网重新验证。")
    }

    suspend fun logout() {
        val previous = synchronized(this) {
            val value = token
            generation++
            token = null
            lock("已退出登录，请登录后继续使用。")
            store.clear()
            value
        }
        if (previous != null) {
            try { api.revoke(previous) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Local access was already revoked; server token has its own expiry. */ }
        }
    }

    private fun lock(message: String) {
        deadline = 0
        mutable.value = AccountState(message = message, hasSession = token != null)
        onLocked()
    }

    companion object { const val LEASE_MILLIS = 60_000L }
}
