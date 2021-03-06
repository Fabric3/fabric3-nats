package org.fabric3.binding.nats.runtime;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import nats.client.Message;
import nats.client.MessageHandler;
import nats.client.Nats;
import nats.client.NatsConnector;
import nats.client.Subscription;
import org.fabric3.api.binding.nats.model.NATSBinding;
import org.fabric3.api.host.Fabric3Exception;
import org.fabric3.binding.nats.provision.NATSConnectionSource;
import org.fabric3.binding.nats.provision.NATSConnectionTarget;
import org.fabric3.binding.nats.provision.NATSData;
import org.fabric3.spi.container.builder.DirectConnectionFactory;
import org.fabric3.spi.container.channel.ChannelConnection;
import org.fabric3.spi.util.Cast;
import org.oasisopen.sca.annotation.Destroy;
import org.oasisopen.sca.annotation.EagerInit;
import org.oasisopen.sca.annotation.Init;
import org.oasisopen.sca.annotation.Reference;
import org.oasisopen.sca.annotation.Service;

/**
 *
 */
@EagerInit
@Service({NATSConnectionManager.class, DirectConnectionFactory.class})
public class NATSConnectionManagerImpl implements NATSConnectionManager, DirectConnectionFactory {
    private static final List<Class<?>> TYPES = Arrays.asList(Nats.class, Subscription.class);
    private static final String DEFAULT_HOST = "nats://localhost:4222";

    private Map<URI, Holder> connections = new HashMap<>();

    @Reference
    protected ExecutorService executorService;

    @Init
    public void init() {
    }

    @Destroy
    public void destroy() {
        for (Holder holder : connections.values()) {
            holder.delegate.closeUnderyling();
        }
    }

    public List<Class<?>> getTypes() {
        return TYPES;
    }

    public Nats getNats(NATSConnectionTarget target) {
        return connections.computeIfAbsent(target.getChannelUri(), (s) -> createNats(target)).delegate;
    }

    public void release(URI channelUri, String topic) {
        release(channelUri, true);
    }

    public <T> T createDirectConsumer(Class<T> type, NATSConnectionSource source) {
        URI channelUri = source.getChannelUri();
        if (Nats.class.isAssignableFrom(type)) {
            return Cast.cast(connections.computeIfAbsent(channelUri, (s) -> createNats(source)).delegate);
        } else if (Subscription.class.isAssignableFrom(type)) {
            Nats nats = connections.computeIfAbsent(channelUri, (s) -> createNats(source)).delegate;
            String topic = source.getTopic() != null ? source.getTopic() : source.getDefaultTopic();
            Subscription subscription = nats.subscribe(topic);
            return Cast.cast(new SubscriptionWrapper(subscription, channelUri, topic, this));
        } else {
            throw new Fabric3Exception("Invalid consumer type: " + type.getName());
        }
    }

    @SuppressWarnings("unchecked")
    public void subscribe(NATSConnectionSource source, ChannelConnection connection) {
        URI channelUri = source.getChannelUri();
        String topic = source.getTopic() != null ? source.getTopic() : source.getDefaultTopic();

        Holder holder = connections.get(channelUri);

        boolean exists = holder != null;

        // only create the subscription
        Nats nats;
        if (!exists) {
            holder = createNats(source);
        } else {
            holder.counter++;
        }
        nats = holder.delegate;

        connections.put(channelUri, holder);
        FanoutHandler messageHandler = new FanoutHandler(source.getConsumerId(), connection);
        Subscription subscription = nats.subscribe(topic, messageHandler);

        // set the closeable callback
        connection.setCloseable(() -> {
            subscription.close();
            release(channelUri, topic);
        });
    }

    @SuppressWarnings("unchecked")
    public <T> Supplier<T> getConnection(URI channelUri, URI attachUri, Class<T> type, String topic) {
        // Nats is created by createDirectConsumer(..)
        final String resolvedTopic = topic != null ? topic : NATSBinding.DEFAULT_TOPIC;
        if (Nats.class.isAssignableFrom(type)) {
            return () -> Cast.cast(connections.get(channelUri).delegate);
        } else if (Subscription.class.isAssignableFrom(type)) {
            Holder holder = connections.get(channelUri);
            return () -> {
                Subscription subscription = holder.delegate.subscribe(resolvedTopic);
                return Cast.cast(new SubscriptionWrapper(subscription, channelUri, resolvedTopic, this));
            };
        } else {
            throw new Fabric3Exception("Invalid connection type: " + type.getName());
        }
    }

    public <T> Supplier<T> getConnection(URI uri, URI attachUri, Class<T> type) {
        return getConnection(uri, attachUri, type, null);
    }

    @SuppressWarnings("unchecked")
    private Holder createNats(NATSConnectionTarget target) {
        NatsConnector connector = new NatsConnector();
        NATSData natsData = target.getData();
        Nats nats = populateNats(connector, natsData);
        URI channelUri = target.getChannelUri();
        NatsWrapper wrapper = new NatsWrapper(nats, channelUri, this);
        return new Holder(wrapper);
    }

    private Holder createNats(NATSConnectionSource source) {
        NatsConnector connector = new NatsConnector();
        NATSData natsData = source.getData();
        Nats nats = populateNats(connector, natsData);
        URI channelUri = source.getChannelUri();
        NatsWrapper wrapper = new NatsWrapper(nats, channelUri, this);
        return new Holder(wrapper);
    }

    private Nats populateNats(NatsConnector connector, NATSData natsData) {
        List<String> hosts = natsData.getHosts();
        if (hosts.isEmpty()) {
            connector.addHost(DEFAULT_HOST);
        } else {
            hosts.forEach(connector::addHost);
        }
        connector.calllbackExecutor(executorService);
        connector.automaticReconnect(natsData.isAutomaticReconnect());
        connector.maxFrameSize(natsData.getMaxFrameSize());
        connector.pedantic(natsData.isPedantic());
        connector.reconnectWaitTime(natsData.getReconnectWaitTime(), TimeUnit.MILLISECONDS);
        return connector.connect();
    }

    private Nats release(URI channelUri, boolean shutdown) {
        Holder holder = connections.get(channelUri);
        if (holder == null) {
            return null;
        }
        if (--holder.counter == 0) {
            if (shutdown) {
                holder.delegate.close();
            }
            connections.remove(channelUri);
        }
        return holder.delegate;
    }

    private class Holder {
        NatsWrapper delegate;
        int counter = 1;

        public Holder(NatsWrapper delegate) {
            this.delegate = delegate;
        }

    }

    private class FanoutHandler implements MessageHandler {
        List<ConnectionPair> connections = new CopyOnWriteArrayList<>();

        public FanoutHandler(String consumerId, ChannelConnection connection) {
            this.connections.add(new ConnectionPair(consumerId, connection));
        }

        public void onMessage(Message message) {
            for (ConnectionPair pair : connections) {
                pair.connection.getEventStream().getHeadHandler().handle(message.getBody(), false);
            }
        }

    }

    private class ConnectionPair {
        String consumerId;
        ChannelConnection connection;

        public ConnectionPair(String consumerId, ChannelConnection connection) {
            this.consumerId = consumerId;
            this.connection = connection;
        }
    }
}
