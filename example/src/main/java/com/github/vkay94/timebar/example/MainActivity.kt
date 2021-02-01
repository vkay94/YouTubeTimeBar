package com.github.vkay94.timebar.example

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.github.vkay94.timebar.LibTimeBar
import com.github.vkay94.timebar.YouTubeChapter
import com.github.vkay94.timebar.YouTubeSegment
import com.github.vkay94.timebar.YouTubeTimeBarPreview
import com.github.vkay94.timebar.example.databinding.ActivityMainBinding
import com.github.vkay94.timebar.example.databinding.ExoPlaybackControlViewYtBinding
import com.github.vkay94.timebar.example.fragments.MainViewModel
import com.github.vkay94.timebar.example.fragments.SectionsPagerAdapter
import com.github.vkay94.timebar.example.fragments.TimeBarAdjustmentsFragment
import com.google.android.exoplayer2.ui.TimeBar
import java.util.concurrent.TimeUnit

class MainActivity : BaseVideoActivity() {

    // View bindings
    private lateinit var binding: ActivityMainBinding
    private lateinit var controlsBinding: ExoPlaybackControlViewYtBinding

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup layout views with View binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        val controls = view.findViewById<ViewGroup>(R.id.exo_controls_root)
        controlsBinding = ExoPlaybackControlViewYtBinding.bind(controls)
        setContentView(view)

        videoPlayer = binding.previewPlayerView

        setSupportActionBar(binding.toolbar)

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        initTimeBar()
        initViewModel()
        startNextVideo()

        val sectionsPagerAdapter = SectionsPagerAdapter(this, supportFragmentManager).apply {
            addFragment(TimeBarAdjustmentsFragment.newInstance())
        }
        binding.viewPager.adapter = sectionsPagerAdapter
        controlsBinding.fullscreenButton.setOnClickListener {
            Toast.makeText(this, "No functionality, just for intersection purposes", Toast.LENGTH_SHORT).show()
        }

        /*
            Manually handling of the progress updates if not used in ExoPlayer's
            controls
         */

        progressListener = object : ProgressUpdateListener {
            override fun onProgressUpdate(
                currentPosition: Long,
                bufferedPosition: Long,
                duration: Long
            ) {
                with(binding.youtubeTimeBar) {
                    setDuration(duration)
                    setBufferedPosition(bufferedPosition)
                    setPosition(currentPosition)
                }
                // Probably set it outside when the timeBar is fully ready
                updateDelay = binding.youtubeTimeBar.preferredUpdateDelay
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
                val cutoutCompat = insets.displayCutout
                binding.youtubeTimeBarPreview.adjustWithDisplayCutout(cutoutCompat)
                return@setOnApplyWindowInsetsListener insets
            }
        }
    }

    private fun initTimeBar() {
        /*
            1.  Show a toast message when a colored segment is changed (either by normal progress
                update or by stopping the drag on the segment)

            2.  Vibrate shortly when the chapter is changed during dragging
         */
        binding.youtubeTimeBar.addSegmentListener(object : LibTimeBar.SegmentListener {
            override fun onSegmentChanged(timeBar: LibTimeBar, newSegment: YouTubeSegment?) {
                if (newSegment is YTSegment) {
                    Toast.makeText(this@MainActivity, "Text = ${newSegment.text}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onChapterChanged(timeBar: LibTimeBar, newChapter: YouTubeChapter, drag: Boolean) {
                if (newChapter is YTChapter) {
                    val vTime = 9L
                    if (drag && viewModel.tbVibrateChapter.value == true) {
                        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(
                                VibrationEffect.createOneShot(
                                    vTime,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                                )
                            )
                        } else {
                            v.vibrate(vTime)
                        }
                    }
                }
            }
        })
        binding.youtubeTimeBar.timeBarPreview(binding.youtubeTimeBarPreview)
        binding.youtubeTimeBarPreview.useTitle(true)
        binding.youtubeTimeBar.chapters = defaultChaptersList
        binding.youtubeTimeBar.segments = defaultSegmentsList

        /*
            1.  Handles the preview loading

            2.  (optional) Lets you react on overlapping: The preview view has bounds. If it intersects with
                other views, for example the current playback TextView, you can hide the TextView
                and show it again when it's not overlapping anymore
         */
        binding.youtubeTimeBarPreview.previewListener(object : YouTubeTimeBarPreview.Listener {
            override fun loadThumbnail(imageView: ImageView, position: Long) {
                Glide.with(this@MainActivity)
                    .load(DataAndUtils.STORYBOARD_URL)
                    .override(SIZE_ORIGINAL, SIZE_ORIGINAL)
                    .transform(GlideThumbnailTransformation(position))
                    .placeholder(android.R.color.black)
                    .into(imageView)
            }

            override fun onPreviewPositionUpdate(viewRect: Rect) {
                val fullscreenButtonRect = controlsBinding.fullscreenButton.boundingBox
                controlsBinding.fullscreenButton.visibility =
                    if (viewRect.intersect(fullscreenButtonRect))
                        View.GONE else View.VISIBLE

                val positionViewRect = controlsBinding.exoPosition.boundingBox
                controlsBinding.exoPosition.visibility = if (viewRect.intersect(positionViewRect))
                    View.GONE else View.VISIBLE
            }
        })
        binding.youtubeTimeBarPreview.durationPerFrame(20 * 1000)

        /*
            1.  This code listener simply ensures that the controls aren't hiding too soon during
                seeking

            2.  The player only changes its playback position when the dragging is stopped
         */
        binding.youtubeTimeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                // Do something
                binding.previewPlayerView.showController()
                binding.previewPlayerView.controllerShowTimeoutMs = 120 * 1000
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                /* Don't seek during moving */
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                player?.seekTo(position)
                // Make your calling this to update the timeBar immediately otherwise
                // there is a small delay because it would wait until onProgressUpdate is called
                timeBar.setPosition(position)
                binding.previewPlayerView.controllerShowTimeoutMs = 2 * 1000
            }
        })

        /*
            Since the timeBar is always visible (in portrait) the scrubber circle is hiding
            when the controls are hidden and (re-)appearing if the controls are shown again
         */
        binding.previewPlayerView.setControllerVisibilityListener {
            if (it == View.VISIBLE) {
                binding.youtubeTimeBar.showScrubber()
            } else {
                binding.youtubeTimeBar.hideScrubber()
            }
        }
    }

    private val defaultChaptersList = arrayListOf(
        YTChapter(
            0,
            "Intro"
        ),
        YTChapter(
            timeToMillis(0, 55),
            "First steps"
        ),
        YTChapter(
            timeToMillis(2, 10),
            "A long chapter title to demonstrate the limited width of the preview and sliding of the image along the text"
        ),
        YTChapter(
            timeToMillis(4, 0),
            "Some other chapter title"
        ),
        YTChapter(
            timeToMillis(5, 0),
            "Somewhere in the middle I think"
        ),
        YTChapter(
            timeToMillis(6, 10),
            "Another long chapter title to demonstrate the limited width of the preview and sliding of the image along the text"
        ),
        YTChapter(
            timeToMillis(8, 50),
            "Conclusion"
        ),
        YTChapter(
            timeToMillis(9, 30),
            "It's nice, right? :)"
        )
    )

    private val defaultSegmentsList = arrayListOf(
        YTSegment(
            0, "Yellow",
            timeToMillis(1, 50),
            timeToMillis(3, 10),
            Color.YELLOW
        ),
        YTSegment(
            1, "Green",
            timeToMillis(6, 50),
            timeToMillis(7, 10),
            Color.GREEN
        ),
        YTSegment(
            2, "Cyan",
            timeToMillis(7, 40),
            timeToMillis(9, 10),
            Color.CYAN
        )
    )

    private fun timeToMillis(minutes: Long, seconds: Long): Long {
        return TimeUnit.MINUTES.toMillis(minutes) + TimeUnit.SECONDS.toMillis(seconds)
    }

    data class YTChapter(
        override val startTimeMs: Long,
        override var title: String?
    ) : YouTubeChapter

    data class YTSegment(
        val id: Int,
        val text: String,
        override val startTimeMs: Long,
        override val endTimeMs: Long,
        override var color: Int
    ) : YouTubeSegment

    private fun initViewModel() {
        viewModel.currentVideoId.observe(this, { index ->
            releasePlayer()
            initializePlayer()
            buildMediaSource(Uri.parse(DataAndUtils.videoList[index]))
            player?.play()
        })
        viewModel.tbShowChapters.observe(this, { isChecked ->
            binding.youtubeTimeBar.chapters = if (isChecked) defaultChaptersList else emptyList()
            binding.youtubeTimeBarPreview.useTitle(isChecked)
        })
        viewModel.tbShowSegments.observe(this, { isChecked ->
            binding.youtubeTimeBar.segments = if (isChecked) defaultSegmentsList else emptyList()
        })
        viewModel.tbUsePreview.observe(this, { isChecked ->
            binding.youtubeTimeBar.timeBarPreview(if (isChecked) binding.youtubeTimeBarPreview else null)
        })
    }

    private fun startNextVideo() {
        with(viewModel.currentVideoId) {
            value = (value?.plus(1) ?: 0).rem(DataAndUtils.videoList.size)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.menu_main_action_github -> {
                openInBrowser(DataAndUtils.GITHUB_LINK)
                true
            }
            R.id.menu_main_action_change_video -> {
                startNextVideo()
                Toast.makeText(
                    this,
                    "Video has changed: ${viewModel.currentVideoId.value?.plus(1)} of ${DataAndUtils.videoList.size}",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
}