package mbot;

import java.util.*;

/** Minimal JSON serializer: Map, List, arrays, strings, numbers, boolean, null. */
public class Json{

    public static Map<String, Object> obj(Object... kv){
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for(int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public static String write(Object o){
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object o){
        if(o == null){
            sb.append("null");
        }else if(o instanceof String s){
            str(sb, s);
        }else if(o instanceof Float f){
            num(sb, f.doubleValue());
        }else if(o instanceof Double d){
            num(sb, d);
        }else if(o instanceof Number || o instanceof Boolean){
            sb.append(o);
        }else if(o instanceof Map<?, ?> m){
            sb.append('{');
            boolean first = true;
            for(var e : m.entrySet()){
                if(!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        }else if(o instanceof Iterable<?> it){
            sb.append('[');
            boolean first = true;
            for(Object v : it){
                if(!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
        }else if(o instanceof Object[] arr){
            write(sb, Arrays.asList(arr));
        }else if(o instanceof int[] arr){
            List<Integer> l = new ArrayList<>();
            for(int v : arr) l.add(v);
            write(sb, l);
        }else{
            str(sb, o.toString());
        }
    }

    static void num(StringBuilder sb, double d){
        if(Double.isNaN(d) || Double.isInfinite(d)){
            sb.append("null");
        }else if(d == Math.rint(d) && Math.abs(d) < 1e15){
            sb.append((long)d);
        }else{
            sb.append(Math.round(d * 100.0) / 100.0);
        }
    }

    static void str(StringBuilder sb, String s){
        sb.append('"');
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            switch(c){
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if(c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
