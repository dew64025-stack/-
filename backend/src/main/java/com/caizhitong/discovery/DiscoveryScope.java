package com.caizhitong.discovery;

import com.caizhitong.campus.CampusReadBridge;
import com.caizhitong.campus.CampusService;
import com.caizhitong.common.BusinessException;
import com.caizhitong.security.CurrentActor;
import org.springframework.stereotype.Component;

/** Public discovery scope: authenticated campus, explicit open campusCode, or the first open campus. */
@Component
public class DiscoveryScope {
    private final CurrentActor current;
    private final CampusService campuses;
    private final CampusReadBridge campusRead;

    public DiscoveryScope(CurrentActor current, CampusService campuses, CampusReadBridge campusRead) {
        this.current = current;
        this.campuses = campuses;
        this.campusRead = campusRead;
    }

    public long campus(String campusCode) {
        var actor = current.optional();
        if (actor.isPresent()) return actor.get().campusId();
        if (campusCode != null && !campusCode.isBlank()) return campuses.open(campusCode);
        Long first = campusRead.firstOpenCampus();
        if (first == null) throw new BusinessException(400, "CAMPUS_UNAVAILABLE", "校园未开放");
        return first;
    }
}
