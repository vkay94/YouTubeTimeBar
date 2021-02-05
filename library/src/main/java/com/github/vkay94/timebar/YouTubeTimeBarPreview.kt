package com.github.vkay94.timebar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.DisplayCutoutCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.github.vkay94.timebar.utils.boundingBox
import com.github.vkay94.timebar.utils.center
import com.google.android.exoplayer2.util.Util
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class YouTubeTimeBarPreview(context: Context, private val attrs: AttributeSet?) : LinearLayout(context, attrs) {

    interface Listener {
        fun onPreviewPositionUpdate(viewRect: Rect) { /* no-op */ }
        fun loadThumbnail(imageView: ImageView, position: Long)
        fun customTimeText(scrubberPosition: Long): String? {
            return null
        }
    }

    private var rootLayout: LinearLayout
    private var thumbnailImageView: ImageView
    private var titleTextView: TextView
    private var timeTextView: TextView
    private var thumbnailContainer: FrameLayout
    private var textContainer: LinearLayout

    private val formatBuilder: StringBuilder = StringBuilder()
    private val formatter: Formatter = Formatter(formatBuilder, Locale.getDefault())

    private var latestScrub: Int = 0
    private var latestRect: Rect? = null
    private var latestNormedPosition = -1L
    private var positionDiff = TimeUnit.SECONDS.toMillis(10)

    private var listener: Listener? = null

    private var xPositionAdjustmentLeft: Int = 0
    private var xPositionAdjustmentRight: Int = 0

    /**
     * Makes the View notch-aware if the device has display curtouts. If set the preview
     * is "sliding" into the cutout.
     */
    fun adjustWithDisplayCutout(cutout: DisplayCutoutCompat?) = apply {
        xPositionAdjustmentLeft = cutout?.safeInsetLeft ?: 0
        xPositionAdjustmentRight = cutout?.safeInsetRight ?: 0
    }

    /**
     * Makes the View notch-aware if the device has display curtouts. If set the preview
     * is "sliding" into the cutout.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun adjustWithDisplayCutout(cutout: DisplayCutout?) = apply {
        xPositionAdjustmentLeft = cutout?.safeInsetLeft ?: 0
        xPositionAdjustmentRight = cutout?.safeInsetRight ?: 0
    }

    /**
     * Makes the View notch-aware if the device has display curtouts. If set the preview
     * is "sliding" into the cutout.
     */
    fun adjustWithDisplayCutout(pixelsLeft: Int, pixelsRight: Int) = apply {
        xPositionAdjustmentLeft = max(0, pixelsLeft)
        xPositionAdjustmentRight = max(0, pixelsRight)
    }

    /**
     * Sets the time for each frame. This reduces the amount of [Listener.loadThumbnail] calls.
     * The view checks whether the new scrubbed position is within the provided interval and calls
     * the method if it isn't.
     *
     * Default is 10 seconds.
     *
     * @param duration Duration in milliseconds
     */
    fun durationPerFrame(duration: Long) {
        this.positionDiff = duration
    }

    private var useTitle: Boolean = true

    /**
     * Sets whether to use the title or not. The height of the text container (title and time)
     * keeps the same if set to `false`.
     *
     * Passing `true` disables the _preview only_ setting.
     *
     * @param use Use the title
     */
    fun useTitle(use: Boolean) {
        useTitle = use
        if (use) {
            usePreviewOnly(false)
            titleTextView.visibility = View.VISIBLE
        } else {
            titleTextView.visibility = View.GONE
            titleTextView.text = ""
        }
    }

    private var usePreviewOnly: Boolean = false

    /**
     * Sets whether to show the preview image only or not. If set to `true` the space for the
     * texts is reduced, so the overall height is smaller.
     *
     * @param previewOnly Hides the title and time texts if `true` is passed, shows it again if `false` is passed.
     */
    fun usePreviewOnly(previewOnly: Boolean) = apply {
        usePreviewOnly = previewOnly
        if (previewOnly) {
            titleTextView.visibility = View.GONE
            timeTextView.visibility = View.INVISIBLE
            titleTextView.text = ""
            timeTextView.text = ""
            textContainer.minimumHeight = (8 * context.resources.displayMetrics.density + 0.5f).toInt()
        } else {
            if (useTitle)
                titleTextView.visibility = View.VISIBLE
            timeTextView.visibility = View.VISIBLE
            textContainer.minimumHeight = (34 * context.resources.displayMetrics.density + 0.5f).toInt()
        }
    }

    /**
     * Sets the [Listener] for this view.
     *
     * @param listener Listener
     */
    fun previewListener(listener: Listener) = apply {
        this.listener = listener
    }

    /**
     * Animates the view out with fading effect.
     *
     * @param duration Total time in milliseconds to disappear
     */
    fun hide(duration: Long = 300) {
        with(rootLayout) {
            animate().setListener(null).cancel()
            alpha = 1f
            visibility = View.VISIBLE

            animate().let {
                it.duration = duration
                it.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        rootLayout.alpha = 0f
                        rootLayout.visibility = View.GONE
                        this@YouTubeTimeBarPreview.visibility = View.GONE
                    }
                })
                it.interpolator = FastOutSlowInInterpolator()
                it.alpha(0f)
                it.start()
            }
        }
    }

    /**
     * Animates the view in with fading effect.
     *
     * @param duration Total time in milliseconds to appear
     */
    fun show(duration: Long = 300) {
        visibility = View.VISIBLE
        with(rootLayout) {
            animate().setListener(null).cancel()
            alpha = 0f
            visibility = View.VISIBLE

            animate().let {
                it.duration = duration
                it.setListener(object : AnimatorListenerAdapter() {  })
                it.alpha(1f)
                it.interpolator = FastOutSlowInInterpolator()
                it.start()
            }
        }
    }

    /**
     * Sets the title.
     */
    internal fun title(title: String) = apply {
        if (!usePreviewOnly)
            titleTextView.text = title
    }

    /**
     * Sets the time text depending on the provided position in milliseconds. Uses the default
     * ExoPlayer implementation.
     */
    internal fun time(millis: Long) = apply {
        if (listener?.customTimeText(millis) != null) {
            timeTextView.text = listener?.customTimeText(millis) ?: "NULL"
        } else {
            Util.getStringForTime(formatBuilder, formatter, millis).let {
                if (timeTextView.text != it)
                    timeTextView.text = it
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.yt_timebar_preview, this, true)

        rootLayout = findViewById(R.id.root_layout)
        thumbnailImageView = findViewById(R.id.thumbnail)
        titleTextView = findViewById(R.id.chapter_title)
        timeTextView = findViewById(R.id.timestamp)
        thumbnailContainer = findViewById(R.id.thumbnail_container)
        textContainer = findViewById(R.id.text_container)

        if (!isInEditMode) {
            rootLayout.visibility = View.GONE
        }

        initializeAttributes()
    }

    private fun initializeAttributes() {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.YouTubeTimeBarPreview, 0, 0)

            val previewWidth = a.getDimensionPixelSize(
                R.styleable.YouTubeTimeBarPreview_yt_preview_width,
                context.resources.getDimensionPixelSize(R.dimen.yt_timebar_preview_width)
            )

            val previewHeight = a.getDimensionPixelSize(
                R.styleable.YouTubeTimeBarPreview_yt_preview_height,
                context.resources.getDimensionPixelSize(R.dimen.yt_timebar_preview_height)
            )

            val chapterMaxWidth = a.getDimensionPixelSize(
                R.styleable.YouTubeTimeBarPreview_yt_chapter_title_max_width,
                context.resources.getDimensionPixelSize(R.dimen.yt_timebar_chapter_max_width)
            )

            titleTextView.maxWidth = chapterMaxWidth
            thumbnailContainer.layoutParams = LayoutParams(previewWidth, previewHeight)

            a.recycle()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            listener?.onPreviewPositionUpdate(Rect(0,0,0,0))
        }
    }

    internal fun updatePosition(scrubberScreenPosition: Int, playbackPosition: Long) {
        latestScrub = scrubberScreenPosition
        if (isInInterval(playbackPosition)) {
            latestNormedPosition = playbackPosition
            listener?.loadThumbnail(thumbnailImageView, playbackPosition)
        }
    }

    private fun isInInterval(newPosition: Long): Boolean {
        return with(latestNormedPosition) {
            this == -1L || newPosition in this.minus(positionDiff)..this.plus(positionDiff)
        }
    }

    private fun moveFullContainer(pos: Int) {
        val minimumX: Int = min(thumbnailContainer.left, textContainer.left).minus(xPositionAdjustmentLeft)  // left edge
        val maximumX: Int = ((parent as ViewGroup).width - width).plus(xPositionAdjustmentRight) // right edge
        val centerOfScrubber = (pos.toFloat() - center).toInt() // scrubber

        val containerPos = centerOfScrubber
            .coerceAtLeast(minimumX)
            .coerceAtMost(maximumX)

        when (containerPos) {
            minimumX -> moveThumbStart()
            maximumX -> moveThumbEnd()
            else -> resetThumbnailAndTimestampPosition()
        }
        x = containerPos.toFloat()
    }

    private fun moveThumbStart() {
        if (thumbnailContainer.width >= textContainer.width) {
            resetThumbnailAndTimestampPosition()
            return
        }

        val minimum = min(thumbnailContainer.left, textContainer.left).toFloat()
        val thumbPos = (latestScrub - thumbnailContainer.center - paddingLeft).coerceAtLeast(minimum)

        thumbnailContainer.x = thumbPos
        centerTimeTextViewToThumbnailPos()
    }

    private fun moveThumbEnd() {
        if (thumbnailContainer.width >= textContainer.width) {
            resetThumbnailAndTimestampPosition()
            return
        }

        val maximum = min(
            width - thumbnailContainer.center,
            textContainer.right.toFloat() - thumbnailContainer.center
        )

        val scrubRelative = latestScrub.toFloat() - (parent as ViewGroup).width + width - paddingRight
        val thumbPos = min(scrubRelative, maximum)

        thumbnailContainer.x = thumbPos - thumbnailContainer.center
        centerTimeTextViewToThumbnailPos()
    }

    private fun resetThumbnailAndTimestampPosition() {
        thumbnailContainer.x = thumbnailContainer.left.toFloat()
        timeTextView.x = timeTextView.left.toFloat()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        moveFullContainer(latestScrub)
        if (latestRect != boundingBox) {
            // To reduce the callback only notify if the view bounds are changed
            boundingBox.let {
                it.left += paddingLeft
                it.right -= paddingRight
                listener?.onPreviewPositionUpdate(it)
                latestRect = it
            }
        }
    }

    private fun centerTimeTextViewToThumbnailPos() {
        val centerThumbnailContainer = thumbnailContainer.x + thumbnailContainer.center - thumbnailContainer.paddingLeft
        timeTextView.x = centerThumbnailContainer - timeTextView.center // - rootLayout.paddingLeft
    }
}
