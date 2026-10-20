package id.jakforge.openorchestrator.support;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server LDAPS kecil yang meniru Windows AD — hanya untuk uji, tanpa pustaka.
 *
 * <p>Yang ditiru adalah yang dipakai klien OpenOrchestrator: bind sederhana
 * (DN atau UPN nama@akhiran), kode galat AD di pesan bind ("data 52e", "data
 * 533", "data 775"), pencarian dengan filter persamaan/AND/OR/NOT/ada, matching
 * rule keanggotaan bersarang 1.2.840.113556.1.4.1941, objectGUID biner, dan
 * pencarian yang ditolak sebelum bind.
 *
 * <p>Sengaja MENERIMA bind dengan sandi kosong ("unauthenticated bind"), seperti
 * server yang paling longgar — klien harus menolaknya sendiri.
 *
 * <p>Sertifikat TLS dibuat {@code keytool} saat mulai, dengan SAN yang diminta;
 * {@link #trustPem()} berisi sertifikatnya untuk dipakai sebagai AD_TRUST_STORE.
 */
public final class FakeActiveDirectory implements AutoCloseable {

    public static final String SUFFIX = "DC=uji,DC=local";
    public static final String UPN_SUFFIX = "uji.local";
    public static final String USERS = "OU=Users," + SUFFIX;
    public static final String GROUPS = "OU=Groups," + SUFFIX;
    public static final String SERVICE_DN = "CN=svc-oo,OU=Service," + SUFFIX;
    public static final String SERVICE_PASSWORD = "sandi-layanan";

    /** Entri direktori: atribut (nama kecil → nilai), sandi, dan status AD. */
    public static final class Entry {
        final String dn;
        final Map<String, List<byte[]>> attributes = new LinkedHashMap<>();
        volatile String password;
        volatile boolean locked;

        Entry(String dn) {
            this.dn = dn;
            put("distinguishedName", dn);
        }

        public Entry put(String name, String... values) {
            List<byte[]> list = new ArrayList<>();
            for (String v : values) list.add(v.getBytes(StandardCharsets.UTF_8));
            attributes.put(name.toLowerCase(Locale.ROOT), list);
            return this;
        }

        Entry putBytes(String name, byte[] value) {
            attributes.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(List.of(value)));
            return this;
        }

        String first(String name) {
            List<byte[]> v = attributes.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : new String(v.getFirst(), StandardCharsets.UTF_8);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** Setiap bind yang sampai ke server: "nama|sandi kosong?". */
    public final List<String> binds = new CopyOnWriteArrayList<>();
    private final SSLServerSocket server;
    private final Path folder;
    private final Path trustPem;
    private volatile boolean running = true;

    public FakeActiveDirectory(String... subjectAltNames) throws Exception {
        this(0, subjectAltNames);
    }

    public FakeActiveDirectory(int port, String... subjectAltNames) throws Exception {
        folder = Files.createTempDirectory("oo-ad-uji-");
        Path keystore = folder.resolve("dc.p12");
        trustPem = folder.resolve("ad-ca.pem");
        String san = String.join(",", subjectAltNames.length == 0 ? new String[]{"dns:localhost", "ip:127.0.0.1"} : subjectAltNames);
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        run(keytool, "-genkeypair", "-alias", "dc", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=dc01.uji.local", "-ext", "SAN=" + san, "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "uji-uji", "-keypass", "uji-uji");
        run(keytool, "-exportcert", "-rfc", "-alias", "dc", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", "uji-uji", "-file", trustPem.toString());

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "uji-uji".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "uji-uji".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);

        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        Thread acceptor = new Thread(this::acceptLoop, "fake-ad-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        seed();
    }

    public int port() {
        return server.getLocalPort();
    }

    public String url() {
        return "ldaps://localhost:" + port();
    }

    public Path trustPem() {
        return trustPem;
    }

    // ------------------------------------------------------------------ isi direktori

    private void seed() {
        add(new Entry(SERVICE_DN).put("objectClass", "user"), SERVICE_PASSWORD);
        addGroup("CN=OpenOrchestratorUsers," + GROUPS, "CN=TimRPA," + GROUPS);
        addGroup("CN=TimRPA," + GROUPS);
        addGroup("CN=Keuangan," + GROUPS);
    }

    /** Pengguna AD baru dengan GUID acak. */
    public Entry addUser(String sam, String password, String displayName, String... groups) {
        return addUser(sam, password, displayName, UUID.randomUUID(), groups);
    }

    public Entry addUser(String sam, String password, String displayName, UUID guid, String... groups) {
        String dn = "CN=" + displayName + "," + USERS;
        Entry e = new Entry(dn)
                .put("objectClass", "top", "person", "organizationalPerson", "user")
                .put("objectCategory", "CN=Person,CN=Schema,CN=Configuration," + SUFFIX)
                .put("sAMAccountName", sam)
                .put("userPrincipalName", sam + "@" + UPN_SUFFIX)
                .put("displayName", displayName)
                .put("mail", sam + "@contoh.test")
                .put("userAccountControl", "512")
                .putBytes("objectGUID", guidBytes(guid));
        add(e, password);
        for (String g : groups) addMember(g, dn);
        return e;
    }

    public void removeUser(String sam) {
        entries.values().removeIf(e -> sam.equalsIgnoreCase(e.first("sAMAccountName")));
    }

    public void disable(String sam) {
        user(sam).put("userAccountControl", "514");
    }

    public void lock(String sam) {
        user(sam).locked = true;
    }

    public Entry user(String sam) {
        return entries.values().stream().filter(e -> sam.equalsIgnoreCase(e.first("sAMAccountName"))).findFirst()
                .orElseThrow();
    }

    public void addGroup(String dn, String... members) {
        add(new Entry(dn).put("objectClass", "top", "group").put("member", members), null);
    }

    public void addMember(String groupDn, String memberDn) {
        Entry g = entries.get(groupDn.toLowerCase(Locale.ROOT));
        List<byte[]> members = g.attributes.computeIfAbsent("member", k -> new ArrayList<>());
        members.add(memberDn.getBytes(StandardCharsets.UTF_8));
    }

    private void add(Entry e, String password) {
        e.password = password;
        entries.put(e.dn.toLowerCase(Locale.ROOT), e);
    }

    /** objectGUID dalam urutan bita AD (tiga bagian pertama little-endian). */
    public static byte[] guidBytes(UUID guid) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16);
        long high = guid.getMostSignificantBits();
        b.order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt((int) (high >>> 32)).putShort((short) (high >>> 16)).putShort((short) high);
        b.order(java.nio.ByteOrder.BIG_ENDIAN).putLong(guid.getLeastSignificantBits());
        return b.array();
    }

    // ------------------------------------------------------------------ jaringan

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "fake-ad-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket socket) {
        Entry bound = null;
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            while (true) {
                Node message = Node.read(in);
                if (message == null) return;
                int id = (int) message.children.get(0).integer();
                Node op = message.children.get(1);

                switch (op.tag) {
                    case 0x60 -> bound = bind(id, op, out);
                    case 0x42 -> {
                        return; // unbind
                    }
                    case 0x63 -> search(id, op, bound, out);
                    default -> { /* abandon dan lainnya: diabaikan */ }
                }
            }
        } catch (IOException ignored) {
            // Sambungan ditutup klien.
        }
    }

    private Entry bind(int id, Node op, OutputStream out) throws IOException {
        String name = op.children.get(1).string();
        Node auth = op.children.get(2);
        String password = new String(auth.value, StandardCharsets.UTF_8);
        binds.add(name + "|" + (password.isEmpty() ? "kosong" : "berisi"));

        if (password.isEmpty()) {
            // Unauthenticated bind: "berhasil" tanpa identitas — kondisi terburuk.
            write(out, id, 0x61, ldapResult(0, ""));
            return null;
        }

        Entry e = findByBindName(name);
        if (e == null || e.password == null || !e.password.equals(password)) {
            write(out, id, 0x61, ldapResult(49, adError("52e")));
            return null;
        }
        if (e.locked) {
            write(out, id, 0x61, ldapResult(49, adError("775")));
            return null;
        }
        String uac = e.first("userAccountControl");
        if (uac != null && (Integer.parseInt(uac) & 2) != 0) {
            write(out, id, 0x61, ldapResult(49, adError("533")));
            return null;
        }
        write(out, id, 0x61, ldapResult(0, ""));
        return e;
    }

    private static String adError(String code) {
        return "80090308: LdapErr: DSID-0C09042A, comment: AcceptSecurityContext error, data " + code + ", v4563";
    }

    private Entry findByBindName(String name) {
        Entry byDn = entries.get(name.toLowerCase(Locale.ROOT));
        if (byDn != null) return byDn;
        return entries.values().stream().filter(e -> name.equalsIgnoreCase(e.first("userPrincipalName"))).findFirst().orElse(null);
    }

    private void search(int id, Node op, Entry bound, OutputStream out) throws IOException {
        if (bound == null) {
            write(out, id, 0x65, ldapResult(1, "000004DC: LdapErr: DSID-0C090A5C, comment: In order to perform this "
                    + "operation a successful bind must be completed on the connection., data 0, v4563"));
            return;
        }

        String base = op.children.get(0).string().toLowerCase(Locale.ROOT);
        int scope = (int) op.children.get(1).integer();
        int sizeLimit = (int) op.children.get(3).integer();
        Node filter = op.children.get(6);
        Set<String> wanted = new HashSet<>();
        for (Node a : op.children.get(7).children) wanted.add(a.string().toLowerCase(Locale.ROOT));

        if (scope == 0 && !entries.containsKey(base)) {
            write(out, id, 0x65, ldapResult(32, "0000208D: NameErr: DSID-03100241, problem 2001 (NO_OBJECT)"));
            return;
        }

        int sent = 0;
        for (Entry e : List.copyOf(entries.values())) {
            String dn = e.dn.toLowerCase(Locale.ROOT);
            boolean inScope = switch (scope) {
                case 0 -> dn.equals(base);
                case 1 -> dn.endsWith("," + base) && dn.substring(0, dn.length() - base.length() - 1).indexOf(",") < 0;
                default -> dn.equals(base) || dn.endsWith("," + base);
            };
            if (!inScope || !matches(filter, e)) continue;

            if (sizeLimit > 0 && sent >= sizeLimit) {
                write(out, id, 0x65, ldapResult(4, "Size limit exceeded"));
                return;
            }
            write(out, id, 0x64, entry(e, wanted));
            sent++;
        }
        write(out, id, 0x65, ldapResult(0, ""));
    }

    private boolean matches(Node f, Entry e) {
        return switch (f.tag) {
            case 0xA0 -> f.children.stream().allMatch(c -> matches(c, e));
            case 0xA1 -> f.children.stream().anyMatch(c -> matches(c, e));
            case 0xA2 -> !matches(f.children.getFirst(), e);
            case 0x87 -> e.attributes.containsKey(new String(f.value, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT));
            case 0xA3 -> equality(e, f.children.get(0).string(), f.children.get(1).value);
            case 0xA9 -> extensible(f, e);
            default -> false;
        };
    }

    private static boolean equality(Entry e, String attribute, byte[] value) {
        List<byte[]> values = e.attributes.get(attribute.toLowerCase(Locale.ROOT));
        if (values == null) return false;
        String text = new String(value, StandardCharsets.UTF_8);
        for (byte[] v : values) {
            if (Arrays.equals(v, value)) return true;
            if (attribute.equalsIgnoreCase("objectCategory") && text.equalsIgnoreCase("person")
                    && new String(v, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).startsWith("cn=person,")) return true;
            if (new String(v, StandardCharsets.UTF_8).equalsIgnoreCase(text)) return true;
        }
        return false;
    }

    /** (member:1.2.840.113556.1.4.1941:=DN): anggota langsung atau lewat grup bersarang. */
    private boolean extensible(Node f, Entry e) {
        String rule = null, type = null;
        byte[] value = null;
        for (Node c : f.children) {
            if (c.tag == 0x81) rule = new String(c.value, StandardCharsets.UTF_8);
            if (c.tag == 0x82) type = new String(c.value, StandardCharsets.UTF_8);
            if (c.tag == 0x83) value = c.value;
        }
        if (!"1.2.840.113556.1.4.1941".equals(rule) || !"member".equalsIgnoreCase(type) || value == null) return false;
        return memberInChain(e, new String(value, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT), new HashSet<>());
    }

    private boolean memberInChain(Entry group, String memberDn, Set<String> seen) {
        if (!seen.add(group.dn.toLowerCase(Locale.ROOT))) return false;
        for (byte[] m : group.attributes.getOrDefault("member", List.of())) {
            String dn = new String(m, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (dn.equals(memberDn)) return true;
            Entry nested = entries.get(dn);
            if (nested != null && memberInChain(nested, memberDn, seen)) return true;
        }
        return false;
    }

    private static byte[] entry(Entry e, Set<String> wanted) {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        for (Map.Entry<String, List<byte[]>> a : e.attributes.entrySet()) {
            if (!wanted.isEmpty() && !wanted.contains(a.getKey())) continue;
            ByteArrayOutputStream vals = new ByteArrayOutputStream();
            for (byte[] v : a.getValue()) vals.writeBytes(tlv(0x04, v));
            String name = originalName(a.getKey());
            attrs.writeBytes(tlv(0x30, concat(tlv(0x04, name.getBytes(StandardCharsets.UTF_8)), tlv(0x31, vals.toByteArray()))));
        }
        return concat(tlv(0x04, e.dn.getBytes(StandardCharsets.UTF_8)), tlv(0x30, attrs.toByteArray()));
    }

    /** Nama atribut seperti ejaan AD — JNDI membaca biner berdasarkan nama "objectGUID". */
    private static String originalName(String lower) {
        for (String n : List.of("objectGUID", "sAMAccountName", "userPrincipalName", "displayName", "mail",
                "distinguishedName", "userAccountControl", "objectClass", "objectCategory", "member")) {
            if (n.equalsIgnoreCase(lower)) return n;
        }
        return lower;
    }

    private static byte[] ldapResult(int code, String diagnostic) {
        return concat(tlv(0x0A, integer(code)), tlv(0x04, new byte[0]), tlv(0x04, diagnostic.getBytes(StandardCharsets.UTF_8)));
    }

    private static synchronized void write(OutputStream out, int id, int tag, byte[] body) throws IOException {
        out.write(tlv(0x30, concat(tlv(0x02, integer(id)), tlv(tag, body))));
        out.flush();
    }

    // ------------------------------------------------------------------ BER

    static final class Node {
        final int tag;
        final byte[] value;
        final List<Node> children;

        Node(int tag, byte[] value, List<Node> children) {
            this.tag = tag;
            this.value = value;
            this.children = children;
        }

        static Node read(InputStream in) throws IOException {
            int tag = in.read();
            if (tag < 0) return null;
            int length = readLength(in);
            byte[] value = in.readNBytes(length);
            if (value.length < length) throw new EOFException();
            return parse(tag, value);
        }

        static Node parse(int tag, byte[] value) throws IOException {
            if ((tag & 0x20) == 0) return new Node(tag, value, Collections.emptyList());
            List<Node> children = new ArrayList<>();
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(value);
            while (in.available() > 0) children.add(read(in));
            return new Node(tag, value, children);
        }

        long integer() {
            long v = value.length > 0 && value[0] < 0 ? -1 : 0;
            for (byte b : value) v = (v << 8) | (b & 0xFF);
            return v;
        }

        String string() {
            return new String(value, StandardCharsets.UTF_8);
        }

        private static int readLength(InputStream in) throws IOException {
            int first = in.read();
            if (first < 0) throw new EOFException();
            if (first < 0x80) return first;
            int n = first & 0x7F, length = 0;
            for (int i = 0; i < n; i++) length = (length << 8) | in.read();
            return length;
        }
    }

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else if (n < 0x10000) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        } else {
            out.write(0x84);
            out.write(n >> 24);
            out.write(n >> 16);
            out.write(n >> 8);
            out.write(n);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    static byte[] integer(long v) {
        byte[] full = java.nio.ByteBuffer.allocate(8).putLong(v).array();
        int i = 0;
        while (i < 7 && ((full[i] == 0 && full[i + 1] >= 0) || (full[i] == -1 && full[i + 1] < 0))) i++;
        return Arrays.copyOfRange(full, i, 8);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + " gagal: " + output);
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }

    /**
     * Untuk uji ujung-ke-ujung: jalankan sebagai proses tersendiri.
     * Argumen: port, lalu SAN (mis. dns:host.docker.internal). Isi awalnya:
     * dina/Sandi-Dina-1 (grup TimRPA, bersarang di OpenOrchestratorUsers),
     * budi/Sandi-Budi-1 (grup Keuangan), nonaktif/Sandi-Non-1 (dinonaktifkan).
     * Perintah lewat stdin: "recreate dina", "remove budi", "lock dina", "stop".
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        FakeActiveDirectory ad = new FakeActiveDirectory(port, Arrays.copyOfRange(args, 1, args.length));
        ad.addUser("dina", "Sandi-Dina-1", "Dina Aulia", "CN=TimRPA," + GROUPS);
        ad.addUser("budi", "Sandi-Budi-1", "Budi Santoso", "CN=Keuangan," + GROUPS);
        ad.addUser("nonaktif", "Sandi-Non-1", "Akun Nonaktif");
        ad.disable("nonaktif");
        System.out.println("TRUST=" + ad.trustPem());
        System.out.println("SIAP port " + ad.port());
        System.out.flush();

        java.io.BufferedReader stdin = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = stdin.readLine()) != null; ) {
            String[] cmd = line.trim().split("\\s+");
            switch (cmd[0]) {
                case "recreate" -> {
                    ad.removeUser(cmd[1]);
                    ad.addUser(cmd[1], "Sandi-Baru-1", "Dibuat Ulang " + cmd[1], "CN=TimRPA," + GROUPS);
                }
                case "remove" -> ad.removeUser(cmd[1]);
                case "lock" -> ad.lock(cmd[1]);
                case "stop" -> {
                    ad.close();
                    return;
                }
                default -> { }
            }
            System.out.println("OK " + line);
            System.out.flush();
        }
    }

    /** Port bebas untuk uji "AD tidak terjangkau". */
    public static int unusedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
