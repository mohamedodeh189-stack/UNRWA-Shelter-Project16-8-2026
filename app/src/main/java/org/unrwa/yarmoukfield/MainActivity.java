package org.unrwa.yarmoukfield;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import org.unrwa.yarmoukfield.sync.ShareIntentDesktopAdapter;
import org.unrwa.yarmoukfield.sync.StudyRevisionPackage;
import org.unrwa.yarmoukfield.sync.SyncPackageBuilder;
import org.unrwa.yarmoukfield.sync.SyncResult;
import org.unrwa.yarmoukfield.sync.SyncTarget;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MainActivity extends Activity {
    private static final int NAV_HOME=0,NAV_PEOPLE=1,NAV_ROUTE=2,NAV_GUIDE=3,NAV_SETTINGS=4;
    // ===== DEBUG-ONLY — REMOVE BEFORE RELEASE. Visual-QA seeder for the field-assistant rows. =====
    private static final boolean DEBUG_SEED = true;
    private static final int REQUEST_IMPORT=4101,REQUEST_BACKUP=4102,REQUEST_PHOTO_ROOT=4103,REQUEST_CAPTURE_PHOTO=4104,REQUEST_HOUSE_PLAN=4105,REQUEST_CAD_FONT=4106,REQUEST_LOCATION_PERMISSION=4107,REQUEST_CONTACTS_PERMISSION=4108,REQUEST_SYNC_SHARE=4109,REQUEST_PACKAGE_ROOT=4110,REQUEST_SEQ_CAMERA=4111,REQUEST_CAMERA_PERMISSION=4112,REQUEST_ADDRESS_REF=4113,REQUEST_SATELLITE_IMAGE=4114,REQUEST_FULL_BACKUP=4115;
    private static final long GPS_CAPTURE_WINDOW_MS=75000,GPS_ACCURACY_WARNING_M=30;
    // Matches the Windows app's palette (launcher.py NAVY/Accent.TButton) so both apps read as one product.
    // UI POLISH: these were `static final` (fixed light palette); now plain instance fields recomputed by
    // applyPalette() so a runtime theme switch (حسب النظام/فاتح/داكن، SharedPreferences فقط) can re-theme
    // Home/Studies/Followup/Settings/Map/cards/dialogs without touching any functional logic.
    private int NAVY,BLUE,ROSE,SURFACE,TEXT,MUTED,CARD_BG,CARD_BORDER,SURFACE_CARD,STATUSBAR,BRAND;
    private static final String THEME_PREFS="app_prefs",THEME_KEY="theme_mode";
    private String themeMode(){return getSharedPreferences(THEME_PREFS,MODE_PRIVATE).getString(THEME_KEY,"system");}
    private void setThemeMode(String mode){getSharedPreferences(THEME_PREFS,MODE_PRIVATE).edit().putString(THEME_KEY,mode).apply();}
    // Last-backup tracker (either JSON-only or full+photos counts) — drives the home-screen reminder and the
    // status line in Settings. Same small app_prefs store as theme/font-size, never the DB.
    private long lastBackupAt(){return getSharedPreferences(THEME_PREFS,MODE_PRIVATE).getLong("last_backup_at",0);}
    private void setLastBackupAt(long t){getSharedPreferences(THEME_PREFS,MODE_PRIVATE).edit().putLong("last_backup_at",t).apply();}
    private String backupStatusLabel(){long t=lastBackupAt();if(t==0)return "لم تُنفَّذ أي نسخة احتياطية بعد";long days=(System.currentTimeMillis()-t)/86400000L;return days<=0?"آخر نسخة احتياطية: اليوم":"آخر نسخة احتياطية: قبل "+days+" يوماً";}
    private boolean isDarkActive(){String m=themeMode();if("dark".equals(m))return true;if("light".equals(m))return false;int uiMode=getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK;return uiMode==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
    private void applyPalette(){
        if(isDarkActive()){
            // NAVY is used overwhelmingly as a HEADING/TEXT colour (74 sites) — in dark mode it must be LIGHT so
            // titles never sit dark-on-dark. BRAND keeps the dark-navy brand fill for the few real backgrounds
            // (header band, featured card, beneficiary header) which carry white text. BLUE/ROSE are lightened
            // so accent text/badges stay legible on dark cards; TEXT/MUTED are already light.
            NAVY=0xffDCEAF3;BLUE=0xff4FC3D9;ROSE=0xffE0698A;SURFACE=0xff0E1B26;TEXT=0xffE7EFF4;MUTED=0xffAFC2CE;
            CARD_BG=0xff152736;CARD_BORDER=0xff2B4557;SURFACE_CARD=0xff17293A;STATUSBAR=0xff081420;BRAND=0xff10314C;
        } else {
            NAVY=0xff123B5D;BLUE=0xff0E7490;ROSE=0xffC04368;SURFACE=0xffF4F8FB;TEXT=0xff15364D;MUTED=0xff667A89;
            CARD_BG=0xffF4F8FB;CARD_BORDER=0xffD6E5EB;SURFACE_CARD=Color.WHITE;STATUSBAR=0xff0B273C;BRAND=0xff123B5D;
        }
        // Belt-and-braces: also disable Force Dark at the View level (styles.xml values-v29 override is
        // the primary fix; this covers any OEM path that ignores the theme attribute).
        if(android.os.Build.VERSION.SDK_INT>=29)getWindow().getDecorView().setForceDarkAllowed(false);
    }
    private String themeModeLabel(String mode){if("dark".equals(mode))return "داكن";if("light".equals(mode))return "فاتح";return "حسب النظام";}
    // FONT SIZE — display-only text scaling, SharedPreferences (same app_prefs store), never the DB. Applied at
    // the single text() factory so it flows to Home/Studies/Followup/Settings/cards/dialogs at once. Moderate
    // steps (≤1.30) so buttons/cards don't overflow; recreate() on change to re-lay everything.
    private String fontSizeMode(){return getSharedPreferences(THEME_PREFS,MODE_PRIVATE).getString("font_size","normal");}
    private void setFontSizeMode(String v){getSharedPreferences(THEME_PREFS,MODE_PRIVATE).edit().putString("font_size",v).apply();}
    private float fontScale(){String m=fontSizeMode();return "xlarge".equals(m)?1.30f:"large".equals(m)?1.15f:1.0f;}
    private String fontSizeLabel(){String m=fontSizeMode();return "xlarge".equals(m)?"كبير جداً":"large".equals(m)?"كبير":"عادي";}
    /** Dark-mode readability: our text colours turn light in dark mode, but a native AlertDialog's own chrome
     * stays light (the app's base theme is fixed Material.Light). So in dark mode ONLY, give a custom-content
     * dialog a theme-aware dark background — its light text then reads on it, and the native buttons stay the
     * accent-blue colour (readable on dark). No-op in light mode, so the light look is byte-for-byte unchanged. */
    private void themeWin(android.view.Window w){if(w!=null&&isDarkActive())w.setBackgroundDrawable(round(SURFACE_CARD,18,CARD_BORDER,1));}
    /** One-call variant so it drops cleanly into the many single-expression onShow lambdas: theme the window
     * (dark bg in dark mode only) AND apply the requested size. Replaces bare {@code getWindow().setLayout(w,h)}. */
    private void themeAndSize(android.view.Window w,int lw,int lh){if(w==null)return;themeWin(w);w.setLayout(lw,lh);}
    private void showFontSizePicker(){
        String[] values={"normal","large","xlarge"};String[] labels={"عادي","كبير","كبير جداً"};
        int cur=0;for(int i=0;i<values.length;i++)if(values[i].equals(fontSizeMode()))cur=i;
        new AlertDialog.Builder(this).setTitle("حجم الخط").setSingleChoiceItems(labels,cur,(d,which)->{setFontSizeMode(values[which]);d.dismiss();recreate();}).setNegativeButton("إلغاء",null).show();
    }
    private void showThemePicker(){
        String[] values={"system","light","dark"};String[] labels={"حسب النظام","فاتح","داكن"};
        int cur=0;for(int i=0;i<values.length;i++)if(values[i].equals(themeMode()))cur=i;
        new AlertDialog.Builder(this).setTitle("مظهر التطبيق").setSingleChoiceItems(labels,cur,(d,which)->{setThemeMode(values[which]);d.dismiss();recreate();}).setNegativeButton("إلغاء",null).show();
    }
    private static final String APP_VERSION="19.1";
    private static final String DESIGNER_CREDIT="تصميم وتطوير التطبيق: المهندس محمد أمين عودة";
    private static final String ACKNOWLEDGEMENT="نتقدّم بجزيل الشكر والتقدير إلى كل من ساهم في إنجاح هذا العمل الميداني — من مهندسين وباحثين وكوادرَ إداريةٍ وميدانية — على جهودهم المخلصة في خدمة الأهل في مخيم اليرموك";
    private static final String PRINT_CREDIT=DESIGNER_CREDIT;
    // Pages in the divided PDF are West 1-3, East 1-3, then Orouba/Taqaddum/Maalia.
    // The app sector order alternates East/West, therefore an explicit map is required.
    private static final int[] SECTOR_PDF_PAGES={3,0,4,1,5,2,6};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AppDatabase db;private AddressClassifier classifier;private BeneficiaryPhotoManager photoManager;private AppSecurity security;private List<QuantityCatalog.Item> quantityCatalog;private LinearLayout root,header,bottom;private ScrollView headerScroller;private Button headerToggle;private FrameLayout content;private boolean headerExpanded=true;private int headerTopInset;
    private Spinner projectSpinner;private TextView projectCaption;private String currentProject;private long currentProjectId;private int activePage=NAV_HOME;
    private String pendingBackup,pendingPhotoPhase="",pendingPhotoFileName="";private Uri pendingPhotoUri;private long pendingPhotoBeneficiaryId,pendingPhotoCapturedAt,deferredPhotoBeneficiaryId,pendingPlanBeneficiaryId,pendingCameraBenId;private String deferredPhotoPhase="",pendingCameraPhase="";private AlertDialog beneficiaryDialog,showSecondPaymentReadyListDialog;private boolean securityUnlocked,securityDialogVisible;
    private GpsLocationHelper gpsHelper;private long pendingGpsBeneficiaryId;private AlertDialog gpsProgressDialog;
    private int pendingContactsSyncMode;private long pendingContactsSyncBeneficiaryId;private String pendingContactsSyncBatch="";
    private List<String> pendingSyncRevisionUuids=new ArrayList<>();

    @Override public void onCreate(Bundle state){super.onCreate(state);applyPalette();getWindow().setStatusBarColor(STATUSBAR);getWindow().setNavigationBarColor(STATUSBAR);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);security=new AppSecurity(this);db=new AppDatabase(this);photoManager=new BeneficiaryPhotoManager(this);gpsHelper=new GpsLocationHelper(this);restorePendingPhoto();try{classifier=new AddressClassifier(this);quantityCatalog=QuantityCatalog.load(this);}catch(Exception e){fatal("تعذر تحميل المراجع الميدانية",e);return;}
        currentProject=getPreferences(MODE_PRIVATE).getString("current_project",AppDatabase.DEFAULT_PROJECT);currentProjectId=db.ensureProject(currentProject);buildShell();refreshProjects();showPage(NAV_HOME);root.setVisibility(View.INVISIBLE);root.post(this::ensureSecurityAccess);}
    @Override protected void onResume(){super.onResume();if(security==null)return;if(securityUnlocked&&security.shouldRelock()){securityUnlocked=false;if(root!=null)root.setVisibility(View.INVISIBLE);}else if(securityUnlocked)security.clearBackground();if(root!=null)root.post(this::ensureSecurityAccess);}
    @Override protected void onStop(){if(security!=null&&!isChangingConfigurations())security.markBackground();super.onStop();}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(root!=null)root.post(root::requestApplyInsets);}
    @Override protected void onDestroy(){worker.shutdownNow();if(db!=null)db.close();super.onDestroy();}

    private void buildShell(){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(SURFACE);setContentView(root);
        headerScroller=new ScrollView(this);headerScroller.setBackgroundColor(BRAND);headerScroller.setFillViewport(false);headerScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);root.addView(headerScroller,new LinearLayout.LayoutParams(-1,dp(228)));
        header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(14),dp(8),dp(14),dp(10));header.setBackgroundColor(BRAND);headerScroller.addView(header,new ScrollView.LayoutParams(-1,dp(228)));

        // Photo cover: the reference camp-gate photo (yarmouk_camp_cover, shipped
        // unmodified) fills the identity band, with a bottom-anchored gradient
        // scrim for text contrast and a small corner UNRWA mark — replacing the
        // old plain-navy row + 104dp logo, same 166dp height budget so the
        // header/inset/toggle logic below needs no changes.
        FrameLayout cover=new FrameLayout(this);header.addView(cover,new LinearLayout.LayoutParams(-1,dp(166)));
        ImageView coverPhoto=new ImageView(this);coverPhoto.setImageResource(computeResource("yarmouk_camp_cover","drawable"));coverPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);coverPhoto.setContentDescription("صورة بوابة مخيم اليرموك");cover.addView(coverPhoto,new FrameLayout.LayoutParams(-1,-1));
        View scrim=new View(this);scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00081C2C,0x33081C2C,0xCC081C2C}));cover.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        // Official identity mark: the UNRWA logo on a solid white chip so it reads clearly over the photo, sized
        // for a professional international-organisation header rather than a faint corner watermark.
        ImageView logo=new ImageView(this);logo.setImageResource(getResources().getIdentifier("unrwa_logo","drawable",getPackageName()));logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setBackground(round(0xffFFFFFF,10,0x33FFFFFF,1));logo.setPadding(dp(7),dp(7),dp(7),dp(7));FrameLayout.LayoutParams logoParams=new FrameLayout.LayoutParams(dp(58),dp(58));logoParams.gravity=Gravity.TOP|Gravity.END;logoParams.setMargins(dp(12),dp(12),dp(12),0);cover.addView(logo,logoParams);
        // Clean title stack: project name (AR + EN) and the designer credit only — no agency sub-line, description
        // or thanks list, so the cover reads as an official tool.
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(14),0,dp(14),dp(11));FrameLayout.LayoutParams titlesParams=new FrameLayout.LayoutParams(-1,-2);titlesParams.gravity=Gravity.BOTTOM;cover.addView(titles,titlesParams);
        TextView title=text("مشروع المأوى في الأونروا",21,Color.WHITE,true);title.setMaxLines(2);titles.addView(title);
        TextView en=text("UNRWA SHELTER PROJECT",11,0xffCFEAF3,true);en.setLetterSpacing(0.14f);en.setPadding(0,dp(1),0,0);titles.addView(en);
        TextView designer=text(DESIGNER_CREDIT,10,0xffEAF5F9,false);designer.setMaxLines(2);designer.setPadding(0,dp(6),0,0);titles.addView(designer);
        projectCaption=text("إصدار ميداني • "+APP_VERSION,9,0xffB9DCE9,false);titles.addView(projectCaption);

        LinearLayout projectBox=new LinearLayout(this);projectBox.setGravity(Gravity.CENTER_VERTICAL);header.addView(projectBox,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView label=text("المشروع الحالي",11,0xffD7EDF5,true);projectBox.addView(label,new LinearLayout.LayoutParams(dp(105),-1));
        projectSpinner=new Spinner(this);projectSpinner.setBackground(round(0xffF2F7FA,12,0,0));projectBox.addView(projectSpinner,new LinearLayout.LayoutParams(0,dp(46),1));
        headerToggle=smallButton("▲ تصغير رأس التطبيق",NAVY);headerToggle.setTextSize(12);headerToggle.setContentDescription("اضغط أو اسحب للأعلى والأسفل لتصغير أو توسيع رأس التطبيق");root.addView(headerToggle,new LinearLayout.LayoutParams(-1,dp(32)));headerToggle.setOnClickListener(v->setHeaderExpanded(!headerExpanded));headerToggle.setOnTouchListener(new View.OnTouchListener(){float startY;@Override public boolean onTouch(View v,android.view.MotionEvent event){if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){startY=event.getRawY();return false;}if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){float delta=event.getRawY()-startY;if(Math.abs(delta)>dp(22)){setHeaderExpanded(delta>0);return true;}}return false;}});
        content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER);bottom.setPadding(dp(5),dp(5),dp(5),dp(5));bottom.setBackgroundColor(SURFACE_CARD);root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(70)));
        String[]names={"⌂\nالرئيسية","⌕\nالدراسات","✓\nالمتابعة","⌖\nالدليل","⚙\nالإعدادات"};for(int i=0;i<names.length;i++){final int page=i;Button b=new Button(this);b.setText(names[i]);b.setTextSize(13);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setPadding(0,0,0,0);b.setTag(i);b.setOnClickListener(v->showPage(page));bottom.addView(b,new LinearLayout.LayoutParams(0,-1,1));}
        root.setOnApplyWindowInsetsListener((view,insets)->{int topInset,bottomInset,leftInset,rightInset;if(android.os.Build.VERSION.SDK_INT>=30){int types=android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout();android.graphics.Insets safe=insets.getInsets(types);topInset=safe.top;bottomInset=safe.bottom;leftInset=safe.left;rightInset=safe.right;}else{topInset=Math.max(insets.getStableInsetTop(),insets.getSystemWindowInsetTop());bottomInset=insets.getStableInsetBottom();if(bottomInset<=0)bottomInset=insets.getSystemWindowInsetBottom();leftInset=Math.max(insets.getStableInsetLeft(),insets.getSystemWindowInsetLeft());rightInset=Math.max(insets.getStableInsetRight(),insets.getSystemWindowInsetRight());if(android.os.Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){topInset=Math.max(topInset,insets.getDisplayCutout().getSafeInsetTop());bottomInset=Math.max(bottomInset,insets.getDisplayCutout().getSafeInsetBottom());leftInset=Math.max(leftInset,insets.getDisplayCutout().getSafeInsetLeft());rightInset=Math.max(rightInset,insets.getDisplayCutout().getSafeInsetRight());}}headerTopInset=topInset;header.setPadding(leftInset+dp(14),topInset+dp(8),rightInset+dp(14),dp(10));ViewGroup.LayoutParams headerParams=header.getLayoutParams();headerParams.height=dp(228)+topInset;header.setLayoutParams(headerParams);setHeaderExpanded(headerExpanded);bottom.setPadding(leftInset+dp(5),dp(5),rightInset+dp(5),bottomInset+dp(5));ViewGroup.LayoutParams bottomParams=bottom.getLayoutParams();bottomParams.height=dp(70)+bottomInset;bottom.setLayoutParams(bottomParams);return insets;});root.requestApplyInsets();}

    private void setHeaderExpanded(boolean expanded){headerExpanded=expanded;if(headerScroller==null)return;boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;int visible=expanded?(landscape?170:228):(landscape?72:92);ViewGroup.LayoutParams params=headerScroller.getLayoutParams();params.height=dp(visible)+headerTopInset;headerScroller.setLayoutParams(params);headerToggle.setText(expanded?"▲ تصغير رأس التطبيق":"▼ عرض المصمم ووصف التطبيق");if(expanded)headerScroller.post(()->headerScroller.fullScroll(View.FOCUS_DOWN));else headerScroller.scrollTo(0,0);}

    private void refreshProjects(){List<String>names=db.listProjects();ArrayAdapter<String>adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names){@Override public View getView(int p,View c,ViewGroup parent){TextView v=(TextView)super.getView(p,c,parent);v.setTextColor(NAVY);v.setGravity(Gravity.CENTER);v.setTextSize(12);return v;}};projectSpinner.setAdapter(adapter);int selected=Math.max(0,names.indexOf(currentProject));projectSpinner.setSelection(selected,false);
        projectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean ready;@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){if(!ready){ready=true;return;}currentProject=String.valueOf(p.getItemAtPosition(pos));currentProjectId=db.projectId(currentProject);getPreferences(MODE_PRIVATE).edit().putString("current_project",currentProject).apply();renderActive();}});}

    private void showPage(int page){activePage=page;renderActive();for(int i=0;i<bottom.getChildCount();i++){Button b=(Button)bottom.getChildAt(i);boolean active=(Integer)b.getTag()==page;b.setTextColor(active?Color.WHITE:MUTED);b.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);b.setBackground(active?round(BLUE,14,0,0):round(Color.TRANSPARENT,14,0,0));}}
    private void renderActive(){content.removeAllViews();if(activePage==NAV_HOME)showHome();else if(activePage==NAV_PEOPLE)showPeople(false);else if(activePage==NAV_ROUTE)showPeople(true);else if(activePage==NAV_GUIDE)showGuide();else showSettings();}

    private void showHome(){FrameLayout frame=new FrameLayout(this);content.addView(frame,new FrameLayout.LayoutParams(-1,-1));ImageView mark=new ImageView(this);mark.setImageResource(computeResource("wm_unrwa","drawable"));mark.setAlpha(.14f);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);mark.setPadding(dp(24),dp(24),dp(24),dp(24));FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);frame.addView(mark,mp);
        ScrollView scroll=new ScrollView(this);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout box=column(dp(16));scroll.addView(box,new ScrollView.LayoutParams(-1,-2));
        TextView greeting=text("مرحباً بك في مركز العمل الميداني",22,NAVY,true);box.addView(greeting);TextView subtitle=text("قائمة مرتبة، عناوين أوضح، ومتابعة ميدانية أسرع — تعمل دون إنترنت",13,MUTED,false);subtitle.setPadding(0,dp(4),0,dp(14));box.addView(subtitle);
        // Presentation home: the four figures are live counts AND navigation shortcuts.
        final int cStudy=db.stageCount(currentProjectId,"study");final int cReady=db.studyReadyCount(currentProjectId);final int cFollow=db.stageCount(currentProjectId,"followup");final int cDone=db.followupCompletedCount(currentProjectId);
        final int[] fadeIdx={0};
        LinearLayout stats=new LinearLayout(this);stats.setOrientation(LinearLayout.HORIZONTAL);box.addView(stats,new LinearLayout.LayoutParams(-1,-2));
        stats.addView(stat("الدراسات",cStudy,NAVY,fadeIdx[0]++,v->showPage(NAV_PEOPLE)),new LinearLayout.LayoutParams(0,dp(108),1));
        stats.addView(stat("جاهزة للدفعة",cReady,0xffC9851C,fadeIdx[0]++,v->{if(cReady<=0)toast("لا يوجد مستفيدون جاهزون حالياً");else{showPage(NAV_PEOPLE);toast("اختر الفلتر «جاهزة للمراجعة» لعرض الجاهزين");}}),new LinearLayout.LayoutParams(0,dp(108),1));
        LinearLayout stats2=new LinearLayout(this);stats2.setOrientation(LinearLayout.HORIZONTAL);box.addView(stats2,new LinearLayout.LayoutParams(-1,-2));
        stats2.addView(stat("قيد المتابعة",cFollow,0xff0E7490,fadeIdx[0]++,v->showPage(NAV_ROUTE)),new LinearLayout.LayoutParams(0,dp(108),1));
        stats2.addView(stat("منجزة",cDone,0xff149176,fadeIdx[0]++,v->{if(cDone<=0)toast("لا يوجد مستفيدون منجزون بعد");else{showPage(NAV_ROUTE);toast("اختر الفلتر «تمت الزيارة» لعرض المنجزين");}}),new LinearLayout.LayoutParams(0,dp(108),1));
        // Small status strip under the figures — a quiet at-a-glance summary + offline reassurance.
        LinearLayout strip=new LinearLayout(this);strip.setOrientation(LinearLayout.HORIZONTAL);strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(12),dp(8),dp(12),dp(8));strip.setBackground(round(0xffEFF5F8,12,0xffDCE9EF,1));LinearLayout.LayoutParams stripP=new LinearLayout.LayoutParams(-1,-2);stripP.setMargins(dp(5),dp(6),dp(5),0);box.addView(strip,stripP);
        TextView stripText=text(cStudy+" دراسات · "+cFollow+" قيد المتابعة · "+cDone+" منجزة",12,NAVY,true);strip.addView(stripText,new LinearLayout.LayoutParams(0,-2,1));
        TextView offline=text("● يعمل دون إنترنت",11,0xff149176,true);strip.addView(offline);
        fadeIn(strip,fadeIdx[0]++);
        // Backup reminder — only shown when stale (never backed up, or >7 days), so it disappears the moment
        // the engineer makes a fresh one. Protects against data loss on phone theft/loss/app uninstall.
        long lastBackup=lastBackupAt();
        if(lastBackup==0||System.currentTimeMillis()-lastBackup>604800000L){
            String note=lastBackup==0?"لم تُنسخ بياناتك احتياطياً بعد — اضغط لعمل نسخة الآن":"آخر نسخة احتياطية قبل "+((System.currentTimeMillis()-lastBackup)/86400000L)+" يوماً — اضغط لعمل نسخة جديدة";
            box.addView(action("💾","احمِ بياناتك — اعمل نسخة احتياطية",note,0xffBD4868,fadeIdx[0]++,v->showPage(NAV_SETTINGS)));
        }
        TextView actionsTitle=text("اختصارات سريعة",17,NAVY,true);actionsTitle.setPadding(0,dp(18),0,dp(8));box.addView(actionsTitle);
        box.addView(action("👤","بدء دراسة مستفيد جديد","إضافة الاسم والعنوان وتصوير الأضرار قبل الترميم",0xff7A5CB8,fadeIdx[0]++,v->showAddBeneficiary()));
        box.addView(action("📋","الدراسات","استكمال المخطط والكميات والمبلغ وتجهيز الاعتماد",BLUE,fadeIdx[0]++,v->showPage(NAV_PEOPLE)));
        box.addView(action("✓","المتابعة","تنفيذ الترميم والتصوير والدفعات والاستيراد والخرائط",0xff149176,fadeIdx[0]++,v->showPage(NAV_ROUTE)));
        box.addView(action("🗺","الدليل والخرائط","المخطط المرجعي والقطاعات والشوارع ودليل الاستخدام",NAVY,fadeIdx[0]++,v->showPage(NAV_GUIDE)));
        // Featured card — the flagship close-out action, visually distinct (tinted navy) from the plain shortcuts.
        box.addView(featured("📦","تجهيز ملف مستفيد","إنشاء مجلد كامل: الكميات + CAD + PDF + الصور + Backup",fadeIdx[0]++,v->{if(cStudy+cFollow<=0)toast("لا يوجد مستفيدون بعد — ابدأ دراسة مستفيد جديد أولاً");else{showPage(NAV_PEOPLE);toast("افتح ملف المستفيد ثم اضغط «📦 تجهيز ملف المستفيد»");}}));
    }

    private View stat(String label,int value,int color,int fadeIdx,View.OnClickListener click){LinearLayout card=column(dp(12));card.setGravity(Gravity.CENTER);card.setBackground(round(SURFACE_CARD,18,0xffDCE9EF,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(108),1);p.setMargins(dp(5),dp(5),dp(5),dp(5));card.setLayoutParams(p);TextView number=text("0",28,color,true);number.setGravity(Gravity.CENTER);card.addView(number);TextView caption=text(label,12,MUTED,true);caption.setGravity(Gravity.CENTER);card.addView(caption);card.setClickable(true);card.setOnClickListener(click);pressFeedback(card);countUp(number,value);fadeIn(card,fadeIdx);return card;}
    private View action(String emoji,String title,String subtitle,int color,int fadeIdx,View.OnClickListener click){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(14),dp(12),dp(16),dp(12));card.setBackground(round(SURFACE_CARD,18,0xffDCE9EF,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(88));p.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(p);TextView icon=text(emoji,22,color,false);icon.setGravity(Gravity.CENTER);icon.setBackground(round((color&0x00FFFFFF)|0x18000000,14,0,0));icon.setTranslationX(dp(6));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(48),dp(48));ip.setMargins(0,0,dp(12),0);card.addView(icon,ip);LinearLayout labels=column(0);labels.addView(text(title,16,NAVY,true));labels.addView(text(subtitle,13,MUTED,false));card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("←",20,color,true);arrow.setGravity(Gravity.CENTER);card.addView(arrow,new LinearLayout.LayoutParams(dp(28),-1));card.setClickable(true);card.setOnClickListener(click);pressFeedback(card);fadeIn(card,fadeIdx);return card;}
    private View featured(String emoji,String title,String subtitle,int fadeIdx,View.OnClickListener click){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(round(BRAND,18,0,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,dp(6));card.setLayoutParams(p);TextView icon=text(emoji,24,Color.WHITE,false);icon.setGravity(Gravity.CENTER);icon.setBackground(round(0x33FFFFFF,14,0,0));icon.setTranslationX(dp(6));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(50),dp(50));ip.setMargins(0,0,dp(12),0);card.addView(icon,ip);LinearLayout labels=column(0);labels.addView(text(title,17,Color.WHITE,true));TextView sub=text(subtitle,12,0xffCFE6F0,false);sub.setPadding(0,dp(2),0,0);labels.addView(sub);card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("←",20,Color.WHITE,true);arrow.setGravity(Gravity.CENTER);card.addView(arrow,new LinearLayout.LayoutParams(dp(28),-2));card.setClickable(true);card.setOnClickListener(click);pressFeedback(card);fadeIn(card,fadeIdx);return card;}
    // --- Lightweight presentation animations (view-only; no data/logic touched) ---
    private void pressFeedback(final View v){v.setOnTouchListener((view,ev)->{switch(ev.getActionMasked()){case android.view.MotionEvent.ACTION_DOWN:view.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();break;case android.view.MotionEvent.ACTION_UP:case android.view.MotionEvent.ACTION_CANCEL:view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();break;}return false;});}
    private void countUp(final TextView tv,final int target){if(target<=0){tv.setText("0");return;}android.animation.ValueAnimator a=android.animation.ValueAnimator.ofInt(0,target);a.setDuration(Math.min(900,300+target*22L));a.setInterpolator(new android.view.animation.DecelerateInterpolator());a.addUpdateListener(an->tv.setText(String.valueOf((int)an.getAnimatedValue())));a.start();}
    private void fadeIn(final View v,int index){v.setAlpha(0f);v.setTranslationY(dp(12));v.animate().alpha(1f).translationY(0).setStartDelay(55L*index).setDuration(300).start();}

    /** Title + action buttons are two SEPARATE rows, not one fixed-height row sharing space via weight —
     * on a narrower/denser screen (or a larger system font scale) the buttons' combined wrap-content width
     * could exceed the row and squeeze the weighted title down to almost nothing, clipping it (observed on a
     * real test device). The title now always gets the full row width on its own line, and the buttons sit
     * in a HorizontalScrollView below — they scroll instead of ever clipping or overlapping the title,
     * regardless of screen width, density, or font scale. */
    private void showPeople(boolean followup){LinearLayout page=column(dp(12));content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        TextView titleText=text(followup?"متابعة التنفيذ":"الدراسات",21,NAVY,true);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,-2);titleParams.setMargins(0,0,0,dp(10));page.addView(titleText,titleParams);
        if(DEBUG_SEED)titleText.setOnLongClickListener(v->{seedDebugTestData();return true;}); // DEBUG-ONLY — REMOVE BEFORE RELEASE
        HorizontalScrollView actionsScroll=new HorizontalScrollView(this);actionsScroll.setHorizontalScrollBarEnabled(false);actionsScroll.setClipToPadding(false);actionsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);actionsScroll.addView(titleRow,new FrameLayout.LayoutParams(-2,-2));
        page.addView(actionsScroll,new LinearLayout.LayoutParams(-1,dp(55)));
        if(followup){Button importList=smallButton("استيراد قائمة متابعة ZIP",0xff0E7490);importList.setOnClickListener(v->chooseImport());titleRow.addView(importList);Button map=smallButton("الخريطة",0xff0E7490);map.setOnClickListener(v->showOperationalMap());addRowGap(titleRow,map);Button addrAnalysis=smallButton("تحليل العناوين",0xff7A5CB8);addrAnalysis.setOnClickListener(v->showAddressAnalysis());addRowGap(titleRow,addrAnalysis);Button route=smallButton("خطط جولتي",0xff7A5CB8);route.setOnClickListener(v->showRoutePlan());addRowGap(titleRow,route);Button print=smallButton("طباعة A4",NAVY);print.setOnClickListener(v->printRoute());addRowGap(titleRow,print);Button secondPaymentReady=smallButton("جاهزون للدفعة الثانية",0xff149176);secondPaymentReady.setOnClickListener(v->showSecondPaymentReadyList());addRowGap(titleRow,secondPaymentReady);}else{Button manual=smallButton("+ دراسة",0xff7A5CB8);manual.setOnClickListener(v->showAddBeneficiary());titleRow.addView(manual);Button stats=smallButton("إحصائية جغرافية",0xff0E7490);stats.setOnClickListener(v->showGeographicStatistics());addRowGap(titleRow,stats);}
        EditText search=new EditText(this);search.setHint("ابحث بالاسم أو العنوان أو الهاتف أو رقم التسجيل");search.setSingleLine(true);search.setTextSize(14);search.setPadding(dp(14),0,dp(14),0);search.setBackground(round(SURFACE_CARD,14,CARD_BORDER,1));page.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        LinearLayout filterRow=new LinearLayout(this);filterRow.setGravity(Gravity.CENTER_VERTICAL);filterRow.setPadding(0,dp(6),0,dp(6));page.addView(filterRow,new LinearLayout.LayoutParams(-1,dp(54)));TextView hint=text(followup?"مرتب حسب القطاع والشارع والجادة":"حالة تجهيز الدراسة",12,MUTED,true);filterRow.addView(hint,new LinearLayout.LayoutParams(0,-2,1));Spinner filter=new Spinner(this);String[]filterNames=followup?new String[]{"المتبقي","الكل","تم التسليم","قيد التنفيذ","جاهز للتسليم","يحتاج مراجعة","مؤجل","بدون GPS","عناوين ناقصة"}:new String[]{"الكل","مسودة","جاهزة للمراجعة","مراجعة مكتبية","معتمدة","نسخ سابقة"};String[]filterValues=followup?new String[]{"remaining","all","delivered","in_progress","ready","review","deferred","no_gps","missing_address"}:new String[]{"all","draft","ready","office_review","approved","superseded"};ArrayAdapter<String>fa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,filterNames);filter.setAdapter(fa);filter.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));filterRow.addView(filter,new LinearLayout.LayoutParams(dp(150),dp(44)));
        // Each import keeps its own tagged batch (see importUri); this picker
        // lets the field team look at one imported list at a time instead of
        // every import blending into a single undivided pool.
        Spinner batchFilter=new Spinner(this);List<String>batchNames=new ArrayList<>();batchNames.add("كل القوائم");if(followup)batchNames.addAll(db.followupBatches(currentProjectId));ArrayAdapter<String>ba=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,batchNames);batchFilter.setAdapter(ba);batchFilter.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));
        if(followup){LinearLayout.LayoutParams batchParams=new LinearLayout.LayoutParams(dp(170),dp(44));batchParams.setMargins(dp(6),0,0,0);filterRow.addView(batchFilter,batchParams);}
        // Multiple engineers can contribute to the same project; this picker
        // narrows the list to one engineer's records at a time.
        Spinner engineerFilter=new Spinner(this);List<String>engineerNames=new ArrayList<>();engineerNames.add("كل المهندسين");engineerNames.addAll(db.engineerNames(currentProjectId));ArrayAdapter<String>ea=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,engineerNames);engineerFilter.setAdapter(ea);engineerFilter.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));
        if(engineerNames.size()>1){LinearLayout.LayoutParams engineerParams=new LinearLayout.LayoutParams(dp(150),dp(44));engineerParams.setMargins(dp(6),0,0,0);filterRow.addView(engineerFilter,engineerParams);}
        // F3: live counters strip (followup only) — total / remaining / each state / no-GPS / missing-address.
        final HorizontalScrollView counterScroll=new HorizontalScrollView(this);counterScroll.setHorizontalScrollBarEnabled(false);counterScroll.setClipToPadding(false);
        final LinearLayout counterStrip=new LinearLayout(this);counterStrip.setOrientation(LinearLayout.HORIZONTAL);counterStrip.setGravity(Gravity.CENTER_VERTICAL);counterScroll.addView(counterStrip);
        if(followup){LinearLayout.LayoutParams csp=new LinearLayout.LayoutParams(-1,-2);csp.setMargins(0,0,0,dp(6));page.addView(counterScroll,csp);}
        FrameLayout listBox=new FrameLayout(this);page.addView(listBox,new LinearLayout.LayoutParams(-1,0,1));ListView list=new ListView(this);list.setDividerHeight(dp(7));list.setDivider(new android.graphics.drawable.ColorDrawable(SURFACE));list.setPadding(0,dp(3),0,dp(12));list.setClipToPadding(false);listBox.addView(list,new FrameLayout.LayoutParams(-1,-1));TextView empty=text(followup?"لا توجد أسماء في المتابعة ضمن هذا الفلتر":"لا توجد دراسات",16,MUTED,true);empty.setGravity(Gravity.CENTER);listBox.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        Runnable refresh=()->{String state=filterValues[filter.getSelectedItemPosition()];String batch=followup&&batchFilter.getSelectedItemPosition()>0?(String)batchFilter.getSelectedItem():"all";String engineer=engineerFilter.getSelectedItemPosition()>0?(String)engineerFilter.getSelectedItem():"all";
            if(followup){
                // Fetch the whole followup list once, compute counters over it, then apply the F3 quick-filter in Java
                // so a derived state (followupState) can be filtered without a schema-specific SQL query.
                List<AppDatabase.Beneficiary> allRows=db.listStage(currentProjectId,search.getText().toString(),"followup","all",batch,engineer);
                updateFollowupCounters(counterStrip,allRows);
                List<AppDatabase.Beneficiary> shown=new ArrayList<>();for(AppDatabase.Beneficiary b:allRows)if(followupFilterMatch(b,state))shown.add(b);
                list.setAdapter(new BeneficiaryAdapter(shown,true));
            } else {
                List<AppDatabase.Beneficiary> rows=db.listStage(currentProjectId,search.getText().toString(),"study",state,batch,engineer);list.setAdapter(new BeneficiaryAdapter(rows,false));
            }
        };
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        batchFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        engineerFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        search.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}@Override public void onTextChanged(CharSequence s,int st,int b,int c){refresh.run();}@Override public void afterTextChanged(Editable e){}});refresh.run();}

    private final class BeneficiaryAdapter extends BaseAdapter{private final List<AppDatabase.Beneficiary>rows;private final boolean followup;BeneficiaryAdapter(List<AppDatabase.Beneficiary>r,boolean field){rows=r;followup=field;}@Override public int getCount(){return rows.size();}@Override public Object getItem(int p){return rows.get(p);}@Override public long getItemId(int p){return rows.get(p).id;}
        @Override public View getView(int position,View convert,ViewGroup parent){AppDatabase.Beneficiary b=rows.get(position);
            int stateColor;String stateLabel;String nextStep;
            if(followup){String st=followupState(b);stateColor=followupStateColor(st);stateLabel=followupStateLabel(st);nextStep=nextStepFollowupState(st);}
            else{stateColor=studyStatusColor(b.studyStatus);stateLabel=studyStatusLabel(b.studyStatus);nextStep=nextStepStudy(b.studyStatus);}
            LinearLayout card=column(dp(13));card.setBackground(round(SURFACE_CARD,16,stateColor,2));card.setMinimumHeight(dp(120));
            // Row 1 = the decision line: prominent status chip (+ position index in followup).
            LinearLayout top=new LinearLayout(MainActivity.this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
            TextView chip=text(stateLabel,12,stateColor,true);chip.setPadding(dp(10),dp(3),dp(10),dp(4));chip.setBackground(round((stateColor&0x00FFFFFF)|0x22000000,99,stateColor,1));top.addView(chip,new LinearLayout.LayoutParams(-2,-2));
            View grow=new View(MainActivity.this);top.addView(grow,new LinearLayout.LayoutParams(0,1,1f));
            if(followup){TextView idx=text("#"+(position+1),12,MUTED,true);top.addView(idx);}
            card.addView(top);
            // FINAL FIELD UI: name+address get more breathing room, and sit alongside a large, unmistakable entry
            // chevron (the same affordance language as the home shortcut cards) — the WHOLE card already opens the
            // file (see setOnClickListener below), but this makes that obvious at a glance instead of relying on
            // the small «➤ next step» line alone. Purely presentation — no change to what the tap does.
            LinearLayout bodyRow=new LinearLayout(MainActivity.this);bodyRow.setOrientation(LinearLayout.HORIZONTAL);bodyRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams bodyRowP=new LinearLayout.LayoutParams(-1,-2);bodyRowP.setMargins(0,dp(9),0,0);card.addView(bodyRow,bodyRowP);
            LinearLayout textCol=column(0);
            TextView name=text(safe(b.name,"مستفيد بلا اسم"),17,NAVY,true);textCol.addView(name);
            TextView loc=text(b.sector+(b.street.isEmpty()?"":" — "+shortStreet(b.street)),13,0xff0E7490,true);loc.setPadding(0,dp(4),0,0);textCol.addView(loc);
            bodyRow.addView(textCol,new LinearLayout.LayoutParams(0,-2,1f));
            TextView enterArrow=text("‹",28,stateColor,true);enterArrow.setPadding(dp(12),0,0,0);bodyRow.addView(enterArrow,new LinearLayout.LayoutParams(-2,-2));
            // The field-assistant line — the most important element after status: what the researcher must do now.
            LinearLayout.LayoutParams stepParams=new LinearLayout.LayoutParams(-1,-2);stepParams.setMargins(0,dp(9),0,0);TextView step=text("➤  "+nextStep,14,stateColor,true);card.addView(step,stepParams);
            // Visual progress indicator: continuous bar (followup) or a 4-step stepper (study).
            LinearLayout barRow=new LinearLayout(MainActivity.this);barRow.setOrientation(LinearLayout.HORIZONTAL);barRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams brp=new LinearLayout.LayoutParams(-1,-2);brp.setMargins(0,dp(9),0,0);
            if(followup){int pct=Math.max(0,Math.min(100,b.progressPercent));
                LinearLayout track=new LinearLayout(MainActivity.this);track.setOrientation(LinearLayout.HORIZONTAL);track.setBackground(round(0xffE6EDF1,99,0,0));
                View fill=new View(MainActivity.this);fill.setBackground(round(stateColor,99,0,0));View rest=new View(MainActivity.this);
                track.addView(fill,new LinearLayout.LayoutParams(0,dp(7),(float)pct));track.addView(rest,new LinearLayout.LayoutParams(0,dp(7),(float)(100-pct)));
                barRow.addView(track,new LinearLayout.LayoutParams(0,dp(7),1f));
                TextView pv=text(pct+"%",11,MUTED,true);pv.setPadding(dp(8),0,0,0);barRow.addView(pv);
                if(b.workDelivered){TextView dl=text("✔ مُسلَّم",11,0xff149176,true);dl.setPadding(dp(8),0,0,0);barRow.addView(dl);}
            }else{int idx=studyStageIndex(b.studyStatus);boolean superseded="superseded".equals(b.studyStatus);int fillCol=superseded?0xff9AA7B0:stateColor;
                LinearLayout track=new LinearLayout(MainActivity.this);track.setOrientation(LinearLayout.HORIZONTAL);
                for(int i=1;i<=4;i++){View cell=new View(MainActivity.this);cell.setBackground(round(i<=idx?fillCol:0xffE6EDF1,99,0,0));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(7),1f);if(i>1)cp.setMargins(dp(4),0,0,0);track.addView(cell,cp);}
                barRow.addView(track,new LinearLayout.LayoutParams(0,dp(7),1f));
                if(!superseded&&SpreadsheetImporter.AMOUNT_NEEDS_REVIEW.equals(b.amount)){TextView fr=text("⚠ مراجعة مالية",11,0xffC9851C,true);fr.setPadding(dp(8),0,0,0);barRow.addView(fr);}
            }
            card.addView(barRow,brp);
            card.setOnClickListener(v->showBeneficiary(b));return card;}}

    private void showAddBeneficiary(){
        // PROJECT SAVE ROOT — ONE FOLDER ONLY (§1): a study cannot begin before the single project folder is
        // chosen. If it's missing (or the saved one is no longer accessible), prompt once, then re-open this form.
        if(!rootUsable()){requireProjectRoot(this::showAddBeneficiary);return;}
        ScrollView scroll=new ScrollView(this);LinearLayout form=column(dp(18));scroll.addView(form);form.addView(text("بدء دراسة مستفيد جديد",21,NAVY,true));TextView intro=text("لا تحتاج إلى قائمة مسبقة. احفظ الاسم والبيانات الأولية، ثم افتح الدراسة لتصوير الأضرار واستكمال العنوان والمخطط والكميات.",12,MUTED,false);intro.setPadding(0,dp(4),0,dp(8));form.addView(intro);
        EditText name=formInput(form,"اسم المستفيد *","الاسم الثلاثي",InputType.TYPE_CLASS_TEXT,false);EditText registration=formInput(form,"رقم التسجيل","اختياري",InputType.TYPE_CLASS_TEXT,false);EditText phone=formInput(form,"رقم الهاتف","اختياري",InputType.TYPE_CLASS_PHONE,false);EditText address=formInput(form,"العنوان","يساعد في تحديد القطاع والمسار",InputType.TYPE_CLASS_TEXT,true);EditText notes=formInput(form,"ملاحظات الدراسة الأولية","وصف حالة المنزل أو أي ملاحظة ميدانية",InputType.TYPE_CLASS_TEXT,true);
        // فريق الدراسة + التاريخ الذكي — the study date pre-fills to today's device date and stays editable.
        TextView teamHead=text("فريق الدراسة",13,0xff0E7490,true);teamHead.setPadding(0,dp(14),0,dp(2));form.addView(teamHead);
        EditText eng1=formInput(form,"المهندس الأول *",currentEngineerName().isEmpty()?"اسم المهندس المسؤول":currentEngineerName(),InputType.TYPE_CLASS_TEXT,false);if(!currentEngineerName().isEmpty())eng1.setText(currentEngineerName());
        EditText eng2=formInput(form,"المهندس الثاني","اختياري",InputType.TYPE_CLASS_TEXT,false);
        EditText assistant=formInput(form,"الباحث الاجتماعي","اختياري",InputType.TYPE_CLASS_TEXT,false);
        EditText studyDate=dateField(form,"تاريخ الدراسة",todayYmd());
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("حفظ المستفيد",null).setNegativeButton("إلغاء",null).create();dialog.setOnShowListener(v->{themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.96),(int)(getResources().getDisplayMetrics().heightPixels*.92));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String cleanName=name.getText().toString().trim();if(cleanName.isEmpty()){name.setError("اسم المستفيد مطلوب");return;}try{AppDatabase.Beneficiary b=new AppDatabase.Beneficiary();b.name=cleanName;b.engineerName=currentEngineerName();b.registration=registration.getText().toString().trim();b.phone1=phone.getText().toString().trim();b.address=address.getText().toString().trim();b.notes=notes.getText().toString().trim();
                    b.studyEngineer1=eng1.getText().toString().trim();b.studyEngineer2=eng2.getText().toString().trim();b.fieldAssistant=assistant.getText().toString().trim();String sd=studyDate.getText().toString().trim();b.studyDate=sd.isEmpty()?todayYmd():sd;if(b.engineerName.isEmpty())b.engineerName=b.studyEngineer1;if(b.address.isEmpty()){b.matchStatus="أضيف يدوياً — العنوان بحاجة لمراجعة";}else{AddressResolution.Result resolved=resolveAddress(b.address,null);b.sector=resolved.sector;b.street=resolved.street;b.matchStatus="أضيف يدوياً | "+(resolved.needsReview?"بحاجة لمراجعة — ":"")+resolved.addressSource;b.confidence=resolved.confidence;b.sectorOrder=resolved.sectorIndex;b.routeOrder=resolved.routeIndex;b.streetOrder=resolved.streetIndex;b.alleyOrder=resolved.alleyIndex;b.addressSource=resolved.addressSource;}db.addBeneficiary(currentProjectId,b);dialog.dismiss();showPage(NAV_PEOPLE);showBeneficiary(b);toast("تمت إضافة المستفيد ويمكن بدء التصوير");}catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر حفظ المستفيد").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();}});});dialog.show();}
    private EditText formInput(LinearLayout form,String label,String hint,int type,boolean multiline){TextView caption=text(label,12,NAVY,true);caption.setPadding(0,dp(8),0,dp(4));form.addView(caption);EditText input=new EditText(this);input.setHint(hint);input.setInputType(type|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));input.setSingleLine(!multiline);if(multiline){input.setMinLines(2);input.setGravity(Gravity.TOP|Gravity.START);}input.setTextSize(14);input.setPadding(dp(12),dp(8),dp(12),dp(8));input.setBackground(round(CARD_BG,12,CARD_BORDER,1));form.addView(input,new LinearLayout.LayoutParams(-1,multiline?dp(82):dp(50)));return input;}

    /** Beneficiary file as a FULL-SCREEN page (not a small dialog): a guided header (name + status + 5-step
     * journey + next step + call/navigation) on top, the existing stage work in the middle, the house-plan card,
     * and secondary details collapsed at the bottom. Presentation-only reordering — it reuses the exact same
     * builders/actions (buildStudyDetails/buildFollowupDetails, housePlanCard, dial, openNavigation) and the same
     * AlertDialog handle they already dismiss on, so DB / import / status logic / BOQ are untouched. */
    private void showBeneficiary(AppDatabase.Beneficiary b){
        boolean study="study".equals(b.workflowStage);
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));box.setPadding(dp(14),dp(30),dp(14),dp(24));scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).create();beneficiaryDialog=dialog;
        final View[] workAnchor=new View[1]; // set to the house-plan card so the header's primary CTA can scroll to the work/action area just above it
        box.addView(benefTopBar(dialog));
        box.addView(benefHeader(b,dialog,study,scroll,workAnchor));
        box.addView(benefCompletionCard(b,scroll));
        // Study screen order (user-confirmed): بيانات → كميات → صور → رسم → تجهيز → اعتماد. For a study, the plan
        // card + «تجهيز» button are placed inside buildStudyDetails (after the photos); followup keeps its own layout.
        if(study)buildStudyDetails(box,b,dialog,workAnchor);
        else buildFollowupDetails(box,b,dialog,workAnchor); // F2: followup file — no study house-plan/drawing card
        box.addView(benefExtraInfo(b));
        dialog.setOnDismissListener(v->{if(beneficiaryDialog==dialog)beneficiaryDialog=null;});
        dialog.setOnShowListener(x->{android.view.Window w=dialog.getWindow();if(w!=null){w.setLayout(-1,-1);w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(SURFACE_CARD));w.setDimAmount(0f);}});
        dialog.show();
    }

    private View benefTopBar(final AlertDialog dialog){
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(0,0,0,dp(8));
        TextView back=text("‹ رجوع",14,0xff0E7490,true);back.setPadding(0,dp(4),dp(12),dp(4));back.setOnClickListener(v->dialog.dismiss());bar.addView(back);
        TextView title=text("ملف المستفيد",16,NAVY,true);title.setGravity(Gravity.END);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1f));
        return bar;
    }

    /** Current journey step (1..5): دراسة → اعتماد → تنفيذ → دفعة أولى → دفعة ثانية, derived read-only from the
     * existing status/payment fields — no state is written. */
    private int benefStep(AppDatabase.Beneficiary b){
        if("study".equals(b.workflowStage))return "approved".equals(b.studyStatus)?2:1;
        if(b.secondPaymentApprovedAt>0||b.secondPaymentReady)return 5;
        if(b.firstPaymentAvailableAt>0)return 4;
        return 3;
    }

    private View journeyStepper(int current){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(12),0,dp(4));
        String[] labels={"دراسة","اعتماد","تنفيذ","دفعة أولى","دفعة ثانية"};
        for(int i=0;i<5;i++){
            int step=i+1;boolean done=step<current,cur=step==current;
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);
            // dot + connector stubs → a continuous path: ✓━━✓━━●┄┄○┄┄○
            LinearLayout hrow=new LinearLayout(this);hrow.setOrientation(LinearLayout.HORIZONTAL);hrow.setGravity(Gravity.CENTER_VERTICAL);
            View lc=new View(this);lc.setBackgroundColor(step<=current?0xff149176:0x33FFFFFF);if(i==0)lc.setVisibility(View.INVISIBLE);hrow.addView(lc,new LinearLayout.LayoutParams(0,dp(3),1f));
            // done ✓ (filled green) · current ● (filled teal + white ring, most prominent) · coming ○ (hollow)
            TextView dot=text(done?"✓":cur?"●":"○",cur?13:12,done||cur?Color.WHITE:0x88FFFFFF,true);dot.setGravity(Gravity.CENTER);
            dot.setBackground(done?round(0xff149176,99,0,0):cur?round(0xff15A6BC,99,0xffFFFFFF,2):round(0,99,0x66FFFFFF,2));
            hrow.addView(dot,new LinearLayout.LayoutParams(dp(cur?28:24),dp(cur?28:24)));
            View rc=new View(this);rc.setBackgroundColor(step<current?0xff149176:0x33FFFFFF);if(i==4)rc.setVisibility(View.INVISIBLE);hrow.addView(rc,new LinearLayout.LayoutParams(0,dp(3),1f));
            cell.addView(hrow,new LinearLayout.LayoutParams(-1,-2));
            TextView lab=text(labels[i],9,cur?0xffFFFFFF:done?0xffCFE6DF:0xff9FB8C6,cur);lab.setGravity(Gravity.CENTER);lab.setPadding(0,dp(4),0,0);cell.addView(lab);
            row.addView(cell,new LinearLayout.LayoutParams(0,-2,1f));
        }
        return row;
    }

    /** Display form of a registration read off the white card: the source stores «<بادئة>-<الرقم>» (e.g. «1-00164908»)
     * but the field team reads it «<الرقم>-<البادئة>» (e.g. «00164908-1»). Reorders the two segments and wraps the
     * result in LTR marks so the Arabic RTL layout never visually reverses the digits. The stored value (used for
     * matching, folder names, backups) is NEVER changed — this is display-only. */
    private String formatRegistration(String reg){
        if(reg==null)return "";
        String r=reg.trim();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(\\d{1,2})-(\\d{3,})$").matcher(r);
        String out=m.matches()?(m.group(2)+"-"+m.group(1)):r;
        return out.isEmpty()?"":("‎"+out+"‎");
    }

    private View benefHeader(AppDatabase.Beneficiary b,final AlertDialog dialog,boolean study,final ScrollView scroll,final View[] workAnchor){
        LinearLayout card=column(dp(14));card.setBackground(round(BRAND,16,0,0));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(8));card.setLayoutParams(cp);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(safe(b.name,"مستفيد بلا اسم"),20,Color.WHITE,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams namep=new LinearLayout.LayoutParams(0,-2,1f);namep.setMarginEnd(dp(8));top.addView(name,namep);
        String label;int color;
        if(study){label=studyStatusLabel(b.studyStatus);color=studyStatusColor(b.studyStatus);}else{String st=followupState(b);label=followupStateLabel(st);color=followupStateColor(st);}
        TextView chip=text(label,10,Color.WHITE,true);chip.setMaxLines(1);chip.setPadding(dp(9),dp(3),dp(9),dp(3));chip.setBackground(round(color,99,0,0));top.addView(chip,new LinearLayout.LayoutParams(-2,-2));
        card.addView(top);
        boolean classified=!b.sector.isEmpty()&&!"غير مصنف".equals(b.sector);
        String loc=classified?(b.sector+(b.street.isEmpty()?"":" — "+shortStreet(b.street))):"⚠ يحتاج تصنيف عنوان";
        String sub=(b.registration.isEmpty()?"":"التسجيل "+formatRegistration(b.registration)+"  •  ")+loc+(b.revisionNo>1?"  •  نسخة "+b.revisionNo:"");
        TextView subTv=text(sub,11,classified?0xffBFD8E6:0xffF3C98C,!classified);subTv.setMaxLines(1);subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);subTv.setPadding(0,dp(4),0,0);card.addView(subTv);
        card.addView(journeyStepper(benefStep(b)));
        // primary action for the current state = prominent CTA that jumps to the work/action area below
        String next=study?nextStepStudy(b.studyStatus):nextStepFollowupState(followupState(b));
        Button primary=dialogButton("➤  "+next,0xff149176,v->{if(workAnchor[0]!=null)scroll.smoothScrollTo(0,Math.max(0,workAnchor[0].getTop()-dp(150)));});
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(50));pp.setMargins(0,dp(10),0,0);card.addView(primary,pp);
        TextView ctaHint=text("العمل المطلوب الآن",10,0xffAFC7D6,false);ctaHint.setGravity(Gravity.CENTER);ctaHint.setPadding(0,dp(4),0,0);card.addView(ctaHint);
        // secondary quick actions beside/under it: call + navigation (muted)
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);actions.setPadding(0,dp(6),0,0);
        if(!b.phone1.isEmpty())actions.addView(dialogButton("اتصال 1",0xff1B4E7A,v->dial(b.phone1)));
        if(!b.phone2.isEmpty())actions.addView(dialogButton("اتصال 2",0xff1B4E7A,v->dial(b.phone2)));
        actions.addView(dialogButton("📍 الموقع",0xff1B4E7A,v->openNavigation(b)));
        card.addView(actions);
        return card;
    }

    /** A quick "file completeness" read-out at the top of the work area — derived READ-ONLY from existing
     * fields (name/address/damage/quantities), writes nothing. Lets the researcher scan what's missing before
     * scrolling down. */
    private View benefCompletionCard(AppDatabase.Beneficiary b,ScrollView scroll){
        LinearLayout card=column(dp(12));card.setBackground(round(CARD_BG,14,CARD_BORDER,1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(8));card.setLayoutParams(cp);
        card.addView(text("حالة اكتمال الملف",13,NAVY,true));
        card.addView(checkRow("البيانات الأساسية",!safe(b.name,"").isEmpty(),null,scroll));
        card.addView(checkRow("العنوان",!b.address.isEmpty(),"f_address",scroll));
        card.addView(checkRow("وصف الأضرار",!b.damageNotes.isEmpty(),"f_damage",scroll));
        card.addView(checkRow("جدول الكميات",db.quantityCount(b.id)>0,"f_quantities",scroll));
        return card;
    }
    /** A completeness line. When the item is missing AND a field tag is given, tapping it jumps straight to that
     * field below (focusing an EditText so the researcher can fill it) — pure navigation, no data change. */
    private View checkRow(String label,boolean ok,final String tag,final ScrollView scroll){
        TextView t=text((ok?"✓ ":"⚠ ")+label+(ok?"":" — ناقص (اضغط للانتقال)"),12,ok?0xff149176:0xffC9851C,!ok);t.setPadding(0,dp(5),0,dp(1));
        if(!ok&&tag!=null)t.setOnClickListener(v->scrollToTag(scroll,tag));
        return t;
    }
    private void scrollToTag(final ScrollView scroll,String tag){
        final View v=scroll.findViewWithTag(tag);if(v==null)return;
        scroll.post(()->{
            int y=0;View cur=v;while(cur!=null&&cur!=scroll){y+=cur.getTop();android.view.ViewParent p=cur.getParent();cur=(p instanceof View)?(View)p:null;}
            scroll.smoothScrollTo(0,Math.max(0,y-dp(90)));
            if(v instanceof EditText)v.requestFocus();
        });
    }

    private View benefExtraInfo(AppDatabase.Beneficiary b){
        LinearLayout wrap=column(dp(0));
        final LinearLayout body=column(dp(4));body.setVisibility(View.GONE);
        body.addView(info("القطاع",b.sector));body.addView(info("الشارع المرجعي",b.street));body.addView(info("الهاتف",b.phone1));body.addView(info("الهاتف الثاني",b.phone2));body.addView(info("رقم التسجيل",formatRegistration(b.registration)));if(!b.engineerName.isEmpty())body.addView(info("المهندس/الباحث",b.engineerName));
        if(!b.phone1.isEmpty()||!b.phone2.isEmpty()){Button sync=smallButton("مزامنة جهة الاتصال",0xff7A5CB8);sync.setOnClickListener(v->syncBeneficiaryContact(b));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(46));sp.setMargins(0,dp(6),0,0);body.addView(sync,sp);}
        final TextView hdr=text("▸ بيانات إضافية",13,NAVY,true);hdr.setPadding(0,dp(14),0,dp(4));
        hdr.setOnClickListener(v->{boolean vis=body.getVisibility()==View.VISIBLE;body.setVisibility(vis?View.GONE:View.VISIBLE);hdr.setText((vis?"▸":"▾")+" بيانات إضافية");});
        wrap.addView(hdr);wrap.addView(body);
        return wrap;
    }

    private void buildStudyDetails(LinearLayout box,AppDatabase.Beneficiary b,AlertDialog dialog,View[] workAnchor){
        boolean formEditable="draft".equals(b.studyStatus);
        // Once a revision is approved (or superseded by a later one) it IS the historical
        // snapshot — quantities/BOQ/plan must freeze here so later edits can only happen
        // through a brand new corrected revision, never by silently mutating this record.
        boolean historyLocked="approved".equals(b.studyStatus)||"superseded".equals(b.studyStatus);
        TextView studyTitle=text("بيانات دراسة المنزل"+(historyLocked?" (Snapshot معتمد — للقراءة فقط)":""),17,NAVY,true);studyTitle.setPadding(0,dp(8),0,0);box.addView(studyTitle);
        box.addView(studyTeamCard(b));
        EditText address=formInput(box,"العنوان التفصيلي *","القطاع، الشارع، الجادة، الزقاق، رقم المنزل وأقرب معلم",InputType.TYPE_CLASS_TEXT,true);address.setText(b.address);
        // Collapsed to 2 lines to save space; tapping (focus) expands it. Display-only — text/save unchanged.
        address.setMaxLines(2);address.setOnFocusChangeListener((v,f)->address.setMaxLines(f?10:2));address.setTag("f_address");
        box.addView(addressStatusCard(b,dialog));
        EditText damage=formInput(box,"وصف الأضرار *","الغرف والأسقف والجدران والأبواب والنوافذ والأعمال المطلوبة",InputType.TYPE_CLASS_TEXT,true);damage.setText(b.damageNotes);damage.setTag("f_damage");
        box.addView(gpsLocationSection(b));
        TextView quantitiesTitle=text("جدول الكميات المرجعي *",12,NAVY,true);quantitiesTitle.setPadding(0,dp(10),0,dp(4));quantitiesTitle.setTag("f_quantities");box.addView(quantitiesTitle);
        LinearLayout quantityCard=column(dp(12));quantityCard.setBackground(round(CARD_BG,12,CARD_BORDER,1));box.addView(quantityCard,new LinearLayout.LayoutParams(-1,-2));
        TextView quantitySummary=text(quantitySummary(b.id),13,TEXT,true);quantityCard.addView(quantitySummary);
        TextView amountSummary=text(paymentSummary(db.quantityTotal(b.id)),12,0xff0E7490,true);amountSummary.setPadding(0,dp(5),0,dp(4));quantityCard.addView(amountSummary);
        Button editQuantities=smallButton(historyLocked?"عرض جدول الكميات المعتمد (Snapshot)":db.quantityCount(b.id)==0?"تعبئة جدول الكميات (37 بنداً)":"تعديل جدول الكميات",0xff7A5CB8);quantityCard.addView(editQuantities,new LinearLayout.LayoutParams(-1,dp(48)));
        // P1: export the standalone quantities workbook (project template + engineer's quantities only)
        Button exportQty=smallButton("⤓ تصدير ملف الكميات (Excel)",0xff149176);exportQty.setEnabled(db.quantityCount(b.id)>0);exportQty.setAlpha(db.quantityCount(b.id)>0?1f:.5f);exportQty.setOnClickListener(v->exportQuantitiesXlsx(b));LinearLayout.LayoutParams eqp=new LinearLayout.LayoutParams(-1,dp(46));eqp.setMargins(0,dp(6),0,0);quantityCard.addView(exportQty,eqp);
        Runnable refreshQuantitySummary=()->{int count=db.quantityCount(b.id);quantitySummary.setText(quantitySummary(b.id));amountSummary.setText(paymentSummary(db.quantityTotal(b.id)));editQuantities.setText(count==0?"تعبئة جدول الكميات (37 بنداً)":"تعديل جدول الكميات");exportQty.setEnabled(count>0);exportQty.setAlpha(count>0?1f:.5f);};
        editQuantities.setOnClickListener(v->showQuantityEditor(b,!historyLocked,historyLocked?null:refreshQuantitySummary));
        // صور — kept right after the quantities so photography and quantities are consecutive stages.
        box.addView(beforeReferenceCard(b)); // «صور قبل الترميم المرجعية» — imported reference photos (read-only)
        TextView photosTitle=text("صور الأضرار وقبل الترميم",17,NAVY,true);photosTitle.setPadding(0,dp(14),0,dp(5));box.addView(photosTitle);box.addView(text(rootUsable()?"الحفظ داخل مجلد المشروع: "+packageRootDisplay():"اختر مجلد المشروع أولاً — يُحفظ داخله كل شيء تلقائياً",11,rootUsable()?0xff149176:0xffC9851C,true));box.addView(photoStageCard(b,BeneficiaryPhotoManager.BEFORE,0xff0E7490,!historyLocked));
        // مخطط المنزل (الرسم) — created inside the app; external-file attachment was removed (every file drawn from scratch).
        View houseCard=housePlanCard(b);if(workAnchor!=null)workAnchor[0]=houseCard;box.addView(houseCard);
        // تجهيز ملف المستفيد — after the drawing; gathers الكميات + الرسم + الصور + backup into the delivery folder.
        Button prepPkg=smallButton("📦 تجهيز ملف المستفيد",0xff149176);prepPkg.setOnClickListener(v->prepareBeneficiaryPackage(b));LinearLayout.LayoutParams ppp=new LinearLayout.LayoutParams(-1,dp(52));ppp.setMargins(0,dp(10),0,0);box.addView(prepPkg,ppp);
        Button openPkg=smallButton("📂 فتح مجلد المستفيد",0xff0E7490);openPkg.setOnClickListener(v->openBeneficiaryFolder(b));LinearLayout.LayoutParams opp=new LinearLayout.LayoutParams(-1,dp(48));opp.setMargins(0,dp(8),0,0);box.addView(openPkg,opp);
        if(formEditable){Button save=smallButton("حفظ بيانات الدراسة",0xff0E7490);save.setOnClickListener(v->{saveStudy(b,address,damage);dialog.dismiss();renderActive();toast("تم حفظ بيانات الدراسة");});LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(-1,dp(50));saveParams.setMargins(0,dp(10),0,0);box.addView(save,saveParams);}
        else{address.setEnabled(false);damage.setEnabled(false);}
        box.addView(studyLifecycleAction(b,address,damage,dialog));
    }

    /** يبني زر إجراء دورة الحياة المناسب حسب study_status الحالي فقط — لا يوجد أي مسار يقفز فوق مراجعة مكتبية:
     * مسودة → (تحقق اكتمال) → جاهزة للمراجعة → مراجعة مكتبية → معتمدة، ثم "إصدار نسخة مصححة" اختياري بعد الاعتماد. */
    private View studyLifecycleAction(AppDatabase.Beneficiary b,EditText address,EditText damage,AlertDialog dialog){
        switch(b.studyStatus){
            case "draft":{
                Button submit=smallButton("اعتماد الدراسة وإرسالها للمراجعة المكتبية",0xff149176);
                submit.setOnClickListener(v->approveAndSendToOfficeReview(b,address,damage,dialog));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(6),0,dp(8));submit.setLayoutParams(p);return submit;
            }
            case "ready":{
                Button start=smallButton("بدء المراجعة المكتبية",0xffC9851C);
                start.setOnClickListener(v->{if(db.submitForOfficeReview(b.id)){dialog.dismiss();renderActive();toast("انتقلت الدراسة إلى المراجعة المكتبية");}else{toast("تعذّر بدء المراجعة — تحقق من حالة الدراسة");}});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(6),0,dp(8));start.setLayoutParams(p);return start;
            }
            case "office_review":{
                Button approve=smallButton("اعتماد نهائي وتجهيز للإرسال",0xff149176);
                approve.setOnClickListener(v->{if(db.approveStudy(b.id)){dialog.dismiss();renderActive();toast("تم اعتماد الدراسة نهائياً");}else{toast("تعذّر الاعتماد — تحقق من حالة الدراسة");}});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(6),0,dp(8));approve.setLayoutParams(p);return approve;
            }
            case "approved":{
                Button revise=smallButton("إصدار نسخة مصححة",0xff7A5CB8);
                revise.setOnClickListener(v->confirmCorrectedRevision(b,dialog));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(6),0,dp(8));revise.setLayoutParams(p);return revise;
            }
            default:{
                TextView banner=text("هذه نسخة سابقة استُبدلت بنسخة مصححة — للاطلاع فقط",12,0xff6B7280,true);
                banner.setPadding(0,dp(10),0,dp(8));return banner;
            }
        }
    }

    private void confirmCorrectedRevision(AppDatabase.Beneficiary b,AlertDialog dialog){
        new AlertDialog.Builder(this).setTitle("إصدار نسخة مصححة").setMessage("سيتم إنشاء نسخة جديدة قابلة للتعديل من هذه الدراسة المعتمدة، مع نقل كل الصور وجدول الكميات إليها، وتتحول النسخة الحالية إلى نسخة سابقة محفوظة في السجل.")
                .setPositiveButton("إصدار النسخة",(d,w)->{try{long newId=db.createCorrectedRevision(b.id,currentEngineerName());dialog.dismiss();renderActive();AppDatabase.Beneficiary fresh=db.beneficiary(newId);toast("تم إصدار نسخة مصححة جديدة (رقم "+fresh.revisionNo+")");showBeneficiary(fresh);}catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر إصدار النسخة المصححة").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();}})
                .setNegativeButton("إلغاء",null).show();
    }

    private void buildFollowupDetails(LinearLayout box,AppDatabase.Beneficiary b,AlertDialog dialog,View[] workAnchor){double total=db.quantityTotal(b.id);box.addView(followupAddressCard(b));box.addView(addressStatusCard(b,dialog));box.addView(info("الإجمالي المعتمد",total>0?QuantityCatalog.money(total)+" $":b.amount));box.addView(info("الدفعة الأولى 60%",total>0?QuantityCatalog.firstPayment(total)+" $":"—"));box.addView(info("الدفعة الثانية 40%",total>0?QuantityCatalog.secondPayment(total)+" $":"—"));box.addView(info("وصف الأضرار",b.damageNotes));box.addView(info("جدول الكميات",quantitySummary(b.id)));if(db.quantityCount(b.id)>0){Button boq=smallButton("تسليم أعمال BOQ ومتابعة التنفيذ",0xff0E7490);boq.setOnClickListener(v->showBoqDelivery(b));box.addView(boq,new LinearLayout.LayoutParams(-1,dp(46)));}
        box.addView(followupDeliveryCard(b,dialog)); // F3: «حالة المتابعة والتسليم» — the primary delivery control
        box.addView(deliveryLogCard(b));             // F3: «سجل التسليم»
        box.addView(worksRequiredCard(b,dialog));    // F4: «الأعمال المطلوبة» — per-item execution status
        box.addView(executionPriorityCard(b));       // F4: «اقتراح أولوية التنفيذ»
        box.addView(paymentSuggestionCard(b));       // F4: «اقتراح ضمن الدفعة»
        box.addView(gpsLocationSection(b));
        box.addView(deliveryTimelineSection(b,dialog));
        // FINAL FIELD USABILITY FIX §3: «الوثائق المستوردة» card is NO LONGER shown in the followup UI (the images
        // are still imported and stored — just not surfaced here). §4: the reference «قبل الترميم» photos move down
        // to sit directly under the «قبل الترميم» capture card so the engineer sees them as its reference.
        TextView photosTitle=text("توثيق تنفيذ الترميم",17,NAVY,true);photosTitle.setPadding(0,dp(14),0,dp(5));if(workAnchor!=null)workAnchor[0]=photosTitle;box.addView(photosTitle);box.addView(text(rootUsable()?"الحفظ داخل مجلد المشروع: "+packageRootDisplay():"اختر مجلد المشروع أولاً — يُحفظ داخله كل شيء تلقائياً",11,rootUsable()?0xff149176:0xffC9851C,true));box.addView(photoStageCard(b,BeneficiaryPhotoManager.BEFORE,0xff0E7490,false));box.addView(beforeReferenceCard(b));box.addView(photoStageCard(b,BeneficiaryPhotoManager.DURING,0xffC9851C,true));box.addView(photoStageCard(b,BeneficiaryPhotoManager.AFTER,0xff149176,true));
        EditText progress=formInput(box,"نسبة الإنجاز %","من 0 إلى 100",InputType.TYPE_CLASS_NUMBER,false);progress.setText(String.valueOf(b.progressPercent));
        EditText notes=new EditText(this);notes.setHint("ملاحظات متابعة التنفيذ");notes.setText(b.notes);notes.setMinLines(3);notes.setGravity(Gravity.TOP|Gravity.START);notes.setBackground(round(CARD_BG,12,CARD_BORDER,1));box.addView(notes,new LinearLayout.LayoutParams(-1,dp(95)));
        // F3: delivery + status are handled by «حالة المتابعة والتسليم» above; here we only save progress/notes.
        Button saveProgress=smallButton("حفظ نسبة الإنجاز والملاحظات",0xff0E7490);saveProgress.setOnClickListener(v->{saveProgressNotes(b,progress,notes);dialog.dismiss();renderActive();toast("تم حفظ نسبة الإنجاز والملاحظات");});
        LinearLayout.LayoutParams spp=new LinearLayout.LayoutParams(-1,dp(48));spp.setMargins(0,dp(8),0,0);box.addView(saveProgress,spp);
        box.addView(secondPaymentSection(b,dialog));
    }

    // ===== F3: «حالة المتابعة والتسليم» + «سجل التسليم» ===================================================
    /** F3: the primary followup control — current state + «تغيير الحالة» (6 states) + «تسليم الأعمال» (with a
     * confirm) + delivery date + internal note. Delivery/status live ONLY here now (the old checkbox/status buttons
     * were removed) so «من تم تسليمه» is unambiguous. */
    private View followupDeliveryCard(AppDatabase.Beneficiary b,AlertDialog dialog){
        String st=followupState(b);int col=followupStateColor(st);
        LinearLayout card=column(dp(10));card.setBackground(round(CARD_BG,14,col,2));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(4));card.setLayoutParams(p);
        card.addView(text("حالة المتابعة والتسليم",15,NAVY,true));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(6),0,dp(2));
        row.addView(text("الحالة الحالية",12,MUTED,true),new LinearLayout.LayoutParams(0,-2,1f));
        TextView chip=text(followupStateLabel(st),12,col,true);chip.setPadding(dp(12),dp(4),dp(12),dp(4));chip.setBackground(round((col&0x00FFFFFF)|0x18000000,99,col,1));row.addView(chip,new LinearLayout.LayoutParams(-2,-2));
        card.addView(row);
        if(AppDatabase.FS_DELIVERED.equals(st)&&b.deliveryConfirmedAt>0)card.addView(text("تاريخ التسليم: "+new SimpleDateFormat("yyyy-MM-dd  HH:mm",Locale.US).format(new Date(b.deliveryConfirmedAt)),12,0xff2E7D32,true));
        if(b.deliveryNote!=null&&!b.deliveryNote.trim().isEmpty())card.addView(text("ملاحظات تنفيذ داخلية: "+b.deliveryNote,11,MUTED,false));
        Button change=smallButton("تغيير الحالة",0xff0E7490);change.setOnClickListener(v->showFollowupStatePicker(b,dialog));LinearLayout.LayoutParams cpp=new LinearLayout.LayoutParams(-1,dp(46));cpp.setMargins(0,dp(8),0,0);card.addView(change,cpp);
        if(!AppDatabase.FS_DELIVERED.equals(st)){Button deliver=smallButton("✔ تسليم الأعمال",0xff2E7D32);deliver.setOnClickListener(v->confirmDeliver(b,dialog));LinearLayout.LayoutParams dpp=new LinearLayout.LayoutParams(-1,dp(50));dpp.setMargins(0,dp(8),0,0);card.addView(deliver,dpp);}
        return card;
    }
    private void showFollowupStatePicker(AppDatabase.Beneficiary b,AlertDialog dialog){
        final String[] states={AppDatabase.FS_NOT_STARTED,AppDatabase.FS_IN_PROGRESS,AppDatabase.FS_READY,AppDatabase.FS_DELIVERED,AppDatabase.FS_REVIEW,AppDatabase.FS_DEFERRED};
        String[] labels=new String[states.length];for(int i=0;i<states.length;i++)labels[i]=followupStateLabel(states[i]);
        new AlertDialog.Builder(this).setTitle("تغيير حالة المتابعة").setItems(labels,(d,w)->{
            if(AppDatabase.FS_DELIVERED.equals(states[w])){confirmDeliver(b,dialog);return;}
            db.setFollowupStatus(b.id,states[w],currentEngineerName(),null);
            if(dialog!=null)dialog.dismiss();renderActive();AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null)showBeneficiary(fresh);
            toast("تم تحديث الحالة إلى «"+followupStateLabel(states[w])+"»");
        }).setNegativeButton("إلغاء",null).show();
    }
    private void confirmDeliver(AppDatabase.Beneficiary b,AlertDialog dialog){
        LinearLayout box=column(dp(10));box.setPadding(dp(20),dp(10),dp(20),dp(4));
        box.addView(text("هل تريد اعتماد هذا المستفيد كتم تسليمه؟",14,TEXT,true));
        final EditText note=new EditText(this);note.setHint("ملاحظات تنفيذ داخلية (اختياري)");note.setText(b.deliveryNote==null?"":b.deliveryNote);note.setTextSize(14);note.setPadding(dp(12),dp(8),dp(12),dp(8));note.setBackground(round(CARD_BG,12,CARD_BORDER,1));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(80));np.setMargins(0,dp(10),0,0);box.addView(note,np);
        new AlertDialog.Builder(this).setTitle("تسليم الأعمال").setView(box)
            .setPositiveButton("تأكيد التسليم",(d,w)->{db.setFollowupStatus(b.id,AppDatabase.FS_DELIVERED,currentEngineerName(),note.getText().toString().trim());if(dialog!=null)dialog.dismiss();renderActive();AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null)showBeneficiary(fresh);toast("تم تسجيل تسليم الأعمال");})
            .setNegativeButton("إلغاء",null).show();
    }
    private void saveProgressNotes(AppDatabase.Beneficiary b,EditText progress,EditText notes){
        int pct;try{pct=Integer.parseInt(progress.getText().toString().trim());}catch(Exception e){pct=b.progressPercent;}pct=Math.max(0,Math.min(100,pct));
        db.updateVisit(b.id,b.visitStatus==null||b.visitStatus.isEmpty()?"pending":b.visitStatus,pct,notes.getText().toString());
    }
    /** F3: «سجل التسليم» — every recorded followup-status change (date/time/status/engineer/note), newest first. */
    private View deliveryLogCard(AppDatabase.Beneficiary b){
        java.util.List<AppDatabase.FollowupLogEntry> log=db.followupLog(b.id);
        if(log.isEmpty())return new View(this);
        LinearLayout card=column(dp(6));card.setBackground(round(0xffF7F9FB,14,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(6));card.setLayoutParams(p);
        card.addView(text("سجل التسليم",14,NAVY,true));
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd  HH:mm",Locale.US);
        for(AppDatabase.FollowupLogEntry e:log){
            card.addView(text("• "+fmt.format(new Date(e.at))+" — "+followupStateLabel(e.status)+(e.engineer==null||e.engineer.isEmpty()?"":" ("+e.engineer+")"),12,TEXT,false));
            if(e.note!=null&&!e.note.trim().isEmpty())card.addView(text("    ملاحظة: "+e.note,11,MUTED,false));
        }
        return card;
    }

    // ===== F4: «الأعمال المطلوبة» + «اقتراح أولوية التنفيذ» + «اقتراح ضمن الدفعة» ===========================
    /** One required work = a BOQ item with quantity>0 for this beneficiary, plus its unit price (from the imported
     * BOQ / quantities — never an external price) and approximate cost. */
    private static final class Work{final QuantityCatalog.Item item;final int itemNo;final double quantity,unitPrice,cost;
        Work(QuantityCatalog.Item item,int itemNo,double quantity,double unitPrice){this.item=item;this.itemNo=itemNo;this.quantity=quantity;this.unitPrice=unitPrice;this.cost=quantity*unitPrice;}
        String name(){return item==null?("بند "+itemNo):item.name;}String unit(){return item==null?"":item.unit;}}
    private List<Work> beneficiaryWorks(AppDatabase.Beneficiary b){
        List<Work> out=new ArrayList<>();
        for(AppDatabase.QuantityEntry e:db.quantities(b.id)){
            if(e.quantity<=0)continue;
            QuantityCatalog.Item it=catalogItem(e.itemNo);
            double price=e.unitPrice>0?e.unitPrice:(it==null?0:it.unitPrice); // ONLY BOQ/quantities prices — no external prices
            out.add(new Work(it,e.itemNo,e.quantity,price));
        }
        return out;
    }
    private String workStatusLabel(String s){switch(s==null?"":s){case "in_progress":return "قيد التنفيذ";case "executed":return "تم التنفيذ";case "needs_review":return "يحتاج مراجعة";default:return "لم ينفذ";}}
    private int workStatusColor(String s){switch(s==null?"":s){case "in_progress":return 0xff0E7490;case "executed":return 0xff2E7D32;case "needs_review":return 0xffBD4868;default:return 0xffC9851C;}}

    /** F4: «الأعمال المطلوبة» — each BOQ work with quantity/unit/price/cost + a per-item EXECUTION status the engineer
     * can change (لم ينفذ/قيد التنفيذ/تم التنفيذ/يحتاج مراجعة) + internal note. Separate from the F3 final delivery. */
    private View worksRequiredCard(AppDatabase.Beneficiary b,AlertDialog dialog){
        LinearLayout card=column(dp(10));card.setBackground(round(0xffFBF7F0,14,0xffE3C58A,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(4));card.setLayoutParams(p);
        card.addView(text("الأعمال المطلوبة",15,NAVY,true));
        List<Work> works=beneficiaryWorks(b);
        if(works.isEmpty()){card.addView(text("لا توجد أعمال/كميات مسجلة لهذا المستفيد",12,MUTED,false));return card;}
        java.util.Map<Integer,AppDatabase.WorkStatusEntry> st=db.workStatuses(b.id);
        for(Work w:works){
            LinearLayout item=column(dp(3));item.setBackground(round(SURFACE_CARD,10,0xffE7DCC3,1));item.setPadding(dp(11),dp(9),dp(11),dp(9));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.setMargins(0,dp(6),0,dp(1));item.setLayoutParams(ip);
            item.addView(text(w.itemNo+". "+w.name(),14,BLUE,true));
            item.addView(text("الكمية: "+QuantityCatalog.quantityNumber(w.quantity)+" "+w.unit()+"  •  السعر: "+QuantityCatalog.money(w.unitPrice)+" $  •  التكلفة: "+QuantityCatalog.money(w.cost)+" $",11,MUTED,false));
            String cur=st.containsKey(w.itemNo)?st.get(w.itemNo).status:AppDatabase.WS_NOT_EXECUTED;
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(6),0,0);
            row.addView(text("الحالة",12,MUTED,true),new LinearLayout.LayoutParams(0,-2,1f));
            int wc=workStatusColor(cur);TextView chip=text(workStatusLabel(cur),11,wc,true);chip.setPadding(dp(10),dp(3),dp(10),dp(3));chip.setBackground(round((wc&0x00FFFFFF)|0x18000000,99,wc,1));row.addView(chip,new LinearLayout.LayoutParams(-2,-2));
            item.addView(row);
            String note=st.containsKey(w.itemNo)?st.get(w.itemNo).note:"";
            if(note!=null&&!note.trim().isEmpty())item.addView(text("ملاحظات تنفيذ داخلية: "+note,11,MUTED,false));
            // §3.7: show WHERE the damage is for this item so the followup engineer knows where to work — read-only.
            java.util.List<AppDatabase.DamageLocation> dls=db.damageLocations(b.id,w.itemNo);
            if(!dls.isEmpty()){
                item.addView(text("أماكن الضرر:",11,0xff23507A,true));
                for(AppDatabase.DamageLocation dl:dls){item.addView(text("• "+dl.place+": "+QuantityCatalog.quantityNumber(dl.qty)+" "+w.unit()+(dl.damageType.isEmpty()?"":" — "+dl.damageType),11,MUTED,false));}
            }
            Button change=smallButton("تغيير حالة العمل",0xff0E7490);change.setTextSize(12);change.setOnClickListener(v->showWorkStatusPicker(b,w.itemNo,w.name(),dialog));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(42));bp.setMargins(0,dp(6),0,0);item.addView(change,bp);
            card.addView(item);
        }
        return card;
    }
    private void showWorkStatusPicker(AppDatabase.Beneficiary b,int itemNo,String name,AlertDialog parent){
        AppDatabase.WorkStatusEntry cur=db.workStatuses(b.id).get(itemNo);
        LinearLayout box=column(dp(8));box.setPadding(dp(20),dp(10),dp(20),dp(4));
        final EditText note=new EditText(this);note.setHint("ملاحظات تنفيذ داخلية (اختياري)");note.setText(cur==null?"":cur.note);note.setTextSize(14);note.setPadding(dp(12),dp(8),dp(12),dp(8));note.setBackground(round(CARD_BG,12,CARD_BORDER,1));box.addView(note,new LinearLayout.LayoutParams(-1,dp(70)));
        final String[] states={AppDatabase.WS_NOT_EXECUTED,AppDatabase.WS_IN_PROGRESS,AppDatabase.WS_EXECUTED,AppDatabase.WS_NEEDS_REVIEW};
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("حالة العمل: "+name).setView(box).setNegativeButton("إلغاء",null).create();
        for(String s:states){Button btn=smallButton(workStatusLabel(s),workStatusColor(s));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(46));bp.setMargins(0,dp(6),0,0);btn.setOnClickListener(v->{db.setWorkStatus(b.id,itemNo,s,note.getText().toString().trim());dlg.dismiss();if(parent!=null)parent.dismiss();AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null)showBeneficiary(fresh);toast("تم حفظ حالة العمل");});box.addView(btn,bp);}
        dlg.show();
    }

    /** F4: classify a BOQ item into one of the engineer's 9 priority buckets (10 = «أعمال أخرى» when unknown). Uses
     * the BOQ category/name only — a helper suggestion, never a final decision. */
    private int priorityBucket(QuantityCatalog.Item it){
        if(it==null)return 10;
        String n=it.name==null?"":it.name, cat=it.category==null?"":it.category;
        if(n.contains("طينة")||n.contains("لياسة"))return 6;      // plaster (طينة اسمنتية/غراوت)
        if(cat.contains("هيكلي"))return 1;                        // بيتون/تدعيم/بلوك/مداخن → safety/structure
        if(cat.contains("صحي"))return 2;
        if(cat.contains("كهرب"))return 3;
        if(cat.contains("عزل"))return 4;
        if(cat.contains("نجار")||cat.contains("ألمنيوم")||cat.contains("حديد")||cat.contains("زجاج"))return 5;
        if(cat.contains("الأرضية")||cat.contains("الجدران"))return 7;
        if(cat.contains("دهان"))return 8;
        return 10;
    }
    private String priorityLabel(int b){switch(b){case 1:return "١. أعمال السلامة والخطر المباشر";case 2:return "٢. الصحية والصرف والمياه";case 3:return "٣. الكهرباء الأساسية والخطرة";case 4:return "٤. العزل وتسرب المياه والسقف";case 5:return "٥. الأبواب والشبابيك والحماية";case 6:return "٦. اللياسة / السواد الأساسية";case 7:return "٧. البلاط والأرضيات الضرورية";case 8:return "٨. الدهان والتشطيبات";case 9:return "٩. التحسينات الأقل أولوية";default:return "أعمال أخرى";}}

    private View executionPriorityCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(8));card.setBackground(round(CARD_BG,14,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(4));card.setLayoutParams(p);
        card.addView(text("اقتراح أولوية التنفيذ",15,NAVY,true));
        card.addView(text("هذا اقتراح مساعد للمهندس، وليس قراراً نهائياً.",11,0xffC9851C,true));
        List<Work> works=beneficiaryWorks(b);
        if(works.isEmpty()){card.addView(text("لا توجد أعمال لعرض أولويتها",12,MUTED,false));return card;}
        java.util.TreeMap<Integer,List<Work>> byBucket=new java.util.TreeMap<>();
        for(Work w:works){int bk=priorityBucket(w.item);List<Work> g=byBucket.get(bk);if(g==null){g=new ArrayList<>();byBucket.put(bk,g);}g.add(w);}
        for(java.util.Map.Entry<Integer,List<Work>> e:byBucket.entrySet()){
            card.addView(text(priorityLabel(e.getKey()),13,0xff0E7490,true));
            double sub=0;for(Work w:e.getValue()){sub+=w.cost;card.addView(text("   • "+w.name()+" — "+QuantityCatalog.money(w.cost)+" $",11,TEXT,false));}
            card.addView(text("   المجموع: "+QuantityCatalog.money(sub)+" $",11,MUTED,true));
        }
        return card;
    }

    private View paymentSuggestionCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(8));card.setBackground(round(0xffF0F6F4,14,0xff9FCBBE,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(6));card.setLayoutParams(p);
        card.addView(text("اقتراح ضمن الدفعة",15,NAVY,true));
        card.addView(text("الاقتراح حسب أسعار BOQ فقط — أداة مساعدة للمهندس وليس قراراً نهائياً.",11,0xffC9851C,true));
        double total=db.quantityTotal(b.id);long defaultPay=total>0?QuantityCatalog.firstPayment(total):0;
        card.addView(text("قيمة الدفعة الأولى ($):",12,MUTED,true));
        final EditText payIn=new EditText(this);payIn.setHint("مثال: "+(defaultPay>0?defaultPay:1000));payIn.setInputType(InputType.TYPE_CLASS_NUMBER);payIn.setTextSize(14);payIn.setPadding(dp(12),dp(8),dp(12),dp(8));payIn.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));if(defaultPay>0)payIn.setText(String.valueOf(defaultPay));card.addView(payIn,new LinearLayout.LayoutParams(-1,dp(48)));
        final LinearLayout result=column(dp(3));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(8),0,0);
        Button compute=smallButton("اقترِح الأعمال ضمن الدفعة",0xff149176);compute.setOnClickListener(v->renderPaymentSuggestion(b,payIn.getText().toString(),result));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(46));cp.setMargins(0,dp(8),0,0);card.addView(compute,cp);
        card.addView(result,rp);
        if(defaultPay>0)renderPaymentSuggestion(b,String.valueOf(defaultPay),result);
        else result.addView(text("أدخل قيمة الدفعة لاقتراح الأعمال الممكن تنفيذها ضمنها.",12,MUTED,false));
        return card;
    }
    private void renderPaymentSuggestion(AppDatabase.Beneficiary b,String payText,LinearLayout out){
        out.removeAllViews();
        double pay;try{pay=Double.parseDouble(payText.trim().replace(",",""));}catch(Exception e){pay=0;}
        if(pay<=0){out.addView(text("أدخل قيمة الدفعة لاقتراح الأعمال الممكن تنفيذها ضمنها.",12,MUTED,false));return;}
        List<Work> works=beneficiaryWorks(b);
        java.util.Collections.sort(works,(a,c)->Integer.compare(priorityBucket(a.item),priorityBucket(c.item)));
        double spent=0;List<Work> within=new ArrayList<>(),deferred=new ArrayList<>();
        for(Work w:works){if(spent+w.cost<=pay){within.add(w);spent+=w.cost;}else deferred.add(w);}
        out.addView(text("قيمة الدفعة: "+QuantityCatalog.money(pay)+" $",12,NAVY,true));
        out.addView(text("الأعمال المقترحة ضمن الدفعة (حسب الأولوية):",12,0xff149176,true));
        if(within.isEmpty())out.addView(text("   لا يوجد عمل تكلفته ضمن الدفعة.",11,MUTED,false));
        for(Work w:within)out.addView(text("   • "+w.name()+" — "+QuantityCatalog.money(w.cost)+" $",11,TEXT,false));
        out.addView(text("التكلفة التقريبية: "+QuantityCatalog.money(spent)+" $  •  المتبقي من الدفعة: "+QuantityCatalog.money(Math.max(0,pay-spent))+" $",11,MUTED,true));
        if(!deferred.isEmpty()){
            out.addView(text("الأعمال المؤجلة للدفعة التالية:",12,0xffC9851C,true));
            for(Work w:deferred)out.addView(text("   • "+w.name()+" — "+QuantityCatalog.money(w.cost)+" $",11,TEXT,false));
        }
    }

    // ===== F5: «تحليل العناوين» — القطاع → الشارع → الجادة =================================================
    /** Extracts a «جادة/حارة/زقاق» label from the address text, or «غير محدد» when none is present. Simple pattern
     * match on the working + original address — good enough for a field breakdown, never a hard classification. */
    private String extractAlley(AppDatabase.Beneficiary b){
        String addr=((b.address==null?"":b.address)+" "+(b.originalAddress==null?"":b.originalAddress));
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(جادة|جاده|حارة|حاره|زقاق)\\s*([0-9\\u0660-\\u0669]{1,3}|[^\\s،\\-]{1,15})").matcher(addr);
        if(m.find()){String kind=m.group(1).replace("جاده","جادة").replace("حاره","حارة");return kind+" "+m.group(2).trim();}
        return "غير محدد";
    }
    private void showAddressAnalysis(){showAddressAnalysis(null);}
    /** F5 + FINAL FIELD USABILITY FIX §8/§9/§10: address breakdown القطاع→الشارع→الجادة. When {@code onlySector}
     * is non-null it scopes STRICTLY to that one sector (by the stored b.sector — never by map position), so
     * «تحليل عناوين القطاع» from a map sector card shows only that sector's streets/alleys/beneficiaries. */
    private void showAddressAnalysis(String onlySector){
        List<AppDatabase.Beneficiary> rows=db.listStage(currentProjectId,"","followup","all","all","all");
        if(onlySector!=null){List<AppDatabase.Beneficiary> f=new ArrayList<>();for(AppDatabase.Beneficiary b:rows)if(onlySector.equals(b.sector))f.add(b);rows=f;}
        if(rows.isEmpty()){toast(onlySector!=null?"لا يوجد مستفيدون في هذا القطاع":"لا يوجد مستفيدون في المتابعة بعد");return;}
        java.util.Collections.sort(rows,(a,b)->{
            int s=Integer.compare(a.sectorOrder,b.sectorOrder);if(s!=0)return s;
            s=AddressClassifier.normalize(a.street).compareTo(AddressClassifier.normalize(b.street));if(s!=0)return s;
            return AddressClassifier.normalize(extractAlley(a)).compareTo(AddressClassifier.normalize(extractAlley(b)));
        });
        // sector → street → alley → beneficiaries (LinkedHashMap keeps the sorted order above)
        java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>>>> tree=new java.util.LinkedHashMap<>();
        for(AppDatabase.Beneficiary b:rows){
            String sec=b.sector==null||b.sector.trim().isEmpty()?"غير مصنف":b.sector.trim();
            String str=b.street==null||b.street.trim().isEmpty()?"غير محدد":b.street.trim();
            String al=extractAlley(b);
            java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>>> byStreet=tree.get(sec);if(byStreet==null){byStreet=new java.util.LinkedHashMap<>();tree.put(sec,byStreet);}
            java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>> byAlley=byStreet.get(str);if(byAlley==null){byAlley=new java.util.LinkedHashMap<>();byStreet.put(str,byAlley);}
            List<AppDatabase.Beneficiary> list=byAlley.get(al);if(list==null){list=new ArrayList<>();byAlley.put(al,list);}
            list.add(b);
        }
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(6));box.setPadding(dp(14),dp(16),dp(14),dp(14));scroll.addView(box);
        box.addView(text(onlySector!=null?"تحليل عناوين القطاع — "+onlySector:"تحليل العناوين",19,NAVY,true));
        box.addView(text("توزيع المستفيدين حسب "+(onlySector!=null?"الشارع ثم الجادة/الحارة داخل هذا القطاع":"القطاع ثم الشارع ثم الجادة")+". اضغط شارعاً أو جادة لعرض مستفيديه.",12,MUTED,false));
        box.addView(text("الإجمالي: "+rows.size()+" مستفيد"+(onlySector!=null?"":" • القطاعات: "+tree.size()),12,0xff0E7490,true));
        final AlertDialog[] holder=new AlertDialog[1];
        for(java.util.Map.Entry<String,java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>>>> sEntry:tree.entrySet()){
            int secCount=0;for(java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>> st:sEntry.getValue().values())for(List<AppDatabase.Beneficiary> l:st.values())secCount+=l.size();
            TextView sh=text("▪ "+sEntry.getKey()+"  ("+secCount+" مستفيد)",15,0xff23507A,true);sh.setPadding(0,dp(12),0,dp(4));box.addView(sh);
            for(java.util.Map.Entry<String,java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>>> strEntry:sEntry.getValue().entrySet()){
                int strCount=0;List<AppDatabase.Beneficiary> streetAll=new ArrayList<>();for(List<AppDatabase.Beneficiary> l:strEntry.getValue().values()){strCount+=l.size();streetAll.addAll(l);}
                final List<AppDatabase.Beneficiary> streetList=streetAll;final String streetTitle=sEntry.getKey()+" — "+strEntry.getKey();
                TextView strTv=text("  ◂ "+strEntry.getKey()+": "+strCount+" مستفيد",13,0xff0E7490,true);strTv.setPadding(dp(9),dp(7),dp(9),dp(5));strTv.setBackground(round(0xffEFF5F8,8,CARD_BORDER,1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(0,dp(4),0,0);strTv.setLayoutParams(sp);
                strTv.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();showAddressGroupList(streetTitle,streetList);});
                box.addView(strTv);
                java.util.LinkedHashMap<String,List<AppDatabase.Beneficiary>> alleys=strEntry.getValue();
                // Only break down alleys when there's more than one, or a single but named («جادة …») one — a lone «غير محدد» adds no info.
                if(alleys.size()>1||!alleys.containsKey("غير محدد")){
                    for(java.util.Map.Entry<String,List<AppDatabase.Beneficiary>> alEntry:alleys.entrySet()){
                        final List<AppDatabase.Beneficiary> alList=alEntry.getValue();final String alTitle=streetTitle+" — "+alEntry.getKey();
                        TextView alTv=text("      • "+alEntry.getKey()+": "+alList.size(),12,TEXT,false);alTv.setPadding(dp(12),dp(4),dp(8),dp(4));
                        alTv.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();showAddressGroupList(alTitle,alList);});
                        box.addView(alTv);
                    }
                }
            }
        }
        holder[0]=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        holder[0].setOnShowListener(v->themeAndSize(holder[0].getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.96),(int)(getResources().getDisplayMetrics().heightPixels*.9)));
        holder[0].show();
    }
    /** F5: the beneficiaries in a chosen street/alley — tap one to open its file. */
    private void showAddressGroupList(String title,List<AppDatabase.Beneficiary> list){
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(6));box.setPadding(dp(14),dp(16),dp(14),dp(14));scroll.addView(box);
        box.addView(text(title,15,NAVY,true));
        box.addView(text(list.size()+" مستفيد — اضغط الاسم لفتح ملفه",12,MUTED,true));
        for(AppDatabase.Beneficiary b:list){
            String st=followupState(b);int col=followupStateColor(st);
            LinearLayout card=column(dp(4));card.setBackground(round(SURFACE_CARD,12,col,1));card.setPadding(dp(11),dp(9),dp(11),dp(9));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(5),0,0);card.setLayoutParams(cp);
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(safe(b.name,"مستفيد"),14,NAVY,true),new LinearLayout.LayoutParams(0,-2,1f));
            TextView chip=text(followupStateLabel(st),10,col,true);chip.setPadding(dp(8),dp(2),dp(8),dp(3));chip.setBackground(round((col&0x00FFFFFF)|0x18000000,99,col,1));row.addView(chip);
            card.addView(row);
            card.addView(text(b.sector+(b.street==null||b.street.isEmpty()?"":" — "+b.street),11,0xff0E7490,false));
            card.setOnClickListener(v->showBeneficiary(b));
            box.addView(card);
        }
        new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).show();
    }

    /** F2: followup address line with a clear SOURCE badge (من قائمة العناوين / من التقييم / بلا عنوان) and, when the
     * address fell back to the التقييم sheet, a short explanatory note. Tagged «f_address» so the completeness card
     * can jump to it. Read-only. */
    private View followupAddressCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(4));card.setTag("f_address");
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text("العنوان التفصيلي",13,MUTED,true),new LinearLayout.LayoutParams(0,-2,1f));
        String src=b.addressSource==null?"":b.addressSource;
        String badge;int bg,fg;
        if(b.address==null||b.address.trim().isEmpty()){badge="بلا عنوان";bg=0xffFCE8E8;fg=0xffBD4868;}
        else if(src.contains("التقييم")){badge="من التقييم";bg=0xffFFF3E0;fg=0xffC9851C;}
        else if(src.contains("قائمة العناوين")){badge="من قائمة العناوين";bg=0xffE6F5F0;fg=0xff149176;}
        else{badge="";bg=0;fg=0;}
        if(!badge.isEmpty()){TextView chip=text(badge,10,fg,true);chip.setPadding(dp(9),dp(3),dp(9),dp(3));chip.setBackground(round(bg,99,0,0));row.addView(chip,new LinearLayout.LayoutParams(-2,-2));}
        card.addView(row);
        card.addView(text(safe(b.address,"—"),14,TEXT,true));
        if(src.contains("التقييم"))card.addView(text("هذا العنوان مأخوذ من صفحة التقييم لعدم وجوده في قائمة العناوين.",11,0xffC9851C,false));
        return card;
    }

    /** F2: «الوثائق المستوردة» — images from the eval file's «الوثائق» sheet, kept SEPARATE from before-reference /
     * during / after photos. Read-only thumbnails (tap to view full size); an empty state when there are none. */
    private View importedDocumentsCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(12));card.setBackground(round(0xffF3F6FB,14,0xffC9D6E5,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,dp(2));card.setLayoutParams(p);
        card.addView(text("الوثائق المستوردة",15,0xff23507A,true));
        java.util.List<AppDatabase.Photo> docs=db.photos(b.id,BeneficiaryPhotoManager.IMPORTED_DOCUMENTS);
        if(docs.isEmpty()){card.addView(text("لا توجد وثائق مستوردة لهذا المستفيد",12,MUTED,false));return card;}
        card.addView(text("ملفات/صور مستوردة من ملف المتابعة (ليست صور قبل الترميم). اضغط للعرض كاملاً.",11,MUTED,false));
        android.widget.HorizontalScrollView hs=new android.widget.HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);hs.addView(r);
        int idx=1;
        for(AppDatabase.Photo ph:docs){
            final Uri u=Uri.parse(ph.uri);
            LinearLayout cell=column(dp(3));
            ImageView iv=new ImageView(this);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);iv.setBackgroundColor(0xffE7EEF2);
            cell.addView(iv,new LinearLayout.LayoutParams(dp(128),dp(96)));
            loadThumbnail(iv,u);
            iv.setOnClickListener(v->showReferenceImage(u));
            cell.addView(text("وثيقة "+idx,11,MUTED,true));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.setMargins(0,dp(6),dp(8),dp(2));r.addView(cell,cp);
            idx++;
        }
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.setMargins(0,dp(6),0,0);card.addView(hs,hp);
        return card;
    }

    /** P1: build the standalone quantities workbook from the project template — filling ONLY the Quantity column
     * (linked by item number, not input order) — and hand it to a share sheet as «الاسم_الكميات.xlsx». Prices,
     * descriptions and formulas stay exactly as the template; the other sheets stay empty. Later this same file
     * drops into the beneficiary package's «الكميات» folder. */
    private void exportQuantitiesXlsx(AppDatabase.Beneficiary b){
        java.util.Map<Integer,Double> q=new java.util.LinkedHashMap<>();
        for(AppDatabase.QuantityEntry e:db.quantities(b.id))if(e.quantity>0)q.put(e.itemNo,e.quantity);
        if(q.isEmpty()){toast("لا توجد كميات لتصديرها — عبّئ جدول الكميات أولاً");return;}
        try{
            java.io.File dir=new java.io.File(getCacheDir(),"embedded_cad");dir.mkdirs();
            String safe=b.name.replaceAll("[\\\\/:*?\"<>|]"," ").trim();if(safe.isEmpty())safe="مستفيد";
            java.io.File out=new java.io.File(dir,safe+"_الكميات.xlsx");
            try(java.io.InputStream tpl=getAssets().open(BeneficiaryXlsx.TEMPLATE_ASSET);
                java.io.OutputStream os=new java.io.FileOutputStream(out)){
                BeneficiaryXlsx.write(tpl,q,b.name,os);
            }
            Uri uri=CadFileProvider.uriFor(this,out);
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            send.putExtra(Intent.EXTRA_STREAM,uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send,"تصدير ملف الكميات"));
        }catch(Exception ex){toast("تعذّر تصدير الكميات: "+ex.getMessage());}
    }

    // ---- P2: «تجهيز ملف المستفيد» — the one-button final delivery folder (SAF) ----
    private long pendingPackageBenId;
    /** PROJECT SAVE ROOT — ONE FOLDER ONLY: what to run once the single project root is chosen/re-granted. */
    private Runnable pendingRootAction;

    private android.net.Uri packageRootUri(){
        String s=getSharedPreferences("package_export",MODE_PRIVATE).getString("root_uri","");
        return s==null||s.isEmpty()?null:android.net.Uri.parse(s);
    }
    /** True only when a project root is chosen AND its SAF permission is still valid AND the folder still exists —
     * so we can save into it silently. A saved-but-broken root (permission revoked / folder deleted) returns false
     * so the caller can prompt to re-pick it instead of failing silently. */
    private boolean rootUsable(){
        android.net.Uri u=packageRootUri();if(u==null)return false;
        try{
            android.net.Uri doc=android.provider.DocumentsContract.buildDocumentUriUsingTree(u,android.provider.DocumentsContract.getTreeDocumentId(u));
            try(android.database.Cursor c=getContentResolver().query(doc,new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){
                return c!=null&&c.moveToFirst();
            }
        }catch(Exception e){return false;} // SecurityException (permission lost) or folder gone
    }
    /** The single gate: if the project root is usable, run {@code onReady} now; otherwise ask the user to pick the
     * project folder ONCE (or re-pick if the saved one is no longer accessible), then run {@code onReady}. This is
     * the ONLY place a folder picker is triggered for saving — photos/quantities/CAD/backup/package all pass here. */
    private void requireProjectRoot(Runnable onReady){
        if(rootUsable()){if(onReady!=null)onReady.run();return;}
        boolean lost=packageRootUri()!=null; // saved before but no longer usable
        pendingRootAction=onReady;
        new AlertDialog.Builder(this)
                .setTitle(lost?"تعذر الوصول إلى مجلد المشروع":"اختيار مجلد المشروع")
                .setMessage(lost
                        ?"تعذر الوصول إلى مجلد المشروع (قد يكون حُذف أو أُلغي الإذن). يرجى اختياره مرة أخرى."
                        :"يرجى اختيار مجلد حفظ ملفات المشروع قبل بدء الدراسة.\n\nيُختار مرة واحدة فقط، ثم تُحفظ داخله كل ملفات المستفيدين تلقائياً (الصور والكميات والمخطط والنسخة الاحتياطية) دون أسئلة متكررة.")
                .setPositiveButton("اختيار المجلد",(d,w)->choosePackageRoot())
                .setNegativeButton("إلغاء",null).show();
    }
    private void choosePackageRoot(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // Pre-navigate the picker to the phone's Documents folder so the engineer only has to confirm (Android
        // won't let the app write there silently, but this makes the one-time grant a single tap on «Documents»).
        if(android.os.Build.VERSION.SDK_INT>=26){try{Uri docs=android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents");i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,docs);}catch(Exception ignored){}}
        startActivityForResult(i,REQUEST_PACKAGE_ROOT);
    }
    /** Human-readable current root folder (display name of the persisted tree), or empty if none chosen. */
    private String packageRootDisplay(){
        android.net.Uri u=packageRootUri();if(u==null)return "";
        try{android.net.Uri doc=android.provider.DocumentsContract.buildDocumentUriUsingTree(u,android.provider.DocumentsContract.getTreeDocumentId(u));
            try(android.database.Cursor c=getContentResolver().query(doc,new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}
        }catch(Exception ignored){}
        return u.getLastPathSegment()==null?"":u.getLastPathSegment();
    }

    /** Entry point of the delivery flow: verify a name, ensure a destination folder is chosen (once), then pick
     * which «before» photos to include and write the package. Nothing is stored in the database. */
    private void prepareBeneficiaryPackage(AppDatabase.Beneficiary b){
        if(b.name==null||b.name.trim().isEmpty()){toast("لا يمكن التجهيز: لا يوجد اسم للمستفيد");return;}
        // PROJECT SAVE ROOT — ONE FOLDER ONLY (§4): uses the SAME single project root — never asks for a new
        // folder. Chosen once; re-asked only if the saved root is no longer accessible. Then writes automatically
        // into «مشروع المأوى في الأونروا/المستفيدين/<الاسم - رقم التسجيل>/».
        if(!rootUsable()){requireProjectRoot(()->prepareBeneficiaryPackage(b));return;}
        showBeforePhotoPicker(b);
    }

    /** Opens the beneficiary's already-created delivery folder in a file manager. If it doesn't exist yet
     * (package never prepared), offers to prepare it now. No folder picker is ever shown here. */
    private void openBeneficiaryFolder(AppDatabase.Beneficiary b){
        if(!rootUsable()){requireProjectRoot(()->openBeneficiaryFolder(b));return;}
        String base=BeneficiaryPackage.baseName(b.name,b.registration);
        android.net.Uri folder=BeneficiaryPackageExporter.locateBeneficiaryFolder(getContentResolver(),packageRootUri(),base);
        if(folder==null){
            new AlertDialog.Builder(this).setTitle("لم يُجهَّز الملف بعد").setMessage("لا يوجد مجلد لهذا المستفيد حتى الآن.\nاضغط «تجهيز ملف المستفيد» أولاً لإنشائه.").setPositiveButton("تجهيز الآن",(d,w)->prepareBeneficiaryPackage(b)).setNegativeButton("حسناً",null).show();
            return;
        }
        try{Intent v=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,android.provider.DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(v);}
        catch(Exception e){toast("افتح المجلد من مدير الملفات: مشروع المأوى في الأونروا/المستفيدين/"+base);}
    }

    /** Lets the engineer choose which «before» photos go into the delivery (suggests the first 3, but any count). */
    private void showBeforePhotoPicker(AppDatabase.Beneficiary b){
        java.util.List<AppDatabase.Photo> photos=db.photos(b.id,BeneficiaryPhotoManager.BEFORE);
        if(photos.isEmpty()){ runPackageExport(b,new java.util.ArrayList<>()); return; }
        LinearLayout box=column(dp(6));
        box.addView(text("اختر صور «قبل الترميم» التي تريد وضعها في ملف التسليم (يُقترح 3، ويمكن أي عدد):",13,MUTED,false));
        final java.util.List<android.widget.CheckBox> boxes=new java.util.ArrayList<>();
        for(int i=0;i<photos.size();i++){
            AppDatabase.Photo p=photos.get(i);
            // Each row shows a real thumbnail of the photo next to its checkbox, so the engineer picks by sight,
            // not by a blind «صورة N» label. Tapping the thumbnail toggles selection too.
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(6),dp(6),dp(6));
            row.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(4),0,dp(4));row.setLayoutParams(rp);
            ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setBackgroundColor(0xffE7EEF2);
            row.addView(preview,new LinearLayout.LayoutParams(dp(120),dp(90)));
            loadThumbnail(preview,android.net.Uri.parse(p.uri));
            android.widget.CheckBox cb=new android.widget.CheckBox(this);
            cb.setText("صورة "+(i+1)+(p.note==null||p.note.isEmpty()?"":" — "+p.note));
            cb.setTextColor(TEXT);cb.setChecked(i<3);cb.setTag(p.uri);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1f);cp.setMargins(dp(8),0,0,0);
            row.addView(cb,cp);
            preview.setOnClickListener(v->cb.setChecked(!cb.isChecked()));
            boxes.add(cb);box.addView(row);
        }
        android.widget.ScrollView sv=new android.widget.ScrollView(this);sv.addView(box);
        new AlertDialog.Builder(this).setTitle("صور قبل الترميم").setView(sv)
            .setPositiveButton("تجهيز الملف",(d,w)->{
                java.util.List<android.net.Uri> sel=new java.util.ArrayList<>();
                for(android.widget.CheckBox cb:boxes) if(cb.isChecked()) sel.add(android.net.Uri.parse((String)cb.getTag()));
                runPackageExport(b,sel);
            }).setNegativeButton("إلغاء",null).show();
    }

    private void runPackageExport(AppDatabase.Beneficiary b,java.util.List<android.net.Uri> beforePhotos){ runPackageExport(b,beforePhotos,false); }
    /** {@code adminMode}=true: silent write used by «اعتماد الدراسة وإرسالها للمراجعة المكتبية» — no result dialog
     * (the caller shows its own message), and errors surface as a toast instead of a modal. */
    private void runPackageExport(AppDatabase.Beneficiary b,java.util.List<android.net.Uri> beforePhotos,boolean adminMode){
        try{
            String benBase=BeneficiaryPackage.baseName(b.name,b.registration);
            String fname=BeneficiaryPackage.sanitize(b.name); if(fname.isEmpty()) fname="مستفيد";
            // Two paths (user decision): the SELECTED few photos are embedded into the workbook, while ALL of the
            // beneficiary's «قبل الترميم» photos are copied as files into «03_صور قبل الترميم».
            byte[] xlsx=genQuantitiesBytes(b,beforePhotos);
            java.util.List<android.net.Uri> allBefore=new java.util.ArrayList<>();
            for(AppDatabase.Photo p:db.photos(b.id,BeneficiaryPhotoManager.BEFORE)) allBefore.add(android.net.Uri.parse(p.uri));
            org.unrwa.yarmoukfield.drawing.DrawModel plan=loadPlan(b);
            byte[] dxf=plan==null?null:org.unrwa.yarmoukfield.drawing.DxfWriter.toDxf(plan).getBytes(java.nio.charset.Charset.forName("windows-1256"));
            byte[] pdf=plan==null?null:genPdfBytes(b,plan);
            byte[] backup=genBackupJson(b);
            // B4: imported «before_reference» photos go into 03/مرجعية_مستوردة (separate from the engineer's shots).
            java.util.List<android.net.Uri> refUris=new java.util.ArrayList<>();
            for(AppDatabase.Photo rp:db.photos(b.id,BeneficiaryPhotoManager.BEFORE_REFERENCE)) refUris.add(android.net.Uri.parse(rp.uri));
            // PROJECT SAVE ROOT — ONE FOLDER ONLY: both «اعتماد» and normal «تجهيز» now write into the SAME single
            // canonical «الاسم - رقم التسجيل» folder (update, never «_2») — the very folder where live photos are
            // already saved — so a beneficiary never has scattered/duplicate folders. backup.json is always
            // refreshed; CAD/Excel are written only when missing (an engineer's edited files are never clobbered).
            byte[] damageJson=genDamageLocationsJson(b); // §3.8 — 07_البيانات/damage_locations.json
            BeneficiaryPackageExporter.Result res=
                BeneficiaryPackageExporter.update(getContentResolver(),packageRootUri(),benBase,fname,xlsx,dxf,pdf,backup,allBefore,refUris,damageJson);
            if(!adminMode) showPackageResult(res);
        }catch(Exception ex){ if(adminMode) toast("تم تحديث الحالة، لكن تعذّر تجهيز الملف: "+ex.getMessage()); else new AlertDialog.Builder(this).setTitle("تعذّر تجهيز الملف").setMessage(String.valueOf(ex.getMessage())).setPositiveButton("حسناً",null).show(); }
    }
    /** Administrative «اعتماد الدراسة وإرسالها للمراجعة المكتبية»: saves edits, allows sending even if incomplete
     * (with a heads-up), then hands off to {@link #doSendToOfficeReview}. */
    private void approveAndSendToOfficeReview(AppDatabase.Beneficiary b,EditText address,EditText damage,AlertDialog dialog){
        saveStudy(b,address,damage);
        AppDatabase.Beneficiary fresh=db.beneficiary(b.id);
        List<String> missing=new ArrayList<>();
        if(fresh.address.isEmpty())missing.add("العنوان التفصيلي");
        if(fresh.damageNotes.isEmpty())missing.add("وصف الأضرار");
        if(db.quantityCount(b.id)==0||db.quantityTotal(b.id)<=0)missing.add("جدول الكميات والمبلغ");
        if(loadPlan(fresh)==null)missing.add("مخطط المنزل");
        if(db.photoCount(b.id,BeneficiaryPhotoManager.BEFORE)==0)missing.add("صور قبل الترميم");
        if(missing.isEmpty()) doSendToOfficeReview(b.id,dialog);
        else new AlertDialog.Builder(this).setTitle("بعض عناصر الدراسة ناقصة")
                .setMessage("العناصر الناقصة:\n"+join(missing)+"\n\nيمكن الإرسال للمراجعة المكتبية الآن، وسيُجهَّز الملف بما هو متوفر.")
                .setPositiveButton("إرسال على أي حال",(d,w)->doSendToOfficeReview(b.id,dialog))
                .setNegativeButton("إلغاء",null).show();
    }
    private void doSendToOfficeReview(long id,AlertDialog dialog){
        long now=System.currentTimeMillis();
        if(!db.markStudySentToOfficeReview(id,now)){toast("تعذّر الإرسال — قد تكون الدراسة أُرسلت مسبقاً");return;}
        AppDatabase.Beneficiary fresh=db.beneficiary(id);
        // Prepare/update the package in the SAME official path — never asks for a folder.
        if(packageRootUri()!=null){
            java.util.List<android.net.Uri> embed=new java.util.ArrayList<>();
            java.util.List<AppDatabase.Photo> before=db.photos(id,BeneficiaryPhotoManager.BEFORE);
            for(int i=0;i<before.size()&&i<3;i++) embed.add(android.net.Uri.parse(before.get(i).uri));
            runPackageExport(fresh,embed,true);
        }
        if(dialog!=null)dialog.dismiss();renderActive();
        toast("تم اعتماد الدراسة وتجهيز ملف المستفيد للمراجعة المكتبية");
    }

    private void showPackageResult(BeneficiaryPackageExporter.Result res){
        StringBuilder m=new StringBuilder("المجلد: "+res.folderName+"\n\nما كُتب:\n");
        for(String s:res.written) m.append("✓ ").append(s).append("\n");
        if(!res.skipped.isEmpty()){ m.append("\nغير متوفر:\n"); for(String s:res.skipped) m.append("• ").append(s).append("\n"); }
        m.append("\n• .bak: يُنشئه AutoCAD عند تعديل المهندس");
        new AlertDialog.Builder(this).setTitle("تم تجهيز ملف المستفيد")
            .setMessage(m.toString())
            .setPositiveButton("فتح المجلد",(d,w)->{try{Intent v=new Intent(Intent.ACTION_VIEW).setDataAndType(res.folderUri,android.provider.DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(v);}catch(Exception e){toast("افتح المجلد من مدير الملفات على الجهاز");}})
            .setNegativeButton("حسناً",null).show();
    }

    private org.unrwa.yarmoukfield.drawing.DrawModel loadPlan(AppDatabase.Beneficiary b){
        try{ java.io.File jf=houseJson(b.id); if(!jf.exists()) return null;
            org.unrwa.yarmoukfield.drawing.DrawModel m=org.unrwa.yarmoukfield.drawing.DrawJson.fromJson(readTextFile(jf));
            return m.isCalibrated()?m:null;
        }catch(Exception e){ return null; }
    }
    private byte[] genQuantitiesBytes(AppDatabase.Beneficiary b,java.util.List<android.net.Uri> beforePhotos){
        try{
            java.util.Map<Integer,Double> q=new java.util.LinkedHashMap<>();
            for(AppDatabase.QuantityEntry e:db.quantities(b.id)) if(e.quantity>0) q.put(e.itemNo,e.quantity);
            if(q.isEmpty()) return null;
            // The selected «قبل الترميم» photos are embedded (scaled down) into the workbook's photo sheet, so the
            // Excel file is a complete beneficiary record — not just a quantities list.
            java.util.List<byte[]> photoBytes=new java.util.ArrayList<>();
            if(beforePhotos!=null)for(android.net.Uri u:beforePhotos){try{byte[] jb=scaledJpegBytes(u,1200);if(jb!=null)photoBytes.add(jb);}catch(Exception ignored){}}
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream tpl=getAssets().open(BeneficiaryXlsx.TEMPLATE_ASSET)){ BeneficiaryXlsx.write(tpl,q,b.name,photoBytes,bo); }
            return bo.toByteArray();
        }catch(Exception e){ return null; }
    }
    /** Decodes a photo down to at most ~{@code maxPx} on the long edge and re-encodes as JPEG — keeps the embedded
     * workbook a sensible size instead of carrying full 8MP originals. */
    private byte[] scaledJpegBytes(android.net.Uri uri,int maxPx)throws Exception{
        Bitmap bmp=decodeThumbnail(uri,maxPx);if(bmp==null)return null;
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG,82,bo);bmp.recycle();return bo.toByteArray();
    }
    private byte[] genPdfBytes(AppDatabase.Beneficiary b,org.unrwa.yarmoukfield.drawing.DrawModel m){
        try{
            org.unrwa.yarmoukfield.drawing.DrawExportMeta meta=new org.unrwa.yarmoukfield.drawing.DrawExportMeta();
            meta.benefName=safe(b.name,"");meta.fileNo=(b.registration==null||b.registration.isEmpty())?String.valueOf(b.id):b.registration;
            meta.engineer=b.engineerName==null?"":b.engineerName;meta.date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
            if(m.ceilingHeightM>0)meta.ceiling=String.format(Locale.US,"h=%.2fm",m.ceilingHeightM);
            java.io.File tmp=new java.io.File(getCacheDir(),"pkg_plan.pdf");
            org.unrwa.yarmoukfield.drawing.PdfExporter.export(this,m,meta,tmp);
            byte[] bytes=readFileBytes(tmp); tmp.delete(); return bytes;
        }catch(Exception e){ return null; }
    }
    private byte[] genBackupJson(AppDatabase.Beneficiary b){
        try{
            org.json.JSONObject o=new org.json.JSONObject();
            o.put("name",b.name);o.put("registration",b.registration);o.put("fileNo",b.fileNo);
            o.put("phone1",b.phone1);o.put("phone2",b.phone2);o.put("address",b.address);
            o.put("sector",b.sector);o.put("workflowStage",b.workflowStage);o.put("engineer",b.engineerName);
            // فريق الدراسة + التاريخ الذكي
            o.put("studyEngineer1",b.studyEngineer1);o.put("studyEngineer2",b.studyEngineer2);o.put("fieldAssistant",b.fieldAssistant);o.put("studyDate",b.studyDate);
            // حالة الاعتماد الإداري + تاريخ الإرسال للمراجعة المكتبية
            o.put("studyStatus",b.studyStatus);
            o.put("statusLabel","ready".equals(b.studyStatus)?"مرسلة للمراجعة المكتبية":studyStatusLabel(b.studyStatus));
            if(b.sentToOfficeReviewAt>0)o.put("sentToOfficeReviewAt",new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(b.sentToOfficeReviewAt)));
            // B4: imported reference before-photos (never fails when none — count 0, empty list).
            java.util.List<AppDatabase.Photo> refPhotos=db.photos(b.id,BeneficiaryPhotoManager.BEFORE_REFERENCE);
            o.put("beforeReferencePhotosCount",refPhotos.size());
            org.json.JSONArray refNames=new org.json.JSONArray();for(AppDatabase.Photo rp:refPhotos)refNames.put(rp.fileName==null?"":rp.fileName);
            o.put("beforeReferencePhotos",refNames);
            o.put("beforeReferenceSource",refPhotos.isEmpty()?"":"imported_followup_list");
            org.json.JSONArray qs=new org.json.JSONArray();
            for(AppDatabase.QuantityEntry e:db.quantities(b.id)){ org.json.JSONObject it=new org.json.JSONObject(); it.put("item_no",e.itemNo); it.put("quantity",e.quantity); it.put("unit_price",e.unitPrice); qs.put(it); }
            o.put("quantities",qs);
            o.put("damageLocations",damageLocationsJson(b.id)); // §3.8 — كل أماكن الضرر داخل النسخة الاحتياطية
            o.put("exportedAt",new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date()));
            return o.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){ return null; }
    }
    /** §3.8: all damage-location rows of a beneficiary as a JSON array — shared by backup.json and the exported
     * 07_البيانات/damage_locations.json. */
    private org.json.JSONArray damageLocationsJson(long beneficiaryId)throws org.json.JSONException{
        org.json.JSONArray arr=new org.json.JSONArray();
        for(AppDatabase.DamageLocation dl:db.damageLocations(beneficiaryId)){
            org.json.JSONObject j=new org.json.JSONObject();
            j.put("item_no",dl.itemNo);j.put("place",dl.place);j.put("damage_type",dl.damageType);j.put("qty",dl.qty);j.put("note",dl.note);
            arr.put(j);
        }
        return arr;
    }
    /** §3.8: standalone damage_locations.json written into the delivery folder's 07_البيانات (null when none). */
    private byte[] genDamageLocationsJson(AppDatabase.Beneficiary b){
        try{
            if(db.damageLocations(b.id).isEmpty())return null;
            org.json.JSONObject o=new org.json.JSONObject();
            o.put("beneficiary",b.name);o.put("registration",b.registration);
            o.put("damageLocations",damageLocationsJson(b.id));
            o.put("exportedAt",new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date()));
            return o.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){ return null; }
    }
    private byte[] readFileBytes(java.io.File f)throws Exception{
        try(java.io.FileInputStream in=new java.io.FileInputStream(f)){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)bo.write(buf,0,n);return bo.toByteArray();
        }
    }

    // ---- Phase 4: BOQ item delivery, 5-week countdown, second-payment approval ----

    // ---- Phase 4: BOQ item delivery, 5-week countdown, second-payment approval ----

    private static final String[] BOQ_STATUS_VALUES={AppDatabase.BOQ_NOT_DELIVERED,AppDatabase.BOQ_DELIVERED,AppDatabase.BOQ_IN_PROGRESS,AppDatabase.BOQ_READY_FOR_PICKUP,AppDatabase.BOQ_RECEIVED};
    private static final String[] BOQ_STATUS_LABELS={"لم يُسلّم","تم تسليمه","قيد التنفيذ","جاهز للاستلام","تم استلامه"};
    private int boqStatusIndex(String status){for(int i=0;i<BOQ_STATUS_VALUES.length;i++)if(BOQ_STATUS_VALUES[i].equals(status))return i;return 0;}
    private QuantityCatalog.Item catalogItem(int number){for(QuantityCatalog.Item item:quantityCatalog)if(item.number==number)return item;return null;}
    private String boqDeliveryMeta(AppDatabase.BoqDeliveryEntry entry){
        String base=entry.updatedAt<=0?"لم يُحدَّث بعد":"آخر تحديث: "+(entry.engineerName.isEmpty()?"—":entry.engineerName)+" — "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(entry.updatedAt));
        return entry.note.isEmpty()?base:base+"\nملاحظة: "+entry.note;
    }

    /** All BOQ items for this beneficiary in one screen; changing a status Spinner saves immediately (no
     * separate screen per item), and each save stamps the current engineer + timestamp automatically. */
    private void showBoqDelivery(AppDatabase.Beneficiary b){
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text("تسليم أعمال BOQ ومتابعة التنفيذ",20,NAVY,true));
        TextView intro=text("غيّر حالة أي بند مباشرة من هنا؛ يُحفظ اسم المهندس والوقت تلقائياً مع كل تغيير.",13,MUTED,false);intro.setPadding(0,dp(4),0,dp(10));box.addView(intro);
        List<AppDatabase.BoqDeliveryEntry> entries=db.boqDeliveryStatus(b.id);
        if(entries.isEmpty())box.addView(text("لا توجد بنود كميات مسجلة لهذا المستفيد",14,MUTED,false));
        for(AppDatabase.BoqDeliveryEntry entry:entries){
            QuantityCatalog.Item item=catalogItem(entry.itemNo);
            LinearLayout card=column(dp(11));card.setBackground(round(SURFACE_CARD,13,CARD_BORDER,1));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(cardParams);
            card.addView(text(entry.itemNo+". "+(item==null?"بند":item.name),15,BLUE,true));
            card.addView(text("الكمية: "+QuantityCatalog.quantityNumber(entry.quantity)+" "+(item==null?"":item.unit)+"   •   التكلفة: "+QuantityCatalog.money(entry.subtotal())+" $",12,MUTED,true));
            Spinner statusSpinner=new Spinner(this);ArrayAdapter<String>statusAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,BOQ_STATUS_LABELS);statusSpinner.setAdapter(statusAdapter);statusSpinner.setBackground(round(CARD_BG,10,CARD_BORDER,1));statusSpinner.setSelection(boqStatusIndex(entry.status));
            LinearLayout.LayoutParams spinnerParams=new LinearLayout.LayoutParams(-1,dp(46));spinnerParams.setMargins(0,dp(6),0,0);card.addView(statusSpinner,spinnerParams);
            TextView meta=text(boqDeliveryMeta(entry),11,0xff0E7490,false);meta.setPadding(0,dp(5),0,0);card.addView(meta);
            Button noteBtn=smallButton(entry.note.isEmpty()?"إضافة ملاحظة":"تعديل الملاحظة ✎",0xff7A5CB8);noteBtn.setTextSize(12);LinearLayout.LayoutParams noteParams=new LinearLayout.LayoutParams(-1,dp(40));noteParams.setMargins(0,dp(6),0,0);card.addView(noteBtn,noteParams);
            box.addView(card);
            String[]currentStatus={entry.status};
            boolean[]settingInitial={true};
            statusSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onNothingSelected(AdapterView<?>p){}
                @Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){
                    if(settingInitial[0]){settingInitial[0]=false;return;}
                    currentStatus[0]=BOQ_STATUS_VALUES[pos];
                    db.updateBoqDeliveryStatus(b.id,entry.itemNo,currentStatus[0],entry.note,currentEngineerName());
                    entry.status=currentStatus[0];entry.engineerName=currentEngineerName();entry.updatedAt=System.currentTimeMillis();
                    meta.setText(boqDeliveryMeta(entry));
                }
            });
            noteBtn.setOnClickListener(v->{
                EditText noteField=new EditText(this);noteField.setText(entry.note);noteField.setHint("ملاحظة اختيارية لهذا البند");noteField.setMinLines(2);noteField.setGravity(Gravity.TOP|Gravity.START);noteField.setBackground(round(CARD_BG,12,CARD_BORDER,1));LinearLayout wrap=column(dp(10));wrap.addView(noteField,new LinearLayout.LayoutParams(-1,dp(90)));
                new AlertDialog.Builder(this).setTitle(entry.itemNo+". "+(item==null?"بند":item.name)).setView(wrap).setPositiveButton("حفظ",(d,w)->{
                    entry.note=noteField.getText().toString().trim();
                    db.updateBoqDeliveryStatus(b.id,entry.itemNo,currentStatus[0],entry.note,currentEngineerName());
                    entry.engineerName=currentEngineerName();entry.updatedAt=System.currentTimeMillis();
                    meta.setText(boqDeliveryMeta(entry));noteBtn.setText(entry.note.isEmpty()?"إضافة ملاحظة":"تعديل الملاحظة ✎");
                }).setNegativeButton("إلغاء",null).show();
            });
        }
        AlertDialog editor=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        editor.setOnShowListener(v->themeAndSize(editor.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.97),(int)(getResources().getDisplayMetrics().heightPixels*.94)));
        editor.show();
    }

    /** Two independent manual dates feed the 35-day countdown: work-delivery ("تأكيد تسليم الأعمال", locked after
     * first press) and first-payment-availability ("تأكيد توفر/استلام الدفعة الأولى", correctable/cancellable
     * with an audit trail). execution_start_at = MAX of the two — the section shows a neutral "waiting" state,
     * never an overdue one, until BOTH are set. */
    private View deliveryTimelineSection(AppDatabase.Beneficiary b,AlertDialog dialog){
        LinearLayout card=column(dp(13));card.setBackground(round(CARD_BG,14,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,dp(6));card.setLayoutParams(p);
        card.addView(text("تسليم الأعمال والدفعة الأولى ومهلة التنفيذ (٥ أسابيع)",15,NAVY,true));
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);

        if(b.deliveryConfirmedAt<=0){
            TextView hint=text("لم يتم تأكيد تسليم الأعمال بعد.",12,MUTED,false);hint.setPadding(0,dp(6),0,dp(6));card.addView(hint);
            Button confirm=smallButton("تأكيد تسليم الأعمال",0xff149176);
            confirm.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("تأكيد تسليم الأعمال")
                    .setMessage("يُسجَّل تاريخ تسليم الأعمال الآن. لا يمكن لأي ضغط لاحق تغيير هذا التاريخ. مدة التنفيذ (٣٥ يوماً) لن تبدأ إلا بعد تأكيد توفر الدفعة الأولى أيضاً.")
                    .setPositiveButton("تأكيد",(d,w)->{
                        boolean first=db.confirmDeliveryStart(b.id);
                        if(first){AppDatabase.Beneficiary fresh=db.beneficiary(b.id);dialog.dismiss();renderActive();toast("تم تسجيل تسليم الأعمال");showBeneficiary(fresh);}
                        else toast("تم تسجيل تسليم الأعمال مسبقاً — التاريخ الأصلي لا يتغيّر");
                    }).setNegativeButton("إلغاء",null).show());
            card.addView(confirm,new LinearLayout.LayoutParams(-1,dp(48)));
        } else {
            card.addView(info("تاريخ تسليم الأعمال",fmt.format(new Date(b.deliveryConfirmedAt))));
        }

        LinearLayout divider=new LinearLayout(this);divider.setBackground(new android.graphics.drawable.ColorDrawable(CARD_BORDER));card.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)){{topMargin=dp(8);bottomMargin=dp(8);}});

        if(b.firstPaymentAvailableAt<=0){
            TextView hint=text("لم يتم تأكيد توفر/استلام الدفعة الأولى بعد — لا يُعتبر وجود نسبة 60% في ملف مستورد دليلاً كافياً.",12,MUTED,false);hint.setPadding(0,0,0,dp(6));card.addView(hint);
            Button confirmFirst=smallButton("تأكيد توفر/استلام الدفعة الأولى",0xff0E7490);
            confirmFirst.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("تأكيد توفر/استلام الدفعة الأولى")
                    .setMessage("سيُسجَّل هذا التاريخ باسمك الآن، وقد يبدأ عدّاد الخمس أسابيع اعتماداً عليه (إذا كان تسليم الأعمال قد تم أيضاً).")
                    .setPositiveButton("تأكيد",(d,w)->{db.setFirstPaymentAvailable(b.id,currentEngineerName());AppDatabase.Beneficiary fresh=db.beneficiary(b.id);dialog.dismiss();renderActive();toast("تم تسجيل توفر الدفعة الأولى");showBeneficiary(fresh);})
                    .setNegativeButton("إلغاء",null).show());
            card.addView(confirmFirst,new LinearLayout.LayoutParams(-1,dp(48)));
        } else {
            card.addView(info("تاريخ توفر/استلام الدفعة الأولى",fmt.format(new Date(b.firstPaymentAvailableAt))+(b.firstPaymentConfirmedBy.isEmpty()?"":" — "+b.firstPaymentConfirmedBy)));
            LinearLayout firstPaymentActions=new LinearLayout(this);firstPaymentActions.setGravity(Gravity.END);
            Button correct=smallButton("تصحيح التاريخ",0xff7A5CB8);correct.setTextSize(12);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(38));cp.setMargins(dp(6),dp(4),0,0);
            correct.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("تصحيح تاريخ توفر الدفعة الأولى")
                    .setMessage("سيُستبدل التاريخ الحالي بتاريخ اليوم وسيُسجَّل هذا التصحيح في سجل التدقيق مع التاريخ القديم — لن يُحذف أثره.")
                    .setPositiveButton("تصحيح",(d,w)->{db.setFirstPaymentAvailable(b.id,currentEngineerName());AppDatabase.Beneficiary fresh=db.beneficiary(b.id);dialog.dismiss();renderActive();toast("تم تصحيح تاريخ توفر الدفعة الأولى");showBeneficiary(fresh);})
                    .setNegativeButton("إلغاء",null).show());
            firstPaymentActions.addView(correct,cp);
            Button cancel=smallButton("إلغاء التأكيد",0xffBD4868);cancel.setTextSize(12);LinearLayout.LayoutParams xp=new LinearLayout.LayoutParams(-2,dp(38));xp.setMargins(dp(6),dp(4),0,0);
            cancel.setOnClickListener(v->promptCancelFirstPayment(b,dialog));
            firstPaymentActions.addView(cancel,xp);
            card.addView(firstPaymentActions);
        }

        long executionStartAt=AppDatabase.executionStartAt(b);
        LinearLayout divider2=new LinearLayout(this);divider2.setBackground(new android.graphics.drawable.ColorDrawable(CARD_BORDER));card.addView(divider2,new LinearLayout.LayoutParams(-1,dp(1)){{topMargin=dp(8);bottomMargin=dp(8);}});
        if(executionStartAt<=0){
            String missing=b.deliveryConfirmedAt<=0&&b.firstPaymentAvailableAt<=0?"تسليم الأعمال وتوفر الدفعة الأولى":b.deliveryConfirmedAt<=0?"تسليم الأعمال":"الدفعة الأولى";
            TextView waiting=text("⏳ بانتظار "+missing+" — مدة التنفيذ لم تبدأ بعد",13,0xff7A5CB8,true);waiting.setPadding(0,dp(2),0,dp(4));card.addView(waiting);
        } else {
            long dayMs=24L*3600*1000;long target=executionStartAt+35*dayMs;long now=System.currentTimeMillis();
            SimpleDateFormat dateOnly=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
            card.addView(info("تاريخ بدء مدة التنفيذ الفعلية",dateOnly.format(new Date(executionStartAt))));
            card.addView(info("الموعد المستهدف للإنجاز",dateOnly.format(new Date(target))));
            String statusText;int statusColor;
            if(now>target){long daysOverdue=(now-target+dayMs-1)/dayMs;statusText="⚠ تجاوز الموعد المستهدف بـ "+daysOverdue+" يوماً — بحاجة لمتابعة";statusColor=0xffBD4868;}
            else{long daysRemaining=(target-now)/dayMs;
                if(daysRemaining<=5){statusText="⏰ يقترب الموعد المستهدف — تبقّى "+daysRemaining+" يوماً";statusColor=0xffC9851C;}
                else{statusText="متبقٍ "+daysRemaining+" يوماً على الموعد المستهدف";statusColor=0xff0E7490;}
            }
            TextView statusView=text(statusText,13,statusColor,true);statusView.setPadding(0,dp(6),0,dp(4));card.addView(statusView);
        }
        TextView note=text("انتهاء المدة لا يعني صرف أي دفعة أو اعتمادها تلقائياً — القرار النهائي يبقى بيد المهندس/الإدارة.",11,MUTED,false);card.addView(note);
        return card;
    }

    private void promptCancelFirstPayment(AppDatabase.Beneficiary b,AlertDialog dialog){
        EditText reason=new EditText(this);reason.setHint("سبب الإلغاء (اختياري)");reason.setMinLines(2);reason.setGravity(Gravity.TOP|Gravity.START);reason.setBackground(round(CARD_BG,12,CARD_BORDER,1));
        LinearLayout box=column(dp(14));box.addView(text("إلغاء تأكيد توفر الدفعة الأولى",17,NAVY,true));TextView note=text("لن يُحذف أثر التأكيد السابق من السجل — يُضاف سجل إلغاء جديد بتاريخ اليوم، وقد يعود المستفيد لحالة \"مدة التنفيذ لم تبدأ\".",12,MUTED,false);note.setPadding(0,dp(4),0,dp(8));box.addView(note);box.addView(reason,new LinearLayout.LayoutParams(-1,dp(80)));
        new AlertDialog.Builder(this).setView(box).setPositiveButton("تأكيد الإلغاء",(d,w)->{db.cancelFirstPaymentAvailable(b.id,currentEngineerName(),reason.getText().toString().trim());AppDatabase.Beneficiary fresh=db.beneficiary(b.id);dialog.dismiss();renderActive();toast("تم إلغاء تأكيد توفر الدفعة الأولى");showBeneficiary(fresh);}).setNegativeButton("رجوع",null).show();
    }

    /** Purely manual second-payment readiness — never auto-triggered by BOQ status, photos, or the 35-day timer. */
    private View secondPaymentSection(AppDatabase.Beneficiary b,AlertDialog dialog){
        LinearLayout card=column(dp(13));card.setBackground(round(b.secondPaymentReady?0xffE6F5F0:CARD_BG,14,b.secondPaymentReady?0xff149176:CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,dp(4));card.setLayoutParams(p);
        card.addView(text("موافقة رفع الدفعة الثانية",15,NAVY,true));
        if(b.secondPaymentReady){
            TextView status=text("✔ جاهز لرفع الدفعة الثانية — بواسطة "+safe(b.secondPaymentApprovedBy,"—")+" بتاريخ "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(b.secondPaymentApprovedAt)),13,0xff149176,true);status.setPadding(0,dp(6),0,dp(8));card.addView(status);
            Button revoke=smallButton("إلغاء موافقة الدفعة الثانية",0xffBD4868);revoke.setOnClickListener(v->promptRevokeSecondPayment(b,dialog));card.addView(revoke,new LinearLayout.LayoutParams(-1,dp(46)));
        } else {
            TextView hint=text("موافقة يدوية بالكامل — لا يُفعّلها إكمال جدول الكميات، أو وصول الصور، أو انتهاء مهلة الخمس أسابيع تلقائياً.",12,MUTED,false);hint.setPadding(0,dp(5),0,dp(8));card.addView(hint);
            Button approve=smallButton("اعتماد للدفعة الثانية",0xff149176);approve.setOnClickListener(v->showSecondPaymentSummary(b,dialog));card.addView(approve,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        return card;
    }

    private void showSecondPaymentSummary(AppDatabase.Beneficiary b,AlertDialog dialog){
        AppDatabase.SecondPaymentSummary s=db.computeSecondPaymentSummary(b.id);
        String message="بنود تم تسليمها للعامل (تسليم/تنفيذ/جاهز للاستلام): "+s.deliveredCount+" من "+s.totalItems+"\n"+
                "بنود أصبحت 'تم استلامه': "+s.receivedCount+" من "+s.totalItems+"\n"+
                "بنود ما زالت 'لم يُسلّم': "+s.pendingCount+"\n"+
                "صور أثناء الترميم المتوفرة: "+s.duringPhotoCount+"\n\n"+
                "المبلغ الإجمالي المعتمد: "+QuantityCatalog.money(s.totalAmount)+" $\n"+
                "الدفعة الأولى 60%: "+s.firstPayment+" $\n"+
                "الدفعة الثانية 40% المستحقة: "+s.secondPayment+" $\n\n"+
                "بالضغط على «تأكيد الاعتماد» يصبح هذا المستفيد ضمن قائمة الجاهزين لرفع الدفعة الثانية فقط — هذا لا يعني صرف أي مبلغ.";
        new AlertDialog.Builder(this).setTitle("مراجعة قبل اعتماد الدفعة الثانية").setMessage(message)
                .setPositiveButton("تأكيد الاعتماد",(d,w)->{db.approveSecondPayment(b.id,currentEngineerName());dialog.dismiss();renderActive();toast("تم اعتماد المستفيد ضمن الجاهزين لرفع الدفعة الثانية");AppDatabase.Beneficiary fresh=db.beneficiary(b.id);showBeneficiary(fresh);})
                .setNegativeButton("إلغاء",null).show();
    }

    private void promptRevokeSecondPayment(AppDatabase.Beneficiary b,AlertDialog dialog){
        EditText reason=new EditText(this);reason.setHint("سبب الإلغاء (اختياري)");reason.setMinLines(2);reason.setGravity(Gravity.TOP|Gravity.START);reason.setBackground(round(CARD_BG,12,CARD_BORDER,1));
        LinearLayout box=column(dp(14));box.addView(text("إلغاء موافقة الدفعة الثانية",17,NAVY,true));TextView note=text("لن يُحذف أثر الموافقة السابقة — يبقى تاريخ الاعتماد ومن أعطاه في السجل، ويُضاف سجل إلغاء جديد بتاريخ اليوم.",12,MUTED,false);note.setPadding(0,dp(4),0,dp(8));box.addView(note);box.addView(reason,new LinearLayout.LayoutParams(-1,dp(80)));
        new AlertDialog.Builder(this).setView(box).setPositiveButton("تأكيد الإلغاء",(d,w)->{db.revokeSecondPayment(b.id,currentEngineerName(),reason.getText().toString().trim());dialog.dismiss();renderActive();toast("تم إلغاء موافقة الدفعة الثانية");AppDatabase.Beneficiary fresh=db.beneficiary(b.id);showBeneficiary(fresh);}).setNegativeButton("رجوع",null).show();
    }

    private void showSecondPaymentReadyList(){
        List<AppDatabase.Beneficiary> rows=db.secondPaymentReadyList(currentProjectId);
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text("جاهزون لرفع الدفعة الثانية",20,NAVY,true));
        TextView sub=text(currentProject+" — "+rows.size()+" مستفيداً",13,MUTED,true);sub.setPadding(0,dp(4),0,dp(10));box.addView(sub);
        if(rows.isEmpty())box.addView(text("لا يوجد أي مستفيد معتمد لرفع الدفعة الثانية حتى الآن",14,MUTED,false));
        for(AppDatabase.Beneficiary b:rows){
            double total=db.quantityTotal(b.id);long secondPay=total>0?QuantityCatalog.secondPayment(total):0;
            LinearLayout card=column(dp(11));card.setBackground(round(SURFACE_CARD,13,0xffB7DED3,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(p);
            card.addView(text(b.name,15,NAVY,true));
            card.addView(text(b.followupBatch.isEmpty()?"—":b.followupBatch,12,0xff0E7490,true));
            card.addView(text("الدفعة الثانية 40%: "+QuantityCatalog.money(secondPay)+" $   •   بواسطة "+safe(b.secondPaymentApprovedBy,"—")+" — "+new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(b.secondPaymentApprovedAt)),12,TEXT,false));
            card.setOnClickListener(v->{showSecondPaymentReadyListDialog.dismiss();showBeneficiary(b);});
            box.addView(card);
        }
        showSecondPaymentReadyListDialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        showSecondPaymentReadyListDialog.setOnShowListener(v->themeAndSize(showSecondPaymentReadyListDialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.9)));
        showSecondPaymentReadyListDialog.show();
    }

    // ---- Phase 5: offline GPS home-location capture ----

    /** "تثبيت موقع المنزل" card for the study screen. Never touches the map/DWG layer — purely GPS lat/lng/accuracy/time. */
    private String gpsSourceLabel(String source){
        if(GpsLocationHelper.MODE_DEVICE_HIGH_ACCURACY.equals(source))return "دقة عالية من الهاتف (قد تعتمد على Wi-Fi/الشبكة)";
        return "GPS حقيقي دون إنترنت";
    }

    /** Photo Reference (B2): «صور قبل الترميم المرجعية» — read-only thumbnails imported from the followup list's
     * Excel, shown before the capture section so the engineer can re-shoot from the same angle. Empty-state text
     * when none. Tapping a thumbnail opens it full-size. (The «التقاط بنفس الزاوية» button is added in B3.) */
    private View beforeReferenceCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(12));card.setBackground(round(0xffF7F1FB,14,0xffD8CBE8,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(2));card.setLayoutParams(p);
        card.addView(text("صور «قبل الترميم» المرجعية",15,0xff5B3E86,true));
        java.util.List<AppDatabase.Photo> refs=db.photos(b.id,BeneficiaryPhotoManager.BEFORE_REFERENCE);
        if(refs.isEmpty()){card.addView(text("لا توجد صور قبل الترميم مستوردة لهذا المستفيد",12,MUTED,false));return card;}
        card.addView(text("مرجعية فقط — مستوردة من ملف المتابعة. لا تُعدَّل ولا تُحذف. اضغط الصورة لعرضها كاملة، أو «التقاط بنفس الزاوية».",11,MUTED,false));
        // FINAL FIELD USABILITY FIX §4: a tidy GRID (3 per row) instead of one long horizontal scroll — each cell
        // shows the reference thumbnail + a «📷 التقاط بنفس الزاوية» button (re-shoots as a NEW during/after photo;
        // the reference itself is never touched). At export these go to 03_صور قبل الترميم/مرجعية_مستوردة.
        final int cols=3;LinearLayout rowLayout=null;int idx=1;
        for(AppDatabase.Photo ph:refs){
            if((idx-1)%cols==0){rowLayout=new LinearLayout(this);rowLayout.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,-2);rlp.setMargins(0,dp(6),0,0);card.addView(rowLayout,rlp);}
            final Uri u=Uri.parse(ph.uri);
            LinearLayout cell=column(dp(3));
            ImageView iv=new ImageView(this);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);iv.setBackgroundColor(0xffE7EEF2);
            cell.addView(iv,new LinearLayout.LayoutParams(-1,dp(92)));
            loadThumbnail(iv,u);
            iv.setOnClickListener(v->showReferenceImage(u));
            cell.addView(text("مرجعية "+idx,10,MUTED,true));
            Button cap=smallButton("📷 نفس الزاوية",0xff149176);cap.setTextSize(10);cap.setPadding(dp(4),0,dp(4),0);cap.setOnClickListener(v->captureSameAngle(b));
            LinearLayout.LayoutParams capp=new LinearLayout.LayoutParams(-1,dp(38));capp.setMargins(0,dp(3),0,0);cell.addView(cap,capp);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1f);cp.setMargins(dp(3),0,dp(3),0);rowLayout.addView(cell,cp);
            idx++;
        }
        // pad the last row so cells keep equal thirds
        if(rowLayout!=null){int rem=(idx-1)%cols;if(rem!=0)for(int k=rem;k<cols;k++){View spacer=new View(this);rowLayout.addView(spacer,new LinearLayout.LayoutParams(0,-2,1f));}}
        return card;
    }

    /** B3: pick the followup phase then open the camera to re-shoot the same angle. The result is saved as a NEW
     * DURING/AFTER photo (via the normal capture path) — the imported reference photo is never modified/replaced. */
    private void captureSameAngle(AppDatabase.Beneficiary b){
        // NOTE: AlertDialog cannot show setItems() together with setMessage() — the message wins and the list
        // vanishes. So the phase choices are real buttons inside a custom view, with the reassurance note above.
        LinearLayout box=column(dp(14));box.setPadding(dp(20),dp(10),dp(20),dp(6));
        box.addView(text("ستُحفظ الصورة الجديدة كصورة متابعة مستقلة — الصورة المرجعية تبقى كما هي.",13,MUTED,false));
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("اختر مرحلة الصورة الجديدة").setView(box).setNegativeButton("إلغاء",null).create();
        Button during=smallButton("📸 أثناء الترميم",0xff149176);LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(-1,dp(50));dp1.setMargins(0,dp(12),0,0);during.setOnClickListener(v->{dlg.dismiss();startPhotoCapture(b,BeneficiaryPhotoManager.DURING);});box.addView(during,dp1);
        Button after=smallButton("✅ بعد الترميم",0xff0E7490);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(50));ap.setMargins(0,dp(10),0,0);after.setOnClickListener(v->{dlg.dismiss();startPhotoCapture(b,BeneficiaryPhotoManager.AFTER);});box.addView(after,ap);
        dlg.show();
    }

    /** Full-size preview of a reference photo (decode + show in a dialog); never modifies the file. */
    private void showReferenceImage(Uri uri){
        ImageView iv=new ImageView(this);iv.setAdjustViewBounds(true);iv.setBackgroundColor(0xff101820);
        worker.execute(()->{try{Bitmap bmp=decodeThumbnail(uri,1400);runOnUiThread(()->{if(bmp!=null&&!isFinishing())iv.setImageBitmap(bmp);});}catch(Exception ignored){}});
        new AlertDialog.Builder(this).setTitle("صورة قبل الترميم المرجعية").setView(iv).setPositiveButton("إغلاق",null).show();
    }

    private View gpsLocationSection(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(13));card.setBackground(round(b.gpsCapturedAt>0?0xffE6F5F0:CARD_BG,14,b.gpsCapturedAt>0?0xff149176:CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,dp(4));card.setLayoutParams(p);
        card.addView(text("موقع المنزل (GPS)",15,NAVY,true));
        if(b.gpsCapturedAt<=0){
            TextView hint=text("لم يُثبَّت موقع المنزل بعد. الوضع الرسمي الافتراضي هو GPS حقيقي دون إنترنت؛ يمكن اختيار «دقة عالية من الهاتف» كبديل أسرع داخل الأبنية إن توفّرت خدمة موقع الشبكة.",12,MUTED,false);hint.setPadding(0,dp(5),0,dp(8));card.addView(hint);
            Button pin=smallButton("📍 تحديد موقع المنزل الحالي (GPS)",0xff149176);pin.setOnClickListener(v->requestHomeLocation(b));card.addView(pin,new LinearLayout.LayoutParams(-1,dp(48)));
        } else {
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);
            TextView status=text("✔ الموقع مثبَّت",13,0xff149176,true);status.setPadding(0,dp(5),0,dp(4));card.addView(status);
            card.addView(info("الدقة",QuantityCatalog.quantityNumber(b.gpsAccuracyM)+" م"+(b.gpsAccuracyM>GPS_ACCURACY_WARNING_M?" ⚠ تقريبية":"")));
            card.addView(info("المصدر",gpsSourceLabel(b.gpsSource)));
            card.addView(info("تاريخ ووقت التثبيت",fmt.format(new Date(b.gpsCapturedAt))));
            Button navigate=smallButton("🧭 الذهاب إلى الموقع",0xff149176);navigate.setOnClickListener(v->openNavigation(b));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(46));np.setMargins(0,dp(6),0,0);card.addView(navigate,np);
            Button update=smallButton("📍 تحديث موقع المنزل الحالي (GPS)",0xff0E7490);update.setOnClickListener(v->requestHomeLocation(b));LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,dp(46));up.setMargins(0,dp(6),0,0);card.addView(update,up);
        }
        return card;
    }

    /** Opens the beneficiary's real GPS fix in whatever navigation app the device has — never a sector/text
     * guess. A Geo URI (RFC 5870, "geo:lat,lng?q=lat,lng(label)") is handled by Google Maps and every other
     * standard Android map app, so this app is never tied to one provider. No fix yet -> a clear message,
     * never an estimated/sector-centroid location, with an offer to jump to the existing pinning flow. */
    private void openNavigation(AppDatabase.Beneficiary b){
        if(b==null||b.gpsCapturedAt<=0){
            AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("لا يوجد موقع GPS")
                    .setMessage("لا يوجد موقع GPS مثبَّت لهذا المستفيد بعد.").setNegativeButton("إغلاق",null);
            if(b!=null)builder.setPositiveButton("تثبيت الموقع الآن",(d,w)->requestHomeLocation(b));
            builder.show();
            return;
        }
        try{
            String label=Uri.encode(safe(b.name,"موقع المستفيد"));
            Uri geo=Uri.parse("geo:"+b.gpsLat+","+b.gpsLng+"?q="+b.gpsLat+","+b.gpsLng+"("+label+")");
            Intent intent=new Intent(Intent.ACTION_VIEW,geo);
            if(intent.resolveActivity(getPackageManager())!=null)startActivity(intent);
            else toast("لا يوجد تطبيق خرائط/ملاحة متاح على هذا الجهاز لفتح الموقع");
        }catch(Exception error){toast("تعذر فتح تطبيق الملاحة");}
    }

    /** Entry point for both "تحديد موقع المنزل" and "إعادة تحديد الموقع" — if a location already exists, it is
     * shown and an explicit confirmation is required before starting a new capture; nothing is ever silently
     * replaced. */
    private void requestHomeLocation(AppDatabase.Beneficiary b){
        if(b.gpsCapturedAt>0){
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);
            new AlertDialog.Builder(this).setTitle("يوجد موقع محفوظ مسبقاً")
                    .setMessage("الموقع الحالي:\nخط العرض: "+b.gpsLat+"\nخط الطول: "+b.gpsLng+"\nالدقة: "+QuantityCatalog.quantityNumber(b.gpsAccuracyM)+" م\nالمصدر: "+gpsSourceLabel(b.gpsSource)+"\nبتاريخ: "+fmt.format(new Date(b.gpsCapturedAt))+
                            "\n\nهل تريد استبداله بموقع جديد؟ لن يتم أي تغيير قبل نجاح الالتقاط الجديد وتأكيدك الصريح.")
                    .setPositiveButton("نعم، التقط موقعاً جديداً",(d,w)->beginCapture(b))
                    .setNegativeButton("تراجع",null).show();
        } else {
            beginCapture(b);
        }
    }

    private void beginCapture(AppDatabase.Beneficiary b){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            pendingGpsBeneficiaryId=b.id;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_LOCATION_PERMISSION);
            return;
        }
        chooseCaptureMode(b);
    }

    /** GPS Offline is always the official default and the only option offered when no network-based provider
     * is even enabled on the device — there is nothing meaningful to choose between in that case. "دقة عالية
     * من الهاتف" is only offered as an explicit alternate when it could actually do something GPS_PROVIDER
     * alone can't (faster indoors), and the dialog states plainly that it may not be pure GPS. */
    private void chooseCaptureMode(AppDatabase.Beneficiary b){
        if(!gpsHelper.isNetworkProviderEnabled()){captureHomeLocation(b,GpsLocationHelper.MODE_GPS_OFFLINE);return;}
        new AlertDialog.Builder(this).setTitle("طريقة تحديد الموقع")
                .setMessage("GPS Offline: يستخدم GPS الحقيقي فقط دون إنترنت — الوضع الرسمي الافتراضي.\n\nدقة عالية من الهاتف: يستخدم أفضل مزود موقع متاح على الجهاز (قد يشمل خدمة موقع الشبكة/Wi-Fi) لتسريع الالتقاط داخل الأبنية — يُذكر مصدر القراءة النهائية بوضوح دائماً، ولا يُستخدم لتغيير العنوان أو القطاع تلقائياً.")
                .setPositiveButton("GPS Offline (موصى به)",(d,w)->captureHomeLocation(b,GpsLocationHelper.MODE_GPS_OFFLINE))
                .setNeutralButton("دقة عالية من الهاتف",(d,w)->captureHomeLocation(b,GpsLocationHelper.MODE_DEVICE_HIGH_ACCURACY))
                .setNegativeButton("إلغاء",null).show();
    }

    /** Captures a fresh real fix over a live 60-90s window, keeping only the single best (most accurate)
     * reading seen — the OLD coordinates are never touched until this succeeds AND (for an update over an
     * existing fix, or any fix not from pure GPS) the user explicitly confirms. Cancelling at any point, or
     * the window ending with no reading at all, leaves the previously saved location 100% unchanged. */
    private void captureHomeLocation(AppDatabase.Beneficiary b,String mode){
        boolean isUpdate=b.gpsCapturedAt>0;
        LinearLayout progressBox=column(dp(6));
        TextView statusText=text("جارٍ البحث عن إشارة موقع…",14,TEXT,false);progressBox.addView(statusText);
        TextView accuracyText=text("لم تُستقبل أي قراءة بعد",13,MUTED,true);accuracyText.setPadding(0,dp(8),0,0);progressBox.addView(accuracyText);
        TextView sourceText=text("الوضع: "+gpsSourceLabel(mode),12,0xff0E7490,true);sourceText.setPadding(0,dp(6),0,0);progressBox.addView(sourceText);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("جارٍ تحديد الموقع…").setView(progressBox)
                .setPositiveButton("قبول القراءة الحالية",null)
                .setNegativeButton("إلغاء",(d,w)->gpsHelper.cancel())
                .setCancelable(false).create();
        gpsProgressDialog=dialog;
        dialog.setOnShowListener(v->{
            Button acceptButton=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            acceptButton.setEnabled(false);
            acceptButton.setOnClickListener(x->gpsHelper.acceptNow());
        });
        dialog.show();
        gpsHelper.startCapture(mode,GPS_CAPTURE_WINDOW_MS,new GpsLocationHelper.Callback(){
            @Override public void onProgress(Location best,long elapsedMs,long windowMs){
                long remaining=Math.max(0,(windowMs-elapsedMs)/1000);
                if(best==null){
                    statusText.setText("جارٍ البحث عن إشارة موقع… ("+remaining+" ث متبقية)");
                    accuracyText.setText("لم تُستقبل أي قراءة بعد");
                } else {
                    statusText.setText("أفضل قراءة حتى الآن ("+remaining+" ث متبقية)");
                    accuracyText.setText("الدقة: "+QuantityCatalog.quantityNumber(best.getAccuracy())+" م"+(best.getAccuracy()>GPS_ACCURACY_WARNING_M?" ⚠ تقريبية":"")+"  •  المصدر الفعلي: "+gpsSourceLabel(sourceFor(best)));
                    Button acceptButton=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(acceptButton!=null)acceptButton.setEnabled(true);
                }
            }
            @Override public void onFinished(Location location){
                if(gpsProgressDialog!=null){gpsProgressDialog.dismiss();gpsProgressDialog=null;}
                String source=sourceFor(location);
                float accuracy=location.getAccuracy();
                String sourceNote=GpsLocationHelper.MODE_GPS_OFFLINE.equals(source)?"":"\n\n⚠ ملاحظة: هذه القراءة اعتمدت على مزود موقع الشبكة/الهاتف — ليست GPS صافياً.";
                if(accuracy>GPS_ACCURACY_WARNING_M){
                    new AlertDialog.Builder(MainActivity.this).setTitle("موقع تقريبي — دقة ضعيفة")
                            .setMessage("دقة الالتقاط الحالية "+QuantityCatalog.quantityNumber(accuracy)+" متراً، وهي أضعف من الحد المُوصى به (30 متراً)."+sourceNote+"\n\nيمكنك إعادة المحاولة في مكان أكثر انفتاحاً، أو القبول يدوياً رغم ضعف الدقة.")
                            .setPositiveButton("إعادة المحاولة",(d,w)->captureHomeLocation(b,mode))
                            .setNeutralButton("قبول رغم ضعف الدقة",(d,w)->saveHomeLocation(b,location,source))
                            .setNegativeButton("إلغاء",null).show();
                } else if(isUpdate){
                    new AlertDialog.Builder(MainActivity.this).setTitle("تأكيد الموقع الجديد")
                            .setMessage("تم التقاط موقع جديد بدقة "+QuantityCatalog.quantityNumber(accuracy)+" متراً."+sourceNote+"\nهل تريد استبدال الموقع المحفوظ سابقاً بهذا الموقع؟")
                            .setPositiveButton("استبدال الموقع",(d,w)->saveHomeLocation(b,location,source))
                            .setNegativeButton("إلغاء — الاحتفاظ بالموقع القديم",null).show();
                } else if(!GpsLocationHelper.MODE_GPS_OFFLINE.equals(source)){
                    new AlertDialog.Builder(MainActivity.this).setTitle("حفظ الموقع؟")
                            .setMessage("دقة الالتقاط: "+QuantityCatalog.quantityNumber(accuracy)+" متراً."+sourceNote)
                            .setPositiveButton("حفظ الموقع",(d,w)->saveHomeLocation(b,location,source))
                            .setNegativeButton("إلغاء",null).show();
                } else {
                    saveHomeLocation(b,location,source);
                }
            }
            @Override public void onUnavailable(String reason){
                if(gpsProgressDialog!=null){gpsProgressDialog.dismiss();gpsProgressDialog=null;}
                new AlertDialog.Builder(MainActivity.this).setTitle("تعذر تحديد الموقع").setMessage(reason)
                        .setPositiveButton("إعادة المحاولة",(d,w)->captureHomeLocation(b,mode)).setNegativeButton("إغلاق",null).show();
            }
        });
    }

    private String sourceFor(Location location){
        return LocationManager.GPS_PROVIDER.equals(location.getProvider())?GpsLocationHelper.MODE_GPS_OFFLINE:GpsLocationHelper.MODE_DEVICE_HIGH_ACCURACY;
    }

    private void saveHomeLocation(AppDatabase.Beneficiary b,Location location,String source){
        db.setGpsLocation(b.id,location.getLatitude(),location.getLongitude(),location.getAccuracy(),System.currentTimeMillis(),source);
        toast("تم تثبيت موقع المنزل");
        AppDatabase.Beneficiary fresh=db.beneficiary(b.id);
        if(beneficiaryDialog!=null)beneficiaryDialog.dismiss();
        renderActive();
        if(fresh!=null)showBeneficiary(fresh);
    }

    // ---- Phase 10: multi-engineer sync with the Windows desktop app (transport-independent SyncTarget) ----

    /** Exports EVERY revision (study + followup stage, including superseded ones — Windows needs the full
     * correction history to rebuild study_uuid groupings) of the current project, then hands the package to
     * whatever share transport the user picks. Success/failure/cancellation is handled entirely in
     * onActivityResult(REQUEST_SYNC_SHARE,...) — this method never itself decides sync_status. */
    private void showSyncWithWindows(){
        List<AppDatabase.Beneficiary> rows=db.list(currentProjectId,"","all");
        if(rows.isEmpty()){toast("لا توجد بيانات في هذا المشروع لتصديرها");return;}
        new AlertDialog.Builder(this).setTitle("مزامنة مع جهاز Windows")
                .setMessage("سيتم تجهيز حزمة JSON تضم "+rows.size()+" سجلاً (كل الدراسات وإصداراتها، بما فيها النسخ السابقة المستبدَلة) من مشروع «"+currentProject+"»، ثم تُعرض نافذة مشاركة لإرسالها إلى جهاز Windows بأي وسيلة متاحة (نقل ملفات، بريد، تطبيق مراسلة). هذا التطبيق لا يستخدم إنترنت مباشرة ولا يعتمد أي عمل ميداني على نجاح هذه المزامنة.")
                .setPositiveButton("تجهيز ومشاركة",(d,w)->{
                    List<StudyRevisionPackage> packages=SyncPackageBuilder.build(db,rows);
                    List<String> uuids=new ArrayList<>();for(StudyRevisionPackage p:packages)uuids.add(p.revisionUuid);
                    SyncTarget target=new ShareIntentDesktopAdapter(this,currentProject,currentEngineerName(),REQUEST_SYNC_SHARE);
                    SyncResult result=target.export(packages);
                    if(result.success){pendingSyncRevisionUuids=uuids;}
                    else new AlertDialog.Builder(this).setTitle("تعذّر تجهيز حزمة المزامنة").setMessage(result.message).setPositiveButton("حسناً",null).show();
                }).setNegativeButton("إلغاء",null).show();
    }

    // ---- Phase 8: optional contacts sync ----

    private boolean hasContactsPermission(){
        return checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
                &&checkSelfPermission(Manifest.permission.WRITE_CONTACTS)==PackageManager.PERMISSION_GRANTED;
    }

    /** Syncs a single beneficiary's phone contact — requests the (fully optional) permission on first use
     * only; a rejection just toasts and leaves the rest of the screen fully usable. */
    private void syncBeneficiaryContact(AppDatabase.Beneficiary b){
        if(!hasContactsPermission()){
            pendingContactsSyncMode=1;pendingContactsSyncBeneficiaryId=b.id;
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS,Manifest.permission.WRITE_CONTACTS},REQUEST_CONTACTS_PERMISSION);
            return;
        }
        if(b.phone1.isEmpty()&&b.phone2.isEmpty()){toast("لا يوجد رقم هاتف مسجَّل لهذا المستفيد لمزامنته");return;}
        ContactsSyncHelper.SyncResult result=ContactsSyncHelper.syncOne(this,currentProject,b.followupBatch,b.name,b.phone1,b.phone2,formatRegistration(b.registration),b.sector,b.street,followupStateLabel(followupState(b)));
        toast(result.created>0?"تمت إضافة جهة اتصال جديدة":result.updated>0?"تم تحديث جهة الاتصال الموجودة (بلا تكرار)":result.failed>0?"تعذّرت المزامنة — السبب: "+result.lastError:"تعذّرت المزامنة");
    }

    /** Syncs every followup-stage beneficiary in one list (or every list in the project) — always looked up
     * fresh by phone number per beneficiary, so re-running this on the same list never creates duplicates. */
    private void syncBatchContacts(String batchName){
        if(!hasContactsPermission()){
            pendingContactsSyncMode=2;pendingContactsSyncBatch=batchName==null?"":batchName;
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS,Manifest.permission.WRITE_CONTACTS},REQUEST_CONTACTS_PERMISSION);
            return;
        }
        List<AppDatabase.Beneficiary> rows=db.listStage(currentProjectId,"","followup","all",batchName==null||batchName.isEmpty()?"all":batchName);
        ContactsSyncHelper.SyncResult total=new ContactsSyncHelper.SyncResult();
        for(AppDatabase.Beneficiary b:rows)total.add(ContactsSyncHelper.syncOne(this,currentProject,b.followupBatch,b.name,b.phone1,b.phone2,formatRegistration(b.registration),b.sector,b.street,followupStateLabel(followupState(b))));
        String failedPart=total.failed>0?("، "+total.failed+" فشلت — السبب: "+total.lastError):"";
        toast("تمت المزامنة: "+total.created+" جهة اتصال جديدة، "+total.updated+" مُحدَّثة (بلا تكرار)، "+total.skippedNoPhone+" بلا رقم هاتف"+failedPart);
    }

    /** Settings entry point: pick "كل القوائم" or a single imported list, then sync all its beneficiaries'
     * phone numbers into the device's contacts. */
    private void showContactsSyncPicker(){
        List<String> batchNames=new ArrayList<>();batchNames.add("كل القوائم");batchNames.addAll(db.followupBatches(currentProjectId));
        LinearLayout box=column(dp(16));
        box.addView(text("مزامنة قائمة بجهات الاتصال",18,NAVY,true));
        TextView hint=text("يُضاف اسم كل مستفيد بصيغة [المشروع] | [رقم القائمة] | [الاسم]. إعادة المزامنة لا تُنشئ تكراراً — يُحدَّث الاسم فقط عند وجود رقم الهاتف نفسه مسبقاً.",12,MUTED,false);hint.setPadding(0,dp(4),0,dp(8));box.addView(hint);
        Spinner batchSpinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,batchNames);batchSpinner.setAdapter(adapter);batchSpinner.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));box.addView(batchSpinner,new LinearLayout.LayoutParams(-1,dp(46)));
        new AlertDialog.Builder(this).setView(box).setPositiveButton("بدء المزامنة",(d,w)->{
            String selected=batchSpinner.getSelectedItemPosition()>0?(String)batchSpinner.getSelectedItem():"all";
            syncBatchContacts(selected);
        }).setNegativeButton("إلغاء",null).show();
    }

    // ---- Phase 6: operational map (sector boundaries + beneficiary points + GPS heatmap) ----

    private static final String[] MAP_FILTER_NAMES={"كل المستفيدين","بانتظار الزيارة","قيد التنفيذ","متأخر","جاهز للاستلام"};
    private static final String[] MAP_FILTER_VALUES={"all","awaiting","in_progress","late","ready_for_pickup"};

    /** Classifies a followup-stage beneficiary into one map/filter bucket. 'late' reuses the exact same
     * execution_start_at (Phase 4) logic as the follow-up detail screen, so the two never disagree. Bucket
     * assignment is read-only classification for display — it never writes back to the beneficiary row. */
    // ===== F3: explicit followup/delivery state ==========================================================
    /** The explicit followup state (6 values). Uses b.followupStatus when the engineer has set it; otherwise derives
     * a sensible default from the existing work-delivered/progress/visit fields so pre-F3 rows still bucket cleanly. */
    private String followupState(AppDatabase.Beneficiary b){
        String s=b.followupStatus==null?"":b.followupStatus.trim();
        if(!s.isEmpty())return s;
        if(b.workDelivered)return AppDatabase.FS_DELIVERED;
        if("review".equals(b.visitStatus))return AppDatabase.FS_REVIEW;
        if("completed".equals(b.visitStatus)||b.progressPercent>=100)return AppDatabase.FS_READY;
        if(b.progressPercent>0)return AppDatabase.FS_IN_PROGRESS;
        return AppDatabase.FS_NOT_STARTED;
    }
    private String followupStateLabel(String s){switch(s==null?"":s){case "in_progress":return "قيد التنفيذ";case "ready":return "جاهز للتسليم";case "delivered":return "تم التسليم";case "review":return "يحتاج مراجعة";case "deferred":return "مؤجل";default:return "لم يبدأ";}}
    private int followupStateColor(String s){switch(s==null?"":s){case "in_progress":return 0xff0E7490;case "ready":return 0xff149176;case "delivered":return 0xff2E7D32;case "review":return 0xffBD4868;case "deferred":return 0xff8A6D3B;default:return 0xffC9851C;}}
    /** «عناوين ناقصة» = the working address is NOT the authoritative one from «قائمة العناوين» (empty, or «من التقييم»). */
    private boolean addressMissing(AppDatabase.Beneficiary b){return !"قائمة العناوين".equals(b.addressSource);}
    private boolean noGps(AppDatabase.Beneficiary b){return b.gpsCapturedAt<=0;}
    /** Does a beneficiary pass the selected F3 quick-filter? */
    private boolean followupFilterMatch(AppDatabase.Beneficiary b,String filter){
        String st=followupState(b);
        switch(filter==null?"all":filter){
            case "all":return true;
            case "remaining":return !AppDatabase.FS_DELIVERED.equals(st);
            case "no_gps":return noGps(b);
            case "missing_address":return addressMissing(b);
            default:return filter.equals(st);
        }
    }
    /** F3: recompute and render the followup counters strip over the (unfiltered) followup list. */
    private void updateFollowupCounters(LinearLayout strip,List<AppDatabase.Beneficiary> rows){
        int total=rows.size(),remaining=0,inProg=0,ready=0,delivered=0,review=0,deferred=0,gpsMissing=0,addrMissing=0;
        for(AppDatabase.Beneficiary b:rows){
            String s=followupState(b);
            if(!AppDatabase.FS_DELIVERED.equals(s))remaining++;
            switch(s){case "in_progress":inProg++;break;case "ready":ready++;break;case "delivered":delivered++;break;case "review":review++;break;case "deferred":deferred++;break;}
            if(noGps(b))gpsMissing++;
            if(addressMissing(b))addrMissing++;
        }
        strip.removeAllViews();
        strip.addView(counterChip("الإجمالي",total,NAVY));
        strip.addView(counterChip("المتبقي",remaining,0xffC9851C));
        strip.addView(counterChip("قيد التنفيذ",inProg,0xff0E7490));
        strip.addView(counterChip("جاهز للتسليم",ready,0xff149176));
        strip.addView(counterChip("تم التسليم",delivered,0xff2E7D32));
        strip.addView(counterChip("يحتاج مراجعة",review,0xffBD4868));
        strip.addView(counterChip("مؤجل",deferred,0xff8A6D3B));
        strip.addView(counterChip("بدون GPS",gpsMissing,0xff6B7280));
        strip.addView(counterChip("عناوين ناقصة",addrMissing,0xff9C5A2B));
    }
    private View counterChip(String label,int value,int color){
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);
        cell.setBackground(round((color&0x00FFFFFF)|0x14000000,12,color,1));cell.setPadding(dp(13),dp(6),dp(13),dp(6));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(0,0,dp(7),0);cell.setLayoutParams(p);
        TextView v=text(String.valueOf(value),18,color,true);v.setGravity(Gravity.CENTER);cell.addView(v);
        TextView l=text(label,10,MUTED,false);l.setGravity(Gravity.CENTER);cell.addView(l);
        return cell;
    }

    private String mapStatusBucket(AppDatabase.Beneficiary b){
        if("completed".equals(b.visitStatus))return b.workDelivered?"done":"ready_for_pickup";
        long executionStart=AppDatabase.executionStartAt(b);
        if(executionStart>0&&System.currentTimeMillis()>executionStart+35L*24*3600*1000)return "late";
        if("pending".equals(b.visitStatus))return b.progressPercent>0?"in_progress":"awaiting";
        if("review".equals(b.visitStatus))return "in_progress";
        return "awaiting";
    }
    private String mapBucketLabel(String bucket){switch(bucket){case "awaiting":return "بانتظار الزيارة";case "in_progress":return "قيد التنفيذ";case "late":return "متأخر";case "ready_for_pickup":return "جاهز للاستلام";default:return "مكتمل ومُسلَّم";}}
    private int mapBucketColor(String bucket){switch(bucket){case "awaiting":return 0xffC9851C;case "in_progress":return 0xff0E7490;case "late":return 0xffBD4868;case "ready_for_pickup":return 0xff149176;default:return 0xff6B7280;}}
    // "Next step" turns the list from a name registry into a daily task list. Derived purely from the existing
    // status — no new DB field, no workflow change. Study uses studyStatus; followup uses the richer read-only
    // operational bucket from mapStatusBucket (so "متأخر" surfaces on the row, not just on the map).
    private String nextStepStudy(String s){switch(s==null?"":s){case "ready":return "راجع واعتمد الدراسة";case "office_review":return "أكمل المراجعة والاعتماد";case "approved":return "جاهزة — ضمن دفعة الاعتماد";case "superseded":return "نسخة سابقة — للاطلاع فقط";default:return "أكمل بيانات الدراسة";}}
    private String nextStepFollowup(String bucket){switch(bucket==null?"":bucket){case "in_progress":return "حدّث نسبة الإنجاز وصوّر العمل";case "late":return "مراجعة عاجلة";case "ready_for_pickup":return "وثّق الاستلام النهائي";case "done":return "مكتمل — لا إجراء مطلوب";default:return "ابدأ زيارة المستفيد";}}
    /** F3: the daily next-step line for a followup file, derived from the explicit followup state. */
    private String nextStepFollowupState(String s){switch(s==null?"":s){case "in_progress":return "حدّث نسبة الإنجاز وصوّر العمل";case "ready":return "وثّق التسليم النهائي";case "delivered":return "مكتمل — تم التسليم";case "review":return "مراجعة عاجلة";case "deferred":return "مؤجّل — أعد الجدولة";default:return "ابدأ زيارة المستفيد";}}
    private int studyStageIndex(String s){switch(s==null?"":s){case "ready":return 2;case "office_review":return 3;case "approved":return 4;case "superseded":return 0;default:return 1;}}

    // ===== DEBUG-ONLY — REMOVE BEFORE RELEASE ==================================================
    // Seeds 8 clearly-labelled "(تجريبي)" beneficiaries covering every row state, using the app's
    // own db.addBeneficiary() — no schema/logic change. Triggered by a hidden long-press on the
    // Studies/Followup page title. Delete this method, DEBUG_SEED, and the long-press hook before release.
    private void seedDebugTestData(){
        long now=System.currentTimeMillis();
        String[][] d={
            {"زياد فارس (تجريبي)","شرق اليرموك 1","شارع لوبية","study","draft","pending","0","0","?"},
            {"سلمى حداد (تجريبي)","شرق اليرموك 1","حسن سلامة","study","ready","pending","0","0","1200"},
            {"منى درويش (تجريبي)","غرب اليرموك 1","الشجرة","study","approved","pending","0","0","3400"},
            {"أحمد موسى (تجريبي)","شرق اليرموك 1","شارع لوبية","followup","approved","pending","0","0","3982"},
            {"هبة سليمان (تجريبي)","غرب اليرموك 1","عين غزال","followup","approved","pending","45","0","2750"},
            {"خالد نبهان (تجريبي)","غرب اليرموك 3","الجرمق","followup","approved","review","30","0","2600"},
            {"نور عبد الله (تجريبي)","غرب اليرموك 2","غزة","followup","approved","completed","100","0","3100"},
            {"سامر قاسم (تجريبي)","شرق اليرموك 2","فلسطين","followup","approved","completed","100","1","2600"},
        };
        int seq=1;
        for(String[] r:d){
            AppDatabase.Beneficiary b=new AppDatabase.Beneficiary();
            b.name=r[0];b.sector=r[1];b.street=r[2];b.address=r[2];
            b.workflowStage=r[3];b.studyStatus=r[4];b.visitStatus=r[5];
            b.progressPercent=Integer.parseInt(r[6]);b.workDelivered="1".equals(r[7]);b.amount=r[8];
            b.registration="T-"+String.format(java.util.Locale.US,"%04d",seq);
            b.phone1="0999"+String.format(java.util.Locale.US,"%06d",seq);
            b.engineerName="م. تجريبي";b.matchStatus="بيانات تجريبية";
            int idx=AddressClassifier.SECTOR_ORDER.indexOf(b.sector);b.sectorOrder=idx>=0?idx:999;
            if("followup".equals(b.workflowStage)){b.followupBatch="قائمة تجريبية 1";b.approvedAt=now;}
            // make one row genuinely "late": execution start (delivery + first-payment) 40 days ago
            if(b.name.startsWith("خالد")){long old=now-40L*24*3600*1000;b.deliveryConfirmedAt=old;b.firstPaymentAvailableAt=old;b.visitStatus="pending";b.progressPercent=30;}
            db.addBeneficiary(currentProjectId,b);
            if(b.name.startsWith("أحمد موسى")||b.name.startsWith("منى درويش"))seedReferencePhotos(b.id); // DEBUG: 2 before_reference photos to test B2/B3/B4 (followup + study)
            seq++;
        }
        renderActive();toast("تم إنشاء 8 سجلات تجريبية (debug) + صور مرجعية");
    }
    /** DEBUG-ONLY: fabricate 2 «before_reference» photos for a beneficiary (simulates a followup-list import that
     * carried embedded before-photos) so B2/B3/B4 can be tested without the SAF picker. REMOVE BEFORE RELEASE. */
    private void seedReferencePhotos(long benId){
        int[] colors={0xffCC5050,0xff4682C8};
        java.io.File dir=new java.io.File(getFilesDir(),"ref_photos/"+benId);dir.mkdirs();
        int n=1;
        for(int c:colors){
            try{
                Bitmap bmp=Bitmap.createBitmap(480,360,Bitmap.Config.ARGB_8888);bmp.eraseColor(c);
                java.io.File out=new java.io.File(dir,String.format(Locale.US,"ref_%02d.jpg",n));
                try(java.io.FileOutputStream fo=new java.io.FileOutputStream(out)){bmp.compress(Bitmap.CompressFormat.JPEG,90,fo);}
                bmp.recycle();
                db.addPhoto(benId,BeneficiaryPhotoManager.BEFORE_REFERENCE,Uri.fromFile(out).toString(),"مرجعية_تجريبية_"+n+".jpg",System.currentTimeMillis(),"صورة قبل الترميم مستوردة (تجريبية)");
                n++;
            }catch(Exception ignored){}
        }
    }
    // ===== end DEBUG-ONLY =====================================================================

    private SectorBoundaries.Sector findSector(List<SectorBoundaries.Sector> list,String name){for(SectorBoundaries.Sector s:list)if(s.name.equals(name))return s;return null;}
    /** True area-weighted polygon centroid (not a plain vertex average, which can land outside a concave/irregular
     * shape like the L-shaped sector splits) — this is what keeps every "تقديري" fallback marker visibly inside
     * its own sector outline instead of drifting into a neighboring one. */
    private float[] polygonCentroid(SectorBoundaries.Sector s){
        int n=s.fracX.length;double area=0,cx=0,cy=0;
        for(int i=0;i<n;i++){int j=(i+1)%n;double cross=s.fracX[i]*s.fracY[j]-s.fracX[j]*s.fracY[i];area+=cross;cx+=(s.fracX[i]+s.fracX[j])*cross;cy+=(s.fracY[i]+s.fracY[j])*cross;}
        area*=0.5;
        if(Math.abs(area)<1e-9){float sx=0,sy=0;for(int i=0;i<n;i++){sx+=s.fracX[i];sy+=s.fracY[i];}return new float[]{sx/n,sy/n};}
        return new float[]{(float)(cx/(6*area)),(float)(cy/(6*area))};
    }

    private void showOperationalMap(){
        Bitmap mapBitmap;List<SectorBoundaries.Sector> sectorList;
        try{mapBitmap=BitmapFactory.decodeResource(getResources(),R.drawable.yarmouk_camp_calibrated);sectorList=SectorBoundaries.load(this);}
        catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر تحميل الخريطة").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();return;}

        LinearLayout page=column(dp(8));
        // Compact title row + a toggle that hides the summary/dashboard so the MAP can take the whole screen.
        LinearLayout titleRow2=new LinearLayout(this);titleRow2.setGravity(Gravity.CENTER_VERTICAL);page.addView(titleRow2,new LinearLayout.LayoutParams(-1,-2));
        TextView mapTitle=text("الخريطة التشغيلية",17,NAVY,true);titleRow2.addView(mapTitle,new LinearLayout.LayoutParams(0,-2,1f));
        Button bigMapBtn=smallButton("⛶ تكبير الخريطة",0xff0E7490);titleRow2.addView(bigMapBtn,new LinearLayout.LayoutParams(dp(140),dp(40)));
        // FINAL FIELD USABILITY FIX §5: a small «؟ دليل» button with a short, field-focused map help sheet.
        Button guideBtn=smallButton("؟ دليل",0xff7A5CB8);LinearLayout.LayoutParams gbp=new LinearLayout.LayoutParams(dp(72),dp(40));gbp.setMargins(dp(6),0,0,0);guideBtn.setOnClickListener(v->showMapGuide());titleRow2.addView(guideBtn,gbp);

        // ALWAYS-VISIBLE background status + chooser — replaces the old single "cycle" button that gave no
        // indication of which background was active. Now: one line always says exactly what's showing and
        // whether it's calibrated, and three explicit named chips pick the background directly (no guessing/
        // clicking-and-hoping). The satellite chip is disabled until an image has actually been imported.
        final TextView calibStatus=text("",12,TEXT,true);calibStatus.setPadding(dp(10),dp(7),dp(10),dp(7));LinearLayout.LayoutParams csp=new LinearLayout.LayoutParams(-1,-2);csp.setMargins(0,dp(4),0,dp(2));page.addView(calibStatus,csp);
        final String[] mapKey={AppDatabase.MAP_CALIBRATED};
        LinearLayout bgChipRow=new LinearLayout(this);bgChipRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams bcrp=new LinearLayout.LayoutParams(-1,-2);bcrp.setMargins(0,0,0,dp(4));page.addView(bgChipRow,bcrp);
        final Button chipCalibrated=smallButton("الخريطة المعايرة",0xff7A5CB8);
        final Button chipPlan=smallButton("المخطط التنظيمي",0xff7A5CB8);
        final Button chipSatellite=smallButton("🛰 القمر الصناعي",0xff7A5CB8);
        Button[] bgChips={chipCalibrated,chipPlan,chipSatellite};
        String[] bgChipKeys={AppDatabase.MAP_CALIBRATED,AppDatabase.MAP_PLAN,AppDatabase.MAP_SATELLITE};
        for(int i=0;i<bgChips.length;i++){LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(40),1);if(i>0)cp.setMargins(dp(4),0,0,0);bgChipRow.addView(bgChips[i],cp);}

        // Operational Map Pro — a summary bar + a per-sector distribution strip that reflect the current list/filter,
        // so the engineer immediately understands how the imported list spread across the five sectors.
        final TextView summaryBar=text("",12,NAVY,true);summaryBar.setPadding(dp(10),dp(7),dp(10),dp(7));summaryBar.setBackground(round(CARD_BG,10,CARD_BORDER,1));LinearLayout.LayoutParams sbp=new LinearLayout.LayoutParams(-1,-2);sbp.setMargins(0,dp(4),0,dp(2));page.addView(summaryBar,sbp);
        // M1 (Map V2 declutter): the TWO key filters — «القائمة» + «القطاع» — are up-front (not hidden behind ⚙)
        // so the engineer narrows the map to one list / one sector immediately.
        final LinearLayout mainFilters=new LinearLayout(this);mainFilters.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams mfp=new LinearLayout.LayoutParams(-1,-2);mfp.setMargins(0,dp(4),0,dp(2));page.addView(mainFilters,mfp);
        mainFilters.addView(text("القائمة:",12,NAVY,true),new LinearLayout.LayoutParams(-2,-2));
        Spinner batchFilter=new Spinner(this);List<String>batchNames=new ArrayList<>();batchNames.add("كل القوائم");batchNames.addAll(db.followupBatches(currentProjectId));ArrayAdapter<String>batchAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,batchNames);batchFilter.setAdapter(batchAdapter);batchFilter.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));LinearLayout.LayoutParams bfp=new LinearLayout.LayoutParams(0,dp(44),1.3f);bfp.setMargins(dp(4),0,dp(8),0);mainFilters.addView(batchFilter,bfp);
        mainFilters.addView(text("القطاع:",12,NAVY,true),new LinearLayout.LayoutParams(-2,-2));
        Spinner sectorFilter=new Spinner(this);List<String>sectorNames=new ArrayList<>();sectorNames.add("كل القطاعات");for(SectorBoundaries.Sector s:sectorList)sectorNames.add(s.name);ArrayAdapter<String>sectorAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sectorNames);sectorFilter.setAdapter(sectorAdapter);sectorFilter.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));LinearLayout.LayoutParams sfp=new LinearLayout.LayoutParams(0,dp(44),1f);sfp.setMargins(dp(4),0,0,0);mainFilters.addView(sectorFilter,sfp);
        // MAP V3: a quick LOCATION filter — الكل / بدون GPS فقط (تقديري) / GPS الحقيقي فقط — so the engineer isolates
        // exactly who still needs a real GPS fix on the visit, or only the confirmed locations.
        final LinearLayout locRow=new LinearLayout(this);locRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams lrp=new LinearLayout.LayoutParams(-1,-2);lrp.setMargins(0,dp(2),0,dp(2));page.addView(locRow,lrp);
        locRow.addView(text("الموقع:",12,NAVY,true),new LinearLayout.LayoutParams(-2,-2));
        final String[] GPS_FILTER_NAMES={"الكل","بدون GPS فقط (تقديري)","GPS الحقيقي فقط"};
        Spinner gpsFilter=new Spinner(this);gpsFilter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,GPS_FILTER_NAMES));gpsFilter.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));LinearLayout.LayoutParams gfp=new LinearLayout.LayoutParams(0,dp(44),1);gfp.setMargins(dp(4),0,0,0);locRow.addView(gpsFilter,gfp);
        // Detailed/advanced controls (status filter, layers, calibration, satellite) stay hidden behind a toggle.
        final LinearLayout controlsBox=column(dp(6));controlsBox.setVisibility(View.GONE);
        Button toggleCtl=smallButton("⚙ خيارات متقدمة",0xff7A5CB8);toggleCtl.setOnClickListener(v->controlsBox.setVisibility(controlsBox.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        LinearLayout.LayoutParams tcp=new LinearLayout.LayoutParams(-1,dp(40));tcp.setMargins(0,dp(4),0,dp(2));page.addView(toggleCtl,tcp);
        page.addView(controlsBox,new LinearLayout.LayoutParams(-1,-2));

        controlsBox.addView(settingsSection("الحالة"));
        LinearLayout filterRow=new LinearLayout(this);filterRow.setGravity(Gravity.CENTER_VERTICAL);controlsBox.addView(filterRow,new LinearLayout.LayoutParams(-1,dp(46)));
        filterRow.addView(text("الحالة:",12,NAVY,true),new LinearLayout.LayoutParams(-2,-2));
        Spinner statusFilter=new Spinner(this);statusFilter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,MAP_FILTER_NAMES));statusFilter.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(0,-2,1);stp.setMargins(dp(4),0,0,0);filterRow.addView(statusFilter,stp);

        controlsBox.addView(settingsSection("الطبقات"));
        LinearLayout layerRow=new LinearLayout(this);layerRow.setPadding(0,dp(6),0,dp(4));controlsBox.addView(layerRow,new LinearLayout.LayoutParams(-1,dp(40)));
        CheckBox cbSectors=new CheckBox(this);cbSectors.setText("حدود القطاعات");cbSectors.setChecked(true);cbSectors.setTextSize(11);layerRow.addView(cbSectors,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox cbPoints=new CheckBox(this);cbPoints.setText("نقاط المستفيدين");cbPoints.setChecked(true);cbPoints.setTextSize(11);layerRow.addView(cbPoints,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox cbHeatmap=new CheckBox(this);cbHeatmap.setText("الخريطة الحرارية");cbHeatmap.setChecked(false);cbHeatmap.setTextSize(11);layerRow.addView(cbHeatmap,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout layerRow2=new LinearLayout(this);layerRow2.setPadding(0,0,0,dp(4));controlsBox.addView(layerRow2,new LinearLayout.LayoutParams(-1,dp(40)));
        CheckBox cbStreets=new CheckBox(this);cbStreets.setText("أسماء الشوارع الحقيقية (OpenStreetMap)");cbStreets.setChecked(true);cbStreets.setTextSize(11);layerRow2.addView(cbStreets,new LinearLayout.LayoutParams(0,-2,1));

        controlsBox.addView(settingsSection("إعداد القمر الصناعي — 3 خطوات بالترتيب"));
        // Numbered, self-checking setup flow — replaces 3 scattered buttons the engineer had to discover in
        // the right order on their own. Each step shows a ✓ once done and explains what comes next, instead
        // of silently doing nothing until the earlier step is complete.
        final TextView step1Status=text("",12,TEXT,false);step1Status.setPadding(0,dp(2),0,dp(6));controlsBox.addView(step1Status);
        Button satBtn=smallButton("① استيراد صورة القمر الصناعي (بلا إنترنت)",0xff0E7490);satBtn.setOnClickListener(v->chooseSatelliteImage());controlsBox.addView(satBtn,new LinearLayout.LayoutParams(-1,dp(42)));
        final TextView step2Status=text("",12,TEXT,false);step2Status.setPadding(0,dp(8),0,dp(6));controlsBox.addView(step2Status);
        Button manageCalib=smallButton("② معايرة صورة القمر الصناعي",0xff7A5CB8);LinearLayout.LayoutParams mcbp=new LinearLayout.LayoutParams(-1,dp(42));mcbp.setMargins(0,dp(2),0,0);controlsBox.addView(manageCalib,mcbp);
        final TextView step3Status=text("③ التفعيل — اختر «🛰 القمر الصناعي» من الأزرار الثلاثة أعلى الخريطة",12,MUTED,false);step3Status.setPadding(0,dp(8),0,dp(2));controlsBox.addView(step3Status);

        // M1: the sector-distribution dashboard now sits BELOW the map (added later) so the top stays clean and the
        // map gets the height — the strip is still one horizontal scroll of cards, tap to focus a sector.
        final android.widget.HorizontalScrollView dashScroll=new android.widget.HorizontalScrollView(this);dashScroll.setHorizontalScrollBarEnabled(false);final LinearLayout dash=new LinearLayout(this);dash.setOrientation(LinearLayout.HORIZONTAL);dashScroll.addView(dash);LinearLayout.LayoutParams dsp=new LinearLayout.LayoutParams(-1,-2);dsp.setMargins(0,dp(6),0,dp(2));
        // The map is the star — give it ALL the remaining vertical space (weight) so it's big and readable, with a
        // sensible minimum, instead of a fixed strip squeezed under the controls.
        final Runnable[] refreshHolder=new Runnable[1];
        FrameLayout mapHolder=new FrameLayout(this);mapHolder.setMinimumHeight(dp(360));page.addView(mapHolder,new LinearLayout.LayoutParams(-1,0,1f));
        OperationalMapView mapView=new OperationalMapView(this);mapView.setMapBitmap(mapBitmap);mapView.setSectors(sectorList);mapHolder.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        final TextView mapCount=text("",12,Color.WHITE,true);mapCount.setPadding(dp(10),dp(6),dp(10),dp(6));mapCount.setBackground(round(0xCC0B2F52,9,0,0));
        FrameLayout.LayoutParams mcp=new FrameLayout.LayoutParams(-2,-2);mcp.gravity=Gravity.TOP|Gravity.START;mcp.setMargins(dp(8),dp(8),dp(8),0);mapHolder.addView(mapCount,mcp);

        LinearLayout mapCtl=new LinearLayout(this);mapCtl.setGravity(Gravity.CENTER_VERTICAL);mapCtl.setPadding(0,dp(6),0,0);page.addView(mapCtl,new LinearLayout.LayoutParams(-1,-2));
        Button fitBtn=smallButton("⤢ ملاءمة الشاشة",0xff0E7490);fitBtn.setOnClickListener(v->mapView.fitToScreen());mapCtl.addView(fitBtn,new LinearLayout.LayoutParams(0,dp(42),1));
        // FINAL FIELD UI (#5): reset out of a focused sector back to the whole camp — clears the sector filter too,
        // not just the zoom, so "عرض كل المخيم" really does mean every beneficiary again.
        Button allCampBtn=smallButton("🗺 عرض كل المخيم",0xff7A5CB8);LinearLayout.LayoutParams acp=new LinearLayout.LayoutParams(0,dp(42),1);acp.setMargins(dp(6),0,0,0);mapCtl.addView(allCampBtn,acp);
        // FINAL FIELD USABILITY FIX §10 + §7: quick address analysis from inside the map — on its OWN full-width row
        // so the control row above stays uncluttered (no truncated labels). Scoped to the selected sector if one is
        // chosen, otherwise the whole camp. Reuses the existing «تحليل العناوين» (F5), no new logic.
        LinearLayout analyzeRow=new LinearLayout(this);analyzeRow.setPadding(0,dp(6),0,0);page.addView(analyzeRow,new LinearLayout.LayoutParams(-1,-2));
        Button analyzeBtn=smallButton("🏘 تحليل العناوين (الشوارع والحارات)",0xff7A5CB8);analyzeRow.addView(analyzeBtn,new LinearLayout.LayoutParams(-1,dp(42)));
        analyzeBtn.setOnClickListener(v->{int pos=sectorFilter.getSelectedItemPosition();showAddressAnalysis(pos>0?(String)sectorFilter.getSelectedItem():null);});
        // Background switch: the official detailed «المخطط التنظيمي» (clear street names, no sector polygons) vs the
        // calibrated map (GPS-aligned, carries the sector boundaries). Sector boundaries are auto-hidden on the plan
        // because those polygons were digitised on the calibrated map and would not line up here.
        // Overlay row: lay the official street plan over the satellite photo (rooftops + street names together).
        LinearLayout ovRow=new LinearLayout(this);ovRow.setGravity(Gravity.CENTER_VERTICAL);ovRow.setVisibility(View.GONE);page.addView(ovRow,new LinearLayout.LayoutParams(-1,-2));
        CheckBox cbOverlay=new CheckBox(this);cbOverlay.setText("المخطط فوق القمر الصناعي");cbOverlay.setTextSize(11);ovRow.addView(cbOverlay,new LinearLayout.LayoutParams(-2,-2));
        SeekBar ovAlpha=new SeekBar(this);ovAlpha.setMax(255);ovAlpha.setProgress(140);ovRow.addView(ovAlpha,new LinearLayout.LayoutParams(0,-2,1f));
        // Restyles the three background chips so the ACTIVE one is unmistakable (filled brand colour) and the
        // rest are neutral outlines — no more guessing which background is currently showing.
        Runnable[] restyleChipsHolder=new Runnable[1];
        restyleChipsHolder[0]=()->{
            boolean hasSat=satelliteFile().exists();
            for(int i=0;i<bgChips.length;i++){
                boolean active=bgChipKeys[i].equals(mapKey[0]);
                boolean enabled=!AppDatabase.MAP_SATELLITE.equals(bgChipKeys[i])||hasSat;
                bgChips[i].setEnabled(enabled);
                bgChips[i].setBackground(round(active?BRAND:(enabled?0xffEFEFF4:0xffE0E0E0),12,0,0));
                bgChips[i].setTextColor(active?Color.WHITE:(enabled?NAVY:0xffB0B0B0));
            }
        };
        java.util.function.Consumer<String> switchBg=key->{
            boolean hasSat=satelliteFile().exists();
            if(AppDatabase.MAP_SATELLITE.equals(key)&&!hasSat){toast("استورد صورة القمر الصناعي أولاً (خيارات متقدمة ← ① استيراد)");return;}
            mapKey[0]=key;
            Bitmap bg;String title;
            if(AppDatabase.MAP_PLAN.equals(key)){bg=BitmapFactory.decodeResource(getResources(),computeResource("yarmouk_camp_plan","drawable"));title="المخطط التنظيمي";}
            else if(AppDatabase.MAP_SATELLITE.equals(key)){bg=BitmapFactory.decodeFile(satelliteFile().getAbsolutePath());title="القمر الصناعي";}
            else{bg=BitmapFactory.decodeResource(getResources(),R.drawable.yarmouk_camp_calibrated);title="الخريطة المعايرة";}
            if(bg==null){toast("تعذر تحميل الخلفية");mapKey[0]=AppDatabase.MAP_CALIBRATED;return;}
            currentMapKey=mapKey[0];
            mapView.setMapBitmap(bg);mapView.fitToScreen();
            cbSectors.setChecked(AppDatabase.MAP_CALIBRATED.equals(mapKey[0]));
            ovRow.setVisibility(AppDatabase.MAP_SATELLITE.equals(mapKey[0])?View.VISIBLE:View.GONE);
            if(!AppDatabase.MAP_SATELLITE.equals(mapKey[0])){cbOverlay.setChecked(false);mapView.setOverlay(null,0);}
            restyleChipsHolder[0].run();
            refreshHolder[0].run();
            toast("الخلفية الآن: "+title+" — لكل خلفية معايرتها الخاصة");
        };
        chipCalibrated.setOnClickListener(v->switchBg.accept(AppDatabase.MAP_CALIBRATED));
        chipPlan.setOnClickListener(v->switchBg.accept(AppDatabase.MAP_PLAN));
        chipSatellite.setOnClickListener(v->switchBg.accept(AppDatabase.MAP_SATELLITE));
        restyleChipsHolder[0].run();
        cbOverlay.setOnCheckedChangeListener((btn,checked)->mapView.setOverlay(checked?BitmapFactory.decodeResource(getResources(),computeResource("yarmouk_camp_plan","drawable")):null,ovAlpha.getProgress()));
        ovAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean u){mapView.setOverlayAlpha(p);}
            @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){}});
        CheckBox cbLabels=new CheckBox(this);cbLabels.setText("أسماء القطاعات");cbLabels.setChecked(true);cbLabels.setTextSize(11);cbLabels.setOnCheckedChangeListener((btn,checked)->mapView.setLabelsVisible(checked));LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(0,-2,1);clp.setMargins(dp(6),0,0,0);mapCtl.addView(cbLabels,clp);

        page.addView(dashScroll,dsp); // dashboard below the map (kept out of the way of the top)
        // MAP V3: «المستفيدون المعروضون» — one tappable chip per point currently on the map. Tap → focus the map on
        // that home + highlight it + open its card. Rebuilt in refresh so it always matches the active filters.
        // FINAL FIELD UI (#7): when the list is long, chips alone are not enough — an explicit button opens the
        // same names as a proper scrollable vertical list (shared with the sector list from #5/#6).
        final LinearLayout presentTitleRow=new LinearLayout(this);presentTitleRow.setGravity(Gravity.CENTER_VERTICAL);page.addView(presentTitleRow,new LinearLayout.LayoutParams(-1,-2));
        final TextView presentTitle=text("المستفيدون المعروضون",12,NAVY,true);presentTitle.setPadding(dp(2),dp(6),0,dp(1));presentTitleRow.addView(presentTitle,new LinearLayout.LayoutParams(0,-2,1));
        final Button presentListBtn=smallButton("📋 عرض كقائمة كاملة",0xff0E7490);presentListBtn.setTextSize(11);LinearLayout.LayoutParams plbp=new LinearLayout.LayoutParams(-2,dp(34));presentTitleRow.addView(presentListBtn,plbp);
        final android.widget.HorizontalScrollView presentScroll=new android.widget.HorizontalScrollView(this);presentScroll.setHorizontalScrollBarEnabled(false);final LinearLayout presentList=new LinearLayout(this);presentList.setOrientation(LinearLayout.HORIZONTAL);presentScroll.addView(presentList);LinearLayout.LayoutParams prp=new LinearLayout.LayoutParams(-1,-2);prp.setMargins(0,0,0,dp(2));page.addView(presentScroll,prp);
        TextView legend=text("باعد إصبعين للتكبير • اسحب للتحريك  |  📍 GPS حقيقي (قرص ممتلئ)   ◌ تقديري (حلقة مجوّفة)",10,MUTED,false);legend.setPadding(0,dp(4),0,0);page.addView(legend);

        AlertDialog dialog=new AlertDialog.Builder(this).setView(page).setPositiveButton("إغلاق",null).create();
        // FINAL FIELD UI (#5/#6/#7): always holds exactly what's plotted on the map right now, so the sector list
        // and the full "عرض كقائمة كاملة" list are never stale relative to the active filters.
        @SuppressWarnings("unchecked") final List<OperationalMapView.MapPoint>[] latestPoints=new List[]{new ArrayList<>()};

        Runnable refresh=()->{
            List<AppDatabase.CalibrationPointRow> calibRows=db.listCalibrationPoints(mapKey[0]);
            List<MapCalibration.Point> calibPts=new ArrayList<>();for(AppDatabase.CalibrationPointRow row:calibRows)calibPts.add(new MapCalibration.Point(row.label,row.lat,row.lng,row.fracX,row.fracY));
            MapCalibration.FitResult fit=MapCalibration.fit(calibPts);
            String bgName=AppDatabase.MAP_PLAN.equals(mapKey[0])?"المخطط التنظيمي":AppDatabase.MAP_SATELLITE.equals(mapKey[0])?"القمر الصناعي":"الخريطة المعايرة";
            int calibColor;String calibMsg;
            if(calibRows.size()<3){calibMsg="المعايرة غير مكتملة ("+calibRows.size()+"/3 نقاط على الأقل) — تُعرض تقديرات مستوى القطاع فقط حتى اكتمالها";calibColor=0xffC9851C;}
            else if(!fit.reliable){calibMsg=fit.message;calibColor=0xffBD4868;}
            else{calibMsg=fit.message+" ("+calibRows.size()+" نقطة)";calibColor=0xff149176;}
            calibStatus.setText("الخلفية الحالية: "+bgName+"  •  "+calibMsg);
            calibStatus.setTextColor(Color.WHITE);
            calibStatus.setBackground(round(calibColor,10,0,0));
            restyleChipsHolder[0].run();
            // Steps ①②③ track the SATELLITE setup specifically, independent of whichever background is active
            // right now, so the engineer can check progress on it even while looking at a different background.
            boolean satImported=satelliteFile().exists();
            step1Status.setText(satImported?"✓ تم استيراد الصورة":"لم تُستورد صورة بعد");step1Status.setTextColor(satImported?0xff149176:MUTED);
            List<AppDatabase.CalibrationPointRow> satCalibRows=db.listCalibrationPoints(AppDatabase.MAP_SATELLITE);
            List<MapCalibration.Point> satCalibPts=new ArrayList<>();for(AppDatabase.CalibrationPointRow row:satCalibRows)satCalibPts.add(new MapCalibration.Point(row.label,row.lat,row.lng,row.fracX,row.fracY));
            MapCalibration.FitResult satFit=MapCalibration.fit(satCalibPts);
            boolean satCalibrated=satFit.fitted&&satFit.reliable;
            step2Status.setText(!satImported?"بانتظار الخطوة ① أولاً":satCalibrated?"✓ معايرة موثوقة ("+satCalibRows.size()+" نقطة)":"لسا ناقصة ("+satCalibRows.size()+"/3 نقاط على الأقل)");
            step2Status.setTextColor(!satImported?0xffB0B0B0:satCalibrated?0xff149176:0xffC9851C);
            manageCalib.setEnabled(satImported);
            step3Status.setText((satCalibrated?"✓ ":"")+"③ التفعيل — اختر «🛰 القمر الصناعي» من الأزرار الثلاثة أعلى الخريطة");
            step3Status.setTextColor(satCalibrated?0xff149176:MUTED);

            String statusValue=MAP_FILTER_VALUES[statusFilter.getSelectedItemPosition()];
            String batchValue=batchFilter.getSelectedItemPosition()>0?(String)batchFilter.getSelectedItem():"all";
            String sectorValue=sectorFilter.getSelectedItemPosition()>0?(String)sectorFilter.getSelectedItem():"all";
            String gpsValue=gpsFilter.getSelectedItemPosition()==1?"no_gps":gpsFilter.getSelectedItemPosition()==2?"real_gps":"all";
            List<AppDatabase.Beneficiary> rows=db.listStage(currentProjectId,"","followup","all",batchValue);
            List<OperationalMapView.MapPoint> mapPoints=new ArrayList<>();
            boolean calibReady=fit.fitted&&fit.reliable;
            // Real, OpenStreetMap-sourced street names/lines — only meaningful once THIS background has a
            // reliable calibration fit; on an uncalibrated background there is nothing correct to convert to,
            // so the layer is simply empty rather than guessed.
            List<OperationalMapView.StreetLine> streetLines=new ArrayList<>();
            if(calibReady){
                try{
                    for(VerifiedStreets.Street st:VerifiedStreets.load(this)){
                        List<float[]> fracXY=new ArrayList<>();
                        for(List<double[]> seg:st.segments){
                            for(double[] latLng:seg){double[] f=fit.calibration.geoToFraction(latLng[0],latLng[1]);fracXY.add(new float[]{(float)f[0],(float)f[1]});}
                            fracXY.add(new float[]{Float.NaN,Float.NaN}); // marks a break before the next segment
                        }
                        streetLines.add(new OperationalMapView.StreetLine(st.name,fracXY));
                    }
                }catch(Exception ignored){}
            }
            mapView.setStreets(streetLines);
            java.util.HashMap<String,Integer> estPerSector=new java.util.HashMap<>(); // MAP V3: spread stacked estimates
            for(AppDatabase.Beneficiary b:rows){
                String bucket=mapStatusBucket(b);
                if(!"all".equals(statusValue)&&!statusValue.equals(bucket))continue;
                if(!"all".equals(sectorValue)&&!sectorValue.equals(b.sector))continue;
                boolean hasGpsB=b.gpsCapturedAt>0;
                // MAP V3 location filter — بدون GPS فقط (تقديري) / GPS الحقيقي فقط.
                if("no_gps".equals(gpsValue)&&hasGpsB)continue;
                if("real_gps".equals(gpsValue)&&!hasGpsB)continue;
                int color=mapBucketColor(bucket);
                double[] frac=null;
                if(calibReady&&hasGpsB){
                    double[] candidate=fit.calibration.geoToFraction(b.gpsLat,b.gpsLng);
                    if(candidate[0]>=0&&candidate[0]<=1&&candidate[1]>=0&&candidate[1]<=1)frac=candidate;
                }
                if(frac!=null){
                    mapPoints.add(new OperationalMapView.MapPoint(b.id,b.name,bucket,b.address,b.sector,(float)frac[0],(float)frac[1],false,color));
                }else{
                    SectorBoundaries.Sector sec=findSector(sectorList,b.sector);
                    if(sec==null)continue; // unclassified sector — no invented position at all
                    float[] c=polygonCentroid(sec);
                    // MAP V3 declutter: estimated points share one sector centroid, so fan them out on a small ring
                    // (golden-angle spiral) so they never stack into one confusing blob — still clearly «تقديري».
                    int k=estPerSector.containsKey(b.sector)?estPerSector.get(b.sector):0;estPerSector.put(b.sector,k+1);
                    if(k>0){double ang=k*2.399963;float rr=(float)(0.013*Math.sqrt(k));c[0]+=(float)(rr*Math.cos(ang));c[1]+=(float)(rr*Math.sin(ang));}
                    mapPoints.add(new OperationalMapView.MapPoint(b.id,b.name,bucket,b.address,b.sector,c[0],c[1],true,color));
                }
            }
            mapView.setPoints(mapPoints);
            latestPoints[0]=mapPoints;
            mapView.setLayerVisibility(cbSectors.isChecked(),cbPoints.isChecked(),cbHeatmap.isChecked());
            // MAP V3 indicators: displayed • real GPS • estimate/no-GPS — with icons, not colour alone.
            int plotted=mapPoints.size(),realPlot=0;for(OperationalMapView.MapPoint mp2:mapPoints)if(!mp2.estimated)realPlot++;
            mapCount.setText("🏠 المعروضون: "+plotted+"   •   📍 GPS حقيقي: "+realPlot+"   •   ◌ تقديري/بدون GPS: "+(plotted-realPlot));
            // MAP V3: fill the «المستفيدون المعروضون» chip strip from exactly what's on the map now.
            presentList.removeAllViews();
            for(OperationalMapView.MapPoint mp:mapPoints){
                final OperationalMapView.MapPoint fmp=mp;
                LinearLayout chip=new LinearLayout(this);chip.setOrientation(LinearLayout.HORIZONTAL);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setBackground(round(SURFACE_CARD,99,mp.estimated?0xffE3C58A:0xff149176,mp.estimated?1:2));chip.setPadding(dp(11),dp(6),dp(11),dp(6));
                chip.addView(text(mp.estimated?"◌":"📍",12,mp.estimated?0xffC9851C:0xff149176,true));
                TextView nm=text(mp.name,12,NAVY,true);nm.setSingleLine(true);nm.setPadding(dp(5),0,0,0);chip.addView(nm);
                chip.setOnClickListener(v->{mapView.setHighlight(fmp.beneficiaryId);mapView.focusPoint(fmp.fracX,fmp.fracY);showPointSummary(fmp,dialog);});
                LinearLayout.LayoutParams chp=new LinearLayout.LayoutParams(-2,-2);chp.setMargins(0,dp(2),dp(6),dp(2));presentList.addView(chip,chp);
            }
            if(mapPoints.isEmpty()){TextView none=text("لا يوجد مستفيدون ضمن هذه الفلاتر",11,MUTED,false);none.setPadding(dp(4),dp(4),dp(4),dp(4));presentList.addView(none);}

            // ---- Operational Map Pro: per-sector distribution of the CURRENT list (batch) ----
            java.util.LinkedHashMap<String,int[]> stat=new java.util.LinkedHashMap<>(); // sector → [total, realGps, estimate]
            for(SectorBoundaries.Sector s:sectorList)stat.put(s.name,new int[3]);
            int unclassified=0,realTotal=0,estTotal=0;
            for(AppDatabase.Beneficiary b:rows){
                boolean hasGps=b.gpsCapturedAt>0;
                int[] st=b.sector==null?null:stat.get(b.sector);
                if(st==null){unclassified++;continue;}
                st[0]++;if(hasGps){st[1]++;realTotal++;}else{st[2]++;estTotal++;}
            }
            String topSector="—";int topCount=-1;for(java.util.Map.Entry<String,int[]> e:stat.entrySet())if(e.getValue()[0]>topCount){topCount=e.getValue()[0];topSector=e.getKey();}
            summaryBar.setText("القائمة: "+("all".equals(batchValue)?"كل القوائم":batchValue)+"  •  المعروضون: "+rows.size()+"  •  GPS حقيقي: "+realTotal+"  •  تقدير: "+estTotal+"  •  غير مصنف: "+unclassified+"  •  أكثر قطاع: "+topSector+(topCount>0?" ("+topCount+")":""));
            dash.removeAllViews();
            for(java.util.Map.Entry<String,int[]> e:stat.entrySet()){
                final String secName=e.getKey();int[] st=e.getValue();
                LinearLayout cardv=column(dp(3));cardv.setBackground(round(SURFACE_CARD,10,secName.equals(sectorValue)?0xff149176:CARD_BORDER,secName.equals(sectorValue)?2:1));cardv.setPadding(dp(11),dp(9),dp(11),dp(9));
                cardv.addView(text(secName,14,NAVY,true));
                cardv.addView(text("المستفيدون: "+st[0],13,0xff0E7490,true));
                cardv.addView(text("GPS "+st[1]+"  •  تقدير "+st[2],11,MUTED,false));
                final int idx=sectorNames.indexOf(secName);
                // FINAL FIELD UI (#5/#6): selecting a sector card zooms the map to that sector (existing
                // focusSector), filters the map points to it (sectorFilter → refresh), AND now also opens
                // «مستفيدو القطاع» — the actual names, not just the map — so the engineer knows who the N
                // beneficiaries are immediately, not only that they exist.
                cardv.setOnClickListener(v->{
                    if(idx>=0)sectorFilter.setSelection(idx); // focus/zoom + dim other sectors via the filter
                    SectorBoundaries.Sector sec=findSector(sectorList,secName);
                    if(sec!=null)mapView.focusSector(sec);
                    // §6 fix: list built from the stored sector name (authoritative), not from latestPoints[0].
                    showSectorBeneficiaryList(secName,latestPoints[0],mapView,dialog);
                });
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(155),-2);cp.setMargins(0,dp(2),dp(8),dp(2));dash.addView(cardv,cp);
            }
            if(unclassified>0){LinearLayout uc=column(dp(3));uc.setBackground(round(0xffFFF6E9,10,0xffE3C58A,1));uc.setPadding(dp(11),dp(9),dp(11),dp(9));uc.addView(text("غير مصنف",14,0xffC9851C,true));uc.addView(text("المستفيدون: "+unclassified,13,0xffC9851C,true));uc.addView(text("بلا قطاع — راجِع العناوين",11,MUTED,false));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(155),-2);cp.setMargins(0,dp(2),dp(8),dp(2));dash.addView(uc,cp);}
        };
        refreshHolder[0]=refresh;

        manageCalib.setOnClickListener(v->showMapCalibrationManager(refresh));
        cbSectors.setOnCheckedChangeListener((btn,checked)->refresh.run());
        cbPoints.setOnCheckedChangeListener((btn,checked)->refresh.run());
        cbHeatmap.setOnCheckedChangeListener((btn,checked)->refresh.run());
        cbStreets.setOnCheckedChangeListener((btn,checked)->mapView.setStreetsVisible(checked));
        statusFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        batchFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        sectorFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        gpsFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        // ⛶ — collapse everything except the map, so the plan fills the screen for reading street names.
        final boolean[] maximized={false};
        bigMapBtn.setOnClickListener(v->{
            maximized[0]=!maximized[0];
            int vis=maximized[0]?View.GONE:View.VISIBLE;
            summaryBar.setVisibility(vis);mainFilters.setVisibility(vis);locRow.setVisibility(vis);toggleCtl.setVisibility(vis);dashScroll.setVisibility(vis);presentTitle.setVisibility(vis);presentScroll.setVisibility(vis);legend.setVisibility(vis);
            if(maximized[0])controlsBox.setVisibility(View.GONE);
            bigMapBtn.setText(maximized[0]?"⛶ إظهار الإحصائيات":"⛶ تكبير الخريطة");
        });
        // FINAL FIELD UI (#5/#6): tapping a sector polygon directly on the map now does the same focus+filter+list
        // as tapping its dashboard card, instead of only a counts-only summary.
        // §6: focus + open the authoritative sector list for a chosen sector name (never by tap position).
        final java.util.function.Consumer<String> openMapSector=secName->{
            int idx=sectorNames.indexOf(secName);
            if(idx>=0)sectorFilter.setSelection(idx);
            SectorBoundaries.Sector sec=findSector(sectorList,secName);
            if(sec!=null)mapView.focusSector(sec);
            showSectorBeneficiaryList(secName,latestPoints[0],mapView,dialog);
        };
        mapView.setOnSectorTapListener(hits->{
            if(hits==null||hits.isEmpty())return;
            if(hits.size()==1){openMapSector.accept(hits.get(0));return;}
            // §6 (overlap): more than one sector under the tap — ask, never guess.
            final String[] names=hits.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("اختر القطاع").setItems(names,(d,w)->openMapSector.accept(names[w])).setNegativeButton("إلغاء",null).show();
        });
        mapView.setOnPointTapListener(point->showPointSummary(point,dialog));
        presentListBtn.setOnClickListener(v->showMapPointList("المستفيدون المعروضون",latestPoints[0],mapView,dialog));
        allCampBtn.setOnClickListener(v->{sectorFilter.setSelection(0);mapView.fitToScreen();});

        // FINAL FIELD UI (#4): the default AlertDialog "إغلاق" button is small and easy to miss on a field
        // device — add a large, unmistakable ✕ button pinned at the top of the map page, with a generous touch
        // target, that always closes from the first tap.
        Button closeX=new Button(this);closeX.setText("✕");closeX.setTextSize(20);closeX.setAllCaps(false);closeX.setTypeface(null,Typeface.BOLD);closeX.setTextColor(Color.WHITE);closeX.setBackground(round(0xffBD4868,99,0,0));closeX.setOnClickListener(v->dialog.dismiss());
        LinearLayout.LayoutParams closeP=new LinearLayout.LayoutParams(dp(44),dp(44));closeP.setMargins(dp(8),0,0,0);titleRow2.addView(closeX,0,closeP);

        dialog.setOnShowListener(v->{
            themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.98),(int)(getResources().getDisplayMetrics().heightPixels*.97));
            // Also enlarge the native "إغلاق" button's touch target as a second, always-available way to close.
            Button nativeClose=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if(nativeClose!=null){nativeClose.setMinHeight(dp(52));nativeClose.setTextSize(15);nativeClose.setPadding(dp(20),dp(10),dp(20),dp(10));}
            refresh.run();
        });
        dialog.show();
    }

    /** FINAL FIELD USABILITY FIX §5: a short, field-focused map guide — a few one-liners, not a long page. */
    private void showMapGuide(){
        ScrollView sv=new ScrollView(this);LinearLayout box=column(dp(8));box.setPadding(dp(18),dp(14),dp(18),dp(12));sv.addView(box);
        box.addView(text("دليل الخريطة — باختصار",18,NAVY,true));
        String[] tips={
                "اختر «القائمة» و«القطاع» من الأعلى لتصفية المستفيدين المعروضين.",
                "اضغط بطاقة قطاع (أسفل الخريطة) لعرض مستفيدي هذا القطاع فقط، مع تكبير الخريطة عليه.",
                "داخل قائمة القطاع: «تحليل عناوين القطاع» يعرض الشوارع والحارات ومستفيديها.",
                "«🗺 عرض كل المخيم» يعيد كل القطاعات والمستفيدين.",
                "«⤢ ملاءمة الشاشة» يضبط الخريطة لتظهر كاملة.",
                "«🏘 تحليل العناوين» يفتح توزيع الشوارع والحارات (للقطاع المحدد أو الكل).",
                "اضغط اسم مستفيد لفتح ملفه أو التركيز على موقعه.",
                "إذا كان الموقع «تقديري»، حدّث موقع المنزل بالـ GPS أثناء الزيارة ليظهر على مكانه الحقيقي."};
        for(String t:tips){TextView tv=text("•  "+t,13,TEXT,false);tv.setPadding(0,dp(4),0,dp(4));box.addView(tv);}

        TextView satTitle=text("إضافة خلفية قمر صناعي حقيقية (بلا إنترنت)",15,NAVY,true);satTitle.setPadding(0,dp(14),0,dp(4));box.addView(satTitle);
        String[] satSteps={
                "التطبيق نفسه لا يتصل بالإنترنت أبداً — يحتاج شخصاً معه اتصال إنترنت (بالمكتب مثلاً) يجهّز الصورة مسبقاً.",
                "افتح Google Earth أو أي خدمة خرائط أخرى على حاسوب، وابحث عن مخيم اليرموك، دمشق.",
                "صدّر أو التقط صورة عالية الدقة تغطي المخيم بالكامل (بمساحة أوسع منه بقليل).",
                "انقل الصورة إلى الموبايل (كابل أو فلاشة)، ثم استوردها من «⚙ خيارات متقدمة ← 🛰 استيراد صورة القمر الصناعي».",
                "عايرها من «إدارة نقاط المعايرة» بإدخال 2-3 إحداثيات GPS حقيقية لنقاط معروفة على الصورة نفسها.",
                "بعد المعايرة، تظهر أسماء الشوارع الحقيقية (من OpenStreetMap) فوق الصورة تلقائياً كلما كبّرت — هذه بيانات مفتوحة لا تحتاج إنترنت بعد تحميل التطبيق."};
        for(String t:satSteps){TextView tv=text("•  "+t,13,TEXT,false);tv.setPadding(0,dp(4),0,dp(4));box.addView(tv);}
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("فهمت",null).show();
    }

    private void showSectorSummary(String sectorName){
        List<AppDatabase.Beneficiary> rows=db.listStage(currentProjectId,"","followup","all");
        int total=0,awaiting=0,inProgress=0,late=0,ready=0,done=0;
        for(AppDatabase.Beneficiary b:rows){if(!sectorName.equals(b.sector))continue;total++;
            switch(mapStatusBucket(b)){case "awaiting":awaiting++;break;case "in_progress":inProgress++;break;case "late":late++;break;case "ready_for_pickup":ready++;break;default:done++;}}
        String message="إجمالي المستفيدين في هذا القطاع: "+total+"\n\nبانتظار الزيارة: "+awaiting+"\nقيد التنفيذ: "+inProgress+"\nمتأخر: "+late+"\nجاهز للاستلام: "+ready+"\nمكتمل ومُسلَّم: "+done;
        new AlertDialog.Builder(this).setTitle(sectorName).setMessage(message).setPositiveButton("إغلاق",null).show();
    }

    /** FINAL FIELD UI (#5/#6/#7): a proper vertical, scrollable list of beneficiaries — used both for «مستفيدو
     * القطاع» (scoped to one sector's currently-plotted points) and for the full «المستفيدون المعروضون» list
     * (#7, everything on the map right now). Each row shows the name, status, real/estimated GPS marker and a
     * short address; tapping a row focuses the map on that point, highlights it and opens the beneficiary card —
     * the same behaviour the small chips already had, just legible at any list length. */
    private void showMapPointList(String title,List<OperationalMapView.MapPoint> points,OperationalMapView mapView,AlertDialog mapDialog){
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(12));scroll.addView(box);
        box.addView(text(title+"  ("+points.size()+")",18,NAVY,true));
        AlertDialog d=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        if(points.isEmpty()){TextView none=text("لا يوجد مستفيدون ضمن هذه الفلاتر",13,MUTED,false);none.setPadding(0,dp(10),0,0);box.addView(none);}
        for(OperationalMapView.MapPoint mp:points){
            final OperationalMapView.MapPoint fmp=mp;
            LinearLayout row=column(dp(4));row.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));row.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(4),0,dp(4));row.setLayoutParams(rp);
            LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(mp.estimated?"◌":"📍",14,mp.estimated?0xffC9851C:0xff149176,true));
            TextView nm=text(safe(mp.name,"مستفيد"),15,NAVY,true);nm.setPadding(dp(6),0,0,0);head.addView(nm,new LinearLayout.LayoutParams(0,-2,1));
            head.addView(text(mapBucketLabel(mp.status),11,mp.color,true));
            row.addView(head);
            row.addView(text(safe(mp.address,"—"),12,MUTED,false));
            row.setOnClickListener(v->{mapView.setHighlight(fmp.beneficiaryId);mapView.focusPoint(fmp.fracX,fmp.fracY);d.dismiss();showPointSummary(fmp,mapDialog);});
            box.addView(row);
        }
        d.setOnShowListener(v->themeAndSize(d.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.88)));
        d.show();
    }

    /** FINAL FIELD USABILITY FIX §6/§9: «مستفيدو القطاع» built AUTHORITATIVELY from the stored b.sector (NOT from
     * the map's currently-plotted points, which depend on filters/tap position and caused the wrong sector's list
     * to open). So tapping «العروبة والتقدم» always shows العروبة والتقدم's beneficiaries — never شرق اليرموك's.
     * Also offers «تحليل عناوين القطاع» (§8) scoped to this exact sector. Tapping a name focuses its plotted point
     * (if any) + opens its card; otherwise opens the file directly. */
    private void showSectorBeneficiaryList(String sectorName,List<OperationalMapView.MapPoint> plotted,OperationalMapView mapView,AlertDialog mapDialog){
        List<AppDatabase.Beneficiary> all=db.listStage(currentProjectId,"","followup","all");
        List<AppDatabase.Beneficiary> inSector=new ArrayList<>();for(AppDatabase.Beneficiary b:all)if(sectorName.equals(b.sector))inSector.add(b);
        java.util.HashMap<Long,OperationalMapView.MapPoint> byId=new java.util.HashMap<>();if(plotted!=null)for(OperationalMapView.MapPoint mp:plotted)byId.put(mp.beneficiaryId,mp);
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(12));scroll.addView(box);
        box.addView(text("مستفيدو القطاع — "+sectorName+"  ("+inSector.size()+")",18,NAVY,true));
        AlertDialog d=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        Button analyze=smallButton("🏘 تحليل عناوين القطاع (الشوارع والحارات)",0xff7A5CB8);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.setMargins(0,dp(6),0,dp(4));analyze.setOnClickListener(v->{d.dismiss();showAddressAnalysis(sectorName);});box.addView(analyze,ap);
        if(inSector.isEmpty()){TextView none=text("لا يوجد مستفيدون مصنّفون على هذا القطاع",13,MUTED,false);none.setPadding(0,dp(10),0,0);box.addView(none);}
        for(AppDatabase.Beneficiary b:inSector){
            final AppDatabase.Beneficiary fb=b;final OperationalMapView.MapPoint mp=byId.get(b.id);final boolean est=mp==null||mp.estimated;
            LinearLayout row=column(dp(4));row.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));row.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(4),0,dp(4));row.setLayoutParams(rp);
            LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(est?"◌":"📍",14,est?0xffC9851C:0xff149176,true));
            head.addView(text(safe(b.name,"مستفيد"),15,NAVY,true),new LinearLayout.LayoutParams(0,-2,1));
            head.addView(text(est?"تقديري":"GPS حقيقي",10,est?0xffC9851C:0xff149176,true));
            row.addView(head);
            row.addView(text(safe(b.street==null||b.street.isEmpty()?b.address:b.street,"—"),12,MUTED,false));
            row.setOnClickListener(v->{d.dismiss();if(mp!=null){mapView.setHighlight(fb.id);mapView.focusPoint(mp.fracX,mp.fracY);showPointSummary(mp,mapDialog);}else showBeneficiary(fb);});
            box.addView(row);
        }
        d.setOnShowListener(v->themeAndSize(d.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.88)));
        d.show();
    }

    /** M2 (Map V2): a richer point-tap sheet — clear real-GPS vs estimate, «📂 فتح الملف», «📍 تحديث الموقع»
     * (capture the real GPS during the visit — reuses the safe confirm-first {@link #requestHomeLocation} flow),
     * and «🧭 الذهاب إلى الموقع» (navigation). Custom-view so it can carry more than three actions. */
    private void showPointSummary(OperationalMapView.MapPoint p,AlertDialog mapDialog){
        final AppDatabase.Beneficiary b=db.beneficiary(p.beneficiaryId);
        final boolean hasGps=b!=null&&b.gpsCapturedAt>0;
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(16));scroll.addView(box);
        box.addView(text(safe(p.name,"مستفيد"),18,NAVY,true));
        if(b!=null&&b.registration!=null&&!b.registration.trim().isEmpty())box.addView(info("رقم التسجيل",formatRegistration(b.registration)));
        String batch=b!=null&&b.followupBatch!=null&&!b.followupBatch.trim().isEmpty()?b.followupBatch:"—";
        box.addView(info("القائمة",batch));
        box.addView(info("القطاع",safe(p.sector,"—")));
        box.addView(info("الحالة",mapBucketLabel(p.status)));
        box.addView(info("العنوان",safe(p.address,"—")));
        // Criterion 2/4: make the location TYPE unmistakable — real GPS (green + 📍) vs sector-centre estimate
        // (orange + «تقديري»). Never let the two be confused.
        LinearLayout locBox=column(dp(10));
        if(hasGps){
            locBox.setBackground(round(0xffE6F5F0,12,0xff149176,1));
            locBox.addView(text("📍 نوع الموقع: GPS حقيقي مثبَّت",14,0xff149176,true));
            locBox.addView(text("الدقة: "+QuantityCatalog.quantityNumber(b.gpsAccuracyM)+" م • المصدر: "+gpsSourceLabel(b.gpsSource),12,0xff0E7490,false));
            locBox.addView(text("آخر تحديث للموقع: "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(b.gpsCapturedAt)),12,MUTED,false));
        } else {
            locBox.setBackground(round(0xffFFF6E9,12,0xffE3C58A,1));
            locBox.addView(text("◌ نوع الموقع: تقديري (مركز القطاع)",14,0xffC9851C,true));
            locBox.addView(text("لا يوجد GPS حقيقي مثبَّت بعد — حدّث موقع المنزل أثناء الزيارة ليظهر على موقعه الحقيقي.",12,MUTED,false));
        }
        LinearLayout.LayoutParams lbp=new LinearLayout.LayoutParams(-1,-2);lbp.setMargins(0,dp(10),0,dp(2));box.addView(locBox,lbp);

        AlertDialog d=new AlertDialog.Builder(this).setView(scroll).create();
        Button openF=smallButton("📂 فتح ملف المستفيد",0xff0E7490);openF.setOnClickListener(v->{d.dismiss();AppDatabase.Beneficiary fresh=db.beneficiary(p.beneficiaryId);if(fresh!=null)showBeneficiary(fresh);});LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(48));op.setMargins(0,dp(12),0,0);box.addView(openF,op);
        Button updateLoc=smallButton("📍 تحديث موقع المنزل الحالي (GPS)",0xff149176);updateLoc.setOnClickListener(v->{d.dismiss();AppDatabase.Beneficiary fresh=db.beneficiary(p.beneficiaryId);if(fresh!=null)requestHomeLocation(fresh);else toast("تعذّر جلب المستفيد");});LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,dp(48));up.setMargins(0,dp(8),0,0);box.addView(updateLoc,up);
        Button go=smallButton("🧭 الذهاب إلى الموقع",hasGps?0xff0E7490:0xff9AA7B0);go.setOnClickListener(v->{
            if(hasGps){d.dismiss();openNavigation(b);}
            else new AlertDialog.Builder(this).setTitle("لا يوجد موقع GPS").setMessage("لا يوجد موقع GPS حقيقي بعد. حدّث موقع المنزل أثناء الزيارة.").setPositiveButton("حسناً",null).show();
        });LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(48));gp.setMargins(0,dp(8),0,0);box.addView(go,gp);
        Button close=smallButton("إغلاق",0xff6B7280);close.setOnClickListener(v->d.dismiss());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(44));cp.setMargins(0,dp(8),0,0);box.addView(close,cp);
        d.setOnShowListener(v->themeWin(d.getWindow()));
        d.show();
    }

    // ---- Map calibration point management (add/list/delete) ----

    private void showMapCalibrationManager(Runnable onChanged){
        List<AppDatabase.CalibrationPointRow> rows=db.listCalibrationPoints(currentMapKey);
        List<MapCalibration.Point> pts=new ArrayList<>();for(AppDatabase.CalibrationPointRow row:rows)pts.add(new MapCalibration.Point(row.label,row.lat,row.lng,row.fracX,row.fracY));
        MapCalibration.FitResult fit=MapCalibration.fit(pts);

        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text("نقاط معايرة المخطط",20,NAVY,true));
        TextView intro=text("تحتاج 3 نقاط مرجعية حقيقية موزعة جغرافياً على الأقل لتفعيل عرض مواقع GPS الحقيقية على الخريطة. كل نقطة تحتاج: موضعاً دقيقاً على صورة المخطط (مثال: تقاطع شارعين واضح، بوابة معروفة)، وإحداثيات GPS حقيقية مقاسة فعلياً في ذلك الموضع بالضبط.",12,MUTED,false);
        intro.setPadding(0,dp(5),0,dp(8));box.addView(intro);
        TextView statusView=text(rows.size()<3?"النقاط الحالية: "+rows.size()+" من 3 على الأقل":fit.message,13,rows.size()<3?0xffC9851C:(fit.reliable?0xff149176:0xffBD4868),true);
        statusView.setPadding(0,dp(2),0,dp(10));box.addView(statusView);

        for(int i=0;i<rows.size();i++){
            AppDatabase.CalibrationPointRow row=rows.get(i);
            LinearLayout card=column(dp(10));card.setBackground(round(SURFACE_CARD,13,CARD_BORDER,1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(cp);
            String err=(fit.fitted&&i<fit.perPointErrorMeters.length)?"  —  خطأ المعايرة: "+Math.round(fit.perPointErrorMeters[i])+" م":"";
            card.addView(text((row.label.isEmpty()?"نقطة بلا اسم":row.label)+err,14,NAVY,true));
            card.addView(text("خط العرض: "+row.lat+"   خط الطول: "+row.lng,12,MUTED,false));
            Button delete=smallButton("حذف هذه النقطة",0xffBD4868);delete.setTextSize(12);
            delete.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("حذف نقطة معايرة").setMessage("سيُحذف أثر هذه النقطة من حساب المعايرة. متابعة؟")
                    .setPositiveButton("حذف",(d,w)->{db.deleteCalibrationPoint(row.id);showMapCalibrationManagerRefresh(onChanged);})
                    .setNegativeButton("إلغاء",null).show());
            card.addView(delete,new LinearLayout.LayoutParams(-1,dp(38)));
            box.addView(card);
        }
        Button add=smallButton("+ إضافة نقطة معايرة جديدة",0xff149176);
        box.addView(add,new LinearLayout.LayoutParams(-1,dp(48)));

        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",(d,w)->{if(onChanged!=null)onChanged.run();}).create();
        add.setOnClickListener(v->{dialog.dismiss();showAddCalibrationPointFlow(()->showMapCalibrationManagerRefresh(onChanged));});
        dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.9)));
        dialog.show();
    }
    private void showMapCalibrationManagerRefresh(Runnable onChanged){showMapCalibrationManager(onChanged);}

    // ---- Phase 7: geographic statistics (per list or all lists) ----

    /** Counts every beneficiary (study + followup stage, current project) into one of the 7 canonical sectors
     * or "غير مصنف" — optionally scoped to a single imported list (followup_batch). Tapping a count opens the
     * exact matching beneficiaries. */
    private void showGeographicStatistics(){
        List<String> batchNames=new ArrayList<>();batchNames.add("كل القوائم");batchNames.addAll(db.followupBatches(currentProjectId));

        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text("الإحصائية الجغرافية",20,NAVY,true));
        TextView intro=text(currentProject,12,MUTED,true);intro.setPadding(0,dp(4),0,dp(8));box.addView(intro);
        Spinner batchSpinner=new Spinner(this);ArrayAdapter<String> batchAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,batchNames);batchSpinner.setAdapter(batchAdapter);batchSpinner.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));box.addView(batchSpinner,new LinearLayout.LayoutParams(-1,dp(46)));
        LinearLayout resultsBox=column(dp(6));resultsBox.setPadding(0,dp(12),0,0);box.addView(resultsBox);

        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();

        Runnable refresh=()->{
            resultsBox.removeAllViews();
            String batchValue=batchSpinner.getSelectedItemPosition()>0?(String)batchSpinner.getSelectedItem():"all";
            List<AppDatabase.Beneficiary> rows=new ArrayList<>();
            rows.addAll(db.listStage(currentProjectId,"","study","all"));
            rows.addAll(db.listStage(currentProjectId,"","followup","all"));
            Map<String,Integer> counts=new LinkedHashMap<>();
            for(String s:AddressClassifier.SECTOR_ORDER)counts.put(s,0);
            counts.put("غير مصنف",0);
            int total=0;
            for(AppDatabase.Beneficiary b:rows){
                if(!"all".equals(batchValue)&&!batchValue.equals(b.followupBatch))continue;
                total++;
                String key=AddressClassifier.SECTOR_ORDER.contains(b.sector)?b.sector:"غير مصنف";
                counts.put(key,counts.get(key)+1);
            }
            TextView totalView=text("الإجمالي: "+total+" مستفيداً",15,NAVY,true);totalView.setPadding(0,0,0,dp(6));resultsBox.addView(totalView);
            for(Map.Entry<String,Integer> entry:counts.entrySet()){
                boolean unclassified="غير مصنف".equals(entry.getKey());
                LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(round(SURFACE_CARD,12,unclassified?0xffE9C77B:CARD_BORDER,1));row.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(4),0,dp(4));row.setLayoutParams(rp);
                row.addView(text(entry.getKey(),14,unclassified?0xffC9851C:TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
                row.addView(text(String.valueOf(entry.getValue()),17,entry.getValue()>0?0xff0E7490:MUTED,true));
                String sectorKey=entry.getKey();String batchForTap=batchValue;
                row.setOnClickListener(v->{if(entry.getValue()==0)return;showBeneficiariesForSector(sectorKey,batchForTap);});
                resultsBox.addView(row);
            }
        };
        batchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){refresh.run();}});
        dialog.setOnShowListener(v->{themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.9));refresh.run();});
        dialog.show();
    }

    private void showBeneficiariesForSector(String sectorKey,String batchValue){
        List<AppDatabase.Beneficiary> rows=new ArrayList<>();
        rows.addAll(db.listStage(currentProjectId,"","study","all"));
        rows.addAll(db.listStage(currentProjectId,"","followup","all"));
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text(sectorKey,20,NAVY,true));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        int shown=0;
        for(AppDatabase.Beneficiary b:rows){
            String key=AddressClassifier.SECTOR_ORDER.contains(b.sector)?b.sector:"غير مصنف";
            if(!key.equals(sectorKey))continue;
            if(!"all".equals(batchValue)&&!batchValue.equals(b.followupBatch))continue;
            shown++;
            LinearLayout card=column(dp(10));card.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(p);
            card.addView(text(b.name,14,NAVY,true));
            card.addView(text(safe(b.address,"—"),12,MUTED,false));
            card.addView(text("study".equals(b.workflowStage)?"دراسة":"متابعة"+(b.followupBatch.isEmpty()?"":" — "+b.followupBatch),11,0xff0E7490,true));
            card.setOnClickListener(v->{dialog.dismiss();showBeneficiary(b);});
            box.addView(card);
        }
        if(shown==0)box.addView(text("لا يوجد مستفيدون في هذا القطاع ضمن الفلتر الحالي",13,MUTED,false));
        dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.9)));
        dialog.show();
    }

    // ---- Phase 7: خطط جولتي — route planning from REAL GPS only ----

    private double haversineMeters(double lat1,double lng1,double lat2,double lng2){
        double R=6371000;double dLat=Math.toRadians(lat2-lat1),dLng=Math.toRadians(lng2-lng1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2);
        return R*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
    }

    /** Simple nearest-neighbor ordering over REAL GPS fixes only — never invents a coordinate for a
     * beneficiary without one (those are surfaced separately by the caller, not included here at all). */
    private List<AppDatabase.Beneficiary> nearestNeighborRoute(List<AppDatabase.Beneficiary> points){
        List<AppDatabase.Beneficiary> remaining=new ArrayList<>(points);
        List<AppDatabase.Beneficiary> ordered=new ArrayList<>();
        if(remaining.isEmpty())return ordered;
        AppDatabase.Beneficiary current=remaining.remove(0);ordered.add(current);
        while(!remaining.isEmpty()){
            AppDatabase.Beneficiary nearest=null;double nearestDist=Double.MAX_VALUE;
            for(AppDatabase.Beneficiary candidate:remaining){double d=haversineMeters(current.gpsLat,current.gpsLng,candidate.gpsLat,candidate.gpsLng);if(d<nearestDist){nearestDist=d;nearest=candidate;}}
            remaining.remove(nearest);ordered.add(nearest);current=nearest;
        }
        return ordered;
    }

    private void showRoutePlan(){
        List<AppDatabase.Beneficiary> rows=db.listStage(currentProjectId,"","followup","all");
        List<AppDatabase.Beneficiary> withGps=new ArrayList<>();List<AppDatabase.Beneficiary> withoutGps=new ArrayList<>();
        for(AppDatabase.Beneficiary b:rows){if(b.gpsCapturedAt>0)withGps.add(b);else withoutGps.add(b);}
        List<AppDatabase.Beneficiary> ordered=nearestNeighborRoute(withGps);

        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text("خطط جولتي",20,NAVY,true));
        TextView intro=text("الترتيب أدناه يعتمد فقط على مواقع GPS الحقيقية المثبَّتة فعلياً — لا إحداثيات تقديرية من مركز أي قطاع.",12,MUTED,false);intro.setPadding(0,dp(4),0,dp(10));box.addView(intro);

        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();

        if(ordered.isEmpty()){
            box.addView(text("لا يوجد أي مستفيد بموقع GPS مثبَّت بعد ضمن مرحلة المتابعة — استخدم زر «تثبيت موقع المنزل» من ملف كل مستفيد.",14,MUTED,false));
        }else{
            double totalDistance=0;
            for(int i=0;i<ordered.size();i++){
                AppDatabase.Beneficiary b=ordered.get(i);
                LinearLayout card=column(dp(10));card.setBackground(round(SURFACE_CARD,12,0xffB7DED3,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(p);
                card.addView(text((i+1)+". "+b.name,15,NAVY,true));
                card.addView(text(safe(b.address,"—"),12,MUTED,false));
                if(i<ordered.size()-1){
                    double d=haversineMeters(b.gpsLat,b.gpsLng,ordered.get(i+1).gpsLat,ordered.get(i+1).gpsLng);totalDistance+=d;
                    TextView distView=text("↓ "+Math.round(d)+" م إلى المحطة التالية",11,0xff0E7490,true);distView.setPadding(0,dp(4),0,0);card.addView(distView);
                }
                card.setOnClickListener(v->{dialog.dismiss();showBeneficiary(b);});
                box.addView(card);
            }
            TextView totalView=text("إجمالي مسافة الجولة التقريبية بخط مستقيم: "+Math.round(totalDistance)+" م",13,0xff149176,true);totalView.setPadding(0,dp(8),0,dp(4));box.addView(totalView);
        }

        if(!withoutGps.isEmpty()){
            TextView noGpsTitle=text("مستفيدون بلا موقع GPS دقيق ("+withoutGps.size()+")",16,0xffC9851C,true);noGpsTitle.setPadding(0,dp(16),0,dp(4));box.addView(noGpsTitle);
            TextView noGpsHint=text("لم يُثبَّت لهم موقع بعد — معروضون هنا حسب القطاع النصي فقط للاطلاع، وغير مُدرجين ضمن ترتيب الجولة أعلاه (لم تُخترع لهم إحداثيات).",11,MUTED,false);noGpsHint.setPadding(0,0,0,dp(6));box.addView(noGpsHint);
            for(AppDatabase.Beneficiary b:withoutGps){
                LinearLayout card=column(dp(10));card.setBackground(round(0xffFFF8ED,12,0xffE9C77B,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(p);
                card.addView(text(b.name,14,NAVY,true));
                card.addView(text((b.sector.isEmpty()?"غير مصنف":b.sector)+(b.street.isEmpty()?"":" — "+b.street),12,0xffC9851C,true));
                card.addView(text(safe(b.address,"—"),12,MUTED,false));
                card.setOnClickListener(v->{dialog.dismiss();showBeneficiary(b);});
                box.addView(card);
            }
        }

        dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.95),(int)(getResources().getDisplayMetrics().heightPixels*.9)));
        dialog.show();
    }

    /** Step 1: tap the exact spot on the map raster. Step 2: enter the real surveyed GPS for that exact spot
     * (typed manually, or captured live via GPS if the engineer is physically standing at the reference point
     * right now). Nothing is guessed — both the pixel position and the coordinates are explicit user input. */
    private void showAddCalibrationPointFlow(Runnable onSaved){
        Bitmap mapBitmap;List<SectorBoundaries.Sector> sectorList;
        try{mapBitmap=loadMapBackground(currentMapKey);sectorList=SectorBoundaries.load(this);}
        catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر تحميل الخريطة").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();return;}

        LinearLayout page=column(dp(10));
        page.addView(text("الخطوة 1 من 2: حدد الموضع على المخطط",17,NAVY,true));
        TextView hint=text("انقر بدقة على نقطة يمكن التعرف عليها ميدانياً بوضوح (تقاطع شارعين، بوابة رئيسية، مبنى بارز).",12,MUTED,false);hint.setPadding(0,dp(4),0,dp(6));page.addView(hint);
        TextView tapStatus=text("لم يُحدَّد أي موضع بعد",13,0xffC9851C,true);page.addView(tapStatus);
        FrameLayout mapHolder=new FrameLayout(this);page.addView(mapHolder,new LinearLayout.LayoutParams(-1,dp(420)));
        OperationalMapView pickerView=new OperationalMapView(this);pickerView.setMapBitmap(mapBitmap);pickerView.setSectors(sectorList);pickerView.setLayerVisibility(true,true,false);mapHolder.addView(pickerView,new FrameLayout.LayoutParams(-1,-1));

        float[] picked=new float[2];boolean[] hasPicked={false};
        pickerView.setOnMapTapListener((fracX,fracY)->{
            picked[0]=fracX;picked[1]=fracY;hasPicked[0]=true;
            tapStatus.setText("تم تحديد الموضع ✓");tapStatus.setTextColor(0xff149176);
            List<OperationalMapView.MapPoint> marker=new ArrayList<>();marker.add(new OperationalMapView.MapPoint(0,"الموضع المحدد","","", "",fracX,fracY,false,0xffBD4868));
            pickerView.setPoints(marker);
        });

        AlertDialog step1=new AlertDialog.Builder(this).setView(page).setPositiveButton("التالي: إدخال إحداثيات GPS",null).setNegativeButton("إلغاء",null).create();
        step1.setOnShowListener(v->{
            themeAndSize(step1.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.97),(int)(getResources().getDisplayMetrics().heightPixels*.94));
            step1.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(next->{
                if(!hasPicked[0]){toast("انقر على المخطط أولاً لتحديد الموضع");return;}
                step1.dismiss();showAddCalibrationPointStep2(picked[0],picked[1],onSaved);
            });
        });
        step1.show();
    }

    private void showAddCalibrationPointStep2(float fracX,float fracY,Runnable onSaved){
        LinearLayout form=column(dp(16));
        form.addView(text("الخطوة 2 من 2: إحداثيات GPS الحقيقية لهذا الموضع بالضبط",17,NAVY,true));
        EditText label=formInput(form,"اسم/وصف النقطة *","مثال: تقاطع شارع كذا مع كذا",InputType.TYPE_CLASS_TEXT,false);
        EditText lat=formInput(form,"خط العرض (Latitude) *","مثال: 33.4991",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED,false);
        EditText lng=formInput(form,"خط الطول (Longitude) *","مثال: 36.2853",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED,false);
        Button captureNow=smallButton("📍 التقاط GPS الآن (إن كنت واقفاً في هذا الموضع تحديداً)",0xff0E7490);form.addView(captureNow,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView captureStatus=text("",12,MUTED,false);captureStatus.setPadding(0,dp(4),0,0);form.addView(captureStatus);

        AlertDialog step2=new AlertDialog.Builder(this).setView(form).setPositiveButton("حفظ نقطة المعايرة",null).setNegativeButton("رجوع",null).create();
        captureNow.setOnClickListener(v->{
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){toast("امنح صلاحية الموقع أولاً من ملف أي مستفيد ثم أعد المحاولة");return;}
            captureStatus.setText("جارٍ تحديد الموقع (GPS فقط — أفضل قراءة خلال 30 ثانية)…");captureStatus.setTextColor(MUTED);
            // Calibration reference points always require GPS_PROVIDER specifically (never the network-assisted
            // mode) — the whole map calibration's trustworthiness depends on a real satellite fix here.
            gpsHelper.startCapture(GpsLocationHelper.MODE_GPS_OFFLINE,30000L,new GpsLocationHelper.Callback(){
                @Override public void onProgress(Location best,long elapsedMs,long windowMs){
                    if(best!=null){lat.setText(String.valueOf(best.getLatitude()));lng.setText(String.valueOf(best.getLongitude()));captureStatus.setText("أفضل قراءة حتى الآن — الدقة "+QuantityCatalog.quantityNumber(best.getAccuracy())+" م");captureStatus.setTextColor(0xff149176);}
                }
                @Override public void onFinished(Location location){lat.setText(String.valueOf(location.getLatitude()));lng.setText(String.valueOf(location.getLongitude()));captureStatus.setText("تم الالتقاط — الدقة "+QuantityCatalog.quantityNumber(location.getAccuracy())+" م");captureStatus.setTextColor(0xff149176);}
                @Override public void onUnavailable(String reason){captureStatus.setText(reason);captureStatus.setTextColor(0xffBD4868);}
            });
        });
        step2.setOnShowListener(v->step2.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save->{
            String labelValue=label.getText().toString().trim();
            double latValue,lngValue;
            try{latValue=Double.parseDouble(lat.getText().toString().trim());lngValue=Double.parseDouble(lng.getText().toString().trim());}
            catch(Exception ignored){toast("أدخل خط عرض وخط طول صحيحين");return;}
            if(labelValue.isEmpty()){toast("أدخل اسماً أو وصفاً للنقطة");return;}
            db.addCalibrationPoint(currentMapKey,labelValue,latValue,lngValue,fracX,fracY);
            step2.dismiss();toast("تم حفظ نقطة المعايرة");
            if(onSaved!=null)onSaved.run();
        }));
        step2.show();
    }

    /** Saves the editable study fields. Re-classification (resolveAddress/applyAddressResolution) is the
     * expensive, state-changing part and must NEVER run just because the save button was pressed — only when
     * the address text itself actually changed. A manually-confirmed classification (address_corrections has
     * an entry for the address text as it stood before this edit) is never silently replaced by a fresh
     * automatic guess when the text changes; the record is forced to needs_review instead so a human decides. */
    private void saveStudy(AppDatabase.Beneficiary b,EditText address,EditText damage){
        String previousAddress=b.address;
        String value=address.getText().toString().trim();
        db.updateStudy(b.id,value,damage.getText().toString(),null); // never touches original_address once set — see AppDatabase.updateStudy()
        boolean addressTextChanged=!AddressClassifier.normalize(value).equals(AddressClassifier.normalize(previousAddress));
        if(!value.isEmpty()&&addressTextChanged){
            AppDatabase.Beneficiary fresh=db.beneficiary(b.id);
            AddressResolution.Result resolved=resolveAddress(value,fresh);
            String previousNormalized=AddressClassifier.normalize(previousAddress);
            AddressResolution.ConfirmedCorrection priorConfirmation=previousNormalized.isEmpty()?null:db.addressCorrectionFor(previousNormalized);
            if(priorConfirmation!=null&&!AddressResolution.STATUS_CONFIRMED_CORRECTION.equals(resolved.status)){
                resolved.needsReview=true;resolved.status=AddressResolution.STATUS_NEEDS_REVIEW;
                resolved.reviewReason="تغيّر نص العنوان بعد تصحيح مؤكَّد سابقاً ('"+priorConfirmation.sector+(priorConfirmation.street.isEmpty()?"":" — "+priorConfirmation.street)+"') — يحتاج مراجعة يدوية قبل اعتماد تصنيف جديد.";
                resolved.addressSource="تعارض مع تصحيح مؤكَّد سابقاً — يحتاج مراجعة يدوية";
            }
            db.applyAddressResolution(b.id,resolved);
        }
    }

    /** The single place every address-setting path (manual add, import, study edit) routes through: consults a
     * previously CONFIRMED correction first (address_corrections — never guessed, only ever written by an
     * explicit human confirmation), falls back to the fuzzy AddressClassifier match, and — only when a
     * reliable map calibration AND a real GPS fix both already exist — cross-checks the resolved sector
     * against which sector polygon the GPS point actually falls inside. A conflict there always forces
     * needs-review; it is NEVER auto-corrected. Never touches originalAddress. */
    private AddressResolution.Result resolveAddress(String rawAddress,AppDatabase.Beneficiary gpsContext){
        String normalized=AddressClassifier.normalize(rawAddress);
        AddressResolution.ConfirmedCorrection correction=normalized.isEmpty()?null:db.addressCorrectionFor(normalized);
        AddressClassifier.Match fuzzy=classifier.classify(rawAddress);
        AddressResolution.GpsSectorCheck gpsCheck=null;
        if(gpsContext!=null&&gpsContext.gpsCapturedAt>0){
            try{
                List<AppDatabase.CalibrationPointRow> calibRows=db.listCalibrationPoints();
                List<MapCalibration.Point> pts=new ArrayList<>();for(AppDatabase.CalibrationPointRow row:calibRows)pts.add(new MapCalibration.Point(row.label,row.lat,row.lng,row.fracX,row.fracY));
                MapCalibration.FitResult fit=MapCalibration.fit(pts);
                if(fit.fitted&&fit.reliable){
                    List<SectorBoundaries.Sector> sectorList=SectorBoundaries.load(this);
                    double[] frac=fit.calibration.geoToFraction(gpsContext.gpsLat,gpsContext.gpsLng);
                    String gpsSector="";
                    for(SectorBoundaries.Sector s:sectorList){if(SectorBoundaries.contains(s,(float)frac[0],(float)frac[1])){gpsSector=s.name;break;}}
                    if(!gpsSector.isEmpty()){String candidateSector=correction!=null?correction.sector:fuzzy.sector;gpsCheck=new AddressResolution.GpsSectorCheck(gpsSector.equals(candidateSector),gpsSector);}
                }
                // else: calibration not ready/reliable yet (e.g. fewer than 3 field-surveyed reference points) —
                // deliberately skip the GPS check rather than compare against an unreliable transform.
            }catch(Exception ignored){}
        }
        return AddressResolution.resolve(fuzzy,correction,gpsCheck);
    }

    private boolean addressNeedsReview(AppDatabase.Beneficiary b){return b.matchStatus!=null&&b.matchStatus.contains("بحاجة لمراجعة");}

    /** Always shows the immutable original address text, plus the currently-resolved sector/street and why —
     * with a review action only when resolveAddress() actually flagged a conflict or a weak match. */
    private View addressStatusCard(AppDatabase.Beneficiary b,AlertDialog dialog){
        boolean needsReview=addressNeedsReview(b);
        LinearLayout card=column(dp(11));card.setBackground(round(needsReview?0xffFFF2F2:CARD_BG,13,needsReview?0xffBD4868:CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(8));card.setLayoutParams(p);
        card.addView(text("العنوان الأصلي (لا يُعدَّل ولا يُحذف أبداً)",11,MUTED,true));
        card.addView(text(safe(b.originalAddress,"—"),13,TEXT,false));
        TextView statusLine=text((needsReview?"⚠ ":"✓ ")+"القطاع الحالي: "+b.sector+(b.street.isEmpty()?"":" — "+b.street),12,needsReview?0xffBD4868:0xff149176,true);statusLine.setPadding(0,dp(6),0,0);card.addView(statusLine);
        if(!b.matchStatus.isEmpty()){TextView detail=text(b.matchStatus,11,MUTED,false);detail.setPadding(0,dp(3),0,0);card.addView(detail);}
        if(needsReview){
            Button review=smallButton("مراجعة العنوان وتأكيد القطاع/الشارع",0xffBD4868);review.setOnClickListener(v->showAddressReviewDialog(b,dialog));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(44));rp.setMargins(0,dp(8),0,0);card.addView(review,rp);
        }
        // المطلوب 2 — when a «العناوين» reference was matched to this beneficiary, show BOTH addresses and a
        // reviewable suggestion. Nothing is changed automatically — the engineer accepts or ignores.
        if(b.addressesFileAddress!=null&&!b.addressesFileAddress.isEmpty()){
            View div=new View(this);div.setBackgroundColor(CARD_BORDER);LinearLayout.LayoutParams dvp=new LinearLayout.LayoutParams(-1,dp(1));dvp.setMargins(0,dp(10),0,dp(8));card.addView(div,dvp);
            card.addView(text("مقارنة العناوين (من ملف العناوين)",13,NAVY,true));
            card.addView(text("عنوان التقييم: "+safe(b.address,"—"),12,TEXT,false));
            card.addView(text("عنوان ملف العناوين: "+b.addressesFileAddress+(b.addressesFileSector.isEmpty()?"":"  •  القطاع: "+b.addressesFileSector),12,0xff0E7490,true));
            final String sug=b.addressesFileSector==null?"":b.addressesFileSector.trim();
            boolean validSector=AddressClassifier.SECTOR_ORDER.contains(sug);
            boolean conflict=validSector&&!sug.equals(b.sector);
            if(validSector) card.addView(text(conflict?("الاقتراح: القطاع «"+sug+"» يختلف عن الحالي «"+b.sector+"» — راجِع واعتمد"):("الاقتراح: القطاع «"+sug+"» مطابق للحالي ✓"),12,conflict?0xffC9851C:0xff149176,true));
            else card.addView(text("لا يوجد قطاع صريح في ملف العناوين لهذا الصف — العنوان مرجعي فقط",11,MUTED,false));
            LinearLayout arow=new LinearLayout(this);arow.setGravity(Gravity.CENTER);
            if(validSector){Button accept=dialogButton("اعتماد الاقتراح",0xff149176,v->acceptAddressSuggestion(b,sug,dialog));arow.addView(accept);}
            Button reject=dialogButton("رفض/تجاهل",0xffBD4868,v->{db.setAddressReference(b.id,"","");toast("تم تجاهل اقتراح ملف العناوين");if(dialog!=null)dialog.dismiss();AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null)showBeneficiary(fresh);});arow.addView(reject);
            LinearLayout.LayoutParams arp=new LinearLayout.LayoutParams(-1,-2);arp.setMargins(0,dp(8),0,0);card.addView(arow,arp);
        }
        return card;
    }

    /** Accept the «العناوين»-file sector as an EXPLICIT, reviewable correction — writes an address_correction and
     * re-resolves (the same non-silent path as the manual review). original_address is never touched. */
    private void acceptAddressSuggestion(AppDatabase.Beneficiary b,String sector,AlertDialog dialog){
        String normalized=AddressClassifier.normalize(b.address);
        db.confirmAddressCorrection(normalized,sector,b.street,currentEngineerName());
        AddressResolution.Result confirmed=AddressResolution.resolve(classifier.classify(b.address),db.addressCorrectionFor(normalized),null);
        db.applyAddressResolution(b.id,confirmed);
        db.setAddressReference(b.id,"","");
        toast("تم اعتماد القطاع «"+sector+"» من ملف العناوين");
        if(dialog!=null)dialog.dismiss();
        AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null)showBeneficiary(fresh);
    }

    /** The only place address_corrections ever gets written — an explicit engineer confirmation of the
     * correct sector/street for this exact address text. Saved permanently for that text so future
     * imports/edits of the same wording resolve instantly without needing review again. */
    private void showAddressReviewDialog(AppDatabase.Beneficiary b,AlertDialog parentDialog){
        LinearLayout form=column(dp(16));
        form.addView(text("مراجعة عنوان: "+safe(b.name,""),18,NAVY,true));
        TextView original=text("العنوان الأصلي: "+safe(b.originalAddress,"—"),13,MUTED,false);original.setPadding(0,dp(4),0,dp(4));form.addView(original);
        if(!b.matchStatus.isEmpty()){TextView reason=text("السبب: "+b.matchStatus,12,0xffBD4868,false);reason.setPadding(0,0,0,dp(8));form.addView(reason);}
        TextView sectorLabel=text("القطاع الصحيح *",12,NAVY,true);sectorLabel.setPadding(0,dp(6),0,dp(4));form.addView(sectorLabel);
        Spinner sectorSpinner=new Spinner(this);List<String> sectorOptions=new ArrayList<>(AddressClassifier.SECTOR_ORDER);ArrayAdapter<String> sectorAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sectorOptions);sectorSpinner.setAdapter(sectorAdapter);sectorSpinner.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));int idx=sectorOptions.indexOf(b.sector);if(idx>=0)sectorSpinner.setSelection(idx);form.addView(sectorSpinner,new LinearLayout.LayoutParams(-1,dp(46)));
        EditText street=formInput(form,"الشارع (اختياري)","اسم الشارع إن كان معروفاً",InputType.TYPE_CLASS_TEXT,false);street.setText(b.street);
        new AlertDialog.Builder(this).setView(form).setPositiveButton("تأكيد وحفظ",(d,w)->{
            String chosenSector=(String)sectorSpinner.getSelectedItem();String chosenStreet=street.getText().toString().trim();
            String normalized=AddressClassifier.normalize(b.address);
            db.confirmAddressCorrection(normalized,chosenSector,chosenStreet,currentEngineerName());
            AddressResolution.Result confirmed=AddressResolution.resolve(classifier.classify(b.address),db.addressCorrectionFor(normalized),null);
            db.applyAddressResolution(b.id,confirmed);
            toast("تم تأكيد العنوان — سيُطبَّق تلقائياً على أي نص عنوان مطابق لاحقاً");
            if(parentDialog!=null)parentDialog.dismiss();
            AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null)showBeneficiary(fresh);
        }).setNegativeButton("إلغاء",null).show();
    }

    private void markStudyReady(AppDatabase.Beneficiary b,EditText address,EditText damage,AlertDialog dialog){saveStudy(b,address,damage);AppDatabase.Beneficiary fresh=db.beneficiary(b.id);List<String>missing=new ArrayList<>();if(fresh.address.isEmpty())missing.add("العنوان التفصيلي");if(fresh.damageNotes.isEmpty())missing.add("وصف الأضرار");if(db.quantityCount(b.id)==0||db.quantityTotal(b.id)<=0)missing.add("جدول الكميات والمبلغ المحسوب");if(loadPlan(fresh)==null)missing.add("مخطط المنزل (ارسمه وعايِره داخل التطبيق)");if(db.photoCount(b.id,BeneficiaryPhotoManager.BEFORE)==0)missing.add("صور قبل الترميم");if(!missing.isEmpty()){new AlertDialog.Builder(this).setTitle("الدراسة غير مكتملة").setMessage("أكمل العناصر التالية قبل الاعتماد:\n"+join(missing)).setPositiveButton("حسناً",null).show();return;}db.markStudyReady(b.id);dialog.dismiss();renderActive();toast("أصبحت الدراسة جاهزة للاعتماد");}
    private String quantitySummary(long beneficiaryId){int count=db.quantityCount(beneficiaryId);return count==0?"لم تُدخل كميات بعد":count+" بند محدد من أصل "+quantityCatalog.size()+" بنداً";}
    private String paymentSummary(double total){return "الإجمالي: "+QuantityCatalog.money(total)+" $\nالدفعة الأولى 60%: "+QuantityCatalog.firstPayment(total)+" $   •   الدفعة الثانية 40%: "+QuantityCatalog.secondPayment(total)+" $";}
    private double numericQuantity(EditText input){if(input==null)return 0;String value=input.getText().toString().trim().replace("٬","").replace("٫",".");if(value.contains(".")&&value.contains(","))value=value.replace(",","");else value=value.replace(',','.');if(value.isEmpty())return 0;try{return Math.max(0,Double.parseDouble(value));}catch(Exception ignored){return 0;}}
    private void showQuantityEditor(AppDatabase.Beneficiary beneficiary,boolean editable,Runnable onSaved){
        Map<Integer,Double>existing=new LinkedHashMap<>();for(AppDatabase.QuantityEntry entry:db.quantities(beneficiary.id))existing.put(entry.itemNo,entry.quantity);
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);box.addView(text(editable?"جدول كميات دراسة المنزل":"جدول الكميات المعتمد",20,NAVY,true));
        TextView source=text("37 بنداً من Reference.xlsx — اكتب الكمية أو استخدم حاسبة البند، ويُحسب السعر والإجمالي والدفعتان آلياً.",14,MUTED,false);source.setPadding(0,dp(4),0,dp(8));box.addView(source);
        Button fullReference=smallButton("📋 عرض ورقة الكميات المرجعية كاملة (وصف وسبب كل بند)",0xff123B5D);fullReference.setOnClickListener(v->showFullBoqReference());box.addView(fullReference,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView totalView=text("",16,0xff0E7490,true);totalView.setPadding(dp(10),dp(8),dp(10),dp(8));totalView.setBackground(round(0xffEAF7F4,12,0xffB7DED3,1));box.addView(totalView,new LinearLayout.LayoutParams(-1,-2));
        Map<Integer,EditText>fields=new LinkedHashMap<>();Map<Integer,TextView>subtotals=new LinkedHashMap<>();
        Runnable refreshTotal=()->{double total=0;for(QuantityCatalog.Item item:quantityCatalog){EditText field=fields.get(item.number);double quantity=field==null?0:numericQuantity(field);double subtotal=quantity*item.unitPrice;total+=subtotal;TextView subtotalView=subtotals.get(item.number);if(subtotalView!=null)subtotalView.setText("التكلفة: "+QuantityCatalog.money(subtotal)+" $");}totalView.setText(paymentSummary(total));};
        String lastCategory="";for(QuantityCatalog.Item item:quantityCatalog){if(!item.category.equals(lastCategory)){TextView category=text(item.category.trim(),18,NAVY,true);category.setPadding(0,dp(15),0,dp(5));box.addView(category);lastCategory=item.category;}
            LinearLayout card=column(dp(11));card.setBackground(round(SURFACE_CARD,13,CARD_BORDER,1));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(cardParams);
            TextView name=text(item.number+". "+item.name,16,BLUE,true);name.setOnClickListener(v->showQuantityItem(item));card.addView(name);
            card.addView(text("الوحدة: "+item.unit+"   •   السعر: "+QuantityCatalog.money(item.unitPrice)+" $",13,MUTED,true));
            LinearLayout entryRow=new LinearLayout(this);entryRow.setGravity(Gravity.CENTER_VERTICAL);TextView quantityLabel=text("الكمية",14,TEXT,true);entryRow.addView(quantityLabel,new LinearLayout.LayoutParams(dp(68),dp(52)));
            EditText quantity=new EditText(this);quantity.setSingleLine(true);quantity.setGravity(Gravity.CENTER);quantity.setTextSize(18);quantity.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);Double saved=existing.get(item.number);if(saved!=null&&saved>0)quantity.setText(QuantityCatalog.quantityNumber(saved));quantity.setEnabled(editable);quantity.setBackground(round(editable?0xffFFF2F6:0xffF0F3F5,10,editable?0xffD879A3:0xffCBD5DB,1));entryRow.addView(quantity,new LinearLayout.LayoutParams(0,dp(50),1));
            Button calculator=smallButton("حاسبة",0xff7A5CB8);calculator.setTextSize(14);calculator.setEnabled(editable);calculator.setAlpha(editable?1f:.45f);LinearLayout.LayoutParams calculatorParams=new LinearLayout.LayoutParams(dp(92),dp(50));calculatorParams.setMargins(dp(7),0,0,0);entryRow.addView(calculator,calculatorParams);card.addView(entryRow);
            TextView subtotal=text("التكلفة: 0 $",13,0xff0E7490,true);subtotal.setGravity(Gravity.END);subtotal.setPadding(0,dp(5),0,0);card.addView(subtotal,new LinearLayout.LayoutParams(-1,dp(36)));
            // §3: «📍 أماكن الضرر» — log WHERE the damage is (room + type + that place's qty) without long typing.
            LinearLayout dmgRow=new LinearLayout(this);dmgRow.setGravity(Gravity.CENTER_VERTICAL);dmgRow.setPadding(0,dp(2),0,0);
            Button dmgBtn=smallButton("📍 أماكن الضرر",0xff23507A);dmgBtn.setTextSize(12);dmgBtn.setEnabled(editable);dmgBtn.setAlpha(editable?1f:.5f);
            final TextView dmgSummary=text(damageSummary(beneficiary.id,item.number),11,MUTED,false);dmgSummary.setPadding(dp(8),0,0,0);
            dmgBtn.setOnClickListener(v->showDamageLocations(beneficiary,item,quantity,refreshTotal,dmgSummary));
            dmgRow.addView(dmgBtn,new LinearLayout.LayoutParams(dp(128),dp(42)));dmgRow.addView(dmgSummary,new LinearLayout.LayoutParams(0,-2,1));
            card.addView(dmgRow);
            box.addView(card);fields.put(item.number,quantity);subtotals.put(item.number,subtotal);
            calculator.setOnClickListener(v->showQuantityCalculator(item,quantity,refreshTotal));
            quantity.setOnFocusChangeListener((v,hasFocus)->{if(hasFocus)scroll.postDelayed(()->scroll.smoothScrollTo(0,Math.max(0,card.getTop()-dp(80))),250);});
            quantity.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}@Override public void onTextChanged(CharSequence s,int st,int before,int count){refreshTotal.run();}@Override public void afterTextChanged(Editable e){}});
        }
        refreshTotal.run();AlertDialog.Builder builder=new AlertDialog.Builder(this).setView(scroll).setNegativeButton(editable?"إلغاء":"إغلاق",null);if(editable)builder.setPositiveButton("حفظ الجدول",null);AlertDialog editor=builder.create();editor.setOnShowListener(v->{themeAndSize(editor.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.97),(int)(getResources().getDisplayMetrics().heightPixels*.94));editor.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);if(editable)editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save->{List<AppDatabase.QuantityEntry>entries=new ArrayList<>();for(QuantityCatalog.Item item:quantityCatalog){double quantity=numericQuantity(fields.get(item.number));if(quantity>0)entries.add(new AppDatabase.QuantityEntry(item.number,quantity,item.unitPrice));}db.saveQuantities(beneficiary.id,entries);editor.dismiss();if(onSaved!=null)onSaved.run();toast(entries.isEmpty()?"تم مسح جدول الكميات":"تم حفظ "+entries.size()+" بنداً واحتساب الإجمالي");});});editor.show();
    }
    private void showQuantityItem(QuantityCatalog.Item item){new AlertDialog.Builder(this).setTitle(item.number+". "+item.name).setMessage("الفئة: "+item.category.trim()+"\nالوحدة: "+item.unit+"\nالسعر: "+QuantityCatalog.money(item.unitPrice)+" $\n\nالوصف:\n"+safe(item.description,"—")+"\n\nسبب التدخل:\n"+safe(item.reason,"—")).setPositiveButton("إغلاق",null).show();}

    /** The complete 37-item reference sheet in one scrollable view — every
     * description and reason visible at once, instead of one tap per item. */
    private void showFullBoqReference(){
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);
        box.addView(text("ورقة الكميات المرجعية الكاملة",20,NAVY,true));
        TextView note=text("37 بنداً كما وردت في Reference.xlsx — للاطلاع فقط؛ أدخل الكميات من الجدول السابق.",13,MUTED,false);note.setPadding(0,dp(4),0,dp(10));box.addView(note);
        String lastCategory="";
        for(QuantityCatalog.Item item:quantityCatalog){
            if(!item.category.equals(lastCategory)){TextView category=text(item.category.trim(),17,NAVY,true);category.setPadding(0,dp(14),0,dp(5));box.addView(category);lastCategory=item.category;}
            LinearLayout card=column(dp(11));card.setBackground(round(SURFACE_CARD,13,CARD_BORDER,1));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.setMargins(0,dp(4),0,dp(4));card.setLayoutParams(cardParams);
            card.addView(text(item.number+". "+item.name,15,BLUE,true));
            card.addView(text("الوحدة: "+item.unit+"   •   السعر: "+QuantityCatalog.money(item.unitPrice)+" $",12,0xff0E7490,true));
            TextView description=text("الوصف: "+safe(item.description,"—"),12,TEXT,false);description.setPadding(0,dp(5),0,0);card.addView(description);
            TextView reason=text("سبب التدخل: "+safe(item.reason,"—"),12,MUTED,false);reason.setPadding(0,dp(3),0,0);card.addView(reason);
            box.addView(card);
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.97),(int)(getResources().getDisplayMetrics().heightPixels*.92)));
        dialog.show();
    }
    private EditText calculatorField(LinearLayout box,String label,String defaultValue){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView title=text(label,14,TEXT,true);row.addView(title,new LinearLayout.LayoutParams(dp(145),dp(52)));
        EditText input=new EditText(this);input.setSingleLine(true);input.setGravity(Gravity.CENTER);input.setTextSize(18);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setText(defaultValue);input.setSelectAllOnFocus(true);input.setBackground(round(0xffFFF2F6,10,0xffD879A3,1));row.addView(input,new LinearLayout.LayoutParams(0,dp(48),1));box.addView(row);return input;
    }
    private TextWatcher simpleWatcher(Runnable r){return new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}@Override public void onTextChanged(CharSequence s,int a,int b,int c){r.run();}@Override public void afterTextChanged(Editable e){}};}
    /** Safe arithmetic evaluator for the field calculator: + - × ÷ and parentheses with decimals. Normalises
     * Arabic digits/operators, strips spaces/thousands. Returns NaN on any malformed input. No functions/vars. */
    private double evalExpr(String s){
        if(s==null)return Double.NaN;
        String t=s.replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
                  .replace('×','*').replace('x','*').replace('X','*').replace('÷','/').replace('٫','.').replace("٬","").replace("،","").replace(",","").replace(" ","");
        if(t.isEmpty())return Double.NaN;
        try{return new Calc(t).evalAll();}catch(Exception e){return Double.NaN;}
    }
    /** Minimal recursive-descent parser: expr = term (('+'|'-') term)*; term = factor (('*'|'/') factor)*;
     * factor = number | '(' expr ')' | ('+'|'-') factor. Pure, JVM-testable, no Android deps. */
    private static final class Calc {
        private final String s;private int i;
        Calc(String s){this.s=s;}
        double evalAll(){double v=expr();if(i<s.length())throw new RuntimeException("trailing");return v;}
        private double expr(){double v=term();while(i<s.length()&&(peek()=='+'||peek()=='-')){char op=s.charAt(i++);double t=term();v=op=='+'?v+t:v-t;}return v;}
        private double term(){double v=factor();while(i<s.length()&&(peek()=='*'||peek()=='/')){char op=s.charAt(i++);double t=factor();v=op=='*'?v*t:v/t;}return v;}
        private double factor(){if(peek()=='+'){i++;return factor();}if(peek()=='-'){i++;return -factor();}if(peek()=='('){i++;double v=expr();if(peek()!=')')throw new RuntimeException("paren");i++;return v;}return number();}
        private double number(){int st=i;while(i<s.length()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))i++;if(i==st)throw new RuntimeException("num");return Double.parseDouble(s.substring(st,i));}
        private char peek(){return i<s.length()?s.charAt(i):'\0';}
    }
    private Button calcKey(String label){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(20);
        boolean digit=label.length()==1&&(Character.isDigit(label.charAt(0))||label.equals("."));
        boolean equals=label.equals("=");boolean clear=label.equals("C")||label.equals("⌫");
        int bg=equals?0xff149176:clear?0xffF6E1E6:digit?SURFACE_CARD:0xffEDE7F7;
        int fg=equals?Color.WHITE:clear?0xffBD4868:digit?TEXT:0xff5B3FA0;
        b.setTextColor(fg);b.setBackground(round(bg,12,CARD_BORDER,1));
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);b.setTypeface(null,Typeface.BOLD);
        return b;
    }
    private void onCalcKey(EditText expr,String k,Runnable calc){
        // Always APPEND at the logical end (calculator input is strictly left-to-right) — inserting at the caret
        // in a bidi/RTL field reorders parentheses and corrupts the expression, so we never use the caret here.
        Editable e=expr.getText();
        if("C".equals(k)){expr.setText("");expr.setSelection(0);calc.run();return;}
        if("⌫".equals(k)){int n=e.length();if(n>0)e.delete(n-1,n);expr.setSelection(e.length());calc.run();return;}
        if("=".equals(k)){double v=evalExpr(expr.getText().toString());if(!Double.isNaN(v)&&v>=0){expr.setText(QuantityCatalog.quantityNumber(v));}expr.setSelection(expr.length());calc.run();return;}
        e.append(k);expr.setSelection(e.length());calc.run();
    }
    /** FINAL FIELD USABILITY FIX §1 — a real field calculator (Casio-style): a large expression display + big
     * keypad supporting +, −, ×, ÷, parentheses and decimals, so the engineer can compute several sides/areas
     * for ONE BOQ item (e.g. 3.2+4.5+2.1 or (3×4)+(2×1.5)). Result goes to the item's quantity via «استبدال
     * الكمية» or «إضافة إلى الموجود». Works for every unit — the unit is only a label. Saving logic unchanged. */
    private void showQuantityCalculator(QuantityCatalog.Item item,EditText target,Runnable refreshTotal){
        final String unit=item.unit==null?"":item.unit.trim();
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(12));scroll.addView(box);
        box.addView(text(item.number+". "+item.name,17,NAVY,true));
        TextView hint=text("اكتب عملية حسابية للكمية — مثال: 3.2+4.5+2.1  أو  ‎(3×4)+(2×1.5)‎  •  الوحدة: "+unit,12,MUTED,false);hint.setPadding(0,dp(4),0,dp(6));box.addView(hint);
        final EditText expr=new EditText(this);expr.setSingleLine(false);expr.setMinLines(1);expr.setTextSize(24);expr.setGravity(Gravity.CENTER);expr.setInputType(InputType.TYPE_CLASS_TEXT);expr.setTextColor(TEXT);expr.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));expr.setPadding(dp(12),dp(14),dp(12),dp(14));expr.setTextDirection(View.TEXT_DIRECTION_LTR);expr.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);expr.setCursorVisible(true);
        if(android.os.Build.VERSION.SDK_INT>=21)expr.setShowSoftInputOnFocus(false);
        double cur=numericQuantity(target);if(cur>0)expr.setText(QuantityCatalog.quantityNumber(cur));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);ep.setMargins(0,dp(4),0,dp(6));box.addView(expr,ep);
        final TextView resultView=text("= 0 "+unit,22,0xff0E7490,true);resultView.setGravity(Gravity.CENTER);resultView.setPadding(dp(8),dp(10),dp(8),dp(10));resultView.setBackground(round(0xffEAF7F4,12,0xffB7DED3,1));box.addView(resultView,new LinearLayout.LayoutParams(-1,-2));
        final double[] result={cur>0?cur:0};
        final Runnable calc=()->{double v=evalExpr(expr.getText().toString());boolean ok=!Double.isNaN(v)&&v>=0;result[0]=ok?v:0;resultView.setText(ok?"= "+QuantityCatalog.quantityNumber(v)+" "+unit:(expr.getText().length()==0?"= 0 "+unit:"= —"));};
        expr.addTextChangedListener(simpleWatcher(calc));expr.requestFocus();expr.setSelection(expr.length());
        String[][] rows={{"7","8","9","÷","C"},{"4","5","6","×","⌫"},{"1","2","3","-","("},{"0",".","=","+",")"}};
        for(String[] r:rows){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(56));rp.setMargins(0,dp(4),0,0);
            for(final String k:r){Button b=calcKey(k);b.setOnClickListener(v->onCalcKey(expr,k,calc));LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(0,-1,1);kp.setMargins(dp(3),0,dp(3),0);row.addView(b,kp);}
            box.addView(row,rp);}
        calc.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("استبدال الكمية",null).setNeutralButton("إضافة إلى الموجود",null).setNegativeButton("إلغاء",null).create();
        dialog.setOnShowListener(v->{themeWin(dialog.getWindow());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(use->{if(result[0]<=0){toast("الناتج غير صالح — أدخل عملية صحيحة");return;}target.setText(QuantityCatalog.quantityNumber(result[0]));target.setSelection(target.length());refreshTotal.run();dialog.dismiss();});
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(add->{if(result[0]<=0){toast("الناتج غير صالح — أدخل عملية صحيحة");return;}double combined=numericQuantity(target)+result[0];target.setText(QuantityCatalog.quantityNumber(combined));target.setSelection(target.length());refreshTotal.run();dialog.dismiss();});});
        dialog.show();
    }
    // ===== FINAL FIELD USABILITY FIX §3 — «أماكن الضرر» (card-based damage-location logging per BOQ item) =====
    private static final String[] DAMAGE_PLACES={"صالون","غرفة 1","غرفة 2","غرفة 3","مطبخ","حمام","WC","ممر","مدخل","درج","سطح","برندة","واجهة","خارج المنزل"};
    private static final String[] DAMAGE_TYPES={"مكسّر","تالف","رطوبة","بحاجة تبديل","بحاجة ترميم","ناقص","غير صالح"};

    /** Short summary shown inside the BOQ item card: the places + their quantities, or «N مواقع» when many. */
    private String damageSummary(long beneficiaryId,int itemNo){
        java.util.List<AppDatabase.DamageLocation> locs=db.damageLocations(beneficiaryId,itemNo);
        if(locs.isEmpty())return "لا أماكن محددة بعد";
        if(locs.size()<=2){StringBuilder sb=new StringBuilder("أماكن الضرر: ");for(int i=0;i<locs.size();i++){if(i>0)sb.append("، ");sb.append(locs.get(i).place).append(" ").append(QuantityCatalog.quantityNumber(locs.get(i).qty));}return sb.toString();}
        return "أماكن الضرر: "+locs.size()+" مواقع";
    }
    private void promptCustomText(String title,String hint,java.util.function.Consumer<String> onOk){
        EditText in=new EditText(this);in.setHint(hint);in.setPadding(dp(16),dp(12),dp(16),dp(12));
        new AlertDialog.Builder(this).setTitle(title).setView(in).setPositiveButton("تعيين",(d,w)->{String t=in.getText().toString().trim();if(!t.isEmpty())onOk.accept(t);}).setNegativeButton("إلغاء",null).show();
    }
    /** Rebuildable chip grid (3/row). Selected chip is filled; «مخصص…» opens a small text field only when needed.
     * The grid REBUILDS ITSELF on every tap so the highlight always follows the last-tapped chip — the caller's
     * {@code onChange} is only a side-effect hook (e.g. refresh a summary line) and may be null. */
    private void buildDamageChips(LinearLayout container,String[] opts,String customPromptTitle,String[] chosen,Runnable onChange){
        container.removeAllViews();
        java.util.List<String> all=new java.util.ArrayList<>(java.util.Arrays.asList(opts));all.add("مخصص…");
        java.util.List<String> known=java.util.Arrays.asList(opts);
        int cols=3;LinearLayout row=null;
        for(int i=0;i<all.size();i++){
            if(i%cols==0){row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(3),0,0);container.addView(row,rp);}
            final String opt=all.get(i);
            boolean custom=opt.equals("مخصص…");
            boolean sel=opt.equals(chosen[0])||(custom&&!chosen[0].isEmpty()&&!known.contains(chosen[0]));
            TextView chip=text(custom&&sel?"✎ "+chosen[0]:opt,13,sel?Color.WHITE:0xff23507A,true);chip.setGravity(Gravity.CENTER);chip.setPadding(dp(4),dp(9),dp(4),dp(9));chip.setMaxLines(1);chip.setBackground(round(sel?0xff23507A:SURFACE_CARD,10,0xff23507A,1));
            chip.setOnClickListener(v->{
                if(custom)promptCustomText(customPromptTitle,"اكتب هنا",t->{chosen[0]=t;buildDamageChips(container,opts,customPromptTitle,chosen,onChange);if(onChange!=null)onChange.run();});
                else{chosen[0]=(opt.equals(chosen[0])?"":opt);buildDamageChips(container,opts,customPromptTitle,chosen,onChange);if(onChange!=null)onChange.run();}
            });
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1f);cp.setMargins(dp(3),0,dp(3),0);row.addView(chip,cp);
        }
        int rem=all.size()%cols;if(rem!=0&&row!=null)for(int k=rem;k<cols;k++)row.addView(new View(this),new LinearLayout.LayoutParams(0,-2,1f));
    }
    /** §3: the damage-location sheet for one BOQ item — pick place + damage type (cards), enter that place's
     * quantity (with the field calculator), «إضافة» to log it, see the running list + total, then «اعتماد المجموع
     * ككمية البند» / «إضافة إلى الكمية الحالية». Never changes the quantities table itself; details live in their
     * own table + backup.json + 07_البيانات/damage_locations.json. */
    private void showDamageLocations(AppDatabase.Beneficiary b,QuantityCatalog.Item item,EditText quantityField,Runnable refreshTotal,TextView cardSummary){
        final String unit=item.unit==null?"":item.unit.trim();final int itemNo=item.number;
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(10));scroll.addView(box);
        box.addView(text("أماكن الضرر — "+item.number+". "+item.name,17,NAVY,true));
        box.addView(text("الطريقة: 1) اختر المكان   2) اختر نوع الضرر   3) اكتب الكمية   4) اضغط «➕ إضافة هذا المكان».\nكرّر الخطوات لكل مكان متضرّر — يمكن إضافة عدّة أماكن لنفس البند.  •  الوحدة: "+unit,12,MUTED,false));
        final String[] place={""},type={""};
        box.addView(text("1) المكان:  (اضغط المكان — واضغطه ثانيةً لإلغائه)",13,0xff23507A,true));
        final LinearLayout placeGrid=column(0);box.addView(placeGrid);
        box.addView(text("2) نوع الضرر:",13,0xff23507A,true));
        final LinearLayout typeGrid=column(0);box.addView(typeGrid);
        final TextView selTv=text("",13,0xff149176,true);selTv.setPadding(dp(10),dp(8),dp(10),dp(8));selTv.setBackground(round(0xffEAF7F4,10,0xffB7DED3,1));
        LinearLayout.LayoutParams selp=new LinearLayout.LayoutParams(-1,-2);selp.setMargins(0,dp(8),0,0);box.addView(selTv,selp);
        final EditText qty=new EditText(this);
        final Runnable echoSel=()->{
            String p=place[0].isEmpty()?"—":place[0];
            String t=type[0].isEmpty()?"—":type[0];
            String q=qty.getText().toString().trim();if(q.isEmpty())q="—";
            selTv.setText("المحدد الآن:  المكان: "+p+"   •   النوع: "+t+"   •   الكمية: "+q);
        };
        buildDamageChips(placeGrid,DAMAGE_PLACES,"مكان مخصّص",place,echoSel);
        buildDamageChips(typeGrid,DAMAGE_TYPES,"نوع مخصّص",type,echoSel);
        LinearLayout qtyRow=new LinearLayout(this);qtyRow.setGravity(Gravity.CENTER_VERTICAL);qtyRow.setPadding(0,dp(6),0,0);
        qtyRow.addView(text("3) الكمية:",13,TEXT,true),new LinearLayout.LayoutParams(dp(72),-2));
        qty.setSingleLine(true);qty.setGravity(Gravity.CENTER);qty.setTextSize(17);qty.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);qty.setBackground(round(0xffFFF2F6,10,0xffD879A3,1));qty.addTextChangedListener(simpleWatcher(echoSel));qtyRow.addView(qty,new LinearLayout.LayoutParams(0,dp(48),1));
        Button qcalc=smallButton("🧮 حاسبة",0xff7A5CB8);qcalc.setTextSize(12);LinearLayout.LayoutParams qcp=new LinearLayout.LayoutParams(dp(96),dp(48));qcp.setMargins(dp(6),0,0,0);qcalc.setOnClickListener(v->showQuantityCalculator(item,qty,echoSel));qtyRow.addView(qcalc,qcp);
        box.addView(qtyRow);
        echoSel.run();
        Button add=smallButton("➕ إضافة هذا المكان",0xff149176);LinearLayout.LayoutParams addp=new LinearLayout.LayoutParams(-1,dp(50));addp.setMargins(0,dp(8),0,dp(4));box.addView(add,addp);
        final TextView totalTv=text("",15,0xff0E7490,true);totalTv.setPadding(dp(8),dp(6),dp(8),dp(6));totalTv.setBackground(round(0xffEAF7F4,10,0xffB7DED3,1));box.addView(totalTv,new LinearLayout.LayoutParams(-1,-2));
        final LinearLayout listBox=column(0);box.addView(listBox);
        final double[] sum={0};
        final Runnable refreshList=new Runnable(){@Override public void run(){
            listBox.removeAllViews();java.util.List<AppDatabase.DamageLocation> locs=db.damageLocations(b.id,itemNo);double s=0;
            for(AppDatabase.DamageLocation dl:locs){
                s+=dl.qty;final long id=dl.id;
                LinearLayout r=new LinearLayout(MainActivity.this);r.setGravity(Gravity.CENTER_VERTICAL);r.setBackground(round(SURFACE_CARD,10,CARD_BORDER,1));r.setPadding(dp(10),dp(8),dp(10),dp(8));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(4),0,0);r.setLayoutParams(rp);
                LinearLayout col=column(0);col.addView(text(dl.place+"  —  "+QuantityCatalog.quantityNumber(dl.qty)+" "+unit,14,NAVY,true));
                if(!dl.damageType.isEmpty()||!dl.note.isEmpty())col.addView(text((dl.damageType.isEmpty()?"":dl.damageType)+(dl.note.isEmpty()?"":" • "+dl.note),11,MUTED,false));
                r.addView(col,new LinearLayout.LayoutParams(0,-2,1));
                Button del=smallButton("🗑",0xffBD4868);del.setTextSize(12);del.setOnClickListener(v->{db.deleteDamageLocation(id);this.run();});r.addView(del,new LinearLayout.LayoutParams(dp(46),dp(40)));
                listBox.addView(r);
            }
            sum[0]=s;totalTv.setText("المجموع: "+QuantityCatalog.quantityNumber(s)+" "+unit+"   ("+locs.size()+" موقع)");
            if(cardSummary!=null)cardSummary.setText(damageSummary(b.id,itemNo));
        }};
        add.setOnClickListener(v->{
            if(place[0].isEmpty()){toast("اختر المكان أولاً");return;}
            double q=numericQuantity(qty);if(q<=0){toast("أدخل كمية هذا المكان (أكبر من صفر)");return;}
            db.addDamageLocation(b.id,itemNo,place[0],type[0],q,"");qty.setText("");refreshList.run();toast("أُضيف: "+place[0]+" "+QuantityCatalog.quantityNumber(q)+" "+unit);
        });
        Button adopt=smallButton("✔ اعتماد المجموع ككمية البند",0xff0E7490);LinearLayout.LayoutParams adp=new LinearLayout.LayoutParams(-1,dp(48));adp.setMargins(0,dp(10),0,dp(2));box.addView(adopt,adp);
        adopt.setOnClickListener(v->{if(sum[0]<=0){toast("لا يوجد مجموع بعد");return;}quantityField.setText(QuantityCatalog.quantityNumber(sum[0]));quantityField.setSelection(quantityField.length());refreshTotal.run();toast("كمية البند = "+QuantityCatalog.quantityNumber(sum[0])+" "+unit);});
        Button addToQ=smallButton("➕ إضافة المجموع إلى الكمية الحالية",0xff149176);LinearLayout.LayoutParams atp=new LinearLayout.LayoutParams(-1,dp(48));atp.setMargins(0,dp(6),0,dp(2));box.addView(addToQ,atp);
        addToQ.setOnClickListener(v->{if(sum[0]<=0){toast("لا يوجد مجموع بعد");return;}double combined=numericQuantity(quantityField)+sum[0];quantityField.setText(QuantityCatalog.quantityNumber(combined));quantityField.setSelection(quantityField.length());refreshTotal.run();toast("الكمية الحالية = "+QuantityCatalog.quantityNumber(combined)+" "+unit);});
        refreshList.run();
        AlertDialog d=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();
        d.setOnShowListener(v->themeAndSize(d.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.97),(int)(getResources().getDisplayMetrics().heightPixels*.92)));
        d.setOnDismissListener(v->{if(cardSummary!=null)cardSummary.setText(damageSummary(b.id,itemNo));});
        d.show();
    }

    private View photoStageCard(AppDatabase.Beneficiary b,String phase,int color){return photoStageCard(b,phase,color,true);}
    private View photoStageCard(AppDatabase.Beneficiary b,String phase,int color,boolean allowCapture){LinearLayout card=column(dp(12));card.setBackground(round(SURFACE_CARD,14,CARD_BORDER,1));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(cardParams);int count=db.photoCount(b.id,phase);LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(text(BeneficiaryPhotoManager.phaseLabel(phase),15,color,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(text(count+" صورة",12,MUTED,true));card.addView(heading);LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);if(allowCapture){Button capture=dialogButton("فتح الكاميرا",color,v->startPhotoCapture(b,phase));actions.addView(capture);}Button open=dialogButton("عرض الصور",NAVY,v->showStagePhotos(b,phase));open.setEnabled(count>0);open.setAlpha(count>0?1f:.4f);actions.addView(open);card.addView(actions);return card;}
    private View statusAction(String label,String status,int color,AppDatabase.Beneficiary b,EditText progress,EditText notes,AlertDialog dialog){Button button=smallButton(label,color);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(4),0,0);button.setLayoutParams(p);button.setOnClickListener(v->{int percent;try{percent=Integer.parseInt(progress.getText().toString().trim());}catch(Exception ignored){percent=0;}percent=Math.max(0,Math.min(100,percent));db.updateVisit(b.id,status,percent,notes.getText().toString());dialog.dismiss();renderActive();Toast.makeText(this,"تم حفظ الحالة ونسبة الإنجاز",Toast.LENGTH_SHORT).show();});return button;}
    private View info(String label,String value){LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(7),0,dp(7));TextView l=text(label,12,MUTED,true);row.addView(l,new LinearLayout.LayoutParams(dp(120),-2));TextView v=text(safe(value,"—"),13,TEXT,false);v.setTextIsSelectable(true);row.addView(v,new LinearLayout.LayoutParams(0,-2,1));return row;}
    /** «فريق الدراسة» card: first engineer + (optional) second engineer + (optional) field assistant + study date.
     * Empty optional lines are hidden. Shows the office-review send date once the study has been sent. */
    private View studyTeamCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(12));card.setBackground(round(SURFACE_CARD,14,0xffDCE9EF,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(6));card.setLayoutParams(p);
        card.addView(text("فريق الدراسة",14,0xff0E7490,true));
        String e1=b.studyEngineer1==null||b.studyEngineer1.trim().isEmpty()?safe(b.engineerName,"—"):b.studyEngineer1;
        card.addView(info("المهندس الأول",e1));
        if(b.studyEngineer2!=null&&!b.studyEngineer2.trim().isEmpty())card.addView(info("المهندس الثاني",b.studyEngineer2));
        if(b.fieldAssistant!=null&&!b.fieldAssistant.trim().isEmpty())card.addView(info("الباحث الاجتماعي",b.fieldAssistant));
        card.addView(info("تاريخ الدراسة",b.studyDate==null||b.studyDate.isEmpty()?"—":b.studyDate));
        if(b.sentToOfficeReviewAt>0)card.addView(info("أُرسلت للمراجعة المكتبية",new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(b.sentToOfficeReviewAt))));
        return card;
    }

    // ---- House Drawing Module (M8): the drawing lives per-beneficiary as files/drawings/house_<id>.json (no
    // DB change); the card just reflects that file's state and launches the editor with the real beneficiary key.
    private java.io.File houseJson(long id){ return new java.io.File(getFilesDir(),"drawings/house_"+id+".json"); }

    private void openHouseDrawEditor(AppDatabase.Beneficiary b){
        Intent i=new Intent(this,org.unrwa.yarmoukfield.drawing.HouseDrawActivity.class);
        i.putExtra("draw_key",String.valueOf(b.id));
        i.putExtra("benef_name",safe(b.name,""));
        i.putExtra("file_no",(b.registration==null||b.registration.isEmpty())?String.valueOf(b.id):b.registration);
        // Engineer name written on the plan: prefer the study's first engineer, then the record's engineer, then
        // the app-wide engineer set in Settings — so the name entered «at the start» always appears on the plan.
        String eng=firstNonEmpty(b.studyEngineer1,b.engineerName,currentEngineerName());
        i.putExtra("engineer",eng);
        startActivity(i);
    }
    private String firstNonEmpty(String... vals){ if(vals!=null)for(String v:vals)if(v!=null&&!v.trim().isEmpty())return v.trim(); return ""; }

    private String readTextFile(java.io.File f) throws Exception {
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=new java.io.FileInputStream(f)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)bo.write(buf,0,n);}
        return bo.toString("UTF-8");
    }

    private View housePlanCard(AppDatabase.Beneficiary b){
        LinearLayout card=column(dp(14));card.setBackground(round(SURFACE_CARD,15,CARD_BORDER,1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(12),0,0);card.setLayoutParams(cp);
        card.addView(text("مخطط المنزل",16,NAVY,true));
        java.io.File jf=houseJson(b.id);
        if(!jf.exists()){
            TextView none=text("لم يتم إنشاء مخطط بعد",12,MUTED,false);none.setPadding(0,dp(4),0,dp(8));card.addView(none);
            Button draw=smallButton("رسم مخطط جديد",0xff0E8A9C);draw.setOnClickListener(v->openHouseDrawEditor(b));card.addView(draw,new LinearLayout.LayoutParams(-1,dp(48)));
            return card;
        }
        try{
            org.unrwa.yarmoukfield.drawing.DrawModel m=org.unrwa.yarmoukfield.drawing.DrawJson.fromJson(readTextFile(jf));
            android.graphics.Bitmap thumb=org.unrwa.yarmoukfield.drawing.DrawThumb.render(m,dp(260),dp(150));
            ImageView iv=new ImageView(this);iv.setImageBitmap(thumb);iv.setBackground(round(CARD_BG,12,CARD_BORDER,1));iv.setPadding(dp(6),dp(6),dp(6),dp(6));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(160));ip.setMargins(0,dp(6),0,dp(8));card.addView(iv,ip);
            boolean cal=m.isCalibrated();
            card.addView(text(cal?("معاير • 1م = "+Math.round(m.pxPerMeter)+" px"):"غير معاير — افتح «تعديل» وعايِر",12,cal?0xff149176:0xffC9851C,true));
            String area=!cal?"المساحة: — (غير معاير)":(m.roomCount()>0?String.format(java.util.Locale.US,"المساحة: %.2f م²  •  %d غرفة",m.roomAreaM2(),m.roomCount()):"المساحة: — (لا غرف مغلقة)");
            card.addView(text(area,12,NAVY,true));
            card.addView(text("آخر تعديل: "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(jf.lastModified())),11,MUTED,false));
            Button edit=smallButton("تعديل المخطط",0xff0E8A9C);edit.setOnClickListener(v->openHouseDrawEditor(b));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(46));bp.setMargins(0,dp(8),0,0);card.addView(edit,bp);
            Button pdf=smallButton("فتح PDF",NAVY);pdf.setOnClickListener(v->openHouseExport(b,true));
            LinearLayout.LayoutParams bp2=new LinearLayout.LayoutParams(-1,dp(46));bp2.setMargins(0,dp(6),0,0);card.addView(pdf,bp2);
            Button dxf=smallButton("فتح DXF",0xff7A5CB8);dxf.setOnClickListener(v->openHouseExport(b,false));
            LinearLayout.LayoutParams bp3=new LinearLayout.LayoutParams(-1,dp(46));bp3.setMargins(0,dp(6),0,0);card.addView(dxf,bp3);
        }catch(Exception e){
            card.addView(text("تعذّر قراءة المخطط المحفوظ",12,0xffBD4868,false));
            Button draw=smallButton("فتح المحرّر",0xff0E8A9C);draw.setOnClickListener(v->openHouseDrawEditor(b));card.addView(draw,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        return card;
    }

    private void openHouseExport(AppDatabase.Beneficiary b,boolean pdf){
        try{
            java.io.File jf=houseJson(b.id);if(!jf.exists()){toast("لا يوجد مخطط محفوظ");return;}
            org.unrwa.yarmoukfield.drawing.DrawModel m=org.unrwa.yarmoukfield.drawing.DrawJson.fromJson(readTextFile(jf));
            if(!m.isCalibrated()){toast("المخطط غير معاير — افتح «تعديل المخطط» وعايِر المقياس أولاً");return;}
            java.io.File dir=new java.io.File(getCacheDir(),"embedded_cad");dir.mkdirs();
            if(pdf){
                org.unrwa.yarmoukfield.drawing.DrawExportMeta meta=new org.unrwa.yarmoukfield.drawing.DrawExportMeta();
                meta.benefName=safe(b.name,"");meta.fileNo=(b.registration==null||b.registration.isEmpty())?String.valueOf(b.id):b.registration;
                meta.engineer=b.engineerName==null?"":b.engineerName;meta.date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
                if(m.ceilingHeightM>0)meta.ceiling=String.format(java.util.Locale.US,"h=%.2fm",m.ceilingHeightM);
                java.io.File out=new java.io.File(dir,"house_"+b.id+".pdf");
                org.unrwa.yarmoukfield.drawing.PdfExporter.export(this,m,meta,out);
                viewCadFile(out,"application/pdf");
            }else{
                java.io.File out=new java.io.File(dir,"house_"+b.id+".dxf");
                try(java.io.FileOutputStream fos=new java.io.FileOutputStream(out)){fos.write(org.unrwa.yarmoukfield.drawing.DxfWriter.toDxf(m).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
                viewCadFile(out,"application/dxf");
            }
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذّر التصدير/الفتح").setMessage(String.valueOf(e.getMessage())).setPositiveButton("حسناً",null).show();}
    }

    private void viewCadFile(java.io.File f,String mime){
        Uri uri=CadFileProvider.uriFor(this,f);
        Intent v=new Intent(Intent.ACTION_VIEW);v.setDataAndType(uri,mime);v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(v);}catch(Exception e){toast("لا يوجد تطبيق على الجهاز يفتح هذه الصيغة ("+mime+")");}
    }
    private void dial(String phone){try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone.replaceAll("[^+0-9]",""))));}catch(Exception e){toast("الاتصال غير متاح على هذا الجهاز");}}

    private void choosePhotoRoot(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(intent,REQUEST_PHOTO_ROOT);}
    private void chooseHousePlan(AppDatabase.Beneficiary b){pendingPlanBeneficiaryId=b.id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/*","application/dxf","application/dwg","application/octet-stream"});intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_HOUSE_PLAN);}
    private void openHousePlan(String value){if(value==null||value.isEmpty()){toast("لم يُرفق مخطط المنزل بعد");return;}try{Uri uri=Uri.parse(value);String type=getContentResolver().getType(uri);Intent open=new Intent(Intent.ACTION_VIEW);open.setDataAndType(uri,type==null?"*/*":type);open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);open.setClipData(ClipData.newRawUri("مخطط المنزل",uri));startActivity(open);}catch(Exception error){toast("تعذر فتح المخطط؛ تحقق من أن الملف لم يُنقل أو يُحذف");}}
    private void promoteReadyBatch(){int ready=db.studyReadyCount(currentProjectId);if(ready<50){new AlertDialog.Builder(this).setTitle("دفعة المتابعة غير مكتملة").setMessage("عدد الدراسات الجاهزة حالياً: "+ready+" من 50. أكمل اعتماد 50 دراسة قبل إصدار القائمة.").setPositiveButton("حسناً",null).show();return;}String batch="دفعة "+new SimpleDateFormat("yyyy-MM-dd HHmm",Locale.US).format(new Date());new AlertDialog.Builder(this).setTitle("إصدار "+batch).setMessage("سيتم نقل أول 50 دراسة جاهزة إلى مرحلة متابعة التنفيذ. تبقى أي دراسات إضافية للدفعة التالية.").setPositiveButton("اعتماد ونقل",(d,w)->{int moved=db.promoteReadyBatch(currentProjectId,batch);showPage(NAV_ROUTE);toast("تم إصدار القائمة ونقل "+moved+" مستفيداً إلى المتابعة");}).setNegativeButton("إلغاء",null).show();}
    /** Launches the in-app continuous camera ({@link SequentialCameraActivity}) — every shot is saved with no
     * per-photo accept/reject screen, so a whole room can be photographed in one rhythm. Needs the SAF photo
     * folder configured (prompt once) and the CAMERA runtime permission. */
    private void startPhotoCapture(AppDatabase.Beneficiary b,String phase){
        // PROJECT SAVE ROOT (§3): photos never open a folder picker of their own — they save into the single
        // project root automatically. Only if that root is missing/lost do we ask for it once, then resume here.
        if(!rootUsable()){requireProjectRoot(()->startPhotoCapture(b,phase));return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){pendingCameraBenId=b.id;pendingCameraPhase=phase;requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA_PERMISSION);return;}
        pendingPhotoBeneficiaryId=b.id;pendingPhotoPhase=phase;
        Intent cam=new Intent(this,SequentialCameraActivity.class);
        cam.putExtra(SequentialCameraActivity.EXTRA_TITLE,safe(b.name,"")+" — "+BeneficiaryPhotoManager.phaseLabel(phase));
        if(beneficiaryDialog!=null)beneficiaryDialog.dismiss();
        startActivityForResult(cam,REQUEST_SEQ_CAMERA);
    }
    /** Persists every JPEG captured in one continuous session: copies each temp file into the beneficiary's SAF
     * photo folder and records it in the database, then cleans up the temp files. A single unreadable shot is
     * skipped without failing the batch. */
    private void handleSeqCapture(int result,Intent data){
        long beneficiaryId=pendingPhotoBeneficiaryId;String phase=pendingPhotoPhase;pendingPhotoBeneficiaryId=0;pendingPhotoPhase="";
        java.util.ArrayList<String> paths=data==null?null:data.getStringArrayListExtra(SequentialCameraActivity.EXTRA_PATHS);
        AppDatabase.Beneficiary b=beneficiaryId>0?db.beneficiary(beneficiaryId):null;
        if(b==null){return;}
        if(paths==null||paths.isEmpty()){int total=db.photoCount(beneficiaryId,phase);if(total>0)toast("لم تُلتقط صور جديدة — الإجمالي "+total);showBeneficiary(b);return;}
        java.io.File shotDir=null;int saved=0;int failed=0;
        for(String p:paths){
            java.io.File f=new java.io.File(p);if(shotDir==null)shotDir=f.getParentFile();
            try{
                BeneficiaryPhotoManager.Target target=photoManager.createTarget(packageRootUri(),b,phase);
                try(java.io.InputStream in=new java.io.FileInputStream(f);java.io.OutputStream os=getContentResolver().openOutputStream(target.uri,"w")){
                    if(os==null)throw new Exception("تعذر فتح ملف الوجهة");byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)os.write(buf,0,n);
                }
                db.addPhoto(beneficiaryId,phase,target.uri.toString(),target.fileName,target.capturedAt,"");saved++;
            }catch(Exception e){failed++;}
            finally{try{f.delete();}catch(Exception ignored){}}
        }
        if(shotDir!=null)try{shotDir.delete();}catch(Exception ignored){}
        int total=db.photoCount(beneficiaryId,phase);
        toast("تم حفظ "+saved+" صورة في «"+BeneficiaryPhotoManager.phaseLabel(phase)+"» (الإجمالي "+total+")"+(failed>0?" — تعذّر حفظ "+failed:""));
        showBeneficiary(b);
    }
    private boolean photoHasData(Uri uri){try(android.content.res.AssetFileDescriptor descriptor=getContentResolver().openAssetFileDescriptor(uri,"r")){return descriptor!=null&&descriptor.getLength()!=0;}catch(Exception error){return false;}}
    private void promptPhotoNote(long photoId,AppDatabase.Beneficiary b){EditText note=new EditText(this);note.setHint("مثال: إزالة الأنقاض، تنفيذ القميص، استلام المرحلة...");note.setMinLines(3);note.setGravity(Gravity.TOP|Gravity.START);note.setPadding(dp(14),dp(10),dp(14),dp(10));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("ملاحظة على الصورة").setMessage("اختياري — تساعد الملاحظة في متابعة سير العمل.").setView(note).setPositiveButton("حفظ",(d,w)->db.updatePhotoNote(photoId,note.getText().toString().trim())).setNegativeButton("لاحقاً",null).create();dialog.setOnDismissListener(v->{AppDatabase.Beneficiary fresh=db.beneficiary(b.id);if(fresh!=null&&!isFinishing())showBeneficiary(fresh);});dialog.show();}

    private void showStagePhotos(AppDatabase.Beneficiary b,String phase){List<AppDatabase.Photo>photos=db.photos(b.id,phase);ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(14));scroll.addView(box);box.addView(text(BeneficiaryPhotoManager.phaseLabel(phase)+" — "+photos.size()+" صورة",19,NAVY,true));SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd  HH:mm",Locale.US);for(AppDatabase.Photo photo:photos){LinearLayout card=new LinearLayout(this);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(8),dp(8),dp(8),dp(8));card.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(cp);ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setBackgroundColor(0xffE7EEF2);card.addView(preview,new LinearLayout.LayoutParams(dp(128),dp(96)));loadThumbnail(preview,Uri.parse(photo.uri));LinearLayout details=column(dp(9));details.addView(text(format.format(new Date(photo.capturedAt)),12,NAVY,true));details.addView(text(safe(photo.note,"دون ملاحظة"),11,MUTED,false));LinearLayout photoActions=new LinearLayout(this);Button open=smallButton("فتح الصورة",0xff0E7490);open.setOnClickListener(v->openPhoto(photo));LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(0,dp(42),1);photoActions.addView(open,op);Button noteButton=smallButton("✎ ملاحظة",0xff7A5CB8);noteButton.setOnClickListener(v->promptPhotoNote(photo.id,b));LinearLayout.LayoutParams npo=new LinearLayout.LayoutParams(0,dp(42),1);npo.setMargins(dp(6),0,0,0);photoActions.addView(noteButton,npo);LinearLayout.LayoutParams pap=new LinearLayout.LayoutParams(-1,-2);pap.setMargins(0,dp(6),0,0);details.addView(photoActions,pap);card.addView(details,new LinearLayout.LayoutParams(0,-2,1));box.addView(card);}AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.96),(int)(getResources().getDisplayMetrics().heightPixels*.90)));dialog.show();}
    private void openPhoto(AppDatabase.Photo photo){try{Uri uri=Uri.parse(photo.uri);Intent view=new Intent(Intent.ACTION_VIEW);view.setDataAndType(uri,"image/jpeg");view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);view.setClipData(ClipData.newRawUri("صورة المستفيد",uri));startActivity(view);}catch(Exception error){toast("تعذر فتح الصورة؛ تحقق من أن المجلد لم يُنقل أو يُحذف");}}
    private void loadThumbnail(ImageView target,Uri uri){worker.execute(()->{try{Bitmap image=decodeThumbnail(uri,520);runOnUiThread(()->{if(image!=null&&!isFinishing())target.setImageBitmap(image);});}catch(Exception ignored){runOnUiThread(()->target.setImageResource(android.R.drawable.ic_menu_report_image));}});}
    private Bitmap decodeThumbnail(Uri uri,int targetSize)throws Exception{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new Exception("تعذر فتح الصورة");BitmapFactory.decodeStream(input,null,bounds);}int sample=1;while(bounds.outWidth/sample>targetSize*2||bounds.outHeight/sample>targetSize*2)sample*=2;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,sample);Bitmap bmp;try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new Exception("تعذر فتح الصورة");bmp=BitmapFactory.decodeStream(input,null,options);}return applyExifRotation(bmp,uri);}
    /** Display-only: rotate/flip a decoded bitmap to match its EXIF orientation so imported reference photos (and any
     * photo carrying an EXIF tag) show UPRIGHT in-app — «بدون التفاف». Never modifies the original file. Captured
     * photos already have EXIF=NORMAL (rotated at capture), so this is a no-op for them. */
    private Bitmap applyExifRotation(Bitmap bmp,Uri uri){
        if(bmp==null)return null;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)return bmp;
            android.media.ExifInterface exif=new android.media.ExifInterface(in);
            int o=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL);
            int deg=0;boolean fx=false,fy=false;
            switch(o){
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:deg=90;break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180:deg=180;break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270:deg=270;break;
                case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:fx=true;break;
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:fy=true;break;
                case android.media.ExifInterface.ORIENTATION_TRANSPOSE:deg=90;fx=true;break;
                case android.media.ExifInterface.ORIENTATION_TRANSVERSE:deg=270;fx=true;break;
                default:return bmp;
            }
            android.graphics.Matrix m=new android.graphics.Matrix();if(deg!=0)m.postRotate(deg);if(fx)m.postScale(-1,1);if(fy)m.postScale(1,-1);
            Bitmap r=Bitmap.createBitmap(bmp,0,0,bmp.getWidth(),bmp.getHeight(),m,true);
            if(r!=bmp)bmp.recycle();
            return r;
        }catch(Exception e){return bmp;}
    }
    private void savePendingPhoto(){getPreferences(MODE_PRIVATE).edit().putString("pending_photo_uri",pendingPhotoUri==null?"":pendingPhotoUri.toString()).putLong("pending_photo_beneficiary",pendingPhotoBeneficiaryId).putString("pending_photo_phase",pendingPhotoPhase).putString("pending_photo_file",pendingPhotoFileName).putLong("pending_photo_time",pendingPhotoCapturedAt).apply();}
    private void restorePendingPhoto(){String saved=getPreferences(MODE_PRIVATE).getString("pending_photo_uri","");if(saved!=null&&!saved.isEmpty())pendingPhotoUri=Uri.parse(saved);pendingPhotoBeneficiaryId=getPreferences(MODE_PRIVATE).getLong("pending_photo_beneficiary",0);pendingPhotoPhase=getPreferences(MODE_PRIVATE).getString("pending_photo_phase","");pendingPhotoFileName=getPreferences(MODE_PRIVATE).getString("pending_photo_file","");pendingPhotoCapturedAt=getPreferences(MODE_PRIVATE).getLong("pending_photo_time",0);}
    private void clearPendingPhoto(){pendingPhotoUri=null;pendingPhotoBeneficiaryId=0;pendingPhotoPhase="";pendingPhotoFileName="";pendingPhotoCapturedAt=0;getPreferences(MODE_PRIVATE).edit().remove("pending_photo_uri").remove("pending_photo_beneficiary").remove("pending_photo_phase").remove("pending_photo_file").remove("pending_photo_time").apply();}

    private void showGuide(){ScrollView scroll=new ScrollView(this);content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout box=column(dp(16));scroll.addView(box);box.addView(text("الدليل والخرائط",21,NAVY,true));TextView sub=text("الخرائط العامة والقطاعات والشوارع والمخططات المرجعية، بالإضافة إلى دليل استخدام التطبيق.",13,MUTED,false);sub.setPadding(0,dp(4),0,dp(12));box.addView(sub);
        // App-usage guides live here (moved out of Settings) since they belong with the reference material.
        box.addView(action("📖","دليل استخدام التطبيق","شرح تفاعلي لمسار العمل داخل التطبيق",0xff7A5CB8,0,v->showUserGuide()));
        box.addView(action("📄","دليل الاستخدام PDF","نسخة عربية مرفقة تعمل دون إنترنت وقابلة للطباعة",0xff0E7490,1,v->PdfMapViewer.show(this,R.raw.user_guide_ar,0,"دليل استخدام التطبيق")));
        TextView mapsTitle=text("الخرائط والقطاعات",17,NAVY,true);mapsTitle.setPadding(0,dp(16),0,dp(6));box.addView(mapsTitle);
        box.addView(action("🗺","المخطط الكامل - أسماء واضحة","المخطط الجديد عالي الوضوح لقراءة الشوارع عند التقريب",BLUE,0,v->showMapOptions(false,0,"المخطط التنظيمي لمخيم اليرموك")));
        box.addView(action("🧭","صفحات القطاعات - أسماء أوضح","سبع صفحات مفصّلة بخط NART المضمّن أو ملف DXF",0xff149176,1,v->showMapOptions(true,0,"مخططات قطاعات مخيم اليرموك")));
        Spinner sector=new Spinner(this);SectorMapView map=new SectorMapView(this);map.setBackground(round(0xffF4FAFC,18,0xffD6E6EC,1));map.setOnSectorClickListener((index,name)->{sector.setSelection(index);showMapOptions(true,SECTOR_PDF_PAGES[index],"مخطط قطاع "+name);});LinearLayout.LayoutParams mapParams=new LinearLayout.LayoutParams(-1,dp(610));mapParams.setMargins(0,dp(10),0,0);box.addView(map,mapParams);
        TextView mapHint=text("الحدود الملونة تفصل القطاعات. اضغط الاسم الصغير لفتح صفحة القطاع وقراءة الشوارع بوضوح.",13,0xff0E7490,true);mapHint.setGravity(Gravity.CENTER);mapHint.setPadding(0,dp(7),0,0);box.addView(mapHint);
        Button heatToggle=smallButton("🔥 إظهار كثافة تجمع القوائم على المخطط",0xffC9851C);LinearLayout.LayoutParams heatParams=new LinearLayout.LayoutParams(-1,dp(48));heatParams.setMargins(0,dp(10),0,0);heatToggle.setLayoutParams(heatParams);heatToggle.setTag(false);
        heatToggle.setOnClickListener(v->{boolean shown=(Boolean)heatToggle.getTag();if(shown){map.clearHeatData();heatToggle.setText("🔥 إظهار كثافة تجمع القوائم على المخطط");heatToggle.setTag(false);return;}
            List<AppDatabase.Beneficiary>rows=db.listStage(currentProjectId,"","followup","all");int[]counts=new int[AddressClassifier.SECTOR_ORDER.size()];for(AppDatabase.Beneficiary b:rows){int idx=AddressClassifier.SECTOR_ORDER.indexOf(b.sector);if(idx>=0)counts[idx]++;}
            boolean any=false;for(int c:counts)if(c>0)any=true;if(!any){toast("لا توجد قائمة مستوردة بعناوين مصنّفة بعد");return;}
            map.setHeatData(counts);heatToggle.setText("إخفاء كثافة القوائم");heatToggle.setTag(true);});
        box.addView(heatToggle);
        TextView choose=text("اختر القطاع",15,NAVY,true);choose.setPadding(0,dp(18),0,dp(6));box.addView(choose);ArrayAdapter<String>adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,AddressClassifier.SECTOR_ORDER);sector.setAdapter(adapter);sector.setBackground(round(SURFACE_CARD,12,CARD_BORDER,1));box.addView(sector,new LinearLayout.LayoutParams(-1,dp(50)));
        Button openSector=smallButton("فتح مخطط القطاع المحدد",0xff0E7490);LinearLayout.LayoutParams openParams=new LinearLayout.LayoutParams(-1,dp(50));openParams.setMargins(0,dp(8),0,dp(4));openSector.setLayoutParams(openParams);openSector.setOnClickListener(v->{int pos=sector.getSelectedItemPosition();String name=AddressClassifier.SECTOR_ORDER.get(pos);showMapOptions(true,SECTOR_PDF_PAGES[pos],"مخطط قطاع "+name);});box.addView(openSector);LinearLayout streets=column(dp(8));box.addView(streets,new LinearLayout.LayoutParams(-1,-2));
        sector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?>p){}@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){map.setSelectedSector(pos);streets.removeAllViews();String name=AddressClassifier.SECTOR_ORDER.get(pos);List<String>items=classifier.getReference().get(name);TextView count=text(name+" — "+items.size()+" شارعاً/معلماً",15,0xff0E7490,true);count.setPadding(0,dp(12),0,dp(6));streets.addView(count);for(int i=0;i<items.size();i++){String street=items.get(i);TextView line=text((i+1)+". "+street,13,TEXT,false);line.setPadding(dp(10),dp(9),dp(10),dp(9));line.setBackground(round(i%2==0?CARD_BG:SURFACE_CARD,8,0,0));streets.addView(line,new LinearLayout.LayoutParams(-1,-2));}}});}

    private void showMapOptions(boolean sectorMap,int pdfPage,String title){LinearLayout choices=column(dp(16));TextView explanation=text(sectorMap?"لأوضح أسماء الشوارع اختر PDF؛ هذه الصفحات تستخدم خط NART المضمّن. لفتح DXF في FastView احفظ الخط أولاً واتبع التعليمات العربية.":"هذا هو المخطط الكامل الجديد عالي الوضوح؛ كبّر بإصبعين لقراءة أسماء الشوارع ثم اسحب للانتقال داخل المخطط.",13,TEXT,false);explanation.setPadding(0,0,0,dp(10));choices.addView(explanation);Button pdf=smallButton(sectorMap?"عرض PDF التفصيلي - أسماء أوضح":"عرض PDF الكامل - أسماء واضحة",BLUE);LinearLayout.LayoutParams pdfParams=new LinearLayout.LayoutParams(-1,dp(52));pdfParams.setMargins(0,dp(3),0,dp(5));choices.addView(pdf,pdfParams);Button cad=smallButton("فتح ملف CAD "+(sectorMap?"DXF":"DWG"),0xff149176);LinearLayout.LayoutParams cadParams=new LinearLayout.LayoutParams(-1,dp(52));cadParams.setMargins(0,dp(3),0,dp(5));choices.addView(cad,cadParams);Button font=smallButton("شرح عربي وحفظ خط NART",0xffC9851C);LinearLayout.LayoutParams fontParams=new LinearLayout.LayoutParams(-1,dp(52));fontParams.setMargins(0,dp(3),0,dp(3));choices.addView(font,fontParams);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(choices).setNegativeButton("إلغاء",null).create();pdf.setOnClickListener(v->{dialog.dismiss();PdfMapViewer.show(this,sectorMap?R.raw.yarmouk_sector_maps:R.raw.yarmouk_camp_map,pdfPage,title);});cad.setOnClickListener(v->{dialog.dismiss();openEmbeddedCad(sectorMap);});font.setOnClickListener(v->{dialog.dismiss();showCadFontGuide();});dialog.show();}
    private void showCadFontGuide(){new AlertDialog.Builder(this).setTitle("تثبيت خط NART في FastView").setMessage("1. اضغط «حفظ الخط» أدناه واختر مجلد التنزيلات.\n\n2. افتح تطبيق FastView.\n\n3. افتح القائمة ثم «الإعدادات» (Settings).\n\n4. اختر «الخطوط» (Fonts).\n\n5. اضغط علامة + ثم اختر ملف NART.TTF من التنزيلات.\n\n6. أغلق مخطط DWG أو DXF وافتحه من جديد.\n\nملاحظة: إذا بقي اسم مشوهاً فالمشكلة من رسم CAD الأصلي؛ استخدم PDF التفصيلي للقطاع.").setPositiveButton("حفظ الخط",(d,w)->exportCadFont()).setNegativeButton("إغلاق",null).show();}
    private void openEmbeddedCad(boolean sectorMap){String asset=sectorMap?"yarmouk_sector_maps.dxf":"yarmouk_camp_map.dwg";String outputName=sectorMap?"Yarmouk_Sector_Maps.dxf":"Yarmouk_Camp_Map.dwg";String mime=sectorMap?"application/dxf":"application/vnd.dwg";try{File folder=new File(getCacheDir(),"embedded_cad");if(!folder.exists()&&!folder.mkdirs())throw new Exception("تعذر تجهيز مجلد CAD");File output=new File(folder,outputName),font=new File(folder,"NART.TTF");copyAsset(asset,output);copyAsset("NART.TTF",font);Uri uri=CadFileProvider.uriFor(this,output),fontUri=CadFileProvider.uriFor(this,font);Intent open=new Intent(Intent.ACTION_VIEW);open.setDataAndType(uri,mime);open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);ClipData clip=new ClipData("مخطط CAD وخط NART",new String[]{mime,"font/ttf"},new ClipData.Item(uri));clip.addItem(new ClipData.Item(fontUri));open.setClipData(clip);startActivity(Intent.createChooser(open,"فتح المخطط باستخدام"));}catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر فتح ملف CAD").setMessage("ثبّت تطبيقاً يدعم "+(sectorMap?"DXF":"DWG")+" ثم أعد المحاولة. يمكنك دائماً اختيار عرض PDF داخل التطبيق.\n\n"+safe(error.getMessage(),"لا يوجد عارض متوافق")).setPositiveButton("حسناً",null).show();}}
    private void copyAsset(String asset,File output)throws Exception{try(InputStream input=getAssets().open(asset);FileOutputStream stream=new FileOutputStream(output,false)){byte[]buffer=new byte[32768];int count;while((count=input.read(buffer))!=-1)stream.write(buffer,0,count);}}
    private void exportCadFont(){Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("font/ttf");intent.putExtra(Intent.EXTRA_TITLE,"NART.TTF");startActivityForResult(intent,REQUEST_CAD_FONT);}
    private void writeCadFont(Uri uri){try(InputStream input=getAssets().open("NART.TTF");OutputStream output=getContentResolver().openOutputStream(uri,"w")){if(output==null)throw new Exception("تعذر إنشاء ملف الخط");byte[]buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);new AlertDialog.Builder(this).setTitle("تم حفظ NART.TTF").setMessage("الآن افتح FastView ثم: القائمة ← الإعدادات (Settings) ← الخطوط (Fonts) ← علامة + ← اختر NART.TTF. بعد ذلك أغلق المخطط وافتحه من جديد.").setPositiveButton("حسناً",null).show();}catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر حفظ الخط").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();}}

    private void ensureSecurityAccess(){if(securityDialogVisible)return;if(!security.isEnabled()){root.setVisibility(View.VISIBLE);return;}if(!security.isConfigured()){root.setVisibility(View.INVISIBLE);showPinSetup(false);return;}if(!securityUnlocked){root.setVisibility(View.INVISIBLE);showUnlock();return;}root.setVisibility(View.VISIBLE);}
    /** Enabling protection never flips security.setEnabled(true) up front — that used to leave the app
     * permanently stuck re-opening an uncancellable setup dialog on every launch if the user backed out
     * before saving a PIN. Protection only becomes "enabled" inside showPinSetup() itself, after a PIN is
     * actually saved successfully. */
    private void togglePinProtection(){if(security.isEnabled()){security.disableAndClear();securityUnlocked=false;root.setVisibility(View.VISIBLE);toast("تم إيقاف كود الحماية");renderActive();}else{showPinSetup(false);}}
    private EditText pinInput(String hint){EditText input=new EditText(this);input.setHint(hint);input.setSingleLine(true);input.setGravity(Gravity.CENTER);input.setTextSize(20);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);input.setPadding(dp(12),0,dp(12),0);input.setBackground(round(CARD_BG,12,CARD_BORDER,1));return input;}
    /** BACK and an explicit "إلغاء" both close this dialog before anything is saved, in every case — first-time
     * setup included. Protection is only ever considered enabled after security.setPin() succeeds AND
     * security.setEnabled(true) is called here, in that order; cancelling always leaves the app in exactly the
     * state it was in before the dialog opened (also self-heals a device left "enabled but unconfigured" by an
     * older build of this dialog, which used to trap the user in an uncancellable setup loop). */
    private void showPinSetup(boolean changing){if(securityDialogVisible)return;securityDialogVisible=true;LinearLayout form=column(dp(14));EditText pin=pinInput("كود من 6 أرقام");EditText confirm=pinInput("تأكيد الكود");form.addView(pin,new LinearLayout.LayoutParams(-1,dp(54)));LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(-1,dp(54));second.setMargins(0,dp(9),0,0);form.addView(confirm,second);AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(changing?"تعيين كود حماية جديد":"حماية التطبيق").setMessage("اختر كود PIN من 6 أرقام. يُخزن كبصمة مشفرة ولا يمكن للتطبيق إظهاره أو استعادته؛ احتفظ به في مكان آمن.").setView(form).setPositiveButton("حفظ الكود",null).setNegativeButton("إلغاء",null);AlertDialog dialog=builder.create();dialog.setCancelable(true);dialog.setCanceledOnTouchOutside(false);dialog.setOnDismissListener(v->{securityDialogVisible=false;if(!changing&&!security.isConfigured()&&security.isEnabled())security.setEnabled(false);if(!securityUnlocked)root.post(this::ensureSecurityAccess);});dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(cancel->dialog.dismiss());dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save->{String first=pin.getText().toString().trim(),secondValue=confirm.getText().toString().trim();if(!AppSecurity.isValidPin(first)){pin.setError("اكتب 6 أرقام");return;}if(!first.equals(secondValue)){confirm.setError("الكودان غير متطابقين");return;}try{security.setPin(first);security.setEnabled(true);securityUnlocked=true;security.clearBackground();root.setVisibility(View.VISIBLE);dialog.dismiss();toast(changing?"تم تغيير كود الحماية":"تم تفعيل كود حماية التطبيق");}catch(Exception error){pin.setError("تعذر حفظ الكود");}});});dialog.show();}
    private void showUnlock(){if(securityDialogVisible)return;securityDialogVisible=true;LinearLayout form=column(dp(14));EditText pin=pinInput("أدخل كود الحماية");TextView status=text("",11,ROSE,true);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(7),0,0);form.addView(pin,new LinearLayout.LayoutParams(-1,dp(56)));form.addView(status);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("فتح التطبيق").setMessage("أدخل كود PIN المكوّن من 6 أرقام للوصول إلى بيانات المستفيدين.").setView(form).setPositiveButton("فتح",null).create();dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false);dialog.setOnDismissListener(v->securityDialogVisible=false);dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(open->{String value=pin.getText().toString().trim();if(!AppSecurity.isValidPin(value)){pin.setError("اكتب 6 أرقام");return;}AppSecurity.Verification result=security.verify(value);pin.setText("");if(result.success){securityUnlocked=true;security.clearBackground();root.setVisibility(View.VISIBLE);dialog.dismiss();return;}if(result.locked){long seconds=Math.max(1,(result.retryAfterMs+999)/1000);status.setText("محاولات كثيرة. حاول بعد "+seconds+" ثانية.");}else status.setText("الكود غير صحيح. المحاولات المتبقية: "+result.attemptsLeft);}));dialog.show();}
    private void changeSecurityPin(){if(securityDialogVisible)return;securityDialogVisible=true;LinearLayout form=column(dp(14));EditText pin=pinInput("الكود الحالي");TextView status=text("",11,ROSE,true);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(7),0,0);form.addView(pin,new LinearLayout.LayoutParams(-1,dp(56)));form.addView(status);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("التحقق من الكود الحالي").setView(form).setPositiveButton("متابعة",null).setNegativeButton("إلغاء",null).create();dialog.setOnDismissListener(v->securityDialogVisible=false);dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(next->{String value=pin.getText().toString().trim();if(!AppSecurity.isValidPin(value)){pin.setError("اكتب 6 أرقام");return;}AppSecurity.Verification result=security.verify(value);pin.setText("");if(result.success){dialog.dismiss();root.post(()->showPinSetup(true));}else if(result.locked){long seconds=Math.max(1,(result.retryAfterMs+999)/1000);status.setText("حاول بعد "+seconds+" ثانية.");}else status.setText("الكود غير صحيح. المحاولات المتبقية: "+result.attemptsLeft);}));dialog.show();}

    /** The engineer/researcher name stamped on records this device adds or imports — lets several engineers' contributions stay attributable inside one merged project list. */
    private String currentEngineerName(){return getPreferences(MODE_PRIVATE).getString("engineer_name","");}
    /** Today's device date as YYYY-MM-DD — the smart default for study/followup dates. */
    private String todayYmd(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
    /** A tappable read-only field that opens a date picker and stores YYYY-MM-DD; pre-filled with {@code initial}. */
    private EditText dateField(LinearLayout form,String label,String initial){
        TextView caption=text(label,12,NAVY,true);caption.setPadding(0,dp(8),0,dp(4));form.addView(caption);
        final EditText input=new EditText(this);input.setFocusable(false);input.setClickable(true);input.setText(initial==null||initial.isEmpty()?todayYmd():initial);
        input.setTextSize(14);input.setPadding(dp(12),dp(8),dp(12),dp(8));input.setBackground(round(CARD_BG,12,CARD_BORDER,1));
        input.setOnClickListener(v->{java.util.Calendar cal=java.util.Calendar.getInstance();try{String[] p=input.getText().toString().split("-");if(p.length==3)cal.set(Integer.parseInt(p[0]),Integer.parseInt(p[1])-1,Integer.parseInt(p[2]));}catch(Exception ignored){}
            new android.app.DatePickerDialog(this,(view,y,m,d)->input.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),cal.get(java.util.Calendar.YEAR),cal.get(java.util.Calendar.MONTH),cal.get(java.util.Calendar.DAY_OF_MONTH)).show();});
        form.addView(input,new LinearLayout.LayoutParams(-1,dp(50)));return input;
    }
    private void promptEngineerName(){EditText input=new EditText(this);input.setHint("مثال: م. محمد أمين عودة");input.setText(currentEngineerName());input.setSingleLine(true);new AlertDialog.Builder(this).setTitle("اسم المهندس/الباحث الحالي").setMessage("يُسجَّل مع كل مستفيد يُضاف أو يُستورد من هذا الجهاز، ليظهر بوضوح من أضاف كل سجل عند دمج قوائم عدة مهندسين.").setView(input).setPositiveButton("حفظ",(d,w)->{getPreferences(MODE_PRIVATE).edit().putString("engineer_name",input.getText().toString().trim()).apply();renderActive();}).setNegativeButton("إلغاء",null).show();}

    private void showSettings(){ScrollView scroll=new ScrollView(this);content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout box=column(dp(16));scroll.addView(box);box.addView(text("الإعدادات",21,NAVY,true));
        TextView settingsSub=text("مشروع المأوى في الأونروا • UNRWA Shelter Project",11,MUTED,false);settingsSub.setPadding(0,dp(2),0,dp(2));box.addView(settingsSub);

        box.addView(settingsSection("معلومات المشروع"));
        box.addView(setting("المشروع الحالي",currentProject,BLUE,v->newProject()));
        box.addView(setting("إنشاء مشروع أو قائمة جديدة","يحفظ كل مشروع وبياناته بصورة مستقلة",BLUE,v->newProject()));
        box.addView(setting("اسم المهندس/الباحث الحالي",currentEngineerName().isEmpty()?"غير محدد — اضغط للتعيين":currentEngineerName(),0xff7A5CB8,v->promptEngineerName()));

        box.addView(settingsSection("المظهر"));
        box.addView(setting("مظهر التطبيق",themeModeLabel(themeMode()),0xff7A5CB8,v->showThemePicker()));
        box.addView(setting("حجم الخط",fontSizeLabel()+" — لتحسين القراءة الميدانية",0xff7A5CB8,v->showFontSizePicker()));

        box.addView(settingsSection("الحماية والخصوصية"));
        box.addView(setting(security.isEnabled()?"إيقاف كود الحماية":"تفعيل كود حماية التطبيق",security.isEnabled()?"مفعّل حالياً — قفل تلقائي بعد 3 دقائق • اضغط للإيقاف":"اختياري — فعّله إذا أردت طلب PIN من 6 أرقام عند فتح التطبيق",0xffBD4868,v->togglePinProtection()));
        if(security.isEnabled())box.addView(setting("تغيير كود حماية التطبيق","PIN مشفر من 6 أرقام",0xffBD4868,v->changeSecurityPin()));

        box.addView(settingsSection("النسخ الاحتياطي والاستعادة"));
        TextView backupStatus=text(backupStatusLabel(),11,MUTED,false);backupStatus.setPadding(0,0,0,dp(4));box.addView(backupStatus);
        box.addView(setting("نسخة احتياطية JSON","تصدير الدراسات والمتابعة وجدول الكميات وفهرس الصور (بدون ملفات الصور)",0xff149176,v->createBackup()));
        box.addView(setting("نسخة احتياطية كاملة (JSON + الصور)","تشمل ملفات الصور الفعلية — احفظها على بطاقة SD أو USB أو تطبيق تخزين سحابي لحمايتها من سرقة/فقدان/حذف التطبيق",0xff149176,v->createFullBackup()));
        box.addView(setting("استيراد قائمة أو نسخة احتياطية","Excel أو CSV أو JSON من ذاكرة الهاتف",0xff0E7490,v->chooseImport()));

        box.addView(settingsSection("مجلد المشروع"));
        // PROJECT SAVE ROOT — ONE FOLDER ONLY: a SINGLE root for EVERYTHING (صور/كميات/CAD/backup/الحزمة). The
        // old separate «مجلد صور المستفيدين» row is removed — photos now save into this same root automatically.
        String pkgRoot=packageRootDisplay();
        box.addView(setting("مجلد حفظ ملفات المشروع",pkgRoot.isEmpty()?"لم يُحدَّد بعد — يُطلب مرة واحدة، ثم يُحفظ داخله كل شيء تلقائياً • اضغط للتحديد":"المسار: "+pkgRoot+" / مشروع المأوى في الأونروا / المستفيدين • كل الصور والملفات تُحفظ هنا تلقائياً • اضغط لتغيير المجلد الرئيسي",0xff149176,v->new AlertDialog.Builder(this).setTitle("تغيير المجلد الرئيسي").setMessage("سيتم حفظ الملفات الجديدة في المجلد الجديد. لن يتم نقل الملفات القديمة تلقائياً.").setPositiveButton("اختيار مجلد جديد",(d,w)->choosePackageRoot()).setNegativeButton("إلغاء",null).show()));

        box.addView(settingsSection("الدليل والمساعدة"));
        box.addView(setting("دليل استخدام التطبيق","شرح تفاعلي داخل التطبيق",0xff7A5CB8,v->showUserGuide()));
        box.addView(setting("دليل الاستخدام PDF","نسخة عربية مرفقة تعمل دون إنترنت وقابلة للطباعة",0xff0E7490,v->PdfMapViewer.show(this,R.raw.user_guide_ar,0,"دليل استخدام التطبيق")));

        box.addView(settingsSection("متقدم (اختياري)"));
        box.addView(setting("مزامنة جهات الاتصال","اختياري بالكامل — يطلب صلاحية جهات الاتصال عند أول استخدام فقط",0xff7A5CB8,v->showContactsSyncPicker()));
        box.addView(setting("مزامنة مع جهاز Windows","تصدير كل الدراسات وإصداراتها كحزمة JSON للمشاركة — لا إنترنت، تأكيد يدوي دائماً",0xff149176,v->showSyncWithWindows()));

        LinearLayout about=column(dp(16));about.setBackground(round(SURFACE_CARD,16,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(18),0,0);about.setLayoutParams(p);about.addView(text("حول التطبيق",16,NAVY,true));about.addView(text("مشروع المأوى في الأونروا\nUNRWA Shelter Project\nإصدار "+APP_VERSION+" • يعمل دون إنترنت\n\n"+DESIGNER_CREDIT+"\n\n"+ACKNOWLEDGEMENT+".\n\nمرجع القطاعات: ملف الشوارع + DXF + المخططان PDF\nالبيانات والصور تبقى محلياً على الجهاز. يسمح التطبيق بالتقاط لقطات الشاشة عند الحاجة؛ احمِ أي لقطة تحتوي بيانات شخصية.",12,MUTED,false));box.addView(about);
        // Danger zone: deliberately separated (extra top margin + its own labeled section) from the everyday
        // settings above so it is never mistaken for or brushed against a routine action.
        TextView dangerLabel=text("منطقة خطرة",13,0xffBD4868,true);LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(-1,-2);dlp.setMargins(0,dp(30),0,dp(6));dangerLabel.setLayoutParams(dlp);box.addView(dangerLabel);
        box.addView(setting("حذف بيانات المشروع الحالي","لا يحذف مجلدات الصور ولا المشاريع الأخرى",0xffBD4868,v->confirmClear()));}
    private void showUserGuide(){ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(18));scroll.addView(box);box.addView(text("دليل استخدام التطبيق",21,NAVY,true));TextView intro=text("التطبيق يعمل دون إنترنت بالكامل، ويحفظ كل مشروع وبياناته محلياً على الهاتف.",13,MUTED,false);intro.setPadding(0,dp(5),0,dp(10));box.addView(intro);Button pdfGuide=smallButton("فتح نسخة الدليل PDF",0xff0E7490);pdfGuide.setOnClickListener(v->PdfMapViewer.show(this,R.raw.user_guide_ar,0,"دليل استخدام التطبيق"));box.addView(pdfGuide,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout quickPath=column(dp(14));quickPath.setBackground(round(0xffEAF7F4,14,0xffB7DED3,1));LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,-2);qp.setMargins(0,dp(12),0,dp(6));quickPath.setLayoutParams(qp);
        quickPath.addView(text("مسار العمل السريع",16,0xff149176,true));
        TextView pathText=text("استيراد قائمة (كل ملف مضغوط = قائمة واحدة)  ←  تدقيق العناوين واقتراح القطاع  ←  دراسة المنزل: العنوان + الأضرار + الكميات + رسم المخطط + تصوير «قبل الترميم»  ←  تجهيز ملف المستفيد (مجلد تسليم كامل)  ←  اعتماد الدراسة  ←  المتابعة: تصوير أثناء/بعد الترميم ونِسب الإنجاز  ←  تسليم بنود BOQ والدفعتان  ←  الخريطة التشغيلية للتحليل. كل شيء يعمل دون إنترنت.",13,TEXT,false);pathText.setPadding(0,dp(6),0,0);quickPath.addView(pathText);
        box.addView(quickPath);
        guideStep(box,"1","المشروع والقوائم","كل مشروع مستقل تماماً ببياناته وصوره. من تبويب «المتابعة» ← «استيراد قائمة» تستورد أرشيف ZIP: كل أرشيف = قائمة واحدة (يُستنتج اسمها من اسم الملف مثل «قائمة 13»، وتقدر تعدّله)، وكل ملفات المستفيدين داخله تتبع نفس القائمة — لا يُعتبر كل ملف قائمة منفصلة. يقبل أيضاً XLSX/CSV/JSON، ويمكن بدء دراسة يدوية من «الدراسات».");
        guideStep(box,"2","تدقيق العناوين (جديد)","«استيراد عناوين القائمة» يقرأ ملف العناوين المنفصل ويربطه بالمستفيدين برقم التسجيل ثم الاسم، دون إنشاء مستفيدين وهميين. في ملف المستفيد يظهر «عنوان التقييم» مقابل «عنوان ملف العناوين» مع اقتراح القطاع الصحيح (يُشتق حتى من اسم الشارع/تجميعة البيوت). تعتمد الاقتراح أو ترفضه — بلا أي تغيير صامت، والعنوان الأصلي لا يُمسّ أبداً.");
        guideStep(box,"3","بيانات الدراسة والكميات","اكتب العنوان التفصيلي ووصف الأضرار، ثم جدول الكميات المرجعي (37 بنداً): اكتب الكمية أو استخدم الحاسبة، ويُحسب الإجمالي والدفعتان 60% و40% تلقائياً. يمكن تصدير ملف الكميات Excel وحده أيضاً.");
        guideStep(box,"4","رسم مخطط المنزل داخل التطبيق (جديد)","لم يعد يُرفق مخطط خارجي — ترسمه داخل التطبيق: أدوات جدار/غرفة/باب/نافذة/درج + معايرة المقياس. الرسم متتابع: نهاية كل جدار «لاصقة» فيلتقطها التالي، والأبعاد تظهر جانب كل جدار بوضوح، والمساحة تُحسب وتُعرض تلقائياً. الأبواب: عرض 70/80/90 أو مخصّص، ونوع (داخلي/خارجي/أكورديون/مطوي)، وتدوير اتجاه الفتح. الدرج: طول وعرض واتجاه صعود. «تحديد الشمال» يقرأ بوصلة الجوال بلا إنترنت. يُصدَّر PDF (للعرض) و DXF (يفتح في AutoCAD).");
        guideStep(box,"5","تصوير «قبل الترميم» بالكاميرا المتتابعة (جديد)","كاميرا داخل التطبيق تلتقط الصور متتالية بلا شاشة «صح/خطأ» بعد كل لقطة، وتُحفظ تلقائياً في مجلد المستفيد ضمن «قبل الترميم» — لا يسألك أين تحفظ. اختر مجلد الصور مرة واحدة فقط.");
        guideStep(box,"6","تجهيز ملف المستفيد (جديد)","زر واحد «تجهيز ملف المستفيد» يجمع كل شيء في مجلد تسليم واحد (تختار مكانه مرة واحدة) باسم «الاسم - رقم التسجيل»: 01 الكميات (Excel مع تضمين الصور المختارة في ورقة «قبل الترميم») · 02 CAD (DXF + PDF) · 03 كل صور قبل الترميم · مجلدات أثناء/بعد/الوثائق · 07 نسخة بيانات JSON. لا يستبدل أي مجلد سابق (يضيف _2/_3).");
        guideStep(box,"7","ترتيب الدراسة والاعتماد","ترتيب صفحة المستفيد واضح: البيانات ← الكميات ← الصور ← الرسم ← التجهيز ← الاعتماد. تمر الدراسة بأربع حالات بالترتيب: مسودة ← جاهزة للمراجعة ← مراجعة مكتبية ← معتمدة. بعد الاعتماد، للتصحيح استخدم «إصدار نسخة مصححة» بدل التعديل المباشر — تبقى النسخة السابقة سجلاً تاريخياً كاملاً للقراءة فقط.");
        guideStep(box,"8","المتابعة والصور","افتح المستفيد من «المتابعة»، سجّل نسبة الإنجاز والحالة (قيد التنفيذ / مكتمل / بحاجة لمراجعة / تعذر الوصول)، وصوّر «أثناء الترميم» و«بعد الترميم» بنفس الكاميرا المتتابعة.");
        guideStep(box,"9","تسليم بنود BOQ وعدّاد الدفعة الأولى","من «تسليم أعمال BOQ» غيّر حالة كل بند مع ملاحظة اختيارية. يبدأ عدّاد المرحلة الأولى (35 يوماً) فقط بعد «تأكيد تسليم الأعمال» و«تأكيد توفر الدفعة الأولى» معاً، ولا يعني انتهاؤه صرف أي دفعة تلقائياً — القرار يدوي دائماً.");
        guideStep(box,"10","اعتماد الدفعة الثانية","بعد مراجعة الإنجاز والصور، اضغط «اعتماد للدفعة الثانية» بعد الاطلاع على الملخص. يظهر المستفيد ضمن «جاهزون لرفع الدفعة الثانية»، ويمكن إلغاء الاعتماد لاحقاً مع حفظ أثر القرار كاملاً.");
        guideStep(box,"11","تثبيت موقع المنزل (GPS)","من ملف الدراسة «تثبيت موقع المنزل» يلتقط إحداثيات حقيقية دون إنترنت. عند دقة أضعف من ~30م يظهر تحذير مع خيار الإعادة أو القبول يدوياً. تحديث موقع محفوظ يطلب تأكيداً صريحاً ولا يُستبدل بصمت.");
        guideStep(box,"12","الخريطة التشغيلية الاحترافية (محدّثة)","من «المتابعة» ← «الخريطة». الخريطة الآن تفاعلية بالكامل: باعِد إصبعين للتكبير، اسحب للتحريك، نقر مزدوج للتكبير/الإرجاع، وزر «ملاءمة الشاشة». اضغط بطاقة قطاع لتكبيره تلقائياً. أعلى الخريطة شريط ملخص وبطاقات توزيع لكل قطاع تعكس القائمة الحالية، وأسماء القطاعات كبيرة وواضحة. الضغط على قطاع يعرض إحصائيته، وعلى مستفيد يفتح ملفه.");
        guideStep(box,"13","خلفيات الخريطة والقمر الصناعي (جديد)","زر «الخلفية» يبدّل بين ثلاث خلفيات: الخريطة المعايرة · المخطط التنظيمي الواضح (أسماء الشوارع) · صورة قمر صناعي تستوردها أنت من «استيراد صورة القمر الصناعي» (بلا إنترنت — التطبيق لا يحمّل خرائط). يمكن عرض المخطط فوق القمر الصناعي بشفافية لرؤية الأسطح والأسماء معاً. لكل خلفية معايرتها الخاصة (3 نقاط GPS)، فتظهر البيوت في مواقعها الحقيقية.");
        guideStep(box,"14","خطط جولتي والإحصائية الجغرافية","«خطط جولتي» يرتب زيارات من لديهم GPS حقيقي حسب الأقرب، ويعرض من بلا موقع في قسم منفصل. «إحصائية جغرافية» (من الدراسات) تعرض عدد المستفيدين في كل قطاع لقائمة واحدة أو للكل، والضغط على أي عدد يفتح المطابقين مباشرة.");
        guideStep(box,"15","النسخ الاحتياطي وحماية البيانات","كل شيء محلي على الجهاز ويعمل دون إنترنت. احفظ نسخة JSON دورياً (تتضمن الدراسات والمتابعة والكميات وفهرس الصور) وانسخ مجلد الصور. عيّن PIN من الإعدادات إن رغبت؛ يُقفل التطبيق بعد 3 دقائق في الخلفية. احفظ أي لقطة شاشة تتضمن بيانات شخصية بمكان آمن.");
        AlertDialog dialog=new AlertDialog.Builder(this).setView(scroll).setPositiveButton("إغلاق",null).create();dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.96),(int)(getResources().getDisplayMetrics().heightPixels*.90)));dialog.show();}
    private void guideStep(LinearLayout box,String number,String title,String body){LinearLayout card=column(dp(14));card.setBackground(round(SURFACE_CARD,15,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(p);card.addView(text(number+". "+title,15,0xff0E7490,true));TextView detail=text(body,12,TEXT,false);detail.setPadding(0,dp(5),0,0);card.addView(detail);box.addView(card);}
    private View settingsSection(String title){TextView label=text(title,13,0xff0E7490,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(20),0,dp(2));label.setLayoutParams(lp);return label;}
    private View setting(String title,String sub,int color,View.OnClickListener click){LinearLayout card=column(dp(14));card.setMinimumHeight(dp(88));card.setBackground(round(SURFACE_CARD,15,CARD_BORDER,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(p);card.addView(text(title,15,color,true));card.addView(text(sub,11,MUTED,false));card.setOnClickListener(click);return card;}
    private void newProject(){EditText input=new EditText(this);input.setHint("مثال: قائمة آب 2026");input.setSingleLine(true);input.setPadding(dp(16),0,dp(16),0);new AlertDialog.Builder(this).setTitle("مشروع جديد").setMessage("سيظهر له قسم مستقل داخل التطبيق.").setView(input).setPositiveButton("إنشاء",(d,w)->{String name=input.getText().toString().trim();if(name.isEmpty()){toast("اكتب اسم المشروع");return;}currentProject=name;currentProjectId=db.ensureProject(name);getPreferences(MODE_PRIVATE).edit().putString("current_project",name).apply();refreshProjects();renderActive();}).setNegativeButton("إلغاء",null).show();}
    private void confirmClear(){new AlertDialog.Builder(this).setTitle("حذف بيانات المشروع؟").setMessage("سيتم حذف جميع المستفيدين وحالات الزيارة وفهرس الصور من «"+currentProject+"». لن تُحذف ملفات الصور من المجلد المختار. يفضّل أخذ نسخة احتياطية أولاً.").setPositiveButton("حذف",(d,w)->{db.deleteProjectData(currentProjectId);renderActive();toast("تم حذف بيانات المشروع وبقيت ملفات الصور في مجلدها");}).setNegativeButton("إلغاء",null).show();}

    @Override public void onRequestPermissionsResult(int requestCode,String[]permissions,int[]grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQUEST_LOCATION_PERMISSION){
            long beneficiaryId=pendingGpsBeneficiaryId;pendingGpsBeneficiaryId=0;
            boolean granted=grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED;
            if(!granted){toast("تم رفض صلاحية الموقع — يمكنك متابعة استخدام التطبيق بشكل طبيعي، وتفعيلها لاحقاً من إعدادات الجهاز عند الحاجة لتثبيت الموقع");return;}
            AppDatabase.Beneficiary b=beneficiaryId>0?db.beneficiary(beneficiaryId):null;
            if(b!=null)chooseCaptureMode(b);
        }
        if(requestCode==REQUEST_CONTACTS_PERMISSION){
            int mode=pendingContactsSyncMode;long beneficiaryId=pendingContactsSyncBeneficiaryId;String batch=pendingContactsSyncBatch;
            pendingContactsSyncMode=0;pendingContactsSyncBeneficiaryId=0;pendingContactsSyncBatch="";
            boolean granted=grantResults.length>=2&&grantResults[0]==PackageManager.PERMISSION_GRANTED&&grantResults[1]==PackageManager.PERMISSION_GRANTED;
            if(!granted){toast("تم رفض صلاحية جهات الاتصال — يمكنك متابعة استخدام التطبيق بشكل طبيعي، وتفعيلها لاحقاً من إعدادات الجهاز عند الحاجة للمزامنة");return;}
            if(mode==1){AppDatabase.Beneficiary b=beneficiaryId>0?db.beneficiary(beneficiaryId):null;if(b!=null)syncBeneficiaryContact(b);}
            else if(mode==2)syncBatchContacts(batch);
        }
        if(requestCode==REQUEST_CAMERA_PERMISSION){
            long id=pendingCameraBenId;String ph=pendingCameraPhase;pendingCameraBenId=0;pendingCameraPhase="";
            boolean granted=grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED;
            if(!granted){toast("تم رفض صلاحية الكاميرا — يمكنك تفعيلها لاحقاً من إعدادات الجهاز عند الحاجة للتصوير");return;}
            AppDatabase.Beneficiary b=id>0?db.beneficiary(id):null;if(b!=null)startPhotoCapture(b,ph);
        }
    }

    private void chooseImport(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","application/json","text/plain","application/zip","application/x-zip-compressed"});startActivityForResult(intent,REQUEST_IMPORT);}

    // ---- Satellite background: the engineer supplies their OWN high-resolution satellite image of the camp
    // (UNRWA GIS / an authorised provider). The app never downloads imagery — it works offline and must not
    // bulk-fetch or cache third-party map tiles. Once imported it is calibrated with 3 GPS points like any
    // other background, so beneficiary markers land on the real rooftops. ----
    private String currentMapKey=AppDatabase.MAP_CALIBRATED; // which background is active (calibration is per-map)
    private java.io.File satelliteFile(){ return new java.io.File(getFilesDir(),"satellite_map.jpg"); }
    /** Load the background bitmap for a map key (calibrated raster / official plan / imported satellite). */
    private Bitmap loadMapBackground(String key){
        try{
            if(AppDatabase.MAP_PLAN.equals(key))return BitmapFactory.decodeResource(getResources(),computeResource("yarmouk_camp_plan","drawable"));
            if(AppDatabase.MAP_SATELLITE.equals(key)&&satelliteFile().exists())return BitmapFactory.decodeFile(satelliteFile().getAbsolutePath());
        }catch(Exception ignored){}
        return BitmapFactory.decodeResource(getResources(),R.drawable.yarmouk_camp_calibrated);
    }
    private void chooseSatelliteImage(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");
        startActivityForResult(i,REQUEST_SATELLITE_IMAGE);
    }
    private void importSatelliteImage(android.net.Uri uri){
        try{
            java.io.File out=satelliteFile();
            try(java.io.InputStream in=getContentResolver().openInputStream(uri);java.io.OutputStream os=new java.io.FileOutputStream(out)){
                if(in==null)throw new Exception("تعذر فتح الصورة");
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)os.write(buf,0,n);
            }
            android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(out.getAbsolutePath(),opt);
            new AlertDialog.Builder(this).setTitle("✓ الخطوة ① تمت — تم استيراد صورة القمر الصناعي")
                .setMessage("الأبعاد: "+opt.outWidth+"×"+opt.outHeight+" بكسل\n\nالخطوة التالية (②): أغلق هذه النافذة وأعد فتح «الخريطة التشغيلية»، ثم من «⚙ خيارات متقدمة» اضغط «② معايرة صورة القمر الصناعي» وأضف 3 نقاط GPS حقيقية على الأقل.\n\nبعدها (③) اختر «🛰 القمر الصناعي» من الأزرار الثلاثة أعلى الخريطة لتفعيلها.")
                .setPositiveButton("حسناً",null).show();
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر استيراد الصورة").setMessage(String.valueOf(e.getMessage())).setPositiveButton("حسناً",null).show();}
    }

    /** المطلوب 2 — pick a SEPARATE «العناوين» reference file (xlsx/csv) and reconcile it against the imported list. */
    private void chooseAddressReference(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","application/json","text/plain"});startActivityForResult(intent,REQUEST_ADDRESS_REF);}
    private String digitsOnly(String s){return s==null?"":s.replaceAll("[^0-9]","");}
    /** Parses the «العناوين» file (name/registration/phone/sector=المنطقة حسب المخطط/address=العنوان التفصيلي) and
     * stores each row on the MATCHED beneficiary (registration first, then exact name) WITHOUT touching their
     * assessment address — the beneficiary file then shows both + a reviewable suggestion. Never creates rows. */
    private void importAddressReference(android.net.Uri uri){
        ProgressDialog progress=new ProgressDialog(this);progress.setMessage("جارٍ قراءة ملف العناوين ومطابقته…");progress.setCancelable(false);progress.show();
        worker.execute(()->{
            try{
                SpreadsheetImporter.Result result=SpreadsheetImporter.read(this,uri);
                int[] matched={0},reg={0},nameM={0},unmatched={0};
                for(AppDatabase.Beneficiary ref:result.rows){
                    String addr=ref.address==null?"":ref.address.trim(),sector=ref.sector==null?"":ref.sector.trim();
                    if(addr.isEmpty()&&sector.isEmpty())continue; // an empty row / summary line — skip, never store
                    AppDatabase.Beneficiary b=null;
                    String rd=digitsOnly(ref.registration);
                    if(!rd.isEmpty()){b=db.beneficiaryByRegistration(currentProjectId,rd);if(b!=null)reg[0]++;}
                    if(b==null&&ref.name!=null&&!ref.name.trim().isEmpty()){b=db.beneficiaryByName(currentProjectId,ref.name);if(b!=null)nameM[0]++;}
                    if(b==null){unmatched[0]++;continue;}
                    db.setAddressReference(b.id,addr,sector);matched[0]++;
                }
                runOnUiThread(()->{progress.dismiss();
                    new AlertDialog.Builder(this).setTitle("مطابقة العناوين")
                        .setMessage("صفوف ملف العناوين: "+result.rows.size()+"\nطُوبقت: "+matched[0]+"  (بالتسجيل "+reg[0]+" • بالاسم "+nameM[0]+")\nبلا مطابقة: "+unmatched[0]+"\n\nافتح ملف المستفيد لرؤية «عنوان ملف العناوين» والاقتراح، ثم اعتماده أو رفضه. لم يُغيَّر أي عنوان أو قطاع تلقائياً.")
                        .setPositiveButton("حسناً",(d,w)->renderActive()).show();
                });
            }catch(Exception e){runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("تعذر قراءة ملف العناوين").setMessage(friendlyImportError(e)).setPositiveButton("حسناً",null).show();});}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==REQUEST_SEQ_CAMERA){handleSeqCapture(result,data);return;}
        if(request==REQUEST_SYNC_SHARE){
            // Cancelling the share chooser (nothing picked) must never be recorded as a sync — and picking
            // some target app is only ever a best-effort "handed off" signal, never confirmed receipt (see
            // ShareIntentDesktopAdapter's own note). study_status is never touched either way.
            List<String>uuids=pendingSyncRevisionUuids;pendingSyncRevisionUuids=new ArrayList<>();
            if(result==RESULT_CANCELED){toast("تم إلغاء المشاركة — لم يتغيّر أي شيء محلياً");return;}
            db.markRevisionsSynced(uuids);toast("تم تصدير "+uuids.size()+" سجلاً (بانتظار استلامها على جهاز Windows)");
            return;
        }
        if(result!=RESULT_OK){if(request==REQUEST_PHOTO_ROOT){deferredPhotoBeneficiaryId=0;deferredPhotoPhase="";}if(request==REQUEST_HOUSE_PLAN)pendingPlanBeneficiaryId=0;return;}if(data==null||data.getData()==null)return;Uri uri=data.getData();if(request==REQUEST_IMPORT){if(ZipImportHelper.looksLikeZip(SpreadsheetImporter.queryName(this,uri)))importZipUri(uri);else importUri(uri);}else if(request==REQUEST_ADDRESS_REF)importAddressReference(uri);else if(request==REQUEST_SATELLITE_IMAGE)importSatelliteImage(uri);else if(request==REQUEST_BACKUP)writeBackup(uri);else if(request==REQUEST_FULL_BACKUP)writeFullBackup(uri);else if(request==REQUEST_CAD_FONT)writeCadFont(uri);else if(request==REQUEST_HOUSE_PLAN){long beneficiaryId=pendingPlanBeneficiaryId;pendingPlanBeneficiaryId=0;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}db.setDrawingUri(beneficiaryId,uri.toString());if(beneficiaryDialog!=null)beneficiaryDialog.dismiss();AppDatabase.Beneficiary fresh=db.beneficiary(beneficiaryId);if(fresh!=null)showBeneficiary(fresh);toast("تم ربط مخطط المنزل بالدراسة");}else if(request==REQUEST_PACKAGE_ROOT){try{int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getContentResolver().takePersistableUriPermission(uri,flags);getSharedPreferences("package_export",MODE_PRIVATE).edit().putString("root_uri",uri.toString()).apply();toast("تم اعتماد مجلد المشروع — سيُحفظ داخله كل شيء تلقائياً");
            // PROJECT SAVE ROOT: resume whatever the user was doing (start study / capture photos / prepare package)
            // via the single pendingRootAction. The legacy pendingPackageBenId path stays as a fallback.
            Runnable act=pendingRootAction;pendingRootAction=null;
            if(act!=null){act.run();}
            else{long id=pendingPackageBenId;pendingPackageBenId=0;AppDatabase.Beneficiary pb=db.beneficiary(id);if(pb!=null)showBeforePhotoPicker(pb);else renderActive();}
        }catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر اعتماد المجلد").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();}}
        else if(request==REQUEST_PHOTO_ROOT){try{photoManager.setRoot(uri,data.getFlags());toast("تم اعتماد مجلد الصور: "+photoManager.rootName());long beneficiaryId=deferredPhotoBeneficiaryId;String phase=deferredPhotoPhase;deferredPhotoBeneficiaryId=0;deferredPhotoPhase="";AppDatabase.Beneficiary b=db.beneficiary(beneficiaryId);if(b!=null&&!phase.isEmpty())startPhotoCapture(b,phase);else renderActive();}catch(Exception error){new AlertDialog.Builder(this).setTitle("تعذر اعتماد المجلد").setMessage(error.getMessage()).setPositiveButton("حسناً",null).show();}}}
    /** ZIP is only ever a container: extract the supported files it holds, let the user pick which one to
     * import, then hand that single extracted file straight to importUri() — the exact same parser/importer
     * a plain .xlsx/.csv/.json pick would use, never a separate import path. */
    private void importZipUri(Uri zipUri){
        ProgressDialog progress=new ProgressDialog(this);progress.setMessage("جارٍ فحص الملف المضغوط…");progress.setCancelable(false);progress.show();
        worker.execute(()->{
            ZipImportHelper.ExtractResult extracted;
            try{extracted=ZipImportHelper.extract(this,zipUri);}
            catch(Exception error){runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("تعذر فتح الملف المضغوط").setMessage(error.getMessage()==null?error.toString():error.getMessage()).setPositiveButton("حسناً",null).show();});return;}
            runOnUiThread(()->{progress.dismiss();
                if(extracted.files.isEmpty()){
                    ZipImportHelper.cleanup(extracted.extractDir);
                    String skippedNote=extracted.skipped.isEmpty()?"":("\n\nتم تجاهل:\n"+join(extracted.skipped));
                    new AlertDialog.Builder(this).setTitle("لا توجد ملفات مدعومة").setMessage("لم يُعثر داخل الأرشيف على أي ملف Excel أو CSV أو JSON مدعوم."+skippedNote).setPositiveButton("حسناً",null).show();
                    return;
                }
                showZipContentsPicker(extracted,SpreadsheetImporter.queryName(this,zipUri));
            });
        });
    }

    /** A ZIP is ONE list (import batch): its filename names the list. «قائمة 13» / «list_13» / «batch-13» →
     * «قائمة 13»; otherwise the cleaned filename. The engineer can still edit it before importing. */
    private String inferListName(String zipName){
        if(zipName==null)return "";
        String n=zipName.replaceAll("(?i)\\.zip$","").trim();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:قائمة|list|batch)[ _\\-]*([0-9]{1,4})").matcher(n);
        if(m.find())return "قائمة "+m.group(1);
        return n.isEmpty()?"":("قائمة: "+n);
    }

    /** Confirms the ONE list name for the whole ZIP before importing (pre-filled from the ZIP filename), then runs
     * the batch under that single followup_batch — every file in the archive belongs to the same list. */
    private void promptListNameThenImport(ZipImportHelper.ExtractResult extracted,List<ZipImportHelper.ExtractedFile> files,String zipName){
        final EditText in=new EditText(this);in.setText(inferListName(zipName));in.setHint("مثال: قائمة 13");in.setPadding(dp(16),dp(12),dp(16),dp(12));
        LinearLayout box=column(dp(10));box.setPadding(dp(20),dp(14),dp(20),dp(4));
        box.addView(text("سيتم استيراد "+files.size()+" ملفاً ضمن قائمة واحدة. أكّد اسم/رقم القائمة:",13,MUTED,false));
        box.addView(in);
        new AlertDialog.Builder(this).setTitle("اسم القائمة (دفعة الاستيراد)").setView(box)
            .setPositiveButton("استيراد",(d,w)->{
                String name=in.getText().toString().trim();if(name.isEmpty())name=inferListName(zipName);if(name.isEmpty())name="قائمة "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());
                importZipFilesBatch(extracted,files,name);
            })
            .setNegativeButton("إلغاء",(d,w)->ZipImportHelper.cleanup(extracted.extractDir))
            .setOnCancelListener(d->ZipImportHelper.cleanup(extracted.extractDir))
            .show();
    }

    /** Summary + choice screen for a ZIP's supported contents. Offers importing ALL supported files, or
     * hand-picking a subset — never silently importing just the first file (the old behaviour, which lost 49
     * of 50 lists in a real archive). A single supported file still works exactly as before. */
    private void showZipContentsPicker(ZipImportHelper.ExtractResult extracted,String zipName){
        int count=extracted.files.size();
        long totalBytes=0;for(ZipImportHelper.ExtractedFile f:extracted.files)totalBytes+=f.size;
        StringBuilder names=new StringBuilder();
        int preview=Math.min(count,8);
        for(int i=0;i<preview;i++)names.append("\n• ").append(extracted.files.get(i).originalName);
        if(count>preview)names.append("\n… و").append(count-preview).append(" ملفاً آخر");
        String summary="القائمة (من اسم الأرشيف): "+inferListName(zipName)+
                "\nكل الملفات داخل هذا الأرشيف تُستورد تحت هذه القائمة الواحدة."+
                "\n\nالملفات المدعومة داخل الأرشيف: "+count+" (xlsx / xls / csv / json)"+
                (extracted.skipped.isEmpty()?"":"\nعناصر غير مدعومة سيتم تجاهلها: "+extracted.skipped.size())+
                "\nالحجم التقريبي بعد الاستخراج: "+(totalBytes/1024)+" ك.ب"+
                "\n\nالملفات:"+names;
        if(count==1){
            new AlertDialog.Builder(this).setTitle("محتوى الأرشيف المضغوط").setMessage(summary)
                    .setPositiveButton("استيراد",(d,w)->promptListNameThenImport(extracted,new ArrayList<>(extracted.files),zipName))
                    .setNegativeButton("إلغاء",(d,w)->ZipImportHelper.cleanup(extracted.extractDir))
                    .setOnCancelListener(d->ZipImportHelper.cleanup(extracted.extractDir))
                    .show();
            return;
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("محتوى الأرشيف المضغوط").setMessage(summary)
                .setPositiveButton("استيراد كل الملفات ("+count+")",(d,w)->promptListNameThenImport(extracted,new ArrayList<>(extracted.files),zipName))
                .setNeutralButton("اختيار ملفات محددة",(d,w)->showZipFileSelector(extracted,zipName))
                .setNegativeButton("إلغاء",(d,w)->ZipImportHelper.cleanup(extracted.extractDir))
                .create();
        dialog.setOnCancelListener(d->ZipImportHelper.cleanup(extracted.extractDir));
        dialog.show();
    }

    /** Multi-select list to hand-pick which extracted files to import (all checked by default). */
    private void showZipFileSelector(ZipImportHelper.ExtractResult extracted,String zipName){
        int count=extracted.files.size();
        String[] labels=new String[count];boolean[] checked=new boolean[count];
        for(int i=0;i<count;i++){ZipImportHelper.ExtractedFile f=extracted.files.get(i);labels[i]=f.originalName+"  ("+(f.size/1024)+" ك.ب)";checked[i]=true;}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("اختر الملفات المطلوب استيرادها")
                .setMultiChoiceItems(labels,checked,(d,which,isChecked)->checked[which]=isChecked)
                .setPositiveButton("استيراد المحدد",(d,w)->{
                    List<ZipImportHelper.ExtractedFile> chosen=new ArrayList<>();
                    for(int i=0;i<count;i++)if(checked[i])chosen.add(extracted.files.get(i));
                    if(chosen.isEmpty()){toast("لم تختر أي ملف");ZipImportHelper.cleanup(extracted.extractDir);return;}
                    promptListNameThenImport(extracted,chosen,zipName);
                })
                .setNegativeButton("إلغاء",(d,w)->ZipImportHelper.cleanup(extracted.extractDir))
                .create();
        dialog.setOnCancelListener(d->ZipImportHelper.cleanup(extracted.extractDir));
        dialog.show();
    }

    /** Imports the chosen extracted files one after another through the SAME SpreadsheetImporter.read +
     * classifyImportedRows + db.importBeneficiaries pipeline the interactive single-file path uses — no new
     * parser, no per-file confirmation dialog (that would be unusable across 50 files). Always APPENDS
     * (never replace) so a batch can't wipe the project. A single file failing is logged with its name and
     * reason and the batch continues; at the end a summary reports succeeded/failed/ignored counts and the
     * total beneficiaries imported. The extraction dir is cleaned up when the whole batch finishes. */
    private void importZipFilesBatch(ZipImportHelper.ExtractResult extracted,List<ZipImportHelper.ExtractedFile> files,String listName){
        ProgressDialog progress=new ProgressDialog(this);progress.setMessage("جارٍ التحضير…");progress.setCancelable(false);progress.setMax(files.size());progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);progress.show();
        worker.execute(()->{
            // F1 — the «العناوين» address list is NOT a beneficiary file; it is the AUTHORITATIVE address source,
            // matched onto the eval files by registration (then exact name). Pull it out of the batch first, and
            // never import it (or Excel «~$» lock files) as beneficiaries.
            ZipImportHelper.ExtractedFile addressFile=findAddressListFile(files);
            AddressList addressList=null;
            if(addressFile!=null){try{addressList=AddressList.parse(SpreadsheetImporter.read(this,Uri.fromFile(addressFile.file)));}catch(Exception ignored){}}
            final AddressList addr=addressList;
            List<ZipImportHelper.ExtractedFile> evalFiles=new ArrayList<>();
            for(ZipImportHelper.ExtractedFile f:files)if(f!=addressFile)evalFiles.add(f);
            int[] ok={0},failed={0},beneficiaries={0},addrMatched={0},addrFromEval={0},addrMissing={0},refPhotos={0},docPhotos={0};
            final int evalCount=evalFiles.size();
            List<String> failures=new ArrayList<>();
            for(int i=0;i<evalFiles.size();i++){
                ZipImportHelper.ExtractedFile f=evalFiles.get(i);
                final int position=i+1;final int total=evalFiles.size();
                runOnUiThread(()->{progress.setMax(total);progress.setMessage("جارٍ استيراد:\n"+f.originalName+"\n("+position+" / "+total+")");progress.setProgress(position-1);});
                try{
                    SpreadsheetImporter.Result result=SpreadsheetImporter.read(this,Uri.fromFile(f.file));
                    // The WHOLE zip is ONE list: every file's rows carry the SAME followup_batch (listName). The inner
                    // file name is kept only as an audit source in notes — never as a separate list. F1: no fuzzy
                    // cross-record guessing — the address comes from the «العناوين» list below.
                    classifyImportedRows(result,listName,false);
                    // The ZIP list name is AUTHORITATIVE — force it onto every row, overriding any per-sheet/per-file
                    // batch tag the parser may have inferred (e.g. from an internal sheet named «قائمة»). The inner
                    // file name is preserved ONLY as an audit source in notes, never as a separate list.
                    for(AppDatabase.Beneficiary b:result.rows){
                        b.followupBatch=listName;String src="المصدر: "+f.originalName;b.notes=(b.notes==null||b.notes.isEmpty())?src:(b.notes+" | "+src);
                        // F1: take the address authoritatively from the «العناوين» list — same place → merge, clear conflict → list wins.
                        reconcileAddressFromList(b,addr,addrMatched,addrFromEval,addrMissing);
                    }
                    if(result.rows.isEmpty()){failed[0]++;failures.add(f.originalName+": لا توجد سجلات قابلة للاستيراد");}
                    else{db.importBeneficiaries(currentProjectId,result.rows,false);ok[0]++;beneficiaries[0]+=result.rows.size();
                        // Photo reference: pull any «صور قبل الترميم» embedded in this beneficiary's Excel and link
                        // them as read-only reference photos (does NOT fail the import if there are none/errors).
                        try{int[] ic=importReferencePhotos(f.file,result.rows);refPhotos[0]+=ic[0];docPhotos[0]+=ic[1];}catch(Exception ignored){}}
                }catch(Exception e){failed[0]++;failures.add(f.originalName+": "+friendlyImportError(e));}
            }
            ZipImportHelper.cleanup(extracted.extractDir);
            final boolean hadList=addressFile!=null;final String addrName=addressFile==null?"":addressFile.originalName;
            runOnUiThread(()->{
                progress.dismiss();
                StringBuilder msg=new StringBuilder();
                msg.append("القائمة: ").append(listName).append("\n\n");
                msg.append("تم استيراد ").append(beneficiaries[0]).append(" مستفيد ضمن «").append(listName).append("»\n");
                msg.append("ملفات التقييم: ").append(evalCount).append(" (نجح ").append(ok[0]).append(" • فشل ").append(failed[0]).append(")\n");
                if(hadList)msg.append("ملف العناوين: تم العثور عليه «").append(lastName(addrName)).append("»\n").append("طُوبق ").append(addrMatched[0]).append(" • من التقييم ").append(addrFromEval[0]).append(" • بلا عنوان ").append(addrMissing[0]).append("\n");
                else msg.append("ملف العناوين: لم يتم العثور على ملف عناوين داخل ZIP — تم استخدام عناوين التقييم عند توفرها\n");
                msg.append("صور قبل الترميم المرجعية: ").append(refPhotos[0]).append(" • الوثائق المستوردة: ").append(docPhotos[0]).append("\n");
                msg.append("الملفات غير المدعومة: ").append(extracted.skipped.size()).append(" عنصر");
                if(!failures.isEmpty()){
                    msg.append("\n\nالملفات التي فشلت:");
                    int shown=Math.min(failures.size(),10);
                    for(int i=0;i<shown;i++)msg.append("\n• ").append(failures.get(i));
                    if(failures.size()>shown)msg.append("\n… و").append(failures.size()-shown).append(" ملفاً آخر");
                }
                new AlertDialog.Builder(this).setTitle("ملخص الاستيراد").setMessage(msg.toString())
                        .setPositiveButton("حسناً",(d,w)->showPage(NAV_HOME)).show();
            });
        });
    }

    /** Photo Reference (B1): copy the «قبل الترميم» images EMBEDDED in a beneficiary's imported Excel into the
     * app's private files (UNCHANGED bytes) and register them under the phase «before_reference», so they show in
     * the file as read-only reference photos. Never overwrites, never touches the captured BEFORE/DURING/AFTER
     * sets, and a file with no embedded images is simply a no-op. */
    /** Returns {beforeReferenceCount, importedDocumentsCount} imported for this file (0/0 if none). */
    private int[] importReferencePhotos(java.io.File xlsx,List<AppDatabase.Beneficiary> rows){
        // F2: classify the embedded images by their SHEET so «صور قبل الترميم» become reference photos while
        // «الوثائق» become imported documents — instead of lumping every image (incl. form logos) as before-reference.
        java.util.Map<String,List<EmbeddedImageExtractor.Img>> bySheet=EmbeddedImageExtractor.fromXlsxBySheet(xlsx);
        if(bySheet.isEmpty()||rows==null)return new int[]{0,0};
        List<EmbeddedImageExtractor.Img> before=new ArrayList<>(),docs=new ArrayList<>();
        for(java.util.Map.Entry<String,List<EmbeddedImageExtractor.Img>> e:bySheet.entrySet()){
            String n=AddressClassifier.normalize(e.getKey());
            if(n.contains("قبل الترميم"))before.addAll(e.getValue());
            else if(n.contains("وثائق")||n.contains("الوثائق"))docs.addAll(e.getValue());
            // التقييم logos, المخطط (emf), «بعد الترميم», «نسبة الإنجاز» → not imported here.
        }
        if(before.isEmpty()&&docs.isEmpty())return new int[]{0,0};
        for(AppDatabase.Beneficiary b:rows){
            if(b==null||b.id<=0)continue;
            saveImportedImages(b.id,before,BeneficiaryPhotoManager.BEFORE_REFERENCE,"ref","مرجعية_","صورة قبل الترميم مستوردة من قائمة المتابعة");
            saveImportedImages(b.id,docs,BeneficiaryPhotoManager.IMPORTED_DOCUMENTS,"doc","وثيقة_","وثيقة مستوردة من ملف المتابعة");
        }
        return new int[]{before.size(),docs.size()};
    }
    /** F2: write a set of imported images to the beneficiary's private folder under a given phase (before_reference
     * or imported_documents). A single unreadable image never blocks the rest.
     * UI POLISH FINAL: re-importing the same followup ZIP/list (a common field workflow — "استيراد قائمة متابعة"
     * run twice for the same beneficiary) used to add a fresh duplicate DB photo row + file every time, since the
     * old code restarted its filename counter at 1 and silently overwrote the previous file while still inserting
     * a new record. Now each incoming image's bytes are fingerprinted (CRC32 + length) against every already-
     * imported photo in this exact phase for this beneficiary; an exact match is skipped entirely (no new file,
     * no new DB row). Genuinely new images get a collision-proof filename (timestamp-based) so they can never
     * clobber an existing file on disk. The original imported bytes are never modified either way. */
    private void saveImportedImages(long benId,List<EmbeddedImageExtractor.Img> imgs,String phase,String filePrefix,String namePrefix,String note){
        if(imgs==null||imgs.isEmpty())return;
        java.io.File dir=new java.io.File(getFilesDir(),"ref_photos/"+benId);dir.mkdirs();
        List<AppDatabase.Photo> existing=db.photos(benId,phase);
        java.util.Set<Long> existingFingerprints=new java.util.HashSet<>();
        for(AppDatabase.Photo p:existing){
            try{
                Uri u=Uri.parse(p.uri);java.io.File f=new java.io.File(u.getPath());
                if(f.exists())existingFingerprints.add(fileFingerprint(f));
            }catch(Exception ignored){}
        }
        int n=1;
        for(EmbeddedImageExtractor.Img img:imgs){
            try{
                long fp=bytesFingerprint(img.bytes);
                if(existingFingerprints.contains(fp))continue; // already imported for this beneficiary/phase — skip duplicate
                String ext=img.name.toLowerCase(Locale.US).endsWith(".png")?"png":"jpg";
                java.io.File out=new java.io.File(dir,String.format(Locale.US,"%s_%d_%02d.%s",filePrefix,System.currentTimeMillis(),n,ext));
                try(java.io.FileOutputStream fo=new java.io.FileOutputStream(out)){fo.write(img.bytes);}
                db.addPhoto(benId,phase,Uri.fromFile(out).toString(),namePrefix+img.name,System.currentTimeMillis(),note);
                existingFingerprints.add(fp);
                n++;
            }catch(Exception ignored){}
        }
    }
    /** CRC32 + length — cheap, sufficient to catch "same bytes re-imported", never mutates the source. */
    private long bytesFingerprint(byte[] bytes){java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(bytes);return crc.getValue()^((long)bytes.length<<32);}
    private long fileFingerprint(java.io.File f)throws java.io.IOException{
        try(java.io.FileInputStream fis=new java.io.FileInputStream(f)){
            java.io.ByteArrayOutputStream bos=new java.io.ByteArrayOutputStream();byte[] buf=new byte[8192];int r;
            while((r=fis.read(buf))>0)bos.write(buf,0,r);
            return bytesFingerprint(bos.toByteArray());
        }
    }

    // ===== F1: «العناوين» address list reconciliation for the followup ZIP import ==========================
    private static String lastName(String path){if(path==null)return "";int s=Math.max(path.lastIndexOf('/'),path.lastIndexOf('\\'));return s>=0?path.substring(s+1):path;}
    /** F1: find the «العناوين» address list inside a followup ZIP (by file name). It is the authoritative address
     * source and must NOT be imported as beneficiaries. Returns null if the archive carries no such list. */
    private ZipImportHelper.ExtractedFile findAddressListFile(List<ZipImportHelper.ExtractedFile> files){
        for(ZipImportHelper.ExtractedFile f:files){
            String n=AddressClassifier.normalize(lastName(f.originalName));
            if(n.contains("عناوين")||n.contains("العناوين")||n.contains("address"))return f;
        }
        return null;
    }
    /** F1: authoritatively reconcile a beneficiary's address against the «العناوين» list — match by registration
     * (digits) then by exact normalized name. Same place → merge (they complete each other); clear conflict → the
     * list wins; unmatched → keep the assessment (التقييم) address as a fallback and flag «عنوان ناقص». The eval
     * address is always preserved in original_address for the side-by-side comparison in the beneficiary file. */
    private void reconcileAddressFromList(AppDatabase.Beneficiary b,AddressList list,int[] matched,int[] fromEval,int[] missing){
        String evalAddr=b.address==null?"":b.address.trim();
        b.originalAddress=evalAddr;
        AddressList.Entry e=(list==null)?null:list.match(digitsOnly(b.registration),b.name);
        String listAddr=(e==null||e.address==null)?"":e.address.trim();
        if(e!=null){
            b.addressesFileAddress=listAddr;b.addressesFileSector=e.sector==null?"":e.sector.trim();
            if(!b.addressesFileSector.isEmpty()&&AddressClassifier.SECTOR_ORDER.contains(b.addressesFileSector)){b.sector=b.addressesFileSector;b.sectorOrder=AddressClassifier.SECTOR_ORDER.indexOf(b.addressesFileSector);}
            if(e.street!=null&&!e.street.trim().isEmpty())b.street=e.street.trim();
        }
        if(!listAddr.isEmpty()){
            // The «العناوين» list is authoritative: same place → merge (they complete each other); clear conflict → list wins.
            if(evalAddr.isEmpty())b.address=listAddr;
            else if(sameLocation(evalAddr,listAddr))b.address=mergeAddresses(listAddr,evalAddr);
            else b.address=listAddr;
            b.addressSource="قائمة العناوين";
            matched[0]++;
        }else if(!evalAddr.isEmpty()){
            // No address in the «العناوين» list, but the التقييم sheet has one → use it, clearly labelled «من التقييم».
            b.address=evalAddr;
            b.addressSource="صفحة التقييم";
            b.matchStatus="العنوان من صفحة التقييم (لا يوجد في قائمة العناوين) | "+(b.matchStatus==null?"":b.matchStatus);
            b.notes=(b.notes==null||b.notes.isEmpty()?"":b.notes+" | ")+"العنوان من صفحة التقييم — لا يوجد في قائمة العناوين";
            fromEval[0]++;
        }else{
            // No address anywhere (neither the list nor the التقييم sheet).
            b.matchStatus="بلا عنوان — لا في قائمة العناوين ولا في التقييم | "+(b.matchStatus==null?"":b.matchStatus);
            b.notes=(b.notes==null||b.notes.isEmpty()?"":b.notes+" | ")+"لا يوجد عنوان في قائمة العناوين ولا في صفحة التقييم";
            missing[0]++;
        }
    }
    /** Two addresses describe the same place when one contains the other, or they share ≥2 meaningful (≥3-char) words. */
    private boolean sameLocation(String a,String b){
        String na=AddressClassifier.normalize(a),nb=AddressClassifier.normalize(b);
        if(na.isEmpty()||nb.isEmpty())return false;
        if(na.contains(nb)||nb.contains(na))return true;
        java.util.Set<String> wa=new java.util.HashSet<>(java.util.Arrays.asList(na.split(" ")));
        wa.retainAll(java.util.Arrays.asList(nb.split(" ")));
        int meaningful=0;for(String w:wa)if(w.length()>=3)meaningful++;
        return meaningful>=2;
    }
    /** Merge: keep the list address as the base and append only the assessment detail it doesn't already cover. */
    private String mergeAddresses(String base,String extra){
        String nb=AddressClassifier.normalize(base);StringBuilder add=new StringBuilder();
        for(String part:extra.split("[-،,]")){String p=part.trim();if(p.isEmpty())continue;
            if(!nb.contains(AddressClassifier.normalize(p))){if(add.length()>0)add.append(" ");add.append(p);}}
        return add.length()==0?base:base+" — "+add;
    }
    /** F1: the parsed «العناوين» list, indexed by registration digits and by exact normalized name for authoritative
     * address lookup during a followup ZIP import. Registration is the primary key; name is the fallback. */
    private static final class AddressList {
        static final class Entry{final String address,sector,street;Entry(String a,String s,String st){address=a;sector=s;street=st;}}
        private final java.util.Map<String,Entry> byReg=new java.util.HashMap<>();
        private final java.util.Map<String,Entry> byName=new java.util.HashMap<>();
        static AddressList parse(SpreadsheetImporter.Result result){
            AddressList list=new AddressList();
            if(result==null)return list;
            for(AppDatabase.Beneficiary r:result.rows){
                String a=r.address==null?"":r.address.trim(),s=r.sector==null?"":r.sector.trim(),st=r.street==null?"":r.street.trim();
                if(a.isEmpty()&&s.isEmpty())continue; // a blank/summary line — never index it
                Entry e=new Entry(a,s,st);
                String rd=r.registration==null?"":r.registration.replaceAll("[^0-9]","");
                if(!rd.isEmpty())list.byReg.putIfAbsent(rd,e);
                String nn=AddressClassifier.normalize(r.name);
                if(!nn.isEmpty())list.byName.putIfAbsent(nn,e);
            }
            return list;
        }
        Entry match(String regDigits,String name){
            if(regDigits!=null&&!regDigits.isEmpty()){Entry e=byReg.get(regDigits);if(e!=null)return e;}
            String nn=AddressClassifier.normalize(name);
            return nn.isEmpty()?null:byName.get(nn);
        }
    }
    // ===== end F1 =========================================================================================

    /** The single place a freshly-parsed import Result gets its rows classified and stage-stamped — shared
     * verbatim by the interactive single-file path (importUri) and the batch ZIP path (importZipFilesBatch),
     * so the two can never drift apart. Runs on the worker thread (does DB-free classification only). */
    private void classifyImportedRows(SpreadsheetImporter.Result result,String batchTag){classifyImportedRows(result,batchTag,true);}
    /** {@code enrichFuzzy}=false disables the cross-record fuzzy address completion (enrichAddresses) — used by the
     * followup ZIP path (F1), where the address is taken authoritatively from the separate «العناوين» list matched
     * by registration/name, NOT guessed from other rows. */
    private void classifyImportedRows(SpreadsheetImporter.Result result,String batchTag,boolean enrichFuzzy){
        int inferred=enrichFuzzy?SpreadsheetImporter.enrichAddresses(result.rows,classifier):0;
        if(inferred>0)result.warnings.add("تم استكمال "+inferred+" عنواناً من سجلات مرتبطة داخل الملف؛ راجع المصدر قبل الزيارة.");
        for(AppDatabase.Beneficiary b:result.rows){
            // A sector explicitly given by the sheet's own "القطاع" column is authoritative and overrides the
            // text-classified guess below — but only when it names one of the seven real sectors.
            String explicitSector=b.sector;String explicitStreet=b.street;
            String enrichmentNote=b.addressSource;
            AddressResolution.Result resolved=resolveAddress(b.address,null);
            b.sector=resolved.sector;b.street=resolved.street;b.confidence=resolved.confidence;
            b.sectorOrder=resolved.sectorIndex;b.routeOrder=resolved.routeIndex;b.streetOrder=resolved.streetIndex;b.alleyOrder=resolved.alleyIndex;
            b.matchStatus=(enrichmentNote.isEmpty()?"":"مستكمل من "+enrichmentNote+" — تحقق سريع | ")+(resolved.needsReview?"بحاجة لمراجعة — ":"")+resolved.addressSource;
            b.addressSource=resolved.addressSource;
            if(!explicitSector.isEmpty()&&AddressClassifier.SECTOR_ORDER.contains(explicitSector)&&!explicitSector.equals(b.sector)){b.sector=explicitSector;b.sectorOrder=AddressClassifier.SECTOR_ORDER.indexOf(explicitSector);b.matchStatus="القطاع محدد صراحةً في الملف | "+b.matchStatus;}
            if(!explicitStreet.isEmpty()&&!explicitStreet.equals(b.street)){b.street=explicitStreet;b.matchStatus="الشارع محدد صراحةً في الملف | "+b.matchStatus;}
            if(!result.backup){b.workflowStage="followup";b.studyStatus="approved";if(b.followupBatch.isEmpty())b.followupBatch=batchTag;b.approvedAt=System.currentTimeMillis();if(b.engineerName.isEmpty())b.engineerName=currentEngineerName();}
        }
    }

    private void importUri(Uri uri){ProgressDialog progress=new ProgressDialog(this);progress.setMessage("جارٍ قراءة الملف وتصنيف العناوين…");progress.setCancelable(false);progress.show();
        // Every import keeps its own tag (source file + time) so lists from
        // different imports stay distinguishable instead of blending into one
        // generic "imported list" group.
        String batchTag="قائمة: "+SpreadsheetImporter.queryName(this,uri)+" — "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());
        worker.execute(()->{try{SpreadsheetImporter.Result result=SpreadsheetImporter.read(this,uri);classifyImportedRows(result,batchTag);runOnUiThread(()->{progress.dismiss();confirmImport(result);});}catch(Exception e){runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("تعذر استيراد الملف").setMessage(friendlyImportError(e)).setPositiveButton("حسناً",null).show();});}});}
    private String friendlyImportError(Throwable error){String raw=error.getMessage()==null?error.toString():error.getMessage();String lower=raw.toLowerCase(Locale.ROOT);if(lower.contains("xmlreader")||lower.contains("org.xml.sax.driver"))return "تعذر تشغيل قارئ Excel على هذا الجهاز. يعالج هذا الإصدار مشكلة قارئ XML؛ إذا ظهرت مرة أخرى أغلق التطبيق وأعد فتحه.";if(lower.contains("zip")||lower.contains("central directory"))return "الملف ليس مصنف Excel XLSX سليماً أو قد يكون تالفاً. افتحه في Excel واحفظه مرة أخرى بصيغة XLSX.";return "لم يتمكن التطبيق من قراءة الملف. تأكد من أنه XLSX أو CSV أو JSON وأنه ليس محمياً بكلمة مرور.\n\nالتفصيل التقني: "+raw;}
    private void confirmImport(SpreadsheetImporter.Result result){if(result.rows.isEmpty()){new AlertDialog.Builder(this).setTitle("لا توجد سجلات").setMessage(result.warnings.isEmpty()?"لم يُعثر على بيانات قابلة للاستيراد.":join(result.warnings)).setPositiveButton("حسناً",null).show();return;}
        ScrollView scroll=new ScrollView(this);LinearLayout box=column(dp(18));scroll.addView(box);
        box.addView(text((result.backup?"نسخة احتياطية تضم ":"تم العثور على ")+result.rows.size()+" مستفيداً",17,NAVY,true));
        if(result.backup){TextView note=text("سيُستعاد جدول الكميات ومراحل الدراسة والمتابعة.",12,MUTED,false);note.setPadding(0,dp(4),0,0);box.addView(note);}
        TextView auditTitle=text("تدقيق العناوين",15,NAVY,true);auditTitle.setPadding(0,dp(14),0,dp(4));box.addView(auditTitle);
        Map<String,Integer>quality=new LinkedHashMap<>();quality.put("جاهز ميدانيًا",0);quality.put("مستكمل — تحقق سريع",0);quality.put("مطابقة تقريبية",0);quality.put("مراجعة إلزامية",0);
        Map<String,Integer>bySector=new LinkedHashMap<>();double totalAmount=0;int amountCount=0;
        for(AppDatabase.Beneficiary b:result.rows){
            String key;
            if(!b.addressSource.isEmpty())key="مستكمل — تحقق سريع";
            else if(b.confidence>=0.93)key="جاهز ميدانيًا";
            else if(b.confidence>=0.60)key="مطابقة تقريبية";
            else key="مراجعة إلزامية";
            quality.put(key,quality.get(key)+1);
            String sector=b.sector.isEmpty()?"غير محدد":b.sector;bySector.put(sector,(bySector.containsKey(sector)?bySector.get(sector):0)+1);
            try{double amount=Double.parseDouble(b.amount.replace(",",""));if(amount>0){totalAmount+=amount;amountCount++;}}catch(Exception ignored){}
        }
        for(Map.Entry<String,Integer>entry:quality.entrySet())box.addView(auditRow(entry.getKey(),String.valueOf(entry.getValue())));
        TextView sectorTitle=text("التوزيع حسب القطاع",15,NAVY,true);sectorTitle.setPadding(0,dp(14),0,dp(4));box.addView(sectorTitle);
        for(Map.Entry<String,Integer>entry:bySector.entrySet())box.addView(auditRow(entry.getKey(),String.valueOf(entry.getValue())));
        if(amountCount>0){
            TextView amountTitle=text("المبالغ",15,NAVY,true);amountTitle.setPadding(0,dp(14),0,dp(4));box.addView(amountTitle);
            long total=Math.round(totalAmount);long firstPay=QuantityCatalog.firstPayment(total);long secondPay=QuantityCatalog.secondPayment(total);
            box.addView(auditRow("الإجمالي ("+amountCount+" مبلغاً)",total+" $"));
            box.addView(auditRow("الدفعة الأولى 60%",firstPay+" $"));
            box.addView(auditRow("الدفعة الثانية 40%",secondPay+" $"));
        }
        if(!result.warnings.isEmpty()){TextView warnTitle=text("ملاحظات الربط",15,NAVY,true);warnTitle.setPadding(0,dp(14),0,dp(4));box.addView(warnTitle);TextView warnings=text(join(result.warnings),12,MUTED,false);box.addView(warnings);}
        TextView hint=text("اختر استبدال بيانات المشروع أو الإضافة إليها.",12,MUTED,false);hint.setPadding(0,dp(14),0,0);box.addView(hint);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(result.backup?"النسخة الاحتياطية جاهزة للاستعادة":"القائمة جاهزة للاستيراد").setView(scroll).setPositiveButton("استبدال",(d,w)->saveImport(result,true)).setNeutralButton("إضافة",(d,w)->saveImport(result,false)).setNegativeButton("إلغاء",null).create();
        dialog.setOnShowListener(v->themeAndSize(dialog.getWindow(),(int)(getResources().getDisplayMetrics().widthPixels*.94),(int)(getResources().getDisplayMetrics().heightPixels*.82)));dialog.show();}
    private View auditRow(String label,String value){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(30));row.setLayoutParams(p);TextView v=text(value,13,0xff0E7490,true);row.addView(v,new LinearLayout.LayoutParams(dp(70),-2));TextView l=text(label,13,TEXT,false);row.addView(l,new LinearLayout.LayoutParams(0,-2,1));return row;}
    private void saveImport(SpreadsheetImporter.Result result,boolean replace){db.importBeneficiaries(currentProjectId,result.rows,replace);toast("تم حفظ "+result.rows.size()+" مستفيداً وترتيب العناوين");showPage(NAV_HOME);}

    private void createBackup(){try{pendingBackup=db.backup(currentProjectId,currentProject).toString(2);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());intent.putExtra(Intent.EXTRA_TITLE,"Yarmouk_"+safeFile(currentProject)+"_"+date+".json");startActivityForResult(intent,REQUEST_BACKUP);}catch(Exception e){fatal("تعذر تجهيز النسخة الاحتياطية",e);}}
    private void writeBackup(Uri uri){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new Exception("تعذر فتح ملف الحفظ");out.write(pendingBackup.getBytes(StandardCharsets.UTF_8));setLastBackupAt(System.currentTimeMillis());toast("تم حفظ النسخة الاحتياطية بنجاح");}catch(Exception e){fatal("تعذر حفظ النسخة الاحتياطية",e);}finally{pendingBackup=null;}}

    private void createFullBackup(){Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/zip");String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());intent.putExtra(Intent.EXTRA_TITLE,"Yarmouk_Full_"+safeFile(currentProject)+"_"+date+".zip");startActivityForResult(intent,REQUEST_FULL_BACKUP);}
    /** Same data as the plain JSON backup, plus every referenced photo's actual bytes, zipped — the JSON-only
     * backup has no way to recover photos if the device itself is lost, stolen, or the app is uninstalled.
     * A photo whose source URI can no longer be opened (permission revoked, file moved) is skipped, not
     * fatal — the rest of the backup still completes; the skipped count is reported to the user. */
    private void writeFullBackup(Uri uri){
        ProgressDialog progress=new ProgressDialog(this);progress.setMessage("جارٍ تجهيز النسخة الاحتياطية الكاملة…");progress.setCancelable(false);progress.show();
        worker.execute(()->{
            int[] counts={0,0}; // {photoCount, skipped}
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new Exception("تعذر فتح ملف الحفظ");
                try(ZipOutputStream zip=new ZipOutputStream(out)){
                    byte[] json=db.backup(currentProjectId,currentProject).toString(2).getBytes(StandardCharsets.UTF_8);
                    zip.putNextEntry(new ZipEntry("backup.json"));zip.write(json);zip.closeEntry();
                    for(AppDatabase.Beneficiary b:db.list(currentProjectId,"","all")){
                        String benFolder=safeFile(b.name.isEmpty()?("مستفيد_"+b.id):b.name)+"_"+b.id;
                        for(AppDatabase.Photo p:db.photos(b.id,"")){
                            try(InputStream in=getContentResolver().openInputStream(Uri.parse(p.uri))){
                                if(in==null)throw new Exception("no stream");
                                String name=p.fileName.isEmpty()?("photo_"+p.id+".jpg"):p.fileName;
                                zip.putNextEntry(new ZipEntry("photos/"+benFolder+"/"+safeFile(p.phase)+"/"+p.id+"_"+safeFile(name)));
                                byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)zip.write(buffer,0,count);
                                zip.closeEntry();counts[0]++;
                            }catch(Exception photoError){counts[1]++;}
                        }
                    }
                }
            }catch(Exception e){
                runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("تعذر حفظ النسخة الاحتياطية الكاملة").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("حسناً",null).show();});
                return;
            }
            setLastBackupAt(System.currentTimeMillis());
            runOnUiThread(()->{progress.dismiss();toast("تم حفظ النسخة الاحتياطية الكاملة — "+counts[0]+" صورة"+(counts[1]>0?("، تعذّر نسخ "+counts[1]+" صورة"):""));renderActive();});
        });
    }
    private void printRoute(){List<AppDatabase.Beneficiary>rows=db.listStage(currentProjectId,"","followup","all");if(rows.isEmpty()){toast("لا توجد قائمة متابعة للطباعة");return;}PrintManager manager=(PrintManager)getSystemService(Context.PRINT_SERVICE);PrintAttributes attrs=new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).setColorMode(PrintAttributes.COLOR_MODE_COLOR).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();manager.print("Yarmouk_Field_"+safeFile(currentProject),new PrintRouteAdapter(this,currentProject,PRINT_CREDIT,rows),attrs);}

    private TextView text(String value,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(sp*fontScale());v.setTextColor(color);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.create("sans",Typeface.BOLD));v.setLineSpacing(0,1.12f);return v;}
    private LinearLayout column(int padding){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(padding,padding,padding,padding);return v;}
    private Button smallButton(String value,int color){Button b=new Button(this);b.setText(value);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(null,Typeface.BOLD);b.setAllCaps(false);b.setPadding(dp(12),0,dp(12),0);b.setBackground(round(color,12,0,0));return b;}
    /** Adds `view` to a horizontal row with a small gap from the previous sibling — used for the
     * horizontally-scrolling action-button rows so buttons never sit flush against each other. */
    private void addRowGap(LinearLayout row,View view){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(8),0,0,0);row.addView(view,p);}
    private Button dialogButton(String value,int color,View.OnClickListener click){Button b=smallButton(value,color);b.setOnClickListener(click);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),dp(6),dp(3),dp(6));b.setLayoutParams(p);return b;}
    private GradientDrawable round(int color,int radius,int stroke,int strokeWidth){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(strokeWidth>0)d.setStroke(dp(strokeWidth),stroke);return d;}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private int computeResource(String name,String type){return getResources().getIdentifier(name,type,getPackageName());}
    private int statusColor(String status){if("completed".equals(status))return 0xff149176;if("review".equals(status))return 0xffBD4868;if("unreachable".equals(status))return 0xff6B7280;return 0xffC9851C;}
    private String statusLabel(String status){if("completed".equals(status))return "تمت الزيارة";if("review".equals(status))return "بحاجة لمراجعة";if("unreachable".equals(status))return "تعذر الوصول";return "بانتظار الزيارة";}
    private int studyStatusColor(String status){switch(status){case "ready":return 0xff0E7490;case "office_review":return 0xffC9851C;case "approved":return 0xff149176;case "superseded":return 0xff6B7280;default:return 0xff7A5CB8;}}
    private String studyStatusLabel(String status){switch(status){case "ready":return "مرسلة للمراجعة المكتبية";case "office_review":return "قيد المراجعة المكتبية";case "approved":return "معتمدة وجاهزة للإرسال";case "superseded":return "نسخة سابقة — استُبدلت";default:return "مسودة قيد الإعداد";}}
    private static String safe(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value.trim();}
    private static String safeFile(String value){return value.replaceAll("[^0-9a-zA-Z\\u0600-\\u06FF._-]+","_");}
    private static String shortStreet(String value){String[]parts=value.split("[/()\\-–—]");return parts.length==0?value:parts[0].trim();}
    private static String join(List<String>values){StringBuilder b=new StringBuilder();for(String v:values)b.append("• ").append(v).append('\n');return b.toString();}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void fatal(String title,Exception e){new AlertDialog.Builder(this).setTitle(title).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("إغلاق",(d,w)->finish()).show();}
}
