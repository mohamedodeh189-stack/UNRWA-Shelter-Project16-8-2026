package org.unrwa.yarmoukfield.drawing;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Host screen for the sketch tool (M1 canvas + M2 tools). Field-first chrome: a persistent top bar with an
 * always-visible Undo/Redo, and a compact bottom tool row (select / wall / line / room / delete / grid).
 * Autosave is wired from the very first stroke — every change writes the model JSON to a file so a drawing is
 * never lost. For M1/M2 this saves to a debug file; M5 re-points the same path to the beneficiary's folder.
 *
 * DEBUG/TEST ENTRY — this Activity is launched directly for testing; wiring into the beneficiary file and
 * removing the temporary exported flag happen in M8 (see docs/HOUSE_DRAWING_MODULE_SPEC.md). */
public final class HouseDrawActivity extends Activity implements DrawCanvasView.Listener {
    private DrawCanvasView canvas;
    private Button undoBtn, redoBtn, calibrateBtn;
    private TextView saveState, assistant;
    private File saveFile;
    private float density;
    // A0: tool buttons + their base colours, so the active tool can be highlighted and the destructive one styled apart
    private final java.util.ArrayList<Button> toolBtns = new java.util.ArrayList<>();
    private final java.util.HashMap<Button, Integer> toolBase = new java.util.HashMap<>();

    // ---- DRAWING FAST FIELD MODE: three small SharedPreferences flags, global to the drawing tool (never the
    // DB, never per-drawing JSON) — a display/entry convenience only. The model, DXF and PDF pipelines always
    // work in metres regardless of these; only how a number is TYPED and SHOWN in a dialog changes. ----
    private static final String DRAW_PREFS = "draw_prefs";
    private boolean unitCm() { return getSharedPreferences(DRAW_PREFS, MODE_PRIVATE).getBoolean("unit_cm", false); }
    private void setUnitCm(boolean v) { getSharedPreferences(DRAW_PREFS, MODE_PRIVATE).edit().putBoolean("unit_cm", v).apply(); }
    private boolean promptAfterDraw() { return getSharedPreferences(DRAW_PREFS, MODE_PRIVATE).getBoolean("prompt_after_draw", false); }
    private void setPromptAfterDraw(boolean v) { getSharedPreferences(DRAW_PREFS, MODE_PRIVATE).edit().putBoolean("prompt_after_draw", v).apply(); }
    private boolean fastMode() { return getSharedPreferences(DRAW_PREFS, MODE_PRIVATE).getBoolean("fast_mode", false); }
    /** Turning fast mode ON bundles the field-speed defaults in one tap: cm entry, wall-length prompt after
     * drawing, and interior(net) dimensions (already the app default, set explicitly here too so it is never
     * left on axis mode by accident). It never forces itself back off if the engineer later changes one of those
     * three individually — this is a convenience preset, not a locked mode. */
    private void setFastMode(boolean v) {
        getSharedPreferences(DRAW_PREFS, MODE_PRIVATE).edit().putBoolean("fast_mode", v).apply();
        if (v) {
            setUnitCm(true); setPromptAfterDraw(true);
            if (!canvas.netDimensions()) canvas.toggleDimensionRef();
        }
    }
    private String unitSuffix() { return unitCm() ? "سم" : "م"; }
    /** Format a metre value for display in an input field, respecting the current input unit. */
    private String formatLenForInput(double meters) { return unitCm() ? String.format(Locale.US, "%.0f", meters * 100) : String.format(Locale.US, "%.2f", meters); }
    /** Parse a typed number as metres, respecting the current input unit (500 → 5.00 م when unit=سم). Returns -1
     * on an invalid/non-positive entry so callers can show one consistent error message. */
    private double parseLenInput(String s) {
        double v; try { v = Double.parseDouble(s.trim()); } catch (Exception e) { return -1; }
        if (v <= 0) return -1;
        return unitCm() ? v / 100.0 : v;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        saveFile = resolveSaveFile();

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- top bar ----
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBackgroundColor(0xff0B2F52); top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView title = new TextView(this); title.setText("مخطط المنزل"); title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        saveState = new TextView(this); saveState.setTextColor(0xffAFE3EE); saveState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        saveState.setText("حفظ تلقائي"); top.addView(saveState);
        Button helpBtn = barButton("؟ شرح", 0xff7A5CB8); helpBtn.setOnClickListener(v -> showHelpSheet()); top.addView(space()); top.addView(helpBtn);
        undoBtn = barButton("↶ تراجع", 0xff15A6BC); undoBtn.setOnClickListener(v -> canvas.undo()); top.addView(space()); top.addView(undoBtn);
        redoBtn = barButton("↷ إعادة", 0xff15A6BC); redoBtn.setOnClickListener(v -> canvas.redo()); top.addView(space()); top.addView(redoBtn);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- field-assistant banner (two-step calibration guidance) ----
        assistant = new TextView(this); assistant.setTextColor(0xff0B2F52); assistant.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        assistant.setBackgroundColor(0xffE3F3F5); assistant.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(assistant, new LinearLayout.LayoutParams(-1, -2));

        // ---- canvas ----
        canvas = new DrawCanvasView(this);
        canvas.setListener(this);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- bottom tool row ----
        HorizontalScrollView sc = new HorizontalScrollView(this); sc.setHorizontalScrollBarEnabled(false);
        sc.setBackgroundColor(0xffEDF2F5);
        LinearLayout tools = new LinearLayout(this); tools.setOrientation(LinearLayout.HORIZONTAL); tools.setPadding(dp(8), dp(8), dp(8), dp(8));
        // UX: while there is no scale yet, «معايرة» is the very first job — show it FIRST (rightmost in RTL) so
        // the engineer sees it immediately. Once calibrated it hides (still reachable in «المزيد») to keep the bar simple.
        calibrateBtn = tool("📏 معايرة", DrawCanvasView.TOOL_CALIBRATE, 0xff149176);
        tools.addView(calibrateBtn);
        // A4: only the FIVE primary drawing tools stay on the bar, always visible — the ones the engineer uses constantly
        tools.addView(tool("تحديد", DrawCanvasView.TOOL_SELECT));
        tools.addView(tool("جدار", DrawCanvasView.TOOL_WALL));
        tools.addView(tool("غرفة", DrawCanvasView.TOOL_ROOM));
        // FAST FIELD MODE (#4/#12): «غرفة بأبعاد» promoted onto the primary bar — not a drawing tool itself (it
        // doesn't change canvas.tool()), just a one-tap shortcut to the same dialog reachable from «المزيد».
        Button dimsBtn = barButton("📐 أبعاد", 0xff0E8A9C); dimsBtn.setOnClickListener(v -> promptRoomByDimensions());
        LinearLayout.LayoutParams dimsLp = new LinearLayout.LayoutParams(-2, -2); dimsLp.setMargins(dp(3), 0, dp(3), 0); dimsBtn.setLayoutParams(dimsLp);
        tools.addView(dimsBtn);
        tools.addView(tool("🚪 باب", DrawCanvasView.TOOL_DOOR));
        tools.addView(tool("🪟 نافذة", DrawCanvasView.TOOL_WINDOW));
        tools.addView(tool("🪜 درج", DrawCanvasView.TOOL_STAIRS));
        // A4: everything secondary (سماكة/طبقات/شبكة/خط/تصدير + معايرة after calibration) is behind «المزيد»
        tools.addView(divider());
        Button more = barButton("☰ المزيد", 0xff1B4E7A); more.setOnClickListener(v -> showMoreMenu()); tools.addView(more);
        // A0/A4: delete kept far apart, in a distinct red, so it is never confused with a drawing tool
        tools.addView(divider());
        tools.addView(tool("🗑 حذف", DrawCanvasView.TOOL_DELETE, 0xffC0392B));
        sc.addView(tools); root.addView(sc, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        // Lift the tool bar clear of the phone's on-screen navigation buttons / gesture area: add the bottom system
        // inset (plus a small margin) as padding so the tools never sit under the device nav controls.
        final LinearLayout toolBar = tools;
        sc.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom;
            if (android.os.Build.VERSION.SDK_INT >= 30)
                navBottom = insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
            else navBottom = insets.getSystemWindowInsetBottom();
            toolBar.setPadding(dp(8), dp(8), dp(8), dp(8) + navBottom + dp(10));
            return insets;
        });
        sc.requestApplyInsets();

        loadIfPresent();
        canvas.setTool(DrawCanvasView.TOOL_SELECT);
        refreshButtons();
        refreshAssistant();
        highlightTools();
        updateCalibrateButton();
    }

    /** UX: show the primary-bar «معايرة» button only until a scale exists; hide it afterwards. */
    private void updateCalibrateButton() {
        if (calibrateBtn != null) calibrateBtn.setVisibility(canvas.model().isCalibrated() ? View.GONE : View.VISIBLE);
    }

    // ---- DrawCanvasView.Listener ----
    @Override public void onChange() { save(); refreshButtons(); refreshAssistant(); highlightTools(); }

    @Override public void onCalibrate(final double drawnLengthWorld) {
        // FAST FIELD MODE (#10): a clearer, self-contained explanation — what calibration IS (a scale, from two
        // points of a KNOWN real distance), unit-aware examples, and a note that typed lengths (wall-length /
        // «غرفة بأبعاد») are the most accurate reference once drawing from scratch.
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(12), dp(18), dp(6));
        box.addView(sheetText("المعايرة تعني تحديد مقياس الرسم", 15, 0xff0B2F52, true));
        box.addView(sheetText("اخترت نقطتين تعرف المسافة الحقيقية بينهما (الخط الذي رسمته الآن) — أدخل تلك المسافة الحقيقية.", 12, 0xff2A3F4C, false));
        box.addView(sheetText(unitCm() ? "وحدة الإدخال الحالية: سنتيمتر — مثال: أدخل 500 لجدار طوله 5 م." : "وحدة الإدخال الحالية: متر — مثال: أدخل 5.00 لجدار طوله 5 م.", 12, 0xff0E7490, true));
        TextView tip = sheetText("إذا كنت ترسم من الصفر وتُدخل الأطوال رقمياً (طول الجدار / غرفة بأبعاد)، فهذه الأطوال الرقمية هي المرجع الأدق — لا حاجة لإعادة المعايرة كل مرة.", 11, 0xff6E8698, false);
        tip.setPadding(0, dp(6), 0, dp(2)); box.addView(tip);
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint(unitCm() ? "المسافة بالسنتيمتر — مثال: 500" : "المسافة بالمتر — مثال: 5.00");
        in.setPadding(dp(14), dp(10), dp(14), dp(10)); box.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("معايرة المقياس — الخطوة 2 من 2")
                .setView(box)
                .setPositiveButton("تعيين المقياس", (d, w) -> {
                    double meters = parseLenInput(in.getText().toString());
                    if (meters > 0 && canvas.model().calibrate(drawnLengthWorld, meters)) {
                        canvas.setTool(DrawCanvasView.TOOL_SELECT);
                        onChange();
                        toast(String.format(Locale.US, "تمت المعايرة: 1 م = %.0f px", canvas.model().pxPerMeter));
                    } else {
                        toast(unitCm() ? "أدخل مسافة صحيحة بالسنتيمتر (أكبر من صفر)" : "أدخل مسافة صحيحة بالمتر (أكبر من صفر)");
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ---- A1: room naming — a small library of common field labels ----
    private static final String[] ROOM_PRESETS = {"غرفة نوم", "غرفة جلوس", "صالون", "مجلس", "مطبخ", "حمام", "WC",
            "برندة", "بلكون", "مدخل", "ممر", "درج", "مخزن", "غرفة غسيل", "سطح"};

    @Override public void onRoomCommitted(DrawElement room) { promptRoomName(room); }
    /** Tapping a drawn room opens its menu — the name library, exact length×width, turning it into real walls,
     * or delete. Keeps the rectangle overlay non-conflicting: you can convert it to walls whenever you like. */
    @Override public void onRoomTap(final DrawElement room) {
        new AlertDialog.Builder(this)
                .setTitle("الغرفة" + roomDimStr(room))
                .setItems(new String[]{"🏷 التسمية (من المكتبة)", "📐 ضبط الطول والعرض", "🧱 توليد الجدران حول الغرفة", "🗑 حذف الغرفة"}, (d, which) -> {
                    switch (which) {
                        case 0: promptRoomName(room); break;
                        case 1: promptResizeRoom(room); break;
                        case 2: canvas.roomToWalls(room); toast("تم رسم جدران الغرفة"); break;
                        default: deleteElement(room);
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private String roomDimStr(DrawElement room) {
        if (!canvas.model().isCalibrated() || room.pts == null || room.pts.length < 4) return "";
        double w = canvas.model().meters(Math.abs(room.pts[2] - room.pts[0]));
        double h = canvas.model().meters(Math.abs(room.pts[3] - room.pts[1]));
        return String.format(Locale.US, " — %.2f×%.2f م", w, h);
    }

    /** Set an existing room's exact length×width (m). Needs calibration. */
    private void promptResizeRoom(final DrawElement room) {
        if (!canvas.model().isCalibrated()) { toast("عايِر المقياس أولاً: «المزيد» ← «معايرة»"); return; }
        roomDimensionDialog("ضبط الطول والعرض", (len, wid) -> canvas.resizeRoom(room, len, wid));
    }

    /** New room by typed length×width — draws four REAL walls (no overlay), the easy "type size, get walls" flow.
     * FAST FIELD MODE (#4): first pick a room TYPE from the same name library used for renaming — the new room
     * is labelled immediately, no separate naming step afterward. "بلا تسمية" skips straight to dimensions. */
    private void promptRoomByDimensions() {
        if (!canvas.model().isCalibrated()) { toast("عايِر المقياس أولاً: «المزيد» ← «معايرة» — ثم أدخل الأبعاد"); return; }
        final String[] items = new String[ROOM_PRESETS.length + 2];
        System.arraycopy(ROOM_PRESETS, 0, items, 0, ROOM_PRESETS.length);
        items[ROOM_PRESETS.length] = "مخصّص…";
        items[ROOM_PRESETS.length + 1] = "بلا تسمية (تخطٍّ)";
        new AlertDialog.Builder(this)
                .setTitle("غرفة بالأبعاد — نوع الغرفة")
                .setItems(items, (d, which) -> {
                    if (which < ROOM_PRESETS.length) promptRoomByDimensionsSized(ROOM_PRESETS[which]);
                    else if (which == ROOM_PRESETS.length) promptCustomRoomTypeThenDims();
                    else promptRoomByDimensionsSized("");
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void promptCustomRoomTypeThenDims() {
        final EditText in = new EditText(this);
        in.setHint("اسم الغرفة"); in.setPadding(dp(16), dp(12), dp(16), dp(12));
        showTopInputDialog(new AlertDialog.Builder(this).setTitle("اسم مخصّص").setView(in)
                .setPositiveButton("متابعة", (d, w) -> promptRoomByDimensionsSized(in.getText().toString().trim()))
                .setNegativeButton("إلغاء", null));
    }

    private void promptRoomByDimensionsSized(final String roomType) {
        roomDimensionDialog(roomType.isEmpty() ? "غرفة بأبعاد (طول×عرض)" : "غرفة بأبعاد — " + roomType, (len, wid) -> {
            if (canvas.addRoomWallsByDimensions(len, wid, roomType))
                toast(String.format(Locale.US, "غرفة %.2f×%.2f م" + (roomType.isEmpty() ? "" : " — " + roomType), len, wid));
        });
    }

    private interface DimConsumer { void accept(double lengthM, double widthM); }

    /** Shared two-field (الطول / العرض) dialog used by «غرفة بأبعاد» and «ضبط الطول والعرض» — unit-aware (م/سم). */
    private void roomDimensionDialog(String title, final DimConsumer onOk) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(14), dp(16), dp(8));
        box.addView(sheetText(title, 17, 0xff0B2F52, true));
        box.addView(sheetText("الطول (" + unitSuffix() + "):", 13, 0xff0B2F52, true));
        final EditText len = new EditText(this); len.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        len.setHint(unitCm() ? "مثال: 400" : "مثال: 4.00"); len.setPadding(dp(14), dp(10), dp(14), dp(10)); box.addView(len);
        box.addView(sheetText("العرض (" + unitSuffix() + "):", 13, 0xff0B2F52, true));
        final EditText wid = new EditText(this); wid.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        wid.setHint(unitCm() ? "مثال: 300" : "مثال: 3.00"); wid.setPadding(dp(14), dp(10), dp(14), dp(10)); box.addView(wid);
        showTopInputDialog(new AlertDialog.Builder(this).setView(box)
                .setPositiveButton("رسم", (d, w) -> {
                    double l = parseLenInput(len.getText().toString()), ww = parseLenInput(wid.getText().toString());
                    if (l > 0 && ww > 0) onOk.accept(l, ww); else toast("أدخل الطول والعرض (" + unitSuffix() + ") أكبر من صفر");
                })
                .setNegativeButton("إلغاء", null));
    }

    /** Room name picker: a quick list of field presets + «مخصّص…». Picking sets the name (one undoable step) and
     * the name + area then show inside the room in the canvas and the PDF. Reused for both new rooms and rename. */
    private void promptRoomName(final DrawElement room) {
        final String[] items = new String[ROOM_PRESETS.length + 1];
        System.arraycopy(ROOM_PRESETS, 0, items, 0, ROOM_PRESETS.length);
        items[ROOM_PRESETS.length] = "مخصّص…";
        new AlertDialog.Builder(this)
                .setTitle("اسم الغرفة")
                .setItems(items, (d, which) -> {
                    if (which < ROOM_PRESETS.length) setRoomName(room, ROOM_PRESETS[which]);
                    else promptCustomRoomName(room);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void promptCustomRoomName(final DrawElement room) {
        final EditText in = new EditText(this);
        in.setHint("اسم الغرفة"); in.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (room.label != null) in.setText(room.label);
        showTopInputDialog(new AlertDialog.Builder(this).setTitle("اسم مخصّص").setView(in)
                .setPositiveButton("تعيين", (d, w) -> setRoomName(room, in.getText().toString().trim()))
                .setNegativeButton("إلغاء", null));
    }

    private void setRoomName(DrawElement room, String name) {
        canvas.model().beginEdit();   // one undoable step
        room.label = name == null ? "" : name;
        canvas.externalChanged();     // repaint + autosave
        toast("اسم الغرفة: " + (room.label.isEmpty() ? "—" : room.label));
    }

    // ---- A3: window editing (length / delete) ----
    @Override public void onWindowTap(final DrawElement window) {
        final String[] items = {"طول 0.80 م", "طول 1.00 م", "طول 1.20 م", "طول 1.50 م", "طول مخصّص…", "🗑 حذف النافذة"};
        final double[] vals = {0.80, 1.00, 1.20, 1.50, -1, -2};
        new AlertDialog.Builder(this)
                .setTitle("النافذة")
                .setItems(items, (d, which) -> {
                    if (vals[which] > 0) setWindowWidth(window, vals[which]);
                    else if (vals[which] == -1) promptCustomWindowWidth(window);
                    else deleteElement(window);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void promptCustomWindowWidth(final DrawElement window) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint("طول النافذة بالمتر — مثال: 1.5"); in.setPadding(dp(16), dp(12), dp(16), dp(12));
        showTopInputDialog(new AlertDialog.Builder(this).setTitle("طول مخصّص (م)").setView(in)
                .setPositiveButton("تعيين", (d, w) -> {
                    double v = 0; try { v = Double.parseDouble(in.getText().toString().trim()); } catch (Exception ignored) {}
                    if (v > 0) setWindowWidth(window, v); else toast("أدخل طولاً صحيحاً بالمتر");
                })
                .setNegativeButton("إلغاء", null));
    }

    private void setWindowWidth(DrawElement window, double m) {
        canvas.model().beginEdit(); window.widthM = m; canvas.externalChanged();
        toast(String.format(Locale.US, "طول النافذة: %.2f م", m));
    }

    /** A2: door editor — standard widths 70/80/90 (or custom), the four field types, an easy swing rotation, and
     * delete. Type «أكورديون»/«مطوي» switch the symbol to a folding zigzag; «داخلي»/«خارجي» stay hinged. */
    @Override public void onDoorTap(final DrawElement door) {
        // FAST FIELD MODE (#7): the old single "⟳ تدوير جهة الفتح" cycled through all 4 hinge/swing states one at
        // a time, blind. Replaced with three EXPLICIT, named toggles (door.swing is 2 bits: bit0 = which jamb is
        // the hinge, bit1 = which side of the wall the leaf swings to) so the engineer picks exactly what they
        // mean instead of cycling and checking: «Mirror» flips BOTH bits at once (full mirror image), «يمين/يسار»
        // flips only the hinge jamb, «داخلي/خارجي» flips only the swing side. None of them delete or move the door.
        final String[] items = {"عرض 0.70 م", "عرض 0.80 م", "عرض 0.90 م", "عرض 1.00 م", "عرض مخصّص…",
                "النوع: باب داخلي", "النوع: باب خارجي", "النوع: باب ضرفتين (مصراعين)", "النوع: أكورديون", "النوع: باب مطوي",
                "🪞 Mirror (عكس كامل)", "⇄ تبديل جهة المفصلة (يمين/يسار)", "⇅ عكس اتجاه الفتح (داخلي/خارجي)", "🗑 حذف الباب"};
        final String head = "الباب — " + (door.kind == null || door.kind.isEmpty() ? "باب" : door.kind)
                + (door.widthM > 0 ? String.format(Locale.US, " • %.2f م", door.widthM) : "");
        new AlertDialog.Builder(this)
                .setTitle(head)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: setDoorWidth(door, 0.70); break;
                        case 1: setDoorWidth(door, 0.80); break;
                        case 2: setDoorWidth(door, 0.90); break;
                        case 3: setDoorWidth(door, 1.00); break;
                        case 4: promptCustomDoorWidth(door); break;
                        case 5: setDoorKind(door, "داخلي"); break;
                        case 6: setDoorKind(door, "خارجي"); break;
                        case 7: setDoorKind(door, "ضرفتين"); break;
                        case 8: setDoorKind(door, "أكورديون"); break;
                        case 9: setDoorKind(door, "مطوي"); break;
                        case 10: setDoorSwing(door, door.swing ^ 3, "Mirror — تم عكس الباب بالكامل"); break;
                        case 11: setDoorSwing(door, door.swing ^ 1, "تم تبديل جهة المفصلة"); break;
                        case 12: setDoorSwing(door, door.swing ^ 2, "تم عكس اتجاه الفتح (داخلي/خارجي)"); break;
                        default: deleteElement(door);
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void promptCustomDoorWidth(final DrawElement door) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint("عرض الباب بالمتر — مثال: 0.90"); in.setPadding(dp(16), dp(12), dp(16), dp(12));
        showTopInputDialog(new AlertDialog.Builder(this).setTitle("عرض مخصّص (م)").setView(in)
                .setPositiveButton("تعيين", (d, w) -> {
                    double v = 0; try { v = Double.parseDouble(in.getText().toString().trim()); } catch (Exception ignored) {}
                    if (v > 0) setDoorWidth(door, v); else toast("أدخل عرضاً صحيحاً بالمتر");
                })
                .setNegativeButton("إلغاء", null));
    }

    private void setDoorWidth(DrawElement door, double m) {
        canvas.model().beginEdit(); door.widthM = m; canvas.externalChanged();
        toast(String.format(Locale.US, "عرض الباب: %.2f م", m));
    }
    private void setDoorKind(DrawElement door, String kind) {
        canvas.model().beginEdit(); door.kind = kind; canvas.externalChanged();
        toast("نوع الباب: " + kind);
    }
    private void setDoorSwing(DrawElement door, int swing, String message) {
        canvas.model().beginEdit(); door.swing = swing & 3; canvas.externalChanged();
        toast(message); // door stays on the same wall/position — only the leaf/hinge symbol changes
    }

    private void deleteElement(DrawElement e) {
        canvas.model().remove(e); canvas.clearSelection(); canvas.externalChanged();
        toast("تم الحذف");
    }

    @Override public void onOpeningMissedWall() { toast("اضغط على الجدار نفسه لإضافة الباب أو النافذة"); }

    /** Stairs: simple editor — set exact الطول / العرض (m), flip the ascent direction, or delete. */
    @Override public void onStairsTap(final DrawElement stairs) {
        new AlertDialog.Builder(this)
                .setTitle("الدرج" + stairsDimStr(stairs))
                .setItems(new String[]{"📏 الطول (م)", "📐 العرض (م)", "↕ عكس اتجاه الصعود", "🗑 حذف الدرج"}, (d, which) -> {
                    switch (which) {
                        case 0: promptStairsDim(stairs, true); break;
                        case 1: promptStairsDim(stairs, false); break;
                        case 2: canvas.model().beginEdit(); stairs.swing = (stairs.swing + 1) & 1; canvas.externalChanged(); toast("تم عكس اتجاه الصعود"); break;
                        default: deleteElement(stairs);
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private String stairsDimStr(DrawElement st) {
        if (!canvas.model().isCalibrated() || st.pts == null || st.pts.length < 4) return "";
        double x1 = Math.min(st.pts[0], st.pts[2]), y1 = Math.min(st.pts[1], st.pts[3]);
        double x2 = Math.max(st.pts[0], st.pts[2]), y2 = Math.max(st.pts[1], st.pts[3]);
        double w = x2 - x1, h = y2 - y1; boolean runX = w >= h;
        double len = canvas.model().meters(runX ? w : h), wid = canvas.model().meters(runX ? h : w);
        return String.format(Locale.US, " — %.2f×%.2f م", len, wid);
    }

    private void promptStairsDim(final DrawElement st, final boolean length) {
        if (!canvas.model().isCalibrated()) { toast("عايِر المقياس أولاً: «المزيد» ← «معايرة»"); return; }
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint(length ? "طول الدرج بالمتر — مثال: 3.0" : "عرض الدرج بالمتر — مثال: 1.0");
        in.setPadding(dp(16), dp(12), dp(16), dp(12));
        showTopInputDialog(new AlertDialog.Builder(this).setTitle(length ? "طول الدرج (م)" : "عرض الدرج (م)").setView(in)
                .setPositiveButton("تعيين", (d, w) -> {
                    double v = 0; try { v = Double.parseDouble(in.getText().toString().trim()); } catch (Exception ignored) {}
                    if (v > 0) setStairsDim(st, v, length); else toast("أدخل قيمة صحيحة بالمتر");
                })
                .setNegativeButton("إلغاء", null));
    }

    /** Resize the stairs rectangle to an exact metric length (along the run) or width (across it), keeping the
     * top-left corner fixed. Needs a calibrated scale. */
    private void setStairsDim(DrawElement st, double meters, boolean length) {
        double ppm = canvas.model().pxPerMeter; if (ppm <= 0) return;
        double world = meters * ppm;
        double x1 = Math.min(st.pts[0], st.pts[2]), y1 = Math.min(st.pts[1], st.pts[3]);
        double x2 = Math.max(st.pts[0], st.pts[2]), y2 = Math.max(st.pts[1], st.pts[3]);
        double w = x2 - x1, h = y2 - y1; boolean runX = w >= h;
        boolean setX = length == runX; // length maps to the run axis; width to the cross axis
        canvas.model().beginEdit();
        if (setX) x2 = x1 + world; else y2 = y1 + world;
        st.pts = new double[]{x1, y1, x2, y2};
        canvas.externalChanged();
        toast(String.format(Locale.US, length ? "طول الدرج: %.2f م" : "عرض الدرج: %.2f م", meters));
    }

    // ---- B4: numeric wall length + angle, ON DEMAND (tap a wall) — never auto-popped after drawing BY DEFAULT ----
    @Override public void onWallTap(DrawElement wall) {
        if (!canvas.model().isCalibrated()) { toast("اضبط المقياس أولاً: «المزيد» ← «معايرة»"); return; }
        promptWallLength(wall, false);
    }

    /** FAST FIELD MODE (#3): fires after every new wall is FINISHED being drawn — but only actually opens the
     * sheet when «طلب طول الجدار بعد الرسم» is enabled (off by default; on by default once «وضع الرسم الميداني
     * السريع» is enabled). Continuous wall-chaining is unaffected: this is a plain dialog on top, the canvas
     * underneath keeps its chained start point for the next wall regardless of "طول"/"تخطٍّ". */
    @Override public void onWallDrawn(DrawElement wall) {
        if (!promptAfterDraw() || !canvas.model().isCalibrated()) return;
        promptWallLength(wall, true);
    }

    /** Numeric length/angle editing for a wall — «تعديل الجدار» when tapped later (precision, opt-in), or «طول
     * الجدار» right after drawing when fast-mode's prompt-after-draw is on. Also offers «↔ تمديد الجدار» to snap
     * the wall's free end onto the nearest wall it would meet, without creating a separate segment. Unit-aware
     * (م/سم) via {@link #unitCm()}; drawing itself is never blocked — every button here is optional/skippable. */
    private void promptWallLength(final DrawElement wall, final boolean afterDraw) {
        final boolean net = canvas.netDimensions();
        final double cur = net ? canvas.interiorMeters(wall) : canvas.segmentMeters(wall);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(14), dp(16), dp(8));
        box.addView(sheetText(afterDraw ? "طول الجدار" : "تعديل الجدار", 17, 0xff0B2F52, true));
        box.addView(sheetText(String.format(Locale.US, net ? "الطول الداخلي (الصافي) الحالي: %.2f م" : "الطول المحوري الحالي: %.2f م", cur), 13, 0xff667A89, false));
        box.addView(sheetText("القيمة بال" + unitSuffix() + ":", 12, 0xff0E7490, true));
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint(unitCm() ? "مثال: 450" : "مثال: 4.50"); in.setText(formatLenForInput(cur));
        in.setPadding(dp(14), dp(10), dp(14), dp(10)); box.addView(in);
        box.addView(sheetText("الزاوية:", 13, 0xff0B2F52, true));
        final android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        android.widget.RadioButton r0 = new android.widget.RadioButton(this); r0.setText("زاوية حرة (كما رُسمت)"); r0.setId(4000);
        android.widget.RadioButton r1 = new android.widget.RadioButton(this); r1.setText("أفقي 0°"); r1.setId(4001);
        android.widget.RadioButton r2 = new android.widget.RadioButton(this); r2.setText("عمودي 90°"); r2.setId(4002);
        rg.addView(r0); rg.addView(r1); rg.addView(r2); rg.check(4000);
        box.addView(rg);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setView(box)
                .setPositiveButton("تثبيت", (d, w) -> {
                    double v = parseLenInput(in.getText().toString());
                    int mode = rg.getCheckedRadioButtonId() == 4001 ? DrawCanvasView.ANGLE_H
                            : rg.getCheckedRadioButtonId() == 4002 ? DrawCanvasView.ANGLE_V : DrawCanvasView.ANGLE_KEEP;
                    if (v > 0) { canvas.adjustWall(wall, v, mode); toast(String.format(Locale.US, "طول الجدار: %.2f م", v)); }
                    else toast("أدخل طولاً صحيحاً (" + unitSuffix() + ")");
                })
                .setNegativeButton(afterDraw ? "تخطٍّ" : "إلغاء", null);
        // #6 Extend Wall — offered right here since the engineer already has the wall selected; harmless to skip.
        b.setNeutralButton("↔ تمديد لأقرب جدار", (d, w) -> {
            if (canvas.extendWall(wall)) toast("تم تمديد الجدار حتى أقرب جدار");
            else toast("لا يوجد جدار قريب للتمديد");
        });
        showTopInputDialog(b);
    }

    /** UX: show a text-input dialog anchored near the TOP of the screen with ADJUST_RESIZE, so the soft keyboard
     * (which rises from the bottom) never covers the field or the confirm button. Used for every EditText dialog. */
    private void showTopInputDialog(AlertDialog.Builder b) {
        AlertDialog dlg = b.create();
        android.view.Window w = dlg.getWindow();
        if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        if (w != null) {
            w.setGravity(Gravity.TOP);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = dp(48);
            w.setAttributes(lp);
        }
    }

    /** In-app instructions: a scrollable «كيف أرسم؟» panel explaining every tool and the smart helpers, so the
     * engineer never has to guess. Opened from the top-bar «؟ شرح» button and «المزيد ← شرح الأدوات». */
    private void showHelpSheet() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(14), dp(18), dp(14));
        sv.addView(box);
        box.addView(sheetText("كيف أرسم المخطط؟ — دليل سريع", 18, 0xff0B2F52, true));
        helpRow(box, "1) 📏 معايرة", "أول خطوة دائماً. ارسم خطاً تعرف طوله الحقيقي (مثلاً جدار قِسته 5 م) ثم أدخل الطول بالمتر. بدون معايرة لا تظهر الأبعاد ولا يُسمح بالتصدير.");
        helpRow(box, "2) جدار", "ارسم بإصبعك من زاوية لأخرى. تُضبط الزاوية القائمة (90°) تلقائياً، ويلتقط طرف الجدار الزاويةَ أو الجدار الموازي — ويظهر خط منقّط للمحاذاة. تابع الرسم جداراً بعد جدار؛ يتصل الطرف تلقائياً.");
        helpRow(box, "   ضبط طول جدار", "«تحديد» ثم اضغط على الجدار → أدخل الطول بالمتر (وإن شئت اجعله أفقياً/عمودياً تماماً).");
        helpRow(box, "3) غرفة", "طريقتان: (أ) اسحب مستطيلاً بإصبعك. (ب) الأسهل: «المزيد» ← «📐 غرفة بأبعاد» وأدخل الطول والعرض بالمتر فتُرسم جدرانها فوراً. اضغط على الغرفة لاحقاً: تسمية / ضبط الطول والعرض / توليد الجدران / حذف.");
        helpRow(box, "   شكل غير منتظم", "ارسم الجدران واحداً واحداً بأداة «جدار» — المحاذاة الذكية تُغلق الشكل بدقة مهما كان غير منتظم.");
        helpRow(box, "4) 🚪 باب", "اضغط على الجدار نفسه → تظهر مباشرة خيارات العرض (70/80/90) والنوع (داخلي/خارجي/ضرفتين/أكورديون/مطوي) واتجاه الفتح. اسحب الباب لتحريكه على الجدار.");
        helpRow(box, "5) 🪟 نافذة", "اضغط على الجدار لإضافتها → اضغط عليها لتغيير طولها أو حذفها. تلتصق بالجدار وتتحرك معه.");
        helpRow(box, "6) 🪜 درج", "اسحب مستطيلاً للدرج، ثم اضغط عليه لضبط الطول/العرض وعكس اتجاه الصعود.");
        helpRow(box, "7) 🏷 التسمية", "اضغط على أي غرفة ← «التسمية» لتختار من مكتبة الأسماء (غرفة نوم، صالون، مطبخ، حمام…) أو اسم مخصّص. تظهر التسمية والمساحة داخل الغرفة.");
        helpRow(box, "8) تحديد / تحريك", "«تحديد» يختار أي عنصر: اسحبه لتحريكه، أو اضغطه لتعديله. الأبواب والنوافذ تنزلق على جدارها.");
        helpRow(box, "9) 🗑 حذف", "وضع الحذف: اضغط أي عنصر لحذفه. يمكنك دائماً «تراجع». (لونه أحمر ومنفصل حتى لا يختلط بأدوات الرسم.)");
        helpRow(box, "الأبعاد داخلية (صافي)", "الأرقام على الجدران هي الأبعاد الداخلية الصافية (كما تُقاس بالمتر ميدانياً)، وليست من محور لمحور. للتبديل: «المزيد» ← «الأبعاد: داخلية/محورية». وعند إدخال طول جدار أو «غرفة بأبعاد» أدخل القياس الداخلي.");
        helpRow(box, "المساحة", "تظهر تلقائياً أعلى الشاشة عند إغلاق الغرف/الجدران بعد المعايرة.");
        helpRow(box, "التكبير والتحريك", "قرّب/بعّد بإصبعين، وحرّك اللوحة بإصبع واحد في مكان فارغ.");
        helpRow(box, "🧭 الشمال · ▤ الطبقات · ▥ السماكة · ⤓ التصدير", "كلها في «☰ المزيد». التصدير يُخرج PDF (للعرض) وDXF (أصلي للأوتوكاد).");
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("فهمت", null).show();
    }

    private void helpRow(LinearLayout box, String title, String body) {
        TextView t = sheetText(title, 14, 0xff0E7490, true); t.setPadding(0, dp(9), 0, dp(1)); box.addView(t);
        box.addView(sheetText(body, 13, 0xff2A3F4C, false));
    }

    /** Small labelled TextView for the sheet dialogs (HouseDrawActivity has no shared text() helper). */
    private TextView sheetText(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setPadding(0, dp(4), 0, dp(4));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** A4: the secondary tools live behind one «المزيد» menu so the bar can always show the five primary tools.
     * Pure UI — it only re-invokes existing actions (calibrate/line tools, thickness, layers, grid, export). */
    private void showMoreMenu() {
        // FAST FIELD MODE (#1/#2): the mode toggle + its two component settings (unit, prompt-after-draw) sit at
        // the TOP of «المزيد» — always reachable, and each stays independently changeable even after the preset.
        final String[] items = {
                fastMode() ? "🚀 وضع الرسم الميداني السريع: مفعّل ✓ (اضغط للإيقاف)" : "🚀 وضع الرسم الميداني السريع: معطّل (اضغط للتفعيل)",
                "وحدة الإدخال: " + (unitCm() ? "سنتيمتر ✓ (اضغط للمتر)" : "متر ✓ (اضغط للسنتيمتر)"),
                promptAfterDraw() ? "طلب طول الجدار بعد الرسم: مفعّل ✓ (اضغط للإيقاف)" : "طلب طول الجدار بعد الرسم: معطّل (اضغط للتفعيل)",
                "📏 معايرة المقياس", "🧭 تحديد الشمال (بوصلة)", "📐 غرفة بأبعاد (طول×عرض)", "✏️ خط",
                "▥ سماكة الجدران",
                canvas.netDimensions() ? "📐 الأبعاد: داخلية ✓ (اضغط للمحورية)" : "📐 الأبعاد: محورية (اضغط للداخلية)",
                "▤ الطبقات", canvas.gridOn() ? "▦ إخفاء الشبكة" : "▦ إظهار الشبكة",
                "⤓ تصدير PDF / DXF", "🚀 مساعدة الرسم السريع", "❔ شرح الأدوات (تعليمات)"};
        new AlertDialog.Builder(this)
                .setTitle("المزيد")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: setFastMode(!fastMode()); toast(fastMode() ? "وضع الرسم الميداني السريع: مفعّل (سم + طلب الطول بعد الرسم + أبعاد داخلية)" : "وضع الرسم الميداني السريع: معطّل"); break;
                        case 1: setUnitCm(!unitCm()); toast("وحدة الإدخال: " + unitSuffix()); break;
                        case 2: setPromptAfterDraw(!promptAfterDraw()); toast(promptAfterDraw() ? "سيُطلب طول الجدار بعد كل رسم جديد" : "لن يُطلب طول الجدار تلقائياً بعد الرسم"); break;
                        case 3: canvas.setTool(DrawCanvasView.TOOL_CALIBRATE); highlightTools(); refreshAssistant(); break;
                        case 4: showCompassDialog(); break;
                        case 5: promptRoomByDimensions(); break;
                        case 6: canvas.setTool(DrawCanvasView.TOOL_LINE); highlightTools(); refreshAssistant(); break;
                        case 7: showThicknessSheet(); break;
                        case 8: canvas.toggleDimensionRef(); toast(canvas.netDimensions() ? "الأبعاد: داخلية (صافي الغرفة)" : "الأبعاد: محورية (من محور لمحور)"); break;
                        case 9: showLayersSheet(); break;
                        case 10: canvas.toggleGrid(); break;
                        case 11: onExport(); break;
                        case 12: showQuickHelp(); break;
                        case 13: showHelpSheet(); break;
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    /** FAST FIELD MODE (#11): a SHORT, standalone tips sheet — separate from the full «؟ شرح» guide (#12) so the
     * engineer gets the fast path in a few seconds without scrolling a long explanation. */
    private void showQuickHelp() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(14), dp(18), dp(6));
        box.addView(sheetText("مساعدة الرسم السريع", 17, 0xff0B2F52, true));
        String[] tips = {
                "للرسم السريع: فعّل «وضع الرسم الميداني السريع» من «المزيد».",
                "أدخل الأطوال بالسنتيمتر مثل 300×400 (أو بالمتر حسب وحدة الإدخال).",
                "استخدم «📐 أبعاد» لإنشاء غرفة كاملة دفعة واحدة بدل رسم 4 جدران.",
                "استخدم «↔ تمديد لأقرب جدار» (عند تعديل جدار) لوصل جدار بجدار آخر دون قطعة إضافية.",
                "استخدم أدوات الباب (Mirror / يمين‑يسار / داخلي‑خارجي) لتغيير الاتجاه بدل حذف الباب.",
                "عند التصدير، DXF يبقى قابلاً للقياس دائماً (1 وحدة = 1 متر)."};
        for (String t : tips) { TextView tv = sheetText("•  " + t, 13, 0xff2A3F4C, false); tv.setPadding(0, dp(3), 0, dp(3)); box.addView(tv); }
        new AlertDialog.Builder(this).setView(box).setPositiveButton("فهمت", null).show();
    }

    /** Offline compass North: reads the phone's rotation-vector sensor (magnetometer+accelerometer — no GPS, no
     * internet, no permission) live. The engineer holds the phone flat with its top edge along «أعلى المخطط»
     * (the wall drawn upward), then «تثبيت الشمال» stores that heading as {@link DrawModel#northDeg} so the north
     * arrow points to real north. Metal/rebar can disturb the reading — take it clear of heavy steel. */
    private void showCompassDialog() {
        final android.hardware.SensorManager sm =
                (android.hardware.SensorManager) getSystemService(android.content.Context.SENSOR_SERVICE);
        android.hardware.Sensor rv = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (rv == null) { toast("لا يوجد حسّاس بوصلة على هذا الجهاز"); return; }
        final TextView tv = new TextView(this);
        tv.setPadding(dp(18), dp(16), dp(18), dp(10)); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); tv.setTextColor(0xff0B2F52);
        final String help = "أمسك الجوال أفقياً واجعل حافته العليا باتجاه «أعلى المخطط» (نفس اتجاه الجدار الذي رسمته للأعلى)، ثم اضغط «تثبيت الشمال».\nيعمل دون إنترنت. ابتعد عن الحديد لدقة أعلى.\n\nالاتجاه الحالي: ";
        tv.setText(help + "—");
        final double[] az = { Double.NaN };
        final android.hardware.SensorEventListener lis = new android.hardware.SensorEventListener() {
            final float[] R = new float[9], o = new float[3];
            @Override public void onSensorChanged(android.hardware.SensorEvent e) {
                android.hardware.SensorManager.getRotationMatrixFromVector(R, e.values);
                android.hardware.SensorManager.getOrientation(R, o);
                double a = Math.toDegrees(o[0]); if (a < 0) a += 360; az[0] = a;
                tv.setText(help + String.format(Locale.US, "%.0f°", a));
            }
            @Override public void onAccuracyChanged(android.hardware.Sensor s, int acc) {}
        };
        sm.registerListener(lis, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("تحديد الشمال بالبوصلة")
                .setView(tv)
                .setPositiveButton("تثبيت الشمال", (d, w) -> {
                    if (!Double.isNaN(az[0])) {
                        canvas.model().beginEdit(); canvas.model().northDeg = az[0]; canvas.externalChanged();
                        toast(String.format(Locale.US, "تم تثبيت الشمال عند %.0f°", az[0]));
                    } else toast("لم تُقرأ البوصلة بعد — انتظر لحظة وأعد المحاولة");
                })
                .setNeutralButton("تصفير", (d, w) -> { canvas.model().beginEdit(); canvas.model().northDeg = 0; canvas.externalChanged(); toast("أُعيد الشمال إلى الأعلى"); })
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.setOnDismissListener(x -> sm.unregisterListener(lis));
        dlg.show();
    }

    /** Layers as a simple bottom sheet: one toggle per layer, live show/hide. Hiding only flips a visible flag
     * (persisted in the JSON) — it never removes elements, so the data is always recoverable by re-showing. */
    private void showLayersSheet() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0}); box.setBackground(bg);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView t = new TextView(this); t.setText("الطبقات — إظهار / إخفاء"); t.setTextColor(0xff0B2F52);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); box.addView(t);
        TextView sub = new TextView(this); sub.setText("الإخفاء لا يحذف أي بيانات — أعِد الإظهار لتعود."); sub.setTextColor(0xff6E8698);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); sub.setPadding(0, dp(4), 0, dp(6)); box.addView(sub);
        for (final org.unrwa.yarmoukfield.drawing.DrawLayer l : canvas.model().layers) {
            CheckBox cb = new CheckBox(this); cb.setText(l.nameAr); cb.setChecked(l.visible);
            cb.setTextColor(0xff16323F); cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); cb.setPadding(dp(6), dp(10), dp(6), dp(10));
            cb.setOnCheckedChangeListener((b, checked) -> { l.visible = checked; canvas.invalidate(); save(); }); // B3: persist show/hide
            box.addView(cb);
        }
        Button done = barButton("تم", 0xff0E8A9C); done.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2); dp.setMargins(0, dp(10), 0, 0); box.addView(done, dp);
        d.setContentView(box);
        d.setOnDismissListener(x -> onChange()); // persist visibility on close
        Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0x66000000)); w.setGravity(Gravity.BOTTOM); w.setLayout(-1, -2); }
        d.show();
    }

    /** Wall-thickness picker (W1). If a wall is selected it changes THAT wall; otherwise it sets the thickness
     * applied to newly drawn walls. Never alters an existing wall's data unless the engineer explicitly picks. */
    private void showThicknessSheet() {
        final boolean toSelected = canvas.hasSelectedWall();
        final String[] items = {"10 سم", "15 سم", "20 سم", "مخصّص…"};
        final double[] vals = {0.10, 0.15, 0.20, -1};
        new AlertDialog.Builder(this)
                .setTitle(toSelected ? "سماكة الجدار المحدَّد" : "سماكة الجدران الجديدة")
                .setItems(items, (d, which) -> { if (vals[which] > 0) applyThickness(vals[which], toSelected); else promptCustomThickness(toSelected); })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void applyThickness(double m, boolean toSelected) {
        if (toSelected) { canvas.setSelectedWallThickness(m); toast("سماكة الجدار المحدَّد: " + cm(m)); }
        else { canvas.setNewWallThickness(m); toast("سماكة الجدران الجديدة: " + cm(m)); }
    }

    private void promptCustomThickness(final boolean toSelected) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint("السماكة بالسنتيمتر — مثال: 12"); in.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this).setTitle("سماكة مخصّصة (سم)").setView(in)
                .setPositiveButton("تعيين", (d, w) -> {
                    double c = 0; try { c = Double.parseDouble(in.getText().toString().trim()); } catch (Exception ignored) {}
                    if (c > 0) applyThickness(c / 100.0, toSelected); else toast("أدخل سماكة صحيحة بالسنتيمتر");
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private String cm(double m) { return String.format(Locale.US, "%.0f سم", m * 100); }

    private void onExport() {
        if (!canvas.model().isCalibrated()) { toast("لا يمكن التصدير قبل ضبط المقياس — اختر «معايرة» أولاً"); return; }
        final EditText h = new EditText(this);
        h.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        h.setHint("ارتفاع السقف بالمتر — اختياري (مثال: 3.20)");
        if (canvas.model().ceilingHeightM > 0) h.setText(String.format(Locale.US, "%.2f", canvas.model().ceilingHeightM));
        h.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(6), dp(6), dp(6), 0);
        TextView lbl = new TextView(this); lbl.setText("تصدير المخطط — اختر الصيغة"); lbl.setTextColor(0xff6E8698); lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); lbl.setPadding(dp(10), 0, dp(10), dp(6));
        box.addView(lbl); box.addView(h);
        new AlertDialog.Builder(this)
                .setTitle("تصدير المخطط")
                .setView(box)
                .setPositiveButton("PDF", (d, w) -> { applyCeiling(h); doExport(true); })
                .setNeutralButton("DXF", (d, w) -> { applyCeiling(h); doExport(false); })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void applyCeiling(EditText h) {
        double m = 0; try { m = Double.parseDouble(h.getText().toString().trim()); } catch (Exception ignored) {}
        if (m < 0) m = 0;
        canvas.model().ceilingHeightM = m; save();
    }

    private DrawExportMeta buildMeta() {
        DrawExportMeta meta = new DrawExportMeta();
        // Beneficiary + engineer come from the host (MainActivity.openHouseDrawEditor). Defaults are neutral —
        // never a fake sample name — so an empty field shows blank on the plan, not someone else's name.
        meta.benefName = extra("benef_name", "");
        meta.fileNo = extra("file_no", extra("draw_key", "—"));
        meta.floor = extra("floor", "طابق أول");
        meta.engineer = extra("engineer", "");
        meta.date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        double ch = canvas.model().ceilingHeightM;
        meta.ceiling = ch > 0 ? String.format(Locale.US, "h=%.2fm", ch) : "";
        return meta;
    }

    private String extra(String key, String def) {
        String v = getIntent() != null ? getIntent().getStringExtra(key) : null;
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private void doExport(boolean pdf) {
        try {
            File dir = new File(getExternalFilesDir(null), "exports"); dir.mkdirs();
            String base = "house_" + keyForFile();
            if (pdf) {
                File out = new File(dir, base + ".pdf");
                PdfExporter.export(this, canvas.model(), buildMeta(), out);
                toast("تم إنشاء PDF: " + out.getAbsolutePath());
            } else {
                File out = new File(dir, base + ".dxf");
                // B2: write as Windows-1256 so Arabic room names encode under the DXF's ANSI_1256 code page.
                try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(DxfWriter.toDxf(canvas.model()).getBytes(java.nio.charset.Charset.forName("windows-1256"))); }
                toast("تم إنشاء DXF: " + out.getAbsolutePath());
            }
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("تعذّر التصدير").setMessage(String.valueOf(e.getMessage())).setPositiveButton("حسناً", null).show();
        }
    }

    private String keyForFile() {
        String k = extra("draw_key", "debug");
        return k.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private void refreshButtons() {
        boolean u = canvas.model().canUndo(), r = canvas.model().canRedo();
        undoBtn.setEnabled(u); undoBtn.setAlpha(u ? 1f : .4f);
        redoBtn.setEnabled(r); redoBtn.setAlpha(r ? 1f : .4f);
    }

    private void refreshAssistant() {
        boolean cal = canvas.model().isCalibrated();
        updateCalibrateButton(); // UX: primary-bar «معايرة» appears only until a scale exists
        // A0: when the destructive tool is active, the banner turns into a clear red warning so it is never silent.
        if (canvas.tool() == DrawCanvasView.TOOL_DELETE) {
            assistant.setBackgroundColor(0xffF7DEDA); assistant.setTextColor(0xffB03A2E);
            assistant.setText("⚠ وضع الحذف مُفعّل — اضغط على أي عنصر لحذفه. للعودة للرسم اختر «تحديد» أو «جدار». (يمكنك التراجع بـ «تراجع».)");
            return;
        }
        assistant.setBackgroundColor(0xffE3F3F5); assistant.setTextColor(0xff0B2F52);
        if (canvas.tool() == DrawCanvasView.TOOL_CALIBRATE) {
            assistant.setText("📏 المعايرة: ارسم خطاً معلوم الطول (مثلاً جدار قِسته)، ثم أدخل طوله بالمتر لضبط المقياس.");
            return;
        }
        if (canvas.tool() == DrawCanvasView.TOOL_DOOR) {
            assistant.setText("🚪 وضع الباب: اضغط على الجدار نفسه لإضافة باب — تظهر مباشرةً خيارات العرض (70/80/90) والنوع واتجاه الفتح. اضغط عليه لاحقاً لتعديله، واسحبه لتحريكه على الجدار.");
            return;
        }
        if (canvas.tool() == DrawCanvasView.TOOL_WINDOW) {
            assistant.setText("🪟 وضع النافذة: اضغط على أي جدار لإضافة نافذة (تلتصق بالجدار). اضغط على النافذة لتغيير طولها أو حذفها، واسحبها لتحريكها على الجدار.");
            return;
        }
        if (cal && canvas.tool() == DrawCanvasView.TOOL_SELECT)
            assistant.setText("➤ اضغط على أي عنصر لتعديله: جدار ← الطول والزاوية · غرفة ← الاسم · نافذة ← الطول · باب ← اتجاه الفتح.");
        else if (cal) assistant.setText("➤ المقياس مضبوط. ارسم الجدران — تُضبط الزاوية القائمة تلقائياً، ويلتقط طرف الجدار الجدارَ الموازي (يظهر خط منقّط للمحاذاة). لضبط طول جدار بدقة: «تحديد» ثم اضغط عليه.");
        else assistant.setText("➤ المساعد الميداني: ابدأ بـ «📏 معايرة» من الشريط، وارسم خطاً معلوم الطول ثم أدخل طوله بالمتر. (لن تظهر الأبعاد أو يُسمح بالتصدير قبل المعايرة.)");
    }

    /** Each drawing is tied to a beneficiary RECORD, not one global file: the host passes the record's key
     * (registration/id) as the "draw_key" intent extra and we store house_&lt;key&gt;.json in a private folder.
     * Different beneficiaries never share a drawing. With no key (pure dev launch) we fall back to a debug file.
     * No DB change — the association is by file naming; M8 wires the beneficiary file to pass the real key. */
    private File resolveSaveFile() {
        String key = getIntent() != null ? getIntent().getStringExtra("draw_key") : null;
        if (key == null || key.trim().isEmpty()) return new File(getFilesDir(), "draw_debug.json");
        String safe = key.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        File dir = new File(getFilesDir(), "drawings"); dir.mkdirs();
        return new File(dir, "house_" + safe + ".json");
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(saveFile)) {
            out.write(DrawJson.toJson(canvas.model()).getBytes(StandardCharsets.UTF_8));
            saveState.setText("محفوظ ✓");
        } catch (Exception e) { saveState.setText("تعذّر الحفظ"); }
    }

    private void loadIfPresent() {
        if (!saveFile.exists()) return;
        try (InputStream in = new java.io.FileInputStream(saveFile)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            canvas.setModel(DrawJson.fromJson(bo.toString("UTF-8")));
        } catch (Exception ignored) { }
    }

    @Override protected void onPause() { super.onPause(); save(); }

    private Button tool(String label, int toolId) { return tool(label, toolId, 0xff0E8A9C); }

    private Button tool(String label, int toolId, int baseColor) {
        Button b = barButton(label, baseColor);
        b.setOnClickListener(v -> { canvas.setTool(toolId); highlightTools(); refreshAssistant(); });
        b.setTag(toolId);
        toolBtns.add(b); toolBase.put(b, baseColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(3), 0, dp(3), 0); b.setLayoutParams(lp);
        return b;
    }

    /** A0: the active tool must be obvious. The current tool gets a white ring + bold; the rest stay flat. This is
     * what stops the engineer from unknowingly drawing (or deleting) with the wrong tool selected. */
    private void highlightTools() {
        int cur = canvas.tool();
        for (Button b : toolBtns) {
            boolean active = b.getTag() != null && (Integer) b.getTag() == cur;
            int base = toolBase.get(b);
            GradientDrawable g = new GradientDrawable();
            g.setColor(base); g.setCornerRadius(dp(10));
            if (active) g.setStroke(dp(3), 0xffFFFFFF);
            b.setBackground(g);
            b.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            b.setPadding(dp(14), dp(6), dp(14), dp(6));
        }
    }

    /** A0: a slim vertical divider that visually groups the toolbar (primary tools | secondary | delete). */
    private View divider() {
        View v = new View(this); v.setBackgroundColor(0xffC3D0D8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), -1); lp.setMargins(dp(6), dp(4), dp(6), dp(4)); v.setLayoutParams(lp);
        return v;
    }

    private Button barButton(String text, int color) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(10)); b.setBackground(g);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setMinimumWidth(0); b.setMinWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        return b;
    }

    private View space() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1)); return v; }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * density); }
}
