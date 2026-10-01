# LauncherProbe

A throwaway spike that answers one question: **can a Shizuku-privileged app (no root) take over the
shade, quick settings and recents gesture on this phone?** It has no UI polish on purpose. Each button
runs one experiment and prints what happened, including the exact exception if it failed.

## Setup

1. Install **Shizuku** and start it with wireless debugging (Developer options -> Wireless debugging ->
   pair, then "Start" in Shizuku). Restart it after every reboot.
2. Open this folder in Android Studio (it will fetch Gradle 8.9 and the SDK bits) and Run on the phone.
   If Studio offers to update AGP/Kotlin, accepting is fine.
3. In the app: **Request Shizuku permission** -> **Connect service** -> **Identity**.
   Expect `uid=2000` (shell). If you see another uid, note it.

## Safety

- Tests 1a/1b/3 auto-undo after the number of seconds shown (15 by default; monitor stops after 25 s).
- If the shade or recents are stuck blocked: open the app -> Connect service -> **Restore now**.
  If you can't: force-stop LauncherProbe (kills binder-based blocks), or reboot (clears everything).
- Test 3 mode 2 can swallow the swipe-home gesture from the bottom edge for up to 25 s. Have the
  3-button/back route in mind, or just wait for auto-stop.

## Experiments, in order of importance

| # | Button | What to do | What it tells us |
|---|--------|-----------|------------------|
| 1a | Block shade + QS | Go home, then open another app. Pull down from the top. | Whether `disable`/`disable2` work from shell, on home *and* in other apps |
| 1a | Block recents | Try the recents gesture (swipe up + hold) and the button if you have one | Whether the stock overview really stops opening under gesture nav |
| 1b | cmd: block ... | Same checks, then **Kill service now** and check again | Whether cmd-set blocks survive the service dying (would remove the "flags vanish with the process" problem) |
| 2 | List recents | Open a few apps first | Whether shell can read the task list and get thumbnails (`snapshot OK WxH`) |
| 3 | Monitor: observe | Swipe from all four edges, in other apps | Whether a shell-launched process can see system-wide touches at all |
| 3 | Monitor: pilfer... | Swipe up from bottom in another app | The key one: does pilfering stop the stock recents/home gesture? Look for `CANCEL` in the log and whether Overview still opens |
| 4 | Force 3-button | Read mode, force 3-button, check the nav bar changed, then go back to gesture | Fallback route if pilfering can't beat the system's monitor |

## What to send back

Tap **Copy log to clipboard** and paste it, plus:

- Phone model, Android version, and OEM skin (Samsung One UI, Xiaomi HyperOS, Pixel, etc.)
- For 1a/1b: did the stock shade / QS / recents actually stop opening (home screen vs inside another app)?
- For 3: in pilfer modes, did the stock gesture still fire?

## Known limits of this probe

- Written against hidden APIs from memory and **not compiled or run by the author**; method
  signatures differ across Android versions. Failures print the real exception so the next iteration
  can adapt.
- Portrait only. Top/bottom/side detection uses a 40 dp edge band and a 100 dp swipe threshold.

## v2 additions (sections 6 and 7 in the app)

Findings from round 1 that shaped v2: blocking the shade/QS/recents works from uid 2000; `cmd`-set blocks
survive the service dying; the task list works; `getTaskSnapshot` does not exist on this build;
the touch input monitor is denied (`MONITOR_INPUT`); raw `/dev/input` is readable by the shell user;
the `home` disable flag hides the gesture pill but does NOT stop the swipe-home gesture.

So v2 tests the replacement plan: keep the system home gesture, block stock recents, detect the
"swipe up and hold" yourself from raw touch events, and show your own UI on top.

1. **Grant overlay permission (shell appops)**.
2. Set the auto-restore seconds (section 1a) to 45 and tap **Block recents**.
3. Set the device path (default `/dev/input/event2`; use **List input devices** if your touches are on another
   one, i.e. the log stays empty), then **Start reader: log + overlay on HOLD** (runs 40 s).
4. Switch to another app. Swipe up from the bottom edge quickly, then swipe up and hold ~1 s.
5. Come back and copy the log. Report: did HOLD fire, did the overlay appear, did it stay on screen
   after you released (the system will still go home on release), and how long after the finger stopped
   it appeared.
6. Separately, **Dump snapshot/thumbnail/capture methods** and paste the result. It shows what the
   real snapshot API is called on your Android version.

Thresholds (in `ProbeService.kt`): `HOLD_MS = 300`, `HOLD_TRAVEL = 0.10` of screen height, bottom band = 6%.

## v3: full requirement suite (sections ★, 8, 9)

Verified against a real android.jar and type-checked with kotlinc before shipping (the v2 runDetached
had a `Redirect.DISCARD` bug that doesn't exist on Android; fixed).

The design's requirements and how v3 tests each:
- **Suppress stock shade/QS/recents/home** — sections 1a/1b (confirmed working on the emulator).
- **Custom recents needs thumbnails** — §8 "Recents thumbnails": tries `IWindowManager.snapshotTaskForRecents(id)`
  (returns a Bitmap) and `IActivityTaskManager.getTaskSnapshot(id,false)` (real API on this build; the old
  `getTaskSnapshot` guess was wrong because it moved).
- **Switch between apps** — §8 "Switch to least-recent task" via `startActivityFromRecents`.
- **Close a task** — §8 "Remove-task resolves" (non-destructive check; pass a real id to actually remove).
- **Own quick-settings toggles** — §8b wifi/bt/data/dnd/brightness via `svc`/`settings`/`cmd`.
- **Self-enable accessibility etc.** — §8 "Self-grant WRITE_SECURE_SETTINGS".
- **Be the home app** — §8 "Default launcher / home role".
- **Themed surfaces with blur** — §9 "RenderEffect blur available?" (app process, API 31+).
- **Re-render notifications** — §9 "Notification listener bound?" (a real NotificationListenerService is
  now in the manifest; grant with `cmd notification allow_listener <component>`).

**One-tap:** the ★ section at the very top has **Auto-setup + Run all tests** — it grants, connects, and
runs every non-destructive test into the log. Open 3-4 apps first. Then Copy log to clipboard.
The swipe/hold and overlay tests (section 6) still need a real gesture.

## v3 additions — full requirement suite (sections 8 and 9, plus the ★ suite at the top)

v3 turns the probe into a checklist for every system requirement the launcher idea needs, so most of
it runs from taps alone. NOT compiled by the author — build it in Android Studio and paste any red
build errors back; they'll be quick to fix.

**One-tap path:** open a few apps, then tap **★ Auto-setup + Run all tests** at the very top. It grants
Shizuku (approve the dialog and tap again), binds the service, and runs the whole non-destructive
suite into the log. Copy the log and send it.

What the suite covers, and what each result means for the app:
- **recents list / thumbnails** — can the launcher show a recents view with live previews?
  `snapshotTaskForRecents` returning `OK WxH` is the win; `null`/permission error means fall back to
  capturing thumbnails yourself as apps go to background.
- **default home / home role** — what owns HOME now, and can the launcher become it.
- **secure settings** — whether uid 2000 can self-grant `WRITE_SECURE_SETTINGS` (needed to flip your
  own accessibility/settings without the manual toggle).
- **wifi / bluetooth / dnd toggles** — the quick-settings switches your panel needs. `PASS` = the
  `svc`/`cmd` call went through. (data toggle usually shows CHECK on an emulator — no radio.)
- **remove-task resolves** — whether the launcher could add a swipe-to-close in its recents.

**Individual buttons (section 8)** run each of the above on their own, plus **Switch to least-recent
task** (proves you can foreground an app from your recents) and **Go home (shell intent)**.

**Section 9 needs no Shizuku:**
- **RenderEffect blur** — confirms the theme engine's blur works on this API level (31+).
- **Notification listener bound** — whether a `NotificationListenerService` is granted, which is how
  your shade replacement would read notifications. Grant it with the printed
  `cmd notification allow_listener …` line, then tap again.

Still requires a real gesture (section 6): the bottom-edge swipe-vs-hold and the overlay-on-hold test.

### Known things to watch when it builds
- `startActivityFromRecents` is matched by name only; if a build has overloads it may pick the wrong
  one and report a failure — harmless for a probe.
- `data` toggle and `cmd shortcut get-default-launcher` don't exist on every build; both degrade to a
  CHECK/fallback line rather than crashing.

## v4 — touch reader rewritten to use getevent (fixes the silent log on the S24)

Real-hardware result (Galaxy S24, One UI 8.5): the whole requirement suite passed; the gesture
input-monitor is denied as expected; and the raw touch reader logged nothing. Cause: v2/v3 opened a
hardcoded `/dev/input/event2`, which was the touchscreen on the emulator but not on the S24 (Samsung's
touch driver sits on a different node).

v4 stops guessing the node. The reader now:
- runs `getevent -lp` to enumerate every input device and its axis maxima, and auto-picks the
  touchscreen (the device exposing `ABS_MT_POSITION_X/Y`, largest Y range),
- streams `getevent -l` and parses the chosen device's events, handling both `BTN_TOUCH` and
  tracking-id-only drivers for finger down/up,
- scales coordinates by that device's real max, so the bottom-edge/hold thresholds are correct.

The device box in section 6 now defaults to `auto`; leave it. The "started via getevent on
/dev/input/eventN (\"name\", axis max WxH …)" line confirms it locked onto the real touchscreen — if
that name looks like your Samsung touch device and you still get no DOWN/HOLD lines while swiping,
that's the finding to report. (Not compiled here; build and paste any errors.)

## v5 — animation feasibility (section 10)

Requirement testing for the final app made smooth app open/close animations with finger-tracking gestures
the top priority. Two routes could deliver that, and section 10 measures both:

- **System route** (like Pixel/iOS): the launcher drives the system's app transitions itself. 10a lists which
  transition/task/window permissions the shell user actually holds and their protection levels; 10b dumps the
  remote-animation and remote-transition APIs that exist on this Android version.
- **Overlay route** (our own process draws the animation): 10c times `snapshotTaskForRecents` (how fast a real
  app image is available); 10d/10e animate a full-screen card toward an icon position at display refresh and
  report frame times (median, p95, p99, worst, dropped frames); 10f/10g do the same with a real app snapshot,
  including fetch time. "Heavy" adds six blurred full-screen layers as a worst case.

Setup for 10d-10g: tap "Grant overlay permission (shell appops)" in section 6 once. For 10c/10f/10g open 3+ apps
first. Copy the log after each. Not compiled here; paste any build errors from Studio.

### v5.1
- Fixed a likely service crash: v5.0 copied the hardware snapshot bitmap to a software bitmap inside the shell
  process. Snapshots are now returned as hardware bitmaps across Binder.
- Every service call now logs `[name] running...` first, so a test that kills the service shows as a
  `running...` line with no result.
- New button 10b2 dumps the recents-animation API (the route real launchers use for finger-tracking gestures).

### v5.2 — what v5.1's S24 log taught us, and the two new tests
Findings so far (Galaxy S24, Android 16):
- The shell user holds CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS and MANAGE_ACTIVITY_TASKS, but the stock recents
  animation API (`startRecentsActivity`, `IRecentsAnimationRunner`) no longer exists on Android 16 (it moved into
  SystemUI), so finger-tracking has to be drawn by our own overlay.
- Overlay animation is excellent: 120 fps, 0 dropped frames plain; 1 dropped frame in 49 with six blurred layers.
- `snapshotTaskForRecents` only works for the VISIBLE task. Background tasks returned null.

New in v5.2 (all in section 10):
- **10h Snapshot matrix**: for every recent task, tries live (A), cached (B), low-res cached (L) and take (C)
  snapshot paths and shows which return an image. Open 3+ apps first, then run it from this app.
- **10f/10g** now try every path on every background task and say which one worked.
- **10i** blocks home + recents + back + shade + QS via `cmd` for 60 s (auto-restores). **10k** restores at once.
- **10j Bottom-strip gesture prototype**: a thin touchable strip at the bottom receives the finger directly, a card
  follows it, and a release flies it home or springs back. Run 10i first, then 10j, go to another app, swipe up on
  the faint white bar. The log reports frame pacing, touch-to-frame latency, and whether the system home gesture
  was really suppressed (if the stock home animation also plays under the card, it was not).
Safety: everything auto-restores (60 s block, 90 s strip). If stuck: 10k, or force-stop LauncherProbe, or reboot.
