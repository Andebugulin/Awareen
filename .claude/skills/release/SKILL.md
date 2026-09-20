---
name: release
description: Cut an Awareen release - bump the version, verify build and tests, build correctly-signed artifacts, publish the GitHub release, and hand off the Play upload. Use for "make a release", "cut a release", "ship 1.28", or /release.
allowed-tools: Bash, Read, Grep, Glob
---

# Release (Awareen)

Ship one version to GitHub Releases and prepare the Play upload.

**One-time setup lives in `PLAY_RELEASE.md` and is not all done yet.** The
debug-signing fallback is gone, but until `local.properties` carries real
keystore credentials `assembleRelease` produces `app-release-unsigned.apk` and
step 4 cannot produce a publishable artifact. Check `PLAY_RELEASE.md` first.

## 1. Bump the version FIRST

Edit `app/build.gradle.kts`:

- `versionCode` — increment by exactly 1. Play permanently rejects a reused code.
- `versionName` — the human version, e.g. `"1.28"`.

**Never assume this is already done, and never leave it to the user.**

```bash
gh release list --limit 1                        # last GitHub release
grep -E "versionCode|versionName" app/build.gradle.kts
```

**`gh release list` is NOT the authority on versionCode — Play is.** GitHub
lags: a build can be uploaded to Play from Android Studio without ever being
tagged here. This bit v1.27: versionCode 29 sat bumped-but-uncommitted in the
working tree because it had already gone to Play, it cleared the "greater than
the last GitHub release" check, and Play rejected it as an existing version.

So: **check the highest versionCode in Play Console (Release > App bundle
explorer) and exceed that**, not the GitHub tag. If Play cannot be checked,
ask rather than assume — and treat an uncommitted bump in the working tree as
evidence a build was already shipped from it, not as work someone left for
you.

## 2. Preflight

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk     # JDK 17+ required by AGP
export ANDROID_HOME=/home/andrei/Android/Sdk      # local.properties sdk.dir must agree
git fetch origin
git status -sb                                    # on main, no stray staged files
git rev-list --left-right --count origin/main...main
```

Being **behind origin/main is the default failure mode**. Rebase and re-run
every gate afterwards; a build verified on a stale tree proves nothing.

Also confirm no build output is staged — `app/release/` and `*.apk`/`*.aab`
must never be committed.

## 3. Gates

```bash
./gradlew :app:testDebugUnitTest   # expect 0 failures; confirm the count, don't trust UP-TO-DATE
./gradlew :app:assembleRelease     # compile / R8 / lint-vital
```

Add `--rerun-tasks` when results look suspiciously cached — `BUILD SUCCESSFUL`
on an up-to-date task proves nothing. Current baseline: **40 tests, 0
failures**. A lower count means tests were skipped, not that they passed.

See `TESTING.md` for what is and is not covered (phases 0-1 done, 2-5 open).

## 4. Build the artifacts

```bash
./gradlew :app:bundleRelease       # app/build/outputs/bundle/release/app-release.aab  → Play
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk     → GitHub
```

This is only valid once `local.properties` carries `RELEASE_STORE_FILE` and
its three password/alias keys. **If the output is named
`app-release-unsigned.apk`, stop here** — signing is not configured, and
neither Play nor an upgrade-over-GitHub install will accept it.

## 5. Fingerprint gate — blocking

Verify **every** artifact before it leaves the machine. A wrong key on GitHub
is silent: users just hit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and lose their
settings and analytics history.

```bash
# APK
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep "SHA-256 digest"

# AAB (jar-signed; apksigner does not read it)
unzip -p app/build/outputs/bundle/release/app-release.aab 'META-INF/*.RSA' > /tmp/s.der
openssl pkcs7 -inform DER -in /tmp/s.der -print_certs 2>/dev/null | \
  openssl x509 -noout -subject -fingerprint -sha1 -fingerprint -sha256
jarsigner -verify app/build/outputs/bundle/release/app-release.aab 2>&1 | grep -i verified
```

The expected signer, taken from the published v1.26 APK:

| Field | Value |
|---|---|
| DN | `CN=Andrei Gulin` |
| SHA-256 | `b92b0b857679e351f8598dcb86761c50e38aba5e603aa862ec615b7330ced034` |

Compare every release against that. A mismatch means the wrong keystore — stop
and resolve it before uploading anywhere.

Confirm the artifact is what you think it is:

```bash
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep -E "^package|targetSdkVersion"
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk | tail -1   # 16 KB pages, Play requirement
```

## 6. Changelog, commit, tag, push

Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (≤500
chars) **before** tagging — it is scraped from the tagged commit.

```bash
git tag vX.Y.Z && git push origin main --tags
```

## 7. GitHub release

```bash
cp app/build/outputs/apk/release/app-release.apk /tmp/awareen_vX.Y.Z.apk
gh release create vX.Y.Z --target main --title "Awareen_vX.Y.Z" \
  --notes-file notes.md /tmp/awareen_vX.Y.Z.apk
```

Match the conventions of the existing releases exactly. Derive notes from
`git log <prev-tag>..main` and drop anything already described in the previous
release's notes. Draft first (`--draft`) whenever the changelog is uncertain.

## 8. Play Console — manual

No automation exists. Upload the `.aab` to **Internal testing** first, verify on
a device, then promote.

Play validates the signature at upload and rejects a wrong key harmlessly, so it
is a safe check — but step 5 should already have caught it.

On a first submission, budget review time for the **`specialUse` foreground
service declaration** and, if it is still declared, **`USE_EXACT_ALARM`**. See
`PLAY_RELEASE.md`.

## 9. Device verification

Exercise the paths most likely to break under a new target SDK or first-time R8:

- the overlay appears, drags, and snaps to an edge
- a daily reset actually fires (set the reset time a minute out and wait)
- the widget renders and updates
- the timer survives a screen-off / unlock cycle
- settings JSON export **and** import round-trip — R8 strips reflection targets,
  so this is the first thing minification breaks
- light mode: system bar icons still visible under enforced edge-to-edge

## Invariants

- Never commit `*.apk` / `*.aab`, `local.properties`, any `*.jks`, or anything
  under `app/release/` or `app/build/`.
- Never publish an artifact whose fingerprint you have not printed in this session.
- `applicationId` never changes. It is `com.andebugulin.awareen2`, already
  published, permanent on Play. The `2` is not a bug to fix.
- `versionCode` only ever goes up, by 1.
