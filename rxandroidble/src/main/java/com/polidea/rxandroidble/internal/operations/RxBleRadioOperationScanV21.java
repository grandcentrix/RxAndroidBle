package com.polidea.rxandroidble.internal.operations;

import com.polidea.rxandroidble.RxBleScanRecord;
import com.polidea.rxandroidble.exceptions.BleException;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleInternalScanResultV21;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.internal.RxBleRadioOperation;
import com.polidea.rxandroidble.internal.util.RxBleAdapterWrapper;
import com.polidea.rxandroidble.internal.util.UUIDUtil;

import android.annotation.TargetApi;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.ParcelUuid;
import android.support.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class RxBleRadioOperationScanV21 extends RxBleRadioOperation<RxBleInternalScanResultV21> {

    private final ScanSettings settings;
    private final UUID[] filterServiceUUIDs;
    private final UUIDUtil uuidUtil;
    private final HashSet<UUID> filterUUids;
    private final RxBleAdapterWrapper rxBleAdapterWrapper;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            emitFiltered(result);
        }

        /**
         * emits the scan result when when it matches the filter. Sadly some devices don't support
         * filtering from the hardware side.
         */
        private void emitFiltered(final ScanResult result) {
            final byte[] scanRecord = result.getScanRecord().getBytes();
            if (filterUUids == null || uuidUtil.extractUUIDs(scanRecord).containsAll(filterUUids)) {
                doOnNext(result);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                emitFiltered(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            RxBleLog.e("onScanFailed with errorCode %d", errorCode);
            onError(new BleScanException(BleScanException.BLUETOOTH_CANNOT_START));
        }
    };

    public RxBleRadioOperationScanV21(@Nullable ScanSettings settings, UUID[] filterServiceUUIDs,
            RxBleAdapterWrapper rxBleAdapterWrapper, final UUIDUtil uuidUtil) {
        this.settings = settings != null ? settings : new ScanSettings.Builder().build();
        this.filterServiceUUIDs = filterServiceUUIDs;
        this.uuidUtil = uuidUtil;
        if (filterServiceUUIDs != null && filterServiceUUIDs.length > 0) {
            this.filterUUids = new HashSet<>(filterServiceUUIDs.length);
            Collections.addAll(this.filterUUids, filterServiceUUIDs);
        } else {
            this.filterUUids = null;
        }

        this.rxBleAdapterWrapper = rxBleAdapterWrapper;
    }

    @Override
    protected void protectedRun() {
        try {
            if (rxBleAdapterWrapper.isOffloadedFilteringSupported()) {
                List<ScanFilter> scanFilters = getScanFiltersFrom(filterServiceUUIDs);
                rxBleAdapterWrapper.startScan(scanFilters, settings, scanCallback);
            } else {
                // device doesn't support filtering, manual filtering required
                rxBleAdapterWrapper.startScan(null, settings, scanCallback);
            }
        } catch (Throwable throwable) {
            RxBleLog.e(throwable, "Error while calling BluetoothAdapter.startLeScan()");
            onError(new BleScanException(BleScanException.BLUETOOTH_CANNOT_START));
        } finally {
            releaseRadio();
        }
    }

    @Override
    protected BleException provideException(DeadObjectException deadObjectException) {
        return new BleScanException(BleScanException.BLUETOOTH_DISABLED, deadObjectException);
    }

    public void stop() {
        rxBleAdapterWrapper.stopScan(scanCallback);
    }

    private List<ScanFilter> getScanFiltersFrom(UUID[] serviceUuids) {
        List<ScanFilter> filters = new ArrayList<>();
        if (serviceUuids != null && serviceUuids.length > 0) {
            // Note scan filter does not support matching an UUID array so we put one
            // UUID to hardware and match the whole array in callback.
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(
                    new ParcelUuid(serviceUuids[0])).build();
            filters.add(filter);
        }
        return filters;
    }

    private void doOnNext(ScanResult scanResult) {
        ScanRecord scanRecord = scanResult.getScanRecord();
        if (scanRecord == null) return;

        RxBleScanRecord rxBleScanRecord = new RxBleScanRecord(scanRecord.getServiceUuids(),
                scanRecord.getManufacturerSpecificData(),
                scanRecord.getServiceData(),
                scanRecord.getAdvertiseFlags(),
                scanRecord.getTxPowerLevel(),
                scanRecord.getDeviceName(),
                scanRecord.getBytes());

        onNext(new RxBleInternalScanResultV21(scanResult.getDevice(), scanResult.getRssi(), rxBleScanRecord));
    }
}
