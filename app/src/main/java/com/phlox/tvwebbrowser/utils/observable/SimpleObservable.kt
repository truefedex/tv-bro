package com.phlox.tvwebbrowser.utils.observable

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.reflect.KProperty

/**
 * Just simple and more convenient replacement of java.util.Observable/Observer
 * to implement observer pattern
 */

interface Subscribable<O> {
    val observers: ArrayList<O>
    fun subscribe(observer: O) {
        if (!observers.contains(observer)) {
            observers.add(observer)
        }
    }

    fun subscribe(lifecycleOwner: LifecycleOwner, observer: O) {
        subscribe(lifecycleOwner.lifecycle, observer)
    }

    fun subscribe(lifecycle: Lifecycle, observer: O) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            subscribe(observer)
        }

        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (source.lifecycle.currentState) {
                    Lifecycle.State.INITIALIZED, Lifecycle.State.CREATED -> {
                        unsubscribe(observer)
                    }
                    Lifecycle.State.STARTED, Lifecycle.State.RESUMED -> {
                        subscribe(observer)
                    }
                    Lifecycle.State.DESTROYED -> {
                        lifecycle.removeObserver(this)
                        unsubscribe(observer)
                    }

                }
            }
        })
    }

    fun unsubscribe(observer: O) {
        observers.remove(observer)
    }
}

typealias ValueObserver<T> = (value: T) -> Unit

class ObservableValue<T>(default: T, private val pushOnSubscribe: Boolean = true) : Subscribable<ValueObserver<T>> {
    var value: T = default
        set(value) {
            field = value
            notifyChanged(value)
        }

    override val observers = ArrayList<ValueObserver<T>>()

    private fun notifyChanged(new: T) {
        for (observer in observers) {
            observer(new)
        }
    }

    override fun subscribe(observer: ValueObserver<T>) {
        super.subscribe(observer)
        if (pushOnSubscribe) {
            observer(value)
        }
    }

    operator fun getValue(thisRef: Any, prop: KProperty<*>): T = this.value
    operator fun setValue(thisRef: Any, prop: KProperty<*>, value: T) {this.value = value}
}

typealias EventObserver = () -> Unit

class EventSource: Subscribable<EventObserver> {
    override val observers = ArrayList<EventObserver>()
    
    var wasEmitted: Boolean = false

    fun emit() {
        wasEmitted = true
        for (observer in observers) {
            observer()
        }
    }
}

typealias ParameterizedEventObserver<T> = (T) -> Unit

class ParameterizedEventSource<T>: Subscribable<ParameterizedEventObserver<T>> {
    override val observers = ArrayList<ParameterizedEventObserver<T>>()
    
    fun emit(p: T) {
        for (observer in observers) {
            observer(p)
        }
    }
}