package com.redelf.commons.connectivity.indicator.implementation

import android.content.Context
import com.redelf.commons.application.BaseApplication
import com.redelf.commons.connectivity.indicator.connection.ConnectionAvailableService
import com.redelf.commons.connectivity.indicator.stateful.AvailableStatefulService
import com.redelf.commons.context.ContextAvailability
import com.redelf.commons.creation.instantiation.SingleInstance
import com.redelf.commons.extensions.exec
import com.redelf.commons.extensions.isOnMainThread
import com.redelf.commons.extensions.recordException
import com.redelf.commons.logging.Console
import com.redelf.commons.messaging.firebase.FcmConnectivityHandler
import com.redelf.commons.net.connectivity.ConnectionState
import com.redelf.commons.net.connectivity.ConnectivityHandler
import com.redelf.commons.net.connectivity.ConnectivityStateChanges
import com.redelf.commons.net.connectivity.DefaultConnectivityHandler
import com.redelf.commons.obtain.Obtain
import com.redelf.commons.obtain.Obtainer
import com.redelf.commons.stateful.State
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FCMConnectionAvailabilityService private constructor() :

    ContextAvailability<Context>,
    ConnectionAvailableService()

{

    companion object :

        SingleInstance<ConnectionAvailableService>(),
        Obtainer<AvailableStatefulService<*>>

    {

        override fun instantiate(): ConnectionAvailableService {

            return FCMConnectionAvailabilityService()
        }

        override fun getObtainer(): Obtain<AvailableStatefulService<*>> {

            return object : Obtain<AvailableStatefulService<*>> {

                override fun obtain() = instantiate()
            }
        }
    }

    init {

        withConnectionHandler {

            Console.log("${tag()} Instantiated :: ${hashCode()}")
        }
    }

    // TODO: Make sure that all codebase shared between this and Int.Conn.Availability Service is extracted
    //  to a common class which just instantiates the 'cHandler' - #Availability
    private var cHandler: FcmConnectivityHandler? = null

    override fun identifier() = "FCM connection availability"

    private fun withConnectionHandler(doWhat: (handler: ConnectivityHandler) -> Unit) {

        exec(

            onRejected = { err -> recordException(err) }

        ) {

            if (cHandler == null) {

                cHandler = FcmConnectivityHandler(takeContext())

                val state = if (cHandler?.isNetworkAvailable(takeContext()) == true) {

                    ConnectionState.Connected

                } else {

                    ConnectionState.Disconnected
                }

                setState(state)

                cHandler?.register(connectionCallback)
            }

            cHandler?.let {

                doWhat(it)
            }
        }
    }

    private val connectionCallback = object : ConnectivityStateChanges {

        private val tag = this@FCMConnectionAvailabilityService.tag() +
                " Connection callback ::"

        override fun onStateChanged() {

            Console.log("$tag On state changed")

            this@FCMConnectionAvailabilityService.onStateChanged()
        }

        override fun onState(state: State<Int>) {

            Console.log("$tag On state, calling the change callback")

            this@FCMConnectionAvailabilityService.onStateChanged()
        }

        @Throws(IllegalArgumentException::class, IllegalStateException::class)
        override fun getState(): State<Int> {

            if (isOnMainThread()) {

                throw IllegalArgumentException("Cannot get state from main thread")
            }

            val latch = CountDownLatch(1)
            var state = ConnectionState.Disconnected

            withConnectionHandler {

                if (it.isNetworkAvailable(takeContext())) {

                    state = ConnectionState.Connected

                    latch.countDown()
                }
            }

            try {

                val result = latch.await(30, TimeUnit.SECONDS)

                if (!result) {

                    throw IllegalStateException("Get state timeout")
                }

            } catch (e: Exception) {

                recordException(e)

                throw IllegalStateException("Cannot get state")
            }

            return state
        }

        override fun setState(state: State<Int>) {

            this@FCMConnectionAvailabilityService.setState(state)
        }
    }

    override fun takeContext() = BaseApplication.takeContext()

    override fun terminate() {

        super.terminate()

        val tag = "${tag()} Termination ::"

        Console.log("$tag START")

        withConnectionHandler {

            Console.log("$tag Handler available :: ${it.hashCode()}")

            if (it is DefaultConnectivityHandler) {

                Console.log("$tag Type ok")

                it.unregister(connectionCallback)

                Console.log("$tag END")
            }
        }
    }

    override fun onStateChanged() {

        withConnectionHandler {

            if (it.isNetworkAvailable(takeContext())) {

                onState(ConnectionState.Connected)

            } else {

                onState(ConnectionState.Disconnected)
            }
        }
    }

    override fun onState(state: State<Int>) {

        setState(state)
    }

    override fun notifyStateSubscribers(state: State<Int>) {

        onState(state)
    }
}