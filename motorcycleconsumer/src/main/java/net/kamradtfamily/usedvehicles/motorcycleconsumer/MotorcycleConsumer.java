/*
 * The MIT License
 *
 * Copyright 2021 randalkamradt.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.kamradtfamily.usedvehicles.motorcycleconsumer;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.ReactiveCollection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.rabbitmq.client.ConnectionFactory;
import io.github.rkamradt.possibly.PossiblyFunction;
import java.time.Duration;
import net.kamradtfamily.usedvehicles.commonobjects.ContextLogging;
import net.kamradtfamily.usedvehicles.commonobjects.Vehicle;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.util.function.Tuples;

/**
 *
 * @author randalkamradt
 */
public class MotorcycleConsumer {
    private static final String MOTORCYCLE_QUEUE_NAME = "motorcycle-queue";
    private static final String HOST_NAME = "localhost";
    private static final int PORT = 5672;
    private static final String USER_NAME = "guest";
    private static final String PASSWORD = "guest";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static final ObjectReader motorcycleReader = objectMapper.readerFor(Payload.class);
    static final Cluster cluster = Cluster.connect("127.0.0.1", "admin", "admin123");
    static final Bucket bucket = cluster.bucket("po");
    static final Collection collection = bucket.defaultCollection();
    static final ReactiveCollection reactiveCollection = collection.reactive();
    
    public static void consume() {
        ConnectionFactory cfactory = new ConnectionFactory();
        cfactory.setHost(HOST_NAME);
        cfactory.setPort(PORT);
        cfactory.setUsername(USER_NAME);
        cfactory.setPassword(PASSWORD);
        SenderOptions soptions = new SenderOptions()
                .connectionFactory(cfactory);
        Sender sender = RabbitFlux.createSender(soptions);
        ReceiverOptions roptions = new ReceiverOptions()
                .connectionFactory(cfactory);
        Receiver motorcycleReceiver = RabbitFlux.createReceiver(roptions);
        motorcycleReceiver
            .consumeAutoAck(MOTORCYCLE_QUEUE_NAME)
            .onErrorStop()
            .doFinally((s) -> {
                ContextLogging.log("Motorcycle consumer in finally for signal " + s);
                motorcycleReceiver.close();
                sender.close();
            })
            .map(PossiblyFunction.of(d -> motorcycleReader.readValue(new String(d.getBody()))))
            .map(p -> p.exceptional() 
                    ? new Payload("unknown event id", new Vehicle.Motorcycle())
                    : p.getValue().get())
            .cast(Payload.class)
            .map(c -> Tuples.of(ContextLogging.builder()
                    .eventId(c.eventId)
                    .serviceName("MotorcycleConsumer")
                    .build(),new Vehicle.Motorcycle(c.motorcycle.getPo(),"motorcycle lot a")))
            .flatMap(v -> reactiveCollection
                    .get(v.getT2().getPo().getId())
                    .doOnNext(j -> ContextLogging.log(v.getT1(), "po for motorcycle " + v.getT2().getPo().getId() + " confirmed"))
                    .map(j -> v)
                    .single()
                    .onErrorReturn(v))
            .map(c -> Tuples.of(c.getT1(),
                    new Vehicle.Motorcycle(c.getT2().getPo(),"motorcycle lot a")))
            .subscribe(v -> ContextLogging.log(v.getT1(), "received motorcycle " + v));
                
    }
    
    public static class Payload {
        public Payload() {}
        public Payload(String eventId, Vehicle.Motorcycle motorcycle) {
            this.eventId = eventId;
            this.motorcycle = motorcycle;
        }
        public String eventId;
        public Vehicle.Motorcycle motorcycle;
    }
       
}
