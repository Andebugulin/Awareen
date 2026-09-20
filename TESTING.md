# Testing plan (Awareen)

**Status: Phases 0 and 1 are done** (40 JVM tests, ~1s, 0 failures). Phases 2-5
are outstanding. Run with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/andrei/Android/Sdk
./gradlew :app:testDebugUnitTest
```

Original state: 0 real tests — `ExampleUnitTest` and `ExampleInstrumentedTest`
were the Android Studio templates and asserted nothing about this app.
`ExampleUnitTest` is deleted; the instrumented template stays until Phase 5
puts a real test beside it.

This is the plan ported from nfcGuard, which reached 442 tests on the same
shapes of problem (foreground service, overlay, alarms, SharedPreferences,
widget). Awareen differs in two ways that matter: it is **Views, not Compose**,
and it is a **single `:app` module**. Everything below accounts for that.

## Target shape

| Tier | Where | Tool | What belongs there |
|---|---|---|---|
| Pure logic | `domain` package in `:app` | plain JUnit | reset math, level selection, interval visibility, analytics keys |
| Android-dependent | `app/src/test` | Robolectric | repositories, `ResetScheduler` alarms, `ScreenStateMonitor`, `BootReceiver`, service lifecycle, widget |
| Device-only | `app/src/androidTest` | Espresso + UiAutomator | the real overlay window, activities, permission wizard |

Ordering is deliberate: each phase makes the next cheaper. Do not start at the
Activity end — it is the slowest tier and the least of the value.

---

## Phase 0 — wire up the harness — **DONE**

`gradle/libs.versions.toml`:

```toml
robolectric = "4.15.1"
androidxTestCore = "1.6.1"
uiautomator = "2.3.0"

[libraries]
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
```

`app/build.gradle.kts` — inside `android { }`:

```kotlin
testOptions { unitTests.isIncludeAndroidResources = true }   // Robolectric needs this
```

and in `dependencies { }`:

```kotlin
testImplementation(libs.junit)
testImplementation(libs.robolectric)
testImplementation(libs.androidx.test.core)

androidTestImplementation(libs.androidx.junit)
androidTestImplementation(libs.androidx.espresso.core)
androidTestImplementation(libs.androidx.test.core)
androidTestImplementation("androidx.test:runner:1.6.2")
androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
```

`app/src/test/resources/robolectric.properties`:

```properties
# targetSdk is 36; Robolectric 4.15 tops out at 35. Pin the default so tests
# without an explicit @Config still run. Version-sensitive tests declare their
# own @Config(sdk = [...]) range.
sdk=34
```

Delete both `Example*Test.kt` files once the first real test in each source set
exists.

The prerequisite blocker — `.gitignore` swallowing `gradle/`, `gradlew` and
`gradlew.bat`, so `libs.versions.toml` was invisible to git — is fixed in
`e9e669f`. A fresh clone now builds (verified).

---

## Phase 1 — extract the pure decisions — **DONE**

Landed in `9676b22` as a **`com.andebugulin.awareen.domain` package inside
`:app`**, not a separate Gradle module.

Rationale for the deviation: a separate module buys exactly one thing a
package does not — the compiler refusing an Android import. That is real, but
four pure functions did not justify a second Gradle module in a single-module
app, and the tests run on the plain JVM either way (no Robolectric, ~1s). If
the domain layer grows enough that accidental Android coupling becomes a live
risk, promoting the package to a module is a mechanical move.

The original module recipe is kept below for that day.

`settings.gradle.kts`: `include(":domain")`

`domain/build.gradle.kts`:

```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin { jvmToolchain(11) }

dependencies { testImplementation(libs.junit) }
```

`app/build.gradle.kts`: `implementation(project(":domain"))`

You will also need `alias(libs.plugins.kotlin.jvm)` in the root `build.gradle.kts`
plugins block (`apply false`) and a `kotlin-jvm` entry in the version catalog.

### What moved, concretely

Four pieces of real decision-making were welded to Android types. Each was a
pure function hiding inside something that touches the system. All four now
live in `domain/` with callers delegating to them:

**1. Reset wall-clock math** — `ResetScheduler.getMostRecentResetMillis()` /
`getNextResetMillis()` (`service/ResetScheduler.kt:120-152`).

Both call `System.currentTimeMillis()` and `Calendar.getInstance()` internally,
which makes `shouldReset()` untestable without freezing the device clock. Take
`now` and a `ZoneId` as parameters instead:

```kotlin
// :domain
object ResetMath {
    fun mostRecentReset(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long
    fun nextReset(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long
    fun shouldReset(lastResetMillis: Long, nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Boolean
}
```

`ResetScheduler` keeps the `AlarmManager` and repository wiring and passes
`System.currentTimeMillis()` in. This is the piece most worth doing: the reset
is the app's core correctness claim, it has three independent trigger paths
that must agree, and the cases that break it — **DST transitions**, a reset
time of exactly `00:00`, reset hour changed while a reset was already overdue,
device clock moved backwards — cannot be exercised any other way.

**2. Level selection** — `OverlayController.render()` (`overlay/OverlayController.kt:215-220`).

```kotlin
fun levelFor(seconds: Int, level1MaxSeconds: Int, level2DurationSeconds: Int): Int
```

Pin the boundaries (`seconds == level1Max` is level 2, not 1) and what happens
when a user sets level-1 max to 0 or the durations overlap.

**3. Interval visibility** — same function, `:263-269`:

```kotlin
fun shouldShowOverlay(mode: String, seconds: Int, intervalMinutes: Int, durationSeconds: Int): Boolean
```

Note the live division by `settings.timerDisplayIntervalMinutes` — a zero there
is an `ArithmeticException` in the 1-second tick loop, i.e. a crash loop. Test
it.

**4. Analytics key formatting** — `screen_time_YEAR_DAY_OF_YEAR`,
`analytics_YEAR_MONTH_DAY`, `analytics_..._hour_H` in `ScreenTimeRepository`.
Key construction is pure string/date math and is exactly where a year-boundary
or day-of-year off-by-one silently corrupts a user's history.

**Outcome: 40 JVM tests, ~1s, 0 failures**, covering the parts of the app that
are actually hard to get right.

Two live bugs fell out of the extraction and are fixed:

- `shouldShowOverlay` divided by the display interval, which is reachable
  unvalidated from an imported settings JSON. A zero raised
  `ArithmeticException` inside the one-second tick loop — a crash loop, not a
  single crash. The interval is now coerced to >= 1.
- `ResetMath` coerces a stored reset hour/minute into range rather than
  letting `LocalTime.of` reject a corrupt value on the same tick loop.

`AnalyticsKeys` preserves the **0-based month** inherited from
`Calendar.MONTH` — September 2026 is `analytics_2026_8_20`. Changing it would
silently orphan every user's stored history, so the tests assert equivalence
against the `Calendar` form for every day of a year and of a leap year.

---

## Phase 2 — Robolectric: data layer

`ScreenTimeRepository`, `SettingsRepository`, `AppSettings` defaults.

Robolectric gives real `SharedPreferences`, so these are straightforward:
write → read back → assert. What to actually pin:

- **Round-trips** for every settings group (per-level style, thresholds,
  display mode, reset time), including `loadOverlaySettings()` returning the
  right typed snapshot.
- **Defaults on a blank install** — every `AppSettings.DEFAULT_*` surfaces when
  nothing is stored.
- **Corrupt / partial prefs cost the user nothing.** A missing key, a string
  where an int belongs.
- **JSON export/import round-trip** (`SettingsActivity` / `AnalyticsActivity`
  SAF paths) — and that importing a file written by an older version does not
  throw. nfcGuard's equivalent test caught real regressions here.
- **`notifySettingsUpdated()` sets the package on the Intent.** On API 34+ a
  `RECEIVER_NOT_EXPORTED` receiver never sees a broadcast without
  `setPackage()`. CLAUDE.md already flags this as a rule; a test makes it one.

---

## Phase 3 — Robolectric: service layer

This is where nfcGuard got the most surprising value, because the received
wisdom is that a foreground service is untestable. That is true of
*instrumentation* — Android answers a late `startForeground` with
`ForegroundServiceDidNotStartInTimeException` and takes the process down — but
**not of the JVM**, where Robolectric drives the lifecycle directly and no
foreground-service deadline applies.

- **`ScreenStateMonitor`** — `ShadowPowerManager` / `ShadowKeyguardManager`
  give you all four combinations of screen-on × locked. `isActive()` must be
  false when locked even with the screen on. (This exact bug shipped in
  nfcGuard as issue #13.)
- **`ResetScheduler` alarms** — `ShadowAlarmManager` lets you assert that an
  exact alarm was scheduled *at* `nextReset`, that `cancelAlarm()` removes it,
  and that a settings change reschedules rather than stacking.
- **`BootReceiver`** — call `onReceive` directly with `BOOT_COMPLETED` and
  assert the service start intent. Also assert `QUICKBOOT_POWERON`, since it is
  in the filter.
- **`ScreenTimeService` lifecycle** — `onCreate` / `onStartCommand` /
  `onDestroy`, the notification the user actually sees, the return of
  `START_STICKY`, and the four receivers being registered and unregistered.
- **One tick of the loop** — do **not** go through `onStartCommand`: it starts
  the real 1-second `Handler` loop, whose ticks race the ones a test drives,
  and it never ends, so it keeps the test JVM alive after the suite finishes.
  Construct the service, set its collaborators, and invoke the tick body.

---

## Phase 4 — Robolectric: widget

`ScreenTimeWidgetProvider`. `ShadowAppWidgetManager` inflates the real
`RemoteViews`, so the rendered text can be read back, and the provider is a
plain `BroadcastReceiver`, so `onReceive` can be called directly. No device.

Cover every render state (zero, each of the three levels, a stale day), and the
tap `PendingIntent`.

**Pin the action strings literally in the test, even though they are private.**
They are a published contract — baked into `PendingIntent`s that live in the
launcher's process and survive app upgrades. Renaming one silently breaks
already-placed widgets, and a literal-pinning test is the only thing that
catches it.

---

## Phase 5 — device tests

Keep this tier small. It is slow and it is the one that rots.

- **The real overlay window.** `TYPE_APPLICATION_OVERLAY` show / hide / drag /
  snap-to-edge / teardown. This cannot be faked and it is where the visible
  bugs have been (`a79e077`, `9d58e77`).
- **`PermissionWizard`** — the four-step state machine. It eagerly registers
  four `ActivityResultLauncher`s at construction, which is a crash-on-launch
  shape if the ordering ever changes.
- **Activities via Espresso** — `MainActivity` start/stop, navigation,
  `SettingsActivity` save/discard, the color picker (which crashed in
  `efc87a1` — that one deserves a regression test by name).

### Device-tier rules, learned the hard way

- **Do not use `./gradlew connectedAndroidTest`.** It reinstalls the APK every
  run, and a reinstall wipes the overlay grant and battery-optimization
  exemption the suite depends on. Install once, then drive the runner:

  ```bash
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  bash scripts/grant-test-permissions.sh
  adb shell am instrument -w \
    com.andebugulin.awareen2.test/androidx.test.runner.AndroidJUnitRunner
  ```

  Write that grant script (port of nfcGuard's): `appops set SYSTEM_ALERT_WINDOW
  allow`, `pm grant POST_NOTIFICATIONS`, `dumpsys deviceidle whitelist +PKG`.
  Have it print the resulting state so a failure names its own `adb` fix.

- **Rebuild both APKs.** `assembleDebugAndroidTest` does not rebuild
  `app-debug.apk`. Installing only the test APK runs new tests against old
  code, which presents as a mass of mysterious failures.

- **Never `runBlocking` / `Thread.sleep` on the main thread** while driving the
  overlay. Its animations post completion callbacks to the main looper, which a
  blocked main thread can never drain — instant deadlock.

- **Never start the foreground service from an instrumentation context.** Late
  `startForeground` → `ForegroundServiceDidNotStartInTimeException` → the whole
  process dies. Its intent contract belongs in the Robolectric tier.

- **On Xiaomi/HyperOS, `adb install -r -t`** — without `-t` some builds reject
  the test APK, and older HyperOS gates "Install via USB" behind a Mi account
  and needs a manual tap per install.

---

## Multi-SDK `@Config` — do this everywhere it applies

The highest-value single technique, and it came from a real crash (nfcGuard
issue #12, Galaxy S8): `unsafeCheckOpNoThrow` is API 29+, so on API 26/28 it
raised `NoSuchMethodError` — which `catch (Exception)` does **not** catch.

```kotlin
@Config(sdk = [26, 28, 29, 33, 34])
fun `counts a tick only when the screen is on and unlocked, on every API level`()
```

Robolectric loads the real `android-all` jar per level, so a method absent on
API 28 genuinely is absent. `minSdk = 26` and `targetSdk = 36` span ten levels
nobody checks by hand.

Awareen has at least five version-gated sites that each want one:

| Site | Gate |
|---|---|
| `ScreenStateMonitor.isScreenOn` | `KITKAT_WATCH` — `isInteractive` vs deprecated `isScreenOn` |
| `ResetScheduler.scheduleNextAlarm` | `M` — `setExactAndAllowWhileIdle` vs `setExact` |
| The four receiver registrations | API 33/34 — `RECEIVER_EXPORTED` vs `RECEIVER_NOT_EXPORTED` |
| `ScreenTimeService` foreground start | API 34 — `foregroundServiceType` enforcement |
| `PendingIntent` flags | `FLAG_IMMUTABLE` required from API 31 |

**Any code touching a version-gated API should carry a multi-SDK `@Config`.**

---

## Conventions

- Name tests as sentences describing behaviour, not the method under test.
- Reset process-wide singletons in `@Before` — Robolectric hands each test a
  fresh `Application`, so a stale static keeps a dead `Context` and leaks state
  between tests. `AppSettings` is an `object`; check it holds no mutable state.
- Shared builders that both suites need go in `app/src/testShared/java`, wired
  into both source sets:

  ```kotlin
  sourceSets {
      getByName("test").java.srcDir("src/testShared/java")
      getByName("androidTest").java.srcDir("src/testShared/java")
  }
  ```

  Robolectric-only helpers stay in `src/test` — Robolectric is not on the
  instrumented classpath.
- Every fixed bug gets a test that fails without its fix. nfcGuard keeps a
  table mapping GitHub issues to the test that reproduces them; start one here
  with `efc87a1` (color-picker crash) and `8d8759d` (FGS type crash on Samsung).
