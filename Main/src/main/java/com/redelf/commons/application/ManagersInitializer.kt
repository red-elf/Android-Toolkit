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

class ManagersInitializer {

    interface InitializationCallback {

        fun onInitialization(success: Boolean, error: Throwable? = null)

        fun onInitialization(manager: Management, success: Boolean, error: Throwable? = null)
    }

    fun initializeManagers(

        managers: List<Management>,
        callback: InitializationCallback,
        persistence: EncryptedPersistence? = null,
        context: Context? = null,
        defaultResources: Map<Class<*>, Int>? = null

    ) {

        try {

            exec {

                val failure = AtomicBoolean()

                managers.forEach { manager ->

                    if (failure.get()) {

                        Timber.e(

                            "Manager: ${manager.getWho()} initialization skipped"
                        )

                        return@exec
                    }

                    if (manager is DataManagement<*>) {

                        val latch = CountDownLatch(1)

                        val lifecycleCallback = object : ManagerLifecycleCallback() {

                            override fun onInitialization(

                                success: Boolean,
                                vararg args: EncryptedPersistence

                            ) {

                                if (success) {

                                    Timber.v(

                                        "Manager: ${manager.getWho()} " +
                                                "initialization completed with success " +
                                                "(${manager.isInitialized()})"
                                    )

                                } else {

                                    failure.set(true)
                                }

                                callback.onInitialization(manager, success)

                                latch.countDown()
                            }
                        }

                        if (manager is Contextual) {

                            context?.let { ctx ->

                                Timber.v(

                                    "Manager: ${manager.getWho()} " +
                                            "injecting context: $ctx"
                                )

                                manager.injectContext(ctx)
                            }
                        }

                        if (manager is ResourceDefaults) {

                            val defaultResource = defaultResources?.get(manager.javaClass)
                            defaultResource?.let {

                                Timber.v(

                                    "Manager: ${manager.getWho()} " +
                                            "setting defaults from resource: $defaultResource"
                                )

                                manager.setDefaults(it)
                            }
                        }

                        if (manager.initializationReady()) {

                            manager.initialize(lifecycleCallback, persistence = persistence)

                        } else {

                            Timber.w(

                                "Manager: " +
                                        "${manager.getWho()} not initialization ready"
                            )
                        }

                        latch.await()
                    }
                }

                callback.onInitialization(!failure.get())
            }

        } catch (e: RejectedExecutionException) {

            callback.onInitialization(false, e)
        }
    }
}