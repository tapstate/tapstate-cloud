package io.tapstate.cloud.sdk.client;

import java.time.Duration;

/**
 * @author lg&lt;lirufei0808@gmail.com&gt;
 * create at 2026/9/30 14:42
 */
public class Example {

    public void example() throws CloudControlPlaneException, JwtVerificationException {
        CloudControlPlaneSdk sdk = CloudControlPlaneSdk.builder()
                .baseUrl("https://dev.cloud.tapstate.com")          // required
                .issuer("https://dev.cloud.tapstate.com")           // default — GC's signer; change if self-hosted
                .clusterId("6abc97ff2891a6a589fb6508")        // default — reads env at builder creation
                .sdkStaticToken("f93632c15aca3d5cb090514b02bc71c51a4462ae7718145877ddc0e59efff3a2")
                .jwksRefreshInterval(Duration.ofMinutes(10))   // default — 10 min (ADR-0125 D6)
                .build();
        JwtExchangeResult jwt = sdk.exchangeForJwt("br3clq3GNu-pFYcKmtLzB7z9HUqugWtqUnG8K02GDek");
        System.out.println(jwt);

        sdk.verifyJwt(jwt.jwt());
    }

    public static void main(String[] args) throws CloudControlPlaneException, JwtVerificationException {
        new Example().example();
    }
}
