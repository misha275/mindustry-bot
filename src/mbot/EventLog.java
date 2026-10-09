package mbot;

import arc.func.*;

import java.util.*;

/** Event log (chat, server messages, connection) with sequence numbers for polling via /events?since=N. */
public class EventLog{
    static final int max = 2000;
    final ArrayDeque<Map<String, Object>> list = new ArrayDeque<>();
    long nextId = 1;

    public synchronized void add(String type, String text){
        list.addLast(Json.obj("id", nextId++, "time", System.currentTimeMillis(), "type", type, "text", text));
        while(list.size() > max) list.removeFirst();
        notifyAll();
        if(Bot.cfg != null && Bot.cfg.echo) System.out.println("[" + type + "] " + text);
    }

    public synchronized List<Map<String, Object>> since(long id, int limit, String type){
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for(var e : list){
            if((long)e.get("id") > id && (type == null || type.equals(e.get("type")))) out.add(e);
        }
        if(out.size() > limit) return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        return out;
    }

    public synchronized long last(){
        return nextId - 1;
    }

    /** Waits (off the game thread) for an event newer than since that matches type. */
    public synchronized void await(long since, String type, long millis) throws InterruptedException{
        long end = System.currentTimeMillis() + millis;
        while(true){
            boolean found = false;
            for(var e : list){
                if((long)e.get("id") > since && (type == null || type.equals(e.get("type")))){ found = true; break; }
            }
            long left = end - System.currentTimeMillis();
            if(found || left <= 0) return;
            wait(left);
        }
    }

    /** Menus and text prompts the server showed the bot; answer via /menu. */
    public static class Menus{
        int nextId = 1;
        final LinkedHashMap<Integer, Object[]> open = new LinkedHashMap<>();

        public synchronized void put(Intc callback, String title, String message, String[][] options){
            int id = nextId++;
            open.put(id, new Object[]{callback, title, message, options});
            Bot.events.add("menu", "#" + id + " " + BotUI.join(title, message) + " options=" + Arrays.deepToString(options));
        }

        public synchronized void putText(Cons<String> callback, String title, String message, String def){
            int id = nextId++;
            open.put(id, new Object[]{callback, title, message, null});
            Bot.events.add("textinput", "#" + id + " " + BotUI.join(title, message) + " default=" + def);
        }

        @SuppressWarnings("unchecked")
        public synchronized String answer(int id, String value){
            Object[] m = open.remove(id);
            if(m == null) return "no open menu #" + id;
            if(m[3] == null){
                ((Cons<String>)m[0]).get(value);
            }else{
                ((Intc)m[0]).get(Integer.parseInt(value));
            }
            return null;
        }

        public synchronized List<Object> list(){
            ArrayList<Object> out = new ArrayList<>();
            for(var e : open.entrySet()){
                Object[] m = e.getValue();
                out.add(Json.obj("id", e.getKey(), "title", m[1], "message", m[2], "options", m[3], "textInput", m[3] == null));
            }
            return out;
        }

        public synchronized void clear(){
            open.clear();
        }
    }
}
