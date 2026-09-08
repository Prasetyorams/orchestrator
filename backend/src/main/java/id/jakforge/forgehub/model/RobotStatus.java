package id.jakforge.forgehub.model;

/**
 * Keadaan sambungan sebuah robot.
 *
 * <p>DISCONNECTED tidak pernah DITULIS oleh robotnya sendiri — robot yang mati
 * tidak sempat memberi tahu bahwa ia mati. Keadaan itu disimpulkan saat dibaca,
 * dari selisih waktu denyut terakhir.
 */
public enum RobotStatus {
    AVAILABLE,
    BUSY,
    DISCONNECTED;

    /**
     * Keadaan yang boleh DILAPORKAN robot lewat denyut.
     *
     * <p>DISCONNECTED tidak termasuk. Robot yang melaporkan dirinya terputus
     * sedang mengaku mati sambil membuktikan bahwa ia hidup, dan yang tersimpan
     * kemudian adalah kolom status yang bertentangan dengan waktu denyutnya.
     */
    public boolean bolehDilaporkan() {
        return this != DISCONNECTED;
    }

    /**
     * Urai dari denyut, dengan AVAILABLE sebagai jatuhan.
     *
     * <p>Tidak menolak: denyut yang ditolak karena satu untai tak dikenal
     * berarti robotnya tampak mati padahal jelas sedang mengirim kabar. Yang
     * salah cuma sebutannya, dan menganggapnya tersedia jauh lebih dekat ke
     * kenyataan daripada menganggapnya tidak ada.
     */
    public static RobotStatus dariDenyut(String teks) {
        if (teks == null || teks.isBlank()) return AVAILABLE;

        for (RobotStatus s : values()) {
            if (s.name().equalsIgnoreCase(teks.trim()) && s.bolehDilaporkan()) return s;
        }

        return AVAILABLE;
    }
}
