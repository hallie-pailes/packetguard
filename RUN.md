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