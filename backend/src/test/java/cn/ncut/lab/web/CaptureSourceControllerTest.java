package cn.ncut.lab.web;

import cn.ncut.lab.service.RunService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.mockito.Mockito.*;

class CaptureSourceControllerTest {
    @Test void captureUsesServerResolvedClientAddressAndIgnoresSpoofedSourceParameters() {
        var runs=mock(RunService.class);var controller=new WorkflowController(runs,null,null,null,null,null);
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.168.1.12");
        request.setAttribute(Sessions.ATTR,Map.of("role","student","student_id","s1"));
        request.addHeader("X-Forwarded-For","192.168.1.11");request.setParameter("stationId","one");
        controller.wanceData(request,"run");controller.wanceStatus(request);
        var upload=new org.springframework.mock.web.MockMultipartFile("file","test.mdb","application/x-msaccess",new byte[]{1});
        controller.wanceFile(request,"run",upload);
        verify(runs).importWanceFile("run",new RunService.Actor(false,"s1"),upload,"192.168.1.12");
        verify(runs).importWanceData("run",new RunService.Actor(false,"s1"),"192.168.1.12");verify(runs).wanceStatus("192.168.1.12");
    }
}
