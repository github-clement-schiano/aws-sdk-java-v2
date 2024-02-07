/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.utils.async;

import static software.amazon.awssdk.utils.async.StoringSubscriber.EventType.ON_NEXT;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.utils.NumericUtils;

/**
 * This Subscriber buffers the {@link ByteBuffer} it receives until the specified maximumBuffer is reached, at which point
 * it sends those bytes to the delegate {@link Subscriber}.
 */
@SdkProtectedApi
public class DelegatingBufferingSubscriber extends DelegatingSubscriber<ByteBuffer, ByteBuffer> {

    /**
     * The maximum amount of bytes allowed to be stored in the StoringSubscriber
     */
    private final long maximumBuffer;

    /**
     * Current amount of bytes buffered in the StoringSubscriber
     */
    private final AtomicLong currentlyBuffered = new AtomicLong(0);

    /**
     * Stores the bytes received from the upstream publisher, awaiting sending them to the delegate once the buffer size is
     * reached.
     */
    private final StoringSubscriber<ByteBuffer> storage = new StoringSubscriber<>(Integer.MAX_VALUE);

    /**
     * Publisher to send the ByteBuffer received from upstream publisher to the StoringSubscriber
     */
    private final SimplePublisher<ByteBuffer> publisherToStorage = new SimplePublisher<>();

    /**
     * Subscription of the publisher this subscriber subscribe to
     */
    private Subscription subscription;

    public DelegatingBufferingSubscriber(long maximumBuffer, Subscriber<? super ByteBuffer> subscriber) {
        super(subscriber);
        this.maximumBuffer = maximumBuffer;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        // §2.05 must call subscription.cancel() if it already has a subscription and receives another on subscribe signal
        if (this.subscription != null) {
            subscription.cancel();
            return;
        }
        super.onSubscribe(subscription);
        this.subscription = subscription;
        publisherToStorage.subscribe(storage);
        subscription.request(maximumBuffer);
    }

    @Override
    public void onNext(ByteBuffer byteBuffer) {
        while (bufferOverflows(byteBuffer)) {
            flushStorageToDelegate();
            if (!bufferOverflows(byteBuffer)) {
                break;
            }

            // even after flushing the storage buffer and being empty, the incoming ByteBuffer might itself still be too large and
            // overflow the buffer (byteBuffer.remainig > maximumBuffer). If so, send chunks of maximumBuffer out of the
            // incoming ByteBuffer to the storage and to the delegate until what is left in the incoming ByteBuffer can be stored.
            ByteBuffer chunk = ByteBuffer.allocate(NumericUtils.saturatedCast(maximumBuffer));
            while (chunk.hasRemaining()) {
                chunk.put(byteBuffer.get());
            }
            chunk.position(0);
            sendBytesToStorage(chunk);
        }

        sendBytesToStorage(byteBuffer);
        if (currentlyBuffered.get() >= maximumBuffer) {
            flushStorageToDelegate();
        }

        // request more if available
        long available = maximumBuffer - currentlyBuffered.get();
        if (available > 0) {
            subscription.request(available);
        }
    }

    private boolean bufferOverflows(ByteBuffer byteBuffer) {
        return currentlyBuffered.get() + byteBuffer.remaining() > maximumBuffer;
    }

    @Override
    public void onComplete() {
        flushStorageToDelegate();
        super.onComplete();
    }

    @Override
    public void onError(Throwable throwable) {
        flushStorageToDelegate();
        super.onError(throwable);
    }

    private void sendBytesToStorage(ByteBuffer buffer) {
        publisherToStorage.send(buffer.duplicate());
        currentlyBuffered.addAndGet(buffer.remaining());
    }

    private void flushStorageToDelegate() {
        long totalBufferRemaining = currentlyBuffered.get();
        Optional<StoringSubscriber.Event<ByteBuffer>> next = storage.poll();
        while (totalBufferRemaining > 0) {
            if (!next.isPresent() || next.get().type() != ON_NEXT) {
                break;
            }
            StoringSubscriber.Event<ByteBuffer> byteBufferEvent = next.get();
            totalBufferRemaining -= byteBufferEvent.value().remaining();
            currentlyBuffered.addAndGet(-byteBufferEvent.value().remaining());
            subscriber.onNext(byteBufferEvent.value());
            next = storage.poll();
        }
    }
}
