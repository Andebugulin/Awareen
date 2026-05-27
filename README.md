# Awareen - Screen Time Awareness App

**Awareen** (Awareness + Screen) is an Android application that helps users become more aware of their screen time through a persistent overlay timer. The app displays your current screen time in real-time, even when using other applications, promoting mindful device usage.

<div style="text-align: center; margin: 40px 0;">
  <a href="https://play.google.com/store/apps/details?id=com.andebugulin.awareen2" target="_blank">
    <img src="https://upload.wikimedia.org/wikipedia/commons/7/78/Google_Play_Store_badge_EN.svg" alt="Get it on Google Play" style="height: 80px;">
  </a>
</div>

## Features

- **Persistent Overlay Timer**: Always-visible screen time counter that works across all apps
- **Home-Screen Widget**: A glanceable 2×1 widget showing today's accumulated screen time, color-coded by the same level system as the overlay. Tapping it opens the app.
- **Customizable Display**: Adjustable colors, positions, and font sizes for each level
- **Three Display Modes**: Always-on, periodic interval, or never (widget-only)
- **Analytics Dashboard**: Track your daily screen time patterns and trends
- **Auto-Reset**: Configurable daily reset time for screen time tracking
- **In-Settings Help**: Tap the small (i) icon next to any settings section for a plain-English explanation of what it controls

## Screenshots

<p align="center">
  <img src="./images/Screenshot_2025_08_01_15_31_57_447_com_example_screentimetracker.jpg" width="22%" />
  <img src="./images/Screenshot_2025_08_01_15_32_00_510_com_example_screentimetracker.jpg" width="22%" />
  <img src="./images/Screenshot_2025_08_01_15_32_03_565_com_example_screentimetracker.jpg" width="22%" />
  <img src="./images/Screenshot_2025_08_01_15_32_16_560_com_example_screentimetracker.jpg" width="22%" />
</p>

## How It Works

### Three-Level System

1. **Level 1 (Green)**: Default display for normal usage (0-60 minutes)
2. **Level 2 (Yellow)**: Warning phase when approaching time limits (60-120 minutes)
3. **Level 3 (Red)**: Alert phase for excessive usage (120+ minutes)

> Colors and time thresholds are fully customizable in the settings.

### Display Modes

- **Always Mode**: Timer constantly visible on screen
- **Interval Mode**: Timer appears periodically (configurable interval and duration)
- **Never Mode**: Overlay hidden — tracking continues silently in the background. Pair with the home-screen widget for awareness without the floating timer.

## Technical Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Architecture**: Service-based with overlay permissions

## Prerequisites

- Android 8.0 (API level 26) or higher
- Overlay permission (System Alert Window)
- App-pausing permission
- Battery optimization exemption (recommended)
- Auto-start permission (recommended)

## Installation

1. **Clone the repository**

   ```bash
   git clone https://github.com/andebugulin/awareen.git
   cd awareen
   ```

2. **Open in Android Studio**

   - Import the project into Android Studio
   - Sync Gradle files

3. **Build and Install**
   - Just use android studio builder, its the simplest
   - Go to build, then generate signed app bundle or APK, follow the steps

## Usage

1. **Grant Permissions**:

   - Launch Awareen
   - Press "Start Service" to request necessary permissions

2. **Customize Settings**:

   - Access settings through the gear icon
   - Adjust colors, positions, and time thresholds
   - Configure display modes and reset times

3. **View Analytics**:

   - Check your usage patterns in the Analytics section
   - Track daily screen time trends

4. **(Optional) Add the Home-Screen Widget**:
   - Long-press an empty area of your home screen → Widgets → pick "Awareen"
   - The widget mirrors the same level-based coloring as the overlay
   - Especially handy in combination with the **Never** display mode if you'd rather not see the floating timer

## Configuration

### In my opinion, best Settings

- **Level 1**: 60 minutes (color - #6F48A7, Top Right, 22sp, blinking enabled)
- **Level 2**: 60 minutes duration (#A8CC58, Top Left, 23sp, blinking enabled)
- **Level 3**: Unlimited (#A3F5C8, Top Center, 33sp, blinking enabled)
- **Reset Time**: Midnight (00:00)
- **Display Mode**: Interval each minute for 8 seconds

### Customization Options

- Timer colors and positions for each level
- Font sizes (adjustable per level)
- Time thresholds for level transitions
- Display intervals and durations
- Daily reset timing
- Blinking alerts (I higly recommend it)

## Project Structure

The code is split into four packages by responsibility — `ui` talks to `service`, `service` talks to `data`, and `overlay` is a small shared domain used by both.

```
app/src/main/
├── java/com/andebugulin/awareen/
│   ├── data/
│   │   ├── AppSettings.kt           # Pref keys + defaults + broadcast action
│   │   ├── ScreenTimeRepository.kt  # Daily totals, reset bookkeeping, analytics I/O
│   │   └── SettingsRepository.kt    # Per-level settings I/O + settings-updated broadcast
│   ├── overlay/
│   │   ├── OverlayController.kt     # WindowManager overlay, touch handler, render()
│   │   └── OverlaySettings.kt       # Immutable view-config data classes
│   ├── service/
│   │   ├── ScreenTimeService.kt     # Foreground service + 1s tick loop coordinator
│   │   ├── ResetScheduler.kt        # Wall-clock math + AlarmManager (Doze-proof)
│   │   ├── ScreenStateMonitor.kt    # PowerManager + KeyguardManager wrapper
│   │   └── BootReceiver.kt          # Starts the service on BOOT_COMPLETED
│   ├── widget/
│   │   └── ScreenTimeWidgetProvider.kt  # Home-screen widget (level-colored, pushed every 30s by the service)
│   └── ui/
│       ├── MainActivity.kt          # Start/stop + navigation + defensive reset check
│       ├── PermissionWizard.kt      # 4-step permission state machine
│       ├── SettingsActivity.kt      # Per-level UI + JSON export/import
│       ├── AnalyticsActivity.kt     # Daily/hourly RecyclerView + JSON export/import
│       ├── InfoActivity.kt          # About screen
│       ├── UnsavedChangesDialog.kt  # Custom dialog used by SettingsActivity
│       └── Colorpickerview.kt       # HSV color picker custom view
├── res/
│   ├── layout/                      # UI layouts (incl. widget_screen_time.xml)
│   ├── drawable/                    # Icons and graphics (incl. widget_background.xml)
│   ├── values/                      # Strings, colors, themes
│   └── xml/                         # Backup/data rules + widget_screen_time_info.xml
└── AndroidManifest.xml              # App permissions and components
```

See `CLAUDE.md` for a fuller walkthrough of the architecture, the settings broadcast flow, and SharedPreferences key conventions.

## Permissions

```
   <!-- Overlay permissions -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <!-- Boot and startup permissions -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Foreground service permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

    <!-- Doze-proof daily reset -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

    <!-- Keep service alive -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <!-- Foreground-service notification (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Troubleshooting

### Common Issues

**Timer stops working**:

- Check if overlay permission is granted
- Disable battery optimization for Awareen
- Ensure the app pause is disabled

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Author

**Andrei Gulin**

- GitHub: [@Andebugulin](https://github.com/Andebugulin)
- LinkedIn: [Andrei Gulin](https://www.linkedin.com/in/andrei-gulin)

## Support My Work

If you find this app useful, consider supporting me:

<a href="https://www.buymeacoffee.com/andebugulin">
  <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="160">
</a>

## Connect with Me

- [GitHub](https://github.com/Andebugulin)
- [LinkedIn](https://www.linkedin.com/in/andrei-gulin)

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

### `Download`

<div style="text-align: center; margin: 40px 0;">
  <a href="https://play.google.com/store/apps/details?id=com.andebugulin.awareen2" target="_blank">
    <img src="https://upload.wikimedia.org/wikipedia/commons/7/78/Google_Play_Store_badge_EN.svg" alt="Get it on Google Play" style="height: 80px;">
  </a>
</div>
