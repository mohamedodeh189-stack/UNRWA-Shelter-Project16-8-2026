package org.unrwa.yarmoukfield;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The 37 BOQ rows copied from Reference.xlsx / sheet "الكميات" / B7:J43. */
public final class QuantityCatalog {
    public static final double FIRST_PAYMENT_RATIO = 0.60;
    public static final double SECOND_PAYMENT_RATIO = 0.40;

    public static final class Item {
        public final int number;
        public final String category, name, unit, description, reason;
        public final double unitPrice;

        Item(int number,String category,String name,String unit,double unitPrice,String description,String reason){
            this.number=number;this.category=category;this.name=name;this.unit=unit;this.unitPrice=unitPrice;
            this.description=description;this.reason=reason;
        }
    }

    private static List<Item> cached;
    private QuantityCatalog(){}

    public static synchronized List<Item> load(Context context)throws Exception{
        if(cached!=null)return cached;
        String json;
        try(InputStream input=context.getAssets().open("boq_reference.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[]buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            json=output.toString(StandardCharsets.UTF_8.name());
        }
        JSONArray array=new JSONObject(json).getJSONArray("items");List<Item>items=new ArrayList<>();
        for(int i=0;i<array.length();i++){
            JSONObject value=array.getJSONObject(i);items.add(new Item(value.getInt("number"),value.optString("category"),value.optString("name"),
                    value.optString("unit"),value.getDouble("unitPrice"),value.optString("description"),value.optString("reason")));
        }
        if(items.size()!=37)throw new IllegalStateException("يجب أن يحتوي مرجع الكميات على 37 بنداً");
        for(int i=0;i<items.size();i++)if(items.get(i).number!=i+1)throw new IllegalStateException("ترقيم بنود الكميات غير متسلسل");
        cached=Collections.unmodifiableList(items);return cached;
    }

    public static String money(double value){
        DecimalFormat format=new DecimalFormat("0.##",DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(true);format.setGroupingSize(3);return format.format(value);
    }

    /** Quantity inputs must never contain thousands separators because a comma can mean a decimal on some phones. */
    public static String quantityNumber(double value){
        DecimalFormat format=new DecimalFormat("0.###",DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(false);return format.format(value);
    }

    public static long firstPayment(double total){long roundedTotal=Math.round(total);return Math.round(roundedTotal*FIRST_PAYMENT_RATIO);}
    public static long secondPayment(double total){return Math.round(total)-firstPayment(total);}
}
