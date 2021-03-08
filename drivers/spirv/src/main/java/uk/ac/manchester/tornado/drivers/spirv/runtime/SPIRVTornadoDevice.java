package uk.ac.manchester.tornado.drivers.spirv.runtime;

import uk.ac.manchester.tornado.api.TornadoDeviceContext;
import uk.ac.manchester.tornado.api.common.Event;
import uk.ac.manchester.tornado.api.common.SchedulableTask;
import uk.ac.manchester.tornado.api.enums.TornadoDeviceType;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.api.exceptions.TornadoMemoryException;
import uk.ac.manchester.tornado.api.exceptions.TornadoOutOfMemoryException;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.api.mm.ObjectBuffer;
import uk.ac.manchester.tornado.api.mm.TornadoDeviceObjectState;
import uk.ac.manchester.tornado.api.mm.TornadoMemoryProvider;
import uk.ac.manchester.tornado.drivers.opencl.mm.*;
import uk.ac.manchester.tornado.drivers.spirv.SPIRVDevice;
import uk.ac.manchester.tornado.drivers.spirv.SPIRVDeviceContext;
import uk.ac.manchester.tornado.drivers.spirv.SPIRVDriver;
import uk.ac.manchester.tornado.drivers.spirv.SPIRVProxy;
import uk.ac.manchester.tornado.drivers.spirv.mm.*;
import uk.ac.manchester.tornado.runtime.TornadoCoreRuntime;
import uk.ac.manchester.tornado.runtime.common.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is the core class for the actual runtime.
 */
public class SPIRVTornadoDevice implements TornadoAcceleratorDevice {

    private static final boolean BENCHMARKING_MODE = Boolean.parseBoolean(System.getProperties().getProperty("tornado.benchmarking", "False"));

    private SPIRVDevice device;
    private static SPIRVDriver driver = null;
    private int deviceIndex;
    private int platformIndex;

    public SPIRVTornadoDevice(int platformIndex, int deviceIndex) {
        this.platformIndex = platformIndex;
        this.deviceIndex = deviceIndex;
        device = SPIRVProxy.getPlatform(platformIndex).getDevice(deviceIndex);
    }

    public SPIRVTornadoDevice(SPIRVDevice lowLevelDevice) {
        this.platformIndex = lowLevelDevice.getPlatformIndex();
        this.deviceIndex = lowLevelDevice.getDeviceIndex();
        device = lowLevelDevice;
    }

    @Override
    public TornadoSchedulingStrategy getPreferredSchedule() {
        return null;
    }

    @Override
    public CallStack createStack(int numArgs) {
        return getDeviceContext().getMemoryManager().createCallStack(numArgs);
    }

    @Override
    public ObjectBuffer createBuffer(int[] buffer) {
        return null;
    }

    @Override
    public ObjectBuffer createOrReuseBuffer(int[] arr) {
        return null;
    }

    @Override
    public TornadoInstalledCode installCode(SchedulableTask task) {
        return null;
    }

    @Override
    public boolean isFullJITMode(SchedulableTask task) {
        return false;
    }

    @Override
    public TornadoInstalledCode getCodeFromCache(SchedulableTask task) {
        return null;
    }

    @Override
    public int[] checkAtomicsForTask(SchedulableTask task) {
        return new int[0];
    }

    @Override
    public int[] checkAtomicsForTask(SchedulableTask task, int[] array, int paramIndex, Object value) {
        return new int[0];
    }

    @Override
    public int[] updateAtomicRegionAndObjectState(SchedulableTask task, int[] array, int paramIndex, Object value, DeviceObjectState objectState) {
        return new int[0];
    }

    @Override
    public int getAtomicsGlobalIndexForTask(SchedulableTask task, int paramIndex) {
        return 0;
    }

    @Override
    public boolean checkAtomicsParametersForTask(SchedulableTask task) {
        return false;
    }

    @Override
    public void enableThreadSharing() {

    }

    @Override
    public void setAtomicRegion(ObjectBuffer bufferAtomics) {

    }

    private ObjectBuffer createArrayWrapper(Class<?> klass, SPIRVDeviceContext device, long batchSize) {
        if (klass == int[].class) {
            return new SPIRVIntArrayWrapper(device, batchSize);
        } else if (klass == float[].class) {
            return new SPIRVFloatArrayWrapper(device, batchSize);
        } else if (klass == double[].class) {
            return new SPIRVDoubleArrayWrapper(device, batchSize);
        } else if (klass == short[].class) {
            return new SPIRVShortArrayWrapper(device, batchSize);
        } else if (klass == byte[].class) {
            return new SPIRVByteArrayWrapper(device, batchSize);
        } else if (klass == long[].class) {
            return new SPIRVLongArrayWrapper(device, batchSize);
        } else if (klass == char[].class) {
            return new SPIRVCharArrayWrapper(device, batchSize);
        }
        throw new RuntimeException("[SPIRV] Array Wrapper Not Implemented yet: " + klass);
    }

    private ObjectBuffer createMultiArrayWrapper(Class<?> componentType, Class<?> type, SPIRVDeviceContext device, long batchSize) {
        throw new RuntimeException("[SPIRV] createMultiArrayWrapper Not supported yet");
    }

    private ObjectBuffer createDeviceBuffer(Class<?> type, Object object, SPIRVDeviceContext deviceContext, long batchSize) {
        ObjectBuffer result = null;
        if (type.isArray()) {
            if (!type.getComponentType().isArray()) {
                return createArrayWrapper(type, deviceContext, batchSize);
            } else {
                final Class<?> componentType = type.getComponentType();
                if (RuntimeUtilities.isPrimitiveArray(componentType)) {
                    return createMultiArrayWrapper(componentType, type, deviceContext, batchSize);
                } else {
                    throw new RuntimeException("Multi-dimensional array of type " + type.getName() + " not implemented");
                }
            }
        } else if (!type.isPrimitive()) {
            throw new RuntimeException("Not implemented yet");
        }

        TornadoInternalError.guarantee(result != null, "Unable to create a buffer for object with type: " + type);
        return null;
    }

    private void checkBatchSize(long batchSize) {
        if (batchSize > 0) {
            throw new TornadoRuntimeException("[ERROR] Batch computation with non-arrays not supported yet.");
        }
    }

    private void reserveMemory(Object object, long batchSize, TornadoDeviceObjectState state) {

        final ObjectBuffer buffer = createDeviceBuffer(object.getClass(), object, (SPIRVDeviceContext) getDeviceContext(), batchSize);
        buffer.allocate(object, batchSize);
        state.setBuffer(buffer);

        if (buffer.getClass() == AtomicsBuffer.class) {
            state.setAtomicRegion();
        }

        final Class<?> type = object.getClass();
        if (!type.isArray()) {
            checkBatchSize(batchSize);
        }
        state.setValid(true);
    }

    // FIXME <REFACTOR> Common 3 backends
    private void checkForResizeBuffer(Object object, long batchSize, TornadoDeviceObjectState state) {
        // We re-allocate if the buffer size has changed
        final ObjectBuffer buffer = state.getBuffer();
        try {
            buffer.allocate(object, batchSize);
        } catch (TornadoOutOfMemoryException | TornadoMemoryException e) {
            e.printStackTrace();
        }
    }

    // FIXME <REFACTOR> <S>
    private void reAllocateInvalidBuffer(Object object, long batchSize, TornadoDeviceObjectState state) {
        try {
            state.getBuffer().allocate(object, batchSize);
            final Class<?> type = object.getClass();
            if (!type.isArray()) {
                checkBatchSize(batchSize);
                state.getBuffer().write(object);
            }
            state.setValid(true);
        } catch (TornadoOutOfMemoryException | TornadoMemoryException e) {
            e.printStackTrace();
        }
    }

    // FIXME <REFACTOR> <S>
    @Override
    public int ensureAllocated(Object object, long batchSize, TornadoDeviceObjectState state) {
        if (!state.hasBuffer()) {
            reserveMemory(object, batchSize, state);
        } else {
            checkForResizeBuffer(object, batchSize, state);
        }
        if (!state.isValid()) {
            reAllocateInvalidBuffer(object, batchSize, state);
        }
        return -1;
    }

    /**
     * It allocates and copy in the content of the object to the target device.
     *
     * @param object
     *            to be allocated
     * @param objectState
     *            state of the object in the target device
     *            {@link TornadoDeviceObjectState}
     * @param events
     *            list of pending events (dependencies)
     * @param batchSize
     *            size of the object to be allocated. If this value is <= 0, then it
     *            allocates the sizeof(object).
     * @param offset
     *            offset in bytes for the copy within the host input array (or
     *            object)
     * @return A list of event IDs
     */
    @Override
    public List<Integer> ensurePresent(Object object, TornadoDeviceObjectState objectState, int[] events, long batchSize, long offset) {
        if (!objectState.isValid()) {
            ensureAllocated(object, batchSize, objectState);
        }

        if (BENCHMARKING_MODE || !objectState.hasContents()) {
            objectState.setContents(true);
            return objectState.getBuffer().enqueueWrite(object, batchSize, offset, events, events == null);
        }
        return null;
    }

    @Override
    public List<Integer> streamIn(Object object, long batchSize, long hostOffset, TornadoDeviceObjectState objectState, int[] events) {
        if (batchSize > 0 || !objectState.isValid()) {
            ensureAllocated(object, batchSize, objectState);
        }
        objectState.setContents(true);
        return objectState.getBuffer().enqueueWrite(object, batchSize, hostOffset, events, events == null);
    }

    @Override
    public int streamOut(Object object, long hostOffset, TornadoDeviceObjectState objectState, int[] events) {
        TornadoInternalError.guarantee(objectState.isValid(), "invalid variable");
        int event = objectState.getBuffer().enqueueRead(object, hostOffset, events, events == null);
        if (events != null) {
            return event;
        }
        return -1;
    }

    @Override
    public int streamOutBlocking(Object object, long hostOffset, TornadoDeviceObjectState objectState, int[] events) {
        if (objectState.isAtomicRegionPresent()) {
            int eventID = objectState.getBuffer().enqueueRead(null, 0, null, false);
            if (object instanceof AtomicInteger) {
                throw new RuntimeException("Atomics Not supported yet");
            }
            return eventID;
        } else {
            TornadoInternalError.guarantee(objectState.isValid(), "invalid variable");
            return objectState.getBuffer().read(object, hostOffset, events, events == null);
        }
    }

    @Override
    public Event resolveEvent(int event) {
        return null;
    }

    @Override
    public void ensureLoaded() {

    }

    @Override
    public void flushEvents() {

    }

    @Override
    public int enqueueBarrier() {
        return 0;
    }

    @Override
    public int enqueueBarrier(int[] events) {
        return 0;
    }

    @Override
    public int enqueueMarker() {
        return 0;
    }

    @Override
    public int enqueueMarker(int[] events) {
        return 0;
    }

    @Override
    public void sync() {

    }

    @Override
    public void flush() {

    }

    @Override
    public void reset() {

    }

    @Override
    public void dumpEvents() {

    }

    @Override
    public void dumpMemory(String file) {

    }

    @Override
    public String getDeviceName() {
        return "spirv-" + device.getDeviceIndex();
    }

    @Override
    public String getDescription() {
        return String.format("%s %s", device.getName(), device.getTornadoDeviceType());
    }

    @Override
    public String getPlatformName() {
        return device.getPlatformName();
    }

    @Override
    public SPIRVDeviceContext getDeviceContext() {
        return device.getDeviceContext();
    }

    @Override
    public SPIRVDevice getPhysicalDevice() {
        return device;
    }

    @Override
    public TornadoMemoryProvider getMemoryProvider() {
        return null;
    }

    @Override
    public TornadoDeviceType getDeviceType() {
        return device.getTornadoDeviceType();
    }

    @Override
    public long getMaxAllocMemory() {
        return device.getMaxAllocMemory();
    }

    @Override
    public long getMaxGlobalMemory() {
        return device.getDeviceGlobalMemorySize();
    }

    @Override
    public long getDeviceLocalMemorySize() {
        return device.getDeviceLocalMemorySize();
    }

    @Override
    public long[] getDeviceMaxWorkgroupDimensions() {
        return device.getDeviceMaxWorkgroupDimensions();
    }

    @Override
    public String getDeviceOpenCLCVersion() {
        return device.getDeviceOpenCLCVersion();
    }

    @Override
    public Object getDeviceInfo() {
        return null;
    }

    @Override
    public int getDriverIndex() {
        return TornadoCoreRuntime.getTornadoRuntime().getDriverIndex(SPIRVDriver.class);
    }

    @Override
    public Object getAtomic() {
        throw new RuntimeException("Unimplemented");
    }

    @Override
    public void setAtomicsMapping(ConcurrentHashMap<Object, Integer> mappingAtomics) {
        throw new RuntimeException("Unimplemented");
    }

    @Override
    public TornadoVMBackendType getTornadoVMBackend() {
        return TornadoVMBackendType.SPIRV;
    }

    @Override
    public String toString() {
        return device.getName();
    }

    // FIXME <THis should be in the parent class> All backends
    public void sync(Object... objects) {
        for (Object obj : objects) {
            sync(obj);
        }
    }

    // FIXME <THis should be in the parent class> All backends
    public void sync(Object object) {
        final DeviceObjectState state = TornadoCoreRuntime.getTornadoRuntime().resolveObject(object).getDeviceState(this);
        resolveEvent(streamOut(object, 0, state, null)).waitOn();
    }

}
