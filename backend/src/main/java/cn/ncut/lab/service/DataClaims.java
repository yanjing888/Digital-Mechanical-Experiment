package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.util.*;
import static cn.ncut.lab.service.RunService.*;

final class DataClaims {
    static void claim(JdbcTemplate db, Map<String,Object> run, byte[] content) {
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            String runId=str(run.get("id")), trialId=str(run.getOrDefault("trialId",run.get("expId")));
            var previous=db.queryForList("SELECT id,run_id FROM run_files WHERE kind='data' AND sha256=?",hash);
            if(previous.stream().anyMatch(f->!runId.equals(str(f.get("run_id")))))throw new ApiException(409,"该试验文件已被其他小组或课次采集，请完成本组试验后读取新数据");
            if(TrialRecords.combined(run))for(var trial:TrialRecords.items(run)) {
                String fileId=str(map(trial.get("data")).get("fileId"));
                if(!trialId.equals(str(trial.get("id"))) && previous.stream().anyMatch(f->fileId.equals(str(f.get("id")))))throw new ApiException(409,"该试验文件已用于“"+trial.get("name")+"”，请完成当前子实验后读取新数据");
            }
            // The primary key serializes concurrent acquisitions from different groups.
            db.update("INSERT IGNORE INTO experiment_data_claims(fingerprint,run_id,trial_id,created_at) VALUES(?,?,?,?)",hash,runId,trialId,now());
            var owner=db.queryForMap("SELECT run_id,trial_id FROM experiment_data_claims WHERE fingerprint=? FOR UPDATE",hash);
            if(!runId.equals(str(owner.get("run_id")))||!str(owner.get("trial_id")).isEmpty()&&!trialId.equals(str(owner.get("trial_id"))))throw new ApiException(409,"该试验文件已用于其他小组、课次或子实验，请等待新的试验数据");
            if(str(owner.get("trial_id")).isEmpty())db.update("UPDATE experiment_data_claims SET trial_id=? WHERE fingerprint=?",trialId,hash);
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void release(JdbcTemplate db, Map<String,Object> run, String fileId) {
        var files=db.queryForList("SELECT sha256 FROM run_files WHERE id=? AND run_id=? AND kind='data'",fileId,run.get("id"));
        if(files.isEmpty())throw new ApiException(409,"采集文件已变化，请刷新后重试");
        String hash=str(files.get(0).get("sha256")),runId=str(run.get("id")),trialId=str(run.getOrDefault("trialId",run.get("expId")));
        // Keep the file reserved to this group/lesson, while allowing correction to another subtrial.
        db.update("INSERT IGNORE INTO experiment_data_claims(fingerprint,run_id,trial_id,created_at) VALUES(?,?,?,?)",hash,runId,trialId,now());
        var owner=db.queryForMap("SELECT run_id,trial_id FROM experiment_data_claims WHERE fingerprint=? FOR UPDATE",hash);
        if(!runId.equals(str(owner.get("run_id")))||!trialId.equals(str(owner.get("trial_id"))))throw new ApiException(409,"采集归属已变化，请刷新后重试");
        db.update("UPDATE experiment_data_claims SET trial_id='' WHERE fingerprint=?",hash);
    }
}
