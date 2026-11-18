# PacketGuard: architecture, what each file does, and how it differs from existing work

## 1. The claim (narrowed)
We do **not** claim that "a mod can check packets against game state" is new: community tools
(SS Anti-Cheat Stack, ExploitFixer, PacketFixer, Firewall, CheaterDeleter) already do parts of it.
What this project contributes is a **systematic, measured treatment**:

1. one rule model covering container, interaction and mod-payload packets;
2. each rule is labelled by the *information it needs* (the **visibility model**), so the
   comparison with a network proxy is an ablation, not an argument;
3. measured detection, false positives and server overhead, against a protocol-aware proxy that
   uses the same thresholds.

Caveat: our statements about the community tools rest on their public product pages, not on reading
their code. Do not write stronger claims in the report.

## 2. Visibility model (the core idea)
| Level | Needs | Examples | Proxy can do it? |
|---|---|---|---|
| `PACKET` | only this packet's bytes | slot index in plausible range, container id >= 0, frame size | yes |
| `CONNECTION` | per-connection history | token-bucket rate per packet kind / per channel | yes |
| `GAME_STATE` | live server state | menu open? slot exists in *this* menu? creative mode? distance to block? | **no** |

`visibilityCeiling` in config.json hides rules above a level (the ablation). With
`shadowEvaluation=true` in MONITOR mode, every rejection records `rejectedBy` (all rules that
would reject) and `minVisibility` (weakest level that suffices). So **one run** shows how much a
proxy could ever have caught.

## 3. File-by-file

### Java mod (`src/main/java/com/packetguard`)
| File | Role |
|---|---|
| `PacketGuardMod.java` | Fabric entrypoint. Loads config, creates engine, ticks it, polls config file for hot reload (every 20 ticks). |
| `PacketGuard.java` | Holder/static access to the engine for the mixins. |
| `mixin/ServerGamePacketListenerImplMixin.java` | Injects at HEAD of the four vanilla handlers (container click, creative slot, block use, block action) *on the main thread*, asks the engine, cancels the call on rejection. |
| `mixin/ServerCommonPacketListenerImplMixin.java` | Custom (mod) payloads. Two phases: network thread runs cheap THREAD_SAFE rules (structural, rate); if it passes, the packet is re-queued with `server.execute` and the main thread runs STATE_ONLY rules (needs game state). Counted once in metrics. |
| `compat/McCompat.java` | The only place touching version-sensitive Minecraft accessors (marked VERIFY) and the resync helpers (re-send menu/block to the client after a block). |
| `config/GuardConfig.java` | JSON config: mode (MONITOR default / ENFORCE), ceiling, shadow, disabledRules, structural limits, reach tolerance, rates, payload policies, strikes. Bad file on reload keeps the old config. |
| `core/Visibility.java`, `Phase.java` | The visibility levels; which levels may run on which thread. |
| `core/Rule.java` | Rule interface: id, visibility, appliesTo, check. |
| `core/RuleEngine.java` | Runs rules cheapest-first, applies ceiling/shadow/disabled, strikes + kick, logs events, records metrics. |
| `core/PlayerState.java`, `TokenBucket.java` | Per-player buckets and strike window (tick based). |
| `core/PacketKind.java`, `PacketInfo.java`, `Verdict.java` | Neutral packet description and result (ALLOW / reject / soft reject; soft = no strike, e.g. lag race on a just-closed menu). |
| `core/Metrics.java`, `EventLog.java` | metrics.csv (counts, per-rule, min visibility, latency p50/p99 and log2 histogram) and events.jsonl. |
| `rules/StructuralRule` (PACKET) | Slot range, non-negative container id. |
| `rules/RateLimitRule` (CONNECTION) | Per-kind and per-channel token buckets. |
| `rules/ContainerContextRule` (GAME_STATE) | Click names a menu that is not the open one -> soft reject; spectators rejected. |
| `rules/SlotBoundsRule` (GAME_STATE) | Slot must exist in the *actual* open menu (a proxy only knows the global range). |
| `rules/CreativeModeRule` (GAME_STATE) | Creative-slot packets only in creative mode. |
| `rules/ContainerRangeRule` + `MenuGeometry` (GAME_STATE) | Player must still be within range of the backing block (`stillValid` checked per packet, not only on open). |
| `rules/ReachRule` (GAME_STATE) | Block use/break from eye position vs server interaction range + tolerance. |
| `rules/PayloadPolicyRule` (GAME_STATE) | Per-channel policy: alive, not spectator, menu open, within menu distance. |

### Python baseline (`baseline/`)
| File | Role |
|---|---|
| `mcwire.py` | VarInt, framing, position, field reader/writer (no compression/encryption). |
| `profile.py`, `profiles/template.json` | Version-specific packet ids and layouts as data. Play ids are **null on purpose**; relay refuses to filter until filled and `verified`. |
| `rules.py` | Proxy rules: frame_size, structural (PACKET), rate_limit (CONNECTION). Same thresholds, read from the mod's config.json. Wall-clock buckets (a proxy has no tick counter). No game-state rule exists here by design. |
| `relay.py` | asyncio TCP relay + `Session` state machine HANDSHAKE→LOGIN→CONFIG→PLAY. Modes passthrough / monitor / filter; `--inject` sends scripted attacks through the same evaluation. Fails open on parse errors; refuses servers using compression/encryption. Emits metrics.csv/events.jsonl in the mod's schema (`"source":"proxy"`). |
| `attacks.py`, `attacks.example.json` | Builders for 11 attack classes with the weakest sufficient visibility documented per attack. |
| `tests/test_baseline.py` | 22 tests (wire, rules, state machine, TCP end-to-end with a fake upstream, injection). |
| `tools/analyze.py` | Joins attack log with mod/proxy events; false-positive and latency summaries. |

### Verification aids (`dev/stubs`)
Stub classes for the Minecraft API plus `EngineSim.java` (47 checks on engine logic). This proves
the logic, **not** the Minecraft bindings.

## 4. How this differs from current work
| Work | What it does | Difference |
|---|---|---|
| SS Anti-Cheat Stack, ExploitFixer, PacketFixer, Firewall, CheaterDeleter | In-server plugins/mods blocking specific exploits (e.g. payload caps, NBT/book abuse) | Ad-hoc collections; no stated model of *why* a check needs the server, no ablation, no published detection/FP/overhead numbers. PacketFixer's 50 payloads/tick cap is rate-only (CONNECTION level). |
| Bethea et al. (server-side verification by symbolic execution) | Offline-derived checks of client behaviour for games | Different technique and games; not Minecraft packets, not hand-written per-packet state rules. |
| Joens et al. (external referee) | Independent referee validates game state | External component rather than inside the server process. |
| Hong et al. (Forge mod) | Speed check only | One check; no comparison with a network baseline. |
| Network firewalls/proxies | Size, structure, rate | Cannot see menu, mode, position: the `GAME_STATE` rows above. |

## 5. Limits you must state in the report
- A stronger proxy that tracks position/menu by parsing clientbound traffic (B+) could catch some
  of our GAME_STATE attacks; ours is the *stateless-about-the-game* proxy. Say so.
- Proxy needs `online-mode=false` and compression off, and decodes more than a generic firewall.
- Ticks vs wall-clock: bucket refills differ slightly; thresholds are shared, timing is not.
- Not compiled against real Minecraft 26.3; McCompat accessors, mixin targets and Fabric event
  names need verifying (see README "Before you build").
