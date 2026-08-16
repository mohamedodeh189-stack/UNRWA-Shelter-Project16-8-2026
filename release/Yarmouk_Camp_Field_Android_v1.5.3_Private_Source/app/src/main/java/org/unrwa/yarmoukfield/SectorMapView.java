package org.unrwa.yarmoukfield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Full Yarmouk camp plan with polished, tappable sector badges. */
public final class SectorMapView extends View {
    private static final String[] NAMES={"شرق اليرموك 1","غرب اليرموك 1","شرق اليرموك 2","غرب اليرموك 2","شرق اليرموك 3","غرب اليرموك 3","العروبة والتقدم"};
    private static final float[][] POINTS={{.55f,.19f},{.23f,.22f},{.58f,.39f},{.25f,.42f},{.66f,.57f},{.28f,.60f},{.65f,.80f}};
    private static final int[] COLORS={0xff007F9E,0xff2968B2,0xff149176,0xff7A58B5,0xffC9851C,0xffBD4868,0xff5C8432};
    public interface OnSectorClickListener {void onSectorClick(int index,String name);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);private final RectF mapRect=new RectF();private final RectF[] badges=new RectF[NAMES.length];private final int touchSlop;private final Bitmap campPlan;private OnSectorClickListener listener;private int selectedSector;private float downX,downY;
    public SectorMapView(Context context){super(context);touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();campPlan=BitmapFactory.decodeResource(getResources(),R.drawable.yarmouk_camp_overview);for(int i=0;i<badges.length;i++)badges[i]=new RectF();setClickable(true);setFocusable(true);setBackgroundColor(Color.WHITE);setContentDescription("المخطط التنظيمي الكامل لمخيم اليرموك مع بطاقات القطاعات؛ اضغط اسم القطاع لفتح صفحته");}
    public void setOnSectorClickListener(OnSectorClickListener value){listener=value;}
    public void setSelectedSector(int index){selectedSector=Math.max(0,Math.min(index,NAMES.length-1));invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float density=getResources().getDisplayMetrics().density,pad=10*density,w=getWidth(),h=getHeight();if(campPlan==null)return;float scale=Math.min((w-2*pad)/campPlan.getWidth(),(h-2*pad)/campPlan.getHeight());float drawW=campPlan.getWidth()*scale,drawH=campPlan.getHeight()*scale,left=(w-drawW)/2f,top=(h-drawH)/2f;mapRect.set(left,top,left+drawW,top+drawH);canvas.drawColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);paint.setAlpha(210);canvas.drawBitmap(campPlan,null,mapRect,paint);paint.setAlpha(255);
        float badgeWidth=Math.min(118*density,mapRect.width()*.34f),badgeHeight=Math.max(34*density,mapRect.width()*.095f),radius=badgeHeight*.36f;paint.setTypeface(Typeface.create("sans",Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(Math.min(13*density,badgeHeight*.36f));
        for(int i=0;i<POINTS.length;i++){float x=mapRect.left+POINTS[i][0]*mapRect.width(),y=mapRect.top+POINTS[i][1]*mapRect.height();RectF badge=badges[i];badge.set(x-badgeWidth/2f,y-badgeHeight/2f,x+badgeWidth/2f,y+badgeHeight/2f);paint.setColor(0x38000000);canvas.drawRoundRect(new RectF(badge.left+2*density,badge.top+3*density,badge.right+2*density,badge.bottom+3*density),radius,radius,paint);paint.setColor(COLORS[i]);canvas.drawRoundRect(badge,radius,radius,paint);if(i==selectedSector){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setColor(0xff00C4EE);canvas.drawRoundRect(new RectF(badge.left-3*density,badge.top-3*density,badge.right+3*density,badge.bottom+3*density),radius+3*density,radius+3*density,paint);paint.setStyle(Paint.Style.FILL);}paint.setColor(Color.WHITE);Paint.FontMetrics metrics=paint.getFontMetrics();float baseline=badge.centerY()-(metrics.ascent+metrics.descent)/2f;canvas.drawText(NAMES[i]+"  "+(i+1),badge.centerX(),baseline,paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent event){switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:downX=event.getX();downY=event.getY();return true;case MotionEvent.ACTION_UP:float dx=event.getX()-downX,dy=event.getY()-downY;if(dx*dx+dy*dy<=touchSlop*touchSlop){int hit=hitTest(event.getX(),event.getY());if(hit>=0){selectedSector=hit;invalidate();performClick();if(listener!=null)listener.onSectorClick(hit,NAMES[hit]);}}return true;default:return super.onTouchEvent(event);}}
    @Override public boolean performClick(){super.performClick();return true;}
    private int hitTest(float touchX,float touchY){float extra=12*getResources().getDisplayMetrics().density;for(int i=0;i<badges.length;i++){RectF hit=new RectF(badges[i]);hit.inset(-extra,-extra);if(hit.contains(touchX,touchY))return i;}return -1;}
}
