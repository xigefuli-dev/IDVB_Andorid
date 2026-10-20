package com.idvb.android.account

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.StartupRequirements
import com.idvb.android.UsageConsent
import com.idvb.android.UsageRules
import com.idvb.android.keepalive.BootReceiver
import com.idvb.android.overlay.OverlayService
import com.idvb.android.ui.screens.maplist.importIdvmUri
import com.idvb.android.idvm.ImportResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Run only in the dedicated verification application, never against the user's installed app. */
class AccountGateInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val consent get() = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
    private val rules get() = context.getSharedPreferences("mandatory_usage_rules", Context.MODE_PRIVATE)
    private var oldConsent: Int? = null
    private var oldRules: Int? = null
    private var prepared = false

    @Before fun prepare() {
        check(context.packageName == "com.idvb.android.verification") { "Requires the isolated verification APK." }
        check(!AppServices.accounts.state.value.hasSession) { "Do not run against an account in use." }
        oldConsent = consent.all["accepted_revision"] as? Int
        oldRules = rules.all["accepted_revision"] as? Int
        prepared = true
        assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
        assertTrue(rules.edit().putInt("accepted_revision", UsageRules.REVISION).commit())
    }

    @After fun restore() {
        if (!prepared) return
        val a = consent.edit()
        oldConsent?.let { a.putInt("accepted_revision", it) } ?: a.remove("accepted_revision")
        assertTrue(a.commit())
        val b = rules.edit()
        oldRules?.let { b.putInt("accepted_revision", it) } ?: b.remove("accepted_revision")
        assertTrue(b.commit())
    }

    @Test fun acceptedNoticesStillRequireAccountAndRetainExternalImport() {
        assertFalse(StartupRequirements.isAccepted(context))
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("content://com.idvb.test/pending.idvm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertEquals(Intent.ACTION_VIEW, it.intent.action)
                assertFalse(AppServices.accounts.isAuthorized())
            }
            scenario.recreate()
            scenario.onActivity { assertEquals(Intent.ACTION_VIEW, it.intent.action) }
        }
    }

    @Test fun importAndServiceEntrypointsRejectMissingAccount() {
        assertTrue(importIdvmUri(context, Uri.parse("content://com.idvb.test/pending.idvm")).first is ImportResult.Failure)
        val guarded = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = throw AssertionError("Unauthorized service")
            override fun startService(service: Intent): ComponentName? = throw AssertionError("Unauthorized service")
        }
        OverlayService.start(guarded)
        BootReceiver().onReceive(guarded, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test fun installationProofUsesPersistentKeystoreKeyAndFixedWidthSignature() {
        val credentials = AccountCredentials(InstrumentationRegistry.getInstrumentation().context)
        val fields = mapOf("email" to "contract@example.invalid", "password" to "not-a-real-password")
        val first = credentials.prove("login", fields)
        val second = credentials.prove("login", fields)
        assertEquals(first.getJSONObject("device").getString("publicKeySpki"), second.getJSONObject("device").getString("publicKeySpki"))
        assertEquals(64, java.util.Base64.getUrlDecoder().decode(first.getJSONObject("device").getString("signature")).size)
        assertEquals(fields["email"], first.getString("email"))
    }

    @Test fun accountFormsShowLoginRegistrationAndPasswordReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        // Compose exposes virtual accessibility nodes; platform text search need not traverse them.
        fun search(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) search(node.getChild(index), text)?.let { return it }
            return null
        }
        fun find(text: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(100) {
                search(automation.rootInActiveWindow, text)?.let { return it }
                Thread.sleep(50)
            }
            error("Account UI text not found: $text")
        }
        fun click(text: String) {
            var node = find(text)
            while (!node.isClickable) node = node.parent ?: error("No clickable parent for $text")
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun capture(name: String) {
            // Instrumentation runs under the target UID; this emulator cannot write the test APK's UID directory.
            val directory = java.io.File(instrumentation.targetContext.filesDir, "test-evidence/account").apply {
                check(isDirectory || mkdirs()) { "Cannot create private verification evidence directory" }
            }
            val bitmap = requireNotNull(automation.takeScreenshot())
            try {
                java.io.File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            find("IDVB 账户")
            find("密码")
            capture("login")
            click("注册")
            find("昵称")
            find("@qq.com")
            capture("register")
            click("找回密码")
            find("邮箱")
            find("发送验证码")
            capture("reset")
            assertFalse(StartupRequirements.isAccepted(context))
        }
    }
}
