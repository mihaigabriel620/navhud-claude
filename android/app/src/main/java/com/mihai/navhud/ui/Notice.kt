package com.mihai.navhud.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.mihai.navhud.R

/**
 * A short message, in the app's own clothes.
 *
 * The app used eight `Toast` calls. A Toast is a grey system capsule at the
 * bottom-centre of the screen — which on a landscape-locked driving app is
 * over the trip pill, in the wrong palette, at the wrong end of the screen,
 * and on Android 12+ it cannot be restyled at all because `setView` is a no-op
 * from API 30.
 *
 * This is the same idea drawn as part of the app: a card that slides down from
 * the top, sits for a moment, and leaves. It attaches to the activity's own
 * content view, so it inherits the theme and disappears with the screen.
 */
object Notice {

    private const val SHOW_MS = 2600L
    private const val ACTION_SHOW_MS = 8000L
    private const val FADE_MS = 180L

    /**
     * With [action], the card carries a second, bold line naming it, stays
     * longer, and a tap on it runs [onAction] -- for the messages that need
     * the driver to go and change something, like a permission.
     */
    fun show(activity: Activity, message: CharSequence,
             action: CharSequence? = null, onAction: (() -> Unit)? = null) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // One at a time: a second message replaces the first rather than
        // stacking on top of it.
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

        val text = TextView(activity).apply {
            tag = TAG
            this.text = if (action == null) message else
                android.text.SpannableStringBuilder(message).append("\n").append(
                    action, android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (onAction != null) setOnClickListener {
                (parent as? ViewGroup)?.removeView(this)
                onAction()
            }
            setTextColor(androidx.core.content.ContextCompat.getColor(
                activity, R.color.text_primary))
            textSize = 15f
            setBackgroundResource(R.drawable.bg_notice)
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p + p / 2, p, p + p / 2, p)
            elevation = 28 * resources.displayMetrics.density
            alpha = 0f
            translationY = -20 * resources.displayMetrics.density
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (20 * activity.resources.displayMetrics.density).toInt()
        }
        root.addView(text, lp)

        text.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).withEndAction {
            text.postDelayed({
                text.animate().alpha(0f).translationY(
                    -12 * text.resources.displayMetrics.density
                ).setDuration(FADE_MS).withEndAction {
                    (text.parent as? ViewGroup)?.removeView(text)
                }
            }, if (onAction != null) ACTION_SHOW_MS else SHOW_MS)
        }
    }

    private const val TAG = "navhud-notice"
}
