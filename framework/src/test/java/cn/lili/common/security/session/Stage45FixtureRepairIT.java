package cn.lili.common.security.session;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
@EnabledIfEnvironmentVariable(named="S45_RUN",matches=".*S45_[0-9]{8}_[0-9]{6}.*")
class Stage45FixtureRepairIT{
 @BeforeAll static void open()throws Exception{initializeStage45Dependencies();}
 @AfterAll static void close()throws Exception{closeS2();}
 @Test void requeryOnlyOwnedPrimaryMockSupplyEvidenceAfterCapacitySuite()throws Exception{Stage45BusinessIT.restorePrimarySupplyEvidence();}
}
