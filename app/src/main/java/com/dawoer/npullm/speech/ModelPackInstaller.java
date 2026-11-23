package com.dawoer.npullm.speech;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * vc166: 通用模型包安装器（TTS / ASR 共用），零网络。
 *
 * 入口两条，共用「解到 _tmp → 扫描定位模型根 → 按族校验 → 原子改名」这条流水线：
 *   startPackImport(ctx, uri, kind)  选压缩包：zip（系统 ZipInputStream + CRC）优先，
 *                                    tar.gz（本类自写解析：校验 checksum / PAX 长名 /
 *                                    ustar prefix / 零块 break），tar.bz2 明确拒绝
 *   startDirImport(ctx, treeUri, kind) 选已解压目录（DocumentsContract 递归拷贝）
 *
 * 关键纪律（对 vc165 黑盒失败的修正）：
 *   - 不假设包布局：递归找 model*.onnx 所在目录 = 模型根（扁平/多一层都过）
 *   - 校验不过就不动旧模型：_tmp 留着，UI 列缺失项
 *   - zip 走系统 CRC 校验，tar 走头 checksum 校验；坏包不落盘
 */
public final class ModelPackInstaller {

    public static final String PHASE_IDLE = "idle";
    public static final String PHASE_IMPORT = "import";
    public static final String PHASE_EXTRACT = "extract";
    public static final String PHASE_DONE = "done";
    public static final String PHASE_ERROR = "error";

    /** 进度静态量（UI 进程内轮询；服务进程看不到，也不看——服务只读文件系统）。 */
    public static volatile String phase = PHASE_IDLE;
    public static volatile long bytes = 0L;
    public static volatile long total = 0L;
    public static volatile String error = "";
    public static volatile String lastName = "";

    private static Context app;

    private ModelPackInstaller() {}

    public static void init(Context c) {
        if (c != null) app = c.getApplicationContext();
    }

    // ===================== 入口 =====================

    /** 返回 null=已启动；非 null=立即失败的文案。 */
    public static String startPackImport(Context c, Uri uri, String kind) {
        init(c);
        if (c == null || uri == null) return "未选择文件";
        final String k = SpeechConfig.kindOf(kind);
        reset(PHASE_IMPORT);
        Thread t = new Thread(new PackWorker(c.getApplicationContext(), uri, k), "speech-pack-import");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** 备入口：选已解压的模型文件夹。 */
    public static String startDirImport(Context c, Uri treeUri, String kind) {
        init(c);
        if (c == null || treeUri == null) return "未选择目录";
        final String k = SpeechConfig.kindOf(kind);
        reset(PHASE_IMPORT);
        Thread t = new Thread(new DirWorker(c.getApplicationContext(), treeUri, k), "speech-dir-import");
        t.setDaemon(true);
        t.start();
        return null;
    }

    static void reset(String ph) {
        phase = ph;
        bytes = 0L;
        total = 0L;
        error = "";
        lastName = "";
    }

    // ===================== 状态 =====================

    /** {"phase","bytes","total","error","ready","name","family","speakers","files":[...],"models":[...]} */
    public static synchronized String status(String kind) {
        Context c = app;
        String k = SpeechConfig.kindOf(kind);
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("bytes", bytes);
            o.put("total", total);
            o.put("error", error);
            if (c == null) {
                o.put("ready", false);
                o.put("models", new JSONArray());
                o.put("files", new JSONArray());
                return o.toString();
            }
            File dir = SpeechConfig.activeDir(c, k);
            String name = SpeechConfig.activeName(c, k);
            ModelManifest m = ModelManifest.load(dir);
            String fam = m != null && m.family.length() > 0 ? m.family
                    : ModelManifest.detectFamily(dir, k);
            o.put("name", name);
            o.put("family", fam);
            o.put("family_label", ModelManifest.familyLabel(fam));
            o.put("ready", dir != null && ModelManifest.ready(dir, k));
            o.put("speakers", m != null ? m.speakers : 0);
            o.put("dir", dir == null ? "" : dir.getAbsolutePath());
            JSONArray files = new JSONArray();
            if (dir != null) {
                String[] miss = ModelManifest.missing(dir, fam, m);
                List<String> all = new ArrayList<String>();
                all.add("model*.onnx");
                all.add("tokens.txt");
                if (ModelManifest.FAMILY_VITS.equals(fam)) {
                    all.add("lexicon.txt");
                    all.add("dict/");
                }
                for (int i = 0; i < all.size(); i++) {
                    JSONObject f = new JSONObject();
                    String label = all.get(i);
                    f.put("label", label);
                    f.put("ok", !contains(miss, label));
                    files.put(f);
                }
            }
            o.put("files", files);
            o.put("models", listModels(c, k));
        } catch (Throwable t) {
            return "{\"phase\":\"error\",\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
        return o.toString();
    }

    static boolean contains(String[] a, String v) {
        for (String s : a) {
            if (s.equals(v)) return true;
        }
        return false;
    }

    static JSONArray listModels(Context c, String kind) {
        JSONArray arr = new JSONArray();
        File dir = SpeechConfig.kindDir(c, kind);
        File[] fs = dir.listFiles();
        if (fs == null) return arr;
        String active = SpeechConfig.activeName(c, kind);
        for (File f : fs) {
            if (!f.isDirectory() || f.getName().startsWith("_")) continue;
            try {
                ModelManifest m = ModelManifest.load(f);
                String fam = m != null && m.family.length() > 0 ? m.family
                        : ModelManifest.detectFamily(f, kind);
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("family", fam);
                o.put("family_label", ModelManifest.familyLabel(fam));
                o.put("speakers", m != null ? m.speakers : 0);
                o.put("lang", m != null ? m.lang : "");
                o.put("ready", ModelManifest.ready(f, kind));
                o.put("active", f.getName().equals(active));
                o.put("size_mb", dirSizeMb(f));
                arr.put(o);
            } catch (Throwable ignore) {
            }
        }
        return arr;
    }

    static long dirSizeMb(File d) {
        long n = 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            n += f.isDirectory() ? dirSizeMb(f) * 1024 * 1024 : f.length();
        }
        return n / (1024 * 1024);
    }

    // ===================== 工作线程（named static nested，d8 零匿名类）=====================

    private static final class PackWorker implements Runnable {
        private final Context ctx;
        private final Uri uri;
        private final String kind;

        PackWorker(Context c, Uri u, String k) {
            ctx = c;
            uri = u;
            kind = k;
        }

        @Override public void run() {
            File tmp = null;
            try {
                File base = SpeechConfig.tmpDir(ctx, kind);
                cleanTmp(base);
                tmp = new File(base, "pkg-" + System.currentTimeMillis());
                if (!tmp.mkdirs()) throw new Exception("无法创建临时目录");

                File pkg = new File(base, "pkg.bin");
                copyUri(ctx, uri, pkg);
                total = pkg.length();
                bytes = 0L;
                phase = PHASE_EXTRACT;

                String magic = head2(pkg);
                int entries;
                if ("504b".equals(magic)) {
                    entries = unpackZip(pkg, tmp);
                } else if ("1f8b".equals(magic)) {
                    entries = unpackTarGz(pkg, tmp);
                } else if ("425a".equals(magic)) {
                    throw new Exception("不支持 tar.bz2，请先转成 zip 或 tar.gz");
                } else {
                    throw new Exception("不是 zip / tar.gz 压缩包（魔数 " + magic + "）");
                }
                if (entries <= 0) throw new Exception("压缩包内没有文件");
                commit(tmp, kind, nameFrom(uri, tmp));
            } catch (Throwable t) {
                phase = PHASE_ERROR;
                error = msg(t);
            } finally {
                if (tmp != null) deleteTree(tmp);
            }
        }
    }

    private static final class DirWorker implements Runnable {
        private final Context ctx;
        private final Uri tree;
        private final String kind;

        DirWorker(Context c, Uri t, String k) {
            ctx = c;
            tree = t;
            kind = k;
        }

        @Override public void run() {
            File tmp = null;
            try {
                File base = SpeechConfig.tmpDir(ctx, kind);
                cleanTmp(base);
                tmp = new File(base, "dir-" + System.currentTimeMillis());
                if (!tmp.mkdirs()) throw new Exception("无法创建临时目录");
                copyTree(ctx, tree, tmp);
                commit(tmp, kind, nameFrom(tree, tmp));
            } catch (Throwable t) {
                phase = PHASE_ERROR;
                error = msg(t);
            } finally {
                if (tmp != null) deleteTree(tmp);
            }
        }
    }

    static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    // ===================== 提交 =====================

    /**
     * 扫描定位模型根 → 校验 → 原子改名到 models/<kind>/<name> → 写 active.txt。
     * 校验失败：留 _tmp（调用方 finally 会删，但错误信息已带缺失项），不动旧模型。
     */
    static void commit(File tmp, String kind, String hintName) throws Exception {
        File root = scanModelRoot(tmp);
        if (root == null) throw new Exception("包内找不到 model*.onnx，不是模型包");
        ModelManifest m = ModelManifest.load(root);
        String fam = m != null && m.family.length() > 0 ? m.family
                : ModelManifest.detectFamily(root, kind);
        if (fam.length() == 0) {
            throw new Exception("无法识别模型族（本版只支持 VITS / SenseVoice，请确认是 VITS 系或 SenseVoice 系模型）");
        }
        if (SpeechConfig.KIND_TTS.equals(kind) && !ModelManifest.FAMILY_VITS.equals(fam)) {
            throw new Exception("本版 TTS 只支持 VITS 系模型（识别为 " + fam + "）");
        }
        String[] miss = ModelManifest.missing(root, fam, m);
        if (miss.length > 0) {
            throw new Exception("模型文件不齐，缺：" + join(miss));
        }

        String name = (m != null && m.name.length() > 0) ? m.name : hintName;
        if (name == null || name.trim().length() == 0) name = "model-" + System.currentTimeMillis();
        name = sanitize(name);

        Context c = app;
        File kd = SpeechConfig.kindDir(c, kind);
        if (!kd.isDirectory() && !kd.mkdirs()) throw new Exception("无法创建模型目录");
        File target = new File(kd, name);
        String oldActive = SpeechConfig.activeName(c, kind);

        // 同名旧模型：先删（用户重新导入=升级），失败则整体中止，不留半套
        if (target.exists()) {
            deleteTree(target);
            if (target.exists()) throw new Exception("同名模型目录无法替换：" + name);
        }
        if (!root.renameTo(target)) {
            copyTreeFiles(root, target);
            if (!target.isDirectory()) throw new Exception("模型落盘失败：" + name);
            deleteTree(root);
        }
        SpeechConfig.setActiveName(c, kind, name);
        lastName = name;
        bytes = total = 0L;
        phase = PHASE_DONE;
        error = "";
        if (oldActive.length() > 0 && !oldActive.equals(name)) {
            // 旧模型目录保留（多模型并存），只换 active
        }
    }

    static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(a[i]);
        }
        return sb.toString();
    }

    static String sanitize(String n) {
        String s = n.replace('/', '_').replace('\\', '_').replace("..", "_").trim();
        if (s.startsWith(".")) s = s.substring(1);
        return s.length() == 0 ? "model" : s;
    }

    /** 包名（去扩展名）；用于无 manifest 包的模型目录名。 */
    static String nameFrom(Uri uri, File tmp) {
        String last = uri == null ? "" : String.valueOf(uri.getLastPathSegment());
        int sl = last.lastIndexOf('/');
        if (sl >= 0) last = last.substring(sl + 1);
        int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        last = sanitize(last);
        if (last.length() == 0 || "pkg".equals(last) || "primary".equals(last)) {
            File[] fs = tmp.listFiles();
            if (fs != null && fs.length == 1 && fs[0].isDirectory()) return fs[0].getName();
            return "model-" + System.currentTimeMillis();
        }
        return last;
    }

    // ===================== 扫描定位 =====================

    /** 递归找 model*.onnx 所在目录（最浅优先；不假设包内布局）。 */
    public static File scanModelRoot(File dir) {
        return scanDepth(dir, 0);
    }

    private static File scanDepth(File dir, int depth) {
        if (dir == null || depth > 6) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith("model") && f.getName().endsWith(".onnx")) {
                return dir;
            }
        }
        List<File> subs = new ArrayList<File>();
        for (File f : fs) {
            if (f.isDirectory()) subs.add(f);
        }
        File shallow = null;
        for (File d : subs) {
            File hit = scanDepth(d, depth + 1);
            if (hit != null && (shallow == null
                    || hit.getAbsolutePath().length() < shallow.getAbsolutePath().length())) {
                shallow = hit;
            }
        }
        return shallow;
    }

    // ===================== zip =====================

    /** 串行解 zip；CRC 由 ZipInputStream.closeEntry 校验（坏包抛 ZipException）。 */
    static int unpackZip(File src, File dst) throws Exception {
        int n = 0;
        ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(src), 65536));
        byte[] buf = new byte[65536];
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = safeChild(dst, e.getName());
                if (out == null) throw new Exception("包内路径非法：" + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File p = out.getParentFile();
                    if (p != null && !p.isDirectory()) p.mkdirs();
                    OutputStream os = new FileOutputStream(out);
                    try {
                        int r;
                        while ((r = zin.read(buf)) > 0) {
                            os.write(buf, 0, r);
                            bytes += r;
                        }
                        os.flush();
                    } finally {
                        os.close();
                    }
                    n++;
                }
                zin.closeEntry();
            }
        } finally {
            zin.close();
        }
        return n;
    }

    // ===================== tar.gz =====================

    /**
     * tar 解析（vc165 原版四缺陷全部修掉）：
     *   1. 校验 512 字节头 checksum（错→整包拒绝）
     *   2. PAX 扩展头（type 'x'）里的 path= 长名
     *   3. GNU 长名（type 'L'）
     *   4. ustar prefix（name[345..500]）与 name[0..100] 拼接
     *   5. 零块 break（不再靠 EOF 兜底）
     */
    static int unpackTarGz(File src, File dst) throws Exception {
        InputStream in = new GZIPInputStream(new FileInputStream(src), 65536);
        byte[] hdr = new byte[512];
        byte[] buf = new byte[65536];
        String longName = null;
        String paxPath = null;
        int n = 0;
        try {
            while (true) {
                if (!readFully(in, hdr, 512)) break;
                if (isZeroBlock(hdr)) break;
                if (!checkSum(hdr)) throw new Exception("tar 头校验失败（压缩包损坏）");
                long size = octal(hdr, 124, 12);
                int type = hdr[156] & 0xff;
                if (type == 'x' || type == 'X' || type == 'g') {
                    byte[] pax = new byte[(int) Math.min(size, 1 << 20)];
                    readFully(in, pax, pax.length);
                    skipFully(in, size - pax.length);
                    skipFully(in, pad512(size));
                    paxPath = parsePaxPath(new String(pax, "UTF-8"));
                    continue;
                }
                if (type == 'L') {
                    byte[] nm = new byte[(int) Math.min(size, 65536)];
                    readFully(in, nm, nm.length);
                    skipFully(in, size - nm.length);
                    skipFully(in, pad512(size));
                    longName = new String(nm, "UTF-8").trim();
                    continue;
                }
                String name = tarName(hdr);
                if (paxPath != null) {
                    name = paxPath;
                    paxPath = null;
                } else if (longName != null) {
                    name = longName;
                    longName = null;
                }
                File out = safeChild(dst, name);
                if (out == null) throw new Exception("包内路径非法：" + name);
                if (type == '5') {
                    out.mkdirs();
                    skipFully(in, size + pad512(size));
                } else if (type == '0' || type == 0 || type == 7) {
                    File p = out.getParentFile();
                    if (p != null && !p.isDirectory()) p.mkdirs();
                    OutputStream os = new FileOutputStream(out);
                    try {
                        long left = size;
                        while (left > 0) {
                            int want = (int) Math.min(buf.length, left);
                            int r = in.read(buf, 0, want);
                            if (r <= 0) throw new Exception("tar 数据截断：" + name);
                            os.write(buf, 0, r);
                            left -= r;
                            bytes += r;
                        }
                        os.flush();
                    } finally {
                        os.close();
                    }
                    skipFully(in, pad512(size));
                    n++;
                } else {
                    // 其它类型（符号链接/设备）：跳过数据
                    skipFully(in, size + pad512(size));
                }
            }
        } finally {
            SpeechConfig.closeQuietly(in);
        }
        return n;
    }

    static boolean isZeroBlock(byte[] h) {
        for (int i = 0; i < 512; i++) {
            if (h[i] != 0) return false;
        }
        return true;
    }

    static boolean checkSum(byte[] h) {
        long want = octal(h, 148, 8);
        if (want <= 0) return false;
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
        }
        return sum == want;
    }

    static long octal(byte[] h, int off, int len) {
        long v = 0;
        int i = off;
        while (i < off + len && (h[i] == ' ' || h[i] == 0)) i++;
        for (; i < off + len; i++) {
            int c = h[i] & 0xff;
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    static String tarName(byte[] h) {
        String name = cstr(h, 0, 100);
        String prefix = cstr(h, 345, 155);
        if (prefix.length() > 0) return prefix + "/" + name;
        return name;
    }

    static String cstr(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        try {
            return new String(h, off, end - off, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** PAX 记录集里取 path。记录形如 "30 path=xxx/yyy\n"。 */
    static String parsePaxPath(String s) {
        int i = 0;
        String found = null;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(s.substring(i, sp).trim());
            } catch (Throwable t) {
                break;
            }
            if (len <= 0 || i + len > s.length()) break;
            String rec = s.substring(sp + 1, i + len);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String key = rec.substring(0, eq).trim();
                String val = rec.substring(eq + 1).trim();
                if ("path".equals(key)) found = val;
            }
            i += len;
        }
        return found;
    }

    static int pad512(long size) {
        long m = size % 512;
        return (int) (m == 0 ? 0 : 512 - m);
    }

    static boolean readFully(InputStream in, byte[] b, int len) throws Exception {
        int got = 0;
        while (got < len) {
            int r = in.read(b, got, len - got);
            if (r <= 0) break;
            got += r;
        }
        if (got == 0) return false;
        if (got < len) throw new Exception("tar 头截断");
        return true;
    }

    static void skipFully(InputStream in, long n) throws Exception {
        long left = n;
        byte[] junk = new byte[8192];
        while (left > 0) {
            int r = in.read(junk, 0, (int) Math.min(junk.length, left));
            if (r <= 0) return;
            left -= r;
        }
    }

    static String head2(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            int a = in.read();
            int b = in.read();
            if (a < 0 || b < 0) return "";
            return String.format("%02x%02x", a, b);
        } catch (Throwable t) {
            return "";
        } finally {
            SpeechConfig.closeQuietly(in);
        }
    }

    // ===================== 目录/文件工具 =====================

    /** 拒绝绝对路径与 ../（zip slip / tar slip）。 */
    static File safeChild(File dst, String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        while (n.startsWith("./")) n = n.substring(2);
        if (n.length() == 0) return null;
        String[] parts = n.split("/");
        List<String> keep = new ArrayList<String>();
        for (String p : parts) {
            if (p.length() == 0 || ".".equals(p)) continue;
            if ("..".equals(p)) return null;
            keep.add(p);
        }
        File cur = dst;
        for (String p : keep) cur = new File(cur, p);
        return cur;
    }

    static void copyUri(Context c, Uri uri, File dst) throws Exception {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法读取所选文件");
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            long n = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
                if (n % (1024 * 1024) < 65536) bytes = n;   // 进包拷贝进度
            }
            out.flush();
        } finally {
            SpeechConfig.closeQuietly(in);
            SpeechConfig.closeQuietly(out);
        }
    }

    /** 选已解压目录：DocumentsContract 递归拷贝（不依赖 androidx.documentfile）。 */
    static void copyTree(Context c, Uri treeUri, File dst) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        copyDoc(cr, treeUri, rootDoc, dst, 0);
    }

    private static void copyDoc(ContentResolver cr, Uri treeUri, Uri docUri, File out, int depth)
            throws Exception {
        if (depth > 8) return;
        String name = "";
        String mime = "";
        Cursor cur = null;
        try {
            cur = cr.query(docUri, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                name = cur.getString(0);
                mime = cur.getString(1);
            }
        } finally {
            if (cur != null) cur.close();
        }
        if (name == null) name = "";
        boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        if (isDir) {
            if (!out.isDirectory() && !out.mkdirs()) throw new Exception("无法创建目录 " + name);
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getDocumentId(docUri));
            List<String> ids = new ArrayList<String>();
            Cursor kc = null;
            try {
                kc = cr.query(kids, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
                while (kc != null && kc.moveToNext()) ids.add(kc.getString(0));
            } finally {
                if (kc != null) kc.close();
            }
            for (String id : ids) {
                copyDoc(cr, treeUri, DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        new File(out, name), depth + 1);
            }
            return;
        }
        // 文件：当前 out 是父目录，实际落盘名 = name
        File target = safeChild(out, name);
        if (target == null) return;
        File p = target.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        InputStream in = null;
        OutputStream os = null;
        try {
            in = cr.openInputStream(docUri);
            if (in == null) throw new Exception("无法读取 " + name);
            os = new FileOutputStream(target);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
                bytes += r;
            }
            os.flush();
        } finally {
            SpeechConfig.closeQuietly(in);
            SpeechConfig.closeQuietly(os);
        }
    }

    static void copyTreeFiles(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new Exception("mkdirs " + dst.getName());
            File[] fs = src.listFiles();
            if (fs == null) return;
            for (File f : fs) copyTreeFiles(f, new File(dst, f.getName()));
        } else {
            InputStream in = new FileInputStream(src);
            try {
                OutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        }
    }

    static void cleanTmp(File base) {
        File[] fs = base.listFiles();
        if (fs == null) return;
        for (File f : fs) deleteTree(f);
    }

    /** 递归删目录/文件（vc166: 删模型、清 _tmp 共用）。 */
    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteTree(c);
            }
        }
        if (!f.delete()) f.deleteOnExit();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
