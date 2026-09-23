package cn.ncut.lab.web;

import cn.ncut.lab.service.RunService;
import cn.ncut.lab.service.TeachingService;
import cn.ncut.lab.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    private final RunService runs;
    private final TeachingService teaching;
    private final ReviewService review;
    public WorkflowController(RunService runs,TeachingService teaching,ReviewService review) { this.runs=runs;this.teaching=teaching;this.review=review; }
    static RunService.Actor actor(HttpServletRequest req) {
        Map<String,Object> s=Sessions.get(req); boolean teacher="teacher".equals(s.get("role"));
        return new RunService.Actor(teacher,String.valueOf(s.get(teacher?"teacher_name":"student_id")).trim());
    }
    @GetMapping("/runs") public Object list(HttpServletRequest q) { return runs.runs(actor(q)); }
    @GetMapping("/lab-status") public Object labStatus(HttpServletRequest q) {
        RunService.Actor a=actor(q);
        Map<String,Object> out=new java.util.LinkedHashMap<>(runs.studentLabStatus(a));
        if(!a.teacher()) out.put("sid",a.id());
        return out;
    }
    @PostMapping("/repair-runs") public Object repairRuns(HttpServletRequest q) {
        RunService.Actor a=actor(q);
        if(!a.teacher()) throw new cn.ncut.lab.web.ApiException(403,"需要教师权限");
        runs.repairAllStudentRuns();
        return Map.of("ok",true);
    }
    @GetMapping("/runs/{id}") public Object get(HttpServletRequest q,@PathVariable String id) {return runs.detail(id,actor(q));}
    @GetMapping("/runs/{id}/classroom") public Object classroom(HttpServletRequest q,@PathVariable String id) {return runs.classroom(id,actor(q));}
    @GetMapping("/runs/{id}/legacy") public Object legacy(HttpServletRequest q,@PathVariable String id) {return runs.legacy(id,actor(q));}
    @PatchMapping("/runs/{id}") public Object update(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return runs.update(id,actor(q),b);}
    @PostMapping("/runs/{id}/photos") public Object photo(HttpServletRequest q,@PathVariable String id,@RequestParam MultipartFile file,@RequestParam String angle) {
        RunService.Actor a=actor(q);
        runs.photo(id,a,file,angle);
        try { return review.analyze(id,a); }
        catch (ApiException ex) {
            Map<String,Object> out=new LinkedHashMap<>(runs.detail(id,a));
            out.put("analyzeError",ex.getMessage());
            return out;
        }
    }
    @DeleteMapping("/runs/{id}/photos/{fid}") public Object removePhoto(HttpServletRequest q,@PathVariable String id,@PathVariable String fid) {return runs.removePhoto(id,fid,actor(q));}
    @PostMapping("/preview") public Object preview(HttpServletRequest q,@RequestParam MultipartFile file) {actor(q);return runs.preview(file);}
    @GetMapping("/wance/status") public Object wanceStatus(HttpServletRequest q) throws Exception { actor(q); return runs.wanceStatus(); }
    @PostMapping("/runs/{id}/data/wance") public Object wanceData(HttpServletRequest q,@PathVariable String id,@RequestBody(required=false) Map<String,Object> b) {return runs.importWanceData(id,actor(q),b==null?null:str(b.get("fileName")));}
    @PostMapping("/runs/{id}/data") public Object data(HttpServletRequest q,@PathVariable String id,@RequestParam MultipartFile file,@RequestParam int start,@RequestParam int force,@RequestParam int displacement,@RequestParam String unit) {return runs.importData(id,actor(q),file,start,force,displacement,unit);}
    static String str(Object v) { return v==null?"":v.toString().trim(); }
    @PostMapping("/runs/{id}/archive") public Object archive(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {
        Map<String,Object> archived=runs.archive(id,actor(q),b);
        try { return review.analyze(id,actor(q)); } catch (RuntimeException e) { return archived; }
    }
    @GetMapping("/settings") public Object settings(HttpServletRequest q) {actor(q);return teaching.settings();}
    @PutMapping("/settings") public Object settings(HttpServletRequest q,@RequestBody Map<String,Object> b) {return teaching.settings(actor(q),b);}
    @PostMapping("/template") public Object template(HttpServletRequest q,@RequestParam MultipartFile file) {return teaching.template(actor(q),file);}
    @PostMapping("/runs/{id}/deductions") public Object deductions(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return teaching.deduction(id,actor(q),b);}
    @PostMapping("/runs/{id}/deductions/{did}/void") public Object voidDeduction(HttpServletRequest q,@PathVariable String id,@PathVariable String did,@RequestBody Map<String,Object> b) {return teaching.voidDeduction(id,did,actor(q),b);}
    @PostMapping("/runs/{id}/control") public Object control(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return teaching.control(id,actor(q),b);}
    @PostMapping("/runs/{id}/report-file") public Object reportFile(HttpServletRequest q,@PathVariable String id,@RequestParam MultipartFile file) {return teaching.uploadReport(id,actor(q),file);}
    @PostMapping("/runs/{id}/observation") public Object observation(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return teaching.observation(id,actor(q),b);}
    @PostMapping("/runs/{id}/submit") public Object submit(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return teaching.submit(id,actor(q),b);}
    @PostMapping("/runs/{id}/reports/{rid}/review") public Object review(HttpServletRequest q,@PathVariable String id,@PathVariable String rid,@RequestBody Map<String,Object> b) {return teaching.review(id,rid,actor(q),b);}
    @PostMapping("/runs/{id}/retake-request") public Object retakeRequest(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return review.requestRetake(id,actor(q),b);}
    @PostMapping("/runs/{id}/retake") public Object retake(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return review.createRetake(id,actor(q),b);}
    @PostMapping("/runs/{id}/adopt") public Object adopt(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return review.adopt(id,actor(q),b);}
    @PostMapping("/runs/{id}/reference") public Object reference(HttpServletRequest q,@PathVariable String id,@RequestBody Map<String,Object> b) {return review.reference(id,actor(q),b);}
    @PostMapping("/runs/{id}/analyze") public Object analyze(HttpServletRequest q,@PathVariable String id) {return review.analyze(id,actor(q));}
    @PostMapping("/runs/{id}/similarity") public Object similarity(HttpServletRequest q,@PathVariable String id) {return review.similarity(id,actor(q));}
    @PostMapping("/runs/{id}/ai/{rid}") public Object ai(HttpServletRequest q,@PathVariable String id,@PathVariable String rid) {return review.ai(id,rid,actor(q));}
    @GetMapping("/files/{fid}") public ResponseEntity<byte[]> file(HttpServletRequest q,@PathVariable String fid,@RequestParam(defaultValue="false") boolean download) {
        Map<String,Object> f=runs.getFile(fid,actor(q));
        boolean inline=!download && (String.valueOf(f.get("mime")).startsWith("image/")||"application/pdf".equals(f.get("mime")));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,(inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(String.valueOf(f.get("name")),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store")
            .contentType(MediaType.parseMediaType(String.valueOf(f.get("mime")))).body((byte[])f.get("contents"));
    }
}
