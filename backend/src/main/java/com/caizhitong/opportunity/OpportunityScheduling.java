package com.caizhitong.opportunity;

import com.caizhitong.ai.notice.NoticeParseWorker;
import com.caizhitong.material.precheck.ChecklistPrecheckWorker;
import com.caizhitong.material.precheck.MaterialPrecheckWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** A 模块后台任务：资格检查、截止状态、通知解析、清单/单材料预检。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.opportunity.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OpportunityScheduling {
    private final EligibilityWorker eligibility;
    private final OpportunityExpiryWorker expiry;
    private final NoticeParseWorker noticeParse;
    private final ChecklistPrecheckWorker checklistPrecheck;
    private final MaterialPrecheckWorker materialPrecheck;
    public OpportunityScheduling(EligibilityWorker eligibility, OpportunityExpiryWorker expiry, NoticeParseWorker noticeParse,
                                 ChecklistPrecheckWorker checklistPrecheck, MaterialPrecheckWorker materialPrecheck) {
        this.eligibility = eligibility;
        this.expiry = expiry;
        this.noticeParse = noticeParse;
        this.checklistPrecheck = checklistPrecheck;
        this.materialPrecheck = materialPrecheck;
    }

    @Scheduled(fixedDelayString = "${app.opportunity.poll-ms:5000}")
    public void eligibility() { eligibility.run(); }

    @Scheduled(fixedDelayString = "${app.opportunity.poll-ms:5000}")
    public void noticeParse() { noticeParse.run(); }

    @Scheduled(fixedDelayString = "${app.opportunity.poll-ms:5000}")
    public void checklistPrecheck() { checklistPrecheck.run(); }

    @Scheduled(fixedDelayString = "${app.opportunity.poll-ms:5000}")
    public void materialPrecheck() { materialPrecheck.run(); }

    @Scheduled(fixedDelayString = "${app.opportunity.expiry-ms:60000}")
    public void expiry() { expiry.run(); }
}
