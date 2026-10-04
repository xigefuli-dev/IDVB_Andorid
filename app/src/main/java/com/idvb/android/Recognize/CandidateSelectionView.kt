package com.idvb.android.recognize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.os.Build
import android.view.WindowInsets
import com.idvb.android.data.MapRepository
import com.idvb.android.graphics.decodeMapRegion
import com.idvb.android.idvm.MetadataTag
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/** 全屏原生候选窗：识别结果优先，目录中其余地图继续排在末尾。 */
class CandidateSelectionView(
    context: Context,
    private val result: RecognitionResult,
    private val repository: MapRepository,
    private val manualSelection: Boolean = false,
) : View(context) {
    interface Listener { fun onSelected(candidate: RecognitionCandidate); fun onCancelled() }
    var listener: Listener? = null

    private val density get() = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbnails = MutableList<Bitmap?>(result.candidates.size) { null }
    private var scroll = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var armedManualIndex: Int? = null
    private val mapTags = result.candidates.associate { it.map.id to repository.loadTags(it.map.id) }
    private val tagGroups = ManualMapSelectionPolicy.groups(mapTags.values.flatten())
    private val selectedTags = mutableMapOf<String, String>()
    private var tagChipRects = emptyList<Pair<RectF, ManualTagGroup>>()
    private var openTagGroup: ManualTagGroup? = null
    private var tagOptionRects = emptyList<Pair<RectF, String>>()
    private val previewLock = Any()
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var previewRevision = 0L
    @Volatile private var previewDisposed = true
    private var previewWanted = emptyList<Int>()
    private var previewRetained = emptySet<Int>()
    private val previewLoading = mutableSetOf<Int>()
    private val previewFailed = mutableSetOf<Int>()
    private var previewIdle = java.util.concurrent.CountDownLatch(0)
    private var previewWorkerRunning = false
    private var tagMenuBounds: RectF? = null
    private var tagMenuScroll = 0f
    private var tagMenuMaxScroll = 0f
    private var tagMenuGesture = false

    private val safeTop get() = if (Build.VERSION.SDK_INT >= 30) {
        rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())?.top ?: 0
    } else rootWindowInsets?.systemWindowInsetTop ?: 0
    private val safeBottom get() = if (Build.VERSION.SDK_INT >= 30) {
        rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())?.bottom ?: 0
    } else rootWindowInsets?.systemWindowInsetBottom ?: 0

    private fun tagLayout(): List<Pair<RectF, ManualTagGroup>> {
        var left = dp(12f)
        var top = safeTop + dp(64f)
        return tagGroups.map { group ->
            text.textSize = dp(10f); text.typeface = android.graphics.Typeface.DEFAULT
            val chipWidth = (text.measureText("${group.name}：${selectedTags[group.id] ?: "全部"}") + dp(18f))
                .coerceAtMost((width - dp(24f)).coerceAtLeast(dp(24f)))
            if (left > dp(12f) && left + chipWidth > width - dp(12f)) {
                left = dp(12f); top += dp(40f)
            }
            val rect = RectF(left, top, left + chipWidth, top + dp(34f))
            left = rect.right + dp(6f)
            rect to group
        }
    }
    private val headerHeight get() = tagLayout().lastOrNull()?.first?.bottom?.plus(dp(12f)) ?: (safeTop + dp(82f))
    private val landscape get() = width > height
    private val contentBottom get() = height - safeBottom - dp(12f)
    private val cardHeight get() = minOf(dp(232f), (contentBottom - listTop).coerceAtLeast(dp(80f)))
    private val cardGap get() = dp(10f)
    private val previewRect get() = if (landscape) {
        RectF(dp(16f), headerHeight, width * .34f, contentBottom)
    } else {
        val available = (contentBottom - headerHeight - dp(192f)).coerceAtLeast(dp(48f))
        val previewHeight = minOf(dp(160f), height * .22f, available)
        RectF(dp(16f), headerHeight, width - dp(16f), headerHeight + previewHeight)
    }
    private val gridLeft get() = if (landscape) previewRect.right + dp(12f) else dp(12f)
    private val listTop get() = if (landscape) headerHeight else previewRect.bottom + dp(12f)
    private val columnCount get() = if ((width - gridLeft - dp(16f) - cardGap) / 2 >= dp(150f)) 2 else 1
    private val cardWidth get() = (width - gridLeft - dp(16f) - cardGap * (columnCount - 1)) / columnCount
    private val cancelRect get() = RectF(width - dp(92f), safeTop + dp(14f), width - dp(18f), safeTop + dp(54f))
    private fun visibleIndices() = result.candidates.indices.filter { index ->
        ManualMapSelectionPolicy.matches(mapTags[result.candidates[index].map.id].orEmpty(), selectedTags)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation can change the column count; an old scroll offset may exceed
        // the new list length or leave dropdown hit targets at stale positions.
        scroll = 0f
        armedManualIndex = null
        openTagGroup = null
        tagOptionRects = emptyList()
        tagMenuBounds = null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(17, 20, 23))
        text.color = Color.WHITE; text.textSize = dp(18f); text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        drawEllipsized(canvas, if (manualSelection) "手动选择地图" else "选择候选地图", dp(20f), safeTop + dp(31f), cancelRect.left - dp(28f))
        val reliableCount = result.candidates.count { it.disposition == CandidateDisposition.RELIABLE }
        text.typeface = android.graphics.Typeface.DEFAULT
        text.textSize = dp(11f)
        text.color = if (manualSelection || reliableCount > 0) Color.rgb(150, 225, 170) else Color.rgb(244, 190, 90)
        drawEllipsized(canvas,
            if (manualSelection) "按标签筛选后选择地图"
            else if (reliableCount > 0) "已确认 $reliableCount 张；其余请人工核对"
            else "尚未确认，请核对后选择",
            dp(20f),
            safeTop + dp(53f),
            cancelRect.left - dp(28f),
        )
        text.typeface = android.graphics.Typeface.DEFAULT
        paint.color = Color.rgb(50, 55, 60); canvas.drawRoundRect(cancelRect, dp(10f), dp(10f), paint)
        text.textAlign = Paint.Align.CENTER; text.textSize = dp(13f); canvas.drawText("取消", cancelRect.centerX(), cancelRect.centerY() + dp(5f), text); text.textAlign = Paint.Align.LEFT
        drawTagSelectors(canvas)

        val livePreview = previewRect
        paint.color = Color.BLACK; canvas.drawRoundRect(livePreview, dp(12f), dp(12f), paint)
        drawBitmapFit(canvas, result.capturedRegion, livePreview)

        canvas.save(); canvas.clipRect(0f, listTop, width.toFloat(), contentBottom)
        val wantedPreviews = mutableListOf<Int>()
        visibleIndices().forEachIndexed { visibleIndex, index ->
            val candidate = result.candidates[index]
            val row = visibleIndex / columnCount
            val column = visibleIndex % columnCount
            val left = gridLeft + column * (cardWidth + cardGap)
            val top = listTop + row * (cardHeight + cardGap) - scroll
            // Visible cards plus one row of prefetch, independent of catalog size.
            if (top + cardHeight >= listTop - cardHeight - cardGap && top <= height + cardHeight + cardGap)
                wantedPreviews += index
            if (top + cardHeight < listTop || top > height) return@forEachIndexed
            drawCandidate(canvas, index, candidate, thumbnails[index], left, top)
        }
        canvas.restore()
        prepareVisiblePreviews(wantedPreviews)
        drawTagDropdown(canvas)
    }

    private fun drawTagSelectors(canvas: Canvas) {
        val chips = tagLayout()
        chips.forEach { (chip, group) ->
            val value = selectedTags[group.id] ?: "全部"
            text.textSize = dp(10f); text.typeface = android.graphics.Typeface.DEFAULT
            val label = "${group.name}：$value"
            paint.color = if (value == "全部") Color.rgb(50, 55, 60) else Color.rgb(32, 83, 57)
            canvas.drawRoundRect(chip, dp(8f), dp(8f), paint)
            text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
            canvas.drawText(label, chip.centerX(), chip.centerY() + dp(4f), text)
            text.textAlign = Paint.Align.LEFT
        }
        tagChipRects = chips
    }

    private fun drawTagDropdown(canvas: Canvas) {
        val group = openTagGroup ?: return
        val anchor = tagChipRects.firstOrNull { it.second.id == group.id }?.first ?: return
        val values = listOf("全部") + group.values
        val width = max(anchor.width(), text.apply { textSize = dp(12f) }.measureText(values.maxBy(String::length)) + dp(28f))
            .coerceAtMost(this.width - dp(24f))
        val left = (anchor.right - width).coerceIn(dp(12f), (this.width - dp(12f) - width).coerceAtLeast(dp(12f)))
        val rowHeight = dp(40f)
        val menuHeight = minOf(values.size * rowHeight, (contentBottom - safeTop - dp(8f)).coerceAtLeast(rowHeight))
        val top = minOf(anchor.bottom, contentBottom - menuHeight).coerceAtLeast(safeTop + dp(8f))
        val menu = RectF(left, top, left + width, top + menuHeight)
        tagMenuBounds = menu
        tagMenuMaxScroll = max(0f, values.size * rowHeight - menuHeight)
        tagMenuScroll = tagMenuScroll.coerceIn(0f, tagMenuMaxScroll)
        canvas.save(); canvas.clipRect(menu)
        val options = values.mapIndexedNotNull { index, value ->
            RectF(left, top + index * rowHeight - tagMenuScroll, left + width, top + (index + 1) * rowHeight - tagMenuScroll)
                .also { rect ->
                    paint.color = if (value == (selectedTags[group.id] ?: "全部")) Color.rgb(32, 83, 57) else Color.rgb(50, 55, 60)
                    canvas.drawRect(rect, paint)
                    text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                    canvas.drawText(value, rect.centerX(), rect.centerY() + dp(5f), text)
                    text.textAlign = Paint.Align.LEFT
                }.let { rect -> if (rect.intersect(menu)) rect to value else null }
        }
        canvas.restore()
        tagOptionRects = options
    }

    private fun drawCandidate(canvas: Canvas, index: Int, candidate: RecognitionCandidate, thumbnail: Bitmap?, left: Float, top: Float) {
        val card = RectF(left, top, left + cardWidth, top + cardHeight)
        paint.style = Paint.Style.FILL
        paint.color = when (candidate.disposition) {
            CandidateDisposition.RELIABLE -> Color.rgb(27, 54, 38)
            CandidateDisposition.NEEDS_VERIFICATION -> Color.rgb(57, 48, 27)
            CandidateDisposition.CATALOG_ONLY -> Color.rgb(34, 37, 41)
        }
        canvas.drawRoundRect(card, dp(12f), dp(12f), paint)
        // 候选卡片展示完整地图裁剪区；识别姿态不参与缩略图布局。
        val compact = card.height() < dp(180f)
        val imageRect = if (compact) {
            RectF(card.left + dp(7f), card.top + dp(7f),
                card.left + dp(7f) + minOf(card.height() - dp(14f), card.width() * .5f), card.bottom - dp(7f))
        } else RectF(card.left + dp(7f), card.top + dp(7f), card.right - dp(7f), card.bottom - if (manualSelection) dp(32f) else dp(54f))
        paint.color = Color.BLACK; canvas.drawRoundRect(imageRect, dp(7f), dp(7f), paint)
        thumbnail?.let { drawBitmapFit(canvas, it, imageRect) }
        val x = if (compact) imageRect.right + dp(10f) else card.left + dp(12f)
        val labelWidth = card.right - x - dp(12f)
        val status = when (candidate.disposition) {
            CandidateDisposition.RELIABLE -> "结构确认"
            CandidateDisposition.NEEDS_VERIFICATION -> "待人工确认"
            CandidateDisposition.CATALOG_ONLY -> "目录手选"
        }
        val statusColor = when (candidate.disposition) {
            CandidateDisposition.RELIABLE -> Color.rgb(150, 225, 170)
            CandidateDisposition.NEEDS_VERIFICATION -> Color.rgb(244, 190, 90)
            CandidateDisposition.CATALOG_ONLY -> Color.rgb(165, 170, 177)
        }
        if (!manualSelection) paint.color = Color.argb(220, 24, 28, 31)
        val statusWidth = text.apply { textSize = dp(10f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
            .measureText(status) + dp(14f)
        val statusRight = if (compact) card.right - dp(7f) else imageRect.right
        val statusRect = RectF(
            statusRight - statusWidth - dp(6f),
            imageRect.top + dp(6f),
            statusRight - dp(6f),
            imageRect.top + dp(28f),
        )
        if (!manualSelection) {
            canvas.drawRoundRect(statusRect, dp(8f), dp(8f), paint)
            text.textAlign = Paint.Align.CENTER; text.color = statusColor
            canvas.drawText(status, statusRect.centerX(), statusRect.centerY() + dp(4f), text)
            text.textAlign = Paint.Align.LEFT
        }

        text.textSize = dp(12f); text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        text.color = Color.WHITE
        drawEllipsized(canvas, candidate.map.title, x,
            if (compact) card.top + dp(50f) else card.bottom - if (manualSelection) dp(11f) else dp(30f), labelWidth)
        if (!manualSelection) {
            text.textSize = dp(9f); text.typeface = android.graphics.Typeface.DEFAULT
            text.color = statusColor
            val evidence = if (armedManualIndex == index && candidate.disposition != CandidateDisposition.RELIABLE) {
                "再次点击：作为人工选择锁定（结构未确认）"
            } else {
                candidate.evidenceLabel
            }
            drawEllipsized(canvas, evidence, x, if (compact) card.top + dp(68f) else card.bottom - dp(12f), labelWidth)
        }

        if (armedManualIndex == index && candidate.disposition != CandidateDisposition.RELIABLE) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = statusColor
            canvas.drawRoundRect(card, dp(12f), dp(12f), paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tagMenuGesture = openTagGroup != null && tagMenuBounds?.contains(event.x, event.y) == true
            if (openTagGroup == null) tagMenuScroll = 0f
        }
        if (tagMenuGesture) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; lastY = event.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.y - downY) > dp(6f)) moved = true
                    tagMenuScroll = (tagMenuScroll + lastY - event.y).coerceIn(0f, tagMenuMaxScroll)
                    lastY = event.y; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) tagOptionRects.firstOrNull { it.first.contains(event.x, event.y) }?.let { (_, value) ->
                        openTagGroup?.let { group ->
                            if (value == "全部") selectedTags.remove(group.id) else selectedTags[group.id] = value
                        }
                        openTagGroup = null; tagOptionRects = emptyList(); tagMenuBounds = null
                        armedManualIndex = null; scroll = 0f; invalidate()
                    }
                    tagMenuGesture = false
                }
                MotionEvent.ACTION_CANCEL -> tagMenuGesture = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = event.y; lastY = event.y; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > dp(6f)) moved = true
                if (moved && event.y >= listTop) {
                    val rows = (visibleIndices().size + columnCount - 1) / columnCount
                    val maxScroll = max(0f, rows * (cardHeight + cardGap) - cardGap - (contentBottom - listTop))
                    scroll = (scroll - (event.y - lastY)).coerceIn(0f, maxScroll); invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> if (!moved) {
                if (cancelRect.contains(event.x, event.y)) listener?.onCancelled()
                else if (tagOptionRects.firstOrNull { it.first.contains(event.x, event.y) }?.let { (_, value) ->
                    val group = openTagGroup ?: return@let false
                    if (value == "全部") selectedTags.remove(group.id) else selectedTags[group.id] = value
                    openTagGroup = null; tagOptionRects = emptyList(); armedManualIndex = null; scroll = 0f; invalidate(); true
                } == true) Unit
                else if (tagChipRects.firstOrNull { it.first.contains(event.x, event.y) }?.let { (_, group) ->
                    openTagGroup = if (openTagGroup?.id == group.id) null else group
                    tagOptionRects = emptyList(); tagMenuBounds = null; tagMenuScroll = 0f; invalidate(); true
                } == true) Unit
                else if (event.y >= listTop && event.y <= contentBottom) {
                    val column = ((event.x - gridLeft) / (cardWidth + cardGap)).toInt()
                    val row = ((event.y - listTop + scroll) / (cardHeight + cardGap)).toInt()
                    val localX = event.x - gridLeft - column * (cardWidth + cardGap)
                    val localY = event.y - listTop + scroll - row * (cardHeight + cardGap)
                    if (column in 0 until columnCount && row >= 0 && localX in 0f..cardWidth && localY in 0f..cardHeight) {
                        val index = visibleIndices().getOrNull(row * columnCount + column)
                        index?.let { result.candidates[it] }?.let { candidate ->
                            val decision = CandidateSelectionPolicy.onTap(
                                candidate.disposition,
                                index,
                                armedManualIndex,
                            )
                            armedManualIndex = decision.armedIndex
                            if (decision.select) {
                                listener?.onSelected(candidate)
                            } else {
                                invalidate()
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        synchronized(previewLock) { previewDisposed = true; previewRevision++; previewWanted = emptyList() }
        thumbnails.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }
        thumbnails.fill(null)
        previewRetained = emptySet()
        previewFailed.clear()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(previewLock) { previewDisposed = false; previewRevision++ }
        invalidate()
    }

    private fun prepareVisiblePreviews(indices: List<Int>) {
        if (previewDisposed) return
        previewRetained = indices.toSet()
        thumbnails.forEachIndexed { index, bitmap ->
            if (bitmap != null && index !in previewRetained) { bitmap.recycle(); thumbnails[index] = null }
        }
        synchronized(previewLock) {
            previewWanted = indices.filter { thumbnails[it] == null && it !in previewLoading && it !in previewFailed }
            if (previewWorkerRunning || previewWanted.isEmpty()) return
            previewWorkerRunning = true
            val revision = previewRevision
            val finished = java.util.concurrent.CountDownLatch(1).also { previewIdle = it }
            thread(name = "idvb-map-previews", isDaemon = true) {
                try {
                    while (true) {
                        val index = synchronized(previewLock) {
                            if (previewDisposed || revision != previewRevision) null
                            else previewWanted.firstOrNull()?.also {
                                previewWanted = previewWanted.drop(1); previewLoading += it
                            }
                        } ?: break
                        val preview = runCatching { createCandidatePreview(result.candidates[index]) }.getOrNull()
                        // Handler owns late completions; View.post can strand them in a detached view's RunQueue.
                        previewHandler.post {
                            synchronized(previewLock) { previewLoading -= index }
                            if (!previewDisposed && revision == previewRevision && index in previewRetained) {
                                thumbnails[index]?.takeUnless(Bitmap::isRecycled)?.recycle()
                                thumbnails[index] = preview
                                if (preview == null) previewFailed += index
                                invalidate()
                            } else preview?.takeUnless(Bitmap::isRecycled)?.recycle()
                        }
                    }
                } finally {
                    synchronized(previewLock) { previewWorkerRunning = false }
                    finished.countDown()
                    previewHandler.post {
                        if (!previewDisposed) prepareVisiblePreviews(previewRetained.toList())
                    }
                }
            }
        }
    }

    /** Release worker only; main delivery is drained separately before shared caches are cleared. */
    internal fun awaitPreviewIdle() {
        val finished = synchronized(previewLock) { previewIdle }
        check(finished.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Candidate preview worker did not exit" }
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val scale = minOf(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2), paint)
    }
    private fun drawEllipsized(canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float) {
        var shown = value
        while (shown.length > 4 && text.measureText(shown) > maxWidth) shown = shown.dropLast(2) + "…"
        canvas.drawText(shown, x, y, text)
    }
    internal fun createCandidatePreview(candidate: RecognitionCandidate): Bitmap? {
        val floor = candidate.map.floors.firstOrNull { it.key == candidate.floorKey }
            ?: candidate.map.floors.minByOrNull { it.sortOrder }
            ?: return null
        val assets = repository.loadRecognitionAssets(candidate.map.id, floor)
        val recognitionFile = assets.recognitionImageFile
        val sourceFile = recognitionFile ?: repository.floorImageFile(candidate.map.id, floor.imagePath)
        // Decode the selected region before downsampling, keeping small crops sharp.
        // A tentative match may have a tiny scale or an offscreen translation. Neither
        // is a display transform: applying them to a full capture canvas hid the map.
        val target = max(resources.displayMetrics.widthPixels / 2, (177f * density).toInt())
            .coerceIn(400, 1200)
        return decodeMapRegion(
            sourceFile,
            if (recognitionFile != null) null else assets.recognitionRegion,
            target,
            if (recognitionFile != null) emptyList() else repository.loadFreeCropPoints(candidate.map.id, floor),
        )
    }
    private fun dp(value: Float) = value * density
}

data class ManualTagGroup(val id: String, val name: String, val values: List<String>)

internal object ManualMapSelectionPolicy {
    fun groups(tags: List<MetadataTag>): List<ManualTagGroup> = tags.groupBy { it.groupId }
        .map { (id, values) -> ManualTagGroup(id, values.first().groupName, values.map { it.value }.distinct()) }

    fun matches(tags: List<MetadataTag>, selections: Map<String, String>): Boolean =
        selections.all { (groupId, value) -> tags.any { it.groupId == groupId && it.value == value } }
}

internal object CandidateSelectionPolicy {
    data class Decision(val select: Boolean, val armedIndex: Int?)

    fun onTap(disposition: CandidateDisposition, index: Int, armedIndex: Int?): Decision =
        if (disposition == CandidateDisposition.RELIABLE || armedIndex == index) {
            Decision(select = true, armedIndex = null)
        } else {
            Decision(select = false, armedIndex = index)
        }
}
