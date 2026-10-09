@echo off
rem Start the bot. Options you can change: --host address[:port] --name Name --http 6789
rem Java 17 is bundled in the jre folder next to this file, no install needed.
rem To use another Java: set JAVA="C:\path\to\java.exe" before running.
if not defined JAVA if exist "%~dp0jre\bin\java.exe" set JAVA="%~dp0jre\bin\java.exe"
if not defined JAVA set JAVA=java
%JAVA% -jar "%~dp0mindustry-bot.jar" --host 127.0.0.1 --port 6567 --name ClaudeBot %*
pause
