package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;

import java.util.UUID;

/**
 * Mencatat kejadian penting supaya muncul di umpan peringatan dasbor.
 *
 * <p>Terpisah dari pencatatan log biasa dengan sengaja. Log adalah jejak apa
 * yang terjadi dan jumlahnya ribuan; peringatan adalah hal yang perlu DILIHAT
 * orang, dan jumlahnya harus tetap sedikit supaya masih ada yang membacanya.
 */
public final class Peringatan {

    private Peringatan() {
    }

    public static void catat(Db db, UUID tenantId,
                             String tingkat, String judul, String pesan, String sumber) {
        db.exec("""
                INSERT INTO alerts (tenant_id, severity, title, message, source, is_read, created_at)
                VALUES (?, ?, ?, ?, ?, FALSE, now())
                """, tenantId, tingkat, judul, pesan, sumber);
    }
}
