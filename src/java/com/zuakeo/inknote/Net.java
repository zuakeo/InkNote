package com.zuakeo.inknote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 转写网关。两条路：
 *   1) 电脑端 inkvoice.py（质量最好：热词 + 润色 + 三重幻觉过滤）
 *   2) 设备直连 Groq（电脑不在时兜底）
 * 返回 null 表示「这次没成功，等会儿重试」；返回 "" 表示「确实没内容」。
 */
public class Net {

    public static final String PREF = "ink";

    private static boolean tlsReady = false;
    private static javax.net.ssl.SSLSocketFactory tlsFactory = null;

    /**
     * 证书指纹固定：只认我们自己的那张自签证书。
     * 没配 tls_pin 就什么都不做（继续用 HTTP）。
     */
    private static void installTls(Context ctx) {
        if (tlsReady) return;
        String pin = prefs(ctx).getString("tls_pin", "");
        if (pin == null || pin.trim().isEmpty()) {
            tlsReady = true;
            return;
        }
        final String want = pin.trim().toLowerCase();
        try {
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c,
                                                       String a) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                       String a)
                                throws java.security.cert.CertificateException {
                            try {
                                java.security.MessageDigest md =
                                        java.security.MessageDigest.getInstance("SHA-256");
                                byte[] d = md.digest(chain[0].getEncoded());
                                StringBuilder sb = new StringBuilder();
                                for (byte b : d) sb.append(String.format("%02x", b));
                                if (!sb.toString().equalsIgnoreCase(want)) {
                                    throw new java.security.cert.CertificateException("pin mismatch");
                                }
                            } catch (java.security.NoSuchAlgorithmException e) {
                                throw new java.security.cert.CertificateException(e);
                            }
                        }
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            }, new java.security.SecureRandom());
            // 只对连电脑用，绝不能设成全局默认 —— 否则连 Groq 也会被我们的指纹拦下
            tlsFactory = sc.getSocketFactory();
            tlsReady = true;
        } catch (Throwable t) {
            android.util.Log.e("InkNote", "TLS 初始化失败", t);
        }
    }

    /** 给「连电脑」的连接装上指纹校验；别的域名不受影响。 */
    private static void applyTls(HttpURLConnection c) {
        if (tlsFactory != null && c instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection h = (javax.net.ssl.HttpsURLConnection) c;
            h.setSSLSocketFactory(tlsFactory);
            h.setHostnameVerifier((host, session) -> true);   // 靠指纹认，不靠域名
        }
    }

    /** 带上访问令牌（第一批加固）。没配令牌就不带，服务端会 401。 */
    private static void auth(Context c, HttpURLConnection conn) {
        String t = prefs(c).getString("token", "");
        if (t != null && !t.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + t.trim());
        }
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 有没有真正的外网（能被系统判定为 validated）。 */
    public static boolean hasInternet(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            return cap != null
                    && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只要挂着网就算（局域网也算），用来判断「值不值得试一次」。 */
    public static boolean hasNetwork(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            return cm.getActiveNetwork() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long lastDiscover = 0;
    private static long lastHotPush = 0;

    /** 主入口。 */
    public static String[] transcribePair(File f, Context ctx) {
        // 1. 电脑
        String pc = prefs(ctx).getString("pc", "");
        if (pc != null && !pc.trim().isEmpty()) {
            String[] pair = viaPc(pc.trim(), f, ctx);
            if (pair == null) android.util.Log.i("InkNote", "电脑没通：" + pc);
            if (pair != null && pair[1] != null && !pair[1].isEmpty()) {
                maybePushHotwords(ctx, pc.trim());
                return pair;
            }
        }
        // 电脑没通：隔一分钟才扫一次局域网，别把电耗在扫描上
        boolean never = pc == null || pc.trim().isEmpty();
        if (never || System.currentTimeMillis() - lastDiscover > 60000) {
            lastDiscover = System.currentTimeMillis();
            String found = discover(pc == null ? "" : pc.trim());
            if (found != null) {
                if (!found.equals(pc)) {
                    prefs(ctx).edit().putString("pc", found).apply();
                }
                String[] pair2 = viaPc(found, f, ctx);
                if (pair2 != null && pair2[1] != null && !pair2[1].isEmpty()) return pair2;
            }
        }
        // 2. 设备直连 Groq
        String key = Vault.read(ctx, "groq");
        if (key == null || key.trim().isEmpty()) {
            android.util.Log.i("InkNote", "手机兜底：没配 Key");
        } else if (!hasInternet(ctx)) {
            android.util.Log.i("InkNote", "手机兜底：没有可用网络");
        } else {
            String t = viaGroq(key.trim(), f, ctx);
            if (t != null) return new String[]{"", t};
            android.util.Log.i("InkNote", "手机兜底：Groq 调用失败");
        }
        return null;
    }

    // ---------------- 电脑端 ----------------

    /** 返回 {原始识别, 润色后}；失败返回 null。 */
    static String[] viaPc(String base, File f, Context ctx) {
        installTls(ctx);
        HttpURLConnection c = null;
        try {
            String root = base;
            if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
            if (!root.startsWith("http")) root = "http://" + root;
            if (root.indexOf(':', 6) < 0) root = root + ":8765";
            String url = root + "/upload?mode=text2&name="
                    + URLEncoder.encode(f.getName(), "UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            applyTls(c);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(90000);
            c.setFixedLengthStreamingMode((int) f.length());
            c.setRequestProperty("Content-Type", "application/octet-stream");
            // 幂等键：同一段录音重传时沿用同一个 ID，电脑端不会重复转写/重复扣额度
            String clip = f.getName();
            int dot = clip.lastIndexOf('.');
            if (dot > 0) clip = clip.substring(0, dot);
            c.setRequestProperty("X-Clip-Id", clip);
            auth(ctx, c);
            OutputStream os = c.getOutputStream();
            FileInputStream is = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            is.close();
            int code = c.getResponseCode();
            if (code == 202) {
                // 同一段录音正在电脑端处理：等它出结果，别重复送云端
                return waitJob(base, clip, ctx);
            }
            if (code != 200) return null;
            String body = readAll(c.getInputStream());
            if (body == null) return null;
            body = body.trim();
            // 旧版接收端不认 mode=text2，会回纯文本 "OK" 或直接给文字
            if ("OK".equalsIgnoreCase(body)) return null;
            if (body.startsWith("{")) {
                try {
                    JSONObject o = new JSONObject(body);
                    return new String[]{o.optString("raw", ""), o.optString("text", "")};
                } catch (Throwable ignored) {
                    return new String[]{"", body};
                }
            }
            return new String[]{"", body};
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 202 之后轮询任务状态，最多等 25 秒。 */
    private static String[] waitJob(String base, String clip, Context ctx) {
        installTls(ctx);
        for (int i = 0; i < 25; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return null;
            }
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/jobs/" + clip).openConnection();
                applyTls(c);
                auth(ctx, c);
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                if (c.getResponseCode() != 200) continue;
                String body = readAll(c.getInputStream());
                if (body == null) continue;
                JSONObject o = new JSONObject(body);
                String st = o.optString("state", "");
                if ("done".equals(st) || "empty".equals(st)) {
                    return new String[]{o.optString("raw", ""), o.optString("text", "")};
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    /** 设置页用：扫局域网的公开入口。 */
    public static String discoverPublic(String current, Context ctx) {
        installTls(ctx);
        return discover(current == null ? "" : current.trim());
    }

    /** 把热词推给电脑，成功返回电脑端文件的 mtime（秒，double），失败 -1。 */
    public static String pushHotwords(Context ctx) {
        String pc = prefs(ctx).getString("pc", "");
        if (pc == null || pc.trim().isEmpty()) return null;
        return pushHotwordsTo(ctx, pc, prefs(ctx).getString("hotwords", DEFAULT_HOTWORDS));
    }

    static String pushHotwordsTo(Context ctx, String pc, String words) {
        installTls(ctx);
        if (pc == null || pc.trim().isEmpty()) return null;
        if (words == null || words.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            String url = normalize(pc.trim()) + "/hotwords";
            byte[] data = words.getBytes("UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            auth(ctx, c);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream());
            if (body == null) return null;
            String v = new JSONObject(body).optString("v", "");
            return v.isEmpty() ? null : v;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 从电脑取当前生效的热词：返回 {文本, mtime秒}，失败 null。 */
    public static String[] fetchHotwords(Context ctx) {
        installTls(ctx);
        String pc = prefs(ctx).getString("pc", "");
        if (pc == null || pc.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            String url = normalize(pc.trim()) + "/hotwords";
            c = (HttpURLConnection) new URL(url).openConnection();
            applyTls(c);
            auth(ctx, c);
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream());
            if (body == null) return null;
            JSONObject o = new JSONObject(body);
            String text = o.optString("text", "");
            String v = o.optString("v", "");
            if (v.isEmpty()) return null;
            return new String[]{text, v};
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 只问版本号（内容哈希，约 30 字节），变了才去拉全文。 */
    public static String fetchHotVersion(Context ctx) {
        installTls(ctx);
        String pc = prefs(ctx).getString("pc", "");
        if (pc == null || pc.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(normalize(pc.trim()) + "/hotwords/version")
                    .openConnection();
            applyTls(c);
            auth(ctx, c);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream());
            if (body == null) return null;
            String v = new JSONObject(body).optString("v", "");
            return v.isEmpty() ? null : v;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 双向同步。核心：先问版本，**没变就直接返回，不做任何读写**。
     * 返回 0=没动 1=从电脑拉到手机 2=从手机推到电脑 -1=电脑不在
     */
    private static final Object HOT_LOCK = new Object();

    /**
     * 双向同步。整个流程加锁：主页 onResume、后台服务、设置页可能同时触发，
     * 不加锁会出现「A 刚推完，B 又拿旧内容拉一遍」的互相覆盖。
     */
    public static int syncHotwords(Context ctx) {
        synchronized (HOT_LOCK) {
            return syncHotwordsLocked(ctx);
        }
    }

    private static int syncHotwordsLocked(Context ctx) {
        String pc = prefs(ctx).getString("pc", "");
        if (!quickReachable(pc)) return -1;

        String v = fetchHotVersion(ctx);
        if (v == null) return -1;

        String mine = prefs(ctx).getString("hotwords", DEFAULT_HOTWORDS);
        if (mine == null) mine = "";
        mine = mine.trim();
        String synced = prefs(ctx).getString("hotwords_synced_text", "");
        String lastV = prefs(ctx).getString("hotwords_pc_mtime", "");

        if (v.equals(lastV)) {
            // 电脑没动：只有手机改了才推
            if (mine.equals(synced)) return 0;
            String nv = pushHotwordsTo(ctx, pc, mine);
            if (nv == null) return -1;
            save(ctx, mine, mine, nv);
            return 2;
        }

        String[] got = fetchHotwords(ctx);
        if (got == null) return -1;
        String pcText = got[0] == null ? "" : got[0].trim();
        String pcV = got[1];

        if (pcText.equals(mine)) {
            save(ctx, mine, mine, pcV);
            return 0;
        }

        boolean localChanged = !mine.equals(synced);
        boolean pcChanged = !pcText.equals(synced);

        if (pcChanged && localChanged) {
            // 两边都动过：以「上次同步的文本」为基线做三方合并，删除也算数
            String merged = merge3(synced, pcText, mine);
            save(ctx, merged, merged, lastV);
            String nv = pushHotwordsTo(ctx, pc, merged);
            if (nv != null) save(ctx, merged, merged, nv);
            return 3;
        }
        if (pcChanged) {
            // 只有电脑改了：照它来（空 = 明确清空，也要同步过来）
            save(ctx, pcText, pcText, pcV);
            return 1;
        }
        // 只有手机改了
        String nv = pushHotwordsTo(ctx, pc, mine);
        if (nv == null) return -1;
        save(ctx, mine, mine, nv);
        return 2;
    }

    private static void save(Context ctx, String words, String syncedText, String ver) {
        prefs(ctx).edit()
                .putString("hotwords", words)
                .putString("hotwords_synced_text", syncedText)
                .putString("hotwords_pc_mtime", ver)
                .apply();
    }

    private static java.util.LinkedHashSet<String> wordsOf(String s) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (s == null) return set;
        for (String w : s.replace("、", "\n").split("\n")) {
            String t = w.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private static String join(java.util.Collection<String> c) {
        StringBuilder sb = new StringBuilder();
        for (String w : c) {
            if (sb.length() > 0) sb.append('、');
            sb.append(w);
        }
        return sb.toString();
    }

    /**
     * 三方合并：基线 base 是上次同步时的文本。
     * 两边各自的新增都保留；任何一边删掉的词都不会复活。
     */
    static String merge3(String base, String a, String b) {
        java.util.LinkedHashSet<String> bs = wordsOf(base);
        java.util.LinkedHashSet<String> as = wordsOf(a);
        java.util.LinkedHashSet<String> bset = wordsOf(b);

        java.util.LinkedHashSet<String> delA = new java.util.LinkedHashSet<>(bs);
        delA.removeAll(as);          // 电脑删掉的
        java.util.LinkedHashSet<String> delB = new java.util.LinkedHashSet<>(bs);
        delB.removeAll(bset);        // 手机删掉的

        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String w : as) if (!delB.contains(w)) out.add(w);
        for (String w : bset) if (!delA.contains(w)) out.add(w);
        return join(out);
    }

    /** 合并两份热词：电脑的在前，手机独有的补在后面，去重。 */
    private static String mergeWords(String a, String b) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String src : new String[]{a, b}) {
            if (src == null) continue;
            for (String w : src.replace("、", "\n").split("\n")) {
                String t = w.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String w : set) {
            if (sb.length() > 0) sb.append('、');
            sb.append(w);
        }
        return sb.toString();
    }

    private static double parseMtime(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%.3f", d);
    }


    /** 几百毫秒判断电脑在不在，省掉 4 秒的连接超时。 */
    public static boolean quickReachable(String addr) {
        if (addr == null || addr.trim().isEmpty()) return false;
        try {
            String a = addr.trim();
            if (!a.startsWith("http")) a = "http://" + a;
            String host = new URL(a).getHost();
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, 8765), 500);
                return true;
            } finally {
                try { s.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static String normalize(String addr) {
        String root = addr;
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (!root.startsWith("http")) root = "http://" + root;
        if (root.indexOf(':', 6) < 0) root = root + ":8765";
        return root;
    }

    /** 设置页用：测一下能不能连上电脑。 */
    public static boolean pingPublic(String addr, Context ctx) {
        installTls(ctx);
        if (addr == null || addr.trim().isEmpty()) return false;
        try {
            String a = normalize(addr.trim());
            if (a.startsWith("https")) {
                // 开了加密就不能再用裸 socket 发明文，走一次真正的 GET
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(a + "/ping").openConnection();
                    applyTls(c);
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(2000);
                    if (c.getResponseCode() != 200) return false;
                    String body = readAll(c.getInputStream());
                    return body != null && body.contains("inkvoice");
                } finally {
                    if (c != null) c.disconnect();
                }
            }
            return probe(new URL(a).getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 转写成功后顺手同步一次热词（有节流，不会频繁）。 */
    private static void maybePushHotwords(Context ctx, String pc) {
        if (System.currentTimeMillis() - lastHotPush < 10 * 60 * 1000) return;
        lastHotPush = System.currentTimeMillis();
        syncHotwords(ctx);
    }

    /** 扫同网段找 8765 上的 inkvoice。 */
    private static String discover(String current) {
        String host;
        try {
            host = new URL(current.startsWith("http") ? current : "http://" + current).getHost();
        } catch (Throwable t) {
            host = null;
        }
        if (host == null || host.indexOf('.') < 0) {
            // 地址栏是空的就按本机所在网段扫
            String prefix = localPrefix();
            if (prefix == null) return null;
            return scanPrefix(prefix);
        }
        String prefix = host.substring(0, host.lastIndexOf('.') + 1);
        return scanPrefix(prefix);
    }

    /** 本机所在网段，形如 192.168.1. */
    private static String localPrefix() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> ia = ni.getInetAddresses();
                while (ia.hasMoreElements()) {
                    java.net.InetAddress a = ia.nextElement();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("10.") || ip.startsWith("192.168.")
                            || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                            || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                            || ip.startsWith("172.2")) {
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String scanPrefix(String prefix) {
        final String[] hit = new String[1];
        Thread[] pool = new Thread[24];
        for (int t = 0; t < pool.length; t++) {
            final int off = t;
            pool[t] = new Thread(() -> {
                for (int i = 1 + off; i <= 254 && hit[0] == null; i += 24) {
                    String h = prefix + i;
                    if (probe(h)) {
                        synchronized (hit) {
                            if (hit[0] == null) hit[0] = "http://" + h + ":8765";
                        }
                        return;
                    }
                }
            });
            pool[t].start();
        }
        for (Thread t : pool) {
            try { t.join(4000); } catch (InterruptedException ignored) { }
        }
        return hit[0];
    }

    private static boolean probe(String host) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, 8765), 200);
            s.setSoTimeout(700);
            OutputStream os = s.getOutputStream();
            os.write("GET / HTTP/1.0\r\n\r\n".getBytes("UTF-8"));
            os.flush();
            InputStream is = s.getInputStream();
            byte[] buf = new byte[1024];
            StringBuilder sb = new StringBuilder();
            long end = System.currentTimeMillis() + 1500;
            // 响应头和正文常分两个包到，必须循环读，只读一次会误判「连不上」
            while (System.currentTimeMillis() < end && sb.length() < 8192) {
                int n = is.read(buf);
                if (n <= 0) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.indexOf("inkvoice") >= 0) return true;
            }
            return sb.indexOf("inkvoice") >= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------- Groq 直连 ----------------

    private static String viaGroq(String key, File f, Context ctx) {
        HttpURLConnection c = null;
        try {
            String boundary = "----inknote" + System.currentTimeMillis();
            String model = prefs(ctx).getString("model", "whisper-large-v3-turbo");
            String hot = prefs(ctx).getString("hotwords", Net.DEFAULT_HOTWORDS);

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeField(body, boundary, "model", model);
            writeField(body, boundary, "language", "zh");
            writeField(body, boundary, "response_format", "verbose_json");
            if (hot != null && !hot.trim().isEmpty()) {
                writeField(body, boundary, "prompt", hot.trim());
            }
            body.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
            body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + f.getName() + "\"\r\n").getBytes("UTF-8"));
            body.write("Content-Type: audio/mp4\r\n\r\n".getBytes("UTF-8"));
            FileInputStream is = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) body.write(buf, 0, n);
            is.close();
            body.write("\r\n".getBytes("UTF-8"));
            body.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
            byte[] data = body.toByteArray();

            c = (HttpURLConnection) new URL(
                    "https://api.groq.com/openai/v1/audio/transcriptions").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(90000);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("User-Agent", UA);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code != 200) return null;
            String raw = readAll(c.getInputStream());
            if (raw == null) return null;

            JSONObject j = new JSONObject(raw);
            String text = j.optString("text", "");
            // 幻觉过滤：模型自己说没人声、或没把握、或复读
            JSONArray segs = j.optJSONArray("segments");
            if (segs != null && segs.length() > 0) {
                double noSpeech = 0, logprob = 0;
                for (int i = 0; i < segs.length(); i++) {
                    JSONObject s = segs.getJSONObject(i);
                    noSpeech = Math.max(noSpeech, s.optDouble("no_speech_prob", 0));
                    logprob += s.optDouble("avg_logprob", 0);
                }
                logprob = logprob / segs.length();
                if (noSpeech >= 0.6 || logprob < -1.0) return "";
            }
            return text == null ? "" : text.trim();
        } catch (Throwable t) {
            android.util.Log.e("InkNote", "Groq 异常", t); return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes("UTF-8"));
        out.write(value.getBytes("UTF-8"));
        out.write("\r\n".getBytes("UTF-8"));
    }

    private static String readAll(InputStream is) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public static final String DEFAULT_HOTWORDS =
            "劳务公司、签字、护照复印件、签证材料、施工日志、劳务人员、工资表、交到办公室、递交、项目部";
}
