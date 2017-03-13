package com.polidea.rxandroidble.internal.operations;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import com.polidea.rxandroidble.exceptions.BleGattOperationType;
import com.polidea.rxandroidble.internal.RxBleSingleGattRadioOperation;
import com.polidea.rxandroidble.internal.connection.RxBleGattCallback;
import com.polidea.rxandroidble.internal.util.ByteAssociation;
import java.util.concurrent.TimeUnit;
import rx.Observable;
import rx.Scheduler;
import rx.functions.Func1;

public class RxBleRadioOperationDescriptorWrite extends RxBleSingleGattRadioOperation<byte[]> {

    private final BluetoothGattDescriptor bluetoothGattDescriptor;

    private final byte[] data;

    private final int bluetoothGattCharacteristicDefaultWriteType;

    /**
     * Write Descriptor Operator constructor
     * @param rxBleGattCallback the RxBleGattCallback
     * @param bluetoothGatt the BluetoothGatt to use
     * @param bluetoothGattCharacteristicDefaultWriteType BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
     * @param bluetoothGattDescriptor the descriptor to write
     * @param data the value to write
     * @param timeoutScheduler timeoutScheduler
     */
    public RxBleRadioOperationDescriptorWrite(RxBleGattCallback rxBleGattCallback,
                                              BluetoothGatt bluetoothGatt,
                                              int bluetoothGattCharacteristicDefaultWriteType,
                                              BluetoothGattDescriptor bluetoothGattDescriptor,
                                              byte[] data,
                                              Scheduler timeoutScheduler) {
        super(bluetoothGatt, rxBleGattCallback, BleGattOperationType.DESCRIPTOR_WRITE, 30, TimeUnit.SECONDS, timeoutScheduler);
        this.bluetoothGattCharacteristicDefaultWriteType = bluetoothGattCharacteristicDefaultWriteType;
        this.bluetoothGattDescriptor = bluetoothGattDescriptor;
        this.data = data;
    }

    @Override
    protected Observable<byte[]> getCallback(RxBleGattCallback rxBleGattCallback) {
        return rxBleGattCallback
                .getOnDescriptorWrite()
                .filter(new Func1<ByteAssociation<BluetoothGattDescriptor>, Boolean>() {
                    @Override
                    public Boolean call(ByteAssociation<BluetoothGattDescriptor> uuidPair) {
                        return uuidPair.first.equals(bluetoothGattDescriptor);
                    }
                })
                .map(new Func1<ByteAssociation<BluetoothGattDescriptor>, byte[]>() {
                    @Override
                    public byte[] call(ByteAssociation<BluetoothGattDescriptor> uuidPair) {
                        return uuidPair.second;
                    }
                });
    }

    @Override
    protected boolean startOperation(BluetoothGatt bluetoothGatt) {
        bluetoothGattDescriptor.setValue(data);

        /*
        * According to the source code below Android 7.0.0 the BluetoothGatt.writeDescriptor() function used
        * writeType of the parent BluetoothCharacteristic which caused operation failure (for instance when
        * setting Client Characteristic Config). With WRITE_TYPE_DEFAULT problem did not occurred.
        * Compare:
        * https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r74/core/java/android/bluetooth/BluetoothGatt.java#1039
        * https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/bluetooth/BluetoothGatt.java#947
        */
        final BluetoothGattCharacteristic bluetoothGattCharacteristic = bluetoothGattDescriptor.getCharacteristic();
        final int originalWriteType = bluetoothGattCharacteristic.getWriteType();
        bluetoothGattCharacteristic.setWriteType(bluetoothGattCharacteristicDefaultWriteType);

        final boolean success = bluetoothGatt.writeDescriptor(bluetoothGattDescriptor);

        bluetoothGattCharacteristic.setWriteType(originalWriteType);
        return success;
    }
}
