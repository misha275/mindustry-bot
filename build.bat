@echo off
rem Rebuild mindustry-bot.jar from src\. Needs JDK 17+ (javac and jar in PATH).
rem Other game version: set VERSION=v160.5 before running.
cd /d "%~dp0"
if not defined VERSION set VERSION=v160.5
if not exist build mkdir build
if not exist build\server-%VERSION%.jar curl -fL -o build\server-%VERSION%.jar https://github.com/Anuken/Mindustry/releases/download/%VERSION%/server-release.jar || goto :err
if exist build\classes rmdir /s /q build\classes
if exist build\fat rmdir /s /q build\fat
mkdir build\classes build\fat
javac -encoding UTF-8 --release 17 -nowarn -cp build\server-%VERSION%.jar -d build\classes src\mbot\*.java || goto :err
pushd build\fat
jar xf ..\server-%VERSION%.jar || goto :err
del /q META-INF\MANIFEST.MF META-INF\*.SF META-INF\*.RSA META-INF\*.DSA 2>nul
popd
xcopy /e /i /q build\classes\mbot build\fat\mbot >nul
xcopy /e /i /q assets\botassets build\fat\botassets >nul
echo Main-Class: mbot.Bot> build\manifest.txt
jar cfm mindustry-bot.jar build\manifest.txt -C build\fat . || goto :err
echo Done: mindustry-bot.jar (%VERSION%)
pause
exit /b 0
:err
echo Build failed
pause
exit /b 1
