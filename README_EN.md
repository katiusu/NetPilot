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
| Version | **1.3.0** (`versionCode 2026100502`) |
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

State machine: after a downgrade it enters a cooldown (default 60 s, adjustable down to 30 s); once the cooldown ends, **2 consecutive** healthy rounds restore the original mode; **2 consecutive** no-network rounds roll back immediately and leave auto mode; the sampling interval defaults to 60 s.

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

### License change to Apache-2.0 (1.1.0)

- **Why**: up to 1.0.1, `core/priv/TelephonyReflection.kt` was near-verbatim from the GPL-3.0 project
  NetworkSwitch (0.761 token similarity; 98.2% of upstream tokens inside shared runs), which is why
  those releases were GPL-3.0.
- **What changed**: that file is now a clean-room re-implementation (0.291 similarity, longest shared
  run 25 tokens — what remains is platform fact: the `HiddenApiBypass` exemption prefixes, the AOSP
  constant table, interface signatures). `LICENSE` is now **Apache-2.0** and the About / licenses
  screens were updated in step.
- **Irrevocable**: copies already distributed under GPL-3.0 — including the 1.0.1 APK in Releases —
  remain GPL-3.0.
- **Reproduce**: `python3 tools/check_provenance.py` (per-file similarity report); item-by-item
  reasoning in [PROVENANCE](docs/PROVENANCE.md), acceptance steps in [TESTING](docs/TESTING.md) §15.

### Default-value changes (1.1.0)

- **The network-quality downgrade master switch now defaults to on.** The Features page had it hard-coded
  to `false` while the engine defaulted to `true`, so the UI showed "off" while downgrades were already
  running; both now read `MonitorSettings.DEFAULT_ENABLED`.
- **Cooldown defaults to 60 s** (was 300 s), slider minimum **30 s** (was 60 s), step 30 s.
- **Healthy rounds required to restore default to 2** (was 3).
- **Sampling interval defaults to 60 s** (was 120 s).
- No-network rollback stays at 2 rounds.
- Only affects users who never wrote the key: values already stored in `SharedPreferences` win.

### Power optimizations + in-app update check (1.2.0)

See **[`docs/POWER_REPORT.md`](docs/POWER_REPORT.md)** for before/after numbers, the truth-table proof and every reproduction command. What changed, and what you can actually notice:

| Change | Before | After | Perceptible difference |
|---|---|---|---|
| Monitor-page 5-second live sampling | Kept running **even in the background** once you had opened the page — up to 720 real HTTP probes/hour | Runs only while the app is in the foreground, the monitor page is the current tab **and the screen is interactive** — the loop re-checks `PowerManager.isInteractive` every round, because Compose recomposition waits for a frame and no frames arrive once the screen is off | None |
| Probe while the screen is off and the signal is not strong | One HTTP probe every 60 s anyway | **Skipped entirely** — no network traffic | Notification/monitor page shows "Probe skipped (screen off)" |
| Notification redraw | Re-posted every 60 s even when the text never changed | Only when the text actually changes | The notification stops ticking once a minute |
| Log persistence | Rewrote 120 entries into `SharedPreferences` every 4 s | Every 30 s, off the calling thread | None (still 400 entries in the page, ~120 restored after a restart) |
| Keep-alive heartbeat | Re-registered the alarm and wrote a log line (which triggered a persist) on every tick | One in-process state check | None |
| Restart after being killed | Fixed 10 s | Still 10 s normally; exponential backoff up to 15 min when it keeps crashing | Only visible if the service crash-loops |
| Leftover Shizuku processes | Never reclaimed (measured: 4 processes, ~201 MB on one device) | Swept once per app start | More stable background memory |

**The downgrade decision logic is unchanged.** Skipping a probe requires all three of: screen off, state machine idle, and signal not stronger than the threshold. When the signal *is* strong, `Ping` feeds the fake-full-bar rule, so the probe still runs. The per-branch truth table is in the report (§3).

**In-app update check (new)**: an entry in About, plus an optional check at launch (setting defaults to on) against [Releases](../../releases). **Check and notify only** — a dialog shows the version and release notes, and "Update now" opens the Releases page in your browser. No silent downloads, no auto-install, no background polling.

**Play compliance (1.2.0)**: `targetSdk` 34 → **36** (`compileSdk` stays 37). Edge-to-edge, predictive back, the `specialUse` foreground service, BOOT_COMPLETED restrictions and 16 KB page alignment (`zipalign -c -P 16` passes) were each checked; nothing else was needed.

### Tasker switch + adaptive sampling interval (1.3.0)

| Change | Before | After | What you notice |
|---|---|---|---|
| Tasker / Locale interface | Three components always enabled; every Tasker command cold-started the app process | **Off by default**; flipping the switch disables those components at the system level, so broadcasts are never delivered | If you don't use automation you're no longer woken by Tasker; if you do, turn it on once in Features |
| Sampling interval | Always the configured value | Shrinks by 20% per round while readings stay near a threshold (at most down to half), and snaps back as soon as they move away | Faster reactions on marginal signal; Features gains an "Adaptive sampling interval" switch and an "Adaptive sensitivity" slider |
| Keep-alive start failure | A single vague "keep-alive broadcast failed", plus a misleading "service started" line right after it | `ForegroundServiceStartNotAllowedException` is detected separately and logged with the cause and the next step | When keep-alive silently fails, the log now says it is the battery-optimization setting |

**Adaptive sampling changes the cadence only, never the decision**: threshold comparisons still use the raw readings, and `isNearThreshold()` has no caller on any decision path — it cannot change a downgrade or recovery outcome. The full write-up, plus one candidate that was investigated and *rejected as unsafe*, is in [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §6.

**New settings (1.3.0)**: `np_tasker_enabled` (**off**), `np_fake5g_adaptive_interval` (**on**), `np_fake5g_adaptive_margin` (**10 dBm**). No existing threshold or interval default was touched.

---

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
