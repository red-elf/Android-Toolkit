package com.redelf.commons.scheduling.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.redelf.commons.Registration
import com.redelf.commons.callback.CallbackOperation
import com.redelf.commons.callback.Callbacks
import timber.log.Timber

class AlarmReceiver : BroadcastReceiver() {

    companion object : Registration<AlarmCallback> {

        private val callbacks: Callbacks<AlarmCallback> = Callbacks("AlarmReceiver")

        override fun register(subscriber: AlarmCallback) {

            callbacks.register(subscriber)
        }

        override fun unregister(subscriber: AlarmCallback) {

            callbacks.unregister(subscriber)
        }

        override fun isRegistered(subscriber: AlarmCallback): Boolean {

            return callbacks.isRegistered(subscriber)
        }

        private fun onAlarmReceived(value: Int) {

            callbacks.doOnAll(

                operation = object : CallbackOperation<AlarmCallback> {

                    override fun perform(callback: AlarmCallback) {

                        callback.onAlarm(value)
                    }
                },

                name = "onAlarmReceived: $value"
            )
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {

        Timber.v("Alarm received: $intent")

        intent?.let {

            when (intent.action) {

                AlarmScheduler.ALARM_ACTION -> {

                    val alarmValue = intent.getIntExtra(AlarmScheduler.ALARM_VALUE, -1)
                    onAlarmReceived(alarmValue)
                }
            }
        }
    }
}
