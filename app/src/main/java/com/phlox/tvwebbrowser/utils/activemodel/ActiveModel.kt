package com.phlox.tvwebbrowser.utils.activemodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class ActiveModel {
  private var _modelScope: CoroutineScope? = null
  val modelScope: CoroutineScope
    get() {
      return _modelScope ?: kotlin.run {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        _modelScope = newScope
        return newScope
      }
    }

  final fun clear() {
    _modelScope?.cancel()
    onClear()
  }

  open fun onClear() {}
}