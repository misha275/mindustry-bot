package mbot;

import arc.*;
import arc.util.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local HTTP bridge, listens on 127.0.0.1 only. Any method (GET/POST), parameters in the query string,
 * processor code or chat text can be sent as the request body. The response is always JSON.
 * Example: curl "http://127.0.0.1:6789/status"
 */
public class Bridge{
    public static final String help = """
        Coordinates are in tiles. Responses are JSON, errors: {"ok":false,"error":"..."}.
        GET  /status                         bot, unit, map, wave, core
        GET  /events?since=N[&type=chat][&wait=60]  events after N; wait = wait up to 60 s for a new one
        GET  /menus, /menu?id=&option=       server menus and answering them (text= for text input)
        POST /chat  (body or text=)          send to chat, server /commands too
        POST /move?x=&y=, /stop              walk to a point, stop
        GET  /core                           items in the core
        GET  /units?team=&x=&y=&radius=      units
        GET  /players                        players
        GET  /map?x=&y=&w=&h=                character map of blocks and floor with a legend
        GET  /blocks?x=&y=&w=&h=&team=&block= buildings
        GET  /find?name=ore-copper           nearest tiles/blocks by name (ore-*, wall-ore-*, block/floor name)
        GET  /tile?x=&y=                     everything about a tile and its building
        GET  /content?type=block|item|unit|liquid[&category=logic]
        POST /build?block=&x=&y=&rot=[&links=cell1@x,y;x,y][&config=] body = mlog for a processor
        POST /break?x=&y=[&w=&h=]            break
        GET  /plans, POST /clearplans        build queue
        GET  /processor?x=&y=                processor code and links
        POST /processor?x=&y=[&links=...]    body = new mlog (without links the links are kept)
        POST /link?x=&y=&tx=&ty=             toggle a processor/node link to block tx,ty
        POST /config?x=&y=&value=            block config: number, true/false, item/block name, p:dx,dy, s:string
        POST /rotate?x=&y=[&dir=ccw]
        POST /mine?x=&y= (no x = stop), /deposit, /take?item=&amount=, /drop
        POST /aim?x=&y=&shoot=true|false     shoot
        POST /command?units=1,2|all&type=&x=&y=   order units to move/attack
        POST /unitcommand?units=all&command=move|rebuild|assist|mine|boost
        POST /control?id=, /uncontrol        possess a unit / return
        POST /approach?on=false              do not approach build sites automatically
        POST /batch  body: one command per line, e.g. /build?block=conveyor&x=10&y=5&rot=0
        POST /connect?host=&port=&name=, /disconnect

        Eyes (PNG; save=path.png saves the file and answers JSON):
        GET  /displays                       all logic displays: where, size in pixels, when last drawn
        GET  /display?x=&y=[&zoom=2]         display image (any block of a merged display)
        GET  /view?x=&y=&w=&h=[&scale=16][&sprites=1][&units=1][&grid=10]  map snapshot drawn with game sprites
                                             (x,y = bottom-left corner, default 40x30 around the bot)

        Schematics (x,y = bottom-left corner; anchor=center = center, like in game):
        GET  /schematic/list                 bot and game schematics (%APPDATA%/Mindustry/schematics)
        POST /schematic/save?x=&y=&w=&h=[&name=][&team=]  capture an area, answers base64, name= saves a file
        GET  /schematic/info?name=|file=|base64=[&tiles=1]   blocks, size, cost
        POST /schematic/place?x=&y=&name=|file=|base64=(or body)[&rot=0..3][&mode=missing][&dry=1]
                                             mode=missing: build what is missing and restore configs (processor code)
        POST /rebuild[?x=&y=&w=&h=]          rebuild destroyed blocks from team ghosts

        Units and buildings:
        GET  /unit?id=                       unit details (no id = own unit)
        POST /command?units=&x=&y=[&target=id][&queue=1]  move/attack a point, building or unit
        POST /stance?units=&stance=holdfire|pursuetarget|patrol|ram|holdposition|stop|boost|mineauto[&on=1]
        POST /boost?on=1|0|auto              mech boosting (alpha, nova...)
        POST /payload/pick?id= | ?x=&y=      pick up a unit or building as payload (poly, mega, quad...)
        POST /payload/drop?x=&y=             drop payload
        POST /turret?x=&y=                   take control of a turret (leave: /uncontrol)
        POST /rally?x=&y=&tx=&ty=            rally point for the unit factory at x,y
        POST /deposit[?x=&y=]                deposit items into the core or building x,y
        """;

    final HttpServer server;

    public Bridge(int port) throws IOException{
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "bridge");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start(){
        server.start();
    }

    void handle(HttpExchange ex) throws IOException{
        Object out;
        int code = 200;
        try{
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            Map<String, String> q = parseQuery(uri.getRawQuery());
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if(body.isEmpty()) body = null;
            //curl -d turns the body into form-urlencoded; --data-binary sends it as is
            if(path.equals("/events") && q.containsKey("wait")){
                //long poll: wait up to wait seconds for a new event without blocking the game thread
                long since = q.containsKey("since") ? Long.parseLong(q.get("since")) : Bot.events.last();
                q.put("since", String.valueOf(since));
                Bot.events.await(since, q.get("type"), (long)(Math.min(Double.parseDouble(q.get("wait")), 600) * 1000));
            }
            if(path.equals("/batch")){
                out = runBatch(body == null ? "" : body);
            }else{
                out = onGameThread(path, q, body);
            }
        }catch(Throwable t){
            code = 500;
            out = Json.write(Json.obj("ok", false, "error", Strings.getSimpleMessage(t)));
        }
        byte[] bytes;
        if(out instanceof Commands.Bin bin){
            bytes = bin.data();
            ex.getResponseHeaders().set("Content-Type", bin.type());
        }else{
            bytes = String.valueOf(out).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        }
        ex.sendResponseHeaders(code, bytes.length);
        try(OutputStream os = ex.getResponseBody()){
            os.write(bytes);
        }
    }

    Object onGameThread(String path, Map<String, String> q, String body) throws Exception{
        CompletableFuture<Object> f = new CompletableFuture<>();
        Core.app.post(() -> f.complete(exec(path, q, body)));
        return f.get(15, TimeUnit.SECONDS);
    }

    String runBatch(String body) throws Exception{
        CompletableFuture<String> f = new CompletableFuture<>();
        Core.app.post(() -> {
            ArrayList<String> results = new ArrayList<>();
            for(String line : body.split("\n")){
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;
                if(!line.startsWith("/")) line = "/" + line;
                String p = line.contains("?") ? line.substring(0, line.indexOf('?')) : line;
                String query = line.contains("?") ? line.substring(line.indexOf('?') + 1) : null;
                Object r = exec(p, parseQuery(query), null);
                results.add(r instanceof Commands.Bin ? "{\"ok\":true,\"binary\":true}" : String.valueOf(r));
            }
            f.complete("{\"ok\":true,\"results\":[" + String.join(",", results) + "]}");
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    static Object exec(String path, Map<String, String> q, String body){
        try{
            Object res = Commands.run(path, q, body);
            if(res instanceof Commands.Bin) return res;
            return Json.write(res);
        }catch(Commands.Fail e){
            return Json.write(Json.obj("ok", false, "cmd", path, "error", e.getMessage()));
        }catch(Throwable t){
            Log.err("bridge " + path, t);
            return Json.write(Json.obj("ok", false, "cmd", path, "error", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    static Map<String, String> parseQuery(String raw){
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if(raw == null || raw.isEmpty()) return map;
        for(String pair : raw.split("&")){
            if(pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }
}
