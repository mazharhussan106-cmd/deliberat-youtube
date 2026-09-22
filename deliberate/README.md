# Deliberate

A distraction-free YouTube client for Android. It opens to a search box, not a feed.
Reaching the Home feed or Shorts requires a PIN, and that unlock dies when the app process does.

All four phases of the plan are built. Every source file type-checks clean and all 77 unit
tests pass (see [What has actually been verified](#what-has-actually-been-verified)), but
**nothing has run on a real phone yet** — see [First device run](#first-device-run).

> ### Not affiliated with YouTube or Google
>
> This is an independent, unofficial project. "YouTube" is a trademark of Google LLC and
> nothing here is endorsed by or connected to them.
>
> It works by loading YouTube's mobile website in a `WebView` and hiding parts of it. That
> approach is **not compatible with YouTube's developer policies** — among others, III.I.14
> (no accessing YouTube data by any technology other than the YouTube API Services), III.E.6
> (no scraping) and III.I.6 (no blocking any portion of a YouTube player). It is therefore
> **not publishable on Google Play**, and it is distributed here as a personal tool, as-is,
> with no warranty. Use it on your own account at your own risk.
>
> A version that would be policy-compliant is a different app: search through the YouTube
> Data API and playback through the official embedded player, with no feed screens built at
> all. That is a rewrite, not a setting.

```
first run → set PIN → search landing → WebView → UrlClassifier → BlockPolicy
                                                      ↓
                                        Home / Shorts blocked → PIN → session unlock
```

| Phase | What it added | |
|---|---|---|
| 1 | Search landing, WebView, URL classifier, block wall, Home blocked, PIN, session unlock | ✅ |
| 2 | Shorts blocked, with its own independent unlock | ✅ |
| 3 | First-run PIN setup, PBKDF2 + encrypted storage, five-try lockout | ✅ |
| 4 | Home/Shorts tab icons and Shorts shelves hidden | ✅ |

---

## Getting an APK

Three routes. Pick whichever matches what you have installed.

### A. GitHub Actions — no Android Studio needed

Best option if you would rather not run Android Studio on the EliteBook.

1. Push this folder to a GitHub repo (private is fine).
2. **Actions** tab → **Build debug APK** → **Run workflow**.
3. When the run goes green, download the **deliberate-debug-apk** artifact.
4. Unzip, copy `app-debug.apk` to your phone, allow "install unknown apps", install.

The workflow (`.github/workflows/build.yml`) runs the unit tests first, so a red run means
something is genuinely broken rather than the APK being silently wrong.

### B. Android Studio

Open the `deliberate` folder (**not** the `app` folder). Let it sync — first sync downloads AGP,
Kotlin and androidx, so it needs a few hundred MB and some patience. Then
**Build → Build Bundle(s) / APK(s) → Build APK(s)**, or hit Run with a device attached.

### C. Command line

Requires the Android SDK and a `local.properties` pointing at it (Android Studio writes this
for you; otherwise create it with `sdk.dir=/path/to/Android/Sdk`).

```bash
./gradlew :app:testDebugUnitTest    # 77 unit tests, no emulator needed
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`.

---

## Publishing a release

Releases are distributed from this repo's **Releases** page, not from an app store. Pushing a
tag builds a signed APK, runs the tests first, and publishes the release automatically
(`.github/workflows/release.yml`).

### One-time: create a signing key

A release APK must be signed, and **every future update must be signed with the same key** —
Android refuses to install an update signed by a different one. Losing this file means every
user has to uninstall and reinstall. Back it up somewhere you will still have in five years.

`keytool` ships with any JDK (Android Studio includes one).

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -alias deliberate
```

Use the **same password** for the store and the key — PKCS12 does not really support separate
ones. Answer the name and organisation questions however you like; none of it is verified.

### One-time: hand the key to GitHub Actions

Base64-encode the keystore so it can live in a secret.

PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Set-Content release.b64
```

macOS / Linux:

```bash
base64 -w0 release.keystore > release.b64
```

Then in the repo: **Settings → Secrets and variables → Actions → New repository secret**, four
times:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the entire contents of `release.b64` |
| `KEYSTORE_PASSWORD` | the password you chose |
| `KEY_ALIAS` | `deliberate` |
| `KEY_PASSWORD` | the same password again |

Keep `release.keystore` and `release.b64` out of the repo — `.gitignore` already covers them.

### Every release

Either push a tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

…or, with no git installed, do it from the website: **Releases → Draft a new release**, type
`v1.0.0` into the tag box, choose **Create new tag on publish**, then **Publish release**. The
workflow handles both — it attaches the APK to the release you just made rather than trying to
create a second one.

The workflow runs the tests, builds, verifies the APK really is signed, attaches it to a new
GitHub Release and deletes the keystore from the runner. The version name comes from the tag
and the version code from the run number, so updates always install over the previous version.

You can also run it manually from the **Actions** tab — that builds and uploads an artifact
but does not create a Release.

---

## Installing it

No app store, so it is a manual install:

1. Open this repo's **Releases** page and download the `.apk` from the newest release.
2. Open the file on your phone. Android will ask permission to install from this source —
   allow it for your browser or file manager.
3. Install, open, set a PIN.

To update, download the newer APK and install it over the top. Your PIN survives, because the
signing key is the same.

---

## How the block works

`YouTubeWebViewClient` is the only thing between you and the feed. It gates **navigation**,
never resources — `shouldInterceptRequest` is deliberately unused, because it is built for
resource loading, runs off the UI thread, and gives weak guarantees around redirects.

Three callbacks feed one decision:

| Callback | Catches |
|---|---|
| `shouldOverrideUrlLoading` | Real URL loads: link taps, redirects, deep links. Returning `true` means nothing loads at all. |
| `doUpdateVisitedHistory` | **SPA navigation.** Mobile YouTube's bottom-nav Home tap, and most Shorts opens, are a `history.pushState()`, which never reaches `shouldOverrideUrlLoading`. Without this callback the most likely route into both blocked sections is completely unguarded. |
| `onPageStarted` | Redirect chains that land on the root. |

> The plan called `shouldOverrideUrlLoading` the *sole* security boundary. That was aimed at
> keeping resource interception out of the design, and it still holds. `doUpdateVisitedHistory`
> is navigation interception, not resource interception, so it belongs on the same boundary —
> and none of the phases work without it.

### The pieces

| File | Job |
|---|---|
| `UrlClassifier` | What a URL *is*: `HOME`, `SHORTS` or `NORMAL`. |
| `BlockPolicy` | Whether that is *denied*, given the build flags and the session's unlocks. |
| `SessionState` | Which sections are unlocked, for this process only. |
| `PinHasher` | PBKDF2-HMAC-SHA256 derivation and constant-time comparison. |
| `CooldownPolicy` | The lockout arithmetic, including the clock-moved cases. |
| `PageCosmetics` | The CSS that hides tab icons and Shorts shelves. |
| `PinManager` | Encrypted storage; wires the hasher and the cooldown together. |
| `YouTubeWebViewClient` | The block wall. |
| `MainActivity` | Landing, WebView, and the overlay in its three modes. |

The first six have no `android.*` imports at all, which is why the test suite needs no
emulator. `PinManager` is deliberately thin so the parts worth testing are not trapped behind
a `Context`.

**`SessionState`** is a Kotlin `object`, i.e. a process-scoped singleton. It survives Activity
recreation (rotation, config change) and resets only when the process dies. That is exactly
what "session-only" should mean. Holding it on the Activity would reset the unlock on every
rotation, which is a bug wearing a feature's clothes.

**Threading.** 120,000 PBKDF2 iterations cost a few hundred milliseconds on a phone, which is
far too long for the main thread, so every derivation runs on a background executor and posts
back. Expect a short pause after tapping **Unlock** — that is the work happening.

---

## The PIN

Set on first launch, twice. Not in the source, not editable without the app.

Stored as a 16-byte random salt plus a PBKDF2-HMAC-SHA256 derivation at 120,000 iterations in
`EncryptedSharedPreferences` (falling back to plain app-private prefs if the device keystore
is broken — what is stored either way is a hash, not the PIN). Comparison is constant-time.

Five wrong tries trigger a **60-second lockout**, written to storage rather than held in
memory, so force-stopping the app does not clear it. A wrong PIN does not eject you — the
overlay stays and tells you how many tries are left, because a five-attempt budget is
meaningless if one typo ends the attempt. "Go back" is still right there.

### What this does not protect against

Worth being plain about, because PBKDF2 makes things sound stronger than they are:

- **Clearing the app's data resets everything.** Storage → Clear data wipes the stored hash,
  which puts the app back to first-run setup where anyone can pick a new PIN. That is a total
  bypass and nothing in this design prevents it. A sideloaded app cannot defend against the
  person holding the unlocked phone.
- **A 4-digit PIN is 10,000 candidates.** Someone who pulls the hash off the device can try
  all of them; 120,000 iterations turns that into minutes rather than milliseconds, and no
  more. **If that matters to you, use more digits** — each one multiplies the search by ten
  and nothing in the code has to change.
- **The cooldown uses wall-clock time.** Winding the phone's clock back defeats it. On your
  own phone that is more effort than waiting the minute out, which is rather the point.

The real job here is making the Home feed *deliberate*, not impossible. It is good at that.

**Forgot your PIN?** Settings → Apps → Deliberate → Storage → Clear data, then set a new one.
(Yes — this is the bypass above. Same door.)

---

## Phase 4: hiding the temptation

The block wall stops you *going* to the Home feed. It cannot stop YouTube *offering* it — the
tab icons sit in the bottom nav, and Shorts shelves scroll past inside search results as inline
content that no navigation hook ever sees. Phase 4 hides them.

**This is polish, not security.** Everything it hides is still behind the block wall. If a
selector goes stale the app gets slightly more tempting and stays exactly as safe.

**Why CSS rather than deleting nodes.** `onPageFinished` fires once per real page load, and
mobile YouTube then rewrites its own DOM constantly — the bottom nav often does not exist yet
when the page "finishes", and SPA navigation never fires `onPageFinished` again. JavaScript
that deletes nodes would have to win a race it cannot win, repeatedly. A `<style>` element is
declarative: inject it once and it applies to matching elements whenever they appear.

The injection is idempotent (one element, reused), driven by the same `BlockPolicy` as the
block wall (so unlocking a section brings its tab back rather than leaving a hole in the nav),
and wrapped in try/catch. Each selector gets **its own rule** — CSS drops an entire rule when
any selector in a comma-separated list fails to parse, so grouping them would let one
unsupported selector take the working ones down with it.

### When a selector goes stale

YouTube renames its custom elements every so often. When a tab or shelf reappears:

1. Connect the phone by USB with USB debugging on.
2. Open `chrome://inspect` in desktop Chrome, find the WebView, click **inspect**.
3. Find the element, note its tag or a stable attribute.
4. Add it to the matching list in `PageCosmetics.kt`. Nothing else changes.

Keep new selectors narrow. An over-broad rule blanks out parts of the page you wanted, which
is much harder to notice than a shelf that failed to disappear.

---

## Tests

77 JVM cases, no emulator, no Robolectric:

| File | Covers |
|---|---|
| `UrlClassifierTest` | 24 — the plan's matrix, root variants, `/shortsomething`, look-alike hosts, `about:blank`, unparseable URLs, casing. |
| `PageCosmeticsTest` | 13 — conditional inclusion, un-hiding, try/catch, idempotency, quoting, rule isolation, scope. |
| `CooldownPolicyTest` | 12 — attempt countdown, lockout trigger, counter reset, expiry, clock wound forward or back. |
| `BlockPolicyTest` | 11 — every (type, flag, unlock) combination, both independence rules, and a guard that the shipped flags really gate both sections. |
| `PinHasherTest` | 11 — salt sensitivity, iteration sensitivity, near misses, hash size, long PINs, one round-trip at the production iteration count. |
| `SessionStateTest` | 6 — unlock isolation and reset. |

```bash
./gradlew :app:testDebugUnitTest
```

---

## What has actually been verified

| | Status |
|---|---|
| All 9 Kotlin sources type-check | ✅ clean, **zero warnings** (kotlinc 2.4.20) |
| All 77 unit tests compiled and executed | ✅ **77/77 pass** |
| Injected JavaScript parses | ✅ all 4 variants pass `node --check` |
| Injected CSS behaves | ✅ **18/18** in real Chromium against a mock YouTube DOM — every rule survives the parser, `:has()` works, nothing unrelated is hidden, injection is idempotent, un-hiding works, and it survives a DOM re-render |
| PBKDF2 and the cooldown arithmetic | ✅ verified against the real JCA provider, every clock case included |
| XML and resource references | ✅ 48/48 resolve, nothing unused |
| **A real Gradle/AGP build** | ❌ **never run** |
| **Anything on a phone** | ❌ **never run** |

The type-check used hand-written stubs for the Android framework rather than the real
`android.jar`, and AAPT never processed the resources. So it is not a substitute for a real
build — a wrong assumption about an Android signature could still bite. What it does rule out
is the whole class of "a typo wastes your first build cycle".

---

## First device run

This is the order worth checking.

**Does it build at all.** Kotlin-level problems are unlikely now (see above); the remaining
risk is Gradle, AGP and AAPT. If the `androidx.security:security-crypto:1.1.0-alpha06`
dependency causes trouble, that is the first thing to look at — it is the only alpha in the
project.

**Setup.** First launch should demand a PIN and refuse to move on. Back should leave the app.
Three digits should be rejected; a mismatched confirmation should restart the flow.

**The core question this whole design rests on:** tap Home in the bottom nav. Does the block
wall fire? If YouTube reaches Home by `pushState`, `doUpdateVisitedHistory` catches it; if by
a real navigation, `shouldOverrideUrlLoading` does. Both are wired, but only a device says
which is happening. Same for tapping a Short.

**Cosmetics.** Are the Home and Shorts icons actually gone from the bottom nav? Do Shorts
shelves still appear in search results? If yes, the selectors need the `chrome://inspect`
treatment above — the block wall is unaffected either way.

**Lockout.** Five wrong PINs, then force-stop the app, relaunch, and tap Home. It should still
be locked out for the remainder.

**Session scope.** Unlock Home, kill the app from recents, relaunch. Home locked again, PIN
*not* asked for again.

Bump `versionName` to `1.0` once it has survived all of that.

### Full test matrix

| Step | Expected |
|---|---|
| First launch ever | "Set your PIN", not skippable. Back leaves the app. |
| Type 3 digits | "Use at least 4 digits." |
| Confirm with a different PIN | "Those did not match. Start again." |
| Confirm correctly | "PIN set", lands on the search screen. |
| Cold start (PIN already set) | Search screen. No feed, no network hit to the Home feed. |
| Search a term | Results load normally, **with no Shorts shelf**. |
| Open a video | Plays. Sidebar recommendations untouched. |
| Look at the bottom nav | **Home and Shorts icons are gone. Subscriptions and Library remain.** |
| Reach Home some other way (deep link, redirect) | PIN overlay, feed never renders. |
| Wrong PIN | Short pause, then "4 tries left". Overlay stays put. |
| Five wrong PINs | "Wait 60 s", counting down, input disabled. |
| Force-stop during lockout, relaunch | Still locked out for the remainder. |
| Correct PIN | Short pause, then the Home feed loads — **and the Home icon reappears in the nav.** |
| "Go back" while a video was playing | Overlay closes, your video is still there — nothing reloads. |
| "Go back" after an SPA jump to Home | You land back on the last allowed page. |
| Unlock Shorts, then reach Home | Home still prompts — the two unlocks are independent. |
| Unlock Home, then reach a Short | Shorts still prompts. |
| Unlock Shorts, then swipe between Shorts | Allowed — each swipe is a new `/shorts/<id>`, same unlock. |
| Rotate the screen | Page survives, unlock survives. |
| Kill the app from recents, relaunch | Search screen, both sections locked again, PIN not re-asked. |

To test that the unlock survives *Activity recreation* specifically, turn on **Don't keep
activities** in Developer Options. The Activity sets `configChanges` for orientation, so plain
rotation does not recreate it — that keeps the WebView from reloading your page every time you
turn the phone.

---

## Known limitations

- **Clearing app data resets the PIN.** See above.
- **Phase 4 selectors can go stale** when YouTube renames its elements. Cosmetic only; fix
  per the guide above.
- **No "change PIN" screen.** Clear data and set a new one.
- **No fullscreen video.** A bare `WebChromeClient` has no `onShowCustomView`, so
  landscape-fullscreen does nothing. Small addition when you want it.
- **No direct video-URL entry** on the landing screen. Deferred post-MVP in the plan.
- **WebView state is not saved across process death** — by design.
- **Audio keeps playing behind the PIN overlay.**
- **Not Play Store ready.** Personal sideload only.

---

## Structure

```
deliberate/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/mazhar/deliberate/
│       │   │   ├── UrlClassifier.kt        # what a URL is
│       │   │   ├── BlockPolicy.kt          # whether it is denied
│       │   │   ├── SessionState.kt         # process-scoped unlocks + Features
│       │   │   ├── PinHasher.kt            # PBKDF2-HMAC-SHA256
│       │   │   ├── CooldownPolicy.kt       # lockout arithmetic
│       │   │   ├── PageCosmetics.kt        # the injected CSS
│       │   │   ├── PinManager.kt           # encrypted storage + wiring
│       │   │   ├── YouTubeWebViewClient.kt # the block wall
│       │   │   └── MainActivity.kt         # landing + webview + overlay
│       │   └── res/
│       └── test/java/com/mazhar/deliberate/   # 77 cases across 6 files
├── .github/workflows/build.yml             # builds the APK for you
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

**Two structural differences from the plan:** the search screen is a plain view inside
`MainActivity` rather than a `SearchLandingFragment`, and first-run setup is a mode on the PIN
overlay rather than a separate `PinSetupActivity`. For a one-screen app both would add a
container, a transaction and a callback interface for no behavioural gain.

---

Package `com.mazhar.deliberate` · v0.4-phase4 · minSdk 26 · targetSdk 35 · AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.9
