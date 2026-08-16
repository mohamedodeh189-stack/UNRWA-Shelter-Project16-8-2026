package org.unrwa.yarmoukfield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Full Yarmouk camp plan with colored sector boundaries and compact tappable labels. */
public final class SectorMapView extends View {
    private static final String[] NAMES={"شرق اليرموك 1","غرب اليرموك 1","شرق اليرموك 2","غرب اليرموك 2","شرق اليرموك 3","غرب اليرموك 3","العروبة والتقدم"};
    private static final float[][] POINTS={{.49f,.20f},{.20f,.22f},{.61f,.47f},{.21f,.50f},{.68f,.64f},{.22f,.66f},{.67f,.83f}};
    private static final int[] COLORS={0xff007F9E,0xff2968B2,0xff149176,0xff7A58B5,0xffC9851C,0xffBD4868,0xff5C8432};
    // Normalized sector envelopes retraced from the user's green boundary markup.
    private static final float[][][] AREAS={
        {{.23f,.035f},{.32f,.055f},{.46f,.20f},{.70f,.36f},{.52f,.385f},{.34f,.39f}},
        {{.11f,.055f},{.23f,.035f},{.34f,.39f},{.22f,.40f},{.08f,.40f}},
        {{.34f,.39f},{.52f,.385f},{.70f,.36f},{.91f,.58f},{.70f,.585f},{.43f,.61f}},
        {{.08f,.40f},{.22f,.40f},{.34f,.39f},{.43f,.61f},{.24f,.615f},{.06f,.62f}},
        {{.43f,.61f},{.70f,.585f},{.91f,.58f},{.91f,.70f},{.72f,.705f},{.49f,.73f}},
        {{.06f,.62f},{.24f,.615f},{.43f,.61f},{.49f,.73f},{.27f,.72f},{.07f,.75f}},
        {{.07f,.75f},{.27f,.72f},{.49f,.73f},{.72f,.705f},{.91f,.70f},{.84f,.95f},{.57f,.98f},{.45f,.76f}}
    };
    public interface OnSectorClickListener {void onSectorClick(int index,String name);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);private final RectF mapRect=new RectF();private final RectF[] badges=new RectF[NAMES.length];private final int touchSlop;private final Bitmap campPlan;private OnSectorClickListener listener;private int selectedSector;private float downX,downY;
    // Heat data: count of imported-list addresses per sector index, drawn as
    // a soft radial glow centered on each sector's badge point.
    private int[] heatCounts;

    /** Density points to focus on — imported-list concentration per sector, index-aligned with SECTOR_ORDER. */
    public void setHeatData(int[] countsBySector){heatCounts=countsBySector;invalidate();}
    public void clearHeatData(){heatCounts=null;invalidate();}
    public SectorMapView(Context context){super(context);touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();campPlan=BitmapFactory.decodeResource(getResources(),R.drawable.yarmouk_camp_overview);for(int i=0;i<badges.length;i++)badges[i]=new RectF();setClickable(true);setFocusable(true);setBackgroundColor(Color.WHITE);setContentDescription("المخطط التنظيمي الكامل لمخيم اليرموك مع بطاقات القطاعات؛ اضغط اسم القطاع لفتح صفحته");}
    public void setOnSectorClickListener(OnSectorClickListener value){listener=value;}
    public void setSelectedSector(int index){selectedSector=Math.max(0,Math.min(index,NAMES.length-1));invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float density=getResources().getDisplayMetrics().density,pad=10*density,w=getWidth(),h=getHeight();if(campPlan==null)return;float scale=Math.min((w-2*pad)/campPlan.getWidth(),(h-2*pad)/campPlan.getHeight());float drawW=campPlan.getWidth()*scale,drawH=campPlan.getHeight()*scale,left=(w-drawW)/2f,top=(h-drawH)/2f;mapRect.set(left,top,left+drawW,top+drawH);canvas.drawColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);paint.setAlpha(210);canvas.drawBitmap(campPlan,null,mapRect,paint);paint.setAlpha(255);
        for(int i=0;i<AREAS.length;i++){Path area=new Path();for(int j=0;j<AREAS[i].length;j++){float px=mapRect.left+AREAS[i][j][0]*mapRect.width(),py=mapRect.top+AREAS[i][j][1]*mapRect.height();if(j==0)area.moveTo(px,py);else area.lineTo(px,py);}area.close();paint.setStyle(Paint.Style.FILL);paint.setColor((COLORS[i]&0x00ffffff)|0x18000000);canvas.drawPath(area,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(i==selectedSector?3.2f*density:1.8f*density);paint.setColor(COLORS[i]);canvas.drawPath(area,paint);}paint.setStyle(Paint.Style.FILL);
        if(heatCounts!=null){int maxCount=1;for(int count:heatCounts)maxCount=Math.max(maxCount,count);float maxRadius=Math.min(mapRect.width(),mapRect.height())*.22f;
            for(int i=0;i<POINTS.length&&i<heatCounts.length;i++){int count=heatCounts[i];if(count<=0)continue;float x=mapRect.left+POINTS[i][0]*mapRect.width(),y=mapRect.top+POINTS[i][1]*mapRect.height();float intensity=count/(float)maxCount;float radius=maxRadius*(0.45f+0.55f*intensity);
                int coreAlpha=(int)(0xE6*intensity),midAlpha=(int)(0x80*intensity);int core=(coreAlpha<<24)|0x00FF3B1E,mid=(midAlpha<<24)|0x00FFB020,outer=0x00FFCC00;
                Paint heat=new Paint(Paint.ANTI_ALIAS_FLAG);heat.setShader(new RadialGradient(x,y,radius,new int[]{core,mid,outer},new float[]{0f,.55f,1f},Shader.TileMode.CLAMP));canvas.drawCircle(x,y,radius,heat);}
        }
        float badgeWidth=Math.min(92*density,mapRect.width()*.27f),badgeHeight=Math.max(25*density,mapRect.width()*.068f),radius=badgeHeight*.32f;paint.setTypeface(Typeface.create("sans",Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(Math.min(9.5f*density,badgeHeight*.34f));
        for(int i=0;i<POINTS.length;i++){float x=mapRect.left+POINTS[i][0]*mapRect.width(),y=mapRect.top+POINTS[i][1]*mapRect.height();RectF badge=badges[i];badge.set(x-badgeWidth/2f,y-badgeHeight/2f,x+badgeWidth/2f,y+badgeHeight/2f);paint.setColor(0x30000000);canvas.drawRoundRect(new RectF(badge.left+density,badge.top+2*density,badge.right+density,badge.bottom+2*density),radius,radius,paint);paint.setColor(COLORS[i]);canvas.drawRoundRect(badge,radius,radius,paint);if(i==selectedSector){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f*density);paint.setColor(0xff00C4EE);canvas.drawRoundRect(new RectF(badge.left-2*density,badge.top-2*density,badge.right+2*density,badge.bottom+2*density),radius+2*density,radius+2*density,paint);paint.setStyle(Paint.Style.FILL);}paint.setColor(Color.WHITE);Paint.FontMetrics metrics=paint.getFontMetrics();float baseline=badge.centerY()-(metrics.ascent+metrics.descent)/2f;canvas.drawText("‏"+(i+1)+" - "+NAMES[i],badge.centerX(),baseline,paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent event){switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:downX=event.getX();downY=event.getY();return true;case MotionEvent.ACTION_UP:float dx=event.getX()-downX,dy=event.getY()-downY;if(dx*dx+dy*dy<=touchSlop*touchSlop){int hit=hitTest(event.getX(),event.getY());if(hit>=0){selectedSector=hit;invalidate();performClick();if(listener!=null)listener.onSectorClick(hit,NAMES[hit]);}}return true;default:return super.onTouchEvent(event);}}
    @Override public boolean performClick(){super.performClick();return true;}
    private int hitTest(float touchX,float touchY){float extra=12*getResources().getDisplayMetrics().density;for(int i=0;i<badges.length;i++){RectF hit=new RectF(badges[i]);hit.inset(-extra,-extra);if(hit.contains(touchX,touchY))return i;}return -1;}
}
