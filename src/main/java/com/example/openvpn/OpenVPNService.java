package com.example.openvpn;

import android.content.Intent;
import android.os.ParcelFileDescriptor;

import com.example.openvpn.core.OpenVPNService;
import com.example.openvpn.models.Profile;

public class OpenVPNService extends com.example.openvpn.core.OpenVPNService {
    private ParcelFileDescriptor vpnInterface;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 确保服务被杀死后能重启
    }
    
    public void startVPN(Profile profile) {
        // 调用父类方法启动VPN
        super.startVPN(profile);
    }
    
    public void stopVPN(boolean clearNotification) {
        // 调用父类方法停止VPN
        super.stopVPN(clearNotification);
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    @Override
    public void onRevoke() {
        super.onRevoke();
        stopVPN(true);
    }
}
