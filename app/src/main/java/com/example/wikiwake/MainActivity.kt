package com.example.wikiwake

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var alarmList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var settingsSummary: TextView
    private lateinit var fullScreenButton: Button
    private var pendingAlarmId = -1
    private var notificationsAsked = false
    private var readAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        buildScreen()
        renderAlarms()
    }

    override fun onResume() {
        super.onResume()
        if (!::preferences.isInitialized) return
        val pending = pendingAlarmId
        pendingAlarmId = -1
        if (pending != -1 && AlarmScheduler.canScheduleExact(this)) {
            AlarmStore.get(this, pending)?.takeIf { it.enabled }?.let { activate(it) }
        } else {
            try {
                AlarmScheduler.scheduleAll(this)
            } catch (error: SecurityException) {
                // Reported in the status line.
            }
        }
        renderAlarms()
    }

    private fun buildScreen() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(PAPER)
        }
        val scroll = ScrollView(this).apply {
            addView(card)
            setFillViewport(true)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.logo)
                contentDescription = "Wiki Wake"
            },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
        )
        header.addView(
            label("WIKI WAKE", 13f, ACCENT).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.12f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(INK)
            contentDescription = "Settings"
            val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attrs.getDrawable(0)
            attrs.recycle()
            setOnClickListener { showSettingsMenu(this) }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(header)
        val headline = SpannableString("Wake up wiser.").apply {
            setSpan(ForegroundColorSpan(CORAL), 8, 14, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        card.addView(label("", 38f, INK, top = 10).apply {
            text = headline
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        card.addView(label("A Wikipedia article, read out loud, instead of a beep.", 17f, 0xFF4D6360.toInt(), top = 6))

        card.addView(label("ALARMS", 12f, ACCENT, top = 28).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
        })
        alarmList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(alarmList, matchWidth(top = 8))
        card.addView(button("+  Add alarm", primary = true) { showAlarmEditor(null) }, matchWidth(top = 16))

        settingsSummary = label("", 14f, 0xFF4D6360.toInt(), top = 18)
        card.addView(settingsSummary)
        statusText = label("", 15f, INK, top = 12)
        card.addView(statusText)

        card.addView(button("Read one now", primary = false) { readNow() }, matchWidth(top = 18))
        fullScreenButton = button("Enable lock-screen alarm screen", primary = false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        card.addView(fullScreenButton, matchWidth(top = 8))

        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        updateSettingsSummary()
    }

    private fun renderAlarms() {
        if (!::alarmList.isInitialized) return
        alarmList.removeAllViews()
        val alarms = AlarmStore.all(this)
        if (alarms.isEmpty()) {
            alarmList.addView(label("No alarms yet. Add one and wake up a little smarter.", 16f, 0xFF4D6360.toInt(), top = 6))
        }
        alarms.forEachIndexed { index, alarm -> alarmList.addView(alarmRow(alarm, index), matchWidth(top = 12)) }
        updateStatus()
    }

    private fun alarmRow(alarm: WakeAlarm, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (alarm.enabled) CARD_COLORS[index % CARD_COLORS.size] else 0xFFFFFFFF.toInt())
            }
            elevation = dp(if (alarm.enabled) 2 else 1).toFloat()
            setOnClickListener { showAlarmEditor(alarm) }
            setOnLongClickListener {
                confirmDelete(alarm)
                true
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dim = if (alarm.enabled) INK else 0xFF9AAEAA.toInt()
        texts.addView(label(formatTime(alarm.hour, alarm.minute), 34f, dim).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(label(alarm.repeatText(), 15f, 0xFF4D6360.toInt()))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = alarm.enabled
            setOnCheckedChangeListener { _, checked -> setEnabled(alarm, checked) }
        })
        return row
    }

    private fun confirmDelete(alarm: WakeAlarm) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(14), dp(24), dp(24))
            background = GradientDrawable().apply {
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(0xFFFFFFFF.toInt())
            }
        }
        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0xFFCFE0DB.toInt()) }
        }, LinearLayout.LayoutParams(dp(40), dp(5)))
        sheet.addView(label(formatTime(alarm.hour, alarm.minute), 44f, INK, top = 18).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        sheet.addView(label(alarm.repeatText(), 16f, 0xFF4D6360.toInt()))
        sheet.addView(label("Delete this alarm?", 18f, INK, top = 18))
        val remove = button("Delete alarm", primary = true) {
            AlarmScheduler.cancel(this, alarm.id)
            AlarmStore.remove(this, alarm.id)
            dialog.dismiss()
            renderAlarms()
        }
        remove.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xFFE5484D.toInt()) },
            null
        )
        sheet.addView(remove, matchWidth(top = 18))
        sheet.addView(button("Cancel", primary = false) { dialog.dismiss() }, matchWidth(top = 8))
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
        }
        dialog.show()
    }

    private fun setEnabled(alarm: WakeAlarm, enabled: Boolean) {
        val updated = alarm.copy(enabled = enabled)
        AlarmStore.put(this, updated)
        if (enabled) activate(updated) else AlarmScheduler.cancel(this, alarm.id)
        renderAlarms()
    }

    private fun showAlarmEditor(existing: WakeAlarm?) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val now = ZonedDateTime.now()
        val picker = TimePicker(this, null, 0, R.style.SpinnerTimePicker).apply {
            setIs24HourView(android.text.format.DateFormat.is24HourFormat(this@MainActivity))
            hour = existing?.hour ?: now.hour
            minute = existing?.minute ?: now.minute
        }
        content.addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content.addView(label("REPEAT", 12f, 0xFF0F766E.toInt(), top = 12))
        var days = existing?.days ?: WakeAlarm.ALL_DAYS
        val hint = label("", 13f, 0xFF5E7370.toInt(), top = 8)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun refreshHint() {
            hint.text = if (days == 0) "No days selected: rings once" else WakeAlarm(0, 0, 0, days, true).repeatText()
        }
        DayOfWeek.values().forEach { day ->
            val bit = 1 shl (day.value - 1)
            val chip = TextView(this).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 14f
            }
            fun style() {
                val on = days and bit != 0
                chip.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) ACCENT else 0xFFDDEBE7.toInt())
                }
                chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF4A5F5C.toInt())
            }
            style()
            chip.setOnClickListener {
                days = days xor bit
                style()
                refreshHint()
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        refreshHint()
        content.addView(chips)
        content.addView(hint)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New alarm" else "Edit alarm")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val alarm = WakeAlarm(
                    existing?.id ?: AlarmStore.nextId(this),
                    picker.hour,
                    picker.minute,
                    days,
                    true
                )
                AlarmStore.put(this, alarm)
                activate(alarm)
                renderAlarms()
            }
        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                AlarmScheduler.cancel(this, existing.id)
                AlarmStore.remove(this, existing.id)
                renderAlarms()
            }
        }
        builder.show()
    }

    // Asks for the permissions an alarm needs, then schedules it and confirms with a toast.
    private fun activate(alarm: WakeAlarm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsAsked = true
            pendingAlarmId = alarm.id
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        if (!AlarmScheduler.canScheduleExact(this)) {
            pendingAlarmId = alarm.id
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
            return
        }
        try {
            AlarmScheduler.schedule(this, alarm)
            Toast.makeText(this, "Alarm set for ${timeUntil(alarm.nextTrigger())} from now", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            statusText.text = error.message
        }
        updateStatus()
    }

    private fun timeUntil(target: ZonedDateTime): String {
        val minutes = Duration.between(ZonedDateTime.now(), target).toMinutes() + 1
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts += "$days ${if (days == 1L) "day" else "days"}"
        if (hours > 0) parts += "$hours ${if (hours == 1L) "hour" else "hours"}"
        if (mins > 0 && days == 0L) parts += "$mins ${if (mins == 1L) "minute" else "minutes"}"
        return parts.joinToString(" ").ifEmpty { "less than 1 minute" }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val time = java.time.LocalTime.of(hour, minute)
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        updateFullScreenButton()
        val active = AlarmStore.all(this).filter { it.enabled }
        statusText.text = when {
            active.isEmpty() -> "All alarms are off"
            !AlarmScheduler.canScheduleExact(this) -> "Allow exact alarms in Settings to activate your alarms."
            else -> {
                val next = active.minOf { it.nextTrigger() }
                "Next alarm: ${next.format(DateTimeFormatter.ofPattern("EEE, HH:mm", Locale.getDefault()))} · in ${timeUntil(next)}"
            }
        }
    }

    private fun readNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            readAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startReading()
    }

    private fun startReading() {
        // Show the reader straight away; it displays a loading state until the article arrives.
        ReadingSession.prepare()
        startActivity(Intent(this, AlarmActivity::class.java))
        val serviceIntent = Intent(this, AlarmPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this,
                "Notifications are off. Spoken alarms still work, but the lock-screen alarm may not appear.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (readAfterPermission) {
            readAfterPermission = false
            startReading()
        }
        val pending = pendingAlarmId
        pendingAlarmId = -1
        if (pending != -1) AlarmStore.get(this, pending)?.takeIf { it.enabled }?.let { activate(it) }
        renderAlarms()
    }

    private fun showSettingsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Settings").setOnMenuItemClickListener {
                showSettings()
                true
            }
            menu.add("Suggest a feature").setOnMenuItemClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andresgur/wikiwake/issues")))
                true
            }
            show()
        }
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        content.addView(label("ARTICLE LANGUAGE", 12f, 0xFF0F766E.toInt(), top = 12))
        val savedLanguage = preferences.getString(LANGUAGE, "").orEmpty()
        val codes = (listOf("") + LANGUAGE_CODES).let { if (savedLanguage !in it) it + savedLanguage else it }
        val names = codes.map { code ->
            if (code.isEmpty()) {
                "Phone language (${Locale.getDefault().displayLanguage})"
            } else {
                val locale = Locale.forLanguageTag(code)
                val native = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
                val english = locale.getDisplayLanguage(Locale.ENGLISH)
                if (native.equals(english, true) || english == code) native.ifBlank { code } else "$native · $english"
            }
        }
        val languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(codes.indexOf(savedLanguage).coerceAtLeast(0))
        }
        content.addView(languageSpinner, matchWidth())

        content.addView(label("TOPICS (OPTIONAL)", 12f, 0xFF0F766E.toInt(), top = 14))
        val topicsInput = editField(
            hint = "Comma-separated · e.g. astronomy, history",
            value = preferences.getString(TOPICS, "").orEmpty()
        ).apply {
            minLines = 2
            gravity = Gravity.TOP
        }
        content.addView(topicsInput, matchWidth())

        content.addView(label("RANDOMNESS", 12f, 0xFF0F766E.toInt(), top = 14))
        val randomnessHint = label("", 13f, 0xFF5E7370.toInt(), top = 4)
        val randomnessBar = android.widget.SeekBar(this).apply {
            max = 100
            progress = preferences.getInt(RANDOMNESS, 0)
        }
        fun describe(value: Int) {
            randomnessHint.text = when {
                value == 0 -> "Always from your topics"
                value == 100 -> "Fully random, topics ignored"
                else -> "$value% chance of a random article, otherwise from your topics"
            }
        }
        describe(randomnessBar.progress)
        randomnessBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) = describe(value)
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
        })
        content.addView(randomnessBar, matchWidth(top = 6))
        content.addView(randomnessHint)

        content.addView(label("POPULARITY", 12f, 0xFF0F766E.toInt(), top = 14))
        val popularityHint = label("", 13f, 0xFF5E7370.toInt(), top = 4)
        val popularityBar = android.widget.SeekBar(this).apply {
            max = 100
            progress = preferences.getInt(POPULARITY, 0)
        }
        fun describePopularity(value: Int) {
            popularityHint.text = when (value) {
                0 -> "Any article, popular or not"
                100 -> "Only popular articles"
                else -> "$value% chance of a popular article, otherwise any"
            }
        }
        describePopularity(popularityBar.progress)
        popularityBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) = describePopularity(value)
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
        })
        content.addView(popularityBar, matchWidth(top = 6))
        content.addView(popularityHint)

        content.addView(label("MAXIMUM READING TIME", 12f, 0xFF0F766E.toInt(), top = 14))
        val lengths = listOf("No limit", "2 minutes", "5 minutes", "10 minutes")
        val lengthValues = listOf(0, 2, 5, 10)
        val lengthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                lengths
            )
            setSelection(lengthValues.indexOf(preferences.getInt(MAX_MINUTES, 0)).coerceAtLeast(0))
        }
        content.addView(lengthSpinner, matchWidth())
        content.addView(
            label("No limit is the default. You can stop an article at any time.", 13f, 0xFF5E7370.toInt(), top = 6)
        )

        content.addView(label("VOICE", 12f, 0xFF0F766E.toInt(), top = 14))
        var preview: android.speech.tts.TextToSpeech? = null
        var voices: List<android.speech.tts.Voice> = emptyList()
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Default voice"))
        }
        content.addView(voiceSpinner, matchWidth())

        var speedBar: android.widget.SeekBar? = null
        var pitchBar: android.widget.SeekBar? = null
        var userPickedVoice = false

        fun speakSample() {
            val engine = preview ?: return
            val locale = Locale.forLanguageTag(codes[languageSpinner.selectedItemPosition].ifEmpty { Locale.getDefault().language })
            engine.language = locale
            voices.getOrNull(voiceSpinner.selectedItemPosition - 1)?.let { engine.voice = it }
            engine.setSpeechRate(((speedBar?.progress ?: 50) + 50) / 100f)
            engine.setPitch(((pitchBar?.progress ?: 50) + 50) / 100f)
            engine.speak(VoiceSettings.sample(locale.language), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "preview")
        }

        fun slider(title: String, key: String): android.widget.SeekBar {
            val value = label("", 13f, 0xFF5E7370.toInt(), top = 10)
            val bar = android.widget.SeekBar(this).apply {
                max = 150
                progress = preferences.getInt(key, 100) - 50
            }
            fun show(percent: Int) { value.text = "$title: ${percent}%" }
            show(bar.progress + 50)
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar?, v: Int, fromUser: Boolean) = show(v + 50)
                override fun onStartTrackingTouch(b: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(b: android.widget.SeekBar?) = speakSample()
            })
            content.addView(value)
            content.addView(bar, matchWidth())
            return bar
        }
        speedBar = slider("Speed", VoiceSettings.KEY_RATE)
        pitchBar = slider("Pitch", VoiceSettings.KEY_PITCH)

        fun voiceLanguage(): String =
            Locale.forLanguageTag(codes[languageSpinner.selectedItemPosition].ifEmpty { Locale.getDefault().language }).language

        fun loadVoices() {
            val engine = preview ?: return
            voices = VoiceSettings.voicesFor(engine, voiceLanguage())
            voiceSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Default voice") + voices.mapIndexed { i, v -> VoiceSettings.label(v, i) }
            )
            val saved = preferences.getString(VoiceSettings.KEY_VOICE, "")
            voiceSpinner.setSelection(voices.indexOfFirst { it.name == saved } + 1)
        }
        languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) = loadVoices()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
        voiceSpinner.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) userPickedVoice = true
            false
        }
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (userPickedVoice) speakSample()
                userPickedVoice = false
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Article settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val language = codes[languageSpinner.selectedItemPosition]
                preferences.edit()
                    .putString(LANGUAGE, language)
                    .putString(TOPICS, topicsInput.text.toString().trim())
                    .putInt(RANDOMNESS, randomnessBar.progress)
                    .putInt(POPULARITY, popularityBar.progress)
                    .putInt(MAX_MINUTES, lengthValues[lengthSpinner.selectedItemPosition])
                    .putInt(VoiceSettings.KEY_RATE, (speedBar?.progress ?: 50) + 50)
                    .putInt(VoiceSettings.KEY_PITCH, (pitchBar?.progress ?: 50) + 50)
                    .apply()
                // Keep the saved voice if the engine hadn't finished loading.
                if (preview != null && voices.isNotEmpty() || preview != null && voiceSpinner.adapter.count > 1) {
                    preferences.edit()
                        .putString(VoiceSettings.KEY_VOICE, voices.getOrNull(voiceSpinner.selectedItemPosition - 1)?.name.orEmpty())
                        .apply()
                }
                updateSettingsSummary()
                dialog.dismiss()
            }
        }
        preview = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) runOnUiThread { loadVoices() }
        }
        dialog.setOnDismissListener {
            preview?.stop()
            preview?.shutdown()
            preview = null
        }
        dialog.show()
    }

    private fun updateSettingsSummary() {
        if (!::settingsSummary.isInitialized) return
        val language = preferences.getString(LANGUAGE, "").orEmpty()
            .ifBlank { "Phone language (${Locale.getDefault().language})" }
        val topics = preferences.getString(TOPICS, "").orEmpty().ifBlank { "Random topics" }
        val minutes = preferences.getInt(MAX_MINUTES, 0)
        val length = if (minutes == 0) "No limit" else "Up to $minutes min"
        settingsSummary.text = "$language · $topics · $length  (⋮ Settings)"
    }

    private fun updateFullScreenButton() {
        if (!::fullScreenButton.isInitialized) return
        fullScreenButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
            ) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
    }

    private fun label(text: String, size: Float, color: Int, top: Int = 0): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
                        if (top > 0) setPadding(0, dp(top), 0, 0)
        }

    private fun editField(hint: String, value: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            setSingleLine(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

    private fun button(text: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            styleButton(this, primary)
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styleButton(button: Button, primary: Boolean) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            if (primary) {
                setColor(ACCENT)
            } else {
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFCFE0DB.toInt())
            }
        }
        button.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt().let { if (primary) it else 0x220F766E }),
            shape,
            null
        )
        button.stateListAnimator = null
        button.isAllCaps = false
        button.textSize = 16f
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minHeight = dp(54)
        button.setTextColor(if (primary) 0xFFFFFFFF.toInt() else ACCENT)
    }

    private fun matchWidth(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (top > 0) topMargin = dp(top)
            rightMargin = dp(4)
            bottomMargin = dp(4)
        }

    companion object {
        private const val INK = 0xFF12312E.toInt()
        private const val PAPER = 0xFFF3F8F6.toInt()
        private const val ACCENT = 0xFF0F766E.toInt()
        private const val CORAL = 0xFFFF8A3D.toInt()
        private val CARD_COLORS = intArrayOf(0xFFE3F3EE.toInt(), 0xFFFFE9E1.toInt(), 0xFFE1ECFF.toInt(), 0xFFFFF1CC.toInt())
        const val PREFERENCES = "wiki_wake_settings"
        const val HOUR = "alarm_hour"
        const val MINUTE = "alarm_minute"
        const val LANGUAGE = "language"
        const val TOPICS = "topics"
        const val ENABLED = "alarm_enabled"
        const val READ_TITLES = "read_titles"
        const val MAX_MINUTES = "max_reading_minutes"
        const val RANDOMNESS = "randomness"
        const val POPULARITY = "popularity"
        private const val REQUEST_NOTIFICATIONS = 51
        private val LANGUAGE_CODES = listOf(
            "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da", "fi", "pl", "cs", "hu", "ro",
            "el", "tr", "ru", "uk", "ar", "he", "fa", "hi", "bn", "id", "vi", "th", "ja", "ko", "zh", "ca"
        )
    }
}
