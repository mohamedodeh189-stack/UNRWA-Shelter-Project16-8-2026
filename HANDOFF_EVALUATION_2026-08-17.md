# HANDOFF — ميزة «التقييم» (KoBo) + حالة المشروع الحالية

**التاريخ:** 2026-08-17 · **الإصدار الحالي:** `v20.1` (versionCode `2001`) · **الفرع:** `claude/yarmouk-shelter-review-a07y4t`
**المستودع:** `mohamedodeh189-stack/UNRWA-Shelter-Project16-8-2026`

> هذا الملف مكتوب ليُسلَّم لجلسة/وكيل جديد تماماً (Manus أو غيره) ويتابع العمل **بدون إعادة اكتشاف أي شيء**.
> اقرأه كاملاً قبل لمس أي كود. للسياق التاريخي الأقدم راجع `HANDOFF_MASTER.md` و`HANDOFF.md` (لكن انتبه: بعض ما فيهما قديم — مسارات ويندوز `F:\` وإصدار 19.1؛ العمل الآن يجري في مستودع Git على Linux).

---

## 0. ما هو التطبيق باختصار

تطبيق أندرويد عربي **يعمل بدون إنترنت إطلاقاً** لمهندسي الأونروا في مخيم اليرموك: إدخال مستفيد ← دراسة منزل (عنوان، أضرار، صور، كميات BOQ، مخطط) ← مراجعة مكتبية ← اعتماد ← متابعة تنفيذ ← دفعات. الحزمة `org.unrwa.yarmoukfield`، Java خالص، **بدون Gradle**.

---

## 1. قواعد ثابتة لا تُكسر (اقرأها قبل أي شيء)

1. **لا صلاحية `INTERNET` أبداً.** تحقق بعد كل بناء:
   `aapt2 dump permissions <apk>` — يجب أن تظهر فقط: `CAMERA`, `ACCESS_FINE_LOCATION`, `READ_CONTACTS`, `WRITE_CONTACTS`.
2. **بيانات المستفيدين الحقيقية مقدّسة.** لا تُنشأ/تُعدَّل/تُحذف كأثر جانبي لاختبار. لا تُرفع أبداً إلى Git ولا تُضمَّن في APK.
3. **ملف كوبو (KoBo) لا يدخل المستودع ولا التطبيق إطلاقاً.** يحوي أسماء ٣٨٠+ أسرة حقيقية وأرقام هوية وحالات صحية/إعاقة. هو **استيراد محلي لمرة واحدة على كل جهاز** فقط (انظر §4).
4. **كلمة سر مفتاح التوقيع لا تُكتب ولا تُطبع ولا تُسجَّل في أي ملف أو مستند.** المفتاح: `signing/yarmouk-field-release.jks`، الاسم المستعار `yarmouk-field`. كلمة السر تُزوَّد وقت البناء عبر متغير بيئة فقط. تعليمات الحفظ عند المكتب في `signing/KEEP_SAFE_AR.txt`.
5. **بصمة الإصدار الرسمية** (تحقق منها بعد كل توقيع، وإلا لا تُوزَّع النسخة):
   `SHA-256 = d2c1cfc6f33ba0fcc12efd33e12098ad6d50dd832a1f8e88feb774337c6e8566`
6. **التطوير على الفرع `claude/yarmouk-shelter-review-a07y4t` فقط.** لا دفع إلى `main` بدون إذن صريح.
7. **ترحيلات قاعدة البيانات إضافية فقط** — الأعمدة تُضاف ولا تُحذف/تُعاد تسميتها.

---

## 2. البناء والتوقيع (أوامر فعلية مجرَّبة على Linux)

> `BUILD_ANDROID.ps1` سكربت PowerShell للويندوز. على Linux استُخدم نفس التسلسل يدوياً وهو ما تم به بناء كل نسخ هذه الجلسة.

```bash
SDK=<path>/android-sdk            # commandlinetools + platforms;android-37.0 + build-tools;36.0.0
BT=$SDK/build-tools/36.0.0
PLATFORM=$SDK/platforms/android-37.0/android.jar
PROJ=/path/to/UNRWA-Shelter-Project16-8-2026
BUILD=/tmp/build && rm -rf $BUILD && mkdir -p $BUILD/{generated,classes,dex}

$BT/aapt2 compile --dir $PROJ/app/src/main/res -o $BUILD/compiled-res.zip
$BT/aapt2 link -o $BUILD/yarmouk-unsigned.apk -I $PLATFORM \
  --manifest $PROJ/app/src/main/AndroidManifest.xml -A $PROJ/app/src/main/assets \
  --java $BUILD/generated --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 36 \
  --version-code 2001 --version-name '20.1' $BUILD/compiled-res.zip

find $PROJ/app/src/main/java -name "*.java" > $BUILD/sources.txt
echo "$BUILD/generated/org/unrwa/yarmoukfield/R.java" >> $BUILD/sources.txt
javac -nowarn -source 17 -target 17 -encoding UTF-8 -classpath $PLATFORM -d $BUILD/classes @$BUILD/sources.txt

$BT/d8 --release --min-api 24 --lib $PLATFORM --output $BUILD/dex \
  $(cd $BUILD/classes && find . -name "*.class" -printf "$BUILD/classes/%p\n")

cp $BUILD/yarmouk-unsigned.apk $BUILD/with-dex.apk
(cd $BUILD/dex && zip -q -X $BUILD/with-dex.apk classes.dex)
$BT/zipalign -f -p 4 $BUILD/with-dex.apk $BUILD/aligned.apk

export SIGNPW='<كلمة السر — من متغير بيئة، لا تُكتب في ملف>'
$BT/apksigner sign --ks $PROJ/signing/yarmouk-field-release.jks \
  --ks-key-alias yarmouk-field --ks-pass env:SIGNPW --key-pass env:SIGNPW \
  --out $BUILD/Yarmouk_Camp_Field_Android_v20.1.apk $BUILD/aligned.apk
unset SIGNPW

$BT/apksigner verify --print-certs $BUILD/Yarmouk_Camp_Field_Android_v20.1.apk   # قارن SHA-256 مع §1.5
```

**فحص سريع بدون بناء كامل:**
```bash
javac -nowarn -source 17 -target 17 -classpath build_check/android.jar -d /tmp/cc \
  $(find app/src/main/java -name "*.java") build_check/generated/org/unrwa/yarmoukfield/R.java
```

---

## 3. البنية الأساسية لملفات Excel (مهم جداً)

التطبيق **لا يملك أي مكتبة Excel** (لا POI ولا غيرها). كل شيء مكتوب يدوياً بـ `java.util.zip` فقط:

| الملف | الدور |
|---|---|
| `BeneficiaryXlsx.java` | **كاتب**: ينسخ قالب `.xlsx` بايت-ببايت ويعدّل خلايا محددة عبر regex على XML الخام |
| `SpreadsheetImporter.java` | **قارئ**: يفك zip ويقرأ `sharedStrings.xml` + أوراق العمل عبر SAX |
| `app/src/main/assets/quantities_template.xlsx` | القالب المُجمَّع داخل التطبيق (٧ أوراق) |

**ترتيب الأوراق في القالب الحالي** (الترتيب الفيزيائي مهم — الثوابت في `BeneficiaryXlsx` تعتمد عليه):

| الترتيب | الاسم | الملف الفيزيائي | الثابت في الكود |
|---|---|---|---|
| 1 | التقييم | `xl/worksheets/sheet1.xml` | `EVAL_SHEET` |
| 2 | الكميات | `xl/worksheets/sheet2.xml` | `QTY_SHEET` |
| 3 | المخطط | `sheet3.xml` | — |
| 4 | الوثائق | `sheet4.xml` | — |
| 5 | صور قبل الترميم | `sheet5.xml` | `PHOTO_SHEET` |
| 6 | صور بعد الترميم | `sheet6.xml` | — |
| 7 | نسبة الإنجاز | `sheet7.xml` | — |

> ⚠️ لو أُعيد توليد القالب وتغيّر ترتيب الأوراق، **يجب تحديث `QTY_SHEET` / `PHOTO_SHEET` / `EVAL_SHEET` يدوياً** وإلا سيكتب التطبيق في الورقة الخطأ بصمت.

---

## 4. ميزة «التقييم» — التصميم الكامل

### 4.1 دورة العمل اليدوية التي تحاكيها الميزة
الفريق يدوياً: يفتح ملف كوبو ← يبحث عن المستفيد ← ينسخ سطره كاملاً ← يلصقه في ورقة «التطبيق» **السطر ٢** ← ورقة «التقييم» (المليئة بمعادلات `='التطبيق'!X2`) تُظهر التقييم كاملاً.

التطبيق يفعل نفس الشيء تلقائياً، لكن **كقيم ثابتة منسوخة بلا أي ارتباط حي** (طلب صريح من المستخدم).

### 4.2 مسار البيانات
```
ملف كوبو .xlsx  ──(استيراد لمرة واحدة، محلي)──►  EvaluationImporter
                                                       │ يقرأ ورقة «التطبيق»
                                                       │ يفهرس بعمود X
                                                       ▼
                                   getFilesDir()/kobo_evaluation_2026.json   ← لا يغادر الجهاز أبداً
                                                       │
عند تصدير دراسة ──► findByRegistration(b.registration) ─┤
                                                       ▼
                              BeneficiaryXlsx.write(... , registration, evaluation, out)
                                                       ▼
                                      ورقة «التقييم» = أول ورقة، معبّأة بقيم ثابتة
```

### 4.3 ⚠️ مفتاح المطابقة (أخطر نقطة في الميزة)
- `AppDatabase.Beneficiary.registration` يحمل **«1.4 - رقم تسجيل العائلة»** — صيغته `1-XXXXXXXX` — وهو **العمود `X`** في ورقة «التطبيق».
- «1.5 - رقم التسجيل الفردي» (`2-XXXXXXXX`، العمود `Y`) **رقم مختلف تماماً**، يُعرض فقط في الخلية `B7` ولا يُستخدم للمطابقة إطلاقاً.
- استخدام العمود الخطأ يجعل **كل** عمليات البحث تفشل **بصمت بلا أي رسالة خطأ**، فتخرج كل خانات التقييم فارغة. هذا بالضبط ما حدث وكلّف عدة إصدارات.

### 4.4 خريطة أعمدة كوبو → مفاتيح التطبيق
> **المصدر الموثوق:** معادلات ورقة «التقييم» في ملف كوبو المرجعي نفسه (كل خلية فيها `='التطبيق'!<عمود>2`). **لا تُستنتج الأعمدة من نص العناوين** — العمود `M` يحمل نفس عنوان العمود `W` لكنه فارغ دائماً، والبحث النصي يلتقط الفارغ أولاً.

| المفتاح | عمود كوبو | خلية «التقييم» |
|---|---|---|
| `NAME` | `W` | `B1` |
| `NAME2` (نفس الاسم مكرر) | `W` | `B5` |
| `RESEARCHER` | `H` | `E1` |
| `ENGINEER` | `I` | `D1` |
| `VISITDATE` *(تاريخ تسلسلي)* | `A` | `F1` |
| `AGE` *(تاريخ ميلاد تسلسلي)* | `AE` | `D3` |
| `GENDER` | `AD` | `F3` |
| `MARITAL` | `AG` | `D4` |
| `RESIDENCE` | `AH` | `F4` |
| `FAMILIES_IN_HOUSE` | `AL` | `H4` |
| `SECONDWIFE` | `AK` | `D5` |
| `FAMILYREG` **(مفتاح المطابقة)** | `X` | `B6` |
| `OWNERSHIP` | `AM` | `D6` |
| `OWNERDOCS` | `AO` | `E6` |
| `REG` (الرقم الفردي) | `Y` | `B7` |
| `PLOTREF` | `AV` | `D7` |
| `LEDGER` | `Z` | `B8` |
| `BUILDINGNO` | `AX` | `D8` |
| `STREET` | `AW` | `F8` |
| `PHONE1` | `BB` | `D9` |
| `ADDRESS` | `BA` | `F9` |
| `NATID` | `AA` | `B10` |
| `PHONE2` | `BC` | `D10` |
| `HOUSETYPE` | `AY` | `F10` |
| `FLOOR` | `AZ` | `H10` |
| `SCORE_CHRONIC` | `BI` | `G14` |
| `SCORE_MENTAL` | `BJ` | `G16` |
| `SCORE_VULN` | `BL` | `G19` |
| `SCORE_CROWD` | `BM` | `G21` |
| `SCORE_GENDERSEP` | `BN` | `G23` |
| `SCORE_INCOME` | `BO` | `G25` |
| `SCORE_STRUCT_DEVIATION` | `CC` | `C30` |
| `SCORE_STRUCT_CRACKING` | `CD` | `D30` |
| `SCORE_STRUCT_SPALLING` | `CE` | `E30` |
| `SCORE_STRUCT_STABILITY` | `CF` | `F30` |
| `SCORE_STRUCT_TOTAL` | `CG` | `G28` |
| `SCORE_TOILET` | `CI` | `G32` |
| `SCORE_KITCHEN` | `CJ` | `G34` |
| `SCORE_VENTILATION` | `CK` | `G36` |
| `SCORE_DAMPNESS` | `CL` | `G38` |
| `SCORE_SEWAGE` | `CM` | `G40` |
| `GRAND_TOTAL` | `CO` | `G42` |

**مجاميع تُحسب داخل التطبيق** (`addComputedSubtotals`) لأن الأصل معادلات ونحن ننسخ قيماً ثابتة:
- `HEALTH_SUB` (`G13`) = CHRONIC + MENTAL
- `SOCIAL_SUB` (`G18`) = VULN + CROWD + GENDERSEP + INCOME
- `HEALTHSOCIAL_TOTAL` (`G12`) = HEALTH_SUB + SOCIAL_SUB
- `OTHERCOND_SUB` (`G31`) = TOILET + KITCHEN + VENTILATION + DAMPNESS + SEWAGE
- `PHYSICAL_TOTAL` (`G27`) = OTHERCOND_SUB + STRUCT_TOTAL
- `GRAND_TOTAL` يؤخذ من العمود `CO` مباشرة؛ ولا يُحسب إلا إذا كان المصدر فارغاً (حتى يبقى أي تعارض حقيقي بالبيانات ظاهراً بدل إخفائه).

**ملاحظات:**
- `B9` («عدد افراد الاسرة المسجلين») **فارغة دائماً بالتصميم** — لا مصدر لها في النموذج المرجعي أصلاً.
- الأعمدة `A` و`AE` قيم **تاريخ تسلسلية** لإكسل (مثل `46047.55`) وتُحوَّل إلى `yyyy-MM-dd` عبر `excelSerialToDate()` — تستخدم `java.util.Calendar` **وليس `java.time`** لأن `minSdkVersion = 24` و`java.time` يتطلب 26 بدون desugaring (غير مفعّل هنا).
- الاسم ورقم تسجيل العائلة يُملآن **دائماً** من بيانات التطبيق نفسه حتى بدون مطابقة كوبو؛ بقية الخانات تبقى فارغة عند عدم وجود مطابقة — **ولا تُخمَّن أبداً**.

### 4.5 الملفات المعنية
| الملف | الدور |
|---|---|
| `EvaluationImporter.java` | استيراد كوبو، الفهرسة بالعمود `X`، التخزين المحلي، `findByRegistration` |
| `EvaluationRecord.java` | POJO بسيط: `registration` + `name` + `values` |
| `BeneficiaryXlsx.java` | `EVAL_CELL_BY_KEY` + حقن القيم عبر `injectInlineText` |
| `MainActivity.java` | زر الاستيراد في الإعدادات (`chooseEvaluationImport` / `importEvaluationFile`, `REQUEST_EVALUATION_IMPORT=4116`) + استدعاء `findByRegistration` عند التصدير |
| `SpreadsheetImporter.java` | صلاحية بعض الدوال وُسِّعت (package-private) لإعادة استخدامها |

### 4.6 نقاط الاستدعاء عند التصدير (كلاهما مربوط)
- `exportQuantitiesXlsx()` — زر «⤓ تصدير ملف الكميات (Excel)» (مشاركة سريعة).
- `genQuantitiesBytes()` — ضمن «تجهيز ملف المستفيد» (حفظ تلقائي داخل مجلد المشروع).

---

## 5. أعطال حقيقية حدثت — لا تكررها

### 5.1 أربعة أسباب مختلفة لرسالة Excel «We found a problem with some content»
كلها ظهرت بنفس الرسالة تماماً، وكلها **لا يكتشفها** `openpyxl` ولا فحص «XML سليم شكلياً» — Excel وحده صارم بما يكفي:
1. **`localSheetId` قديم**: إدراج ورقة أولاً أزاح «الكميات» من الموضع 0 إلى 1، بينما `definedNames` (`Print_Area`/`Print_Titles`) بقيت تشير إلى 0.
2. **خلايا `t="inlineStr"` بلا عنصر `<is>`**: خلية معلَّن نوعها نص مضمّن لكن فارغة = مخالفة للمخطط.
3. **`docProps/app.xml` لم يُحدَّث**: بقي يعلن ٦ أوراق وأسماءها القديمة بينما الملف صار ٧.
4. **بادئة `r:` غير معرّفة**: `<drawing r:id="..."/>` أُضيف لورقة لا تعلن `xmlns:r` (ظهر بعد إعادة توليد القالب بـ openpyxl).

**الدرس العملي:** التعديل اليدوي على XML الخام لحزمة OOXML هشّ جداً — كل جزء مشترك يجب أن يبقى متسقاً. الحل المعتمد: **إعادة توليد القالب بالكامل عبر `openpyxl`** (يضمن الاتساق تلقائياً) بدل الترقيع، ثم تحديث ثوابت الأوراق في الكود.

### 5.2 خلايا القالب غير المُفرَّغة
`injectInlineText` **لا يستبدل خلية غير فارغة**. عند بناء القالب من ورقة مرجعية تحوي بيانات مثال حقيقية، يجب تفريغ **كل** الخلايا الديناميكية — وإلا ستظهر بيانات المثال لكل مستفيد. (حدث فعلاً في ٦ خلايا: `D1`,`E1`,`F1`,`H4`,`E6`,`F10`.)

### 5.3 تشخيص خاطئ بسبب غياب رقم الإصدار
ملف مُصدَّر من نسخة قديمة بدا وكأنه من الجديدة، فبدت المشكلة غير محلولة. **حُلّت** بإظهار الإصدار في الإعدادات (v20.1).
**علامة تشخيصية مفيدة:** إذا ظهر رقم تسجيل العائلة في `B7` وكانت `B6` فارغة ⇒ النسخة **قديمة** (أقدم من v20.0). الصحيح: `B6` = رقم العائلة، `B7` = الرقم الفردي.

---

## 6. اختبار الانحدار الشامل (شغّله بعد أي تعديل على الميزة)

`tests/EvaluationExportE2E.java` + `tests/eval_e2e_stubs/` — يشغّل **الكود الحقيقي** للمراحل الثلاث (استيراد ← بحث ← كتابة) على JDK عادي.

```bash
GSON=/opt/gradle-8.14.3/lib/gson-2.10.jar     # أو أي gson
javac -d /tmp/stubs -cp $GSON tests/eval_e2e_stubs/android/net/Uri.java \
      tests/eval_e2e_stubs/android/content/*.java tests/eval_e2e_stubs/org/json/*.java
javac -d /tmp/stubs -cp "/tmp/stubs:/tmp/appclasses:$GSON" tests/EvaluationExportE2E.java

LANG=C.UTF-8 java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
  -cp "/tmp/stubs:/tmp/appclasses:$GSON:build_check/android.jar" EvaluationExportE2E \
  <appDataDir> <koboExport.xlsx> <out.xlsx> <رقم-التسجيل> "<الاسم>"
```
> **مهم:** النسخ الاختبارية (`/tmp/stubs`) يجب أن تسبق `android.jar` في مسار التشغيل لتحجب نسخه التي ترمي `Stub!`.
> **مهم:** مرّر `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` وإلا تتشوّه الوسائط العربية.

**المتوقع:** `count > 0`، `MATCHED`، وكل خانات التقييم معبّأة.
**آخر نتيجة مؤكدة:** مطابقة **189/189** خانة مع الورقة الصحيحة يدوياً، **صفر اختلاف**.

---

## 7. سجل إصدارات هذه الجلسة

| الإصدار | المحتوى |
|---|---|
| v19.1 – v19.2 | أول بناء APK موقّع فعلياً من بيئة Linux |
| v19.3 | ورقة «التقييم» أُضيفت كأول ورقة |
| v19.4 | إصلاح `localSheetId` + ربط زر التصدير الفعلي ببيانات التقييم |
| v19.5 | إصلاح خلايا `inlineStr` الفارغة |
| v19.6 | إصلاح `docProps/app.xml` |
| v19.7 | إعادة توليد القالب بالكامل عبر openpyxl + إصلاح `xmlns:r` |
| v19.8 | مطابقة التنسيق الكامل للنموذج المرجعي |
| v19.9 | استخراج كل الحقول بالأعمدة الثابتة + تحويل التواريخ |
| **v20.0** | **إصلاح مفتاح المطابقة (1.4 بدل 1.5) — الإصلاح الحاسم** |
| **v20.1** | إظهار رقم الإصدار في الإعدادات (الحالي) |

---

## 8. ما تبقّى / خطوات مفتوحة

1. **تأكيد ميداني نهائي**: تثبيت v20.1 ← التحقق من ظهور «إصدار التطبيق: v20.1 (2001)» في الإعدادات ← **إعادة استيراد ملف كوبو** (إلزامي: التخزين المحلي القديم مفهرس بالرقم الخطأ) ← تصدير دراسة جديدة.
2. **تنظيف تاريخ Git** (من مرحلة سابقة، لم يُنفَّذ): كلمة سر التوقيع وبيانات مستفيدين حقيقية ما زالت موجودة في **تاريخ** المستودع رغم إزالتها من الشجرة الحالية. يتطلب `git filter-repo` — كان محظوراً في بيئة الجلسة، ويجب أن يُنفَّذ يدوياً من جهاز المكتب، ثم **تدوير كلمة سر المفتاح**.
3. **خانات بلا مصدر مؤكد** (فارغة حالياً بالقصد): صفوف الخيارات الثابتة `F5/G5/H5` و`F6/G6/H6` هي نصوص شرح ثابتة في النموذج وليست بيانات — تُركت كما هي.
4. `HANDOFF.md` و`HANDOFF_MASTER.md` يحويان مسارات ويندوز وإصدارات قديمة — يُفضّل تحديثهما أو الإشارة إلى هذا الملف.

---

## 9. فهرس ملفات سريع

```
app/src/main/java/org/unrwa/yarmoukfield/
  MainActivity.java            ← الواجهة كاملة (~3.7k سطر): الإعدادات، التصدير، الخريطة، النسخ الاحتياطي
  AppDatabase.java             ← SQLite: Beneficiary.registration = رقم تسجيل العائلة
  BeneficiaryXlsx.java         ← كاتب xlsx (zip/regex) + EVAL_CELL_BY_KEY
  SpreadsheetImporter.java     ← قارئ xlsx (SAX)
  EvaluationImporter.java      ← استيراد كوبو + المطابقة (العمود X)
  EvaluationRecord.java        ← POJO
  OperationalMapView.java      ← الخريطة التشغيلية (قطاعات/نقاط/شوارع)
  VerifiedStreets.java         ← شوارع OSM حقيقية (ODbL)
app/src/main/assets/
  quantities_template.xlsx     ← القالب (٧ أوراق، التقييم أولاً)
  verified_streets.json        ← بيانات شوارع مفتوحة المصدر
tests/
  EvaluationExportE2E.java     ← اختبار الانحدار الشامل
  eval_e2e_stubs/              ← نسخ اختبارية لـ android + org.json
signing/
  yarmouk-field-release.jks    ← مفتاح الإصدار (كلمة السر خارج المستودع)
```

---

## 10. ملاحظة أخيرة لمن يتابع

أكبر درس من هذه الجلسة: **الميزة كانت تمر بثلاث مراحل، كل واحدة تُختبر منفردة وتنجح، والعطل كان في الوصلة بينها** (مفتاح المطابقة). أي فحص جزئي كان يعطي نتيجة خضراء بينما الناتج النهائي فارغ. لذلك: **اختبر السلسلة كاملة من طرف لطرف قبل أي شحن** — واختبار §6 موجود تحديداً لهذا الغرض.
