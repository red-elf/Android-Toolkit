package com.redelf.commons.transmission

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.TextUtils
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.redelf.commons.callback.CallbackOperation
import com.redelf.commons.callback.Callbacks
import com.redelf.commons.execution.Executor
import com.redelf.commons.execution.TaskExecutor
import com.redelf.commons.extensions.recordException
import com.redelf.commons.logging.Console
import com.redelf.commons.management.DataManagement
import com.redelf.commons.management.Management
import com.redelf.commons.obtain.OnObtain
import com.redelf.commons.security.encryption.Encrypt
import com.redelf.commons.security.encryption.Encryption
import com.redelf.commons.security.encryption.EncryptionProvider
import com.redelf.commons.transmission.encryption.TransmissionManagerEncryptionProvider
import java.lang.reflect.Type
import java.security.GeneralSecurityException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

abstract class TransmissionManager<T : Encrypt>(private val storageIdentifier: String) : Management {

    companion object {

        const val BROADCAST_ACTION_SEND = "TransmissionManager.Action.SEND"
        const val BROADCAST_EXTRA_RESULT = "TransmissionManager.Extra.RESULT"
        const val BROADCAST_ACTION_RESULT = "TransmissionManager.Action.RESULT"
    }

    /*
    * TODO: Support Jackson as well and stream read & write
    */
    private val gson = Gson()
    private val capacity = 100
    private val maxRetries = 10
    private val sending = AtomicBoolean()
    private var lastSendingTime: Long = 0
    private val sequentialExecutor = TaskExecutor.instantiateSingle()

    private val sendingCallbacks =
        Callbacks<TransmissionSendingCallback<T>>(identifier = "Transmission sending")

    private val persistCallbacks =
        Callbacks<TransmissionManagerPersistCallback>(identifier = "Transmission persistence")

    protected val data = LinkedBlockingQueue<String>(capacity)

    protected open val minSendIntervalInSeconds = 0

    protected abstract val encryptionKeySuffix: String
    protected abstract var currentEncryptionProvider: EncryptionProvider
    protected abstract val sendingDefaultStrategy: TransmissionManagerSendingStrategy<T>
    protected abstract var currentSendingStrategy: TransmissionManagerSendingStrategy<T>

    private val sendRequestReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            Console.log("BROADCAST_ACTION_SEND on receive")

            intent?.let {

                if (it.action == BROADCAST_ACTION_SEND) {

                    Console.log("BROADCAST_ACTION_SEND on action")

                    try {

                        send(executedFrom = "BROADCAST_ACTION_SEND")

                    } catch (e: IllegalStateException) {

                        Console.error(e)
                    }
                }
            }
        }
    }

    private val persistDefaultStrategy = object : TransmissionManagerPersistStrategy {

        override fun persist(identifier: String, data: String): Boolean {

            return DataManagement.STORAGE.push(identifier, data)
        }
    }

    private var persistStrategy: TransmissionManagerPersistStrategy = persistDefaultStrategy

    fun setSendingStrategy(sendingStrategy: TransmissionManagerSendingStrategy<T>): Boolean {

        currentSendingStrategy = sendingStrategy
        return currentSendingStrategy == sendingStrategy
    }

    fun getSendingStrategy() = currentSendingStrategy

    init {

        initialize()
    }

    @Throws(IllegalStateException::class)
    private fun initialize() {

        val intentFilter = IntentFilter(BROADCAST_ACTION_SEND)
        registerReceiver(sendRequestReceiver, intentFilter)

        Console.log("BROADCAST_ACTION_SEND receiver registered")

        val action = Runnable {

            val json = DataManagement.STORAGE.pull(storageIdentifier) ?: ""

            if (TextUtils.isEmpty(json)) {

                Console.warning("No encrypted data persisted")
                onInit(true)

            } else {

                try {

                    val listType: Type = object : TypeToken<LinkedList<String>>() {}.type
                    val items = gson.fromJson<LinkedList<String>>(json, listType)

                    clear()

                    if (items.isEmpty()) {

                        Console.warning("No data loaded from secure storage")

                    } else {

                        add(items)
                    }

                    onInit(true)

                } catch (e: JsonSyntaxException) {

                    recordException(e)
                    onInit(false)

                } catch (e: InterruptedException) {

                    recordException(e)
                    onInit(false)
                }
            }
        }

        try {

            Executor.MAIN.execute(action)

        } catch (e: RejectedExecutionException) {

            recordException(e)
        }
    }

    private fun shutdown() {

        val terminated = terminate()
        onShutdown(terminated)
    }

    @Throws(IllegalStateException::class)
    fun send(data: T, async: Boolean = true) {

        val action = Runnable {

            Console.log("We are about to send data: %s", data::class.simpleName)

            persist(data)
        }

        if (async) {

            try {

                sequentialExecutor.execute(action)

            } catch (e: RejectedExecutionException) {

                recordException(e)
            }

        } else {

            action.run()
        }
    }

    /*
        Note: Returns True if there was something to update
     */
    @Throws(IllegalStateException::class)
    fun update(data: T): Boolean {

        return reSchedule(data)
    }

    private fun unSchedule(scheduled: T): T? {

        doUnSchedule(scheduled)?.let {

            persist()

            return it
        }
        return null
    }

    private fun reSchedule(scheduled: T): Boolean {

        if (doReSchedule(scheduled)) {

            persist()

            return true
        }

        return false
    }

    @Throws(IllegalStateException::class)
    fun send(executedFrom: String = "") {

        Console.log("Send (manager) :: executedFrom='$executedFrom'")

        val action = Runnable { executeSending("send") }
        sequentialExecutor.execute(action)
    }

    @Throws(IllegalStateException::class)
    fun deleteAll() {

        if (isSending()) {

            throw IllegalStateException("Data are being sent")
        }

        val action = Runnable {

            Console.warning("We are about to delete all data")
            clear()
            persist()
        }

        sequentialExecutor.execute(action)
    }

    @Throws(IllegalStateException::class)
    fun delete(item: T, callback: OnObtain<Boolean>) {

        if (isSending()) {

            throw IllegalStateException("Data are being sent")
        }

        val action = Runnable {

            Console.warning("We are about to delete the data item")

            if (clear(item)) {

                Console.log("The data item has been deleted with success")
                persist()

                callback.onCompleted(true)

            } else {

                Console.log("The data item has failed to delete")

                callback.onCompleted(false)
            }
        }

        sequentialExecutor.execute(action)
    }

    @Throws(IllegalStateException::class)
    fun getScheduledCount(): Int {

        return data.size
    }

    abstract fun getScheduled(): Collection<T>

    abstract fun doUnSchedule(scheduled: T): T?

    abstract fun doReSchedule(scheduled: T): Boolean

    fun isSending() = sending.get()

    fun addSendingCallback(callback: TransmissionSendingCallback<T>) {

        sendingCallbacks.register(callback)
    }

    fun removeSendingCallback(callback: TransmissionSendingCallback<T>) {

        sendingCallbacks.unregister(callback)
    }

    fun addPersistCallback(callback: TransmissionManagerPersistCallback) {

        persistCallbacks.register(callback)
    }

    fun removePersistCallback(callback: TransmissionManagerPersistCallback) {

        persistCallbacks.unregister(callback)
    }

    fun resetSendingStrategy(): Boolean {

        currentSendingStrategy = sendingDefaultStrategy
        return currentSendingStrategy == sendingDefaultStrategy
    }

    fun setPersistStrategy(persistStrategy: TransmissionManagerPersistStrategy) {

        this.persistStrategy = persistStrategy
    }

    fun resetPersistStrategy() {

        persistStrategy = persistDefaultStrategy
    }

    fun setEncryptionProvider(provider: EncryptionProvider) {

        Console.log("Setting the data encryption provider: $provider")

        currentEncryptionProvider = provider
    }

    fun getEncryptionProvider(): EncryptionProvider {

        return currentEncryptionProvider
    }

    fun resetEncryptionProvider() {

        val new = TransmissionManagerEncryptionProvider(this, encryptionKeySuffix)

        Console.log("Reset data encryption provider: $new")

        currentEncryptionProvider = new

    }

    @Throws(

        IllegalStateException::class,
        ClassCastException::class,
        NullPointerException::class,
        IllegalArgumentException::class

    )
    fun add(data: String) {

        Console.log("Data: Add, %s", data.length)

        this.data.add(data)
    }

    protected abstract fun getContext(): Context

    protected abstract fun decrypt(encrypted: String, encryption: Encryption<String, String>): T

    private fun executeSending(executedFrom: String = "") {

        val now = System.currentTimeMillis()

        val timeDiff = now - lastSendingTime

        val timeCondition = minSendIntervalInSeconds > 0 &&
                timeDiff < minSendIntervalInSeconds * 1000

        if (timeCondition) {

            // Timber.w("Too soon to send data. Last sending executed before: %s", timeDiff)
            return
        }

        Console.log("Last sending executed before: %s", timeDiff)

        if (currentSendingStrategy.isNotReady()) {

            Console.warning("Current sending strategy is not ready")
            return
        }

        if (isSending()) {

            Console.warning("Data is already sending")
            return
        }

        Console.log("Execute sending :: executedFrom='$executedFrom'")

        setSending(true)

        var persistingRequired = false

        if (data.isEmpty()) {

            Console.warning("No data to be sent yet")
            setSending(false)
            return
        }

        val iterator = data.iterator()

        while (iterator.hasNext()) {

            val item = iterator.next()
            item?.let { encrypted ->

                try {

                    val encryption = currentEncryptionProvider.obtain()
                    Console.log("Data decrypting: %s", encrypted.length)

                    val data = decrypt(encrypted, encryption)
                    Console.log("Data decrypted")

                    onSendingStarted(data)

                    val success = executeSending(data)

                    if (success) {

                        lastSendingTime = System.currentTimeMillis()

                        iterator.remove()

                        if (!persistingRequired) {

                            persistingRequired = true
                        }

                        Console.info("Data has been sent")

                    } else {

                        Console.error("Data has not been sent")
                    }

                    onSent(data, success)

                } catch (e: GeneralSecurityException) {

                    recordException(e)

                } catch (e: IllegalArgumentException) {

                    recordException(e)

                } catch (e: JsonSyntaxException) {

                    recordException(e)
                }
            }
        }

        setSending(false)

        if (persistingRequired) {

            persist()
        }
    }

    private fun persist(data: T) {

        try {

            val encryption = currentEncryptionProvider.obtain()
            val encrypted = data.encrypt(encryption)

            if (this.data.contains(encrypted)) {

                Console.warning("Data has been already persisted: %s", data)
                executeSending("persist")

            } else {

                add(encrypted)
                persist()

                Console.log("Data has been persisted: %s", data)
            }

        } catch (e: OutOfMemoryError) {

            recordException(e)

        } catch (e: Exception) {

            recordException(e)
        }
    }

    private fun executeSending(data: T): Boolean {

        if (currentSendingStrategy == sendingDefaultStrategy) {

            val default = "DEFAULT SENDING STRATEGY"
            Console.log("Executing sending of %s with '%s'", data, default)

        } else {

            val custom = "CUSTOM SENDING STRATEGY"
            Console.debug("Executing sending of %s with '%s'", data, custom)
        }

        return currentSendingStrategy.executeSending(data)
    }

    private fun persist() {

        var success = false

        try {

            val json = gson.toJson(data)
            success = persistStrategy.persist(storageIdentifier, json)

        } catch (e: OutOfMemoryError) {

            recordException(e)
        }

        if (success) {

            Console.info("Data has been persisted")

        } else {

            Console.error("Data has not been persisted")
        }

        onPersisted(success)
    }

    private fun onInit(success: Boolean) {

        if (success) {

            Console.log("Init success")
            return
        }

        Console.error("Init failed")
    }

    private fun onShutdown(success: Boolean) {

        if (success) {

            Console.log("Shutdown success")
            return
        }

        Console.error("Shutdown failed")
    }

    private fun onSent(data: T, success: Boolean) {

        val intent = Intent(BROADCAST_ACTION_RESULT)
        intent.putExtra(BROADCAST_EXTRA_RESULT, success)

        val ctx = getContext()
        ctx.sendBroadcast(intent)

        Console.log("BROADCAST_ACTION_RESULT on sent")

        val operation = object : CallbackOperation<TransmissionSendingCallback<T>> {

            override fun perform(callback: TransmissionSendingCallback<T>) {

                callback.onSent(data, success)
            }
        }

        sendingCallbacks.doOnAll(operation, "onSent")
    }

    private fun onSendingStarted(data: T) {

        val operation = object : CallbackOperation<TransmissionSendingCallback<T>> {

            override fun perform(callback: TransmissionSendingCallback<T>) {

                callback.onSendingStarted(data)
            }
        }

        sendingCallbacks.doOnAll(operation, "onSendingStarted")
    }

    private fun onPersisted(success: Boolean) {

        Console.log("On data persisted: %b", success)

        val operation = object : CallbackOperation<TransmissionManagerPersistCallback> {

            override fun perform(callback: TransmissionManagerPersistCallback) {

                callback.onPersisted(success)
            }
        }

        persistCallbacks.doOnAll(operation, "On persisted")

        if (data.isNotEmpty()) {

            if (success) {

                Console.log("On data persisted: We are about to start sending data")

                executeSending("onPersisted")


            } else {

                Console.error("On data NOT persisted: We are NOT going to start data sending")
            }
        }
    }

    private fun terminate(): Boolean {

        try {

            unregisterReceiver(sendRequestReceiver)

            Console.log("BROADCAST_ACTION_SEND receiver unregistered")

        } catch (e: IllegalArgumentException) {

            Console.error(e)
        }

        clear()
        return data.isEmpty()
    }

    @Throws(InterruptedException::class)
    private fun add(items: LinkedList<String>) {

        Console.log("Data: Add, count: %d", items.size)

        items.forEach {

            data.put(it)
        }
    }

    private fun clear() {

        Console.log("Data: Clear")
        data.clear()
    }

    private fun clear(item: T): Boolean {

        Console.log("Data: Clear item")

        val encryption = currentEncryptionProvider.obtain()

        try {

            val encrypted = item.encrypt(encryption)

            if (data.contains(encrypted)) {

                return data.remove(encrypted)
            }

        } catch (e: GeneralSecurityException) {

            recordException(e)

        } catch (e: OutOfMemoryError) {

            recordException(e)
        }

        return false
    }

    private fun setSending(sending: Boolean) {

        Console.log("Setting: Sending data to %b", sending)
        this.sending.set(sending)
    }

    protected fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {

        receiver?.let { r ->
            filter?.let { f ->

                LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(r, f)
            }
        }

        return null
    }

    protected fun unregisterReceiver(receiver: BroadcastReceiver?) {

        receiver?.let {

            LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(it)
        }
    }

    protected fun sendBroadcast(intent: Intent?) {

        intent?.let {

            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(it)
        }
    }

    protected fun getApplicationContext(): Context = getContext().applicationContext
}