<p align="center"><img src="logo.svg" alt="Wiki Wake logo" width="160"></p>

# Wiki Wake

An Android alarm that wakes you with a spoken Wikipedia article. It supports a daily local-time schedule, random articles or comma-separated topic searches, phone-language default (or a Wikipedia language code such as `es`), a configurable reading-time limit (unlimited by default), and a history that prevents repeats. Use the three-dot menu to configure language, topics, and reading time; enable or disable the alarm with the home-screen switch.

## Run

Open `wikipedia-alarm` in Android Studio, sync with Gradle 8.9, and run the `app` configuration on an Android 8.0+ device. A Java 17 JDK and Android SDK platform 35 are required.

When enabling the alarm, grant notification permission and allow **Alarms & reminders / exact alarms** in Android settings. On Android 14+, allow Wiki Wake to use **full-screen notifications** if you want its alarm screen to appear over the lock screen. Spoken playback starts automatically when the alarm fires; full-screen access only controls the lock-screen display.

The app fetches article text from Wikipedia and uses Android's installed text-to-speech voice. Internet access and a voice for the selected language are required. The alarm is restored after device restart.
