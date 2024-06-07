package com.redelf.commons.persistance.base

import com.redelf.commons.lifecycle.InitializationWithContext
import com.redelf.commons.lifecycle.ShutdownSynchronized
import com.redelf.commons.lifecycle.TerminationSynchronized
import java.lang.reflect.Type

/*
    FIXME: We do not need both ShutdownSynchronized and TerminationSynchronized.
       Check other the files as well!
* */
interface Storage<T> : ShutdownSynchronized, TerminationSynchronized, InitializationWithContext {
    fun put(key: String?, value: T): Boolean

    fun get(key: String?): T

    fun getByType(key: String?, type: Type): Any?

    fun delete(key: String?): Boolean

    fun deleteAll(): Boolean

    fun count(): Long

    fun contains(key: String?): Boolean
}
