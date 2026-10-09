package com.cryptovisor.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQ_CODE = 1234;

    private Button btnToggleService;
    private TextView tvStatus;
    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggleService = findViewById(R.id.btnToggleService);
        tvStatus = findViewById(R.id.tvServiceStatus);
        Button btnRequestOverlay = findViewById(R.id.btnRequestOverlay);
        Button btnRequestBattery = findViewById(R.id.btnRequestBattery);

        // Botones de Simulación
        findViewById(R.id.btnSimMegaShock).setOnClickListener(v -> {
            Toast.makeText(this, "Simulando Alarma MEGA SHOCK", Toast.LENGTH_SHORT).show();
            // Disparar prueba
        });

        btnRequestOverlay.setOnClickListener(v -> checkOverlayPermission());
        btnRequestBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        btnToggleService.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Debes otorgar permiso para mostrar sobre otras apps", Toast.LENGTH_LONG).show();
                checkOverlayPermission();
                return;
            }

            Intent serviceIntent = new Intent(MainActivity.this, FloatingOverlayService.class);
            if (!isServiceRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                isServiceRunning = true;
                updateUI();
                Toast.makeText(this, "Barra Flotante Activa", Toast.LENGTH_SHORT).show();
            } else {
                stopService(serviceIntent);
                isServiceRunning = false;
                updateUI();
                Toast.makeText(this, "Barra Flotante Detenida", Toast.LENGTH_SHORT).show();
            }
        });

        updateUI();
    }

    private void updateUI() {
        if (isServiceRunning) {
            btnToggleService.setText("🛑 Detener Barra Flotante");
            btnToggleService.setBackgroundColor(getColor(android.R.color.holo_red_dark));
            tvStatus.setText("Estado: ACTIVO 24/7 (Flotando sobre otras apps)");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light));
        } else {
            btnToggleService.setText("▶ Iniciar Barra Flotante");
            btnToggleService.setBackgroundColor(getColor(android.R.color.holo_blue_dark));
            tvStatus.setText("Estado: Detenido");
            tvStatus.setTextColor(getColor(android.R.color.darker_gray));
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
            } else {
                Toast.makeText(this, "Permiso de superposición ya concedido ✓", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Optimización de batería ya desactivada ✓ (24/7 garantizado)", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
