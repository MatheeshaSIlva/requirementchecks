package dev.spike.launcherprobe;

import dev.spike.launcherprobe.IGestureCallback;
import android.graphics.Bitmap;

interface IProbeService {
    // Reserved by Shizuku: called when the service is unbound/removed.
    void destroy() = 16777114;

    String identity() = 1;

    // Test 1a: binder call into StatusBarManagerService. Flags die with this service process.
    String statusBarDisable(int what1, int what2, int autoRestoreSec) = 2;
    // Clears binder flags AND runs `cmd statusbar send-disable-flag none`.
    String statusBarRestore() = 3;
    // Test 1b: `cmd statusbar send-disable-flag ...`. Flags may outlive this process.
    String statusBarCmd(String flagNames, int autoRestoreSec) = 4;

    // Test 2: recent task list + snapshot probe.
    String probeRecents(int max) = 5;

    // Test 3: gesture input monitor. mode 0 = observe, 1 = pilfer on detected top/bottom swipe,
    // 2 = pilfer on first MOVE after a top/bottom DOWN.
    String startGestureMonitor(int width, int height, float density, int mode, int autoStopSec) = 6;
    String stopGestureMonitor() = 7;
    String drainGestureLog() = 8;

    // Utility: run a shell command as the Shizuku user (8 s timeout).
    String runShell(String command) = 9;

    // v2: raw touch reader (/dev/input/eventN) with bottom-edge swipe/hold detection.
    String startTouchReader(String devicePath, int width, int height, float density, IGestureCallback callback, int autoStopSec) = 10;
    String stopTouchReader() = 11;
    String listInputDevices() = 12;
    // v2: list methods of a hidden framework interface whose names match a regex.
    String dumpMethods(String ifaceName, String regex) = 13;
    // v2: start a shell command and return immediately (no output, no waiting).
    String runDetached(String command) = 14;

    // v3 requirement tests. Each returns a human-readable PASS/FAIL line (or several).
    // Recents thumbnails: try IWindowManager.snapshotTaskForRecents + IActivityTaskManager.getTaskSnapshot.
    String testThumbnails(int max) = 20;
    // Switch to a task by id via startActivityFromRecents (0 = use the least-recent listed task).
    String testSwitchToTask(int taskId) = 21;
    // Remove a task by id via IActivityTaskManager.removeTask (0 = skip actual removal, just check the call resolves).
    String testRemoveTask(int taskId) = 22;
    // Launch home via HOME intent from the shell uid.
    String testGoHome() = 23;
    // Quick-settings radio + system toggles via svc/settings/cmd. action in
    // {wifi-on,wifi-off,bt-on,bt-off,data-on,data-off,dnd-on,dnd-off,bright-min,bright-max}.
    String testToggle(String action) = 24;
    // Whether this uid can hold WRITE_SECURE_SETTINGS and self-grant it.
    String testSecureSettings() = 25;
    // Current default launcher / home role holder.
    String testDefaultHome() = 26;
    // Run every non-destructive requirement test and return one combined report.
    String runAllTests() = 27;

    // v5 animation feasibility.
    // Snapshot of a task as an ARGB_8888 software bitmap (taskId 0 = most recent task that is not this app). Null on failure.
    Bitmap snapshotTask(int taskId) = 30;
    // Times snapshotTaskForRecents (and the hardware->software copy) over the recent tasks; returns a stats report.
    String benchSnapshots(int repeats) = 31;

    // v5.2: background-app snapshots. mode 1 = getTaskSnapshot cached, 2 = low-res cached, 3 = takeTaskSnapshot.
    int[] listTaskIds(int max) = 32;
    Bitmap snapshotBuffer(int taskId, int mode) = 33;
    // Per recent task: which snapshot APIs return an image (live vs cached vs take), with timings.
    String snapshotMatrix(int max) = 34;

    // v6.1: add a window of the given WindowManager type from THIS (shell uid) process for `seconds` seconds.
    // Used to find out whether a shell-owned window can sit above the stock status bar.
    String shellWindowTest(int type, int seconds) = 40;
    String downloadFile(String url, String dest) = 41;
}
