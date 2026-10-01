package com.hdlee.investon;
import android.app.*;
import android.app.job.*;
import android.content.*;
import org.json.*;
import java.util.*;

public final class Alerts {
    static void channels(Context c) {
        NotificationManager n=c.getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel("prices","목표가격 도달",NotificationManager.IMPORTANCE_HIGH));
        n.createNotificationChannel(new NotificationChannel("monitor","집중 시세 확인",NotificationManager.IMPORTANCE_LOW));
    }
    static void schedule(Context c) {
        try {
        JobScheduler s=c.getSystemService(JobScheduler.class);
        s.schedule(new JobInfo.Builder(73,new ComponentName(c,AlertJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15*60*1000L).setPersisted(true).build());
        } catch (RuntimeException e) {
            // Optional background monitoring must never prevent opening the app.
            android.util.Log.e("InvestOn", "Cannot schedule background price checks", e);
        }
    }
    static void refresh(Context c) {
        try {
            JSONObject state=new JSONObject(c.getSharedPreferences("investon",0).getString("state","{}"));
            JSONArray rules=state.optJSONArray("rules"); if(rules==null) return;
            Set<String> symbols=new HashSet<>();
            for(int i=0;i<rules.length();i++) { JSONObject r=rules.getJSONObject(i); if(r.optBoolean("active",true)&&!c.getSharedPreferences("fired",0).getBoolean(r.optString("id"),false)) symbols.add(r.getString("symbol")); }
            MarketClient m=new MarketClient(c); for(String symbol:symbols) { try { m.quote(symbol); } catch(Exception ignored) {} }
            check(c);
        } catch(Exception ignored) {}
    }
    static synchronized void check(Context c) {
        try {
            NotificationManager manager=c.getSystemService(NotificationManager.class); if(!manager.areNotificationsEnabled()) return;
            JSONObject state=new JSONObject(c.getSharedPreferences("investon",0).getString("state","{}"));
            JSONArray rules=state.optJSONArray("rules"); if(rules==null) return;
            for(int i=0;i<rules.length();i++) {
                JSONObject r=rules.getJSONObject(i); String id=r.optString("id"),symbol=r.optString("symbol");
                if(id.isEmpty()||!r.optBoolean("active",true)||c.getSharedPreferences("fired",0).getBoolean(id,false)) continue;
                String raw=c.getSharedPreferences("quotes",0).getString(symbol,null); if(raw==null) continue;
                JSONObject q=new JSONObject(raw); long age=System.currentTimeMillis()/1000-q.optLong("time",0);
                // Only recently fetched, known trade-time quotes; delayed feeds may alert up to 30 minutes late.
                if(age< -60||age>1800||System.currentTimeMillis()-q.optLong("fetchedAt",0)>300_000) continue;
                double target=r.optDouble("target",0),price=q.optDouble("price",Double.NaN);
                if(target<=0||!Double.isFinite(price)) continue;
                boolean hit="below".equals(r.optString("direction"))?price<=target:price>=target;
                if(hit) {
                    PendingIntent pi=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                    String title=r.optString("name",symbol)+" 목표가 도달";
                    String body=String.format(Locale.KOREA,"현재가 %,.2f · 목표가 %,.2f",price,target);
                    manager.notify(id.hashCode(),new Notification.Builder(c,"prices").setSmallIcon(com.hdlee.investon.R.drawable.ic_notification)
                        .setContentTitle(title).setContentText(body).setContentIntent(pi).setAutoCancel(true).build());
                    c.getSharedPreferences("fired",0).edit().putBoolean(id,true).commit();
                }
            }
        } catch(Exception ignored) {}
    }
}
