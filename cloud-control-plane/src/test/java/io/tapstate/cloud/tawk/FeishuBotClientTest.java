package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FeishuBotClientTest {

    private static final String TOKEN_ENDPOINT = "https://accounts.feishu.cn/oauth/v3/token";
    private static final String MESSAGE_ENDPOINT = "https://open.feishu.cn/open-apis/im/v1/messages"
        + "?receive_id_type=chat_id";

    @Test
    void springInjectsProductionRestClientBuilderConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "feishu-bot-test", Map.of(
                    "tapstate.cloud.tawk.feishu.app-id", "app-id",
                    "tapstate.cloud.tawk.feishu.app-secret", "app-secret",
                    "tapstate.cloud.tawk.feishu.chat-id", "oc_tapstate_launch")));
            context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer());
            context.registerBean("restClientBuilder", RestClient.Builder.class, () -> RestClient.builder());
            context.register(FeishuBotClient.class);

            context.refresh();

            assertTrue(context.getBean(FeishuBotClient.class) != null);
        }
    }

    @Test
    void sendsAsConfiguredApplicationBotAndCachesItsAccessToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBotClient client = new FeishuBotClient(
            builder.build(), "app-id", "app-secret", "oc_tapstate_launch",
            TOKEN_ENDPOINT, MESSAGE_ENDPOINT);

        server.expect(requestTo(TOKEN_ENDPOINT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.grant_type").value("client_credentials"))
            .andExpect(jsonPath("$.client_id").value("app-id"))
            .andExpect(jsonPath("$.client_secret").value("app-secret"))
            .andRespond(withSuccess(
                "{\"code\":0,\"access_token\":\"access-token\",\"expires_in\":7200}",
                MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(MESSAGE_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(jsonPath("$.receive_id").value("oc_tapstate_launch"))
                .andExpect(jsonPath("$.msg_type").value("text"))
                .andExpect(jsonPath("$.uuid")
                    .value("tawk-b7225b34cea06052aadbc73f0abc6a7c63ff0849f343e"))
                .andExpect(jsonPath("$.content").value("{\"text\":\"notification\"}"))
                .andRespond(withSuccess(
                    "{\"code\":0,\"data\":{\"message_id\":\"om_stub\"}}",
                    MediaType.APPLICATION_JSON));
        }

        assertTrue(client.send("property-1", "event-1", "notification").delivered());
        assertTrue(client.send("property-1", "event-1", "notification").delivered());
        server.verify();
    }

    @Test
    void treatsFeishuUuidConflictAsPreviouslyDelivered() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBotClient client = new FeishuBotClient(
            builder.build(), "app-id", "app-secret", "oc_tapstate_launch",
            TOKEN_ENDPOINT, MESSAGE_ENDPOINT);

        server.expect(requestTo(TOKEN_ENDPOINT))
            .andRespond(withSuccess(
                "{\"code\":0,\"access_token\":\"access-token\",\"expires_in\":7200}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(MESSAGE_ENDPOINT))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andExpect(jsonPath("$.uuid").exists())
            .andRespond(withSuccess(
                "{\"code\":60012,\"msg\":\"Uuid unique\"}",
                MediaType.APPLICATION_JSON));

        assertTrue(client.send("property-1", "event-1", "notification").delivered());
        server.verify();
    }

    @Test
    void refusesToSendWithoutApplicationCredentialsOrChatId() {
        assertNotConfigured(" ", "app-secret", "oc_tapstate_launch");
        assertNotConfigured("app-id", " ", "oc_tapstate_launch");
        assertNotConfigured("app-id", "app-secret", " ");
    }

    private static void assertNotConfigured(String appId, String appSecret, String chatId) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBotClient client = new FeishuBotClient(
            builder.build(), appId, appSecret, chatId, TOKEN_ENDPOINT, MESSAGE_ENDPOINT);

        FeishuBotClient.DeliveryResult result = client.send("property-1", "event-1", "notification");

        assertFalse(result.delivered());
        assertEquals("application_bot_not_configured", result.failureCategory());
        server.verify();
    }
}
