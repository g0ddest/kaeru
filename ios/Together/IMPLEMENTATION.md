# Together and pairing implementation record

Scope: owned Together/, Device/, Together*Tests.swift and Pairing*Tests.swift only. No commits or Xcode builds.
Approved design: ../../docs/superpowers/specs/2026-09-19-ios-android-parity-design.md; audited Android 30b8c82.

- [ ] Protocol/validation/ordering/clock tests before implementation; isolated SwiftPM harness.
- [ ] CryptoKit codec, bounded DTOs, invitation validation, symmetric ordering and clock policy.
- [ ] URLSession relay and Network LAN transport, connection deadlines and generation fences.
- [ ] Observable manager and no-echo playback adapter, chat/reactions/voice.
- [ ] Independent OAuth code pairing with explicit confirmation and bounded LAN HTTP.
- [ ] Adaptive SwiftUI sheets and audio receive capability investigation.
- [ ] Harness tests and isolated iOS typecheck; record parent integration and limits.
