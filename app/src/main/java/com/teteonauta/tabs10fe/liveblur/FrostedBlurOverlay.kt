package com.teteonauta.tabs10fe.liveblur

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Synthesizes Samsung One UI 9's ColorCurve neutral frosted glass tint for rounded UI elements
 * (notifications, widgets, lockscreen shortcuts, DeX taskbar), a crisp neutral glass contour +
 * subtle optical specular sheen strictly on the Quick Panel (SecQSNewBlurView), and a gentle
 * Dark Mode darkening veil for the One UI Home app drawer backdrop.
 */
internal object FrostedBlurOverlay {

    private val installed = AtomicBoolean(false)
    private val firstDrawLogged = AtomicBoolean(false)
    private val errorLogged = AtomicBoolean(false)

    // Tracks BackgroundBlurDrawable instances owned by SecQSNewBlurView (Quick Panel only).
    private val quickPanelDrawables: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    // Tracks BackgroundBlurDrawable instances owned by Notification background views.
    private val notificationDrawables: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    // Tracks BackgroundBlurDrawable instances owned by Dialog / Popup / Menu containers in apps.
    private val dialogPopupDrawables: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val fillPaintTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
    }

    private val sheenPaintTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
    }

    private val strokePaintTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
    }

    private val boundsTL = ThreadLocal.withInitial { RectF() }

    private class DrawableFields(bbdClass: Class<*>, colorCurveClass: Class<*>) {
        val mRectPath: Field = field(bbdClass, "mRectPath")
        val mClipPath: Field? = runCatching { field(bbdClass, "mClipPath") }.getOrNull()
        val mPaint: Field = field(bbdClass, "mPaint")
        val mColorCurve: Field = field(bbdClass, "mColorCurve")
        val mAlpha: Field = field(bbdClass, "mAlpha")
        val mBlurRadius: Field = field(bbdClass, "mBlurRadius")
        val mVisible: Field = field(bbdClass, "mVisible")
        val mCornerRadiusTLX: Field = field(bbdClass, "mCornerRadiusTLX")
        val mCornerRadiusTRX: Field = field(bbdClass, "mCornerRadiusTRX")
        val mCornerRadiusBLX: Field = field(bbdClass, "mCornerRadiusBLX")
        val mCornerRadiusBRX: Field = field(bbdClass, "mCornerRadiusBRX")

        val ccMinY: Field = field(colorCurveClass, "mMinY")
        val ccMaxY: Field = field(colorCurveClass, "mMaxY")
        val ccCurveBias: Field = field(colorCurveClass, "mCurveBias")

        private fun field(clazz: Class<*>, name: String): Field =
            clazz.getDeclaredField(name).apply { isAccessible = true }
    }

    fun tagOwnerView(view: Any?) {
        val v = view as? View ?: return
        val bg = v.background ?: return
        if (bg.javaClass.name != "com.android.internal.graphics.drawable.BackgroundBlurDrawable") return

        val className = v.javaClass.name
        val parentClassName = v.parent?.javaClass?.name ?: ""
        if (className == "com.android.systemui.blur.SecQSNewBlurView" ||
            className.endsWith(".SecQSNewBlurView")
        ) {
            quickPanelDrawables.add(bg)
        } else if (className.contains("Notification", ignoreCase = true) ||
            className.contains("Noti", ignoreCase = false) ||
            parentClassName.contains("Notification", ignoreCase = true)
        ) {
            notificationDrawables.add(bg)
        } else if (className.contains("Dialog", ignoreCase = true) ||
            className.contains("Popup", ignoreCase = true) ||
            className.contains("Menu", ignoreCase = true) ||
            parentClassName.contains("Dialog", ignoreCase = true) ||
            parentClassName.contains("Popup", ignoreCase = true)
        ) {
            dialogPopupDrawables.add(bg)
        }
    }

    private fun isSystemNightMode(): Boolean {
        val uiMode = runCatching { Resources.getSystem().configuration.uiMode }.getOrDefault(0)
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    @Suppress("DEPRECATION")
    private fun drawNeutralGlassSheen(canvas: Canvas, path: Path, topAlpha: Int) {
        if (topAlpha <= 0) return
        val bounds = boundsTL.get()!!
        path.computeBounds(bounds, true)
        if (bounds.width() <= 1f || bounds.height() <= 1f) return
        val paint = sheenPaintTL.get()!!
        paint.shader = LinearGradient(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            Color.argb(topAlpha.coerceIn(0, 255), 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    fun install(tag: String, packageName: String, classLoader: ClassLoader): XC_MethodHook.Unhook? {
        if (!installed.compareAndSet(false, true)) return null

        val isLauncher = packageName == "com.sec.android.app.launcher"

        return try {
            val bbdClass = XposedHelpers.findClassIfExists(
                "com.android.internal.graphics.drawable.BackgroundBlurDrawable",
                classLoader
            ) ?: return null
            val ccClass = XposedHelpers.findClassIfExists(
                "android.view.SemBlurInfo\$ColorCurve",
                classLoader
            ) ?: return null

            val f = DrawableFields(bbdClass, ccClass)
            val drawMethod = bbdClass.getDeclaredMethod("draw", Canvas::class.java)

            val unhook = XposedBridge.hookMethod(
                drawMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val canvas = param.args[0] as? Canvas ?: return
                            if (!canvas.isHardwareAccelerated) return

                            val self = param.thisObject ?: return
                            if (!f.mVisible.getBoolean(self)) return

                            val drawableAlpha = f.mAlpha.getInt(self)
                            if (drawableAlpha <= 0) return

                            val blurRadius = f.mBlurRadius.getInt(self)
                            if (blurRadius <= 0) return

                            val path = f.mRectPath.get(self) as? Path ?: return
                            if (path.isEmpty) return

                            // Skip arbitrary Path-masked blur drawables (e.g., Lockscreen blur clock).
                            val clipPath = f.mClipPath?.get(self) as? Path
                            if (clipPath != null && !clipPath.isEmpty) return

                            val radiusFactor = min(1f, blurRadius / 45f)
                            val effectiveFactor = (drawableAlpha / 255f) * radiusFactor
                            if (effectiveFactor <= 0.01f) return

                            val maxCorner = max(
                                max(f.mCornerRadiusTLX.getFloat(self), f.mCornerRadiusTRX.getFloat(self)),
                                max(f.mCornerRadiusBLX.getFloat(self), f.mCornerRadiusBRX.getFloat(self))
                            )

                            // Full-screen unrounded backdrop blurs (App Drawer, Recents, Shade backdrop)
                            if (maxCorner < 12f) {
                                // Gentle extra darkening in One UI Home Dark Mode only (~16% deep black, no grey wash).
                                if (isLauncher && isSystemNightMode()) {
                                    val aInt = (42f * effectiveFactor).toInt().coerceIn(0, 64)
                                    if (aInt > 0) {
                                        val fillPaint = fillPaintTL.get()!!
                                        fillPaint.color = Color.argb(aInt, 8, 8, 10)
                                        canvas.drawPath(path, fillPaint)
                                    }
                                }
                                return
                            }

                            val isQuickPanel = quickPanelDrawables.contains(self)
                            val isNotification = notificationDrawables.contains(self)
                            val isDialogPopup = dialogPopupDrawables.contains(self)
                            val basePaint = f.mPaint.get(self) as? Paint
                            val existingAlpha = if (basePaint != null) Color.alpha(basePaint.color) else 0
                            val colorCurve = f.mColorCurve.get(self)

                            var drewAnything = false

                            if (colorCurve != null) {
                                val minY = f.ccMinY.getFloat(colorCurve)
                                val maxY = f.ccMaxY.getFloat(colorCurve)
                                val bias = f.ccCurveBias.getFloat(colorCurve)

                                val span = (maxY - minY).coerceIn(0f, 255f)
                                val aLin = (1f - span / 255f).coerceIn(0f, 1f)

                                val tintAlpha: Float
                                val lum: Int
                                if (minY < 55f && maxY > 180f && bias < -2f) {
                                    // Dark Mode frosted glass (clean deep neutral so real backdrop colors shine through)
                                    val base = (0.14f + (-bias / 110f)).coerceIn(0.20f, 0.34f)
                                    tintAlpha = when {
                                        isNotification -> (base + 0.24f).coerceIn(0.50f, 0.60f)
                                        isDialogPopup -> (base + 0.16f).coerceIn(0.40f, 0.52f)
                                        else -> base
                                    }
                                    lum = when {
                                        isNotification -> 14
                                        isDialogPopup -> 18
                                        else -> (minY * 0.8f + 12f).coerceIn(16f, 34f).toInt()
                                    }
                                } else if (minY < 55f && maxY <= 180f) {
                                    val base = (aLin * 0.45f).coerceIn(0.18f, 0.32f)
                                    tintAlpha = when {
                                        isNotification -> (base + 0.26f).coerceIn(0.50f, 0.60f)
                                        isDialogPopup -> (base + 0.16f).coerceIn(0.38f, 0.50f)
                                        else -> base
                                    }
                                    lum = if (isNotification) 14 else if (aLin > 0.05f) (minY / aLin).coerceIn(14f, 38f).toInt() else 22
                                } else if (minY < 55f) {
                                    tintAlpha = when {
                                        isNotification && isSystemNightMode() -> 0.54f
                                        isDialogPopup && isSystemNightMode() -> 0.44f
                                        isNotification -> (aLin * 0.9f + 0.14f).coerceIn(0.18f, 0.32f)
                                        else -> (aLin * 0.5f).coerceIn(0f, 0.14f)
                                    }
                                    lum = if ((isNotification || isDialogPopup) && isSystemNightMode()) 15 else 22
                                } else {
                                    // Light Mode frosted glass (clean luminous neutral white, zero color cast)
                                    tintAlpha = when {
                                        isNotification -> (aLin * 0.78f).coerceIn(0.34f, 0.54f)
                                        isDialogPopup -> (aLin * 0.78f).coerceIn(0.34f, 0.58f)
                                        else -> (aLin * 0.65f).coerceIn(0.24f, 0.48f)
                                    }
                                    lum = if (aLin > 0.05f) {
                                        (minY / aLin).coerceIn(228f, 253f).toInt()
                                    } else {
                                        248
                                    }
                                }

                                // Quick Panel gets its own dedicated neutral glass tint & crisp border below.
                                if (!isQuickPanel && tintAlpha >= 0.05f && existingAlpha < 96) {
                                    val scaledAlpha = tintAlpha * effectiveFactor * (1f - existingAlpha / 255f)
                                    val maxAlphaCap = if (isNotification) 175 else if (isDialogPopup) 165 else 140
                                    val aInt = (scaledAlpha * 255f).toInt().coerceIn(0, maxAlphaCap)
                                    if (aInt > 0) {
                                        val fillPaint = fillPaintTL.get()!!
                                        fillPaint.color = Color.argb(aInt, lum, lum, lum)
                                        canvas.drawPath(path, fillPaint)
                                        drewAnything = true
                                    }
                                }
                            }

                            // Clean neutral S25-style contrast glass + subtle optical sheen + crisp border strictly on Quick Panel.
                            if (isQuickPanel) {
                                val night = isSystemNightMode()
                                val qpTintAlpha = (if (night) 64f else 46f) * effectiveFactor // ~25% dark / ~18% light (zero blue cast)
                                val qpAInt = qpTintAlpha.toInt().coerceIn(0, 100)
                                if (qpAInt > 0) {
                                    val fillPaint = fillPaintTL.get()!!
                                    fillPaint.color = if (night) {
                                        Color.argb(qpAInt, 14, 15, 18)
                                    } else {
                                        Color.argb(qpAInt, 24, 26, 30)
                                    }
                                    canvas.drawPath(path, fillPaint)
                                    drewAnything = true
                                }

                                // Pure-white optical glass reflection (subtle top-left sheen, zero color tint)
                                val sheenAlpha = (if (night) 14f else 20f) * effectiveFactor
                                drawNeutralGlassSheen(canvas, path, sheenAlpha.toInt())

                                // Stronger, neutral crisp glass contour (clearly visible over pure white apps in Light Mode).
                                val strokePaint = strokePaintTL.get()!!
                                if (!night) {
                                    strokePaint.strokeWidth = 2.8f // Clipped -> ~1.4px clean neutral graphite rim
                                    val a = (80f * effectiveFactor).toInt().coerceIn(0, 135)
                                    strokePaint.color = Color.argb(a, 100, 104, 112)
                                } else {
                                    strokePaint.strokeWidth = 2.4f // Clipped -> ~1.2px clean luminous white rim
                                    val a = (56f * effectiveFactor).toInt().coerceIn(0, 115)
                                    strokePaint.color = Color.argb(a, 255, 255, 255)
                                }
                                val save = canvas.save()
                                try {
                                    canvas.clipPath(path)
                                    canvas.drawPath(path, strokePaint)
                                    drewAnything = true
                                } finally {
                                    canvas.restoreToCount(save)
                                }
                            }

                            if (drewAnything && firstDrawLogged.compareAndSet(false, true)) {
                                XposedBridge.log("$tag [$packageName]: Clean neutral frosted glass + enhanced Quick Panel border active (v0.1.0)")
                            }
                        } catch (t: Throwable) {
                            if (errorLogged.compareAndSet(false, true)) {
                                XposedBridge.log("$tag [$packageName]: FrostedBlurOverlay draw error: $t")
                                XposedBridge.log(t)
                            }
                        }
                    }
                }
            )
            XposedBridge.log("$tag [$packageName]: Hooked BackgroundBlurDrawable.draw (v0.1.0)")
            unhook
        } catch (t: Throwable) {
            installed.set(false)
            XposedBridge.log("$tag [$packageName]: Failed to hook BackgroundBlurDrawable.draw: $t")
            null
        }
    }
}
