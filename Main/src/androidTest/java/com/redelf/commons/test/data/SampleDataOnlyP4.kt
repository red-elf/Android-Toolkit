package com.redelf.commons.test.data

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.reflect.TypeToken
import com.redelf.commons.logging.Timber
import com.redelf.commons.partition.Partitional
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class SampleDataOnlyP4 @JsonCreator constructor(

    @JsonProperty("partitioningOn")
    @SerializedName("partitioningOn")
    private val partitioningOn: Boolean = true,

    @JsonProperty("partition4")
    @SerializedName("partition4")
    var partition4: SampleData3? = null,

) : Partitional<SampleDataOnlyP4> {

    constructor() : this(

        partitioningOn = true
    )

    override fun getClazz(): Class<SampleDataOnlyP4> {

        return SampleDataOnlyP4::class.java
    }

    override fun isPartitioningEnabled() = partitioningOn

    fun isPartitioningDisabled() = !partitioningOn

    override fun getPartitionCount() = 1

    override fun getPartitionData(number: Int): Any? {

        return when (number) {

            0 -> partition4

            else -> null
        }
    }

    override fun setPartitionData(number: Int, data: Any?): Boolean {

        if (data == null) {

            return true
        }

        when (number) {

            0 -> {

                try {

                    partition4 = data as SampleData3

                } catch (e: Exception) {

                    Timber.e(e)

                    return false
                }

                return true
            }

            else -> return false
        }
    }

    override fun getPartitionType(number: Int): Type? {

        return when (number) {

            0 -> object : TypeToken<SampleData3>() {}.type

            else -> null
        }
    }
}
