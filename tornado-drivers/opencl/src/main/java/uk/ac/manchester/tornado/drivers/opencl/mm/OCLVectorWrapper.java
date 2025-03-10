/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2022, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package uk.ac.manchester.tornado.drivers.opencl.mm;

import static uk.ac.manchester.tornado.runtime.common.RuntimeUtilities.humanReadableByteCount;
import static uk.ac.manchester.tornado.runtime.common.Tornado.info;
import static uk.ac.manchester.tornado.runtime.graal.TornadoLIRGenerator.warn;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import jdk.vm.ci.meta.JavaKind;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.api.exceptions.TornadoMemoryException;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.api.internal.annotations.Payload;
import uk.ac.manchester.tornado.api.memory.ObjectBuffer;
import uk.ac.manchester.tornado.api.types.arrays.ByteArray;
import uk.ac.manchester.tornado.api.types.arrays.CharArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.LongArray;
import uk.ac.manchester.tornado.api.types.arrays.ShortArray;
import uk.ac.manchester.tornado.api.types.arrays.TornadoNativeArray;
import uk.ac.manchester.tornado.api.types.common.PrimitiveStorage;
import uk.ac.manchester.tornado.drivers.opencl.OCLDeviceContext;
import uk.ac.manchester.tornado.runtime.common.Tornado;
import uk.ac.manchester.tornado.runtime.common.TornadoOptions;
import uk.ac.manchester.tornado.runtime.utils.TornadoUtils;

public class OCLVectorWrapper implements ObjectBuffer {

    private static final int INIT_VALUE = -1;
    protected final OCLDeviceContext deviceContext;
    private final long batchSize;
    private final JavaKind kind;
    private long bufferId;
    private long bufferOffset;
    private long bufferSize;
    private long setSubRegionSize;

    public OCLVectorWrapper(final OCLDeviceContext device, final Object object, long batchSize) {
        TornadoInternalError.guarantee(object instanceof PrimitiveStorage, "Expecting a PrimitiveStorage type, but found: " + object.getClass());
        this.deviceContext = device;
        this.batchSize = batchSize;
        this.bufferId = INIT_VALUE;
        this.bufferOffset = 0;
        Object payload = TornadoUtils.getAnnotatedObjectFromField(object, Payload.class);
        this.kind = getJavaKind(payload.getClass());
        this.bufferSize = sizeOf(payload);
    }

    public long getBatchSize() {
        return batchSize;
    }

    @Override
    public void allocate(Object value, long batchSize) {
        TornadoInternalError.guarantee(value instanceof PrimitiveStorage, "Expecting a PrimitiveStorage type");
        final Object hostArray = TornadoUtils.getAnnotatedObjectFromField(value, Payload.class);
        if (batchSize <= 0) {
            bufferSize = sizeOf(hostArray);
        } else {
            bufferSize = batchSize;
        }

        if (bufferSize <= 0) {
            throw new TornadoMemoryException("[ERROR] Bytes Allocated <= 0: " + bufferSize);
        }

        this.bufferId = deviceContext.getBufferProvider().getBufferWithSize(bufferSize);

        if (Tornado.FULL_DEBUG) {
            info("allocated: array kind=%s, size=%s, length offset=%d, header size=%d", kind.getJavaName(), humanReadableByteCount(bufferSize, true), bufferOffset,
                    TornadoOptions.PANAMA_OBJECT_HEADER_SIZE);
        }
    }

    @Override
    public void deallocate() {
        TornadoInternalError.guarantee(bufferId != INIT_VALUE, "Fatal error: trying to deallocate an invalid buffer");

        deviceContext.getBufferProvider().markBufferReleased(bufferId, bufferSize);
        bufferId = INIT_VALUE;
        bufferSize = INIT_VALUE;

        if (Tornado.FULL_DEBUG) {
            info("deallocated: array kind=%s, size=%s, length offset=%d, header size=%d", kind.getJavaName(), humanReadableByteCount(bufferSize, true), bufferOffset,
                    TornadoOptions.PANAMA_OBJECT_HEADER_SIZE);
        }
    }

    @Override
    public long size() {
        return bufferSize;
    }

    @Override
    public void setSizeSubRegion(long batchSize) {
        this.setSubRegionSize = batchSize;
    }

    @Override
    public int enqueueRead(final Object value, long hostOffset, final int[] events, boolean useDeps) {
        TornadoInternalError.guarantee(value instanceof PrimitiveStorage, "Expecting a PrimitiveStorage type");
        final Object actualValue = TornadoUtils.getAnnotatedObjectFromField(value, Payload.class);
        if (actualValue == null) {
            throw new TornadoRuntimeException("[ERROR] output data is NULL");
        }
        final int returnEvent = enqueueReadArrayData(toBuffer(), bufferOffset, bufferSize, actualValue, hostOffset, (useDeps) ? events : null);
        return useDeps ? returnEvent : -1;
    }

    /**
     * Copy data from the device to the main host.
     *
     * @param bufferId
     *     Device Buffer ID
     * @param offset
     *     Offset within the device buffer
     * @param bytes
     *     Bytes to be copied back to the host
     * @param value
     *     Host array that resides the final data
     * @param waitEvents
     *     List of events to wait for.
     * @return Event information
     */
    private int enqueueReadArrayData(long bufferId, long offset, long bytes, Object value, long hostOffset, int[] waitEvents) {
        if (kind == JavaKind.Int) {
            return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, (int[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Float) {
            return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, (float[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Double) {
            return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, (double[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Long) {
            return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, (long[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Short) {
            return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, (short[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Byte) {
            return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, (byte[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Object) {
            if (value instanceof TornadoNativeArray nativeArray) {
                return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, nativeArray.getSegment().address(), hostOffset, waitEvents);
            } else {
                throw new TornadoRuntimeException("Type not supported: " + value.getClass());
            }
        } else {
            TornadoInternalError.shouldNotReachHere("Expecting an array type");
        }
        return -1;
    }

    @Override
    public List<Integer> enqueueWrite(final Object value, long batchSize, long hostOffset, final int[] events, boolean useDeps) {
        TornadoInternalError.guarantee(value instanceof PrimitiveStorage, "Expecting a PrimitiveStorage type");
        final Object array = TornadoUtils.getAnnotatedObjectFromField(value, Payload.class);
        ArrayList<Integer> listEvents = new ArrayList<>();

        if (array == null) {
            throw new TornadoRuntimeException("ERROR] Data to be copied is NULL");
        }
        final int returnEvent = enqueueWriteArrayData(toBuffer(), bufferOffset, bufferSize, array, hostOffset, (useDeps) ? events : null);
        listEvents.add(returnEvent);
        return useDeps ? listEvents : null;
    }

    private int enqueueWriteArrayData(long bufferId, long offset, long bytes, Object value, long hostOffset, int[] waitEvents) {
        if (kind == JavaKind.Int) {
            return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, (int[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Float) {
            return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, (float[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Double) {
            return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, (double[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Long) {
            return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, (long[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Short) {
            return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, (short[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Byte) {
            return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, (byte[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Object) {
            if (value instanceof TornadoNativeArray nativeArray) {
                return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, nativeArray.getSegment().address(), hostOffset, waitEvents);
            } else {
                throw new TornadoRuntimeException("Type not supported: " + value.getClass());
            }
        } else {
            TornadoInternalError.shouldNotReachHere("Expecting an array type");
        }
        return -1;
    }

    @Override
    public void read(final Object value) {
        // TODO: reading with offset != 0
        read(value, 0, null, false);
    }

    @Override
    public int read(final Object value, long hostOffset, int[] events, boolean useDeps) {
        TornadoInternalError.guarantee(value instanceof PrimitiveStorage, "Expecting a PrimitiveStorage type");
        final Object array = TornadoUtils.getAnnotatedObjectFromField(value, Payload.class);
        if (array == null) {
            throw new TornadoRuntimeException("[ERROR] output data is NULL");
        }
        return readArrayData(toBuffer(), bufferOffset, bufferSize, array, hostOffset, (useDeps) ? events : null);
    }

    private int readArrayData(long bufferId, long offset, long bytes, Object value, long hostOffset, int[] waitEvents) {
        if (kind == JavaKind.Int) {
            return deviceContext.readBuffer(bufferId, offset, bytes, (int[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Float) {
            return deviceContext.readBuffer(bufferId, offset, bytes, (float[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Double) {
            return deviceContext.readBuffer(bufferId, offset, bytes, (double[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Long) {
            return deviceContext.readBuffer(bufferId, offset, bytes, (long[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Short) {
            return deviceContext.readBuffer(bufferId, offset, bytes, (short[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Byte) {
            return deviceContext.readBuffer(bufferId, offset, bytes, (byte[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Object) {
            if (value instanceof TornadoNativeArray nativeArray) {
                return deviceContext.readBuffer(bufferId, offset, bytes, nativeArray.getSegment().address(), hostOffset, waitEvents);
            } else {
                throw new TornadoRuntimeException("Type not supported: " + value.getClass());
            }
        } else {
            TornadoInternalError.shouldNotReachHere("Expecting an array type");
        }
        return -1;
    }

    private long sizeOf(final Object array) {
        long size;
        if (array instanceof TornadoNativeArray nativeArray) {
            size = nativeArray.getNumBytesOfSegment();
        } else {
            size = (long) Array.getLength(array) * kind.getByteCount();
        }
        return size;
    }

    @Override
    public long toBuffer() {
        return bufferId;
    }

    @Override
    public void setBuffer(ObjectBufferWrapper bufferWrapper) {
        this.bufferId = bufferWrapper.buffer;
        this.bufferOffset = bufferWrapper.bufferOffset;

        bufferWrapper.bufferOffset += size();
    }

    @Override
    public long getBufferOffset() {
        return bufferOffset;
    }

    @Override
    public String toString() {
        return String.format("buffer<%s> %s", kind.getJavaName(), humanReadableByteCount(bufferSize, true));
    }

    @Override
    public void write(final Object value) {
        TornadoInternalError.guarantee(value instanceof PrimitiveStorage, "Expecting a PrimitiveStorage type");
        final Object array = TornadoUtils.getAnnotatedObjectFromField(value, Payload.class);
        if (array == null) {
            throw new TornadoRuntimeException("[ERROR] data is NULL");
        }
        // TODO: Writing with offset != 0
        writeArrayData(toBuffer(), bufferOffset, bufferSize, array, 0, null);
    }

    private void writeArrayData(long bufferId, long offset, long bytes, Object value, long hostOffset, int[] waitEvents) {
        if (kind == JavaKind.Int) {
            deviceContext.writeBuffer(bufferId, offset, bytes, (int[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Float) {
            deviceContext.writeBuffer(bufferId, offset, bytes, (float[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Double) {
            deviceContext.writeBuffer(bufferId, offset, bytes, (double[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Long) {
            deviceContext.writeBuffer(bufferId, offset, bytes, (long[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Short) {
            deviceContext.writeBuffer(bufferId, offset, bytes, (short[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Byte) {
            deviceContext.writeBuffer(bufferId, offset, bytes, (byte[]) value, hostOffset, waitEvents);
        } else if (kind == JavaKind.Object) {
            if (value instanceof TornadoNativeArray nativeArray) {
                deviceContext.writeBuffer(bufferId, offset, bytes, nativeArray.getSegment().address(), hostOffset, waitEvents);
            } else {
                throw new TornadoRuntimeException("Data type not supported: " + value.getClass());
            }
        } else {
            TornadoInternalError.shouldNotReachHere("Expecting an array type");
        }
    }

    private JavaKind getJavaKind(Class<?> type) {
        if (type.isArray()) {
            if (type == int[].class) {
                return JavaKind.Int;
            } else if (type == float[].class) {
                return JavaKind.Float;
            } else if (type == double[].class) {
                return JavaKind.Double;
            } else if (type == long[].class) {
                return JavaKind.Long;
            } else if (type == short[].class) {
                return JavaKind.Short;
            } else if (type == byte[].class) {
                return JavaKind.Byte;
            } else {
                warn("cannot wrap field: array type=%s", type.getName());
            }
        } else if (type == FloatArray.class || type == IntArray.class || type == DoubleArray.class || type == LongArray.class || type == ShortArray.class || type == CharArray.class || type == ByteArray.class) {
            return JavaKind.Object;
        }  else {
            TornadoInternalError.shouldNotReachHere("The type should be an array, but found: " + type);
        }
        return null;
    }

    @Override
    public long getSizeSubRegionSize() {
        return setSubRegionSize;
    }
}
