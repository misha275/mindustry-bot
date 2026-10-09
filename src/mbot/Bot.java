package mbot;

import arc.*;
import arc.backend.headless.*;
import arc.graphics.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;
import mindustry.world.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Headless Mindustry client: joins a server as a regular player, with no window or graphics.
 * Game state and commands are handled by a local HTTP bridge (see {@link Bridge}).
 */
public class Bot implements ApplicationListener{
    public static Bot instance;
    public static Config cfg;
    public static final EventLog events = new EventLog();
    public static final EventLog.Menus menus = new EventLog.Menus();

    /** Movement target in world coordinates (tile * 8) or null. */
    public Vec2 moveTarget;
    public boolean autoApproach = true;
    public boolean shooting;
    /** null = boost automatically (over blocks and while moving), true/false = manual. */
    public Boolean boost;
    public float aimX, aimY;
    public String lastDisconnect = "";
    public String host;
    public int port;

    float reconnectTimer = -1f;
    int lastSent;
    float syncTimer, pingTimer;
    long lastNanos;
    boolean wasConnected;

    public static class Config{
        public String host = "127.0.0.1";
        public int port = Vars.port;
        public String name = "ClaudeBot";
        public String color = "ffd37f";
        public int httpPort = 6789;
        public boolean reconnect = true;
        public boolean echo = true;
        public String dataDir = "botdata";
        public String schematicsDir;
    }

    public static void main(String[] args){
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        cfg = new Config();
        for(int i = 0; i < args.length; i++){
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : "";
            switch(a){
                case "--host" -> { cfg.host = v; i++; }
                case "--port" -> { cfg.port = Integer.parseInt(v); i++; }
                case "--name" -> { cfg.name = v; i++; }
                case "--color" -> { cfg.color = v; i++; }
                case "--http" -> { cfg.httpPort = Integer.parseInt(v); i++; }
                case "--data" -> { cfg.dataDir = v; i++; }
                case "--schematics" -> { cfg.schematicsDir = v; i++; }
                case "--no-reconnect" -> cfg.reconnect = false;
                case "--quiet" -> cfg.echo = false;
                case "--help", "-h" -> {
                    System.out.println("""
                    java -jar mindustry-bot.jar [options]
                      --host 127.0.0.1    server address (host:port is fine)
                      --port 6567         server port
                      --name ClaudeBot    player name
                      --color ffd37f      name color
                      --http 6789         local HTTP bridge port (127.0.0.1 only)
                      --data botdata      folder for bot settings (uuid) and saved schematics
                      --schematics DIR    extra folder with .msch schematics (the game's folder is found automatically)
                      --no-reconnect      do not reconnect after a disconnect
                      --quiet             do not print chat to the console
                    """);
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + a);
                    return;
                }
            }
        }
        if(cfg.host.contains(":")){
            cfg.port = Integer.parseInt(cfg.host.substring(cfg.host.indexOf(':') + 1));
            cfg.host = cfg.host.substring(0, cfg.host.indexOf(':'));
        }

        Vars.platform = new Platform(){};
        Vars.net = new Net(platform.getNet());
        new HeadlessApplication(instance = new Bot(), t -> {
            Log.err("Fatal error", t);
            System.exit(1);
        });
    }

    @Override
    public void init(){
        Core.settings.setDataDirectory(Core.files.local(cfg.dataDir));
        loadLocales = false;
        headless = true;

        Vars.loadSettings();
        Vars.init();
        UI.loadColors();
        Fonts.loadContentIconsHeadless();
        content.createBaseContent();
        content.init();

        Core.settings.put("name", cfg.name);
        Core.settings.put("locale", "en");

        ui = BotUI.create();
        control = BotUI.createControl();
        player = Player.create();
        player.name = cfg.name;
        player.color.set(Color.valueOf(cfg.color));

        logic = new Logic();
        netServer = new NetServer();
        netClient = new NetClient();
        installHandlers();

        Core.app.addListener(new ApplicationListener(){
            @Override public void update(){ asyncCore.begin(); }
        });
        Core.app.addListener(new ApplicationListener(){
            @Override public void update(){
                try{
                    logic.update();
                }catch(Throwable t){
                    Log.err("logic", t);
                }
            }
        });
        Core.app.addListener(new ApplicationListener(){
            @Override public void update(){ asyncCore.end(); }
        });

        lastNanos = Time.nanos();
        Screen.loadFont();
        Threads.daemon("sprites", Screen.Atlas::load);

        try{
            new Bridge(cfg.httpPort).start();
            Log.info("HTTP bridge: http://127.0.0.1:@/", cfg.httpPort);
        }catch(Exception e){
            Log.err("Could not open HTTP port " + cfg.httpPort, e);
            System.exit(1);
        }

        connect(cfg.host, cfg.port);
    }

    void installHandlers(){
        net.handleClient(Connect.class, packet -> {
            try{
                Log.info("Connected to @, sending player data", packet.addressTCP);
                player.admin = false;
                invoke(netClient, "reset");
                menus.clear();

                if(!net.client()){
                    netClient.disconnectQuietly();
                    return;
                }

                var c = new ConnectPacket();
                c.name = player.name;
                c.locale = "en";
                c.mods = mods.getModStrings();
                c.mobile = false;
                c.versionType = Version.type;
                c.color = player.color.rgba();
                c.usid = (String)invoke(netClient, "getUsid", packet.addressTCP);
                c.uuid = platform.getUUID();
                net.send(c, true);
            }catch(Throwable t){
                Log.err("connect", t);
            }
        });

        //v160: map assets (icons, textures) arrive as a separate stream before the world
        net.handleClient(StreamBegin.class, data -> {
            if(data.type == Net.packetIdAssetStream){
                Threads.daemon(() -> {
                    try{
                        NetworkIO.loadAssets(data.incrementalStream);
                        Core.app.post(Call::requestWorld);
                    }catch(Exception e){
                        Log.err("assets", e);
                        Core.app.post(() -> netClient.disconnectQuietly());
                    }
                });
            }
        });

        //server menus: in v160 they are built straight from the packet, so we intercept the packets
        net.handleClient(MenuCallPacket.class, p -> menus.put(o -> Call.menuChoose(player, p.menuId, o), p.title, p.message, p.options));
        net.handleClient(FollowUpMenuCallPacket.class, p -> menus.put(o -> Call.menuChoose(player, p.menuId, o), p.title, p.message, p.options));
        net.handleClient(TextInputCallPacket.class, p -> menus.putText(t -> Call.textInputResult(player, p.textInputId, t), p.title, p.message, p.def));
        net.handleClient(TextInputCallPacket2.class, p -> menus.putText(t -> Call.textInputResult(player, p.textInputId, t), p.title, p.message, p.def));

        net.handleClient(Disconnect.class, packet -> {
            try{
                setField(netClient, "connecting", false);
                logic.reset();
                Screen.reset();
                player.name = cfg.name;
                lastDisconnect = packet.reason == null ? "disconnected" : packet.reason;
                events.add("disconnect", lastDisconnect);
                Log.info("Disconnected: @", lastDisconnect);
                moveTarget = null;
                wasConnected = false;
                if(cfg.reconnect) scheduleReconnect(5f);
            }catch(Throwable t){
                Log.err("disconnect", t);
            }
        });
    }

    public void connect(String host, int port){
        this.host = host;
        this.port = port;
        reconnectTimer = -1f;
        Core.app.post(() -> {
            try{
                if(net.active()) netClient.disconnectQuietly();
                logic.reset();
                net.reset();
                netClient.beginConnecting();
                events.add("connecting", host + ":" + port);
                Log.info("Connecting to @:@ as @", host, port, player.name);
                net.connect(host, port, () -> {});
            }catch(Throwable t){
                Log.err("connect", t);
            }
        });
    }

    public void disconnect(){
        reconnectTimer = -1f;
        netClient.disconnectQuietly();
        logic.reset();
        events.add("disconnect", "on request");
    }

    public void scheduleReconnect(float seconds){
        reconnectTimer = seconds;
    }

    public boolean inGame(){
        return net.client() && state.isGame() && !netClient.isConnecting();
    }

    @Override
    public void update(){
        try{
            long now = Time.nanos();
            float dt = Math.min((now - lastNanos) / 1_000_000_000f, 0.5f);
            lastNanos = now;
            float ticks = dt * 60f;

            if(reconnectTimer > 0){
                reconnectTimer -= dt;
                if(reconnectTimer <= 0){
                    reconnectTimer = -1f;
                    connect(host, port);
                }
            }

            if(!inGame()) return;

            if(!wasConnected){
                wasConnected = true;
                events.add("joined", (state.map == null ? "?" : state.map.name()) + " " + world.width() + "x" + world.height());
            }

            updateMovement(ticks);
            Screen.update();

            syncTimer += ticks;
            if(syncTimer >= 4f){
                syncTimer = 0f;
                sendSnapshot();
            }
            pingTimer += ticks;
            if(pingTimer >= 60f){
                pingTimer = 0f;
                Call.ping(Time.millis());
            }
        }catch(Throwable t){
            Log.err("update", t);
        }
    }

    void updateMovement(float ticks){
        if(player.dead()) return;
        Unit unit = player.unit();

        Vec2 dest = moveTarget;
        if(dest == null && autoApproach && unit.canBuild() && unit.plans().size > 0){
            var plan = unit.buildPlan();
            float range = unit.type.buildRange - tilesize * 3f;
            if(!state.rules.infiniteResources && !unit.within(plan.drawx(), plan.drawy(), range)){
                dest = Tmp.v3.set(plan.drawx(), plan.drawy());
            }
        }

        boolean moving = false;
        if(dest != null){
            float step = unit.type.speed * 0.9f * ticks;
            float dst = unit.dst(dest);
            float stopAt = dest == moveTarget ? 0f : unit.type.buildRange - tilesize * 4f;
            if(dst <= Math.max(step, 0.01f) + stopAt){
                if(dest == moveTarget){
                    unit.set(dest.x, dest.y);
                    moveTarget = null;
                    events.add("arrived", (int)(dest.x / tilesize) + "," + (int)(dest.y / tilesize));
                }
            }else{
                Tmp.v1.set(dest).sub(unit).limit(step);
                unit.rotation = Tmp.v1.angle();
                if(unit instanceof Mechc m) m.baseRotation(unit.rotation);
                unit.set(unit.x + Tmp.v1.x, unit.y + Tmp.v1.y);
                moving = true;
            }
        }
        unit.vel.setZero();

        //core units (alpha/beta/gamma) fly over buildings while moving or standing on a block
        Tile on = unit.tileOn();
        player.boosting = unit.type.canBoost && (boost != null ? boost : moving || (on != null && on.solid()));
        unit.updateBoosting(player.boosting);

        if(shooting){
            unit.aim(aimX, aimY);
            unit.lookAt(aimX, aimY);
        }else{
            aimX = unit.x + Angles.trnsx(unit.rotation, 40f);
            aimY = unit.y + Angles.trnsy(unit.rotation, 40f);
            unit.aim(aimX, aimY);
        }
        player.shooting = shooting;
        unit.updateBuilding(unit.plans().size > 0);
    }

    void sendSnapshot(){
        boolean dead = player.dead();
        Unit unit = dead ? null : player.unit();
        int uid = dead || unit == null ? -1 : unit.id;
        float vw = Math.max(world.unitWidth(), 2000f), vh = Math.max(world.unitHeight(), 2000f);

        Call.clientSnapshot(
            lastSent++,
            uid,
            dead,
            dead ? player.x : unit.x, dead ? player.y : unit.y,
            aimX, aimY,
            unit == null ? 0f : unit.rotation,
            unit instanceof Mechc m ? m.baseRotation() : 0,
            0f, 0f,
            dead ? null : unit.mineTile,
            player.boosting, shooting, false, !dead && unit.plans().size > 0,
            null, 0,
            player.isBuilder() && unit != null ? unit.plans() : null,
            world.unitWidth() / 2f, world.unitHeight() / 2f,
            vw, vh
        );
    }

    static Object invoke(Object target, String name, Object... args) throws Exception{
        for(Method m : target.getClass().getDeclaredMethods()){
            if(m.getName().equals(name) && m.getParameterCount() == args.length){
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    static void setField(Object target, String name, Object value) throws Exception{
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
