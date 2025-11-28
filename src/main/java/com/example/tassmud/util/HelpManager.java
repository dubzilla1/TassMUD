package com.example.tassmud.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.yaml.snakeyaml.Yaml;

/**
 * Man-style help pages for commands. Simple registry returning formatted pages.
 */
public class HelpManager {
        private static final Map<String, HelpPage> pages = new LinkedHashMap<>();

        static {
                reloadPages();
        }

        public static synchronized void reloadPages() {
                pages.clear();
                // Load YAML files from classpath /help/*.yaml
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                System.out.println("[HelpManager] reloadPages: scanning for help resources on classpath");
                int resourcesFound = 0;
                int pagesLoadedBefore = pages.size();
                try {
                        Enumeration<URL> resources = cl.getResources("help");
                        Yaml yaml = new Yaml();
                        while (resources.hasMoreElements()) {
                                URL url = resources.nextElement();
                                resourcesFound++;
                                String protocol = url.getProtocol();
                                System.out.println("[HelpManager] found resource URL: " + url + " (protocol=" + protocol + ")");
                                if ("file".equals(protocol)) {
                                        File dir = new File(url.toURI());
                                        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".yaml") || name.toLowerCase().endsWith(".yml"));
                                        if (files != null) {
                                                for (File f : files) {
                                                        try (InputStream is = f.toURI().toURL().openStream();
                                                                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                                                                Object obj = yaml.load(br);
                                                                if (obj instanceof Map) {
                                                                        @SuppressWarnings("unchecked")
                                                                        Map<String, Object> top = (Map<String, Object>) obj;
                                                                        System.out.println("[HelpManager] loading help file: " + f.getName());
                                                                        loadPagesFromMap(top);
                                                                }
                                                        } catch (Exception e) {
                                                                System.err.println("[HelpManager] Failed to load help file: " + f.getName() + " : " + e.getMessage());
                                                                e.printStackTrace(System.err);
                                                        }
                                                }
                                        }
                                } else if ("jar".equals(protocol)) {
                                        JarURLConnection jcon = (JarURLConnection) url.openConnection();
                                        JarFile jar = jcon.getJarFile();
                                        Enumeration<JarEntry> entries = jar.entries();
                                        while (entries.hasMoreElements()) {
                                                JarEntry je = entries.nextElement();
                                                String name = je.getName();
                                                if (name.startsWith("help/") && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                                                        try (InputStream is = cl.getResourceAsStream(name);
                                                                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                                                                Object obj = yaml.load(br);
                                                                if (obj instanceof Map) {
                                                                        @SuppressWarnings("unchecked")
                                                                        Map<String, Object> top = (Map<String, Object>) obj;
                                                                        System.out.println("[HelpManager] loading help entry from jar: " + name);
                                                                        loadPagesFromMap(top);
                                                                }
                                                        } catch (Exception e) {
                                                                System.err.println("[HelpManager] Failed to load help entry: " + name + " : " + e.getMessage());
                                                                e.printStackTrace(System.err);
                                                        }
                                                }
                                        }
                                } else {
                                        // Fallback: try to treat as resource listing
                                        try (InputStream is = cl.getResourceAsStream("help")) {
                                                if (is != null) {
                                                        // attempt to read names line-wise (uncommon)
                                                        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                                                        String line;
                                                        Yaml y = new Yaml();
                                                        while ((line = br.readLine()) != null) {
                                                                if (line.trim().endsWith(".yaml") || line.trim().endsWith(".yml")) {
                                                                        String name = "help/" + line.trim();
                                                                        try (InputStream is2 = cl.getResourceAsStream(name);
                                                                                 BufferedReader br2 = new BufferedReader(new InputStreamReader(is2, StandardCharsets.UTF_8))) {
                                                                                Object obj = y.load(br2);
                                                                                if (obj instanceof Map) {
                                                                                        @SuppressWarnings("unchecked")
                                                                                        Map<String, Object> top = (Map<String, Object>) obj;
                                                                                        System.out.println("[HelpManager] loading help resource fallback: " + name);
                                                                                        loadPagesFromMap(top);
                                                                                }
                                                                        } catch (Exception e) {
                                                                                System.err.println("[HelpManager] Failed to load help resource: " + name + " : " + e.getMessage());
                                                                                e.printStackTrace(System.err);
                                                                        }
                                                                }
                                                        }
                                                }
                                        } catch (Exception ignored) {}
                                }
                        }
                        System.out.println("[HelpManager] reloadPages: resourcesFound=" + resourcesFound + ", pagesBefore=" + pagesLoadedBefore + ", pagesAfter=" + pages.size());
                } catch (Exception e) {
                        System.err.println("[HelpManager] Error scanning help resources: " + e.getMessage());
                        e.printStackTrace(System.err);
                }
        }

        public static synchronized int getLoadedPageCount() {
                return pages.size();
        }

        @SuppressWarnings("unchecked")
        private static void loadPagesFromMap(Map<String, Object> top) {
                for (Map.Entry<String, Object> e : top.entrySet()) {
                        String key = e.getKey();
                        Object val = e.getValue();
                        if (!(val instanceof Map)) continue;
                        Map<String, Object> m = (Map<String, Object>) val;
                        HelpPage hp = new HelpPage();
                        hp.name = key;
                        Object s = m.get("summary"); hp.summary = s == null ? null : s.toString();
                        Object v = m.get("visibility"); hp.visibility = v == null ? "public" : v.toString();
                        Object syn = m.get("synopsis");
                        if (syn instanceof List) hp.synopsis = (List<String>) syn;
                        Object b = m.get("body"); hp.body = b == null ? null : b.toString();
                        pages.put(key.toLowerCase(), hp);
                }
        }

        public static String getPage(String cmd, boolean isGm) {
                if (cmd == null) return null;
                HelpPage hp = pages.get(cmd.toLowerCase());
                if (hp == null) return null;
                if ("gm".equalsIgnoreCase(hp.visibility) && !isGm) return null;
                return hp.formatManPage();
        }

        public static Map<String, String> getAllPagesFor(boolean isGm) {
                Map<String, String> m = new LinkedHashMap<>();
                for (Map.Entry<String, HelpPage> e : pages.entrySet()) {
                        HelpPage hp = e.getValue();
                        if ("gm".equalsIgnoreCase(hp.visibility) && !isGm) continue;
                        m.put(e.getKey(), hp.summary == null ? "" : hp.summary);
                }
                return m;
        }

        // Backwards-compat: returns only public pages
        public static Map<String, String> getAllPages() {
                return getAllPagesFor(false);
        }
}
