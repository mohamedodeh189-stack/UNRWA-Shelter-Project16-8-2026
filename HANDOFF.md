# HANDOFF - Yarmouk Camp Shelter Android

هذا الملف هو نقطة البدء المرجعية لأي جلسة Codex جديدة. تمت كتابته بعد فحص ملفات المشروع الفعلية في 2026-08-02، وليس اعتماداً على المحادثة وحدها.

## 1. مكان المشروع وحالة Git

- جذر المشروع: `C:\Users\Administrator\Pictures\New folder\Yarmouk_Camp_Shelter_Android`
- هذا المجلد **ليس مستودع Git**: لا توجد `.git`، لذلك تفشل أوامر `git status` و`git log` برسالة `not a git repository`، ولا توجد قائمة موثوقة بالملفات tracked/untracked أو سجل commits أو baseline للمقارنة.
- لا تبدأ Git أو تحذف الإصدارات القديمة قبل إنشاء نسخة احتياطية منفصلة، وبالأخص مجلد `signing`.

## 2. الهدف الأصلي للمشروع

تطبيق Android عربي ميداني يعمل دون إنترنت لإدارة مشروع ترميم مساكن في مخيم اليرموك على مرحلتين:

1. **الدراسات**: يبدأ المشروع دون قائمة مسبقة؛ يضيف الموظف المستفيد أثناء زيارة المنزل، ويسجل الاسم والهاتف والعنوان التفصيلي ووصف الأضرار، ويلتقط صور «قبل الترميم»، ويربط مخطط المنزل، ويدخل كميات الأعمال من جدول مرجعي مع حساب التكلفة والدفعتين.
2. **متابعة التنفيذ**: بعد اعتماد 50 دراسة تُصدر دفعة متابعة، ثم تُسجل نسبة الإنجاز والحالة والملاحظات وصور «أثناء الترميم» و«بعد الترميم».

الهدف الإضافي هو توفير دليل قطاعات وشوارع مخيم اليرموك، مخططات PDF وCAD، استيراد/تصدير البيانات، طباعة مسار الزيارات، وحماية محلية مناسبة للبيانات.

## 3. الإصدار الحالي والمنتجات النهائية

- الإصدار: `1.5.7`
- `versionCode`: `157`
- الحزمة: `org.unrwa.yarmoukfield`
- أقل Android: API 24 / Android 7.0
- Target SDK: 36
- قاعدة البيانات: الإصدار 5
- مخطط JSON الاحتياطي: 1.3
- APK النهائي: `dist/Yarmouk_Camp_Field_Android_v1.5.7.apk`
- نسخة APK في الجذر: `Yarmouk_Camp_Field_Android_v1.5.7.apk`
- SHA-256 لكليهما: `28FC0530A5BCEEE8B540F3D48F8E1A75AC48F3598E725836E5800604CDC9D39B`
- حزمة التسليم: `Yarmouk_Camp_Field_Android_v1.5.7_Delivery.zip`
- حزمة المصدر والتوقيع الخاصة: `Yarmouk_Camp_Field_Android_v1.5.7_Private_Source_Signing_Backup.zip`
- دليل المستخدم: `output/pdf/Yarmouk_Camp_Field_User_Guide_AR_v1.5.7.pdf`
- المخطط الكامل المحسن: `output/pdf/yarmouk_camp_map_optimized.pdf`
- المخطط المقسم المحسن: `output/pdf/yarmouk_sector_maps_optimized.pdf`

التوقيع الحالي اجتاز APK Signature Scheme v2 وv3. مفتاح الإصدار هو `signing/yarmouk-field-release.jks` والـalias هو `yarmouk-field`. لا تشارك حزمة المصدر الخاصة أو المفتاح مع المستخدمين النهائيين.

## 4. ما تم إنجازه

### سير العمل والبيانات

- مشاريع مستقلة محلياً، وقوائم دراسات ومتابعة منفصلة.
- إضافة مستفيد ميدانياً من دون قائمة مسبقة.
- تخزين SQLite مع migrations تحفظ البيانات القديمة حتى DB v5.
- اعتماد الدراسة يتطلب عنواناً، وصف أضرار، كمية موجبة ومبلغاً موجباً، مخطط منزل، وصور قبل الترميم.
- نقل أول 50 دراسة جاهزة إلى دفعة متابعة واحدة؛ الزائد يبقى للدفعة التالية.
- متابعة بنسبة إنجاز 0-100 وحالات مكتمل/قيد التنفيذ/مراجعة/تعذر الوصول؛ الاكتمال يضبط النسبة إلى 100.
- نسخ احتياطي واستعادة JSON يشمل المستفيدين والمراحل والكميات والمخططات وفهرس الصور، لكنه لا يضم ملفات الصور نفسها.
- استيراد XLSX/CSV/JSON، مع إصلاح مسار `XMLReader`. ملفات `.xls` القديمة يجب تحويلها إلى `.xlsx`.

### الصور والمخططات الشخصية

- اختيار مجلد صور محلي عبر Storage Access Framework.
- إنشاء مجلدات المشروع/المستفيد/المرحلة تلقائياً:
  - `01 - قبل الترميم`
  - `02 - أثناء الترميم`
  - `03 - بعد الترميم`
- التقاط الكاميرا مباشرة إلى الملف المقصود وتسجيل فهرس الصور في SQLite.
- إرفاق مخطط منزل بصيغة PDF أو DXF أو DWG أو صورة وفتحه بتطبيق مناسب.

### جدول الكميات والحساب

- المرجع مأخوذ من `F:\Project_Data17\Reference.xlsx`، ورقة `الكميات`، 37 بنداً.
- الأصل المضمّن: `app/src/main/assets/boq_reference.json`.
- المستخدم يدخل الكمية فقط؛ التطبيق يحسب subtotal والإجمالي بالدولار.
- حاسبة لكل بند بحسب الوحدة:
  - M²: الطول × العرض × العدد.
  - M3: الطول × العرض × الارتفاع × العدد.
  - MR: الطول × العدد.
  - kg: الكمية × وزن الوحدة × العدد.
  - الوحدات الأخرى: العدد × المعامل.
- يمكن استبدال الكمية بنتيجة الحاسبة أو إضافتها إلى القيمة الحالية.
- الدفعتان 60% و40% تعرضان أعداداً صحيحة دون كسور، وتُعدل الثانية بحيث يساوي مجموعهما الإجمالي المقرب.
- نوافذ الكميات تستخدم `adjustResize` وتمرر تلقائياً إلى الحقل النشط كي لا تغطيه لوحة المفاتيح.

### العناوين والقطاعات والمسار والطباعة

- مرجع العناوين في `app/src/main/assets/yarmouk_addresses.json` وتصنيف تلقائي للقطاع.
- ترتيب القطاعات في التطبيق: شرق 1، غرب 1، شرق 2، غرب 2، شرق 3، غرب 3، العروبة والتقدم.
- شاشة خريطة تفاعلية مبنية على المخطط الكامل، بحدود ملونة شفافة وتسميات صغيرة قابلة للضغط.
- حدود القطاعات الحالية في `SectorMapView.AREAS` أُعيد تتبعها يدوياً من العلامات الخضراء التي أرسلها المستخدم؛ وهي المرجع الحالي لكنها ليست حدود GIS/مساحية.
- الضغط على القطاع يفتح الصفحة المقابلة من PDF المقسم. تحويل ترتيب الصفحات موجود في `MainActivity.SECTOR_PDF_PAGES`.
- طباعة مسار الزيارات تدعم ترقيم الصفحات ونطاق صفحات وعناوين مرتبة.

### المخططات وPDF وCAD

- آخر تغيير مكتمل: استبدال المخطط الكامل القديم بملف المستخدم الجديد الواضح:
  `C:\Users\Administrator\Downloads\مخطط مخيم اليرموك.pdf`.
- نُسخ المصدر الجديد إلى `source_maps/original_yarmouk_camp_map.pdf`، وأُرشف السابق في:
  `source_maps/archive/original_yarmouk_camp_map_pre_v1.5.7.pdf`.
- أُعيد إنشاء:
  - `output/pdf/yarmouk_camp_map_optimized.pdf`
  - `app/src/main/res/raw/yarmouk_camp_map.pdf`
  - `app/src/main/res/drawable-nodpi/yarmouk_camp_overview.png` بحجم 1172×1501.
- فُحص المخطط النهائي بصرياً عند رندر مرتفع حتى 500 DPI؛ أسماء الشوارع في المصدر الجديد أوضح من السابق وتبقى قابلة للقراءة عند التكبير.
- SHA-256 لنسخة PDF الكاملة المضمّنة: `D436750FB885A2A154FCEE7173E9C0635827CBA2EEC8BF77A2C227BD444FFC67`.
- عارض PDF داخلي دون إنترنت يدعم pinch zoom والسحب، رندر بحسب ذاكرة الهاتف، وضبط الصفحة.
- اسم القطاع يظهر في رأس مرتفع يقبل سطرين، وفي شارة ثابتة عالية التباين فوق المخطط كي لا يختفي أثناء التكبير.
- ملفات CAD المضمّنة:
  - `app/src/main/assets/yarmouk_camp_map.dwg`
  - `app/src/main/assets/yarmouk_sector_maps.dxf`
- خط `NART.TTF` مضمّن في assets وقابل للتصدير. يوجد شرح عربي داخل التطبيق: حفظ الخط، ثم FastView ← القائمة ← Settings/الإعدادات ← Fonts/الخطوط ← `+` ← اختيار `NART.TTF` ثم إعادة فتح الرسم.
- PDF المقسم يضم خط NART. إذا بقيت كتابة CAD مشوهة في FastView فقد تكون النصوص/الأشكال من المصدر نفسه؛ استخدم PDF كمرجع ثابت.

### الواجهة وسهولة الاستخدام

- خطوط الأزرار والتنقل والكميات والحاسبة مكبرة للعمل الميداني.
- Insets لشريط الحالة وشريط التنقل وdisplay cutout على Android الحديث والقديم؛ الأزرار السفلية ترتفع فوق أزرار الهاتف.
- رأس التطبيق قابل للتوسيع/التصغير بالضغط أو السحب من المقبض، لعرض اسم المصمم ووصف التطبيق والشكر، ويعمل في الاتجاهين.
- لقطات الشاشة مسموحة؛ `FLAG_SECURE` غير موجود.
- التصميم والتطوير: المهندس محمد أمين عودة.
- شكر وتقدير: المهندس عبد المالك أبو حرب، المهندس محمد عبد العال، والمهندسة يمام أمين عودة.

### الحماية

- PIN إلزامي من 6 أرقام، محفوظ كبصمة PBKDF2 مع salt وليس كنص صريح.
- PBKDF2-HMAC-SHA256 عند توفره، وإلا SHA1 للتوافق.
- قفل بعد 3 دقائق في الخلفية، وحظر 30 ثانية بعد خمس محاولات خاطئة.
- `android:allowBackup="false"` و`android:fullBackupContent="false"`.
- لا يوجد إذن INTERNET، وcleartext معطل.
- `CadFileProvider` غير exported ويمنح URI مؤقتاً للقراءة فقط.
- السماح بلقطات الشاشة قرار مقصود بطلب المستخدم؛ يجب تنبيه المستخدم لحماية اللقطات التي تحتوي بيانات شخصية.

### الدليل

- دليل عربي من 7 صفحات مولد عبر `scripts/create_user_guide_pdf.py`.
- النسخة الحالية 1.5.7 موجودة في output ومضمنة كـ`app/src/main/res/raw/user_guide_ar.pdf`.
- الصفحة السادسة تشرح المخطط الكامل الجديد وخط NART بالعربية. جرى رندر صفحات النسخة الحالية؛ الصفحة المعدلة فُحصت بصرياً بلا قص أو تداخل.

## 5. الملفات التي عُدلت أو أُنشئت خلال العمل

لا توجد بيانات Git لإثبات diff، لكن الملفات الحالية التي تم تطويرها/استبدالها ضمن هذا العمل هي:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/unrwa/yarmoukfield/MainActivity.java`
- `app/src/main/java/org/unrwa/yarmoukfield/AppDatabase.java`
- `app/src/main/java/org/unrwa/yarmoukfield/AppSecurity.java`
- `app/src/main/java/org/unrwa/yarmoukfield/AddressClassifier.java`
- `app/src/main/java/org/unrwa/yarmoukfield/SpreadsheetImporter.java`
- `app/src/main/java/org/unrwa/yarmoukfield/BeneficiaryPhotoManager.java`
- `app/src/main/java/org/unrwa/yarmoukfield/QuantityCatalog.java`
- `app/src/main/java/org/unrwa/yarmoukfield/PrintRouteAdapter.java`
- `app/src/main/java/org/unrwa/yarmoukfield/PdfMapViewer.java`
- `app/src/main/java/org/unrwa/yarmoukfield/SectorMapView.java`
- `app/src/main/java/org/unrwa/yarmoukfield/CadFileProvider.java`
- `app/src/main/assets/boq_reference.json`
- `app/src/main/assets/yarmouk_addresses.json`
- `app/src/main/assets/yarmouk_camp_map.dwg`
- `app/src/main/assets/yarmouk_sector_maps.dxf`
- `app/src/main/assets/NART.TTF`
- `app/src/main/res/raw/yarmouk_camp_map.pdf`
- `app/src/main/res/raw/yarmouk_sector_maps.pdf`
- `app/src/main/res/raw/user_guide_ar.pdf`
- `app/src/main/res/drawable-nodpi/yarmouk_camp_overview.png`
- `scripts/prepare_map_pdfs.py`
- `scripts/create_user_guide_pdf.py`
- `tests/test_boq_workflow.py`
- `tests/SpreadsheetXmlSmoke.java`
- `tests/BeneficiaryPhotoManagerSmoke.java`
- `BUILD_ANDROID.ps1`
- `README_AR.md`
- `source_maps/original_yarmouk_camp_map.pdf`
- `source_maps/original_yarmouk_sector_maps.pdf`
- `source_maps/NART.TTF`
- `source_maps/archive/original_yarmouk_camp_map_pre_v1.5.7.pdf`
- الملفات النهائية تحت `output/pdf`, `dist`, `release`، وحزم V1.5.7 في جذر المشروع.

## 6. قرارات مهمة ولماذا

- التطبيق native Java دون Gradle ومكتبات خارجية؛ `BUILD_ANDROID.ps1` يستخدم أدوات Android SDK مباشرة لتقليل الاعتماد على الإنترنت.
- العمل offline-first ولا يطلب الإنترنت لأن البيانات ميدانية وحساسة.
- الصور خارج JSON وSQLite في مجلد يختاره المستخدم؛ JSON يحتفظ بالفهرس فقط، ولذلك يجب نسخ مجلد الصور منفصلاً.
- لا تُحذف مجلدات الصور عند حذف السجل/المشروع لتجنب فقدان صور ميدانية دون قصد.
- دفعات المتابعة ثابتة عند 50 استجابةً لمسار العمل الفعلي.
- PDF هو العرض الداخلي المضمون؛ CAD يفتح بتطبيق خارجي لأن Android لا يوفر عارض DWG/DXF أصلياً.
- تم الاحتفاظ بخيار CAD مع تصدير NART، لكن PDF يبقى fallback عند اختلاف دعم الخطوط بين تطبيقات CAD.
- المخطط الكامل الجديد V1.5.7 هو المصدر المعتمد لأنه أوضح عند التكبير. لا تعد إلى نسخة ما قبل V1.5.7.
- مناطق القطاعات تراكبات رسومية شفافة فوق المخطط، وليست تعديلاً دائماً على ملف PDF.
- لقطات الشاشة مسموحة بطلب صريح، مع بقاء النسخ الاحتياطي السحابي والإنترنت معطلين.
- الحماية PIN محلية؛ لا توجد آلية استرجاع PIN. فقدان الكود يستلزم مسار استعادة/إعادة تثبيت قد يؤثر في البيانات، لذلك لا تعد بإمكانية الاسترجاع.

## 7. المشاكل الحالية وما تبقى بالضبط

لا توجد أخطاء build معروفة في 1.5.7، لكن توجد نقاط تحقق ميداني غير منجزة:

1. **لم يُختبر على هاتف أو Emulator متصل** أثناء آخر التحقق. البناء والتوقيع والفحوص كانت محلية فقط.
2. يجب تثبيت 1.5.7 فوق نسخة سابقة والتأكد أن migration يحافظ على المستفيدين والكميات وفهرس الصور.
3. يجب مراجعة حدود القطاعات الملونة على شاشة الهاتف مع المستخدم. الحدود الحالية مأخوذة يدوياً من رسمه الأخضر وقد تحتاج نقاطاً أدق؛ لا تغيّرها تخميناً. اطلب لقطة مشروحة إذا بقي خط خاطئ.
4. يجب اختبار وضوح المخطط الجديد فعلياً داخل `PdfRenderer` على هاتف ضعيف وعالي الذاكرة، وليس فقط عبر رندر سطح المكتب.
5. يجب اختبار رأس عارض PDF ذي السطرين والشارة الثابتة مع أسماء القطاعات السبعة، وفي portrait وlandscape.
6. يجب اختبار مقبض توسيع/تصغير رأس التطبيق في portrait وlandscape والتأكد أن بيانات المصمم والشكر قابلة للوصول.
7. يجب اختبار عدم تداخل شريط التنقل السفلي ودليل PDF مع أزرار Android في وضعي three-button وgesture.
8. يجب اختبار لوحة المفاتيح في نافذة الكميات على الهاتف والتأكد أن الحقل وزر الحفظ لا يُغطّيان.
9. يجب اختبار كاميرا فعلية وإنشاء المجلدات للصور الثلاثة ومراجعة أذونات SAF بعد إعادة التشغيل.
10. يجب اختبار استيراد ملف XLSX حقيقي على الجهاز، خاصة ملف المستخدم `F:\Project_Data17\Reference.xlsx` وقوائم بأسماء أعمدة مختلفة.
11. يجب اختبار فتح DWG/DXF في FastView بعد إضافة NART بالطريقة الموضحة؛ شكل CAD يعتمد على التطبيق الخارجي.
12. يجب اختبار طباعة/حفظ PDF لمسار 1 و25 و26 مستفيداً ونطاق صفحات مختار.
13. تحذير build غير حاجب: `javac` يوصي باستخدام `--release 17` بدلاً من `-source 17 -target 17`. لم يسبب فشلاً.
14. سكربت إعداد PDF المقسم قد يطبع تحذيرات pypdf من نوع `Unexpected escaped string` بسبب المصدر القديم، لكنه أنتج الملفات ونجحت معاينتها. لا تعتبر التحذير فشلاً وحده.

## 8. أوامر البناء والتشغيل والاختبار

شغّل الأوامر من جذر المشروع في PowerShell.

### بناء APK موقّع

```powershell
$env:YARMOUK_SIGNING_PASSWORD = '<أدخل كلمة المرور محلياً ولا تحفظها في ملف>'
try {
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\BUILD_ANDROID.ps1
} finally {
    Remove-Item Env:YARMOUK_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
```

بدون المتغير سيطلب السكربت كلمة المرور تفاعلياً. الناتج الحالي المفترض:
`dist/Yarmouk_Camp_Field_Android_v1.5.7.apk`.

### اختبار BOQ والحاسبات

```powershell
python .\tests\test_boq_workflow.py
```

الناتج المتوقع:
`BOQ_WORKFLOW_OK|items=37|total=375|roundedPayments=496+331|calculator=24,4.8,18|progress=100`.

### اختبار قارئ XLSX عبر SAX

```powershell
$javac = 'C:\Program Files\Android\Android Studio\jbr\bin\javac.exe'
$java  = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
New-Item -ItemType Directory -Force .\tests\build | Out-Null
& $javac -encoding UTF-8 -d .\tests\build .\tests\SpreadsheetXmlSmoke.java
& $java -cp .\tests\build SpreadsheetXmlSmoke 'F:\Project_Data17\Reference.xlsx'
```

النتيجة الأخيرة المعروفة للمرجع: 6 أوراق، 65 صف XML، و585 خلية XML.

### اختبار سياسة أسماء مجلدات الصور

```powershell
$sdk = if ($env:ANDROID_SDK_ROOT) {$env:ANDROID_SDK_ROOT} elseif ($env:ANDROID_HOME) {$env:ANDROID_HOME} else {Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$androidJar = Get-ChildItem (Join-Path $sdk 'platforms') -Filter android.jar -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
$javac = 'C:\Program Files\Android\Android Studio\jbr\bin\javac.exe'
$java  = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
New-Item -ItemType Directory -Force .\tests\build | Out-Null
& $javac -encoding UTF-8 -cp $androidJar -d .\tests\build .\app\src\main\java\org\unrwa\yarmoukfield\BeneficiaryPhotoManager.java .\tests\BeneficiaryPhotoManagerSmoke.java
& $java -cp ".\tests\build;$androidJar" org.unrwa.yarmoukfield.BeneficiaryPhotoManagerSmoke
```

الناتج المتوقع: `PHOTO_PATH_POLICY_OK`.

### إعادة توليد ملفات المخططات

استخدم Python المزود مع Codex runtime أو Python يحتوي `pypdf`:

```powershell
python .\scripts\prepare_map_pdfs.py `
  --full-source .\source_maps\original_yarmouk_camp_map.pdf `
  --sector-source .\source_maps\original_yarmouk_sector_maps.pdf `
  --nart-font .\source_maps\NART.TTF `
  --output-dir .\output\pdf
Copy-Item .\output\pdf\yarmouk_camp_map_optimized.pdf .\app\src\main\res\raw\yarmouk_camp_map.pdf -Force
Copy-Item .\output\pdf\yarmouk_sector_maps_optimized.pdf .\app\src\main\res\raw\yarmouk_sector_maps.pdf -Force
```

إذا تغير المخطط الكامل، أعد أيضاً توليد `yarmouk_camp_overview.png` بنفس أبعاد 1172×1501 تقريباً، وافحصه بصرياً قبل البناء.

### إعادة توليد دليل المستخدم

```powershell
python .\scripts\create_user_guide_pdf.py
```

السكربت يكتب PDF في `output/pdf` ويحدّث النسخة المضمنة. بعد أي تغيير، ارسم الصفحات إلى PNG تحت `tmp/pdfs/` باستخدام Poppler أو `pypdfium2` وافحص الصفحات السبع بصرياً.

### التحقق من البصمة

```powershell
Get-FileHash .\dist\Yarmouk_Camp_Field_Android_v1.5.7.apk -Algorithm SHA256
```

### تثبيت على هاتف عند توفر ADB

```powershell
adb devices
adb install -r .\dist\Yarmouk_Camp_Field_Android_v1.5.7.apk
```

لا تشغل `adb uninstall` على هاتف يحوي بيانات فعلية، لأنه قد يحذف بيانات التطبيق.

## 9. متغيرات البيئة والمتطلبات

- `YARMOUK_SIGNING_PASSWORD`: مطلوب للتوقيع غير التفاعلي. **لا تكتب قيمته في ملفات أو سجلات أو HANDOFF**.
- `ANDROID_SDK_ROOT` أو `ANDROID_HOME`: اختياري؛ عند غيابهما يستخدم السكربت `%LOCALAPPDATA%\Android\Sdk`.
- `JAVA_HOME`: اختياري؛ عند غيابه يستخدم `C:\Program Files\Android\Android Studio\jbr`.
- Android build-tools 36.0.0 مفضل، وإلا يختار السكربت أحدث build-tools مثبت.
- platform `android-37.0` مفضلة، وإلا يختار أحدث `android.jar` مثبت.
- Java 17 مطلوب.
- توليد الدليل يحتاج `reportlab`, `arabic_reshaper`, `python-bidi`, وPillow؛ معالجة/رندر PDF تحتاج `pypdf` ويفضل `pypdfium2` أو Poppler.
- لا توجد API keys ولا خدمات شبكة مطلوبة.

## 10. آخر خطوة كانت قيد التنفيذ

آخر طلب للمستخدم كان استبدال المخطط الكامل بملف PDF جديد أوضح عند التكبير. تم إنجاز ذلك بالكامل في V1.5.7:

1. فُحص الملف الجديد عند 180 و500 DPI.
2. أُرشف المصدر القديم.
3. استُبدل المصدر الكامل وأُعيد قصه/تحسينه.
4. أُعيد توليد overview التفاعلي.
5. حُدث نص التطبيق والدليل إلى 1.5.7.
6. بُني APK ووُقع وفُحصت الحزمة.
7. طابقت نسخة PDF داخل APK المصدر المحسن byte-for-byte، وطابقت صورة overview المصدر pixel-for-pixel بعد ضغط AAPT.
8. أُنشئت حزمة التسليم وحزمة المصدر الخاصة.

لا توجد عملية معلقة في الطرفية حالياً.

## 11. الخطوات التالية بالترتيب

1. ثبّت `Yarmouk_Camp_Field_Android_v1.5.7.apk` على هاتف اختبار باستخدام `adb install -r` أو يدوياً.
2. نفذ قائمة فحوص الجهاز الواردة في القسم 7، وابدأ بالمخطط الكامل الجديد والتكبير واسم القطاع في الرأس/الشارة.
3. اعرض الخريطة التفاعلية على المستخدم واطلب منه تأكيد حدود القطاعات السبعة. إذا صحح نقطة، عدل إحداثيات `AREAS` و`POINTS` فقط في `SectorMapView.java` بناءً على لقطة مشروحة.
4. اختبر الترقية فوق نسخة سابقة ببيانات تجريبية، ثم الكاميرا ومجلدات الصور والاستيراد والكميات والمتابعة ودفعة 50.
5. اختبر FastView وNART وبديل PDF على الهاتف المستهدف.
6. عند نجاح اختبارات الهاتف، حدث رقم الإصدار في `MainActivity.java` و`BUILD_ANDROID.ps1` وREADME وسكربت الدليل معاً لأي إصدار جديد، ثم أعد البناء والتوقيع والتحقق والحزم.
7. حدّث هذا HANDOFF بنتائج فحص الجهاز والمشاكل المتبقية. لا تنشئ ادعاء نجاح ميداني قبل الاختبار الفعلي.

