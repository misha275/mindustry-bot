---
name: mindustry-bot
description: "Play Mindustry through the headless bot client (HTTP bridge 127.0.0.1:6789): view the map and displays as images, build, place schematics and mlog processors, command units, watch the chat."
---

# Playing Mindustry through the bot client

The bot (`mindustry-bot.jar`, built on Mindustry v160.5) joins a server as a separate player, ClaudeBot.
You are its brain: all control goes through HTTP requests to `http://127.0.0.1:6789`. The game and server are vanilla, no mods.
Talk to the user in English, and write in English in the game chat too.

The bot runs on the same machine as Claude Code: the bridge listens only on 127.0.0.1.
The Windows build ships with Java 17 in the `jre` folder; `run.bat` uses it automatically.

## 1. Startup

1. `curl -s http://127.0.0.1:6789/status`.
   - No connection: the bot is not running. Start it in the background from its folder:
     `java -jar mindustry-bot.jar --host 127.0.0.1 --port 6567 --name ClaudeBot`
     (on Windows use the bundled `jre/bin/java.exe`, or the Java that ships with the game,
     e.g. `"C:/Program Files (x86)/Steam/steamapps/common/Mindustry/jre/bin/java.exe"`).
   - `"ok":false` and "not in game": the server is not open or its version is not 160.5. Check `/events` for the reason.
     Ask the user to host a game (pause menu → Host Multiplayer Game) or give you the server address.
2. `GET /help` returns the full command list; trust it over memory. If it lacks `/view` and `/schematic/...`,
   the jar is outdated: ask the user to get the current release (or rebuild with `build.bat` / `build.sh`).
3. In PowerShell use `curl.exe`, not the `curl` alias. In Git Bash or a Unix shell plain `curl` works.
4. Send code bodies with `--data-binary @file`. `-d` strips newlines and breaks mlog.

## 2. Work loop: look → act → verify visually

Look as text:
- `/status` — unit, core, wave, rules (`infiniteResources`, `pvp`).
- `/core` — resources. Before building, compare with costs from `/content?type=block`.
- `/map?x=&y=&w=&h=` — two character layers with a legend: `blocks` (buildings) and `ground` (floor, ore).
  Rows go top to bottom (y decreasing), the number before `|` is y, the first character after `|` is `area.x`. Use windows of about 60×40.
- `/find?name=ore-copper` (ore-lead, ore-sand, ore-coal, ore-titanium, wall-ore-beryllium, or a block name).
- `/tile?x=&y=` — everything about a tile: items, power, message text (up to 2000 characters), config.
- `/blocks?team=sharded&block=`, `/units?team=crux`, `/unit?id=`, `/players`.

Look as an image (PNG; `save=path.png` writes a file, then open it with your image-reading tool):
- `GET /view?x=&y=&w=&h=&save=area.png` — a map area drawn with the game's sprites. x,y = bottom-left corner,
  `scale` pixels per tile (16 by default, up to 32), a coordinate grid every 10 tiles (`grid=0` removes it),
  `sprites=0` — fast colored minimap. At most 4096 px per side. Other teams' buildings are outlined in their team color.
- `GET /displays` — all logic displays: position, size in pixels, when last drawn.
- `GET /display?x=&y=&zoom=2&save=disp.png` — display contents (any block of a merged display).
  Drawn with the in-game logic font. The bot consumes draw commands every frame, so its picture can be more complete
  than what a player who is not looking at the display sees (see pitfalls).

Act:
- `POST /build?block=&x=&y=&rot=0..3` (0 right, 1 up, 2 left, 3 down). The bot flies there by itself.
  2×2 and 4×4 blocks are placed by the bottom-left of their central tiles, 3×3 by the center.
- `POST /batch`, body with one command per line. For large builds write a script that generates the batch, or use schematics.
- `/plans` — the build queue. `stuck:true` means not enough resources. `/clearplans` clears it.
- `/break?x=&y=[&w=&h=]`. Never break other players' buildings unless the player asks.
- `/config?x=&y=&value=` (number, true/false, item name, `p:dx,dy`, `s:string`), `/rotate`.
- Mining: `/move` to the ore, `/mine?x=&y=`; near the core mined items are deposited automatically, otherwise `/deposit`
  (or `/deposit?x=&y=` into any building). `/take?item=&amount=[&x=&y=]`.

Verify: after building, `/view` or `/blocks` over the same area; after a processor, `GET /processor`, `message1` via `/tile`, the display via `/display`.

## 3. Schematics and repair

- `POST /schematic/save?x=&y=&w=&h=&name=name` — capture an area. Response: `origin` (bottom-left corner),
  a `.msch` file in `botdata/schematics`, and `base64` the player can paste in game (Schematics → Import → from clipboard).
- `GET /schematic/list` — the bot's and the player's schematics (`%APPDATA%/Mindustry/schematics`). `GET /schematic/info?name=` — contents and cost.
- `POST /schematic/place?x=&y=&name=name` — place with its bottom-left corner at x,y. Also accepts `file=`, `base64=` or a body;
  `anchor=center` works like the in-game cursor, `rot=1..3` rotates, `dry=1` only checks. The response's `blocked` lists what does not fit and why.
- `mode=missing` — build only what is missing and restore configs and processor code that were changed. This is the repair after a griefer.
- `POST /rebuild[?x=&y=&w=&h=]` — rebuild destroyed blocks from the team's rebuild ghosts.

## 4. Units

- `/command?units=all|1,2&x=&y=` move to / attack a point or building; `&target=id` attack a unit; `&queue=1` queue the order.
- `/unitcommand?units=all&command=move|rebuild|assist|mine|boost`, `/stance?units=&stance=holdfire|pursuetarget|patrol|ram|holdposition|stop|mineauto&on=1`.
- `/control?id=` possess a unit, `/uncontrol` return. `/turret?x=&y=` take control of a turret.
- Payload units (poly, mega, quad, oct): possess, fly over, `/payload/pick?id=` or `?x=&y=`, `/payload/drop?x=&y=`.
- `/rally?x=&y=&tx=&ty=` factory rally point, `/boost?on=1|0|auto` mech boosting, `/aim?x=&y=&shoot=true` shooting.

## 5. Processors and mlog

- First place every block the processor links to (cell, bank, display, switch, message), then the processor.
- `POST /build?block=micro-processor&x=&y=&links=cell1@x,y;display1@x,y` with the mlog code as the body.
- Replace code: `POST /processor?x=&y=` (without `links` the existing links are kept). Read code: `GET /processor?x=&y=`.
- Conventions: jump only to labels (`loop:` … `jump loop always`), never to line numbers.
  Name links the way the game does: `cell1`, `bank1`, `message1`, `switch1`, `display1`.
  Always tell the user which processor (micro/logic/hyper) you used, where it stands and what is linked to it.
- The processor runs on the server (and as a copy on the client). Debug with `message1` (`print` + `printflush`) and `/tile`, graphics via `/display`.
- The bot sees cell memory through its own copy of the world, which may lag slightly. Code and buildings are exact.

## 6. Known pitfalls

- **A player's display only draws while it is on screen.** The game processes the display command queue (~1000 commands) only for a player
  who is looking at that display. If you redraw only changes, the player's screen can stay empty
  even though `/display` on the bot shows everything. Do a periodic full redraw (every few seconds).
- **Adjacent displays merge** into one seamless screen (up to 16×16 blocks). One link is enough;
  read the size with `sensor @displayWidth` / `@displayHeight`. Draw labels on "separate" displays with the shared screen in mind.
- **Pasting a schematic builds blocks one by one**, but the processor starts immediately. The code must wait until
  all its links exist (`@links` plus a `getlink` check), and screen code must wait until the size stays unchanged for ~2 s.
- **Schematics do not save bank and cell contents**; they do save processor code and message text.
  Store programs in messages (400 characters per message, `read c message1 i` gives the character code;
  past the end of the text you get `null`, not NaN) or in the loader's code.
- **Freshly placed switches can start pressed.** Have a keyboard processor release all buttons at startup.
- **Griefers.** If strangers can join, capture your systems in advance (`/schematic/save`); after a raid
  run `/schematic/place ... mode=missing` and `/rebuild`, then check with `/view`. Suggest the user close the server to strangers.
- **Don't touch the user's buildings** you were not asked to change (reactors etc.).
- Restarting the bot closes the user's `run.bat` window: warn them first.

## 7. Chat

- Read: `GET /events?since=<last>&type=chat&wait=60`, take `last` from the response.
- Write: `POST /chat` with the text as the body. In English, short, one message per question, no spam.
- Server menus: `/menus`, answer with `/menu?id=&option=`.
- Chat duty: loop "wait for a message → if it is addressed to the bot (its name or a leading `!`) → do it → reply".
  Skip your own messages (`[ClaudeBot]:`). Stay on duty only while your task is running.

## 8. Reporting to the user

Say what you did and where (coordinates), what you verified with a request or an image, and what only the user can check in the game.
