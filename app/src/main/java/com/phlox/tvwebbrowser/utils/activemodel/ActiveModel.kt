package com.phlox.tvwebbrowser.utils.activemodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * This class is like ViewModel from Jetpack but better =)
 * It can be used not only with activities and Fragments, but also for example
 * with Services or other Active models (but with manual marking as Needless -
 * @see com.phlox.tvwebbrowser.utils.statemodel.ActiveModelsRepository#markAsNeedless()).
 * This also will survive not only configuration changes but also Activity switching -
 * If you go from one Activity to another and they both accessing the same ActiveModel
 * class then actually they will access the same object what can be good for performance.
 *
 * This named Active Model because I believe that making entire View data models
 * (a.k.a. ViewModels) is not a really good pattern. This lead to data duplication if
 * different views accessing the same data states and this lead to look at View as main
 * logic part of application. Instead I propose to make an new kind of models what will
 * incorporate smaller models, their states and domain actions on them. I see this
 * more memory/performance effective and also more natural because in this case main
 * logic block of application becomes domain data and actions on it.
 */
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