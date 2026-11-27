package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

@Service
public class WanceDataService {
    private final Path fallbackDir;
    private final Path configFile;
    public record Station(String id, String name, Path directory) {}

    public WanceDataService(@Value("${lab.wance.data-dir:}") String dir,
                            @Value("${lab.wance.config-file:}") String configFile) {
        this.fallbackDir = dir == null || dir.isBlank() ? null : Paths.get(dir.trim()).toAbsolutePath().normalize();
        this.configFile = configFile == null || configFile.isBlank() ? null : Paths.get(configFile.trim()).toAbsolutePath().normalize();
    }
    private Map<String,String> configValues() {
        if (configFile == null || !Files.isRegularFile(configFile)) return Map.of();
        Map<String,String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String trimmed=line.trim();int equals=trimmed.indexOf('=');
                if (!trimmed.startsWith("#") && equals>0) values.put(trimmed.substring(0,equals).trim(),trimmed.substring(equals+1).trim());
            }
        } catch (IOException e) { throw new ApiException(503,"读取采集配置失败，请联系管理员检查实验台配置"); }
        return values;
    }
    private static List<String> entries(String value) { return Arrays.stream(value.split(",")).map(String::trim).filter(v->!v.isEmpty()).toList(); }
    private static String address(String value) {
        String text=value==null?"":value.trim().toLowerCase(Locale.ROOT);
        if(text.startsWith("::ffff:"))text=text.substring(7);
        return text.equals("0:0:0:0:0:0:0:1")?"::1":text;
    }
    public Station station(String clientAddress) {
        var config=configValues();var ids=entries(config.getOrDefault("station.ids",""));
        if (!ids.isEmpty()) {
            Set<String> seenIds=new HashSet<>(),seenAddresses=new HashSet<>();Set<Path> seenDirs=new HashSet<>();
            Station matched=null;
            for(String id:ids) {
                if(!id.matches("[A-Za-z0-9_-]{1,32}")||!seenIds.add(id))throw new ApiException(503,"实验台编号配置无效或重复");
                String prefix="station."+id+".",dir=config.getOrDefault(prefix+"dir","");
                var addresses=entries(config.getOrDefault(prefix+"clients",""));
                if(dir.isBlank()||addresses.isEmpty())throw new ApiException(503,"实验台必须配置独立目录和采集电脑地址");
                Path path=Paths.get(dir).toAbsolutePath().normalize();
                if(!seenDirs.add(path))throw new ApiException(503,"多个实验台不能共用同一个采集目录");
                for(String ip:addresses) {
                    if(!ip.matches("[0-9a-fA-F:.]+")||!seenAddresses.add(address(ip)))throw new ApiException(503,"采集电脑地址配置无效或重复绑定");
                    if(address(ip).equals(address(clientAddress)))matched=new Station(id,config.getOrDefault(prefix+"name",id),path);
                }
            }
            if(matched==null)throw new ApiException(403,"当前电脑未绑定实验台，请在对应的拉力机电脑上采集数据");
            return matched;
        }
        Path dir=fallbackDir;
        if(configFile!=null&&Files.isRegularFile(configFile)) {
            String value=config.getOrDefault("data.dir","");
            if(value.isBlank())throw new ApiException(503,"采集配置缺少 data.dir");
            dir=Paths.get(value).toAbsolutePath().normalize();
        }
        if(dir==null)throw new ApiException(503,"尚未配置万测数据目录");
        var allowed=entries(config.getOrDefault("data.clients",""));
        if(!allowed.isEmpty()&&allowed.stream().noneMatch(ip->address(ip).equals(address(clientAddress))))throw new ApiException(403,"当前电脑未绑定实验台，请在对应的拉力机电脑上采集数据");
        return new Station(config.getOrDefault("data.station-id","local"),config.getOrDefault("data.station-name","本机实验台"),dir);
    }
    public Duration settleTime() {
        try {
            int seconds=Integer.parseInt(configValues().getOrDefault("data.settle-seconds","2"));
            if(seconds<1||seconds>30)throw new NumberFormatException();
            return Duration.ofSeconds(seconds);
        } catch(NumberFormatException e) { throw new ApiException(503,"文件稳定时间须为 1 至 30 秒"); }
    }
    public boolean configured() { try{return Files.isDirectory(station("127.0.0.1").directory());}catch(ApiException e){return false;} }
    public Path directory() {
        Path dir=station("127.0.0.1").directory();
        if(!Files.isDirectory(dir))throw new ApiException(503,"实验台数据目录不可用，请联系管理员检查共享目录或采集同步");
        return dir;
    }
    public Map<String,Object> status() throws IOException { return status("127.0.0.1"); }
    public Map<String,Object> status(String clientAddress) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("vendor","万测");out.put("software","TestPilot");
        try {
            var station=station(clientAddress);out.put("configured",Files.isDirectory(station.directory()));
            out.put("stationId",station.id());out.put("stationName",station.name());
            if(!Files.isDirectory(station.directory()))out.put("message","实验台数据目录不可用，请联系管理员检查共享目录或采集同步");
        } catch(ApiException e) { out.put("configured",false);out.put("message",e.getMessage()); }
        return out;
    }
}
