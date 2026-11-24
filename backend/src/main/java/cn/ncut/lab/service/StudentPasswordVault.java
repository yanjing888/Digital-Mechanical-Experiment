package cn.ncut.lab.service;

import cn.ncut.lab.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Login still uses scrypt. Only the teacher-only reveal endpoint decrypts this separate copy. */
@Service
public class StudentPasswordVault {
    private final JdbcTemplate db;
    private final byte[] key;
    public StudentPasswordVault(JdbcTemplate db, @Value("${STUDENT_PASSWORD_KEY_FILE:config/student-password.key}") String keyFile) {
        this.db=db;
        db.execute("CREATE TABLE IF NOT EXISTS student_password_secrets (sid VARCHAR(32) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, ciphertext TEXT NOT NULL)");
        try {
            Path path=Paths.get(keyFile).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                if (db.queryForObject("SELECT COUNT(*) FROM student_password_secrets",Integer.class)>0)
                    throw new IllegalStateException("学生密码密钥缺失，请恢复原 student-password.key 文件");
                Files.createDirectories(path.getParent());
                byte[] generated=new byte[32];new SecureRandom().nextBytes(generated);
                try {Files.writeString(path,Base64.getEncoder().encodeToString(generated),StandardOpenOption.CREATE_NEW);}
                catch(FileAlreadyExistsException ignored) { /* Another service instance created it. */ }
            }
            this.key=Base64.getDecoder().decode(Files.readString(path).trim());
            if(key.length!=32)throw new IllegalStateException("学生密码密钥必须为32字节的Base64编码");
        } catch(Exception e) {throw new IllegalStateException("无法加载学生密码密钥："+e.getMessage(),e);}
    }
    public void save(String sid,String password,String hash) {
        String encrypted=encrypt(sid,hash,password);
        db.update("INSERT INTO student_password_secrets(sid,password_hash,ciphertext) VALUES(?,?,?) ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),ciphertext=VALUES(ciphertext)",sid,hash,encrypted);
    }
    public Optional<String> reveal(String sid,String currentHash) {
        var rows=db.queryForList("SELECT password_hash,ciphertext FROM student_password_secrets WHERE sid=?",sid);
        if(!rows.isEmpty()&&currentHash.equals(rows.get(0).get("password_hash"))) {
            String password=decrypt(sid,currentHash,String.valueOf(rows.get(0).get("ciphertext")));
            if(PasswordUtil.verify(password,currentHash))return Optional.of(password);
            throw new IllegalStateException("密码校验不一致，请重置密码");
        }
        // A known initial password may be displayed only after verifying the stored hash.
        return PasswordUtil.verify(sid,currentHash)?Optional.of(sid):Optional.empty();
    }
    private byte[] aad(String sid,String hash) {return (sid+":"+hash).getBytes(StandardCharsets.UTF_8);}
    private String encrypt(String sid,String hash,String password) {
        try {
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(aad(sid,hash));
            byte[] encrypted=cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] value=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,value,0,iv.length);System.arraycopy(encrypted,0,value,iv.length,encrypted.length);
            return Base64.getEncoder().encodeToString(value);
        } catch(Exception e) {throw new IllegalStateException("无法加密保存学生密码",e);}
    }
    private String decrypt(String sid,String hash,String encrypted) {
        try {
            byte[] value=Base64.getDecoder().decode(encrypted);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(value,0,12)));cipher.updateAAD(aad(sid,hash));
            return new String(cipher.doFinal(Arrays.copyOfRange(value,12,value.length)),StandardCharsets.UTF_8);
        } catch(Exception e) {throw new IllegalStateException("无法解密学生密码，请检查原密钥文件",e);}
    }
}
