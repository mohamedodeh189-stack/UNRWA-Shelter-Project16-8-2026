package org.unrwa.yarmoukfield;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AppDatabase extends SQLiteOpenHelper {
    public static final String DEFAULT_PROJECT = "مشروع مخيم اليرموك";

    public static final class Beneficiary {
        public long id, projectId;
        public int sequence, sectorOrder, routeOrder, streetOrder, alleyOrder;
        public String name="", registration="", relation="", relatedTo="", phone1="", phone2="";
        public String address="", sector="غير مصنف", street="", amount="", matchStatus="", visitStatus="pending", notes="";
        public double confidence;
    }

    public static final class Stats {
        public int total, pending, completed, review;
    }

    public AppDatabase(Context context) { super(context, "yarmouk_field.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0," +
                "name TEXT,registration TEXT,relation TEXT,related_to TEXT,phone1 TEXT,phone2 TEXT,address TEXT,sector TEXT,street TEXT,amount TEXT," +
                "match_status TEXT,confidence REAL DEFAULT 0,sector_order INTEGER DEFAULT 999,route_order INTEGER DEFAULT 999,street_order INTEGER DEFAULT 999," +
                "alley_order INTEGER DEFAULT 999,visit_status TEXT DEFAULT 'pending',notes TEXT DEFAULT '',updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_beneficiary_project_route ON beneficiaries(project_id,sector_order,route_order,street_order,alley_order)");
        db.execSQL("CREATE INDEX idx_beneficiary_name ON beneficiaries(project_id,name)");
        ContentValues values = new ContentValues(); values.put("name", DEFAULT_PROJECT); values.put("created_at", System.currentTimeMillis());
        db.insert("projects", null, values);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    public long ensureProject(String name) {
        String clean = name == null || name.trim().isEmpty() ? DEFAULT_PROJECT : name.trim();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT _id FROM projects WHERE name=?", new String[]{clean})) { if (c.moveToFirst()) return c.getLong(0); }
        ContentValues values = new ContentValues(); values.put("name", clean); values.put("created_at", System.currentTimeMillis());
        return db.insertOrThrow("projects", null, values);
    }

    public List<String> listProjects() {
        List<String> names = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name FROM projects ORDER BY _id", null)) {
            while(c.moveToNext()) names.add(c.getString(0));
        }
        return names;
    }

    public long projectId(String name) { return ensureProject(name); }

    public void importBeneficiaries(long projectId, List<Beneficiary> rows, boolean replace) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (replace) db.delete("beneficiaries", "project_id=?", new String[]{String.valueOf(projectId)});
            for (Beneficiary b : rows) {
                b.projectId = projectId;
                db.insertOrThrow("beneficiaries", null, values(b));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private ContentValues values(Beneficiary b) {
        ContentValues v=new ContentValues();
        v.put("project_id",b.projectId);v.put("sequence_no",b.sequence);v.put("name",b.name);v.put("registration",b.registration);
        v.put("relation",b.relation);v.put("related_to",b.relatedTo);v.put("phone1",b.phone1);v.put("phone2",b.phone2);
        v.put("address",b.address);v.put("sector",b.sector);v.put("street",b.street);v.put("amount",b.amount);
        v.put("match_status",b.matchStatus);v.put("confidence",b.confidence);v.put("sector_order",b.sectorOrder);
        v.put("route_order",b.routeOrder);v.put("street_order",b.streetOrder);v.put("alley_order",b.alleyOrder);
        v.put("visit_status",b.visitStatus);v.put("notes",b.notes);v.put("updated_at",System.currentTimeMillis()); return v;
    }

    public Stats stats(long projectId) {
        Stats s=new Stats();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(CASE WHEN visit_status='pending' THEN 1 ELSE 0 END),"+
                "SUM(CASE WHEN visit_status='completed' THEN 1 ELSE 0 END),SUM(CASE WHEN match_status LIKE '%مراجعة%' OR visit_status='review' THEN 1 ELSE 0 END) " +
                "FROM beneficiaries WHERE project_id=?",new String[]{String.valueOf(projectId)})) {
            if(c.moveToFirst()){s.total=c.getInt(0);s.pending=c.getInt(1);s.completed=c.getInt(2);s.review=c.getInt(3);} }
        return s;
    }

    public List<Beneficiary> list(long projectId,String search,String status) {
        List<Beneficiary> rows=new ArrayList<>(); StringBuilder where=new StringBuilder("project_id=?");
        List<String> args=new ArrayList<>();args.add(String.valueOf(projectId));
        if(search!=null&&!search.trim().isEmpty()){where.append(" AND (name LIKE ? OR address LIKE ? OR phone1 LIKE ? OR phone2 LIKE ? OR registration LIKE ?)");String q="%"+search.trim()+"%";for(int i=0;i<5;i++)args.add(q);}
        if(status!=null&&!status.equals("all")){where.append(" AND visit_status=?");args.add(status);}
        try(Cursor c=getReadableDatabase().query("beneficiaries",null,where.toString(),args.toArray(new String[0]),null,null,
                "sector_order,route_order,street_order,alley_order,address,sequence_no,_id")) {
            while(c.moveToNext()) rows.add(read(c));
        }
        return rows;
    }

    public void updateVisit(long id,String status,String notes) {
        ContentValues v=new ContentValues();v.put("visit_status",status);v.put("notes",notes==null?"":notes);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    public void deleteProjectData(long projectId) { getWritableDatabase().delete("beneficiaries","project_id=?",new String[]{String.valueOf(projectId)}); }

    private Beneficiary read(Cursor c){
        Beneficiary b=new Beneficiary(); b.id=longValue(c,"_id");b.projectId=longValue(c,"project_id");b.sequence=intValue(c,"sequence_no");
        b.name=str(c,"name");b.registration=str(c,"registration");b.relation=str(c,"relation");b.relatedTo=str(c,"related_to");
        b.phone1=str(c,"phone1");b.phone2=str(c,"phone2");b.address=str(c,"address");b.sector=str(c,"sector");b.street=str(c,"street");
        b.amount=str(c,"amount");b.matchStatus=str(c,"match_status");b.confidence=doubleValue(c,"confidence");b.sectorOrder=intValue(c,"sector_order");
        b.routeOrder=intValue(c,"route_order");b.streetOrder=intValue(c,"street_order");b.alleyOrder=intValue(c,"alley_order");
        b.visitStatus=str(c,"visit_status");b.notes=str(c,"notes");return b;
    }
    private static String str(Cursor c,String n){String v=c.getString(c.getColumnIndexOrThrow(n));return v==null?"":v;}
    private static int intValue(Cursor c,String n){return c.getInt(c.getColumnIndexOrThrow(n));}
    private static long longValue(Cursor c,String n){return c.getLong(c.getColumnIndexOrThrow(n));}
    private static double doubleValue(Cursor c,String n){return c.getDouble(c.getColumnIndexOrThrow(n));}

    public JSONObject backup(long projectId,String projectName) throws Exception {
        JSONObject root=new JSONObject();root.put("schema_version","1.0");root.put("project",projectName);root.put("exported_at",System.currentTimeMillis());
        JSONArray data=new JSONArray();for(Beneficiary b:list(projectId,"","all")){JSONObject o=new JSONObject();o.put("name",b.name);o.put("registration",b.registration);
            o.put("relation",b.relation);o.put("related_to",b.relatedTo);o.put("phone1",b.phone1);o.put("phone2",b.phone2);o.put("address",b.address);
            o.put("sector",b.sector);o.put("street",b.street);o.put("amount",b.amount);o.put("match_status",b.matchStatus);o.put("visit_status",b.visitStatus);o.put("notes",b.notes);data.put(o);}root.put("beneficiaries",data);return root;
    }
}
