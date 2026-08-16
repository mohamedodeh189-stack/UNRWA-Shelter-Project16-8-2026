package org.unrwa.yarmoukfield;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AppDatabase extends SQLiteOpenHelper {
    public static final String DEFAULT_PROJECT = "مشروع مخيم اليرموك";

    /** Diagnostic trail for every workflow_stage/study_status transition and every row-removing operation —
     * added after a study record could not be located during field acceptance testing and no root cause could
     * be proven from static code review alone. Logs row id, project id, and state labels only — never name,
     * phone, address, or any other personal field, so it is safe to leave enabled in the field. Read with:
     * `adb logcat -s YarmoukDiag`. */
    private static final String DIAG_TAG = "YarmoukDiag";
    private static void diag(String op, long id, String detail) {
        Log.i(DIAG_TAG, "op=" + op + " beneficiary_id=" + id + " " + detail + " at=" + System.currentTimeMillis());
    }
    private static void diag(String op, String detail) {
        Log.i(DIAG_TAG, "op=" + op + " " + detail + " at=" + System.currentTimeMillis());
    }

    public static final class Beneficiary {
        public long id, projectId;
        public int sequence, sectorOrder=999, routeOrder=999, streetOrder=999, alleyOrder=999,progressPercent;
        public String name="", registration="", fileNo="", relation="", relatedTo="", phone1="", phone2="";
        public String address="", sector="غير مصنف", street="", amount="", matchStatus="", visitStatus="pending", notes="";
        public String originalAddress="", addressSource="";
        public String addressesFileAddress="", addressesFileSector=""; // المطلوب 2: from the «العناوين» reference file
        public String workflowStage="study",studyStatus="draft",damageNotes="",quantitiesNotes="",drawingUri="",followupBatch="";
        public long approvedAt;
        public boolean workDelivered;
        /** Who added/last edited this record — lets one merged list show contributions from several engineers instead of only one. */
        public String engineerName="";
        /** فريق الدراسة — first/second engineer + field assistant + study date (YYYY-MM-DD). Purely descriptive
         * metadata carried into the beneficiary package; never drives status or address logic. */
        public String studyEngineer1="",studyEngineer2="",fieldAssistant="",studyDate="";
        /** Timestamp the study was administratively «sent to office review» (0 = not sent). */
        public long sentToOfficeReviewAt;
        /** Followup team fields — names reserved for the (not-yet-built) followup team page; unused for now. */
        public String followupEngineer1="",followupEngineer2="",followupDate="";
        /** F3 — explicit followup/delivery state set by the engineer: "" (derive) / not_started / in_progress /
         * ready / delivered / review / deferred. Authoritative once set; drives the followup counters/filters and
         * the delivery marking. deliveryNote is an internal field note («ملاحظات تنفيذ داخلية»). */
        public String followupStatus="",deliveryNote="";
        public double confidence;
        /** study_uuid stays constant across every corrected revision of the
         * same study; revision_uuid is unique per row. Columns already exist
         * on disk since the Phase 0 migration — this just wires the app to
         * read/write them. */
        public String studyUuid="",revisionUuid="",previousRevisionUuid="";
        public int revisionNo=1;
        /** Real-world GPS coordinates of the home ("تثبيت موقع المنزل") — never pixel/map coordinates. Columns
         * already existed on disk since the Phase 0 migration; this just wires the app to read/write them. */
        public double gpsLat,gpsLng,gpsAccuracyM;
        public long gpsCapturedAt;
        /** Which provider actually produced the saved fix — "gps_offline" (GPS_PROVIDER only, works with no
         * connectivity, the official default) or "device_high_accuracy" (best available system provider,
         * e.g. NETWORK_PROVIDER — faster indoors, but may be Wi-Fi/cell-assisted; always disclosed to the
         * user, never silently treated as pure GPS). Purely informational — never drives address/sector logic. */
        public String gpsSource="";
        /** Date the work/materials were handed to the worker ("تسليم الأعمال"); 0 = not confirmed yet. Set exactly
         * once by confirmDeliveryStart() and never reset by a repeated press. NOT by itself the execution-start
         * date — see executionStartAt(). */
        public long deliveryConfirmedAt;
        /** Date the first (60%) payment actually became available/received — set only by an explicit engineer
         * action (setFirstPaymentAvailable), NEVER inferred from an Excel amount column. Can be corrected or
         * cancelled later; every change is logged in first_payment_log, never silently overwritten. */
        public long firstPaymentAvailableAt;
        public String firstPaymentConfirmedBy="";
        /** Independent, fully manual "ready for second-payment listing" flag — never auto-set by BOQ/photos/the 35-day timer. */
        public boolean secondPaymentReady;
        public String secondPaymentApprovedBy="";
        public long secondPaymentApprovedAt;
        /** Phase 10: reflects only "was this exact revision handed off to a sync transport", never "Windows
         * confirmed receipt" — see ShareIntentDesktopAdapter. Columns already existed on disk since the
         * Phase 0 migration; this just wires the app to read them (never written on generic save paths). */
        public String syncStatus="local";
        public long syncedAt;
        public List<QuantityEntry> quantities=new ArrayList<>();
    }

    /** One BOQ item's delivery/execution status for a beneficiary, plus who last touched it and when. */
    public static final class BoqDeliveryEntry {
        public int itemNo;
        public double quantity,unitPrice;
        public String status="not_delivered",note="",engineerName="";
        public long updatedAt;
        public double subtotal(){return quantity*unitPrice;}
    }

    /** Everything the engineer needs to decide second-payment readiness, gathered in one place — nothing here is ever auto-approved. */
    public static final class SecondPaymentSummary {
        public int totalItems,deliveredCount,receivedCount,pendingCount,duringPhotoCount;
        public double totalAmount;
        public long firstPayment,secondPayment;
    }

    public static final class SecondPaymentLogEntry {
        public String action="",engineerName="",reason="";
        public long at;
    }

    public static final class FirstPaymentLogEntry {
        public String action="",engineerName="",reason="";
        public long at;
    }

    public static final class Stats {
        public int total, pending, completed, review;
    }

    public static final class Photo {
        public long id, beneficiaryId, capturedAt;
        public String phase="", uri="", fileName="", note="";
    }

    public static final class QuantityEntry {
        public int itemNo;
        public double quantity,unitPrice;
        public QuantityEntry(){}
        public QuantityEntry(int itemNo,double quantity,double unitPrice){this.itemNo=itemNo;this.quantity=quantity;this.unitPrice=unitPrice;}
        public double subtotal(){return quantity*unitPrice;}
    }

    public AppDatabase(Context context) { super(context, "yarmouk_field.db", null, 20); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0," +
                "name TEXT,registration TEXT,relation TEXT,related_to TEXT,phone1 TEXT,phone2 TEXT,address TEXT,sector TEXT,street TEXT,amount TEXT," +
                "match_status TEXT,confidence REAL DEFAULT 0,sector_order INTEGER DEFAULT 999,route_order INTEGER DEFAULT 999,street_order INTEGER DEFAULT 999," +
                "alley_order INTEGER DEFAULT 999,visit_status TEXT DEFAULT 'pending',progress_percent INTEGER DEFAULT 0,notes TEXT DEFAULT '',workflow_stage TEXT DEFAULT 'study',"+
                "study_status TEXT DEFAULT 'draft',damage_notes TEXT DEFAULT '',quantities_notes TEXT DEFAULT '',drawing_uri TEXT DEFAULT '',"+
                "followup_batch TEXT DEFAULT '',approved_at INTEGER DEFAULT 0,work_delivered INTEGER DEFAULT 0,engineer_name TEXT DEFAULT ''," +
                "study_uuid TEXT DEFAULT '',revision_uuid TEXT DEFAULT '',revision_no INTEGER DEFAULT 1,previous_revision_uuid TEXT DEFAULT ''," +
                "gps_lat REAL,gps_lng REAL,gps_accuracy_m REAL,gps_captured_at INTEGER DEFAULT 0,gps_source TEXT DEFAULT ''," +
                "sync_status TEXT DEFAULT 'local',synced_at INTEGER DEFAULT 0,delivery_confirmed_at INTEGER DEFAULT 0,updated_at INTEGER NOT NULL," +
                "second_payment_ready INTEGER DEFAULT 0,second_payment_approved_by TEXT DEFAULT '',second_payment_approved_at INTEGER DEFAULT 0," +
                "first_payment_available_at INTEGER DEFAULT 0,first_payment_confirmed_by TEXT DEFAULT ''," +
                "original_address TEXT DEFAULT '',address_source TEXT DEFAULT ''," +
                "addresses_file_address TEXT DEFAULT '',addresses_file_sector TEXT DEFAULT ''," +
                "study_engineer1 TEXT DEFAULT '',study_engineer2 TEXT DEFAULT '',field_assistant TEXT DEFAULT '',study_date TEXT DEFAULT ''," +
                "sent_to_office_review_at INTEGER DEFAULT 0," +
                "followup_engineer1 TEXT DEFAULT '',followup_engineer2 TEXT DEFAULT '',followup_date TEXT DEFAULT ''," +
                "followup_status TEXT DEFAULT '',delivery_note TEXT DEFAULT ''," +
                "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_beneficiary_project_route ON beneficiaries(project_id,sector_order,route_order,street_order,alley_order)");
        db.execSQL("CREATE INDEX idx_beneficiary_name ON beneficiaries(project_id,name)");
        db.execSQL("CREATE INDEX idx_beneficiary_stage ON beneficiaries(project_id,workflow_stage,study_status)");
        createPhotoSchema(db);
        createQuantitySchema(db);
        createDeliveryAndCorrectionSchema(db);
        createRevisionPhotoLinkSchema(db);
        createSecondPaymentLogSchema(db);
        createFirstPaymentLogSchema(db);
        createFollowupLogSchema(db);
        createWorkStatusSchema(db);
        createDamageLocationSchema(db);
        createMapCalibrationSchema(db);
        ContentValues values = new ContentValues(); values.put("name", DEFAULT_PROJECT); values.put("created_at", System.currentTimeMillis());
        db.insert("projects", null, values);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<2)createPhotoSchema(db);
        if(oldVersion<3){
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN workflow_stage TEXT DEFAULT 'study'");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN study_status TEXT DEFAULT 'draft'");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN damage_notes TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN quantities_notes TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN drawing_uri TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN followup_batch TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN approved_at INTEGER DEFAULT 0");
            db.execSQL("UPDATE beneficiaries SET workflow_stage='followup',study_status='approved',followup_batch='بيانات سابقة',approved_at=updated_at");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_beneficiary_stage ON beneficiaries(project_id,workflow_stage,study_status)");
        }
        if(oldVersion<4)createQuantitySchema(db);
        if(oldVersion<5)addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN progress_percent INTEGER DEFAULT 0");
        if(oldVersion<6)addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN work_delivered INTEGER DEFAULT 0");
        if(oldVersion<7)addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN engineer_name TEXT DEFAULT ''");
        if(oldVersion<8){
            // Phase 0 foundation: additive-only columns/tables for revisioned
            // studies, GPS capture, sync bookkeeping, and per-BOQ-item
            // delivery tracking. Nothing here drops or renames anything, and
            // no exception here is ever caught — a failed migration must
            // surface loudly rather than silently corrupt or reset the DB.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN study_uuid TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN revision_uuid TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN revision_no INTEGER DEFAULT 1");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN previous_revision_uuid TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN gps_lat REAL");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN gps_lng REAL");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN gps_accuracy_m REAL");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN gps_captured_at INTEGER DEFAULT 0");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN sync_status TEXT DEFAULT 'local'");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN synced_at INTEGER DEFAULT 0");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN delivery_confirmed_at INTEGER DEFAULT 0");
            createDeliveryAndCorrectionSchema(db);
            // Backfill a stable identity pair for every pre-existing row.
            // study_uuid stays constant across future corrected revisions of
            // the same study; revision_uuid identifies this exact row. Row
            // _id values are never touched — only the two new text columns
            // on each existing row are set.
            try(Cursor c=db.rawQuery("SELECT _id FROM beneficiaries",null)){
                while(c.moveToNext()){
                    long id=c.getLong(0);
                    ContentValues v=new ContentValues();
                    v.put("study_uuid",java.util.UUID.randomUUID().toString());
                    v.put("revision_uuid",java.util.UUID.randomUUID().toString());
                    db.update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
                }
            }
        }
        if(oldVersion<9){
            // Phase 3 correction: a corrected revision must not erase the
            // approved historical record of the revision it supersedes.
            // revision_photo_links lets a revision "see" a physical photo
            // that was actually captured under an earlier revision, without
            // duplicating the beneficiary_photos row (its uri is UNIQUE and
            // cannot be copied) and without moving it away from the
            // revision that originally captured it.
            createRevisionPhotoLinkSchema(db);
        }
        if(oldVersion<10){
            // Phase 4: per-BOQ-item delivery status already had its table (boq_delivery,
            // Phase 0); this only adds the independent, fully-manual second-payment
            // readiness flag + its audit trail. Nothing here is ever set automatically
            // by BOQ status, photos, or the 35-day timer — only by an explicit engineer action.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN second_payment_ready INTEGER DEFAULT 0");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN second_payment_approved_by TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN second_payment_approved_at INTEGER DEFAULT 0");
            createSecondPaymentLogSchema(db);
        }
        if(oldVersion<11){
            // Phase 4 correction: work-delivery date and first-payment-availability date are
            // independent events, and the 35-day execution window must not start until BOTH have
            // happened (execution_start_at = MAX of the two, computed in Java — never stored, so a
            // correction to either date always recomputes the countdown correctly with zero migration
            // risk). delivery_confirmed_at (added in Phase 0) already IS the work-delivery date and is
            // untouched here; only the first-payment side is new.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN first_payment_available_at INTEGER DEFAULT 0");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN first_payment_confirmed_by TEXT DEFAULT ''");
            createFirstPaymentLogSchema(db);
        }
        if(oldVersion<12){
            // Phase 6: map calibration reference points. Deliberately its own table, fully independent
            // of the beneficiaries table — beneficiaries only ever carry real gps_lat/gps_lng (Phase 5);
            // pixel/fraction positions exist ONLY here, scoped to calibrating a specific map raster.
            createMapCalibrationSchema(db);
        }
        if(oldVersion<13){
            // Phase 7: the pristine, as-first-recorded address text must survive every later
            // classification/correction pass untouched. original_address is set exactly once (guarded
            // by addressOriginalGuard()-style WHERE clauses wherever an address is first written) and
            // never overwritten again. address_source records which mechanism last resolved the
            // CURRENT working address (address/sector/street columns) — purely informational, updated
            // freely, unlike original_address.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN original_address TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN address_source TEXT DEFAULT ''");
            // Backfill: for every pre-existing row, the current address (as it stands today) is the
            // only "original" we have ever recorded — better to capture it now than leave it blank.
            db.execSQL("UPDATE beneficiaries SET original_address=address WHERE (original_address IS NULL OR original_address='') AND address<>''");
        }
        if(oldVersion<14){
            // Dual-mode location capture: gps_source records which provider actually produced the saved
            // fix — "gps_offline" (GPS_PROVIDER only, the official default, works with no connectivity) or
            // "device_high_accuracy" (best available system provider, e.g. NETWORK_PROVIDER — faster indoors
            // but the fix may be network/Wi-Fi-assisted, so the UI always discloses this explicitly).
            // Purely informational/transparency metadata — never read by any classification or address logic.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN gps_source TEXT DEFAULT ''");
            // Backfill: every fix captured before this column existed used the old GPS_PROVIDER-only path.
            db.execSQL("UPDATE beneficiaries SET gps_source='gps_offline' WHERE gps_captured_at>0 AND (gps_source IS NULL OR gps_source='')");
        }
        if(oldVersion<15){
            // المطلوب 2 — Address Reconciliation: the address taken from the separate «العناوين» reference file
            // (kept alongside, NOT merged) so the beneficiary file can show «عنوان التقييم» vs «عنوان ملف العناوين»
            // and offer a reviewable suggestion. address/original_address are never touched by this.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN addresses_file_address TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN addresses_file_sector TEXT DEFAULT ''");
        }
        if(oldVersion<16){
            // Each map background (calibrated plan raster / official تنظيمي plan / imported satellite image) is a
            // DIFFERENT image, so its (lat,lng)→fraction calibration is image-specific. Existing points belong to the
            // original calibrated map.
            addColumn(db,"ALTER TABLE map_calibration_points ADD COLUMN map_key TEXT DEFAULT 'calibrated'");
            db.execSQL("UPDATE map_calibration_points SET map_key='calibrated' WHERE map_key IS NULL OR map_key=''");
        }
        if(oldVersion<17){
            // فريق الدراسة + التاريخ الذكي + «مرسلة للمراجعة المكتبية» timestamp; followup team fields reserved (unused yet).
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN study_engineer1 TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN study_engineer2 TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN field_assistant TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN study_date TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN sent_to_office_review_at INTEGER DEFAULT 0");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN followup_engineer1 TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN followup_engineer2 TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN followup_date TEXT DEFAULT ''");
        }
        if(oldVersion<18){
            // F3 — explicit followup/delivery state + internal delivery note + an append-only «سجل التسليم» log.
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN followup_status TEXT DEFAULT ''");
            addColumn(db,"ALTER TABLE beneficiaries ADD COLUMN delivery_note TEXT DEFAULT ''");
            createFollowupLogSchema(db);
        }
        if(oldVersion<19){
            // F4 — per-item EXECUTION status of «الأعمال المطلوبة» (kept separate from boq_delivery so F3/BOQ delivery
            // tracking is never mixed with this).
            createWorkStatusSchema(db);
        }
        if(oldVersion<20){
            // FINAL FIELD USABILITY FIX §3 — per-BOQ-item «أماكن الضرر»: where the damage is (room), its type, and
            // that location's quantity. Separate table so the quantities table/Excel are never touched.
            createDamageLocationSchema(db);
        }
    }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    /** Idempotent «ALTER TABLE … ADD COLUMN» — a device whose DB already has the column (e.g. it was created by a
     * newer build but the stored user_version is older) would otherwise crash on launch with «duplicate column
     * name …» when onUpgrade re-runs the migration. Swallowing ONLY that specific error makes every column-add
     * safe to re-run; any other SQL error still propagates. */
    private static void addColumn(SQLiteDatabase db,String sql){
        try{db.execSQL(sql);}
        catch(android.database.sqlite.SQLiteException e){
            String m=e.getMessage()==null?"":e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if(!m.contains("duplicate column"))throw e; // only ignore an already-present column; re-throw anything else
        }
    }

    private static void createPhotoSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS beneficiary_photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "phase TEXT NOT NULL,uri TEXT NOT NULL UNIQUE,file_name TEXT,captured_at INTEGER NOT NULL,note TEXT DEFAULT '',"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photo_beneficiary_phase ON beneficiary_photos(beneficiary_id,phase,captured_at)");
    }

    private static void createQuantitySchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS beneficiary_quantities (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "item_no INTEGER NOT NULL,quantity REAL NOT NULL,unit_price REAL NOT NULL,"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quantity_beneficiary ON beneficiary_quantities(beneficiary_id,item_no)");
    }

    private static void createDeliveryAndCorrectionSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS boq_delivery (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "item_no INTEGER NOT NULL,status TEXT DEFAULT 'not_delivered',note TEXT DEFAULT '',engineer_name TEXT DEFAULT '',"+
                "updated_at INTEGER,FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_boq_delivery_beneficiary ON boq_delivery(beneficiary_id,item_no)");
        db.execSQL("CREATE TABLE IF NOT EXISTS address_corrections (_id INTEGER PRIMARY KEY AUTOINCREMENT,normalized_text TEXT UNIQUE,"+
                "confirmed_sector TEXT,confirmed_street TEXT,confirmed_by TEXT,confirmed_at INTEGER)");
    }

    /** beneficiary_id here is the REVISION that can see the photo — never the photo's owner (that stays whatever it was in beneficiary_photos.beneficiary_id, i.e. the revision that originally captured it). Purely a read-visibility link; no file or row is ever duplicated through this table. */
    private static void createRevisionPhotoLinkSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS revision_photo_links (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "photo_id INTEGER NOT NULL,linked_at INTEGER,"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,"+
                "FOREIGN KEY(photo_id) REFERENCES beneficiary_photos(_id) ON DELETE CASCADE,"+
                "UNIQUE(beneficiary_id,photo_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_revision_photo_links_beneficiary ON revision_photo_links(beneficiary_id)");
    }

    /** Append-only audit trail for second-payment approve/revoke actions. A revoke never deletes or
     * overwrites an earlier approve row here — both stay, in order, forever. */
    private static void createSecondPaymentLogSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS second_payment_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "action TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,reason TEXT DEFAULT '',"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_second_payment_log_beneficiary ON second_payment_log(beneficiary_id,at)");
    }

    /** F4 — per-item execution status of «الأعمال المطلوبة» (one row per beneficiary+BOQ item). Separate from
     * boq_delivery so F3/BOQ-delivery tracking is never conflated with execution tracking. */
    private static void createWorkStatusSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS work_status (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "item_no INTEGER NOT NULL,status TEXT NOT NULL,note TEXT DEFAULT '',updated_at INTEGER NOT NULL,"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))");
    }
    /** §3 «أماكن الضرر»: many rows per (beneficiary,item) — each a place + damage type + that place's quantity. */
    private static void createDamageLocationSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS damage_locations (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "item_no INTEGER NOT NULL,place TEXT NOT NULL,damage_type TEXT DEFAULT '',qty REAL DEFAULT 0,note TEXT DEFAULT '',created_at INTEGER NOT NULL,"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_damage_loc ON damage_locations(beneficiary_id,item_no)");
    }

    /** F3 — append-only «سجل التسليم» / followup-status history: every explicit status change (incl. delivery)
     * adds a row here; nothing is ever overwritten, so the file keeps the full delivery trail. */
    private static void createFollowupLogSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS followup_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "status TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,note TEXT DEFAULT '',"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_followup_log_beneficiary ON followup_log(beneficiary_id,at)");
    }

    /** Append-only audit trail for first-payment-available confirm/correct/cancel actions — a correction or
     * cancellation never silently overwrites/erases the prior date without a trace here. */
    private static void createFirstPaymentLogSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS first_payment_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"+
                "action TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,reason TEXT DEFAULT '',"+
                "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_first_payment_log_beneficiary ON first_payment_log(beneficiary_id,at)");
    }

    /** Map-calibration reference points ONLY — completely separate table from beneficiaries, on purpose.
     * frac_x/frac_y are the point's position as a 0..1 fraction of the calibration raster's width/height
     * (resolution-independent — if the raster asset is ever swapped for a higher-resolution one with the
     * same framing, these fractions stay valid). lat/lng are the real GPS coordinates surveyed for that
     * same physical spot. */
    private static void createMapCalibrationSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS map_calibration_points (_id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "label TEXT DEFAULT '',lat REAL NOT NULL,lng REAL NOT NULL,frac_x REAL NOT NULL,frac_y REAL NOT NULL,"+
                "created_at INTEGER NOT NULL,map_key TEXT DEFAULT 'calibrated')");
    }
    /** Map backgrounds are separate images, so a (lat,lng)→fraction calibration is valid for ONE image only. */
    public static final String MAP_CALIBRATED="calibrated",MAP_PLAN="plan",MAP_SATELLITE="satellite";

    public static final class CalibrationPointRow {
        public long id; public String label=""; public double lat,lng,fracX,fracY; public long createdAt;
    }

    public long addCalibrationPoint(String label,double lat,double lng,double fracX,double fracY){
        return addCalibrationPoint(MAP_CALIBRATED,label,lat,lng,fracX,fracY);
    }
    /** Calibration point for a SPECIFIC map background (see MAP_* keys). */
    public long addCalibrationPoint(String mapKey,String label,double lat,double lng,double fracX,double fracY){
        ContentValues v=new ContentValues();v.put("label",label==null?"":label);v.put("lat",lat);v.put("lng",lng);
        v.put("frac_x",fracX);v.put("frac_y",fracY);v.put("created_at",System.currentTimeMillis());
        v.put("map_key",mapKey==null||mapKey.isEmpty()?MAP_CALIBRATED:mapKey);
        return getWritableDatabase().insertOrThrow("map_calibration_points",null,v);
    }

    public List<CalibrationPointRow> listCalibrationPoints(){ return listCalibrationPoints(MAP_CALIBRATED); }
    public List<CalibrationPointRow> listCalibrationPoints(String mapKey){
        List<CalibrationPointRow> rows=new ArrayList<>();
        String key=mapKey==null||mapKey.isEmpty()?MAP_CALIBRATED:mapKey;
        try(Cursor c=getReadableDatabase().query("map_calibration_points",null,"map_key=?",new String[]{key},null,null,"created_at,_id")){
            while(c.moveToNext()){
                CalibrationPointRow row=new CalibrationPointRow();
                row.id=longValue(c,"_id");row.label=str(c,"label");row.lat=doubleValue(c,"lat");row.lng=doubleValue(c,"lng");
                row.fracX=doubleValue(c,"frac_x");row.fracY=doubleValue(c,"frac_y");row.createdAt=longValue(c,"created_at");
                rows.add(row);
            }
        }
        return rows;
    }

    public void deleteCalibrationPoint(long id){
        getWritableDatabase().delete("map_calibration_points","_id=?",new String[]{String.valueOf(id)});
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
            if (replace) {
                int existing = scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=?", new String[]{String.valueOf(projectId)});
                diag("IMPORT_REPLACE_DELETE", "project_id=" + projectId + " rows_removed=" + existing + " rows_incoming=" + rows.size());
                db.delete("beneficiaries", "project_id=?", new String[]{String.valueOf(projectId)});
            } else {
                diag("IMPORT_APPEND", "project_id=" + projectId + " rows_incoming=" + rows.size());
            }
            for (Beneficiary b : rows) {
                b.projectId = projectId;
                ensureRevisionIdentity(b);
                ensureOriginalAddress(b);
                b.id=db.insertOrThrow("beneficiaries", null, values(b));
                saveQuantities(db,b.id,b.quantities,false);
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
        ensureRevisionIdentity(beneficiary);
        ensureOriginalAddress(beneficiary);
        long id=getWritableDatabase().insertOrThrow("beneficiaries",null,values(beneficiary));beneficiary.id=id;return id;
    }

    public Beneficiary beneficiary(long id){
        try(Cursor c=getReadableDatabase().query("beneficiaries",null,"_id=?",new String[]{String.valueOf(id)},null,null,null)){
            return c.moveToFirst()?read(c):null;
        }
    }

    /** المطلوب 2: find a beneficiary in the project by registration DIGITS (hyphens/spaces stripped). */
    public Beneficiary beneficiaryByRegistration(long projectId,String regDigits){
        if(regDigits==null||regDigits.isEmpty())return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM beneficiaries WHERE project_id=? AND REPLACE(REPLACE(registration,'-',''),' ','')=? LIMIT 1",new String[]{String.valueOf(projectId),regDigits})){
            return c.moveToFirst()?read(c):null;
        }
    }
    /** المطلوب 2: fallback match by EXACT trimmed name (loose name matching is avoided — never mis-link). */
    public Beneficiary beneficiaryByName(long projectId,String name){
        if(name==null||name.trim().isEmpty())return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM beneficiaries WHERE project_id=? AND TRIM(name)=? LIMIT 1",new String[]{String.valueOf(projectId),name.trim()})){
            return c.moveToFirst()?read(c):null;
        }
    }
    /** المطلوب 2: store the «العناوين» reference address/sector alongside — NEVER touches address/original_address. */
    public void setAddressReference(long beneficiaryId,String addr,String sector){
        ContentValues v=new ContentValues();v.put("addresses_file_address",addr==null?"":addr);v.put("addresses_file_sector",sector==null?"":sector);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(beneficiaryId)});
    }

    private ContentValues values(Beneficiary b) {
        ContentValues v=new ContentValues();
        v.put("project_id",b.projectId);v.put("sequence_no",b.sequence);v.put("name",b.name);v.put("registration",b.registration);
        v.put("relation",b.relation);v.put("related_to",b.relatedTo);v.put("phone1",b.phone1);v.put("phone2",b.phone2);
        v.put("address",b.address);v.put("sector",b.sector);v.put("street",b.street);v.put("amount",b.amount);
        v.put("match_status",b.matchStatus);v.put("confidence",b.confidence);v.put("sector_order",b.sectorOrder);
        v.put("route_order",b.routeOrder);v.put("street_order",b.streetOrder);v.put("alley_order",b.alleyOrder);
        v.put("visit_status",b.visitStatus);v.put("progress_percent",b.progressPercent);v.put("notes",b.notes);v.put("workflow_stage",b.workflowStage);v.put("study_status",b.studyStatus);
        v.put("damage_notes",b.damageNotes);v.put("quantities_notes",b.quantitiesNotes);v.put("drawing_uri",b.drawingUri);
        v.put("followup_batch",b.followupBatch);v.put("approved_at",b.approvedAt);v.put("work_delivered",b.workDelivered?1:0);v.put("engineer_name",b.engineerName);
        v.put("study_uuid",b.studyUuid);v.put("revision_uuid",b.revisionUuid);v.put("revision_no",b.revisionNo);v.put("previous_revision_uuid",b.previousRevisionUuid);
        v.put("delivery_confirmed_at",b.deliveryConfirmedAt);v.put("second_payment_ready",b.secondPaymentReady?1:0);
        v.put("second_payment_approved_by",b.secondPaymentApprovedBy);v.put("second_payment_approved_at",b.secondPaymentApprovedAt);
        v.put("first_payment_available_at",b.firstPaymentAvailableAt);v.put("first_payment_confirmed_by",b.firstPaymentConfirmedBy);
        v.put("gps_lat",b.gpsCapturedAt>0?b.gpsLat:null);v.put("gps_lng",b.gpsCapturedAt>0?b.gpsLng:null);
        v.put("gps_accuracy_m",b.gpsCapturedAt>0?b.gpsAccuracyM:null);v.put("gps_captured_at",b.gpsCapturedAt);v.put("gps_source",b.gpsSource);
        v.put("original_address",b.originalAddress);v.put("address_source",b.addressSource);
        v.put("addresses_file_address",b.addressesFileAddress);v.put("addresses_file_sector",b.addressesFileSector);
        v.put("study_engineer1",b.studyEngineer1);v.put("study_engineer2",b.studyEngineer2);v.put("field_assistant",b.fieldAssistant);v.put("study_date",b.studyDate);
        v.put("sent_to_office_review_at",b.sentToOfficeReviewAt);
        v.put("followup_engineer1",b.followupEngineer1);v.put("followup_engineer2",b.followupEngineer2);v.put("followup_date",b.followupDate);
        v.put("followup_status",b.followupStatus);v.put("delivery_note",b.deliveryNote);
        v.put("updated_at",System.currentTimeMillis()); return v;
    }

    /** Every new study (manual entry or import) gets its own stable study_uuid/revision_uuid the moment it's created, so later phases (sync, corrected revisions) never have to deal with a study lacking an identity. */
    private static void ensureRevisionIdentity(Beneficiary b){
        if(b.studyUuid==null||b.studyUuid.isEmpty())b.studyUuid=UUID.randomUUID().toString();
        if(b.revisionUuid==null||b.revisionUuid.isEmpty())b.revisionUuid=UUID.randomUUID().toString();
        if(b.revisionNo<1)b.revisionNo=1;
    }

    /** Captures the very first non-empty address text this beneficiary ever had as original_address — only
     * if it isn't already set. Called at every INSERT path; updateStudy() applies the same one-shot guard for
     * addresses entered/edited later. Once set, nothing in this class ever overwrites it again. */
    private static void ensureOriginalAddress(Beneficiary b){
        if((b.originalAddress==null||b.originalAddress.isEmpty())&&b.address!=null&&!b.address.isEmpty())b.originalAddress=b.address;
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
        return listStage(projectId,search,stage,status,null);
    }

    public List<Beneficiary> listStage(long projectId,String search,String stage,String status,String batch) {
        return listStage(projectId,search,stage,status,batch,null);
    }

    public List<Beneficiary> listStage(long projectId,String search,String stage,String status,String batch,String engineer) {
        List<Beneficiary> rows=new ArrayList<>();StringBuilder where=new StringBuilder("project_id=? AND workflow_stage=?");
        List<String> args=new ArrayList<>();args.add(String.valueOf(projectId));args.add(stage);
        if(search!=null&&!search.trim().isEmpty()){where.append(" AND (name LIKE ? OR address LIKE ? OR phone1 LIKE ? OR phone2 LIKE ? OR registration LIKE ?)");String q="%"+search.trim()+"%";for(int i=0;i<5;i++)args.add(q);}
        if(status!=null&&!status.equals("all")){where.append(" AND ").append("study".equals(stage)?"study_status":"visit_status").append("=?");args.add(status);}
        if(batch!=null&&!batch.isEmpty()&&!batch.equals("all")){where.append(" AND followup_batch=?");args.add(batch);}
        if(engineer!=null&&!engineer.isEmpty()&&!engineer.equals("all")){where.append(" AND engineer_name=?");args.add(engineer);}
        try(Cursor c=getReadableDatabase().query("beneficiaries",null,where.toString(),args.toArray(new String[0]),null,null,
                "sector_order,route_order,street_order,alley_order,address,sequence_no,_id")){
            while(c.moveToNext())rows.add(read(c));
        }
        return rows;
    }

    /** Distinct engineer/researcher names recorded on this project's records — lets the team filter a merged list down to one contributor at a time. */
    public List<String> engineerNames(long projectId){
        List<String> values=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT DISTINCT engineer_name FROM beneficiaries WHERE project_id=? AND engineer_name<>'' ORDER BY engineer_name",
                new String[]{String.valueOf(projectId)})){
            while(c.moveToNext())values.add(c.getString(0));
        }
        return values;
    }

    /** Distinct import/follow-up batch labels for this project, most recent first — lets the field team pick one imported list at a time instead of everything blending together. */
    public List<String> followupBatches(long projectId){
        List<String> values=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT followup_batch,MAX(approved_at) latest FROM beneficiaries WHERE project_id=? AND workflow_stage='followup' AND followup_batch<>'' GROUP BY followup_batch ORDER BY latest DESC",
                new String[]{String.valueOf(projectId)})){
            while(c.moveToNext())values.add(c.getString(0));
        }
        return values;
    }

    public int stageCount(long projectId,String stage){return scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage=?",new String[]{String.valueOf(projectId),stage});}
    public int studyReadyCount(long projectId){return scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage='study' AND study_status='approved'",new String[]{String.valueOf(projectId)});}
    public int followupCompletedCount(long projectId){return scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage='followup' AND visit_status='completed'",new String[]{String.valueOf(projectId)});}
    private int scalar(String sql,String[]args){try(Cursor c=getReadableDatabase().rawQuery(sql,args)){return c.moveToFirst()?c.getInt(0):0;}}

    public void updateStudy(long id,String address,String damageNotes,String drawingUri){
        String value=address==null?"":address.trim();
        ContentValues v=new ContentValues();v.put("address",value);v.put("damage_notes",damageNotes==null?"":damageNotes.trim());
        if(drawingUri!=null)v.put("drawing_uri",drawingUri);v.put("updated_at",System.currentTimeMillis());
        SQLiteDatabase database=getWritableDatabase();
        database.update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
        // original_address is captured the first time ANY non-empty address text is recorded for this
        // beneficiary — at creation, or here if it was still empty back then — and is never touched again.
        if(!value.isEmpty()){
            ContentValues original=new ContentValues();original.put("original_address",value);
            database.update("beneficiaries",original,"_id=? AND (original_address IS NULL OR original_address='')",new String[]{String.valueOf(id)});
        }
    }

    public List<QuantityEntry> quantities(long beneficiaryId){
        List<QuantityEntry>rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("beneficiary_quantities",new String[]{"item_no","quantity","unit_price"},"beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)},null,null,"item_no")){
            while(c.moveToNext())rows.add(new QuantityEntry(c.getInt(0),c.getDouble(1),c.getDouble(2)));
        }
        return rows;
    }

    public int quantityCount(long beneficiaryId){
        return scalar("SELECT COUNT(*) FROM beneficiary_quantities WHERE beneficiary_id=? AND quantity>0",new String[]{String.valueOf(beneficiaryId)});
    }

    public double quantityTotal(long beneficiaryId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(quantity*unit_price),0) FROM beneficiary_quantities WHERE beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)})){
            return c.moveToFirst()?c.getDouble(0):0;
        }
    }

    public void saveQuantities(long beneficiaryId,List<QuantityEntry>entries){
        SQLiteDatabase database=getWritableDatabase();database.beginTransaction();
        try{saveQuantities(database,beneficiaryId,entries,true);database.setTransactionSuccessful();}finally{database.endTransaction();}
    }

    private void saveQuantities(SQLiteDatabase database,long beneficiaryId,List<QuantityEntry>entries,boolean updateBeneficiary){
        database.delete("beneficiary_quantities","beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)});double total=0;int count=0;
        if(entries!=null)for(QuantityEntry entry:entries){if(entry.itemNo<1||entry.itemNo>37||entry.quantity<=0||entry.unitPrice<0)continue;ContentValues q=new ContentValues();q.put("beneficiary_id",beneficiaryId);q.put("item_no",entry.itemNo);q.put("quantity",entry.quantity);q.put("unit_price",entry.unitPrice);database.insertOrThrow("beneficiary_quantities",null,q);total+=entry.subtotal();count++;}
        if(updateBeneficiary||count>0){ContentValues b=new ContentValues();b.put("amount",QuantityCatalog.money(total));b.put("quantities_notes",count==0?"":count+" بند من جدول الكميات المرجعي");b.put("updated_at",System.currentTimeMillis());database.update("beneficiaries",b,"_id=?",new String[]{String.valueOf(beneficiaryId)});}
    }

    /** Looks up a previously CONFIRMED correction for this exact normalized address text — never fuzzy, exact
     * match only. Returns null if this text has never been explicitly confirmed by a human before. */
    public AddressResolution.ConfirmedCorrection addressCorrectionFor(String normalizedText){
        if(normalizedText==null||normalizedText.isEmpty())return null;
        try(Cursor c=getReadableDatabase().query("address_corrections",new String[]{"confirmed_sector","confirmed_street","confirmed_by"},
                "normalized_text=?",new String[]{normalizedText},null,null,null)){
            if(!c.moveToFirst())return null;
            return new AddressResolution.ConfirmedCorrection(c.getString(0),c.getString(1)==null?"":c.getString(1),c.getString(2)==null?"":c.getString(2));
        }
    }

    /** The ONLY way address_corrections ever gets written — an explicit human confirmation from the
     * "مراجعة العنوان" flow, never an automatic guess. Re-confirming the same text updates the existing row. */
    public void confirmAddressCorrection(String normalizedText,String sector,String street,String confirmedBy){
        ContentValues v=new ContentValues();v.put("normalized_text",normalizedText);v.put("confirmed_sector",sector);
        v.put("confirmed_street",street==null?"":street);v.put("confirmed_by",confirmedBy==null?"":confirmedBy);v.put("confirmed_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("address_corrections",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Applies a full AddressResolution.Result to a beneficiary — sector/street/confidence/orders/match_status/
     * address_source only. Never touches `address` (the current working address text) or `original_address`
     * (the immutable first-recorded text) — those are managed exclusively by updateStudy()/ensureOriginalAddress(). */
    public void applyAddressResolution(long id,AddressResolution.Result result){
        Beneficiary before=beneficiary(id);
        ContentValues v=new ContentValues();v.put("sector",result.sector);v.put("street",result.street);
        v.put("match_status",result.needsReview?("بحاجة لمراجعة"+(result.reviewReason.isEmpty()?"":" — "+result.reviewReason)):result.addressSource);
        v.put("confidence",result.confidence);v.put("address_source",result.addressSource);
        v.put("sector_order",result.sectorIndex);v.put("route_order",result.routeIndex);v.put("street_order",result.streetIndex);v.put("alley_order",result.alleyIndex);
        v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
        if(before!=null&&(!before.sector.equals(result.sector)||!before.street.equals(result.street)))
            diag("ADDRESS_RECLASSIFIED",id,"sector['"+before.sector+"'->'"+result.sector+"'] street['"+before.street+"'->'"+result.street+"'] status="+result.status);
    }

    public void updateClassification(long id,AddressClassifier.Match match,String source){
        ContentValues v=new ContentValues();v.put("sector",match.sector);v.put("street",match.street);v.put("match_status",source+" | "+match.review);v.put("confidence",match.confidence);
        v.put("sector_order",match.sectorIndex);v.put("route_order",match.routeIndex);v.put("street_order",match.streetIndex);v.put("alley_order",match.alleyIndex);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    public void setDrawingUri(long id,String uri){ContentValues v=new ContentValues();v.put("drawing_uri",uri==null?"":uri);v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});}

    /** Stores the home's real-world GPS fix — latitude/longitude/accuracy(m)/timestamp only, never pixel or
     * map-image coordinates. Overwrite is always an explicit caller decision (the UI confirms with the user
     * first when a location already exists) — this method itself has no guard, by design, so "تحديث الموقع"
     * can freely replace an old fix once the user has confirmed. */
    public void setGpsLocation(long id,double lat,double lng,double accuracyM,long capturedAt,String source){
        ContentValues v=new ContentValues();v.put("gps_lat",lat);v.put("gps_lng",lng);v.put("gps_accuracy_m",accuracyM);
        v.put("gps_captured_at",capturedAt);v.put("gps_source",source==null?"":source);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    /** مسودة → جاهزة للمراجعة. */
    public void markStudyReady(long id){ContentValues v=new ContentValues();v.put("study_status","ready");v.put("updated_at",System.currentTimeMillis());int n=getWritableDatabase().update("beneficiaries",v,"_id=? AND study_status='draft'",new String[]{String.valueOf(id)});diag("STUDY_STATUS_CHANGE",id,"draft->ready applied="+(n>0));}
    /** Administrative «اعتماد الدراسة وإرسالها للمراجعة المكتبية»: draft → ready (=«مرسلة للمراجعة المكتبية»)
     * and stamps the send timestamp. Only advances from draft; returns whether the row moved. */
    public boolean markStudySentToOfficeReview(long id,long at){ContentValues v=new ContentValues();v.put("study_status","ready");v.put("sent_to_office_review_at",at);v.put("updated_at",System.currentTimeMillis());int n=getWritableDatabase().update("beneficiaries",v,"_id=? AND study_status='draft'",new String[]{String.valueOf(id)});diag("STUDY_STATUS_CHANGE",id,"draft->ready(sent to office review) applied="+(n>0));return n>0;}

    /** جاهزة للمراجعة → مراجعة مكتبية. The WHERE guard makes a draft→office_review jump impossible at the DB level, not just in the UI. */
    public boolean submitForOfficeReview(long id){
        ContentValues v=new ContentValues();v.put("study_status","office_review");v.put("updated_at",System.currentTimeMillis());
        boolean ok=getWritableDatabase().update("beneficiaries",v,"_id=? AND workflow_stage='study' AND study_status='ready'",new String[]{String.valueOf(id)})>0;
        diag("STUDY_STATUS_CHANGE",id,"ready->office_review applied="+ok);
        return ok;
    }

    /** مراجعة مكتبية → معتمدة وجاهزة للإرسال. Only a study currently in office_review can be approved — no direct draft/ready→approved path exists anywhere in this class. */
    public boolean approveStudy(long id){
        ContentValues v=new ContentValues();v.put("study_status","approved");v.put("approved_at",System.currentTimeMillis());v.put("updated_at",System.currentTimeMillis());
        boolean ok=getWritableDatabase().update("beneficiaries",v,"_id=? AND workflow_stage='study' AND study_status='office_review'",new String[]{String.valueOf(id)})>0;
        diag("STUDY_STATUS_CHANGE",id,"office_review->approved applied="+ok);
        return ok;
    }

    public int promoteReadyBatch(long projectId,String batchName){
        SQLiteDatabase database=getWritableDatabase();ContentValues v=new ContentValues();v.put("workflow_stage","followup");v.put("followup_batch",batchName);v.put("visit_status","pending");v.put("updated_at",System.currentTimeMillis());
        int moved=database.update("beneficiaries",v,"_id IN (SELECT _id FROM beneficiaries WHERE project_id=? AND workflow_stage='study' AND study_status='approved' ORDER BY sequence_no,_id LIMIT 50)",new String[]{String.valueOf(projectId)});
        diag("PROMOTE_BATCH","project_id="+projectId+" batch="+batchName+" moved="+moved+" (study->followup)");
        return moved;
    }

    /** ينشئ "نسخة مصححة" من دراسة معتمدة: يبقي study_uuid كما هو، يولّد revision_uuid جديداً، يزيد revision_no، ويربط
     * previous_revision_uuid بالنسخة السابقة. الصف القديم يتحول إلى 'superseded' ولا يُحذف أبداً، ويبقى للقراءة فقط —
     * هذا هو السجل التاريخي القابل للمراجعة فعلياً، وليس مجرد نص:
     *  - الكميات وBOQ: تُنسَخ (INSERT) للصف الجديد بدل نقلها؛ صفوف الصف القديم تبقى كما كانت تماماً وقت الاعتماد،
     *    فتعديل الكميات في النسخة الجديدة لا يمس Snapshot النسخة السابقة إطلاقاً. النسخ آمن هنا لأن قيد UNIQUE على
     *    beneficiary_quantities/boq_delivery هو (beneficiary_id,item_no) — معرّف مستفيد جديد يفتح نطاقاً مستقلاً بلا تعارض.
     *  - الصور: لا تُنسخ ولا تُنقل (uri عليها UNIQUE، لا يمكن تكرار الصف فعلياً ولا نقله دون إفراغ النسخة القديمة من
     *    صورها). بدلاً من ذلك تُربط النسخة الجديدة بنفس صفوف الصور القديمة عبر revision_photo_links (رؤية مشتركة لنفس
     *    الملف الفيزيائي دون تكراره)، بينما تبقى كل صورة "مملوكة" فعلياً (beneficiary_photos.beneficiary_id) لنفس
     *    النسخة التي التقطتها أصلاً — فالنسخة القديمة تعرض صورها الأصلية كما كانت دون أي تغيير.
     *  - المخطط/الوثيقة (drawing_uri): مجرد نص URI في عمود الصف نفسه، يُنسَخ كقيمة إلى الصف الجديد (نفس الملف
     *    الفيزيائي، بلا تكرار)، ويبقى في عمود الصف القديم كما هو دون أي مسح.
     */
    public long createCorrectedRevision(long approvedId,String engineerName){
        SQLiteDatabase database=getWritableDatabase();
        database.beginTransaction();
        try{
            Beneficiary old=beneficiary(approvedId);
            if(old==null||!"study".equals(old.workflowStage)||!"approved".equals(old.studyStatus)){
                throw new IllegalStateException("createCorrectedRevision requires an approved study");
            }
            Beneficiary next=new Beneficiary();
            next.projectId=old.projectId;next.sequence=old.sequence;next.name=old.name;next.registration=old.registration;next.fileNo=old.fileNo;
            next.relation=old.relation;next.relatedTo=old.relatedTo;next.phone1=old.phone1;next.phone2=old.phone2;
            next.address=old.address;next.originalAddress=old.originalAddress;next.addressSource=old.addressSource;next.sector=old.sector;next.street=old.street;next.amount=old.amount;
            next.matchStatus=old.matchStatus;next.confidence=old.confidence;next.sectorOrder=old.sectorOrder;
            next.routeOrder=old.routeOrder;next.streetOrder=old.streetOrder;next.alleyOrder=old.alleyOrder;
            next.visitStatus=old.visitStatus;next.progressPercent=old.progressPercent;next.notes=old.notes;
            next.workflowStage="study";next.studyStatus="draft";
            next.damageNotes=old.damageNotes;next.quantitiesNotes=old.quantitiesNotes;next.drawingUri=old.drawingUri;
            next.followupBatch=old.followupBatch;next.workDelivered=false;
            next.engineerName=engineerName==null||engineerName.isEmpty()?old.engineerName:engineerName;
            next.studyUuid=old.studyUuid;
            next.revisionUuid=UUID.randomUUID().toString();
            next.revisionNo=old.revisionNo+1;
            next.previousRevisionUuid=old.revisionUuid;
            long newId=database.insertOrThrow("beneficiaries",null,values(next));

            // Quantities/BOQ: independent copies, not a move — the old row's rows are never touched again.
            database.execSQL(
                    "INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) "+
                    "SELECT ?,item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=?",
                    new Object[]{newId,approvedId});
            database.execSQL(
                    "INSERT INTO boq_delivery(beneficiary_id,item_no,status,note,engineer_name,updated_at) "+
                    "SELECT ?,item_no,status,note,engineer_name,updated_at FROM boq_delivery WHERE beneficiary_id=?",
                    new Object[]{newId,approvedId});

            // Photos: link, never move or duplicate. Inherit every photo the old
            // revision could already see (its own + whatever it had inherited),
            // so a chain of corrections (rev1 -> rev2 -> rev3) keeps working.
            long now=System.currentTimeMillis();
            database.execSQL(
                    "INSERT OR IGNORE INTO revision_photo_links(beneficiary_id,photo_id,linked_at) "+
                    "SELECT ?,_id,? FROM beneficiary_photos WHERE beneficiary_id=?",
                    new Object[]{newId,now,approvedId});
            database.execSQL(
                    "INSERT OR IGNORE INTO revision_photo_links(beneficiary_id,photo_id,linked_at) "+
                    "SELECT ?,photo_id,? FROM revision_photo_links WHERE beneficiary_id=?",
                    new Object[]{newId,now,approvedId});

            ContentValues supersede=new ContentValues();supersede.put("study_status","superseded");supersede.put("updated_at",System.currentTimeMillis());
            database.update("beneficiaries",supersede,"_id=?",new String[]{String.valueOf(approvedId)});
            diag("CORRECTED_REVISION",approvedId,"approved->superseded new_revision_id="+newId+" study_uuid="+next.studyUuid);

            database.setTransactionSuccessful();
            return newId;
        } finally { database.endTransaction(); }
    }

    public void updateVisit(long id,String status,int progressPercent,String notes) {
        int progress="completed".equals(status)?100:Math.max(0,Math.min(100,progressPercent));ContentValues v=new ContentValues();v.put("visit_status",status);v.put("progress_percent",progress);v.put("notes",notes==null?"":notes);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    /** Marks the handover to the beneficiary as done — distinct from visit/progress, set right before or at delivery. */
    public void setWorkDelivered(long id,boolean delivered){
        ContentValues v=new ContentValues();v.put("work_delivered",delivered?1:0);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
    }

    // ===== F3: explicit followup/delivery status + «سجل التسليم» log ======================================
    public static final String FS_NOT_STARTED="not_started",FS_IN_PROGRESS="in_progress",FS_READY="ready",
            FS_DELIVERED="delivered",FS_REVIEW="review",FS_DEFERRED="deferred";
    public static final class FollowupLogEntry{public final long at;public final String status,engineer,note;
        FollowupLogEntry(long at,String status,String engineer,String note){this.at=at;this.status=status;this.engineer=engineer;this.note=note;}}

    /** F3 — set the explicit followup status, appending a «سجل التسليم» row. When the new status is «delivered» the
     * work-delivered flag + delivery date are kept in sync (date set once, never reset). Optional internal note. */
    public void setFollowupStatus(long id,String status,String engineerName,String note){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            long now=System.currentTimeMillis();
            ContentValues v=new ContentValues();v.put("followup_status",status);v.put("updated_at",now);
            if(note!=null)v.put("delivery_note",note);
            if(FS_DELIVERED.equals(status)){
                v.put("work_delivered",1);
                // delivery_confirmed_at is set exactly once (used by the execution timer) — never reset on re-mark.
                Beneficiary cur=beneficiary(id);
                if(cur==null||cur.deliveryConfirmedAt<=0)v.put("delivery_confirmed_at",now);
            }
            db.update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
            ContentValues log=new ContentValues();log.put("beneficiary_id",id);log.put("status",status);log.put("engineer_name",engineerName==null?"":engineerName);log.put("at",now);log.put("note",note==null?"":note);
            db.insert("followup_log",null,log);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** The «سجل التسليم» — every recorded followup-status change, newest first. */
    public java.util.List<FollowupLogEntry> followupLog(long id){
        java.util.List<FollowupLogEntry> out=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT at,status,engineer_name,note FROM followup_log WHERE beneficiary_id=? ORDER BY at DESC",new String[]{String.valueOf(id)})){
            while(c.moveToNext())out.add(new FollowupLogEntry(c.getLong(0),c.getString(1),c.getString(2),c.getString(3)));
        }catch(Exception ignored){}
        return out;
    }

    // ===== F4: per-item execution status of «الأعمال المطلوبة» ===========================================
    public static final String WS_NOT_EXECUTED="not_executed",WS_IN_PROGRESS="in_progress",WS_EXECUTED="executed",WS_NEEDS_REVIEW="needs_review";
    public static final class WorkStatusEntry{public final String status,note;public final long updatedAt;
        WorkStatusEntry(String status,String note,long updatedAt){this.status=status;this.note=note;this.updatedAt=updatedAt;}}
    /** Map of item_no → its saved execution status/note. Items with no saved row default (in the UI) to «لم ينفذ». */
    public java.util.Map<Integer,WorkStatusEntry> workStatuses(long beneficiaryId){
        java.util.Map<Integer,WorkStatusEntry> out=new java.util.HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT item_no,status,note,updated_at FROM work_status WHERE beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)})){
            while(c.moveToNext())out.put(c.getInt(0),new WorkStatusEntry(c.getString(1),c.isNull(2)?"":c.getString(2),c.getLong(3)));
        }catch(Exception ignored){}
        return out;
    }
    public void setWorkStatus(long beneficiaryId,int itemNo,String status,String note){
        ContentValues v=new ContentValues();
        v.put("beneficiary_id",beneficiaryId);v.put("item_no",itemNo);v.put("status",status);v.put("note",note==null?"":note);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("work_status",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ===== §3 «أماكن الضرر» — per-BOQ-item damage locations (place + type + that place's quantity) =====
    public static final class DamageLocation{
        public final long id;public final int itemNo;public final String place,damageType,note;public final double qty;public final long createdAt;
        DamageLocation(long id,int itemNo,String place,String damageType,double qty,String note,long createdAt){this.id=id;this.itemNo=itemNo;this.place=place;this.damageType=damageType;this.qty=qty;this.note=note;this.createdAt=createdAt;}
    }
    private static DamageLocation readDamageLocation(Cursor c){return new DamageLocation(c.getLong(0),c.getInt(1),c.getString(2),c.isNull(3)?"":c.getString(3),c.getDouble(4),c.isNull(5)?"":c.getString(5),c.getLong(6));}
    /** All damage-location rows for one BOQ item, oldest first. */
    public java.util.List<DamageLocation> damageLocations(long beneficiaryId,int itemNo){
        java.util.List<DamageLocation> out=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT _id,item_no,place,damage_type,qty,note,created_at FROM damage_locations WHERE beneficiary_id=? AND item_no=? ORDER BY _id",new String[]{String.valueOf(beneficiaryId),String.valueOf(itemNo)})){
            while(c.moveToNext())out.add(readDamageLocation(c));
        }catch(Exception ignored){}
        return out;
    }
    /** All damage-location rows for the beneficiary (every item), ordered by item then insert order — used by
     * the followup «الأعمال المطلوبة» view, backup.json and the exported damage_locations.json. */
    public java.util.List<DamageLocation> damageLocations(long beneficiaryId){
        java.util.List<DamageLocation> out=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT _id,item_no,place,damage_type,qty,note,created_at FROM damage_locations WHERE beneficiary_id=? ORDER BY item_no,_id",new String[]{String.valueOf(beneficiaryId)})){
            while(c.moveToNext())out.add(readDamageLocation(c));
        }catch(Exception ignored){}
        return out;
    }
    public long addDamageLocation(long beneficiaryId,int itemNo,String place,String damageType,double qty,String note){
        ContentValues v=new ContentValues();
        v.put("beneficiary_id",beneficiaryId);v.put("item_no",itemNo);v.put("place",place==null?"":place);v.put("damage_type",damageType==null?"":damageType);v.put("qty",qty);v.put("note",note==null?"":note);v.put("created_at",System.currentTimeMillis());
        return getWritableDatabase().insert("damage_locations",null,v);
    }
    public void deleteDamageLocation(long id){getWritableDatabase().delete("damage_locations","_id=?",new String[]{String.valueOf(id)});}
    /** Number of BOQ items that have at least one damage-location row (for a quick badge). */
    public int damageLocationItemCount(long beneficiaryId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT item_no) FROM damage_locations WHERE beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)})){
            if(c.moveToFirst())return c.getInt(0);
        }catch(Exception ignored){}
        return 0;
    }

    public static final String BOQ_NOT_DELIVERED="not_delivered",BOQ_DELIVERED="delivered",BOQ_IN_PROGRESS="in_progress",
            BOQ_READY_FOR_PICKUP="ready_for_pickup",BOQ_RECEIVED="received";

    /** The active BOQ for this beneficiary is exactly the set of items with quantity>0 in beneficiary_quantities —
     * the same table the approved study wrote to — LEFT JOINed with whatever delivery status has been recorded so
     * far (a fresh 'not_delivered' row is implied, never inserted, until the engineer actually touches an item). */
    public List<BoqDeliveryEntry> boqDeliveryStatus(long beneficiaryId){
        List<BoqDeliveryEntry> rows=new ArrayList<>();
        String sql="SELECT q.item_no,q.quantity,q.unit_price,d.status,d.note,d.engineer_name,d.updated_at "+
                "FROM beneficiary_quantities q LEFT JOIN boq_delivery d ON d.beneficiary_id=q.beneficiary_id AND d.item_no=q.item_no "+
                "WHERE q.beneficiary_id=? AND q.quantity>0 ORDER BY q.item_no";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{String.valueOf(beneficiaryId)})){
            while(c.moveToNext()){
                BoqDeliveryEntry e=new BoqDeliveryEntry();
                e.itemNo=c.getInt(0);e.quantity=c.getDouble(1);e.unitPrice=c.getDouble(2);
                e.status=c.isNull(3)?BOQ_NOT_DELIVERED:c.getString(3);e.note=c.isNull(4)?"":c.getString(4);
                e.engineerName=c.isNull(5)?"":c.getString(5);e.updatedAt=c.isNull(6)?0:c.getLong(6);
                rows.add(e);
            }
        }
        return rows;
    }

    /** Quick per-item status change from the same BOQ screen — no separate screen per item. Always stamps who and when. */
    public void updateBoqDeliveryStatus(long beneficiaryId,int itemNo,String status,String note,String engineerName){
        ContentValues v=new ContentValues();
        v.put("beneficiary_id",beneficiaryId);v.put("item_no",itemNo);v.put("status",status);
        v.put("note",note==null?"":note);v.put("engineer_name",engineerName==null?"":engineerName);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("boq_delivery",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Starts the 5-week (35-day) first-stage countdown. Guarded so a second press can never reset the original date —
     * this only ever writes once per beneficiary row (delivery_confirmed_at=0 is the only state it will fire from). */
    public boolean confirmDeliveryStart(long id){
        ContentValues v=new ContentValues();v.put("delivery_confirmed_at",System.currentTimeMillis());v.put("updated_at",System.currentTimeMillis());
        return getWritableDatabase().update("beneficiaries",v,"_id=? AND (delivery_confirmed_at IS NULL OR delivery_confirmed_at=0)",new String[]{String.valueOf(id)})>0;
    }

    /** The actual first-stage execution start is whichever happened LAST — work can be handed over before the
     * money is available, or the money can be confirmed before the work is handed over; the 35-day clock only
     * makes sense once BOTH have happened. Returns 0 (not started) if either date is still missing. Never
     * stored — always recomputed from the two independent dates, so correcting either one instantly and
     * correctly recomputes the countdown with no migration involved. */
    public static long executionStartAt(Beneficiary b){
        if(b.deliveryConfirmedAt<=0||b.firstPaymentAvailableAt<=0)return 0;
        return Math.max(b.deliveryConfirmedAt,b.firstPaymentAvailableAt);
    }

    /** Explicit, manual confirmation that the first (60%) payment is actually available/received — NEVER
     * inferred from an Excel amount column or any other imported value. Pressing this again after it was
     * already set is treated as a correction (old value is preserved in the log entry's reason, not erased). */
    public void setFirstPaymentAvailable(long id,String engineerName){
        Beneficiary current=beneficiary(id);
        long now=System.currentTimeMillis();
        boolean wasAlreadySet=current!=null&&current.firstPaymentAvailableAt>0;
        ContentValues v=new ContentValues();v.put("first_payment_available_at",now);v.put("first_payment_confirmed_by",engineerName==null?"":engineerName);v.put("updated_at",now);
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
        String reason=wasAlreadySet&&current!=null?"تصحيح — كان مسجلاً سابقاً بتاريخ "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US).format(new java.util.Date(current.firstPaymentAvailableAt)):"";
        logFirstPayment(id,wasAlreadySet?"corrected":"confirmed",engineerName,reason);
    }

    /** Cancels a first-payment-available date that was set in error. Never a silent delete: the beneficiary row
     * goes back to 0 (so the countdown correctly reverts to "not started"), but a 'cancelled' row is appended
     * to first_payment_log with the optional reason, so the earlier confirmation is never erased from history. */
    public void cancelFirstPaymentAvailable(long id,String engineerName,String reason){
        ContentValues v=new ContentValues();v.put("first_payment_available_at",0);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
        logFirstPayment(id,"cancelled",engineerName,reason);
    }

    private void logFirstPayment(long beneficiaryId,String action,String engineerName,String reason){
        ContentValues v=new ContentValues();v.put("beneficiary_id",beneficiaryId);v.put("action",action);
        v.put("engineer_name",engineerName==null?"":engineerName);v.put("at",System.currentTimeMillis());v.put("reason",reason==null?"":reason);
        getWritableDatabase().insertOrThrow("first_payment_log",null,v);
    }

    public List<FirstPaymentLogEntry> firstPaymentLog(long beneficiaryId){
        List<FirstPaymentLogEntry> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("first_payment_log",null,"beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)},null,null,"at DESC,_id DESC")){
            while(c.moveToNext()){FirstPaymentLogEntry e=new FirstPaymentLogEntry();e.action=str(c,"action");e.engineerName=str(c,"engineer_name");e.reason=str(c,"reason");e.at=longValue(c,"at");rows.add(e);}
        }
        return rows;
    }

    /** Everything an engineer needs to decide second-payment readiness — gathered for display only, never used to auto-approve anything. */
    public SecondPaymentSummary computeSecondPaymentSummary(long beneficiaryId){
        SecondPaymentSummary s=new SecondPaymentSummary();
        for(BoqDeliveryEntry e:boqDeliveryStatus(beneficiaryId)){
            s.totalItems++;
            if(BOQ_RECEIVED.equals(e.status))s.receivedCount++;
            else if(BOQ_DELIVERED.equals(e.status)||BOQ_IN_PROGRESS.equals(e.status)||BOQ_READY_FOR_PICKUP.equals(e.status))s.deliveredCount++;
            else s.pendingCount++;
        }
        s.duringPhotoCount=photoCount(beneficiaryId,BeneficiaryPhotoManager.DURING);
        s.totalAmount=quantityTotal(beneficiaryId);
        s.firstPayment=QuantityCatalog.firstPayment(s.totalAmount);s.secondPayment=QuantityCatalog.secondPayment(s.totalAmount);
        return s;
    }

    /** Purely manual approval — never triggered by BOQ completion, photo arrival, or the 35-day timer expiring.
     * Idempotent and always logged, so re-approving after a revoke is safe and fully auditable. */
    public void approveSecondPayment(long id,String engineerName){
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("second_payment_ready",1);v.put("second_payment_approved_by",engineerName==null?"":engineerName);
        v.put("second_payment_approved_at",now);v.put("updated_at",now);
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
        logSecondPayment(id,"approved",engineerName,"");
    }

    /** Revoking never erases the prior approval's evidence (second_payment_approved_by/at stay as-is on the row,
     * and the earlier 'approved' log row is never touched) — only the live ready flag flips off, and a new
     * 'revoked' row is appended so the full history stays reconstructable. */
    public void revokeSecondPayment(long id,String engineerName,String reason){
        ContentValues v=new ContentValues();v.put("second_payment_ready",0);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("beneficiaries",v,"_id=?",new String[]{String.valueOf(id)});
        logSecondPayment(id,"revoked",engineerName,reason);
    }

    private void logSecondPayment(long beneficiaryId,String action,String engineerName,String reason){
        ContentValues v=new ContentValues();v.put("beneficiary_id",beneficiaryId);v.put("action",action);
        v.put("engineer_name",engineerName==null?"":engineerName);v.put("at",System.currentTimeMillis());v.put("reason",reason==null?"":reason);
        getWritableDatabase().insertOrThrow("second_payment_log",null,v);
    }

    public List<SecondPaymentLogEntry> secondPaymentLog(long beneficiaryId){
        List<SecondPaymentLogEntry> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("second_payment_log",null,"beneficiary_id=?",new String[]{String.valueOf(beneficiaryId)},null,null,"at DESC,_id DESC")){
            while(c.moveToNext()){SecondPaymentLogEntry e=new SecondPaymentLogEntry();e.action=str(c,"action");e.engineerName=str(c,"engineer_name");e.reason=str(c,"reason");e.at=longValue(c,"at");rows.add(e);}
        }
        return rows;
    }

    /** Every beneficiary currently flagged ready — this is the exact "جاهزون لرفع الدفعة الثانية" list, always
     * scoped to the live followup stage so a superseded study revision can never appear here. */
    public List<Beneficiary> secondPaymentReadyList(long projectId){
        List<Beneficiary> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("beneficiaries",null,"project_id=? AND workflow_stage='followup' AND second_payment_ready=1",
                new String[]{String.valueOf(projectId)},null,null,"followup_batch,sequence_no,_id")){
            while(c.moveToNext())rows.add(read(c));
        }
        return rows;
    }

    public long addPhoto(long beneficiaryId,String phase,String uri,String fileName,long capturedAt,String note){
        ContentValues v=new ContentValues();v.put("beneficiary_id",beneficiaryId);v.put("phase",phase);v.put("uri",uri);v.put("file_name",fileName);
        v.put("captured_at",capturedAt);v.put("note",note==null?"":note);return getWritableDatabase().insertOrThrow("beneficiary_photos",null,v);
    }

    public void updatePhotoNote(long photoId,String note){
        ContentValues v=new ContentValues();v.put("note",note==null?"":note);getWritableDatabase().update("beneficiary_photos",v,"_id=?",new String[]{String.valueOf(photoId)});
    }

    /** A revision's photo set is (a) photos it directly captured PLUS (b) photos it inherited (via
     * revision_photo_links) from the revision it corrected — never a copy, always the same underlying row/file. */
    public List<Photo> photos(long beneficiaryId,String phase){
        List<Photo> rows=new ArrayList<>();
        String sql="SELECT p.* FROM beneficiary_photos p WHERE p.beneficiary_id=?"+
                " UNION SELECT p.* FROM beneficiary_photos p JOIN revision_photo_links l ON l.photo_id=p._id WHERE l.beneficiary_id=?";
        List<String>args=new ArrayList<>();args.add(String.valueOf(beneficiaryId));args.add(String.valueOf(beneficiaryId));
        if(phase!=null&&!phase.isEmpty())sql="SELECT * FROM ("+sql+") WHERE phase=?";
        if(phase!=null&&!phase.isEmpty())args.add(phase);
        sql+=" ORDER BY captured_at DESC,_id DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){Photo p=new Photo();p.id=longValue(c,"_id");p.beneficiaryId=longValue(c,"beneficiary_id");p.phase=str(c,"phase");p.uri=str(c,"uri");p.fileName=str(c,"file_name");p.capturedAt=longValue(c,"captured_at");p.note=str(c,"note");rows.add(p);}
        }
        return rows;
    }

    public int photoCount(long beneficiaryId,String phase){
        String sql="SELECT COUNT(*) FROM (SELECT p._id FROM beneficiary_photos p WHERE p.beneficiary_id=? AND p.phase=?"+
                " UNION SELECT p._id FROM beneficiary_photos p JOIN revision_photo_links l ON l.photo_id=p._id WHERE l.beneficiary_id=? AND p.phase=?)";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{String.valueOf(beneficiaryId),phase,String.valueOf(beneficiaryId),phase})){
            return c.moveToFirst()?c.getInt(0):0;
        }
    }

    public void deleteProjectData(long projectId) {
        int existing = scalar("SELECT COUNT(*) FROM beneficiaries WHERE project_id=?", new String[]{String.valueOf(projectId)});
        diag("DELETE_PROJECT_DATA","project_id="+projectId+" rows_removed="+existing);
        getWritableDatabase().delete("beneficiaries","project_id=?",new String[]{String.valueOf(projectId)});
    }

    /** Phase 10: records that these exact revisions were successfully handed off to a sync transport (see
     * ShareIntentDesktopAdapter's honesty note — this is "exported", never "confirmed received"). Only
     * called by the caller AFTER a transport reports success; never called at all if the user cancels the
     * share sheet or if package building fails. Never touches study_status, workflow_stage, or any other
     * field — sync bookkeeping only. */
    public void markRevisionsSynced(List<String> revisionUuids){
        if(revisionUuids==null||revisionUuids.isEmpty())return;
        SQLiteDatabase database=getWritableDatabase();database.beginTransaction();
        try{
            long now=System.currentTimeMillis();
            for(String uuid:revisionUuids){
                if(uuid==null||uuid.isEmpty())continue;
                ContentValues v=new ContentValues();v.put("sync_status","exported");v.put("synced_at",now);
                database.update("beneficiaries",v,"revision_uuid=?",new String[]{uuid});
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    private Beneficiary read(Cursor c){
        Beneficiary b=new Beneficiary(); b.id=longValue(c,"_id");b.projectId=longValue(c,"project_id");b.sequence=intValue(c,"sequence_no");
        b.name=str(c,"name");b.registration=str(c,"registration");b.relation=str(c,"relation");b.relatedTo=str(c,"related_to");
        b.phone1=str(c,"phone1");b.phone2=str(c,"phone2");b.address=str(c,"address");b.sector=str(c,"sector");b.street=str(c,"street");
        b.amount=str(c,"amount");b.matchStatus=str(c,"match_status");b.confidence=doubleValue(c,"confidence");b.sectorOrder=intValue(c,"sector_order");
        b.routeOrder=intValue(c,"route_order");b.streetOrder=intValue(c,"street_order");b.alleyOrder=intValue(c,"alley_order");
        b.visitStatus=str(c,"visit_status");b.progressPercent=intValue(c,"progress_percent");b.notes=str(c,"notes");b.workflowStage=str(c,"workflow_stage");b.studyStatus=str(c,"study_status");
        b.studyUuid=str(c,"study_uuid");b.revisionUuid=str(c,"revision_uuid");b.revisionNo=intValue(c,"revision_no");b.previousRevisionUuid=str(c,"previous_revision_uuid");
        b.damageNotes=str(c,"damage_notes");b.quantitiesNotes=str(c,"quantities_notes");b.drawingUri=str(c,"drawing_uri");b.followupBatch=str(c,"followup_batch");b.approvedAt=longValue(c,"approved_at");b.workDelivered=intValue(c,"work_delivered")!=0;b.engineerName=str(c,"engineer_name");
        b.deliveryConfirmedAt=longValue(c,"delivery_confirmed_at");b.secondPaymentReady=intValue(c,"second_payment_ready")!=0;
        b.secondPaymentApprovedBy=str(c,"second_payment_approved_by");b.secondPaymentApprovedAt=longValue(c,"second_payment_approved_at");
        b.firstPaymentAvailableAt=longValue(c,"first_payment_available_at");b.firstPaymentConfirmedBy=str(c,"first_payment_confirmed_by");
        b.gpsLat=doubleValue(c,"gps_lat");b.gpsLng=doubleValue(c,"gps_lng");b.gpsAccuracyM=doubleValue(c,"gps_accuracy_m");b.gpsCapturedAt=longValue(c,"gps_captured_at");b.gpsSource=str(c,"gps_source");
        b.originalAddress=str(c,"original_address");b.addressSource=str(c,"address_source");
        b.addressesFileAddress=str(c,"addresses_file_address");b.addressesFileSector=str(c,"addresses_file_sector");
        b.studyEngineer1=str(c,"study_engineer1");b.studyEngineer2=str(c,"study_engineer2");b.fieldAssistant=str(c,"field_assistant");b.studyDate=str(c,"study_date");
        b.sentToOfficeReviewAt=longValue(c,"sent_to_office_review_at");
        b.followupEngineer1=str(c,"followup_engineer1");b.followupEngineer2=str(c,"followup_engineer2");b.followupDate=str(c,"followup_date");
        b.followupStatus=str(c,"followup_status");b.deliveryNote=str(c,"delivery_note");
        b.syncStatus=str(c,"sync_status");b.syncedAt=longValue(c,"synced_at");
        return b;
    }
    private static String str(Cursor c,String n){String v=c.getString(c.getColumnIndexOrThrow(n));return v==null?"":v;}
    private static int intValue(Cursor c,String n){return c.getInt(c.getColumnIndexOrThrow(n));}
    private static long longValue(Cursor c,String n){return c.getLong(c.getColumnIndexOrThrow(n));}
    private static double doubleValue(Cursor c,String n){return c.getDouble(c.getColumnIndexOrThrow(n));}

    public JSONObject backup(long projectId,String projectName) throws Exception {
        JSONObject root=new JSONObject();root.put("schema_version","1.3");root.put("project",projectName);root.put("exported_at",System.currentTimeMillis());
        JSONArray data=new JSONArray();for(Beneficiary b:list(projectId,"","all")){JSONObject o=new JSONObject();o.put("name",b.name);o.put("registration",b.registration);
            o.put("relation",b.relation);o.put("related_to",b.relatedTo);o.put("phone1",b.phone1);o.put("phone2",b.phone2);o.put("address",b.address);
            o.put("sector",b.sector);o.put("street",b.street);o.put("amount",b.amount);o.put("match_status",b.matchStatus);o.put("visit_status",b.visitStatus);o.put("progress_percent",b.progressPercent);o.put("notes",b.notes);
            o.put("workflow_stage",b.workflowStage);o.put("study_status",b.studyStatus);o.put("damage_notes",b.damageNotes);o.put("quantities_notes",b.quantitiesNotes);o.put("drawing_uri",b.drawingUri);o.put("followup_batch",b.followupBatch);o.put("approved_at",b.approvedAt);o.put("work_delivered",b.workDelivered);o.put("engineer_name",b.engineerName);
            JSONArray quantityRows=new JSONArray();for(QuantityEntry q:quantities(b.id)){JSONObject item=new JSONObject();item.put("item_no",q.itemNo);item.put("quantity",q.quantity);item.put("unit_price",q.unitPrice);item.put("subtotal",q.subtotal());quantityRows.put(item);}o.put("quantities",quantityRows);
            JSONArray images=new JSONArray();for(Photo p:photos(b.id,"")){JSONObject image=new JSONObject();image.put("phase",p.phase);image.put("uri",p.uri);image.put("file_name",p.fileName);image.put("captured_at",p.capturedAt);image.put("note",p.note);images.put(image);}o.put("photos",images);data.put(o);}root.put("beneficiaries",data);return root;
    }
}
