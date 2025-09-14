package com.example.openvpn;

import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.util.Log;

public class NetworkStatsMonitor {
    private static final String TAG = "NetworkStatsMonitor";
    private Context context;
    private long startRxBytes;
    private long startTxBytes;
    private long startTime;
    private boolean isTracking = false;
    
    public NetworkStatsMonitor(Context context) {
        this.context = context;
    }
    
    // 开始跟踪网络流量
    public void startTracking() {
        // 获取VPN启动时的初始流量值
        startRxBytes = TrafficStats.getTotalRxBytes();
        startTxBytes = TrafficStats.getTotalTxBytes();
        startTime = SystemClock.elapsedRealtime();
        isTracking = true;
        Log.d(TAG, "开始跟踪网络流量");
    }
    
    // 停止跟踪
    public void stopTracking() {
        isTracking = false;
    }
    
    // 获取VPN网络统计
    public NetworkStats getVPNNetworkStats() {
        if (!isTracking) {
            startTracking();
        }
        
        long currentRx = TrafficStats.getTotalRxBytes();
        long currentTx = TrafficStats.getTotalTxBytes();
        long currentTime = SystemClock.elapsedRealtime();
        
        // 计算VPN使用的流量（总流量 - 初始流量）
        long bytesReceived = currentRx - startRxBytes;
        long bytesSent = currentTx - startTxBytes;
        long timeConnected = (currentTime - startTime) / 1000; // 转换为秒
        
        return new NetworkStats(bytesSent, bytesReceived, timeConnected);
    }
    
    // 网络统计数据类
    public static class NetworkStats {
        public long bytesSent;
        public long bytesReceived;
        public long timeConnected;
        
        public NetworkStats(long bytesSent, long bytesReceived, long timeConnected) {
            this.bytesSent = bytesSent;
            this.bytesReceived = bytesReceived;
            this.timeConnected = timeConnected;
        }
    }
}
