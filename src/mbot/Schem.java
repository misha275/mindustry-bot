package mbot;

import arc.struct.*;
import arc.util.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Schematics: capture an area, save, place (fully or only what is missing),
 * rebuild destroyed blocks from the team's "ghosts".
 */
public class Schem{
    static Commands.Fail fail(String msg){ return new Commands.Fail(msg); }

    /** Schematic folders: our own (botdata/schematics) and the game's folder, if found. */
    static ArrayList<File> dirs(){
        ArrayList<File> out = new ArrayList<>();
        out.add(ownDir());
        if(Bot.cfg.schematicsDir != null) out.add(new File(Bot.cfg.schematicsDir));
        String appdata = System.getenv("APPDATA");
        if(appdata != null) out.add(new File(appdata, "Mindustry/schematics"));
        String home = System.getProperty("user.home");
        out.add(new File(home, ".local/share/Mindustry/schematics"));
        out.add(new File(home, "Library/Application Support/Mindustry/schematics"));
        out.removeIf(f -> !f.isDirectory());
        return out;
    }

    static File ownDir(){
        File f = new File(Bot.cfg.dataDir, "schematics");
        f.mkdirs();
        return f;
    }

    public static Object list(){
        ArrayList<Object> out = new ArrayList<>();
        for(File dir : dirs()){
            File[] files = dir.listFiles((d, n) -> n.endsWith(".msch"));
            if(files == null) continue;
            Arrays.sort(files);
            for(File f : files){
                try{
                    Schematic s = Schematics.read(new FileInputStream(f));
                    out.add(Json.obj("name", f.getName().replace(".msch", ""), "title", s.name(), "size", s.width + "x" + s.height,
                        "blocks", s.tiles.size, "file", f.getAbsolutePath()));
                }catch(Exception e){
                    out.add(Json.obj("name", f.getName(), "error", Strings.getSimpleMessage(e)));
                }
            }
        }
        return Json.obj("dirs", dirs().stream().map(File::getAbsolutePath).toList(), "schematics", out);
    }

    /** Schematic from parameters: name= (file in the schematic folders), file= (path), base64= or the request body. */
    static Schematic load(Map<String, String> q, String body){
        try{
            if(q.containsKey("name")){
                String name = q.get("name");
                for(File dir : dirs()){
                    File f = new File(dir, name.endsWith(".msch") ? name : name + ".msch");
                    if(f.isFile()) return Schematics.read(new FileInputStream(f));
                }
                for(File dir : dirs()){
                    File[] files = dir.listFiles((d, n) -> n.endsWith(".msch"));
                    if(files == null) continue;
                    for(File f : files){
                        Schematic s = Schematics.read(new FileInputStream(f));
                        if(s.name().equalsIgnoreCase(name)) return s;
                    }
                }
                throw fail("schematic " + name + " not found (list: /schematic/list)");
            }
            if(q.containsKey("file")) return Schematics.read(new FileInputStream(q.get("file")));
            String b64 = q.containsKey("base64") ? q.get("base64") : body;
            if(b64 == null || b64.isBlank()) throw fail("give name=, file= or base64 of a schematic (the request body works too)");
            return Schematics.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64.trim().replace("\n", "").replace("\r", "").replace(' ', '+'))));
        }catch(Commands.Fail f){
            throw f;
        }catch(Exception e){
            throw fail("could not read schematic: " + Strings.getSimpleMessage(e));
        }
    }

    static String base64(Schematic s){
        try{
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Schematics.write(s, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    /** Capture area x,y..x+w-1,y+h-1 (or x1,y1,x2,y2) into a schematic. No visibility checks, same as a player. */
    static Schematic create(int x1, int y1, int x2, int y2, String team){
        int x = Math.min(x1, x2), y = Math.min(y1, y2), xe = Math.max(x1, x2), ye = Math.max(y1, y2);
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
        LinkedHashSet<Building> builds = new LinkedHashSet<>();
        for(int cx = x; cx <= xe; cx++){
            for(int cy = y; cy <= ye; cy++){
                Building b = world.build(cx, cy);
                if(b == null) continue;
                if(team != null && !b.team.name.equals(team)) continue;
                Block real = b instanceof ConstructBuild cons ? cons.current : b.block;
                if(real == null || (b instanceof ConstructBuild cons && cons.current == null)) continue;
                if(builds.add(b)){
                    int top = real.size / 2, bot = real.size % 2 == 1 ? -real.size / 2 : -(real.size - 1) / 2;
                    minx = Math.min(b.tileX() + bot, minx);
                    miny = Math.min(b.tileY() + bot, miny);
                    maxx = Math.max(b.tileX() + top, maxx);
                    maxy = Math.max(b.tileY() + top, maxy);
                }
            }
        }
        if(builds.isEmpty()) throw fail("no buildings in the area");
        Seq<Stile> tiles = new Seq<>();
        for(Building b : builds){
            Block real = b instanceof ConstructBuild cons ? cons.current : b.block;
            Object config = b instanceof ConstructBuild cons ? cons.lastConfig : b.config();
            tiles.add(new Stile(real, b.tileX() - minx, b.tileY() - miny, config, (byte)b.rotation));
        }
        Schematic s = new Schematic(tiles, new StringMap(), maxx - minx + 1, maxy - miny + 1);
        s.labels.add(minx + "," + miny);
        return s;
    }

    /** Bottom-left corner of the schematic on the map if its center is placed at x,y (like in game). */
    static int offset(int size){
        return size % 2 == 1 ? -size / 2 : -(size - 1) / 2;
    }

    public static Object save(Map<String, String> q){
        int x1, y1, x2, y2;
        if(q.containsKey("x2")){
            x1 = (int)Commands.lng(q, "x", 0);
            y1 = (int)Commands.lng(q, "y", 0);
            x2 = (int)Commands.lng(q, "x2", 0);
            y2 = (int)Commands.lng(q, "y2", 0);
        }else{
            x1 = (int)Commands.lng(q, "x", -1);
            y1 = (int)Commands.lng(q, "y", -1);
            x2 = x1 + (int)Commands.lng(q, "w", 1) - 1;
            y2 = y1 + (int)Commands.lng(q, "h", 1) - 1;
        }
        if(x1 < 0 || y1 < 0) throw fail("need x,y and w,h (or x2,y2)");
        Schematic s = create(x1, y1, x2, y2, q.get("team"));
        String name = q.getOrDefault("name", "");
        if(!name.isEmpty()) s.tags.put("name", name);
        if(q.containsKey("description")) s.tags.put("description", q.get("description"));
        String origin = s.labels.isEmpty() ? null : s.labels.first();
        s.labels.clear();
        String b64 = base64(s);
        String path = null;
        if(!name.isEmpty()){
            File f = new File(ownDir(), name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".msch");
            try(FileOutputStream out = new FileOutputStream(f)){
                Schematics.write(s, out);
            }catch(IOException e){
                throw fail("could not write " + f + ": " + e.getMessage());
            }
            path = f.getAbsolutePath();
        }
        return Json.obj("ok", true, "size", s.width + "x" + s.height, "origin", origin, "blocks", s.tiles.size, "file", path, "base64", b64,
            "note", "in game: Schematics → Import → from clipboard, paste the base64");
    }

    public static Object info(Map<String, String> q, String body){
        Schematic s = load(q, body);
        LinkedHashMap<String, Integer> blocks = new LinkedHashMap<>();
        for(Stile t : s.tiles) blocks.merge(t.block.name, 1, Integer::sum);
        LinkedHashMap<String, Object> cost = new LinkedHashMap<>();
        s.requirements().each((item, amount) -> cost.put(item.name, amount));
        ArrayList<Object> tiles = new ArrayList<>();
        if(Commands.bool(q, "tiles", false)){
            for(Stile t : s.tiles){
                tiles.add(Json.obj("block", t.block.name, "x", (int)t.x, "y", (int)t.y, "rot", (int)t.rotation,
                    "config", t.config == null ? null : Commands.configString(t.config)));
            }
        }
        return Json.obj("name", s.name(), "size", s.width + "x" + s.height, "blocks", blocks, "cost", cost,
            "tiles", tiles.isEmpty() ? null : tiles, "base64", Commands.bool(q, "base64", false) ? base64(s) : null);
    }

    /**
     * Place a schematic. x,y = bottom-left corner (anchor=center: center, like the in-game cursor).
     * mode=all: everything that can be placed; mode=missing: only what is absent or misconfigured
     * (existing blocks get their config again, e.g. processor code).
     */
    public static Object place(Map<String, String> q, String body){
        Schematic s = load(q, body);
        int rot = (int)Commands.lng(q, "rot", 0) & 3;
        if(rot != 0) s = Schematics.rotate(s, rot);
        int x = (int)Commands.lng(q, "x", -1), y = (int)Commands.lng(q, "y", -1);
        if(x < 0 || y < 0) throw fail("need x,y (bottom-left corner of the schematic)");
        if(q.getOrDefault("anchor", "corner").equals("center")){
            x -= s.width / 2;
            y -= s.height / 2;
        }
        boolean missing = q.getOrDefault("mode", "all").equals("missing");
        boolean dry = Commands.bool(q, "dry", false);
        Unit unit = dry ? null : Commands.unit();
        if(unit != null && !unit.canBuild()) throw fail("unit " + unit.type.name + " cannot build");

        Seq<Stile> tiles = s.tiles.copy().sort(Structs.comparingInt(t -> -t.block.schematicPriority));
        int queued = 0, reconfigured = 0, same = 0;
        ArrayList<Object> blocked = new ArrayList<>();
        for(Stile t : tiles){
            int tx = x + t.x, ty = y + t.y;
            Building existing = world.build(tx, ty);
            if(existing != null && existing.block == t.block && existing.tileX() == tx && existing.tileY() == ty && existing.team == player.team()){
                boolean rotOk = !t.block.rotate || existing.rotation == t.rotation;
                boolean cfgOk = sameConfig(existing, t.config);
                if(rotOk && cfgOk){
                    same++;
                    continue;
                }
                if(!dry){
                    if(!rotOk) Call.rotateBlock(player, existing, ((t.rotation - existing.rotation) & 3) == 1);
                    if(!cfgOk && t.config != null) Call.tileConfig(player, existing, t.config);
                }
                reconfigured++;
                continue;
            }
            if(state.rules.isBanned(t.block)){
                blocked.add(Json.obj("block", t.block.name, "x", tx, "y", ty, "why", "banned"));
                continue;
            }
            if(!Build.validPlace(t.block, player.team(), tx, ty, t.rotation)){
                Tile tile = world.tile(tx, ty);
                blocked.add(Json.obj("block", t.block.name, "x", tx, "y", ty, "why",
                    tile == null ? "outside the map" : tile.build != null ? "occupied: " + tile.build.block.name : tile.block().name + " on " + tile.floor().name));
                continue;
            }
            if(!dry) unit.addBuild(new BuildPlan(tx, ty, t.rotation, t.block, t.config));
            queued++;
        }
        if(missing && queued == 0 && reconfigured == 0 && blocked.isEmpty()){
            return Json.obj("ok", true, "intact", true, "same", same);
        }
        return Json.obj("ok", true, "size", s.width + "x" + s.height, "origin", x + "," + y, "queued", queued, "reconfigured", reconfigured,
            "same", same, "blocked", blocked.size() > 50 ? blocked.subList(0, 50) : blocked, "blockedTotal", blocked.size(), "dry", dry);
    }

    static boolean sameConfig(Building b, Object want){
        if(want == null) return true;
        if(b instanceof LogicBuild lb && want instanceof byte[] bytes){
            //compare code and links, not bytes: compression may differ
            try{
                var tmp = new LogicCompare(bytes);
                if(!tmp.code.equals(lb.code)) return false;
                if(tmp.links.size() != lb.links.size) return false;
                for(int i = 0; i < lb.links.size; i++){
                    var l = lb.links.get(i);
                    int[] w = tmp.links.get(i);
                    if(l.x - lb.tileX() != w[0] || l.y - lb.tileY() != w[1]) return false;
                }
                return true;
            }catch(Exception e){
                return false;
            }
        }
        Object have = b.config();
        if(have instanceof byte[] a && want instanceof byte[] w) return Arrays.equals(a, w);
        if(have instanceof mindustry.ctype.Content c1 && want instanceof mindustry.ctype.Content c2) return c1 == c2;
        return Objects.equals(have, want) || String.valueOf(have).equals(String.valueOf(want));
    }

    /** Parse a compressed processor config (LogicBlock.compress format). */
    static class LogicCompare{
        String code;
        ArrayList<int[]> links = new ArrayList<>();

        LogicCompare(byte[] data) throws IOException{
            try(DataInputStream in = new DataInputStream(new java.util.zip.InflaterInputStream(new ByteArrayInputStream(data)))){
                in.read(); //version
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                code = new String(bytes, StandardCharsets.UTF_8);
                int total = in.readInt();
                for(int i = 0; i < total; i++){
                    in.readUTF();
                    links.add(new int[]{in.readShort(), in.readShort()});
                }
            }
        }
    }

    /** Rebuild the team's destroyed buildings from "ghosts" (what the game shows for rebuilding). */
    public static Object rebuild(Map<String, String> q){
        Unit unit = Commands.unit();
        if(!unit.canBuild()) throw fail("unit " + unit.type.name + " cannot build");
        boolean area = q.containsKey("x");
        int x = (int)Commands.lng(q, "x", 0), y = (int)Commands.lng(q, "y", 0), w = (int)Commands.lng(q, "w", 1), h = (int)Commands.lng(q, "h", 1);
        int n = 0, skipped = 0;
        for(Teams.BlockPlan p : player.team().data().plans){
            if(p.removed) continue;
            if(area && (p.x < x || p.y < y || p.x >= x + w || p.y >= y + h)) continue;
            if(!Build.validPlace(p.block, player.team(), p.x, p.y, p.rotation)){
                skipped++;
                continue;
            }
            unit.addBuild(new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
            n++;
        }
        return Json.obj("ok", true, "queued", n, "skipped", skipped, "ghosts", player.team().data().plans.size);
    }
}
