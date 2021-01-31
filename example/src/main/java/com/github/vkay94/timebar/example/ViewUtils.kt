package com.github.vkay94.timebar.example

import android.graphics.Rect
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout

val View.screenLocation
    get(): IntArray {
        val point = IntArray(2)
        getLocationOnScreen(point)
        return point
    }

val View.boundingBox
    get(): Rect {
        val (x, y) = screenLocation
        return Rect(x, y, x + width, y + height)
    }

fun View.updateMargins(left: Int, top: Int, right: Int, bottom: Int) {
    val params = this.layoutParams as ConstraintLayout.LayoutParams
    params.setMargins(left, top, right, bottom)
    this.layoutParams = params
}