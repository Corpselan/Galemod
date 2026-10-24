package gale.devtest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Isolate verification classes, run the external ZKM, and merge without changing other payload bytes. */
public final class ZkmSecurityTool {
    private ZkmSecurityTool() { }
    private static boolean target(String name) {
        return name.startsWith("gale/security/") && name.endsWith(".class")
            && !name.equals("gale/security/WatermarkIdentity.class");
    }
    private static Map<String,byte[]> read(Path jar) throws Exception {
        Map<String,byte[]> result = new LinkedHashMap<>();
        try (JarFile input = new JarFile(jar.toFile(), false)) {
            Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement(); if (e.isDirectory()) continue;
                if (result.containsKey(e.getName())) throw new SecurityException("Duplicate JAR entry");
                try (InputStream in = input.getInputStream(e); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    result.put(e.getName(), out.toByteArray());
                }
            }
        }
        return result;
    }
    private static void write(Path jar, Map<String,byte[]> contents) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String,byte[]> entry : contents.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry();
            }
        }
    }
    private static String quote(Path file) {
        String path = file.toAbsolutePath().toString().replace('\\','/');
        if (path.indexOf('"') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('%') >= 0)
            throw new IllegalArgumentException("Unsupported ZKM path characters");
        return "\"" + path + "\"";
    }
    /** Class names and public/protected ABI must be identical so callers need no rewriting. */
    private static Set<String> api(byte[] bytes) throws Exception {
        Set<String> api = new TreeSet<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) throw new SecurityException("Invalid class output");
            in.readUnsignedShort(); if (in.readUnsignedShort() != 52) throw new SecurityException("Expected Java 8 bytecode");
            Object[] pool = new Object[in.readUnsignedShort()];
            for (int i=1;i<pool.length;i++) {
                switch (in.readUnsignedByte()) {
                    case 1: pool[i]=in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: pool[i]=Integer.valueOf(in.readUnsignedShort()); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new SecurityException("Unsupported class constant");
                }
            }
            int flags=in.readUnsignedShort(), self=in.readUnsignedShort(), parent=in.readUnsignedShort();
            api.add("class:"+flags+":"+pool[(Integer)pool[self]]+":"+(parent==0?"":pool[(Integer)pool[parent]]));
            int interfaces=in.readUnsignedShort();for(int i=0;i<interfaces;i++) api.add("interface:"+pool[(Integer)pool[in.readUnsignedShort()]]);
            for (int kind=0;kind<2;kind++) {
                int count=in.readUnsignedShort();
                for(int i=0;i<count;i++) {
                    int access=in.readUnsignedShort();String name=(String)pool[in.readUnsignedShort()], descriptor=(String)pool[in.readUnsignedShort()];
                    if((access & 5)!=0) api.add(kind+":"+access+":"+name+":"+descriptor);
                    int attributes=in.readUnsignedShort();for(int j=0;j<attributes;j++) {in.readUnsignedShort();int length=in.readInt();if(length<0)throw new SecurityException("Invalid attribute");while(length>0){int n=in.skipBytes(length);if(n==0)throw new EOFException();length-=n;}}
                }
            }
        }
        return api;
    }
    static void merge(Path original, Path processed, Path output) throws Exception {
        Map<String,byte[]> contents=read(original), changed=read(processed); int differences=0;
        for (Map.Entry<String,byte[]> entry : contents.entrySet()) if (target(entry.getKey())) {
            byte[] replacement=changed.get(entry.getKey());
            if (replacement==null || !api(replacement).containsAll(api(entry.getValue()))) throw new SecurityException("ZKM changed the public ABI: "+entry.getKey());
            if (!Arrays.equals(entry.getValue(),replacement)) differences++;
        }
        if (differences==0) throw new SecurityException("ZKM produced no transformed verification classes");
        for (Map.Entry<String,byte[]> entry:changed.entrySet()) {
            if (!target(entry.getKey())) {
                if (entry.getKey().equals("META-INF/MANIFEST.MF")) continue;
                throw new SecurityException("ZKM output escaped verification scope: "+entry.getKey());
            }
            api(entry.getValue()); contents.put(entry.getKey(),entry.getValue());
        }
        Files.createDirectories(output.toAbsolutePath().getParent());Path temp=Files.createTempFile(output.toAbsolutePath().getParent(),"zkm-merged-",".jar");
        try {write(temp,contents);Files.move(temp,output,StandardCopyOption.REPLACE_EXISTING);} finally {Files.deleteIfExists(temp);}
        System.out.println("ZKM verification classes merged; other payload bytes preserved; transformed="+differences);
    }
    public static void main(String[] args) throws Exception {
        if (args.length!=8) throw new IllegalArgumentException("mode input output ZKM.jar java-executable classpath-file template work-directory");
        boolean prepare=args[0].equals("prepare"); if (!prepare&&!args[0].equals("run"))throw new IllegalArgumentException("Unknown mode");
        Path input=Paths.get(args[1]),output=Paths.get(args[2]),zkm=Paths.get(args[3]);
        if (!prepare&&!Files.isRegularFile(zkm))throw new IllegalStateException("Set -PzkmJar to a usable external ZKM.jar");
        Path work=Paths.get(args[7]);Files.createDirectories(work);Path job=Files.createTempDirectory(work,"job-");
        Path isolated=job.resolve("gale-security.jar"),saved=job.resolve("processed"),script=job.resolve("security.zks");Files.createDirectories(saved);
        Path defaults=job.resolve("defaultExclude.txt");Files.write(defaults,"// Explicit exclusions are in security.zks.\n".getBytes(StandardCharsets.UTF_8));
        Map<String,byte[]> selected=new LinkedHashMap<>();
        for(Map.Entry<String,byte[]> entry:read(input).entrySet())if(target(entry.getKey()))selected.put(entry.getKey(),entry.getValue());
        if(selected.size()<5)throw new SecurityException("Verification classes are missing");write(isolated,selected);
        StringBuilder classpath=new StringBuilder();
        for(String line:Files.readAllLines(Paths.get(args[5]),StandardCharsets.UTF_8))if(!line.trim().isEmpty())classpath.append(quote(Paths.get(line))).append('\n');
        String text=new String(Files.readAllBytes(Paths.get(args[6])),StandardCharsets.UTF_8)
            .replace("@CLASSPATH@",classpath.toString()).replace("@INPUT@",isolated.toAbsolutePath().toString().replace('\\','/'))
            .replace("@OUTPUT@",saved.toAbsolutePath().toString().replace('\\','/')).replace("@CHANGELOG@",job.resolve("ChangeLog.txt").toAbsolutePath().toString().replace('\\','/'));
        Files.write(script,text.getBytes(StandardCharsets.UTF_8));System.out.println("Prepared verification-only ZKM script: "+script);
        if(prepare)return;
        int status=new ProcessBuilder(args[4],"-Xmx512m","-Djava.awt.headless=true","-jar",zkm.toString(),"-de",defaults.toString(),"-l",job.resolve("ZKM.log").toString(),script.toString()).directory(job.toFile()).inheritIO().start().waitFor();
        if(status!=0)throw new IllegalStateException("ZKM failed ("+status+"); no merged or signed release was generated. See "+job);
        for (String line:Files.readAllLines(job.resolve("ZKM.log"),StandardCharsets.UTF_8))
            if(line.startsWith("ERROR:"))throw new IllegalStateException("ZKM reported an error despite its exit status; see "+job);
        merge(input,saved.resolve("gale-security.jar"),output);
    }
}
