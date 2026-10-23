package cn.lili.modules.member.profile;

import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.function.Supplier;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@Component
public final class ResidentTransactions {
    private final TransactionTemplate transaction;
    public ResidentTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        // Predicate locks for phone ownership/history checks; actual indexes/engine require B04 verification.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ResidentProfileException(OPERATION_UNAVAILABLE);
        try { return transaction.execute(status -> {
            try { return work.get(); }
            catch (ResidentProfileException e) { throw new ResidentProfileException(e.getCode()); }
            catch (DuplicateKeyException e) { throw new ResidentProfileException(PROFILE_CONFLICT); }
            catch (RuntimeException e) { throw new ResidentProfileException(OPERATION_UNAVAILABLE); }
        }); }
        catch (ResidentProfileException e) { throw e; }
        catch (RuntimeException e) { throw new ResidentProfileException(OPERATION_UNAVAILABLE); }
    }
}
