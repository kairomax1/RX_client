package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2Ray Native Library Manager
 *
 * Thread-safe singleton wrapper for Libv2ray native methods.
 * Provides initialization protection and unified API for V2Ray core operations.
 */
object CoreNativeManager {
    private val initialized = AtomicBoolean(false)

    /**
     * Initialize V2Ray core environment.
     * This method is thread-safe and ensures initialization happens only once.
     * Subsequent calls will be ignored silently.
     *
     */
    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context?.applicationContext)
                val assetPath = Utils.userAssetPath(context)
                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                LogUtil.i(AppConfig.TAG, "V2Ray core environment initialized successfully")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            LogUtil.d(AppConfig.TAG, "V2Ray core environment already initialized, skipping")
        }
    }

    fun reconcileBrowserDialer(dialerAddr: String) {
        // Nothing to reconcile when the address is empty — the browser dialer is
        // not in use — and saying so on every start filled the log with a
        // sentence that reads like a success and reports nothing.
        if (dialerAddr.isBlank()) {
            LogUtil.d(AppConfig.TAG, "Browser dialer is not in use; nothing to reconcile")
            return
        }
        try {
            Libv2ray.reconcileBrowserDialer(dialerAddr)
            LogUtil.i(AppConfig.TAG, "Browser dialer reconciled with address: $dialerAddr")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to reconcile browser dialer with address: $dialerAddr", e)
        }
    }


    /**
     * Get V2Ray core version.
     *
     * @return Version string of the V2Ray core
     */
    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    /**
     * Measure outbound connection delay.
     *
     * @param config The configuration JSON string
     * @param testUrl The URL to test against
     * @return Delay in milliseconds, or -1 if test failed
     */
    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (e: Exception) {
            // A config that answers nothing is the ordinary case here: the list
            // holds hundreds of public ones and most of them are dead. Written as
            // an error, every one of them filled the log — two hundred and
            // forty-six lines in a day — and the lines that were real faults
            // could not be found among them. It is still recorded, quietly.
            LogUtil.w(AppConfig.TAG, "A config carried nothing: ${e.message?.take(120).orEmpty()}")
            -1L
        }
    }

    /**
     * Create a new core controller instance.
     *
     * @param handler The callback handler for core events
     * @return A new CoreController instance
     */
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return try {
            Libv2ray.newCoreController(handler)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to create core controller", e)
            throw e
        }
    }
}