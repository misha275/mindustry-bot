#!/bin/sh
# Start the bot: ./run.sh [--host address[:port]] [--name Name] [--http 6789]
cd "$(dirname "$0")"
exec "${JAVA:-java}" -jar mindustry-bot.jar --host 127.0.0.1 --port 6567 --name ClaudeBot "$@"
