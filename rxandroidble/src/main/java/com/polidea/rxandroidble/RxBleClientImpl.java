package com.polidea.rxandroidble;

import com.polidea.rxandroidble.RxBleAdapterStateObservable.BleAdapterState;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleDeviceProvider;
import com.polidea.rxandroidble.internal.RxBleInternalScanResult;
import com.polidea.rxandroidble.internal.RxBleInternalScanResultV21;
import com.polidea.rxandroidble.internal.RxBleRadio;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationScan;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationScanV21;
import com.polidea.rxandroidble.internal.util.LocationServicesStatus;
import com.polidea.rxandroidble.internal.util.RxBleAdapterWrapper;
import com.polidea.rxandroidble.internal.util.UUIDUtil;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;

class RxBleClientImpl extends RxBleClient {

    private final RxBleRadio rxBleRadio;
    private final UUIDUtil uuidUtil;
    private final RxBleDeviceProvider rxBleDeviceProvider;
    private final ExecutorService executorService;
    private final Map<Set<UUID>, Observable<RxBleScanResult>> queuedScanOperations = new HashMap<>();
    private final RxBleAdapterWrapper rxBleAdapterWrapper;
    private final Observable<BleAdapterState> rxBleAdapterStateObservable;
    private final LocationServicesStatus locationServicesStatus;

    @Inject
    RxBleClientImpl(RxBleAdapterWrapper rxBleAdapterWrapper,
                    RxBleRadio rxBleRadio,
                    Observable<BleAdapterState> adapterStateObservable,
                    UUIDUtil uuidUtil,
                    LocationServicesStatus locationServicesStatus,
                    RxBleDeviceProvider rxBleDeviceProvider,
                    @Named(ClientComponent.NamedSchedulers.GATT_CALLBACK) ExecutorService executorService) {
        this.uuidUtil = uuidUtil;
        this.rxBleRadio = rxBleRadio;
        this.rxBleAdapterWrapper = rxBleAdapterWrapper;
        this.rxBleAdapterStateObservable = adapterStateObservable;
        this.locationServicesStatus = locationServicesStatus;
        this.rxBleDeviceProvider = rxBleDeviceProvider;
        this.executorService = executorService;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        executorService.shutdown();
    }

    @Override
    public RxBleDevice getBleDevice(@NonNull String macAddress) {
        guardBluetoothAdapterAvailable();
        return rxBleDeviceProvider.getBleDevice(macAddress);
    }

    @Override
    public Set<RxBleDevice> getBondedDevices() {
        guardBluetoothAdapterAvailable();
        Set<RxBleDevice> rxBleDevices = new HashSet<>();
        Set<BluetoothDevice> bluetoothDevices = rxBleAdapterWrapper.getBondedDevices();
        for (BluetoothDevice bluetoothDevice : bluetoothDevices) {
            rxBleDevices.add(getBleDevice(bluetoothDevice.getAddress()));
        }

        return rxBleDevices;
    }

    @Override
    public Observable<RxBleScanResult> scanBleDevices(@Nullable final UUID... filterServiceUUIDs) {
        return scanBleDevices(null, filterServiceUUIDs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public Observable<RxBleScanResult> scanBleDevices(@NonNull final ScanSettings settings,
            @Nullable final UUID... filterServiceUUIDs) {
        return Observable.defer(new Func0<Observable<RxBleScanResult>>() {
            @Override
            public Observable<RxBleScanResult> call() {
                if (!rxBleAdapterWrapper.hasBluetoothAdapter()) {
                    return Observable
                            .error(new BleScanException(BleScanException.BLUETOOTH_NOT_AVAILABLE));
                } else if (!rxBleAdapterWrapper.isBluetoothEnabled()) {
                    return Observable
                            .error(new BleScanException(BleScanException.BLUETOOTH_DISABLED));
                } else if (!locationServicesStatus.isLocationPermissionOk()) {
                    return Observable.error(new BleScanException(
                            BleScanException.LOCATION_PERMISSION_MISSING));
                } else if (!locationServicesStatus.isLocationProviderOk()) {
                    return Observable.error(new BleScanException(
                            BleScanException.LOCATION_SERVICES_DISABLED));
                } else {
                    return initializeScan(settings, filterServiceUUIDs);
                }
            }
        });
    }

    private Observable<RxBleScanResult> initializeScan(ScanSettings settings, @Nullable UUID[] filterServiceUUIDs) {
        //TODO add settings to the key
        final Set<UUID> filteredUUIDs = uuidUtil.toDistinctSet(filterServiceUUIDs);

        synchronized (queuedScanOperations) {
            Observable<RxBleScanResult> matchingQueuedScan = queuedScanOperations.get(filteredUUIDs);

            if (matchingQueuedScan == null) {
                matchingQueuedScan = createScanOperation(settings, filterServiceUUIDs);
                queuedScanOperations.put(filteredUUIDs, matchingQueuedScan);
            }

            return matchingQueuedScan;
        }
    }

    private <T> Observable<T> bluetoothAdapterOffExceptionObservable() {
        return rxBleAdapterStateObservable
                .filter(new Func1<BleAdapterState, Boolean>() {
                    @Override
                    public Boolean call(BleAdapterState state) {
                        return state != BleAdapterState.STATE_ON;
                    }
                })
                .first()
                .flatMap(new Func1<BleAdapterState, Observable<T>>() {
                    @Override
                    public Observable<T> call(BleAdapterState status) {
                        return Observable.error(new BleScanException(BleScanException.BLUETOOTH_DISABLED));
                    }
                });
    }

    private Observable<RxBleScanResult> createScanOperation(ScanSettings settings, @Nullable UUID[] filterServiceUUIDs) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ? createScanOperationV18(filterServiceUUIDs)
                : createScanOperationV21(settings, filterServiceUUIDs);
    }

    private RxBleScanResult convertToPublicScanResult(RxBleInternalScanResult scanResult) {
        final BluetoothDevice bluetoothDevice = scanResult.getBluetoothDevice();
        final RxBleDevice bleDevice = getBleDevice(bluetoothDevice.getAddress());
        final RxBleScanRecord scanRecord = uuidUtil.parseFromBytes(scanResult.getScanRecord());
        return new RxBleScanResult(bleDevice, scanResult.getRssi(), scanRecord);
    }

    private Observable<RxBleScanResult> createScanOperationV18(@Nullable final UUID[] filterServiceUUIDs) {
        final Set<UUID> filteredUUIDs = uuidUtil.toDistinctSet(filterServiceUUIDs);
        final RxBleRadioOperationScan scanOperation = new RxBleRadioOperationScan(filterServiceUUIDs, rxBleAdapterWrapper, uuidUtil);
        return rxBleRadio.queue(scanOperation)
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        synchronized (queuedScanOperations) {
                            scanOperation.stop();
                            queuedScanOperations.remove(filteredUUIDs);
                        }
                    }
                })
                .mergeWith(this.<RxBleInternalScanResult>bluetoothAdapterOffExceptionObservable())
                .map(new Func1<RxBleInternalScanResult, RxBleScanResult>() {
                    @Override
                    public RxBleScanResult call(RxBleInternalScanResult scanResult) {
                        return convertToPublicScanResult(scanResult);
                    }
                })
                .share();
    }

    private void guardBluetoothAdapterAvailable() {
        if (!rxBleAdapterWrapper.hasBluetoothAdapter()) {
            throw new UnsupportedOperationException("RxAndroidBle library needs a BluetoothAdapter to be available in the system to work."
            + " If this is a test on an emulator then you can use 'https://github.com/Polidea/RxAndroidBle/tree/master/mockrxandroidble'");
        }
    }

    private RxBleScanResult convertToPublicScanResult(RxBleInternalScanResultV21 scanResult) {
        final BluetoothDevice bluetoothDevice = scanResult.getBluetoothDevice();
        final RxBleDevice bleDevice = getBleDevice(bluetoothDevice.getAddress());
        return new RxBleScanResult(bleDevice, scanResult.getRssi(), scanResult.getScanRecord());
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private Observable<RxBleScanResult> createScanOperationV21(ScanSettings settings, @Nullable UUID[] filterServiceUUIDs) {
        final Set<UUID> filteredUUIDs = uuidUtil.toDistinctSet(filterServiceUUIDs);
        final RxBleRadioOperationScanV21 scanOperation =
                new RxBleRadioOperationScanV21(settings, filterServiceUUIDs, rxBleAdapterWrapper, uuidUtil);
        return rxBleRadio.queue(scanOperation)
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        synchronized (queuedScanOperations) {
                            scanOperation.stop();
                            queuedScanOperations.remove(filteredUUIDs);
                        }
                    }
                })
                .mergeWith(this.<RxBleInternalScanResultV21>bluetoothAdapterOffExceptionObservable())
                .map(new Func1<RxBleInternalScanResultV21, RxBleScanResult>() {
                    @Override
                    public RxBleScanResult call(RxBleInternalScanResultV21 scanResult) {
                        return convertToPublicScanResult(scanResult);
                    }
                })
                .share();
    }

}
