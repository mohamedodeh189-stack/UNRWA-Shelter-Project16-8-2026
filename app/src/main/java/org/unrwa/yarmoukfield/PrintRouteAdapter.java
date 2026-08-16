package org.unrwa.yarmoukfield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
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
    private final Context context;private final String project,credit;private final List<AppDatabase.Beneficiary>rows;private PrintedPdfDocument document;
    public PrintRouteAdapter(Context context,String project,String credit,List<AppDatabase.Beneficiary>rows){this.context=context;this.project=project;this.credit=credit;this.rows=rows;}

    @Override public void onLayout(PrintAttributes oldAttributes,PrintAttributes newAttributes,CancellationSignal signal,LayoutResultCallback callback,Bundle extras){
        if(signal.isCanceled()){callback.onLayoutCancelled();return;}if(document!=null)document.close();document=new PrintedPdfDocument(context,newAttributes);int pages=pageCount();
        PrintDocumentInfo info=new PrintDocumentInfo.Builder("Yarmouk_Field_Route.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build();callback.onLayoutFinished(info,!newAttributes.equals(oldAttributes));
    }

    @Override public void onWrite(PageRange[] ranges,ParcelFileDescriptor destination,CancellationSignal signal,WriteResultCallback callback){
        if(document==null){callback.onWriteFailed("لم يتم تجهيز مستند الطباعة");return;}int pages=pageCount();try{for(int page=0;page<pages;page++){if(signal.isCanceled()){callback.onWriteCancelled();return;}if(!requested(ranges,page))continue;PdfDocument.Page pdf=document.startPage(page);draw(pdf.getCanvas(),page,pages);document.finishPage(pdf);}document.writeTo(new FileOutputStream(destination.getFileDescriptor()));callback.onWriteFinished(ranges==null||ranges.length==0?new PageRange[]{PageRange.ALL_PAGES}:ranges);}catch(Exception error){callback.onWriteFailed(error.getMessage());}finally{document.close();document=null;}}

    private int pageCount(){return Math.max(1,(rows.size()+24)/25);}
    private static boolean requested(PageRange[] ranges,int page){if(ranges==null||ranges.length==0)return true;for(PageRange range:ranges)if(page>=range.getStart()&&page<=range.getEnd())return true;return false;}

    private void draw(Canvas canvas,int page,int pages){float width=canvas.getWidth(),height=canvas.getHeight(),margin=28;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff123B5D);canvas.drawRoundRect(new RectF(margin,12,width-margin,80),8,8,paint);
        int logoId=context.getResources().getIdentifier("unrwa_logo","drawable",context.getPackageName());Bitmap logo=logoId==0?null:BitmapFactory.decodeResource(context.getResources(),logoId);if(logo!=null){canvas.drawBitmap(logo,null,new RectF(width-margin-58,18,width-margin-8,74),paint);logo.recycle();}
        float center=(width/2)-18;paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans",Typeface.BOLD));paint.setTextSize(15);canvas.drawText("مشروع المأوى في الأونروا — المسار الميداني",center,31,paint);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(8.7f);canvas.drawText("وكالة غوث وتشغيل اللاجئين الفلسطينيين في سوريا",center,46,paint);paint.setTypeface(Typeface.create("sans",Typeface.BOLD));paint.setTextSize(7.8f);canvas.drawText(credit,center,59,paint);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(8.5f);canvas.drawText(project+"     الصفحة "+(page+1)+" / "+pages,center,73,paint);

        float top=87,rowHeight=(height-top-24)/26f;float[]x={width-margin,width*.91f,width*.72f,width*.55f,width*.31f,width*.16f,margin};String[]heads={"م","اسم المستفيد","الهاتف","العنوان","القطاع/الشارع","المبلغ"};paint.setColor(0xff0E7490);canvas.drawRect(margin,top,x[0],top+rowHeight,paint);paint.setColor(Color.WHITE);paint.setTextSize(9);paint.setTypeface(Typeface.create("sans",Typeface.BOLD));for(int i=0;i<heads.length;i++){float columnCenter=(x[i]+x[i+1])/2;canvas.drawText(heads[i],columnCenter,top+rowHeight*.66f,paint);}paint.setTypeface(Typeface.DEFAULT);
        int start=page*25,end=Math.min(rows.size(),start+25);for(int i=start;i<end;i++){AppDatabase.Beneficiary beneficiary=rows.get(i);float y=top+rowHeight*(i-start+1);paint.setColor((i-start)%2==0?0xffF4FAFC:Color.WHITE);canvas.drawRect(margin,y,x[0],y+rowHeight,paint);paint.setColor(0xff15364D);paint.setTextSize(8);String[]values={String.valueOf(i+1),fit(beneficiary.name,24),fit(beneficiary.phone1,14),fit(beneficiary.address,34),fit(beneficiary.sector+" — "+beneficiary.street,32),fit(beneficiary.amount,14)};for(int column=0;column<values.length;column++){float columnCenter=(x[column]+x[column+1])/2;canvas.drawText(values[column],columnCenter,y+rowHeight*.64f,paint);}paint.setColor(0xffD6E6EC);paint.setStyle(Paint.Style.STROKE);canvas.drawRect(margin,y,x[0],y+rowHeight,paint);paint.setStyle(Paint.Style.FILL);}
    }

    private static String fit(String value,int max){if(value==null)return "";return value.length()>max?value.substring(0,max-1)+"…":value;}
}
