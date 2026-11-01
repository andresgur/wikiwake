package com.example.wikiwake

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Reader screen: shows the article being spoken and follows the voice.
class AlarmActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    private lateinit var body: TextView
    private lateinit var imageView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var pauseButton: Button
    private lateinit var nextButton: Button
    private lateinit var stopButton: Button
    private lateinit var secondaryRow: LinearLayout
    private lateinit var followButton: Button
    private lateinit var wikiButton: Button

    private val readSpan = ForegroundColorSpan(0xFF9AAEAA.toInt())
    private val currentSpan = BackgroundColorSpan(0xFFCDEBE3.toInt())
    private var shownText: String? = null
    private var following = true
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF3F8F6.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        header.addView(imageView, LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(16) })
        titleView = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFF12312E.toInt())
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(0xFF0F766E.toInt())
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })

        body = TextView(this).apply {
            textSize = 19f
            setLineSpacing(0f, 1.25f)
            setTextColor(0xFF12312E.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(120))
        }
        scroll = ScrollView(this).apply {
            addView(body)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE && following) {
                    following = false
                    updateButtons()
                }
                false
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF3F8F6.toInt())
        }
        val params = {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = pill("Pause", primary = false, icon = R.drawable.ic_action_pause) {
            sendToService(AlarmPlaybackService.ACTION_TOGGLE_PAUSE)
        }
        nextButton = pill("Next", primary = false, icon = R.drawable.ic_action_next) {
            sendToService(AlarmPlaybackService.ACTION_NEXT)
        }
        stopButton = pill("Stop", primary = true, icon = R.drawable.ic_action_stop) {
            sendToService(AlarmPlaybackService.ACTION_STOP)
            finish()
        }
        controls.addView(pauseButton, params())
        controls.addView(nextButton, params())
        controls.addView(stopButton, params())
        bar.addView(controls)

        secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        followButton = pill("Follow", primary = false) {
            following = true
            updateButtons()
            scrollToCurrent()
        }
        wikiButton = pill("Wikipedia", primary = false, icon = R.drawable.ic_action_open) {
            if (ReadingSession.url.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReadingSession.url)))
            }
        }
        secondaryRow.addView(followButton, params())
        secondaryRow.addView(wikiButton, params())
        bar.addView(secondaryRow)
        root.addView(bar)

        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ReadingSession.addListener(listener)
        refresh()
    }

    override fun onStop() {
        ReadingSession.removeListener(listener)
        super.onStop()
    }

    private fun refresh() {
        val text = ReadingSession.text
        if (text.isEmpty()) {
            shownText = null
            titleView.text = "Wiki Wake"
            imageView.visibility = View.GONE
            body.text = if (ReadingSession.loading) "Finding today's Wikipedia article…"
            else "No article is being read right now."
            updateButtons()
            return
        }
        if (shownText != text) {
            shownText = text
            titleView.text = ReadingSession.title
            imageView.setImageBitmap(ReadingSession.image)
            imageView.visibility = if (ReadingSession.image != null) View.VISIBLE else View.GONE
            body.setText(SpannableString(text), TextView.BufferType.SPANNABLE)
            following = true
            scroll.scrollTo(0, 0)
        }
        val spannable = body.text as Spannable
        val start = ReadingSession.start
        val sentenceStart = sentenceStart(text, start)
        val sentenceEnd = sentenceEnd(text, ReadingSession.end.coerceAtLeast(start))
        spannable.setSpan(readSpan, 0, sentenceStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (ReadingSession.active) {
            spannable.setSpan(currentSpan, sentenceStart, sentenceEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            spannable.removeSpan(currentSpan)
        }
        updateButtons()
        if (following) scrollToCurrent()
    }

    private fun sentenceStart(text: String, position: Int): Int {
        if (position <= 0) return 0
        val index = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), position - 1)
        var start = if (index < 0) 0 else index + 1
        while (start < position && text[start].isWhitespace()) start++
        return start
    }

    private fun sentenceEnd(text: String, position: Int): Int {
        val index = text.indexOfAny(charArrayOf('.', '!', '?', '\n'), position)
        return if (index < 0) text.length else index + 1
    }

    private fun scrollToCurrent() {
        val layout = body.layout ?: return
        if (shownText == null) return
        val line = layout.getLineForOffset(ReadingSession.start.coerceAtMost(body.text.length))
        val target = body.top + layout.getLineTop(line) - scroll.height / 3
        scroll.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    private fun updateButtons() {
        val active = ReadingSession.active
        progress.visibility = if (ReadingSession.loading) View.VISIBLE else View.GONE
        pauseButton.isEnabled = active
        nextButton.isEnabled = active || ReadingSession.loading
        pauseButton.text = if (ReadingSession.paused) "Resume" else "Pause"
        pauseButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (ReadingSession.paused) R.drawable.ic_action_play else R.drawable.ic_action_pause, 0, 0, 0
        )
        stopButton.text = if (active || ReadingSession.loading) "Stop" else "Close"
        followButton.visibility = if (active && !following) View.VISIBLE else View.GONE
        wikiButton.visibility = if (ReadingSession.url.isNotEmpty()) View.VISIBLE else View.GONE
        secondaryRow.visibility =
            if (followButton.visibility == View.VISIBLE || wikiButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun sendToService(action: String) {
        startService(Intent(this, AlarmPlaybackService::class.java).setAction(action))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pill(label: String, primary: Boolean, icon: Int = 0, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            minHeight = dp(48)
            val accent = 0xFF0F766E.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                if (primary) {
                    setColor(accent)
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), 0xFFCFE0DB.toInt())
                }
            }
            setTextColor(if (primary) Color.WHITE else accent)
            if (icon != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = dp(6)
            }
            compoundDrawableTintList = ColorStateList.valueOf(if (primary) Color.WHITE else accent)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
        }
}
