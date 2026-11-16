package com.odd.analyzer;

import org.json.JSONArray;
import java.util.*;
import java.util.regex.*;

/** Offline, deterministic sports-market normalizer. Ambiguous groups are rejected. */
public final class OfflineMarketEngine {
    private OfflineMarketEngine() {}
    public static final class Parsed {
        public final String key;
        public final ArrayList<String> outcomes;
        Parsed(String k, ArrayList<String> o) { key=k; outcomes=o; }
    }
    private static final class Hit {
        final int at; final String value;
        Hit(int p,String v){at=p;value=v;}
    }

    public static Parsed parse(String source, JSONArray odds) {
        if(source==null||odds==null||odds.length()<2||odds.length()>3||!validOdds(odds)) return null;
        String t=norm(source);
        if(t.length()<4) return null;
        String period=period(t);
        if(period==null) return null;
        String type, line="NONE", participant="NONE";
        ArrayList<Hit> hits=new ArrayList<>();

        boolean btts=has(t,"both teams to score","both team score","btts","هر دو تیم گل می زنند","هر دو تیم گل میزنند","گلزنی هر دو تیم");
        boolean teamTotal=has(t,"home team total","away team total","team total","team goals total","مجموع گل تیم","تعداد گل تیم","گل تیم میزبان","گل تیم مهمان");
        boolean asian=has(t,"asian handicap","asian hcap","هندیکپ آسیایی");
        boolean dc=has(t,"double chance","شانس دوگانه","دو شانس");
        boolean result=has(t,"match result","full time result","1x2","نتیجه مسابقه","نتیجه نهایی","برنده مسابقه");

        if(btts){
            if(teamTotal||odds.length()!=2)return null;
            type="BTTS";
            add(hits,t,"\\byes\\b","YES"); add(hits,t,"\\bno\\b","NO");
            add(hits,t,"بله","YES"); add(hits,t,"خیر","NO");
        } else if(teamTotal){
            if(odds.length()!=2)return null;
            boolean home=has(t,"home team total","گل تیم میزبان"), away=has(t,"away team total","گل تیم مهمان");
            if(home==away)return null;
            participant=home?"HOME":"AWAY"; type="TEAM_TOTAL";
            line=singleLine(t,false); if(line==null)return null;
            overUnder(hits,t,line);
        } else if(asian){
            if(odds.length()!=2)return null;
            type="ASIAN_HANDICAP"; line=singleLine(t,true); if(line==null)return null;
            add(hits,t,"\\bhome\\b","HOME"); add(hits,t,"\\baway\\b","AWAY");
            add(hits,t,"میزبان","HOME"); add(hits,t,"مهمان","AWAY");
        } else if(dc){
            if(odds.length()!=3)return null;
            type="DOUBLE_CHANCE";
            add(hits,t,"\\b1\\s*/?\\s*x\\b|\\b1x\\b","HOME_DRAW");
            add(hits,t,"\\b1\\s*/?\\s*2\\b|\\b12\\b","HOME_AWAY");
            add(hits,t,"\\bx\\s*/?\\s*2\\b|\\bx2\\b","DRAW_AWAY");
            add(hits,t,"میزبان\\s*[-/]\\s*مساوی","HOME_DRAW");
            add(hits,t,"میزبان\\s*[-/]\\s*مهمان","HOME_AWAY");
            add(hits,t,"مساوی\\s*[-/]\\s*مهمان","DRAW_AWAY");
        } else if(result){
            if(odds.length()!=3)return null;
            type="MATCH_RESULT";
            add(hits,t,"\\bhome\\b","HOME"); add(hits,t,"\\bdraw\\b|\\btie\\b","DRAW");
            add(hits,t,"\\baway\\b","AWAY"); add(hits,t,"میزبان","HOME");
            add(hits,t,"مساوی","DRAW"); add(hits,t,"مهمان","AWAY");
        } else {
            if(odds.length()!=2||!has(t,"total goals","goals total","over under","مجموع گل","گل های مسابقه","گل‌های مسابقه","بیشتر از","کمتر از"))return null;
            type="TOTAL_GOALS"; line=singleLine(t,false); if(line==null)return null;
            overUnder(hits,t,line);
        }

        ArrayList<Hit> ordered=ordered(hits);
        if(ordered.size()!=odds.length())return null;
        ArrayList<String> outcomes=new ArrayList<>();
        for(Hit h:ordered)outcomes.add(h.value);
        if(new HashSet<>(outcomes).size()!=outcomes.size())return null;
        if("BTTS".equals(type)&&!containsExactly(outcomes,"YES","NO"))return null;
        if(("TOTAL_GOALS".equals(type)||"TEAM_TOTAL".equals(type))&&!containsExactly(outcomes,"OVER","UNDER"))return null;
        if("MATCH_RESULT".equals(type)&&!containsExactly(outcomes,"HOME","DRAW","AWAY"))return null;
        if("DOUBLE_CHANCE".equals(type)&&!containsExactly(outcomes,"HOME_DRAW","HOME_AWAY","DRAW_AWAY"))return null;
        if("ASIAN_HANDICAP".equals(type)&&!containsExactly(outcomes,"HOME","AWAY"))return null;
        return new Parsed(type+"|LINE="+line+"|PERIOD="+period+"|PARTICIPANT="+participant,outcomes);
    }

    private static boolean validOdds(JSONArray a){
        for(int i=0;i<a.length();i++)try{double v=Double.parseDouble(a.optString(i).replace(",","."));if(!Double.isFinite(v)||v<=1||v>1000)return false;}catch(Exception e){return false;}
        return true;
    }
    private static void overUnder(ArrayList<Hit> h,String t,String l){
        add(h,t,"(?<![a-z])over\\s*"+Pattern.quote(l)+"(?![0-9])","OVER");
        add(h,t,"(?<![a-z])under\\s*"+Pattern.quote(l)+"(?![0-9])","UNDER");
        add(h,t,"بیشتر از\\s*"+Pattern.quote(l),"OVER"); add(h,t,"کمتر از\\s*"+Pattern.quote(l),"UNDER");
        add(h,t,"بالای\\s*"+Pattern.quote(l),"OVER"); add(h,t,"زیر\\s*"+Pattern.quote(l),"UNDER");
    }
    private static String singleLine(String t,boolean signed){
        String sign=signed?"[+-]?":"";
        Matcher m=Pattern.compile("(?<![\\d.])("+sign+"[0-9]{1,2}(?:\\.[02578])?)(?![\\d.])").matcher(t);
        ArrayList<String> vals=new ArrayList<>();
        while(m.find())if(!vals.contains(m.group(1)))vals.add(m.group(1));
        return vals.size()==1?vals.get(0):null;
    }
    private static void add(ArrayList<Hit> h,String t,String regex,String value){
        Matcher m=Pattern.compile(regex).matcher(t);while(m.find())h.add(new Hit(m.start(),value));
    }
    private static ArrayList<Hit> ordered(ArrayList<Hit> h){
        Collections.sort(h,Comparator.comparingInt(x->x.at));ArrayList<Hit> out=new ArrayList<>();
        for(Hit x:h)if(out.isEmpty()||out.get(out.size()-1).at!=x.at)out.add(x);return out;
    }
    private static boolean containsExactly(ArrayList<String> a,String... expected){
        if(a.size()!=expected.length)return false;for(String s:expected)if(!a.contains(s))return false;return true;
    }
    private static String period(String t){
        boolean h1=has(t,"first half","1st half","1h","نیمه اول");
        boolean h2=has(t,"second half","2nd half","2h","نیمه دوم");
        boolean et=has(t,"extra time","overtime","وقت اضافه");
        boolean ft=has(t,"full time","90 minutes","90 min","کل مسابقه","پایان مسابقه","وقت قانونی","تمام وقت");
        if((h1?1:0)+(h2?1:0)+(et?1:0)+(ft?1:0)!=1)return null;
        return h1?"1H":h2?"2H":et?"ET":"FT";
    }
    private static boolean has(String t,String... words){for(String w:words)if(t.contains(w))return true;return false;}
    private static String norm(String s){
        return s.toLowerCase(Locale.ROOT).replace('ي','ی').replace('ك','ک')
        .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
        .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
        .replace('٫','.').replace('−','-').replace('–','-').replaceAll("\\s+"," ").trim();
    }
}
