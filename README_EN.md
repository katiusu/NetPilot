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
| Version | **1.5.2** (`versionCode 2026100600`) |
| Requires | Android 14+ (minSdk 34 / targetSdk 36) |
| UI | Jetpack Compose + [Miuix](https://github.com/YuKongA/Miuix) 0.9.4 |
| Languages | Simplified Chinese / English |
| License | [Apache-2.0](LICENSE) |

> **Install**: grab the APK from [Releases](../../releases). Release builds are signed with the project's own release key (SHA-256 `34100875…4c4c`, the same key as 1.0.1/1.1.0, so upgrades install over each other); `debug` builds use the Android debug key and cannot overwrite a release install.

---

## Features

### Auto-downgrade on poor network quality

Two **independent** rules, and every threshold is adjustable in the app (the monitor page always renders the *effective* values, never a hardcoded string):

```
Rule 1 — "fake full bar" (5G / 5G+ only since 1.4.0):
  ⓪ camped on 5G (NR reported) or 5G+ (LTE reported but NR cells visible)  ← new gate
  ① RSRP >= strong threshold (default -85 dBm)     ← looks strong
  ② AND any of:
       ping  > ping threshold (default 300 ms)
       SINR  < SINR threshold (default 0 dB)
       ping  fails entirely (separate switch, off by default)
  → downgrade

Rule 2 — "very weak signal" (separate switch, on by default):
  RSRP readable AND < weak threshold (default -110 dBm)
  → downgrade (strength only, ignores ping/SINR)
```

State machine: after a downgrade it enters a cooldown (default 120 s, adjustable down to 30 s); once the cooldown ends, **2 consecutive** healthy rounds restore the original mode; **2 consecutive** no-network rounds roll back immediately and leave auto mode; the sampling interval defaults to 60 s.

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

Three layers: `START_STICKY` → `onTaskRemoved` re-arm via `AlarmManager` → an inexact 15-minute heartbeat. A master switch in Settings stops everything at once (foreground monitor, heartbeat, restart-on-kill, boot autostart).

Since 1.2.0 the re-arm delay **backs off exponentially**: the normal case (the service had already been running for 2+ minutes) still comes back in **10 seconds**, exactly as before; only a service that keeps dying within 2 minutes of starting is delayed 10 s → 20 s → 40 s → … up to a 15-minute cap, so a crash loop no longer becomes a restart storm. The heartbeat also **no longer re-registers the repeating alarm** on every tick — `setInexactRepeating` is self-renewing, and re-arming only reset the alarm phase and disturbed Doze batching while paying for a binder call.

### System compatibility: measured, never guessed

The app deliberately contains **no vendor-specific branches**. It writes `settings put global preferred_network_mode[1]` (not `nr_sa_mode`), so the assumptions behind those branches do not hold here. Instead, Settings shows real probes: the active privileged channel, a real `getmode` execution, the exact command string built from the live subId/slot and `context.packageCodePath`, and a four-state result (`OK` / `FAILED` / `SKIPPED` / `NO_TARGET`). Write-back verification (`settings get` after `settings put`) is on by default and can be disabled for devices whose read-back is unreliable.

### Automation

Broadcast commands `com.katiusu.netpilot.action.*` and events `com.katiusu.netpilot.event.*`, plus a Locale plugin for Tasker. See [`docs/TASKER.md`](docs/TASKER.md). **Off by default since 1.3.0** — the command receiver, the Locale plugin and its config screen are disabled at the system level until you turn on Settings → Features → "Enable Tasker / Locale interface", so Tasker broadcasts cannot wake the app at all. Users upgrading from 1.2.0 must turn it on once.

---

### Version history

| Version | versionCode | Highlights |
|---|---|---|
| 1.0.0 | 2026100400 | First public release: auto-downgrade, per-SIM policies, Quick Settings tile, Tasker interface. |
| 1.0.1 | 2026100401 | Adaptive icon; orphaned Shizuku service processes reclaimed; smaller log persistence. |
| 1.1.0 | 2026100500 | Licence changed to Apache-2.0 (`TelephonyReflection.kt` rewritten clean-room); default values revised (downgrade on, 60 s cooldown, 2 recovery rounds, 60 s sampling). |
| 1.2.0 | 2026100501 | Background power optimisations; in-app update check. |
| 1.3.0 | 2026100502 | Master switch for the automation interface (off by default); adaptive sampling interval. |
| 1.4.0 | 2026100503 | Fake full bars judged on 5G / 5G+ only; detection defaults revised. |
| 1.5.0 | 2026100504 | Log persistence fix; write diagnostics; duplicate launcher icon removed; six visible false successes on the write path fixed; carrier defaults corrected; Tasker event gating. |
| 1.5.1 | 2026100505 | Three distinct "cannot read" causes in the authoritative store separated; log conclusions carry their reason; brief/detailed log modes; newest-first log page; log export to file. |
| 1.5.2 | 2026100600 | Shizuku-channel logs completed; log page gets an oldest/newest order toggle (oldest first by default); all 34 network modes switchable from the UI; fixed stale Shizuku user-service pruning (each orphan ~40 MB); log export now streams to disk; release build now enables R8 (APK 33.3 MB -> 4.15 MB). |
| 1.5.3 | 2026100601 | Background power: screen-off HTTP probe throttled to once per 5 min; network-mode reads memoised for 5 min (no per-tick privileged fork); dirty checks / tiered windows for downgrade-state and log persistence; the mode list merges duplicate entries and folds the four carrier-specific 5G-auto modes into "Auto (match carrier)" (resolved from this SIM), 6 dp between rows. |

> Per-version details are no longer duplicated here: measurements live in [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) and acceptance steps in [`docs/TESTING.md`](docs/TESTING.md) (both Chinese).
> Licence: copies distributed as 1.0.1 and earlier (including the 1.0.1 APK in Releases) remain GPL-3.0 and that grant cannot be revoked; from 1.1.0 onwards this project is released under Apache-2.0 — see [`docs/PROVENANCE.md`](docs/PROVENANCE.md).
## Privacy

NetPilot **uploads nothing**. Its only network requests are the ping probe and — only at launch or when you tap the button yourself — a single read of this project's public GitHub Releases feed. It does not read contacts, SMS, or the photo library, and it does not collect location — `ACCESS_FINE_LOCATION` is requested only because Android 10+ classifies cellular signal strength (including SINR) as location data.

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

[Apache-2.0](LICENSE). Provenance per file: [`docs/PROVENANCE.md`](docs/PROVENANCE.md).

**License change.** Releases up to 1.0.1 shipped code ported from [NetworkSwitch](https://github.com/aunchagaonkar/NetworkSwitch) (GPL-3.0): `core/priv/TelephonyReflection.kt` was near-verbatim (0.761 token similarity, 98.2% of upstream tokens inside shared ≥12-token runs), which is why those versions were GPL-3.0. That file has since been re-implemented from scratch (clean-room; method, measurements and reproduction in [PROVENANCE](docs/PROVENANCE.md)), so the project is Apache-2.0 from this commit on. Copies already distributed under GPL-3.0 — including the 1.0.1 APK in Releases — remain GPL-3.0; that grant cannot be withdrawn. Everything else is MIT/Apache-2.0-derived or original.
