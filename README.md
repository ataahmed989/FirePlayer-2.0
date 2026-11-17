# FirePlayer 2.0

An evolving all-in-one Fire TV app built on the existing FirePlayer VOD player: Live TV, program guide, VOD browsing, shared playback and Multiview.

## Start here

- [Claude implementation handoff](CLAUDE-HANDOFF.md)
- [Design and settings decisions](design-layout.md)
- [Architecture brief](design-brief.md)
- [Design references](Design-References/)
- [Wallpapers](Wallpapers/)

The Android Gradle project is in `firestick-player-main/`. Run build commands from that directory. It uses JDK 17 and Android compile SDK 36. See its README for the original player's behavior.

The new designs are specifications and concept artwork, not a claim that all proposed features are implemented. Baseline tests and a successful build still need verification.

## Signing

Release signing material is excluded from this public repository. Debug builds now use Android's standard development signing identity. Release builds require the original key supplied privately through `FIREPLAYER_KEYSTORE_PATH`, `FIREPLAYER_STORE_PASSWORD`, `FIREPLAYER_KEY_ALIAS`, and `FIREPLAYER_KEY_PASSWORD`. A differently signed debug APK cannot replace an existing installation signed with the original release key. Do not publish signing keys or personal service credentials. This configuration change has not yet been build-tested.

## Repository layout

The outer folder is the Git repository; the Android source remains nested to preserve handoff paths. Reference app archives, APKs, analysis tools, local SDK settings and build caches are excluded.
