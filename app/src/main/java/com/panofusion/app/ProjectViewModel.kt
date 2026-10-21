package com.panofusion.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@HiltViewModel
class ProjectViewModel @Inject constructor(val store: ProjectStore): ViewModel() {
    val projects = store.dao.observe()
    private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = mutableErrors.asSharedFlow()
    fun execute(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableErrors.emit(e.message ?: "操作失败") }
        }
    }
}
