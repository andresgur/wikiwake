package com.example.wikiwake

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

// Shared between the playback service and the reader screen. Main thread only.
object ReadingSession {
    var title: String = ""
        private set
    var url: String = ""
        private set
    var text: String = ""
        private set
    var start = 0
        private set
    var end = 0
        private set
    var active = false
        private set
    // True from the moment a read is requested until the article text arrives.
    var loading = false
        private set
    var paused = false
        private set
    var image: Bitmap? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    // A read was requested; the article is still being fetched.
    fun prepare() {
        title = ""
        url = ""
        text = ""
        start = 0
        end = 0
        image = null
        paused = false
        active = false
        loading = true
        notifyChanged()
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        notifyChanged()
    }

    fun begin(title: String, url: String, text: String, image: Bitmap? = null) {
        this.image = image
        paused = false
        loading = false
        this.title = title
        this.url = url
        this.text = text
        start = 0
        end = 0
        active = true
        notifyChanged()
    }

    fun update(start: Int, end: Int) {
        if (!active) return
        this.start = start.coerceIn(0, text.length)
        this.end = end.coerceIn(this.start, text.length)
        notifyChanged()
    }

    fun finish() {
        if (!active && !loading) return
        active = false
        loading = false
        paused = false
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun postUpdate(start: Int, end: Int) = handler.post { update(start, end) }

    private fun notifyChanged() = listeners.toList().forEach { it() }
}
