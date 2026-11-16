package com.odd.analyzer;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative multilingual market canonicalizer.
 * It intentionally abstains when the market or selection labels are ambiguous.
 */
public final class SemanticMarketMatcher {
    private SemanticMarketMatcher() {}

    public static final class Parsed {
        public final String key;
        public final ArrayList<String> outcomes;
        Parsed(String key, ArrayList<String> outcomes) {
            this.key = key;
            this.outcomes = outcomes;
        }
    }

    private static final class Hit {
        final int position;
        final String outcome;
        Hit(int position, String outcome) { this.position = position; this.outcome = outcome; }
    }

    public static Parsed parse(String source, JSONArray odds) {
        if (source == null || odds == null || odds.length() != 2) return null;
        String text = normalize(source);
        if (text.length() < 5) return null;

        String period = detectPeriod(text);
        if (period == null) return null; // Unknown period is not silently equated with full time.

        String market;
        String line = "";
        boolean teamScope = hasAny(text, "team total", "home team total", "away team total",
                "team goals", "گل تیم میزبان", "گل تیم مهمان", "گل‌های تیم", "گل تیم");

        if (hasAny(text, "both teams to score", "both team score", "btts",
                "هر دو تیم گل می زنند", "هر دو تیم گل میزنند", "گلزنی هر دو تیم")) {
            market = "BTTS";
            if (teamScope) return null;
        } else {
            Matcher lm = Pattern.compile("(?<!\\d)([0-9]{1,2}(?:\\.[05])?)(?!\\d)").matcher(text);
            ArrayList<String> lines = new ArrayList<>();
            while (lm.find()) {
                String v = lm.group(1);
                if (v.matches("[0-9]{1,2}\\.[05]")) lines.add(v);
            }
            if (lines.size() != 1) return null;
            line = lines.get(0);
            if (!hasAny(text, "over", "under", "total goals", "goals total", "مجموع گل",
                    "گل های مسابقه", "گل‌های مسابقه", "بیشتر از", "کمتر از", "بالای", "زیر")) return null;
            market = "TOTAL_GOALS";
            if (teamScope) return null;
        }

        ArrayList<Hit> hits = new ArrayList<>();
        if ("TOTAL_GOALS".equals(market)) {
            collect(hits, text, "(?<![a-z])over\\s*" + Pattern.quote(line) + "(?![0-9])", "OVER");
            collect(hits, text, "(?<![a-z])under\\s*" + Pattern.quote(line) + "(?![0-9])", "UNDER");
            collect(hits, text, "بیشتر از\\s*" + Pattern.quote(line), "OVER");
            collect(hits, text, "کمتر از\\s*" + Pattern.quote(line), "UNDER");
            collect(hits, text, "بالای\\s*" + Pattern.quote(line), "OVER");
            collect(hits, text, "زیر\\s*" + Pattern.quote(line), "UNDER");
        } else {
            collect(hits, text, "(?<![a-z])yes(?![a-z])", "YES");
            collect(hits, text, "(?<![a-z])no(?![a-z])", "NO");
            collect(hits, text, "بله", "YES");
            collect(hits, text, "خیر", "NO");
        }

        // Deduplicate identical label hits caused by overlapping multilingual patterns.
        Collections.sort(hits, Comparator.comparingInt(h -> h.position));
        ArrayList<Hit> unique = new ArrayList<>();
        for (Hit h : hits) {
            if (unique.isEmpty() || unique.get(unique.size() - 1).position != h.position)
                unique.add(h);
        }
        if (unique.size() != 2) return null;
        if (odds.length() != unique.size()) return null;
        if (unique.get(0).outcome.equals(unique.get(1).outcome)) return null;

        ArrayList<String> outcomes = new ArrayList<>();
        outcomes.add(unique.get(0).outcome);
        outcomes.add(unique.get(1).outcome);
        String key = market + "|LINE=" + line + "|PERIOD=" + period + "|SCOPE=MATCH";
        return new Parsed(key, outcomes);
    }

    private static void collect(ArrayList<Hit> hits, String text, String regex, String outcome) {
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) hits.add(new Hit(m.start(), outcome));
    }

    private static String detectPeriod(String t) {
        boolean first = hasAny(t, "first half", "1st half", "half time", "1h", "نیمه اول");
        boolean second = hasAny(t, "second half", "2nd half", "2h", "نیمه دوم");
        boolean full = hasAny(t, "full time", "90 minutes", "match result", "کل مسابقه",
                "پایان مسابقه", "وقت قانونی");
        if ((first && second) || (first && full) || (second && full)) return null;
        if (first) return "1H";
        if (second) return "2H";
        if (full) return "FT";
        return null;
    }

    private static boolean hasAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('ي', 'ی').replace('ك', 'ک')
                .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3')
                .replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7')
                .replace('۸','8').replace('۹','9')
                .replaceAll("\\s+", " ").trim();
    }
}
