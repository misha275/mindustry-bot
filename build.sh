#!/bin/sh
# Rebuild mindustry-bot.jar from src/. Needs JDK 17+.
# Game version must match the server version: VERSION=v160.5 ./build.sh
set -e
cd "$(dirname "$0")"
VERSION="${VERSION:-v160.5}"
mkdir -p build
[ -f "build/server-$VERSION.jar" ] || curl -fL -o "build/server-$VERSION.jar" "https://github.com/Anuken/Mindustry/releases/download/$VERSION/server-release.jar"
rm -rf build/classes build/fat && mkdir -p build/classes build/fat
javac -encoding UTF-8 --release 17 -nowarn -cp "build/server-$VERSION.jar" -d build/classes src/mbot/*.java
(cd build/fat && unzip -q "../server-$VERSION.jar" && rm -f META-INF/MANIFEST.MF META-INF/*.SF META-INF/*.RSA META-INF/*.DSA)
cp -r build/classes/mbot build/fat/
cp -r assets/botassets build/fat/
printf 'Main-Class: mbot.Bot\n' > build/manifest.txt
jar cfm mindustry-bot.jar build/manifest.txt -C build/fat .
echo "Done: mindustry-bot.jar ($VERSION)"
