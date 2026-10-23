package io.tapstate.cloud.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class TrialCodePolicy {

    private final boolean required;

    public TrialCodePolicy(@Value("${tapstate.cloud.auth.trial-code-required:true}") boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }
}
