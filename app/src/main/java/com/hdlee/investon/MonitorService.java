package com.hdlee.investon;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;
public class MonitorService extends Service {
    public static volatile boolean running=false;
    ScheduledExecutorService timer;
    @Override public void onCreate() { super.onCreate(); Alerts.channels(this);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MonitorService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(9,new Notification.Builder(this,"monitor").setSmallIcon(R.drawable.ic_notification).setContentTitle("InvestOn · 집중 알림")
            .setContentText("목표가격 30초 확인 · Android 시간 제한 적용").setContentIntent(open).addAction(new Notification.Action.Builder(null,"중지",stop).build()).build());
        running=true; timer=Executors.newSingleThreadScheduledExecutor(); timer.scheduleWithFixedDelay(()->Alerts.refresh(this),0,30,TimeUnit.SECONDS);
        // Exit safely before Android 15+ 6h time budget. Boot only schedules periodic jobs.
        new Handler(Looper.getMainLooper()).postDelayed(()->{ if(running) stopSelf(); },5*60*60*1000L);
    }
    @Override public int onStartCommand(Intent i,int flags,int id) { if(i!=null&&"STOP".equals(i.getAction())) stopSelf(); return START_NOT_STICKY; }
    @Override public void onTimeout(int id,int type) { stopSelf(); }
    @Override public void onDestroy() { running=false; if(timer!=null) timer.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
