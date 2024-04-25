package com.redelf.commons.management

import com.redelf.commons.callback.Callbacks
import com.redelf.commons.interruption.Abort
import com.redelf.commons.isNotEmpty
import com.redelf.commons.lifecycle.LifecycleCallback
import com.redelf.commons.lifecycle.exception.InitializingException
import com.redelf.commons.lifecycle.exception.NotInitializedException
import com.redelf.commons.locking.Lockable
import com.redelf.commons.obtain.Obtain
import com.redelf.commons.persistance.EncryptedPersistence
import com.redelf.commons.reset.Resettable
import timber.log.Timber
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

abstract class DataManagement<T> :

    Management,
    Obtain<T?>,
    Resettable,
    Lockable,
    Abort

{

    companion object {

        private val DATA_STORAGE: EncryptedPersistence =
            EncryptedPersistence(storageTag = "Data_Managed")
    }

    protected abstract val storageKey: String
    protected open val instantiateDataObject: Boolean = false

    private var data: T? = null
    private val locked = AtomicBoolean()

    private val initCallbacks =
        Callbacks<LifecycleCallback<EncryptedPersistence>>(initCallbacksTag())

    protected abstract fun getLogTag(): String

    protected open fun createDataObject(): T? = null

    override fun abort() = Unit

    override fun lock() {

        Timber.v("${getLogTag()} Lock")

        abort()

        locked.set(true)
    }

    override fun unlock() {

        Timber.v("${getLogTag()} :: Unlock")

        locked.set(false)
    }

    final override fun isLocked(): Boolean {

        return locked.get()
    }

    final override fun isUnlocked() = !isLocked()

    @Throws(InitializingException::class, NotInitializedException::class)
    override fun obtain(): T? {

        if (isLocked()) {

            Timber.w("${getLogTag()} Obtain :: Locked")

            return null
        }

        if (data == null) {

            data = DATA_STORAGE.pull(storageKey)
        }

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
    fun takeStorage(): EncryptedPersistence? {

        if (isLocked()) {

            return null
        }

        return DATA_STORAGE
    }

    @Throws(IllegalStateException::class)
    open fun pushData(data: T) {

        if (isLocked()) {

            Timber.w("${getLogTag()} Push data :: Locked: SKIPPING")

            return
        }

        val store = takeStorage()

        this.data = data

        try {

            store?.push(storageKey, data)

        } catch (e: RejectedExecutionException) {

            Timber.e(e)
        }
    }

    override fun reset(): Boolean {

        val tag = "${getLogTag()} :: Reset ::"

        Timber.v("$tag START")

        try {

            if (isNotEmpty(storageKey)) {

                Timber.v("$tag Storage key: $storageKey")

                val s = takeStorage()

                if (instantiateDataObject) {

                    data = createDataObject()

                    data?.let {

                        pushData(it)
                    }

                } else {

                    s?.delete(storageKey)
                }

                s?.shutdown()

            } else {

                Timber.w("$tag Empty storage key")
            }

            this.data = null

            Timber.v("$tag END")

            return true

        } catch (e: RejectedExecutionException) {

            Timber.e(tag, e)

        } catch (e: IllegalStateException) {

            Timber.e(tag, e)
        }

        Timber.e("$tag END: FAILED (2)")

        return false
    }

    override fun getWho(): String? = this::class.simpleName

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

    private fun initCallbacksTag() = "${getLogTag()} Data management initialization"
}