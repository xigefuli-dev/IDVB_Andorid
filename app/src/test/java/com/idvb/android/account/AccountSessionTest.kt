package com.idvb.android.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountSessionTest {
    private class Storage(var token: String? = "saved") : TokenStore {
        var failSave = false
        override fun load() = token
        override fun save(token: String) { check(!failSave); this.token = token }
        override fun clear() { token = null }
    }
    private class Api : AccountApi {
        var validation: suspend (String) -> ValidatedAccount = { valid() }
        var login: suspend () -> LoginResult = { LoginResult("new-token") }
        override suspend fun authenticate(operation: String, fields: Map<String, String>) = login()
        override suspend fun validate(token: String) = validation(token)
        override suspend fun revoke(token: String) {}
        companion object { fun valid(ttl: Long = 100_000) = ValidatedAccount(AccountIdentity("subject", "用户"), ttl) }
    }
    private var now = 100L
    private var stops = 0
    private val api = Api()
    private val store = Storage()
    private val session = AccountSession(api, store, { now }, { stops++ })

    @Test fun savedTokenNeverAuthorizesUntilServerValidation() = runBlocking {
        assertFalse(session.isAuthorized())
        session.refresh()
        assertTrue(session.isAuthorized())
    }

    @Test fun leaseExpiresWithoutWaitingForNetworkAndTriggersStop() = runBlocking {
        session.refresh()
        now += AccountSession.LEASE_MILLIS
        assertFalse(session.isAuthorized())
        session.expireIfNeeded()
        assertFalse(session.state.value.authorized)
        assertEquals(1, stops)
    }

    @Test fun serverExpiryIsStricterThanLocalLease() = runBlocking {
        api.validation = { Api.valid(1_000) }
        session.refresh()
        now += 1_000
        assertFalse(session.isAuthorized())
    }

    @Test fun banAndUnbanRequireFreshLogin() = runBlocking {
        session.refresh()
        api.validation = { throw AccountFailure(401, "invalid_publish_token", "disabled") }
        session.refresh()
        assertFalse(session.isAuthorized())
        assertNull(store.token)
        assertTrue(stops > 0)
        api.validation = { Api.valid() }
        session.refresh()
        assertFalse(session.isAuthorized())
        session.authenticate("login", emptyMap())
        assertTrue(session.isAuthorized())
    }

    @Test fun networkFailureLocksButKeepsTokenForExplicitRetry() = runBlocking {
        session.refresh()
        api.validation = { error("offline") }
        session.refresh()
        assertFalse(session.isAuthorized())
        assertEquals("saved", store.token)
        api.validation = { Api.valid() }
        session.refresh()
        assertTrue(session.isAuthorized())
    }

    @Test fun lateSuccessCannotUndoLogout() = runBlocking {
        val pending = CompletableDeferred<ValidatedAccount>()
        api.validation = { pending.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        session.logout()
        pending.complete(Api.valid())
        job.join()
        assertFalse(session.isAuthorized())
        assertNull(store.token)
    }

    @Test fun lateRejectionCannotClearNewAccount() = runBlocking {
        val pending = CompletableDeferred<ValidatedAccount>()
        api.validation = { if (it == "saved") pending.await() else Api.valid() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        session.authenticate("login", emptyMap())
        pending.completeExceptionally(AccountFailure(401, "invalid_publish_token", "expired"))
        job.join()
        assertTrue(session.isAuthorized())
        assertEquals("new-token", store.token)
        session.reject("saved")
        assertTrue(session.isAuthorized())
    }

    @Test fun verificationChallengeDoesNotAuthorize() = runBlocking {
        api.login = { LoginResult(message = "verification_required") }
        session.authenticate("login", emptyMap())
        session.refresh()
        assertFalse(session.isAuthorized())
        assertNull(store.token)
    }

    @Test fun failedTokenSaveDoesNotAuthorize() = runBlocking {
        store.failSave = true
        assertTrue(runCatching { session.authenticate("login", emptyMap()) }.isFailure)
        assertFalse(session.isAuthorized())
    }

    @Test fun lateLoginCannotRestoreLoggedOutAccount() = runBlocking {
        val pending = CompletableDeferred<LoginResult>()
        api.login = { pending.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(runCatching { session.authenticate("login", emptyMap()) }.isFailure)
        }
        session.logout()
        pending.complete(LoginResult("late"))
        job.join()
        assertFalse(session.isAuthorized())
        assertNull(store.token)
    }
}
