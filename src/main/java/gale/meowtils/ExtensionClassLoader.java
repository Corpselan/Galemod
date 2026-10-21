package gale.meowtils;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

/**
 * Loads one .meowtils extension from memory, as Meowtils does. Its classes are rewritten on the way in: Meowtils' API
 * (wtf/tatp/meowtils/...) becomes Gale's copy (gale/meowtils/api/...), so Gale never defines classes under Meowtils'
 * names; in the development environment Minecraft's SRG names become the MCP names it runs with.
 */
final class ExtensionClassLoader extends ClassLoader {
    private static final String MEOWTILS = "wtf/tatp/meowtils/", OURS = "gale/meowtils/api/";
    final String name;
    private final Map<String, byte[]> entries;
    private final Map<String, String> methods, fields;
    private final Handler handler = new Handler();

    ExtensionClassLoader(String name, Map<String, byte[]> entries, ClassLoader parent, Map<String, String> methods, Map<String, String> fields) {
        super(parent);
        this.name = name;
        this.entries = entries;
        this.methods = methods;
        this.fields = fields;
    }

    @Override
    protected Class<?> findClass(String className) throws ClassNotFoundException {
        byte[] bytes = entries.get(className.replace('.', '/') + ".class");
        if (bytes == null) throw new ClassNotFoundException(className);
        byte[] out = remap(bytes);
        return defineClass(className, out, 0, out.length);
    }

    private byte[] remap(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new RemappingClassAdapter(writer, new Remapper() {
            @Override
            public String map(String type) {
                return type.startsWith(MEOWTILS) ? OURS + type.substring(MEOWTILS.length()) : type;
            }

            @Override
            public String mapMethodName(String owner, String name, String desc) {
                String m = methods.get(name);
                return m == null ? name : m;
            }

            @Override
            public String mapFieldName(String owner, String name, String desc) {
                String f = fields.get(name);
                return f == null ? name : f;
            }
        }), ClassReader.EXPAND_FRAMES); // Forge's ASM 5.0.3 remaps methods only with expanded frames
        return writer.toByteArray();
    }

    private static String path(String name) { return name.startsWith("/") ? name.substring(1) : name; }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] data = entries.get(path(name));
        return data != null ? new ByteArrayInputStream(data) : super.getResourceAsStream(name);
    }

    @Override
    protected URL findResource(String name) {
        String p = path(name);
        if (!entries.containsKey(p)) return null;
        try {
            return new URL("galeext", null, -1, "/" + p, handler);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        URL u = findResource(name);
        return u == null ? Collections.<URL>emptyEnumeration() : Collections.enumeration(Collections.singletonList(u));
    }

    /** Lets go of the extension's files (when extensions are reloaded). */
    void close() { entries.clear(); }

    // "galeext:/path" URLs read the extension's files from memory
    private final class Handler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL u) {
            return new URLConnection(u) {
                @Override
                public void connect() { }

                @Override
                public InputStream getInputStream() throws IOException {
                    byte[] data = entries.get(path(u.getPath()));
                    if (data == null) throw new IOException("not in the extension: " + u.getPath());
                    return new ByteArrayInputStream(data);
                }
            };
        }
    }
}
