package com.teteonauta.tabs10fe.liveblur

import android.graphics.Path
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

internal object BlurRegionEncoder {

    private val EMPTY: Array<FloatArray> = emptyArray()

    @Volatile
    private var cachedAccess: Access? = null

    private class Access(val regionType: Class<*>) {
        val getBlurRadius: Method = regionType.getMethod("getBlurRadius")
        val getAlpha: Method = regionType.getMethod("getAlpha")
        val getRect: Method = regionType.getMethod("getRect")
        val getCornerRadii: Method = regionType.getMethod("getCornerRadii")
        val getClipRect: Method = regionType.getMethod("getClipRect")
        val getColorCurve: Method = regionType.getMethod("getColorCurve")
        val getPath: Method? = runCatching { regionType.getMethod("getPath") }.getOrNull()
        val rectFields: Array<Field> = resolveFields(
            getRect.returnType,
            arrayOf("left", "top", "right", "bottom"),
            Int::class.javaPrimitiveType!!
        )
        val curveFields: Array<Field>? = runCatching {
            resolveFields(
                getColorCurve.returnType,
                arrayOf("mMinX", "mMinY", "mMaxX", "mMaxY", "mCurveBias", "mSaturation"),
                Float::class.javaPrimitiveType!!
            )
        }.getOrNull()

        fun writeRect(row: FloatArray, offset: Int, rect: Any) {
            for (i in 0 until 4) {
                row[offset + i] = rectFields[i].getInt(rect).toFloat()
            }
        }

        companion object {
            private fun resolveFields(
                clazz: Class<*>,
                names: Array<String>,
                expectedType: Class<*>
            ): Array<Field> = Array(names.size) { index ->
                val f = clazz.getField(names[index])
                if (f.type != expectedType) {
                    throw IllegalArgumentException("Unexpected type for ${clazz.name}.${names[index]}")
                }
                f
            }
        }
    }

    fun prepare(regionType: Class<*>) {
        accessFor(regionType)
    }

    @Synchronized
    private fun accessFor(regionType: Class<*>): Access {
        val existing = cachedAccess
        if (existing != null && existing.regionType == regionType) return existing
        val created = Access(regionType)
        cachedAccess = created
        return created
    }

    fun encode(regions: Any?): Array<FloatArray> {
        val count = if (regions == null) 0 else ReflectArray.getLength(regions)
        if (count == 0) return EMPTY

        val componentType = regions!!.javaClass.componentType
            ?: throw IllegalArgumentException("Expected array of SemBlurRegionData")
        var access = cachedAccess
        if (access == null || access.regionType != componentType) {
            access = accessFor(componentType)
        }

        @Suppress("UNCHECKED_CAST")
        val rawItems = regions as Array<Any>
        // Standard setBlurRegions(float[][]) only supports rounded rectangles, not arbitrary Path masks
        // (e.g., Lockscreen blur clock digit outlines whose mRect spans the entire 0,0-W,H screen).
        // Skip Path-masked regions so they never blur the full screen.
        val getPath = access.getPath
        val items = ArrayList<Any>(count)
        for (i in 0 until count) {
            val item = rawItems[i]
            if (getPath != null) {
                val p = getPath.invoke(item) as? Path
                if (p != null && !p.isEmpty) continue
            }
            items.add(item)
        }
        val filteredCount = items.size
        if (filteredCount == 0) return EMPTY

        val curveFields = access.curveFields
        val curves = arrayOfNulls<Any>(filteredCount)
        var hasCurve = false
        if (curveFields != null) {
            for (i in 0 until filteredCount) {
                val c = access.getColorCurve.invoke(items[i])
                curves[i] = c
                if (c != null) hasCurve = true
            }
        }

        val cols = if (hasCurve) 24 else 18
        val rows = Array(filteredCount) { FloatArray(cols) }

        for (i in 0 until filteredCount) {
            val item = items[i]
            val row = rows[i]

            val radius = (access.getBlurRadius.invoke(item) as Number).toFloat()
            val alpha = (access.getAlpha.invoke(item) as Number).toFloat()
            requireFinite(radius)
            requireFinite(alpha)

            row[0] = tuneVibrantRadius(radius)
            row[1] = max(0f, min(1f, alpha))

            val bounds = access.getRect.invoke(item)
                ?: throw IllegalArgumentException("Null blur bounds")
            access.writeRect(row, 2, bounds)

            val corners = access.getCornerRadii.invoke(item) as? FloatArray
                ?: throw IllegalArgumentException("Null corner radii")
            if (corners.size != 8) {
                throw IllegalArgumentException("Invalid corner radii length: ${corners.size}")
            }
            System.arraycopy(corners, 0, row, 6, 8)

            val clip = access.getClipRect.invoke(item) ?: bounds
            access.writeRect(row, 14, clip)

            if (hasCurve && curveFields != null) {
                val curve = curves[i]
                if (curve != null) {
                    for (j in 0 until 6) {
                        row[18 + j] = curveFields[j].getFloat(curve)
                    }
                } else {
                    row[20] = 255f
                    row[21] = 255f
                    row[23] = 1f
                }
            }

            for (v in row) requireFinite(v)
        }

        return rows
    }

    fun encodeVisible(regions: Any?, width: Int, height: Int): Array<FloatArray> {
        val rows = encode(regions)
        if (rows.isEmpty() || width <= 0 || height <= 0) return rows

        val maxW = width.toFloat()
        val maxH = height.toFloat()
        var visible = 0
        for (row in rows) {
            row[14] = max(0f, row[14])
            row[15] = max(0f, row[15])
            row[16] = min(maxW, row[16])
            row[17] = min(maxH, row[17])
            if (row[0] > 0f &&
                row[1] > 0f &&
                max(row[2], row[14]) < min(row[4], row[16]) &&
                max(row[3], row[15]) < min(row[5], row[17])
            ) {
                rows[visible++] = row
            }
        }
        return when {
            visible == rows.size -> rows
            visible == 0 -> EMPTY
            else -> rows.copyOfRange(0, visible)
        }
    }

    /**
     * Gentle ceiling (~175px) for huge Samsung radii (250-300px) so AOSP KawaseDualFilterV2 keeps
     * creamy flagship diffusion while preserving real color vibrancy from whatever is behind the glass.
     */
    private fun tuneVibrantRadius(rawRadius: Float): Float {
        val r = max(0f, rawRadius)
        return if (r <= 80f) {
            r
        } else {
            min(175f, 80f + (r - 80f) * 0.44f)
        }
    }

    private fun requireFinite(value: Float) {
        if (value.isNaN() || value.isInfinite()) {
            throw IllegalArgumentException("Non-finite blur region value: $value")
        }
    }
}

internal class BlurSubmissionCache {
    private class Entry(
        val frame: Long,
        val layerId: Int,
        val rows: Array<FloatArray>
    )

    private val entries = WeakHashMap<Any, Entry>()

    @Synchronized
    fun isDuplicate(surface: Any, frame: Long, layerId: Int, rows: Array<FloatArray>): Boolean {
        val entry = entries[surface] ?: return false
        return entry.frame == frame && entry.layerId == layerId && entry.rows.contentDeepEquals(rows)
    }

    @Synchronized
    fun remember(surface: Any, frame: Long, layerId: Int, rows: Array<FloatArray>) {
        entries[surface] = Entry(frame, layerId, rows)
    }
}
