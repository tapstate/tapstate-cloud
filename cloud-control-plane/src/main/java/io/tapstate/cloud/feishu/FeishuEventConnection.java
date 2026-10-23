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
    private boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
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

    FeishuEventConnection(Client client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("feishu-event-connection").start(() -> {
            synchronized (this) {
                if (!closed) client.start();
            }
        });
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        client.close();
        // SDK 2.8.7 close() leaves its protected executor alive. Keep this
        // compatibility cleanup pinned to that version and covered by a test.
        try {
            var field = Client.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ExecutorService) field.get(client)).shutdownNow();
        } catch (ReflectiveOperationException incompatibleSdk) {
            throw new IllegalStateException("Feishu SDK executor lifecycle contract changed", incompatibleSdk);
        }
    }
}
