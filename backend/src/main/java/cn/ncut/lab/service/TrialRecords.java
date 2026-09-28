package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import java.util.*;
import static cn.ncut.lab.service.RunService.*;

/** A task has one group record and four independently collected trial records. */
final class TrialRecords {
    private TrialRecords() {}
    static final List<String> FIELDS = List.of("data", "photos", "specimenId", "deviceId", "experimentAt", "note", "analysis", "fractureSummary", "reference");
    static boolean combined(Map<String,Object> run) { return ExperimentCatalog.COMBINED.equals(run.get("expId")); }
    static void project(Map<String,Object> run, String requested) {
        if (!combined(run)) return;
        String trial = requested == null || requested.isBlank() ? ExperimentCatalog.TRIAL_IDS.get(0) : requested;
        if (!ExperimentCatalog.TRIAL_IDS.contains(trial)) throw new ApiException(400,"试验子项无效");
        run.put("trialId", trial); run.put("trialName", ExperimentCatalog.NAMES.get(trial));
        Map<String,Object> entry = map(map(run.get("trials")).get(trial));
        for (String key : FIELDS) { run.remove(key); if (entry.containsKey(key)) run.put(key, entry.get(key)); }
        run.putIfAbsent("photos", new ArrayList<>());
    }
    static Map<String,Object> stored(Map<String,Object> run) {
        Map<String,Object> stored = new LinkedHashMap<>(run);
        if (!combined(run)) return stored;
        Map<String,Object> trials = new LinkedHashMap<>(map(run.get("trials")));
        String trial = str(run.get("trialId"));
        if (!ExperimentCatalog.TRIAL_IDS.contains(trial)) throw new ApiException(400,"请选择试验子项");
        Map<String,Object> entry = new LinkedHashMap<>();
        for (String key : FIELDS) { if (run.containsKey(key)) entry.put(key, run.get(key)); stored.remove(key); }
        trials.put(trial, entry); stored.put("trials", trials);
        stored.remove("trialId"); stored.remove("trialName"); stored.remove("trialItems");
        return stored;
    }
    static List<Map<String,Object>> items(Map<String,Object> run) {
        Map<String,Object> trials = map(stored(run).get("trials"));
        List<Map<String,Object>> items = new ArrayList<>();
        for (String id : ExperimentCatalog.TRIAL_IDS) {
            Map<String,Object> item = new LinkedHashMap<>(map(trials.get(id)));
            item.put("id",id); item.put("name",ExperimentCatalog.NAMES.get(id));
            item.put("dataReady",!map(item.get("data")).isEmpty());
            item.put("photoReady",!list(item.get("photos")).isEmpty());
            items.add(item);
        }
        return items;
    }
    static void requireComplete(Map<String,Object> run) {
        for (Map<String,Object> trial : items(run)) {
            if (!Boolean.TRUE.equals(trial.get("dataReady")) || !Boolean.TRUE.equals(trial.get("photoReady")))
                throw new ApiException(400,"请补齐“"+trial.get("name")+"”的设备数据与试件照片后提交实验操作记录");
        }
    }
}
