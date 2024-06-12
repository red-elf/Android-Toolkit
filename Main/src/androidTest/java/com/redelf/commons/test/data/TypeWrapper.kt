package com.redelf.commons.test.data

import com.redelf.commons.logging.Timber
import com.redelf.commons.model.Wrapper
import com.redelf.commons.partition.Partitional
import org.junit.Assert

abstract class TypeWrapper<T>(wrapped: T?) :

    Wrapper<T?>(wrapped),
    Partitional<TypeWrapper<T?>>

{

    constructor() : this(null)

    override fun isPartitioningEnabled() = true

    override fun getPartitionCount() = 1

    override fun getPartitionData(number: Int): Any? {

        if (number > 0) {

            Assert.fail("Unexpected partition number: $number")
        }

        return takeData()
    }

    @Suppress("UNCHECKED_CAST")
    override fun setPartitionData(number: Int, data: Any?): Boolean {

        if (number > 0) {

            Assert.fail("Unexpected partition number: $number")
        }

        try {

            this.data = data as T?

        } catch (e: Exception) {

            Timber.e(e)

            return false
        }

        return true
    }
}