package com.caizhitong.privacy;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.text;

public final class PrivacyInputs {
    private PrivacyInputs() {}

    public static final Set<String> AUTHORIZATION_TYPES =
            Set.of("privacy", "phone", "subscription", "location", "material_ai");

    public record Grant(JsonNode type, JsonNode scope, JsonNode documentVersion) {
        public Grant {
            String value = text(type, 32, false);
            if (!AUTHORIZATION_TYPES.contains(value)) throw invalid();
            text(scope, 128, false);
            text(documentVersion, 64, false);
        }
    }

    public record PrivacyUpdate(JsonNode personalization, JsonNode historyEnabled, JsonNode nearbyEnabled,
                                JsonNode version) {
        public PrivacyUpdate {
            com.caizhitong.identity.IdentityInputs.version(version);
            if (personalization == null && historyEnabled == null && nearbyEnabled == null) throw invalid();
            if (personalization != null && !personalization.isBoolean()) throw invalid();
            if (historyEnabled != null && !historyEnabled.isBoolean()) throw invalid();
            if (nearbyEnabled != null && !nearbyEnabled.isBoolean()) throw invalid();
        }
    }

    public record Deletion(JsonNode confirm) {
        public Deletion {
            if (confirm == null || !confirm.isBoolean() || !confirm.asBoolean()) throw invalid();
        }
    }

    public record Empty() {}
}
