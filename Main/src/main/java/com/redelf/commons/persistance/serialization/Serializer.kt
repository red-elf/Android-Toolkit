package com.redelf.commons.persistance.serialization

interface Serializer {

    fun serialize(key: String, value: Any): Boolean

    fun deserialize(key: String): Any?
}