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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private EditText[] urlInputs = new EditText[4];
    private EditText bankrollInput, aiEndpointInput;
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private Spinner countSpinner;
    private TextView status, analysis;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int siteCount=2, siteIndex=0, polls=0;
    private final ArrayList<SiteResult> results=new ArrayList<>();
    private JSONArray currentGroups=new JSONArray();
    private String currentTitle="", lastSignature="";
    private boolean waitingForPage=false;
    private JSONObject lastDiagnostics=new JSONObject();

    private static final String EXTRACT_SCRIPT = """
      (function() {
        const docs=[document], seen=new Set(), frameInfo={total:0,accessible:0,blocked:0};
        function addFrames(doc){
          if(seen.has(doc))return; seen.add(doc);
          let frames=[];try{frames=[...doc.querySelectorAll('iframe,frame')];}catch(e){}
          frameInfo.total+=frames.length;
          for(const fr of frames){
            try{
              const child=fr.contentDocument;
              if(child&&child.documentElement){frameInfo.accessible++;docs.push(child);addFrames(child);}
              else frameInfo.blocked++;
            }catch(e){frameInfo.blocked++;}
          }
        }
        function visible(e,w){
          try{const r=e.getBoundingClientRect(),s=w.getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';}
          catch(x){return false;}
        }
        function odd(t){
          const v=String(t||'').trim().replace(',','.');
          return /^\\d{1,3}(?:\\.\\d{1,3})?$/.test(v)&&Number(v)>=1.01&&Number(v)<=1000;
        }
        try{
          addFrames(document);
          const groups=new Map();let candidates=0,docTexts=0;
          for(const doc of docs){
            let w=doc.defaultView||window;
            try{docTexts+=String(doc.body?.innerText||'').length;}catch(e){}
            const nodes=[...doc.querySelectorAll('button,[role="button"],a,span,div')];
            const leaves=nodes.filter(e=>visible(e,w)&&odd(e.innerText||e.textContent)&&
              ![...e.children].some(ch=>odd(ch.innerText||ch.textContent)));
            candidates+=leaves.length;
            for(const el of leaves){
              let p=el.parentElement,chosen=null;
              for(let depth=0;p&&depth<6;depth++,p=p.parentElement){
                const os=[...p.querySelectorAll('button,[role="button"],a,span,div')]
                  .filter(x=>visible(x,w)&&odd(x.innerText||x.textContent)&&
                    ![...x.children].some(ch=>odd(ch.innerText||ch.textContent)));
                const vals=os.map(x=>String(x.innerText||x.textContent).trim().replace(',','.'));
                if(vals.length>=2&&vals.length<=3){chosen={node:p,odds:vals};break;}
              }
              if(chosen){
                const raw=String(chosen.node.innerText||chosen.node.textContent||'').replace(/\\s+/g,' ').trim().slice(0,420);
                const key=chosen.odds.join('|')+'::'+raw;
                if(!groups.has(key))groups.set(key,{text:raw,odds:chosen.odds});
              }
            }
          }
          const bodyText=String(document.body?.innerText||'').toLowerCase();
          const loginHint=/log in|login|sign in|ورود به حساب|captcha|verify you are human|security check/.test(bodyText);
          const diagnostics={frames:frameInfo.total,accessibleFrames:frameInfo.accessible,blockedFrames:frameInfo.blocked,
            oddsCandidates:candidates,bodyTextLength:docTexts,loginHint:loginHint};
          if(window.OddsBridge)window.OddsBridge.onData(JSON.stringify({
            url:location.href,title:document.title,groups:[...groups.values()].slice(0,100),diagnostics:diagnostics
          }));
        }catch(e){
          if(window.OddsBridge)window.OddsBridge.onData(JSON.stringify({error:String(e),groups:[],diagnostics:frameInfo}));
        }
      })();
    """;

    @Override public void onCreate(Bundle state){super.onCreate(state);buildUi();}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String text,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);return t;
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(10),dp(14),dp(8));root.setBackgroundColor(Color.rgb(246,248,251));
        root.addView(label("OddsAnalyzer | مقایسه چندسایتی",22,Color.rgb(15,23,42),true));
        TextView intro=label("تعداد سایت‌ها را انتخاب کن و لینک همان مسابقه را در هر سایت وارد کن. برنامه فقط بازارهای قابل‌تطبیق را مقایسه می‌کند.",13,Color.rgb(71,85,105),false);
        intro.setPadding(0,dp(5),0,dp(6));root.addView(intro);
        countSpinner=new Spinner(this);
        String[] counts={"۲ سایت","۳ سایت","۴ سایت"};
        countSpinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,counts));
        root.addView(label("تعداد سایت‌ها",14,Color.rgb(30,41,59),true));root.addView(countSpinner);
        for(int i=0;i<4;i++){
            urlInputs[i]=new EditText(this);urlInputs[i].setSingleLine(true);urlInputs[i].setTextSize(14);
            urlInputs[i].setHint("آدرس مسابقه در سایت "+(i+1));
            root.addView(urlInputs[i],new LinearLayout.LayoutParams(-1,dp(48)));
            if(i>=2)urlInputs[i].setVisibility(View.GONE);
        }
        bankrollInput=new EditText(this);bankrollInput.setSingleLine(true);bankrollInput.setInputType(8194);bankrollInput.setText("100");bankrollInput.setHint("سرمایه فرضی (مثلاً 100)");
        root.addView(bankrollInput,new LinearLayout.LayoutParams(-1,dp(46)));
        aiEndpointInput=new EditText(this);aiEndpointInput.setSingleLine(true);aiEndpointInput.setTextSize(13);
        aiEndpointInput.setHint("آدرس سرور هوش مصنوعی (اختیاری؛ HTTPS)");
        root.addView(aiEndpointInput,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView aiNote=label("برای فعال‌سازی تحلیل هوشمند، Worker امن را مستقر و نشانی /match آن را اینجا وارد کن. بدون آن، موتور محلی اجرا می‌شود.",11,Color.rgb(100,116,139),false);
        root.addView(aiNote);
        countSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                siteCount=pos+2;for(int i=0;i<4;i++)urlInputs[i].setVisibility(i<siteCount?View.VISIBLE:View.GONE);
            }
        });
        Button go=new Button(this);go.setText("مقایسه ضرایب سایت‌ها");root.addView(go);go.setOnClickListener(v->startComparison());
        status=label("آماده؛ لینک مسابقه‌ها را وارد کن.",13,Color.rgb(51,65,85),false);status.setPadding(0,dp(6),0,dp(6));root.addView(status);
        ScrollView sc=new ScrollView(this);analysis=label("پس از دریافت ضرایب، بازارهای مشترک و بهترین ضرایب هر نتیجه اینجا نمایش داده می‌شوند.",14,Color.rgb(30,41,59),false);
        analysis.setPadding(dp(10),dp(8),dp(10),dp(12));sc.addView(analysis);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        webView=new WebView(this);webView.setVisibility(View.GONE);WebSettings ws=webView.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new OddsBridge(),"OddsBridge");webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){
            if(!waitingForPage)return;status.setText("سایت "+(siteIndex+1)+" باز شد؛ در حال خواندن ضرایب...");
            polls=0;lastSignature="";currentGroups=new JSONArray();currentTitle="";lastDiagnostics=new JSONObject();
            handler.removeCallbacks(pollTask);handler.postDelayed(pollTask,1400);
        }});
        root.addView(webView,new LinearLayout.LayoutParams(1,1));setContentView(root);
    }
    private void startComparison(){
        siteCount=countSpinner.getSelectedItemPosition()+2;results.clear();siteIndex=0;
        for(int i=0;i<siteCount;i++){
            String u=urlInputs[i].getText().toString().trim();
            if(!validUrl(u)){status.setText("آدرس سایت "+(i+1)+" معتبر نیست؛ لینک کامل HTTPS مسابقه را وارد کن.");return;}
            for(int j=0;j<i;j++)if(host(u).equals(host(urlInputs[j].getText().toString().trim()))){status.setText("برای هر ردیف یک سایت متفاوت وارد کن.");return;}
        }
        String aiEndpoint=aiEndpointInput.getText().toString().trim();
        if(!aiEndpoint.isEmpty()&&!validUrl(aiEndpoint)){status.setText("نشانی سرور هوش مصنوعی باید یک آدرس کامل HTTPS باشد.");return;}
        handler.removeCallbacks(pollTask);analysis.setText("مقایسه در حال شروع است...");loadCurrentSite();
    }
    private boolean validUrl(String s){
        try{URI u=URI.create(s);String h=u.getHost();return "https".equalsIgnoreCase(u.getScheme())&&h!=null&&h.contains(".");}
        catch(Exception e){return false;}
    }
    private String host(String s){try{return URI.create(s).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");}catch(Exception e){return s;}}
    private void loadCurrentSite(){
        if(siteIndex>=siteCount){waitingForPage=false;analyzeWithAiOrLocal();return;}
        String u=urlInputs[siteIndex].getText().toString().trim();waitingForPage=true;
        status.setText("در حال باز کردن سایت "+(siteIndex+1)+" از "+siteCount+": "+host(u));
        webView.loadUrl(u);
    }
    private final Runnable pollTask=new Runnable(){public void run(){
        if(!waitingForPage)return;
        if(polls++>=9){finishCurrentSite();return;}
        webView.evaluateJavascript(EXTRACT_SCRIPT,null);handler.postDelayed(this,1800);
    }};
    private class OddsBridge{@JavascriptInterface public void onData(String json){runOnUiThread(()->{
        try{JSONObject d=new JSONObject(json);JSONArray g=d.optJSONArray("groups");JSONObject diag=d.optJSONObject("diagnostics");if(diag!=null)lastDiagnostics=diag;
            if(g!=null&&g.length()>0){currentGroups=g;currentTitle=d.optString("title","");String sig=g.toString();
              if(sig.equals(lastSignature)){finishCurrentSite();return;}lastSignature=sig;}}
        catch(Exception ignored){}
    });}}
    private void finishCurrentSite(){
        if(!waitingForPage)return;waitingForPage=false;handler.removeCallbacks(pollTask);
        String url=urlInputs[siteIndex].getText().toString().trim();
        results.add(new SiteResult(host(url),currentTitle,currentGroups,lastDiagnostics));
        String reason=extractionReason(currentGroups,lastDiagnostics);
        status.setText("سایت "+(siteIndex+1)+" تمام شد؛ "+currentGroups.length()+" گروه ضریب. "+reason);
        siteIndex++;handler.postDelayed(()->loadCurrentSite(),700);
    }
    private void analyzeWithAiOrLocal(){
        String endpoint=aiEndpointInput.getText().toString().trim();
        if(endpoint.isEmpty()){showComparison(null);return;}
        status.setText("در حال ارسال عناوین و ضرایب به موتور هوش مصنوعی...");
        JSONArray sites=new JSONArray();
        try{for(SiteResult site:results){JSONObject item=new JSONObject();item.put("host",site.host);item.put("title",site.title);item.put("groups",site.groups);sites.put(item);}}
        catch(Exception e){showComparison(null);return;}
        JSONObject payload=new JSONObject();try{payload.put("sites",sites);}catch(Exception ignored){}
        aiExecutor.execute(()->{
            JSONObject response=null;String error="";HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");connection.setConnectTimeout(20000);connection.setReadTimeout(60000);
                connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream os=connection.getOutputStream()){os.write(bytes);}
                int code=connection.getResponseCode();java.io.InputStream stream=(code>=200&&code<300)?connection.getInputStream():connection.getErrorStream();
                StringBuilder body=new StringBuilder();
                if(stream!=null)try(BufferedReader br=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)body.append(line);}
                if(code>=200&&code<300){response=new JSONObject(body.toString());if(response.optJSONArray("markets")==null)error="پاسخ سرور ساختار معتبر ندارد";}
                else error="سرور هوش مصنوعی پاسخ خطا داد (HTTP "+code+")";
            }catch(Exception e){error="اتصال به سرور هوش مصنوعی برقرار نشد";}
            finally{if(connection!=null)connection.disconnect();}
            final JSONObject result=response;final String failure=error;
            runOnUiThread(()->{
                if(result!=null&&result.optJSONArray("markets")!=null){showComparison(result);status.setText(status.getText()+" | تطبیق معنایی هوش مصنوعی فعال بود.");}
                else{showComparison(null);status.setText(status.getText()+" | هوش مصنوعی در دسترس نبود؛ نتیجه فقط از موتور محلی است. "+failure);}
            });
        });
    }
    private JSONObject findAiMarket(JSONObject ai,int siteIndex,int groupIndex){
        if(ai==null)return null;JSONArray a=ai.optJSONArray("markets");if(a==null)return null;
        for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);if(m!=null&&m.optInt("site_index",-1)==siteIndex&&m.optInt("group_index",-1)==groupIndex)return m;}
        return null;
    }
    private JSONArray trustedAiOutcomes(JSONObject ai,int siteIndex,int groupIndex,JSONObject group){
        JSONObject m=findAiMarket(ai,siteIndex,groupIndex);if(m==null||m.optDouble("confidence",0)<0.88)return null;
        String type=m.optString("market_type","UNKNOWN"),period=m.optString("period","UNKNOWN");
        String line=m.optString("line","").trim(),scope=m.optString("scope",""),participant=m.optString("participant","");
        if("UNKNOWN".equals(type)||"UNKNOWN".equals(period)||line.isEmpty()||scope.isEmpty())return null;
        JSONArray outcomes=m.optJSONArray("outcomes"),odds=group.optJSONArray("odds");
        if(outcomes==null||odds==null||outcomes.length()!=odds.length()||outcomes.length()<2||outcomes.length()>3)return null;
        java.util.HashSet<String> unique=new java.util.HashSet<>();
        for(int i=0;i<outcomes.length();i++){String o=outcomes.optString(i,"UNKNOWN");if("UNKNOWN".equals(o)||!unique.add(o))return null;}
        if("TOTAL_GOALS".equals(type)||"TEAM_TOTAL".equals(type)){
            if(outcomes.length()!=2||!unique.contains("OVER")||!unique.contains("UNDER")||"NONE".equals(line))return null;
            if("TEAM_TOTAL".equals(type)&&("NONE".equals(participant)||participant.isEmpty()))return null;
        }else if("BTTS".equals(type)){
            if(outcomes.length()!=2||!unique.contains("YES")||!unique.contains("NO"))return null;
        }else if("MATCH_RESULT".equals(type)){
            if(outcomes.length()!=3||!unique.contains("HOME")||!unique.contains("DRAW")||!unique.contains("AWAY"))return null;
        }else if("DOUBLE_CHANCE".equals(type)){
            if(outcomes.length()!=3)return null;
            for(int i=0;i<3;i++)if(!java.util.Arrays.asList("HOME_DRAW","HOME_AWAY","DRAW_AWAY").contains(outcomes.optString(i)))return null;
        }else if("ASIAN_HANDICAP".equals(type)){
            if(outcomes.length()!=2||!unique.contains("HOME")||!unique.contains("AWAY")||"NONE".equals(line))return null;
        }else return null;
        return outcomes;
    }
    private String aiMarketKey(JSONObject ai,int siteIndex,int groupIndex,JSONObject group){
        JSONObject m=findAiMarket(ai,siteIndex,groupIndex);JSONArray outcomes=trustedAiOutcomes(ai,siteIndex,groupIndex,group);
        if(m==null||outcomes==null)return "";
        String type=m.optString("market_type"),period=m.optString("period"),line=m.optString("line").trim();
        String scope=m.optString("scope").trim().toUpperCase(Locale.ROOT);
        String participant=m.optString("participant","NONE").trim().toLowerCase(Locale.ROOT);
        return type+"|LINE="+line+"|PERIOD="+period+"|SCOPE="+scope+"|PARTICIPANT="+participant;
    }
    private void showComparison(JSONObject ai){
        SpannableStringBuilder out=new SpannableStringBuilder();
        add(out,"نتیجه مقایسه "+siteCount+" سایت\n",Color.rgb(15,23,42),true);
        for(SiteResult s:results)add(out,"• "+s.host+" — "+s.groups.length()+" گروه ضریب\n",Color.rgb(71,85,105),false);
        add(out,"\nبازارها با مشخصات استانداردشده و نام گزینه‌ها تطبیق داده می‌شوند؛ موارد نامطمئن کنار گذاشته می‌شوند.\n\n",Color.rgb(51,65,85),false);
        LinkedHashMap<String,ArrayList<MarketAtSite>> common=new LinkedHashMap<>();
        if(!results.isEmpty()){
            JSONArray first=results.get(0).groups;
            for(int i=0;i<first.length();i++){
                JSONObject g=first.optJSONObject(i);if(g==null)continue;
                String key=ai==null?marketKey(g):aiMarketKey(ai,0,i,g);
                if(key.isEmpty()||common.containsKey(key))continue;
                ArrayList<MarketAtSite> matches=new ArrayList<>();boolean all=true;
                for(int si=0;si<results.size();si++){
                    MarketAtSite found=findMarket(results.get(si),key,si,ai);
                    if(found==null){all=false;break;}matches.add(found);
                }
                if(all)common.put(key,matches);
            }
        }
        if(common.isEmpty())add(out,"بازار مشترک قابل‌تطبیقی پیدا نشد. ممکن است سایت‌ها بازار را با نام یا ساختار متفاوت نمایش دهند، یا استخراج ضرایب کامل نباشد.\n",Color.rgb(180,83,9),true);
        int idx=0,arb=0;
        for(Map.Entry<String,ArrayList<MarketAtSite>> entry:common.entrySet()){
            ArrayList<MarketAtSite> list=entry.getValue();int n=list.get(0).odds.size();if(n<2||n>3)continue;
            java.util.LinkedHashSet<String> outcomeSet=new java.util.LinkedHashSet<>(list.get(0).outcomes);
            if(outcomeSet.size()!=n || outcomeSet.contains("UNKNOWN"))continue;
            boolean compatible=true;
            for(MarketAtSite m:list){if(m.outcomes.size()!=n || !outcomeSet.equals(new java.util.LinkedHashSet<>(m.outcomes))){compatible=false;break;}}
            if(!compatible)continue;
            ArrayList<String> outcomes=new ArrayList<>(outcomeSet);
            double[] best=new double[n];String[] bestSite=new String[n];
            for(int k=0;k<n;k++){
                best[k]=0;
                for(MarketAtSite m:list){int oi=m.outcomes.indexOf(outcomes.get(k));if(oi<0){compatible=false;break;}double x=m.odds.get(oi);if(x>best[k]){best[k]=x;bestSite[k]=m.host;}}
            }
            if(!compatible)continue;
            double sum=0;for(double x:best)sum+=1.0/x;
            double bankroll=readBankroll();boolean sure=sum<0.999999;
            idx++;if(sure)arb++;
            JSONObject firstMarket=list.get(0).source;
            add(out,(sure?"✅ ":"🔎 ")+"بازار مشترک "+idx+"\n",sure?Color.rgb(21,128,61):Color.rgb(37,99,235),true);
            add(out,makeReadableTitle(firstMarket.optString("text",""))+"\n",Color.rgb(15,23,42),true);
            add(out,"بهترین ضرایب (پس از تطبیق نام گزینه‌ها):\n",Color.rgb(51,65,85),false);
            for(int k=0;k<n;k++)add(out,outcomeLabel(outcomes.get(k))+": "+String.format(Locale.US,"%.2f",best[k])+" — "+bestSite[k]+"\n",Color.rgb(30,41,59),true);
            add(out,"مجموع احتمال ضمنی: "+String.format(Locale.US,"%.2f%%",sum*100)+"\n",Color.rgb(51,65,85),false);
            if(sure){
                double profit=(1.0/sum-1)*100.0;double payout=bankroll/sum;
                add(out,"نامزد آربیتراژ: سود نظری "+String.format(Locale.US,"%.2f%%",profit)+"\n",Color.rgb(21,128,61),true);
                for(int k=0;k<n;k++)add(out,"مبلغ نتیجه "+(k+1)+": "+String.format(Locale.US,"%.2f",payout/best[k])+"\n",Color.rgb(21,128,61),false);
            }else add(out,"❌ با بهترین ضرایب فعلی آربیتراژ ریاضی دیده نمی‌شود.\n",Color.rgb(185,28,28),false);
            add(out,"────────────────────────\n",Color.rgb(148,163,184),false);
        }
        add(out,"هشدار مهم: هوش مصنوعی ممکن است عنوان یا گزینه را اشتباه تفسیر کند. برنامه هویت واقعی مسابقه، قوانین تسویه، شرایط مساوی/لغو و زنده‌بودن ضرایب را مستقلاً تأیید نمی‌کند. همه موارد را دستی بررسی کن؛ تیک سبز تضمین سود یا امکان ثبت شرط نیست.\n",Color.rgb(180,83,9),true);
        analysis.setText(out);status.setText("مقایسه تمام شد؛ "+common.size()+" بازار مشترک پیدا شد و "+arb+" نامزد آربیتراژ محاسباتی.");
    }
    private String extractionReason(JSONArray groups,JSONObject d){
        if(groups!=null&&groups.length()>0)return "استخراج موفق";
        int frames=d==null?0:d.optInt("frames",0),blocked=d==null?0:d.optInt("blockedFrames",0);
        int accessible=d==null?0:d.optInt("accessibleFrames",0),candidates=d==null?0:d.optInt("oddsCandidates",0);
        int text=d==null?0:d.optInt("bodyTextLength",0);
        if(d!=null&&d.optBoolean("loginHint",false))return "احتمالاً صفحه ورود/تأیید نمایش داده شده؛ نیازمند بررسی دستی";
        if(frames>0&&blocked>0&&accessible==0)return "ضرایب احتمالاً داخل iframe بین‌دامنه‌ای هستند و از صفحه اصلی قابل‌خواندن نیستند";
        if(candidates>0)return "اعداد شبیه ضریب دیده شد، اما گروه کامل ضرایب شناسایی نشد";
        if(text<80)return "محتوای صفحه بسیار کم است؛ احتمال بارگذاری ناقص";
        if(frames>0&&blocked>0)return "بخشی از iframeها به‌دلیل محدودیت دسترسی مرورگر قابل‌خواندن نیستند";
        return "صفحه خوانده شد، اما ساختار ضرایب شناسایی نشد";
    }
    private double readBankroll(){try{double x=Double.parseDouble(bankrollInput.getText().toString().trim());if(x>0&&Double.isFinite(x))return x;}catch(Exception ignored){}return 100;}
    private MarketAtSite findMarket(SiteResult site,String key,int siteIndex,JSONObject ai){
        for(int i=0;i<site.groups.length();i++){
            JSONObject g=site.groups.optJSONObject(i);if(g==null)continue;
            String candidate=ai==null?marketKey(g):aiMarketKey(ai,siteIndex,i,g);
            if(key.equals(candidate)){JSONArray outcomes=ai==null?null:trustedAiOutcomes(ai,siteIndex,i,g);return new MarketAtSite(site.host,g,outcomes);}
        }
        return null;
    }
    private String marketKey(JSONObject g){
        SemanticMarketMatcher.Parsed p=SemanticMarketMatcher.parse(g.optString("text",""),g.optJSONArray("odds"));
        return p==null?"":p.key;
    }
    private String outcomeLabel(String s){
        if("OVER".equals(s))return "بیشتر از خط";
        if("UNDER".equals(s))return "کمتر از خط";
        if("YES".equals(s))return "بله";
        if("NO".equals(s))return "خیر";
        return s;
    }
    private String makeReadableTitle(String raw){
        if(raw==null||raw.trim().isEmpty())return "عنوان بازار مشخص نیست";
        String s=raw.replaceAll("\\s+"," ").trim();if(s.length()>180)s=s.substring(0,180)+"…";return s;
    }
    private void add(SpannableStringBuilder b,String s,int color,boolean bold){
        int st=b.length();b.append(s);b.setSpan(new ForegroundColorSpan(color),st,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if(bold)b.setSpan(new StyleSpan(Typeface.BOLD),st,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private static class SiteResult{final String host,title;final JSONArray groups;final JSONObject diagnostics;SiteResult(String h,String t,JSONArray g,JSONObject d){host=h;title=t;groups=g;diagnostics=d;}}
    private static class MarketAtSite{final String host;final JSONObject source;final ArrayList<Double> odds=new ArrayList<>();final ArrayList<String> outcomes=new ArrayList<>();MarketAtSite(String h,JSONObject s,JSONArray aiOutcomes){host=h;source=s;JSONArray a=s.optJSONArray("odds");if(a!=null)for(int i=0;i<a.length();i++){try{double v=Double.parseDouble(a.optString(i).replace(",","."));if(Double.isFinite(v)&&v>1.0&&v<=1000)odds.add(v);}catch(Exception ignored){}}if(aiOutcomes!=null){for(int i=0;i<aiOutcomes.length();i++)outcomes.add(aiOutcomes.optString(i));}else{SemanticMarketMatcher.Parsed p=SemanticMarketMatcher.parse(s.optString("text",""),a);if(p!=null)outcomes.addAll(p.outcomes);}}}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);aiExecutor.shutdownNow();if(webView!=null){webView.removeJavascriptInterface("OddsBridge");webView.destroy();}super.onDestroy();}
}