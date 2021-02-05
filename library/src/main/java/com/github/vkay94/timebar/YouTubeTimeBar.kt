package com.github.vkay94.timebar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ui.TimeBar
import com.google.android.exoplayer2.util.Assertions
import com.google.android.exoplayer2.util.Util
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max


class YouTubeTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), LibTimeBar {

    /** Rect of total width */
    private val seekBounds = Rect()
    /** Rect of the progress bounds in millis, from 0 to duration */
    private val progressBar = Rect()
    /** Rect of the current playback state */
    private val positionBar = Rect()
    /** Rect of the buffered state */
    private val bufferedBar = Rect()
    /** Rect of the scrubber circle position */
    private val scrubberBar = Rect()

    /** Scrubber position in millis. May not exactly as the scrubber circle (visually) itself */
    private var scrubPosition: Long = 0
    /** Total duration in millis */
    private var duration: Long = C.TIME_UNSET
    /** Current playback position in millis */
    private var position: Long = 0
    /** Buffered position in millis */
    private var bufferedPosition: Long = 0

    // Paint stuff
    private val playedPaint: Paint = Paint()
    private val bufferedPaint: Paint = Paint()
    private val unPlayedPaint: Paint = Paint()
    private val scrubberCirclePaint: Paint = Paint()

    // Scrubbing stuff
    private var isScrubbing = false
        set(value) {
            field = value
            calculateGapChapters()
        }
    private var scrubberDisabledSize: Int
    private var scrubberEnabledSize: Int
    private var scrubberDraggedSize: Int

    private val scrubListeners: ArrayList<TimeBar.OnScrubListener> = arrayListOf()
    private val segmentListeners: ArrayList<LibTimeBar.SegmentListener> = arrayListOf()

    //region General, public settings

    private var showScrubber = false

    fun showScrubber() {
        showScrubber = true
        invalidate()
    }

    fun hideScrubber() {
        showScrubber = false
        invalidate()
    }

    //endregion

    //region TimeBar scrubbing logic + listener

    override fun addListener(listener: TimeBar.OnScrubListener) {
        Assertions.checkNotNull(listener)
        scrubListeners.add(listener)
    }

    override fun removeListener(listener: TimeBar.OnScrubListener) {
        Assertions.checkNotNull(listener)
        scrubListeners.remove(listener)
    }

    private fun startScrubbing(scrubPosition: Long) {
        this.scrubPosition = scrubPosition

        isScrubbing = true
        isPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)

        for (listener in scrubListeners) {
            listener.onScrubStart(this, scrubPosition)
        }
    }

    private fun updateScrubbing(scrubPosition: Long) {
        if (this.scrubPosition == scrubPosition)
            return

        this.scrubPosition = scrubPosition

        for (listener in scrubListeners) {
            listener.onScrubMove(this, scrubPosition)
        }
    }

    private fun stopScrubbing(canceled: Boolean) {
        removeCallbacks(stopScrubbingRunnable)

        isScrubbing = false
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()

        for (listener in scrubListeners) {
            listener.onScrubStop(this, scrubPosition, canceled)
        }
    }

    //endregion

    //region Chapter / segment scrubbing logic + listener and TimeBarPreview

    private var currentChapter: YouTubeChapter? = null

    /**
     * [YouTubeChapter]s for this TimeBar.
     *
     * If the chapter's list is not empty the portions will
     * be drawn on the TimeBar with small gaps as indicators.
     *
     * You can use [SegmentListener.onChapterChanged][LibTimeBar.SegmentListener.onChapterChanged] to react
     * on changes.
     */
    var chapters = listOf<YouTubeChapter>()
        set(value) {
            val sorted = value.sortedBy { it.startTimeMs }.toList()
            field = if (sorted.size > 1 && sorted.first().startTimeMs == 0L) sorted else emptyList()
            calculateGapChapters()
            invalidate()
        }

    private var currentSegment: YouTubeSegment? = null

    /**
     * [YouTubeSegment]s for this TimeBar.
     *
     * Each chapter is drawn as colored portion. Make sure that there aren't any overlapping
     * segments. There may be a check in the future but in the moment there is none.
     *
     * You can use [SegmentListener.onSegmentChanged][LibTimeBar.SegmentListener.onSegmentChanged] to react
     * on changes.
     */
    var segments = listOf<YouTubeSegment>()
        set(value) {
            val sorted = value.filter { it.startTimeMs < it.endTimeMs && it.startTimeMs >= 0 }
                .sortedBy { it.startTimeMs }
                .toList()
            field = sorted
            invalidate()
        }

    private var timeBarPreview: YouTubeTimeBarPreview? = null

    /**
     * Sets the [YouTubeTimeBarPreview] which is listening for scrubbing updates to.
     */
    fun timeBarPreview(timeBarPreview: YouTubeTimeBarPreview?) = apply {
        this.timeBarPreview = timeBarPreview
    }

    override fun addSegmentListener(listener: LibTimeBar.SegmentListener) {
        Assertions.checkNotNull(listener)
        segmentListeners.add(listener)
    }

    override fun removeSegmentListener(listener: LibTimeBar.SegmentListener) {
        Assertions.checkNotNull(listener)
        segmentListeners.remove(listener)
    }

    private fun libStartScrubbing(scrubPosition: Long) {
        // Chapters
        if (chapters.isNotEmpty()) {
            getChapterAt(scrubPosition)?.let {
                if (currentChapter != it) { notifyChapterChanged(it, true) }
            }
        }
        timeBarPreview?.let {
            it.updatePosition(scrubberPositionScreen, scrubPosition)
            it.show()
            it.time(scrubPosition)
        }
    }

    private fun libUpdateScrubbing(scrubPosition: Long) {
        if (chapters.isNotEmpty()) {
            getChapterAt(scrubPosition)?.let {
                if (currentChapter != it) { notifyChapterChanged(it, true) }
            }
        }
        timeBarPreview?.let {
            it.updatePosition(scrubberPositionScreen, scrubPosition)
            it.time(scrubPosition)
        }
    }

    private fun libStopScrubbing() {
        timeBarPreview?.hide()
    }

    private fun getSegmentAt(position: Long): YouTubeSegment? {
        return segments.firstOrNull { position in it.startTimeMs..it.endTimeMs }
    }

    private fun getChapterAt(position: Long): YouTubeChapter? {
        var result: YouTubeChapter? = null
        chapters.forEach {
            if (it.startTimeMs > position) {
                return@forEach
            }
            result = it
        }
        return result
    }

    private fun notifyChapterChanged(newChapter: YouTubeChapter, drag: Boolean) {
        currentChapter = newChapter
        segmentListeners.forEach { it.onChapterChanged(this, newChapter, drag) }
        timeBarPreview?.title(newChapter.title ?: "UNDEFINED")
    }

    private fun notifySegmentChanged(newSegment: YouTubeSegment?) {
        currentSegment = newSegment
        segmentListeners.forEach { it.onSegmentChanged(this, newSegment) }
    }

    //endregion

    //region Overridden durations

    override fun getPosition(): Long {
        return if (duration == C.TIME_UNSET) -1 else position
    }

    override fun getBufferedPosition(): Long {
        return if (duration == C.TIME_UNSET) -1 else bufferedPosition
    }

    override fun getDuration(): Long {
        return if (duration == C.TIME_UNSET) -1 else duration
    }

    override fun setPosition(position: Long) {
        if (this.position != position) {
            this.position = position
            updateUIValues()
            invalidate()

            if (!isScrubbing) {
                if (chapters.isNotEmpty()) {
                    getChapterAt(position)?.let {
                        if (currentChapter != it) { notifyChapterChanged(it, false) }
                    }
                }
            }
            if (segments.isNotEmpty()) {
                with(getSegmentAt(position)) {
                    if (currentSegment != this) {
                        notifySegmentChanged(this)
                    }
                }
            }
        }
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (this.bufferedPosition != bufferedPosition) {
            this.bufferedPosition = bufferedPosition
            updateUIValues()
            invalidate()
        }
    }

    override fun setDuration(duration: Long) {
        if (isScrubbing && duration == C.TIME_UNSET) {
            stopScrubbing(true)
        }
        if (this.duration != duration) {
            this.duration = duration
            calculateGapChapters()
            updateUIValues()
            invalidate()
        }
    }

    override fun getPreferredUpdateDelay(): Long {
        val timeBarWidthDp = pxToDp(progressBar.width())
        return if (timeBarWidthDp == 0 || duration <= 0L || duration == C.TIME_UNSET) Long.MAX_VALUE
        else duration / timeBarWidthDp
    }

    //endregion

    //region Drawing canvas and updating UI

    // Small helpers for the correct y-positions on the bar
    private val barTop: Int
        get() = (progressBar.centerY() - progressBar.height().toFloat().div(2)).toInt()

    private val barBottom: Int
        get() = barTop + progressBar.height()

    // Helpers for chapters
    private val topBottomAdd: Int
    private val defaultGapSize: Int

    override fun draw(canvas: Canvas?) {
        if (canvas == null) {
            super.draw(canvas)
        } else {
            canvas.save()
            drawTimeBar(canvas)
            drawScrubber(canvas)
            canvas.restore()
        }
    }

    private fun drawTimeBar(canvas: Canvas) {
        if (duration <= 0) {
            canvas.drawRect(
                seekBounds.left.toFloat(), barTop.toFloat(),
                seekBounds.right.toFloat(), barBottom.toFloat(), unPlayedPaint
            )
            return
        }

        // Render small start part in red
        canvas.drawRect(
            seekBounds.left.toFloat(),
            barTop.toFloat(),
            progressBar.left.toFloat(),
            barBottom.toFloat(),
            playedPaint
        )

        // Render small end part in grey or red
        canvas.drawRect(
            progressBar.right.toFloat(),
            barTop.toFloat(),
            seekBounds.right.toFloat(),
            barBottom.toFloat(),
            if (position >= duration) playedPaint else unPlayedPaint
        )

        if (chapters.isNotEmpty()) drawChapters(canvas)
        else drawNormalTimeBar(canvas)

        for (segment in segments) {
            drawSegment(canvas, segment)
        }
    }

    private data class GapHelper(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val startScreenPosition: Float,
        val endScreenPosition: Float,
        val color: Int = 0
    )

    private var gapChapters: List<GapHelper> = emptyList()

    /**
     * Sets the list of the chapters with their start and end time and corresponding
     * screen locations (x position) with gap awareness.
     *
     * Call this method whenever the chapters change or the bounds of the progressBar (seekBounds).
     */
    private fun calculateGapChapters() {
        if (chapters.isEmpty()) {
            gapChapters = emptyList()
            return
        }

        val result = arrayListOf<GapHelper>()

        for ((index, chapter) in chapters.withIndex()) {
            val startTimeMs = chapter.startTimeMs
            val endTimeMs = when (index) {
                chapters.lastIndex -> duration
                else -> chapters[index + 1].startTimeMs
            }

            when (index) {
                0 -> {
                    result.add(
                        GapHelper(
                            startTimeMs, endTimeMs,
                            seekBounds.left.toFloat(),
                            screenPositionOnProgressBar(endTimeMs).toFloat()
                        )
                    )
                }
                chapters.lastIndex -> {
                    result.add(
                        GapHelper(
                            startTimeMs, duration,
                            (screenPositionOnProgressBar(startTimeMs) + defaultGapSize).toFloat(),
                            seekBounds.right.toFloat()
                        )
                    )
                }
                else -> {
                    result.add(
                        GapHelper(
                            startTimeMs, endTimeMs,
                            (screenPositionOnProgressBar(startTimeMs) + defaultGapSize).toFloat(),
                            screenPositionOnProgressBar(endTimeMs).toFloat()
                        )
                    )
                }
            }
        }
        gapChapters = result
    }

    private fun drawNormalTimeBar(canvas: Canvas) {
        // UnPlayed, from the small red portion to the end // Grey
        canvas.drawRect(
            (if (isScrubbing) seekBounds.left else progressBar.left).toFloat(),
            barTop.toFloat(),
            progressBar.right.toFloat(),
            barBottom.toFloat(),
            unPlayedPaint
        )

        // Buffered // White
        canvas.drawRect(
            (if (isScrubbing) seekBounds.left else progressBar.left).toFloat(),
            barTop.toFloat(),
            bufferedBar.right.toFloat() - if (isScrubbing) initEdgeSize else 0,
            barBottom.toFloat(),
            bufferedPaint
        )

        // Played // Red
        canvas.drawRect(
            (if (isScrubbing) seekBounds.left else progressBar.left).toFloat(),
            barTop.toFloat(),
            positionBar.right.toFloat() - if (isScrubbing) initEdgeSize else 0,
            barBottom.toFloat(),
            playedPaint
        )
    }

    private fun drawSegment(canvas: Canvas, segment: YouTubeSegment) {
        canvas.drawRect(
            screenPositionOnProgressBar(segment.startTimeMs).toFloat(),
            barTop.toFloat(),
            screenPositionOnProgressBar(segment.endTimeMs).toFloat(),
            barBottom.toFloat(),
            Paint().apply { color = segment.color }
        )
    }

    private fun drawChapters(canvas: Canvas) {
        val progressPositionOnBar = if (isScrubbing) screenPositionOnSeekBounds(position)
            else screenPositionOnProgressBar(position)

        val bufferedPositionOnBar = if (isScrubbing) screenPositionOnSeekBounds(bufferedPosition)
            else screenPositionOnProgressBar(bufferedPosition)

        gapChapters.forEach { gapHelper ->
            // Draw fully unplayed
            canvas.drawRect(
                gapHelper.startScreenPosition,
                barTop.toFloat(),
                gapHelper.endScreenPosition,
                barBottom.toFloat(),
                unPlayedPaint
            )

            if (!isScrubbing) {
                // Buffered
                if (bufferedPosition >= gapHelper.startTimeMs) {
                    canvas.drawRect(
                        gapHelper.startScreenPosition,
                        barTop.toFloat(),
                        bufferedPositionOnBar.toFloat()
                            .coerceAtLeast(gapHelper.startScreenPosition)
                            .coerceAtMost(gapHelper.endScreenPosition),
                        barBottom.toFloat(),
                        bufferedPaint
                    )
                }
                // Played
                if (position >= gapHelper.startTimeMs) {
                    canvas.drawRect(
                        gapHelper.startScreenPosition,
                        barTop.toFloat(),
                        progressPositionOnBar.toFloat()
                            .coerceAtMost(gapHelper.endScreenPosition),
                        barBottom.toFloat(),
                        playedPaint
                    )
                }
            } else {
                val thick = scrubPosition in gapHelper.startTimeMs..gapHelper.endTimeMs

                if (scrubPosition in gapHelper.startTimeMs..gapHelper.endTimeMs) {
                    canvas.drawRect(
                        gapHelper.startScreenPosition,
                        (barTop - if (thick) topBottomAdd else 0).toFloat(),
                        gapHelper.endScreenPosition,
                        (barBottom + if (thick) topBottomAdd else 0).toFloat(),
                        bufferedPaint
                    )
                }
                // Played
                if (position >= gapHelper.startTimeMs) {
                    canvas.drawRect(
                        gapHelper.startScreenPosition,
                        (barTop - if (thick) topBottomAdd else 0).toFloat(),
                        progressPositionOnBar.toFloat()
                            .coerceAtMost(gapHelper.endScreenPosition),
                        (barBottom + if (thick) topBottomAdd else 0).toFloat(),
                        playedPaint
                    )
                }
            }
        }
    }

    private fun drawScrubber(canvas: Canvas) {
        if (duration <= 0  || !showScrubber)
            return

        canvas.drawCircle( /* x, y, radius, paint */
            getScreenScrubber().toFloat(),
            scrubberBar.centerY().toFloat(),
            scrubberRadius.toFloat(),
            scrubberCirclePaint
        )
    }

    /**
     * Sets the rect values depending on the current progress of each value for rendering
     */
    private fun updateUIValues() {
        positionBar.set(progressBar)
        bufferedBar.set(progressBar)
        scrubberBar.set(progressBar)

        if (duration > 0) {
            positionBar.right = screenPositionOnProgressBar(position)
            bufferedBar.right = screenPositionOnProgressBar(bufferedPosition)
            scrubberBar.right = getScreenScrubber()
        }
    }

    //endregion

    //region Measurement, onTouch and onLayout

    private val barHeight: Int
    private val initEdgeSize: Int
    private val touchTargetHeight: Int
    private var metricsLeft: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (!isEnabled || duration <= 0 || event == null) {
            return false
        }

        val touchPosition = resolveTouchPosition(event)
        val x = touchPosition.x
        val y = touchPosition.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> if (isInSeekBar(x, y)) {
                startScrubbing(positionOnProgressBarMillis(x))
                updateUIValues()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (isScrubbing) {
                updateScrubbing(positionOnProgressBarMillis(x))
                updateUIValues()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isScrubbing) {
                stopScrubbing(event.action == MotionEvent.ACTION_CANCEL)
                return true
            }
            else -> { /* no-op */ }
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val height = when (heightMode) {
            MeasureSpec.UNSPECIFIED -> touchTargetHeight
            MeasureSpec.EXACTLY -> heightSize
            else -> touchTargetHeight.coerceAtMost(heightSize)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        metricsLeft = left

        val width: Int = right - left
        val height: Int = bottom - top
        val barY: Int = (height - touchTargetHeight) / 2

        val seekLeft: Int = paddingLeft
        val seekRight: Int = width - paddingRight
        val progressY: Int = barY + (touchTargetHeight - barHeight) / 2

        seekBounds.set( /* left, top, right, bottom */
            seekLeft, barY, seekRight, barY + touchTargetHeight
        )

        progressBar.set( /* left, top, right, bottom */
            seekBounds.left + initEdgeSize,
            progressY,
            seekBounds.right - initEdgeSize,
            progressY + barHeight
        )

        calculateGapChapters()
        updateUIValues()

    }

    //endregion

    //region Key handling

    private var keyCountIncrement: Int = DEFAULT_INCREMENT_COUNT
    private var keyTimeIncrement: Long = C.TIME_UNSET

    override fun setKeyTimeIncrement(time: Long) {
        Assertions.checkArgument(time > 0)
        keyCountIncrement = C.INDEX_UNSET
        keyTimeIncrement = time
    }

    override fun setKeyCountIncrement(count: Int) {
        Assertions.checkArgument(count > 0)
        keyCountIncrement = count
        keyTimeIncrement = C.TIME_UNSET
    }

    private fun getPositionIncrement(): Long {
        return if (keyTimeIncrement == C.TIME_UNSET)
            if (duration == C.TIME_UNSET)
                0
            else duration.div(keyCountIncrement)
        else keyTimeIncrement
    }

    private fun scrubIncrementally(positionChange: Long): Boolean {
        if (duration <= 0) {
            return false
        }

        val previousPosition = if (isScrubbing) scrubPosition else position
        val scrubPosition = Util.constrainValue(previousPosition + positionChange, 0, duration)

        if (scrubPosition == previousPosition) {
            return false
        }

        if (!isScrubbing) startScrubbing(scrubPosition)
        else updateScrubbing(scrubPosition)

        updateUIValues()
        invalidate()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // TODO: Requires check, especially because of missing break (is it really fall through?)
        if (isEnabled) {
            // Initially handle it as RIGHT (increment) ...
            var positionIncrement = getPositionIncrement()

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // ... but if it'S LEFT (decrement) inverse the value and fall through
                    positionIncrement = -positionIncrement
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (scrubIncrementally(positionIncrement)) {
                    removeCallbacks(stopScrubbingRunnable)
                    postDelayed(stopScrubbingRunnable, STOP_SCRUBBING_TIMEOUT_MS)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (isScrubbing) {
                    stopScrubbing(true)
                    return true
                }
                else -> { /* no-op */ }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val stopScrubbingRunnable: Runnable = Runnable { stopScrubbing(false) }

    //endregion

    //region View accessibility: Mostly copied from ExoPlayer's DefaultTimeBar
    // TODO: Implement ...

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent?) {
        super.onInitializeAccessibilityEvent(event)
        event?.let { _ ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
                event.text.add(progressText)
            }
            event.className = ACCESSIBILITY_CLASS_NAME
        }
    }

    private val formatBuilder = StringBuilder()
    private val formatter = Formatter(formatBuilder, Locale.getDefault())
    private val progressText: String
        get() = Util.getStringForTime(formatBuilder, formatter, position)

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (isScrubbing && !enabled) {
            stopScrubbing(true)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (isScrubbing && !gainFocus) {
            stopScrubbing(false)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.let { _ ->
            with(info) {
                className = ACCESSIBILITY_CLASS_NAME
                contentDescription = progressText
                if (duration <= 0)
                    return

                if (Util.SDK_INT >= 21) {
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                } else {
                    addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                }
            }
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (super.performAccessibilityAction(action, arguments))
            return true

        if (duration <= 0)
            return false

        if (action ==  AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            if (scrubIncrementally(-getPositionIncrement())) {
                stopScrubbing(false)
            }
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            if (scrubIncrementally(getPositionIncrement())) {
                stopScrubbing(false)
            }
        } else {
            return false
        }

        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    //endregion

    //region Helpers: general

    private val touchPosition: Point = Point()
    private val displayMetrics: DisplayMetrics = context.resources.displayMetrics
    private val density: Float = displayMetrics.density

    private fun resolveTouchPosition(motionEvent: MotionEvent): Point {
        touchPosition.set(motionEvent.x.toInt(), motionEvent.y.toInt())
        return touchPosition
    }

    private fun isInSeekBar(x: Int, y: Int): Boolean {
        return seekBounds.contains(x, y)
    }

    private fun dpToPx(dps: Int): Int {
        return (dps * density + 0.5f).toInt()
    }

    private fun pxToDp(px: Int): Int {
        return (px / density).toInt()
    }

    //endregion

    //region Helpers: Millis position, Rect position transformations

    /**
     * Returns the x position which would be on the progressBar rectangle.
     * Ensures that there is no relative changes which might result because
     * of onLayout/onMeasure (=> scrubberSize). Important for the gaps.
     *
     * @param millis Time in milliseconds
     *
     * @return x position on the screen
     */
    private fun screenPositionOnProgressBar(millis: Long): Int {
        return progressBar.left.plus(progressBar.width().times(millis.toFloat().div(duration))).toInt()
            .coerceAtLeast(progressBar.left)
            .coerceAtMost(progressBar.right)
    }

    /**
     * Returns the x position which would be on the seekBounds rectangle.
     *
     * @param millis Time in milliseconds
     *
     * @return x position on the screen
     */
    private fun screenPositionOnSeekBounds(millis: Long): Int {
        return seekBounds.left.plus(seekBounds.width().times(millis.toFloat().div(duration))).toInt()
            .coerceAtLeast(seekBounds.left)
            .coerceAtMost(seekBounds.right)
    }

    /**
     * Returns the x position of the scrubber circle with minimal offset on the edges.
     */
    private fun getScreenScrubber(): Int {
        return if (isScrubbing) {
            additionalOffsetFromTouch()
                .coerceAtLeast(seekBounds.left + scrubberRadius)
                .coerceAtMost(seekBounds.right - scrubberRadius)
        } else {
            positionBar.right
                .coerceAtLeast(seekBounds.left + scrubberRadius)
                .coerceAtMost(seekBounds.right - scrubberRadius)
        }
    }

    /**
     * Returns the x position on the seekBounds rect relative to the progressBar rect (uses the
     * percentage). Substracts the left additional space between seekBounds.left and progressBar.left.
     */
    private fun additionalOffsetFromTouch(): Int {
        val progressPercentage = touchPosition.x.toFloat().minus(progressBar.left).div(progressBar.width())
        val relPosOnSeekBounds = seekBounds.width().toFloat().times(progressPercentage)
            .coerceAtLeast(0f).toInt()

        return relPosOnSeekBounds.plus(seekBounds.left)
            .coerceAtLeast(seekBounds.left).coerceAtMost(seekBounds.right)
    }

    private val additionDragOffset: Int

    /**
     * Returns the position in millis on the progressBar rect depending on x position but adds a
     * small offset. Ensures, for example, that the finger has not to be at the left edge to reach
     * the position 0. Same applies to the end position.
     *
     * May require some optimization in its implementation.
     */
    private fun positionOnProgressBarMillis(xPosition: Int): Long {
        val newX = xPosition - progressBar.left - additionDragOffset
        val newWidth = progressBar.width() - additionDragOffset.times(2)

        val relativePercent = newX.toFloat() / newWidth
        val relativeXPosition = progressBar.left + relativePercent.times(progressBar.width())

        return relativeXPosition.minus(progressBar.left).div(progressBar.width()).times(
            duration
        )
            .coerceAtLeast(0f)
            .coerceAtMost(duration.toFloat()).toLong()
    }

    /**
     * Returns the scrubber circle size (width) depending on the state (disabled, enabled or
     * focused/dragged)
     */
    private val scrubberRadius: Int
        get() = when {
            isScrubbing || isFocused -> scrubberDraggedSize
            isEnabled -> scrubberEnabledSize
            else -> scrubberDisabledSize
        }.div(2)

    private val scrubberPositionScreen: Int
        get() = metricsLeft + getScreenScrubber()

    //endregion

    init {
        barHeight = dpToPx(DEFAULT_BAR_HEIGHT_DP)
        touchTargetHeight = dpToPx(DEFAULT_TOUCH_TARGET_HEIGHT_DP)
        additionDragOffset = dpToPx(DEFAULT_ADDITIONAL_DRAG_OFFSET_DP)
        initEdgeSize = dpToPx(DEFAULT_INIT_EDGE_DP)

        topBottomAdd = dpToPx(DEFAULT_TOP_BOTTOM_ADD)
        defaultGapSize = dpToPx(DEFAULT_GAP_SIZE)

        scrubberDisabledSize = dpToPx(DEFAULT_SCRUBBER_DISABLED_SIZE_DP)

        //region Init custom attributes

        scrubberEnabledSize = dpToPx(DEFAULT_SCRUBBER_ENABLED_SIZE_DP)
        scrubberDraggedSize = dpToPx(DEFAULT_SCRUBBER_DRAGGED_SIZE_DP)

        playedPaint.color = DEFAULT_PLAYED_COLOR
        scrubberCirclePaint.color = DEFAULT_SCRUBBER_COLOR
        bufferedPaint.color = DEFAULT_BUFFERED_COLOR
        unPlayedPaint.color = DEFAULT_UNPLAYED_COLOR

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.YouTubeTimeBar, 0, 0)

            val unPlayedColor = a.getColor(
                R.styleable.YouTubeTimeBar_yt_unplayed_color,
                DEFAULT_UNPLAYED_COLOR
            )

            val playedColor = a.getColor(
                R.styleable.YouTubeTimeBar_yt_played_color,
                DEFAULT_PLAYED_COLOR
            )

            val bufferedColor = a.getColor(
                R.styleable.YouTubeTimeBar_yt_buffered_color,
                DEFAULT_BUFFERED_COLOR
            )

            val scrubberColor = a.getColor(
                R.styleable.YouTubeTimeBar_yt_scrubber_color,
                DEFAULT_SCRUBBER_COLOR
            )

            val scrubberSizeEnabled = a.getDimensionPixelSize(
                R.styleable.YouTubeTimeBar_yt_scrubber_size_enabled,
                dpToPx(DEFAULT_SCRUBBER_ENABLED_SIZE_DP)
            )

            val scrubberSizeDragged = a.getDimensionPixelSize(
                R.styleable.YouTubeTimeBar_yt_scrubber_size_dragged,
                dpToPx(DEFAULT_SCRUBBER_DRAGGED_SIZE_DP)
            )

            scrubberEnabledSize = scrubberSizeEnabled
            scrubberDraggedSize = scrubberSizeDragged

            playedPaint.color = playedColor
            scrubberCirclePaint.color = scrubberColor
            bufferedPaint.color = bufferedColor
            unPlayedPaint.color = unPlayedColor

            a.recycle()
        }

        //endregion


        // Add scrub listener for segments and chapters
        addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                libStartScrubbing(
                    position
                        .coerceAtLeast(0)
                        .coerceAtMost(max(getDuration(), 0))
                )
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                libUpdateScrubbing(
                    position
                        .coerceAtLeast(0)
                        .coerceAtMost(max(getDuration(), 0))
                )
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                libStopScrubbing()
            }
        })

        // For XML layout preview
        if (isInEditMode) {
            setDuration(TimeUnit.MINUTES.toMillis(100))
            setPosition(TimeUnit.MINUTES.toMillis(15))
            setBufferedPosition(TimeUnit.MINUTES.toMillis(40))
            showScrubber()
            updateUIValues()
            invalidate()
        }
    }

    companion object {
        private const val DEFAULT_PLAYED_COLOR = 0xFFFF3333.toInt()
        private const val DEFAULT_UNPLAYED_COLOR = 0xFF999999.toInt()
        private const val DEFAULT_BUFFERED_COLOR = 0xFFEEEEEE.toInt()
        private const val DEFAULT_SCRUBBER_COLOR = 0xFFFF3333.toInt() // -0x1

        private const val DEFAULT_BAR_HEIGHT_DP = 2 // 4
        private const val DEFAULT_TOUCH_TARGET_HEIGHT_DP = 26
        private const val DEFAULT_SCRUBBER_ENABLED_SIZE_DP = 12
        private const val DEFAULT_SCRUBBER_DRAGGED_SIZE_DP = 24 // 16
        private const val DEFAULT_SCRUBBER_DISABLED_SIZE_DP = 4 // 0?

        private const val DEFAULT_GAP_SIZE = 4
        private const val DEFAULT_TOP_BOTTOM_ADD = 2
        private const val DEFAULT_ADDITIONAL_DRAG_OFFSET_DP = 4
        private const val DEFAULT_INIT_EDGE_DP = 3

        private const val DEFAULT_INCREMENT_COUNT = 20
        private const val STOP_SCRUBBING_TIMEOUT_MS = 1000L
        private const val ACCESSIBILITY_CLASS_NAME = "android.widget.SeekBar"
    }
}