package com.example.bleenvmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;

import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BLEnvMon";
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int ENV_ADV_LEN = 28;
    private static final byte ENV_ADV_SIGN1 = 0x55;
    private static final byte ENV_ADV_SIGN2 = (byte)0xAA;

    BluetoothManager bluetoothManager;
    BluetoothAdapter bluetoothAdapter;
    BluetoothLeScanner bluetoothLeScanner;
    ScanSettings scanSettings;

    TextView textViewData;
    Button buttonRefresh;

    boolean scanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        scanSettings = new ScanSettings.Builder()
            .setLegacy(true)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build();

        textViewData = findViewById(R.id.textViewData);
        buttonRefresh = findViewById(R.id.buttonRefresh);
        buttonRefresh.setEnabled(bluetoothLeScanner != null);
        buttonRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (! scanning)
                    bleStartScan();
                else
                    bleStopScan();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (! bluetoothAdapter.isEnabled())
            promptEnableBluetooth();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanning) {
            bleStopScan();
        }
    }

    private void promptEnableBluetooth() {
        if (hasRequiredBlePermissions() && (! bluetoothAdapter.isEnabled())) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activityResultLauncher.launch(enableIntent);
        }
    }

    ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() != MainActivity.RESULT_OK) {
                promptEnableBluetooth();
            }
        }
    );

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0) {
                boolean granted = true;

                for (int i = 0; i < grantResults.length; ++i) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                        break;
                    }
                }
                if (granted)
                    bleStartScan();
            }
        } else
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private boolean hasPermission(String permissionType) {
        return ContextCompat.checkSelfPermission(this, permissionType) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasRequiredBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }, PERMISSION_REQUEST_CODE);
        } else {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, PERMISSION_REQUEST_CODE);
        }
    }

    private short getAdvDataSigned(byte[] advData, int index) {
        return (short)((advData[index] & 0xFF) | ((advData[index + 1] & 0xFF) << 8));
    }

    private int getAdvDataUnsigned(byte[] advData, int index) {
        return ((advData[index] & 0xFF) | ((advData[index + 1] & 0xFF) << 8));
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();

            if ((device != null) && (! result.isConnectable())) {
                byte[] advData = result.getScanRecord().getBytes();

                if ((advData != null) && (advData[0] == ENV_ADV_LEN + 1) && (advData[1] == (byte)0xFF)) {
                    if ((advData[2] == ENV_ADV_SIGN1) && (advData[3] == ENV_ADV_SIGN2)) {
                        StringBuilder str = new StringBuilder();

                        str.append("Last time: ");
                        str.append(new SimpleDateFormat("HH:mm:ss").format(new Date()));
                        str.append(String.format("\n\nTemperature: %04.2f\u00B0 C\n", getAdvDataSigned(advData, 4) / 100.0));
                        str.append(String.format("Humidity: %04.2f%%\n", getAdvDataUnsigned(advData, 6) / 100.0));
                        str.append(String.format("Pressure: %d hPa\n", getAdvDataUnsigned(advData, 8)));
                        str.append(String.format("CO\u2082: %d ppm\n", getAdvDataUnsigned(advData, 10)));
                        str.append("PMS: [");
                        for (int i = 12; i < ENV_ADV_LEN + 2; i += 2) {
                            if (i > 12)
                                str.append(", ");
                            str.append(getAdvDataUnsigned(advData, i));
                        }
                        str.append("]\n");
                        textViewData.setText(str);
                        bleStopScan();
                    }
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "BLE scan error " + errorCode + "!");
        }
    };

    @SuppressLint("MissingPermission")
    private void bleStartScan() {
        if (scanning)
            return;

        if (! hasRequiredBlePermissions()) {
            requestBlePermissions();
        } else {
            try {
                bluetoothLeScanner.startScan(null, scanSettings, scanCallback);
                buttonRefresh.setText("Stop");
                scanning = true;
            } catch (Exception e) {
                Log.e(TAG, "startScan() exception " + e.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void bleStopScan() {
        if (scanning) {
            bluetoothLeScanner.stopScan(scanCallback);
            buttonRefresh.setText("Refresh");
            scanning = false;
        }
    }
}
