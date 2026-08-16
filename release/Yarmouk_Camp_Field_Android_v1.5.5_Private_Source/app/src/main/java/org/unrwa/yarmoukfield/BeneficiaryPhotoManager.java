package org.unrwa.yarmoukfield;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Creates stable beneficiary/stage folders inside a user-selected document tree. */
public final class BeneficiaryPhotoManager {
    public static final String BEFORE="before",DURING="during",AFTER="after";
    private static final String PREFS="beneficiary_photo_storage",KEY_ROOT="root_tree_uri";
    private final Context context;private final ContentResolver resolver;private final SharedPreferences preferences;private Uri treeUri;

    public static final class Target {
        public final Uri uri;public final String fileName;public final long capturedAt;
        Target(Uri value,String name,long time){uri=value;fileName=name;capturedAt=time;}
    }

    public BeneficiaryPhotoManager(Context context){
        this.context=context.getApplicationContext();resolver=this.context.getContentResolver();preferences=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String saved=preferences.getString(KEY_ROOT,"");if(saved!=null&&!saved.isEmpty())treeUri=Uri.parse(saved);
    }

    public boolean isConfigured(){return treeUri!=null;}
    public Uri rootUri(){return treeUri;}

    public void setRoot(Uri uri,int resultFlags){
        int flags=resultFlags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        resolver.takePersistableUriPermission(uri,flags);treeUri=uri;preferences.edit().putString(KEY_ROOT,uri.toString()).apply();
    }

    public String rootName(){
        if(treeUri==null)return "لم يتم اختيار مجلد";
        Uri root=documentUri(treeUri);try(Cursor c=resolver.query(root,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "مجلد الصور المحدد";
    }

    public Target createTarget(String project,AppDatabase.Beneficiary beneficiary,String phase)throws Exception{
        if(treeUri==null)throw new Exception("اختر مجلد صور المستفيدين أولاً");
        Uri root=documentUri(treeUri);Uri projectFolder=findOrCreateDirectory(root,"المشروع - "+safeName(project));ensureNoMedia(projectFolder);
        String beneficiaryFolder=String.format(Locale.US,"%06d - %s",beneficiary.id,safeName(beneficiary.name));
        Uri personFolder=findOrCreateDirectory(projectFolder,beneficiaryFolder);Uri stageFolder=findOrCreateDirectory(personFolder,phaseFolder(phase));
        long now=System.currentTimeMillis();String fileName=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS",Locale.US).format(new Date(now))+".jpg";
        Uri output=DocumentsContract.createDocument(resolver,stageFolder,"image/jpeg",fileName);if(output==null)throw new Exception("تعذر إنشاء ملف الصورة داخل المجلد المحدد");
        return new Target(output,fileName,now);
    }

    public void delete(Uri uri){try{DocumentsContract.deleteDocument(resolver,uri);}catch(Exception ignored){}}

    public static String phaseLabel(String phase){if(BEFORE.equals(phase))return "قبل الترميم";if(DURING.equals(phase))return "أثناء الترميم";if(AFTER.equals(phase))return "بعد الترميم";return "صور المستفيد";}
    static String phaseFolder(String phase){if(BEFORE.equals(phase))return "01 - قبل الترميم";if(DURING.equals(phase))return "02 - أثناء الترميم";if(AFTER.equals(phase))return "03 - بعد الترميم";return "صور أخرى";}

    static String safeName(String value){
        String clean=value==null?"":value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("\\s+"," ");
        while(clean.endsWith(".")||clean.endsWith(" "))clean=clean.substring(0,clean.length()-1);if(clean.isEmpty())clean="بدون اسم";return clean.length()>70?clean.substring(0,70).trim():clean;
    }

    private Uri documentUri(Uri tree){return DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}

    private Uri findOrCreateDirectory(Uri parent,String name)throws Exception{
        Uri existing=findChild(parent,name);if(existing!=null)return existing;
        Uri created=DocumentsContract.createDocument(resolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,name);if(created==null)throw new Exception("تعذر إنشاء المجلد: "+name);return created;
    }

    private Uri findChild(Uri parent,String name)throws Exception{
        String parentId=DocumentsContract.getDocumentId(parent);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,parentId);
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
        try(Cursor c=resolver.query(children,projection,null,null,null)){
            while(c!=null&&c.moveToNext())if(name.equals(c.getString(1))){if(!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))throw new Exception("يوجد ملف باسم المجلد المطلوب: "+name);return DocumentsContract.buildDocumentUriUsingTree(treeUri,c.getString(0));}
        }
        return null;
    }

    /** Prevents sensitive dwelling photos from being indexed by gallery apps. */
    private void ensureNoMedia(Uri projectFolder){
        try{String parentId=DocumentsContract.getDocumentId(projectFolder);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,parentId);try(Cursor c=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){while(c!=null&&c.moveToNext())if(".nomedia".equals(c.getString(0)))return;}DocumentsContract.createDocument(resolver,projectFolder,"application/octet-stream",".nomedia");}catch(Exception ignored){}
    }
}
