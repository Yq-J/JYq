package com.yqj.onesignaltester

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import com.onesignal.notifications.IPermissionObserver
import com.onesignal.user.state.IUserStateObserver
import com.onesignal.user.state.UserChangedState
import com.onesignal.user.subscriptions.IPushSubscriptionObserver
import com.onesignal.user.subscriptions.PushSubscriptionChangedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized OneSignal integration. No other app class calls the SDK directly.
 */
object OneSignalManager {
    data class State(
        val initialized: Boolean = false,
        val initializing: Boolean = false,
        val sdkVersion: String = "",
        val oneSignalId: String = "",
        val externalId: String = "",
        val subscriptionId: String = "",
        val pushToken: String = "",
        val optedIn: Boolean = false,
        val notificationPermission: Boolean = false,
        val error: String? = null,
    )

    private const val PREFERENCES_NAME = "onesignal_tester"
    private const val DIALOG_SHOWN_KEY = "verification_dialog_shown"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initializationStarted = AtomicBoolean(false)
    private val dialogShown = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    @Volatile
    private var initialized = false

    @Volatile
    private var initializing = false

    @Volatile
    private var initializationError: String? = null

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var activityReference: WeakReference<Activity>? = null

    // OneSignal holds observers weakly, so retain them for the app process lifetime.
    private var pushSubscriptionObserver: IPushSubscriptionObserver? = null
    private var permissionObserver: IPermissionObserver? = null
    private var userStateObserver: IUserStateObserver? = null

    fun initialize(context: Context) {
        if (!initializationStarted.compareAndSet(false, true)) return

        applicationContext = context.applicationContext
        dialogShown.set(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(DIALOG_SHOWN_KEY, false),
        )
        initializing = true
        dispatchState()

        ioScope.launch {
            try {
                setVerboseLogging(true)
                val success = OneSignal.initWithContextSuspend(
                    context.applicationContext,
                    BuildConfig.ONESIGNAL_APP_ID,
                )
                check(success) { "OneSignal SDK initialization returned false" }

                registerObservers()
                initialized = true
                initializationError = null

                // Evaluate immediately in case registration completed before the observer attached.
                maybeShowIntegrationCompleteDialog(OneSignal.User.pushSubscription.id)
            } catch (throwable: Throwable) {
                initializationError = throwable.message ?: throwable.javaClass.simpleName
            } finally {
                initializing = false
                dispatchState()
            }
        }
    }

    private fun registerObservers() {
        if (pushSubscriptionObserver == null) {
            pushSubscriptionObserver = object : IPushSubscriptionObserver {
                override fun onPushSubscriptionChange(state: PushSubscriptionChangedState) {
                    maybeShowIntegrationCompleteDialog(state.current.id)
                    dispatchState()
                }
            }.also { observer ->
                OneSignal.User.pushSubscription.addObserver(observer)
            }
        }

        if (permissionObserver == null) {
            permissionObserver = object : IPermissionObserver {
                override fun onNotificationPermissionChange(permission: Boolean) {
                    dispatchState()
                }
            }.also { observer ->
                OneSignal.Notifications.addPermissionObserver(observer)
            }
        }

        if (userStateObserver == null) {
            userStateObserver = object : IUserStateObserver {
                override fun onUserStateChange(state: UserChangedState) {
                    dispatchState()
                }
            }.also { observer ->
                OneSignal.User.addObserver(observer)
            }
        }
    }

    fun attachActivity(activity: Activity) {
        activityReference = WeakReference(activity)
        ioScope.launch {
            if (initialized) {
                // Evaluate immediately again because the ID may already exist before UI attachment.
                maybeShowIntegrationCompleteDialog(OneSignal.User.pushSubscription.id)
            }
            dispatchState()
        }
    }

    fun detachActivity(activity: Activity) {
        if (activityReference?.get() === activity) {
            activityReference?.clear()
            activityReference = null
        }
    }

    fun addStateListener(listener: (State) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(snapshot()) }
    }

    fun removeStateListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun refresh() {
        dispatchState()
    }

    fun login(externalId: String) = runSdkOperation {
        require(externalId.isNotBlank()) { "External ID 不能为空" }
        OneSignal.login(externalId.trim())
    }

    fun logout() = runSdkOperation {
        OneSignal.logout()
    }

    fun addEmail(email: String) = runSdkOperation {
        require(email.isNotBlank()) { "Email 不能为空" }
        OneSignal.User.addEmail(email.trim())
    }

    fun removeEmail(email: String) = runSdkOperation {
        require(email.isNotBlank()) { "Email 不能为空" }
        OneSignal.User.removeEmail(email.trim())
    }

    fun addSms(e164Number: String) = runSdkOperation {
        require(e164Number.isNotBlank()) { "手机号不能为空" }
        OneSignal.User.addSms(e164Number.trim())
    }

    fun removeSms(e164Number: String) = runSdkOperation {
        require(e164Number.isNotBlank()) { "手机号不能为空" }
        OneSignal.User.removeSms(e164Number.trim())
    }

    fun addTag(key: String, value: String) = runSdkOperation {
        require(key.isNotBlank()) { "Tag Key 不能为空" }
        OneSignal.User.addTag(key.trim(), value.trim())
    }

    fun removeTag(key: String) = runSdkOperation {
        require(key.isNotBlank()) { "Tag Key 不能为空" }
        OneSignal.User.removeTag(key.trim())
    }

    fun setVerboseLogging(enabled: Boolean) {
        OneSignal.Debug.logLevel = if (enabled) LogLevel.VERBOSE else LogLevel.WARN
    }

    private fun runSdkOperation(operation: () -> Unit) {
        ioScope.launch {
            if (!initialized) {
                initializationError = "OneSignal 尚未完成初始化"
                dispatchState()
                return@launch
            }

            try {
                operation()
                initializationError = null
            } catch (throwable: Throwable) {
                initializationError = throwable.message ?: throwable.javaClass.simpleName
            }
            dispatchState()
        }
    }

    private fun isServerAssignedSubscription(subscriptionId: String?): Boolean {
        return !subscriptionId.isNullOrBlank() && !subscriptionId.startsWith("local-")
    }

    private fun maybeShowIntegrationCompleteDialog(subscriptionId: String?) {
        if (!isServerAssignedSubscription(subscriptionId) || dialogShown.get()) return

        mainHandler.post {
            val activity = activityReference?.get()
            if (
                activity == null ||
                activity.isFinishing ||
                activity.isDestroyed ||
                !dialogShown.compareAndSet(false, true)
            ) {
                return@post
            }

            applicationContext
                ?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(DIALOG_SHOWN_KEY, true)
                ?.apply()

            AlertDialog.Builder(activity)
                .setTitle("Your OneSignal SDK integration is complete!")
                .setMessage(
                    "You can now send Push Notifications & In-App Messages through OneSignal. " +
                        "Tap below to enable push notifications.",
                )
                .setPositiveButton("Got it") { _, _ ->
                    requestPushPermission()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun requestPushPermission() {
        mainScope.launch {
            try {
                OneSignal.Notifications.requestPermission(true)
                initializationError = null
            } catch (throwable: Throwable) {
                initializationError = throwable.message ?: throwable.javaClass.simpleName
            }
            dispatchState()
        }
    }

    private fun snapshot(): State {
        if (!initialized) {
            return State(
                initialized = false,
                initializing = initializing,
                error = initializationError,
            )
        }

        return try {
            val subscription = OneSignal.User.pushSubscription
            State(
                initialized = true,
                initializing = false,
                sdkVersion = OneSignal.sdkVersion,
                oneSignalId = OneSignal.User.onesignalId,
                externalId = OneSignal.User.externalId,
                subscriptionId = subscription.id,
                pushToken = subscription.token,
                optedIn = subscription.optedIn,
                notificationPermission = OneSignal.Notifications.permission,
                error = initializationError,
            )
        } catch (throwable: Throwable) {
            State(
                initialized = false,
                initializing = initializing,
                error = throwable.message ?: throwable.javaClass.simpleName,
            )
        }
    }

    private fun dispatchState() {
        val state = snapshot()
        mainHandler.post {
            listeners.forEach { listener -> listener(state) }
        }
    }
}
