package mbot;

import arc.files.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.world.blocks.logic.LogicDisplay.*;
import mindustry.world.blocks.logic.TileableLogicDisplay.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * The bot's "eyes". The server sends no image, but the client runs processors itself, so
 * draw commands land in the display queues. Here they are rendered in software, with the same font
 * as in game, and map snapshots are assembled from game sprites (sprites/*.png from Mindustry.jar).
 */
public class Screen{
    /** Color of an empty display in game (Pal.darkerMetal). */
    static final int background = 0xff565666;
    static final int frameSize = 6;

    static final IdentityHashMap<LogicDisplayBuild, Raster> rasters = new IdentityHashMap<>();
    static final IdentityHashMap<LExecutor, LInstruction[]> patched = new IdentityHashMap<>();
    static int patchTimer;

    // ---------- logic display font (taken from the game: logic.ttf, 16 px) ----------

    static float fontAdvance = 7, fontLine = 13, fontCap = 8, fontAscent = 2;
    static final HashMap<Character, Glyph> glyphs = new HashMap<>();

    static class Glyph{
        int xo, yo, w, h, adv;
        byte[] alpha;
    }

    static void loadFont(){
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(resource("logicfont.bin")))){
            fontAdvance = in.readFloat();
            fontLine = in.readFloat();
            fontCap = in.readFloat();
            fontAscent = in.readFloat();
            in.readFloat();
            int n = in.readInt();
            for(int i = 0; i < n; i++){
                Glyph g = new Glyph();
                char c = in.readChar();
                g.xo = in.readShort();
                g.yo = in.readShort();
                g.w = in.readShort();
                g.h = in.readShort();
                g.adv = in.readShort();
                g.alpha = new byte[g.w * g.h];
                in.readFully(g.alpha);
                glyphs.put(c, g);
            }
        }catch(Exception e){
            Log.err("display font not loaded", e);
        }
    }

    static InputStream resource(String name) throws IOException{
        InputStream in = Screen.class.getResourceAsStream("/botassets/" + name);
        if(in == null) throw new FileNotFoundException("botassets/" + name);
        return in;
    }

    // ---------- draw interception ----------

    /** In DrawI the game skips drawing when headless. We replace the instruction with our own copy without that check. */
    static void patchExecutors(){
        if(++patchTimer < 10) return;
        patchTimer = 0;
        for(Building b : Groups.build){
            if(!(b instanceof LogicBuild lb)) continue;
            LExecutor exec = lb.executor;
            LInstruction[] ins = exec.instructions;
            if(ins == null || patched.get(exec) == ins) continue;
            for(int i = 0; i < ins.length; i++){
                if(ins[i] instanceof DrawI d && !(d instanceof BotDrawI)){
                    ins[i] = new BotDrawI(d);
                }
            }
            patched.put(exec, ins);
        }
        patched.keySet().removeIf(e -> e.build == null || !e.build.isValid());
    }

    public static class BotDrawI extends DrawI{
        BotDrawI(DrawI d){
            super(d.type, d.x, d.y, d.p1, d.p2, d.p3, d.p4);
        }

        @Override
        public void run(LExecutor exec){
            LongSeq buf = exec.graphicsBuffer;
            if(buf.size >= LExecutor.maxGraphicsBuffer) return;

            if(type == LogicDisplay.commandColorPack){
                int value = (int)(Double.doubleToRawLongBits(x.num()));
                int r = (value & 0xff000000) >>> 24, g = (value & 0x00ff0000) >>> 16, b = (value & 0x0000ff00) >>> 8, a = value & 0xff;
                buf.add(DisplayCmd.get(LogicDisplay.commandColor, pack(r), pack(g), pack(b), pack(a), 0, 0));
            }else if(type == LogicDisplay.commandPrint){
                CharSequence str = exec.textBuffer;
                if(str.length() == 0) return;
                int advance = (int)fontAdvance, lineHeight = (int)fontLine;
                int align = p1.numi();
                int maxWidth = 0, lines = 1, lineWidth = 0;
                for(int i = 0; i < str.length(); i++){
                    if(str.charAt(i) == '\n'){
                        maxWidth = Math.max(maxWidth, lineWidth);
                        lineWidth = 0;
                        lines++;
                    }else{
                        lineWidth++;
                    }
                }
                maxWidth = Math.max(maxWidth, lineWidth);
                float width = maxWidth * advance, height = lines * lineHeight,
                    ha = ((Align.isLeft(align) ? -1f : 0f) + 1f + (Align.isRight(align) ? 1f : 0f)) / 2f,
                    va = ((Align.isBottom(align) ? -1f : 0f) + 1f + (Align.isTop(align) ? 1f : 0f)) / 2f;
                int xOffset = -(int)(width * ha), yOffset = -(int)(height * va) + (lines - 1) * lineHeight;
                int curX = x.numi(), curY = y.numi();
                for(int i = 0; i < str.length(); i++){
                    char next = str.charAt(i);
                    if(next == '\n'){
                        curY -= lineHeight;
                        curX = x.numi();
                        continue;
                    }
                    if(glyphs.containsKey(next)){
                        buf.add(DisplayCmd.get(LogicDisplay.commandPrint, packSign(curX + xOffset), packSign(curY + yOffset), next, 0, 0, 0));
                    }
                    curX += advance;
                    if(buf.size >= LExecutor.maxGraphicsBuffer) break;
                }
                exec.textBuffer.setLength(0);
            }else{
                int num1 = packSign(p1.numi()), num4 = packSign(p4.numi()), xval = packSign(x.numi()), yval = packSign(y.numi());
                if(type == LogicDisplay.commandImage){
                    int packed = -1;
                    if(p1.obj() instanceof UnlockableContent u){
                        packed = (u.id << 5) | (u.getContentType().ordinal() & 31);
                    }else if(p1.obj() instanceof LogicDisplayBuild d){
                        packed = (d.rootDisplay.index << 5) | LogicDisplay.displayDrawType;
                    }
                    num1 = packed & 0x3FF;
                    num4 = packed >> 10;
                }else if(type == LogicDisplay.commandScale){
                    xval = packSign((int)(x.numf() / LogicDisplay.scaleStep));
                    yval = packSign((int)(y.numf() / LogicDisplay.scaleStep));
                }
                buf.add(DisplayCmd.get(type, xval, yval, num1, packSign(p2.numi()), packSign(p3.numi()), num4));
            }
        }

        static int pack(int value){
            return value & 0b0111111111;
        }

        static int packSign(int value){
            return (Math.abs(value) & 0b0111111111) | (value < 0 ? 0b1000000000 : 0);
        }
    }

    // ---------- software display rendering ----------

    static class Raster{
        final LogicDisplayBuild root;
        BufferedImage img;
        int color = 0xffffffff;
        float stroke = 1f;
        AffineTransform user;
        long updated, commandsDrawn;

        Raster(LogicDisplayBuild root, int w, int h){
            this.root = root;
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(background, true));
            g.fillRect(0, 0, w, h);
            g.dispose();
        }
    }

    static int[] bufferSize(LogicDisplayBuild root){
        if(root instanceof TileableLogicDisplayBuild t){
            if(t.tilesWidth > 16 || t.tilesHeight > 16) return null;
            return new int[]{32 * t.tilesWidth - 2 * frameSize, 32 * t.tilesHeight - 2 * frameSize};
        }
        int s = ((LogicDisplay)root.block).displaySize;
        return new int[]{s, s};
    }

    static Raster raster(LogicDisplayBuild root){
        int[] size = bufferSize(root);
        if(size == null) return null;
        Raster r = rasters.get(root);
        if(r == null || r.img.getWidth() != size[0] || r.img.getHeight() != size[1]){
            Raster old = r;
            r = new Raster(root, size[0], size[1]);
            if(old != null){
                Graphics2D g = r.img.createGraphics();
                g.drawImage(old.img, 0, r.img.getHeight() - old.img.getHeight(), null);
                g.dispose();
                r.color = old.color;
                r.stroke = old.stroke;
            }
            rasters.put(root, r);
        }
        return r;
    }

    /** Every frame: take commands from all display queues and draw them. */
    public static void update(){
        try{
            patchExecutors();
            for(LogicDisplayBuild d : LogicDisplay.displays){
                LogicDisplayBuild root = d.rootDisplay;
                if(root != d || root.commands.isEmpty()) continue;
                Raster r = raster(root);
                if(r == null){
                    root.commands.clear();
                    continue;
                }
                process(r, root.commands);
            }
            if(Mathf.chance(0.01)){
                rasters.keySet().removeIf(k -> !k.isAdded() || k.rootDisplay != k);
            }
        }catch(Throwable t){
            Log.err("screen", t);
        }
    }

    public static void reset(){
        rasters.clear();
        patched.clear();
    }

    static int unpackSign(int value){
        return (value & 0b0111111111) * ((value & 0b1000000000) != 0 ? -1 : 1);
    }

    static void process(Raster r, LongQueue commands){
        BufferedImage img = r.img;
        int h = img.getHeight();
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        AffineTransform base = new AffineTransform(1, 0, 0, -1, 0, h);
        while(!commands.isEmpty()){
            long c = commands.removeFirst();
            int type = DisplayCmd.type(c);
            int x = unpackSign(DisplayCmd.x(c)), y = unpackSign(DisplayCmd.y(c)),
                p1 = unpackSign(DisplayCmd.p1(c)), p2 = unpackSign(DisplayCmd.p2(c)), p3 = unpackSign(DisplayCmd.p3(c)), p4 = unpackSign(DisplayCmd.p4(c));
            AffineTransform t = new AffineTransform(base);
            if(r.user != null) t.concatenate(r.user);
            g.setTransform(t);
            g.setColor(new Color(r.color, true));
            g.setStroke(new BasicStroke(r.stroke, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            switch(type){
                case LogicDisplay.commandClear -> {
                    g.setTransform(new AffineTransform());
                    g.setComposite(AlphaComposite.Src);
                    g.setColor(new Color(clamp(x), clamp(y), clamp(p1), 255));
                    g.fillRect(0, 0, img.getWidth(), h);
                    g.setComposite(AlphaComposite.SrcOver);
                }
                case LogicDisplay.commandLine -> g.draw(new Line2D.Float(x, y, p1, p2));
                case LogicDisplay.commandRect -> g.fill(new Rectangle2D.Float(Math.min(x, x + p1), Math.min(y, y + p2), Math.abs(p1), Math.abs(p2)));
                case LogicDisplay.commandLineRect -> g.draw(new Rectangle2D.Float(Math.min(x, x + p1), Math.min(y, y + p2), Math.abs(p1), Math.abs(p2)));
                case LogicDisplay.commandPoly -> g.fill(poly(x, y, Math.min(p1, 25), p2, p3));
                case LogicDisplay.commandLinePoly -> g.draw(poly(x, y, Math.min(p1, 25), p2, p3));
                case LogicDisplay.commandTriangle -> {
                    Path2D.Float p = new Path2D.Float();
                    p.moveTo(x, y);
                    p.lineTo(p1, p2);
                    p.lineTo(p3, p4);
                    p.closePath();
                    g.fill(p);
                }
                case LogicDisplay.commandColor -> r.color = (clamp(p2) << 24) | (clamp(x) << 16) | (clamp(y) << 8) | clamp(p1);
                case LogicDisplay.commandStroke -> r.stroke = Math.max(x, 0);
                case LogicDisplay.commandImage -> {
                    int packed = (DisplayCmd.p4(c) << 10) | DisplayCmd.p1(c);
                    int ctype = packed & 0x1F, id = packed >> 5;
                    BufferedImage src = null;
                    if(ctype == LogicDisplay.displayDrawType){
                        if(id >= 0 && id < LogicDisplay.displays.size){
                            Raster other = rasters.get(LogicDisplay.displays.get(id).rootDisplay);
                            if(other != null && other != r) src = other.img;
                        }
                    }else if(ctype < ContentType.all.length && content.getByID(ContentType.all[ctype], id) instanceof UnlockableContent u){
                        src = Atlas.icon(u);
                        if(src == null) src = solid(contentColor(u));
                    }
                    if(src != null){
                        float w = p2, hh = p2 * src.getHeight() / (float)src.getWidth();
                        AffineTransform it = new AffineTransform();
                        it.translate(x, y);
                        it.rotate(Math.toRadians(p3));
                        it.translate(-w / 2f, hh / 2f);
                        it.scale(w / src.getWidth(), -hh / src.getHeight());
                        g.drawImage(tint(src, r.color), it, null);
                    }
                }
                case LogicDisplay.commandPrint -> {
                    Glyph gl = glyphs.get((char)p1);
                    if(gl != null && gl.w > 0 && gl.h > 0){
                        float left = x + gl.xo, bottom = y + gl.yo + fontCap + fontAscent;
                        AffineTransform it = new AffineTransform();
                        it.translate(left, bottom + gl.h);
                        it.scale(1, -1);
                        g.drawImage(glyphImage(gl, r.color), it, null);
                    }
                }
                case LogicDisplay.commandTranslate -> user(r).translate(x, y);
                case LogicDisplay.commandScale -> user(r).scale(x * LogicDisplay.scaleStep, y * LogicDisplay.scaleStep);
                case LogicDisplay.commandRotate -> user(r).rotate(Math.toRadians(p1));
                case LogicDisplay.commandResetTransform -> r.user = new AffineTransform();
            }
            r.commandsDrawn++;
        }
        g.dispose();
        r.updated = Time.millis();
    }

    static AffineTransform user(Raster r){
        if(r.user == null) r.user = new AffineTransform();
        return r.user;
    }

    static int clamp(int v){
        return Math.max(0, Math.min(255, v));
    }

    static Shape poly(float x, float y, int sides, float radius, float rotation){
        Path2D.Float p = new Path2D.Float();
        sides = Math.max(sides, 3);
        for(int i = 0; i < sides; i++){
            double a = Math.toRadians(rotation + 360.0 * i / sides);
            float px = x + (float)Math.cos(a) * radius, py = y + (float)Math.sin(a) * radius;
            if(i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        return p;
    }

    static BufferedImage glyphImage(Glyph gl, int color){
        BufferedImage img = new BufferedImage(gl.w, gl.h, BufferedImage.TYPE_INT_ARGB);
        int rgb = color & 0xffffff, ca = color >>> 24;
        for(int yy = 0; yy < gl.h; yy++){
            for(int xx = 0; xx < gl.w; xx++){
                int a = (gl.alpha[yy * gl.w + xx] & 0xff) * ca / 255;
                if(a > 0) img.setRGB(xx, yy, (a << 24) | rgb);
            }
        }
        return img;
    }

    /** Multiply the image by the brush color (what Draw.color does in game). */
    static BufferedImage tint(BufferedImage src, int color){
        if(color == 0xffffffff) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int cr = (color >> 16) & 0xff, cg = (color >> 8) & 0xff, cb = color & 0xff, ca = color >>> 24;
        for(int yy = 0; yy < src.getHeight(); yy++){
            for(int xx = 0; xx < src.getWidth(); xx++){
                int p = src.getRGB(xx, yy);
                int a = (p >>> 24) * ca / 255, r = ((p >> 16) & 0xff) * cr / 255, gg = ((p >> 8) & 0xff) * cg / 255, b = (p & 0xff) * cb / 255;
                out.setRGB(xx, yy, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return out;
    }

    static BufferedImage solid(int argb){
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, argb);
        return img;
    }

    static int contentColor(UnlockableContent u){
        if(u instanceof Item i) return i.color.argb8888();
        if(u instanceof Liquid l) return l.color.argb8888();
        if(u instanceof Block b) return Atlas.blockColor(b);
        return 0xffcccccc;
    }

    // ---------- requests ----------

    static LogicDisplayBuild findDisplay(int x, int y){
        Building b = world.build(x, y);
        if(b instanceof LogicDisplayBuild d) return d.rootDisplay;
        throw new Commands.Fail("no display at " + x + "," + y);
    }

    public static Object displays(){
        ArrayList<Object> out = new ArrayList<>();
        for(LogicDisplayBuild d : LogicDisplay.displays){
            if(d.rootDisplay != d) continue;
            int[] size = bufferSize(d);
            Raster r = rasters.get(d);
            var m = Json.obj("block", d.block.name, "x", d.tileX(), "y", d.tileY(), "team", d.team.name,
                "pixels", size == null ? "too large (over 16x16)" : size[0] + "x" + size[1],
                "drawn", r == null ? 0 : r.commandsDrawn,
                "lastDrawMsAgo", r == null ? null : Time.timeSinceMillis(r.updated));
            if(d instanceof TileableLogicDisplayBuild t){
                m.put("tiles", t.tilesWidth + "x" + t.tilesHeight);
                m.put("origin", t.originX + "," + t.originY);
            }
            out.add(m);
        }
        return Json.obj("displays", out);
    }

    /** PNG of one display in its own pixels (zoom times larger). */
    public static byte[] display(int x, int y, int zoom){
        LogicDisplayBuild root = findDisplay(x, y);
        Raster r = raster(root);
        if(r == null) throw new Commands.Fail("display is larger than 16x16 blocks, the game does not draw it");
        zoom = Math.max(1, Math.min(zoom, 8));
        BufferedImage out = new BufferedImage(r.img.getWidth() * zoom, r.img.getHeight() * zoom, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(r.img, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return png(out);
    }

    public static byte[] png(BufferedImage img){
        try{
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            return bytes.toByteArray();
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Snapshot of a map area. x,y,w,h in tiles, scale = pixels per tile.
     * Floor, ore, buildings and units are drawn with game sprites, logic displays with their contents.
     */
    public static byte[] view(int x0, int y0, int w, int h, int scale, boolean sprites, boolean units, int grid){
        int W = w * scale, H = h * scale;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 16 ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(Color.black);
        g.fillRect(0, 0, W, H);
        boolean useSprites = sprites && Atlas.load();
        float s = scale / 8f; //pixels per world unit

        //floor and ore
        for(int ty = y0; ty < y0 + h; ty++){
            for(int tx = x0; tx < x0 + w; tx++){
                Tile t = world.tile(tx, ty);
                if(t == null) continue;
                int px = (tx - x0) * scale, py = (y0 + h - 1 - ty) * scale;
                BufferedImage floor = useSprites ? Atlas.variant(t.floor(), t) : null;
                if(floor != null){
                    g.drawImage(floor, px, py, scale, scale, null);
                }else{
                    g.setColor(new Color(Atlas.blockColor(t.floor())));
                    g.fillRect(px, py, scale, scale);
                }
                if(t.overlay() != Blocks.air){
                    BufferedImage ore = useSprites ? Atlas.variant(t.overlay(), t) : null;
                    if(ore != null){
                        g.drawImage(ore, px, py, scale, scale, null);
                    }else{
                        g.setColor(new Color(Atlas.blockColor(t.overlay())));
                        g.fillRect(px + scale / 4, py + scale / 4, Math.max(scale / 2, 1), Math.max(scale / 2, 1));
                    }
                }
                //walls and other blocks without a building
                if(t.build == null && t.block() != Blocks.air){
                    BufferedImage wall = useSprites ? Atlas.variant(t.block(), t) : null;
                    if(wall != null){
                        g.drawImage(wall, px, py, scale, scale, null);
                    }else{
                        g.setColor(new Color(Atlas.blockColor(t.block())));
                        g.fillRect(px, py, scale, scale);
                    }
                }
            }
        }

        //buildings
        HashSet<Building> seen = new HashSet<>();
        for(int ty = y0 - 4; ty < y0 + h + 4; ty++){
            for(int tx = x0 - 4; tx < x0 + w + 4; tx++){
                Building b = world.build(tx, ty);
                if(b == null || !seen.add(b)) continue;
                Block block = b instanceof ConstructBuild cb ? cb.current : b.block;
                if(block == null) block = b.block;
                float size = block.size * scale;
                float cx = (b.x - (x0 * 8 - 4)) * s, cy = H - (b.y - (y0 * 8 - 4)) * s;
                Composite old = g.getComposite();
                if(b instanceof ConstructBuild) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                BufferedImage icon = useSprites ? Atlas.icon(block) : null;
                if(icon != null){
                    AffineTransform at = new AffineTransform();
                    at.translate(cx, cy);
                    if(block.rotate) at.rotate(-Math.toRadians(b.rotation * 90));
                    at.translate(-size / 2f, -size / 2f);
                    at.scale(size / icon.getWidth(), size / icon.getHeight());
                    g.drawImage(icon, at, null);
                }else{
                    g.setColor(new Color(Atlas.blockColor(block)));
                    g.fill(new Rectangle2D.Float(cx - size / 2f + 0.5f, cy - size / 2f + 0.5f, size - 1, size - 1));
                }
                g.setComposite(old);
                if(b.team != player.team()){
                    g.setColor(new Color(b.team.color.argb8888()));
                    g.setStroke(new BasicStroke(Math.max(1f, scale / 8f)));
                    g.draw(new Rectangle2D.Float(cx - size / 2f, cy - size / 2f, size, size));
                }
            }
        }
        //logic display contents over their blocks
        LinkedHashSet<LogicDisplayBuild> roots = new LinkedHashSet<>();
        for(Building b : seen){
            if(b instanceof LogicDisplayBuild d) roots.add(d.rootDisplay);
        }
        for(LogicDisplayBuild d : roots) drawDisplay(g, d, x0, y0, H, s);

        //units
        if(units){
            for(Unit u : Groups.unit){
                float cx = (u.x - (x0 * 8 - 4)) * s, cy = H - (u.y - (y0 * 8 - 4)) * s;
                if(cx < -64 || cy < -64 || cx > W + 64 || cy > H + 64) continue;
                BufferedImage icon = useSprites ? Atlas.icon(u.type) : null;
                if(icon != null){
                    float iw = icon.getWidth() / 4f * s, ih = icon.getHeight() / 4f * s;
                    AffineTransform at = new AffineTransform();
                    at.translate(cx, cy);
                    at.rotate(-Math.toRadians(u.rotation - 90));
                    at.translate(-iw / 2f, -ih / 2f);
                    at.scale(iw / icon.getWidth(), ih / icon.getHeight());
                    g.drawImage(icon, at, null);
                }
                float r = Math.max(2f, u.hitSize / 2f * s);
                g.setColor(new Color(u.team.color.argb8888()));
                g.setStroke(new BasicStroke(Math.max(1f, scale / 10f)));
                g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
                if(u.isPlayer()) text(img, (int)(cx - r), (int)(cy - r) - 2, Strings.stripColors(u.getPlayer().name), 0xffffffff, Math.max(1, scale / 16));
            }
        }

        //grid with coordinate labels
        if(grid > 0){
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(1f));
            int zoom = Math.max(1, scale / 16);
            for(int tx = x0; tx < x0 + w; tx++){
                if(tx % grid != 0) continue;
                int px = (tx - x0) * scale;
                g.drawLine(px, 0, px, H);
                text(img, px + 2, H - 2, String.valueOf(tx), 0xffffff80, zoom);
            }
            for(int ty = y0; ty < y0 + h; ty++){
                if(ty % grid != 0) continue;
                int py = (y0 + h - ty) * scale;
                g.drawLine(0, py, W, py);
                text(img, 2, py - 2, String.valueOf(ty), 0xffffff80, zoom);
            }
        }
        g.dispose();
        return png(img);
    }

    static void drawDisplay(Graphics2D g, LogicDisplayBuild d, int x0, int y0, int H, float s){
        Raster r = rasters.get(d);
        if(r == null) return;
        float left, bottom, ww, hh;
        if(d instanceof TileableLogicDisplayBuild t){
            left = t.originX * 8 - 4 + frameSize / 4f;
            bottom = t.originY * 8 - 4 + frameSize / 4f;
            ww = r.img.getWidth() / 4f;
            hh = r.img.getHeight() / 4f;
        }else{
            float f = ((LogicDisplay)d.block).scaleFactor;
            ww = r.img.getWidth() * f / 4f;
            hh = r.img.getHeight() * f / 4f;
            left = d.x - ww / 2f;
            bottom = d.y - hh / 2f;
        }
        float px = (left - (x0 * 8 - 4)) * s, py = H - (bottom + hh - (y0 * 8 - 4)) * s;
        g.drawImage(r.img, Math.round(px), Math.round(py), Math.round(ww * s), Math.round(hh * s), null);
    }

    /** Label in the display font: x,y = bottom-left corner of the line in image coordinates. */
    static void text(BufferedImage img, int x, int y, String str, int color, int zoom){
        int cx = x;
        for(char c : str.toCharArray()){
            Glyph gl = glyphs.get(c);
            if(gl != null){
                int left = cx + gl.xo * zoom, top = y - (int)((gl.yo + fontCap + fontAscent + gl.h) * zoom);
                for(int yy = 0; yy < gl.h; yy++){
                    for(int xx = 0; xx < gl.w; xx++){
                        int a = gl.alpha[yy * gl.w + xx] & 0xff;
                        if(a < 128) continue;
                        for(int zy = 0; zy < zoom; zy++){
                            for(int zx = 0; zx < zoom; zx++){
                                int px = left + xx * zoom + zx, py = top + yy * zoom + zy;
                                if(px >= 0 && py >= 0 && px < img.getWidth() && py < img.getHeight()) img.setRGB(px, py, color);
                            }
                        }
                    }
                }
            }
            cx += (int)fontAdvance * zoom;
        }
    }

    // ---------- game sprites ----------

    static class Atlas{
        static boolean tried, loaded;
        static final HashMap<String, Region> regions = new HashMap<>();
        static final HashMap<String, BufferedImage> cache = new HashMap<>();
        static int[] blockColors;

        record Region(BufferedImage page, int left, int top, int width, int height, int ow, int oh, float ox, float oy){}

        static int blockColor(Block b){
            if(blockColors == null){
                blockColors = new int[0];
                try{
                    BufferedImage img = ImageIO.read(resource("sprites/block_colors.png"));
                    blockColors = new int[img.getWidth()];
                    for(int i = 0; i < img.getWidth(); i++) blockColors[i] = img.getRGB(i, 0);
                }catch(Exception e){
                    Log.err("block_colors", e);
                }
            }
            int c = b.id < blockColors.length ? blockColors[b.id] : 0;
            if((c >>> 24) == 0 || (c & 0xffffff) == 0) return b.mapColor.argb8888() | 0xff000000;
            return c | 0xff000000;
        }

        static synchronized boolean load(){
            if(tried) return loaded;
            tried = true;
            try{
                Path dir = Files.createTempDirectory("mbot-sprites");
                Path pack = dir.resolve("sprites.aatls");
                try(InputStream in = resource("sprites/sprites.aatls")){
                    Files.copy(in, pack);
                }
                TextureAtlasData data = new TextureAtlasData(new Fi(pack.toFile()), new Fi(dir.toFile()), false);
                HashMap<Object, BufferedImage> pages = new HashMap<>();
                for(var page : data.getPages()){
                    try(InputStream in = resource("sprites/" + page.textureFile.name())){
                        pages.put(page, ImageIO.read(in));
                    }
                }
                for(var r : data.getRegions()){
                    regions.put(r.name, new Region(pages.get(r.page), r.left, r.top, r.width, r.height, r.originalWidth, r.originalHeight, r.offsetX, r.offsetY));
                }
                loaded = true;
                Log.info("Sprites loaded: @ regions", regions.size());
            }catch(Throwable e){
                Log.err("sprites not loaded, snapshots will be colored squares", e);
            }
            return loaded;
        }

        static BufferedImage find(String name){
            if(!load()) return null;
            if(cache.containsKey(name)) return cache.get(name);
            Region r = regions.get(name);
            BufferedImage img = null;
            if(r != null && r.page != null){
                BufferedImage sub = r.page.getSubimage(r.left, r.top, r.width, r.height);
                img = new BufferedImage(Math.max(r.ow, r.width), Math.max(r.oh, r.height), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.drawImage(sub, (int)r.ox, img.getHeight() - r.height - (int)r.oy, null);
                g.dispose();
            }
            cache.put(name, img);
            return img;
        }

        static BufferedImage first(String... names){
            for(String n : names){
                BufferedImage img = find(n);
                if(img != null) return img;
            }
            return null;
        }

        static BufferedImage icon(UnlockableContent c){
            String type = c.getContentType().name();
            return first(type + "-" + c.name + "-full", c.name + "-full", c.name, type + "-" + c.name, c.name + "1");
        }

        static BufferedImage variant(Block b, Tile t){
            if(b.variants > 0){
                BufferedImage v = find(b.name + (1 + Mathf.randomSeed(t.pos(), 0, Math.max(0, b.variants - 1))));
                if(v != null) return v;
            }
            return first(b.name, b.name + "1");
        }
    }
}
