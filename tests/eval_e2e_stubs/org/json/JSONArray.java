package org.json;
import com.google.gson.*;
public class JSONArray {
    final JsonArray a;
    public JSONArray(){ a = new JsonArray(); }
    JSONArray(JsonArray src){ a = src; }
    public JSONArray(String json){ a = JsonParser.parseString(json).getAsJsonArray(); }
    public JSONArray put(Object v){
        if (v instanceof JSONObject) a.add(((JSONObject) v).o);
        else if (v instanceof JSONArray) a.add(((JSONArray) v).a);
        else if (v instanceof Number) a.add((Number) v);
        else a.add(v == null ? null : String.valueOf(v));
        return this;
    }
    public int length(){ return a.size(); }
    public JSONObject getJSONObject(int i){ return new JSONObject(a.get(i).getAsJsonObject()); }
    public JSONObject optJSONObject(int i){
        JsonElement e = a.get(i);
        return (e == null || !e.isJsonObject()) ? null : new JSONObject(e.getAsJsonObject());
    }
    @Override public String toString(){ return a.toString(); }
}
