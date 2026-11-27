package cn.ncut.lab.service;

import cn.ncut.lab.config.DataCaptureSchema;
import cn.ncut.lab.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DataClaimsTest {
    JdbcTemplate db;TransactionTemplate tx;
    final byte[] content={1,2,3,4};
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));DataCaptureSchema.create(db);
        db.execute("CREATE TABLE run_files(id VARCHAR(64),run_id VARCHAR(64),kind VARCHAR(24),sha256 VARCHAR(64))");
    }
    Map<String,Object> run(String id,String trial) { return Map.of("id",id,"trialId",trial,"expId","TENS"); }
    void claim(String id,String trial) { tx.executeWithoutResult(s->DataClaims.claim(db,run(id,trial),content)); }
    @Test void sameTrialCanRefreshButOtherGroupsAndSubtrialsCannotReuse() {
        claim("a","STEEL_TENS");claim("a","STEEL_TENS");
        assertThrows(ApiException.class,()->claim("a","CAST_TENS"));assertThrows(ApiException.class,()->claim("b","STEEL_TENS"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM experiment_data_claims",Integer.class));
    }
    @Test void concurrentGroupsCannotBothClaimTheSameFile() throws Exception {
        var gate=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
        try(var executor=Executors.newFixedThreadPool(2)) {
            for(String id:List.of("a","b"))results.add(executor.submit(()->{gate.await();try{claim(id,"STEEL_TENS");return true;}catch(ApiException e){assertEquals(409,e.getStatus());return false;}}));
            gate.countDown();int accepted=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);
        }
    }
    @Test void failedAcquisitionRollsBackItsClaim() {
        assertThrows(ApiException.class,()->tx.executeWithoutResult(s->{DataClaims.claim(db,run("a","STEEL_TENS"),content);throw new ApiException(409,"save failed");}));
        claim("b","STEEL_TENS");assertEquals("b",db.queryForObject("SELECT run_id FROM experiment_data_claims",String.class));
    }
    @Test void historicalFilesAreProtectedBeforeAnyNewClaimExists() throws Exception {
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        db.update("INSERT INTO run_files VALUES('old','a','data',?)",hash);
        assertThrows(ApiException.class,()->claim("b","STEEL_TENS"));
        var current=new LinkedHashMap<String,Object>();current.put("id","a");current.put("expId",ExperimentCatalog.COMBINED);current.put("trialId","CAST_TENS");
        current.put("trials",Map.of("STEEL_TENS",Map.of("data",Map.of("fileId","old"))));
        assertThrows(ApiException.class,()->tx.executeWithoutResult(s->DataClaims.claim(db,current,content)));
    }
}
