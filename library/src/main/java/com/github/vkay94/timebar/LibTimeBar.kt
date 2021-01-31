package com.github.vkay94.timebar

import com.google.android.exoplayer2.ui.TimeBar

/**
 * Interface for time bar views that can display a playback position, buffered position, and
 * duration, and that have a listener for scrubbing (seeking) events.
 *
 * It is based on [ExoPlayer's official TimeBar][com.google.android.exoplayer2.ui.TimeBar]
 * but it also removes ad-related methods and adds segments/chapters.
 *
 */
interface LibTimeBar : TimeBar {
    /**
     * Adds a listener for segment events.
     *
     * @param listener The listener to add.
     */
    fun addSegmentListener(listener: SegmentListener)

    /**
     * Removes a listener for segment events.
     *
     * @param listener The listener to remove.
     */
    fun removeSegmentListener(listener: SegmentListener)

    /**
     * Listener for segment and chapter events.
     */
    interface SegmentListener {
        /**
         * Called when the segment has been changed during scrubbing events or progress updates.
         *
         * @param timeBar The time bar.
         * @param newSegment The new segment.
         */
        fun onSegmentChanged(timeBar: LibTimeBar, newSegment: YouTubeSegment?)

        /**
         * Called when the chapter has been changed on progress update.
         *
         * @param timeBar The time bar.
         * @param newChapter The new chapter.
         * @param drag Whether the change was during dragging/scrubbing or not
         */
        fun onChapterChanged(timeBar: LibTimeBar, newChapter: YouTubeChapter, drag: Boolean)
    }

    /**
     * Returns the current position in milliseconds or -1 if there is no valid value.
     **/
    fun getPosition(): Long

    /**
     * Returns the buffered position in milliseconds or -1 if there is no valid value.
     **/
    fun getBufferedPosition(): Long

    /**
     * Returns the duration in milliseconds or -1 if there is no valid value.
     **/
    fun getDuration(): Long

    // Override ads-related methods here with empty bodies, so they don't have to be implemented
    // in the actual TimeBar-view.

    override fun setAdGroupTimesMs(
        adGroupTimesMs: LongArray?,
        playedAdGroups: BooleanArray?,
        adGroupCount: Int
    ) {
        /* Ignore this */
    }
}