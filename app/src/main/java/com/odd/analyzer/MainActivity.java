package com.odd.analyzer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

public class MainActivity extends Activity {
    private EditText urlInput;
    private EditText bankrollInput;
    private TextView status;
    private TextView analysis;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastSignature = "";
    private int polls = 0;

    private static final String EXTRACT_SCRIPT = """
      (function() {
        try {
          const visible = e => {
            const r = e.getBoundingClientRect();
            const s = getComputedStyle(e);
            return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
          };
          const odd = t => {
            const v = String(t || '').trim().replace(',', '.');
            return /^\\d{1,3}(?:\\.\\d{1,3})?$/.test(v) && Number(v) >= 1.01 && Number(v) <= 1000;
          };
          const leaves = [...document.querySelectorAll('button,[role="button"],a,span,div')]
            .filter(e => visible(e) && odd(e.innerText || e.textContent) &&
              ![...e.children].some(c => odd(c.innerText || c.textContent)));
          const groups = new Map();
          for (const el of leaves) {
            let p = el.parentElement, chosen = null;
            for (let depth = 0; p && depth < 6; depth++, p = p.parentElement) {
              const os = [...p.querySelectorAll('button,[role="button"],a,span,div')]
                .filter(x => visible(x) && odd(x.innerText || x.textContent) &&
                  ![...x.children].some(c => odd(c.innerText || c.textContent)));
              const vals = os.map(x => String(x.innerText || x.textContent).trim().replace(',', '.'));
              if (vals.length >= 2 && vals.length <= 4) { chosen = {node:p, odds:vals}; break; }
            }
            if (chosen) {
              const text = String(chosen.node.innerText || chosen.node.textContent || '').trim().slice(0, 500);
              const key = chosen.odds.join('|') + '::' + text;
              if (!groups.has(key)) groups.set(key, {text:text, odds:chosen.odds});
            }
          }
          const body = String(document.body ? document.body.innerText : '').slice(0, 14000);
          const payload = {url:location.href, title:document.title, body:body,
            groups:[...groups.values()].slice(0,100)};
          if (window.OddsBridge) window.OddsBridge.onData(JSON.stringify(payload));
        } catch (e) {
          if (window.OddsBridge) window.OddsBridge.onData(JSON.stringify({error:String(e)}));
        }
      })();
    """;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = new TextView(this);
        title.setText("OddsAnalyzer");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14);
        urlInput.setHint("لینک مسابقه 1xBet را وارد کنید");
        root.addView(urlInput, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        bankrollInput = new EditText(this);
        bankrollInput.setSingleLine(true);
        bankrollInput.setInputType(8194);
        bankrollInput.setText("100");
        bankrollInput.setHint("سرمایه فرضی برای محاسبه");
        root.addView(bankrollInput, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button analyzeButton = new Button(this);
        analyzeButton.setText("بارگذاری و بررسی مسابقه");
        root.addView(analyzeButton);
        analyzeButton.setOnClickListener(v -> loadMatch());

        status = new TextView(this);
        status.setText("لینک مسابقه را وارد کنید.");
        status.setTextColor(Color.rgb(51, 65, 85));
        status.setTextSize(13);
        status.setPadding(0, dp(5), 0, dp(5));
        root.addView(status);

        ScrollView resultScroll = new ScrollView(this);
        analysis = new TextView(this);
        analysis.setTextColor(Color.rgb(15, 23, 42));
        analysis.setTextSize(13);
        analysis.setText("نتیجه تحلیل پس از بارگذاری بازارها نمایش داده می‌شود.");
        analysis.setPadding(dp(10), dp(8), dp(10), dp(8));
        resultScroll.addView(analysis);
        root.addView(resultScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        webView.addJavascriptInterface(new OddsBridge(), "OddsBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                status.setText("صفحه باز شد؛ در حال شناسایی بازارها و ضرایب...");
                polls = 0;
                lastSignature = "";
                handler.postDelayed(pollTask, 1800);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadMatch() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            status.setText("ابتدا لینک مسابقه را وارد کنید.");
            return;
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                !(uri.getHost().toLowerCase(Locale.ROOT).contains("1xbet") ||
                  uri.getHost().toLowerCase(Locale.ROOT).contains("bet"))) {
                status.setText("لطفاً یک لینک HTTPS معتبر از صفحه مسابقه وارد کنید.");
                return;
            }
        } catch (Exception e) {
            status.setText("ساختار لینک معتبر نیست.");
            return;
        }
        handler.removeCallbacks(pollTask);
        analysis.setText("در حال بارگذاری صفحه مسابقه...");
        status.setText("در حال اتصال به سایت...");
        webView.loadUrl(url);
    }

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (webView == null || polls++ > 24) {
                status.setText("پایان زمان بررسی. اگر بازارها دیده نمی‌شوند، ممکن است سایت دسترسی WebView را محدود کرده باشد.");
                return;
            }
            webView.evaluateJavascript(EXTRACT_SCRIPT, null);
            handler.postDelayed(this, 2500);
        }
    };

    private class OddsBridge {
        @JavascriptInterface public void onData(String json) {
            runOnUiThread(() -> processExtractedData(json));
        }
    }

    private void processExtractedData(String json) {
        try {
            JSONObject data = new JSONObject(json);
            if (data.has("error")) {
                status.setText("خطا در خواندن صفحه: " + data.optString("error"));
                return;
            }
            JSONArray groups = data.optJSONArray("groups");
            if (groups == null || groups.length() == 0) {
                if (polls >= 8) {
                    status.setText("صفحه باز است، اما هنوز بازار قابل‌استخراجی پیدا نشده است. صفحه را در پایین بررسی کنید.");
                }
                return;
            }
            String signature = groups.toString();
            if (signature.equals(lastSignature)) return;
            lastSignature = signature;
            renderAnalysis(data, groups);
        } catch (Exception e) {
            status.setText("داده صفحه قابل‌تجزیه نبود.");
        }
    }

    private void renderAnalysis(JSONObject data, JSONArray groups) {
        double bankroll = 100.0;
        try { bankroll = Double.parseDouble(bankrollInput.getText().toString().trim()); }
        catch (Exception ignored) {}
        if (!(bankroll > 0) || Double.isInfinite(bankroll)) bankroll = 100.0;

        ArrayList<Market> markets = new ArrayList<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray oddsArray = g.optJSONArray("odds");
            if (oddsArray == null || oddsArray.length() < 2 || oddsArray.length() > 3) continue;
            ArrayList<Double> odds = new ArrayList<>();
            for (int j = 0; j < oddsArray.length(); j++) {
                double o = parseOdd(oddsArray.optString(j));
                if (o >= 1.01 && o <= 1000) odds.add(o);
            }
            if (odds.size() == oddsArray.length()) {
                String text = g.optString("text", "").replaceAll("\\s+", " ").trim();
                markets.add(new Market(text, odds));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("مسابقه: ").append(data.optString("title", "نامشخص")).append("\n");
        out.append("گروه‌های بازار قابل‌خواندن: ").append(markets.size()).append("\n\n");
        if (markets.isEmpty()) {
            out.append("فعلاً هیچ بازار ۲ یا ۳ نتیجه‌ای با ساختار قابل‌اعتماد پیدا نشد. ممکن است ضرایب هنوز بارگذاری نشده باشند یا ساختار سایت تغییر کرده باشد.\n");
        } else {
            Market best = null;
            for (Market m : markets) {
                m.sum = 0;
                for (double o : m.odds) m.sum += 1.0 / o;
                if (best == null || m.sum < best.sum) best = m;
            }
            int shown = Math.min(12, markets.size());
            out.append("بهترین حاشیه ضمنیِ شناسایی‌شده (نه لزوماً بهترین شرط):\n");
            for (int i = 0; i < shown; i++) {
                Market m = markets.get(i);
                out.append("\n").append(i + 1).append(") ").append(shortText(m.text, 110)).append("\n");
                out.append("ضرایب: ");
                for (int j = 0; j < m.odds.size(); j++) {
                    if (j > 0) out.append(" | ");
                    out.append(String.format(Locale.US, "%.2f", m.odds.get(j)));
                }
                out.append("\nمجموع احتمال ضمنی: ").append(String.format(Locale.US, "%.2f%%", m.sum * 100));
                if (m.sum < 0.999999) {
                    double profitPct = (1.0 / m.sum - 1.0) * 100.0;
                    out.append("\nآربیتراژ احتمالی: ").append(String.format(Locale.US, "%.2f%%", profitPct));
                    double payout = bankroll / m.sum;
                    out.append("\nتقسیم سرمایه فرضی ").append(String.format(Locale.US, "%.2f", bankroll)).append(": ");
                    for (int j = 0; j < m.odds.size(); j++) {
                        if (j > 0) out.append(" / ");
                        out.append(String.format(Locale.US, "%.2f", payout / m.odds.get(j)));
                    }
                    out.append("\n");
                } else {
                    out.append("\nآربیتراژ از این ضرایب به‌تنهایی تأیید نمی‌شود.\n");
                }
            }
            out.append("\nتوجه: گروه‌بندی خودکار صفحه ممکن است بازارهای نامرتبط را کنار هم قرار دهد. قبل از هر تصمیم، نام بازار و کامل‌بودن تمام نتایج را دستی تأیید کنید. هیچ سودی تضمین‌شده نیست.");
        }
        analysis.setText(out.toString());
        status.setText("بررسی انجام شد؛ " + markets.size() + " گروه قابل‌بررسی شناسایی شد.");
    }

    private double parseOdd(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (Exception e) { return -1; }
    }

    private String shortText(String s, int max) {
        if (s == null || s.isEmpty()) return "بازار بدون عنوان";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static class Market {
        final String text;
        final ArrayList<Double> odds;
        double sum;
        Market(String text, ArrayList<Double> odds) { this.text = text; this.odds = odds; }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("OddsBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
