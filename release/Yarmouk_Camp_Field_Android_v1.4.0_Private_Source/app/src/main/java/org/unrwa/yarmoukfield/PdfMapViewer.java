package org.unrwa.yarmoukfield;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Offline full-screen viewer for the two embedded Yarmouk PDF maps. */
public final class PdfMapViewer {
    private static final int NAVY=0xff123B5D,BLUE=0xff009ED2,MUTED=0xff667A89;
    private PdfMapViewer(){}

    public static void show(Activity activity,int rawResource,int initialPage,String title){
        try{new Session(activity,rawResource,initialPage,title).show();}
        catch(Exception error){Toast.makeText(activity,"تعذر فتح المخطط: "+safeMessage(error),Toast.LENGTH_LONG).show();}
    }

    private static final class Session {
        private final Activity activity;private final String baseTitle;private final Dialog dialog;private final ZoomImageView image;
        private final TextView titleView,pageView;private final Button previous,next;private ParcelFileDescriptor descriptor;private PdfRenderer renderer;private Bitmap bitmap;private int pageIndex;

        Session(Activity activity,int rawResource,int initialPage,String title)throws Exception{
            this.activity=activity;this.baseTitle=title;
            File maps=new File(activity.getCacheDir(),"embedded_maps");if(!maps.exists()&&!maps.mkdirs())throw new Exception("تعذر تجهيز مجلد المخططات");
            File source=new File(maps,"map_"+rawResource+".pdf");
            try(InputStream in=activity.getResources().openRawResource(rawResource);FileOutputStream out=new FileOutputStream(source,false)){
                byte[] buffer=new byte[64*1024];int count;while((count=in.read(buffer))>0)out.write(buffer,0,count);
            }
            descriptor=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);if(renderer.getPageCount()==0)throw new Exception("المخطط PDF لا يحتوي صفحات");pageIndex=Math.max(0,Math.min(initialPage,renderer.getPageCount()-1));

            dialog=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar);dialog.setCancelable(true);
            LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xffF4F8FB);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

            LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(8),dp(12),dp(8));header.setBackgroundColor(NAVY);root.addView(header,new LinearLayout.LayoutParams(-1,dp(66)));
            Button close=button("إغلاق",0xffBD4868);close.setOnClickListener(v->dialog.dismiss());header.addView(close,new LinearLayout.LayoutParams(dp(86),dp(46)));
            titleView=label(title,16,Color.WHITE,true);titleView.setGravity(Gravity.CENTER);header.addView(titleView,new LinearLayout.LayoutParams(0,-1,1));

            FrameLayout stage=new FrameLayout(activity);stage.setBackgroundColor(0xffDCE7ED);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
            image=new ZoomImageView(activity);image.setBackgroundColor(Color.WHITE);stage.addView(image,new FrameLayout.LayoutParams(-1,-1));
            TextView hint=label("كبّر بإصبعين • اسحب للتحرك • العرض محدود بالدقة الأصلية لمنع تشوش الشوارع",11,MUTED,true);hint.setGravity(Gravity.CENTER);hint.setBackgroundColor(0xddffffff);FrameLayout.LayoutParams hintParams=new FrameLayout.LayoutParams(-1,dp(34),Gravity.BOTTOM);stage.addView(hint,hintParams);

            LinearLayout controls=new LinearLayout(activity);controls.setGravity(Gravity.CENTER);controls.setPadding(dp(8),dp(7),dp(8),dp(7));controls.setBackgroundColor(Color.WHITE);root.addView(controls,new LinearLayout.LayoutParams(-1,dp(68)));
            previous=button("السابق",NAVY);previous.setOnClickListener(v->load(pageIndex-1));controls.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));
            Button reset=button("ضبط",0xff0E7490);reset.setOnClickListener(v->image.resetZoom());LinearLayout.LayoutParams resetParams=new LinearLayout.LayoutParams(dp(82),dp(48));resetParams.setMargins(dp(6),0,dp(6),0);controls.addView(reset,resetParams);
            pageView=label("",13,NAVY,true);pageView.setGravity(Gravity.CENTER);controls.addView(pageView,new LinearLayout.LayoutParams(dp(92),dp(48)));
            next=button("التالي",BLUE);next.setOnClickListener(v->load(pageIndex+1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));

            dialog.setContentView(root);dialog.setOnDismissListener(v->close());
        }

        void show(){dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);window.setStatusBarColor(0xff0B273C);window.setNavigationBarColor(0xff0B273C);}load(pageIndex);}

        private void load(int requested){if(renderer==null)return;pageIndex=Math.max(0,Math.min(requested,renderer.getPageCount()-1));try{
                image.setImageBitmap(null);if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();bitmap=null;
                PdfRenderer.Page page=renderer.openPage(pageIndex);Bitmap nextBitmap;try{int targetWidth=qualityWidth(page);nextBitmap=render(page,targetWidth);}
                catch(OutOfMemoryError lowMemory){System.gc();nextBitmap=render(page,1900);}finally{page.close();}
                bitmap=nextBitmap;image.setImageBitmap(bitmap);image.resetZoom();
                int count=renderer.getPageCount();pageView.setText("صفحة "+(pageIndex+1)+" / "+count);titleView.setText(baseTitle+(count>1?" — "+(pageIndex+1)+"/"+count:""));previous.setEnabled(pageIndex>0);next.setEnabled(pageIndex<count-1);previous.setAlpha(previous.isEnabled()?1f:.35f);next.setAlpha(next.isEnabled()?1f:.35f);
            }catch(Exception error){Toast.makeText(activity,"تعذر عرض صفحة المخطط: "+safeMessage(error),Toast.LENGTH_LONG).show();}}

        private Bitmap render(PdfRenderer.Page page,int width){int height=Math.max(1,Math.round(width*(page.getHeight()/(float)page.getWidth())));Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);out.eraseColor(Color.WHITE);page.render(out,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return out;}
        private int qualityWidth(PdfRenderer.Page page){int screen=activity.getResources().getDisplayMetrics().widthPixels;ActivityManager manager=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);int memoryClass=manager==null?192:manager.getMemoryClass();long pixelBudget=memoryClass>=256?14_000_000L:memoryClass>=192?11_000_000L:8_000_000L;float ratio=page.getHeight()/(float)page.getWidth();int budgetWidth=(int)Math.sqrt(pixelBudget/Math.max(.2f,ratio));int desired=Math.min(4200,Math.max(2600,screen*6));return Math.max(1900,Math.min(desired,budgetWidth));}
        private void close(){image.setImageBitmap(null);if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();bitmap=null;try{if(renderer!=null)renderer.close();}catch(Exception ignored){}try{if(descriptor!=null)descriptor.close();}catch(Exception ignored){}renderer=null;descriptor=null;}
        private Button button(String text,int color){Button value=new Button(activity);value.setText(text);value.setTextColor(Color.WHITE);value.setTextSize(12);value.setAllCaps(false);value.setTypeface(null,Typeface.BOLD);value.setBackgroundColor(color);return value;}
        private TextView label(String text,int size,int color,boolean bold){TextView value=new TextView(activity);value.setText(text);value.setTextSize(size);value.setTextColor(color);if(bold)value.setTypeface(Typeface.DEFAULT,Typeface.BOLD);value.setPadding(dp(6),0,dp(6),0);return value;}
        private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    }

    /** Matrix-based pinch zoom and drag without an external Android library. */
    private static final class ZoomImageView extends ImageView {
        private final Matrix transform=new Matrix();private final float[] values=new float[9];private final PointF last=new PointF();private final ScaleGestureDetector detector;private float minimumScale=1f,maximumScale=8f;
        ZoomImageView(Activity context){super(context);setScaleType(ScaleType.MATRIX);detector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){if(getDrawable()==null)return false;float current=currentScale();float wanted=Math.max(minimumScale,Math.min(maximumScale,current*d.getScaleFactor()));float factor=wanted/current;transform.postScale(factor,factor,d.getFocusX(),d.getFocusY());constrain();setImageMatrix(transform);return true;}});}
        @Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);post(this::resetZoom);}
        void resetZoom(){if(getDrawable()==null||getWidth()==0||getHeight()==0)return;float dw=getDrawable().getIntrinsicWidth(),dh=getDrawable().getIntrinsicHeight();float scale=Math.min(getWidth()/dw,getHeight()/dh);minimumScale=scale;maximumScale=Math.max(minimumScale,1f);float x=(getWidth()-dw*scale)/2f,y=(getHeight()-dh*scale)/2f;transform.reset();transform.postScale(scale,scale);transform.postTranslate(x,y);setImageMatrix(transform);}
        @Override public boolean onTouchEvent(MotionEvent event){getParent().requestDisallowInterceptTouchEvent(true);detector.onTouchEvent(event);switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:last.set(event.getX(),event.getY());return true;case MotionEvent.ACTION_MOVE:if(!detector.isInProgress()&&event.getPointerCount()==1){float dx=event.getX()-last.x,dy=event.getY()-last.y;transform.postTranslate(dx,dy);constrain();setImageMatrix(transform);last.set(event.getX(),event.getY());}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);return true;default:return true;}}
        private float currentScale(){transform.getValues(values);return values[Matrix.MSCALE_X];}
        private void constrain(){if(getDrawable()==null)return;transform.getValues(values);float scale=values[Matrix.MSCALE_X],tx=values[Matrix.MTRANS_X],ty=values[Matrix.MTRANS_Y];float width=getDrawable().getIntrinsicWidth()*scale,height=getDrawable().getIntrinsicHeight()*scale;float fixedX=width<=getWidth()?(getWidth()-width)/2f:Math.max(getWidth()-width,Math.min(0,tx));float fixedY=height<=getHeight()?(getHeight()-height)/2f:Math.max(getHeight()-height,Math.min(0,ty));transform.postTranslate(fixedX-tx,fixedY-ty);}
    }

    private static String safeMessage(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
