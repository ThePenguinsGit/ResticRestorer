package work.penguinnet.resticrestorer.core.rabbit;

import com.google.gson.Gson;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.orchestration.RestoreRequest;
import work.penguinnet.resticrestorer.core.orchestration.RestoreService;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * Consumes {@code { "x": int, "y": int, "snapshotId": string }} messages (JSON {@code y} -> chunk Z) and
 * hands them to {@link RestoreService} (see SPEC.md M6). Runs entirely on its own thread — never touches
 * the world directly; {@code RestoreService}/{@code MinecraftBridge} marshal onto the main thread themselves.
 */
public final class RabbitConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitConsumer.class);
    private static final Gson GSON = new Gson();

    private final RestoreConfig.RabbitMqConfig config;
    private final String defaultDimensionId;
    private final RestoreService restoreService;

    private Connection connection;
    private Channel channel;

    public RabbitConsumer(RestoreConfig.RabbitMqConfig config, String defaultDimensionId, RestoreService restoreService) {
        this.config = config;
        this.defaultDimensionId = defaultDimensionId;
        this.restoreService = restoreService;
    }

    public void start() throws IOException, TimeoutException {
        if (!config.enabled()) {
            LOGGER.info("RabbitMQ trigger disabled in config; not connecting");
            return;
        }

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.host());
        factory.setPort(config.port());
        factory.setVirtualHost(config.virtualHost());
        factory.setUsername(config.username());
        factory.setPassword(config.password());

        connection = factory.newConnection("resticrestorer");
        channel = connection.createChannel();
        channel.basicQos(config.prefetch());

        DeliverCallback deliverCallback = (consumerTag, delivery) -> onDelivery(delivery.getEnvelope().getDeliveryTag(), delivery.getBody());
        CancelCallback cancelCallback = consumerTag -> LOGGER.warn("RabbitMQ consumer was cancelled: {}", consumerTag);
        channel.basicConsume(config.queue(), false, deliverCallback, cancelCallback);
        LOGGER.info("RabbitMQ consumer listening on queue '{}'", config.queue());
    }

    private void onDelivery(long deliveryTag, byte[] body) {
        try {
            RabbitRestoreMessage message = GSON.fromJson(new String(body, StandardCharsets.UTF_8), RabbitRestoreMessage.class);
            if (message == null || message.snapshotId == null || message.snapshotId.isBlank()) {
                throw new IllegalArgumentException("Message is missing a snapshotId");
            }

            RestoreRequest request = new RestoreRequest(defaultDimensionId, message.x, message.y, SnapshotRef.ofId(message.snapshotId));
            restoreService.submit(request).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    LOGGER.warn("Restore triggered by RabbitMQ message failed: {}", throwable.getMessage());
                } else if (!result.success()) {
                    LOGGER.warn("Restore triggered by RabbitMQ message failed: {}", result.message());
                }
            });
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            LOGGER.warn("Rejecting malformed RabbitMQ restore message: {}", e.getMessage());
            nack(deliveryTag);
        } catch (IOException e) {
            LOGGER.warn("Failed to ack RabbitMQ message {}: {}", deliveryTag, e.getMessage());
        }
    }

    private void nack(long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            LOGGER.warn("Failed to nack RabbitMQ message {}: {}", deliveryTag, e.getMessage());
        }
    }

    public void stop() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException | TimeoutException e) {
            LOGGER.warn("Failed to close RabbitMQ channel: {}", e.getMessage());
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to close RabbitMQ connection: {}", e.getMessage());
        }
    }

    /** Wire format: {@code { "x": int, "y": int, "snapshotId": string } } — JSON {@code y} is chunk Z. */
    private static final class RabbitRestoreMessage {
        int x;
        int y;
        String snapshotId;
    }
}
