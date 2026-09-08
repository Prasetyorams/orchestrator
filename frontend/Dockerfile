# ---------- Tahap 1: dependensi ----------
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

# ---------- Tahap 2: bangun ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# URL API dibaca saat BANGUN karena NEXT_PUBLIC_ ditanam ke berkas
# JavaScript-nya. Mengubahnya berarti membangun ulang image — itu memang
# sifat variabel NEXT_PUBLIC_, bukan kelalaian.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

RUN npm run build

# ---------- Tahap 3: jalankan ----------
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production

RUN addgroup -g 10001 nodejs && adduser -S -u 10001 -G nodejs nextjs

COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
