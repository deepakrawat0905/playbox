package com.playboxpro.tv.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.playboxpro.tv.R
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Phones and small tablets.
 *
 * Every screen is laid out for a TV: about 960 x 540dp in landscape. On
 * any device the whole interface is scaled until that canvas fits the
 * screen, so every page, dialog and player keeps its proportions - a
 * phone, a tablet, a 720p box or a 4K TV all show the same layout.
 */
object ScreenFit {
    private const val DESIGN_WIDTH_DP = 960f
    private const val DESIGN_HEIGHT_DP = 540f

    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** The context an activity should be built on; wrap it in attachBaseContext. */
    fun wrap(base: Context): Context {
        val dm = base.resources.displayMetrics
        val longPx = max(dm.widthPixels, dm.heightPixels).toFloat()
        val shortPx = min(dm.widthPixels, dm.heightPixels).toFloat()
        if (longPx <= 0f || shortPx <= 0f) return base

        // Density at which the TV canvas just fits a landscape screen.
        val targetDpi = (min(longPx / DESIGN_WIDTH_DP, shortPx / DESIGN_HEIGHT_DP) * 160f).toInt()
        val current = base.resources.configuration.densityDpi
        val cfg = Configuration(base.resources.configuration)
        // A phone set to large text would push titles out of their space;
        // the app keeps its own text size here.
        val bigFont = cfg.fontScale > 1f
        if (bigFont) cfg.fontScale = 1f
        // Every device - phone, tablet or TV at any resolution and density -
        // gets the layout at the same proportions: scaled down where the
        // screen is smaller than the design, up where it is larger.
        val needsScale = targetDpi > 0 && kotlin.math.abs(targetDpi - current) > 2
        if (needsScale) cfg.densityDpi = targetDpi
        else if (!bigFont) return base
        return base.createConfigurationContext(cfg)
    }

    /**
     * Full screen on phones: status and navigation bars stay hidden and
     * come back for a moment with a swipe from the edge.
     */
    fun immersive(activity: Activity) {
        if (isTv(activity)) return
        immersive(activity.window)
        keepClearOfCutout(activity)
    }

    /**
     * Newer phones let the app draw under the camera cut-out; in landscape
     * that hides the left edge of every page. The page is padded clear of it.
     */
    private fun keepClearOfCutout(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        if (content.getTag(R.id.cutoutPadded) == true) return
        content.setTag(R.id.cutoutPadded, true)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content)
    }

    /** The same for a dialog's own window, which otherwise brings the bars back. */
    fun immersive(window: android.view.Window?) {
        if (window == null || isTv(window.context)) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * On a touch screen the "Menu" label at the top of a page is the way
     * into the side menu (on a TV that is LEFT on the remote). It stays out
     * of the remote's focus order, so TV navigation is unchanged.
     */
    fun makeMenuLabelTappable(root: View, openMenu: () -> Unit) {
        if (root is TextView && root.text?.toString() == "Menu") {
            val target = (root.parent as? ViewGroup)?.takeIf { it.childCount <= 3 } ?: root
            target.setOnClickListener { openMenu() }
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) makeMenuLabelTappable(root.getChildAt(i), openMenu)
        }
    }
}
