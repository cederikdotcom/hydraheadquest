package com.limelight.hydra

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Shared styling for the Hydra VR panels.
 *
 * The app renders as a flat panel at a distance in the headset, so
 * everything is sized up compared to a phone UI: 20 sp body text as the
 * floor, 64 dp minimum touch targets, and high contrast on a near-black
 * background. One accent color, status colors matching the iPad app
 * (green up, orange attention, red error). All drawables are built in
 * code (GradientDrawable), no resources, no new dependencies.
 */
object HydraUi {

    // ------------------------------------------------------------------
    // Palette (dark, iPad-app parity)
    // ------------------------------------------------------------------

    const val COLOR_BACKGROUND = 0xFF0E0E12.toInt()
    const val COLOR_OVERLAY_SCRIM = 0xF20E0E12.toInt()
    const val COLOR_PANEL = 0xFF16161C.toInt()
    const val COLOR_SURFACE = 0xFF1E1E26.toInt()
    const val COLOR_SURFACE_PRESSED = 0xFF32323E.toInt()
    const val COLOR_STROKE = 0xFF3A3A46.toInt()
    const val COLOR_TEXT = 0xFFFFFFFF.toInt()
    const val COLOR_TEXT_DIM = 0xFFB4B4C0.toInt()
    const val COLOR_TEXT_FAINT = 0xFF7A7A88.toInt()
    const val COLOR_ACCENT = 0xFF5A9CFF.toInt()
    const val COLOR_ACCENT_PRESSED = 0xFF3D7BDB.toInt()
    const val COLOR_GREEN = 0xFF4DDD8C.toInt()
    const val COLOR_ORANGE = 0xFFFFB84D.toInt()
    const val COLOR_RED = 0xFFFF6B6B.toInt()

    // ------------------------------------------------------------------
    // Type scale (sp)
    // ------------------------------------------------------------------

    const val TEXT_STATUS = 32f
    const val TEXT_TITLE = 30f
    const val TEXT_TILE = 26f
    const val TEXT_BUTTON = 22f
    const val TEXT_BODY = 20f
    const val TEXT_CAPTION = 18f

    // ------------------------------------------------------------------
    // Spacing scale and shapes (dp)
    // ------------------------------------------------------------------

    const val SPACE_S = 16
    const val SPACE_M = 24
    const val SPACE_L = 32

    const val RADIUS_CARD = 16
    const val RADIUS_BUTTON = 12
    const val BUTTON_MIN_HEIGHT = 64
    const val BUTTON_MIN_WIDTH = 220

    // ------------------------------------------------------------------
    // Panel scale
    // ------------------------------------------------------------------

    /**
     * Horizon OS presents the app as a flat 2D panel and reports it with
     * densityDpi=0, so dp and sp map near 1:1 to pixels on a surface that
     * is 3664 px wide on Quest 2. A phone-tuned type scale renders tiny
     * there. Scale every dp and sp value by the real panel width: 1x up
     * to 1600 px wide, proportional above that, capped at 3x. On the
     * Quest panel this gives about 2.3x. On a phone it stays 1x.
     */
    @Volatile
    private var cachedScale = 0f
    @Volatile
    private var cachedWidth = 0

    fun scale(context: Context): Float {
        val width = context.resources.displayMetrics.widthPixels
        if (width > 0) {
            // Keyed on the width: the panel can first report a portrait
            // width and re-lay out landscape a moment later, and a scale
            // cached from the first reading would stick forever
            if (width == cachedWidth && cachedScale > 0f) return cachedScale
            val computed = (width / 1600f).coerceIn(1f, 3f)
            cachedWidth = width
            cachedScale = computed
            return computed
        }
        // displayMetrics can lie (0 width) early on Horizon OS. Assume the
        // Quest panel on Meta hardware; do not cache, so a later call with
        // valid metrics recomputes.
        return if (isQuestDevice()) 2f else 1f
    }

    private fun isQuestDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        return manufacturer.contains("oculus") ||
            manufacturer.contains("meta") ||
            model.contains("quest")
    }

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density * scale(context)).toInt()
    }

    /** Panel-scaled sp value. Use for every setTextSize(COMPLEX_UNIT_SP, ...). */
    fun sp(context: Context, value: Float): Float {
        return value * scale(context)
    }

    // ------------------------------------------------------------------
    // Drawables
    // ------------------------------------------------------------------

    /** Rounded filled rectangle, optional stroke. */
    fun card(
        context: Context,
        fill: Int = COLOR_SURFACE,
        radiusDp: Int = RADIUS_CARD,
        stroke: Int? = COLOR_STROKE
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) {
                setStroke(dp(context, 1), stroke)
            }
        }
    }

    /** Rounded card with a subtle pressed state for pointer feedback. */
    fun pressableCard(
        context: Context,
        fill: Int = COLOR_SURFACE,
        pressed: Int = COLOR_SURFACE_PRESSED,
        radiusDp: Int = RADIUS_CARD,
        stroke: Int? = COLOR_STROKE
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                card(context, pressed, radiusDp, stroke)
            )
            addState(intArrayOf(), card(context, fill, radiusDp, stroke))
        }
    }

    // ------------------------------------------------------------------
    // View factories
    // ------------------------------------------------------------------

    fun title(context: Context, label: String): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, TEXT_TITLE))
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun body(context: Context, label: String, color: Int = COLOR_TEXT_DIM): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, TEXT_BODY))
        }
    }

    /**
     * A big rounded button sized for the controller pointer: 64 dp tall,
     * 22 sp label, pressed feedback. Accent fill for the primary action,
     * surface fill for everything else.
     */
    fun bigButton(
        context: Context,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, TEXT_BUTTON))
            background = if (primary) {
                pressableCard(
                    context, COLOR_ACCENT, COLOR_ACCENT_PRESSED,
                    RADIUS_BUTTON, null
                )
            } else {
                pressableCard(
                    context, COLOR_SURFACE, COLOR_SURFACE_PRESSED,
                    RADIUS_BUTTON, COLOR_STROKE
                )
            }
            minHeight = dp(context, BUTTON_MIN_HEIGHT)
            minimumHeight = dp(context, BUTTON_MIN_HEIGHT)
            minWidth = dp(context, BUTTON_MIN_WIDTH)
            minimumWidth = dp(context, BUTTON_MIN_WIDTH)
            setPadding(
                dp(context, SPACE_M), dp(context, SPACE_S),
                dp(context, SPACE_M), dp(context, SPACE_S)
            )
            stateListAnimator = null
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    /** Dark rounded input field with 20 sp text and a readable hint. */
    fun styleField(context: Context, field: EditText) {
        field.setTextColor(COLOR_TEXT)
        field.setHintTextColor(COLOR_TEXT_FAINT)
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, TEXT_BODY))
        field.background = card(context, COLOR_SURFACE, RADIUS_BUTTON, COLOR_STROKE)
        field.setPadding(
            dp(context, SPACE_S), dp(context, SPACE_S),
            dp(context, SPACE_S), dp(context, SPACE_S)
        )
        field.minHeight = dp(context, BUTTON_MIN_HEIGHT)
    }
}
