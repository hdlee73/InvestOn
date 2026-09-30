package com.hdlee.investon;
import android.content.*;
public class BootReceiver extends BroadcastReceiver { @Override public void onReceive(Context c,Intent i) { Alerts.schedule(c); } }
