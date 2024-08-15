package com.redelf.commons.interprocess

import android.content.Intent
import com.google.gson.Gson
import com.redelf.commons.extensions.isEmpty
import com.redelf.commons.logging.Console
import com.redelf.commons.processing.Process

abstract class InterprocessProcessor : Process<Intent> {

    companion object {

        const val EXTRA_DATA = "data"
    }

    override fun process(input: Intent) {

        val data = input.getStringExtra(EXTRA_DATA)

        if (isEmpty(data)) {

            Console.error("Received empty data")
            return
        }

        try {

            val ipcData: InterprocessData? = Gson().fromJson(data, InterprocessData::class.java)

            if (ipcData == null) {

                Console.error("Failed to parse the IPC data")
                return
            }

            onData(ipcData)

        } catch (e: Exception) {

            Console.error("Error processing data: ${e.message}")
            Console.error(e)
        }
    }

    abstract fun onData(data: InterprocessData)
}