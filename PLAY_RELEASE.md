# Play Store readiness (Awareen)

One-time setup and the things that will get a submission rejected. The
per-release procedure lives in `.claude/skills/release/SKILL.md`.

Ported from nfcGuard, which is already shipping on Play, F-Droid and
IzzyOnDroid. Everything here was checked against this repo's actual state on
2026-09-20, not assumed.

## Status

| Item | State |
|---|---|
| Target API 36 | **Done** — `d50ba19`, verified in the built APK |
| Repo buildable from a clone | **Done** — `e9e669f`, verified by cloning |
| Tracked build outputs | **Done** — `e9e669f` |
| Release no longer debug-signed | **Done** — `8d92536` |
| Release signing wired to a real keystore | **Blocked on credentials** — see below |
| `USE_EXACT_ALARM` | **Cleared by Play** — do not remove, see below |
| `specialUse` FGS Console declaration | **Cleared by Play** |
| Privacy policy URL | **Open** |
| Listing screenshots | **Open** — stale package name |

The app is **already published**, so two questions the original draft posed as
"decide now" are in fact already settled and must not be changed:

- **`applicationId` is fixed** at `com.andebugulin.awareen2`. The `2` is
  permanent. Do not "clean it up" — a different id is a different app with a
  different listing and zero installs.
- **The upload key already exists.** Do not run `keytool`. The published
  v1.26 APK is signed `CN=Andrei Gulin`, SHA-256
  `b92b0b857679e351f8598dcb86761c50e38aba5e603aa862ec615b7330ced034`.
  Signing must reproduce that fingerprint.

---

## Blockers — fix before any upload

### 1. Release signing — ~~debug key~~ now wired, needs credentials

**The debug-signing fallback is removed** (`8d92536`). `assembleRelease` now
produces `app-release-unsigned.apk` and fails `apksigner verify` unless a real
keystore is configured — loud failure rather than a Play-rejected artifact.

To finish, add to `local.properties` (gitignored):

```properties
RELEASE_STORE_FILE=/home/andrei/Documents/key_awareen_updated.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Two candidate keystores exist — `~/Documents/key_awareen.jks` and
`~/Documents/key_awareen_updated.jks`. **Which one is the real upload key is
not recorded anywhere in the repo.** Confirm by fingerprint before trusting
either:

```bash
keytool -list -v -keystore <candidate>.jks | grep -A1 "SHA256:"
```

It must match `b92b0b85…ced034` above. Back the keystore up somewhere that is
not this machine.

<details>
<summary>Original guidance, for reference only — do NOT create a new key</summary>

`app/build.gradle.kts`:

```kotlin
release {
    signingConfig = signingConfigs.getByName("debug")   // ← Play rejects this outright
}
```

Play refuses any artifact signed in debug mode. It is also the *only*
irreversible mistake in this document: whichever key first uploads this
`applicationId` is the upload key forever (short of a Play support reset), and
for any APK you distribute outside Play, a key change means every existing user
hits `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and loses their data.

Create a real keystore and wire it the way nfcGuard does:

```bash
keytool -genkey -v -keystore app/keystore/release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias awareen
```

```kotlin
import java.util.Properties
import java.io.FileInputStream

signingConfigs {
    create("release") {
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) {
            val props = Properties().apply { load(FileInputStream(propsFile)) }
            storeFile = file("keystore/release.jks")
            storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = props.getProperty("KEY_ALIAS", "awareen")
            keyPassword = props.getProperty("KEY_PASSWORD", "")
        }
    }
}

buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        isDebuggable = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) signingConfig = signingConfigs.getByName("release")
    }
}
```

`local.properties` is gitignored and holds `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. `*.jks` is already gitignored here — keep it that way, and back
the keystore up somewhere that is not this machine.

Note that turning on `isMinifyEnabled` for the first time is a real change:
smoke-test the release build, especially the JSON export/import paths, since R8
strips reflection targets. `isMinifyEnabled` is still **off** and was left off
deliberately — enabling it is a separate change that needs its own smoke test.

</details>

### 2. The repository cannot be built from a clone — **FIXED** (`e9e669f`)

`.gitignore` ignores `gradle/`, `gradlew`, and `gradlew.bat`, and none of the
three is tracked. `gradle/libs.versions.toml` — the version catalog every
module reads — is therefore invisible to git. Any dependency you add for
testing will work locally and vanish on clone.

```gitignore
# replace the blanket `gradle/` rule with:
.gradle/
!gradle/wrapper/gradle-wrapper.properties
```

then `git add -f gradle/ gradlew gradlew.bat`. The wrapper and the catalog are
meant to be committed; only `.gradle/` (the cache) is not.

Done, and verified by cloning the repo into a temp directory and running
`:app:assembleDebug` from the clone.

### 3. Build outputs are tracked — **FIXED** (`e9e669f`)

`app/release/baselineProfiles/**` and `app/release/output-metadata.json` are in
the index (your working tree already deletes them — commit that). Add
`app/release/` to `.gitignore`. `*.apk` / `*.aab` are already covered.

---

## Policy items — these are what a screen-time app actually gets flagged on

### `USE_EXACT_ALARM` — RESOLVED, keep it

**Play reviewed this app's declared use of `USE_EXACT_ALARM` and accepted it.**
The justification was supplied in the Console and approved, along with the
`specialUse` foreground-service declaration.

**Do not remove either.** Dropping `USE_EXACT_ALARM` now would be an
unnecessary behaviour change to the reset path, and re-declaring it later
would mean another review round trip. The analysis below is kept only as the
record of why it was questioned.

<details>
<summary>Original concern, superseded by Play's approval</summary>

The manifest declares **both**:

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

`USE_EXACT_ALARM` (API 33+) is auto-granted precisely because Play restricts
who may declare it: alarm clocks, timers, and calendar apps whose *core user-facing
function* is a scheduled alert. A daily counter reset is internal bookkeeping,
not an alarm the user set — that is very likely to be read as ineligible, and
Play rejects on it at review time rather than at upload.

`SCHEDULE_EXACT_ALARM` alone is the right permission here, and it is
user-grantable rather than restricted. Recommended: **drop `USE_EXACT_ALARM`**,
keep `SCHEDULE_EXACT_ALARM`, and handle `AlarmManager.canScheduleExactAlarms()`
returning false by falling back to `setAndAllowWhileIdle` (inexact, but the
tick loop and the `onResume` defensive check already cover a missed reset —
that is what the three independent triggers in `ResetScheduler` are for).

If you keep it, be ready to justify it in the Play Console declaration form and
expect a round trip with review.

</details>

### `specialUse` foreground service — declared and accepted

`8d8759d` moved the FGS type from `dataSync` to `specialUse`, which was the
right call for the crash — but `specialUse` is the one type Play reviews by
hand. The manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text is already written
and is good, and the matching declaration in **Play Console → App content →
Foreground service permissions** has been made and accepted.

Keep the two in sync if the manifest subtype text ever changes.

### `SYSTEM_ALERT_WINDOW`

Declarable without a form, but it draws scrutiny on a first submission because
it is the classic overlay-malware permission. The listing should say plainly,
above the fold, that the overlay is the product.

### Data safety form

Easy win: the manifest declares **no `INTERNET` permission**, so nothing can
leave the device. Declare *no data collected, no data shared*, and say so in
the listing — it is a genuine differentiator against every other screen-time
app.

A **privacy policy URL is mandatory** for every app regardless. `docs/` already
hosts a site; add a `privacy.html` there the way nfcGuard does and link it.

### `applicationId` is permanent — already settled

`applicationId = "com.andebugulin.awareen2"` while `namespace =
"com.andebugulin.awareen"`. The `2` reads like a leftover, but the app is
already published under it, so it is **permanent and must not be touched**.
The namespace/applicationId mismatch is harmless — namespace only names the
generated `R` class and is invisible to Play.

---

## Already satisfied

- **Target API level — DONE** (`d50ba19`). `compileSdk`/`targetSdk = 36`,
  `versionCode = 29`, `versionName = "1.27"`, committed. Verified in the built
  artifact: `aapt2 dump badging` reports `targetSdkVersion:'36'`,
  `compileSdkVersion='36'`, `platformBuildVersionName='16'`.
  **The Play warning clears only once a build with this target is actually
  published to production** — committing it is not enough.
  - The two behaviours API 36 enforces are edge-to-edge and predictive back.
    nfcGuard needed no migration but was still bitten one release later by
    *system bar icons invisible in light mode*. Check that on a light-mode
    device before shipping.
  - `README.md` said "Target SDK: 35 (Android 15)"; updated in `d50ba19`.
- **16 KB page alignment** (required at targetSdk 35+). Verify per release:
  `zipalign -c -P 16 -v 4 app-release.apk`. No NDK here, so it should pass.

---

## Store listing metadata

Keep the listing copy in the repo rather than only in the Console, using the
fastlane layout. It costs nothing, it version-controls your copy, and it is
exactly what F-Droid and IzzyOnDroid scrape from a tag if you ever list there.

```
fastlane/metadata/android/en-US/
├── title.txt                     ≤30 chars
├── short_description.txt         ≤80    — heavily indexed by Play search; use all 80
├── full_description.txt          ≤4000  — plain text, `*` bullets render on both stores
├── changelogs/<versionCode>.txt  ≤500   — one per release, written BEFORE the tag
└── images/
    ├── icon.png                  512×512
    ├── featureGraphic.png        1024×500
    └── phoneScreenshots/1.png…   portrait, min 2, ordered by filename
```

The screenshots in `images/` are from the old `com.example.screentimetracker`
build and show a stale package name in the filename — retake them before
uploading.

Copy lessons from nfcGuard's listing rewrite:

- **Lead with the mechanic, not the name.** "A timer that sits on top of every
  app so you always know how long you have been on your phone" beats "Awareen
  is a screen time awareness app".
- **Use the full 80 characters** of the short description, with the terms
  people search: *screen time, overlay timer, digital wellbeing, focus*. Ours
  used 37 of 80 and ranked accordingly.
- **Move the differentiator up.** No ads, no tracking, no account, fully
  offline, open source — that belongs in the first three lines, not the last.

---

## Optional: Play Developer API automation

Does not exist in nfcGuard either — the listing is edited by hand there. If you
want it here: create a service account in Google Cloud, grant it release
permission in Play Console → Users and permissions, download the JSON key, add
a `Fastfile` with `upload_to_play_store(track: 'internal', aab: ...)`, and keep
the key as a CI secret, never in the repo. The `fastlane/metadata/` tree above
is already what `supply` uploads, so the listing half comes free.

Worth it only once releases are frequent enough that the manual upload is the
bottleneck. It is not the bottleneck yet.
