/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.client.impl.consumer;

import apache.rocketmq.v1.AckMessageRequest;
import apache.rocketmq.v1.AckMessageResponse;
import apache.rocketmq.v1.Broker;
import apache.rocketmq.v1.ConsumePolicy;
import apache.rocketmq.v1.FilterType;
import apache.rocketmq.v1.ForwardMessageToDeadLetterQueueRequest;
import apache.rocketmq.v1.ForwardMessageToDeadLetterQueueResponse;
import apache.rocketmq.v1.Message;
import apache.rocketmq.v1.NackMessageRequest;
import apache.rocketmq.v1.NackMessageResponse;
import apache.rocketmq.v1.Partition;
import apache.rocketmq.v1.ReceiveMessageRequest;
import apache.rocketmq.v1.ReceiveMessageResponse;
import apache.rocketmq.v1.Resource;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.ConsumeStatus;
import org.apache.rocketmq.client.consumer.MessageModel;
import org.apache.rocketmq.client.consumer.OffsetQuery;
import org.apache.rocketmq.client.consumer.PullMessageQuery;
import org.apache.rocketmq.client.consumer.PullMessageResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.consumer.QueryOffsetPolicy;
import org.apache.rocketmq.client.consumer.ReceiveMessageResult;
import org.apache.rocketmq.client.consumer.ReceiveStatus;
import org.apache.rocketmq.client.consumer.filter.ExpressionType;
import org.apache.rocketmq.client.consumer.filter.FilterExpression;
import org.apache.rocketmq.client.consumer.listener.MessageListenerType;
import org.apache.rocketmq.client.impl.ClientImpl;
import org.apache.rocketmq.client.impl.ClientManager;
import org.apache.rocketmq.client.message.MessageAccessor;
import org.apache.rocketmq.client.message.MessageExt;
import org.apache.rocketmq.client.message.MessageImpl;
import org.apache.rocketmq.client.message.MessageQueue;
import org.apache.rocketmq.client.message.protocol.SystemAttribute;
import org.apache.rocketmq.client.route.Endpoints;

@Slf4j
public class ProcessQueueImpl implements ProcessQueue {
    public static final long RECEIVE_LONG_POLLING_TIMEOUT_MILLIS = 15 * 1000L;
    public static final long RECEIVE_LATER_DELAY_MILLIS = 3 * 1000L;

    public static final long PULL_LONG_POLLING_TIMEOUT_MILLIS = 15 * 1000L;
    public static final long PULL_LATER_DELAY_MILLIS = 3 * 1000L;

    public static final long MAX_IDLE_MILLIS = 30 * 1000L;
    public static final long ACK_FIFO_MESSAGE_DELAY_MILLIS = 100L;
    public static final long REDIRECT_FIFO_MESSAGE_TO_DLQ_DELAY_MILLIS = 100L;

    private volatile boolean dropped;

    @Getter
    private final MessageQueue messageQueue;
    private final FilterExpression filterExpression;

    private final DefaultMQPushConsumerImpl consumerImpl;

    @GuardedBy("pendingMessagesLock")
    private final List<MessageExt> pendingMessages;
    private final ReentrantReadWriteLock pendingMessagesLock;

    @GuardedBy("inflightMessagesLock")
    private final List<MessageExt> inflightMessages;
    private final ReentrantReadWriteLock inflightMessagesLock;

    private final AtomicLong cachedMessagesBytes;
    private final AtomicBoolean fifoConsumptionOccupied;

    @GuardedBy("offsetsLock")
    private final TreeSet<OffsetRecord> offsets;
    private final ReentrantReadWriteLock offsetsLock;

    private volatile long activityNanoTime = System.nanoTime();
    private volatile long throttleNanoTime = System.nanoTime();

    public ProcessQueueImpl(DefaultMQPushConsumerImpl consumerImpl, MessageQueue messageQueue,
                            FilterExpression filterExpression) {
        this.consumerImpl = consumerImpl;

        this.messageQueue = messageQueue;
        this.filterExpression = filterExpression;

        this.dropped = false;

        this.pendingMessages = new ArrayList<MessageExt>();
        this.pendingMessagesLock = new ReentrantReadWriteLock();

        this.inflightMessages = new ArrayList<MessageExt>();
        this.inflightMessagesLock = new ReentrantReadWriteLock();

        this.cachedMessagesBytes = new AtomicLong(0L);
        this.fifoConsumptionOccupied = new AtomicBoolean(false);

        this.offsets = new TreeSet<OffsetRecord>();
        this.offsetsLock = new ReentrantReadWriteLock();
    }

    @Override
    public void drop() {
        this.dropped = true;
    }

    private boolean fifoConsumptionInbound() {
        return fifoConsumptionOccupied.compareAndSet(false, true);
    }

    private void fifoConsumptionOutbound() {
        fifoConsumptionOccupied.compareAndSet(true, false);
    }

    @VisibleForTesting
    public void cacheMessages(List<MessageExt> messageList) {
        pendingMessagesLock.writeLock().lock();
        try {
            for (MessageExt message : messageList) {
                pendingMessages.add(message);
                cachedMessagesBytes.addAndGet(null == message.getBody() ? 0 : message.getBody().length);
                if (MessageModel.BROADCASTING.equals(consumerImpl.getMessageModel())) {
                    offsetsLock.writeLock().lock();
                    try {
                        if (1 == offsets.size()) {
                            final OffsetRecord offsetRecord = offsets.iterator().next();
                            if (offsetRecord.isRelease()) {
                                offsets.remove(offsetRecord);
                            }
                        }
                        offsets.add(new OffsetRecord(message.getQueueOffset()));
                    } finally {
                        offsetsLock.writeLock().unlock();
                    }
                }
            }
        } finally {
            pendingMessagesLock.writeLock().unlock();
        }
    }

    @Override
    public List<MessageExt> tryTakeMessages(int batchMaxSize) {
        pendingMessagesLock.writeLock().lock();
        inflightMessagesLock.writeLock().lock();
        try {
            List<MessageExt> messageExtList = new ArrayList<MessageExt>();
            final String topic = messageQueue.getTopic();
            final RateLimiter rateLimiter = consumerImpl.rateLimiter(topic);
            // no rate limiter for current topic.
            if (null == rateLimiter) {
                final int actualSize = Math.min(pendingMessages.size(), batchMaxSize);
                final List<MessageExt> subList = new ArrayList<MessageExt>(pendingMessages.subList(0, actualSize));
                messageExtList.addAll(subList);

                inflightMessages.addAll(subList);
                pendingMessages.removeAll(subList);
                return messageExtList;
            }
            // has rate limiter for current topic.
            while (pendingMessages.size() > 0 && messageExtList.size() < batchMaxSize && rateLimiter.tryAcquire()) {
                final MessageExt messageExt = pendingMessages.iterator().next();
                messageExtList.add(messageExt);

                inflightMessages.add(messageExt);
                pendingMessages.remove(messageExt);
            }
            return messageExtList;
        } finally {
            inflightMessagesLock.writeLock().unlock();
            pendingMessagesLock.writeLock().unlock();
        }
    }

    /**
     * Erase message from in-flight message list, and re-calculate the total messages body size.
     *
     * @param messageExt message to erase.
     */
    private void eraseMessage(final MessageExt messageExt) {
        final List<MessageExt> messageExtList = new ArrayList<MessageExt>();
        messageExtList.add(messageExt);
        eraseMessages(messageExtList);
    }

    /**
     * Try to take fifo message from {@link ProcessQueueImpl#pendingMessages}, and move them
     * to {@link ProcessQueueImpl#inflightMessages}. each fifo message taken from MUST be erased by
     * {@link ProcessQueueImpl#eraseFifoMessage(MessageExt, ConsumeStatus)}
     *
     * @return message which has been taken, or null if no message available
     */
    @Override
    public MessageExt tryTakeFifoMessage() {
        pendingMessagesLock.writeLock().lock();
        inflightMessagesLock.writeLock().lock();
        try {
            // no new message arrived.
            if (pendingMessages.isEmpty()) {
                return null;
            }
            // failed to lock.
            if (!fifoConsumptionInbound()) {
                log.debug("Fifo consumption task are not finished, mq={}", messageQueue);
                return null;
            }
            final String topic = messageQueue.getTopic();
            final RateLimiter rateLimiter = consumerImpl.rateLimiter(topic);
            // no rate limiter for current topic.
            if (null == rateLimiter) {
                final MessageExt first = pendingMessages.iterator().next();
                pendingMessages.remove(first);
                inflightMessages.add(first);
                return first;
            }
            // unlock cause failure of acquire the token,
            if (!rateLimiter.tryAcquire()) {
                fifoConsumptionOutbound();
                return null;
            }
            final MessageExt first = pendingMessages.iterator().next();
            pendingMessages.remove(first);
            pendingMessages.add(first);
            return first;
        } finally {
            inflightMessagesLock.writeLock().unlock();
            pendingMessagesLock.writeLock().unlock();
        }
    }

    @Override
    public void eraseFifoMessage(final MessageExt messageExt, final ConsumeStatus status) {
        statsMessageConsumptionStatus(status);

        final MessageModel messageModel = consumerImpl.getMessageModel();
        if (MessageModel.BROADCASTING.equals(messageModel)) {
            // for broadcasting mode, no need to ack message or forward it to DLQ.
            eraseMessage(messageExt);
            fifoConsumptionOutbound();
            return;
        }
        final int maxAttempts = consumerImpl.getMaxDeliveryAttempts();
        final int attempt = messageExt.getDeliveryAttempt();
        // failed to consume message but deliver attempt are not exhausted.
        if (ConsumeStatus.ERROR.equals(status) && attempt < maxAttempts) {
            final MessageImpl messageImpl = MessageAccessor.getMessageImpl(messageExt);
            final SystemAttribute systemAttribute = messageImpl.getSystemAttribute();
            // increment the delivery attempt and prepare to deliver message once again.
            systemAttribute.setDeliveryAttempt(1 + attempt);
            // try to deliver message once again.
            final long fifoConsumptionSuspendTimeMillis = consumerImpl.getFifoConsumptionSuspendTimeMillis();
            final ConsumeService consumeService = consumerImpl.getConsumeService();
            ListenableFuture<ConsumeStatus> future = consumeService
                    .consume(messageExt, fifoConsumptionSuspendTimeMillis, TimeUnit.MILLISECONDS);
            Futures.addCallback(future, new FutureCallback<ConsumeStatus>() {
                @Override
                public void onSuccess(ConsumeStatus consumeStatus) {
                    eraseFifoMessage(messageExt, consumeStatus);
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("[Bug] Exception raised while message redelivery, mq={}, messageId={}, attempt={}, "
                              + "maxAttempts={}", messageQueue, messageExt.getMsgId(), messageExt.getDeliveryAttempt(),
                              maxAttempts, t);
                }
            });
            return;
        }
        // ack message or forward it to DLQ depends on consumption status.
        ListenableFuture<Void> future = ConsumeStatus.OK.equals(status) ? ackFifoMessage(messageExt) :
                                        forwardToDeadLetterQueue(messageExt);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                eraseMessage(messageExt);
                fifoConsumptionOutbound();
            }
        }, consumerImpl.getConsumptionExecutor());
    }

    /**
     * Erase message list from in-flight message list, and re-calculate the total messages body size.
     *
     * @param messageExtList message list to erase.
     */
    private void eraseMessages(final List<MessageExt> messageExtList) {
        inflightMessagesLock.writeLock().lock();
        try {
            for (MessageExt messageExt : messageExtList) {
                if (inflightMessages.remove(messageExt)) {
                    cachedMessagesBytes.addAndGet(null == messageExt.getBody() ? 0 : -messageExt.getBody().length);
                }
            }
        } finally {
            inflightMessagesLock.writeLock().unlock();
        }
    }

    /**
     * Erase consumed message froms {@link ProcessQueueImpl#pendingMessages} and execute ack/nack.
     *
     * @param messageExtList consumed message list.
     * @param status         consume status, which indicates to ack/nack message.
     */
    @Override
    public void eraseMessages(List<MessageExt> messageExtList, ConsumeStatus status) {
        statsMessageConsumptionStatus(messageExtList.size(), status);
        eraseMessages(messageExtList);
        final MessageModel messageModel = consumerImpl.getMessageModel();
        // for broadcasting mode.
        if (MessageModel.BROADCASTING.equals(messageModel)) {
            return;
        }
        // for clustering mode.
        if (ConsumeStatus.OK == status) {
            for (MessageExt messageExt : messageExtList) {
                ackMessage(messageExt);
            }
            return;
        }
        for (MessageExt messageExt : messageExtList) {
            nackMessage(messageExt);
        }
    }

    private void onReceiveMessageResult(ReceiveMessageResult result) {
        final ReceiveStatus receiveStatus = result.getReceiveStatus();
        final List<MessageExt> messagesFound = result.getMessagesFound();
        final Endpoints endpoints = result.getEndpoints();

        switch (receiveStatus) {
            case OK:
                if (!messagesFound.isEmpty()) {
                    cacheMessages(messagesFound);
                    consumerImpl.getReceivedMessagesQuantity().getAndAdd(messagesFound.size());
                    consumerImpl.getConsumeService().dispatch();
                }
                log.debug("Receive message with OK, mq={}, endpoints={}, messages found count={}", messageQueue,
                          endpoints, messagesFound.size());
                receiveMessage();
                break;
            // fall through on purpose.
            case DEADLINE_EXCEEDED:
            case RESOURCE_EXHAUSTED:
            case NOT_FOUND:
            case DATA_CORRUPTED:
            case INTERNAL:
            default:
                log.error("Receive message with status={}, mq={}, endpoints={}, messages found count={}", receiveStatus,
                          messageQueue, endpoints, messagesFound.size());
                receiveMessageLater();
        }
    }

    private void onPullMessageResult(PullMessageResult result) {
        final PullStatus pullStatus = result.getPullStatus();
        final List<MessageExt> messagesFound = result.getMessagesFound();

        switch (pullStatus) {
            case OK:
                if (!messagesFound.isEmpty()) {
                    cacheMessages(messagesFound);
                    consumerImpl.getPulledMessagesQuantity().getAndAdd(messagesFound.size());
                    consumerImpl.getConsumeService().dispatch();
                }
                log.debug("Pull message with OK, mq={}, messages found count={}", messageQueue, messagesFound.size());
                pullMessage(result.getNextBeginOffset());
                break;
            // fall through on purpose.
            case DEADLINE_EXCEEDED:
            case RESOURCE_EXHAUSTED:
            case NOT_FOUND:
            case OUT_OF_RANGE:
            case INTERNAL:
            default:
                log.error("Pull message with status={}, mq={}, messages found status={}", pullStatus, messageQueue,
                          messagesFound.size());
                pullMessageLater(result.getNextBeginOffset());
        }
    }

    @VisibleForTesting
    public void receiveMessage() {
        if (dropped) {
            log.debug("Process queue has been dropped, no longer receive message, mq={}.", messageQueue);
            return;
        }
        if (this.throttled()) {
            log.warn("Process queue is throttled, would receive message later, mq={}.", messageQueue);
            throttleNanoTime = System.nanoTime();
            receiveMessageLater();
            return;
        }
        receiveMessageImmediately();
    }

    public void receiveMessageLater() {
        final ScheduledExecutorService scheduler = consumerImpl.getScheduler();
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    receiveMessage();
                }
            }, RECEIVE_LATER_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // should never reach here.
            log.error("[Bug] Failed to schedule receive message request", t);
            receiveMessageLater();
        }
    }

    public boolean throttled() {
        final long actualMessagesQuantity = this.cachedMessagesQuantity();
        final int cachedMessageQuantityThresholdPerQueue = consumerImpl.cachedMessagesQuantityThresholdPerQueue();
        if (cachedMessageQuantityThresholdPerQueue <= actualMessagesQuantity) {
            log.warn("Process queue total messages quantity exceeds the threshold, threshold={}, actual={}, mq={}",
                     cachedMessageQuantityThresholdPerQueue, actualMessagesQuantity, messageQueue);
            return true;
        }
        final int cachedMessagesBytesPerQueue = consumerImpl.cachedMessagesBytesThresholdPerQueue();
        final long actualCachedMessagesBytes = this.cachedMessageBytes();
        if (cachedMessagesBytesPerQueue <= actualCachedMessagesBytes) {
            log.warn("Process queue total messages memory exceeds the threshold, threshold={} bytes, actual={} bytes,"
                     + " mq={}", cachedMessagesBytesPerQueue, actualCachedMessagesBytes, messageQueue);
            return true;
        }
        return false;
    }

    @Override
    public boolean expired() {
        final long idleMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - activityNanoTime);
        if (idleMillis < MAX_IDLE_MILLIS) {
            return false;
        }

        final long throttleIdleMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - throttleNanoTime);
        if (throttleIdleMillis < MAX_IDLE_MILLIS) {
            return false;
        }

        log.warn("Process queue is idle, reception idle time={}ms, throttle idle time={}ms", idleMillis,
                 throttleIdleMillis);
        return true;
    }

    /**
     * Pull message immediately.
     *
     * <p> Actually, offset for message queue to pull from must be fetched before pull. If offset store was
     * customized, it would be read from offset store, otherwise offset would be fetched from remote according to the
     * consumption policy.
     */
    @Override
    public void pullMessageImmediately() {
        if (consumerImpl.hasCustomOffsetStore()) {
            long offset;
            try {
                offset = consumerImpl.readOffset(messageQueue);
            } catch (Throwable t) {
                log.error("Exception raised while reading offset from offset store, mq={}", messageQueue);
                // drop this pq, waiting for the next assignments scan.
                consumerImpl.dropProcessQueue(messageQueue);
                return;
            }
            pullMessage(offset);
            return;
        }

        QueryOffsetPolicy queryOffsetPolicy;
        switch (consumerImpl.getConsumeFromWhere()) {
            case BEGINNING:
                queryOffsetPolicy = QueryOffsetPolicy.BEGINNING;
                break;
            case END:
                queryOffsetPolicy = QueryOffsetPolicy.END;
                break;
            case TIMESTAMP:
            default:
                queryOffsetPolicy = QueryOffsetPolicy.TIME_POINT;
        }
        long timePoint = consumerImpl.getConsumeFromTimeMillis();
        OffsetQuery offsetQuery = new OffsetQuery(messageQueue, queryOffsetPolicy, timePoint);
        final ListenableFuture<Long> future = consumerImpl.queryOffset(offsetQuery);
        Futures.addCallback(future, new FutureCallback<Long>() {
            @Override
            public void onSuccess(Long offset) {
                log.info("Query offset successfully from remote, mq={}, offset={}", messageQueue, offset);
                pullMessage(offset);
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Exception raised while query offset to pull, mq={}", messageQueue, t);
                // drop this pq, waiting for the next assignments scan.
                consumerImpl.dropProcessQueue(messageQueue);
            }
        });
    }

    /**
     * Pull message immediately by message queue according to the given offset.
     *
     * <p> Make sure that there is no any exception thrown.
     *
     * @param offset offset for message queue to pull from.
     */
    public void pullMessageImmediately(final long offset) {
        try {
            final Endpoints endpoints = messageQueue.getPartition().getBroker().getEndpoints();
            final long maxAwaitTimeMillisPerQueue = consumerImpl.getMaxAwaitTimeMillisPerQueue();
            final int maxAwaitBatchSizePerQueue = consumerImpl.getMaxAwaitBatchSizePerQueue();
            PullMessageQuery pullMessageQuery = new PullMessageQuery(messageQueue, filterExpression, offset,
                                                                     maxAwaitBatchSizePerQueue,
                                                                     maxAwaitTimeMillisPerQueue,
                                                                     PULL_LONG_POLLING_TIMEOUT_MILLIS);

            activityNanoTime = System.nanoTime();
            final ListenableFuture<PullMessageResult> future = consumerImpl.pull(pullMessageQuery);
            Futures.addCallback(future, new FutureCallback<PullMessageResult>() {
                @Override
                public void onSuccess(PullMessageResult pullMessageResult) {
                    try {
                        ProcessQueueImpl.this.onPullMessageResult(pullMessageResult);
                    } catch (Throwable t) {
                        // should never reach here.
                        log.error("[Bug] Exception raised while handling pull result, would pull later, mq={}, "
                                  + "endpoints={}", messageQueue, endpoints, t);
                        pullMessageLater(offset);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("Exception raised while pull message, would pull later, mq={}, endpoints={}",
                              messageQueue,
                              endpoints, t);
                    pullMessageLater(offset);
                }
            });
            consumerImpl.getPullTimes().getAndIncrement();
        } catch (Throwable t) {
            log.error("Exception raised while pull message, would pull message later, mq={}", messageQueue, t);
            pullMessageLater(offset);
        }
    }

    /**
     * Pull message later by message queue according to the given offset.
     *
     * <p> Make sure that there is no any exception thrown.
     *
     * @param offset offset for message queue to pull from.
     */
    public void pullMessageLater(final long offset) {
        final ScheduledExecutorService scheduler = consumerImpl.getScheduler();
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    pullMessage(offset);
                }
            }, PULL_LATER_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // should never reach here.
            log.error("[Bug] Failed to schedule pull message request", t);
            pullMessageLater(offset);
        }
    }

    /**
     * Pull message by message queue according to the given offset.
     *
     * <p> Make sure that there is no any exception thrown.
     *
     * @param offset offset for message queue to pull from.
     */
    private void pullMessage(long offset) {
        if (dropped) {
            log.info("Process queue has been dropped, no longer pull message, mq={}.", messageQueue);
            return;
        }
        if (this.throttled()) {
            log.warn("Process queue is throttled, would pull message later, mq={}", messageQueue);
            throttleNanoTime = System.nanoTime();
            pullMessageLater(offset);
            return;
        }
        pullMessageImmediately(offset);
    }

    @Override
    public void receiveMessageImmediately() {
        try {
            final ClientManager clientManager = consumerImpl.getClientManager();
            final Endpoints endpoints = messageQueue.getPartition().getBroker().getEndpoints();
            final ReceiveMessageRequest request = wrapReceiveMessageRequest();

            activityNanoTime = System.nanoTime();
            final Metadata metadata = consumerImpl.sign();
            final ListenableFuture<ReceiveMessageResponse> future0 = clientManager.receiveMessage(
                    endpoints, metadata, request, RECEIVE_LONG_POLLING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

            final ListenableFuture<ReceiveMessageResult> future = Futures.transform(
                    future0, new Function<ReceiveMessageResponse, ReceiveMessageResult>() {
                        @Override
                        public ReceiveMessageResult apply(ReceiveMessageResponse response) {
                            return processReceiveMessageResponse(endpoints, response);
                        }
                    });

            Futures.addCallback(future, new FutureCallback<ReceiveMessageResult>() {
                @Override
                public void onSuccess(ReceiveMessageResult receiveMessageResult) {
                    try {
                        ProcessQueueImpl.this.onReceiveMessageResult(receiveMessageResult);
                    } catch (Throwable t) {
                        // should never reach here.
                        log.error("[Bug] Exception raised while handling receive result, would receive later, mq={}, "
                                  + "endpoints={}", messageQueue, endpoints, t);
                        receiveMessageLater();
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("Exception raised while message reception, would receive later, mq={}, endpoints={}",
                              messageQueue, endpoints, t);
                    receiveMessageLater();
                }
            });
            consumerImpl.getReceptionTimes().getAndIncrement();
        } catch (Throwable t) {
            log.error("Exception raised while message reception, would receive later, mq={}.", messageQueue, t);
            receiveMessageLater();
        }
    }

    private ListenableFuture<Void> ackFifoMessage(final MessageExt messageExt) {
        SettableFuture<Void> future0 = SettableFuture.create();
        ackFifoMessage(messageExt, 1, future0);
        return future0;
    }

    private void ackFifoMessage(final MessageExt messageExt, final int attempt, final SettableFuture<Void> future0) {
        final ListenableFuture<AckMessageResponse> future = ackMessage(messageExt, attempt);
        Futures.addCallback(future, new FutureCallback<AckMessageResponse>() {
            @Override
            public void onSuccess(AckMessageResponse response) {
                final Code code = Code.forNumber(response.getCommon().getStatus().getCode());
                if (Code.OK != code) {
                    ackFifoMessageLater(messageExt, 1 + attempt, future0);
                    return;
                }
                future0.set(null);
            }

            @Override
            public void onFailure(Throwable t) {
                ackFifoMessageLater(messageExt, 1 + attempt, future0);
            }
        });
    }

    private void ackFifoMessageLater(final MessageExt messageExt, final int attempt,
                                     final SettableFuture<Void> future0) {
        final String msgId = messageExt.getMsgId();
        if (dropped) {
            log.info("Process queue was dropped, give up to ack message. mq={}, messageId={}", messageQueue, msgId);
            return;
        }
        final ScheduledExecutorService scheduler = consumerImpl.getScheduler();
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    ackFifoMessage(messageExt, attempt, future0);
                }
            }, ACK_FIFO_MESSAGE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // should never reach here.
            log.error("[Bug] Failed to schedule ack fifo message request, mq={}, msgId={}.", messageQueue, msgId);
            ackFifoMessageLater(messageExt, 1 + attempt, future0);
        }
    }

    private ListenableFuture<Void> forwardToDeadLetterQueue(final MessageExt messageExt) {
        final SettableFuture<Void> future0 = SettableFuture.create();
        forwardToDeadLetterQueue(messageExt, 1, future0);
        return future0;
    }

    private void forwardToDeadLetterQueue(final MessageExt messageExt, final int attempt,
                                          final SettableFuture<Void> future0) {
        final ListenableFuture<ForwardMessageToDeadLetterQueueResponse> future =
                forwardToDeadLetterQueue(messageExt, attempt);
        Futures.addCallback(future, new FutureCallback<ForwardMessageToDeadLetterQueueResponse>() {
            @Override
            public void onSuccess(ForwardMessageToDeadLetterQueueResponse response) {
                final Code code = Code.forNumber(response.getCommon().getStatus().getCode());
                if (Code.OK != code) {
                    forwardToDeadLetterQueueLater(messageExt, 1 + attempt, future0);
                    return;
                }
                future0.set(null);
            }

            @Override
            public void onFailure(Throwable t) {
                forwardToDeadLetterQueueLater(messageExt, 1 + attempt, future0);
            }
        });
    }

    private void forwardToDeadLetterQueueLater(final MessageExt messageExt, final int attempt,
                                               final SettableFuture<Void> future0) {
        if (dropped) {
            log.info("Process queue was dropped, give up to redirect message to DLQ, mq={}, messageId={}", messageQueue,
                     messageExt.getMsgId());
            return;
        }
        final ScheduledExecutorService scheduler = consumerImpl.getScheduler();
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    forwardToDeadLetterQueue(messageExt, attempt, future0);
                }
            }, REDIRECT_FIFO_MESSAGE_TO_DLQ_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // should never reach here.
            log.error("[Bug] Failed to schedule DLQ message request.");
            forwardToDeadLetterQueueLater(messageExt, 1 + attempt, future0);
        }
    }

    private ListenableFuture<ForwardMessageToDeadLetterQueueResponse> forwardToDeadLetterQueue(
            final MessageExt messageExt, final int attempt) {
        ListenableFuture<ForwardMessageToDeadLetterQueueResponse> future;
        final String messageId = messageExt.getMsgId();
        final Endpoints endpoints = messageExt.getAckEndpoints();
        try {
            final ForwardMessageToDeadLetterQueueRequest request =
                    wrapForwardMessageToDeadLetterQueueRequest(messageExt);
            final Metadata metadata = consumerImpl.sign();
            final ClientManager clientManager = consumerImpl.getClientManager();
            final long ioTimeoutMillis = consumerImpl.getIoTimeoutMillis();
            future = clientManager.forwardMessageToDeadLetterQueue(endpoints, metadata, request, ioTimeoutMillis,
                                                                   TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            final SettableFuture<ForwardMessageToDeadLetterQueueResponse> future0 = SettableFuture.create();
            future0.setException(t);
            future = future0;
        }
        Futures.addCallback(future, new FutureCallback<ForwardMessageToDeadLetterQueueResponse>() {
            @Override
            public void onSuccess(ForwardMessageToDeadLetterQueueResponse response) {
                final Status status = response.getCommon().getStatus();
                final Code code = Code.forNumber(status.getCode());
                if (Code.OK != code) {
                    log.error("Failed to forward message to DLQ, attempt={}, messageId={}, endpoints={}, code={}, "
                              + "status message={}", attempt, messageId, endpoints, code, status.getMessage());
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Exception raised while forward message to DLQ, attempt={}, messageId={}, endpoints={}",
                          attempt, messageId, endpoints, t);
            }
        });
        return future;
    }

    public void ackMessage(MessageExt messageExt) {
        ackMessage(messageExt, 1);
    }

    public ListenableFuture<AckMessageResponse> ackMessage(final MessageExt messageExt, final int attempt) {
        ListenableFuture<AckMessageResponse> future;
        final String messageId = messageExt.getMsgId();
        final Endpoints endpoints = messageExt.getAckEndpoints();
        try {
            final AckMessageRequest request = wrapAckMessageRequest(messageExt);
            final Metadata metadata = consumerImpl.sign();
            final ClientManager clientManager = consumerImpl.getClientManager();
            final long ioTimeoutMillis = consumerImpl.getIoTimeoutMillis();
            future = clientManager.ackMessage(endpoints, metadata, request, ioTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            final SettableFuture<AckMessageResponse> future0 = SettableFuture.create();
            future0.setException(t);
            future = future0;
        }
        Futures.addCallback(future, new FutureCallback<AckMessageResponse>() {
            @Override
            public void onSuccess(AckMessageResponse response) {
                final Status status = response.getCommon().getStatus();
                final Code code = Code.forNumber(status.getCode());
                if (Code.OK != code) {
                    log.error("Failed to ACK, attempt={}, messageId={}, endpoints={}, code={}, status message={}",
                              attempt, messageId, endpoints, code, status.getMessage());
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Exception raised while ACK, attempt={}, messageId={}, endpoints={}", attempt, messageId,
                          endpoints, t);
            }
        });
        return future;
    }

    public void nackMessage(final MessageExt messageExt) {
        final String messageId = messageExt.getMsgId();
        final Endpoints endpoints = messageExt.getAckEndpoints();
        ListenableFuture<NackMessageResponse> future;
        try {
            final NackMessageRequest request = wrapNackMessageRequest(messageExt);
            final Metadata metadata = consumerImpl.sign();
            final ClientManager clientManager = consumerImpl.getClientManager();
            final long ioTimeoutMillis = consumerImpl.getIoTimeoutMillis();
            future = clientManager.nackMessage(endpoints, metadata, request, ioTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            log.error("Failed to NACK, messageId={}, endpoints={}", messageId, endpoints, t);
            return;
        }
        Futures.addCallback(future, new FutureCallback<NackMessageResponse>() {
            @Override
            public void onSuccess(NackMessageResponse response) {
                final Status status = response.getCommon().getStatus();
                final Code code = Code.forNumber(status.getCode());
                if (Code.OK != code) {
                    log.error("Failed to NACK, messageId={}, endpoints={}, code={}, status message={}", messageId,
                              endpoints, code, status.getMessage());
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Exception raised while NACK, messageId={}, endpoints={}", messageId, endpoints, t);
            }
        });
    }

    private ReceiveMessageRequest wrapReceiveMessageRequest() {
        final Broker broker = Broker.newBuilder().setName(messageQueue.getBrokerName()).build();
        final Partition partition = Partition.newBuilder()
                                             .setTopic(getTopicResource())
                                             .setId(messageQueue.getQueueId())
                                             .setBroker(broker).build();
        final int maxAwaitBatchSizePerQueue = consumerImpl.getMaxAwaitBatchSizePerQueue();
        final Duration invisibleDuration = Durations.fromMillis(consumerImpl.getConsumptionTimeoutMillis());
        final Duration awaitTimeDuration = Durations.fromMillis(consumerImpl.getMaxAwaitTimeMillisPerQueue());
        final ReceiveMessageRequest.Builder builder =
                ReceiveMessageRequest.newBuilder()
                                     .setGroup(getGroupResource())
                                     .setClientId(consumerImpl.getClientId())
                                     .setPartition(partition).setBatchSize(maxAwaitBatchSizePerQueue)
                                     .setInvisibleDuration(invisibleDuration)
                                     .setAwaitTime(awaitTimeDuration);

        switch (consumerImpl.getConsumeFromWhere()) {
            case BEGINNING:
                builder.setConsumePolicy(ConsumePolicy.PLAYBACK);
                break;
            case TIMESTAMP:
                builder.setConsumePolicy(ConsumePolicy.TARGET_TIMESTAMP);
                break;
            case END:
                builder.setConsumePolicy(ConsumePolicy.DISCARD);
                break;
            default:
                builder.setConsumePolicy(ConsumePolicy.RESUME);
        }

        final ExpressionType expressionType = filterExpression.getExpressionType();

        apache.rocketmq.v1.FilterExpression.Builder expressionBuilder =
                apache.rocketmq.v1.FilterExpression.newBuilder();

        final String expression = filterExpression.getExpression();
        expressionBuilder.setExpression(expression);
        switch (expressionType) {
            case SQL92:
                expressionBuilder.setType(FilterType.SQL);
                break;
            case TAG:
                expressionBuilder.setType(FilterType.TAG);
                break;
            default:
                log.error("Unknown filter expression type={}, expression string={}", expressionType, expression);
        }

        builder.setFilterExpression(expressionBuilder.build());
        if (MessageListenerType.ORDERLY == consumerImpl.getMessageListener().getListenerType()) {
            builder.setFifoFlag(true);
        }

        return builder.build();
    }

    private ForwardMessageToDeadLetterQueueRequest wrapForwardMessageToDeadLetterQueueRequest(MessageExt messageExt) {
        return ForwardMessageToDeadLetterQueueRequest.newBuilder()
                                                     .setGroup(getGroupResource())
                                                     .setTopic(getTopicResource())
                                                     .setClientId(consumerImpl.getClientId())
                                                     .setReceiptHandle(messageExt.getReceiptHandle())
                                                     .setMessageId(messageExt.getMsgId())
                                                     .setDeliveryAttempt(messageExt.getDeliveryAttempt())
                                                     .setMaxDeliveryAttempts(consumerImpl.getMaxDeliveryAttempts())
                                                     .build();
    }

    private Resource getGroupResource() {
        return Resource.newBuilder().setArn(consumerImpl.getArn()).setName(consumerImpl.getGroup()).build();
    }

    private Resource getTopicResource() {
        return Resource.newBuilder().setArn(consumerImpl.getArn()).setName(messageQueue.getTopic()).build();
    }

    private AckMessageRequest wrapAckMessageRequest(MessageExt messageExt) {
        return AckMessageRequest.newBuilder()
                                .setGroup(getGroupResource())
                                .setTopic(getTopicResource())
                                .setMessageId(messageExt.getMsgId())
                                .setClientId(consumerImpl.getClientId())
                                .setReceiptHandle(messageExt.getReceiptHandle())
                                .build();
    }

    private NackMessageRequest wrapNackMessageRequest(MessageExt messageExt) {
        return NackMessageRequest.newBuilder()
                                 .setGroup(getGroupResource())
                                 .setTopic(getTopicResource())
                                 .setClientId(consumerImpl.getClientId())
                                 .setReceiptHandle(messageExt.getReceiptHandle())
                                 .setMessageId(messageExt.getMsgId())
                                 .setDeliveryAttempt(messageExt.getDeliveryAttempt())
                                 .setMaxDeliveryAttempts(consumerImpl.getMaxDeliveryAttempts())
                                 .build();
    }

    // TODO: handle the case that the topic does not exist.
    public ReceiveMessageResult processReceiveMessageResponse(Endpoints endpoints,
                                                              ReceiveMessageResponse response) {
        ReceiveStatus receiveStatus;

        final Status status = response.getCommon().getStatus();
        final Code code = Code.forNumber(status.getCode());
        switch (null != code ? code : Code.UNKNOWN) {
            case OK:
                receiveStatus = ReceiveStatus.OK;
                break;
            case RESOURCE_EXHAUSTED:
                receiveStatus = ReceiveStatus.RESOURCE_EXHAUSTED;
                log.warn("Too many request in server, server endpoints={}, status message={}", endpoints,
                         status.getMessage());
                break;
            case DEADLINE_EXCEEDED:
                receiveStatus = ReceiveStatus.DEADLINE_EXCEEDED;
                log.warn("Gateway timeout, server endpoints={}, status message={}", endpoints, status.getMessage());
                break;
            default:
                receiveStatus = ReceiveStatus.INTERNAL;
                log.warn("Receive response indicated server-side error, server endpoints={}, code={}, status "
                         + "message={}", endpoints, code, status.getMessage());
        }

        List<MessageExt> msgFoundList = new ArrayList<MessageExt>();
        if (ReceiveStatus.OK == receiveStatus) {
            final List<Message> messageList = response.getMessagesList();
            for (Message message : messageList) {
                MessageImpl messageImpl;
                try {
                    messageImpl = ClientImpl.wrapMessageImpl(message);
                } catch (Throwable t) {
                    // TODO: need nack immediately.
                    continue;
                }
                messageImpl.getSystemAttribute().setAckEndpoints(endpoints);
                msgFoundList.add(new MessageExt(messageImpl));
            }
        }

        return new ReceiveMessageResult(endpoints, receiveStatus, Timestamps.toMillis(response.getDeliveryTimestamp()),
                                        Durations.toMillis(response.getInvisibleDuration()), msgFoundList);
    }

    public int cachedMessagesQuantity() {
        pendingMessagesLock.readLock().lock();
        inflightMessagesLock.readLock().lock();
        try {
            return pendingMessages.size() + inflightMessages.size();
        } finally {
            inflightMessagesLock.readLock().unlock();
            pendingMessagesLock.readLock().unlock();
        }
    }

    public int inflightMessagesQuantity() {
        inflightMessagesLock.readLock().lock();
        try {
            return inflightMessages.size();
        } finally {
            inflightMessagesLock.readLock().unlock();
        }
    }

    public long cachedMessageBytes() {
        return cachedMessagesBytes.get();
    }

    private void statsMessageConsumptionStatus(ConsumeStatus status) {
        statsMessageConsumptionStatus(1, status);
    }

    private void statsMessageConsumptionStatus(int messageSize, ConsumeStatus status) {
        if (ConsumeStatus.OK.equals(status)) {
            consumerImpl.getConsumptionOkCounter().addAndGet(messageSize);
            return;
        }
        consumerImpl.getConsumptionErrorCounter().addAndGet(messageSize);
    }
}
