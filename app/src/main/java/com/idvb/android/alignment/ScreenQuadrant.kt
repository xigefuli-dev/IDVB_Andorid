package com.idvb.android.alignment

/**
 * 屏幕象限枚举（以屏幕中心为原点的笛卡尔坐标系）。
 *
 * 屏幕横放时：
 * - 第一象限（右上，x >= centerX && y < centerY）：用户在此区域点击通常为关图（X 按钮）。
 * - 第二象限（左上，x < centerX && y < centerY）：用户在此区域点击通常为开图（地图雷达按钮）。
 * - 第三象限（左下，x < centerX && y >= centerY）：摇杆与移动控制区。
 * - 第四象限（右下，x >= centerX && y >= centerY）：技能与操作区。
 */
enum class ScreenQuadrant {
    FIRST,
    SECOND,
    THIRD,
    FOURTH,
    UNKNOWN;

    companion object {
        fun fromCoordinates(x: Float, y: Float, screenWidth: Int, screenHeight: Int): ScreenQuadrant {
            if (screenWidth <= 0 || screenHeight <= 0 || !x.isFinite() || !y.isFinite() || x < 0f || y < 0f ||
                x > screenWidth.toFloat() || y > screenHeight.toFloat()) {
                return UNKNOWN
            }
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            return when {
                x >= centerX && y < centerY -> FIRST
                x < centerX && y < centerY -> SECOND
                x < centerX && y >= centerY -> THIRD
                x >= centerX && y >= centerY -> FOURTH
                else -> UNKNOWN
            }
        }
    }
}
