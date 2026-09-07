# Experimental direct-root restart repair

## Failure addressed

A read-only probe on an affected Pixel 11 Pro XL found the recorded root helper PID already absent,
an unchanged on-disk heartbeat, and the original duplicate-process shell scan still running at its
30-second limit (30.060 s, exit 124). Version 1.0.11 allows only nine seconds for the entire shutdown
command. Archiving the old heartbeat allows a first launch, but the next restart re-enters that scan.

This patch replaces only the global fork-per-PID discovery loop with a single Java process that reads
proc directly. Exact PID/owner/instance validation and cooperative TERM remain in RootCommand. The
scanner refuses replacement when another AdbHelper remains or an app_process identity cannot be
resolved. It does not drive LEDs, send signals, or write files. The existing singleton lock and
release/cleanup/replay checks are unchanged. The shutdown budget is 20 seconds, including the existing
6.5-second exit wait, VM startup, and the scanner's own five-second watchdog.

Root stages now log under `HiLightRoot`, including timing, exit code, and the scanner's short summary.
The full shell command and other apps' argv are not logged. A failure of the scanner remains a failure
of the stop command; it must never be followed by an unconditional success exit.

This is a targeted repair for the measured scan timeout, not a claim to fix every root-manager,
Sui/Shizuku, PID-reuse, or helper-lifetime issue. It does not add a boot service or automatic heartbeat
recovery, and it does not weaken lighting safety limits.

## Automated checks

The dependency-free scanner harness can be run with JDK 17 or newer:

```sh
mkdir -p /tmp/hilight-scanner-tests
javac --release 17 -d /tmp/hilight-scanner-tests \
  core/src/com/hilight/core/RendererProcessScanner.java \
  app/src/test/java/com/hilight/core/RendererProcessScannerSelfTest.java
java -cp /tmp/hilight-scanner-tests com.hilight.core.RendererProcessScannerSelfTest
```

It covers 16 cases including root/ADB helpers, exact NUL-delimited tokens, misleading shell command
text, numeric PID filtering, kernel threads/exited processes, unreadable app_process identities,
deleted executable suffixes, truncated argv, enumeration failures, and a 3,000-entry synthetic tree.
The JUnit suite invokes the same harness. RootCommand tests also execute the absent-PID path with
fake pm/app_process binaries and verify exit-code propagation for success, rejection, and timeout.

For the complete Android project, use the prerequisites in CONTRIBUTING.md and run:

```sh
./gradlew :app:testDebugUnitTest :app:build :app:lint
./scripts/build-helper.sh
```

CI also checks that R8 retained the new `com.hilight.core.RendererProcessScanner` entry point. A local
JVM/shell test is not a substitute for the full Android build or testing on the affected phone.

## Device validation before merging

Use a test build and verify a short preview, then force-stop/reopen several times and test again after
one phone reboot. Do not archive helper_status.json between repetitions: recovery with the retained
record is the behavior under test. Expect a fresh heartbeat and a connected root renderer after
startup finishes. Capture `HiLightRoot`/`HiLightStore` logcat and a fresh Copy LED diagnostics export
on any failed repetition. Check that every physical LED also turns fully off after each short test.

A debug APK is signed with a debug certificate, not the upstream permanent release certificate. It
cannot be installed as an in-place update over that release without matching signing credentials.
Do not bypass signature checks, uninstall the release blindly, or assume presets export includes all
rules/settings. Arrange an appropriate settings backup/migration before replacing the installed app.
CI debug artifacts are experimental and may use a different debug certificate on another clean run.

Do not delete the renderer lock, blindly kill a remembered PID, mark an expired heartbeat alive, or
skip the duplicate scan to make the UI appear connected. Report any scanner rejection instead.
