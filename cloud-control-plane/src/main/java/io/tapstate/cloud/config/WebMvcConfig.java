package io.tapstate.cloud.config;

import io.tapstate.cloud.event.UserOperationEventInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the user-operation event capture for the Cloud API surface. */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserOperationEventInterceptor userOperationEventInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userOperationEventInterceptor)
            .addPathPatterns("/v1/api/**");
    }
}
