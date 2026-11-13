package cn.lili.modules.community.p0.uat;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.Stage1Db;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registers only a newly created real-WeChat resident in this isolated Mock run.
 * Called inside the same transaction that creates the Member and WeChat binding.
 */
@Component
@ConditionalOnProperty(name="p0.stage2.server-uat-mode", havingValue="true")
public class ServerWechatResidentEnrollment {
    private final Stage2Settings settings;
    private final Stage1Db db;

    public ServerWechatResidentEnrollment(Stage2Settings settings, Stage1Db db) {
        this.settings=settings; this.db=db;
    }

    public void registerNew(String memberId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || memberId == null || !memberId.matches("[1-9][0-9]{0,18}"))
            throw new IllegalStateException("UAT_RESIDENT_ENROLLMENT_INVALID");
        UatResourceScope.registered(settings);
        if (db.update("INSERT INTO li_p0_s2_member_scope(member_id,run_id,permission,evidence) VALUES(?,?,'TRANSACT','WECHAT_TEST_NEW')",
                memberId, settings.getRunId()) != 1)
            throw new IllegalStateException("UAT_RESIDENT_ENROLLMENT_FAILED");
    }
}
