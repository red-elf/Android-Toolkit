package com.redelf.commons.persistance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import com.redelf.commons.application.BaseApplication
import com.redelf.commons.context.ContextAvailability
import com.redelf.commons.execution.Executor
import com.redelf.commons.extensions.isEmpty
import com.redelf.commons.extensions.isNotEmpty
import com.redelf.commons.extensions.randomInteger
import com.redelf.commons.extensions.randomString
import com.redelf.commons.logging.Console
import com.redelf.commons.persistance.base.Encryption
import com.redelf.commons.persistance.base.Storage
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/*
    TODO: Make sure that this is not static object
*/
object DBStorage : Storage<String> {

    /*

        TODO: Implement the mechanism to split data into chunks
        TODO: Make possible for data managers to have multiple databases - each manager its own
    */

    var DEBUG: Boolean? = null

    private const val DATABASE_VERSION = 1
    private const val DATABASE_NAME = "sdb"
    private const val DATABASE_NAME_SUFFIX_KEY = "DATABASE.NAME.SUFFIX.KEY"

    private const val TABLE_ = "dt"
    private const val COLUMN_KEY_ = "ky"
    private const val COLUMN_VALUE_ = "ct"

    private var table = TABLE_
    private var columnKey = COLUMN_KEY_
    private var columnValue = COLUMN_VALUE_

    private val executor = Executor.SINGLE
    private var enc: Encryption = NoEncryption()
    private var prefs: SharedPreferencesStorage? = null

    fun getString(source: String, key: String = DATABASE_NAME, prefsKey: String = source): String {

        var result = prefs?.get(prefsKey) ?: ""

        if (isNotEmpty(result)) {

            return result
        }

        result = source

        try {

            val bytes = enc.encrypt(key, source)

            bytes?.let {

                result = String(bytes)
            }

        } catch (e: Exception) {

            Console.error(e)
        }

        if (prefs?.put(prefsKey, result) != true) {

            Console.error("Error saving key preferences: $source")
        }

        return result
    }

    private fun table() = getString(TABLE_)

    private fun columnValue() = getString(COLUMN_VALUE_)

    private fun columnKey() = getString(COLUMN_KEY_)

    private fun sqlCreate() = "CREATE TABLE $table (" +
            "${BaseColumns._ID} INTEGER PRIMARY KEY," +
            "$columnKey TEXT," +
            "$columnValue TEXT)"

    private fun sqlDelete() = "DROP TABLE IF EXISTS $table"

    private class DbHelper(context: Context, dbName: String) :

        SQLiteOpenHelper(

            context,
            dbName,
            null,
            DATABASE_VERSION
        ),

        ContextAvailability<BaseApplication> {

        override fun onCreate(db: SQLiteDatabase) {

            try {

                db.execSQL(sqlCreate())

            } catch (e: Exception) {

                Console.error(e)
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {

            if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log(

                "Old version: $oldVersion :: New version: $newVersion"
            )
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {

            onUpgrade(db, oldVersion, newVersion)
        }

        override fun takeContext(): BaseApplication {

            return BaseApplication.takeContext()
        }

        fun closeDatabase() {

            val latch = CountDownLatch(1)

            withDb { db ->

                try {

                    if (db?.isOpen == true) {

                        db.close()
                    }

                } catch (e: Exception) {

                    Console.error(e)
                }

                latch.countDown()
            }

            latch.await()
        }
    }

    private var dbHelper: DbHelper? = null

    override fun initialize(ctx: Context) {

        val tag = "Initialize ::"

        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

        try {

            val mainKey = "$DATABASE_NAME.$DATABASE_VERSION"
            val sPrefs = ctx.getSharedPreferences(mainKey, Context.MODE_PRIVATE)
            val nPrefs = SharedPreferencesStorage(sPrefs)

            var suffix = nPrefs.get(DATABASE_NAME_SUFFIX_KEY)

            if (isEmpty(suffix)) {

                suffix = "_" + System.currentTimeMillis()

                if (!nPrefs.put(DATABASE_NAME_SUFFIX_KEY, suffix)) {

                    Console.error("Error saving key preferences: $DATABASE_NAME_SUFFIX_KEY")
                }
            }

            prefs = nPrefs

            enc = object : Encryption {

                override fun init() = true

                override fun encrypt(key: String?, value: String?): ByteArray {

                    fun getRandom(max: Int = 3) = randomInteger(max = max, min = 1)

                    fun getRandomString() = randomString(getRandom())

                    fun getSeparator() = randomString(getRandom(max = 2))

                    val builder = StringBuilder()
                    val separator = getSeparator()

                    value?.forEach { letter ->

                        builder.append(letter)
                            .append(separator)
                            .append(getRandomString())
                    }

                    return builder.toString().toByteArray()
                }

                override fun decrypt(key: String?, value: ByteArray?): String {

                    return value?.let { String(it) } ?: ""
                }
            }

            val rawName = "$mainKey.$suffix"
            val dbName = getString(rawName, prefsKey = "$DATABASE_NAME.$DATABASE_VERSION")

            table = table()
            columnKey = columnKey()
            columnValue = columnValue()

            if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log(

                "$tag dbName = '$dbName', rawName = '$rawName'"
            )

            dbHelper = DbHelper(ctx, dbName)

            if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag END")

        } catch (e: SQLException) {

            Console.error(tag, e.message ?: "Unknown error")

            Console.error(e)
        }
    }

    override fun shutdown(): Boolean {

        dbHelper?.closeDatabase()

        return true
    }

    override fun terminate(): Boolean {

        if (shutdown()) {

            return deleteDatabase()
        }

        return false
    }


    override fun put(key: String?, value: String): Boolean {

        if (isEmpty(key)) {

            return false
        }

        val tag = "Put :: $key :: column_key = $columnValue :: column_value = $columnValue ::"

        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

        val result = AtomicBoolean()
        val latch = CountDownLatch(1)

        withDb { db ->

            if (db?.isOpen == false) {

                Console.warning("DB is not open")

                latch.countDown()

                return@withDb
            }

            val res = transact(

                object : LocalDBStorageOperation<Boolean>(db) {

                    override fun perform(): Boolean {

                        try {

                            val values = ContentValues().apply {

                                put(columnKey, key)
                                put(columnValue, value)
                            }

                            val selection = "$columnKey = ?"
                            val selectionArgs = arrayOf(key)

                            val rowsUpdated = db?.update(

                                table,
                                values,
                                selection,
                                selectionArgs

                            ) ?: 0

                            if (rowsUpdated > 0) {

                                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log(

                                    "$tag END: rowsUpdated = $rowsUpdated, " +
                                            "length = ${value.length}"
                                )

                                return true
                            }

                            val rowsInserted = (db?.insert(table(), null, values) ?: 0)

                            if (rowsInserted > 0) {

                                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log(

                                    "$tag END: rowsInserted = $rowsInserted, " +
                                            "length = ${value.length}"
                                )

                                return true
                            }

                        } catch (e: Exception) {

                            Console.error(tag, e.message ?: "Unknown error")

                            Console.error(e)
                        }

                        Console.error(

                            "$tag END :: Nothing was inserted or updated, " +
                                    "length = ${value.length}"
                        )

                        return false
                    }
                }

            ) ?: false

            result.set(res)

            latch.countDown()
        }

        latch.await()

        return result.get()
    }

    override fun get(key: String?): String {

        if (isEmpty(key)) {

            return ""
        }

        var result = ""
        val selectionArgs = arrayOf(key)
        val selection = "$columnKey = ?"
        val latch = CountDownLatch(1)
        val projection = arrayOf(BaseColumns._ID, columnKey, columnValue)
        val tag = "Get :: key = $key :: column_key = $columnValue :: column_value = $columnValue ::"

        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

        withDb { db ->

            if (db?.isOpen == false) {

                Console.warning("DB is not open")

                latch.countDown()

                return@withDb
            }

            try {

                val cursor = db?.query(

                    table,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    null
                )

                cursor?.let {

                    with(it) {

                        while (moveToNext() && isEmpty(result)) {

                            result = getString(getColumnIndexOrThrow(columnValue))
                        }
                    }
                }

                cursor?.close()

            } catch (e: Exception) {

                Console.error(

                    "$tag SQL args :: Selection: $selection, Selection args:" +
                            " ${selectionArgs.toMutableList()}, projection: " +
                            "${projection.toMutableList()}", e.message ?: "Unknown error"
                )

                Console.error(e)
            }

            if (isNotEmpty(result)) {

                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag END")

            } else {

                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag END: Nothing found")
            }

            latch.countDown()
        }

        latch.await()

        return result
    }

    override fun delete(key: String?): Boolean {

        val tag = "Delete :: By key :: $key ::"

        if (isEmpty(key)) {

            Console.error("$tag Empty key")

            return false
        }

        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

        val result = AtomicBoolean()
        val latch = CountDownLatch(1)

        withDb { db ->

            if (db?.isOpen == false) {

                Console.warning("DB is not open")

                latch.countDown()

                return@withDb
            }

            val res = transact(

                object : LocalDBStorageOperation<Boolean>(db) {

                    override fun perform(): Boolean {

                        val selection = "$columnKey = ?"
                        val selectionArgs = arrayOf(key)

                        try {

                            val res = (db?.delete(table, selection, selectionArgs) ?: 0) > 0

                            if (res) {

                                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag END")

                            } else {

                                Console.error("$tag FAILED")
                            }

                            return res

                        } catch (e: Exception) {

                            Console.error(

                                "$tag ERROR :: SQL args :: Selection: $selection, " +
                                        "Selection args: " +
                                        "${selectionArgs.toMutableList()}",

                                e.message ?: "Unknown error"
                            )

                            Console.error(e)
                        }

                        return false
                    }
                }

            ) ?: false

            result.set(res)

            latch.countDown()
        }

        latch.await()

        return result.get()
    }

    override fun deleteAll(): Boolean {

        val tag = "Delete :: All ::"

        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

        val result = AtomicBoolean()
        val latch = CountDownLatch(1)

        withDb { db ->

            if (db?.isOpen == false) {

                Console.warning("DB is not open")

                latch.countDown()

                return@withDb
            }

            val res = transact(

                object : LocalDBStorageOperation<Boolean>(db) {

                    override fun perform(): Boolean {

                        try {

                            val res = (db?.delete(table, null, null) ?: 0) > 0

                            if (res) {

                                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag END")

                            } else {

                                Console.error("$tag FAILED")
                            }

                            return res

                        } catch (e: Exception) {

                            Console.error(

                                "$tag ERROR :: SQL args :: " +
                                        "TO DELETE ALL", e.message ?: "Unknown error"
                            )

                            Console.error(e)
                        }

                        return false
                    }
                }

            ) ?: false

            result.set(res)

            latch.countDown()
        }

        latch.await()

        return result.get()
    }

    private fun deleteDatabase(): Boolean {

        val tag = "Delete :: Database ::"

        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

        try {

            val dbName = dbHelper?.databaseName
            val context = dbHelper?.takeContext()
            val result = context?.deleteDatabase(dbName) ?: false

            if (result) {

                if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log(

                    "$tag END: DB '$dbName' has been deleted"
                )

                if (prefs?.delete(DATABASE_NAME_SUFFIX_KEY) != true) {

                    Console.error("Error deleting key preferences: $DATABASE_NAME_SUFFIX_KEY")
                }

                if (prefs?.delete(TABLE_) != true) {

                    Console.error("Error deleting key preferences: $TABLE_")
                }

                if (prefs?.delete(COLUMN_KEY_) != true) {

                    Console.error("Error deleting key preferences: $COLUMN_KEY_")
                }

                if (prefs?.delete(COLUMN_VALUE_) != true) {

                    Console.error("Error deleting key preferences: $COLUMN_VALUE_")
                }

                val dbKey = "$DATABASE_NAME.$DATABASE_VERSION"

                if (prefs?.delete(dbKey) != true) {

                    Console.error("Error deleting key preferences: $dbKey")
                }

            } else {

                Console.error("$tag FAILED")
            }

            return result

        } catch (e: Exception) {

            Console.error(

                "$tag ERROR :: SQL args :: " +
                        "TO DELETE DB", e.message ?: "Unknown error"
            )

            Console.error(e)
        }

        return false
    }

    override fun contains(key: String?): Boolean {

        return isNotEmpty(get(key))
    }

    override fun count(): Long {

        val result = AtomicLong()
        val latch = CountDownLatch(1)

        withDb { db ->

            if (db?.isOpen == false) {

                Console.warning("DB is not open")

                latch.countDown()

                return@withDb
            }

            try {

                val projection = arrayOf(BaseColumns._ID, columnKey, columnValue)

                val cursor = db?.query(

                    table,
                    projection,
                    null,
                    null,
                    null,
                    null,
                    null
                )

                val res = cursor?.count?.toLong() ?: 0
                result.set(res)

                cursor?.close()

            } catch (e: Exception) {

                Console.error(e)
            }

            latch.countDown()
        }

        latch.await()

        return result.get()
    }

    private abstract class LocalDBStorageOperation<T>(db: SQLiteDatabase?) :
        DBStorageOperation<T>(db)

    private fun <T> transact(operation: DBStorageOperation<T>): T? {

        val db = operation.db
        var success: T? = null

        db?.let {

            try {

                it.beginTransaction()

                success = operation.perform()

                success?.let {

                    db.setTransactionSuccessful()
                }

            } catch (e: Exception) {

                Console.error(e)

            } finally {

                try {

                    db.endTransaction()

                } catch (e: Exception) {

                    Console.error(e)
                }
            }
        }

        return success
    }

    private fun withDb(doWhat: (db: SQLiteDatabase?) -> Unit) {

        var tag = "With DB :: doWhat = ${doWhat.hashCode()} ::"

        try {

            val db = dbHelper?.writableDatabase

            tag = "$tag db = ${db?.hashCode()} ::"

            if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag START")

            db?.let {

                if (db.isOpen) {

                    if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag EXECUTING")

                    executor.execute {

                        doWhat(db)

                        if (DEBUG ?: BaseApplication.DEBUG.get()) Console.log("$tag EXECUTED")
                    }

                } else {

                    Console.warning("$tag DB is not open")
                }
            }

            if (db == null) {

                Console.error("$tag DB is null")
            }

        } catch (e: Exception) {

            Console.error("$tag ERROR ::", e.message ?: "Unknown error")

            Console.error(e)
        }
    }
}
