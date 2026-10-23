package io.kestra.plugin.telegram;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.property.URIFetcher;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.utils.IdUtils;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpStatus;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToObject;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test will only test the main task, this allow you to send any input
 * parameters to your task and test the returning behaviour easily.
 */
@KestraTest
class TelegramSendTest {

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private ModelValidator modelValidator;

    @BeforeEach
    @AfterEach
    void resetMock() {
        FakeTelegramController.message = null;
        FakeTelegramController.token = null;
        FakeTelegramController.documentChannel = null;
        FakeTelegramController.documentCaption = null;
        FakeTelegramController.documentParseMode = null;
        FakeTelegramController.documentFilename = null;
        FakeTelegramController.documentBytes = null;
        FakeTelegramController.customHeader = null;
        FakeTelegramController.responseOk = true;
        FakeTelegramController.responseStatus = HttpStatus.OK;
        FakeTelegramController.responseDescription = null;
    }

    @Test
    void run_withFile_shouldUploadDocumentWithoutPayload() throws Exception {
        RunContext runContext = runContextFactory.of();
        byte[] content = { 0, 1, 2, 13, 10, (byte) 0x80, (byte) 0xff };
        URI file = storeFile(runContext, "report.bin", content);

        documentTask().from(Property.ofValue(file.toString())).build().run(runContext);

        assertThat(FakeTelegramController.token, equalToObject("token"));
        assertThat(FakeTelegramController.documentChannel, equalToObject("channel"));
        assertThat(FakeTelegramController.documentFilename, equalToObject("report.bin"));
        assertArrayEquals(content, FakeTelegramController.documentBytes);
        assertNull(FakeTelegramController.documentCaption);
        assertNull(FakeTelegramController.documentParseMode);
        assertNull(FakeTelegramController.message);
    }

    @Test
    void run_withUnicodeFilename_shouldPreserveFilename() throws Exception {
        RunContext runContext = runContextFactory.of();
        byte[] content = "report".getBytes(StandardCharsets.UTF_8);
        URI file = storeFile(runContext, "résumé-报告.txt", content);

        documentTask().from(Property.ofValue(file.toString())).build().run(runContext);

        assertThat(FakeTelegramController.documentFilename, equalToObject("résumé-报告.txt"));
        assertArrayEquals(content, FakeTelegramController.documentBytes);
    }

    @Test
    void run_withFileFromPreviousOutputAndCaption_fromYaml_shouldRenderProperties() throws Exception {
        byte[] content = "name,total\nexample,42\n".getBytes(StandardCharsets.UTF_8);
        URI file = storeFile(runContextFactory.of(), "report with spaces.csv", content);
        RunContext runContext = runContextFactory.of(
            Map.of(
                "outputs", Map.of("report", Map.of("uri", file.toString())),
                "destination", "-12345",
                "caption", "<b>Résumé</b> 📄",
                "mode", "HTML"
            )
        );
        EmbeddedServer server = applicationContext.getBean(EmbeddedServer.class);
        server.start();
        String yaml = """
            endpointOverride: "%s"
            token: token
            channel: "{{ destination }}"
            from: "{{ outputs.report.uri }}"
            caption: "{{ caption }}"
            parseMode: "{{ mode }}"
            options:
              headers:
                X-Telegram-Test: "{{ destination }}"
            """.formatted(server.getURL());

        JacksonMapper.ofYaml().readValue(yaml, TelegramSend.class).run(runContext);

        assertThat(FakeTelegramController.documentChannel, equalToObject("-12345"));
        assertThat(FakeTelegramController.documentFilename, equalToObject("report with spaces.csv"));
        assertThat(FakeTelegramController.documentCaption, equalToObject("<b>Résumé</b> 📄"));
        assertThat(FakeTelegramController.documentParseMode, equalToObject("HTML"));
        assertThat(FakeTelegramController.customHeader, equalToObject("-12345"));
        assertArrayEquals(content, FakeTelegramController.documentBytes);
        assertNull(FakeTelegramController.message);
    }

    @Test
    void run_withFileAndMarkdownCaption_shouldUseCaptionInsteadOfPayload() throws Exception {
        RunContext runContext = runContextFactory.of();
        URI file = storeFile(runContext, "empty.txt", new byte[0]);

        documentTask()
            .from(Property.ofValue(file.toString()))
            .payload(Property.ofExpression("{{ unused_payload }}"))
            .caption(Property.ofValue("*Report*"))
            .parseMode(Property.ofValue(TelegramSend.ParseMode.MARKDOWNV2))
            .build().run(runContext);

        assertThat(FakeTelegramController.documentCaption, equalToObject("*Report*"));
        assertThat(FakeTelegramController.documentParseMode, equalToObject("MarkdownV2"));
        assertArrayEquals(new byte[0], FakeTelegramController.documentBytes);
        assertNull(FakeTelegramController.message);
    }

    @Test
    void run_withLegacyAlias_shouldUploadDocument() throws Exception {
        RunContext runContext = runContextFactory.of();
        byte[] content = "legacy alias".getBytes(StandardCharsets.UTF_8);
        URI file = storeFile(runContext, "legacy.txt", content);
        EmbeddedServer server = applicationContext.getBean(EmbeddedServer.class);
        server.start();
        String yaml = """
            id: send_file
            type: io.kestra.plugin.notifications.telegram.TelegramSend
            endpointOverride: "%s"
            token: token
            channel: channel
            from: "%s"
            """.formatted(server.getURL(), file);

        ((TelegramSend) JacksonMapper.ofYaml().readValue(yaml, Task.class)).run(runContext);

        assertThat(FakeTelegramController.documentFilename, equalToObject("legacy.txt"));
        assertArrayEquals(content, FakeTelegramController.documentBytes);
    }

    @Test
    void from_shouldAcceptEverySchemeSupportedByKestra() {
        // 'from' delegates resolution to URIFetcher, so it accepts internal storage,
        // namespace files and local files rather than only the kestra scheme.
        assertTrue(URIFetcher.supports(URI.create("kestra:///main/f/e/tasks/report.csv")));
        assertTrue(URIFetcher.supports(URI.create("nsfile:///telegram/report.csv")));
        assertTrue(URIFetcher.supports(URI.create("file:///tmp/report.csv")));
        assertFalse(URIFetcher.supports(URI.create("https://example.com/report.csv")));
    }

    @Test
    void run_withInvalidFile_shouldFailBeforeSending() {
        RunContext runContext = runContextFactory.of();
        for (String from : new String[] { null, "", " ", "not a uri", "https://example.com/report.txt", "s3://bucket/report.txt", "kestra:///", "kestra://host", "nsfile:///", "file:///" }) {
            TelegramSend task = documentTask().from(Property.ofValue(from)).build();

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext), "from: " + from);

            assertThat(exception.getMessage(), containsString("'from' must specify"));
            assertNull(FakeTelegramController.token);
        }
    }

    @Test
    void run_withMissingFile_shouldFailBeforeSending() {
        RunContext runContext = runContextFactory.of();
        TelegramSend task = documentTask().from(Property.ofValue("kestra:///telegram-tests/" + UUID.randomUUID() + "/missing.txt")).build();

        IOException exception = assertThrows(IOException.class, () -> task.run(runContext));

        assertThat(exception.getMessage(), containsString("Unable to read the file specified by 'from'"));
        assertNull(FakeTelegramController.token);
    }

    @Test
    void run_withoutPayloadOrFile_shouldFailBeforeSending() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> documentTask().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), equalToObject("Either 'payload' or 'from' must be provided."));
        assertNull(FakeTelegramController.token);
    }

    @Test
    void run_withDocumentApiFailure_shouldThrowSendingException() throws Exception {
        RunContext runContext = runContextFactory.of();
        URI file = storeFile(runContext, "report.txt", "report".getBytes(StandardCharsets.UTF_8));
        FakeTelegramController.responseOk = false;
        TelegramSend task = documentTask().from(Property.ofValue(file.toString())).build();

        TelegramBotApiService.ErrorSendingMessageException exception = assertThrows(
            TelegramBotApiService.ErrorSendingMessageException.class, () -> task.run(runContext)
        );

        assertThat(exception.httpStatus.getCode(), equalToObject(200));
        assertThat(FakeTelegramController.documentFilename, equalToObject("report.txt"));
    }

    @Test
    void run_withDocumentHttpFailure_shouldThrowSendingException() throws Exception {
        RunContext runContext = runContextFactory.of();
        URI file = storeFile(runContext, "report.txt", "report".getBytes(StandardCharsets.UTF_8));
        FakeTelegramController.responseStatus = HttpStatus.BAD_REQUEST;
        TelegramSend task = documentTask().from(Property.ofValue(file.toString())).build();

        TelegramBotApiService.ErrorSendingMessageException exception = assertThrows(
            TelegramBotApiService.ErrorSendingMessageException.class, () -> task.run(runContext)
        );

        assertThat(exception.httpStatus.getCode(), equalToObject(400));
        assertThat(exception.getMessage(), equalToObject("Unable to send Telegram message: HTTP 400 Bad Request"));
    }

    @Test
    void run_withDocumentHttpFailure_shouldIncludeTelegramDescription() throws Exception {
        RunContext runContext = runContextFactory.of();
        URI file = storeFile(runContext, "report.txt", "report".getBytes(StandardCharsets.UTF_8));
        FakeTelegramController.responseStatus = HttpStatus.BAD_REQUEST;
        FakeTelegramController.responseOk = false;
        FakeTelegramController.responseDescription = "Bad Request: message caption is too long";
        TelegramSend task = documentTask().from(Property.ofValue(file.toString())).caption(Property.ofValue("caption")).build();

        TelegramBotApiService.ErrorSendingMessageException exception = assertThrows(
            TelegramBotApiService.ErrorSendingMessageException.class, () -> task.run(runContext)
        );

        assertThat(exception.getMessage(), equalToObject("Unable to send Telegram message: HTTP 400 - Bad Request: message caption is too long"));
    }

    @Test
    void run_withDocumentTooLarge_shouldHintUploadLimit() throws Exception {
        RunContext runContext = runContextFactory.of();
        URI file = storeFile(runContext, "report.txt", "report".getBytes(StandardCharsets.UTF_8));
        FakeTelegramController.responseStatus = HttpStatus.REQUEST_ENTITY_TOO_LARGE;
        TelegramSend task = documentTask().from(Property.ofValue(file.toString())).build();

        TelegramBotApiService.ErrorSendingMessageException exception = assertThrows(
            TelegramBotApiService.ErrorSendingMessageException.class, () -> task.run(runContext)
        );

        assertThat(exception.httpStatus.getCode(), equalToObject(413));
        assertThat(exception.getMessage(), containsString("50 MB upload limit"));
    }

    @Test
    void run_withTextApiFailure_shouldIncludeTelegramDescription() {
        FakeTelegramController.responseOk = false;
        FakeTelegramController.responseDescription = "Bad Request: chat not found";
        TelegramSend task = documentTask().payload(Property.ofValue("Hello")).build();

        TelegramBotApiService.ErrorSendingMessageException exception = assertThrows(
            TelegramBotApiService.ErrorSendingMessageException.class, () -> task.run(runContextFactory.of())
        );

        assertThat(exception.getMessage(), containsString("Bad Request: chat not found"));
    }

    @Test
    void run_withTextApiFailure_shouldThrowSendingException() {
        FakeTelegramController.responseOk = false;
        TelegramSend task = documentTask().payload(Property.ofValue("Hello")).build();

        assertThrows(TelegramBotApiService.ErrorSendingMessageException.class, () -> task.run(runContextFactory.of()));
    }

    private TelegramSend.TelegramSendBuilder<?, ?> documentTask() {
        EmbeddedServer server = applicationContext.getBean(EmbeddedServer.class);
        server.start();
        return TelegramSend.builder()
            .endpointOverride(Property.ofValue(server.getURL().toString()))
            .token(Property.ofValue("token"))
            .channel(Property.ofValue("channel"));
    }

    private URI storeFile(RunContext runContext, String filename, byte[] content) throws IOException {
        URI uri = URI.create("kestra:///telegram-tests/" + UUID.randomUUID() + "/" + filename.replace(" ", "%20"));
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return runContext.storage().putFile(input, uri);
        }
    }

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of();

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String message = "Hello";
        String channel = "channel";
        String token = "token";

        TelegramSend task = TelegramSend.builder()
            .endpointOverride(Property.ofValue(embeddedServer.getURL().toString()))
            .token(Property.ofValue(token))
            .channel(Property.ofValue(channel))
            .payload(Property.ofValue(message))
            .build();
        task.run(runContext);

        assertThat(FakeTelegramController.token, containsString(token));
        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage(channel, message, null)));

    }

    @Test
    void run_withParseModeAsHTML_shouldSendTelegram() throws Exception {
        RunContext runContext = runContextFactory.of();

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String message = "Hello";
        String channel = "channel";
        String token = "token";
        String parseMode = TelegramSend.ParseMode.HTML.getValue();

        TelegramSend task = TelegramSend.builder()
            .endpointOverride(Property.ofValue(embeddedServer.getURL().toString()))
            .token(Property.ofValue(token))
            .channel(Property.ofValue(channel))
            .payload(Property.ofValue(message))
            .parseMode(Property.ofValue(TelegramSend.ParseMode.HTML))
            .build();
        task.run(runContext);

        assertThat(FakeTelegramController.token, containsString(token));
        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage(channel, message, parseMode)));

    }

    @Test
    void run_withParseModeAsMARKDOWNV2_shouldSendTelegram() throws Exception {
        RunContext runContext = runContextFactory.of();

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String message = "Hello";
        String channel = "channel";
        String token = "token";
        String parseMode = TelegramSend.ParseMode.MARKDOWNV2.getValue();

        TelegramSend task = TelegramSend.builder()
            .endpointOverride(Property.ofValue(embeddedServer.getURL().toString()))
            .token(Property.ofValue(token))
            .channel(Property.ofValue(channel))
            .payload(Property.ofValue(message))
            .parseMode(Property.ofValue(TelegramSend.ParseMode.MARKDOWNV2))
            .build();
        task.run(runContext);

        assertThat(FakeTelegramController.token, containsString(token));
        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage(channel, message, parseMode)));

    }

    @Test
    void run_withParseModeAsMARKDOWNV2_fromYaml_shouldSendTelegram() throws Exception {
        RunContext runContext = runContextFactory.of();

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String message = "Hello";
        String channel = "channel";
        String token = "token";

        String yaml = """
            endpointOverride: "%s"
            token: "%s"
            channel: "%s"
            payload: "%s"
            parseMode: MARKDOWNV2
            """.formatted(embeddedServer.getURL().toString(), token, channel, message);

        TelegramSend task = JacksonMapper.ofYaml().readValue(yaml, TelegramSend.class);
        task.run(runContext);

        assertThat(FakeTelegramController.token, containsString(token));
        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage(channel, message, "MarkdownV2")));
    }

    @Test
    void run_withParseModeAsHTML_fromYaml_shouldSendTelegram() throws Exception {
        RunContext runContext = runContextFactory.of();

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String message = "Hello";
        String channel = "channel";
        String token = "token";

        String yaml = """
            endpointOverride: "%s"
            token: "%s"
            channel: "%s"
            payload: "%s"
            parseMode: HTML
            """.formatted(embeddedServer.getURL().toString(), token, channel, message);

        TelegramSend task = JacksonMapper.ofYaml().readValue(yaml, TelegramSend.class);
        task.run(runContext);

        assertThat(FakeTelegramController.token, containsString(token));
        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage(channel, message, "HTML")));
    }

    @Test
    void run_withParseModeAsMarkdownV2_fromYaml_shouldThrowDescriptiveException() throws Exception {
        RunContext runContext = runContextFactory.of();

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String yaml = """
            endpointOverride: "%s"
            token: "token"
            channel: "channel"
            payload: "Hello"
            parseMode: MarkdownV2
            """.formatted(embeddedServer.getURL().toString());

        TelegramSend task = JacksonMapper.ofYaml().readValue(yaml, TelegramSend.class);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> task.run(runContext)
        );

        assertThat(
            exception.getMessage(),
            equalToObject("Invalid parseMode value 'MarkdownV2'. Valid values (case-sensitive): HTML, MARKDOWNV2")
        );
    }

    @Test
    void run_withParseModeAsExpression_shouldSendTelegram() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("mode", "MARKDOWNV2"));

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String message = "Hello";
        String channel = "channel";
        String token = "token";

        String yaml = """
            endpointOverride: "%s"
            token: "%s"
            channel: "%s"
            payload: "%s"
            parseMode: "{{ mode }}"
            """.formatted(embeddedServer.getURL().toString(), token, channel, message);

        TelegramSend task = JacksonMapper.ofYaml().readValue(yaml, TelegramSend.class);
        task.run(runContext);

        assertThat(FakeTelegramController.token, containsString(token));
        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage(channel, message, "MarkdownV2")));
    }

    @Test
    void run_withParseModeAsExpression_invalidMixedCase_shouldThrowDescriptiveException() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("mode", "MarkdownV2"));

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        String yaml = """
            endpointOverride: "%s"
            token: "token"
            channel: "channel"
            payload: "Hello"
            parseMode: "{{ mode }}"
            """.formatted(embeddedServer.getURL().toString());

        TelegramSend task = JacksonMapper.ofYaml().readValue(yaml, TelegramSend.class);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> task.run(runContext)
        );

        assertThat(
            exception.getMessage(),
            equalToObject("Invalid parseMode value 'MarkdownV2'. Valid values (case-sensitive): HTML, MARKDOWNV2")
        );
    }

    @SuppressWarnings("deprecation")
    @Test
    void flowSettingDeprecatedOptionsStillValidatesAndRuns() throws Exception {
        var message = "deprecated-options-" + IdUtils.create();
        var embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        var flow = YamlParser.parse(
            """
                id: telegram_deprecated_options
                namespace: io.kestra.tests
                tasks:
                  - id: send
                    type: io.kestra.plugin.telegram.TelegramSend
                    endpointOverride: "%s"
                    token: token
                    channel: channel
                    payload: "%s"
                    options:
                      readTimeout: PT30S
                      connectionPoolIdleTimeout: PT10S
                      maxContentLength: 1024
                """.formatted(embeddedServer.getURL(), message),
            Flow.class
        );

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));

        var runContext = runContextFactory.of();
        var task = (TelegramSend) flow.getTasks().getFirst();
        var options = task.getOptions();
        assertThat(runContext.render(options.getReadTimeout()).as(Duration.class).orElseThrow(), is(Duration.ofSeconds(30)));
        assertThat(runContext.render(options.getConnectionPoolIdleTimeout()).as(Duration.class).orElseThrow(), is(Duration.ofSeconds(10)));
        assertThat(runContext.render(options.getMaxContentLength()).as(Integer.class).orElseThrow(), is(1024));

        task.run(runContext);

        assertThat(FakeTelegramController.message, equalToObject(new TelegramBotApiService.TelegramMessage("channel", message, null)));
    }

    @Test
    void parseMode_fromString_validValues() {
        assertThat(TelegramSend.ParseMode.fromString("HTML"), equalToObject(TelegramSend.ParseMode.HTML));
        assertThat(TelegramSend.ParseMode.fromString("MARKDOWNV2"), equalToObject(TelegramSend.ParseMode.MARKDOWNV2));
        assertThat(TelegramSend.ParseMode.fromString(null), equalToObject(null));
    }

    @Test
    void parseMode_fromString_invalidMixedCase_shouldThrowDescriptiveException() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> TelegramSend.ParseMode.fromString("MarkdownV2")
        );

        assertThat(
            exception.getMessage(),
            equalToObject("Invalid parseMode value 'MarkdownV2'. Valid values (case-sensitive): HTML, MARKDOWNV2")
        );
    }

    @Test
    void parseMode_fromString_invalidValue_shouldThrowDescriptiveException() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> TelegramSend.ParseMode.fromString("INVALID")
        );

        assertThat(
            exception.getMessage(),
            equalToObject("Invalid parseMode value 'INVALID'. Valid values (case-sensitive): HTML, MARKDOWNV2")
        );
    }

    @Test
    void deserialize_parseMode_withInvalidMixedCase_shouldThrowDescriptiveException() {
        Exception exception = assertThrows(
            Exception.class,
            () -> JacksonMapper.ofYaml().readValue("\"MarkdownV2\"", TelegramSend.ParseMode.class)
        );

        assertThat(
            exception.getMessage(),
            containsString("Invalid parseMode value 'MarkdownV2'. Valid values (case-sensitive): HTML, MARKDOWNV2")
        );
    }

    @Test
    void deserialize_parseMode_withValidValues() throws Exception {
        TelegramSend.ParseMode html = JacksonMapper.ofYaml().readValue("\"HTML\"", TelegramSend.ParseMode.class);
        TelegramSend.ParseMode markdown = JacksonMapper.ofYaml().readValue("\"MARKDOWNV2\"", TelegramSend.ParseMode.class);

        assertThat(html, equalToObject(TelegramSend.ParseMode.HTML));
        assertThat(markdown, equalToObject(TelegramSend.ParseMode.MARKDOWNV2));
    }
}
