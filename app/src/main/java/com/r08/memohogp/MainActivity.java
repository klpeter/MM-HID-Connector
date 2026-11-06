package com.r08.memohogp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_BT = 10;
    private HidPeripheral peripheral;
    private TextView log;
    @Override public void onCreate(Bundle state) { super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,24);
        TextView title = new TextView(this); title.setText("R08 → MemoMind · HOGP proof of concept"); title.setTextSize(21); root.addView(title);
        addButton(root,"1. Start HOGP advertising", v -> start());
        addButton(root,"2. Test: single click (Play/Pause)", v -> send(3));
        addButton(root,"3. Test: swipe up (Volume Up)", v -> send(8));
        addButton(root,"4. Test: swipe down (Volume Down)", v -> send(9));
        addButton(root,"5. Stop", v -> { if(peripheral != null) peripheral.stop(); });
        log = new TextView(this); log.setTextIsSelectable(true); log.setPadding(0,20,0,0);
        ScrollView scroll = new ScrollView(this); scroll.addView(log); root.addView(scroll); setContentView(root);
        peripheral = new HidPeripheral(this, this::writeLog);
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        writeLog("BLE advertiser: " + (adapter!=null && adapter.isMultipleAdvertisementSupported()));
        writeLog("BLE peripheral supported: " + (adapter!=null && adapter.getBluetoothLeAdvertiser()!=null));
    }
    private void addButton(LinearLayout root, String label, View.OnClickListener l) { Button b=new Button(this); b.setText(label); b.setAllCaps(false);root.addView(b);b.setOnClickListener(l); }
    private void writeLog(String s) { runOnUiThread(() -> log.append(s+"\n")); }
    private boolean granted() { return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED; }
    private void start() { if(!granted()) { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},REQUEST_BT); return; } peripheral.start(); }
    @Override public void onRequestPermissionsResult(int request, String[] p, int[] results) { super.onRequestPermissionsResult(request,p,results); if(request==REQUEST_BT && granted()) peripheral.start(); else writeLog("Bluetooth permissions not granted"); }
    private void send(int bit) { if(peripheral == null) return; peripheral.pressAndRelease(bit); }
    @Override protected void onDestroy() { if(peripheral!=null) peripheral.stop();super.onDestroy(); }
}
