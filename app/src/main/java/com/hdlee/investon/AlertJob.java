package com.hdlee.investon;
import android.app.job.*;
import java.util.concurrent.*;
public class AlertJob extends JobService {
    ExecutorService executor;
    @Override public boolean onStartJob(JobParameters p) {
        executor=Executors.newSingleThreadExecutor(); executor.submit(()->{ Alerts.refresh(this); jobFinished(p,false); executor.shutdown(); }); return true;
    }
    @Override public boolean onStopJob(JobParameters p) { if(executor!=null) executor.shutdownNow(); return true; }
}
