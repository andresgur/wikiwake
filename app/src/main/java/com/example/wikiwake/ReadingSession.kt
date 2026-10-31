package com.example.wikiwake

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

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun begin(title: String, url: String, text: String) {
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
        if (!active) return
        active = false
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
