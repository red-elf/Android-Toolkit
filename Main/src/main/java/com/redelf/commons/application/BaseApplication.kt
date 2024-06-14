@file:Suppress("DEPRECATION")

package com.redelf.commons.application

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.NameNotFoundException
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.profileinstaller.ProfileInstaller
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import com.redelf.commons.R
import com.redelf.commons.activity.ActivityCount
import com.redelf.commons.context.ContextAvailability
import com.redelf.commons.extensions.detectAllExpect
import com.redelf.commons.extensions.exec
import com.redelf.commons.extensions.isEmpty
import com.redelf.commons.extensions.isNotEmpty
import com.redelf.commons.extensions.recordException
import com.redelf.commons.fcm.FcmService
import com.redelf.commons.firebase.FirebaseConfigurationManager
import com.redelf.commons.logging.Console
import com.redelf.commons.management.DataManagement
import com.redelf.commons.management.managers.ManagersInitializer
import com.redelf.commons.persistance.SharedPreferencesStorage
import com.redelf.commons.updating.Updatable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

abstract class BaseApplication :

    Application(),
    ContextAvailability<BaseApplication>,
    ActivityLifecycleCallbacks,
    ActivityCount,
    LifecycleObserver,
    Updatable<Long>

{

    companion object : ContextAvailability<BaseApplication>, ApplicationInfo {

        val DEBUG = AtomicBoolean()
        val STRICT_MODE_DISABLED = AtomicBoolean()

        @SuppressLint("StaticFieldLeak")
        lateinit var CONTEXT: BaseApplication

        var TOP_ACTIVITY = mutableListOf<Class<out Activity>>()
        var TOP_ACTIVITIES = mutableListOf<Class<out Activity>>()

        const val ACTIVITY_LIFECYCLE_TAG = "Activity lifecycle ::"
        const val BROADCAST_ACTION_APPLICATION_SCREEN_OFF = "APPLICATION_STATE.SCREEN_OFF"
        const val BROADCAST_ACTION_APPLICATION_STATE_BACKGROUND = "APPLICATION_STATE.BACKGROUND"
        const val BROADCAST_ACTION_APPLICATION_STATE_FOREGROUND = "APPLICATION_STATE.FOREGROUND"

        override fun takeContext() = CONTEXT

        private var isAppInBackground = AtomicBoolean()

        fun restart(context: Context) {

            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            val mainIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }

        override fun getName(): String {

            try {

                val context = takeContext()
                val pm = context.packageManager
                val packageInfo = pm.getPackageInfo(context.packageName, 0)
                val ai = packageInfo.applicationInfo

                return if (ai != null) pm.getApplicationLabel(ai) as String else "Unknown"

            } catch (e: NameNotFoundException) {

                recordException(e)
            }

            return "Unknown"
        }

        override fun getVersion(): String {

            try {

                val context = takeContext()
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

                return packageInfo.versionName

            } catch (e: NameNotFoundException) {

                recordException(e)
            }

            return ""
        }

        @Suppress("DEPRECATION")
        @SuppressLint("ObsoleteSdkInt")
        override fun getVersionCode(): String {

            try {

                val context = takeContext()
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

                    packageInfo.longVersionCode.toString()

                } else {

                    packageInfo.versionCode.toString()
                }

            } catch (e: NameNotFoundException) {

                recordException(e)
            }

            return ""
        }
    }

    private val prefsKeyUpdate = "Preferences.Update"
    private var telecomManager: TelecomManager? = null
    private var telephonyManager: TelephonyManager? = null
    private val registeredForPhoneCallsDetection = AtomicBoolean()
    private val registeredForAudioFocusDetection = AtomicBoolean()

    protected open val managers = mutableListOf<List<DataManagement<*>>>(

        listOf(FirebaseConfigurationManager)
    )

    val defaultManagerResources = mutableMapOf<Class<*>, Int>()

    open val firebaseEnabled = true
    open val detectAudioStreamed = false
    open val detectPhoneCallReceived = false

    open fun canRecordApplicationLogs() = false

    protected abstract fun isProduction(): Boolean

    protected abstract fun onDoCreate()

    protected abstract fun takeSalt(): String

    protected open fun populateManagers() = listOf<List<DataManagement<*>>>()

    protected open fun populateDefaultManagerResources() = mapOf<Class<*>, Int>()

    protected lateinit var prefs: SharedPreferencesStorage

    private val screenReceiver: BroadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            if (Intent.ACTION_SCREEN_ON == intent.action) {

                onScreenOn()
                return
            }

            if (Intent.ACTION_SCREEN_OFF == intent.action) {

                onScreenOff()
            }
        }
    }

    private val fcmTokenReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            intent?.let {

                if (FcmService.BROADCAST_ACTION_TOKEN == it.action) {

                    val token = it.getStringExtra(FcmService.BROADCAST_KEY_TOKEN)

                    token?.let { tkn ->

                        if (isNotEmpty(tkn)) {

                            onFcmToken(tkn)
                        }
                    }
                }
            }
        }
    }

    private val fcmEventReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            intent?.let {

                if (FcmService.BROADCAST_ACTION_EVENT == intent.action) {

                    onFcmEvent(it)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {

            super.onCallStateChanged(state, phoneNumber)

            when (state) {

                TelephonyManager.CALL_STATE_RINGING -> {

                    onPhoneIsRinging()
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {

                    Console.log("Phone is OFF-HOOK")
                }

                TelephonyManager.CALL_STATE_IDLE -> {

                    Console.log("Phone is IDLE")
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->

        when (focusChange) {

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                Console.debug("Audio focus :: Transient or can dock")

                onExternalStreamStarted()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {

                Console.log("Audio focus :: Gained")
            }

            AudioManager.AUDIOFOCUS_LOSS -> {

                onExternalStreamStarted()
            }
        }
    }

    fun registerPhoneStateListener() {

        val tag = "Register phone state listener ::"

        Console.log("$tag START")

        if (registeredForPhoneCallsDetection.get()) {

            Console.log("$tag Already registered")

            return
        }

        if (detectPhoneCallReceived) {

            Console.log("$tag Phone calls detection enabled")

            telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            try {

                @Suppress("DEPRECATION")
                telephonyManager?.listen(

                    phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE
                )

                Console.log("$tag Phone state listener registered with success")

                registeredForPhoneCallsDetection.set(true)

            } catch (e: SecurityException) {

                Console.error(tag, e)
            }

        } else {

            Console.log("$tag Phone calls detection disabled")
        }
    }

    fun registerAudioFocusChangeListener() {

        val tag = "Register audio focus listener ::"

        Console.log("$tag START")

        if (registeredForAudioFocusDetection.get()) {

            Console.log("$tag Already registered")

            return
        }

        if (detectAudioStreamed) {

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?

            val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioManager?.requestAudioFocus(audioFocusRequest)

            Console.log("$tag END")

        } else {

            Console.log("$tag Audio focus detection disabled")
        }
    }

    protected open fun onPhoneIsRinging() {

        Console.log("Phone is RINGING")
    }

    protected open fun onExternalStreamStarted() {

        Console.debug("Audio focus :: Lost")
    }

    override fun takeContext() = CONTEXT

    protected open fun isStrictModeDisabled() = !DEBUG.get()

    override fun onCreate() {
        super.onCreate()

        prefs = SharedPreferencesStorage(applicationContext)

        disableActivityAnimations(applicationContext)

        CONTEXT = this
        DEBUG.set(CONTEXT.resources.getBoolean(R.bool.debug))
        STRICT_MODE_DISABLED.set(isStrictModeDisabled())

        if (DEBUG.get() || canRecordApplicationLogs()) {

            Console.initialize(canRecordApplicationLogs(), production = isProduction())

            Console.info("Application :: Initializing")

            enableStrictMode()
        }

        DataManagement.initialize(applicationContext)

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerActivityLifecycleCallbacks(this)

        managers.addAll(populateManagers())
        defaultManagerResources.putAll(populateDefaultManagerResources())

        doCreate()
    }

    private fun doCreate() {

        try {

            exec {

                onDoCreate()

                val intentFilter = IntentFilter()
                intentFilter.addAction(Intent.ACTION_SCREEN_ON)
                intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
                registerReceiver(screenReceiver, intentFilter)

                initializeManagers()
                onManagers()

                Console.log("Installing profile: START")
                ProfileInstaller.writeProfile(applicationContext)
                Console.log("Installing profile: END")
            }

        } catch (e: RejectedExecutionException) {

            recordException(e)

            throw e
        }
    }

    protected open fun onScreenOn() {

        Console.log("Screen is ON")
    }

    protected open fun onScreenOff() {

        Console.log("Screen is OFF")

        val intent = Intent(BROADCAST_ACTION_APPLICATION_SCREEN_OFF)
        sendBroadcast(intent)
    }

    protected open fun onFcmToken(token: String) {

        Console.log("FCM: Token => $token")
    }

    protected open fun onFcmEvent(intent: Intent) {

        Console.log("FCM: Event => $intent")
    }

    protected open fun onManagersReady() {

        Console.info("Managers: Ready")
    }

    private fun initializeManagers(): Boolean {

        var success = true

        managers.forEach {

            val result = ManagersInitializer().initializeManagers(

                managers = it,
                context = this,
                defaultResources = defaultManagerResources
            )

            if (!result) {

                success = false
            }
        }

        return success
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initializeFcm() {

        if (!firebaseEnabled) {

            return
        }

        Console.info("FCM: Initializing")

        val tokenFilter = IntentFilter(FcmService.BROADCAST_ACTION_TOKEN)
        val eventFilter = IntentFilter(FcmService.BROADCAST_ACTION_EVENT)

        registerReceiver(fcmTokenReceiver, tokenFilter)
        registerReceiver(fcmEventReceiver, eventFilter)

        FirebaseMessaging.getInstance()
            .token
            .addOnCompleteListener(

                OnCompleteListener { task ->

                    if (!task.isSuccessful) {

                        Console.warning("FCM: Fetching registration token failed", task.exception)
                        return@OnCompleteListener
                    }

                    val token = task.result

                    if (isNotEmpty(token)) {

                        Console.info("FCM: Initialized, token => $token")

                        onFcmToken(token)

                    } else {

                        Console.info("FCM: Initialized with no token")
                    }
                }
            )
    }

    override fun getActivityCount(): Int {

        return TOP_ACTIVITY.size
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        super.onActivityPreCreated(activity, savedInstanceState)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

        // Ignore
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {

        // Ignore

        super.onActivityPostCreated(activity, savedInstanceState)
    }

    override fun onActivityPreStarted(activity: Activity) {

        // Ignore

        super.onActivityPreStarted(activity)
    }

    override fun onActivityStarted(activity: Activity) {

        // Ignore
    }

    override fun onActivityPostStarted(activity: Activity) {

        // Ignore

        super.onActivityPostStarted(activity)
    }

    override fun onActivityPreResumed(activity: Activity) {

        val clazz = activity::class.java

        TOP_ACTIVITY.add(clazz)
        TOP_ACTIVITIES.add(clazz)

        Console.log("$ACTIVITY_LIFECYCLE_TAG PRE-RESUMED :: ${clazz.simpleName}")

        Console.debug("$ACTIVITY_LIFECYCLE_TAG Top activity: ${clazz.simpleName}")

        super.onActivityPreResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG PAUSED :: ${activity.javaClass.simpleName}")
    }

    override fun onActivityResumed(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG RESUMED :: ${activity.javaClass.simpleName}")

        if (isAppInBackground.get()) {

            val intent = Intent(BROADCAST_ACTION_APPLICATION_STATE_FOREGROUND)
            sendBroadcast(intent)

            Console.debug("$ACTIVITY_LIFECYCLE_TAG Foreground")
        }

        isAppInBackground.set(false)
    }

    override fun onActivityPostResumed(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG POST-RESUMED :: ${activity.javaClass.simpleName}")

        super.onActivityPostResumed(activity)
    }

    override fun onActivityPrePaused(activity: Activity) {

        val clazz = activity::class.java

        TOP_ACTIVITIES.remove(clazz)

        Console.log("$ACTIVITY_LIFECYCLE_TAG PRE-PAUSED :: ${activity.javaClass.simpleName}")

        Console.debug("$ACTIVITY_LIFECYCLE_TAG Top activity: ${clazz.simpleName}")

        super.onActivityPrePaused(activity)
    }

    override fun onActivityPostPaused(activity: Activity) {

        Console.log(

            "$ACTIVITY_LIFECYCLE_TAG POST-PAUSED :: ${activity.javaClass.simpleName}, " +
                    "Active: ${TOP_ACTIVITY.size}"
        )

        if (TOP_ACTIVITIES.size <= 1) {

            onAppBackgroundState()
        }

        super.onActivityPostPaused(activity)
    }

    override fun onActivityPreStopped(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG PRE-STOPPED :: ${activity.javaClass.simpleName}")

        super.onActivityPreStopped(activity)
    }

    override fun onActivityStopped(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG STOPPED :: ${activity.javaClass.simpleName}")
    }

    override fun onActivityPostStopped(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG POST-STOPPED :: ${activity.javaClass.simpleName}")

        super.onActivityPostStopped(activity)
    }

    override fun onActivityPreSaveInstanceState(activity: Activity, outState: Bundle) {

        // Ignore

        super.onActivityPreSaveInstanceState(activity, outState)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

        // Ignore
    }

    override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {

        // Ignore

        super.onActivityPostSaveInstanceState(activity, outState)
    }

    override fun onActivityPreDestroyed(activity: Activity) {

        val iterator = TOP_ACTIVITY.iterator()

        while (iterator.hasNext()) {

            val item = iterator.next()

            if (item == activity::class.java) {

                iterator.remove()
            }
        }

        Console.log("$ACTIVITY_LIFECYCLE_TAG PRE-DESTROYED :: ${activity.javaClass.simpleName}")

        if (TOP_ACTIVITIES.isEmpty()) {

            Console.debug("$ACTIVITY_LIFECYCLE_TAG No top activity")

            onAppBackgroundState()

        } else {

            if (TOP_ACTIVITY.isNotEmpty()) {

                val clazz = TOP_ACTIVITY.last()

                Console.debug("$ACTIVITY_LIFECYCLE_TAG Top activity: ${clazz.simpleName}")
            }
        }

        super.onActivityPreDestroyed(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {

        Console.log("$ACTIVITY_LIFECYCLE_TAG DESTROYED :: ${activity.javaClass.simpleName}")
    }

    override fun onActivityPostDestroyed(activity: Activity) {

        // Ignore

        super.onActivityPostDestroyed(activity)
    }

    private fun onManagers() {

        initializeFcm()
        onManagersReady()
        update()
    }

    private fun enableStrictMode() {

        Console.log("Enable Strict Mode, disabled=$STRICT_MODE_DISABLED")

        if (STRICT_MODE_DISABLED.get()) {

            return
        }

        StrictMode.setThreadPolicy(

            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )

        StrictMode.setVmPolicy(

            StrictMode.VmPolicy.Builder()
                .detectAllExpect("android.os.StrictMode.onUntaggedSocket")
                .build()
        )
    }

    @Suppress("DEPRECATION")
    protected fun disableActivityAnimations(context: Context) {

        try {

            val scale = 0
            val contentResolver = context.contentResolver

            Settings.System.putFloat(
                contentResolver,
                Settings.System.WINDOW_ANIMATION_SCALE,
                scale.toFloat()
            )

            Settings.System.putFloat(
                contentResolver,
                Settings.System.TRANSITION_ANIMATION_SCALE,
                scale.toFloat()
            )

            Settings.System.putFloat(
                contentResolver,
                Settings.System.ANIMATOR_DURATION_SCALE,
                scale.toFloat()
            )

        } catch (e: Throwable) {

            Console.error(e)
        }
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {

        receiver?.let { r ->
            filter?.let { f ->

                LocalBroadcastManager.getInstance(applicationContext).registerReceiver(r, f)
            }
        }

        return null
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver?) {

        receiver?.let {

            LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(it)
        }
    }

    override fun sendBroadcast(intent: Intent?) {

        intent?.let {

            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(it)
        }
    }

    protected open fun getUpdatesCodes() = setOf<Long>()

    override fun update() {

        /*
            TODO: Integrate DataMigration recipes with the updates
         */

        var versionCode = 0

        val tag = "Update ::"

        try {

            versionCode = getVersionCode().toInt()

        } catch (e: NumberFormatException) {

            onUpdatedFailed(0)

            Console.error(e)
        }

        getUpdatesCodes().forEach { code ->

            /*
                TODO: Incorporate until which version code is the update applicable (if needed)
            */
            if (versionCode >= code && isUpdateAvailable(code)) {

                Console.log("$tag Code :: $versionCode :: START")

                val success = update(code)

                if (success) {

                    onUpdated(code)

                } else {

                    onUpdatedFailed(code)
                }

                Console.log("$tag Code :: $versionCode :: END")
            }
        }
    }

    override fun update(identifier: Long) = false

    override fun onUpdatedFailed(identifier: Long) {

        val msg = "Failed to update, versionCode = ${getVersionCode()}, " +
                "identifier = $identifier"

        val error = IllegalStateException(msg)
        recordException(error)
    }

    override fun onUpdated(identifier: Long) {

        val tag = "Update ::"
        val key = "$prefsKeyUpdate.$identifier"
        val result = prefs.put(key, "$identifier")

        val msg = "$tag Success: versionCode = ${getVersionCode()}, " +
                "identifier = $identifier"

        Console.debug(msg)

        if (!result) {

            Console.error("$tag Failed to update preferences :: key = '$key'")
        }
    }

    override fun isUpdateApplied(identifier: Long) = !isUpdateAvailable(identifier)

    protected fun isUpdateAvailable(identifier: Long): Boolean {

        val key = "$prefsKeyUpdate.$identifier"
        val value = prefs[key]
        val updateAvailable = isEmpty(value)

        if (updateAvailable) {

            Console.log("Update :: Available :: identifier = '$identifier'")

        } else {

            Console.log("Update :: Already applied :: identifier = '$identifier'")
        }

        return updateAvailable
    }

    private fun onAppBackgroundState() {

        isAppInBackground.set(true)

        val intent = Intent(BROADCAST_ACTION_APPLICATION_STATE_BACKGROUND)
        sendBroadcast(intent)

        Console.debug("$ACTIVITY_LIFECYCLE_TAG Background")
    }
}