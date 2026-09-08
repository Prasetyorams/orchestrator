# ---------- Tahap 1: bangun ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# pom.xml disalin lebih dulu dan dependensinya diunduh terpisah. Selama
# pom-nya tidak berubah, lapisan ini dipakai ulang — perubahan kode tidak
# memicu unduh ulang seluruh dependensi.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Tahap 2: jalankan ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl dipasang KHUSUS untuk healthcheck.
#
# Image temurin-jre tidak memuat wget maupun curl, dan healthcheck yang
# memanggil alat yang tidak ada gagal dengan cara yang menyesatkan: container
# ditandai unhealthy padahal aplikasinya menjawab 200 dengan baik, lalu
# layanan yang menunggunya menolak start. Yang terlihat "backend gagal", yang
# terjadi "wget: not found".
#
# Dipasang sebelum USER berganti, karena apt butuh root.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Berjalan sebagai pengguna biasa, bukan root: kalau ada celah di aplikasi,
# yang didapat penyerang bukan root di dalam container.
RUN useradd --system --uid 10001 forgehub
USER forgehub

COPY --from=build /build/target/forgehub-1.0.0.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
