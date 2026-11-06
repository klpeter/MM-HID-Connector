package com.r08.memohogp;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;

@SuppressLint("MissingPermission")
public class HidPeripheral {
    private static UUID uuid(int n) {
        return UUID.fromString(String.format(Locale.ROOT,"0000%04x-0000-1000-8000-00805f9b34fb",n));
    }
    private static final UUID HID=uuid(0x1812), CCCD=uuid(0x2902);
    private final Context context;
    private final Consumer<String> logger;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic report;
    private BluetoothDevice connected;
    private boolean notifyEnabled=false;
    private byte[] current=new byte[3];
    private final Queue<BluetoothGattService> pending=new ArrayDeque<>();

    private static final int[] USAGES={0xB5,0xB6,0xB7,0xCD,0xE2,0xB8,0xB9,0xBA,0xE9,0xEA,
        0x152,0x153,0x154,0x155,0x183,0xE0,0xE1,0xE3,0xE4,0xE5,0xE6,0xE7,0xE8,0xE10};
    private static byte[] map() {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        int[] h={0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x04,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x18};
        for(int n:h)b.write(n);
        for(int n:USAGES){
            if(n<256){b.write(0x09);b.write(n);}
            else {b.write(0x0A);b.write(n&255);b.write(n>>8);}
        }
        b.write(0x81);b.write(0x02);b.write(0xC0);
        return b.toByteArray();
    }
    public HidPeripheral(Context c,Consumer<String> callback){context=c;logger=callback;}
    private void log(String s){logger.accept(s);}
    private static BluetoothGattCharacteristic characteristic(int id,int properties,int permissions,byte[] data){
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(uuid(id),properties,permissions);
        c.setValue(data);
        return c;
    }
    private static void descriptor(BluetoothGattCharacteristic c,int id,int permission,byte[] data){
        BluetoothGattDescriptor d=new BluetoothGattDescriptor(uuid(id),permission);
        d.setValue(data);c.addDescriptor(d);
    }
    private BluetoothGattService hidService(){
        BluetoothGattService s=new BluetoothGattService(HID,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(characteristic(0x2A4A,2,1,new byte[]{0x11,0x01,0,3}));
        s.addCharacteristic(characteristic(0x2A4B,2,1,map()));
        s.addCharacteristic(characteristic(0x2A4C,4,16,new byte[]{0}));
        s.addCharacteristic(characteristic(0x2A4E,2|4,1|16,new byte[]{1}));
        report=characteristic(0x2A4D,2|16,BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,new byte[3]);
        descriptor(report,0x2908,BluetoothGattDescriptor.PERMISSION_READ,new byte[]{4,1});
        descriptor(report,0x2902,BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,new byte[]{0,0});
        s.addCharacteristic(report);
        return s;
    }
    private BluetoothGattService deviceInfo(){
        BluetoothGattService s=new BluetoothGattService(uuid(0x180A),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(characteristic(0x2A50,2,1,new byte[]{1,0x34,0x12,1,0,1,0}));
        s.addCharacteristic(characteristic(0x2A29,2,1,"MM HID Test".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return s;
    }
    private BluetoothGattService battery(){
        BluetoothGattService s=new BluetoothGattService(uuid(0x180F),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(characteristic(0x2A19,2,1,new byte[]{100}));
        return s;
    }
    private final BluetoothGattServerCallback serverCallback=new BluetoothGattServerCallback(){
        @Override public void onConnectionStateChange(BluetoothDevice device,int status,int state){
            if(state==BluetoothProfile.STATE_CONNECTED){connected=device;log("Host connected: "+device.getAddress());}
            else if(state==BluetoothProfile.STATE_DISCONNECTED){
                if(device.equals(connected)){connected=null;notifyEnabled=false;}
                log("Host disconnected");
            }
        }
        @Override public void onServiceAdded(int status,BluetoothGattService service){
            log("Service "+service.getUuid()+" status "+status);
            if(status==BluetoothGatt.GATT_SUCCESS)addNext();
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device,int id,int offset,BluetoothGattCharacteristic c){
            if(server==null)return;
            byte[] value=c==report?current:c.getValue();
            if(value==null)value=new byte[0];
            if(offset>value.length){server.sendResponse(device,id,BluetoothGatt.GATT_INVALID_OFFSET,offset,null);return;}
            server.sendResponse(device,id,BluetoothGatt.GATT_SUCCESS,offset,Arrays.copyOfRange(value,offset,value.length));
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice device,int id,int offset,BluetoothGattDescriptor d){
            if(server==null)return;
            byte[] value=CCCD.equals(d.getUuid())?(notifyEnabled?new byte[]{1,0}:new byte[]{0,0}):d.getValue();
            if(value==null)value=new byte[0];
            if(offset>value.length){server.sendResponse(device,id,BluetoothGatt.GATT_INVALID_OFFSET,offset,null);return;}
            server.sendResponse(device,id,BluetoothGatt.GATT_SUCCESS,offset,Arrays.copyOfRange(value,offset,value.length));
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice device,int id,BluetoothGattDescriptor d,boolean prepared,boolean response,int offset,byte[] value){
            if(server==null)return;
            boolean ok=CCCD.equals(d.getUuid())&&!prepared&&offset==0&&value!=null&&value.length==2&&
                (value[0]==0||value[0]==1)&&value[1]==0;
            if(ok){notifyEnabled=value[0]==1;log("HID notifications "+notifyEnabled);}
            if(response)server.sendResponse(device,id,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_FAILURE,offset,null);
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device,int id,BluetoothGattCharacteristic c,boolean prepared,boolean response,int offset,byte[] value){
            if(server==null)return;
            boolean ok=!prepared&&offset==0&&value!=null;
            if(ok)c.setValue(value);
            if(response)server.sendResponse(device,id,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_FAILURE,offset,null);
        }
        @Override public void onNotificationSent(BluetoothDevice device,int status){log("Notification result "+status);}
    };
    private final AdvertiseCallback advertiseCallback=new AdvertiseCallback(){
        @Override public void onStartSuccess(AdvertiseSettings settings){log("Advertising HID service 1812");}
        @Override public void onStartFailure(int code){log("Advertising failed: "+code);}
    };
    private void addNext(){
        BluetoothGattService s=pending.poll();
        if(s!=null&&server!=null&&!server.addService(s))log("addService failed: "+s.getUuid());
    }
    public void start(){
        stop();
        manager=context.getSystemService(BluetoothManager.class);
        adapter=manager==null?null:manager.getAdapter();
        if(adapter==null||!adapter.isEnabled()){log("Enable Bluetooth first");return;}
        advertiser=adapter.getBluetoothLeAdvertiser();
        if(advertiser==null){log("LE advertiser unavailable");return;}
        server=manager.openGattServer(context,serverCallback);
        if(server==null){log("GATT server unavailable");return;}
        pending.add(hidService());pending.add(deviceInfo());pending.add(battery());addNext();
        AdvertiseSettings settings=new AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build();
        AdvertiseData data=new AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(new ParcelUuid(HID)).build();
        advertiser.startAdvertising(settings,data,advertiseCallback);
    }
    public void pressAndRelease(int bit){
        if(server==null||connected==null||!notifyEnabled){log("HID host not connected/subscribed");return;}
        if(bit<0||bit>=24)return;
        byte[] pressed=new byte[3];
        pressed[bit/8]=(byte)(1<<(bit%8));
        send(pressed);
        handler.postDelayed(()->send(new byte[3]),100);
    }
    private void send(byte[] value){
        if(server==null||connected==null||!notifyEnabled)return;
        current=value.clone();
        int status=server.notifyCharacteristicChanged(connected,report,false,current);
        log("HID report "+Arrays.toString(value)+" status "+status);
    }
    public void stop(){
        if(advertiser!=null){
            try{advertiser.stopAdvertising(advertiseCallback);}catch(Exception ignored){}
            advertiser=null;
        }
        if(server!=null){server.close();server=null;}
        pending.clear();connected=null;notifyEnabled=false;
        log("Stopped");
    }
}
