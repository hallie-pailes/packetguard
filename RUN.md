python -m unittest baseline.tests.test_baseline -v 
- used to run the 22 tests in `baseline/tests/test_baseline.py` and print verbose output.

New-Item -ItemType Directory -Force C:\libs | Out-Null
Invoke-WebRequest https://repo1.maven.org/maven2/com/google/code/gson/gson/2.13.1/gson-2.13.1.jar -OutFile C:\libs\gson-2.13.1.jar
Invoke-WebRequest https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar -OutFile C:\libs\slf4j-api-1.7.36.jar
dir C:\libs

- these make the folder and download both files from Maven Central, the standard public place for Java libraries
- The mod’s code uses two small ones jars: 
    - gson: reads and writes the JSON config file
    - slf4j-api: the logging interface
- Minecraft normally provides both when the mod runs for real

$libs = "C:\libs\gson-2.13.1.jar;C:\libs\slf4j-api-1.7.36.jar"
New-Item -ItemType Directory -Force out_java | Out-Null
$files = Get-ChildItem -Recurse -Filter *.java src\main\java, dev\stubs\src, dev\stubs\test | ForEach-Object { $_.FullName }
javac -proc:none -d out_java -cp $libs $files
java -cp "out_java;$libs" EngineSim
- This tests the rule logic against stand-in Minecraft classes, not the real game

- go to fabricmc.net/develop/template, choose Minecraft 26.3, tick nothing extra and download the zip
- Unzip it somewhere, for example C:\temp\fabric-template
- Copy these three items from the template into your packetguard folder:
    - the file gradlew
    - the file gradlew.bat
    - the whole gradle folder (it contains wrapper)
- Don’t copy build.gradle, settings.gradle or src

- run: .\gradlew.bat build
- The first build downloads Gradle and Minecraft, so it takes several minutes
- there will be errors:
    - run: Get-ChildItem -Recurse -Filter *.jar "$env:USERPROFILE\.gradle\caches\fabric-loom", ".\.gradle" -ErrorAction SilentlyContinue | Where-Object { $_.Length -gt 5MB } | Select-Object FullName, Length
    - take the one from the packetguard foler with minecraft in the file name
    - run: $jar = "PASTE-THE-FULL-PATH-HERE"
    - javap -public -cp $jar net.minecraft.network.protocol.game.ServerboundContainerClickPacket
    - javap -public -cp $jar net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
    - javap -public -cp $jar net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket


# Run on Test Server
Make a server folder. For example C:\mcserver
Go to fabricmc.net/use/server & choose Minecraft 26.3 & 0.20.1 fabric and download the server jar
Save it in the server folder you created
Download the fabric api from modrinth.com/mod/fabric-api for 26.3 and save it in the server folder under C:\mcserver\mods\
Copy packetguard-0.1.0.jar from this repo into C:\mcserver\mods\

- cd C:\mcserver
- java -jar fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar nogui
- firt runs stops and asks you to agree to the EULA, so edit eula.txt and change false to true, then run command again

# Proxy Comparison
- run: .\gradlew.bat genSources
- run Get-ChildItem -Recurse -Filter *sources*.jar .\.gradle\loom-cache, "$env:USERPROFILE\.gradle\caches\fabric-loom" -ErrorAction SilentlyContinue | Select-Object FullName
    - this is used to find the class we need
- run this, but replace src (keep quotes) with the path from above: 
    New-Item -ItemType Directory -Force C:\temp | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $src = "PASTE-THE-SOURCES-JAR-PATH-HERE"
    $zip = [IO.Compression.ZipFile]::OpenRead($src)
    $e = $zip.Entries | Where-Object FullName -eq 'net/minecraft/network/protocol/game/GameProtocols.java'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, "C:\temp\GameProtocols.java", $true)
    $zip.Dispose()

# For Demo:
To clear old output:
cd C:\Users\hal60\PacketGuard\packetguard
Remove-Item C:\mcserver\config\packetguard\events.jsonl -ErrorAction SilentlyContinue
Remove-Item -Recurse out\demo1, out\demo2 -ErrorAction SilentlyContinue

- Make sure mode is set to MONITOR

Start the relay in one terminal:
python -m baseline.relay --mode filter --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --out out\demo1 --inject baseline\attacks.demo.json
- In Minecraft, join 127.0.0.1:25566 and stay in the world. The first attack fires 8 seconds after you enter, and all three finish in about 15 seconds. Wait about 20 seconds.
- Press Ctrl-C to stop the relay. The out\demo1 folder now contains the attack log and the proxy event log.

python tools\analyze.py attacks --attack-log out\demo1\attack_log.jsonl --proxy-events out\demo1\events.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl

- Set Mode to ENFORCE
- Move old events out of the way:
Move-Item C:\mcserver\config\packetguard\events.jsonl C:\mcserver\config\packetguard\events_run1.jsonl

- Start the relay in passthrough mode, so only the mod is defending:
python -m baseline.relay --mode passthrough --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --out out\demo2 --inject baseline\attacks.demo.json

- Join 127.0.0.1:25566 again. In the first 8 seconds, open a chest and move an item, so you show that normal play still works. Then wait about 20 seconds
- Press Ctrl+C and run:
python tools\analyze.py attacks --attack-log out\demo2\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl


# For Overall Test Suite:
- Run server, whitelist off

## Run A/M
- set "kickOnStrikeLimit": true (? - check this)
- Mod config (C:\mcserver\config\packetguard\config.json): set "mode": "MONITOR", "shadowEvaluation": true, "visibilityCeiling": "GAME_STATE", "reachTolerance": 1.5, and save.
- Run relay:
python -m baseline.relay --mode passthrough --verbose --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --inject baseline\attacks.rest.json --out out\A
- Join 127.0.0.1:25566 in survival. In the first 25 seconds, open a chest, click one item, then close it and stand still. Stay connected 90+ seconds.
- Press Ctrl+C on the relay after it has been quiet 20 seconds.
- Run to analyze: 
python tools\analyze.py attacks --attack-log out\A\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl --proxy-events out\A\events.jsonl

## To run A10:
- Add the policy. In the mod’s config.json, set payloadPolicies to:
"payloadPolicies": {
  "minecraft:brand": { "requireOpenMenu": true }
}
- Keep "mode": "MONITOR" and "shadowEvaluation": true.
- Run relay:
python -m baseline.relay --mode passthrough --verbose --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --inject baseline\attacks.a10.json --out out\A10
- Join 127.0.0.1:25566 in survival and stand still with no menu open (no chest, no inventory). Wait about 20 seconds, then Ctrl+C.
- Run to analyze:
python tools\analyze.py attacks --attack-log out\A10\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl --proxy-events out\A10\events.jsonl

## Run B
- set "kickOnStrikeLimit": false
- Mod config: "mode": "MONITOR", "shadowEvaluation": true, "visibilityCeiling": "GAME_STATE", "kickOnStrikeLimit": false, and "payloadPolicies": {}.
- Run relay:
python -m baseline.relay --mode filter --verbose --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --inject baseline\attacks.26.3.json --out out\B
- Join 127.0.0.1:25566 in survival. Open a chest and click one item in the first 25 seconds, then stand still. Stay connected 90+ seconds.
- Run to analyze:
python tools\analyze.py attacks --attack-log out\B\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl --proxy-events out\B\events.jsonl

## Run C
- set "kickOnStrikeLimit": false
- mod "mode": "ENFORCE"
- Run relay:
python -m baseline.relay --mode passthrough --verbose --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --inject baseline\attacks.26.3.json --out out\C
- Join 127.0.0.1:25566 in survival. Open a chest and click one item in the first 25 seconds, then stand still. Stay connected 90+ seconds.
- Run to analyze:
python tools\analyze.py attacks --attack-log out\C\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl --proxy-events out\C\events.jsonl

## Run C-PACKET
- ENFORCE with "visibilityCeiling": "PACKET"
- Run relay:
python -m baseline.relay --mode passthrough --verbose --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --inject baseline\attacks.26.3.json --out out\CP
- Join 127.0.0.1:25566 in survival. Open a chest and click one item in the first 25 seconds, then stand still. Stay connected 90+ seconds.
- Run to analyze:
python tools\analyze.py attacks --attack-log out\CP\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl --proxy-events out\CP\events.jsonl

## Run C-CONNECTION
- ENFORCE with "visibilityCeiling": "CONNECTION"
- Run relay:
python -m baseline.relay --mode passthrough --verbose --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --inject baseline\attacks.26.3.json --out out\CC
- Join 127.0.0.1:25566 in survival. Open a chest and click one item in the first 25 seconds, then stand still. Stay connected 90+ seconds.
- Run to analyze:
python tools\analyze.py attacks --attack-log out\CC\attack_log.jsonl --mod-events C:\mcserver\config\packetguard\events.jsonl --proxy-events out\CC\events.jsonl

## To test false positives & speed:
- Reset the files so the numbers are only from normal play. Stop the server (stop). Then back up the old files:
New-Item -ItemType Directory -Force C:\mcserver\backup_attack_runs | Out-Null
Move-Item C:\mcserver\config\packetguard\events.jsonl, C:\mcserver\config\packetguard\metrics.csv C:\mcserver\backup_attack_runs\ -Force
- Mod config: "mode": "MONITOR", "shadowEvaluation": true, "visibilityCeiling": "GAME_STATE", "reachTolerance": 1.5, "kickOnStrikeLimit": true, "payloadPolicies": {}. Start the server.
- Start the relay in monitor mode (no --inject), so you get the proxy’s numbers from the same session:
python -m baseline.relay --mode monitor --profile baseline\profiles\26.3.json --config C:\mcserver\config\packetguard\config.json --upstream-port 25565 --listen-port 25566 --out out\benign
- Play normally for about 15 minutes through 127.0.0.1:25566. Open a chest, click one item, close it, move around, jump, switch to creative and and take items from invetory and build then switch back to survival etc. Then Ctrl+C the relay & type stop in the server console.
- Run to analyze:
python tools\analyze.py benign --metrics C:\mcserver\config\packetguard\metrics.csv --label mod-normal-play
python tools\analyze.py latency --metrics C:\mcserver\config\packetguard\metrics.csv --label mod
python tools\analyze.py benign --metrics out\benign\metrics.csv --label proxy-normal-play
python tools\analyze.py latency --metrics out\benign\metrics.csv --label proxy

