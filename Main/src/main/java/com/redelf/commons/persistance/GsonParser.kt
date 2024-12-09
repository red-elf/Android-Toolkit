package com.redelf.commons.persistance

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.annotations.Expose
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.redelf.commons.application.BaseApplication
import com.redelf.commons.extensions.isEmpty
import com.redelf.commons.extensions.recordException
import com.redelf.commons.logging.Console
import com.redelf.commons.obtain.Obtain
import com.redelf.commons.persistance.base.Parser
import com.redelf.commons.persistance.serialization.ByteArraySerializer
import com.redelf.commons.persistance.serialization.CustomSerializable
import com.redelf.commons.persistance.serialization.DefaultCustomSerializer
import com.redelf.commons.persistance.serialization.Serializer
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicBoolean

class GsonParser(

    parserKey: String,
    private val provider: Obtain<GsonBuilder>

) : Parser {

    companion object {

        val DEBUG = AtomicBoolean()
    }

    private val ctx: Context = BaseApplication.takeContext()
    private val tag = "Parser :: GSON :: Key = '$parserKey', Hash = '${hashCode()}'"
    private val byteArraySerializer = ByteArraySerializer(ctx, "Parser.GSON.$parserKey")

    @Suppress("DEPRECATION")
    override fun <T> fromJson(content: String?, type: Type?): T? {

        try {

            if (isEmpty(content)) {

                return null
            }

            val tag = "$tag Deserialize ::"

            Console.log("$tag START")

            val gsonProvider = provider.obtain()

            type?.let { t ->

                try {

                    val clazz = Class.forName(t.typeName)

                    Console.log("$tag Class = '${clazz.canonicalName}'")

                    val instance = clazz.newInstance()

                    Console.log("$tag Instance hash = ${instance.hashCode()}")

                    if (instance is CustomSerializable) {

                        val customizations = instance.getCustomSerializations()

                        Console.log("$tag Customizations = $customizations")

                        val typeAdapter = createTypeAdapter(instance::class.java, customizations)

                        gsonProvider.registerTypeAdapter(instance::class.java, typeAdapter)

                        Console.log("$tag Type adapter registered")
                    }

                } catch (e: Exception) {

                    Console.error("$tag ERROR: ${e.message}")
                    recordException(e)
                }
            }

            return gsonProvider.create().fromJson(content, type)

        } catch (e: Exception) {

            recordException(e)

            Console.error("Tried to deserialize into '${type?.typeName}' from '$content'")
        }

        return null
    }

    @Suppress("DEPRECATION")
    override fun <T> fromJson(content: String?, clazz: Class<T>?): T? {

        if (isEmpty(content)) {

            return null
        }

        val tag = "$tag Deserialize ::"

        Console.log("$tag START")

        val gsonProvider = provider.obtain()

        try {

            Console.log("$tag Class = '${clazz?.canonicalName}'")

            val instance = clazz?.newInstance()

            Console.log("$tag Instance hash = ${instance.hashCode()}")

            if (instance is CustomSerializable) {

                val customizations = instance.getCustomSerializations()

                Console.log("$tag Customizations = $customizations")

                val typeAdapter = createTypeAdapter(instance::class.java, customizations)

                gsonProvider.registerTypeAdapter(instance::class.java, typeAdapter)

                Console.log("$tag Type adapter registered")
            }

        } catch (e: Exception) {

            Console.error("$tag ERROR: ${e.message}")
            recordException(e)
        }

        return gsonProvider.create().fromJson(content, clazz)
    }

    override fun toJson(body: Any?): String? {

        if (body == null) {

            return null
        }

        val tag = "$tag Class = '${body::class.java.canonicalName}' ::"

        if (DEBUG.get()) Console.log("$tag START")

        try {

            val gsonProvider = provider.obtain()

            if (body is CustomSerializable) {

                val customizations = body.getCustomSerializations()

                Console.log("$tag Customizations = $customizations")

                val typeAdapter = createTypeAdapter(body::class.java, customizations)

                gsonProvider.registerTypeAdapter(body::class.java, typeAdapter)

                Console.log("$tag Type adapter registered")
            }

            return gsonProvider.create().toJson(body)

        } catch (e: Exception) {

            recordException(e)
        }

        return null
    }

    private fun createTypeAdapter(

        who: Any,
        recipe: Map<String, Serializer>

    ): TypeAdapter<Any> {

        val clazz = who.javaClass
        val tag = "$tag Type adapter :: Class = '${clazz.canonicalName}'"

        Console.log("$tag CREATE :: Recipe = $recipe")

        return object : TypeAdapter<Any>() {

            override fun write(out: JsonWriter?, value: Any?) {

                try {

                    if (value == null) {

                        out?.nullValue()

                    } else {

                        out?.beginObject()

                        val fields = clazz.declaredFields

                        fields.forEach { field ->

                            var excluded = false
                            field.isAccessible = true

                            if (field.isAnnotationPresent(Expose::class.java)) {

                                val exposeAnnotation = field.getAnnotation(Expose::class.java)

                                if (exposeAnnotation?.serialize == true) {

                                    val value = field.get(who)

                                    if (value is Boolean) {

                                        excluded = value
                                    }
                                }
                            }

                            if (!excluded) {

                                excluded = field.isAnnotationPresent(Transient::class.java)
                            }

                            val fieldName = field.name

                            if (excluded) {

                                Console.log("$tag EXCLUDED :: Field name = '$fieldName'")

                            } else {

                                val wTag = "$tag WRITING :: Field name = '$fieldName' ::"

                                Console.log("$wTag START")

                                val fieldValue = field.get(who)

                                fieldValue?.let { fValue ->

                                    fun regularWrite() {

                                        val rwTag = "$wTag REGULAR WRITE ::"

                                        Console.log("$rwTag START")

                                        try {

                                            out?.name(fieldName)

                                            // FIXME:
                                            // out?.value(gson.toJson(fValue))

                                            Console.log("$rwTag END")

                                        } catch (e: Exception) {

                                            Console.error("$rwTag ERROR: ${e.message}")
                                            recordException(e)
                                        }
                                    }

                                    fun customWrite() {

                                        Console.log(

                                            "$wTag Custom write :: START :: " +
                                                    "Class = '${clazz.canonicalName}'"
                                        )

                                        recipe[fieldName]?.let { serializer ->

                                            if (serializer is DefaultCustomSerializer) {

                                                Console.log("$wTag Custom write :: Custom serializer")

                                                when (clazz.canonicalName) {

                                                    ByteArray::class.java.canonicalName -> {

                                                        try {

                                                            out?.name(fieldName)

                                                            out?.value(

                                                                byteArraySerializer.serialize(

                                                                    fieldName,
                                                                    fValue
                                                                )
                                                            )

                                                        } catch (e: Exception) {

                                                            Console.error("$wTag ERROR: ${e.message}")
                                                            recordException(e)
                                                        }
                                                    }

                                                    else -> {

                                                        val e = IllegalArgumentException(

                                                            "Not supported type for default " +
                                                                    "custom serializer " +
                                                                    "'${clazz.canonicalName}'"
                                                        )

                                                        Console.error("$wTag ERROR: ${e.message}")
                                                        recordException(e)
                                                    }
                                                }

                                            } else {

                                                Console.log(

                                                    "$wTag Custom write :: Custom provided serializer"
                                                )

                                                try {

                                                    out?.name(fieldName)

                                                    out?.value(

                                                        serializer.serialize(

                                                            fieldName,
                                                            fValue
                                                        )
                                                    )

                                                } catch (e: Exception) {

                                                    Console.error("$tag ERROR: ${e.message}")
                                                    recordException(e)
                                                }
                                            }
                                        }

                                        if (recipe[fieldName] == null) {

                                            Console.log("$wTag END :: To write regular (1)")

                                            regularWrite()
                                        }
                                    }

                                    if (recipe.containsKey(fieldName)) {

                                        Console.log("$wTag END :: To write custom")

                                        customWrite()

                                    } else {

                                        Console.log("$wTag END :: To write regular (2)")

                                        regularWrite()
                                    }
                                }

                                if (fieldValue == null) {

                                    Console.log("$wTag END :: Field value is null")
                                }
                            }
                        }

                        out?.endObject()
                    }

                } catch (e: Exception) {

                    recordException(e)
                }
            }

            @Suppress("DEPRECATION")
            override fun read(`in`: JsonReader?): Any? {

                val tag = "$tag READ ::"

                Console.log("$tag START")

                try {

                    val instance = clazz.newInstance()

                    `in`?.beginObject()

                    while (`in`?.hasNext() == true) {

                        val fieldName = `in`.nextName()

                        val tag = "$tag Field = '$fieldName' ::"

                        fun customRead(): Any? {

                            val tag = "$tag CUSTOM ::"

                            Console.log("$tag START")

                            try {

                                recipe[fieldName]?.let { serializer ->

                                    if (serializer is DefaultCustomSerializer) {

                                        Console.log("$tag Custom write :: Custom serializer")

                                        when (clazz.canonicalName) {

                                            ByteArray::class.java.canonicalName -> {

                                                val result = byteArraySerializer.deserialize(fieldName)

                                                return result
                                            }

                                            else -> {

                                                val e = IllegalArgumentException(

                                                    "Not supported type for default " +
                                                            "custom serializer " +
                                                            "'${clazz.canonicalName}'"
                                                )

                                                Console.error("$tag ERROR: ${e.message}")
                                                recordException(e)

                                                return null
                                            }
                                        }

                                    } else {

                                        val result = serializer.deserialize(fieldName)

                                        return result
                                    }
                                }

                            } catch (e: Exception) {

                                Console.error("$tag ERROR: ${e.message}")
                                recordException(e)
                            }

                            return null
                        }

                        fun regularRead(): Any? {

                            val tag = "$tag REGULAR ::"

                            Console.log("$tag START")

                            try {

                                // FIXME:
                                val json = `in`.nextString()

//                                Console.log("$tag JSON obtained")

//                                val result = gson.fromJson(json, clazz)
//
//                                Console.log("$tag END: $result")

                                return ""

                            } catch (e: Exception) {

                                Console.error("$tag ERROR: ${e.message}")
                                recordException(e)
                            }

                            return null
                        }

                        if (recipe.containsKey(fieldName)) {

                            return customRead()

                        } else {

                            return regularRead()
                        }
                    }

                    `in`?.endObject()

                    return instance

                } catch (e: Exception) {

                    Console.error("$tag ERROR: ${e.message}")
                    recordException(e)
                }

                return null
            }
        }
    }
}
