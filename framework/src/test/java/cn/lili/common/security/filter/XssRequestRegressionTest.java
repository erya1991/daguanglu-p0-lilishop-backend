package cn.lili.common.security.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class XssRequestRegressionTest {
    @RestController static class Probe {
        @GetMapping("/page") String page(@RequestParam(defaultValue="1") int pageNumber,@RequestParam(required=false) Boolean enabled){return pageNumber+":"+enabled;}
    }
    @Test void absentParametersKeepServletNullAndMvcDefaults()throws Exception{
        var wrapper=new XssHttpServletRequestWrapper(new MockHttpServletRequest());assertNull(wrapper.getParameterValues("absent"));assertNull(wrapper.getParameter("absent"));
        MockMvcBuilders.standaloneSetup(new Probe()).addFilters(new XssFilter()).build().perform(get("/page")).andExpect(status().isOk()).andExpect(content().string("1:null"));
    }
    @Test void typedQueriesStillBindAndMalformedNumbersFail()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new Probe()).addFilters(new XssFilter()).build();
        mvc.perform(get("/page").param("pageNumber","2").param("enabled","false")).andExpect(status().isOk()).andExpect(content().string("2:false"));
        mvc.perform(get("/page").param("pageNumber","bad")).andExpect(status().isBadRequest());
    }
    @Test void sanitizingDoesNotMutateUnderlyingParameterArrays(){
        var request=new MockHttpServletRequest();request.addParameter("label","<script>alert(1)</script>safe");
        var wrapper=new XssHttpServletRequestWrapper(request);assertFalse(wrapper.getParameterMap().get("label")[0].contains("script"));
        assertEquals("<script>alert(1)</script>safe",request.getParameter("label"));
        assertEquals(wrapper.getParameter("label"),wrapper.getParameterValues("label")[0]);
    }
}
