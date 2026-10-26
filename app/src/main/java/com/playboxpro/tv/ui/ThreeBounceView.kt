package com.playboxpro.tv.ui

import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.playboxpro.tv.R
import kotlin.math.min

/**
 * The three-dot loader STB+ shows under its splash logo.
 *
 * STB+ uses SpinKit's ThreeBounce (SpinKitView.ThreeBounce, tinted
 * @color/light_gray). This reproduces it without pulling in the library:
 * three circles laid out across the view, each scaling 0 -> 1 -> 0 over a
 * 1400ms cycle, staggered 160ms apart - the exact keyframes SpinKit uses.
 */
class ThreeBounceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val DOT_COUNT = 3
        const val CYCLE_MS = 1400L
        const val STAGGER_MS = 160L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.light_gray)
    }

    /** Current scale of each dot, driven by its own animator. */
    private val scales = FloatArray(DOT_COUNT)
    private val animators = ArrayList<ValueAnimator>(DOT_COUNT)

    fun setDotColor(color: Int) {
        paint.color = color
        invalidate()
    }

    private fun animatorFor(index: Int): ValueAnimator {
        // SpinKit's Bounce sprite: scale 0 -> 1 -> 0 at 0 / 0.4 / 0.8, then
        // held at 0 for the remainder so the dots rest between bounces.
        val frames = arrayOf(
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(0.4f, 1f),
            Keyframe.ofFloat(0.8f, 0f),
            Keyframe.ofFloat(1f, 0f)
        )
        frames.forEach { it.interpolator = AccelerateDecelerateInterpolator() }

        val values = PropertyValuesHolder.ofKeyframe("scale", *frames)
        return ValueAnimator.ofPropertyValuesHolder(values).apply {
            duration = CYCLE_MS
            startDelay = STAGGER_MS * (index + 1)
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                scales[index] = it.getAnimatedValue("scale") as Float
                invalidate()
            }
        }
    }

    private fun start() {
        if (animators.isNotEmpty()) return
        for (i in 0 until DOT_COUNT) {
            animatorFor(i).also { animators.add(it); it.start() }
        }
    }

    private fun stop() {
        animators.forEach { it.cancel() }
        animators.clear()
        scales.fill(0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        val cell = width / DOT_COUNT.toFloat()
        // A dot fills its cell, so the row reads as three touching circles at
        // full scale - the same proportions SpinKit lays out.
        val radius = min(cell, height.toFloat()) / 2f
        val cy = height / 2f

        for (i in 0 until DOT_COUNT) {
            val scale = scales[i]
            if (scale <= 0f) continue
            canvas.drawCircle(cell * i + cell / 2f, cy, radius * scale, paint)
        }
    }
}
