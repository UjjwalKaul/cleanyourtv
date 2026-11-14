package io.github.cleanyourtv.airemote;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import io.github.cleanyourtv.airemote.qr.MicroQr;
import io.github.cleanyourtv.airemote.server.NativeServer;
import io.github.cleanyourtv.airemote.server.SessionAuth;
import io.github.cleanyourtv.airemote.service.RemoteAccessibilityService;
import android.provider.Settings;

public class MainActivity extends Activity implements NativeServer.StatusListener {

    private static final int PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    public static volatile NativeServer sServerInstance;
    private static volatile SessionAuth sSessionAuth;

    private ImageView ivQrCode;
    private TextView tvStatusBadge;
    private TextView tvSubtitle;
    private TextView tvUrl;
    private TextView tvToken;
    private Button btnRevoke;
    private Button btnClose;

    // Connected device card & sever button
    private LinearLayout layoutConnectedDevice;
    private TextView tvDeviceName;
    private TextView tvDeviceIp;
    private Button btnSever;
    private volatile String currentConnectedClientId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ivQrCode = findViewById(R.id.ivQrCode);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvUrl = findViewById(R.id.tvUrl);
        tvToken = findViewById(R.id.tvToken);
        btnRevoke = findViewById(R.id.btnRevoke);
        btnClose = findViewById(R.id.btnClose);

        layoutConnectedDevice = findViewById(R.id.layoutConnectedDevice);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvDeviceIp = findViewById(R.id.tvDeviceIp);
        btnSever = findViewById(R.id.btnSever);

        // Thread-safe singleton server initialization across activity lifecycle
        synchronized (MainActivity.class) {
            if (sSessionAuth == null) {
                sSessionAuth = new SessionAuth(getApplicationContext());
            }
            if (sServerInstance == null) {
                String ip = getIpAddress();
                sServerInstance = new NativeServer(this, PORT, HTTPS_PORT, ip, sSessionAuth, this);
                try {
                    sServerInstance.start();
                } catch (Exception e) {
                    tvStatusBadge.setText("Error starting server: " + e.getMessage());
                }
            } else {
                sServerInstance.setStatusListener(this);
            }
        }

        btnRevoke.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sSessionAuth.regenerateToken();
                if (sServerInstance != null) {
                    sServerInstance.disconnectAllClients();
                }
                refreshPairingUi();
            }
        });

        btnSever.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sServerInstance != null) {
                    sServerInstance.disconnectAllClients();
                }
                // Regenerate pairing code so disconnected clients cannot re-pair automatically
                if (sSessionAuth != null) {
                    sSessionAuth.regenerateToken();
                }
                refreshPairingUi();
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ensureAccessibilityService();
        refreshPairingUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureAccessibilityService();
    }

    private void ensureAccessibilityService() {
        try {
            String myService = getPackageName() + "/" + RemoteAccessibilityService.class.getName();
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(myService)) {
                String toSet = (enabled == null || enabled.isEmpty()) ? myService : enabled + ":" + myService;
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, toSet);
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshPairingUi() {
        String ip = getIpAddress();
        String token = sSessionAuth != null ? sSessionAuth.getCurrentToken() : "------";
        String url = "http://" + ip + ":" + PORT + "/?token=" + token;

        tvUrl.setText("http://" + ip + ":" + PORT);
        if (tvSubtitle != null) {
            tvSubtitle.setText(R.string.pairing_instructions);
        }
        tvToken.setText(token);

        try {
            Bitmap qr = MicroQr.encodeToBitmap(url, 480);
            ivQrCode.setImageBitmap(qr);
        } catch (Exception ignored) {
        }
    }

    private String getIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp())
                    continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (!host.startsWith("10.0.2.")) {
                            return host; // Real device Wi-Fi IP (e.g. Lumio Arc 5)
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Emulator fallback: check if host IP was set via adb shell setprop
        // debug.host_ip <ip>
        try {
            Process p = Runtime.getRuntime().exec("getprop debug.host_ip");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String prop = r.readLine();
            if (prop != null && !prop.trim().isEmpty()) {
                return prop.trim();
            }
        } catch (Exception ignored) {
        }

        return "10.0.2.15";
    }

    @Override
    public void onClientsChanged(final List<NativeServer.ClientInfo> clients) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (clients != null && !clients.isEmpty()) {
                    NativeServer.ClientInfo client = clients.get(0);
                    currentConnectedClientId = client.id;
                    tvStatusBadge
                            .setText(clients.size() == 1 ? "1 phone connected" : clients.size() + " phones connected");
                    tvStatusBadge.setTextColor(0xFF10B981); // Emerald status success

                    layoutConnectedDevice.setVisibility(View.VISIBLE);
                    if (clients.size() == 1) {
                        tvDeviceName.setText(client.deviceName);
                        tvDeviceIp.setText(client.ip);
                    } else {
                        tvDeviceName.setText(client.deviceName + " (+" + (clients.size() - 1) + " other)");
                        tvDeviceIp.setText(clients.size() + " active remotes");
                    }
                } else {
                    currentConnectedClientId = null;
                    tvStatusBadge.setText(R.string.status_waiting);
                    tvStatusBadge.setTextColor(0xFF38BDF8); // Cyan
                    layoutConnectedDevice.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sServerInstance != null) {
            sServerInstance.setStatusListener(null);
        }
    }

}
    