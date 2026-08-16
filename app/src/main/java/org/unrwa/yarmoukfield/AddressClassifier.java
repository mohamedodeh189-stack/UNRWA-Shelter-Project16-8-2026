package org.unrwa.yarmoukfield;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline classifier matching the authoritative seven-sector Yarmouk reference. */
public final class AddressClassifier {
    public static final List<String> SECTOR_ORDER = Arrays.asList(
            "شرق اليرموك 1", "غرب اليرموك 1", "شرق اليرموك 2", "غرب اليرموك 2",
            "شرق اليرموك 3", "غرب اليرموك 3", "العروبة والتقدم");

    private static final List<String> FIELD_ROUTE = Arrays.asList(
            "مفلح السالم", "فلسطين", "القدس", "اليرموك", "حيفا", "صفد",
            "نوح إبراهيم", "صرفند", "محمد جمجوم", "لوبية", "عبد الله الأصبح",
            "الجاعونة", "صفوريا", "عين جالوت", "سرور برهم", "حسن سلامة",
            "المدارس", "سبع السباعي", "بيسان", "محمود عزيمة", "بئر السبع",
            "المجدل", "ام الزينات", "العروبة", "التقدم", "القسطل",
            "قاسم أبو القاسم", "مجد الكروم", "عبد الرحيم حج محمد", "معاليا");

    public static final class Match {
        public String sector = "غير مصنف";
        public String street = "";
        public String review = "بحاجة لمراجعة";
        public double confidence;
        public int sectorIndex = 999;
        public int streetIndex = 999;
        public int routeIndex = 999;
        public int alleyIndex = 999;
    }

    private static final class Entry {
        String variant, sector, street;
        int sectorIndex, streetIndex;
        boolean isFragment;
        Entry(String v, String s, String st, int si, int sti, boolean fragment) {
            variant = v; sector = s; street = st; sectorIndex = si; streetIndex = sti; isFragment = fragment;
        }
    }

    private static final class Variants {
        final Set<String> strong = new HashSet<>();
        final Set<String> fragments = new HashSet<>();
    }

    private final LinkedHashMap<String, List<String>> reference = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> normalizedRoute = new ArrayList<>();

    public AddressClassifier(Context context) throws Exception {
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("yarmouk_addresses.json"), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
        }
        JSONObject sectors = new JSONObject(raw.toString()).getJSONObject("sectors");
        for (String sector : SECTOR_ORDER) {
            JSONArray array = sectors.optJSONArray(sector);
            List<String> streets = new ArrayList<>();
            if (array != null) for (int i = 0; i < array.length(); i++) streets.add(array.getString(i));
            reference.put(sector, streets);
        }
        for (String value : FIELD_ROUTE) normalizedRoute.add(normalize(value));
        for (int si = 0; si < SECTOR_ORDER.size(); si++) {
            String sector = SECTOR_ORDER.get(si);
            List<String> streets = reference.get(sector);
            for (int sti = 0; sti < streets.size(); sti++) {
                String street = streets.get(sti);
                Variants v = variants(street);
                for (String variant : v.strong) entries.add(new Entry(variant, sector, street, si, sti, false));
                for (String variant : v.fragments) entries.add(new Entry(variant, sector, street, si, sti, true));
            }
        }
    }

    public Map<String, List<String>> getReference() { return reference; }

    public static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        text = text.replaceAll("[\\u0617-\\u061A\\u064B-\\u0652\\u0670\\u06D6-\\u06ED\\u0640]", "");
        text = text.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه');
        text = text.replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
                .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9');
        return text.replaceAll("[^0-9a-zA-Z\\u0600-\\u06FF]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static Variants variants(String street) {
        Set<String> values = new HashSet<>();
        String base = normalize(street);
        values.add(base);
        for (String qualifier : Arrays.asList(" مشترك", " جزء", " اول جزء", " الرئيسي")) {
            int at = base.indexOf(qualifier);
            if (at > 0) values.add(base.substring(0, at).trim());
        }
        for (String part : street.split("[/()\\-–—]")) {
            String normalized = normalize(part);
            if (normalized.length() >= 4) values.add(normalized);
        }
        for (String prefix : Arrays.asList("شارع", "جامع", "مدرسه", "مقبره", "مستوصف", "حديقه")) {
            if (base.startsWith(prefix + " ")) values.add(base.substring(prefix.length()).trim());
        }
        values.remove("");
        Variants result = new Variants();
        result.strong.addAll(values);
        // A field address often names only the distinctive last word of a
        // multi-word reference street (e.g. "جمجوم" for "محمد جمجوم"). These
        // fragments are scored well below a full-name match in classify().
        String[] words = base.split(" ");
        if (words.length > 1) {
            for (String word : words) if (word.length() >= 4) result.fragments.add(word);
        }
        result.fragments.removeAll(result.strong);
        return result;
    }

    public Match classify(String address) {
        Match result = new Match();
        String norm = normalize(address);
        if (norm.isEmpty()) { result.review = "العنوان فارغ — بحاجة لمراجعة"; return result; }

        Map<String, String> aliases = new HashMap<>();
        aliases.put("صقوريه", "صفوريا"); aliases.put("صفوريه", "صفوريا");
        aliases.put("سبع سباع", "سبع السباعي"); aliases.put("المدارس", "شارع المدارس الحوله");
        aliases.put("مجد كروم", "مجد الكروم"); aliases.put("عبد الرحيم الحاج محمد", "عبد الرحيم حج محمد");
        aliases.put("قاسم ابو قاسم", "قاسم ابو القاسم"); aliases.put("قاسم الو لقاسم", "قاسم ابو القاسم");
        aliases.put("صلفند", "صرفند"); aliases.put("سعيد بن العاص", "سعيد العاص");
        for (Map.Entry<String,String> alias : aliases.entrySet()) {
            if (norm.contains(alias.getKey())) norm += " " + alias.getValue();
        }

        String explicit = null;
        for (String sector : SECTOR_ORDER) {
            List<String> names = new ArrayList<>(); names.add(sector);
            if (sector.equals("العروبة والتقدم")) names.addAll(Arrays.asList("التقدم والعروبة", "معاليا", "معليا"));
            for (String name : names) if (norm.contains(normalize(name))) { explicit = sector; break; }
            if (explicit != null) break;
        }

        Entry best = null; double bestScore = 0;
        for (Entry entry : entries) {
            if (explicit != null && !explicit.equals(entry.sector)) continue;
            double score;
            if (norm.contains(entry.variant) || entry.variant.contains(norm)) {
                if (entry.variant.equals(norm)) score = 1.0;
                else if ((entry.variant.equals("يرموك") || entry.variant.equals("مخيم اليرموك") || entry.variant.equals("شارع اليرموك"))
                        && norm.split(" ").length > 2) score = 0.61;
                else if (entry.isFragment) score = 0.70;
                else score = Math.min(.99, .92 + .02 * entry.variant.split(" ").length);
            } else {
                score = similarity(norm, entry.variant);
                Set<String> words = new HashSet<>(Arrays.asList(norm.split(" ")));
                words.retainAll(Arrays.asList(entry.variant.split(" ")));
                if (!words.isEmpty()) score = Math.max(score, Math.min(.91, .58 + .09 * words.size()));
                if (entry.isFragment) score = Math.min(score, 0.65);
            }
            if (best == null || score > bestScore || (score == bestScore &&
                    (entry.sectorIndex < best.sectorIndex || (entry.sectorIndex == best.sectorIndex && entry.streetIndex < best.streetIndex)))) {
                best = entry; bestScore = score;
            }
        }

        // The plan/sector reference always yields a real sector below — an
        // address is never reported as "unclassified" here. A weak or
        // missing textual match still gets the closest sector from the
        // street reference, flagged for field confirmation.
        if (best != null && bestScore >= .78) {
            result.sector = best.sector; result.street = best.street;
            result.sectorIndex = best.sectorIndex; result.streetIndex = best.streetIndex;
            result.review = bestScore >= .93 ? "مطابقة مؤكدة" : "مطابقة تقريبية — راجعها";
        } else if (explicit != null) {
            result.sector = explicit; result.sectorIndex = SECTOR_ORDER.indexOf(explicit);
            result.streetIndex = 998; bestScore = Math.max(.60, bestScore);
            result.review = "تم تحديد القطاع من نص العنوان — الشارع بحاجة لمراجعة";
        } else if (best != null && best.sector != null && !best.sector.isEmpty()) {
            result.sector = best.sector; result.street = best.street;
            result.sectorIndex = best.sectorIndex; result.streetIndex = best.streetIndex;
            result.review = "أقرب قطاع من المخطط المرجعي — يحتاج تأكيداً ميدانياً";
        } else {
            result.sector = SECTOR_ORDER.isEmpty() ? "بحاجة لتحديد القطاع" : SECTOR_ORDER.get(0);
            result.sectorIndex = SECTOR_ORDER.isEmpty() ? 999 : 0;
            result.street = ""; result.streetIndex = 999;
            bestScore = 0;
            result.review = "لم يظهر تشابه مع المخطط — أقرب قطاع افتراضي، يتطلب تأكيداً ميدانياً";
        }
        result.confidence = bestScore;
        String streetCore = normalize(result.street.split("[/()\\-–—]")[0]);
        for (int i = 0; i < normalizedRoute.size(); i++) {
            String route = normalizedRoute.get(i);
            if (!streetCore.isEmpty() && (streetCore.contains(route) || route.contains(streetCore))) {
                result.routeIndex = i; break;
            }
        }
        Matcher alley = Pattern.compile("(?:جاده|حاره|مدخل|كتله)\\s*(\\d+)").matcher(norm);
        if (alley.find()) try { result.alleyIndex = Integer.parseInt(alley.group(1)); } catch (Exception ignored) {}
        return result;
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1;
        int[][] d = new int[a.length()+1][b.length()+1];
        for (int i=0;i<=a.length();i++) d[i][0]=i;
        for (int j=0;j<=b.length();j++) d[0][j]=j;
        for (int i=1;i<=a.length();i++) for(int j=1;j<=b.length();j++)
            d[i][j]=Math.min(Math.min(d[i-1][j]+1,d[i][j-1]+1),d[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
        return 1.0 - (double)d[a.length()][b.length()] / Math.max(1, Math.max(a.length(), b.length()));
    }
}

