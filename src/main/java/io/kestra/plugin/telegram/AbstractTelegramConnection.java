package io.kestra.plugin.telegram;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractTelegramConnection extends Task implements RunnableTask<VoidOutput> {
    @Schema(
        title = "Configure Telegram HTTP client",
        description = "Optional HTTP client overrides for Telegram calls. Leave unset to use defaults: 5m read idle timeout, UTF-8 charset."
    )
    @PluginProperty(dynamic = true, group = "advanced")
    protected RequestOptions options;

    protected HttpConfiguration httpClientConfigurationWithOptions() throws IllegalVariableEvaluationException {
        HttpConfiguration.HttpConfigurationBuilder configuration = HttpConfiguration.builder();

        if (this.options != null) {

            configuration
                .timeout(
                    TimeoutConfiguration.builder()
                        .connectTimeout(this.options.getConnectTimeout())
                        .readIdleTimeout(this.options.getReadIdleTimeout())
                        .build()
                )
                .defaultCharset(this.options.getDefaultCharset());
        }

        return configuration.build();
    }

    protected HttpRequest.HttpRequestBuilder createRequestBuilder(
        RunContext runContext) throws IllegalVariableEvaluationException {

        HttpRequest.HttpRequestBuilder builder = HttpRequest.builder();

        if (this.options != null && this.options.getHeaders() != null) {
            Map<String, String> headers = runContext.render(this.options.getHeaders())
                .asMap(String.class, String.class);

            if (headers != null) {
                headers.forEach(builder::addHeader);
            }
        }
        return builder;
    }

    @Getter
    @Builder
    public static class RequestOptions {
        @Schema(title = "Maximum time to establish the connection before failing; unset uses the client default.")
        @PluginProperty(group = "execution")
        private final Property<Duration> connectTimeout;

        @Deprecated
        @Schema(
            title = "Maximum read duration before failing; defaults to 10s.",
            description = """
                Deprecated, this option has no effect: Kestra's HTTP client has no separate read timeout. Use `readIdleTimeout` instead.
                """
        )
        @Builder.Default
        @PluginProperty(group = "deprecated")
        private final Property<Duration> readTimeout = Property.ofValue(Duration.ofSeconds(10));

        @Schema(title = "Idle time allowed while reading before closing the connection; defaults to 5m.")
        @Builder.Default
        @PluginProperty(group = "execution")
        private final Property<Duration> readIdleTimeout = Property.ofValue(Duration.of(5, ChronoUnit.MINUTES));

        @Deprecated
        @Schema(
            title = "Idle timeout for pooled connections; defaults to 0s.",
            description = """
                Deprecated, this option has no effect: Kestra's HTTP client does not support a connection pool idle timeout.
                """
        )
        @Builder.Default
        @PluginProperty(group = "deprecated")
        private final Property<Duration> connectionPoolIdleTimeout = Property.ofValue(Duration.ofSeconds(0));

        @Deprecated
        @Schema(
            title = "Maximum response size; defaults to 10 MB.",
            description = """
                Deprecated, this option has no effect: Kestra's HTTP client does not limit the response size.
                """
        )
        @Builder.Default
        @PluginProperty(group = "deprecated")
        private final Property<Integer> maxContentLength = Property.ofValue(1024 * 1024 * 10);

        @Schema(title = "Request charset; defaults to UTF-8.")
        @Builder.Default
        @PluginProperty(group = "advanced")
        private final Property<Charset> defaultCharset = Property.ofValue(StandardCharsets.UTF_8);

        @Schema(
            title = "HTTP headers",
            description = "HTTP headers to include in the request"
        )
        @PluginProperty(group = "advanced")
        public Property<Map<String, String>> headers;
    }
}
