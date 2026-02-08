# MassDroid Roadmap

## Scope
This roadmap captures agreed technical enhancements to improve maintainability, security, and stability without breaking current behavior.

## Status Snapshot (2026-02-08)
- `v1.3.0` shipped major reliability work and partial modularization.
- Current priority is finishing modularization and adding regression coverage.

## Completed in `v1.3.0`
- Extracted network reconnect/auto-resume orchestration into `NetworkAutoResumeCoordinator`.
- Added multi-tier retry strategy (soft retry, controlled socket reset, WebView reload fallback).
- Added WebSocket race protections (`stale` socket guards, debounced disconnect notify).
- Improved audio focus behavior so other apps can take playback.
- Improved Bluetooth handoff/disconnect handling and recovery behavior.
- Hardened WebView surface and backup defaults.

## Next Milestones
### 1) MainActivity Modularization
- Continue splitting `MainActivity` into focused modules:
- Target modules: `PlaybackController`, `WebViewBridgeManager`, `ConnectivityCoordinator`, `BluetoothCoordinator`.
- Keep behavior stable while reducing class size and coupling.

### 2) Security Hardening
- Keep Bluetooth dynamic receiver non-exported (`RECEIVER_NOT_EXPORTED`).
- Further tighten WebView settings:
- disable unnecessary file/content access.
- keep JS bridge exposure minimal and controlled.
- Restrict cleartext traffic policy to local/trusted targets instead of global allow.

### 3) Reliability and Coverage
- Continue tuning reconnect timing for edge-case network/BT flapping.
- Add unit/instrumentation coverage for:
- Bluetooth auto-play/disconnect flows.
- Network reconnect + auto-resume.
- MediaSession/notification playback-state sync.

### 4) Update and Dependency Cleanup
- Keep startup checks as `checkForUpdates(force = false)`.
- Keep manual settings checks as `force = true`.
- Remove unused dependencies if no ownership migration requires them.

## Definition of Done
- Smaller, focused classes with clear ownership.
- Hardened runtime security surface.
- Predictable update checks and cleaner dependencies.
- Regression safety net for core playback behaviors.
