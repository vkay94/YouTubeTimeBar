package com.github.vkay94.timebar.example

import android.app.Activity
import android.content.Intent
import android.net.Uri

object DataAndUtils {

    /**
     * This is a selected list of sample videos for demonstration.
     *
     * Source: [Github Gist](https://gist.github.com/jsturgis/3b19447b304616f18657)
     */
    val videoList = listOf(
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
    )

    const val GITHUB_LINK = "https://github.com/vkay94/YouTubeTimeBar"

    const val STORYBOARD_URL = "https://bitdash-a.akamaihd.net/content/MI201109210084_1/thumbnails/" +
            "f08e80da-bf1d-4e3d-8899-f0f6155f6efa.jpg"
}

fun Activity.openInBrowser(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}