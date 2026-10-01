package com.idvb.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** A single passive window with newest-first cards; expiry owns a card, never the window. */
class OverlayNotifications(private val context: Context, private val screenSize: () -> Pair<Int, Int>) {
    private val handler = Handler(Looper.getMainLooper())
    private val window = OverlayWindowManager(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private data class Card(val id: Long, val view: TextView, var expiry: Runnable? = null)
    private val cards = mutableListOf<Card>()
    private var sequence = 0L
    private var hidden = false
    private var closed = false
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun show(message: String, durationMs: Long = 4_000L): Long {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || !com.idvb.android.UsageConsent.isAccepted(context)) return -1
        val text = TextView(context).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(Color.rgb(28, 35, 43)); cornerRadius = dp(10).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val card = Card(++sequence, text)
        cards.add(0, card)
        column.addView(text, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        while (cards.size > 3) remove(cards.last())
        update(card.id, message, durationMs)
        position()
        // Reattach only if not already attached, putting it above older application windows.
        column.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (!window.isAdded()) {
            window.add(column, locked = true)
        } else {
            window.update(locked = true)
        }
        return card.id
    }

    /** A late update can change only its own card; it cannot cover newer notifications. */
    fun update(id: Long, message: String, durationMs: Long = 4_000L): Boolean {
        val card = cards.firstOrNull { it.id == id } ?: return false
        card.expiry?.let(handler::removeCallbacks)
        card.view.text = message
        card.view.contentDescription = message
        card.expiry = if (durationMs > 0) Runnable { remove(card) }.also { handler.postDelayed(it, durationMs) } else null
        return true
    }

    fun setHidden(value: Boolean) {
        hidden = value
        column.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun position() {
        val (width, height) = screenSize()
        window.width = minOf(dp(350), (width - dp(24)).coerceAtLeast(1))
        window.height = WindowManager.LayoutParams.WRAP_CONTENT
        window.x = ((width - window.width) / 2).coerceAtLeast(0)
        window.y = minOf(dp(64), height / 5)
        window.update()
    }

    private fun remove(card: Card) {
        card.expiry?.let(handler::removeCallbacks)
        if (!cards.remove(card)) return
        column.removeView(card.view)
        if (cards.isEmpty()) window.remove()
    }

    fun close() {
        closed = true
        cards.toList().forEach(::remove)
        window.remove()
    }

    internal fun messages(): List<String> = cards.map { it.view.text.toString() }
}
