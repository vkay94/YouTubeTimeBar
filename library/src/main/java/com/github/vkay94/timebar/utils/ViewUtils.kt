package com.github.vkay94.timebar.utils

import android.graphics.Rect
import android.view.View

internal val View.screenLocation
    get(): IntArray {
        val point = IntArray(2)
        getLocationOnScreen(point)
        return point
    }

internal val View.boundingBox
    get(): Rect {
        val (x, y) = screenLocation
        return Rect(x, y, x + width, y + height)
    }

internal val View.center: Float
    get() = this.width / 2.0f