package org.unrwa.yarmoukfield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;

import java.io.FileOutputStream;
import java.util.List;

public final class PrintRouteAdapter extends PrintDocumentAdapter {
    private final Context context;private final String project;private final List<AppDatabase.Beneficiary>rows;private PrintedPdfDocument document;
    public PrintRouteAdapter(Context c,String p,List<AppDatabase.Beneficiary>r){context=c;project=p;rows=r;}
    @Override public void onLayout(PrintAttributes oldAttributes,PrintAttributes newAttributes,CancellationSignal signal,LayoutResultCallback callback,Bundle extras){
        document=new PrintedPdfDocument(context,newAttributes);int pages=Math.max(1,(rows.size()+24)/25);callback.onLayoutFinished(new PrintDocumentInfo.Builder("Yarmouk_Field_Route.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(),true);}
    @Override public void onWrite(PageRange[]ranges,ParcelFileDescriptor destination,CancellationSignal signal,WriteResultCallback callback){
        int pages=Math.max(1,(rows.size()+24)/25);try{for(int page=0;page<pages;page++){if(signal.isCanceled()){callback.onWriteCancelled();return;}PdfDocument.Page pdf=document.startPage(page);draw(pdf.getCanvas(),page,pages);document.finishPage(pdf);}document.writeTo(new FileOutputStream(destination.getFileDescriptor()));callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});}catch(Exception e){callback.onWriteFailed(e.getMessage());}finally{if(document!=null)document.close();}}
    private void draw(Canvas c,int page,int pages){float w=c.getWidth(),h=c.getHeight(),margin=28;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTypeface(android.graphics.Typeface.create("sans",android.graphics.Typeface.BOLD));p.setColor(0xff123B5D);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(18);c.drawText("مشروع المأوى في مخيم اليرموك — المسار الميداني",w/2,34,p);p.setTextSize(11);p.setTypeface(android.graphics.Typeface.DEFAULT);c.drawText(project+"     الصفحة "+(page+1)+" / "+pages,w/2,52,p);
        float top=67,rowH=(h-top-28)/26f;float[]x={w-margin,w*.91f,w*.72f,w*.55f,w*.31f,w*.16f,margin};String[]heads={"م","اسم المستفيد","الهاتف","العنوان","القطاع/الشارع","المبلغ"};p.setStyle(Paint.Style.FILL);p.setColor(0xff0E7490);c.drawRect(margin,top,x[0],top+rowH,p);p.setColor(Color.WHITE);p.setTextSize(9);p.setTypeface(android.graphics.Typeface.create("sans",android.graphics.Typeface.BOLD));for(int i=0;i<heads.length;i++){float center=(x[i]+x[i+1])/2;c.drawText(heads[i],center,top+rowH*.66f,p);}p.setTypeface(android.graphics.Typeface.DEFAULT);
        int start=page*25,end=Math.min(rows.size(),start+25);for(int i=start;i<end;i++){AppDatabase.Beneficiary b=rows.get(i);float y=top+rowH*(i-start+1);p.setColor((i-start)%2==0?0xffF4FAFC:Color.WHITE);c.drawRect(margin,y,x[0],y+rowH,p);p.setColor(0xff15364D);p.setTextSize(8);String[]values={String.valueOf(i+1),fit(b.name,24),fit(b.phone1,14),fit(b.address,34),fit(b.sector+" — "+b.street,32),fit(b.amount,14)};for(int col=0;col<values.length;col++){float center=(x[col]+x[col+1])/2;c.drawText(values[col],center,y+rowH*.64f,p);}p.setColor(0xffD6E6EC);p.setStyle(Paint.Style.STROKE);c.drawRect(margin,y,x[0],y+rowH,p);p.setStyle(Paint.Style.FILL);} }
    private static String fit(String s,int max){if(s==null)return "";return s.length()>max?s.substring(0,max-1)+"…":s;}
}

