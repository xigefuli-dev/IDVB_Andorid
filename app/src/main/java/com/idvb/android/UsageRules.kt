package com.idvb.android

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Separate from app versions, tutorial progress and the responsibility statement. */
object UsageRules {
    const val REVISION = 1
    internal const val PREFS = "mandatory_usage_rules"
    internal const val KEY = "accepted_revision"
    const val TITLE = "IDVB 使用规则（请认真阅读）"
    const val CONFIRMATION = "我已了解并会遵守使用规则"
    const val CONFIRM = "确认"
    val statement = """
        IDVB 由开发者利用业余时间免费开发和维护。我们很乐意让它变得更好，但需要说明以下几点：

        1、免费提供，不包含一对一答疑服务。开发者没有义务解答每一位用户的每一个问题。
        遇到问题，请走标准反馈渠道：设置 - 问题反馈（如果无法在线反馈，需要导出分享包发送至交流群内或是官方邮箱，见末尾）

        2、提交时请附上机型、系统版本、IDVB 版本号和复现步骤。

        3、无法复现或信息不足的问题，我们不会处理。只描述“用不了”“闪退了”而没有具体信息的反馈，会被直接关闭。

        4、官方群不是客服渠道。在群内直接提问的问题，开发者可能不会回复，也不保证跟进。

        5、群内严禁询问新手教程中已讲解过的内容。
        IDVB 内置了完整的引导教程，并设有答题检测，答错必须重新学习。我们已经尽最大努力确保每位用户都能学会基本操作。
        跳过教程、敷衍答题导致不会使用，责任完全在用户本人。开发者和管理员不会为此提供任何额外帮助。
        在群内询问教程已涵盖的问题，一经发现将受到处罚，包括但不限于禁言、直接移出群聊等。不予警告，不接受解释。
        教程可随时在【首页 > 新手教程】中重新查看。
    """.trimIndent()
    const val FOOTER = "邮箱：idvb.team@icloud.com\n—— IDVB 团队 · 总裁"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) == REVISION

    /** Validate again at the storage boundary; a failed disk write never unlocks features. */
    internal fun accept(context: Context, confirmation: String): Boolean {
        if (!UsageConsent.isAccepted(context) || confirmation != CONFIRMATION) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.edit().putInt(KEY, REVISION).commit()) return true
        prefs.edit().remove(KEY).commit()
        return false
    }

    fun show(activity: ComponentActivity) {
        if (!UsageConsent.isAccepted(activity)) {
            UsageConsent.show(activity)
            return
        }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        fun label(value: String) = TextView(activity).apply {
            text = value
            textSize = 16f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val input = EditText(activity).apply {
            id = R.id.usage_rules_confirmation
            hint = "请输入上方指定文字"
            contentDescription = "使用规则确认文字"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            // Unconfirmed text is never treated as a saved confirmation after recreation.
            isSaveEnabled = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(label(statement))
            addView(label(FOOTER))
            addView(label("请在下方输入框中输入以下文字以继续：\n“$CONFIRMATION”"))
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(TITLE).setView(scroll)
            .setPositiveButton(CONFIRM, null)
            .setNegativeButton("退出") { _, _ -> activity.finishAndRemoveTask() }
            .setCancelable(false).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setLayout(MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * .85f).toInt())
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        // Keep the opening view at the beginning of the rules instead of focusing the field.
        scroll.isFocusableInTouchMode = true
        scroll.requestFocus()
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false
        input.doAfterTextChanged { button.isEnabled = it?.toString() == CONFIRMATION }
        button.setOnClickListener {
            if (input.text.toString() != CONFIRMATION) return@setOnClickListener
            if (accept(activity, input.text.toString())) {
                dialog.dismiss()
                activity.recreate()
            } else Toast.makeText(activity, "确认保存失败，请重试", Toast.LENGTH_LONG).show()
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialog.dismiss()
                owner.lifecycle.removeObserver(this)
            }
        })
    }
}
