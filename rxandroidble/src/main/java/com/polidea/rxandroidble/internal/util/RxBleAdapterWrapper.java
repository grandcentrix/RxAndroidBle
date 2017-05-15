package com.polidea.rxandroidble.internal.util;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import static android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR;

public class RxBleAdapterWrapper {

    private final BluetoothAdapter bluetoothAdapter;

    @Inject
    public RxBleAdapterWrapper(@Nullable BluetoothAdapter bluetoothAdapter) {
        this.bluetoothAdapter = bluetoothAdapter;
    }

    public Set<BluetoothDevice> getBondedDevices() {
        return bluetoothAdapter.getBondedDevices();
    }

    public BluetoothDevice getRemoteDevice(String macAddress) {
        return bluetoothAdapter.getRemoteDevice(macAddress);
    }

    public boolean hasBluetoothAdapter() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean startLeScan(BluetoothAdapter.LeScanCallback leScanCallback) {
        return bluetoothAdapter.startLeScan(leScanCallback);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean isOffloadedFilteringSupported() {
        return bluetoothAdapter.isOffloadedFilteringSupported();
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    public void startScan(@Nullable List<ScanFilter> scanFilters, ScanSettings scanSettings,
            ScanCallback scanCallback) {
        final BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            scanCallback.onScanFailed(SCAN_FAILED_INTERNAL_ERROR);
            return;
        }
        scanner.startScan(scanFilters, scanSettings, scanCallback);
    }

    public void stopLeScan(BluetoothAdapter.LeScanCallback leScanCallback) {
        bluetoothAdapter.stopLeScan(leScanCallback);
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    public void stopScan(ScanCallback scanCallback) {
        final BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            // cannot stop, bluetooth most likely already disabled
            return;
        }
        scanner.stopScan(scanCallback);
    }
}
