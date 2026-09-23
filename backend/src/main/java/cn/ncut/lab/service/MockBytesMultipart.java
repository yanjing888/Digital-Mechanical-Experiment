package cn.ncut.lab.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

/** 将本地文件字节包装为 MultipartFile，便于复用 file() 存档逻辑。 */
record MockBytesMultipart(String name, byte[] content) implements MultipartFile {
    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return name; }
    @Override public String getContentType() { return "application/x-msaccess"; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
}
