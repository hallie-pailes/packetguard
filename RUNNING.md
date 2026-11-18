# Running PacketGuard and its tests: step by step

Three levels, in the order you should do them. Level 1 works today on any machine with Java and
Python. Levels 2 and 3 need you to build against real Minecraft and are **not yet verified**.

## Level 1: automated tests (no Minecraft needed)

### 1a. Python baseline tests (22 tests)
Needs Python 3.10+, nothing to install.
```
cd packetguard
python3 -m unittest baseline.tests.test_baseline -v
```
Expected: `Ran 22 tests ... OK`. These cover the wire format, the proxy rules, the connection state
machine, a real TCP end-to-end run with a fake upstream server, and attack injection.

### 1b. Java engine simulation (47 checks)
Needs a JDK (17+ is fine for this step; the stubs do not need Java 25) plus two jars: gson and slf4j-api
(download from Maven Central, or find them in your Gradle cache `~/.gradle/caches`).
```
GSON=/path/to/gson-2.13.1.jar
SLF4J=/path/to/slf4j-api-1.7.36.jar
OUT=/tmp/pgout; rm -rf $OUT; mkdir -p $OUT
javac -proc:none -d $OUT -cp "$GSON:$SLF4J" \
  $(find src/main/java dev/stubs/src dev/stubs/test -name '*.java')
java -cp "$OUT:$GSON:$SLF4J" EngineSim
```
Expected last line: `47 checks, 0 failures`. On Windows replace `:` with `;` in classpaths and
list files via `dir /s /b *.java`.
This tests the rule logic, ceilings, shadow evaluation, hot reload and metrics against *stub* Minecraft
classes. It proves nothing about the real Minecraft bindings.

## Level 2: build the mod and run it on a test server

1. Install JDK **25** (the project targets Java 25) and Gradle (once, to create the wrapper):
   `gradle wrapper` in the project folder, then `./gradlew build`.
2. Expect compile errors the first time. They will be in three places only:
   - `compat/McCompat.java`: accessor names marked `VERIFY` (menu, slots, block position, resync calls).
   - the two classes in `mixin/`: target class or method names in `@Mixin` / `@Inject`.
   - `PacketGuardMod.java`: Fabric event names.
   Fix them against the 26.3 sources (`./gradlew genSources`, then open the decompiled class in your IDE).
3. The jar appears in `build/libs/packetguard-0.1.0.jar`.
4. Make a test server: download the Fabric server launcher for 26.3, run it once, accept the EULA,
   put `fabric-api-0.165.0+26.3.jar` and `packetguard-0.1.0.jar` in `mods/`, start again.
5. First start creates `config/packetguard/config.json` (MONITOR mode: logs, never blocks). After
   joining and playing you get, in the same folder: `events.jsonl` (each would-be rejection) and
   `metrics.csv` (written every `metricsFlushIntervalTicks`, default 6000 = 5 min, and ideally on shutdown;
   confirm this after your first run, and lower the interval while testing).
6. Smoke test: join, open a chest, click around. Expect **no** events (or very few). Then edit
   `config.json` (e.g. `"reachTolerance": -5`) and watch `events.jsonl` start filling without a restart
   (hot reload; a `config_reload` row marks it). Undo it afterwards.
7. Only then set `"mode": "ENFORCE"`.

## Level 3: experiments against the proxy baseline

### 3.0 Server settings for the proxy
In `server.properties`: `online-mode=false` and `network-compression-threshold=-1`. The proxy cannot
read packets otherwise, and the relay will stop with a message if it sees compression/encryption.
Use a private test server only.

### 3.1 Fill the protocol profile
Copy `baseline/profiles/template.json` to `baseline/profiles/26.3.json`. Replace each `"id": null`
with the serverbound **play** packet id for your version (sources: the Java Edition protocol page on
minecraft.wiki, or PrismarineJS minecraft-data; or decompile). Check the field layouts too, and the
`attack_encoding` tail bytes. Set `"verified": true` only when done.
Validate it: run the relay in monitor mode (below), join through it, play normally for 2 minutes. The
`proxy,parse_errors` row in `metrics.csv` must be 0 and rejections about 0. Parse errors or nonsense
rejections mean a wrong id or layout.

### 3.2 Run the relay
The server listens on 25565; players (and the injector) connect to the relay on 25566.
```
python3 -m baseline.relay --mode filter --profile baseline/profiles/26.3.json \
  --config <server>/config/packetguard/config.json \
  --upstream-port 25565 --listen-port 25566 \
  --inject baseline/attacks.example.json --out out/B
```
Modes: `passthrough` (forward only), `monitor` (log, forward), `filter` (drop). `--inject` sends the
attack list **after your client reaches the play state**, so: start the relay, join `localhost:25566`
with a normal client, stay in survival, stand still, and wait. The relay writes `events.jsonl`,
`metrics.csv`, `attack_log.jsonl` to `--out`. Stop it with Ctrl-C (flushes metrics).

Edit `attacks.example.json` first: far coordinates must be far from the player; `A10` needs a real mod
channel name that your policy restricts to menus; `"snoop"` means "use the container id of the last
menu the real client clicked in", so open a chest and click once before the attack starts.

### 3.3 The configurations (repeat the same attack file for each)
| Run | Mod config.json | Relay | Output dir |
|---|---|---|---|
| A control | mod removed or MONITOR | `--mode passthrough --inject` | out/A |
| B proxy | mod removed | `--mode filter --inject` | out/B |
| C mod | `"mode":"ENFORCE"` | `--mode passthrough --inject` | out/C |
| D both | ENFORCE | `--mode filter --inject` | out/D |
| C-PACKET | ENFORCE, `"visibilityCeiling":"PACKET"` | passthrough + inject | out/CP |
| C-CONN | ENFORCE, `"visibilityCeiling":"CONNECTION"` | passthrough + inject | out/CC |
| M shadow | MONITOR, `"shadowEvaluation":true` | passthrough + inject | out/M |

Between runs: restart the server (or at least rejoin), clear/rename the mod's `events.jsonl`, and give
the player a fresh state. In run A, **verify each attack really has an effect** on the server (item
moved, block broken, console line); without that, "blocked" in the other runs means nothing.
Note passthrough relays still need a verified profile when `--inject` is used (to build packets).

### 3.4 Analyse
```
python3 tools/analyze.py attacks --attack-log out/C/attack_log.jsonl \
   --mod-events <server>/config/packetguard/events.jsonl --proxy-events out/B/events.jsonl
```
Prints a CSV per attack: sent, proxy flagged/blocked, mod flagged/blocked, and for the mod the
`minVisibility` (PACKET/CONNECTION/GAME_STATE). Match is by packet kind within the attack's time
window (+-1.5 s), so keep the player idle during attacks to avoid benign events leaking in.
Expected pattern (a hypothesis to test, not a result): proxy catches A3, A4, A8, A9, A11 only; mod
also catches A1, A2, A5, A6, A7, A10; C-PACKET/C-CONN look like B.

### 3.5 False positives
Play normally for 30+ minutes with the mod in MONITOR (inventory shuffling, chests, shift-click
spam, creative building, mod GUIs), then
`python3 tools/analyze.py benign --metrics <server>/config/packetguard/metrics.csv`.
Add a stress: legitimate fast clicking. Do the same through the relay in monitor mode for the proxy's
rate. Tune thresholds on one session and report on a different one. Add latency with `tc netem` (Linux)
or Clumsy (Windows), e.g. 20, 100, 250 ms, to test range/lag tolerance.

### 3.6 Overhead
- Per packet: `analyze.py latency --metrics ...` (p50/p99 ns), mod and proxy.
- Server tick time: use `/tick query` or Spark (`/spark tps`) with and without the mod while N bot
  players (e.g. mineflayer scripts) click at a fixed rate. Report MSPT mean and p95.
- Proxy latency: compare ping with and without the relay.

### 3.7 Optional comparison E
Install SS Anti-Cheat Stack (or another tool) alone and rerun A..: same attack file, same analysis.
Its logging format differs, so judge by whether the attack's effect still happens (step A check).

## Troubleshooting
- Relay says profile has null ids / not verified: fill and verify the profile (3.1).
- Relay prints "server enabled compression/encryption": fix `server.properties` (3.0).
- Attacks `reachedServer` = sent in filter mode for a PACKET attack: profile layout wrong.
- Mod events empty after attack runs: mod still in MONITOR with logging off, or the config you edited
  is not `config/packetguard/config.json` of the running server.
- Client kicked after attacks: strike limit reached (`strikeLimit`); raise it for experiments.
