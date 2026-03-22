package com.radiodroid.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Inset types for [androidx.activity.enableEdgeToEdge] roots: status/navigation bars **and**
 * [display cutout](https://developer.android.com/develop/ui/views/layout/display-cutout)
 * (notch / punch-hole). OEMs like Samsung Fold cover often need the cutout union; using only
 * [WindowInsetsCompat.Type.systemBars] can leave the toolbar under the camera island.
 */
private val edgeToEdgeInsetTypes: Int =
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

/**
 * Apply padding so this view’s laid-out content stays in the safe region. Call on the
 * activity content root (e.g. binding root) after [setContentView].
 */
fun View.applyEdgeToEdgeInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val inner = insets.getInsets(edgeToEdgeInsetTypes)
        v.updatePadding(inner.left, inner.top, inner.right, inner.bottom)
        insets
    }
}
