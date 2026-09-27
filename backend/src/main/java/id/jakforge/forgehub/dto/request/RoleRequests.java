package id.jakforge.forgehub.dto.request;

/** Batas bersama permintaan membuat dan mengubah peran. */
public final class RoleRequests {

    /** Mengikuti kolom users.role (VARCHAR 48): nama peran disimpan di sana. */
    public static final int MAX_NAME_LENGTH = 48;

    public static final int MAX_DESCRIPTION_LENGTH = 400;

    private RoleRequests() {
    }
}
