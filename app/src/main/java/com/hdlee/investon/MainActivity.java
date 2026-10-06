package com.hdlee.investon;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    WebView web; final ExecutorService pool=Executors.newFixedThreadPool(4);
    String exportText=""; boolean visible;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        android.widget.FrameLayout root=new android.widget.FrameLayout(this);
        web=new WebView(this);
        root.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        web.setBackgroundColor(0xfff5f7f8);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime()); v.setPadding(i.left,i.top,i.right,i.bottom); }
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(),"Native");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v,String u) { v.evaluateJavascript("window.appVisibility&&appVisibility("+visible+")",null); }
        });
        web.loadUrl("file:///android_asset/index.html");
        Alerts.channels(this); Alerts.schedule(this);
    }
    @Override public void onResume() { super.onResume(); visible=true; if(web!=null) web.evaluateJavascript("window.appVisibility&&appVisibility(true)",null); }
    @Override public void onPause() { visible=false; web.evaluateJavascript("window.appVisibility&&appVisibility(false)",null); super.onPause(); }
    @Override public void onDestroy() { pool.shutdownNow(); web.removeJavascriptInterface("Native"); web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() { web.evaluateJavascript("window.back&&back()",null); }
    void answer(String id,Object result,String error) {
        try { JSONObject response=new JSONObject(); if(error!=null) response.put("error",error); else response.put("data",result);
            runOnUiThread(()->{ if(!isDestroyed()) web.evaluateJavascript("window.receive("+JSONObject.quote(id)+","+response.toString()+")",null); });
        } catch(Exception ignored) {}
    }
    class Bridge {
        @JavascriptInterface public String state() { return getSharedPreferences("investon",0).getString("state","{}"); }
        @JavascriptInterface public String cachedQuotes() {
            JSONObject out=new JSONObject(); getSharedPreferences("quotes",0).getAll().forEach((k,v)->{ try { out.put(k,new JSONObject((String)v)); } catch(Exception ignored) {} }); return out.toString();
        }
        @JavascriptInterface public void save(String value) {
            try { if(value.length()>1_000_000) return; JSONObject o=new JSONObject(value);
                getSharedPreferences("investon",0).edit().putString("state",o.toString()).apply(); Alerts.schedule(MainActivity.this);
            } catch(Exception ignored) {}
        }
        @JavascriptInterface public void request(String id,String action,String payload) {
            if(id.length()>100||payload.length()>50000) return;
            pool.submit(()->{
                try {
                    MarketClient client=new MarketClient(MainActivity.this); JSONObject p=new JSONObject(payload); Object data;
                    switch(action) {
                        case "quote": data=client.quote(p.getString("symbol")); Alerts.check(MainActivity.this); break;
                        case "history": data=client.history(p.getString("symbol")); break;
                        case "search": data=client.search(p.getString("query")); break;
                        case "news": data=client.news(); break;
                        case "blog": data=client.blog(); break;
                        default: throw new IllegalArgumentException("지원하지 않는 요청");
                    }
                    answer(id,data,null);
                } catch(Exception e) { answer(id,null,e.getMessage()==null?"연결을 확인해 주세요":e.getMessage()); }
            });
        }
        @JavascriptInterface public String version() { try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; } catch(Exception e) { return ""; } }
        @JavascriptInterface public void open(String url) { runOnUiThread(()->{ try { Uri u=Uri.parse(url); if("https".equals(u.getScheme())&&u.getHost()!=null) startActivity(new Intent(Intent.ACTION_VIEW,u)); } catch(Exception ignored) {} }); }
        @JavascriptInterface public void permission() { runOnUiThread(()->{ if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},7); }); }
        @JavascriptInterface public boolean notificationsEnabled() { return getSystemService(NotificationManager.class).areNotificationsEnabled(); }
        @JavascriptInterface public boolean monitoring() { return MonitorService.running; }
        @JavascriptInterface public void monitor(boolean enabled) { runOnUiThread(()->{ try {
            if(enabled) startForegroundService(new Intent(MainActivity.this,MonitorService.class)); else stopService(new Intent(MainActivity.this,MonitorService.class));
        } catch(Exception e) { web.evaluateJavascript("toast('집중 알림을 시작할 수 없습니다. 앱을 다시 열어 주세요.')",null); } }); }
        @JavascriptInterface public void export(String value) { if(value.length()>1_000_000) return; exportText=value;
            runOnUiThread(()->{ Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(BackupWorkbook.MIME).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"InvestOn-backup.xlsx"); startActivityForResult(i,10); });
        }
        @JavascriptInterface public void importFile() { runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(BackupWorkbook.MIME).addCategory(Intent.CATEGORY_OPENABLE),11)); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK||data==null||data.getData()==null) return;
        Uri uri=data.getData();
        pool.submit(()->{ try {
            if(request==10) { try(OutputStream out=getContentResolver().openOutputStream(uri)) { if(out==null) throw new IOException(); BackupWorkbook.write(new JSONObject(exportText),out); } runOnUiThread(()->web.evaluateJavascript("toast('백업 파일을 저장했습니다')",null)); }
            if(request==11) { try(InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null) throw new IOException(); String text=BackupWorkbook.read(in).toString();
                runOnUiThread(()->web.evaluateJavascript("window.importBackup("+JSONObject.quote(text)+")",null));
            } }
        } catch(Exception e) { runOnUiThread(()->web.evaluateJavascript("toast("+JSONObject.quote("엑셀 처리 실패: "+(e.getMessage()==null?"파일을 확인해 주세요":e.getMessage()))+")",null)); } });
    }
}
