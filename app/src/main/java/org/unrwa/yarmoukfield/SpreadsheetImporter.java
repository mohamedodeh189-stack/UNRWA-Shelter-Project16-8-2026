package org.unrwa.yarmoukfield;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lightweight offline XLSX/CSV/JSON importer without third-party libraries. */
public final class SpreadsheetImporter {
    public static final class Result {
        public final List<AppDatabase.Beneficiary> rows = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public boolean backup;
    }

    private static final class SheetRow {
        final Map<Integer,String> cells=new HashMap<>();
        String get(int column){return cells.containsKey(column)?cells.get(column):"";}
    }

    public static Result read(Context context,Uri uri) throws Exception {
        String name=queryName(context,uri).toLowerCase(Locale.ROOT);
        if(name.endsWith(".xlsx")||name.endsWith(".xlsm")) return fromXlsx(context,uri);
        // Legacy binary .xls is NOT the same format as .xlsx and this offline reader can't parse it — reject
        // it explicitly instead of falling through to the CSV reader, which would mis-read its binary bytes.
        if(name.endsWith(".xls")) throw new IllegalArgumentException("ملفات XLS القديمة غير مدعومة مباشرة — افتح الملف في Excel واحفظه بصيغة XLSX ثم أعد الاستيراد.");
        if(name.endsWith(".json")) return fromJson(context,uri);
        return fromCsv(context,uri);
    }

    /** Completes only empty/unclassified addresses from repeated rows in the same workbook. */
    public static int enrichAddresses(List<AppDatabase.Beneficiary> rows,AddressClassifier classifier){
        Map<String,List<AppDatabase.Beneficiary>> registrations=new HashMap<>(),files=new HashMap<>(),phones=new HashMap<>(),names=new HashMap<>();
        for(AppDatabase.Beneficiary b:rows){
            if(b.address.trim().isEmpty())continue;
            index(registrations,digits(b.registration),b);index(files,digits(b.fileNo),b);index(names,person(b.name),b);
            index(phones,phoneKey(b.phone1),b);index(phones,phoneKey(b.phone2),b);
        }
        int completed=0;
        for(AppDatabase.Beneficiary target:rows){
            target.originalAddress=target.address;
            AddressClassifier.Match current=classifier.classify(target.address);
            // The classifier always returns a real sector now (never "غير
            // مصنف"), so weak confidence — not the literal label — is what
            // signals a completion from another record is worth trying.
            if(!target.address.trim().isEmpty()&&current.confidence>=0.60)continue;
            LinkedHashMap<String,List<AppDatabase.Beneficiary>> choices=new LinkedHashMap<>();
            addChoice(choices,"رقم التسجيل",registrations.get(digits(target.registration)));
            addChoice(choices,"رقم الملف",files.get(digits(target.fileNo)));
            addChoice(choices,"رقم الهاتف",phones.get(phoneKey(target.phone1)));
            addChoice(choices,"رقم الهاتف",phones.get(phoneKey(target.phone2)));
            addChoice(choices,"اسم المستفيد",names.get(person(target.name)));
            String owner=person(target.relatedTo);if(!owner.isEmpty())addChoice(choices,"المستفيد الرئيسي بالوثيقة",names.get(owner));
            AppDatabase.Beneficiary best=null;String method="";int strength=-1;
            for(Map.Entry<String,List<AppDatabase.Beneficiary>> choice:choices.entrySet()){
                if(choice.getValue()==null)continue;
                for(AppDatabase.Beneficiary donor:choice.getValue()){
                    if(donor==target||donor.address.trim().isEmpty())continue;
                    if((choice.getKey().equals("اسم المستفيد"))&&!digits(target.registration).isEmpty()&&!digits(donor.registration).isEmpty()&&!digits(target.registration).equals(digits(donor.registration)))continue;
                    AddressClassifier.Match dm=classifier.classify(donor.address);if(dm.confidence<0.60)continue;
                    int score=addressStrength(donor.address);if(score>strength){best=donor;method=choice.getKey();strength=score;}
                }
                if(best!=null)break;
            }
            if(best!=null&&!AddressClassifier.normalize(best.address).equals(AddressClassifier.normalize(target.address))){
                String detail=target.address.trim();target.address=best.address.trim();
                if(!detail.isEmpty()&&!AddressClassifier.normalize(target.address).contains(AddressClassifier.normalize(detail)))target.address+=" — "+detail;
                target.addressSource=method+" — "+best.name;
                target.notes=(target.notes.isEmpty()?"":target.notes+"\n")+"استكمال العنوان آليًا من "+target.addressSource;
                completed++;
            }
        }
        return completed;
    }
    private static void index(Map<String,List<AppDatabase.Beneficiary>> map,String key,AppDatabase.Beneficiary value){if(key==null||key.isEmpty())return;List<AppDatabase.Beneficiary> list=map.get(key);if(list==null){list=new ArrayList<>();map.put(key,list);}list.add(value);}
    private static void addChoice(LinkedHashMap<String,List<AppDatabase.Beneficiary>> choices,String method,List<AppDatabase.Beneficiary> rows){if(rows!=null&&!rows.isEmpty()&&!choices.containsKey(method))choices.put(method,rows);}
    private static String digits(String value){return value==null?"":value.replaceAll("[^0-9]","");}
    private static String phoneKey(String value){String d=digits(value);return d.length()>9?d.substring(d.length()-9):d;}
    private static String person(String value){String n=AddressClassifier.normalize(value);n=n.replaceAll("\\b(المرحوم|المرحومه|المتوفي|المتوفاه|باسم|اسم|نفسه|نفسها|زوج|زوجه|زوجته|زوجها|والده|والدها|ابن|ابنه|بنت|ورثه)\\b"," ").replaceAll("\\s+"," ").trim();return n.split(" ").length>=2?n:"";}
    private static int addressStrength(String value){String n=AddressClassifier.normalize(value);if(n.isEmpty())return 0;int score=n.split(" ").length;if(n.matches(".*(شارع|حاره|جاده|مقابل|جانب|تقاطع).*"))score+=4;if(n.matches(".*(كتله|مدخل|بناء|منزل|بيت).*"))score+=3;if(n.matches(".*\\d.*"))score+=2;return score;}

    public static String queryName(Context context,Uri uri){
        try(android.database.Cursor c=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}return uri.getLastPathSegment()==null?"list.xlsx":uri.getLastPathSegment();
    }

    private static Result fromXlsx(Context context,Uri uri)throws Exception{
        File temp=File.createTempFile("yarmouk_import_",".xlsx",context.getCacheDir());
        try(InputStream in=context.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){
            if(in==null)throw new IllegalArgumentException("تعذر فتح الملف");byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);
        }
        try(ZipFile zip=new ZipFile(temp)){
            List<String> shared=readSharedStrings(zip);
            List<String> sheetNames=readSheetNames(zip);
            List<ZipEntry>sheets=new ArrayList<>();
            zip.stream().filter(e->e.getName().matches("xl/worksheets/sheet\\d+\\.xml")).forEach(sheets::add);
            Collections.sort(sheets,Comparator.comparing(ZipEntry::getName));
            Result result=new Result();

            // Parse every sheet once, keyed by its real declared name when
            // available (falls back to a positional label so behaviour is
            // unchanged for workbooks aapt/Excel didn't name distinctly).
            LinkedHashMap<String,List<SheetRow>> sheetsByName=new LinkedHashMap<>();
            for(int i=0;i<sheets.size();i++){
                List<SheetRow> matrix=parseSheet(zip.getInputStream(sheets.get(i)),shared);
                String name=i<sheetNames.size()&&sheetNames.get(i)!=null&&!sheetNames.get(i).trim().isEmpty()?sheetNames.get(i).trim():("Sheet"+(i+1));
                sheetsByName.put(name,matrix);
            }

            // Single-beneficiary workbook shape (e.g. Organized_Final/*.xlsx:
            // one beneficiary per file, sheets like التقييم/الكميات/المخطط).
            // Detected first and handled entirely separately from the flat
            // multi-beneficiary table path below.
            AppDatabase.Beneficiary single=tryReadSingleBeneficiaryWorkbook(context,sheetsByName,result.warnings);
            if(single!=null){
                result.rows.add(single);
                return result;
            }

            // A field-definitions/"العناوين" sheet, when present, extends the
            // header-matching aliases below. Absent in every real file
            // inspected so far, so this is a no-op unless the workbook
            // actually contains one.
            Map<String,List<String>> extraAliases=loadFieldDefinitions(sheetsByName);

            // Otherwise: a flat list workbook — every sheet is a table of
            // many beneficiaries (the real reference file uses sheets named
            // "11","12","13","14","15" for five distinct field lists). Each
            // sheet keeps its own follow-up batch tag so the lists never
            // blend into one another.
            for(Map.Entry<String,List<SheetRow>> entry:sheetsByName.entrySet()){
                Result part=convert(entry.getValue(),extraAliases);
                String batchTag=sheetBatchTag(entry.getKey());
                if(batchTag!=null)for(AppDatabase.Beneficiary b:part.rows)if(b.followupBatch.isEmpty())b.followupBatch=batchTag;
                result.rows.addAll(part.rows);result.warnings.addAll(part.warnings);
            }
            if(result.rows.isEmpty())result.warnings.add("لم يتم العثور على جدول يحتوي عمود عنوان أو اسم مستفيد.");return result;
        }finally{temp.delete();}
    }

    /** Sheet display names in declaration order, straight from xl/workbook.xml — sheetN.xml file numbering already matches this order in practice, same assumption the existing filename sort makes. */
    private static List<String> readSheetNames(ZipFile zip)throws Exception{
        List<String> names=new ArrayList<>();
        ZipEntry entry=zip.getEntry("xl/workbook.xml");
        if(entry==null)return names;
        XMLReader parser=createXmlReader();
        parser.setContentHandler(new DefaultHandler(){
            @Override public void startElement(String u,String l,String q,Attributes a){
                String local=l!=null&&!l.isEmpty()?l:q;
                if("sheet".equals(local)){String name=a.getValue("name");names.add(name==null?"":name);}
            }});
        parser.parse(new InputSource(zip.getInputStream(entry)));
        return names;
    }

    /** A bare number like "11" is a known list-number sheet name in the field workbooks (List-11..15) — tag it clearly instead of leaving every row batch-less. Ordinary/default sheet names return null so the caller's generic file+timestamp tag still applies, preserving old behaviour for simple single-sheet files. */
    private static String sheetBatchTag(String sheetName){
        String trimmed=sheetName==null?"":sheetName.trim();
        if(trimmed.isEmpty()||trimmed.matches("(?i)sheet\\d+"))return null;
        if(trimmed.matches("\\d{1,3}"))return "قائمة "+trimmed;
        return "قائمة: "+trimmed;
    }

    private static List<String> readSharedStrings(ZipFile zip)throws Exception{
        ZipEntry entry=zip.getEntry("xl/sharedStrings.xml");List<String> values=new ArrayList<>();if(entry==null)return values;
        XMLReader parser=createXmlReader();parser.setContentHandler(new DefaultHandler(){StringBuilder text=new StringBuilder();StringBuilder current=new StringBuilder();boolean inT;
            @Override public void startElement(String u,String l,String q,Attributes a){if("si".equals(q))current.setLength(0);if("t".equals(q)){text.setLength(0);inT=true;}}
            @Override public void characters(char[]c,int s,int n){if(inT)text.append(c,s,n);}
            @Override public void endElement(String u,String l,String q){if("t".equals(q)){current.append(text);inT=false;}if("si".equals(q))values.add(current.toString());}});
        parser.parse(new InputSource(zip.getInputStream(entry)));return values;
    }

    private static List<SheetRow> parseSheet(InputStream stream,List<String>shared)throws Exception{
        List<SheetRow> rows=new ArrayList<>();XMLReader parser=createXmlReader();
        parser.setContentHandler(new DefaultHandler(){SheetRow row;String type="";int col;StringBuilder value=new StringBuilder();boolean capture;
            @Override public void startElement(String u,String l,String q,Attributes a){
                if("row".equals(q))row=new SheetRow();else if("c".equals(q)){type=a.getValue("t");col=column(a.getValue("r"));}
                else if("v".equals(q)||"t".equals(q)){value.setLength(0);capture=true;}}
            @Override public void characters(char[]c,int s,int n){if(capture)value.append(c,s,n);}
            @Override public void endElement(String u,String l,String q){if("v".equals(q)||"t".equals(q)){String raw=value.toString();
                    if("s".equals(type))try{raw=shared.get(Integer.parseInt(raw));}catch(Exception ignored){}if(row!=null&&!raw.isEmpty())row.cells.put(col,raw);capture=false;}
                else if("row".equals(q)&&row!=null){rows.add(row);row=null;}}});
        parser.parse(new InputSource(stream));return rows;
    }
    /** Android does not define the legacy org.xml.sax.driver system property. */
    private static XMLReader createXmlReader()throws Exception{
        SAXParserFactory factory=SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newSAXParser().getXMLReader();
    }
    private static int column(String ref){if(ref==null)return 0;int value=0;for(int i=0;i<ref.length()&&Character.isLetter(ref.charAt(i));i++)value=value*26+(Character.toUpperCase(ref.charAt(i))-'A'+1);return value-1;}

    private static Result fromCsv(Context context,Uri uri)throws Exception{
        List<SheetRow> matrix=new ArrayList<>();try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getContentResolver().openInputStream(uri),StandardCharsets.UTF_8))){String line;
            while((line=reader.readLine())!=null){List<String>values=csv(line);SheetRow row=new SheetRow();for(int i=0;i<values.size();i++)row.cells.put(i,values.get(i));matrix.add(row);}}
        return convert(matrix);
    }
    private static List<String> csv(String line){List<String>out=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean quotes=false;for(int i=0;i<line.length();i++){char ch=line.charAt(i);
        if(ch=='"'){if(quotes&&i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else quotes=!quotes;}else if(ch==','&&!quotes){out.add(cur.toString());cur.setLength(0);}else cur.append(ch);}out.add(cur.toString());return out;}

    private static Result fromJson(Context context,Uri uri)throws Exception{
        StringBuilder text=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(context.getContentResolver().openInputStream(uri),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)text.append(line);}
        Object root=text.toString().trim().startsWith("[")?new JSONArray(text.toString()):new JSONObject(text.toString());JSONArray array;
        Result result=new Result();if(root instanceof JSONArray)array=(JSONArray)root;else{JSONObject o=(JSONObject)root;result.backup=o.has("schema_version")&&o.has("beneficiaries");array=o.optJSONArray("beneficiaries");if(array==null)array=o.optJSONArray("rows");if(array==null)throw new IllegalArgumentException("لا توجد قائمة beneficiaries أو rows في JSON");}
        for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);AppDatabase.Beneficiary b=new AppDatabase.Beneficiary();
            b.sequence=i+1;b.name=first(o,"name","master_name","اسم المستفيد","الاسم");b.registration=first(o,"registration","reg_number","رقم التسجيل");b.fileNo=first(o,"file_no","file number","رقم الملف");
            b.relation=first(o,"relation","صلة القرابة");b.relatedTo=first(o,"related_to","main_owner","primary_beneficiary","مع من","المستفيد الرئيسي بالوثيقة");b.phone1=phone(first(o,"phone1","phone","الهاتف"));
            b.phone2=phone(first(o,"phone2","الهاتف الثاني"));b.address=first(o,"address","العنوان");b.amount=resolveAmount(first(o,"amount","master_amount","المبلغ"),o.has("amount")||o.has("master_amount")||o.has("المبلغ"));b.engineerName=first(o,"engineer_name","engineer","المهندس","الباحث");
            b.visitStatus=first(o,"visit_status");if(b.visitStatus.isEmpty())b.visitStatus="pending";b.notes=first(o,"notes","ملاحظات");
            if(result.backup){b.workflowStage=first(o,"workflow_stage");if(b.workflowStage.isEmpty())b.workflowStage="followup";b.studyStatus=first(o,"study_status");if(b.studyStatus.isEmpty())b.studyStatus="approved";b.damageNotes=first(o,"damage_notes");b.quantitiesNotes=first(o,"quantities_notes");b.drawingUri=first(o,"drawing_uri");b.followupBatch=first(o,"followup_batch");b.approvedAt=o.optLong("approved_at",0);b.workDelivered=o.optBoolean("work_delivered",false);b.progressPercent=Math.max(0,Math.min(100,o.optInt("progress_percent",0)));JSONArray quantities=o.optJSONArray("quantities");if(quantities!=null)for(int q=0;q<quantities.length();q++){JSONObject item=quantities.optJSONObject(q);if(item==null)continue;int itemNo=item.optInt("item_no");double quantity=item.optDouble("quantity");double unitPrice=item.optDouble("unit_price");if(itemNo>0&&quantity>0)b.quantities.add(new AppDatabase.QuantityEntry(itemNo,quantity,unitPrice));}}
            if(!b.name.isEmpty()||!b.address.isEmpty())result.rows.add(b);}return result;
    }
    private static String first(JSONObject o,String...keys){for(String k:keys){Object v=o.opt(k);if(v!=null&&v!=JSONObject.NULL&&!String.valueOf(v).trim().isEmpty())return String.valueOf(v).trim();}return "";}

    // ------------------------------------------------------------------
    // Single-beneficiary workbook shape (Organized_Final/*.xlsx): one file =
    // one beneficiary, values live as label/value cell pairs on a صفحة
    // التقييم sheet instead of a header-row table, and BOQ quantities live
    // on a صفحة الكميات sheet matching the 37-item reference layout.
    // ------------------------------------------------------------------
    private static AppDatabase.Beneficiary tryReadSingleBeneficiaryWorkbook(Context context,Map<String,List<SheetRow>>sheetsByName,List<String>warnings){
        List<SheetRow> eval=findSheetByHint(sheetsByName,"تقييم");
        if(eval==null)return null;
        String name=labelValue(eval,"اسم رب/ة الاسره","اسم رب ة الاسره","اسم رب الاسره","اسم المستفيد");
        if(name.isEmpty())return null;
        AppDatabase.Beneficiary b=new AppDatabase.Beneficiary();
        b.name=name;
        b.registration=clean(labelValue(eval,"رقم التسجيل في الكرت الابيض لرب العائله","رقم العائله","رقم التسجيل"));
        b.phone1=phone(labelValue(eval,"رقم الجوال 1","رقم الجوال ١","رقم الجوال"));
        b.phone2=phone(labelValue(eval,"رقم الجوال 2","رقم الجوال ٢"));
        String home=labelValue(eval,"عنوان المنزل");
        String street=labelValue(eval,"اسم الشارع");
        String general=labelValue(eval,"العنوان");
        java.util.LinkedHashSet<String> parts=new java.util.LinkedHashSet<>();
        for(String part:new String[]{home,street,general})if(!part.trim().isEmpty())parts.add(part.trim());
        b.address=String.join(" — ",parts);
        b.originalAddress=b.address;
        List<SheetRow> boq=findSheetByHint(sheetsByName,"كميات");
        if(boq!=null)b.quantities=readBoqEntries(context,boq,warnings);
        return b;
    }

    private static List<SheetRow> findSheetByHint(Map<String,List<SheetRow>>sheetsByName,String hint){
        String target=AddressClassifier.normalize(hint);
        for(Map.Entry<String,List<SheetRow>> entry:sheetsByName.entrySet())
            if(AddressClassifier.normalize(entry.getKey()).contains(target))return entry.getValue();
        return null;
    }

    /** Scans label cells (col N) for a value in the next column, falling back to the row directly below the label — mirrors the layout used by the field evaluation form. */
    private static String labelValue(List<SheetRow>matrix,String...labels){
        java.util.Set<String> targets=new java.util.HashSet<>();
        for(String l:labels)targets.add(AddressClassifier.normalize(l));
        for(int r=0;r<Math.min(matrix.size(),80);r++){
            SheetRow row=matrix.get(r);
            for(Map.Entry<Integer,String> cell:row.cells.entrySet()){
                String norm=AddressClassifier.normalize(cell.getValue());
                if(norm.isEmpty()||!targets.contains(norm))continue;
                String next=row.get(cell.getKey()+1);
                if(!next.trim().isEmpty())return next.trim();
                if(r+1<matrix.size()){String below=matrix.get(r+1).get(cell.getKey());if(!below.trim().isEmpty())return below.trim();}
            }
        }
        return "";
    }
    private static String clean(String value){return value==null?"":value.trim();}

    // ------------------------------------------------------------------
    // BOQ matching: item number first, then item name/description, and
    // only as a last resort (and only when the sheet's row count matches
    // the known 37-item reference layout) positional row order — flagged
    // explicitly as needing confirmation.
    // ------------------------------------------------------------------
    private static List<AppDatabase.QuantityEntry> readBoqEntries(Context context,List<SheetRow>matrix,List<String>warnings){
        List<AppDatabase.QuantityEntry> result=new ArrayList<>();
        List<QuantityCatalog.Item> catalog;
        try{catalog=QuantityCatalog.load(context);}catch(Exception e){return result;}
        int header=-1;Map<String,Integer>boqCols=null;
        for(int r=0;r<Math.min(15,matrix.size());r++){
            Map<String,Integer> candidate=boqColumns(matrix.get(r));
            if(candidate.containsKey("quantity")&&(candidate.containsKey("item_no")||candidate.containsKey("description"))){header=r;boqCols=candidate;break;}
        }
        if(header<0)return result;
        int dataRows=matrix.size()-header-1;
        boolean rowOrderEligible=!boqCols.containsKey("item_no")&&dataRows>=30&&dataRows<=45;
        int position=0;
        for(int r=header+1;r<matrix.size();r++){
            SheetRow row=matrix.get(r);
            double quantity=parseNumber(get(row,boqCols,"quantity"));
            if(quantity<=0){position++;continue;}
            QuantityCatalog.Item matched=null;String method="";
            Integer itemNo=parseInt(get(row,boqCols,"item_no"));
            if(itemNo!=null)for(QuantityCatalog.Item item:catalog)if(item.number==itemNo){matched=item;method="رقم البند";break;}
            if(matched==null){
                String desc=get(row,boqCols,"description");
                if(!desc.trim().isEmpty()){
                    String descNorm=AddressClassifier.normalize(desc);
                    QuantityCatalog.Item best=null;double bestScore=0;
                    for(QuantityCatalog.Item item:catalog){
                        double score=nameSimilarity(descNorm,AddressClassifier.normalize(item.name));
                        if(score>bestScore){bestScore=score;best=item;}
                    }
                    if(best!=null&&bestScore>=0.55){matched=best;method="اسم البند";}
                }
            }
            if(matched==null&&rowOrderEligible&&position<catalog.size()){
                matched=catalog.get(position);method="مطابقة بالترتيب — تحتاج تأكيد";
                warnings.add("بند BOQ في صف "+(r+1)+" اعتمد على ترتيب الصفوف فقط ("+matched.number+". "+matched.name+") — يحتاج تأكيد يدوي.");
            }
            if(matched!=null)result.add(new AppDatabase.QuantityEntry(matched.number,quantity,matched.unitPrice));
            position++;
        }
        return result;
    }
    private static Map<String,Integer>boqColumns(SheetRow row){
        Map<String,Integer>cols=new LinkedHashMap<>();
        for(Map.Entry<Integer,String>cell:row.cells.entrySet()){
            String t=AddressClassifier.normalize(cell.getValue());if(t.isEmpty())continue;
            if(t.equals("م")||t.equals("no")||t.equals("no.")||t.contains("item"))put(cols,"item_no",cell.getKey());
            if(t.contains("الكميه")||t.contains("quantity"))put(cols,"quantity",cell.getKey());
            if(t.contains("نوع الضرر")||t.contains("الوصف")||t.contains("description")||t.contains("type of damage"))put(cols,"description",cell.getKey());
        }
        return cols;
    }
    private static double parseNumber(String value){try{return Double.parseDouble(value.trim().replace(",",""));}catch(Exception e){return 0;}}
    private static Integer parseInt(String value){try{return (int)Math.round(Double.parseDouble(value.trim()));}catch(Exception e){return null;}}
    private static double nameSimilarity(String a,String b){
        if(a.isEmpty()||b.isEmpty())return 0;
        if(a.equals(b))return 1.0;
        if(a.contains(b)||b.contains(a))return 0.85;
        java.util.Set<String> wa=new java.util.HashSet<>(java.util.Arrays.asList(a.split(" ")));
        java.util.Set<String> wb=new java.util.HashSet<>(java.util.Arrays.asList(b.split(" ")));
        int minLen=Math.min(wa.size(),wb.size());if(minLen==0)return 0;
        wa.retainAll(wb);
        return (double)wa.size()/minLen*0.8;
    }

    // ------------------------------------------------------------------
    // Optional field-definitions sheet ("العناوين"/"تعريف الحقول"): a
    // two-column label->alias table that extends header matching below.
    // A complete no-op when no such sheet exists, which is every real file
    // inspected so far — zero behaviour change unless one is present.
    // ------------------------------------------------------------------
    private static Map<String,List<String>>loadFieldDefinitions(Map<String,List<SheetRow>>sheetsByName){
        Map<String,List<String>>extra=new HashMap<>();
        for(Map.Entry<String,List<SheetRow>> entry:sheetsByName.entrySet()){
            String norm=AddressClassifier.normalize(entry.getKey());
            if(!norm.contains("العناوين")&&!norm.contains("تعريف")&&!norm.contains("definition"))continue;
            for(SheetRow row:entry.getValue()){
                String label=row.get(0).trim();String alias=row.get(1).trim();
                if(label.isEmpty()||alias.isEmpty())continue;
                String key=canonicalFieldKey(label);
                if(key==null)continue;
                extra.computeIfAbsent(key,k->new ArrayList<>()).add(AddressClassifier.normalize(alias));
            }
        }
        return extra;
    }
    private static String canonicalFieldKey(String label){
        String n=AddressClassifier.normalize(label);
        if(n.contains("عنوان"))return "address";
        if(n.contains("قطاع"))return "sector";
        if(n.contains("شارع"))return "street";
        if(n.contains("مبلغ")||n.contains("قيمه"))return "amount";
        if(n.contains("تسجيل"))return "registration";
        if(n.contains("هاتف")||n.contains("جوال"))return "phone1";
        if(n.contains("اسم"))return "name";
        return null;
    }

    private static Result convert(List<SheetRow>matrix){return convert(matrix,java.util.Collections.emptyMap());}
    private static Result convert(List<SheetRow>matrix,Map<String,List<String>>extraAliases){Result result=new Result();if(matrix.isEmpty())return result;int header=-1;Map<String,Integer>cols=null;int best=-1;
        for(int r=0;r<Math.min(20,matrix.size());r++){Map<String,Integer>candidate=columns(matrix.get(r),extraAliases);int score=(candidate.containsKey("address")?5:0)+(candidate.containsKey("name")?3:0)+(candidate.containsKey("amount")?2:0);
            if(score>best){best=score;header=r;cols=candidate;}}
        if(cols==null||best<2){result.warnings.add("ورقة بلا رؤوس واضحة وتم تجاوزها.");return result;}
        // Columns already mapped to a specific field are excluded from the
        // whole-row address fallback so numbers/phones/amounts don't pollute
        // it; every other text cell is a candidate when the address column
        // itself is missing or empty on that row.
        Set<Integer> excluded=new java.util.HashSet<>();
        for(String key:new String[]{"name","amount","registration","file_no","phone1","phone2","engineer","sector","street_explicit"}){Integer c=cols.get(key);if(c!=null)excluded.add(c);}
        for(int r=header+1;r<matrix.size();r++){SheetRow row=matrix.get(r);AppDatabase.Beneficiary b=new AppDatabase.Beneficiary();b.sequence=r-header;
            b.name=get(row,cols,"name");b.registration=get(row,cols,"registration");b.fileNo=get(row,cols,"file_no");b.relation=get(row,cols,"relation");b.relatedTo=get(row,cols,"related_to");
            b.phone1=phone(get(row,cols,"phone1"));b.phone2=phone(get(row,cols,"phone2"));b.address=get(row,cols,"address");b.amount=resolveAmount(get(row,cols,"amount"),cols.containsKey("amount"));b.engineerName=get(row,cols,"engineer");
            // Explicit sector/street text from the sheet (when those columns
            // exist) is authoritative and takes precedence over text
            // classification later in MainActivity.importUri, which restores
            // these exact values after running the classifier for ordering.
            b.sector=get(row,cols,"sector");
            // Address-reference files (العناوين): the «المنطقة حسب المخطط» sector column is only on the first row of
            // each cluster; the «تجميعة البيوت المتجاورة» column carries the sector as a prefix on EVERY row
            // (e.g. «شرق اليرموك 1-جامع البشير-ت1»). Derive the sector from it when the explicit column is blank —
            // this is the landmark→sector intelligence, giving full sector coverage.
            if(b.sector.isEmpty()){String gs=sectorFromGrouping(get(row,cols,"grouping"));if(!gs.isEmpty())b.sector=gs;}
            b.street=get(row,cols,"street_explicit");
            if(b.address.isEmpty()){
                StringBuilder rowText=new StringBuilder();
                for(Map.Entry<Integer,String> cell:row.cells.entrySet()){
                    if(excluded.contains(cell.getKey()))continue;
                    String text=cell.getValue()==null?"":cell.getValue().trim();
                    if(text.isEmpty()||text.matches("[\\d\\s.,-]+"))continue;
                    if(rowText.length()>0)rowText.append(' ');
                    rowText.append(text);
                }
                b.address=rowText.toString().trim();
            }
            if(!b.name.isEmpty()||!b.address.isEmpty())result.rows.add(b);}return result;}
    private static String get(SheetRow row,Map<String,Integer>cols,String key){Integer col=cols.get(key);return col==null?"":row.get(col).trim();}
    private static Map<String,Integer>columns(SheetRow row){return columns(row,java.util.Collections.emptyMap());}
    private static Map<String,Integer>columns(SheetRow row,Map<String,List<String>>extraAliases){Map<String,Integer>cols=new LinkedHashMap<>();for(Map.Entry<Integer,String>cell:row.cells.entrySet()){String t=AddressClassifier.normalize(cell.getValue());if(t.isEmpty())continue;
        if(t.contains("عنوان")||t.equals("الشارع")||t.equals("شارع")||t.equals("address")||t.equals("street"))put(cols,"address",cell.getKey());
        if(t.contains("اسم الشارع")||t.contains("الشارع الرئيسي")||t.equals("main street")||t.equals("street name"))put(cols,"street_explicit",cell.getKey());
        if(t.equals("القطاع")||t.equals("sector")||t.contains("المنطقه حسب المخطط")||t.contains("حسب المخطط"))put(cols,"sector",cell.getKey());
        if(t.contains("تجميعه")||t.contains("تجميعة")||t.contains("البيوت المتجاوره")||t.contains("البيوت المتجاورة"))put(cols,"grouping",cell.getKey());
        if(t.equals("الاسم")||t.equals("اسم")||t.equals("name")||t.equals("full name")||t.contains("اسم المستفيد")||t.contains("beneficiary"))put(cols,"name",cell.getKey());
        if(t.contains("مبلغ")||t.contains("قيمه")||t.contains("كلفه")||t.contains("amount"))put(cols,"amount",cell.getKey());
        if(t.contains("تسجيل")||t.contains("registration")||t.equals("reg number")||t.equals("reg no")||t.equals("regnumber"))put(cols,"registration",cell.getKey());
        if(t.contains("رقم الملف")||t.equals("file no")||t.equals("file number")||t.equals("fileno"))put(cols,"file_no",cell.getKey());
        if(t.contains("صله القرابه")||t.equals("القرابه")||t.equals("relationship")||t.equals("relation"))put(cols,"relation",cell.getKey());
        if(t.contains("المهندس")||t.contains("الباحث")||t.equals("engineer")||t.contains("engineer name"))put(cols,"engineer",cell.getKey());
        if(t.contains("مع من")||t.contains("مرتبط")||t.contains("اسم رب الاسره")||t.contains("مالك اساسي")||t.contains("المستفيد الرئيسي")||t.equals("related to")||t.equals("related person")||t.contains("main owner"))put(cols,"related_to",cell.getKey());
        for(Map.Entry<String,List<String>> extra:extraAliases.entrySet())if(extra.getValue().contains(t))put(cols,extra.getKey(),cell.getKey());
        boolean phone=t.contains("هاتف")||t.contains("جوال")||t.contains("موبايل")||t.contains("تلفون")||t.contains("phone")||t.contains("mobile");if(phone){boolean second=t.contains("ثاني")||t.contains("الثاني")||t.contains("بديل")||t.contains("اخر")||t.contains("2")||t.contains("secondary")||t.contains("alternate");if(second)put(cols,"phone2",cell.getKey());else if(!cols.containsKey("phone1"))cols.put("phone1",cell.getKey());else put(cols,"phone2",cell.getKey());}}
        return cols;}
    private static void put(Map<String,Integer>m,String k,int v){if(!m.containsKey(k))m.put(k,v);}

    /** Derives the sector from a «تجميعة البيوت المتجاورة» value whose prefix is the sector name (before the first
     * «-»), e.g. «شرق اليرموك 1-جامع البشير-ت1» → «شرق اليرموك 1». Returns the matching authoritative sector name,
     * or "" if none matches. */
    private static String sectorFromGrouping(String grouping){
        if(grouping==null||grouping.trim().isEmpty())return "";
        String head=AddressClassifier.normalize(grouping.split("-")[0]);
        for(String sector:AddressClassifier.SECTOR_ORDER){
            String ns=AddressClassifier.normalize(sector);
            if(head.equals(ns)||head.startsWith(ns)||ns.startsWith(head))return sector;
        }
        // العروبة والتقدم appears as «العروبة والتقدم ومعاليا» in the field file — match its core name.
        if(head.contains("العروبه")&&head.contains("التقدم"))for(String sector:AddressClassifier.SECTOR_ORDER)if(AddressClassifier.normalize(sector).contains("العروبه"))return sector;
        return "";
    }
    private static String phone(String value){String digits=value==null?"":value.trim().replaceAll("\\s+","");if(digits.matches("9\\d{8}"))digits="0"+digits;if(digits.endsWith(".0")&&digits.substring(0,digits.length()-2).matches("\\d+"))digits=digits.substring(0,digits.length()-2);return digits;}

    public static final String AMOUNT_NEEDS_REVIEW="المبلغ يحتاج مراجعة";

    /** Imported amounts show as plain whole-number digits, matching the app's payment display — no thousands separators, no decimals. Returns "" (not the raw text) when the cell can't be read as a real positive amount, so a garbled cell is never mistaken for a valid figure. */
    private static String cleanAmount(String value){
        if(value==null)return "";
        String text=value.trim().replace(",","").replace("$","");
        if(text.isEmpty())return "";
        try{
            double parsed=Double.parseDouble(text);
            if(parsed<=0)return "";
            return String.valueOf(Math.round(parsed));
        }
        catch(NumberFormatException notNumeric){return "";}
    }

    /** A column that exists but couldn't be read for this specific row is flagged for review; a column that simply doesn't exist in the file (e.g. lists that never track money) stays blank as before — not every list is expected to carry an amount. */
    private static String resolveAmount(String rawValue,boolean columnPresent){
        if(!columnPresent)return "";
        String cleaned=cleanAmount(rawValue);
        return cleaned.isEmpty()?AMOUNT_NEEDS_REVIEW:cleaned;
    }
}
