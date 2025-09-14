package com.example.openvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.openvpn.core.OpenVPNService;
import com.example.openvpn.core.VpnStatus;
import com.example.openvpn.models.Profile;
import com.example.openvpn.models.ProfileManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Timer;
import java.util.TimerTask;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.plugin.UniPlugin;

public class OpenVPNPlugin extends UniPlugin implements VpnStatus.StateListener {
    private static final String TAG = "OpenVPNPlugin";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "openvpn_channel";
    private static final String ACTION_STOP_VPN = "com.example.openvpn.STOP_VPN";
    
    private OpenVPNService vpnService;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private UniJSCallback statusCallback;
    private String currentStatus = "DISCONNECTED";
    private Timer networkMonitorTimer;
    private PowerManager.WakeLock wakeLock;
    private NetworkStatsMonitor networkStatsMonitor;
    
    // 接收停止VPN的广播
    private BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP_VPN.equals(intent.getAction())) {
                disconnect();
            }
        }
    };
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        VpnStatus.removeStateListener(this);
        if (vpnService != null) {
            vpnService.stopVPN(false);
        }
        stopNetworkMonitoring();
        releaseWakeLock();
        mContext.unregisterReceiver(stopReceiver);
    }
    
    @UniJSMethod(uiThread = false)
    public void init(UniJSCallback callback) {
        try {
            // 注册广播接收器
            IntentFilter filter = new IntentFilter(ACTION_STOP_VPN);
            mContext.registerReceiver(stopReceiver, filter);
            
            // 初始化网络统计监控
            networkStatsMonitor = new NetworkStatsMonitor(mContext);
            
            // 获取唤醒锁，确保锁屏时运行
            PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, 
                                               "OpenVPN:WakeLock");
            
            // 请求VPN权限
            Intent intent = VpnService.prepare(mContext);
            if (intent != null) {
                // 需要用户授权
                callback.invoke(new JSONObject() {{
                    put("success", false);
                    put("needPermission", true);
                    put("intent", intent);
                }});
            } else {
                // 已授权
                callback.invoke(new JSONObject() {{
                    put("success", true);
                    put("needPermission", false);
                }});
            }
        } catch (Exception e) {
            Log.e(TAG, "初始化失败", e);
            invokeErrorCallback(callback, "初始化失败: " + e.getMessage());
        }
    }
    
    @UniJSMethod(uiThread = false)
    public void connect(String username, String password, String config, UniJSCallback callback) {
        executor.execute(() -> {
            try {
                // 申请唤醒锁
                acquireWakeLock();
                
                // 创建VPN配置文件
                Profile profile = Profile.parseVpnProfile(config.getBytes());
                if (profile == null) {
                    invokeErrorCallback(callback, "配置文件解析失败");
                    return;
                }
                
                // 设置认证信息
                profile.mUsername = username;
                profile.mPassword = password;
                
                // 保存配置
                ProfileManager.getInstance(mContext).addProfile(profile);
                ProfileManager.getInstance(mContext).saveProfile(mContext, profile);
                
                // 初始化并启动VPN服务
                if (vpnService == null) {
                    vpnService = new OpenVPNService();
                    VpnStatus.addStateListener(this);
                }
                
                // 启动VPN
                vpnService.startVPN(profile);
                currentStatus = "CONNECTING";
                updateStatusCallback();
                
                // 开始网络监控
                startNetworkMonitoring();
                
                // 显示前台通知，确保后台运行
                showForegroundNotification("VPN连接中", "正在建立VPN连接...");
                
                callback.invoke(new JSONObject() {{
                    put("success", true);
                }});
            } catch (Exception e) {
                Log.e(TAG, "VPN连接失败", e);
                currentStatus = "ERROR";
                updateStatusCallback();
                invokeErrorCallback(callback, "连接失败: " + e.getMessage());
                releaseWakeLock();
            }
        });
    }
    
    @UniJSMethod(uiThread = false)
    public void disconnect(UniJSCallback callback) {
        try {
            if (vpnService != null) {
                vpnService.stopVPN(false);
            }
            currentStatus = "DISCONNECTED";
            updateStatusCallback();
            stopForegroundNotification();
            stopNetworkMonitoring();
            releaseWakeLock();
            
            if (callback != null) {
                callback.invoke(new JSONObject() {{
                    put("success", true);
                }});
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN断开失败", e);
            if (callback != null) {
                invokeErrorCallback(callback, "断开失败: " + e.getMessage());
            }
        }
    }
    
    @UniJSMethod(uiThread = false)
    public void getStatus(UniJSCallback callback) {
        try {
            callback.invoke(new JSONObject() {{
                put("status", currentStatus);
            }});
        } catch (Exception e) {
            invokeErrorCallback(callback, "获取状态失败");
        }
    }
    
    @UniJSMethod(uiThread = false)
    public void setStatusCallback(UniJSCallback callback) {
        this.statusCallback = callback;
        // 立即返回当前状态
        updateStatusCallback();
    }
    
    @UniJSMethod(uiThread = false)
    public void getNetworkStats(UniJSCallback callback) {
        try {
            if (networkStatsMonitor == null) {
                networkStatsMonitor = new NetworkStatsMonitor(mContext);
            }
            
            NetworkStats stats = networkStatsMonitor.getVPNNetworkStats();
            callback.invoke(new JSONObject() {{
                put("bytesSent", stats.bytesSent);
                put("bytesReceived", stats.bytesReceived);
                put("timeConnected", stats.timeConnected);
            }});
        } catch (Exception e) {
            Log.e(TAG, "获取网络统计失败", e);
            invokeErrorCallback(callback, "获取网络统计失败: " + e.getMessage());
        }
    }
    
    // 状态更新回调
    @Override
    public void updateState(String state, String logmessage, int localizedResId, Throwable throwable) {
        Log.d(TAG, "VPN状态更新: " + state);
        currentStatus = state;
        
        // 更新通知
        if (state.equals("CONNECTED")) {
            showForegroundNotification("VPN已连接", "OpenVPN连接已建立");
            networkStatsMonitor.startTracking();
        } else if (state.equals("DISCONNECTED")) {
            stopForegroundNotification();
            stopNetworkMonitoring();
            releaseWakeLock();
        } else if (state.equals("ERROR")) {
            showForegroundNotification("VPN错误", logmessage);
        }
        
        updateStatusCallback();
    }
    
    @Override
    public void setConnectedVPN(String uuid) {
        // 处理VPN连接UUID
    }
    
    // 显示前台通知(用于后台运行)
    private void showForegroundNotification(String title, String message) {
        createNotificationChannel();
        
        // 停止VPN的意图
        Intent stopIntent = new Intent(ACTION_STOP_VPN);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
            mContext, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_vpn)
            .addAction(R.drawable.ic_stop, "断开连接", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build();
            
        // 启动前台服务，确保后台运行
        if (vpnService != null) {
            vpnService.startForeground(NOTIFICATION_ID, notification);
        }
    }
    
    // 停止前台通知
    private void stopForegroundNotification() {
        if (vpnService != null) {
            vpnService.stopForeground(true);
        }
    }
    
    // 创建通知渠道
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "VPN通知";
            String description = "OpenVPN连接状态通知";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    // 开始网络监控
    private void startNetworkMonitoring() {
        stopNetworkMonitoring(); // 先停止已有的监控
        
        networkMonitorTimer = new Timer();
        networkMonitorTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (currentStatus.equals("CONNECTED") && networkStatsMonitor != null) {
                    try {
                        NetworkStats stats = networkStatsMonitor.getVPNNetworkStats();
                        if (statusCallback != null) {
                            mainHandler.post(() -> {
                                try {
                                    statusCallback.invoke(new JSONObject() {{
                                        put("status", currentStatus);
                                        put("networkStats", new JSONObject() {{
                                            put("bytesSent", stats.bytesSent);
                                            put("bytesReceived", stats.bytesReceived);
                                            put("timeConnected", stats.timeConnected);
                                        }});
                                    }});
                                } catch (Exception e) {
                                    Log.e(TAG, "网络统计回调失败", e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "网络监控失败", e);
                    }
                }
            }
        }, 0, 5000); // 每5秒更新一次
    }
    
    // 停止网络监控
    private void stopNetworkMonitoring() {
        if (networkMonitorTimer != null) {
            networkMonitorTimer.cancel();
            networkMonitorTimer = null;
        }
        if (networkStatsMonitor != null) {
            networkStatsMonitor.stopTracking();
        }
    }
    
    // 获取唤醒锁，确保锁屏时运行
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L /* 10分钟 */);
        }
    }
    
    // 释放唤醒锁
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
    
    // 更新状态回调
    private void updateStatusCallback() {
        if (statusCallback != null) {
            mainHandler.post(() -> {
                try {
                    statusCallback.invoke(new JSONObject() {{
                        put("status", currentStatus);
                    }});
                } catch (Exception e) {
                    Log.e(TAG, "状态回调失败", e);
                }
            });
        }
    }
    
    // 调用错误回调
    private void invokeErrorCallback(UniJSCallback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.invoke(new JSONObject() {{
                        put("success", false);
                        put("message", message);
                    }});
                } catch (Exception e) {
                    Log.e(TAG, "错误回调失败", e);
                }
            });
        }
    }
}
