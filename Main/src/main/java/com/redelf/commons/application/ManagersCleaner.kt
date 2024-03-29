package com.redelf.commons.application

import android.content.Context
import com.redelf.commons.context.Contextual
import com.redelf.commons.defaults.ResourceDefaults
import com.redelf.commons.exec
import com.redelf.commons.management.DataManagement
import com.redelf.commons.management.Management
import com.redelf.commons.persistance.EncryptedPersistence
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock

class ManagersCleaner {

    interface CleanupCallback {

        fun onCleanup(success: Boolean, error: Throwable? = null)

        fun onCleanup(manager: Management, success: Boolean, error: Throwable? = null)
    }

    fun cleanupManagers(

        managers: List<Management>,
        callback: CleanupCallback,

    ) {

        val tag = "Managers :: Cleanup ::"

        try {

            Timber.v("$tag START")

            exec {

                managers.forEach { manager ->

                    if (manager is DataManagement<*>) {

                        manager.lock()

                        Timber.v(

                            "$tag Manager :: ${manager.javaClass.simpleName} :: LOCKED"
                        )
                    }
                }

                val failure = AtomicBoolean()

                managers.forEach { manager ->

                    Timber.v("$tag Manager :: ${manager.javaClass.simpleName}")

                    if (manager is DataManagement<*>) {

                        if (manager.reset()) {

                            Timber.v(

                                "$tag Manager :: ${manager.javaClass.simpleName} :: " +
                                        "Cleaned"
                            )

                        } else {

                            Timber.w(

                                "$tag Manager :: ${manager.javaClass.simpleName} :: " +
                                        "Not cleaned"
                            )

                            failure.set(true)
                        }

                    } else {

                        Timber.w(

                            "$tag Manager :: ${manager.javaClass.simpleName} :: " +
                                    "SKIPPED: Not data manager"
                        )
                    }
                }

                managers.forEach { manager ->

                    if (manager is DataManagement<*>) {

                        manager.unlock()

                        Timber.v(

                            "$tag Manager :: ${manager.javaClass.simpleName} :: UNLOCKED"
                        )
                    }
                }

                callback.onCleanup(!failure.get())
            }

        } catch (e: RejectedExecutionException) {

            callback.onCleanup(false, e)
        }
    }
}