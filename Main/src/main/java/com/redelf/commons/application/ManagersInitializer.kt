package com.redelf.commons.application

import android.content.Context
import com.redelf.commons.context.Contextual
import com.redelf.commons.exec
import com.redelf.commons.management.DataManagement
import com.redelf.commons.management.Management
import com.redelf.commons.persistance.EncryptedPersistence
import timber.log.Timber
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class ManagersInitializer {

    interface InitializationCallback {
        fun onInitialization(success: Boolean, error: Throwable? = null)
    }

    fun initializeManagers(

        managers: List<Management>,
        callback: InitializationCallback,
        persistence: EncryptedPersistence? = null,
        context: Context? = null

    ) {

        try {

            exec {

                val failure = AtomicBoolean()

                managers.forEach { manager ->

                    if (failure.get()) {

                        Timber.e(

                            "Manager: ${manager.javaClass.simpleName} initialization skipped"
                        )

                        return@exec
                    }

                    if (manager is DataManagement<*>) {

                        val lifecycleCallback = object : ManagerLifecycleCallback() {

                            override fun onInitialization(

                                success: Boolean,
                                vararg args: EncryptedPersistence

                            ) {

                                if (success) {

                                    Timber.v(

                                        "Manager: ${manager.javaClass.simpleName} " +
                                                "initialization completed with success"
                                    )

                                    callback.onInitialization(true)

                                } else {

                                    val error = IllegalStateException(

                                        "Manager: ${manager.javaClass.simpleName} " +
                                                "initialization completed with failure"
                                    )

                                    callback.onInitialization(false, error)

                                    failure.set(true)
                                }
                            }
                        }

                        if (manager is Contextual) {

                            context?.let { ctx ->

                                manager.injectContext(ctx)
                            }
                        }

                        manager.initialize(lifecycleCallback, persistence = persistence)
                    }
                }
            }

        } catch (e: RejectedExecutionException) {

            callback.onInitialization(false, e)
        }
    }
}