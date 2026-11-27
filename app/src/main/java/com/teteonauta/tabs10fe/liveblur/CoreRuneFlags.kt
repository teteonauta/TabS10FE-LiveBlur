package com.teteonauta.tabs10fe.liveblur

import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class CoreRuneFlags(private val coreRuneClass: Class<*>) {

    private data class TargetField(
        val name: String,
        val field: Field,
        val original: Boolean,
        val base: Any,
        val offset: Long
    )

    private val unsafe: Any
    private val putBooleanVolatile: Method
    private val targets: List<TargetField>
    private var changedCount = 0

    init {
        val unsafeClass = runCatching { Class.forName("jdk.internal.misc.Unsafe") }
            .getOrElse { Class.forName("sun.misc.Unsafe") }
        unsafe = runCatching { unsafeClass.getMethod("getUnsafe").invoke(null) }.getOrNull()
            ?: unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            ?: throw IllegalStateException("Could not obtain Unsafe instance")
        val staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field::class.java)
        val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field::class.java)
        putBooleanVolatile = runCatching {
            unsafeClass.getMethod(
                "putBooleanVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        }.getOrElse {
            unsafeClass.getMethod(
                "putBoolean",
                Any::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        }

        val list = mutableListOf<TargetField>()
        for (name in FLAG_NAMES) {
            val field = runCatching { coreRuneClass.getDeclaredField(name) }.getOrNull() ?: continue
            field.isAccessible = true
            if (field.type != Boolean::class.javaPrimitiveType || !Modifier.isStatic(field.modifiers)) {
                continue
            }
            val original = field.getBoolean(null)
            val base = staticFieldBase.invoke(unsafe, field)
                ?: throw IllegalStateException("Null staticFieldBase for $name")
            val offset = (staticFieldOffset.invoke(unsafe, field) as Number).toLong()
            if (offset < 0L) throw IllegalStateException("Invalid field offset for $name")
            list.add(TargetField(name, field, original, base, offset))
        }
        targets = list
    }

    fun enable(tag: String, packageName: String) {
        val summary = StringBuilder()
        for (i in targets.indices) {
            val t = targets[i]
            if (!t.original) {
                changedCount = i + 1
                write(t, true)
            }
            if (summary.isNotEmpty()) summary.append(", ")
            summary.append(t.name).append('=').append(t.original).append("->").append(t.field.getBoolean(null))
        }
        XposedBridge.log("$tag [$packageName]: CoreRune flags: $summary")
    }

    fun restore() {
        var firstError: Throwable? = null
        for (i in changedCount - 1 downTo 0) {
            val t = targets[i]
            if (t.original) continue
            try {
                write(t, t.original)
            } catch (err: Throwable) {
                if (firstError == null) firstError = err else firstError.addSuppressed(err)
            }
        }
        changedCount = 0
        if (firstError != null) throw firstError
    }

    private fun write(target: TargetField, value: Boolean) {
        putBooleanVolatile.invoke(unsafe, target.base, target.offset, value)
        if (target.field.getBoolean(null) != value) {
            throw IllegalStateException("CoreRune write verification failed for ${target.name}")
        }
    }

    companion object {
        private val FLAG_NAMES = arrayOf(
            "FW_WINDOW_BLUR_SUPPORTED",
            "FW_STACKED_BLUR_SUPPORTED",
            "FW_WINDOW_BLUR_SNAPSHOT_AFTER_PREDRAW",
            "FW_RESPECT_LIGHT_APPEARANCE_OF_BLURRING_WINDOW"
        )
    }
}
