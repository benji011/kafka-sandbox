package no.nav.kafka.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import no.nav.kafka.sandbox.admin.TopicAdmin;
import no.nav.kafka.sandbox.consumer.JsonMessageConsumer;
import no.nav.kafka.sandbox.producer.JsonMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A simple Kafka Java producer/consumer demo app with minimized set of dependencies.
 *
 * <p>Purpose:</p>
 * <ol>
 *     <li>Get quickly up and running with Kafka using standard Java Kafka client.</li>
 *     <li>Experiment with the settings to learn and understand behaviour.</li>
 *     <li>Experiment with the console clients to learn about communication patterns possible with Kafka, and
 *     how topic partitions and consumer groups work in practice.</li>
 *     <li>Easily modify and re-run code in the experimentation process.</li>
 *     <li>Create unit/integration tests that use Kafka.</li>
 * </ol>
 */
public class Bootstrap {

    final static String DEFAULT_BROKER = "localhost:9092";
    final static String MEASUREMENTS_TOPIC = "measurements";
    final static String MESSAGES_TOPIC = "messages";
    final static String SEQUENCE_TOPIC = "sequence";
    final static String CONSUMER_GROUP_DEFAULT = "console";

    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String...a) {
        final LinkedList<String> args = new LinkedList(Arrays.asList(a));

        if (args.isEmpty() || args.get(0).isBlank() || "-h".equals(args.get(0)) || "--help".equals(args.get(0))) {
            System.err.println("Use: 'producer [TOPIC [P]]' or 'consumer [TOPIC [GROUP]]'");
            System.err.println("Use: 'console-message-producer [TOPIC [P]]' or 'console-message-consumer [TOPIC [GROUP]]'");
            System.err.println("Use: 'sequence-producer [TOPIC [P]]' or 'sequence-consumer [TOPIC [GROUP]]'");
            System.err.println("Use: 'newtopic TOPIC [N]' to create a topic with N partitions (default 1).");
            System.err.println("Use: 'deltopic TOPIC' to delete a topic.");
            System.err.println("Default topic is chosen according to consumer/producer type.");
            System.err.println("Default consumer group is '"+ CONSUMER_GROUP_DEFAULT + "'");
            System.err.println("Kafka broker is " + DEFAULT_BROKER);
            System.exit(1);
        }

        try {
            switch (args.remove()) {
                case "newtopic":
                    newTopic(args);
                    break;

                case "deltopic":
                    deleteTopic(args);
                    break;

                case "producer":
                    measurementProducer(args);
                    break;

                case "consumer":
                    measurementConsumer(args);
                    break;

                case "sequence-producer":
                    sequenceProducer(args);
                    break;

                case "sequence-consumer":
                    sequenceValidatorConsumer(args);
                    break;

                case "console-message-producer":
                    consoleMessageProducer(args);
                    break;

                case "console-message-consumer":
                    consoleMessageConsumer(args);
                    break;

                default:
                    System.err.println("Invalid mode");
                    System.exit(1);
            }
        } catch (IllegalArgumentException | NoSuchElementException e) {
            System.err.println("Bad syntax");
            System.exit(1);
        }
    }

    private static void newTopic(Queue<String> args) {
        try (TopicAdmin ta = new TopicAdmin(DEFAULT_BROKER)) {
            String topic = args.remove();
            int partitions = args.isEmpty() ? 1 : Integer.parseInt(args.remove());
            ta.create(topic, partitions);
            LOG.info("New topic '{}' created with {} partitions.", topic, partitions);
        } catch (Exception e) {
            System.err.println("Failed: "+ e.getMessage());
        }
    }

    private static void deleteTopic(Queue<String> args) {
        try (TopicAdmin ta = new TopicAdmin(DEFAULT_BROKER)) {
            String topic = args.remove();
            ta.delete(topic);
            LOG.info("Delete topic '{}'", topic);
        } catch (Exception e) {
            System.err.println("Failed: "+ e.getMessage());
        }
    }

    private static void measurementProducer(Queue<String> args) {
        String topic = args.isEmpty() ? MEASUREMENTS_TOPIC : args.remove();
        Integer partition = args.isEmpty() ? null : Integer.parseInt(args.remove());
        producer(topic, partition, Measurements::acquireTemperatureSensorMeasurement, m -> m.getDeviceId());
    }

    private static void measurementConsumer(Queue<String> args) {
        String topic = args.isEmpty() ? MEASUREMENTS_TOPIC : args.remove();
        String group = args.isEmpty() ? CONSUMER_GROUP_DEFAULT : args.remove();
        consumer(topic, group, Measurements.SensorEvent.class, Measurements::sensorEventToConsole);
    }

    private static void consoleMessageProducer(Queue<String> args) {
        String topic = args.isEmpty() ? MESSAGES_TOPIC : args.remove();
        Integer partition = args.isEmpty() ? null : Integer.parseInt(args.remove());
        producer(topic, partition, ConsoleMessages.consoleMessageSupplier(), m -> m.senderId);
    }

    private static void consoleMessageConsumer(Queue<String> args) {
        String topic = args.isEmpty() ? MESSAGES_TOPIC : args.remove();
        String group = args.isEmpty() ? CONSUMER_GROUP_DEFAULT : args.remove();
        consumer(topic, group, ConsoleMessages.Message.class, ConsoleMessages.consoleMessageConsumer());
    }

    private static void sequenceProducer(Queue<String> args) {
        String topic = args.isEmpty() ? SEQUENCE_TOPIC : args.remove();
        Integer partition = args.isEmpty() ? null : Integer.parseInt(args.remove());
        producer(topic, partition, SequenceValidation.sequenceSupplier(
                new File("target/sequence-producer.state"), 1, TimeUnit.SECONDS), m -> null);
    }

    private static void sequenceValidatorConsumer(Queue<String> args) {
        String topic = args.isEmpty() ? SEQUENCE_TOPIC : args.remove();
        String group = args.isEmpty() ? CONSUMER_GROUP_DEFAULT : args.remove();
        consumer(topic, group, Long.class, SequenceValidation.sequenceValidator());
    }

    private static <M> void producer(String topic, Integer partition, Supplier<M> messageSupplier, Function<M, String> keyFunction) {
        LOG.info("New producer with PID " + obtainPid());
        JsonMessageProducer<M> producer = new JsonMessageProducer<>(topic, partition, KafkaConfig.kafkaProducerProps(), objectMapper(),
                messageSupplier, keyFunction, true);

        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            main.interrupt();
            try { main.join(2000); } catch (InterruptedException ie){ }
        }));

        producer.produceLoop();
    }

    private static <M> void consumer(String topic, String group, Class<M> messageType, Consumer<M> messageHandler) {
        LOG.info("New consumer with PID " + obtainPid());
        JsonMessageConsumer<M> consumer =
                new JsonMessageConsumer(topic, messageType, KafkaConfig.kafkaConsumerProps(group),
                        objectMapper(), messageHandler);
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Cannot directly close KafkaConsumer in shutdown hook,
            // since this code runs in another thread, and KafkaConsumer complains loudly if accessed by multiple threads
            main.interrupt();
            try { main.join(2000); } catch (InterruptedException ie){ }
        }));

        consumer.consumeLoop();
    }

    static long obtainPid() {
        return ProcessHandle.current().pid();
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

}
