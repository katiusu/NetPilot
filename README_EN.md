<div align="center">

# NetPilot

**5G shows full bars but nothing loads? Let it drop back to 4G automatically.**

Per-SIM policies · Wi-Fi rule book · Keep-alive · Tasker integration · Root first, Shizuku fallback

[中文说明](README.md) · [Testing guide](docs/TESTING.md) · [Code provenance](docs/PROVENANCE.md)

</div>

---

## What it is

You are standing in one spot. The 5G icon shows full bars, but the connection behaves like it is dead — switch to 4G manually and everything works again. This is a **"fake full bar"**: the *strength* is fine but the *quality* is not (SINR has gone negative, ping times out).

NetPilot automates both the judgement and the switch:

1. Periodically samples RSRP / SINR / ping;
2. On a **fake-full-bar** or **very weak signal** hit, downgrades the current default data SIM's network mode to 4G;
3. After a cooldown, if things stay healthy for several consecutive rounds, restores 5G;
4. Writing the network mode needs privilege: **root first** (`su` + `app_process` calling the hidden API), with **Shizuku** as fallback, then `settings put global preferred_network_mode[1]` as a last resort.

It is also a general dual-SIM network manager: manual mode switching, two Quick Settings tiles, independent per-SIM policies, automatic default-data-SIM switching based on Wi-Fi SSID/BSSID rules, a runtime log page, and a Tasker/Locale automation interface.

| | |
|---|---|
| Package | `com.katiusu.netpilot` |
| Version | **1.0.1** (`versionCode 2026100401`) |
| Requires | Android 14+ (minSdk 34 / targetSdk 34) |
| UI | Jetpack Compose + [Miuix](https://github.com/YuKongA/Miuix) 0.9.4 |
| Languages | Simplified Chinese / English |
| License | [GPL-3.0](LICENSE) |

> **Install**: grab the APK from [Releases](../../releases). It is signed with the **Android debug key** — fine for personal use, not for redistribution.

---

## Features

### Auto-downgrade on poor network quality

Two **independent** rules, and every threshold is adjustable in the app (the monitor page always renders the *effective* values, never a hardcoded string):

```
Rule 1 — "fake full bar":
  ① RSRP >= strong threshold (default -85 dBm)     ← looks strong
  ② AND any of:
       ping  > ping threshold (default 200 ms)
       SINR  < SINR threshold (default 0 dB)
       ping  fails entirely (separate switch, off by default)
  → downgrade

Rule 2 — "very weak signal" (separate switch, on by default):
  RSRP readable AND < weak threshold (default -110 dBm)
  → downgrade (strength only, ignores ping/SINR)
```

State machine: after a downgrade it enters a cooldown (default 300 s); once the cooldown ends, **3 consecutive** healthy rounds restore the original mode; **2 consecutive** no-network rounds roll back immediately and leave auto mode; the sampling interval defaults to 120 s.

Ping is measured as **HTTP time-to-first-byte** against `http://www.bing.com/` by default, falling back to `cn.bing.com` → Baidu → vendor connectivity-check endpoints, and the UI shows the **exact failure reason**.

### Manual mode switching + Quick Settings tiles

34 network modes (`NETWORK_MODE_*`) with one-tap presets. Two QS tiles: cycle network mode, and toggle auto-downgrade.

### Per-SIM policies

Each SIM gets a card with its own master switch and a mode dropdown: `Follow system` / `Network quality auto-downgrade` / `Downgrade to 4G on Wi-Fi` / `Custom` (the first two as independent toggles). These policies are genuinely enforced, not just UI state — the monitor engine is a single global instance acting on the *current default data SIM*, and it asks the data-card layer (`qualityDowngradeAllowed(subId)`) whether that SIM is allowed to downgrade. The dependency is inverted through `core/NetPilotEvents.kt` so the two packages never form a cycle.

### Wi-Fi rules

Switch the **default data SIM** based on SSID / BSSID. Match priority: **exact BSSID > case-insensitive SSID > empty SSID = any Wi-Fi**. Each rule carries its target SIM (or "any"), a priority, a cooldown (default 120 s) and a "revert when leaving Wi-Fi" flag.

### First-launch guide

Two explanation dialogs before any system permission dialog: one lists every permission **with the reason it is needed**, and one explains autostart + battery optimization with a "Go to settings" button (left "Cancel" / right "Go to settings"), targeting the MIUI/HyperOS autostart page first with a fallback to the app details page.

### Keep-alive + a master service switch

Three layers: `START_STICKY` → `onTaskRemoved` re-arm after 10 s via `AlarmManager` → an inexact 15-minute heartbeat. A master switch in Settings stops everything at once (foreground monitor, heartbeat, restart-on-kill, boot autostart).

### System compatibility: measured, never guessed

The app deliberately contains **no vendor-specific branches**. It writes `settings put global preferred_network_mode[1]` (not `nr_sa_mode`), so the assumptions behind those branches do not hold here. Instead, Settings shows real probes: the active privileged channel, a real `getmode` execution, the exact command string built from the live subId/slot and `context.packageCodePath`, and a four-state result (`OK` / `FAILED` / `SKIPPED` / `NO_TARGET`). Write-back verification (`settings get` after `settings put`) is on by default and can be disabled for devices whose read-back is unreliable.

### Automation

Broadcast commands `com.katiusu.netpilot.action.*` and events `com.katiusu.netpilot.event.*`, plus a Locale plugin for Tasker. See [`docs/TASKER.md`](docs/TASKER.md).

---

### App icon & runtime memory (1.0.1)

- **Adaptive icon**: a black background layer, a white "signal bars + plus" foreground layer, and a
  **monochrome layer** (used by Android 13+ themed icons). `minSdk 34`, so there is a single
  `mipmap-anydpi-v26` XML plus five density PNGs, and a `roundIcon` is provided too.
- **Reaping orphaned Shizuku user-service processes**: the Shizuku user service is a separate
  root/shell process named `<package>:np_service`. When Android kills the app, it never gets to run
  `unbindUserService(remove = true)`, so the process survives as an orphan — measured on one device:
  **4 processes, ~180 MB**. After a successful bind, NetPilot scans `/proc` once per app process and
  kills every `:np_service` process of this package that is not itself. Failures are ignored: this
  only saves memory and can never break the privileged channel.
- **Cheaper log persistence**: 400 entries stay in memory, but only the newest 120 are written to
  disk and a single message is capped at 2000 characters. Previously a 40+ KB JSON string was built
  every 4 seconds — the steadiest allocation source in the app.
- **About page**: the version string is memoised instead of calling `PackageManager` on every
  recomposition.

## Privacy

NetPilot **uploads nothing**. Its only network request is the ping probe. It does not read contacts, SMS, or the photo library, and it does not collect location — `ACCESS_FINE_LOCATION` is requested only because Android 10+ classifies cellular signal strength (including SINR) as location data.

---

## Build

```bash
git clone https://github.com/katiusu/NetPilot.git
cd NetPilot
bash _build.sh :app:assembleDebug     # output: app/build/outputs/apk/debug/app-debug.apk
```

A standard Android Gradle project (Kotlin 2.2 + AGP 8.x + Compose) — open it in Android Studio if you prefer. `minSdk 34`.

<details>
<summary>Build environment notes (low-memory container / restricted network)</summary>

1. Project-level `org.gradle.jvmargs` in `gradle.properties` is ignored by Gradle — the JVM starts before the project config is read. `_build.sh` therefore passes `-Dorg.gradle.jvmargs` on the command line and sets `kotlin.compiler.execution.strategy=in-process`.
2. If `dl.google.com` / `repo.maven.apache.org` are unreachable, an `init.gradle` can redirect repositories to a mirror.
3. Check `_build.sh`'s `=== EXIT=…` line for success; **do not pipe the script into `tail`**, that swallows the exit code.

</details>

---

## Known limitations

**This APK compiles, its components are complete, its signature is valid and its dependency graph is cycle-free — but not a single on-device privileged write has been verified.** The development device (Redmi K40 / HyperOS / Android 15) blocks `app_process`, `su`, `settings put` and `dumpsys telephony.registry` by device policy, so no end-to-end test was possible.

Still unverified: whether `app_process` really runs under HyperOS; the Shizuku UserService binding; the Tasker/Locale protocol constants; actual QS tile rendering; and whether SINR is readable on a given modem (the code falls back from `signalStrength` to `allCellInfo` and reports structured failure reasons). Disabling carrier aggregation is out of scope — a limitation shared by every non-root approach.

---

## License

[GPL-3.0](LICENSE). Provenance per file: [`docs/PROVENANCE.md`](docs/PROVENANCE.md). The GPL-3.0 obligation comes from the three files ported from [NetworkSwitch](https://github.com/aunchagaonkar/NetworkSwitch); everything else is MIT/Apache-2.0-derived or original.
