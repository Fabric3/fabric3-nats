/*
 * Fabric3
 * Copyright (c) 2009-2015 Metaform Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fabric3.binding.nats.monitor;

import java.util.List;

import nats.client.Nats;
import nats.client.NatsConnector;
import org.fabric3.api.annotation.wire.Key;
import org.fabric3.monitor.spi.appender.Appender;
import org.fabric3.monitor.spi.appender.AppenderBuilder;
import org.oasisopen.sca.annotation.EagerInit;

/**
 *
 */
@EagerInit
@Key("org.fabric3.binding.nats.monitor.NATSPhysicalAppender")
public class NATSAppenderBuilder implements AppenderBuilder<NATSPhysicalAppender> {

    public Appender build(NATSPhysicalAppender physicalAppender) {
        NatsConnector connector = new NatsConnector();
        List<String> hosts = physicalAppender.getHosts();
        hosts.forEach(connector::addHost);

        String topic = physicalAppender.getTopic();

        Nats nats = connector.connect();
        return new NATSAppender(topic, nats);
    }
}
