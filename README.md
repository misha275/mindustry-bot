# Mindustry bot for Claude

A separate player with no window or graphics. The bot joins a Mindustry server as a regular client
and is controlled through a local HTTP bridge at `http://127.0.0.1:6789`. Its brain is Claude Code on the same PC,
no API key needed. No mods: the game and the server stay vanilla.

Built on Mindustry **v160.5** (build 160.5) code. The server version must match the bot version.

## Requirements

- Java 17 or newer. The Windows package ships with Java 17 in the `jre` folder and `run.bat` uses it.
  Otherwise the Java from the game folder (Steam) works too: `...\steamapps\common\Mindustry\jre\bin\java.exe`.
- A Mindustry server of the same version. Either works:
  - the game itself: start a map and choose Host Multiplayer Game in the pause menu. The game becomes a server on port 6567;
  - the dedicated server `server-release.jar`: `java -jar server-release.jar`, then `host`.

## Running

Windows: double-click `run.bat`, or from a console:

```
java -jar mindustry-bot.jar --host 127.0.0.1 --port 6567 --name ClaudeBot
```

Options: `--host address[:port]`, `--port`, `--name`, `--color ffd37f`, `--http 6789`,
`--data botdata` (stores the bot's uuid and saved schematics), `--schematics folder` (an extra schematics folder),
`--no-reconnect`, `--quiet`.

Once started, the bot prints chat and events to the console and reconnects by itself if the server restarts.

Check: `curl http://127.0.0.1:6789/status`. In PowerShell type `curl.exe`, because plain `curl` there
is a different command.

## What it can do

| Command | What it does |
|---|---|
| `GET /status` | the bot, its unit, map, wave, core |
| `GET /events?since=N&type=chat&wait=60` | chat and server messages; `wait` waits for a new event |
| `POST /chat` (body) | send to chat, including server `/commands` |
| `POST /move?x=&y=`, `/stop` | walk to a point (core units fly over buildings) |
| `GET /map?x=&y=&w=&h=` | character map with a legend: a buildings layer and a floor/ore layer |
| `GET /blocks`, `/tile?x=&y=`, `/find?name=ore-copper` | buildings, everything about a tile, find ore and blocks |
| `GET /core`, `/units`, `/players`, `/content?type=block` | resources, units, players, block list |
| `POST /build?block=&x=&y=&rot=` | place a block; the bot flies into build range by itself |
| `POST /build?block=micro-processor&x=&y=&links=cell1@x,y;x,y` + mlog body | place a processor with its code and links at once |
| `GET/POST /processor?x=&y=` | read or replace processor code |
| `POST /link`, `/config`, `/rotate`, `/break` | links, block config, rotation, deconstruction |
| `POST /mine?x=&y=`, `/deposit`, `/take` | mine ore, deposit into the core, take from the core |
| `POST /command?units=all&x=&y=`, `/unitcommand` | orders for combat units |
| `POST /control?id=`, `/uncontrol` | possess a unit and return |
| `GET /menus`, `POST /menu?id=&option=` | answer server menus |
| `POST /batch` | several commands, one per line |
| `GET /displays`, `GET /display?x=&y=&zoom=2` | list logic displays and get an image of any of them (PNG) |
| `GET /view?x=&y=&w=&h=&scale=16` | snapshot of a map area with game sprites: floor, ore, buildings, units, display contents (PNG) |
| `GET /schematic/list`, `/schematic/info` | bot and game schematics (`%APPDATA%\Mindustry\schematics`), contents and cost |
| `POST /schematic/save?x=&y=&w=&h=&name=` | capture an area as a schematic: a `.msch` file and base64 for import in game |
| `POST /schematic/place?x=&y=&name=&rot=` | place a schematic; `mode=missing` builds what is missing and restores processor code |
| `POST /rebuild` | rebuild destroyed blocks from the team's "ghosts" |
| `GET /unit?id=`, `POST /stance`, `/boost`, `/payload/pick`, `/payload/drop`, `/turret`, `/rally` | unit details, stances, boosting, carrying units and buildings, turrets, factory rally points |

`GET /help` returns the full list. Coordinates are always in tiles. Responses are JSON (images come as PNG),
an error looks like this: `{"ok":false,"error":"..."}`.

## How the bot sees the screen

The bot does not render the game on screen, but it builds images itself:

- **Logic displays.** Processors also run on the client, so `draw` commands reach the bot.
  The bot renders them in software with the same font as the game (taken from `logic.ttf` of version 160.5).
  A seamless display up to 16×16 blocks counts as one and can be requested by any of its blocks.
- **Map.** `/view` draws an area with the game's sprites (they are inside the jar). Other teams' buildings are outlined in their team color,
  units get a circle, and a grid with coordinate labels runs every 10 tiles (`grid=0` removes it).
  `sprites=0` gives a fast colored minimap.

An image can be saved straight to a file: `save=C:/path/snapshot.png`. Claude Code can open such files
and see the image.

Note: a player's game only processes display commands while the display is on their screen,
and drops the excess when the queue overflows. The bot consumes commands every frame, so its image
can be more complete than what the player sees.

## Examples

```sh
B=http://127.0.0.1:6789
curl -s $B/status
curl -s -X POST $B/chat --data-binary "Hi! I'm a bot."
curl -s "$B/find?name=ore-copper&limit=5"
curl -s -X POST "$B/build?block=mechanical-drill&x=109&y=54"

# a processor with code from a file and a link to a memory cell at 126,40
curl -s -X POST "$B/build?block=memory-cell&x=126&y=40"
curl -s -X POST "$B/build?block=micro-processor&x=124&y=40&links=cell1@126,40" --data-binary @prog.mlog

# look at a display and at a map area
curl -s "$B/display?x=81&y=118&save=disp.png"
curl -s "$B/view?x=50&y=80&w=40&h=40&save=area.png"

# capture a schematic, place a copy to the right, then repair it after a griefer
curl -s -X POST "$B/schematic/save?x=50&y=84&w=16&h=28&name=mcpu"
curl -s -X POST "$B/schematic/place?x=100&y=84&name=mcpu"
curl -s -X POST "$B/schematic/place?x=100&y=84&name=mcpu&mode=missing"

# wait for the next chat message (up to 60 seconds)
curl -s "$B/events?type=chat&wait=60"
```

`--data-binary @file` sends the file as is. `-d @file` strips newlines and breaks mlog.

## How Claude Code works with it

Open this folder in Claude Code. The `CLAUDE.md` file explains its role and the commands.
Then ask, for example: "join the game, find copper near the core and place 4 drills with a conveyor to the core"
or "answer players in chat for the next 15 minutes".

Claude Code acts while it has a task. It does not sit in the chat around the clock on its own.

## Limitations

- Version v160.5 only. For another version: `set VERSION=vXXX` and `build.bat` (needs a JDK).
- The bot sees cell memory and processor values through its own copy of the world. That copy can lag
  slightly behind the server. Processor code and buildings come from the server and are exact.
- Ground non-core units (after `/control`) walk in a straight line and can get stuck on a wall.
  The server then puts them back.
- Other people's servers may not allow bots or may kick them for acting too fast.

## Building from source

`build.bat` (Windows) or `./build.sh`: downloads `server-release.jar` of the right version from GitHub,
compiles `src/mbot/*.java` and builds `mindustry-bot.jar` together with `assets/botassets`
(sprites and the display font from `Mindustry.jar` of the same version).
