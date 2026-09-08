package id.jakforge.forgehub.common;

/** Sumber daya tidak ada, atau bukan milik tenant yang meminta. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super(what + " tidak ditemukan.");
    }
}
