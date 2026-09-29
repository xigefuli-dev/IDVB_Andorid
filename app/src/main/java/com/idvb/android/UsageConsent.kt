package com.idvb.android

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Mandatory startup invariant: no migration, tutorial flag or app version may imply consent. */
object UsageConsent {
    const val REVISION = 1
    const val WAIT_MS = 5_000L
    private const val PREFS = "mandatory_usage_consent"
    private const val KEY = "accepted_revision"
    const val TITLE = "软件性质及使用责任声明"
    const val CONFIRM = "我已阅读并确认"
    val statement = """
        Identity Vision Bridge（IDVB）是独立的辅助工具，不属于游戏外挂。本软件不修改游戏数据，不读取或修改游戏进程内存，不通过上述方式干预游戏运行。

        用户应自行遵守游戏运营方的用户协议、管理规则及相关规定。严禁以本软件为由实施、掩饰或为任何违规行为辩解。

        因用户自身行为，包括但不限于使用外挂或其他违规工具、辱骂或骚扰他人、违反游戏规则等，所产生的账号警告、功能限制、停权、封禁及其他损失或争议，由用户自行承担相应责任。本软件及其开发者不为用户自身违规行为承担责任。

        本声明不代表游戏运营方的认可或授权，亦不构成账号不会受到限制或处罚的保证。依法应由相关责任主体承担的责任，不因本声明而免除。

        请完整阅读本声明。等待 5 秒并明确确认后，方可继续使用；未确认时退出，下一次启动仍须确认。
    """.trimIndent()

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) == REVISION

    /** Return true only after durable storage; failed writes never unlock the app. */
    internal fun accept(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.edit().putInt(KEY, REVISION).commit()) return true
        // SharedPreferences updates its in-memory value even if the disk write fails.
        prefs.edit().remove(KEY).commit()
        return false
    }

    fun show(activity: ComponentActivity) {
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val body = TextView(activity).apply {
            text = statement
            textSize = 16f
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(TITLE).setView(scroll)
            .setPositiveButton(CONFIRM, null)
            .setNegativeButton("退出") { _, _ -> activity.finishAndRemoveTask() }
            .setCancelable(false).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        // Keep actions visible even with large fonts or short displays; body remains scrollable.
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * .85f).toInt(),
        )
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false
        val handler = Handler(Looper.getMainLooper())
        var deadline = Long.MAX_VALUE
        val tick = object : Runnable {
            override fun run() {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                button.isEnabled = remaining == 0L
                button.text = if (remaining == 0L) CONFIRM else "$CONFIRM（${(remaining + 999) / 1000} 秒）"
                if (remaining > 0) handler.postDelayed(this, 100)
            }
        }
        button.setOnClickListener {
            if (SystemClock.elapsedRealtime() < deadline) return@setOnClickListener
            if (accept(activity)) {
                dialog.dismiss()
                activity.recreate()
            } else Toast.makeText(activity, "确认保存失败，请重试", Toast.LENGTH_LONG).show()
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                deadline = SystemClock.elapsedRealtime() + WAIT_MS
                tick.run()
            }
            override fun onPause(owner: LifecycleOwner) {
                handler.removeCallbacks(tick)
                deadline = Long.MAX_VALUE
                button.isEnabled = false
            }
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(tick)
                dialog.dismiss()
                owner.lifecycle.removeObserver(this)
            }
        })
    }
}
