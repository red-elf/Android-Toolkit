package com.redelf.commons.test

import com.redelf.commons.extensions.GLOBAL_RECORD_EXCEPTIONS_ASSERT_FALLBACK
import com.redelf.commons.extensions.randomInteger
import com.redelf.commons.logging.Console
import com.redelf.commons.persistance.DBStorage
import com.redelf.commons.persistance.EncryptedPersistence
import com.redelf.commons.security.encryption.EncryptionListener
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class EncryptedPersistenceTest : BaseTest() {

    private lateinit var persistence: EncryptedPersistence

    private class Dummy(private val value: Int) {

        fun getValue() = value
    }

    @Before
    fun prepare() {

        Console.initialize(failOnError = true)

        Console.log("Console initialized: $this")

        GLOBAL_RECORD_EXCEPTIONS_ASSERT_FALLBACK.set(true)

        val testTag = "test.${System.currentTimeMillis()}"

        DBStorage.initialize(ctx = applicationContext)

        try {

            persistence = EncryptedPersistence(

                doLog = true,
                doEncrypt = true,
                storageTag = testTag,
                ctx = applicationContext,
            )

            Assert.assertTrue(persistence.isEncryptionEnabled())

        } catch (e: Exception) {

            Assert.fail(e.message)
        }
    }

    @Test
    fun testEncryptedPersistence() {

            testNumbers()
            testBoolean()
            testRandomPositiveNumbers()
            testRandomNegativeNumbers()
            testString()
            testUtfString()

        // FIXME:
//            testClass()
//            testMap()
//            testList()
//            testSet()
    }

    private fun testBoolean() {

        log("Boolean testing: START")

        val booleanValueKey = "testBool"
        var persistOK = persistence.push(booleanValueKey, true)

        Assert.assertTrue(persistOK)

        var retrievedBooleanValue = persistence.pull<Boolean>(booleanValueKey)

        Assert.assertNotNull(retrievedBooleanValue)
        Assert.assertTrue(retrievedBooleanValue == true)

        persistOK = persistence.push(booleanValueKey, false)

        Assert.assertTrue(persistOK)

        Assert.assertNotNull(retrievedBooleanValue)
        retrievedBooleanValue = persistence.pull(booleanValueKey)

        Assert.assertFalse(retrievedBooleanValue ?: true)

        log("Boolean testing: END")
    }

    private fun testNumbers() {

        log("Numbers testing: START")

        val numbers = listOf(1, 2, 21, 1.0, 0.1, 1.0000000001, 100, 1000, 100000)

        numbers.forEach { number ->

            testNumber(number)
        }

        log("Numbers testing: END")
    }

    private fun testRandomPositiveNumbers() {

        log("Positive numbers testing: START")

        val count = 1000

        for (x in 0..count) {

            val number = (1..Int.MAX_VALUE).random()
            testNumber(number)
        }

        for (x in 0..count) {

            val number = Random.nextDouble(0.001, Double.MAX_VALUE)
            testNumber(number)
        }

        log("Positive numbers testing: END")
    }

    private fun testRandomNegativeNumbers() {

        log("Negative numbers testing: START")

        val count = 1000

        for (x in 0..count) {

            val number = (Int.MIN_VALUE..-1).random()
            testNumber(number)
        }

        for (x in 0..count) {

            val number = Random.nextDouble(-999999999.999, -0.001)
            testNumber(number)
        }


        log("Negative numbers testing: END")
    }

    private fun testString() {

        log("String testing: START")

        val strings = listOf(
            "Hello", "World", "I need the floating point values to at " +
                    "least 4 decimals, preferably 7"
        )

        val stringValueKey = "testString"

        strings.forEach {

            val persistOK = persistence.push(stringValueKey, it)

            Assert.assertTrue(persistOK)

            val retrievedStringValue = persistence.pull<String>(stringValueKey)
            Assert.assertEquals(it, retrievedStringValue)
        }

        log("String testing: END")
    }

    private fun testUtfString() {

        log("UTF string testing: START")

        val strings = listOf(

            "Шш Ђђ Чч Ћћ Љљ"
        )

        val stringValueKey = "testUtfString"

        strings.forEach {

            val persistOK = persistence.push(stringValueKey, it)

            Assert.assertTrue(persistOK)

            val retrievedStringValue = persistence.pull<String>(stringValueKey)
            Assert.assertEquals(it, retrievedStringValue)
        }

        log("UTF string testing: END")
    }

    private fun testNumber(number: Number) {

        val latch = CountDownLatch(2)

        var encKey = ""
        var encRaw = ""
        var enc = ""

        var decKey = ""
        var decEnc = ""
        var dec = ""

        val callback = object : EncryptionListener<String, String> {

            override fun onEncrypted(

                key: String,
                raw: String,
                encrypted: String

            ) {

                Console.log("On :: Encrypted :: Key = $key, Raw = $raw, Encrypted = $encrypted")

                encKey = key
                encRaw = raw
                enc = encrypted

                latch.countDown()
            }

            override fun onDecrypted(

                key: String,
                encrypted: String,
                decrypted: String

            ) {

                Console.log(

                    "On :: Encrypted :: Key = $key, Encrypted = $encrypted, Decrypted = $decrypted"
                )

                decKey = key
                decEnc = encrypted
                dec = decrypted

                latch.countDown()
            }

            override fun onEncryptionFailure(key: String, error: Throwable) {

                Assert.fail("ASSERT FAILURE :: Key = $key, Error = ${error.message}")
            }

            override fun onDecryptionFailure(key: String, error: Throwable) {

                Assert.fail("ASSERT FAILURE :: Key = $key, Error = ${error.message}")
            }
        }

        persistence.register(callback)

        val numberValueKey = "testNumber"

        val persistOK = persistence.push(numberValueKey, number)

        Assert.assertTrue(persistOK)

        val retrieved = persistence.pull<Any?>(numberValueKey)

        try {

            if (!latch.await(15, TimeUnit.SECONDS)) {

                Assert.fail("Latch timed out")
            }

        } catch (e: Exception) {

            Assert.fail(e.message)
        }

        Assert.assertTrue(encKey.isNotEmpty())
        Assert.assertTrue(encRaw.isNotEmpty())
        Assert.assertTrue(enc.isNotEmpty())

        Assert.assertTrue(decKey.isNotEmpty())
        Assert.assertTrue(decEnc.isNotEmpty())
        Assert.assertTrue(dec.isNotEmpty())

        Assert.assertEquals(numberValueKey, encKey)
        Assert.assertEquals(numberValueKey, decKey)
        Assert.assertEquals(encKey, decKey)

        Assert.assertNotEquals(encRaw, enc)
        Assert.assertNotEquals(decEnc, dec)

        Assert.assertEquals(number.toString(), encRaw)
        Assert.assertEquals(number.toString(), dec)

        Assert.assertNotNull(retrieved)
        Assert.assertTrue(retrieved is Number)
        Assert.assertTrue(number == retrieved)

        persistence.unregister(callback)
    }

    private fun testClass() {

        log("Class testing: START")

        val instances = listOf(

            Dummy(-1),
            Dummy(-3),
            Dummy(-5),
            Dummy(0),
            Dummy(1),
            Dummy(3),
            Dummy(5)
        )

        val classValueKey = "testDummy"

        instances.forEach { dummy ->

            val persistOK = persistence.push(classValueKey, dummy)

            Assert.assertTrue(persistOK)

            val retrievedClassValue = persistence.pull<Dummy>(classValueKey)

            Assert.assertNotNull(retrievedClassValue)
            Assert.assertEquals(dummy.getValue(), retrievedClassValue?.getValue())
        }

        log("Class testing: END")
    }

    private fun testMap() {

        val map = mutableMapOf<String, String>()
        val map2 = mutableMapOf<Int, Float>()
        val map3 = mutableMapOf<Int, Boolean>()
        val map4 = mutableMapOf<String, String>()

        map["bla"] = "bla"
        map["hello"] = "world"

        map2[2] = 0.2F
        map2[2240] = 0.05F

        map3[randomInteger()] = true
        map3[randomInteger()] = false
        map3[randomInteger()] = true
        map3[randomInteger()] = false
        map3[randomInteger()] = true
        map3[randomInteger()] = false

        val builder = StringBuilder()

        for (x in 0..100) {

            builder.append(x.toString())

            map4[randomInteger().toString()] = builder.toString()
        }

        testObject("map", map)
        testObject("map", map2)
        testObject("map", map3)
        testObject("map", map4)
    }

    private fun testList() {

        val list = emptyList<String>()
        val list2 = listOf(2, 4, 6, 8, -1)
        val list3 = listOf(2, 3L, 6, 8, -1L)
        val list4 = listOf("hello", "hello", "world")
        val list5 = listOf(1.2, 2.3, 3.4, 4.5)
        val list6 = listOf(true, false, true)

        testObject("list", list)
        testObject("list", list2)
        testObject("list", list3)
        testObject("list", list4)
        testObject("list", list5)
        testObject("list", list6)
    }

    private fun testSet() {

        val set = setOf(1, 2, 3, 4, 5, 5, 5, 6, 7, 7, 7)
        val set2 = setOf("aa", "aa", "bb")
        val set3 = setOf(true, false, true)

        testObject("set", set)
        testObject("set", set2)
        testObject("set", set3)
    }

    private fun testObject(key: String, value: Any) {

        val persistOK = persistence.push(key, value)

        Assert.assertTrue(persistOK)

        val retrieved = persistence.pull<Any?>(key)

        Assert.assertNotNull(retrieved)
        Assert.assertEquals(value, retrieved)
    }
}