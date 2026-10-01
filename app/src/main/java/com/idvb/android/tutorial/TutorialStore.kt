package com.idvb.android.tutorial

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TutorialStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("beginner_tutorial_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<TutorialProgress>(prefs.getString("progress", null) ?: "{}")
    }.getOrDefault(TutorialProgress()))
    val state = mutable.asStateFlow()

    @Synchronized fun update(transform: (TutorialProgress) -> TutorialProgress) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        // Persist the entire checkpoint atomically, including practice gestures.
        // apply updates memory immediately and Android flushes it on lifecycle changes.
        prefs.edit().putString("progress", json.encodeToString(next)).apply()
        mutable.value = next
    }

    fun performed(step: TutorialStep, transform: (PracticeState) -> PracticeState) = update {
        it.copy(performed = it.performed + step, practice = transform(it.practice))
    }

    fun recordCheckPassed() {
        val target = System.currentTimeMillis() + 15_000L
        prefs.edit().putLong("cooldown_until", target).apply()
    }

    fun checkCooldownRemaining(): Int {
        val target = prefs.getLong("cooldown_until", 0L)
        val remaining = target - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + 999L) / 1000L).toInt() else 0
    }

    companion object {
        @Volatile private var instance: TutorialStore? = null
        fun get(context: Context): TutorialStore = instance ?: synchronized(this) {
            instance ?: TutorialStore(context.applicationContext).also { instance = it }
        }
    }
}
