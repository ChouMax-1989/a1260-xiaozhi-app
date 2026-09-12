# A1260 XiaoZhi ARMv7 client

## Project scope
- Clean-room Android client for ALC/AIC A1260: Android 11, ARMv7, 1 GB RAM.
- Rebuild the observable XiaoZhi client feature set using public protocols and original code. Normal official device activation/binding is required; bypassing account, entitlement or server checks is forbidden.
- Core scope includes OTA provisioning and six-digit binding, WebSocket voice, continuous/full-duplex modes, foreground wake/VAD, agent entry points, MCP/IoT tools, media controls, downloads and optional visual modules.

## Repository map
- `app/src/main/`: Java source, original resources and Android manifest.
- `research/`: evidence and protocol decisions; do not present research as tested behavior.
- `FEATURE-PARITY.md`: implementation/validation matrix for the expanded client.
- `.toolchain/`, `.downloads/`, `.gradle/`, `build/` and `app/build/`: local/generated, never commit or package.

## Authoritative commands
- Build: `gradlew.bat --no-daemon :app:assembleDebug` with project-local JDK 17 and Android SDK.
- Static tests: `gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug` when configured.
- Install: project platform-tools `adb install -r app/build/outputs/apk/debug/app-debug.apk` after ABI and signature checks.

## Engineering constraints
- Java/View UI; avoid Compose and Flutter. Heavy wake, camera, Live2D, local-server and plugin modules must load only when enabled and must have measured A1260 memory impact.
- Target/min SDK 30 for this device. Use platform `MediaCodec` Opus only after runtime capability probing; report actual 20 ms framing.
- Default to held push-to-talk: release sends, reply completion does not reopen the microphone. Wake-triggered speech ends after a user-configurable 0.4–2 seconds of silence (default 0.6 seconds, explicitly authorized on 2026-09-12). After a wake-triggered reply fully drains, listen for a follow-up for the user-configured 0.5–10 seconds (default 0.5); if no speech follows, play the conversation-end cue and return to wake. Held PTT and full duplex do not enter this follow-up window; full duplex/continuous modes remain opt-in. Never disable TLS validation. Never log endpoint credentials, device identity, verification codes, conversation contents or audio.
- Generate a persistent random Client-Id and privacy-preserving locally administered Device-Id. Provision connection settings through the public OTA activation-v1 flow; store any issued secret encrypted with Android Keystore. Allow an explicit custom server mode.
- Official `78/xiaozhi-esp32` MIT code/assets may be reused only with commit pin and attribution. Do not copy proprietary APK code/assets, signing material, embedded credentials or service secrets.
- Keep queues bounded, serialize the session state machine and release AudioRecord/AudioTrack/codec objects on stop/error.

## Definition of done
- Debug APK builds reproducibly and is signed; APK contains no arm64-only native library.
- Fresh A1260 install requests OTA provisioning, shows and audibly announces the server-issued binding code, polls normal activation, consumes issued WebSocket config and survives expiry/network/error paths.
- A1260 installs and launches it; configuration, permission-denied and disabled-heavy-module paths are usable without crashes.
- Every row in `FEATURE-PARITY.md` distinguishes implemented, device-tested, server-tested and blocked. UI presence alone never counts as functional parity.
- Codec probe, local audio loop, activation and controlled WebSocket handshake have explicit PASS/FAIL evidence. Production voice is claimed only after a user-authorized account binding and three real conversations.

- Background wake/session lifetime belongs to a user-visible foreground service, not Activity. Preserve user templates and credentials on upgrades. Favor Android foreground-service priority and battery exemptions over disabling system memory protection.

- Device deployment uses the non-debuggable release variant (same existing signing identity preserves installed data). Do not overwrite with debug APK during routine optimization; Android 11 downgrades debuggable app compilation to quicken. Verify actual dexopt status after requesting speed.

- Wake runtime/model/dictionary inputs must be public, version/hash pinned and attributed. Never copy them from the proprietary APK. KWS and enrollment must use the same engine; a recorded-template similarity pass is not KWS validation. Preserve existing templates and connection settings on upgrade.
