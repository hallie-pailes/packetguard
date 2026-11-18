# PacketGuard

A Fabric **server mod** that validates client packets against the **live game state** (open menu,
player position, game mode, tick timing) before vanilla or other mods act on them, plus a
**network-proxy baseline** (Python) used to measure what a proxy can and cannot catch.

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.165.0+26.3, Loom 1.17, Java 25, Mojang names.

> **Status: not compiled against real Minecraft.** Engine logic passes 47 checks against stubs
> (`dev/stubs`); the Python baseline passes 22 tests. Minecraft-facing code (`compat/McCompat.java`,
> mixin targets, Fabric event names) is unverified.

* What each file does, how this differs from existing tools: **ARCHITECTURE.md**
* How it is tested against the proxy baseline: **TESTING.md**
* Step-by-step run and test instructions: **RUNNING.md**

## Safe defaults
MONITOR mode (log only, never blocks) until you set `"mode": "ENFORCE"` in
`config/packetguard/config.json`. Hot-reloaded; an invalid file keeps the previous config.

## Before you build
1. Add a Gradle wrapper (`gradle wrapper`), then `./gradlew build`.
2. Fix compile errors in `compat/McCompat.java` (accessor names marked VERIFY), the two mixin
   classes (target class/method names), and the Fabric event names in `PacketGuardMod.java`.
3. Fill `baseline/profiles/<version>.json` play-state packet ids (null in the template).

## Quick commands
```
python3 -m unittest baseline.tests.test_baseline     # baseline tests
python3 -m baseline.relay --help                     # proxy
python3 tools/analyze.py --help                      # results
```
