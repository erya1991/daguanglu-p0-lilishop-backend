package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class Stage3GateTest {
    @Test void approvedDesignAloneNeverEnablesWritesOrCredentials(){
        var s=new Stage3Settings();s.setEnabled(true);var gate=new Stage3Gate(s,new Stage2Settings());
        assertEquals("STAGE3_WRITES_DISABLED",assertThrows(Stage1Exception.class,gate::requireWrites).code());
        assertThrows(Stage1Exception.class,gate::requireCredentials);
        s.setWritesEnabled(true);assertEquals("SC23_NOT_ADMITTED",assertThrows(Stage1Exception.class,gate::requireWrites).code());
        s.setCredentialsEnabled(true);assertThrows(Stage1Exception.class,gate::requireCredentials);
    }
}
