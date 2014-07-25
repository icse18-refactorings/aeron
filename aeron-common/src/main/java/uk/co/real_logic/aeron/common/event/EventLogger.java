/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.common.event;

import uk.co.real_logic.aeron.common.IoUtil;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.concurrent.ringbuffer.ManyToOneRingBuffer;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

import static uk.co.real_logic.aeron.common.event.EventCode.EXCEPTION;
import static uk.co.real_logic.aeron.common.event.EventCode.INVOCATION;

/**
 * Event logger interface for applications/libraries
 */
public class EventLogger
{
    /** A bitset storing the enabled event codes */
    private static final long enabledEventCodes;
    private static final ManyToOneRingBuffer ringBuffer;
    private static final ThreadLocal<AtomicBuffer> encodingBuffer;

    /**
     *  The index in the stack trace of the method that called logException().
     *
     *  NB: stack[0] is Thread.currentThread().getStackTrace() and
     *  stack[1] is logException().
     */
    private static final int INVOKING_METHOD_INDEX = 2;

    static
    {
        ManyToOneRingBuffer tmpRingBuffer;
        MappedByteBuffer tmpBuffer;
        ThreadLocal<AtomicBuffer> tmpEncodingBuffer;
        final File bufferLocation = new File(System.getProperty(EventConfiguration.LOCATION_PROPERTY_NAME,
                                                                EventConfiguration.LOCATION_DEFAULT));

        // if can't map existing file, then turn logging off

        try
        {
            tmpBuffer = IoUtil.mapExistingFile(bufferLocation, "event-buffer");
            tmpRingBuffer = new ManyToOneRingBuffer(new AtomicBuffer(tmpBuffer));
            tmpEncodingBuffer = ThreadLocal.withInitial(
                () -> new AtomicBuffer(ByteBuffer.allocateDirect(EventConfiguration.MAX_EVENT_LENGTH)));
        }
        catch (final Exception ex)
        {
            tmpRingBuffer = null;
            tmpEncodingBuffer = null;
        }

        ringBuffer = tmpRingBuffer;
        encodingBuffer = tmpEncodingBuffer;

        // TODO: other config - like snaplen (tcpdump style)
        if (tmpRingBuffer == null)
        {
            enabledEventCodes = 0L;
        }
        else
        {
            enabledEventCodes = EventConfiguration.getEnabledEventCodes();
        }
    }

    public void log(final EventCode code, final AtomicBuffer buffer, final int offset, final int length)
    {
        if (isEnabled(code))
        {
            final AtomicBuffer encodedBuffer = encodingBuffer.get();
            final int encodedLength = EventCodec.encode(encodedBuffer, buffer, offset, length);

            ringBuffer.write(code.id(), encodedBuffer, 0, encodedLength);
        }
    }

    public void log(final EventCode code, final ByteBuffer buffer, final int offset,
                    final int length, final InetSocketAddress dstAddress)
    {
        if (isEnabled(code))
        {
            final AtomicBuffer encodedBuffer = encodingBuffer.get();
            final int encodedLength = EventCodec.encode(encodedBuffer, buffer, offset, length, dstAddress);

            ringBuffer.write(code.id(), encodedBuffer, 0, encodedLength);
        }
    }

    // TODO: in order to make this an instance field UdpDestination's initialisation needs to avoid
    // a static block
    public static void log(final EventCode code, final String value)
    {
        if (isEnabled(code))
        {
            final AtomicBuffer encodedBuffer = encodingBuffer.get();
            final int encodingLength = EventCodec.encode(encodedBuffer, value);

            ringBuffer.write(code.id(), encodedBuffer, 0, encodingLength);
        }
    }

    /**
     * Method static because its currently only used in tests.
     */
    public static void logInvocation()
    {
        if (isEnabled(INVOCATION))
        {
            final StackTraceElement[] stack = Thread.currentThread().getStackTrace();

            final AtomicBuffer encodedBuffer = encodingBuffer.get();
            final int encodedLength = EventCodec.encode(encodedBuffer, stack[INVOKING_METHOD_INDEX]);

            ringBuffer.write(INVOCATION.id(), encodedBuffer, 0, encodedLength);
        }
    }

    public void logException(final Exception ex)
    {
        if (isEnabled(EXCEPTION))
        {
            final AtomicBuffer encodedBuffer = encodingBuffer.get();
            final int encodedLength = EventCodec.encode(encodedBuffer, ex);

            ringBuffer.write(EXCEPTION.id(), encodedBuffer, 0, encodedLength);
        }
    }

    private static boolean isEnabled(final EventCode code)
    {
        final long tagBit = code.tagBit();
        return (enabledEventCodes & tagBit) == tagBit;
    }

}
