package com.github.vkay94.timebar

interface YouTubeSegment {
    val startTimeMs: Long
    val endTimeMs: Long
    var color: Int
}

interface YouTubeChapter {
    val startTimeMs: Long
    var title: String?
}