package org.unrwa.yarmoukfield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Arrays;
import java.util.List;

/** Lightweight schematic using sector-label coordinates extracted from the supplied DXF. */
public final class SectorMapView extends View {
    private static final String[] NAMES={"شرق اليرموك 1","غرب اليرموك 1","شرق اليرموك 2","غرب اليرموك 2","شرق اليرموك 3","غرب اليرموك 3","العروبة والتقدم"};
    private static final float[][] POINTS={{.62f,.08f},{.24f,.24f},{.76f,.33f},{.21f,.48f},{.88f,.59f},{.21f,.69f},{.78f,.91f}};
    private static final int[] COLORS={0xff007F9E,0xff2968B2,0xff149176,0xff7A58B5,0xffC9851C,0xffBD4868,0xff5C8432};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path route=new Path();
    public SectorMapView(Context context){super(context);setBackgroundColor(0xffF4FAFC);setContentDescription("مخطط قطاعات مخيم اليرموك المستخرج من DXF");}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight(),pad=22*getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(0xff87BFD1);route.reset();
        for(int i=0;i<POINTS.length;i++){float x=pad+POINTS[i][0]*(w-2*pad),y=pad+POINTS[i][1]*(h-2*pad);if(i==0)route.moveTo(x,y);else route.lineTo(x,y);}canvas.drawPath(route,paint);
        float radius=Math.min(w,h)*.055f;paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(Math.max(20,w*.025f));paint.setTypeface(android.graphics.Typeface.create("sans",android.graphics.Typeface.BOLD));
        for(int i=0;i<POINTS.length;i++){float x=pad+POINTS[i][0]*(w-2*pad),y=pad+POINTS[i][1]*(h-2*pad);paint.setStyle(Paint.Style.FILL);paint.setColor(COLORS[i]);canvas.drawCircle(x,y,radius,paint);paint.setColor(Color.WHITE);canvas.drawText(String.valueOf(i+1),x,y+paint.getTextSize()*.35f,paint);
            paint.setColor(0xff123B5D);paint.setTextSize(Math.max(17,w*.021f));float labelX=i%2==0?x-radius-8:x+radius+8;paint.setTextAlign(i%2==0?Paint.Align.RIGHT:Paint.Align.LEFT);canvas.drawText(NAMES[i],labelX,y+paint.getTextSize()*.3f,paint);}
    }
}

