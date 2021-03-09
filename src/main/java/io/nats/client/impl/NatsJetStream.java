package io.nats.client.impl;

import io.nats.client.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.nats.client.impl.JsonUtils.simpleMessageBody;
import static io.nats.client.support.ApiConstants.SEQ;
import static io.nats.client.support.Validator.*;

public class NatsJetStream implements JetStream, JetStreamManagement, NatsJetStreamConstants {

    private final NatsConnection conn;
    private final String prefix;
    private final Duration requestTimeout;

    // ----------------------------------------------------------------------------------------------------
    // Create / Init
    // ----------------------------------------------------------------------------------------------------
    NatsJetStream(NatsConnection connection, JetStreamOptions jsOptions) throws IOException {
        conn = connection;
        if (jsOptions == null) {
            prefix = JetStreamOptions.DEFAULT_JS_OPTIONS.getPrefix();
            requestTimeout = JetStreamOptions.DEFAULT_JS_OPTIONS.getRequestTimeout();
        }
        else {
            prefix = jsOptions.getPrefix();
            requestTimeout = jsOptions.getRequestTimeout();
        }

        checkEnabled();
    }

    private void checkEnabled() throws IOException {
        try {
            JetStreamApiResponse jsApiResp = null;
            Message respMessage = makeRequest(JSAPI_ACCOUNT_INFO, null, requestTimeout);
            if (respMessage != null) {
                jsApiResp = new JetStreamApiResponse(respMessage);
            }
            if (jsApiResp == null) {
                throw new IllegalStateException("JetStream is not enabled.");
            }
            if (jsApiResp.getErrorCode() == 503) {
                throw new IllegalStateException(jsApiResp.getDescription());
            }

            // check the response // TODO find out why this is being done
            new NatsJetStreamAccountStats(jsApiResp.getResponse());

        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Manage
    // ----------------------------------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamInfo addStream(StreamConfiguration config) throws IOException, JetStreamApiException {
        return addOrUpdateStream(config, JSAPI_STREAM_CREATE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamInfo updateStream(StreamConfiguration config) throws IOException, JetStreamApiException {
        return addOrUpdateStream(config, JSAPI_STREAM_UPDATE);
    }

    private StreamInfo addOrUpdateStream(StreamConfiguration config, String template) throws IOException, JetStreamApiException {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null.");
        }
        String streamName = config.getName();
        if (nullOrEmpty(streamName)) {
            throw new IllegalArgumentException("Configuration must have a valid stream name");
        }

        String subj = String.format(template, streamName);
        Message resp = makeRequestResponseRequired(subj, config.toJSON().getBytes(), requestTimeout);
        return new StreamInfo(extractApiResponseThrowOnError(resp).getResponse());
    }

    @Override
    public boolean deleteStream(String streamName) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_STREAM_DELETE, streamName);
        Message resp = makeRequestResponseRequired(subj, null, requestTimeout);
        return extractApiResponseThrowOnError(resp).getSuccess();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamInfo getStreamInfo(String streamName) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_STREAM_INFO, streamName);
        Message resp = makeRequestResponseRequired(subj, null, requestTimeout);
        return new StreamInfo(extractJsonThrowOnError(resp));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PurgeResponse purgeStream(String streamName) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_STREAM_PURGE, streamName);
        Message resp = makeRequestResponseRequired(subj, null, requestTimeout);
        return new PurgeResponse(extractJsonThrowOnError(resp));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsumerInfo addOrUpdateConsumer(String streamName, ConsumerConfiguration config) throws IOException, JetStreamApiException {
        validateStreamNameRequired(streamName);
        validateNotNull(config, "Config");
        validateNotNull(config.getDurable(), "Durable");
        return addOrUpdateConsumerInternal(streamName, config);
    }

    private ConsumerInfo addOrUpdateConsumerInternal(String streamName, ConsumerConfiguration config) throws IOException, JetStreamApiException {
        String durable = config.getDurable();
        String requestJSON = config.toJSON(streamName);

        String subj;
        if (durable == null) {
            subj = String.format(JSAPI_CONSUMER_CREATE, streamName);
        } else {
            subj = String.format(JSAPI_DURABLE_CREATE, streamName, durable);
        }
        Message resp = makeRequestResponseRequired(subj, requestJSON.getBytes(), conn.getOptions().getConnectionTimeout());
        return new ConsumerInfo(extractApiResponseThrowOnError(resp).getResponse());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean deleteConsumer(String streamName, String consumer) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_CONSUMER_DELETE, streamName, consumer);
        Message resp = makeRequestResponseRequired(subj, null, requestTimeout);
        return extractApiResponseThrowOnError(resp).getSuccess();
    }

    @Override
    public ConsumerInfo getConsumerInfo(String streamName, String consumer) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_CONSUMER_INFO, streamName, consumer);
        Message resp = makeRequestResponseRequired(subj, null, requestTimeout);
        return new ConsumerInfo(extractJsonThrowOnError(resp));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getConsumerNames(String streamName) throws IOException, JetStreamApiException {
        return getConsumerNames(streamName, null);
    }

    // TODO FUTURE resurface this api publicly when server supports
    // @Override
    private List<String> getConsumerNames(String streamName, String filter) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_CONSUMER_NAMES, streamName);

        ConsumerNamesResponse cnr = new ConsumerNamesResponse();
        while (cnr.hasMore()) {
            Message resp = makeRequestResponseRequired(subj, cnr.nextJson(filter), requestTimeout);
            cnr.add(extractJsonThrowOnError(resp));
        }

        return cnr.getConsumers();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConsumerInfo> getConsumers(String streamName) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_CONSUMER_LIST, streamName);

        ConsumerListResponse cir = new ConsumerListResponse();
        while (cir.hasMore()) {
            Message resp = makeRequestResponseRequired(subj, cir.nextJson(), requestTimeout);
            cir.add(extractJsonThrowOnError(resp));
        }

        return cir.getConsumers();
    }

    @Override
    public List<String> getStreamNames() throws IOException, JetStreamApiException {
        StreamNamesResponse snr = new StreamNamesResponse();
        while (snr.hasMore()) {
            Message resp = makeRequestResponseRequired(JSAPI_STREAMS, snr.nextJson(), requestTimeout);
            snr.add(extractJsonThrowOnError(resp));
        }

        return snr.getStreams();
    }

    @Override
    public List<StreamInfo> getStreams() throws IOException, JetStreamApiException {
        StreamListResponse sir = new StreamListResponse();
        while (sir.hasMore()) {
            Message resp = makeRequestResponseRequired(JSAPI_STREAM_LIST, sir.nextJson(), requestTimeout);
            sir.add(extractJsonThrowOnError(resp));
        }

        return sir.getStreams();
    }

    @Override
    public MessageInfo getMessage(String streamName, long seq) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_MSG_GET, streamName);
        Message resp = makeRequestResponseRequired(subj, simpleMessageBody(SEQ, seq), requestTimeout);
        return new MessageInfo(extractJsonThrowOnError(resp));
    }

    @Override
    public boolean deleteMessage(String streamName, long seq) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_MSG_DELETE, streamName);
        Message resp = makeRequestResponseRequired(subj, simpleMessageBody(SEQ, seq), requestTimeout);
        return extractApiResponseThrowOnError(resp).getSuccess();
    }

    // ----------------------------------------------------------------------------------------------------
    // Publish
    // ---------------------------------------------------------------------------------------------NatsJsPullSub-------
    /**
     * {@inheritDoc}
     */
    @Override
    public PublishAck publish(String subject, byte[] body) throws IOException, JetStreamApiException {
        return publishSync(subject, null, null, body, conn.getOptions().supportUTF8Subjects(), null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PublishAck publish(String subject, byte[] body, PublishOptions options) throws IOException, JetStreamApiException {
        return publishSync(subject, null, null, body, conn.getOptions().supportUTF8Subjects(), options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PublishAck publish(Message message) throws IOException, JetStreamApiException {
        validateNotNull(message, "Message");
        return publishSync(message.getSubject(), message.getReplyTo(), message.getHeaders(), message.getData(), message.isUtf8mode(), null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PublishAck publish(Message message, PublishOptions options) throws IOException, JetStreamApiException {
        validateNotNull(message, "Message");
        return publishSync(message.getSubject(), message.getReplyTo(), message.getHeaders(), message.getData(), message.isUtf8mode(), options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<PublishAck> publishAsync(String subject, byte[] body) {
        return publishAsync(subject, null, null, body, conn.getOptions().supportUTF8Subjects(), null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<PublishAck> publishAsync(String subject, byte[] body, PublishOptions options) {
        return publishAsync(subject, null, null, body, conn.getOptions().supportUTF8Subjects(), options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<PublishAck> publishAsync(Message message) {
        validateNotNull(message, "Message");
        return publishAsync(message.getSubject(), message.getReplyTo(), message.getHeaders(), message.getData(), message.isUtf8mode(), null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<PublishAck> publishAsync(Message message, PublishOptions options) {
        validateNotNull(message, "Message");
        return publishAsync(message.getSubject(), message.getReplyTo(), message.getHeaders(), message.getData(), message.isUtf8mode(), options);
    }

    private CompletableFuture<PublishAck> publishAsync(String subject, String replyTo, Headers headers, byte[] data, boolean utf8mode, PublishOptions options) {
        Headers merged = mergePublishOptions(headers, options);
        CompletableFuture<Message> future = conn.request(subject, replyTo, merged, data, utf8mode)
                .exceptionally(e -> null);

        return future.thenCompose(resp -> {
            try {
                if (resp == null) {
                    throw new IOException("Invalid publish or timeout / no response waiting for NATS JetStream server");
                }
                return CompletableFuture.completedFuture(processAck(resp, options));
            } catch (IOException | JetStreamApiException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private PublishAck publishSync(String subject, String replyTo, Headers headers, byte[] data, boolean utf8mode, PublishOptions options) throws IOException, JetStreamApiException {
        Duration timeout = options == null ? requestTimeout : options.getStreamTimeout();
        Headers merged = mergePublishOptions(headers, options);
        Message resp = makeRequestResponseRequired(subject, replyTo, merged, data, utf8mode, timeout);
        return processAck(resp, options);
    }

    private NatsPublishAck processAck(Message resp, PublishOptions options) throws IOException, JetStreamApiException {
        NatsPublishAck ack = new NatsPublishAck(resp.getData());

        String ackStream = ack.getStream();
        if (ackStream == null || ackStream.length() == 0 || ack.getSeqno() == 0) {
            throw new IOException("Invalid JetStream ack.");
        }

        String pubStream = options == null ? null : options.getStream();
        if (isStreamSpecified(pubStream) && !pubStream.equals(ackStream)) {
            throw new IOException("Expected ack from stream " + pubStream + ", received from: " + ackStream);
        }

        return ack;
    }

    private Headers mergePublishOptions(Headers headers, PublishOptions options) {
        Headers piHeaders;

        if (options == null) {
            piHeaders = headers == null ? null : new Headers(headers);
        }
        else {
            piHeaders = new Headers(headers);

            // we know no headers are set with default options
            long seqno = options.getExpectedLastSequence();
            if (seqno > 0) {
                piHeaders.add(EXPECTED_LAST_SEQ_HDR, Long.toString(seqno));
            }

            String s = options.getExpectedLastMsgId();
            if (s != null) {
                piHeaders.add(EXPECTED_LAST_MSG_ID_HDR, s);
            }

            s = options.getExpectedStream();
            if (s != null) {
                piHeaders.add(EXPECTED_STREAM_HDR, s);
            }

            s = options.getMessageId();
            if (s != null) {
                piHeaders.add(MSG_ID_HDR, s);
            }
        }
        return piHeaders;
    }

    private boolean isStreamSpecified(String streamName) {
        return streamName != null;
    }

    // ----------------------------------------------------------------------------------------------------
    // Subscribe
    // ----------------------------------------------------------------------------------------------------
    NatsJetStreamSubscription createSubscription(String subject, String queueName,
                                                 NatsDispatcher dispatcher, MessageHandler handler, boolean autoAck,
                                                 PushSubscribeOptions pushSubscribeOptions,
                                                 PullSubscribeOptions pullSubscribeOptions) throws IOException, JetStreamApiException {
        // first things first...
        boolean isPullMode = pullSubscribeOptions != null;

        // setup the configuration, use a default.
        String stream;
        ConsumerConfiguration.Builder ccBuilder;
        SubscribeOptions so;

        if (isPullMode) {
            so = pullSubscribeOptions;
            stream = pullSubscribeOptions.getStream();
            ccBuilder = ConsumerConfiguration.builder(pullSubscribeOptions.getConsumerConfiguration());
            ccBuilder.deliverSubject(null); // pull mode can't have a deliver subject
        }
        else {
            so = pushSubscribeOptions == null
                    ? PushSubscribeOptions.builder().build()
                    : pushSubscribeOptions;
            stream = so.getStream(); // might be null, that's ok
            ccBuilder = ConsumerConfiguration.builder(so.getConsumerConfiguration());
        }

        String durable = ccBuilder.getDurable();
        String inbox = ccBuilder.getDeliverSubject();

        boolean createConsumer = true;

        // 1. Did they tell me what stream? No? look it up
        if (stream == null) {
            stream = lookupStreamBySubject(subject);
        }

        // 2. Is this a durable or ephemeral
        if (durable != null) {
            ConsumerInfo consumerInfo =
                    lookupConsumerInfo(stream, durable);

            if (consumerInfo != null) { // consumer for that durable already exists
                createConsumer = false;
                ConsumerConfiguration cc = consumerInfo.getConsumerConfiguration();

                // Make sure the subject matches or is a subset...
                String filterSub = cc.getFilterSubject();
                if (filterSub != null && !filterSub.equals(subject)) {
                    throw new IllegalArgumentException(
                            String.format("Subject %s mismatches consumer configuration %s.", subject, filterSub));
                }

                // use the deliver subject as the inbox. It may be null, that's ok
                inbox = cc.getDeliverSubject();
            }
        }

        // 3. If no deliver subject (inbox) provided or found, make an inbox.
        if (inbox == null) {
            inbox = conn.createInbox();
        }

        // 4. create the subscription
        NatsJetStreamSubscription sub;
        if (dispatcher == null) {
            sub = (NatsJetStreamSubscription) conn.createSubscription(inbox, queueName, null, true);
        }
        else {
            MessageHandler mh;
            if (autoAck) {
                mh = new AutoAckMessageHandler(handler);
            } else {
                mh = handler;
            }
            sub = (NatsJetStreamSubscription) dispatcher.subscribeImpl(inbox, queueName, mh, true);
        }

        // 5-Consumer didn't exist. It's either ephemeral or a durable that didn't already exist.
        if (createConsumer) {
            // Defaults should set the right ack pending.
            // if we have acks and the maxAckPending is not set, set it
            // to the internal Max.
            // TODO: too high value?
            if (ccBuilder.getMaxAckPending() == 0) {
                ccBuilder.maxAckPending(sub.getPendingMessageLimit());
            }

            // Pull mode doesn't maintain a deliver subject. It's actually an error if we send it.
            if (!isPullMode) {
                ccBuilder.deliverSubject(inbox);
            }

            // being discussed if this is correct, but leave it for now.
            ccBuilder.filterSubject(subject);

            // createOrUpdateConsumer can fail for security reasons, maybe other reasons?
            ConsumerInfo ci;
            try {
                ci = addOrUpdateConsumerInternal(stream, ccBuilder.build());
            } catch (JetStreamApiException e) {
                sub.unsubscribe();
                throw e;
            }
            sub.setupJetStream(this, ci.getName(), ci.getStreamName(), inbox, so);
        }
        // 5-Consumer did exist.
        else {
            sub.setupJetStream(this, durable, stream, inbox, so);
        }

        return sub;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JetStreamSubscription subscribe(String subject) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        return createSubscription(subject, null, null, null, false, null, null);
    }

    @Override
    public JetStreamSubscription subscribe(String subject, PushSubscribeOptions options) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        return createSubscription(subject, null, null, null, false, options, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JetStreamSubscription subscribe(String subject, String queue, PushSubscribeOptions options) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        validateQueueNameRequired(queue);
        return createSubscription(subject, queue, null, null, false, options, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JetStreamSubscription subscribe(String subject, Dispatcher dispatcher, MessageHandler handler, boolean autoAck) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        validateNotNull(dispatcher, "Dispatcher");
        validateNotNull(handler, "Handler");
        return createSubscription(subject, null, (NatsDispatcher) dispatcher, handler, autoAck, null, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JetStreamSubscription subscribe(String subject, Dispatcher dispatcher, MessageHandler handler, boolean autoAck, PushSubscribeOptions options) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        validateNotNull(dispatcher, "Dispatcher");
        validateNotNull(handler, "Handler");
        return createSubscription(subject, null, (NatsDispatcher) dispatcher, handler, autoAck, options, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JetStreamSubscription subscribe(String subject, String queue, Dispatcher dispatcher, MessageHandler handler, boolean autoAck, PushSubscribeOptions options) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        validateQueueNameRequired(queue);
        validateNotNull(dispatcher, "Dispatcher");
        validateNotNull(handler, "Handler");
        return createSubscription(subject, queue, (NatsDispatcher) dispatcher, handler, autoAck, options, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JetStreamSubscription subscribe(String subject, PullSubscribeOptions options) throws IOException, JetStreamApiException {
        validateJsSubscribeSubjectRequired(subject);
        validateNotNull(options, "Options");
        validateNotNull(options.getDurable(), "Durable");
        return createSubscription(subject, null, null, null, false, null, options);
    }

    // ----------------------------------------------------------------------------------------------------
    // General Utils
    // ----------------------------------------------------------------------------------------------------
    ConsumerInfo lookupConsumerInfo(String stream, String consumer) throws IOException, JetStreamApiException {
        try {
            return getConsumerInfo(stream, consumer);
        }
        catch (JetStreamApiException e) {
            if (e.getErrorCode() == 404 && e.getErrorDescription().contains("consumer")) {
                return null;
            }
            throw e;
        }
    }

    private String lookupStreamBySubject(String subject) throws IOException, JetStreamApiException {
        String streamRequest = String.format("{\"subject\":\"%s\"}", subject);

        Message resp = makeRequestResponseRequired(JSAPI_STREAMS, streamRequest.getBytes(), requestTimeout);

        String[] streams = JsonUtils.getStringArray("streams", extractJsonThrowOnError(resp));
        if (streams.length != 1) {
            throw new IllegalStateException("No matching streams for subject: " + subject);
        }
        return streams[0];
    }

    private static class AutoAckMessageHandler implements MessageHandler {
        MessageHandler userMH;

        // caller must ensure userMH is not null
        AutoAckMessageHandler(MessageHandler userMH) {
            this.userMH = userMH;
        }

        @Override
        public void onMessage(Message msg) throws InterruptedException {
            try  {
                userMH.onMessage(msg);
                msg.ack();
            } catch (Exception e) {
                // TODO ignore??  schedule async error?
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Request Utils
    // ----------------------------------------------------------------------------------------------------
    private Message makeRequest(String subject, byte[] bytes, Duration timeout) throws InterruptedException {
        return conn.request(prependPrefix(subject), bytes, timeout);
    }

    private Message makeRequestResponseRequired(String subject, byte[] bytes, Duration timeout) throws IOException {
        try {
            return responseRequired(conn.request(prependPrefix(subject), bytes, timeout));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private Message makeRequestResponseRequired(String subject, String replyTo, Headers headers, byte[] data, boolean utf8mode, Duration timeout) throws IOException {
        try {
            return responseRequired(conn.request(subject, replyTo, headers, data, utf8mode, timeout));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private Message responseRequired(Message respMessage) throws IOException {
        if (respMessage == null) {
            throw new IOException("Timeout or no response waiting for NATS JetStream server");
        }
        return respMessage;
    }

    private String extractJsonThrowOnError(Message resp) throws JetStreamApiException {
        return extractApiResponseThrowOnError(resp).getResponse();
    }

    private JetStreamApiResponse extractApiResponseThrowOnError(Message respMessage) throws JetStreamApiException {
        JetStreamApiResponse jsApiResp = extractApiResponse(respMessage);
        if (jsApiResp.hasError()) {
            throw new JetStreamApiException(jsApiResp);
        }
        return jsApiResp;
    }

    private JetStreamApiResponse extractApiResponse(Message respMessage) {
        return new JetStreamApiResponse(respMessage);
    }

    String prependPrefix(String subject) {
        return prefix + subject;
    }

    Duration getRequestTimeout() {
        return requestTimeout;
    }
}
