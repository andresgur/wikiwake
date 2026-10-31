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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Reader screen: shows the article being spoken and follows the voice.
class AlarmActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    private lateinit var body: TextView
    private lateinit var stopButton: Button
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
        titleView = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFF12312E.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.addView(titleView)

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
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF3F8F6.toInt())
        }
        stopButton = pill("Stop reading", primary = true) {
            if (ReadingSession.active) {
                startService(
                    Intent(this, AlarmPlaybackService::class.java).setAction(AlarmPlaybackService.ACTION_STOP)
                )
            }
            finish()
        }
        followButton = pill("Follow", primary = false) {
            following = true
            updateButtons()
            scrollToCurrent()
        }
        wikiButton = pill("Wikipedia", primary = false) {
            if (ReadingSession.url.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReadingSession.url)))
            }
        }
        val params = {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        bar.addView(stopButton, params())
        bar.addView(followButton, params())
        bar.addView(wikiButton, params())
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
            titleView.text = "Wiki Wake"
            body.text = "Finding today's Wikipedia article…"
            updateButtons()
            return
        }
        if (shownText != text) {
            shownText = text
            titleView.text = ReadingSession.title
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
        stopButton.text = if (ReadingSession.active) "Stop reading" else "Close"
        followButton.visibility = if (ReadingSession.active && !following) View.VISIBLE else View.GONE
        wikiButton.visibility = if (ReadingSession.url.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pill(label: String, primary: Boolean, action: () -> Unit): Button =
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
            setOnClickListener { action() }
        }
}
