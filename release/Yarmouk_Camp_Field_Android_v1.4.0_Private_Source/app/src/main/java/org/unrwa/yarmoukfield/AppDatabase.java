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
        public int sequence, sectorOrder=999, routeOrder=999, streetOrder=999, alleyOrder=999;
        public String name="", registration="", fileNo="", relation="", relatedTo="", phone1="", phone2="";
        public String address="", sector="غير مصنف", street="", amount="", matchStatus="", visitStatus="pending", notes="";
        public String originalAddress="", addressSource="";
        public String workflowStage="study",studyStatus="draft",damageNotes="",quantitiesNotes="",drawingUri="",followupBatch="";
        public long approvedAt;
        public double confidence;
    }

    public static final class Stats {
        public int total, pending, completed, review;
    }

    public static final class Photo {
        public long id, beneficiaryId, capturedAt;
        public String phase="", uri="", fileName="", note="";
    }

    public AppDatabase(Context context) { super(context, "yarmouk_field.db", null, 3); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0," +
                "name TEXT,registration TEXT,relation TEXT,related_to TEXT,phone1 TEXT,phone2 TEXT,address TEXT,sector TEXT,street TEXT,amount TEXT," +
                "match_status TEXT,confidence REAL DEFAULT 0,sector_order INTEGER DEFAULT 999,route_order INTEGER DEFAULT 999,street_order INTEGER DEFAULT 999," +
                "alley_order INTEGER DEFAULT 999,visit_status TEXT DEFAULT 'pending',notes TEXT DEFAULT '',workflow_stage TEXT DEFAULT 'study',"+
                "study_status TEXT DEFAULT 'draft',damage_notes TEXT DEFAULT '',quantities_notes TEXT DEFAULT '',drawing_uri TEXT DEFAULT '',"+
                "followup_batch TEXT DEFAULT '',approved_at INTEGER DEFAULT 0,updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_beneficiary_project_route ON beneficiaries(project_id,sector_order,route_order,street_order,alley_order)");
        db.execSQL("CREATE INDEX idx_beneficiary_name ON beneficiaries(project_id,name)");
        db.execSQL("CREATE INDEX idx_beneficiary_stage ON beneficiaries(project_id,workflow_stage,study_status)");
        createPhotoSchema(db);
        ContentValues values = new ContentValues(); values.put("name", DEFAULT_PROJECT); values.put("created_at", System.currentTimeMillis());
        db.insert("projects", null, values);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<2)createPhotoSchema(db);
        if(oldVersion<3){
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN workflow_stage TEXT DEFAULT 'study'");
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN study_status TEXT DEFAULT 'draft'");
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN damage_notes TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN quantities_notes TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN drawing_uri TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN followup_batch TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE beneficiaries ADD COLUMN approved_at INTEGER DEFAULT 0");
            db.execSQL("UPDATE beneficiaries SET workflow_stage='followup',study_status='approved',followup_batch='بيانات سابقة',approved_at=updated_at");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_beneficiary_stage ON beneficiaries(project_id,workflow_stage,study_status)");
        }
    }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    private static void createPhotoSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS beneficiary_photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "phase TEXT NOT NULL,uri TEXT NOT NULL UNIQUE,file_name TEXT,captured_at INTEGER NOT NULL,note TEXT DEFAULT '',"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photo_beneficiary_phase ON beneficiary_photos(beneficiary_id,phase,captured_at)");
    }

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

    public long addBeneficiary(long projectId,Beneficiary beneficiary){
        beneficiary.projectId=projectId;
        if(beneficiary.sequence<=0){
            try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(sequence_no),0)+1 FROM beneficiaries WHERE project_id=?",new String[]{String.valueOf(projectId)})){
                if(c.moveToFirst())beneficiary.sequence=c.getInt(0);
            }
        }
        long id=getWritableDatabase().insertOrThrow("beneficiaries",null,values(beneficiary));beneficiary.id=id;return id;
    }

    public Beneficiary beneficiary(long id){
        try(Cursor c=getReadableDatabase().query("beneficiaries",null,"_id=?",new String[]{String.valueOf(id)},null,null,null)){
            return c.moveToFirst()?read(c):null;
        }
    }

    private ContentValues values(Beneficiary b) {
        ContentValues v=new ContentValues();
        v.put("project_id",b.projectId);v.put("sequence_no",b.sequence);v.put("name",b.name);v.put("registration",b.registration);
        v.put("relation",b.relation);v.put("related_to",b.relatedTo);v.put("phone1",b.phone1);v.put("phone2",b.phone2);
        v.put("address",b.address);v.put("sector",b.sector);v.put("street",b.street);v.put("amount",b.amount);
        v.put("match_status",b.matchStatus);v.put("confidence",b.confidence);v.put("sector_order",b.sectorOrder);
        v.put("route_order",b.routeOrder);v.put("street_order",b.streetOrder);v.put("alley_order",b.alleyOrder);
        v.put("visit_status",b.visitStatus);v.put("notes",b.notes);v.put("workflow_stage",b.workflowStage);v.put("study_status",b.studyStatus);
        v.put("damage_notes",b.damageNotes);v.put("quantities_notes",b.quantitiesNotes);v.put("drawing_uri",b.drawingUri);
        v.put("followup_batch",b.followupBatch);v.put("approved_at",b.approvedAt);v.put("updated_at",System.currentTimeMillis()); return v;
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

    public List<Beneficiary> listStage(long projectId,String search,String stage,String status) {
        List<Beneficiary> rows=new ArrayList<>();StringBuilder where=new StringBuilder("project_id=? AND workflow_stage=?");
        List<String> args=new ArrayList<>();args.add(String.valueOf(projectId));args.add(stage);
        if(search!=null&&!search.trim().isEmpty()){where.append(" AND (name LIKE ? OR address LIKE ? OR phone1 LIKE ? OR phone2 LIKE ? OR registration LIKE ?)");String q="%"+search.trim()+"%";for(int i=0;i<5;i++)args.add(q);}
        if(status!=null&&!status.equals("all")){where.append(" AND ").append("study".equals(stage)?"study_status":"visit_status").append("=?");args.add(status);}
        try(Cursor c=getReadableDatabase().query("beneficiaries",null,where.toString(),args.toArray(new String[0]),null,null,
                "sector_order,route_order,street_order,alley_order,address,sequence_no,_id")){
            while(c.moveToNext())rows.add(read(c));
        }
        return rows;
    }

    public int stageCount(long projectId,String stage){return scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage=?",new String[]{String.valueOf(projectId),stage});}
    public int studyReadyCount(long projectId){return scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage='study' AND study_status='ready'",new String[]{String.valueOf(projectId)});}
    public int followupCompletedCount(long projectId){return scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage='followup' AND visit_status='completed'",new String[]{String.valueOf(projectId)});}
    private int scalar(String sql,String[]args){try(Cursor c=getReadableDatabase().rawQuery(sql,args)){return c.moveToFirst()?c.getInt(0):0;}}

    public void updateStudy(long id,String address,String damageNotes,String quantitiesNotes,String amount,String drawingUri){
        ContentValues v=new ContentValues();v.put("address",address==null?"":address.trim());v.put("damage_notes",damageNotes==null?"":damageNotes.trim());
        v.put("quantities_notes",quantitiesNotes==null?"":quantitiesNotes.trim());v.put("amount",amount==null?"":amount.trim());
        if(drawingUri!=null)v.put("drawing_uri",drawingUri);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    public void updateClassification(long id,AddressClassifier.Match match,String source){
        ContentValues v=new ContentValues();v.put("sector",match.sector);v.put("street",match.street);v.put("match_status",source+" | "+match.review);v.put("confidence",match.confidence);
        v.put("sector_order",match.sectorIndex);v.put("route_order",match.routeIndex);v.put("street_order",match.streetIndex);v.put("alley_order",match.alleyIndex);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    public void setDrawingUri(long id,String uri){ContentValues v=new ContentValues();v.put("drawing_uri",uri==null?"":uri);v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});}

    public void markStudyReady(long id){ContentValues v=new ContentValues();v.put("study_status","ready");v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});}

    public int promoteReadyBatch(long projectId,String batchName){
        SQLiteDatabase database=getWritableDatabase();ContentValues v=new ContentValues();v.put("workflow_stage","followup");v.put("study_status","approved");v.put("followup_batch",batchName);v.put("approved_at",System.currentTimeMillis());v.put("visit_status","pending");v.put("updated_at",System.currentTimeMillis());
        return database.update("beneficiaries",v,"_id IN (SELECT _id FROM beneficiaries WHERE project_id=? AND workflow_stage='study' AND study_status='ready' ORDER BY sequence_no,_id LIMIT 50)",new String[]{String.valueOf(projectId)});
    }

    public void updateVisit(long id,String status,String notes) {
        ContentValues v=new ContentValues();v.put("visit_status",status);v.put("notes",notes==null?"":notes);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    public long addPhoto(long beneficiaryId,String phase,String uri,String fileName,long capturedAt,String note){
        ContentValues v=new ContentValues();v.put("beneficiary_id",beneficiaryId);v.put("phase",phase);v.put("uri",uri);v.put("file_name",fileName);
        v.put("captured_at",capturedAt);v.put("note",note==null?"":note);return getWritableDatabase().insertOrThrow("beneficiary_photos",null,v);
    }

    public void updatePhotoNote(long photoId,String note){
        ContentValues v=new ContentValues();v.put("note",note==null?"":note);getWritableDatabase().update("beneficiary_photos",v,"_id=?",new String[]{String.valueOf(photoId)});
    }

    public List<Photo> photos(long beneficiaryId,String phase){
        List<Photo> rows=new ArrayList<>();String selection="beneficiary_id=?";List<String>args=new ArrayList<>();args.add(String.valueOf(beneficiaryId));
        if(phase!=null&&!phase.isEmpty()){selection+=" AND phase=?";args.add(phase);}
        try(Cursor c=getReadableDatabase().query("beneficiary_photos",null,selection,args.toArray(new String[0]),null,null,"captured_at DESC,_id DESC")){
            while(c.moveToNext()){Photo p=new Photo();p.id=longValue(c,"_id");p.beneficiaryId=longValue(c,"beneficiary_id");p.phase=str(c,"phase");p.uri=str(c,"uri");p.fileName=str(c,"file_name");p.capturedAt=longValue(c,"captured_at");p.note=str(c,"note");rows.add(p);}
        }
        return rows;
    }

    public int photoCount(long beneficiaryId,String phase){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM beneficiary_photos WHERE beneficiary_id=? AND phase=?",new String[]{String.valueOf(beneficiaryId),phase})){
            return c.moveToFirst()?c.getInt(0):0;
        }
    }

    public void deleteProjectData(long projectId) { getWritableDatabase().delete("beneficiaries","project_id=?",new String[]{String.valueOf(projectId)}); }

    private Beneficiary read(Cursor c){
        Beneficiary b=new Beneficiary(); b.id=longValue(c,"_id");b.projectId=longValue(c,"project_id");b.sequence=intValue(c,"sequence_no");
        b.name=str(c,"name");b.registration=str(c,"registration");b.relation=str(c,"relation");b.relatedTo=str(c,"related_to");
        b.phone1=str(c,"phone1");b.phone2=str(c,"phone2");b.address=str(c,"address");b.sector=str(c,"sector");b.street=str(c,"street");
        b.amount=str(c,"amount");b.matchStatus=str(c,"match_status");b.confidence=doubleValue(c,"confidence");b.sectorOrder=intValue(c,"sector_order");
        b.routeOrder=intValue(c,"route_order");b.streetOrder=intValue(c,"street_order");b.alleyOrder=intValue(c,"alley_order");
        b.visitStatus=str(c,"visit_status");b.notes=str(c,"notes");b.workflowStage=str(c,"workflow_stage");b.studyStatus=str(c,"study_status");
        b.damageNotes=str(c,"damage_notes");b.quantitiesNotes=str(c,"quantities_notes");b.drawingUri=str(c,"drawing_uri");b.followupBatch=str(c,"followup_batch");b.approvedAt=longValue(c,"approved_at");return b;
    }
    private static String str(Cursor c,String n){String v=c.getString(c.getColumnIndexOrThrow(n));return v==null?"":v;}
    private static int intValue(Cursor c,String n){return c.getInt(c.getColumnIndexOrThrow(n));}
    private static long longValue(Cursor c,String n){return c.getLong(c.getColumnIndexOrThrow(n));}
    private static double doubleValue(Cursor c,String n){return c.getDouble(c.getColumnIndexOrThrow(n));}

    public JSONObject backup(long projectId,String projectName) throws Exception {
        JSONObject root=new JSONObject();root.put("schema_version","1.2");root.put("project",projectName);root.put("exported_at",System.currentTimeMillis());
        JSONArray data=new JSONArray();for(Beneficiary b:list(projectId,"","all")){JSONObject o=new JSONObject();o.put("name",b.name);o.put("registration",b.registration);
            o.put("relation",b.relation);o.put("related_to",b.relatedTo);o.put("phone1",b.phone1);o.put("phone2",b.phone2);o.put("address",b.address);
            o.put("sector",b.sector);o.put("street",b.street);o.put("amount",b.amount);o.put("match_status",b.matchStatus);o.put("visit_status",b.visitStatus);o.put("notes",b.notes);
            o.put("workflow_stage",b.workflowStage);o.put("study_status",b.studyStatus);o.put("damage_notes",b.damageNotes);o.put("quantities_notes",b.quantitiesNotes);o.put("drawing_uri",b.drawingUri);o.put("followup_batch",b.followupBatch);o.put("approved_at",b.approvedAt);
            JSONArray images=new JSONArray();for(Photo p:photos(b.id,"")){JSONObject image=new JSONObject();image.put("phase",p.phase);image.put("uri",p.uri);image.put("file_name",p.fileName);image.put("captured_at",p.capturedAt);image.put("note",p.note);images.put(image);}o.put("photos",images);data.put(o);}root.put("beneficiaries",data);return root;
    }
}
