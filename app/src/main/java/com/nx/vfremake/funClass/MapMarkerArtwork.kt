package com.nx.vfremake.funClass

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.res.ResourcesCompat
import com.esri.arcgisruntime.symbology.MarkerSymbol
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.nx.vfremake.R

/** 在主线程生成并复用纹理，ArcGIS 符号的角度由各地图图层独立维护。 */
internal object MapMarkerArtwork {
    private const val TEXTURE_SCALE = 3
    private val vehicleTextures = mutableMapOf<Int, Bitmap>()
    private val rowTextures = mutableMapOf<Pair<Int, Boolean>, Bitmap>()

    fun tractor(resources: Resources): PictureMarkerSymbol =
        vehicle(resources, R.drawable.map_tractor, 34, 42)

    fun compensation(resources: Resources): PictureMarkerSymbol =
        vehicle(resources, R.drawable.map_position_compensation, 14, 14)

    private fun vehicle(resources: Resources, resourceId: Int, width: Int, height: Int): PictureMarkerSymbol {
        val texture = vehicleTextures.getOrPut(resourceId) {
            bitmap(width, height) { canvas ->
                val vector = checkNotNull(ResourcesCompat.getDrawable(resources, resourceId, null))
                vector.setBounds(0, 0, width, height)
                vector.draw(canvas)
            }
        }
        return symbol(resources, texture, width, height)
    }

    fun rowUnit(resources: Resources, qualityColor: Int, selected: Boolean): PictureMarkerSymbol {
        val texture = rowTextures.getOrPut(qualityColor to selected) {
            bitmap(20, 24) { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                fun panel(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
                    paint.color = color
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
                }

                // 浅蓝外缘表示选中。白色轮廓使小件在不同图斑上都能看清。
                val outline = if (selected) 0xFF91D9FF.toInt() else Color.WHITE
                panel(6.5f, 0.6f, 13.5f, 23.4f, 2.9f, outline)
                panel(1f, 5f, 19f, 21.5f, 4.2f, outline)
                val frame = 0xFF24313C.toInt()
                panel(7.7f, 1.8f, 12.3f, 22.2f, 1.8f, frame)
                panel(2.1f, 8.6f, 6.1f, 20.4f, 1.7f, frame)
                panel(13.9f, 8.6f, 17.9f, 20.4f, 1.7f, frame)
                val tread = 0xFF748491.toInt()
                for (top in listOf(11f, 14f, 17f)) {
                    panel(2.6f, top, 4.2f, top + 0.7f, 0.35f, tread)
                    panel(15.8f, top, 17.4f, top + 0.7f, 0.35f, tread)
                }
                panel(4f, 5.9f, 16f, 18.5f, 3.2f, frame)
                panel(4.7f, 6.6f, 15.3f, 17.8f, 2.6f, qualityColor)
                panel(6.3f, 7.2f, 13.7f, 8.1f, 0.4f, 0x66FFFFFF)
            }
        }
        return symbol(resources, texture, 20, 24)
    }

    private inline fun bitmap(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(width * TEXTURE_SCALE, height * TEXTURE_SCALE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(TEXTURE_SCALE.toFloat(), TEXTURE_SCALE.toFloat())
        draw(canvas)
        return bitmap
    }

    private fun symbol(resources: Resources, texture: Bitmap, widthDp: Int, heightDp: Int) =
        PictureMarkerSymbol(BitmapDrawable(resources, texture)).apply {
            width = widthDp.toFloat()
            height = heightDp.toFloat()
            angleAlignment = MarkerSymbol.AngleAlignment.SCREEN
        }
}
