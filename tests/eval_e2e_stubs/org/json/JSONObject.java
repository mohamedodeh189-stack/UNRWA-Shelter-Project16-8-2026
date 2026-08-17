package org.json;
import com.google.gson.*;
import java.util.*;
/** Minimal org.json shim backed by gson — test-only, just enough for EvaluationImporter. */
public class JSONObject {
    final JsonObject o;
    public JSONObject(){ o = new JsonObject(); }
    JSONObject(JsonObject src){ o = src; }
    public JSONObject(String json){ o = JsonParser.parseString(json).getAsJsonObject(); }
    public JSONObject put(String k, Object v){
        if (v instanceof JSONObject) o.add(k, ((JSONObject) v).o);
        else if (v instanceof JSONArray) o.add(k, ((JSONArray) v).a);
        else if (v instanceof Number) o.addProperty(k, (Number) v);
        else if (v instanceof Boolean) o.addProperty(k, (Boolean) v);
        else o.addProperty(k, v == null ? null : String.valueOf(v));
        return this;
    }
    public String optString(String k){ return optString(k, ""); }
    public String optString(String k, String def){
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? def : e.getAsString();
    }
    public JSONObject optJSONObject(String k){
        JsonElement e = o.get(k);
        return (e == null || !e.isJsonObject()) ? null : new JSONObject(e.getAsJsonObject());
    }
    public JSONArray optJSONArray(String k){
        JsonElement e = o.get(k);
        return (e == null || !e.isJsonArray()) ? null : new JSONArray(e.getAsJsonArray());
    }
    public boolean has(String k){ return o.has(k); }
    public Iterator<String> keys(){ return new ArrayList<>(o.keySet()).iterator(); }
    @Override public String toString(){ return o.toString(); }
}
