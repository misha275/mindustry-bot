# You play Mindustry through a bot

This folder holds a Mindustry bot client (`mindustry-bot.jar`). It is a separate player on the server,
and you are its brain. Control goes through HTTP at `http://127.0.0.1:6789`. Talk to the user in English,
and write in English in the game chat too.

## Startup

1. `curl -s http://127.0.0.1:6789/status`. If there is no connection, the bot is not running. Start it in the background:
   `java -jar mindustry-bot.jar --host 127.0.0.1 --port 6567 --name ClaudeBot`
   (on Windows without Java in PATH use the bundled `jre/bin/java.exe`, or the game's
   `"C:/Program Files (x86)/Steam/steamapps/common/Mindustry/jre/bin/java.exe"`).
2. If the response has `"ok":false` and "not in game", the server is not running or the version is wrong (the bot is v160.5).
   Ask the user to host a game.
3. `GET /help` returns the list of all commands.

In PowerShell call `curl.exe`, not `curl`. In Git Bash plain `curl` works.

## Looking at the world

- `/status`: where your unit is, what it carries, the core, the wave, the rules (`infiniteResources`, `pvp`).
- `/core`: resources. Before building, check there is enough for the block (`/content?type=block` shows costs).
- `/map?x=&y=&w=&h=` gives two character layers with a legend. `blocks` has buildings and walls, `ground` has floor and ore.
  Rows go top to bottom (y decreasing), the number before `|` is y, the first character after `|` is x from `area.x`.
  Don't request huge areas: 60×40 around the spot you need is usually enough.
- `/find?name=ore-copper` (also `ore-lead`, `ore-sand`, `ore-coal`, `ore-titanium`, `wall-ore-beryllium`, a block name):
  the nearest free tiles.
- `/tile?x=&y=`: tile details, items and power of the building.
- `/blocks?team=sharded`, `/units?team=crux`, `/players`.

## Acting

- Building: `POST /build?block=<name>&x=&y=&rot=0..3` (0 right, 1 up, 2 left, 3 down).
  The bot flies into build range by itself. Watch the queue in `/plans`; the error comes back immediately
  if the spot is taken. 2×2 and 4×4 blocks are placed by the bottom-left of their central tiles, 3×3 by the center.
- Many blocks at once: `POST /batch`, one command per line in the body (`/build?block=conveyor&x=10&y=5&rot=0`).
- Breaking: `/break?x=&y=[&w=&h=]`. Don't break other players' buildings unless a player asks.
- Mining: fly to the ore (`/move`), then `/mine?x=&y=`. Next to the core, mined items go into the core right away;
  otherwise deposit them with `/deposit`.
- Units: `/command?units=all&x=&y=` (or `type=dagger`), `/unitcommand?units=all&command=rebuild`.

## Processors (mlog)

- Place with code: `POST /build?block=micro-processor&x=&y=&links=cell1@x,y;display1@x,y`,
  with the code as the request body (`--data-binary @file.mlog`). Place the linked blocks first.
- Replace code: `POST /processor?x=&y=` with a body. Without `links` the links are kept.
- Read: `GET /processor?x=&y=`. Toggle a single link: `/link?x=&y=&tx=&ty=`.
- mlog conventions: jump only to labels (`loop:` and `jump loop always`), never to line numbers.
  Name links as the game does: `cell1`, `bank1`, `message1`, `switch1`, `display1`.
  Always say which processor you place (micro/logic/hyper) and what is linked to it.
- The processor runs on the server. Check that it works through `message1` (`/tile` shows its text)
  or by how units and blocks behave.

## Eyes: displays and snapshots

- `GET /displays` lists logic displays. `GET /display?x=&y=&save=disp.png` saves an image
  of a display (any block of a merged display); then open the file with the Read tool and look.
- `GET /view?x=&y=&w=&h=&save=area.png` gives a snapshot of an area with game sprites (x,y = bottom-left corner,
  scale = pixels per tile, 16 by default, a labeled grid every 10 tiles). At most 4096 px per side.
- Check your work visually: after placing a schematic or uploading code, look with `/view` or `/display`.

## Schematics and repair

- `POST /schematic/save?x=&y=&w=&h=&name=name` captures an area (response: `origin` = bottom-left corner, base64 for the player).
- `POST /schematic/place?x=&y=&name=name` places a schematic with its bottom-left corner at x,y (`anchor=center` like the in-game cursor,
  `rot=1..3` rotation, `dry=1` check only). The player's schematics from `%APPDATA%/Mindustry/schematics` show up in `/schematic/list`.
- After a griefer: `mode=missing` builds the missing blocks and restores configs and processor code,
  `POST /rebuild` rebuilds destroyed blocks from the team's ghosts.

## Units

- `GET /unit?id=` unit details, `POST /stance?units=all&stance=holdfire`, `/command?...&target=id` attack a unit.
- Carrying (poly, mega, quad...): `/control?id=`, fly over, `/payload/pick?id=` or `?x=&y=`, `/payload/drop?x=&y=`.
- `/turret?x=&y=` take control of a turret, `/rally?x=&y=&tx=&ty=` factory rally point, `/boost?on=1|0|auto` mech boosting.

## Chat and players

- Read: `GET /events?since=<last>&type=chat&wait=60` waits up to 60 seconds for a new message.
  Put the `last` number from the response into the next request.
- Write: `POST /chat` with a body. In English, keep it short, don't spam, at most one message per question.
- Server messages (`info`, `announce`, `menu`, `kick`) also arrive in `/events`. Answer menus with `/menu?id=&option=`.
- When the user asks you to watch the chat, run a loop: wait for a message; if it is addressed to the bot
  (mentions the bot's name or starts with `!`), do what it asks and reply. Skip your own messages
  (they contain `[ClaudeBot]:`).

## When something goes wrong

- `"bot is not in game"`: check `/events` for the disconnect reason. The bot reconnects by itself every 5 seconds.
- A block is not being built and `/plans` shows `stuck:true`: the core lacks resources.
- After `/control` the bot drives another unit; return with `/uncontrol`.
