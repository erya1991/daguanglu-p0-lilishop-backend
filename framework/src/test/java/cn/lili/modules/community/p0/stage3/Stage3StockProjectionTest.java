package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.stage2.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class Stage3StockProjectionTest {
 @Test void explicitStage3ModeUsesOnlyItsOwnProjectionRun(){var s=new Stage2Settings();s.setStage3ResourceMode(true);s.setDatabase("dgl_p0_s3");s.setRunId("S3_20261031_150410");assertTrue(new Stage2StockProjection(s).columns().contains("S3_20261031_150410"));s.setRunId("S2_20261028_214544");assertThrows(IllegalArgumentException.class,()->new Stage2StockProjection(s));s.setRunId("S3_20261031_150410");s.setDatabase("dgl_p0_s2");assertThrows(IllegalArgumentException.class,()->new Stage2StockProjection(s));}
 @Test void legacyModeStillRejectsStage3AndSqlMetacharacters(){var s=new Stage2Settings();s.setRunId("S2_20261028_214544");assertNotNull(new Stage2StockProjection(s));for(String run:new String[]{"S3_20261031_150410","S2_20261028_214544' OR 1=1","S1_20261026_203407"}){s.setRunId(run);assertThrows(IllegalArgumentException.class,()->new Stage2StockProjection(s));}}
}
