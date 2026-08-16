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
            if(!target.address.trim().isEmpty()&&!"غير مصنف".equals(current.sector))continue;
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
                    AddressClassifier.Match dm=classifier.classify(donor.address);if("غير مصنف".equals(dm.sector))continue;
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

    private static String queryName(Context context,Uri uri){
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
            List<String> shared=readSharedStrings(zip);List<ZipEntry>sheets=new ArrayList<>();
            zip.stream().filter(e->e.getName().matches("xl/worksheets/sheet\\d+\\.xml")).forEach(sheets::add);
            Collections.sort(sheets,Comparator.comparing(ZipEntry::getName));Result result=new Result();
            for(ZipEntry sheet:sheets){List<SheetRow> matrix=parseSheet(zip.getInputStream(sheet),shared);Result part=convert(matrix);
                result.rows.addAll(part.rows);result.warnings.addAll(part.warnings);}
            if(result.rows.isEmpty())result.warnings.add("لم يتم العثور على جدول يحتوي عمود عنوان أو اسم مستفيد.");return result;
        }finally{temp.delete();}
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
            b.phone2=phone(first(o,"phone2","الهاتف الثاني"));b.address=first(o,"address","العنوان");b.amount=first(o,"amount","master_amount","المبلغ");
            b.visitStatus=first(o,"visit_status");if(b.visitStatus.isEmpty())b.visitStatus="pending";b.notes=first(o,"notes","ملاحظات");
            if(result.backup){b.workflowStage=first(o,"workflow_stage");if(b.workflowStage.isEmpty())b.workflowStage="followup";b.studyStatus=first(o,"study_status");if(b.studyStatus.isEmpty())b.studyStatus="approved";b.damageNotes=first(o,"damage_notes");b.quantitiesNotes=first(o,"quantities_notes");b.drawingUri=first(o,"drawing_uri");b.followupBatch=first(o,"followup_batch");b.approvedAt=o.optLong("approved_at",0);b.progressPercent=Math.max(0,Math.min(100,o.optInt("progress_percent",0)));JSONArray quantities=o.optJSONArray("quantities");if(quantities!=null)for(int q=0;q<quantities.length();q++){JSONObject item=quantities.optJSONObject(q);if(item==null)continue;int itemNo=item.optInt("item_no");double quantity=item.optDouble("quantity");double unitPrice=item.optDouble("unit_price");if(itemNo>0&&quantity>0)b.quantities.add(new AppDatabase.QuantityEntry(itemNo,quantity,unitPrice));}}
            if(!b.name.isEmpty()||!b.address.isEmpty())result.rows.add(b);}return result;
    }
    private static String first(JSONObject o,String...keys){for(String k:keys){Object v=o.opt(k);if(v!=null&&v!=JSONObject.NULL&&!String.valueOf(v).trim().isEmpty())return String.valueOf(v).trim();}return "";}

    private static Result convert(List<SheetRow>matrix){Result result=new Result();if(matrix.isEmpty())return result;int header=-1;Map<String,Integer>cols=null;int best=-1;
        for(int r=0;r<Math.min(20,matrix.size());r++){Map<String,Integer>candidate=columns(matrix.get(r));int score=(candidate.containsKey("address")?5:0)+(candidate.containsKey("name")?3:0)+(candidate.containsKey("amount")?2:0);
            if(score>best){best=score;header=r;cols=candidate;}}
        if(cols==null||best<2){result.warnings.add("ورقة بلا رؤوس واضحة وتم تجاوزها.");return result;}
        for(int r=header+1;r<matrix.size();r++){SheetRow row=matrix.get(r);AppDatabase.Beneficiary b=new AppDatabase.Beneficiary();b.sequence=r-header;
            b.name=get(row,cols,"name");b.registration=get(row,cols,"registration");b.fileNo=get(row,cols,"file_no");b.relation=get(row,cols,"relation");b.relatedTo=get(row,cols,"related_to");
            b.phone1=phone(get(row,cols,"phone1"));b.phone2=phone(get(row,cols,"phone2"));b.address=get(row,cols,"address");b.amount=get(row,cols,"amount");
            if(!b.name.isEmpty()||!b.address.isEmpty())result.rows.add(b);}return result;}
    private static String get(SheetRow row,Map<String,Integer>cols,String key){Integer col=cols.get(key);return col==null?"":row.get(col).trim();}
    private static Map<String,Integer>columns(SheetRow row){Map<String,Integer>cols=new LinkedHashMap<>();for(Map.Entry<Integer,String>cell:row.cells.entrySet()){String t=AddressClassifier.normalize(cell.getValue());if(t.isEmpty())continue;
        if(t.contains("عنوان")||t.equals("الشارع")||t.equals("شارع")||t.equals("address")||t.equals("street"))put(cols,"address",cell.getKey());
        if(t.equals("الاسم")||t.equals("اسم")||t.equals("name")||t.equals("full name")||t.contains("اسم المستفيد")||t.contains("beneficiary"))put(cols,"name",cell.getKey());
        if(t.contains("مبلغ")||t.contains("قيمه")||t.contains("كلفه")||t.contains("amount"))put(cols,"amount",cell.getKey());
        if(t.contains("تسجيل")||t.contains("registration")||t.equals("reg number")||t.equals("reg no")||t.equals("regnumber"))put(cols,"registration",cell.getKey());
        if(t.contains("رقم الملف")||t.equals("file no")||t.equals("file number")||t.equals("fileno"))put(cols,"file_no",cell.getKey());
        if(t.contains("صله القرابه")||t.equals("القرابه")||t.equals("relationship")||t.equals("relation"))put(cols,"relation",cell.getKey());
        if(t.contains("مع من")||t.contains("مرتبط")||t.contains("اسم رب الاسره")||t.contains("مالك اساسي")||t.contains("المستفيد الرئيسي")||t.equals("related to")||t.equals("related person")||t.contains("main owner"))put(cols,"related_to",cell.getKey());
        boolean phone=t.contains("هاتف")||t.contains("جوال")||t.contains("موبايل")||t.contains("تلفون")||t.contains("phone")||t.contains("mobile");if(phone){boolean second=t.contains("ثاني")||t.contains("الثاني")||t.contains("بديل")||t.contains("اخر")||t.contains("2")||t.contains("secondary")||t.contains("alternate");if(second)put(cols,"phone2",cell.getKey());else if(!cols.containsKey("phone1"))cols.put("phone1",cell.getKey());else put(cols,"phone2",cell.getKey());}}
        return cols;}
    private static void put(Map<String,Integer>m,String k,int v){if(!m.containsKey(k))m.put(k,v);}
    private static String phone(String value){String digits=value==null?"":value.trim().replaceAll("\\s+","");if(digits.matches("9\\d{8}"))digits="0"+digits;if(digits.endsWith(".0")&&digits.substring(0,digits.length()-2).matches("\\d+"))digits=digits.substring(0,digits.length()-2);return digits;}
}
