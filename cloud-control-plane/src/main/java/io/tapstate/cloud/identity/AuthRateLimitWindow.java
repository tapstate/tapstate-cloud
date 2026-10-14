package io.tapstate.cloud.identity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "auth_rate_limit_windows")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthRateLimitWindow {

    @Id
    private String id;

    private int count;
    @Indexed(expireAfter = "0s")
    private Instant expiresAt;
}
