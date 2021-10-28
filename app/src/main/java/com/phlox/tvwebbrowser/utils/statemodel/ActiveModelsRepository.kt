package com.phlox.tvwebbrowser.utils.statemodel

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import kotlin.reflect.KClass

class ActiveModelsRepository(app: Application) {
  private val holdersMap = HashMap<String, StateModelHolder>()

  private class StateModelHolder(val activeModel: ActiveModel) {
    val users = ArrayList<ActiveModelUser>()
  }

  private val activityLifecycleCallbacks = object: Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(p0: Activity, p1: Bundle?) {
    }

    override fun onActivityStarted(p0: Activity) {
    }

    override fun onActivityResumed(p0: Activity) {
    }

    override fun onActivityPaused(p0: Activity) {
    }

    override fun onActivityStopped(p0: Activity) {
    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
      Log.d("StateModelsRepository", "onActivityDestroyed")
      if (activity !is ActiveModelUser) return
      markAsNeedlessAllStatesUsedBy(activity)
    }
  }

  init {
    app.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
  }

  @Suppress("UNCHECKED_CAST")
  fun <T: ActiveModel>get(clazz: KClass<T>, user: ActiveModelUser): T {
    val className = clazz.qualifiedName ?: throw IllegalStateException("clazz should have name!")
    var modelHolder: StateModelHolder? = holdersMap[className]
    if (modelHolder == null) {
      modelHolder = StateModelHolder(clazz.constructors.first().call())
      holdersMap[className] = modelHolder
    }
    if (!modelHolder.users.contains(user)) {
      modelHolder.users.add(user)
    }
    return modelHolder.activeModel as T
  }

  fun markAsNeedless(activeModelUsed: ActiveModel, byUser: ActiveModelUser) {
    holdersMap[activeModelUsed::class.qualifiedName]?.let {
      if (it.users.remove(byUser)) {
        if (it.users.isEmpty()) {
          holdersMap.remove(activeModelUsed::class.qualifiedName)
          it.activeModel.clear()
        }
      }
    }
  }

  fun markAsNeedlessAllStatesUsedBy(user: ActiveModelUser) {
    for (kv in holdersMap) {
      if (kv.value.users.remove(user)) {
        if (kv.value.users.isEmpty()) {
          holdersMap.remove(kv.key)
          kv.value.activeModel.clear()
        }
      }
    }
  }
}