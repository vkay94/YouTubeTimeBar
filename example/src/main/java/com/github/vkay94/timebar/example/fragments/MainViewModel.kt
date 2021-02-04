package com.github.vkay94.timebar.example.fragments

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    val currentVideoId = MutableLiveData<Int>()

    val tbShowSegments = MutableLiveData<Boolean>()
    val tbShowChapters = MutableLiveData<Boolean>()
    val tbUsePreview = MutableLiveData<Boolean>()
    val tbVibrateChapter = MutableLiveData<Boolean>()
    val tbUsePreviewOnly = MutableLiveData<Boolean>()

    init {
        tbShowChapters.value = true
        tbShowSegments.value = true
        tbUsePreview.value = true
        tbVibrateChapter.value = false
        tbUsePreviewOnly.value = false
    }
}