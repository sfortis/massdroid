# MassDroid Roadmap

## Scope
This roadmap captures agreed technical enhancements to improve maintainability, security, and stability without breaking current behavior.

## Phase 1: Safe Refactor (No Behavior Change)
- Split `MainActivity` responsibilities into focused components:
- `PlaybackController` (MediaSession + AudioService sync + media commands)
- `WebViewBridgeManager` (JS injection + `AndroidMediaSession` bridge callbacks)
- `ConnectivityCoordinator` (network loss/restore + auto-resume orchestration)
- `BluetoothCoordinator` (receiver lifecycle + BT connect/disconnect policy)
- Keep user-visible behavior unchanged while reducing class size and coupling.

## Phase 2: Security Hardening
- Register Bluetooth dynamic receiver as non-exported (`RECEIVER_NOT_EXPORTED`).
- Harden WebView defaults where possible:
- disable unnecessary file/content access.
- keep JavaScript bridge exposure minimal and controlled.
- Restrict cleartext traffic policy to local/trusted targets instead of global allow.

## Phase 3: Update and Dependency Cleanup
- Use `checkForUpdates(force = false)` on app startup.
- Keep manual settings check as `force = true`.
- Remove `lifecycle-viewmodel-ktx` if no ViewModel is introduced.
- If ViewModel is introduced during modularization, keep dependency and migrate state ownership there.

## Phase 4: Reliability and Test Coverage
- Reduce duplicated auto-resume logic split across Kotlin and injected JS.
- Add unit/instrumentation coverage for:
- Bluetooth auto-play/disconnect flows.
- Network reconnect + auto-resume.
- MediaSession/notification playback-state sync.

## Definition of Done
- Smaller, focused classes with clear ownership.
- Hardened runtime security surface.
- Predictable update checks and cleaner dependencies.
- Regression safety net for core playback behaviors.
