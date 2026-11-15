package com.nuvio.tv.ui.screens.apps

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings for the Apps page: whether the tab is shown and which apps the user hid.
 * Kept in plain preferences on the device so no extra wiring is needed.
 */
object AppsStore {
    private const val FILE = "meowio_apps"
    private const val KEY_TAB = "tab_visible"
    private const val KEY_HIDDEN = "hidden"

    private val _tabVisible = MutableStateFlow(true)
    val tabVisible: StateFlow<Boolean> = _tabVisible.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _tabVisible.value = prefs.getBoolean(KEY_TAB, true)
        _hidden.value = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()
    }

    fun setTabVisible(context: Context, visible: Boolean) {
        _tabVisible.value = visible
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAB, visible).apply()
    }

    fun setHidden(context: Context, packageName: String, hide: Boolean) {
        val next = if (hide) _hidden.value + packageName else _hidden.value - packageName
        _hidden.value = next
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN, next).apply()
    }
}
