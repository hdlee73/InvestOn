package com.hdlee.investon;
import android.app.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Checks the latest GitHub release and notifies once per new version. Network failures are ignored. */
public final class UpdateChecker {
    static final String API="https://api.github.com/repos/hdlee73/InvestOn/releases/latest";
    static final String PAGE="https://github.com/hdlee73/InvestOn/releases";
    static final long INTERVAL=6*60*60*1000L;
    static final int NOTIFICATION_ID=7301;

    /** Blocking; call from a background thread. Returns true when a newer version is known. */
    static synchronized boolean check(Context c,boolean force) {
        try {
            SharedPreferences p=c.getSharedPreferences("update",0);
            long now=System.currentTimeMillis();
            if(!force&&now-p.getLong("checkedAt",0)<INTERVAL) return hasUpdate(c);
            String tag=fetchTag(); if(tag==null) return hasUpdate(c);
            p.edit().putString("latest",tag).putLong("checkedAt",now).commit();
            if(!newer(tag,current(c))) return false;
            notifyOnce(c,tag);
            return true;
        } catch(Exception e) { return false; }
    }
    static String fetchTag() {
        HttpURLConnection h=null;
        try {
            h=(HttpURLConnection)new URL(API).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(10000);
            h.setRequestProperty("Accept","application/vnd.github+json");
            h.setRequestProperty("User-Agent","InvestOn");
            if(h.getResponseCode()!=200) return null;
            try(InputStream in=h.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[4096]; int n;
                while((n=in.read(b))!=-1) { out.write(b,0,n); if(out.size()>2_000_000) return null; }
                String tag=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).optString("tag_name","");
                return tag.matches("v?\\d+(\\.\\d+)*")?tag:null;
            }
        } catch(Exception e) { return null; } finally { if(h!=null) h.disconnect(); }
    }
    static String current(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; } catch(Exception e) { return ""; }
    }
    static String clean(String v) { return v==null?"":(v.startsWith("v")||v.startsWith("V")?v.substring(1):v); }
    static boolean newer(String latest,String current) {
        String[] a=clean(latest).split("\\."),b=clean(current).split("\\.");
        if(clean(current).isEmpty()) return false;
        for(int i=0;i<Math.max(a.length,b.length);i++) {
            int x=num(a,i),y=num(b,i); if(x!=y) return x>y;
        }
        return false;
    }
    static int num(String[] p,int i) { try { return i<p.length?Integer.parseInt(p[i].replaceAll("\\D.*","")):0; } catch(Exception e) { return 0; } }
    static boolean hasUpdate(Context c) {
        String t=c.getSharedPreferences("update",0).getString("latest",null); return t!=null&&newer(t,current(c));
    }
    /** JSON for the web UI: {"available":bool,"version":"1.0.11"} */
    static String info(Context c) {
        JSONObject o=new JSONObject();
        try { boolean has=hasUpdate(c); o.put("available",has); o.put("version",has?clean(c.getSharedPreferences("update",0).getString("latest","")):""); } catch(Exception ignored) {}
        return o.toString();
    }
    static void notifyOnce(Context c,String tag) {
        try {
            SharedPreferences p=c.getSharedPreferences("update",0);
            if(tag.equals(p.getString("notified",null))) return;
            NotificationManager m=c.getSystemService(NotificationManager.class);
            if(!m.areNotificationsEnabled()) return; // retried on the next check until notifications are allowed
            Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(PAGE));
            PendingIntent pi=PendingIntent.getActivity(c,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            m.notify(NOTIFICATION_ID,new Notification.Builder(c,"update").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("InvestOn 새 버전 "+tag).setContentText("눌러서 릴리스 페이지에서 업데이트하세요")
                .setContentIntent(pi).setAutoCancel(true).build());
            p.edit().putString("notified",tag).commit();
        } catch(Exception ignored) {}
    }
}
