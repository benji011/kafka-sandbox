package no.nav.kafka.sandbox.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

public class JsonMessageProducer<T> {

    private final ObjectMapper mapper;
    private final Map<String,Object> kafkaSettings;
    private final String topic;
    private static final Logger log = LoggerFactory.getLogger(JsonMessageProducer.class);
    private final Supplier<T> messageSupplier;
    private final Function<T, String> keyFunction;
    private final boolean nonBlockingSend;

    public JsonMessageProducer(String topic, Map<String,Object> kafkaSettings, ObjectMapper mapper,
                               Supplier<T> messageSupplier, Function<T, String> keyFunction,
                               boolean nonBlockingSend) {
        this.mapper = mapper;
        this.kafkaSettings = kafkaSettings;
        this.topic = topic;
        this.messageSupplier = messageSupplier;
        this.keyFunction = keyFunction;
        this.nonBlockingSend = nonBlockingSend;
    }

    private static KafkaProducer<String,String> initKafkaProducer(Map<String,Object> settings) {
        return new KafkaProducer<>(settings);
    }

    /**
     * Send as fast as the supplier can generate messages, until interrupted, then close producer
     */
    public void produceLoop() {
        log.info("Start producer loop");
        final KafkaProducer<String,String> kafkaProducer = initKafkaProducer(kafkaSettings);
        final SendStrategy<T> sendStrategy = nonBlockingSend ? nonBlocking(kafkaProducer) : blocking(kafkaProducer);

        while (!Thread.interrupted()) {
            try {
                T message = messageSupplier.get();
                String key = keyFunction.apply(message);

                sendStrategy.send(key, message);
            } catch (InterruptException kafkaInterrupt) {
                // Expected on shutdown from console
            } catch (Exception ex) {
                log.error("Unexpected error when sending to Kafka", ex);
            }
        }
        log.info("Closing KafkaProducer ..");
        kafkaProducer.close();
    }

    @FunctionalInterface
    interface SendStrategy<T> {
        void send(String key, T message) throws Exception;
    }

    /**
     * Non-blocking send, generally does not block, prints status in callback invoked by Kafka producer
     */
    private SendStrategy<T> nonBlocking(KafkaProducer<String,String> kafkaProducer) {
        return (String key, T message) -> {
            final String json = mapper.writeValueAsString(message);
            log.debug("Send non-blocking ..");
            kafkaProducer.send(new ProducerRecord<>(topic, key, json), (metadata, ex) -> {
                if (ex != null) {
                    log.error("Failed to send message to Kafka", ex);
                } else {
                    log.debug("Async message ack, offset: {}, timestamp: {}, topic-partition: {}-{}",
                            metadata.offset(), metadata.timestamp(), metadata.topic(), metadata.partition());
                }
            });
        };
    }

    /**
     * Blocking send strategy, waits for result of sending, then prints status and returns
     */
    private SendStrategy<T> blocking(KafkaProducer<String, String> kafkaProducer) {
        return (String key, T message) -> {
            String json = mapper.writeValueAsString(message);
            log.debug("Send blocking ..");
            Future<RecordMetadata> send = kafkaProducer.send(new ProducerRecord<>(topic, key, json));
            try {
                RecordMetadata metadata = send.get();
                log.debug("Message ack, offset: {}, timestamp: {}, topic-partition: {}-{}",
                        metadata.offset(), metadata.timestamp(), metadata.topic(), metadata.partition());
            } catch (ExecutionException exception) {
                log.error("Failed to send message to Kafka", exception.getCause());
            }
        };
    }

}