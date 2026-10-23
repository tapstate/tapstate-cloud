package io.tapstate.cloud.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Maintains an outbound event connection for the environment's dedicated Feishu application. */
@Component
@ConditionalOnProperty(name = "FEISHU_LONG_CONNECTION_ENABLED", havingValue = "true")
public class FeishuEventConnection {
    private final Client client;

    public FeishuEventConnection(FeishuInviteController commands,
            @Value("${FEISHU_BOT_APP_ID}") String appId,
            @Value("${FEISHU_BOT_APP_SECRET}") String appSecret) {
        ObjectMapper json = new ObjectMapper();
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                @Override
                public void handle(P2MessageReceiveV1 event) throws Exception {
                    var response = commands.handleVerifiedEvent(json.readTree(Jsons.DEFAULT.toJson(event)));
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new io.tapstate.cloud.common.api.BusinessException(
                            io.tapstate.cloud.common.api.AppErrorCode.SYSTEM_ERROR, 503);
                    }
                }
            }).build();
        client = new Client.Builder(appId, appSecret).eventHandler(dispatcher).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("feishu-event-connection").start(client::start);
    }

    @PreDestroy
    public void close() {
        client.close();
    }
}
