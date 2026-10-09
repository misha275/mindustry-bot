package mbot;

import arc.func.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.ui.dialogs.*;
import mindustry.ui.fragments.*;
import sun.misc.Unsafe;

import java.lang.reflect.*;

/**
 * Stub of the game UI. Mindustry client code (NetClient, Net, Menus) calls Vars.ui
 * directly, and headless mode has none. Objects are created via Unsafe without calling constructors,
 * so no graphics get initialized; every method that is used is overridden.
 * Everything the server shows the player (messages, menus, kicks) goes into the bot's event log.
 */
public class BotUI extends UI{
    static Unsafe unsafe;

    static{
        try{
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe)f.get(null);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type){
        try{
            return (T)unsafe.allocateInstance(type);
        }catch(InstantiationException e){
            throw new RuntimeException(e);
        }
    }

    public static BotUI create(){
        BotUI ui = alloc(BotUI.class);
        ui.loadfrag = alloc(StubLoading.class);
        ui.chatfrag = alloc(StubChat.class);
        ui.join = alloc(StubJoin.class);
        return ui;
    }

    /** Control stub: in v160 GlobalVars reads control.sound even without graphics. */
    public static Control createControl(){
        Control c = alloc(Control.class);
        c.sound = alloc(StubSound.class);
        c.input = alloc(mindustry.input.DesktopInput.class);
        return c;
    }

    public static class StubSound extends mindustry.audio.SoundControl{
        @Override public boolean isPlaying(){ return false; }
        @Override public arc.audio.Music getCurrent(){ return null; }
        @Override public void update(){}
        @Override public void stop(){}
    }

    static void ev(String type, String text){
        Bot.events.add(type, text == null ? "" : Strings.stripColors(text).replace("[[", "["));
    }

    @Override public void showText(String title, String text){ ev("info", join(title, text)); }
    @Override public void showText(String title, String text, int align){ ev("info", join(title, text)); }
    @Override public void showSmall(String title, String text){ ev("info", join(title, text)); }
    @Override public void showInfo(String info){ ev("info", info); }
    @Override public void showInfoFade(String info){ ev("info", info); }
    @Override public void showInfoFade(String info, float duration){ ev("info", info); }
    @Override public void showInfoToast(String info, float duration){ ev("toast", info); }
    @Override public void showInfoPopup(String info, String id, float duration, int align, int top, int left, int bottom, int right){ ev("popup", info); }
    @Override public void showLabel(String info, int id, float duration, float worldx, float worldy, int flags){
        ev("label", info + " @ " + (int)(worldx / 8) + "," + (int)(worldy / 8));
    }
    @Override public void showErrorMessage(String text){ ev("error", text); }
    @Override public void showException(Throwable t){ ev("error", Strings.getSimpleMessage(t)); }
    @Override public void showException(String text, Throwable exc){ ev("error", text + ": " + Strings.getSimpleMessage(exc)); }
    @Override public void showConfirm(String text, Runnable confirmed){ ev("confirm", text); }
    @Override public void showConfirm(String title, String text, Runnable confirmed){ ev("confirm", join(title, text)); }
    @Override public void showConfirm(String title, String text, Boolp hide, Runnable confirmed){ ev("confirm", join(title, text)); }
    @Override public void announce(String text){ ev("announce", text); }
    @Override public void announce(String text, float duration){ ev("announce", text); }

    @Override
    public void showTextInput(String title, String text, int textLength, String def, boolean numbers, boolean allowEmpty, Cons<String> confirmed, Runnable closed){
        Bot.menus.putText(confirmed, title, text, def);
    }
    @Override public void showTextInput(String title, String text, int textLength, String def, boolean numbers, Cons<String> confirmed, Runnable closed){ showTextInput(title, text, textLength, def, numbers, false, confirmed, closed); }
    @Override public void showTextInput(String title, String text, String def, Cons<String> confirmed){ showTextInput(title, text, 0, def, false, confirmed, null); }
    @Override public void showTextInput(String title, String text, int textLength, String def, Cons<String> confirmed){ showTextInput(title, text, textLength, def, false, confirmed, null); }
    @Override public void showTextInput(String title, String text, int textLength, String def, boolean numeric, Cons<String> confirmed){ showTextInput(title, text, textLength, def, numeric, confirmed, null); }

    static String join(String a, String b){
        a = a == null ? "" : a.startsWith("@") ? tr(a) : a;
        b = b == null ? "" : b.startsWith("@") ? tr(b) : b;
        return a.isEmpty() ? b : a + ": " + b;
    }

    static String tr(String key){
        try{
            String s = arc.Core.bundle.get(key.substring(1));
            return s == null || s.startsWith("???") ? key.substring(1) : s;
        }catch(Throwable t){
            return key.substring(1);
        }
    }

    public static class StubLoading extends LoadingFragment{
        @Override public void show(){}
        @Override public void show(String text){}
        @Override public void hide(){}
        @Override public void setButton(Runnable listener){}
        @Override public void setProgress(Floatp progress){}
        @Override public void setProgress(float progress){}
        @Override public void snapProgress(){}
        @Override public void setText(String text){}
        @Override public void setText(String text, arc.graphics.Color color){}
        @Override public void showProgressBar(){}
        @Override public boolean showingProgress(){ return false; }
        @Override public boolean shown(){ return false; }
        @Override public void toFront(){}
    }

    public static class StubChat extends ChatFragment{
        @Override public void addMessage(String message){
            Bot.events.add("chat", Strings.stripColors(message).replace("[[", "["));
        }
        @Override public void clearMessages(){}
        @Override public boolean shown(){ return false; }
        @Override public void toggle(){}
        @Override public void hide(){}
    }

    public static class StubJoin extends JoinDialog{
        @Override public void hide(){}
        @Override public void connect(String ip, int port){
            Bot.instance.connect(ip, port);
        }
        @Override public void reconnect(){
            Bot.instance.scheduleReconnect(3f);
        }
    }
}
