package com.teteonauta.tabs10fe.liveblur

import android.graphics.Point
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Array as ReflectArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LiveBlurHook : IXposedHookLoadPackage {

    private class DispatchContext(
        val source: Any?,
        val surface: Any,
        val frame: Long,
        val layerId: Int,
        val rows: Array<FloatArray>,
        var submitted: Boolean = false
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        val classLoader = lpparam.classLoader ?: return

        hookSemFloatingFeature(packageName, classLoader)
        hookSemCscFeature(packageName, classLoader)
        hookSystemProperties(packageName, classLoader)
        hookCrossWindowBlurListeners(packageName, classLoader)
        hookActivityManager(packageName, classLoader)
        installNativeBlurBridge(packageName, classLoader)
    }

    private fun installNativeBlurBridge(packageName: String, classLoader: ClassLoader) {
        if (!bridgeInstalled.compareAndSet(false, true)) return

        val unhooks = mutableListOf<XC_MethodHook.Unhook>()
        var coreFlags: CoreRuneFlags? = null
        try {
            val surfaceControlClass = XposedHelpers.findClassIfExists("android.view.SurfaceControl", classLoader)
                ?: return
            val transactionClass = XposedHelpers.findClassIfExists("android.view.SurfaceControl\$Transaction", classLoader)
                ?: return
            val regionClass = XposedHelpers.findClassIfExists("android.view.SemBlurRegionData", classLoader)
                ?: return
            val viewRootClass = XposedHelpers.findClassIfExists("android.view.ViewRootImpl", classLoader)
                ?: return
            val coreRuneClass = XposedHelpers.findClassIfExists("com.samsung.android.rune.CoreRune", classLoader)
                ?: return
            val viewClass = XposedHelpers.findClassIfExists("android.view.View", classLoader)
                ?: return
            val blurInfoClass = XposedHelpers.findClassIfExists("android.view.SemBlurInfo", classLoader)
                ?: return

            val regionArrayClass = ReflectArray.newInstance(regionClass, 0).javaClass
            val standardSetBlurRegions = transactionClass.getMethod(
                "setBlurRegions",
                surfaceControlClass,
                Array<FloatArray>::class.java
            ).apply { isAccessible = true }
            val extendedSetBlurRegions = transactionClass.getMethod(
                "setBlurRegionsEx",
                surfaceControlClass,
                regionArrayClass
            )
            val dispatchBlurRegionsEx = viewRootClass.getMethod(
                "dispatchBlurRegionsEx",
                regionArrayClass,
                Long::class.javaPrimitiveType
            )
            val getSurfaceControl = viewRootClass.getMethod("getSurfaceControl")
            val getLayerId = surfaceControlClass.getMethod("getLayerId")
            val surfaceSizeField = viewRootClass.getDeclaredField("mSurfaceSize").apply { isAccessible = true }
            val blastField = viewRootClass.getDeclaredField("mBlastBufferQueue").apply { isAccessible = true }
            val semSetBlurInfo = viewClass.getMethod("semSetBlurInfo", blurInfoClass)

            BlurRegionEncoder.prepare(regionClass)
            coreFlags = CoreRuneFlags(coreRuneClass)

            unhooks += XposedBridge.hookMethod(
                dispatchBlurRegionsEx,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        currentDispatch.remove()
                        try {
                            val size = surfaceSizeField.get(param.thisObject) as? Point
                            val width = size?.x ?: 0
                            val height = size?.y ?: 0
                            val rows = BlurRegionEncoder.encodeVisible(param.args[0], width, height)
                            val sc = getSurfaceControl.invoke(param.thisObject) ?: return
                            val layerId = (getLayerId.invoke(sc) as Number).toInt()
                            val frame = (param.args[1] as Number).toLong()
                            if (submissionCache.isDuplicate(sc, frame, layerId, rows)) {
                                param.result = null
                            } else {
                                currentDispatch.set(DispatchContext(param.args[0], sc, frame, layerId, rows))
                            }
                        } catch (t: Throwable) {
                            logBridgeErrorOnce(packageName, t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = currentDispatch.get()
                        currentDispatch.remove()
                        try {
                            if (ctx != null && ctx.submitted && !param.hasThrowable() && blastField.get(param.thisObject) != null) {
                                submissionCache.remember(ctx.surface, ctx.frame, ctx.layerId, ctx.rows)
                            }
                        } catch (t: Throwable) {
                            logBridgeErrorOnce(packageName, t)
                        }
                    }
                }
            )

            unhooks += XposedBridge.hookMethod(
                extendedSetBlurRegions,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = currentDispatch.get()
                            val prepared = ctx != null && ctx.source === param.args[1] && ctx.surface === param.args[0]
                            val rows = if (prepared) ctx!!.rows else BlurRegionEncoder.encode(param.args[1])
                            standardSetBlurRegions.invoke(param.thisObject, param.args[0], rows)
                            param.result = param.thisObject
                            if (prepared) ctx!!.submitted = true
                            if (rows.isNotEmpty() && firstSubmissionLogged.compareAndSet(false, true)) {
                                XposedBridge.log("$TAG [$packageName]: Native setBlurRegions bridge active (regions=${rows.size}, cols=${rows[0].size})")
                            }
                        } catch (t: Throwable) {
                            logBridgeErrorOnce(packageName, t)
                        }
                    }
                }
            )

            coreFlags.enable(TAG, packageName)

            // Hooking semSetBlurInfo forces ART to deoptimize code paths that cached old CoreRune values
            // and tags SecQSNewBlurView's BackgroundBlurDrawable for the subtle Quick Panel contour.
            unhooks += XposedBridge.hookMethod(
                semSetBlurInfo,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] != null && firstSemBlurLogged.compareAndSet(false, true)) {
                            XposedBridge.log("$TAG [$packageName]: View.semSetBlurInfo invoked with CoreRune blur enabled")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        FrostedBlurOverlay.tagOwnerView(param.thisObject)
                    }
                }
            )

            // Also tag SecQSNewBlurView when invalidateBlurBackground attaches a new BackgroundBlurDrawable.
            runCatching {
                val invBlurBg = viewClass.getDeclaredMethod("invalidateBlurBackground")
                unhooks += XposedBridge.hookMethod(
                    invBlurBg,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            FrostedBlurOverlay.tagOwnerView(param.thisObject)
                        }
                    }
                )
            }

            FrostedBlurOverlay.install(TAG, packageName, classLoader)?.let { unhooks += it }

            XposedBridge.log("$TAG [$packageName]: One UI 9 Live Blur bridge + flagship animations + app/dialog blur enabled (v0.1.0)")
        } catch (t: Throwable) {
            for (i in unhooks.size - 1 downTo 0) {
                runCatching { unhooks[i].unhook() }
            }
            runCatching { coreFlags?.restore() }
            bridgeInstalled.set(false)
            XposedBridge.log("$TAG [$packageName]: Native blur bridge activation failed; rolled back: $t")
            XposedBridge.log(t)
        }
    }

    private fun logBridgeErrorOnce(packageName: String, error: Throwable) {
        if (bridgeErrorLogged.compareAndSet(false, true)) {
            XposedBridge.log("$TAG [$packageName]: Native blur region bridge error: $error")
            XposedBridge.log(error)
        }
    }

    private fun hookSemFloatingFeature(packageName: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_SEM_FLOATING_FEATURE, classLoader) ?: return
        if (!hookedClasses.add(clazz)) return

        try {
            XposedBridge.hookAllMethods(
                clazz,
                "getBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        if (shouldForceFloatingTrue(key)) {
                            logKeyOnce(packageName, "SemFloatingFeature.getBoolean", key, "-> true")
                            param.result = true
                        } else if (isInterestingBlurKey(key)) {
                            logKeyOnce(packageName, "SemFloatingFeature.getBoolean", key, "(passthrough)")
                        }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                clazz,
                "getString",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        val customString = FLOATING_STRING_OVERRIDES[key]
                        if (customString != null) {
                            logKeyOnce(packageName, "SemFloatingFeature.getString", key, "-> $customString")
                            param.result = customString
                        } else if (shouldForceFloatingTrue(key)) {
                            logKeyOnce(packageName, "SemFloatingFeature.getString", key, "-> TRUE")
                            param.result = "TRUE"
                        } else if (isInterestingBlurKey(key)) {
                            logKeyOnce(packageName, "SemFloatingFeature.getString", key, "(passthrough)")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Hooked $CLASS_SEM_FLOATING_FEATURE in $packageName")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed hooking $CLASS_SEM_FLOATING_FEATURE in $packageName: $t")
        }
    }

    private fun hookSemCscFeature(packageName: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_SEM_CSC_FEATURE, classLoader) ?: return
        if (!hookedClasses.add(clazz)) return

        try {
            XposedBridge.hookAllMethods(
                clazz,
                "getBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        if (shouldForceCscTrue(key)) {
                            logKeyOnce(packageName, "SemCscFeature.getBoolean", key, "-> true")
                            param.result = true
                        } else if (isInterestingBlurKey(key)) {
                            logKeyOnce(packageName, "SemCscFeature.getBoolean", key, "(passthrough)")
                        }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                clazz,
                "getString",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        if (shouldForceCscTrue(key)) {
                            logKeyOnce(packageName, "SemCscFeature.getString", key, "-> TRUE")
                            param.result = "TRUE"
                        } else if (isInterestingBlurKey(key)) {
                            logKeyOnce(packageName, "SemCscFeature.getString", key, "(passthrough)")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Hooked $CLASS_SEM_CSC_FEATURE in $packageName")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed hooking $CLASS_SEM_CSC_FEATURE in $packageName: $t")
        }
    }

    private fun hookSystemProperties(packageName: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_SYSTEM_PROPERTIES, classLoader) ?: return
        if (!hookedClasses.add(clazz)) return

        try {
            XposedBridge.hookAllMethods(
                clazz,
                "getBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        val override = SYSPROP_BOOL_OVERRIDES[key] ?: return
                        logKeyOnce(packageName, "SystemProperties.getBoolean", key, "-> $override")
                        param.result = override
                    }
                }
            )

            XposedBridge.hookAllMethods(
                clazz,
                "getInt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        val override = SYSPROP_INT_OVERRIDES[key] ?: return
                        logKeyOnce(packageName, "SystemProperties.getInt", key, "-> $override")
                        param.result = override
                    }
                }
            )

            XposedBridge.hookAllMethods(
                clazz,
                "get",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = extractKey(param.args) ?: return
                        val override = SYSPROP_STRING_OVERRIDES[key] ?: return
                        logKeyOnce(packageName, "SystemProperties.get", key, "-> $override")
                        param.result = override
                    }
                }
            )

            XposedBridge.log("$TAG: Hooked $CLASS_SYSTEM_PROPERTIES in $packageName")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed hooking $CLASS_SYSTEM_PROPERTIES in $packageName: $t")
        }
    }

    private fun hookCrossWindowBlurListeners(packageName: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_CROSS_WINDOW_BLUR_LISTENERS, classLoader) ?: return
        if (!hookedClasses.add(clazz)) return

        try {
            runCatching {
                XposedHelpers.setStaticBooleanField(clazz, "CROSS_WINDOW_BLUR_SUPPORTED", true)
            }

            XposedBridge.hookAllMethods(
                clazz,
                "isCrossWindowBlurEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )

            XposedBridge.log("$TAG: Hooked $CLASS_CROSS_WINDOW_BLUR_LISTENERS in $packageName")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed hooking $CLASS_CROSS_WINDOW_BLUR_LISTENERS in $packageName: $t")
        }
    }

    private fun hookActivityManager(packageName: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_ACTIVITY_MANAGER, classLoader) ?: return
        if (!hookedClasses.add(clazz)) return

        try {
            XposedBridge.hookAllMethods(
                clazz,
                "isHighEndGfx",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )

            XposedBridge.log("$TAG: Hooked $CLASS_ACTIVITY_MANAGER in $packageName")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed hooking $CLASS_ACTIVITY_MANAGER in $packageName: $t")
        }
    }

    private fun extractKey(args: Array<Any?>?): String? {
        if (args == null) return null
        for (arg in args) {
            if (arg is String) {
                return arg
            }
        }
        return null
    }

    private fun logKeyOnce(packageName: String, source: String, key: String, detail: String) {
        val token = "$packageName|$source|$key"
        if (loggedKeys.add(token)) {
            XposedBridge.log("$TAG [$packageName]: $source($key) $detail")
        }
    }

    private fun isInterestingBlurKey(key: String): Boolean {
        val upper = key.uppercase()
        return upper.contains("BLUR") ||
            upper.contains("3D_SURFACE") ||
            upper.contains("HIGH_END") ||
            upper.contains("GRAPHICS_SUPPORT")
    }

    private fun shouldForceFloatingTrue(key: String): Boolean {
        if (key in FLOATING_FORCE_TRUE_KEYS) return true

        val upper = key.uppercase()
        if (EXCLUDED_SUBSTRINGS.any { upper.contains(it) }) return false

        if (upper.startsWith("SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_") &&
            (upper.contains("BLUR") || upper.contains("3D_SURFACE_TRANSITION") || upper.contains("HIGH_END"))
        ) {
            return true
        }

        if (upper.startsWith("SEC_FLOATING_FEATURE_") &&
            upper.contains("_SUPPORT_") &&
            (upper.contains("PARTIAL_BLUR") ||
                upper.contains("LIVE_BLUR") ||
                upper.contains("REALTIME_BLUR") ||
                upper.contains("CAPTURED_BLUR") ||
                upper.contains("GPU_BLUR") ||
                upper.contains("3D_SURFACE_TRANSITION") ||
                upper.contains("HIGH_END_UX"))
        ) {
            return true
        }

        return false
    }

    private fun shouldForceCscTrue(key: String): Boolean {
        if (key in CSC_FORCE_TRUE_KEYS) return true

        val upper = key.uppercase()
        if (EXCLUDED_SUBSTRINGS.any { upper.contains(it) }) return false

        if (upper.startsWith("CSCFEATURE_") &&
            upper.contains("SUPPORT") &&
            (upper.contains("BLUR") ||
                upper.contains("3DSURFACETRANSITION") ||
                upper.contains("3D_SURFACE_TRANSITION") ||
                upper.contains("HIGHENDUX") ||
                upper.contains("HIGH_END_UX"))
        ) {
            return true
        }

        return false
    }

    companion object {
        private const val TAG = "TabS10FELiveBlur"
        private const val CLASS_SEM_FLOATING_FEATURE = "com.samsung.android.feature.SemFloatingFeature"
        private const val CLASS_SEM_CSC_FEATURE = "com.samsung.android.feature.SemCscFeature"
        private const val CLASS_SYSTEM_PROPERTIES = "android.os.SystemProperties"
        private const val CLASS_CROSS_WINDOW_BLUR_LISTENERS = "android.view.CrossWindowBlurListeners"
        private const val CLASS_ACTIVITY_MANAGER = "android.app.ActivityManager"

        private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        private val loggedKeys = ConcurrentHashMap.newKeySet<String>()
        private val bridgeInstalled = AtomicBoolean(false)
        private val bridgeErrorLogged = AtomicBoolean(false)
        private val firstSubmissionLogged = AtomicBoolean(false)
        private val firstSemBlurLogged = AtomicBoolean(false)
        private val submissionCache = BlurSubmissionCache()
        private val currentDispatch = ThreadLocal<DispatchContext?>()

        private val EXCLUDED_SUBSTRINGS = setOf(
            "DISABLE",
            "LOW_END",
            "LOWEND",
            "LEGACY"
        )

        private val SYSPROP_BOOL_OVERRIDES = mapOf(
            "ro.surface_flinger.supports_background_blur" to true,
            "ro.sf.blurs_are_expensive" to false,
            "persist.sys.sf.disable_blurs" to false,
            "ro.launcher.blur.appLaunch" to true
        )

        private val SYSPROP_INT_OVERRIDES = mapOf(
            "ro.surface_flinger.supports_background_blur" to 1,
            "ro.sf.blurs_are_expensive" to 0,
            "persist.sys.sf.disable_blurs" to 0,
            "ro.launcher.blur.appLaunch" to 1
        )

        private val SYSPROP_STRING_OVERRIDES = mapOf(
            "ro.surface_flinger.supports_background_blur" to "1",
            "ro.sf.blurs_are_expensive" to "0",
            "persist.sys.sf.disable_blurs" to "0",
            "ro.launcher.blur.appLaunch" to "1"
        )

        private val FLOATING_STRING_OVERRIDES = mapOf(
            "SEC_FLOATING_FEATURE_LAUNCHER_CONFIG_ANIMATION_TYPE" to "HighEnd",
            "SEC_FLOATING_FEATURE_COMMON_CONFIG_ANIMATION_TYPE" to "HighEnd",
            "SEC_FLOATING_FEATURE_SYSTEMUI_CONFIG_ANIMATION_TYPE" to "HighEnd"
        )

        private val FLOATING_FORCE_TRUE_KEYS = setOf(
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_PARTIAL_BLUR",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_3D_SURFACE_TRANSITION_FLAG",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_CAPTURED_BLUR",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_REALTIME_BLUR",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_LIVE_BLUR",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_GPU_BLUR",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_BLUR",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_HIGH_END",
            "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_HIGH_END_UX",
            "SEC_FLOATING_FEATURE_COMMON_SUPPORT_HIGH_END_UX",
            "SEC_FLOATING_FEATURE_COMMON_CONFIG_HIGH_END",
            "SEC_FLOATING_FEATURE_COMMON_SUPPORT_HIGH_END_ANIMATION",
            "SEC_FLOATING_FEATURE_LAUNCHER_SUPPORT_HIGH_END_ANIMATION",
            "SEC_FLOATING_FEATURE_LAUNCHER_SUPPORT_WALLPAPER_BLUR",
            "SEC_FLOATING_FEATURE_LAUNCHER_SUPPORT_WALLPAPER_ZOOM",
            "SEC_FLOATING_FEATURE_SYSTEMUI_SUPPORT_HIGH_END_ANIMATION",
            "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_HIGH_END_ANIMATION",
            "SEC_FLOATING_FEATURE_SYSTEMUI_SUPPORT_BLUR",
            "SEC_FLOATING_FEATURE_SYSTEMUI_SUPPORT_LIVE_BLUR",
            "SEC_FLOATING_FEATURE_SYSTEMUI_SUPPORT_PARTIAL_BLUR",
            "SEC_FLOATING_FEATURE_LAUNCHER_SUPPORT_BLUR",
            "SEC_FLOATING_FEATURE_LAUNCHER_SUPPORT_LIVE_BLUR",
            "SEC_FLOATING_FEATURE_LAUNCHER_SUPPORT_PARTIAL_BLUR",
            "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_BLUR",
            "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_REALTIME_BLUR",
            "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_PARTIAL_BLUR"
        )

        private val CSC_FORCE_TRUE_KEYS = setOf(
            "CscFeature_Common_SupportBlur",
            "CscFeature_Common_SupportLiveBlur",
            "CscFeature_Common_SupportPartialBlur",
            "CscFeature_Common_SupportCapturedBlur",
            "CscFeature_Common_SupportHighEndUx",
            "CscFeature_Common_SupportHighEndGraphics",
            "CscFeature_Graphics_SupportPartialBlur",
            "CscFeature_Graphics_SupportLiveBlur",
            "CscFeature_Graphics_Support3DSurfaceTransition",
            "CscFeature_SystemUI_SupportBlur",
            "CscFeature_SystemUI_SupportLiveBlur",
            "CscFeature_SystemUI_SupportPartialBlur",
            "CscFeature_Launcher_SupportBlur",
            "CscFeature_Launcher_SupportLiveBlur"
        )
    }
}
