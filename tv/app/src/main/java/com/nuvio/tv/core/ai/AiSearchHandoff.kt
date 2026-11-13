package com.nuvio.tv.core.ai

import kotlinx.coroutines.flow.MutableStateFlow

/** Carries a title picked in the assistant over to the Search screen, which looks it up. */
object AiSearchHandoff {
    val pendingTitle = MutableStateFlow<String?>(null)
}
