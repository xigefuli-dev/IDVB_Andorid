package com.idvb.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView

/** 新攻略图显示层，仅负责渲染；窗口本身由服务设置为完全点击穿透。 */
class GuideMapView(context: Context) : ImageView(context) {
    init {
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = false
    }

    fun showBitmap(bitmap: Bitmap?, opacity: Float) {
        alpha = opacity.coerceIn(.1f, 1f)
        setImageBitmap(bitmap)
    }
}
