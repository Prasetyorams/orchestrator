package id.jakforge.openorchestrator.model;

/** Dari mana sebuah versi paket masuk ke umpan (V17). Disimpan dengan namanya. */
public enum PackageSource {
    /** POST /api/packages — Terbitkan dari OpenStudio (JSON atau multipart). */
    STUDIO,
    /** POST /api/packages/upload — Tenant › Paket › Unggah Paket di dasbor. */
    MANUAL_UPLOAD
}
