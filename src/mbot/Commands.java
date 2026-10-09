package mbot;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.blocks.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * All bridge commands. They run on the game thread. Coordinates are in tiles everywhere.
 * They return a Map (sent as JSON) or throw Fail with an error message.
 */
public class Commands{
    static final int maxArea = 40000;
    static final int maxCodeBytes = 1024 * 100;

    public static class Fail extends RuntimeException{
        public Fail(String msg){ super(msg); }
    }

    static Fail fail(String msg){ return new Fail(msg); }

    /** Binary response (image). */
    public record Bin(byte[] data, String type){}

    /** PNG: return as is, or save to the save= file and answer JSON. */
    static Object image(Map<String, String> q, byte[] png){
        String save = q.get("save");
        if(save == null || save.isEmpty()) return new Bin(png, "image/png");
        try{
            java.io.File f = new java.io.File(save).getAbsoluteFile();
            if(f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), png);
            return Json.obj("ok", true, "file", f.getPath(), "bytes", png.length);
        }catch(java.io.IOException e){
            throw fail("could not write " + save + ": " + e.getMessage());
        }
    }

    public static Object run(String path, Map<String, String> q, String body){
        Bot bot = Bot.instance;
        switch(path){
            case "/", "/help" -> { return Json.obj("help", Bridge.help); }
            case "/status" -> { return status(); }
            case "/events" -> {
                long since = lng(q, "since", 0);
                return Json.obj("last", Bot.events.last(), "events", Bot.events.since(since, (int)lng(q, "limit", 200), q.get("type")));
            }
            case "/menus" -> { return Json.obj("menus", Bot.menus.list()); }
            case "/menu" -> {
                String value = q.containsKey("option") ? q.get("option") : q.containsKey("text") ? q.get("text") : body;
                String err = Bot.menus.answer((int)lng(q, "id", -1), value);
                if(err != null) throw fail(err);
                return ok();
            }
            case "/connect" -> {
                String host = q.getOrDefault("host", bot.host);
                int port = (int)lng(q, "port", bot.port);
                if(q.containsKey("name")){
                    player.name = q.get("name");
                    Bot.cfg.name = player.name;
                }
                bot.connect(host, port);
                return ok();
            }
            case "/disconnect" -> {
                bot.disconnect();
                return ok();
            }
        }

        if(!bot.inGame()) throw fail("bot is not in game (connection: " + (netClient.isConnecting() ? "in progress" : "none, " + bot.lastDisconnect) + ")");

        switch(path){
            case "/chat" -> {
                String text = q.containsKey("text") ? q.get("text") : body;
                if(text == null || text.isBlank()) throw fail("empty message");
                for(String line : text.split("\n")){
                    if(!line.isBlank()) Call.sendChatMessage(line.length() > maxTextLength ? line.substring(0, maxTextLength) : line);
                }
                return ok();
            }
            case "/move" -> {
                bot.moveTarget = new Vec2(flt(q, "x") * tilesize, flt(q, "y") * tilesize);
                return ok();
            }
            case "/stop" -> {
                bot.moveTarget = null;
                bot.shooting = false;
                return ok();
            }
            case "/approach" -> {
                bot.autoApproach = bool(q, "on", true);
                return ok();
            }
            case "/aim" -> {
                bot.aimX = flt(q, "x") * tilesize;
                bot.aimY = flt(q, "y") * tilesize;
                bot.shooting = bool(q, "shoot", true);
                return ok();
            }
            case "/units" -> { return units(q); }
            case "/players" -> {
                ArrayList<Object> out = new ArrayList<>();
                Groups.player.each(p -> out.add(Json.obj(
                    "id", p.id, "name", Strings.stripColors(p.name), "team", p.team().name, "admin", p.admin,
                    "unit", p.dead() ? null : p.unit().type.name, "x", p.x / tilesize, "y", p.y / tilesize, "self", p == player)));
                return Json.obj("players", out);
            }
            case "/core" -> { return core(); }
            case "/blocks" -> { return blocks(q); }
            case "/map" -> { return map(q); }
            case "/find" -> { return find(q); }
            case "/tile" -> { return tile(q); }
            case "/content" -> { return contentList(q); }
            case "/build" -> { return build(q, body); }
            case "/break" -> { return breakBlocks(q); }
            case "/plans" -> {
                ArrayList<Object> out = new ArrayList<>();
                if(!player.dead()){
                    for(BuildPlan p : player.unit().plans()){
                        out.add(Json.obj("block", p.breaking ? "break" : p.block.name, "x", p.x, "y", p.y, "rot", p.rotation,
                            "progress", p.progress, "stuck", p.stuck));
                    }
                }
                return Json.obj("plans", out);
            }
            case "/clearplans" -> {
                if(!player.dead()) player.unit().plans().clear();
                return ok();
            }
            case "/processor" -> {
                if(body == null || body.isEmpty()) body = q.get("code");
                return body == null || body.isEmpty() ? readProcessor(q) : writeProcessor(q, body);
            }
            case "/link" -> {
                Building b = building(q, "x", "y");
                Building t = building(q, "tx", "ty");
                Call.tileConfig(player, b, Point2.pack(t.tileX(), t.tileY()));
                return ok();
            }
            case "/config" -> {
                Building b = building(q, "x", "y");
                Call.tileConfig(player, b, parseConfig(q.containsKey("value") ? q.get("value") : body));
                return ok();
            }
            case "/rotate" -> {
                Call.rotateBlock(player, building(q, "x", "y"), !q.getOrDefault("dir", "cw").equals("ccw"));
                return ok();
            }
            case "/mine" -> {
                Unit unit = unit();
                if(!q.containsKey("x")){
                    unit.mineTile = null;
                    return ok();
                }
                Tile t = tileAt(q, "x", "y");
                if(!unit.canMine()) throw fail("unit " + unit.type.name + " cannot mine");
                if(!unit.validMine(t, false)) throw fail("this tile cannot be mined (ore? tier? range?)");
                unit.mineTile = t;
                return ok();
            }
            case "/deposit" -> {
                Unit unit = unit();
                Building c = q.containsKey("x") ? building(q, "x", "y") : unit.closestCore();
                if(c == null) throw fail("no core");
                if(unit.stack.amount <= 0) throw fail("carrying nothing");
                if(c.acceptStack(unit.stack.item, unit.stack.amount, unit) <= 0) throw fail(c.block.name + " does not accept " + unit.stack.item.name);
                if(!unit.within(c, itemTransferRange)) throw fail("too far: move closer (" + (int)(itemTransferRange / tilesize) + " tiles)");
                Call.transferInventory(player, c);
                return ok();
            }
            case "/take" -> {
                Unit unit = unit();
                Building from = q.containsKey("x") ? building(q, "x", "y") : unit.closestCore();
                Item item = content.item(q.getOrDefault("item", ""));
                if(item == null) throw fail("no such item: " + q.get("item"));
                Call.requestItem(player, from, item, (int)lng(q, "amount", unit.type.itemCapacity));
                return ok();
            }
            case "/drop" -> {
                Call.dropItem(unit().rotation);
                return ok();
            }
            case "/command" -> {
                int[] ids = unitIds(q);
                Unit tu = q.containsKey("target") ? Groups.unit.getByID((int)lng(q, "target", -1)) : null;
                if(q.containsKey("target") && tu == null) throw fail("no target unit with id " + q.get("target"));
                Vec2 target = tu != null ? new Vec2(tu.x, tu.y) : new Vec2(flt(q, "x") * tilesize, flt(q, "y") * tilesize);
                Building tb = tu != null ? null : world.buildWorld(target.x, target.y);
                Call.commandUnits(player, ids, tb != null && tb.team != player.team() ? tb : null, tu, target, bool(q, "queue", false), true);
                return Json.obj("ok", true, "units", ids.length);
            }
            case "/stance" -> {
                int[] ids = unitIds(q);
                String name = q.getOrDefault("stance", "").toLowerCase();
                var st = content.unitStances().find(c -> c.name.toLowerCase().equals(name));
                if(st == null) throw fail("no such stance. Available: " + content.unitStances().map(c -> c.name).toString(", "));
                Call.setUnitStance(player, ids, st, bool(q, "on", true));
                return Json.obj("ok", true, "units", ids.length);
            }
            case "/boost" -> {
                String v = q.getOrDefault("on", "auto");
                bot.boost = v.equals("auto") ? null : bool(q, "on", true);
                return Json.obj("ok", true, "canBoost", !player.dead() && player.unit().type.canBoost);
            }
            case "/unit" -> {
                Unit u = q.containsKey("id") ? Groups.unit.getByID((int)lng(q, "id", -1)) : unit();
                if(u == null) throw fail("no unit with this id");
                return unitInfo(u);
            }
            case "/payload/pick" -> {
                Unit u = unit();
                if(!(u instanceof Payloadc pay)) throw fail(u.type.name + " cannot carry payloads (need poly, mega, quad, oct...)");
                if(q.containsKey("id")){
                    Unit target = Groups.unit.getByID((int)lng(q, "id", -1));
                    if(target == null) throw fail("no unit with this id");
                    if(!pay.canPickup(target)) throw fail("cannot pick up " + target.type.name + " (too big, other team or payload full)");
                    if(!u.within(target, u.type.hitSize * 2f + target.type.hitSize)) throw fail("fly closer to the unit");
                    Call.requestUnitPayload(player, target);
                }else{
                    Building b = building(q, "x", "y");
                    if(!pay.canPickup(b)) throw fail("cannot pick up " + b.block.name + " (size, team or payload full)");
                    if(!u.within(b, tilesize * 2f + b.block.size * tilesize / 2f)) throw fail("fly closer to the building");
                    Call.requestBuildPayload(player, b);
                }
                return ok();
            }
            case "/payload/drop" -> {
                Unit u = unit();
                if(!(u instanceof Payloadc pay) || pay.payloads().isEmpty()) throw fail("payload is empty");
                float px = q.containsKey("x") ? flt(q, "x") * tilesize : u.x, py = q.containsKey("y") ? flt(q, "y") * tilesize : u.y;
                Call.requestDropPayload(player, px, py);
                return ok();
            }
            case "/turret" -> {
                Building b = building(q, "x", "y");
                if(!(b instanceof ControlBlock cb) || !cb.canControl()) throw fail(b.block.name + " cannot be controlled");
                Call.buildingControlSelect(player, b);
                return ok();
            }
            case "/rally" -> {
                Building b = building(q, "x", "y");
                if(!b.block.commandable) throw fail(b.block.name + " does not take a rally point");
                Call.commandBuilding(player, new int[]{b.pos()}, new Vec2(flt(q, "tx") * tilesize, flt(q, "ty") * tilesize));
                return ok();
            }
            case "/displays" -> { return Screen.displays(); }
            case "/display" -> { return image(q, Screen.display((int)lng(q, "x", -1), (int)lng(q, "y", -1), (int)lng(q, "zoom", 2))); }
            case "/view" -> {
                int scale = (int)Math.max(2, Math.min(lng(q, "scale", 16), 32));
                int w = (int)lng(q, "w", 40), h = (int)lng(q, "h", 30);
                int x = q.containsKey("x") ? (int)lng(q, "x", 0) : player.tileX() - w / 2, y = q.containsKey("y") ? (int)lng(q, "y", 0) : player.tileY() - h / 2;
                if((long)w * scale > 4096 || (long)h * scale > 4096) throw fail("image larger than 4096 px: reduce w,h or scale");
                return image(q, Screen.view(x, y, w, h, scale, bool(q, "sprites", true), bool(q, "units", true), (int)lng(q, "grid", 10)));
            }
            case "/schematic/list" -> { return Schem.list(); }
            case "/schematic/save" -> { return Schem.save(q); }
            case "/schematic/info" -> { return Schem.info(q, body); }
            case "/schematic/place" -> { return Schem.place(q, body); }
            case "/rebuild" -> { return Schem.rebuild(q); }
            case "/unitcommand" -> {
                int[] ids = unitIds(q);
                var cmd = content.unitCommands().find(c -> c.name.equals(q.getOrDefault("command", "")));
                if(cmd == null) throw fail("no such command. Available: " + content.unitCommands().map(c -> c.name).toString(", "));
                Call.setUnitCommand(player, ids, cmd);
                return Json.obj("ok", true, "units", ids.length);
            }
            case "/control" -> {
                Unit u = Groups.unit.getByID((int)lng(q, "id", -1));
                if(u == null) throw fail("no unit with this id");
                Call.unitControl(player, u);
                return ok();
            }
            case "/uncontrol" -> {
                Call.unitClear(player);
                return ok();
            }
        }
        throw fail("unknown command " + path + ", list: GET /help");
    }

    static Object unitInfo(Unit u){
        var m = Json.obj("id", u.id, "type", u.type.name, "team", u.team.name, "x", u.x / tilesize, "y", u.y / tilesize,
            "rotation", u.rotation, "health", u.health, "maxHealth", u.maxHealth, "shield", u.shield, "armor", u.armor,
            "item", u.stack.amount > 0 ? u.stack.item.name + ":" + u.stack.amount : null, "itemCapacity", u.type.itemCapacity,
            "flying", u.isFlying(), "player", u.isPlayer() ? Strings.stripColors(u.getPlayer().name) : null,
            "controller", u.controller().getClass().getSimpleName(),
            "range", u.range() / tilesize, "speedTilesPerSec", u.speed() * 60f / tilesize,
            "canBuild", u.canBuild(), "canMine", u.canMine(), "mineTier", u.type.mineTier, "canBoost", u.type.canBoost,
            "mining", u.mineTile == null ? null : u.mineTile.x + "," + u.mineTile.y);
        if(u.isCommandable()){
            var ai = u.command();
            m.put("command", ai.command == null ? null : ai.command.name);
            ArrayList<String> st = new ArrayList<>();
            for(var s : content.unitStances()) if(ai.hasStance(s)) st.add(s.name);
            m.put("stances", st);
            m.put("target", ai.targetPos == null ? null : ai.targetPos.x / tilesize + "," + ai.targetPos.y / tilesize);
        }
        if(u instanceof Payloadc pay){
            ArrayList<String> loads = new ArrayList<>();
            for(var p : pay.payloads()) loads.add(p.content().name);
            m.put("payload", loads);
            m.put("payloadCapacityTiles", u.type.payloadCapacity / tilesize / tilesize);
        }
        ArrayList<String> effects = new ArrayList<>();
        for(var e : content.statusEffects()) if(u.hasEffect(e) && e != mindustry.content.StatusEffects.none) effects.add(e.name);
        if(!effects.isEmpty()) m.put("effects", effects);
        return m;
    }

    static Map<String, Object> ok(){
        return Json.obj("ok", true);
    }

    // ---------- state ----------

    static Object status(){
        Bot bot = Bot.instance;
        Unit u = player.dead() ? null : player.unit();
        var core = player.team().core();
        return Json.obj(
            "ok", true,
            "server", bot.host + ":" + bot.port,
            "name", Strings.stripColors(player.name),
            "playerId", player.id,
            "team", player.team().name,
            "map", Json.obj("name", state.map == null ? null : state.map.plainName(), "width", world.width(), "height", world.height()),
            "wave", state.wave, "waveCountdown", (int)(state.wavetime / 60f), "enemies", state.enemies,
            "rules", Json.obj("pvp", state.rules.pvp, "infiniteResources", state.rules.infiniteResources, "waves", state.rules.waves,
                "attack", state.rules.attackMode, "unitCap", state.rules.unitCap, "fog", state.rules.fog),
            "unit", u == null ? null : Json.obj("id", u.id, "type", u.type.name, "x", u.x / tilesize, "y", u.y / tilesize,
                "health", u.health, "maxHealth", u.maxHealth,
                "item", u.stack.amount > 0 ? u.stack.item.name + ":" + u.stack.amount : null,
                "mining", u.mineTile == null ? null : u.mineTile.x + "," + u.mineTile.y,
                "buildRange", u.type.buildRange / tilesize, "canBuild", u.canBuild(), "canMine", u.canMine(),
                "mineTier", u.type.mineTier, "speedTilesPerSec", u.type.speed * 0.9f * 60f / tilesize),
            "dead", player.dead(),
            "moving", bot.moveTarget == null ? null : (bot.moveTarget.x / tilesize) + "," + (bot.moveTarget.y / tilesize),
            "plans", u == null ? 0 : u.plans().size,
            "core", core == null ? null : Json.obj("block", core.block.name, "x", core.tileX(), "y", core.tileY()),
            "openMenus", Bot.menus.list().size(),
            "lastEvent", Bot.events.last()
        );
    }

    static Object core(){
        ArrayList<Object> cores = new ArrayList<>();
        for(var c : player.team().cores()){
            cores.add(Json.obj("block", c.block.name, "x", c.tileX(), "y", c.tileY()));
        }
        var core = player.team().core();
        LinkedHashMap<String, Object> items = new LinkedHashMap<>();
        if(core != null) core.items.each((item, amount) -> items.put(item.name, amount));
        return Json.obj("cores", cores, "items", items, "capacity", core == null ? 0 : core.storageCapacity);
    }

    static Object units(Map<String, String> q){
        String team = q.get("team"), type = q.get("type");
        float r = flt(q, "radius", -1) * tilesize;
        float cx = q.containsKey("x") ? flt(q, "x") * tilesize : player.x, cy = q.containsKey("y") ? flt(q, "y") * tilesize : player.y;
        int limit = (int)lng(q, "limit", 300);
        ArrayList<Object> out = new ArrayList<>();
        for(Unit u : Groups.unit){
            if(team != null && !u.team.name.equals(team)) continue;
            if(type != null && !u.type.name.equals(type)) continue;
            if(r > 0 && !u.within(cx, cy, r)) continue;
            if(out.size() >= limit) break;
            out.add(Json.obj("id", u.id, "type", u.type.name, "team", u.team.name, "x", u.x / tilesize, "y", u.y / tilesize,
                "health", u.health, "player", u.isPlayer() ? Strings.stripColors(u.getPlayer().name) : null,
                "command", u.isCommandable() && u.command().command != null ? u.command().command.name : null,
                "item", u.stack.amount > 0 ? u.stack.item.name + ":" + u.stack.amount : null,
                "flying", u.isFlying()));
        }
        return Json.obj("units", out);
    }

    static int[] rect(Map<String, String> q, int defSize){
        int w = (int)lng(q, "w", -1), h = (int)lng(q, "h", -1);
        int x, y;
        if(w < 0 && h < 0 && !q.containsKey("x") && world.width() * world.height() <= maxArea){
            return new int[]{0, 0, world.width(), world.height()};
        }
        if(w < 0) w = defSize;
        if(h < 0) h = defSize;
        if(q.containsKey("x")){
            x = (int)lng(q, "x", 0);
            y = (int)lng(q, "y", 0);
        }else{
            x = player.tileX() - w / 2;
            y = player.tileY() - h / 2;
        }
        int x2 = Math.min(x + w, world.width()), y2 = Math.min(y + h, world.height());
        x = Math.max(x, 0);
        y = Math.max(y, 0);
        if((x2 - x) * (y2 - y) > maxArea) throw fail("area larger than " + maxArea + " tiles, give x,y,w,h");
        return new int[]{x, y, x2 - x, y2 - y};
    }

    static Object blocks(Map<String, String> q){
        int[] r = rect(q, 100);
        String team = q.get("team"), name = q.get("block");
        ArrayList<Object> out = new ArrayList<>();
        for(int y = r[1]; y < r[1] + r[3]; y++){
            for(int x = r[0]; x < r[0] + r[2]; x++){
                Tile t = world.tile(x, y);
                if(t == null || t.build == null || !t.isCenter()) continue;
                Building b = t.build;
                if(team != null && !b.team.name.equals(team)) continue;
                if(name != null && !b.block.name.equals(name)) continue;
                out.add(buildingInfo(b, false));
            }
        }
        return Json.obj("area", r, "blocks", out);
    }

    static Map<String, Object> buildingInfo(Building b, boolean full){
        var m = Json.obj("block", b.block.name, "x", b.tileX(), "y", b.tileY(), "team", b.team.name,
            "size", b.block.size, "health", b.healthf());
        if(b.block.rotate) m.put("rot", b.rotation);
        Object cfg = b instanceof LogicBuild ? null : b.config();
        if(cfg != null) m.put("config", configString(cfg));
        if(!b.enabled) m.put("enabled", false);
        if(b instanceof LogicBuild lb) m.put("links", lb.links.size);
        if(full){
            if(b.items != null && b.items.any()){
                LinkedHashMap<String, Object> items = new LinkedHashMap<>();
                b.items.each((item, amount) -> items.put(item.name, amount));
                m.put("items", items);
            }
            if(b.liquids != null && b.liquids.currentAmount() > 0.01f){
                m.put("liquid", b.liquids.current().name + ":" + b.liquids.currentAmount());
            }
            if(b.power != null && b.power.graph != null){
                m.put("power", Json.obj("status", b.power.status, "balance", b.power.graph.getPowerBalance() * 60f,
                    "stored", b.power.graph.getLastPowerStored()));
            }
        }
        return m;
    }

    static String configString(Object cfg){
        if(cfg instanceof UnlockableContent c) return c.name;
        if(cfg instanceof Point2 p) return p.x + "," + p.y;
        if(cfg instanceof Point2[] ps){
            StringBuilder sb = new StringBuilder();
            for(Point2 p : ps) sb.append(sb.length() == 0 ? "" : ";").append(p.x).append(",").append(p.y);
            return sb.toString();
        }
        if(cfg instanceof byte[]) return "bytes";
        String s = String.valueOf(cfg);
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }

    static Object map(Map<String, String> q){
        int[] r = rect(q, 80);
        Legend bl = new Legend(), gl = new Legend();
        bl.fixed('.', "empty");
        ArrayList<String> brows = new ArrayList<>(), grows = new ArrayList<>();
        for(int y = r[1] + r[3] - 1; y >= r[1]; y--){
            StringBuilder b = new StringBuilder(), g = new StringBuilder();
            for(int x = r[0]; x < r[0] + r[2]; x++){
                Tile t = world.tile(x, y);
                Block block = t.block();
                if(block == Blocks.air){
                    b.append('.');
                }else if(t.build != null){
                    b.append(bl.get(block.name + (t.build.team == player.team() ? "" : " (" + t.build.team.name + ")")));
                }else{
                    b.append(bl.get(block.name + (block instanceof StaticWall && t.wallDrop() != null ? " [" + t.wallDrop().name + "]" : "")));
                }
                Floor ore = t.overlay();
                if(ore != Blocks.air && ore.itemDrop != null){
                    g.append(gl.get("ore " + ore.itemDrop.name));
                }else{
                    g.append(gl.get(t.floor().name + (t.floor().isDeep() ? " (deep)" : "")));
                }
            }
            brows.add((y) + "|" + b);
            grows.add((y) + "|" + g);
        }
        return Json.obj("area", Json.obj("x", r[0], "y", r[1], "w", r[2], "h", r[3]),
            "note", "rows top to bottom (y decreasing), row number = y, first character after | = x " + r[0],
            "blocks", Json.obj("legend", bl.map, "rows", brows),
            "ground", Json.obj("legend", gl.map, "rows", grows));
    }

    static class Legend{
        static final String pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@$%&*+=?!<>^~:;-_/()[]{}";
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        HashMap<String, Character> byName = new HashMap<>();
        HashSet<Character> used = new HashSet<>();

        void fixed(char c, String name){
            used.add(c);
            map.put(String.valueOf(c), name);
        }

        char get(String name){
            Character c = byName.get(name);
            if(c != null) return c;
            char pick = 0;
            String clean = name.replace("ore ", "");
            for(char ch : new char[]{clean.charAt(0), Character.toUpperCase(clean.charAt(0))}){
                if(!used.contains(ch)){ pick = ch; break; }
            }
            if(pick == 0){
                for(char ch : pool.toCharArray()){
                    if(!used.contains(ch)){ pick = ch; break; }
                }
            }
            if(pick == 0) pick = '?';
            used.add(pick);
            byName.put(name, pick);
            map.put(String.valueOf(pick), name);
            return pick;
        }
    }

    static Object find(Map<String, String> q){
        String name = q.getOrDefault("name", "");
        int limit = (int)lng(q, "limit", 100);
        ArrayList<Tile> found = new ArrayList<>();
        for(Tile t : world.tiles){
            boolean match = (t.build != null ? t.isCenter() && t.block().name.equals(name) : t.block().name.equals(name))
                || t.floor().name.equals(name) || (t.overlay().name.equals(name) && !name.startsWith("ore-"))
                || (t.block() == Blocks.air && t.drop() != null && name.equals("ore-" + t.drop().name))
                || (t.block() instanceof StaticWall && t.wallDrop() != null && name.equals("wall-ore-" + t.wallDrop().name));
            if(match) found.add(t);
        }
        found.sort(Comparator.comparingDouble(t -> t.dst2(player)));
        ArrayList<Object> out = new ArrayList<>();
        for(int i = 0; i < Math.min(limit, found.size()); i++){
            Tile t = found.get(i);
            out.add(new int[]{t.x, t.y});
        }
        return Json.obj("name", name, "total", found.size(), "nearest", out);
    }

    static Object tile(Map<String, String> q){
        Tile t = tileAt(q, "x", "y");
        var m = Json.obj("x", t.x, "y", t.y, "floor", t.floor().name, "overlay", t.overlay().name, "block", t.block().name,
            "team", t.team().name, "ore", t.drop() == null ? null : t.drop().name, "wallOre", t.wallDrop() == null ? null : t.wallDrop().name,
            "solid", t.solid());
        if(t.build != null) m.put("building", buildingInfo(t.build, true));
        return m;
    }

    static Object contentList(Map<String, String> q){
        String type = q.getOrDefault("type", "block");
        String cat = q.get("category");
        ArrayList<Object> out = new ArrayList<>();
        switch(type){
            case "block" -> {
                for(Block b : content.blocks()){
                    if(!b.isPlaceable() || b.buildVisibility == mindustry.world.meta.BuildVisibility.hidden) continue;
                    if(cat != null && !b.category.name().equals(cat)) continue;
                    LinkedHashMap<String, Object> req = new LinkedHashMap<>();
                    for(ItemStack s : b.requirements) req.put(s.item.name, s.amount);
                    out.add(Json.obj("name", b.name, "category", b.category.name(), "size", b.size, "rotate", b.rotate,
                        "cost", req, "banned", state.rules.isBanned(b), "logic", b instanceof LogicBlock));
                }
            }
            case "item" -> content.items().each(i -> out.add(Json.obj("name", i.name, "hardness", i.hardness)));
            case "liquid" -> content.liquids().each(l -> out.add(l.name));
            case "unit" -> content.units().each(u -> out.add(Json.obj("name", u.name, "flying", u.flying, "health", u.health,
                "speed", u.speed, "canBuild", u.buildSpeed > 0, "mineTier", u.mineTier)));
            default -> throw fail("type: block | item | liquid | unit");
        }
        return Json.obj("type", type, "list", out);
    }

    // ---------- building ----------

    static Object build(Map<String, String> q, String body){
        Unit unit = unit();
        if(!unit.canBuild()) throw fail("unit " + unit.type.name + " cannot build");
        Block block = content.block(q.getOrDefault("block", ""));
        if(block == null) throw fail("no such block: " + q.get("block") + " (list: /content?type=block)");
        int x = (int)lng(q, "x", -1), y = (int)lng(q, "y", -1), rot = (int)lng(q, "rot", 0) & 3;
        if(state.rules.isBanned(block)) throw fail("block is banned on this server");
        if(!Build.validPlace(block, player.team(), x, y, rot)){
            Tile t = world.tile(x, y);
            throw fail("cannot place " + block.name + " here (tile " + x + "," + y + ": " + (t == null ? "outside the map" : t.block().name + " on " + t.floor().name) + ")");
        }
        Object config = null;
        if(block instanceof LogicBlock){
            String code = body == null || body.isEmpty() ? q.getOrDefault("code", "") : body;
            config = LogicBlock.compress(normalize(code), parseLinks(q.get("links"), x, y, true));
        }else if(q.containsKey("config")){
            config = parseConfig(q.get("config"));
        }
        unit.addBuild(new BuildPlan(x, y, rot, block, config));
        return Json.obj("ok", true, "queued", unit.plans().size);
    }

    static Object breakBlocks(Map<String, String> q){
        Unit unit = unit();
        int x = (int)lng(q, "x", -1), y = (int)lng(q, "y", -1);
        int w = (int)lng(q, "w", 1), h = (int)lng(q, "h", 1);
        int n = 0;
        HashSet<Building> seen = new HashSet<>();
        for(int ty = y; ty < y + h; ty++){
            for(int tx = x; tx < x + w; tx++){
                Tile t = world.tile(tx, ty);
                if(t == null) continue;
                if(t.build != null){
                    if(!seen.add(t.build)) continue;
                    if(!Build.validBreak(player.team(), t.build.tileX(), t.build.tileY())) continue;
                    unit.addBuild(new BuildPlan(t.build.tileX(), t.build.tileY()));
                    n++;
                }else if(t.block() != Blocks.air && Build.validBreak(player.team(), tx, ty)){
                    unit.addBuild(new BuildPlan(tx, ty));
                    n++;
                }
            }
        }
        if(n == 0) throw fail("nothing to break here");
        return Json.obj("ok", true, "queued", n);
    }

    static String normalize(String code){
        code = code.replace("\r\n", "\n").replace('\r', '\n');
        if(code.getBytes(Vars.charset).length > maxCodeBytes) throw fail("code longer than " + maxCodeBytes + " bytes");
        return code;
    }

    /**
     * links: "x,y;x,y" or "cell1@x,y;display1@x,y". Coordinates are absolute (tiles).
     * Names not given explicitly are chosen like in game: cell1, cell2, bank1, message1...
     */
    static Seq<LogicLink> parseLinks(String spec, int px, int py, boolean relative){
        Seq<LogicLink> links = new Seq<>();
        if(spec == null || spec.isBlank()) return links;
        HashMap<String, Integer> counters = new HashMap<>();
        for(String part : spec.split(";")){
            part = part.trim();
            if(part.isEmpty()) continue;
            String name = null;
            if(part.contains("@")){
                name = part.substring(0, part.indexOf('@')).trim();
                part = part.substring(part.indexOf('@') + 1);
            }
            String[] xy = part.split(",");
            if(xy.length != 2) throw fail("link must be x,y or name@x,y: " + part);
            int lx = Integer.parseInt(xy[0].trim()), ly = Integer.parseInt(xy[1].trim());
            Building b = world.build(lx, ly);
            if(b != null){
                lx = b.tileX();
                ly = b.tileY();
            }
            if(name == null){
                String base = b == null ? "link" : LogicBlock.getLinkName(b.block);
                int idx = counters.merge(base, 1, Integer::sum);
                name = base + idx;
            }
            links.add(new LogicLink(relative ? lx - px : lx, relative ? ly - py : ly, name, true));
        }
        return links;
    }

    static Object readProcessor(Map<String, String> q){
        Building b = building(q, "x", "y");
        if(!(b instanceof LogicBuild lb)) throw fail("not a processor: " + b.block.name);
        ArrayList<Object> links = new ArrayList<>();
        for(LogicLink l : lb.links){
            Building t = world.build(l.x, l.y);
            links.add(Json.obj("name", l.name, "x", l.x, "y", l.y, "valid", l.valid, "block", t == null ? null : t.block.name));
        }
        return Json.obj("block", b.block.name, "x", b.tileX(), "y", b.tileY(), "code", lb.code, "links", links);
    }

    static Object writeProcessor(Map<String, String> q, String code){
        Building b = building(q, "x", "y");
        if(!(b instanceof LogicBuild lb)) throw fail("not a processor: " + b.block.name);
        Seq<LogicLink> links;
        if(q.containsKey("links")){
            links = parseLinks(q.get("links"), b.tileX(), b.tileY(), true);
        }else{
            links = new Seq<>();
            for(LogicLink l : lb.links){
                links.add(new LogicLink(l.x - b.tileX(), l.y - b.tileY(), l.name, true));
            }
        }
        Call.tileConfig(player, b, LogicBlock.compress(normalize(code), links));
        return Json.obj("ok", true, "links", links.size);
    }

    static Object parseConfig(String v){
        if(v == null || v.equals("null")) return null;
        if(v.startsWith("s:")) return v.substring(2);
        if(v.equals("true") || v.equals("false")) return Boolean.parseBoolean(v);
        if(v.startsWith("p:")){
            String[] xy = v.substring(2).split(",");
            return new Point2(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
        }
        try{
            return Integer.parseInt(v);
        }catch(NumberFormatException ignored){}
        for(ContentType type : new ContentType[]{ContentType.item, ContentType.liquid, ContentType.block, ContentType.unit}){
            var c = content.getByName(type, v);
            if(c != null) return c;
        }
        return v;
    }

    // ---------- helpers ----------

    static int[] unitIds(Map<String, String> q){
        IntSeq ids = new IntSeq();
        String spec = q.getOrDefault("units", "");
        String type = q.get("type");
        if(spec.equals("all") || type != null){
            for(Unit u : Groups.unit){
                if(u.team == player.team() && u.isCommandable() && !u.isPlayer() && (type == null || u.type.name.equals(type))) ids.add(u.id);
            }
        }else{
            for(String s : spec.split(",")){
                if(!s.isBlank()) ids.add(Integer.parseInt(s.trim()));
            }
        }
        if(ids.isEmpty()) throw fail("no units (units=1,2,3 | units=all | type=dagger)");
        return ids.toArray();
    }

    static Unit unit(){
        if(player.dead()) throw fail("the bot has no unit right now (waiting to respawn)");
        return player.unit();
    }

    static Tile tileAt(Map<String, String> q, String kx, String ky){
        int x = (int)lng(q, kx, -1), y = (int)lng(q, ky, -1);
        Tile t = world.tile(x, y);
        if(t == null) throw fail("tile " + x + "," + y + " is outside the map");
        return t;
    }

    static Building building(Map<String, String> q, String kx, String ky){
        Tile t = tileAt(q, kx, ky);
        if(t.build == null) throw fail("no building at " + t.x + "," + t.y);
        return t.build;
    }

    static long lng(Map<String, String> q, String k, long def){
        String v = q.get(k);
        if(v == null || v.isEmpty()) return def;
        try{
            return Math.round(Double.parseDouble(v));
        }catch(NumberFormatException e){
            throw fail("parameter " + k + " must be a number");
        }
    }

    static float flt(Map<String, String> q, String k){
        if(!q.containsKey(k)) throw fail("missing parameter " + k);
        return flt(q, k, 0);
    }

    static float flt(Map<String, String> q, String k, float def){
        String v = q.get(k);
        if(v == null || v.isEmpty()) return def;
        try{
            return Float.parseFloat(v);
        }catch(NumberFormatException e){
            throw fail("parameter " + k + " must be a number");
        }
    }

    static boolean bool(Map<String, String> q, String k, boolean def){
        String v = q.get(k);
        if(v == null || v.isEmpty()) return def;
        return v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes");
    }
}
