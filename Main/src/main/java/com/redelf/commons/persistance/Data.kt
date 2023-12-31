package com.redelf.commons.persistance

import android.content.Context
import android.text.TextUtils
import com.redelf.commons.persistance.Facade.EmptyFacade

/**
 * Secure, simple key-value storage for Android.
 */
object Data {

    private var facade: Facade? = EmptyFacade()

    fun init(

        context: Context,
        storageTag: String? = null,
        salter: Salter? = null

    ): PersistenceBuilder {

        PersistenceUtils.checkNull("Context", context)
        facade = null

        if (!TextUtils.isEmpty(storageTag) && storageTag != null) {

            salter?.let {

                return PersistenceBuilder(context, storageTag = storageTag, salter = it)
            }

            return PersistenceBuilder(context, storageTag = storageTag)
        }

        salter?.let {

            return PersistenceBuilder(context, salter = it)
        }

        return PersistenceBuilder(context)
    }

    fun build(persistenceBuilder: PersistenceBuilder) {

        facade = DefaultFacade(persistenceBuilder)
    }

    /**
     * Saves any type including any collection, primitive values or custom objects
     *
     * @param key   is required to differentiate the given data
     * @param value is the data that is going to be encrypted and persisted
     * @return true if the operation is successful. Any failure in any step will return false
     */
    fun <T> put(key: String?, value: T): Boolean {

        return facade?.put(key, value) ?: false
    }

    /**
     * Gets the original data along with original type by the given key.
     * This is not guaranteed operation since Data uses serialization. Any change in in the requested
     * data type might affect the result. It's guaranteed to return primitive types and String type
     *
     * @param key is used to get the persisted data
     * @return the original object
     */
    operator fun <T> get(key: String?): T? {

        return facade?.get(key)
    }

    /**
     * Gets the saved data, if it is null, default value will be returned
     *
     * @param key          is used to get the saved data
     * @param defaultValue will be return if the response is null
     * @return the saved object
     */
    operator fun <T> get(key: String?, defaultValue: T): T {

        return facade?.get(key, defaultValue) ?: defaultValue
    }

    /**
     * Size of the saved data. Each key will be counted as 1
     *
     * @return the size
     */
    fun count(): Long {

        return facade?.count() ?: -1
    }

    /**
     * Clears the storage, note that crypto data won't be deleted such as salt key etc.
     * Use resetCrypto in order to deleteAll crypto information
     *
     * @return true if deleteAll is successful
     */
    fun deleteAll(): Boolean {

        return facade?.deleteAll() ?: false
    }

    fun deleteKeysWithPrefix(value: String): Boolean {

        return facade?.deleteKeysWithPrefix(value) ?: true
    }

    /**
     * Removes the given key/value from the storage
     *
     * @param key is used for removing related data from storage
     * @return true if delete is successful
     */
    fun delete(key: String?): Boolean {

        return facade?.delete(key) ?: false
    }

    /**
     * Checks the given key whether it exists or not
     *
     * @param key is the key to check
     * @return true if it exists in the storage
     */
    operator fun contains(key: String?): Boolean {

        return facade?.contains(key) ?: false
    }

    val isBuilt: Boolean
        /**
         * Use this method to verify if Data is ready to be used.
         *
         * @return true if correctly initialised and built. False otherwise.
         */
        get() = facade?.isBuilt ?: false

    fun destroy() {

        facade?.destroy()
    }
}