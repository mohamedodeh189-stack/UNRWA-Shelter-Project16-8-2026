package org.unrwa.yarmoukfield;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int NAV_HOME=0,NAV_PEOPLE=1,NAV_ROUTE=2,NAV_GUIDE=3,NAV_SETTINGS=4;
    private static final int REQUEST_IMPORT=4101,REQUEST_BACKUP=4102;
    private static final int NAVY=0xff123B5D,BLUE=0xff009ED2,SURFACE=0xffF4F8FB,TEXT=0xff15364D,MUTED=0xff667A89;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AppDatabase db;private AddressClassifier classifier;private LinearLayout root,header,bottom;private FrameLayout content;
    private Spinner projectSpinner;private TextView projectCaption;private String currentProject;private long currentProjectId;private int activePage=NAV_HOME;
    private String pendingBackup;

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(0xff0B273C);getWindow().setNavigationBarColor(0xff0B273C);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);db=new AppDatabase(this);try{classifier=new AddressClassifier(this);}catch(Exception e){fatal("تعذر تحميل دليل العناوين",e);return;}
        currentProject=getPreferences(MODE_PRIVATE).getString("current_project",AppDatabase.DEFAULT_PROJECT);currentProjectId=db.ensureProject(currentProject);buildShell();refreshProjects();showPage(NAV_HOME);}
    @Override protected void onDestroy(){worker.shutdownNow();if(db!=null)db.close();super.onDestroy();}

    private void buildShell(){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(SURFACE);setContentView(root);
        header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(10),dp(16),dp(10));header.setBackgroundColor(NAVY);root.addView(header,new LinearLayout.LayoutParams(-1,dp(92)));
        ImageView logo=new ImageView(this);logo.setImageResource(getResources().getIdentifier("unrwa_logo","drawable",getPackageName()));logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);header.addView(logo,new LinearLayout.LayoutParams(dp(72),dp(72)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(10),0,dp(10),0);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text("مشروع المأوى في مخيم اليرموك",20,Color.WHITE,true);titles.addView(title);projectCaption=text("Yarmouk Camp — Android Field Edition",11,0xffC8EAF5,false);titles.addView(projectCaption);
        LinearLayout projectBox=new LinearLayout(this);projectBox.setOrientation(LinearLayout.VERTICAL);projectBox.setGravity(Gravity.CENTER);header.addView(projectBox,new LinearLayout.LayoutParams(dp(150),-1));
        TextView label=text("المشروع الحالي",10,0xffC8EAF5,false);projectBox.addView(label);projectSpinner=new Spinner(this);projectSpinner.setBackground(round(0xffF2F7FA,12,0,0));projectBox.addView(projectSpinner,new LinearLayout.LayoutParams(-1,dp(42)));
        content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER);bottom.setPadding(dp(5),dp(5),dp(5),dp(5));bottom.setBackgroundColor(Color.WHITE);root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(70)));
        String[]names={"⌂\nالرئيسية","☷\nالمستفيدون","➤\nالمسار","⌖\nالدليل","⚙\nالإعدادات"};for(int i=0;i<names.length;i++){final int page=i;Button b=new Button(this);b.setText(names[i]);b.setTextSize(11);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setPadding(0,0,0,0);b.setTag(i);b.setOnClickListener(v->showPage(page));bottom.addView(b,new LinearLayout.LayoutParams(0,-1,1));}}

    private void refreshProjects(){List<String>names=db.listProjects();ArrayAdapter<String>adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names){@Override public View getView(int p,View c,ViewGroup parent){TextView v=(TextView)super.getView(p,c,parent);v.setTextColor(NAVY);v.setGravity(Gravity.CENTER);v.setTextSize(12);return v;}};projectSpinner.setAdapter(adapter);int selected=Math.max(0,names.indexOf(currentProject));projectSpinner.setSelection(selected,false);
        projectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean ready;@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){if(!ready){ready=true;return;}currentProject=String.valueOf(p.getItemAtPosition(pos));currentProjectId=db.projectId(currentProject);getPreferences(MODE_PRIVATE).edit().putString("current_project",currentProject).apply();renderActive();}});}

    private void showPage(int page){activePage=page;renderActive();for(int i=0;i<bottom.getChildCount();i++){Button b=(Button)bottom.getChildAt(i);boolean active=(Integer)b.getTag()==page;b.setTextColor(active?Color.WHITE:MUTED);b.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);b.setBackground(active?round(BLUE,14,0,0):round(Color.TRANSPARENT,14,0,0));}}
    private void renderActive(){content.removeAllViews();if(activePage==NAV_HOME)showHome();else if(activePage==NAV_PEOPLE)showPeople(false);else if(activePage==NAV_ROUTE)showPeople(true);else if(activePage==NAV_GUIDE)showGuide();else showSettings();}

    private void showHome(){FrameLayout frame=new FrameLayout(this);content.addView(frame,new FrameLayout.LayoutParams(-1,-1));ImageView mark=new ImageView(this);mark.setImageResource(computeResource("ic_watermark","drawable"));mark.setAlpha(.055f);mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(340),dp(340),Gravity.CENTER);frame.addView(mark,mp);
        ScrollView scroll=new ScrollView(this);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout box=column(dp(16));scroll.addView(box,new ScrollView.LayoutParams(-1,-2));
        TextView greeting=text("مرحباً بك في مركز العمل الميداني",22,NAVY,true);box.addView(greeting);TextView subtitle=text("قائمة مرتبة، عناوين أوضح، ومتابعة ميدانية أسرع — تعمل دون إنترنت",13,MUTED,false);subtitle.setPadding(0,dp(4),0,dp(14));box.addView(subtitle);
        AppDatabase.Stats s=db.stats(currentProjectId);LinearLayout stats=new LinearLayout(this);stats.setOrientation(LinearLayout.HORIZONTAL);box.addView(stats,new LinearLayout.LayoutParams(-1,-2));stats.addView(stat("إجمالي المستفيدين",s.total,NAVY),new LinearLayout.LayoutParams(0,dp(108),1));stats.addView(stat("بانتظار الزيارة",s.pending,0xffC9851C),new LinearLayout.LayoutParams(0,dp(108),1));
        LinearLayout stats2=new LinearLayout(this);stats2.setOrientation(LinearLayout.HORIZONTAL);box.addView(stats2,new LinearLayout.LayoutParams(-1,-2));stats2.addView(stat("تمت الزيارة",s.completed,0xff149176),new LinearLayout.LayoutParams(0,dp(108),1));stats2.addView(stat("بحاجة لمراجعة",s.review,0xffBD4868),new LinearLayout.LayoutParams(0,dp(108),1));
        TextView actionsTitle=text("إجراءات سريعة",17,NAVY,true);actionsTitle.setPadding(0,dp(18),0,dp(8));box.addView(actionsTitle);
        box.addView(action("استيراد قائمة Excel / CSV / JSON","يتعرّف على الأعمدة ويرتب العناوين تلقائياً",BLUE,v->chooseImport()));
        box.addView(action("بدء المسار الميداني","عرض المستفيدين بالترتيب الأقرب فالأقرب",0xff149176,v->showPage(NAV_ROUTE)));
        box.addView(action("دليل قطاعات مخيم اليرموك","المخطط المرجعي والشوارع داخل كل قطاع",NAVY,v->showPage(NAV_GUIDE)));
    }

    private View stat(String label,int value,int color){LinearLayout card=column(dp(12));card.setGravity(Gravity.CENTER);card.setBackground(round(Color.WHITE,18,0xffDCE9EF,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(108),1);p.setMargins(dp(5),dp(5),dp(5),dp(5));card.setLayoutParams(p);TextView number=text(String.valueOf(value),28,color,true);number.setGravity(Gravity.CENTER);card.addView(number);TextView caption=text(label,12,MUTED,true);caption.setGravity(Gravity.CENTER);card.addView(caption);return card;}
    private View action(String title,String subtitle,int color,View.OnClickListener click){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(12));card.setBackground(round(Color.WHITE,18,0xffDCE9EF,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(82));p.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(p);TextView icon=text("←",24,color,true);icon.setGravity(Gravity.CENTER);card.addView(icon,new LinearLayout.LayoutParams(dp(44),-1));LinearLayout labels=column(0);labels.addView(text(title,15,NAVY,true));labels.addView(text(subtitle,11,MUTED,false));card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));card.setOnClickListener(click);return card;}

    private void showPeople(boolean route){LinearLayout page=column(dp(12));content.addView(page,new FrameLayout.LayoutParams(-1,-1));LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);page.addView(titleRow,new LinearLayout.LayoutParams(-1,dp(55)));titleRow.addView(text(route?"المسار الميداني":"المستفيدون",21,NAVY,true),new LinearLayout.LayoutParams(0,-2,1));
        if(route){Button print=smallButton("طباعة A4 — 25 اسماً",NAVY);print.setOnClickListener(v->printRoute());titleRow.addView(print);}else{Button add=smallButton("استيراد",BLUE);add.setOnClickListener(v->chooseImport());titleRow.addView(add);}
        EditText search=new EditText(this);search.setHint("ابحث بالاسم أو العنوان أو الهاتف أو رقم التسجيل");search.setSingleLine(true);search.setTextSize(14);search.setPadding(dp(14),0,dp(14),0);search.setBackground(round(Color.WHITE,14,0xffD6E5EB,1));page.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        LinearLayout filterRow=new LinearLayout(this);filterRow.setGravity(Gravity.CENTER_VERTICAL);filterRow.setPadding(0,dp(6),0,dp(6));page.addView(filterRow,new LinearLayout.LayoutParams(-1,dp(54)));TextView hint=text(route?"مرتب حسب القطاع والشارع والجادة":"تصفية حالة الزيارة",12,MUTED,true);filterRow.addView(hint,new LinearLayout.LayoutParams(0,-2,1));Spinner filter=new Spinner(this);String[]filterNames=route?new String[]{"غير المنجز","الكل","تمت الزيارة","بحاجة لمراجعة"}:new String[]{"الكل","بانتظار الزيارة","تمت الزيارة","بحاجة لمراجعة","تعذر الوصول"};String[]filterValues=route?new String[]{"pending","all","completed","review"}:new String[]{"all","pending","completed","review","unreachable"};ArrayAdapter<String>fa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,filterNames);filter.setAdapter(fa);filter.setBackground(round(Color.WHITE,12,0xffD6E5EB,1));filterRow.addView(filter,new LinearLayout.LayoutParams(dp(150),dp(44)));
        FrameLayout listBox=new FrameLayout(this);page.addView(listBox,new LinearLayout.LayoutParams(-1,0,1));ListView list=new ListView(this);list.setDividerHeight(dp(7));list.setDivider(new android.graphics.drawable.ColorDrawable(SURFACE));list.setPadding(0,dp(3),0,dp(12));list.setClipToPadding(false);listBox.addView(list,new FrameLayout.LayoutParams(-1,-1));TextView empty=text(route?"لا توجد زيارات ضمن هذا الفلتر":"لا توجد نتائج",16,MUTED,true);empty.setGravity(Gravity.CENTER);listBox.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        Runnable refresh=()->{String state=filterValues[filter.getSelectedItemPosition()];List<AppDatabase.Beneficiary>rows=db.list(currentProjectId,search.getText().toString(),state);list.setAdapter(new BeneficiaryAdapter(rows,route));};
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});search.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}@Override public void onTextChanged(CharSequence s,int st,int b,int c){refresh.run();}@Override public void afterTextChanged(Editable e){}});refresh.run();}

    private final class BeneficiaryAdapter extends BaseAdapter{private final List<AppDatabase.Beneficiary>rows;private final boolean route;BeneficiaryAdapter(List<AppDatabase.Beneficiary>r,boolean field){rows=r;route=field;}@Override public int getCount(){return rows.size();}@Override public Object getItem(int p){return rows.get(p);}@Override public long getItemId(int p){return rows.get(p).id;}
        @Override public View getView(int position,View convert,ViewGroup parent){AppDatabase.Beneficiary b=rows.get(position);LinearLayout card=column(dp(13));card.setBackground(round(Color.WHITE,16,statusColor(b.visitStatus),2));card.setMinimumHeight(dp(118));TextView name=text((route?(position+1)+". ":"")+safe(b.name,"مستفيد بلا اسم"),16,NAVY,true);card.addView(name);TextView sector=text(b.sector+(b.street.isEmpty()?"":" — "+shortStreet(b.street)),12,0xff0E7490,true);sector.setPadding(0,dp(4),0,0);card.addView(sector);TextView addr=text(safe(b.address,"العنوان بحاجة لاستكمال"),13,TEXT,false);addr.setMaxLines(2);card.addView(addr);String detail=(b.phone1.isEmpty()?"":"☎ "+b.phone1)+(b.phone2.isEmpty()?"":"  |  "+b.phone2)+(b.amount.isEmpty()?"":"     المبلغ: "+b.amount);TextView d=text(detail,11,MUTED,false);d.setPadding(0,dp(4),0,0);card.addView(d);TextView state=text(statusLabel(b.visitStatus),10,statusColor(b.visitStatus),true);state.setGravity(Gravity.END);card.addView(state);card.setOnClickListener(v->showBeneficiary(b));return card;}}

    private void showBeneficiary(AppDatabase.Beneficiary b){ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(18));scroll.addView(box);box.addView(text(safe(b.name,"مستفيد بلا اسم"),21,NAVY,true));box.addView(info("العنوان",b.address));box.addView(info("القطاع",b.sector));box.addView(info("الشارع المرجعي",b.street));box.addView(info("الهاتف",b.phone1));box.addView(info("الهاتف الثاني",b.phone2));box.addView(info("صلة القرابة",b.relation));box.addView(info("مع من",b.relatedTo));box.addView(info("رقم التسجيل",b.registration));box.addView(info("المبلغ",b.amount));box.addView(info("مطابقة العنوان",b.matchStatus+" — "+Math.round(b.confidence*100)+"%"));
        EditText notes=new EditText(this);notes.setHint("ملاحظات الزيارة");notes.setText(b.notes);notes.setMinLines(3);notes.setGravity(Gravity.TOP|Gravity.START);notes.setBackground(round(0xffF4F8FB,12,0xffD6E5EB,1));box.addView(notes,new LinearLayout.LayoutParams(-1,dp(95)));
        LinearLayout calls=new LinearLayout(this);calls.setGravity(Gravity.CENTER);box.addView(calls);if(!b.phone1.isEmpty())calls.addView(dialogButton("اتصال 1",BLUE,v->dial(b.phone1)));if(!b.phone2.isEmpty())calls.addView(dialogButton("اتصال 2",BLUE,v->dial(b.phone2)));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setNegativeButton("إغلاق",null).create();LinearLayout states=new LinearLayout(this);states.setOrientation(LinearLayout.VERTICAL);box.addView(states);states.addView(statusAction("✓ تمت الزيارة","completed",0xff149176,b,notes,dialog));states.addView(statusAction("↻ بانتظار الزيارة","pending",0xffC9851C,b,notes,dialog));states.addView(statusAction("! بحاجة لمراجعة","review",0xffBD4868,b,notes,dialog));states.addView(statusAction("× تعذر الوصول","unreachable",0xff6B7280,b,notes,dialog));dialog.setOnShowListener(x->{dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.94),-2);});dialog.show();}
    private View statusAction(String label,String status,int color,AppDatabase.Beneficiary b,EditText notes,AlertDialog dialog){Button button=smallButton(label,color);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(4),0,0);button.setLayoutParams(p);button.setOnClickListener(v->{db.updateVisit(b.id,status,notes.getText().toString());dialog.dismiss();renderActive();Toast.makeText(this,"تم حفظ حالة الزيارة",Toast.LENGTH_SHORT).show();});return button;}
    private View info(String label,String value){LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(7),0,dp(7));TextView l=text(label,12,MUTED,true);row.addView(l,new LinearLayout.LayoutParams(dp(120),-2));TextView v=text(safe(value,"—"),13,TEXT,false);v.setTextIsSelectable(true);row.addView(v,new LinearLayout.LayoutParams(0,-2,1));return row;}
    private void dial(String phone){try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone.replaceAll("[^+0-9]",""))));}catch(Exception e){toast("الاتصال غير متاح على هذا الجهاز");}}

    private void showGuide(){ScrollView scroll=new ScrollView(this);content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout box=column(dp(16));scroll.addView(box);box.addView(text("دليل قطاعات وشوارع مخيم اليرموك",21,NAVY,true));TextView sub=text("المخطط مبني على مواضع القطاعات في ملف DXF المرفق، والقائمة أدناه هي المرجع المعتمد للتصنيف.",12,MUTED,false);sub.setPadding(0,dp(4),0,dp(12));box.addView(sub);SectorMapView map=new SectorMapView(this);map.setBackground(round(0xffF4FAFC,18,0xffD6E6EC,1));box.addView(map,new LinearLayout.LayoutParams(-1,dp(350)));
        TextView choose=text("اختر القطاع",15,NAVY,true);choose.setPadding(0,dp(18),0,dp(6));box.addView(choose);Spinner sector=new Spinner(this);ArrayAdapter<String>adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,AddressClassifier.SECTOR_ORDER);sector.setAdapter(adapter);sector.setBackground(round(Color.WHITE,12,0xffD6E5EB,1));box.addView(sector,new LinearLayout.LayoutParams(-1,dp(50)));LinearLayout streets=column(dp(8));box.addView(streets,new LinearLayout.LayoutParams(-1,-2));
        sector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){streets.removeAllViews();String name=AddressClassifier.SECTOR_ORDER.get(pos);List<String>items=classifier.getReference().get(name);TextView count=text(name+" — "+items.size()+" شارعاً/معلماً",15,0xff0E7490,true);count.setPadding(0,dp(12),0,dp(6));streets.addView(count);for(int i=0;i<items.size();i++){String street=items.get(i);TextView line=text((i+1)+". "+street,13,TEXT,false);line.setPadding(dp(10),dp(9),dp(10),dp(9));line.setBackground(round(i%2==0?0xffEAF4F7:Color.WHITE,8,0,0));streets.addView(line,new LinearLayout.LayoutParams(-1,-2));}}});}

    private void showSettings(){ScrollView scroll=new ScrollView(this);content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout box=column(dp(16));scroll.addView(box);box.addView(text("الإعدادات وحماية البيانات",21,NAVY,true));box.addView(setting("المشروع الحالي",currentProject,BLUE,v->newProject()));box.addView(setting("إنشاء مشروع أو قائمة جديدة","يحفظ كل مشروع وبياناته بصورة مستقلة",BLUE,v->newProject()));box.addView(setting("نسخة احتياطية JSON","تصدير المستفيدين وحالات الزيارة والملاحظات",0xff149176,v->createBackup()));box.addView(setting("استيراد نسخة أو قائمة","Excel أو CSV أو JSON من ذاكرة الهاتف",0xff0E7490,v->chooseImport()));box.addView(setting("حذف بيانات المشروع الحالي","لا يحذف المشاريع الأخرى",0xffBD4868,v->confirmClear()));
        LinearLayout about=column(dp(16));about.setBackground(round(Color.WHITE,16,0xffD6E5EB,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(18),0,0);about.setLayoutParams(p);about.addView(text("حول التطبيق",16,NAVY,true));about.addView(text("Yarmouk Camp Shelter — Android Field Edition\nإصدار 1.0.0 • يعمل دون إنترنت\nمرجع القطاعات: ملف الشوارع + مخطط DXF\nالبيانات تبقى محلياً على الجهاز.",12,MUTED,false));box.addView(about);}
    private View setting(String title,String sub,int color,View.OnClickListener click){LinearLayout card=column(dp(14));card.setBackground(round(Color.WHITE,15,0xffD6E5EB,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(88));p.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(p);card.addView(text(title,15,color,true));card.addView(text(sub,11,MUTED,false));card.setOnClickListener(click);return card;}
    private void newProject(){EditText input=new EditText(this);input.setHint("مثال: قائمة آب 2026");input.setSingleLine(true);input.setPadding(dp(16),0,dp(16),0);new AlertDialog.Builder(this).setTitle("مشروع جديد").setMessage("سيظهر له قسم مستقل داخل التطبيق.").setView(input).setPositiveButton("إنشاء",(d,w)->{String name=input.getText().toString().trim();if(name.isEmpty()){toast("اكتب اسم المشروع");return;}currentProject=name;currentProjectId=db.ensureProject(name);getPreferences(MODE_PRIVATE).edit().putString("current_project",name).apply();refreshProjects();renderActive();}).setNegativeButton("إلغاء",null).show();}
    private void confirmClear(){new AlertDialog.Builder(this).setTitle("حذف بيانات المشروع؟").setMessage("سيتم حذف جميع المستفيدين وحالات الزيارة من «"+currentProject+"». يفضّل أخذ نسخة احتياطية أولاً.").setPositiveButton("حذف",(d,w)->{db.deleteProjectData(currentProjectId);renderActive();toast("تم حذف بيانات المشروع");}).setNegativeButton("إلغاء",null).show();}

    private void chooseImport(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","application/json","text/plain"});startActivityForResult(intent,REQUEST_IMPORT);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==REQUEST_IMPORT)importUri(uri);else if(request==REQUEST_BACKUP)writeBackup(uri);}
    private void importUri(Uri uri){ProgressDialog progress=new ProgressDialog(this);progress.setMessage("جارٍ قراءة القائمة وتصنيف العناوين…");progress.setCancelable(false);progress.show();worker.execute(()->{try{SpreadsheetImporter.Result result=SpreadsheetImporter.read(this,uri);for(AppDatabase.Beneficiary b:result.rows){AddressClassifier.Match m=classifier.classify(b.address);b.sector=m.sector;b.street=m.street;b.matchStatus=m.review;b.confidence=m.confidence;b.sectorOrder=m.sectorIndex;b.routeOrder=m.routeIndex;b.streetOrder=m.streetIndex;b.alleyOrder=m.alleyIndex;}runOnUiThread(()->{progress.dismiss();confirmImport(result);});}catch(Exception e){runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("تعذر استيراد القائمة").setMessage(e.getMessage()).setPositiveButton("حسناً",null).show();});}});}
    private void confirmImport(SpreadsheetImporter.Result result){if(result.rows.isEmpty()){new AlertDialog.Builder(this).setTitle("لا توجد سجلات").setMessage(result.warnings.isEmpty()?"لم يُعثر على بيانات قابلة للاستيراد.":join(result.warnings)).setPositiveButton("حسناً",null).show();return;}int review=0;for(AppDatabase.Beneficiary b:result.rows)if(b.matchStatus.contains("مراجعة"))review++;String message="تم العثور على "+result.rows.size()+" مستفيداً\nبحاجة لمراجعة العنوان: "+review+"\n\nاختر استبدال بيانات المشروع أو الإضافة إليها.";
        new AlertDialog.Builder(this).setTitle("القائمة جاهزة للاستيراد").setMessage(message).setPositiveButton("استبدال",(d,w)->saveImport(result,true)).setNeutralButton("إضافة",(d,w)->saveImport(result,false)).setNegativeButton("إلغاء",null).show();}
    private void saveImport(SpreadsheetImporter.Result result,boolean replace){db.importBeneficiaries(currentProjectId,result.rows,replace);toast("تم حفظ "+result.rows.size()+" مستفيداً وترتيب العناوين");showPage(NAV_HOME);}

    private void createBackup(){try{pendingBackup=db.backup(currentProjectId,currentProject).toString(2);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());intent.putExtra(Intent.EXTRA_TITLE,"Yarmouk_"+safeFile(currentProject)+"_"+date+".json");startActivityForResult(intent,REQUEST_BACKUP);}catch(Exception e){fatal("تعذر تجهيز النسخة الاحتياطية",e);}}
    private void writeBackup(Uri uri){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new Exception("تعذر فتح ملف الحفظ");out.write(pendingBackup.getBytes(StandardCharsets.UTF_8));toast("تم حفظ النسخة الاحتياطية بنجاح");}catch(Exception e){fatal("تعذر حفظ النسخة الاحتياطية",e);}finally{pendingBackup=null;}}
    private void printRoute(){List<AppDatabase.Beneficiary>rows=db.list(currentProjectId,"","all");if(rows.isEmpty()){toast("لا توجد قائمة للطباعة");return;}PrintManager manager=(PrintManager)getSystemService(Context.PRINT_SERVICE);PrintAttributes attrs=new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).setColorMode(PrintAttributes.COLOR_MODE_COLOR).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();manager.print("Yarmouk_Field_"+safeFile(currentProject),new PrintRouteAdapter(this,currentProject,rows),attrs);}

    private TextView text(String value,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.create("sans",Typeface.BOLD));v.setLineSpacing(0,1.12f);return v;}
    private LinearLayout column(int padding){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(padding,padding,padding,padding);return v;}
    private Button smallButton(String value,int color){Button b=new Button(this);b.setText(value);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setTypeface(null,Typeface.BOLD);b.setAllCaps(false);b.setPadding(dp(12),0,dp(12),0);b.setBackground(round(color,12,0,0));return b;}
    private Button dialogButton(String value,int color,View.OnClickListener click){Button b=smallButton(value,color);b.setOnClickListener(click);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),dp(6),dp(3),dp(6));b.setLayoutParams(p);return b;}
    private GradientDrawable round(int color,int radius,int stroke,int strokeWidth){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(strokeWidth>0)d.setStroke(dp(strokeWidth),stroke);return d;}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private int computeResource(String name,String type){return getResources().getIdentifier(name,type,getPackageName());}
    private int statusColor(String status){if("completed".equals(status))return 0xff149176;if("review".equals(status))return 0xffBD4868;if("unreachable".equals(status))return 0xff6B7280;return 0xffC9851C;}
    private String statusLabel(String status){if("completed".equals(status))return "تمت الزيارة";if("review".equals(status))return "بحاجة لمراجعة";if("unreachable".equals(status))return "تعذر الوصول";return "بانتظار الزيارة";}
    private static String safe(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value.trim();}
    private static String safeFile(String value){return value.replaceAll("[^0-9a-zA-Z\\u0600-\\u06FF._-]+","_");}
    private static String shortStreet(String value){String[]parts=value.split("[/()\\-–—]");return parts.length==0?value:parts[0].trim();}
    private static String join(List<String>values){StringBuilder b=new StringBuilder();for(String v:values)b.append("• ").append(v).append('\n');return b.toString();}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void fatal(String title,Exception e){new AlertDialog.Builder(this).setTitle(title).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("إغلاق",(d,w)->finish()).show();}
}
