package com.redelf.commons.management

import com.redelf.commons.callback.CallbackOperation
import com.redelf.commons.callback.Callbacks
import com.redelf.commons.execution.Executor
import com.redelf.commons.isNotEmpty
import com.redelf.commons.lifecycle.Initialization
import com.redelf.commons.lifecycle.InitializationPerformer
import com.redelf.commons.lifecycle.LifecycleCallback
import com.redelf.commons.lifecycle.exception.InitializingException
import com.redelf.commons.lifecycle.exception.NotInitializedException
import com.redelf.commons.obtain.Obtain
import com.redelf.commons.persistance.EncryptedPersistence
import com.redelf.commons.reset.Resetable
import timber.log.Timber
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

abstract class DataManagement<T> :

    Management,
    Initialization<EncryptedPersistence>,
    InitializationPerformer,
    Obtain<T?>,
    Resetable
{

    protected abstract val storageKey: String
    protected open val instantiateDataObject: Boolean = false

    private var data: T? = null
    private var storage: EncryptedPersistence? = null
    private val initializing = AtomicBoolean(false)
    private val initCallbacksTag = "Data management initialization"

    private val initCallbacks =
        Callbacks<LifecycleCallback<EncryptedPersistence>>(initCallbacksTag)

    fun initialize(

        callback: LifecycleCallback<EncryptedPersistence>,
        persistence: EncryptedPersistence? = null,

        ) {

        persistence?.let {

            storage = it
        }

        initialize(callback)
    }

    final override fun initialize(callback: LifecycleCallback<EncryptedPersistence>) {

        initCallbacks.register(callback)

        if (initializing.get()) {

            return
        }

        try {

            Executor.MAIN.execute {

                initializing.set(true)

                val store = createStorage()

                data = store.pull(storageKey)

                try {

                    if (initialization()) {

                        onInitializationCompleted()

                    } else {

                        val e = NotInitializedException(who = getWho())
                        onInitializationFailed(e)
                    }

                } catch (e: IllegalStateException) {

                    onInitializationFailed(e)
                }
            }

        } catch (e: RejectedExecutionException) {

            onInitializationFailed(e)
        }
    }

    protected open fun createDataObject(): T? = null

    @Throws(IllegalStateException::class)
    override fun initialization(): Boolean {

        Timber.v("DataManagement :: initialization")
        return true
    }

    override fun isInitialized() = storage != null && !isInitializing()

    override fun isInitializing() = initializing.get()

    @Throws(InitializingException::class, NotInitializedException::class)
    override fun obtain(): T? {

        Initialization.waitForInitialization(

            who = this,
            initLogTag = initCallbacksTag
        )

        if (instantiateDataObject) {

            var current: T? = data

            if (current == null) {

                current = createDataObject()

                current?.let {

                    pushData(current)
                }

                if (current == null) {

                    throw IllegalStateException("Data object creation failed")
                }
            }
        }

        return data
    }

    @Throws(IllegalStateException::class)
    fun takeStorage(): EncryptedPersistence {

        storage?.let {

            return it

        } ?: throw NotInitializedException("Storage")
    }

    @Throws(IllegalStateException::class)
    open fun pushData(data: T) {

        val store = takeStorage()

        this.data = data

        try {

            Executor.MAIN.execute {

                store.push(storageKey, data)
            }

        } catch (e: RejectedExecutionException) {

            Timber.e(e)
        }
    }

    @Throws(IllegalStateException::class)
    override fun reset() {

        val store = takeStorage()

        this.data = null

        try {

            Executor.MAIN.execute {

                store.delete(storageKey)
            }

        } catch (e: RejectedExecutionException) {

            Timber.e(e)
        }
    }

    override fun initializationCompleted(e: Exception?) {

        if (e == null) {

            if (instantiateDataObject) {

                try {

                    var current: T? = obtain()

                    if (current == null) {

                        current = createDataObject()

                        current?.let {

                            pushData(current)
                        }

                        if (current == null) {

                            throw IllegalStateException("Data object creation failed")
                        }
                    }

                } catch (e: IllegalStateException) {

                    initializationCompleted(e)

                } catch (e: IllegalArgumentException) {

                    initializationCompleted(e)
                }
            }
        }

        if (e == null) {

            Timber.v("DataManagement :: initialization completed with success")

        } else {

            Timber.e(e, "DataManagement :: initialization completed with failure")
        }
    }

    protected open fun getWho(): String? = null

    override fun onInitializationFailed(e: Exception) {

        val doOnAllAction = object :
            CallbackOperation<LifecycleCallback<EncryptedPersistence>> {

            override fun perform(callback: LifecycleCallback<EncryptedPersistence>) {

                Timber.e(e)

                storage?.let {

                    callback.onInitialization(true, it)
                }

                if (storage == null) {

                    callback.onInitialization(false)
                }

                initCallbacks.unregister(callback)
            }
        }

        initializing.set(false)
        initCallbacks.doOnAll(doOnAllAction, initCallbacksTag)

        initializationCompleted(e)
    }

    override fun onInitializationCompleted() {

        val doOnAllAction = object :
            CallbackOperation<LifecycleCallback<EncryptedPersistence>> {

            override fun perform(callback: LifecycleCallback<EncryptedPersistence>) {

                storage?.let {

                    callback.onInitialization(true, it)
                }

                if (storage == null) {

                    callback.onInitialization(false)
                }

                initCallbacks.unregister(callback)
            }
        }

        initializing.set(false)
        initCallbacks.doOnAll(doOnAllAction, initCallbacksTag)

        initializationCompleted()
    }

    @Throws(IllegalStateException::class)
    protected fun getData(): T {

        val data = obtain()

        data?.let {

            return it
        }

        val who = getWho()
        val baseMsg = "data is null"

        val msg = if (isNotEmpty(who)) {

            "$who obtained $baseMsg"

        } else {

            "Obtained $baseMsg"
        }

        throw IllegalStateException(msg)
    }

    private fun createStorage(): EncryptedPersistence {

        storage?.let {

            return it
        }

        val store = EncryptedPersistence()
        storage = store
        return store
    }
}