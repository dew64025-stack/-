package com.caizhitong.admin.operations;

import com.caizhitong.identity.IdentityInputs;
import tools.jackson.databind.JsonNode;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.text;

public final class AdminOpsInputs {
    private AdminOpsInputs() {}

    public record CampusCreate(JsonNode name, JsonNode code) {
        public CampusCreate {
            text(name, 128, false);
            String value = text(code, 64, false);
            if (!value.matches("[a-z0-9][a-z0-9-]{1,63}")) throw invalid();
        }
    }

    public record ConfigUpdate(JsonNode value, JsonNode version, JsonNode reason) {
        public ConfigUpdate {
            if (value == null) throw invalid();
            IdentityInputs.version(version);
            text(reason, 512, false);
        }
    }

    public record IntegrationUpdate(JsonNode provider, JsonNode config, JsonNode version, JsonNode reason) {
        public IntegrationUpdate {
            String value = text(provider, 16, false);
            if (!value.matches("demo|manual|official")) throw invalid();
            if (config == null || !config.isObject()) throw invalid();
            IdentityInputs.version(version);
            text(reason, 512, false);
        }
    }

    public record Empty() {}
}
