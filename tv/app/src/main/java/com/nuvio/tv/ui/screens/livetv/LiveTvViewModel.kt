package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.livetv.LiveTvController
import com.nuvio.tv.data.repository.LiveTvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    repository: LiveTvRepository
) : ViewModel() {
    val controller: LiveTvController = repository.controller
}
