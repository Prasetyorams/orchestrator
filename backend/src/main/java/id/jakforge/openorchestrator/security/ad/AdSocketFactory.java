package id.jakforge.openorchestrator.security.ad;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Pabrik soket TLS untuk JNDI LDAPS dengan trust store milik Windows AD
 * (AD_TRUST_STORE), tanpa mengubah trust store seluruh JVM.
 *
 * <p>JNDI hanya menerima NAMA kelas ({@code java.naming.ldap.factory.socket})
 * dan memanggil {@code getDefault()}-nya di utas yang sedang menyambung, jadi
 * {@link ActiveDirectory} memasang konteks TLS-nya untuk utas itu selama
 * menyambung ({@link #during}). Pemeriksaan nama server
 * (endpoint identification "LDAPS") tetap dilakukan JNDI sendiri untuk soket
 * ini; kelas ini tidak melonggarkan apa pun.
 */
public final class AdSocketFactory extends SSLSocketFactory {

    private static final ThreadLocal<SSLSocketFactory> CURRENT = new ThreadLocal<>();

    private final SSLSocketFactory delegate;

    private AdSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    /** Jalankan {@code action} (yang membuka sambungan JNDI) dengan pabrik ini untuk utas sekarang. */
    static <T, E extends Exception> T during(SSLSocketFactory factory, Action<T, E> action) throws E {
        SSLSocketFactory previous = CURRENT.get();
        CURRENT.set(factory);
        try {
            return action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    interface Action<T, E extends Exception> {
        T run() throws E;
    }

    /** Dipanggil JNDI lewat refleksi. */
    public static SocketFactory getDefault() {
        SSLSocketFactory factory = CURRENT.get();
        if (factory == null) throw new IllegalStateException("Trust store Windows AD belum disiapkan untuk utas ini.");
        return new AdSocketFactory(factory);
    }

    private SSLSocketFactory delegate() {
        return delegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate().getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate().getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate().createSocket();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate().createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate().createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate().createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate().createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate().createSocket(address, port, localAddress, localPort);
    }
}
