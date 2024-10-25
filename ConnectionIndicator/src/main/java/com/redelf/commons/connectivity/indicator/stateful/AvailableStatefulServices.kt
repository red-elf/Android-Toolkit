package com.redelf.commons.connectivity.indicator.stateful

import com.redelf.commons.connectivity.indicator.AvailableService
import com.redelf.commons.lifecycle.TerminationAsync
import com.redelf.commons.lifecycle.TerminationSynchronized
import com.redelf.commons.logging.Console
import com.redelf.commons.net.connectivity.ConnectionState
import com.redelf.commons.stateful.GetState
import com.redelf.commons.stateful.State
import com.redelf.commons.stateful.Stateful
import java.util.concurrent.ConcurrentHashMap

class AvailableStatefulServices
@Throws(IllegalArgumentException::class)
constructor(

    builder: AvailableStatefulServicesBuilder

) : AvailableService, TerminationAsync, GetState<Int> {

    private class LocalStateful(val service: AvailableStatefulService) : Stateful {

        /*
         * FIXME: Fix callbacks - #Availability
         */

        private val tag = "Local stateful :: ${service::class.simpleName} ${service.hashCode()} ::"

        fun logRegistered() {

            Console.log("$tag Registered")
        }

        override fun onStateChanged() {

            Console.log("$tag Changed :: State")
        }

        override fun getState(): State<Int> {

            val state = service.getState()

            Console.log("$tag Get :: State = $state")

            return state
        }

        override fun setState(state: State<Int>) {

            Console.log("$tag Set :: State = $state")

            service.setState(state)
        }

        override fun onState(state: State<Int>) {

            Console.log("$tag On :: State = $state")
        }
    }

    private val services:
            ConcurrentHashMap<AvailableStatefulService, Stateful> = ConcurrentHashMap()

    init {

        services.clear()

        builder.build().forEach {

            addService(it)
        }

        if (services.isEmpty()) {

            throw IllegalArgumentException("No services provided")
        }
    }

    fun addService(service: AvailableStatefulService) {

        val listener = LocalStateful(service)

        services[service] = listener

        service.register(listener)

        listener.logRegistered()
    }

    fun removeService(service: AvailableStatefulService) {

        services.remove(service)?.let {

            service.unregister(service)
        }
    }

    fun hasService(service: AvailableService): Boolean {

        return services.contains(service)
    }

    fun getServices(): List<AvailableService> {

        return services.keys().toList()
    }

    override fun getState(): State<Int> {

        if (services.isEmpty()) {

            return ConnectionState.Unavailable
        }

        var failed = 0

        services.forEach { (_, service) ->

            if (service.getState() != ConnectionState.Connected) {

                failed++
            }
        }

        if (failed == 0) {

            return ConnectionState.Connected
        }

        if (failed == services.size) {

            return ConnectionState.Disconnected
        }

        return ConnectionState.Warning
    }

    override fun terminate() {

        services.forEach {

            if (it is TerminationAsync) {

                it.terminate()
            }

            if (it is TerminationSynchronized) {

                it.terminate()
            }
        }

        services.clear()
    }
}