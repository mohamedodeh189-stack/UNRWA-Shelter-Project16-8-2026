from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tmp" / "pdf_python"))

import arabic_reshaper
from bidi.algorithm import get_display
from reportlab.lib.colors import HexColor, white
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader

WIDTH, HEIGHT = A4
MARGIN = 44
NAVY = HexColor("#123B5D")
BLUE = HexColor("#009ED2")
TEAL = HexColor("#149176")
PURPLE = HexColor("#7A5CB8")
GOLD = HexColor("#C9851C")
ROSE = HexColor("#BD4868")
TEXT = HexColor("#15364D")
MUTED = HexColor("#667A89")
SURFACE = HexColor("#F4F8FB")
BORDER = HexColor("#D6E5EB")
PALE_BLUE = HexColor("#EAF6FA")
PALE_GREEN = HexColor("#EAF7F4")

OUTPUT = ROOT / "output" / "pdf" / "Yarmouk_Camp_Field_User_Guide_AR_v1.5.1.pdf"
EMBEDDED = ROOT / "app" / "src" / "main" / "res" / "raw" / "user_guide_ar.pdf"
LOGO = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "unrwa_logo.gif"
TOTAL_PAGES = 7


def rtl(text: str) -> str:
    return get_display(arabic_reshaper.reshape(str(text)))


def register_fonts() -> None:
    pdfmetrics.registerFont(TTFont("Arabic", r"C:\Windows\Fonts\arial.ttf"))
    pdfmetrics.registerFont(TTFont("ArabicBold", r"C:\Windows\Fonts\arialbd.ttf"))


def wrapped(text: str, font: str, size: float, max_width: float) -> list[str]:
    lines: list[str] = []
    for paragraph in str(text).split("\n"):
        words = paragraph.split()
        if not words:
            lines.append("")
            continue
        current = words[0]
        for word in words[1:]:
            candidate = current + " " + word
            if pdfmetrics.stringWidth(rtl(candidate), font, size) <= max_width:
                current = candidate
            else:
                lines.append(current)
                current = word
        lines.append(current)
    return lines


def draw_rtl(c: canvas.Canvas, text: str, right: float, y: float, width: float, *,
             font: str = "Arabic", size: float = 11, color=TEXT,
             leading: float | None = None) -> float:
    leading = leading or size * 1.55
    c.setFont(font, size)
    c.setFillColor(color)
    for line in wrapped(text, font, size, width):
        if line:
            c.drawRightString(right, y, rtl(line))
        y -= leading
    return y


def footer(c: canvas.Canvas, page: int) -> None:
    c.setStrokeColor(BORDER)
    c.line(MARGIN, 31, WIDTH - MARGIN, 31)
    c.setFillColor(MUTED)
    c.setFont("Arabic", 8.5)
    c.drawRightString(WIDTH - MARGIN, 17, rtl("تصميم وتطوير التطبيق: المهندس محمد أمين عودة"))
    c.drawString(MARGIN, 17, f"{page} / {TOTAL_PAGES}")


def page_header(c: canvas.Canvas, page: int, title: str, subtitle: str = "") -> float:
    c.setFillColor(SURFACE)
    c.rect(0, 0, WIDTH, HEIGHT, stroke=0, fill=1)
    c.setFillColor(NAVY)
    c.rect(0, HEIGHT - 100, WIDTH, 100, stroke=0, fill=1)
    c.setFillColor(BLUE)
    c.rect(0, HEIGHT - 104, WIDTH, 4, stroke=0, fill=1)
    if LOGO.exists():
        c.drawImage(ImageReader(str(LOGO)), WIDTH - MARGIN - 54, HEIGHT - 84, 54, 54, preserveAspectRatio=True, mask="auto")
    c.setFillColor(white)
    c.setFont("ArabicBold", 20)
    c.drawRightString(WIDTH - MARGIN - 68, HEIGHT - 45, rtl(title))
    if subtitle:
        c.setFont("Arabic", 9.5)
        c.setFillColor(HexColor("#D7EDF5"))
        c.drawRightString(WIDTH - MARGIN - 68, HEIGHT - 68, rtl(subtitle))
    footer(c, page)
    return HEIGHT - 130


def section(c: canvas.Canvas, title: str, y: float, color=NAVY) -> float:
    c.setFillColor(color)
    c.roundRect(WIDTH - MARGIN - 8, y - 3, 8, 24, 4, stroke=0, fill=1)
    c.setFont("ArabicBold", 16)
    c.setFillColor(NAVY)
    c.drawRightString(WIDTH - MARGIN - 18, y, rtl(title))
    return y - 35


def card(c: canvas.Canvas, y: float, title: str, body: str, *, accent=BLUE,
         number: str | None = None, height: float | None = None) -> float:
    body_lines = wrapped(body, "Arabic", 10.5, WIDTH - 2 * MARGIN - 50)
    computed = 51 + len(body_lines) * 16
    h = height or max(82, computed)
    c.setFillColor(white)
    c.setStrokeColor(BORDER)
    c.roundRect(MARGIN, y - h, WIDTH - 2 * MARGIN, h, 13, stroke=1, fill=1)
    c.setFillColor(accent)
    c.roundRect(WIDTH - MARGIN - 7, y - h, 7, h, 4, stroke=0, fill=1)
    if number:
        c.setFillColor(accent)
        c.circle(MARGIN + 24, y - 27, 15, stroke=0, fill=1)
        c.setFillColor(white)
        c.setFont("ArabicBold", 11)
        c.drawCentredString(MARGIN + 24, y - 31, number)
    c.setFont("ArabicBold", 13)
    c.setFillColor(accent)
    c.drawRightString(WIDTH - MARGIN - 20, y - 27, rtl(title))
    draw_rtl(c, body, WIDTH - MARGIN - 20, y - 50, WIDTH - 2 * MARGIN - 50, size=10.5, color=TEXT, leading=16)
    return y - h - 12


def pill(c: canvas.Canvas, x: float, y: float, width: float, label: str, color) -> None:
    c.setFillColor(color)
    c.roundRect(x, y, width, 31, 15, stroke=0, fill=1)
    c.setFillColor(white)
    c.setFont("ArabicBold", 10)
    c.drawCentredString(x + width / 2, y + 10, rtl(label))


def build() -> None:
    register_fonts()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    EMBEDDED.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(OUTPUT), pagesize=A4, pageCompression=1)
    c.setTitle("دليل استخدام تطبيق مشروع المأوى في مخيم اليرموك")
    c.setAuthor("المهندس محمد أمين عودة")

    # Page 1 - cover
    c.setFillColor(NAVY)
    c.rect(0, 0, WIDTH, HEIGHT, stroke=0, fill=1)
    c.setFillColor(BLUE)
    c.rect(0, HEIGHT - 12, WIDTH, 12, stroke=0, fill=1)
    if LOGO.exists():
        c.drawImage(ImageReader(str(LOGO)), WIDTH - MARGIN - 94, HEIGHT - 146, 94, 94, preserveAspectRatio=True, mask="auto")
    c.setFillColor(HexColor("#D7EDF5"))
    c.setFont("Arabic", 12)
    c.drawRightString(WIDTH - MARGIN - 112, HEIGHT - 78, rtl("وكالة غوث وتشغيل اللاجئين الفلسطينيين في سوريا"))
    c.setFillColor(white)
    c.setFont("ArabicBold", 28)
    c.drawRightString(WIDTH - MARGIN, HEIGHT - 220, rtl("دليل استخدام التطبيق الميداني"))
    c.setFont("ArabicBold", 20)
    c.setFillColor(HexColor("#C8EAF5"))
    c.drawRightString(WIDTH - MARGIN, HEIGHT - 260, rtl("مشروع المأوى في مخيم اليرموك"))
    c.setFont("Arabic", 12)
    c.setFillColor(white)
    c.drawRightString(WIDTH - MARGIN, HEIGHT - 300, rtl("الإصدار 1.5.1 - يعمل دون إنترنت"))
    c.setFillColor(HexColor("#0B2F49"))
    c.roundRect(MARGIN, 275, WIDTH - 2 * MARGIN, 190, 20, stroke=0, fill=1)
    c.setFillColor(white)
    c.setFont("ArabicBold", 16)
    c.drawRightString(WIDTH - MARGIN - 24, 428, rtl("مسار العمل في سطر واحد"))
    pill(c, MARGIN + 25, 350, 118, "بعد الترميم", TEAL)
    pill(c, MARGIN + 151, 350, 118, "أثناء الترميم", GOLD)
    pill(c, MARGIN + 277, 350, 118, "دراسة المنزل", PURPLE)
    c.setFont("Arabic", 11)
    c.setFillColor(HexColor("#D7EDF5"))
    c.drawRightString(WIDTH - MARGIN - 24, 316, rtl("دراسة المستفيد ← اعتماد دفعة 50 ← متابعة التنفيذ والتوثيق"))
    c.setFillColor(white)
    c.setFont("ArabicBold", 12)
    c.drawRightString(WIDTH - MARGIN, 140, rtl("تصميم وتطوير التطبيق: المهندس محمد أمين عودة"))
    c.setFont("Arabic", 10.5)
    c.setFillColor(HexColor("#D7EDF5"))
    c.drawRightString(WIDTH - MARGIN, 112, rtl("شكر وتقدير: م. عبد المالك أبو حرب، م. محمد عبد العال، وم. يمام أمين عودة"))
    c.setFont("Arabic", 8.5)
    c.drawRightString(WIDTH - MARGIN, 68, rtl("نسخة عربية مرفقة داخل التطبيق - آب 2026"))
    c.showPage()

    # Page 2 - workflow
    y = page_header(c, 2, "سير العمل العام", "الفصل بين الدراسة والمتابعة يحافظ على وضوح البيانات")
    y = card(c, y, "إنشاء دراسة جديدة", "من تبويب «الدراسات» أضف اسم المستفيد والهاتف والعنوان الأولي. لا تحتاج إلى قائمة جاهزة قبل بدء العمل الميداني.", accent=PURPLE, number="1")
    y = card(c, y, "زيارة المنزل وتوثيق الأضرار", "التقط صور «قبل الترميم»، ثم اكتب العنوان التفصيلي ووصف الأضرار وأرفق مخطط المنزل.", accent=BLUE, number="2")
    y = card(c, y, "تعبئة جدول الكميات", "اختر الكميات المطلوبة من البنود المرجعية الـ37. اكتب الكمية أو استخدم حاسبة البند، وسيحسب التطبيق التكلفة والإجمالي والدفعتين آلياً.", accent=GOLD, number="3")
    y = card(c, y, "اعتماد الدراسة وإصدار القائمة", "بعد اكتمال البيانات تصبح الدراسة جاهزة. عند وصول العدد إلى 50، أنشئ دفعة متابعة واحدة بالقائمة والعناوين المعتمدة.", accent=TEAL, number="4")
    y = card(c, y, "متابعة التنفيذ", "في تبويب «المتابعة» صوّر الأعمال أثناء الترميم، ثم صوّر النتيجة بعد الترميم وسجّل الحالة النهائية.", accent=ROSE, number="5")
    c.showPage()

    # Page 3 - study and photos
    y = page_header(c, 3, "مرحلة الدراسة والتصوير", "كل مستفيد يملك سجلاً ومجلد صور مستقلاً")
    y = section(c, "إضافة المستفيد", y, PURPLE)
    y = card(c, y, "البيانات الأولية", "الاسم إلزامي. أضف رقم التسجيل والهاتف والعنوان إن توفر. يمكن استكمال بقية البيانات في الزيارة.", accent=PURPLE)
    y = section(c, "مجلد الصور", y, BLUE)
    y = card(c, y, "اختيار المجلد مرة واحدة", "عند أول تصوير اختر مجلداً محلياً على الهاتف. ينشئ التطبيق داخله: اسم المشروع ← اسم المستفيد ← قبل الترميم / أثناء الترميم / بعد الترميم.", accent=BLUE, height=106)
    y = card(c, y, "قبل الترميم", "افتح سجل المستفيد واضغط «فتح الكاميرا». تحفظ الصورة مباشرة في مرحلة «قبل الترميم»، ويمكن إضافة ملاحظة لكل صورة.", accent=BLUE)
    y = section(c, "اكتمال الدراسة", y, TEAL)
    y = card(c, y, "العناصر المطلوبة للاعتماد", "العنوان التفصيلي، وصف الأضرار، جدول كميات بقيمة أكبر من صفر، مخطط المنزل، وصورة واحدة على الأقل قبل الترميم.", accent=TEAL, height=98)
    c.showPage()

    # Page 4 - quantities
    y = page_header(c, 4, "جدول الكميات والتكلفة", "مرجع Reference.xlsx - ورقة الكميات - 37 بنداً")
    y = section(c, "طريقة الإدخال", y, GOLD)
    y = card(c, y, "إدخال مباشر أو حاسبة", "اكتب الكمية مباشرة، أو اضغط «حاسبة» بجانب البند. تختار الحاسبة تلقائياً حقول الطول والعرض والارتفاع والعدد بحسب وحدة القياس.", accent=GOLD)
    c.setFillColor(PALE_GREEN)
    c.setStrokeColor(HexColor("#B7DED3"))
    c.roundRect(MARGIN, y - 105, WIDTH - 2 * MARGIN, 105, 14, stroke=1, fill=1)
    c.setFillColor(TEAL)
    c.setFont("ArabicBold", 15)
    c.drawRightString(WIDTH - MARGIN - 20, y - 28, rtl("المعادلة المعتمدة"))
    c.setFont("ArabicBold", 13)
    c.setFillColor(TEXT)
    c.drawRightString(WIDTH - MARGIN - 20, y - 56, rtl("تكلفة البند = الكمية × السعر"))
    c.setFont("Arabic", 11)
    c.drawRightString(WIDTH - MARGIN - 20, y - 82, rtl("مثال: سيراميك 12 م² × 25 $ = 300 $"))
    y -= 125
    y = card(c, y, "اعتماد ناتج الحاسبة", "اختر «استبدال الكمية» لوضع الناتج مكان القيمة الحالية، أو «إضافة إلى الموجود» لجمع قياس جديد. يتحدث السعر والإجمالي فوراً.", accent=PURPLE)
    y = card(c, y, "الإجمالي والدفعات", "الإجمالي هو مجموع تكاليف البنود. تظهر الدفعتان 60% و40% كأرقام صحيحة دون كسور، ويضبط التطبيق الدفعة الثانية ليبقى مجموعهما مساوياً للإجمالي المقرب.", accent=TEAL)
    y = section(c, "مجموعات البنود", y, NAVY)
    groups = [
        "1-6 أعمال هيكلية", "7-10 الأرضية والجدران", "11 الزجاج", "12-15 أعمال النجارة",
        "16-18 أعمال الألمنيوم", "19-20 أعمال الحديد", "21 العزل", "22-30 الأعمال الصحية",
        "31-36 أعمال الكهرباء", "37 أعمال الدهان",
    ]
    c.setFont("Arabic", 9.7)
    col_width = (WIDTH - 2 * MARGIN - 12) / 2
    for index, label in enumerate(groups):
        col = index % 2
        row = index // 2
        x = MARGIN + col * (col_width + 12)
        yy = y - row * 34
        c.setFillColor(white)
        c.setStrokeColor(BORDER)
        c.roundRect(x, yy - 25, col_width, 27, 8, stroke=1, fill=1)
        c.setFillColor(TEXT)
        c.drawRightString(x + col_width - 10, yy - 16, rtl(label))
    c.showPage()

    # Page 5 - follow-up
    y = page_header(c, 5, "اعتماد قائمة الـ50 والمتابعة", "لا تنتقل الدراسة إلى التنفيذ قبل اكتمال متطلباتها")
    y = card(c, y, "تجهيز الدراسة", "اضغط «اعتماد الدراسة». إذا كان عنصر ناقصاً، يعرض التطبيق قائمة واضحة بما يجب استكماله.", accent=PURPLE, number="1")
    y = card(c, y, "إصدار دفعة 50", "عندما يصل عدد الدراسات الجاهزة إلى 50، اضغط «دفعة 50». ينقل التطبيق أول 50 دراسة مع عناوينها ومبالغها إلى المتابعة.", accent=BLUE, number="2")
    y = card(c, y, "أثناء الترميم", "افتح المستفيد من تبويب المتابعة، وسجّل نسبة الإنجاز من 0 إلى 100، والتقط صور سير العمل. أضف ملاحظات مثل إزالة الأنقاض أو استلام مرحلة محددة.", accent=GOLD, number="3")
    y = card(c, y, "بعد الترميم", "عند انتهاء الأعمال التقط الصور النهائية في مجلد «بعد الترميم»، ثم حدّث الحالة إلى «اكتمل الترميم».", accent=TEAL, number="4")
    y = card(c, y, "الحالات المتاحة", "اكتمل الترميم - قيد التنفيذ - بحاجة لمراجعة - تعذر الوصول. يمكن البحث بالاسم أو العنوان أو الهاتف.", accent=ROSE, number="5")
    c.showPage()

    # Page 6 - maps and import
    y = page_header(c, 6, "المخططات والاستيراد", "اختيار صيغة العرض وحل أخطاء الملفات")
    y = section(c, "عرض المخططات", y, BLUE)
    y = card(c, y, "عرض PDF داخل التطبيق", "يظهر كزر واضح وهو الخيار المضمون دون برامج إضافية. يدعم التكبير والسحب، وجرى تضمين الخطوط وقص هوامش صفحات القطاعات لتحسين الوضوح.", accent=BLUE)
    y = card(c, y, "فتح CAD", "يظهر كزر مستقل ويفتح المخطط الكامل بصيغة DWG والمخطط المقسّم بصيغة DXF في تطبيق CAD مثبت على الهاتف. إذا لم يوجد عارض متوافق، استخدم PDF.", accent=TEAL)
    y = section(c, "استيراد البيانات", y, PURPLE)
    y = card(c, y, "الصيغ المدعومة", "XLSX وCSV وJSON. تُستورد القوائم العادية إلى المتابعة، أما نسخة JSON التي أنشأها التطبيق فتستعيد مراحل العمل وجداول الكميات.", accent=PURPLE)
    y = card(c, y, "عند فشل الاستيراد", "تأكد أن الملف XLSX غير محمي بكلمة مرور. إذا كان XLS قديماً، افتحه في Excel واحفظه بصيغة XLSX. تم إصلاح خطأ XMLReader في هذا الإصدار.", accent=ROSE, height=100)
    c.showPage()

    # Page 7 - backup and credits
    y = page_header(c, 7, "حماية البيانات والملاحظات", "النسخة الاحتياطية لا تستبدل نسخ مجلد الصور")
    y = card(c, y, "كود حماية التطبيق", "في أول تشغيل اختر PIN من 6 أرقام. يُخزن كبصمة مشفرة، ويُقفل التطبيق بعد بقائه في الخلفية 3 دقائق. بعد خمس محاولات خاطئة يتوقف الإدخال 30 ثانية.", accent=ROSE)
    y = card(c, y, "حماية الشاشة والنسخ", "يمنع التطبيق لقطات الشاشة وظهور البيانات في التطبيقات الأخيرة، ويعطل النسخ السحابي التلقائي لقاعدة البيانات. يعمل التطبيق دون صلاحية إنترنت.", accent=PURPLE)
    y = card(c, y, "نسخة JSON ومجلد الصور", "احفظ نسخة JSON وانسخ مجلد الصور دورياً إلى مكان آمن. ملف JSON يتضمن البيانات والكميات وفهرس الصور، لكنه لا يضم ملفات الصور نفسها.", accent=TEAL)
    y = section(c, "عن التطبيق", y, NAVY)
    c.setFillColor(white)
    c.setStrokeColor(BORDER)
    c.roundRect(MARGIN, y - 196, WIDTH - 2 * MARGIN, 196, 16, stroke=1, fill=1)
    y2 = y - 30
    y2 = draw_rtl(c, "تصميم وتطوير التطبيق: المهندس محمد أمين عودة", WIDTH - MARGIN - 22, y2, WIDTH - 2 * MARGIN - 44, font="ArabicBold", size=14, color=NAVY)
    y2 -= 8
    y2 = draw_rtl(c, "شكر وتقدير للمهندس عبد المالك أبو حرب والمهندس محمد عبد العال والمهندسة يمام أمين عودة على دعمهم وتعاونهم في تطوير العمل الميداني.", WIDTH - MARGIN - 22, y2, WIDTH - 2 * MARGIN - 44, size=11, color=TEXT, leading=18)
    y2 -= 8
    draw_rtl(c, "الإصدار 1.5.1 - مشروع المأوى في مخيم اليرموك - آب 2026", WIDTH - MARGIN - 22, y2, WIDTH - 2 * MARGIN - 44, size=10, color=MUTED)
    c.save()
    shutil.copyfile(OUTPUT, EMBEDDED)
    print(f"PDF_READY::{OUTPUT}")
    print(f"EMBEDDED_COPY::{EMBEDDED}")


if __name__ == "__main__":
    build()
