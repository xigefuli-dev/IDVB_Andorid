package com.idvb.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 悬浮窗地图视图（对齐参考项目 MapOverlayWindow 的渲染 + 手势）。
 *
 * 布局：底层 ImageView 显示楼层原图，顶部一条可隐藏控制条
 * （锁定/楼层切换/透明度滑杆/隐藏/关闭）。手势：
 * - 单指拖动 → 回调 [Listener.onMove]（由 Service 更新窗口 x/y）
 * - 双指捏合 → 回调 [Listener.onScale]（等比缩放窗口）
 * - 点按空白 → 切换控制条显隐
 * - 锁定态：控制条隐藏，整窗点击穿透交给 OverlayWindowManager 的 FLAG_NOT_TOUCHABLE
 */
class OverlayMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 与 Service 的通信接口 */
    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onScale(factor: Float)
        fun onOpacityChanged(value: Float)
        fun onToggleLock()
        fun onSwitchFloor(delta: Int)
        fun onHide()
        fun onClose()
    }

    var listener: Listener? = null

    /** 楼层 [key, 显示名] 列表，用于切换与标签 */
    var floors: List<Pair<String, String>> = emptyList()
        set(value) {
            field = value
            updateControls()
        }

    var floorIndex: Int = 0
        set(value) {
            field = value
            updateControls()
        }

    var locked: Boolean = false
        set(value) {
            field = value
            updateControls()
        }

    /** 地图图片（由 Service 解码后注入） */
    var image: Bitmap?
        get() = mapImageView.drawable as? Bitmap
        set(value) {
            mapImageView.setImageBitmap(value)
        }

    /** 透明度 0..1，对齐参考项目 MapOpacity=0.46 */
    var opacity: Float = 0.46f
        set(value) {
            field = value.coerceIn(0f, 1f)
            mapImageView.alpha = field
            opacityBar.progress = (field * 100).toInt()
        }

    /** 控制条显隐（锁定态强制隐藏） */
    var barVisible: Boolean = true
        set(value) {
            field = value
            updateControls()
        }

    private val mapImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
    }

    private val controlBar: LinearLayout
    private val lockButton: TextView
    private val floorLabel: TextView
    private val opacityBar: SeekBar

    private var pointerCount = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(mapImageView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(Color.argb(0xE6, 0x1C, 0x1C, 0x1C))
        }

        lockButton = makeButton("锁定") { listener?.onToggleLock() }
        val prevBtn = makeButton("◀") { listener?.onSwitchFloor(-1) }
        floorLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(6), 0, dp(6), 0)
        }
        val nextBtn = makeButton("▶") { listener?.onSwitchFloor(1) }
        opacityBar = SeekBar(context).apply {
            min = 10
            max = 100
            progress = (opacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) listener?.onOpacityChanged(progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val hideBtn = makeButton("隐藏") { listener?.onHide() }
        val closeBtn = makeButton("✕") { listener?.onClose() }

        controlBar.addView(lockButton)
        controlBar.addView(prevBtn)
        controlBar.addView(floorLabel)
        controlBar.addView(nextBtn)
        controlBar.addView(opacityBar)
        controlBar.addView(hideBtn)
        controlBar.addView(closeBtn)

        addView(controlBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        updateControls()
    }

    private fun makeButton(text: String, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF))
            isClickable = true
            setOnClickListener(onClick)
        }

    private fun updateControls() {
        lockButton.text = if (locked) "解锁" else "锁定"
        floorLabel.text = floors.getOrNull(floorIndex)?.second ?: ""
        controlBar.visibility = if (locked || !barVisible) View.GONE else View.VISIBLE
    }

    // ---- 手势 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCount = 1
                lastX = event.rawX
                lastY = event.rawY
                downX = event.rawX
                downY = event.rawY
                moved = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount++
                if (pointerCount == 2) {
                    lastDist = distance(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    if (!moved && exceedsTouchSlop(event)) moved = true
                    if (moved) {
                        listener?.onMove(event.rawX - lastX, event.rawY - lastY)
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                } else if (pointerCount >= 2) {
                    val d = distance(event)
                    if (lastDist > 0f && d > 0f) {
                        listener?.onScale(d / lastDist)
                    }
                    lastDist = d
                    moved = true
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount--
                if (pointerCount < 2) {
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }

            MotionEvent.ACTION_UP -> {
                if (pointerCount == 1 && !moved) {
                    barVisible = !barVisible
                }
                pointerCount = 0
            }
        }
        return true
    }

    private fun exceedsTouchSlop(event: MotionEvent): Boolean {
        val slop = ViewConfigurationCompat.touchSlop(context)
        return abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** 触控滑阀兼容（API 26 起可直接用 ViewConfiguration.getScaledTouchSlop，这里简化封装） */
private object ViewConfigurationCompat {
    fun touchSlop(context: Context): Int =
        android.view.ViewConfiguration.get(context).scaledTouchSlop
}
