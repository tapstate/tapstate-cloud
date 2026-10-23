package io.tapstate.cloud.feishu;

import static org.junit.jupiter.api.Assertions.*;
import com.lark.oapi.ws.Client;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FeishuEventConnectionTest {
    @Test void startAfterShutdownDoesNotReopenConnection() throws Exception {
        Client sdk = org.mockito.Mockito.mock(Client.class);
        var field = Client.class.getDeclaredField("executor");
        field.setAccessible(true);
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        field.set(sdk, workers);
        var connection = new FeishuEventConnection(sdk);
        connection.close();
        connection.start();
        org.mockito.Mockito.verify(sdk, org.mockito.Mockito.after(100).never()).start();
        assertTrue(workers.isShutdown());
    }

    @Test void shutdownTerminatesSdkWorkersWithoutOpeningConnection() throws Exception {
        Client sdk = new Client.Builder("unused", "unused").build();
        var field = Client.class.getDeclaredField("executor");
        field.setAccessible(true);
        ExecutorService workers = (ExecutorService) field.get(sdk);
        try {
            workers.submit(() -> {}).get(1, TimeUnit.SECONDS);
            new FeishuEventConnection(sdk).close();
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(workers.isShutdown());
        } finally {
            workers.shutdownNow();
        }
    }
}
