package org.fabric3.binding.nats.generator;

import java.net.URI;
import java.util.Map;

import org.fabric3.api.annotation.wire.Key;
import org.fabric3.api.binding.nats.model.NATSBinding;
import org.fabric3.binding.nats.provision.NATSConnectionSource;
import org.fabric3.binding.nats.provision.NATSConnectionTarget;
import org.fabric3.spi.domain.generator.channel.ConnectionBindingGenerator;
import org.fabric3.spi.model.instance.LogicalBinding;
import org.fabric3.spi.model.instance.LogicalConsumer;
import org.fabric3.spi.model.instance.LogicalProducer;
import org.fabric3.spi.model.physical.DeliveryType;
import org.fabric3.spi.model.physical.PhysicalConnectionSource;
import org.fabric3.spi.model.physical.PhysicalConnectionTarget;
import org.oasisopen.sca.annotation.EagerInit;

/**
 *
 */
@EagerInit
@Key("org.fabric3.api.binding.nats.model.NATSBinding")
public class NATSConnectionBindingGenerator implements ConnectionBindingGenerator<NATSBinding> {

    public PhysicalConnectionSource generateConnectionSource(LogicalConsumer consumer, LogicalBinding<NATSBinding> binding, DeliveryType deliveryType) {
        NATSBinding natsBinding = binding.getDefinition();
        URI channelUri = binding.getParent().getUri();
        URI consumerUri = consumer.getUri();
        String defaultTopic = natsBinding.getDefaultTopic();
        String deserializer = natsBinding.getDeserializer();
        Map<String, Object> configuration = natsBinding.getConfiguration();
        return new NATSConnectionSource(channelUri, consumerUri, defaultTopic, deserializer, configuration);

    }

    public PhysicalConnectionTarget generateConnectionTarget(LogicalProducer producer, LogicalBinding<NATSBinding> binding, DeliveryType deliveryType) {
        NATSBinding natsBinding = binding.getDefinition();
        String serializer = natsBinding.getSerializer();
        Map<String, Object> configuration = natsBinding.getConfiguration();
        URI channelUri = binding.getParent().getUri();
        String defaultTopic = natsBinding.getDefaultTopic();
        return new NATSConnectionTarget(channelUri, defaultTopic, serializer, configuration);
    }
}
