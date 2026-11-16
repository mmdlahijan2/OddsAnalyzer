package com.odd.analyzer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private EditText urlInput, bankrollInput;
    private TextView status, analysis;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastSignature = "";
    private int polls = 0;

    private static final String EXTRACT_SCRIPT = """
      (function() {
        try {
          const visible = e => {
            const r=e.getBoundingClientRect(), s=getComputedStyle(e);
            return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none';
          };
          const odd = t => {
            const v=String(t||'').trim().replace(',','.');
            return /^\\d{1,3}(?:\\.\\d{1,3})?$/.test(v) && Number(v)>=1.01 && Number(v)<=1000;
          };
          const leaves=[...document.querySelectorAll('button,[role="button"],a,span,div')]
            .filter(e=>visible(e) && odd(e.innerText||e.textContent) &&
              ![...e.children].some(c=>odd(c.innerText||c.textContent)));
          const groups=new Map();
          for(const el of leaves) {
            let p=el.parentElement, chosen=null;
            for(let depth=0;p && depth<5;depth++,p=p.parentElement) {
              const os=[...p.querySelectorAll('button,[role="button"],a,span,div')]
                .filter(x=>visible(x) && odd(x.innerText||x.textContent) &&
                  ![...x.children].some(c=>odd(c.innerText||c.textContent)));
              const vals=os.map(x=>String(x.innerText||x.textContent).trim().replace(',','.'));
              if(vals.length>=2 && vals.length<=3) { chosen={node:p,odds:vals}; break; }
            }
            if(chosen) {
              const raw=String(chosen.node.innerText||chosen.node.textContent||'')
                .replace(/\\s+/g,' ').trim().slice(0,420);
              const key=chosen.odds.join('|')+'::'+raw;
              if(!groups.has(key)) groups.set(key,{text:raw,odds:chosen.odds});
            }
          }
          const payload={url:location.href,title:document.title,
            groups:[...groups.values()].slice(0,80)};
          if(window.OddsBridge) window.OddsBridge.onData(JSON.stringify(payload));
        } catch(e) {
          if(window.OddsBridge) window.OddsBridge.onData(JSON.stringify({error:String(e)}));
        }
      })();
    """;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private int dp(float v) {
        return (int)(v*getResources().getDisplayMetrics().density+0.5f);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView t=new TextView(this);
        t.setText(text); t.setTextSize(size); t.setTextColor(color);
        if(bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(10),dp(14),dp(8));
        root.setBackgroundColor(Color.rgb(246,248,251));

        root.addView(label("تحلیل‌گر ضرایب",23,Color.rgb(15,23,42),true));
        TextView intro=label("لینک مسابقه را وارد کن تا بازارها و ضرایب قابل مشاهده بررسی شوند.",13,Color.rgb(71,85,105),false);
        intro.setPadding(0,dp(4),0,dp(7)); root.addView(intro);

        urlInput=new EditText(this);
        urlInput.setSingleLine(true); urlInput.setTextSize(14);
        urlInput.setHint("لینک کامل مسابقه را اینجا وارد کن");
        root.addView(urlInput,new LinearLayout.LayoutParams(-1,dp(50)));

        bankrollInput=new EditText(this);
        bankrollInput.setSingleLine(true); bankrollInput.setInputType(8194);
        bankrollInput.setText("100"); bankrollInput.setHint("مبلغ فرضی سرمایه");
        root.addView(bankrollInput,new LinearLayout.LayoutParams(-1,dp(48)));

        Button button=new Button(this);
        button.setText("بررسی مسابقه");
        root.addView(button);
        button.setOnClickListener(v->loadMatch());

        status=label("آماده بررسی؛ لینک مسابقه را وارد کن.",13,Color.rgb(51,65,85),false);
        status.setPadding(0,dp(7),0,dp(7)); root.addView(status);

        ScrollView resultScroll=new ScrollView(this);
        analysis=label("بعد از بررسی، هر بازار با نام، توضیح ساده و نتیجه محاسبه در این قسمت نمایش داده می‌شود.",14,Color.rgb(30,41,59),false);
        analysis.setPadding(dp(10),dp(10),dp(10),dp(14));
        resultScroll.addView(analysis);
        root.addView(resultScroll,new LinearLayout.LayoutParams(-1,dp(235)));

        webView=new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings=webView.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        webView.addJavascriptInterface(new OddsBridge(),"OddsBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                status.setText("صفحه باز شد؛ در حال پیدا کردن بازارها و ضرایب...");
                polls=0; lastSignature="";
                handler.postDelayed(pollTask,1600);
            }
        });
        root.addView(webView,new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);
    }

    private void loadMatch() {
        String url=urlInput.getText().toString().trim();
        if(url.isEmpty()){status.setText("اول لینک مسابقه را وارد کن.");return;}
        try {
            URI u=URI.create(url);
            String host=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equalsIgnoreCase(u.getScheme()) ||
               !(host.contains("1xbet") || host.contains("bet"))){
                status.setText("لینک معتبر HTTPS از صفحه مسابقه وارد کن."); return;
            }
        } catch(Exception e){status.setText("فرمت لینک درست نیست.");return;}
        handler.removeCallbacks(pollTask);
        analysis.setText("در حال باز کردن مسابقه و خواندن ضرایب...");
        status.setText("در حال اتصال به سایت...");
        webView.loadUrl(url);
    }

    private final Runnable pollTask=new Runnable(){
        @Override public void run(){
            if(webView==null || polls++>24){
                status.setText("زمان بررسی تمام شد. ممکن است سایت اجازه خواندن ضرایب را ندهد.");
                return;
            }
            webView.evaluateJavascript(EXTRACT_SCRIPT,null);
            handler.postDelayed(this,2300);
        }
    };

    private class OddsBridge {
        @JavascriptInterface public void onData(String json){
            runOnUiThread(()->processExtractedData(json));
        }
    }

    private void processExtractedData(String json){
        try{
            JSONObject data=new JSONObject(json);
            if(data.has("error")){status.setText("خواندن صفحه ناموفق بود: "+data.optString("error"));return;}
            JSONArray groups=data.optJSONArray("groups");
            if(groups==null || groups.length()==0){
                if(polls>=8) status.setText("صفحه باز است، اما هنوز ضریب قابل تشخیصی پیدا نشده است.");
                return;
            }
            String sig=groups.toString();
            if(sig.equals(lastSignature)) return;
            lastSignature=sig;
            renderAnalysis(data,groups);
        }catch(Exception e){status.setText("اطلاعات صفحه قابل خواندن نبود.");}
    }

    private void renderAnalysis(JSONObject data,JSONArray groups){
        double bankroll=100;
        try{bankroll=Double.parseDouble(bankrollInput.getText().toString().trim());}
        catch(Exception ignored){}
        if(!(bankroll>0) || Double.isInfinite(bankroll) || Double.isNaN(bankroll)) bankroll=100;

        ArrayList<Market> markets=new ArrayList<>();
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i); if(g==null) continue;
            JSONArray a=g.optJSONArray("odds");
            if(a==null || a.length()<2 || a.length()>3) continue;
            ArrayList<Double> odds=new ArrayList<>();
            for(int j=0;j<a.length();j++){
                double x=parseOdd(a.optString(j));
                if(x>=1.01 && x<=1000) odds.add(x);
            }
            if(odds.size()==a.length()){
                String raw=g.optString("text","").replaceAll("\\s+"," ").trim();
                String title=makeReadableTitle(raw);
                markets.add(new Market(title,raw,odds));
            }
        }

        SpannableStringBuilder out=new SpannableStringBuilder();
        addLine(out,"مسابقه: "+data.optString("title","نامشخص")+"\n",Color.rgb(15,23,42),true);
        addLine(out,"تعداد بازارهای پیدا شده: "+markets.size()+"\n",Color.rgb(71,85,105),false);
        addLine(out,"راهنما: ✅ یعنی فقط از نظر محاسباتی گزینه امیدوارکننده است؛ قبل از شرط باید نام و همه نتیجه‌های بازار را بررسی کنی.\n\n",Color.rgb(71,85,105),false);

        if(markets.isEmpty()){
            addLine(out,"بازاری برای تحلیل پیدا نشد. ممکن است ضرایب هنوز بارگذاری نشده باشند یا سایت دسترسی را محدود کرده باشد.\n",Color.rgb(180,83,9),true);
        } else {
            int shown=Math.min(18,markets.size());
            for(int i=0;i<shown;i++){
                Market m=markets.get(i);
                m.sum=0; for(double odd:m.odds) m.sum+=1.0/odd;
                boolean named=hasRecognizableOutcomes(m.raw,m.odds.size());
                boolean candidate=m.sum<0.999999 && named;
                addLine(out,(candidate?"✅ ":"🔎 ")+"بازار "+(i+1)+"\n",candidate?Color.rgb(21,128,61):Color.rgb(37,99,235),true);
                addLine(out,"این شرط درباره چیست؟\n",Color.rgb(51,65,85),true);
                addLine(out,m.title+"\n",Color.rgb(15,23,42),true);
                addLine(out,explainMarket(m.raw,m.odds.size())+"\n",Color.rgb(71,85,105),false);
                addLine(out,"ضرایب پیدا شده: ",Color.rgb(51,65,85),true);
                for(int j=0;j<m.odds.size();j++){
                    if(j>0)addLine(out,"  |  ",Color.rgb(100,116,139),false);
                    addLine(out,String.format(Locale.US,"%.2f",m.odds.get(j)),Color.rgb(15,23,42),true);
                }
                addLine(out,"\nمجموع احتمال ضمنی: "+String.format(Locale.US,"%.2f%%",m.sum*100)+"\n",Color.rgb(51,65,85),false);
                if(candidate){
                    double theoretical=(1.0/m.sum-1.0)*100.0;
                    double totalReturn=bankroll/m.sum;
                    addLine(out,"از نظر ریاضی نامزد آربیتراژ است؛ سود نظری حدود "+String.format(Locale.US,"%.2f%%",theoretical)+" است.\n",Color.rgb(21,128,61),true);
                    addLine(out,"تقسیم مبلغ فرضی "+String.format(Locale.US,"%.2f",bankroll)+": ",Color.rgb(51,65,85),false);
                    for(int j=0;j<m.odds.size();j++){
                        if(j>0)addLine(out," / ",Color.rgb(100,116,139),false);
                        addLine(out,String.format(Locale.US,"%.2f",totalReturn/m.odds.get(j)),Color.rgb(21,128,61),true);
                    }
                    addLine(out,"\n",Color.rgb(51,65,85),false);
                }else if(m.sum<0.999999){
                    addLine(out,"⚠️ مجموع ضرایب ظاهراً جذاب است، اما نام یا کامل بودن نتیجه‌های بازار مشخص نیست؛ تیک تأیید نمی‌گیرد.\n",Color.rgb(180,83,9),true);
                }else{
                    addLine(out,"❌ با این ضرایب آربیتراژ محاسباتی دیده نمی‌شود.\n",Color.rgb(185,28,28),true);
                }
                addLine(out,"────────────────────────\n",Color.rgb(148,163,184),false);
            }
        }
        addLine(out,"نکته مهم: این نسخه نام بازار و نتیجه‌ها را از متن صفحه حدس می‌زند. تیک سبز تأیید قطعی برد یا سود نیست. اگر سایت ضرایب ناقص نشان دهد، شرط‌ها تغییر کنند یا بازارها دقیقاً متناظر نباشند، محاسبه معتبر نیست.\n",Color.rgb(180,83,9),true);
        analysis.setText(out);
        status.setText("بررسی تمام شد؛ "+markets.size()+" گروه ضریب پیدا شد.");
    }

    private void addLine(SpannableStringBuilder b,String s,int color,boolean bold){
        int start=b.length(); b.append(s);
        b.setSpan(new ForegroundColorSpan(color),start,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if(bold)b.setSpan(new StyleSpan(Typeface.BOLD),start,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String makeReadableTitle(String raw){
        if(raw==null || raw.trim().isEmpty()) return "عنوان بازار در صفحه مشخص نبود";
        String s=raw.replaceAll("(?<![A-Za-z])\\d{1,3}(?:[.,]\\d{1,3})?(?![A-Za-z])"," ");
        s=s.replaceAll("\\s+"," ").replaceAll("^[|:/,\\- ]+|[|:/,\\- ]+$","");
        if(s.length()>145)s=s.substring(0,145)+"…";
        return s.isEmpty()?"عنوان بازار در صفحه مشخص نبود":s;
    }

    private boolean hasRecognizableOutcomes(String raw,int count){
        if(raw==null)return false;
        String s=raw.toLowerCase(Locale.ROOT);
        if(count==2){
            return (s.contains("over")&&s.contains("under")) ||
                   (s.contains("بیشتر")&&s.contains("کمتر")) ||
                   (s.contains("yes")&&s.contains("no")) ||
                   (s.contains("بله")&&s.contains("خیر"));
        }
        if(count==3){
            return (s.contains("home")&&s.contains("draw")&&s.contains("away")) ||
                   (s.contains("میزبان")&&s.contains("مساوی")&&s.contains("میهمان")) ||
                   (s.contains("1")&&s.contains("x")&&s.contains("2"));
        }
        return false;
    }

    private String explainMarket(String raw,int count){
        if(raw==null || raw.isEmpty()) return "متن بازار از سایت به‌طور کامل خوانده نشد؛ عنوان و موضوع شرط نامشخص است.";
        String s=raw.toLowerCase(Locale.ROOT);
        if((s.contains("over")&&s.contains("under")) || (s.contains("بیشتر")&&s.contains("کمتر")))
            return "این بازار ظاهراً درباره بیشتر یا کمتر بودن یک مقدار مشخص (مثلاً تعداد گل) است. عدد خط شرط را در صفحه کنترل کن.";
        if((s.contains("yes")&&s.contains("no")) || (s.contains("بله")&&s.contains("خیر")))
            return "این بازار ظاهراً پاسخ بله/خیر به یک اتفاق در مسابقه را می‌سنجد. متن دقیق اتفاق را بررسی کن.";
        if(s.contains("home")&&s.contains("draw")&&s.contains("away"))
            return "این بازار ظاهراً نتیجه نهایی مسابقه است: برد میزبان، مساوی یا برد میهمان.";
        if(s.contains("میزبان")&&s.contains("مساوی")&&s.contains("میهمان"))
            return "این بازار ظاهراً نتیجه نهایی مسابقه است: برد میزبان، مساوی یا برد میهمان.";
        if(s.contains("corner")||s.contains("کرنر"))
            return "این بازار به تعداد یا نتیجه کرنرها مربوط است؛ خط و بازه زمانی بازار را بررسی کن.";
        if(s.contains("card")||s.contains("کارت"))
            return "این بازار به کارت‌های مسابقه مربوط است؛ نوع کارت و بازه زمانی را بررسی کن.";
        return "موضوع دقیق شرط از متن قابل استخراج با اطمینان مشخص نیست. توضیح بالای بازار را در صفحه مسابقه با دقت تطبیق بده.";
    }

    private double parseOdd(String s){
        try{return Double.parseDouble(s.trim().replace(',','.'));}
        catch(Exception e){return -1;}
    }

    private static class Market{
        final String title,raw;
        final ArrayList<Double> odds;
        double sum;
        Market(String title,String raw,ArrayList<Double> odds){this.title=title;this.raw=raw;this.odds=odds;}
    }

    @Override public void onBackPressed(){
        if(webView!=null && webView.canGoBack())webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        if(webView!=null){webView.removeJavascriptInterface("OddsBridge");webView.destroy();}
        super.onDestroy();
    }
}
