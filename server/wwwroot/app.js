/*
   ForgeHub — dasbor.

   Ditulis sebagai satu halaman JavaScript biasa, tanpa kerangka kerja dan
   tanpa langkah pembangunan. Alasannya sederhana: ForgeHub harus bisa dijalankan
   dengan satu perintah di komputer yang belum tentu punya Node terpasang. Berkas
   ini disajikan apa adanya oleh server yang sama dengan API-nya.
*/

'use strict';

// =====================================================================
// Keadaan
// =====================================================================

const S = {
    token: null,
    user: null,
    page: 'dashboard',
    timer: null,
    logAfterId: 0,
    logLines: [],
};

const KEY = 'forgehub.token';

// =====================================================================
// Pembantu
// =====================================================================

const $ = (id) => document.getElementById(id);

/** Semua teks dari basis data melewati sini sebelum masuk innerHTML. */
function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function toast(message, kind) {
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = message;
    $('toasts').appendChild(el);
    setTimeout(() => el.remove(), 4200);
}

/**
 * Waktu dari server selalu UTC dengan akhiran Z. Yang ditampilkan adalah waktu
 * setempat pembacanya — orang membaca dasbor menurut jam dindingnya sendiri.
 */
function when(iso) {
    if (!iso) return '—';
    const d = new Date(iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z');
    if (isNaN(d)) return esc(iso);
    return d.toLocaleString('id-ID', { dateStyle: 'medium', timeStyle: 'short' });
}

function clock(iso) {
    if (!iso) return '—';
    const d = new Date(iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z');
    return isNaN(d) ? esc(iso) : d.toLocaleTimeString('id-ID', { hour12: false });
}

function ago(iso) {
    if (!iso) return 'belum pernah';
    const d = new Date(iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z');
    const seconds = Math.floor((Date.now() - d.getTime()) / 1000);

    if (seconds < 0) return 'sebentar lagi';
    if (seconds < 60) return seconds + ' detik lalu';
    if (seconds < 3600) return Math.floor(seconds / 60) + ' menit lalu';
    if (seconds < 86400) return Math.floor(seconds / 3600) + ' jam lalu';
    return Math.floor(seconds / 86400) + ' hari lalu';
}

function bytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
}

// =====================================================================
// Panggilan API
// =====================================================================

async function api(path, options) {
    options = options || {};

    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    if (S.token) headers['Authorization'] = 'Bearer ' + S.token;

    const response = await fetch(path, {
        method: options.method || 'GET',
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });

    // Token kedaluwarsa mengembalikan orangnya ke layar masuk, bukan ke halaman
    // kosong tanpa penjelasan.
    if (response.status === 401 && S.token) {
        signOut('Sesi berakhir. Silakan masuk lagi.');
        throw new Error('401');
    }

    const text = await response.text();
    let data = null;

    if (text) {
        try { data = JSON.parse(text); } catch (e) { data = { raw: text }; }
    }

    if (!response.ok) {
        const message = (data && data.error) || ('Permintaan gagal (' + response.status + ').');
        const error = new Error(message);
        error.status = response.status;
        throw error;
    }

    return data;
}

// =====================================================================
// Masuk dan keluar
// =====================================================================

$('loginForm').addEventListener('submit', async (event) => {
    event.preventDefault();

    const button = $('loginBtn');
    const error = $('loginError');

    button.disabled = true;
    button.textContent = 'Menyambung…';
    error.hidden = true;

    try {
        const result = await api('/api/auth/login', {
            method: 'POST',
            body: { username: $('u').value.trim(), password: $('p').value },
        });

        S.token = result.token;
        S.user = result.user;

        // sessionStorage, bukan localStorage: token hilang saat tab ditutup.
        // Dasbor sering dibuka di komputer bersama, dan sesi yang tetap hidup
        // setelah orangnya pergi adalah sesi milik orang berikutnya.
        try { sessionStorage.setItem(KEY, result.token); } catch (e) { /* mode privat */ }

        start();
    } catch (ex) {
        error.textContent = ex.message;
        error.hidden = false;
    } finally {
        button.disabled = false;
        button.textContent = 'Masuk';
    }
});

function signOut(message) {
    S.token = null;
    S.user = null;

    if (S.timer) { clearInterval(S.timer); S.timer = null; }

    try { sessionStorage.removeItem(KEY); } catch (e) { /* abaikan */ }

    $('app').classList.remove('ready');
    $('login').style.display = 'flex';
    $('p').value = '';

    if (message) {
        $('loginError').textContent = message;
        $('loginError').hidden = false;
    }
}

$('btnLogout').addEventListener('click', () => signOut(null));

// =====================================================================
// Menu samping
// =====================================================================

// =====================================================================
// Bahasa
// =====================================================================
//
// KUNCINYA adalah teks Indonesianya sendiri, bukan kode seperti "nav.process".
//
// Dua alasan. Pertama, teks yang belum diterjemahkan jatuh kembali ke bahasa
// Indonesia yang benar — bukan menjadi "nav.process" yang bocor ke layar.
// Kedua, sumbernya tetap bisa dibaca: `t('Jalankan')` sudah menjelaskan
// dirinya, sementara `t('btn.run')` menuntut orang membuka kamusnya dulu.
//
// Kamus yang belum lengkap karena itu tidak pernah merusak antarmuka; ia hanya
// membuat sebagian tetap berbahasa Indonesia.

const BAHASA = [
    { id: 'id', label: 'Indonesia' },
    { id: 'en', label: 'English' },
    { id: 'jv', label: 'Jawa' },
];

const I18N = {
    en: {
        // --- menu ---
        'Dasbor': 'Dashboard', 'Automasi': 'Automation', 'Proses': 'Processes',
        'Paket': 'Packages', 'Pemicu & Jadwal': 'Triggers & Schedules',
        'Pemantauan': 'Monitoring', 'Pantau Pekerjaan': 'Jobs', 'Log Langsung': 'Live Logs',
        'Peringatan': 'Alerts', 'Robot': 'Robots', 'Mesin': 'Machines',
        'Lingkungan': 'Environments', 'Kredensial': 'Credentials', 'Data': 'Data',
        'Antrean': 'Queues', 'Aset': 'Assets', 'Gudang Berkas': 'Storage Buckets',
        'Administrasi': 'Administration', 'Pengguna': 'Users', 'Peran': 'Roles',
        'Lisensi': 'Licensing', 'Pengaturan': 'Settings',

        // --- sub-tab ---
        'Pekerjaan': 'Jobs', 'Pemicu': 'Triggers', 'Log': 'Logs',

        // --- tombol umum ---
        'Muat ulang': 'Refresh', 'Jalankan': 'Run', 'Jadwalkan': 'Schedule',
        'Hapus': 'Delete', 'Batal': 'Cancel', 'Simpan': 'Save', 'Keluar': 'Sign out',
        'Proses baru': 'New process', 'Jalankan proses': 'Start job',
        'Hentikan': 'Stop', 'Rincian': 'Details', 'Kosongkan': 'Clear',
        'Batalkan pilihan': 'Clear selection', 'Jalankan terpilih': 'Run selected',
        'Hentikan terpilih': 'Stop selected', 'Hanya ini': 'Only this',
        '← Semua proses': '← All processes', 'Buat pemicu': 'Create trigger',
        'Tampilkan semua log': 'Show all logs',

        // --- kolom tabel ---
        'Nama': 'Name', 'Keadaan': 'State', 'Kemajuan': 'Progress', 'Sumber': 'Source',
        'Mulai': 'Started', 'Selesai': 'Ended', 'Lama': 'Duration', 'Dijalankan': 'Runs',
        'Terakhir': 'Last run', 'Tindakan': 'Actions', 'Versi': 'Version',
        'Titik masuk': 'Entry point', 'Keterangan': 'Description', 'Ukuran': 'Size',
        'Waktu': 'Time', 'Nomor': 'Id', 'Diterbitkan oleh': 'Published by',

        // --- keadaan ---
        'Siap': 'Available', 'Sibuk': 'Busy', 'Terputus': 'Disconnected',
        'Tak merespons': 'Unresponsive', 'Berjalan': 'Running', 'Menunggu': 'Pending',
        'Berhasil': 'Successful', 'Gagal': 'Faulted', 'Menghentikan': 'Stopping',
        'Dihentikan': 'Stopped', 'Baru': 'New', 'Diproses': 'In progress',
        'Diulang': 'Retried', 'Ditinggalkan': 'Abandoned',

        // --- tabel ---
        'Baris': 'Rows', 'Halaman': 'Page', 'terpilih': 'selected',
        'Cari di tabel ini…': 'Search this table…',
        'Semua proses': 'All processes', 'Riwayat pekerjaan': 'Job history',
        'Riwayat jalan': 'Run history', 'Jalan robot': 'Robot runs',

        // --- judul & keterangan halaman ---
        'Automasi yang siap dijalankan robot.': 'Automations ready for robots to run.',
        'Setiap kali proses dijalankan tercatat di sini.': 'Every run of a process is recorded here.',
        'Berkas automasi yang diterbitkan dari Studio.': 'Automation packages published from Studio.',
        'Baris log dari semua robot, diperbarui sendiri.': 'Log lines from every robot, updated automatically.',
        'Tanpa keterangan.': 'No description.',
        'belum pernah': 'never',

        // --- pemicu ---
        'Buat Pemicu Waktu': 'Create Time Trigger',
        'Proses dijalankan sendiri tiap selang waktu yang ditentukan.':
            'The process runs by itself at the interval you set.',
        'Apa yang dijalankan': 'What runs', 'Kapan dijalankan': 'When it runs',
        'Nama pemicu *': 'Trigger name *', 'Proses *': 'Process *',
        'Prioritas pekerjaan': 'Job priority', 'Jenis runtime': 'Runtime type',
        'Zona waktu *': 'Timezone *', 'Frekuensi': 'Frequency', 'Ulangi tiap': 'Repeat every',
        'Langsung aktifkan': 'Enable immediately',
        'Robot mana saja yang siap': 'Any available robot',
        'Menit': 'Minute', 'Jam': 'Hour', 'Hari': 'Day', 'Minggu': 'Week',

        // --- layar masuk ---
        'Masuk': 'Sign in',

        // --- bahasa ---
        'Bahasa': 'Language',
    },

    jv: {
        // --- menu ---
        'Dasbor': 'Papan Kontrol', 'Automasi': 'Otomatisasi', 'Proses': 'Proses',
        'Paket': 'Paket', 'Pemicu & Jadwal': 'Pamicu lan Jadwal',
        'Pemantauan': 'Pangawasan', 'Pantau Pekerjaan': 'Gawean', 'Log Langsung': 'Log Langsung',
        'Peringatan': 'Pènget', 'Robot': 'Robot', 'Mesin': 'Mesin',
        'Lingkungan': 'Lingkungan', 'Kredensial': 'Kredensial', 'Data': 'Data',
        'Antrean': 'Antrian', 'Aset': 'Aset', 'Gudang Berkas': 'Gudhang Berkas',
        'Administrasi': 'Administrasi', 'Pengguna': 'Panganggo', 'Peran': 'Peran',
        'Lisensi': 'Lisensi', 'Pengaturan': 'Setelan',

        // --- sub-tab ---
        'Pekerjaan': 'Gawean', 'Pemicu': 'Pamicu', 'Log': 'Log',

        // --- tombol umum ---
        'Muat ulang': 'Muat manèh', 'Jalankan': 'Lakokna', 'Jadwalkan': 'Dijadwal',
        'Hapus': 'Busak', 'Batal': 'Batal', 'Simpan': 'Simpen', 'Keluar': 'Metu',
        'Proses baru': 'Proses anyar', 'Jalankan proses': 'Lakokna proses',
        'Hentikan': 'Mandhegna', 'Rincian': 'Rincian', 'Kosongkan': 'Kosongna',
        'Batalkan pilihan': 'Batalna pilihan', 'Jalankan terpilih': 'Lakokna sing dipilih',
        'Hentikan terpilih': 'Mandhegna sing dipilih', 'Hanya ini': 'Iki waé',
        '← Semua proses': '← Kabèh proses', 'Buat pemicu': 'Gawé pamicu',
        'Tampilkan semua log': 'Tuduhna kabèh log',

        // --- kolom tabel ---
        'Nama': 'Jeneng', 'Keadaan': 'Kahanan', 'Kemajuan': 'Kemajuan', 'Sumber': 'Sumber',
        'Mulai': 'Wiwit', 'Selesai': 'Rampung', 'Lama': 'Suwéné', 'Dijalankan': 'Dilakokna',
        'Terakhir': 'Pungkasan', 'Tindakan': 'Tumindak', 'Versi': 'Versi',
        'Titik masuk': 'Titik mlebu', 'Keterangan': 'Katrangan', 'Ukuran': 'Ukuran',
        'Waktu': 'Wektu', 'Nomor': 'Nomer', 'Diterbitkan oleh': 'Diterbitaké déning',

        // --- keadaan ---
        'Siap': 'Siyaga', 'Sibuk': 'Sibuk', 'Terputus': 'Pedhot',
        'Tak merespons': 'Ora nyaur', 'Berjalan': 'Mlaku', 'Menunggu': 'Ngentèni',
        'Berhasil': 'Kasil', 'Gagal': 'Gagal', 'Menghentikan': 'Mandhegaké',
        'Dihentikan': 'Dipandhegaké', 'Baru': 'Anyar', 'Diproses': 'Diproses',
        'Diulang': 'Dibalèni', 'Ditinggalkan': 'Ditinggal',

        // --- tabel ---
        'Baris': 'Baris', 'Halaman': 'Kaca', 'terpilih': 'dipilih',
        'Cari di tabel ini…': 'Golèk ing tabel iki…',
        'Semua proses': 'Kabèh proses', 'Riwayat pekerjaan': 'Riwayat gawean',
        'Riwayat jalan': 'Riwayat lakon', 'Jalan robot': 'Lakon robot',

        // --- judul & keterangan halaman ---
        'Automasi yang siap dijalankan robot.': 'Otomatisasi sing siyap dilakokna robot.',
        'Setiap kali proses dijalankan tercatat di sini.': 'Saben proses dilakokna dicathet ing kéné.',
        'Berkas automasi yang diterbitkan dari Studio.': 'Berkas otomatisasi sing diterbitaké saka Studio.',
        'Baris log dari semua robot, diperbarui sendiri.': 'Baris log saka kabèh robot, dianyaraké dhéwé.',
        'Tanpa keterangan.': 'Tanpa katrangan.',
        'belum pernah': 'durung tau',

        // --- pemicu ---
        'Buat Pemicu Waktu': 'Gawé Pamicu Wektu',
        'Proses dijalankan sendiri tiap selang waktu yang ditentukan.':
            'Proses mlaku dhéwé saben sela wektu sing ditemtokaké.',
        'Apa yang dijalankan': 'Apa sing dilakokna', 'Kapan dijalankan': 'Kapan dilakokna',
        'Nama pemicu *': 'Jeneng pamicu *', 'Proses *': 'Proses *',
        'Prioritas pekerjaan': 'Prioritas gawean', 'Jenis runtime': 'Jinis runtime',
        'Zona waktu *': 'Zona wektu *', 'Frekuensi': 'Frekuensi', 'Ulangi tiap': 'Balèni saben',
        'Langsung aktifkan': 'Langsung uripna',
        'Robot mana saja yang siap': 'Robot endi waé sing siyaga',
        'Menit': 'Menit', 'Jam': 'Jam', 'Hari': 'Dina', 'Minggu': 'Minggu',

        // --- layar masuk ---
        'Masuk': 'Mlebu',

        // --- bahasa ---
        'Bahasa': 'Basa',
    },
};

/** Bahasa yang sedang dipakai. Disimpan di peramban, per orang. */
let LANG = (function () {
    try { return localStorage.getItem('jakforge.lang') || 'id'; }
    catch (e) { return 'id'; }
})();

/**
 * Terjemahkan satu teks.
 *
 * Yang tidak ada di kamus dikembalikan APA ADANYA — dan karena kuncinya sudah
 * bahasa Indonesia yang benar, hasilnya tetap kalimat yang utuh.
 */
function t(teks) {
    if (LANG === 'id' || teks == null) return teks;
    const kamus = I18N[LANG];
    return (kamus && kamus[teks]) || teks;
}

function setLang(kode) {
    LANG = kode;
    try { localStorage.setItem('jakforge.lang', kode); } catch (e) { }

    // Menu ikut digambar ulang: labelnya dibangun sekali saat mulai, jadi
    // render() saja tidak cukup.
    buildNav();
    markNav();
    render();
}

const NAV = [
    { title: null, items: [
        { id: 'dashboard', icon: '▤', label: 'Dasbor' },
    ]},
    { title: 'Automasi', items: [
        { id: 'processes', icon: '⚙', label: 'Proses' },
        { id: 'packages',  icon: '▣', label: 'Paket' },
        { id: 'triggers',  icon: '◷', label: 'Pemicu & Jadwal' },
    ]},
    { title: 'Pemantauan', items: [
        { id: 'jobs',   icon: '▷', label: 'Pantau Pekerjaan' },
        { id: 'logs',   icon: '≣', label: 'Log Langsung' },
        { id: 'alerts', icon: '⚑', label: 'Peringatan' },
    ]},
    { title: 'Robot', items: [
        { id: 'robots',       icon: '⬢', label: 'Robot' },
        { id: 'machines',     icon: '▭', label: 'Mesin' },
        { id: 'environments', icon: '◈', label: 'Lingkungan' },
        { id: 'credentials',  icon: '🔑', label: 'Kredensial' },
    ]},
    { title: 'Data', items: [
        { id: 'queues',  icon: '▤', label: 'Antrean' },
        { id: 'assets',  icon: '◆', label: 'Aset' },
        { id: 'buckets', icon: '▦', label: 'Gudang Berkas' },
    ]},
    { title: 'Administrasi', items: [
        { id: 'users',     icon: '◍', label: 'Pengguna' },
        { id: 'roles',     icon: '◎', label: 'Peran' },
        { id: 'licensing', icon: '▧', label: 'Lisensi' },
        { id: 'settings',  icon: '⚒', label: 'Pengaturan' },
    ]},
];

function buildNav() {
    const html = NAV.map((group) => {
        const items = group.items.map((item) =>
            `<div class="nav-item" data-page="${item.id}">
                 <span class="ico">${item.icon}</span>
                 <span>${esc(t(item.label))}</span>
                 <span class="count" data-count="${item.id}" hidden></span>
             </div>`).join('');

        return (group.title ? `<div class="title">${esc(t(group.title))}</div>` : "") + items;
    }).join('');

    $('nav').innerHTML = `<div class="nav-group">${html}</div>`;

    $('nav').querySelectorAll('.nav-item').forEach((el) =>
        el.addEventListener('click', () => go(el.dataset.page)));
}

function markNav() {
    // Halaman detail proses tidak punya butir menunya sendiri, tapi ia MASIH
    // bagian dari Proses. Tanpa pemetaan ini, membukanya membuat seluruh menu
    // kiri padam dan orang kehilangan petunjuk sedang berada di mana.
    const aktif = S.page === 'process' ? 'processes' : S.page;

    $('nav').querySelectorAll('.nav-item').forEach((el) =>
        el.classList.toggle('active', el.dataset.page === aktif));
}

function setCount(page, value) {
    const el = $('nav').querySelector(`[data-count="${page}"]`);
    if (!el) return;

    if (!value) { el.hidden = true; return; }

    el.hidden = false;
    el.textContent = value;
}

// =====================================================================
// Perutean
// =====================================================================

const PAGES = {};

function go(page) {
    if (!PAGES[page]) page = 'dashboard';

    S.page = page;
    location.hash = page;

    markNav();
    render();
}

async function render() {
    const page = $('page');

    try {
        await PAGES[S.page](page);
    } catch (ex) {
        if (ex.message === '401') return;

        page.innerHTML = `<div class="panel"><div class="empty">
            <span class="big">⚠</span>
            Halaman ini gagal dimuat.<br /><b>${esc(ex.message)}</b>
        </div></div>`;
    }
}

window.addEventListener('hashchange', () => {
    const page = location.hash.replace('#', '');
    if (page && page !== S.page) go(page);
});

// =====================================================================
// Awalan halaman
// =====================================================================

function head(title, lead, actions) {
    return `<div class="page-head">
        <div><h2>${esc(t(title))}</h2><p>${esc(t(lead))}</p></div>
        <div class="spacer"></div>
        ${actions || ''}
    </div>`;
}

function panel(title, body, actions, hint) {
    return `<div class="panel">
        <div class="panel-head">
            <h3>${esc(t(title))}</h3>
            ${hint ? `<span class="hint">${esc(t(hint))}</span>` : ""}
            <div class="spacer"></div>
            ${actions || ''}
        </div>
        ${body}
    </div>`;
}

function table(columns, rows, emptyText) {
    if (!rows || rows.length === 0) {
        return `<div class="empty"><span class="big">◌</span>${esc(emptyText)}</div>`;
    }

    return `<table>
        <thead><tr>${columns.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>
        <tbody>${rows.join('')}</tbody>
    </table>`;
}

// Penerjemahan keadaan ke warna lencana, di satu tempat supaya semua
// halaman memakai warna yang sama untuk keadaan yang sama.
const STATE_TAG = {
    AVAILABLE: 'green', BUSY: 'blue', DISCONNECTED: 'gray', UNRESPONSIVE: 'red',
    RUNNING: 'blue', PENDING: 'amber', SUCCESSFUL: 'green', FAULTED: 'red',
    STOPPING: 'amber', STOPPED: 'gray',
    NEW: 'amber', IN_PROGRESS: 'blue', FAILED: 'red', RETRIED: 'amber', ABANDONED: 'gray',
};

const STATE_ID = {
    AVAILABLE: 'Siap', BUSY: 'Sibuk', DISCONNECTED: 'Terputus', UNRESPONSIVE: 'Tak merespons',
    RUNNING: 'Berjalan', PENDING: 'Menunggu', SUCCESSFUL: 'Berhasil', FAULTED: 'Gagal',
    STOPPING: 'Menghentikan', STOPPED: 'Dihentikan',
    NEW: 'Baru', IN_PROGRESS: 'Diproses', FAILED: 'Gagal', RETRIED: 'Diulang', ABANDONED: 'Ditinggalkan',
};

function stateTag(state) {
    const key = String(state || '').toUpperCase();
    return `<span class="tag ${STATE_TAG[key] || "gray"}">${esc(t(STATE_ID[key]) || state || "—")}</span>`;
}

function progressBar(percent, state) {
    const value = Math.max(0, Math.min(100, Number(percent) || 0));
    const tone = state === 'FAULTED' ? ' red' : (state === 'SUCCESSFUL' ? ' green' : '');

    return `<div class="bar-wrap">
        <div class="bar${tone}"><i style="width:${value}%"></i></div>
        <span class="pct">${value}%</span>
    </div>`;
}

// =====================================================================
// Kotak dialog
// =====================================================================

/**
 * Yang dipanggil kalau dialognya ditutup dari LUAR alur normalnya: tombol X,
 * klik latar, atau Esc.
 *
 * Tanpa ini, menutup dialog dengan cara itu meninggalkan janji yang tidak
 * pernah selesai — pemanggilnya menunggu selamanya, dan halaman yang menunggu
 * jawaban dialog berhenti bereaksi tanpa satu pun pesan.
 */
let tutupDialogHandler = null;

function closeDialog(hasil) {
    $('veil').classList.remove('open');
    $('dialog').innerHTML = '';

    const f = tutupDialogHandler;
    tutupDialogHandler = null;
    if (f) f(hasil);
}


// Esc menutup dialog. Sudah jadi kebiasaan semua orang, dan tidak menutupnya
// membuat dialog terasa seperti jebakan.
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && $('veil').classList.contains('open')) closeDialog();
});

$('veil').addEventListener('click', (event) => {
    if (event.target === $('veil')) closeDialog();
});

document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') { closeDialog(); hideSearch(); }
});

/**
 * Formulir sederhana dalam kotak dialog.
 *
 * fields: [{ name, label, type, value, options, hint }]
 * Mengembalikan objek nilai, atau null kalau dibatalkan — pemanggilnya
 * cukup memeriksa null, bukan menyusun penanganan tombol sendiri tiap kali.
 */
function form(title, lead, fields, submitLabel, opts) {
    opts = opts || {};

    return new Promise((resolve) => {
        const body = fields.map((f) => {
            const id = "f_" + f.name;

            // Judul bagian: bukan isian, hanya pemisah. Dialog yang panjang
            // tanpa pemisah membuat orang harus membaca seluruhnya untuk tahu
            // mana yang wajib dan mana yang bisa dilewati.
            if (f.type === "section") {
                return `<div class="form-section">${esc(t(f.label))}</div>`;
            }

            const lebar = f.wide ? " wide" : "";

            if (f.type === 'select') {
                const options = (f.options || []).map((o) => {
                    const value = typeof o === 'string' ? o : o.value;
                    const label = typeof o === 'string' ? o : o.label;
                    const sel = String(f.value) === String(value) ? ' selected' : '';
                    return `<option value="${esc(value)}"${sel}>${esc(label)}</option>`;
                }).join('');

                return `<div class="field${lebar}"><label for="${id}">${esc(t(f.label))}</label>
                        <select id="${id}">${options}</select>
                        ${f.hint ? `<div style="font-size:11px;color:var(--muted);margin-top:5px">${esc(f.hint)}</div>` : ''}
                    </div>`;
            }

            if (f.type === 'textarea') {
                return `<div class="field${lebar}"><label for="${id}">${esc(t(f.label))}</label>
                        <textarea id="${id}" placeholder="${esc(f.placeholder || '')}">${esc(f.value || '')}</textarea>
                        ${f.hint ? `<div style="font-size:11px;color:var(--muted);margin-top:5px">${esc(f.hint)}</div>` : ''}
                    </div>`;
            }

            if (f.type === 'checkbox') {
                return `<div class="field${lebar}" style="display:flex;align-items:center;gap:9px">
                        <input id="${id}" type="checkbox" style="width:auto"${f.value ? ' checked' : ''} />
                        <label for="${id}" style="margin:0">${esc(t(f.label))}</label>
                    </div>`;
            }

            return `<div class="field${lebar}"><label for="${id}">${esc(t(f.label))}</label>
                    <input id="${id}" type="${f.type || 'text'}" value="${esc(f.value || '')}"
                           placeholder="${esc(f.placeholder || '')}" />
                    ${f.hint ? `<div style="font-size:11px;color:var(--muted);margin-top:5px">${esc(f.hint)}</div>` : ''}
                </div>`;
        }).join('');

        $('dialog').innerHTML = `
            <button class="dlg-x" data-act="closeDialog" title="Tutup" aria-label="Tutup">✕</button>
            <h3>${esc(t(title))}</h3>
            ${lead ? `<p class="lead">${esc(t(lead))}</p>` : ""}
            <form id="dlgForm">
                ${opts.columns === 2 ? `<div class="form-grid">${body}</div>` : body}
                <div class="err" id="dlgErr" hidden></div>
                <div class="actions">
                    <button type="button" class="btn" id="dlgCancel">${esc(t("Batal"))}</button>
                    <button type="submit" class="btn primary">${esc(t(submitLabel || "Simpan"))}</button>
                </div>
            </form>`;

        $("veil").classList.add("open");

        // Ditutup dari luar (X, latar, Esc) sama artinya dengan Batal.
        tutupDialogHandler = () => resolve(null);

        const first = $("dialog").querySelector("input, select, textarea");
        if (first) first.focus();

        $("dlgCancel").addEventListener("click", () => { tutupDialogHandler = null; closeDialog(); resolve(null); });

        $('dlgForm').addEventListener('submit', (event) => {
            event.preventDefault();

            const values = {};
            fields.forEach((f) => {
                if (f.type === "section") return;

                const el = $("f_" + f.name);
                values[f.name] = f.type === "checkbox" ? el.checked : el.value.trim();
            });

            tutupDialogHandler = null;
            closeDialog();
            resolve(values);
        });
    });
}

function confirmBox(title, message, confirmLabel, danger) {
    return new Promise((resolve) => {
        $('dialog').innerHTML = `
            <button class="dlg-x" data-act="closeDialog" title="Tutup" aria-label="Tutup">✕</button>
            <h3>${esc(title)}</h3>
            <p class="lead">${esc(message)}</p>
            <div class="actions">
                <button class="btn" id="cbNo">Batal</button>
                <button class="btn ${danger ? 'danger' : 'primary'}" id="cbYes">${esc(confirmLabel || 'Lanjutkan')}</button>
            </div>`;

        $('veil').classList.add('open');
        $('cbYes').focus();

        // Ditutup dari luar (X, latar, Esc) sama artinya dengan TIDAK.
        // Menganggapnya "ya" akan menghapus sesuatu yang tidak diminta.
        tutupDialogHandler = () => resolve(false);

        $('cbNo').addEventListener('click', () => { tutupDialogHandler = null; closeDialog(); resolve(false); });
        $('cbYes').addEventListener('click', () => { tutupDialogHandler = null; closeDialog(); resolve(true); });
    });
}

/** Bungkus aksi: tampilkan hasilnya sebagai roti panggang, lalu muat ulang. */
async function act(promise, okMessage) {
    try {
        await promise;
        if (okMessage) toast(okMessage, 'good');
        await render();
        return true;
    } catch (ex) {
        if (ex.message !== '401') toast(ex.message, 'bad');
        return false;
    }
}

// Aksi dipasang lewat delegasi pada #page. Halaman digambar ulang tiap
// beberapa detik, dan pendengar yang dipasang ke tombol satu per satu akan
// ikut terbuang tiap penggambaran — beserta klik yang sedang berlangsung.
$('page').addEventListener('click', (event) => {
    const el = event.target.closest('[data-act]');
    if (!el) return;

    const handler = ACTIONS[el.dataset.act];
    if (handler) handler(el.dataset);
});

const ACTIONS = {};

// Dipasang SESUDAH deklarasi ACTIONS.
//
// Versi pertama menaruhnya jauh di atas, sebelum "const ACTIONS = {}". Itu
// zona mati temporal: app.js melempar saat dimuat, dan SELURUH pendaftaran
// sesudah baris itu tidak pernah berjalan. Yang terlihat cuma satu tombol yang
// diam, bukan pesan galat yang menunjuk sebabnya.
ACTIONS.closeDialog = () => closeDialog();

// Tombol X ada di dalam #dialog, bukan di #page, jadi penyalur aksi halaman
// tidak menjangkaunya.
$('dialog').addEventListener('click', (event) => {
    const el = event.target.closest('[data-act]');
    if (!el) return;

    const handler = ACTIONS[el.dataset.act];
    if (handler) handler(el.dataset);
});

// =====================================================================
// Tabel data: urut, pilih, halaman
// =====================================================================
//
// Satu komponen untuk semua daftar. Sebelumnya tiap halaman menyusun
// <table>-nya sendiri, jadi pengurutan dan penomoran halaman harus ditulis
// ulang tiap kali — dan karena mahal, tidak pernah ditulis sama sekali.
// Daftar dengan dua ratus pekerjaan tidak bisa dibaca tanpa keduanya.
//
// Keadaannya disimpan DI LUAR fungsi render, karena render menggambar ulang
// seluruh halaman: kalau keadaannya ikut digambar ulang, urutan dan halaman
// yang sedang dilihat orang akan kembali ke awal tiap kali daftarnya
// disegarkan sendiri.
const TABLES = {};

function tableState(key) {
    if (!TABLES[key]) {
        TABLES[key] = { sort: null, dir: 'asc', page: 1, size: 25, q: '', selected: [] };
    }
    return TABLES[key];
}

/**
 * Bangun satu tabel lengkap.
 *
 * @param key     nama tabelnya; dipakai menyimpan keadaan urut/halaman
 * @param columns [{ id, label, sortable, align, value(row), render(row) }]
 * @param rows    seluruh baris, BELUM disaring atau diurutkan
 * @param opts    { rowKey, empty, searchHint, bulk, openable }
 */
function dataTable(key, columns, rows, opts) {
    opts = opts || {};
    const s = tableState(key);
    const rowKey = opts.rowKey || function (r) { return r.id || r.name; };

    // --- saring ---
    let hasil = rows;
    if (s.q) {
        const cari = s.q.toLowerCase();
        hasil = rows.filter(function (r) {
            return columns.some(function (c) {
                const v = c.value ? c.value(r) : r[c.id];
                return String(v == null ? '' : v).toLowerCase().indexOf(cari) !== -1;
            });
        });
    }

    // --- urut ---
    if (s.sort) {
        const col = columns.filter(function (c) { return c.id === s.sort; })[0];
        if (col) {
            const arah = s.dir === 'desc' ? -1 : 1;
            hasil = hasil.slice().sort(function (a, b) {
                const x = col.value ? col.value(a) : a[col.id];
                const y = col.value ? col.value(b) : b[col.id];

                // Angka diurutkan sebagai angka, sisanya sebagai teks tanpa
                // membedakan huruf besar-kecil. Tanpa pemisahan ini, "10"
                // berada sebelum "9".
                if (typeof x === 'number' && typeof y === 'number') return (x - y) * arah;

                const sx = String(x == null ? '' : x).toLowerCase();
                const sy = String(y == null ? '' : y).toLowerCase();
                return sx < sy ? -arah : sx > sy ? arah : 0;
            });
        }
    }

    // --- halaman ---
    const total = hasil.length;
    const halamanTerakhir = Math.max(1, Math.ceil(total / s.size));
    if (s.page > halamanTerakhir) s.page = halamanTerakhir;

    const mulai = (s.page - 1) * s.size;
    const tampil = hasil.slice(mulai, mulai + s.size);

    if (total === 0) {
        return '<div class="table-tools">' + alatCari(key, s, opts) + '</div>' +
               '<div class="empty"><span class="big">◌</span>' +
               esc(t(opts.empty || 'Belum ada data.')) + '</div>';
    }

    // --- kepala ---
    const kepala = columns.map(function (c) {
        const aktif = s.sort === c.id;
        const panah = !c.sortable ? ''
            : aktif ? (s.dir === 'asc' ? ' <span class="sort on">▲</span>' : ' <span class="sort on">▼</span>')
                    : ' <span class="sort">⇅</span>';
        const klik = c.sortable
            ? ' data-act="tblSort" data-key="' + esc(key) + '" data-col="' + esc(c.id) + '" style="cursor:pointer"'
            : '';
        return '<th' + (c.align === 'num' ? ' class="num"' : '') + klik + '>' + esc(t(c.label)) + panah + '</th>';
    }).join('');

    const semuaTerpilih = tampil.every(function (r) { return s.selected.indexOf(rowKey(r)) !== -1; });

    const kotakSemua = '<th class="pick"><input type="checkbox" data-act="tblAll" data-key="' +
        esc(key) + '"' + (semuaTerpilih ? ' checked' : '') + ' /></th>';

    // --- isi ---
    const isi = tampil.map(function (r) {
        const k = rowKey(r);
        const dipilih = s.selected.indexOf(k) !== -1;

        const sel = '<td class="pick"><input type="checkbox" data-act="tblPick" data-key="' +
            esc(key) + '" data-row="' + esc(k) + '"' + (dipilih ? ' checked' : '') + ' /></td>';

        const kolom = columns.map(function (c) {
            const isiSel = c.render
                ? c.render(r)
                : esc(String(c.value ? c.value(r) : (r[c.id] == null ? '—' : r[c.id])));
            return '<td' + (c.align === 'num' ? ' class="num"' : '') + '>' + isiSel + '</td>';
        }).join('');

        const buka = opts.openable ? ' class="clickable" data-open="' + esc(k) + '"' : '';
        return '<tr' + buka + '>' + sel + kolom + '</tr>';
    }).join('');

    // --- kaki ---
    const dari = mulai + 1;
    const sampai = Math.min(mulai + s.size, total);

    function tombol(act, label, mati) {
        return '<button class="pg" data-act="' + act + '" data-key="' + esc(key) + '"' +
               (mati ? ' disabled' : '') + '>' + label + '</button>';
    }

    const kaki = '<div class="table-foot">' +
        '<span class="count">' + dari + ' – ' + sampai + ' / ' + total + '</span>' +
        '<div class="pager">' +
            tombol('tblFirst', '|◀', s.page === 1) +
            tombol('tblPrev', '◀', s.page === 1) +
            '<span class="pg-now">' + esc(t('Halaman')) + ' ' + s.page + ' / ' + halamanTerakhir + '</span>' +
            tombol('tblNext', '▶', s.page === halamanTerakhir) +
            tombol('tblLast', '▶|', s.page === halamanTerakhir) +
        '</div>' +
        '<label class="per-page">' + esc(t('Baris')) + ' <select data-act="tblSize" data-key="' + esc(key) + '">' +
            [10, 25, 50, 100].map(function (n) {
                return '<option value="' + n + '"' + (n === s.size ? ' selected' : '') + '>' + n + '</option>';
            }).join('') +
        '</select></label>' +
    '</div>';

    const terpilih = s.selected.length;
    const bilahPilih = (terpilih > 0 && opts.bulk) ? '<div class="bulk-bar"><span>' + terpilih + ' ' + esc(t('terpilih')) + '</span>' +
        opts.bulk.map(function (b) {
            return '<button class="btn sm' + (b.danger ? ' danger' : '') + '" data-act="' + esc(b.act) +
                   '" data-key="' + esc(key) + '">' + esc(t(b.label)) + '</button>';
        }).join('') +
        '<button class="btn sm" data-act="tblClear" data-key="' + esc(key) + '">Batalkan pilihan</button></div>' : '';

    return '<div class="table-tools">' + alatCari(key, s, opts) + '</div>' +
        bilahPilih +
        '<table class="dt"><thead><tr>' + kotakSemua + kepala + '</tr></thead><tbody>' + isi + '</tbody></table>' +
        kaki;
}

function alatCari(key, s, opts) {
    return '<input class="tbl-search" data-act="tblSearchBox" data-key="' + esc(key) + '" placeholder="' +
           esc(t(opts.searchHint || 'Cari di tabel ini…')) + '" value="' + esc(s.q) + '" />' +
           '<div class="spacer"></div>' +
           '<button class="icon-btn" data-act="refresh" title="Muat ulang">⟳</button>';
}

/** Kunci baris yang sedang terpilih di sebuah tabel. */
function tableSelection(key) {
    return tableState(key).selected.slice();
}

ACTIONS.tblSort = function (data) {
    const s = tableState(data.key);
    if (s.sort === data.col) s.dir = s.dir === 'asc' ? 'desc' : 'asc';
    else { s.sort = data.col; s.dir = 'asc'; }
    render();
};

ACTIONS.tblFirst = function (data) { tableState(data.key).page = 1; render(); };
ACTIONS.tblPrev = function (data) { const s = tableState(data.key); s.page = Math.max(1, s.page - 1); render(); };
ACTIONS.tblNext = function (data) { tableState(data.key).page += 1; render(); };
ACTIONS.tblLast = function (data) { tableState(data.key).page = 1e9; render(); };
ACTIONS.tblClear = function (data) { tableState(data.key).selected = []; render(); };

// Kotak centang, pemilih jumlah baris, dan kotak cari TIDAK memakai "click":
// perubahannya datang lewat change dan input. Disalurkan di sini supaya
// halaman mana pun yang memakai dataTable langsung mendapatkannya.
$('page').addEventListener('change', function (event) {
    const el = event.target.closest('[data-act]');
    if (!el) return;

    const act = el.dataset.act;

    if (act === 'tblPick') {
        const s = tableState(el.dataset.key);
        const i = s.selected.indexOf(el.dataset.row);
        if (el.checked) { if (i === -1) s.selected.push(el.dataset.row); }
        else if (i !== -1) s.selected.splice(i, 1);
        render();
        return;
    }

    if (act === 'tblAll') {
        // "Pilih semua" hanya berlaku untuk baris yang SEDANG TAMPIL.
        // Memilih seluruh isi tabel dari satu kotak centang membuat orang
        // menghapus lebih banyak daripada yang mereka lihat.
        const s = tableState(el.dataset.key);
        const kotak = el.closest('table').querySelectorAll('[data-act="tblPick"]');

        kotak.forEach(function (k) {
            const i = s.selected.indexOf(k.dataset.row);
            if (el.checked) { if (i === -1) s.selected.push(k.dataset.row); }
            else if (i !== -1) s.selected.splice(i, 1);
        });

        render();
        return;
    }

    if (act === 'tblSize') {
        const s = tableState(el.dataset.key);
        s.size = Number(el.value) || 25;
        s.page = 1;
        render();
    }
});

let cariTunda = null;
$('page').addEventListener('input', function (event) {
    const el = event.target.closest('[data-act="tblSearchBox"]');
    if (!el) return;

    // Ditunda: menggambar ulang tiap ketukan tombol membuat kursor melompat
    // keluar dari kotak carinya.
    clearTimeout(cariTunda);
    const key = el.dataset.key;
    const nilai = el.value;

    cariTunda = setTimeout(function () {
        const s = tableState(key);
        s.q = nilai;
        s.page = 1;
        render();

        // Fokus dikembalikan ke kotak carinya supaya orang bisa terus mengetik.
        const lagi = document.querySelector('[data-act="tblSearchBox"][data-key="' + key + '"]');
        if (lagi) { lagi.focus(); lagi.setSelectionRange(nilai.length, nilai.length); }
    }, 350);
});

/**
 * Klik ganda pada baris tabel membuka isinya.
 *
 * Dipisahkan dari penyalur klik biasa dengan sengaja: kalau seluruh baris
 * bereaksi pada satu klik, tombol Hapus di ujung baris jadi berbahaya —
 * meleset sedikit saja langsung membuka halaman lain. Klik ganda tidak
 * pernah terjadi karena tidak sengaja.
 */
$('page').addEventListener('dblclick', (event) => {
    const row = event.target.closest('tr[data-open]');
    if (!row) return;

    // Klik ganda di atas tombol tetap milik tombolnya.
    if (event.target.closest('button, a')) return;

    ACTIONS.openProcess({ name: row.dataset.open });
});

// =====================================================================
// Halaman: Dasbor
// =====================================================================

PAGES.dashboard = async (page) => {
    const d = await api('/api/dashboard');

    setCount('alerts', d.unreadAlerts);
    setCount('jobs', d.jobs.running + d.jobs.pending);
    $('alertBadge').hidden = !d.unreadAlerts;
    $('alertBadge').textContent = d.unreadAlerts;

    const cards = `<div class="cards">
        <div class="stat">
            <div class="label">Robot</div>
            <div class="value">${d.robots.total}</div>
            <div class="subs">
                <span><b style="color:var(--green)">${d.robots.available}</b> siap</span>
                <span><b style="color:var(--blue)">${d.robots.busy}</b> sibuk</span>
                <span><b>${d.robots.disconnected}</b> terputus</span>
            </div>
        </div>

        <div class="stat blue">
            <div class="label">Pekerjaan berjalan</div>
            <div class="value">${d.jobs.running}</div>
            <div class="subs">
                <span><b>${d.jobs.pending}</b> menunggu</span>
                <span><b>${d.jobs.totalToday}</b> dibuat hari ini</span>
            </div>
        </div>

        <div class="stat green">
            <div class="label">Berhasil hari ini</div>
            <div class="value">${d.jobs.successfulToday}</div>
            <div class="subs"><span>Tingkat keberhasilan <b>${d.successRate}%</b></span></div>
        </div>

        <div class="stat red">
            <div class="label">Gagal hari ini</div>
            <div class="value">${d.jobs.faultedToday}</div>
            <div class="subs"><span><b>${d.unreadAlerts}</b> peringatan belum dibaca</span></div>
        </div>

        <div class="stat amber">
            <div class="label">Butir antrean</div>
            <div class="value">${d.queues.newItems}</div>
            <div class="subs">
                <span><b>${d.queues.inProgress}</b> diproses</span>
                <span><b>${d.queues.failed}</b> gagal</span>
            </div>
        </div>

        <div class="stat">
            <div class="label">Pustaka</div>
            <div class="value">${d.library.processes}</div>
            <div class="subs">
                <span><b>${d.library.packages}</b> paket</span>
                <span><b>${d.library.triggers}</b> pemicu aktif</span>
            </div>
        </div>
    </div>`;

    // ---------------------------------------------------------------
    const jobRows = d.jobsInProgress.map((j) => `<tr>
        <td><b>${esc(j.processName)}</b><span class="sub">${esc(j.info || '')}</span></td>
        <td>${esc(j.robotName || '— belum ditugaskan')}</td>
        <td>${stateTag(j.state)}</td>
        <td>${progressBar(j.progress, j.state)}</td>
        <td>${esc(j.source)}</td>
        <td class="num">${when(j.startedAt || j.createdAt)}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="watchJob" data-id="${esc(j.id)}" data-name="${esc(j.processName)}">Log</button>
            <button class="btn sm danger" data-act="stopJob" data-id="${esc(j.id)}">Hentikan</button>
        </td>
    </tr>`);

    const jobsPanel = panel('Pekerjaan Berjalan',
        `<div class="panel-body flush">${table(
            ['Proses', 'Robot', 'Keadaan', 'Kemajuan', 'Sumber', 'Mulai', ''],
            jobRows,
            'Tidak ada pekerjaan yang sedang berjalan atau menunggu.')}</div>`,
        `<button class="btn sm" data-act="goJobs">Semua pekerjaan</button>
         <button class="btn sm primary" data-act="newJob">Jalankan proses</button>`);

    // ---------------------------------------------------------------
    const robotRows = d.activeRobots.map((r) => `<tr>
        <td><b>${esc(r.name)}</b><span class="sub">${esc(r.machineName || '—')} · ${esc(r.type)}</span></td>
        <td>${stateTag(r.status)}</td>
        <td>${esc(r.environment || '—')}</td>
        <td class="num">${(Number(r.cpuPercent) || 0).toFixed(1)} %</td>
        <td class="num">${Math.round(Number(r.memoryMb) || 0)} MB</td>
        <td class="num">${r.runningJobs}</td>
        <td class="num">${ago(r.lastHeartbeatAt)}</td>
    </tr>`);

    const robotsPanel = panel('Robot Aktif',
        `<div class="panel-body flush">${table(
            ['Robot', 'Keadaan', 'Lingkungan', 'CPU', 'Memori', 'Kerja', 'Denyut terakhir'],
            robotRows,
            'Belum ada robot yang menyambung. Jalankan JakRunner dengan jakrunner.json yang menunjuk ke ForgeHub ini.')}</div>`,
        `<button class="btn sm" data-act="goRobots">Kelola robot</button>`);

    // ---------------------------------------------------------------
    const triggerEvents = d.upcomingTriggers.length
        ? `<div class="timeline">${d.upcomingTriggers.map((t) => `
            <div class="ev">
                <div class="n">${esc(t.name)}</div>
                <div class="d">${esc(t.processName)} · tiap ${t.intervalMinutes} menit<br />
                    Berikutnya ${when(t.nextRunAt)}</div>
            </div>`).join('')}</div>`
        : `<div class="empty" style="padding:22px"><span class="big">◷</span>Belum ada pemicu aktif.</div>`;

    const triggersPanel = panel('Jadwal Berikutnya',
        `<div class="panel-body">${triggerEvents}</div>`,
        `<button class="btn sm" data-act="newTrigger">Tambah pemicu</button>`);

    // ---------------------------------------------------------------
    const alertsPanel = panel('Peringatan Terbaru',
        d.recentAlerts.length
            ? `<div class="feed">${d.recentAlerts.map((a) => `
                <div class="item${a.isRead ? '' : ' unread'}">
                    <span class="pip ${esc(a.severity)}"></span>
                    <div>
                        <div class="t">${esc(a.title)}</div>
                        <div class="m">${esc(a.message || '')}</div>
                        <div class="w">${esc(a.source || '')} · ${ago(a.createdAt)}</div>
                    </div>
                </div>`).join('')}</div>`
            : `<div class="empty" style="padding:22px"><span class="big">✓</span>Tidak ada peringatan.</div>`,
        d.unreadAlerts ? `<button class="btn sm" data-act="readAll">Tandai terbaca</button>` : '');

    // ---------------------------------------------------------------
    const queueRows = d.queueSummary.map((q) => `<tr>
        <td><b>${esc(q.name)}</b></td>
        <td class="num"><span class="tag amber">${q.newCount}</span></td>
        <td class="num"><span class="tag blue">${q.inProgressCount}</span></td>
        <td class="num"><span class="tag green">${q.successfulCount}</span></td>
        <td class="num"><span class="tag red">${q.failedCount}</span></td>
    </tr>`);

    const queuesPanel = panel('Antrean',
        `<div class="panel-body flush">${table(
            ['Antrean', 'Baru', 'Diproses', 'Berhasil', 'Gagal'],
            queueRows, 'Belum ada antrean.')}</div>`,
        `<button class="btn sm" data-act="goQueues">Kelola antrean</button>`);

    // ---------------------------------------------------------------
    const history = await api('/api/dashboard/history').catch(() => []);
    const peak = Math.max(1, ...history.map((h) => (h.successful || 0) + (h.faulted || 0)));

    const chart = history.length
        ? `<div class="spark">${history.map((h) => {
            const ok = h.successful || 0;
            const bad = h.faulted || 0;
            return `<div class="col" title="${esc(h.day)}: ${ok} berhasil, ${bad} gagal">
                        ${bad ? `<div class="bad" style="height:${(bad / peak) * 74}px"></div>` : ''}
                        ${ok ? `<div class="ok" style="height:${(ok / peak) * 74}px"></div>` : ''}
                    </div>`;
        }).join('')}</div>
           <div style="display:flex;justify-content:space-between;font-size:10.5px;color:var(--muted);margin-top:7px">
               <span>${esc(history[0].day)}</span><span>${esc(history[history.length - 1].day)}</span>
           </div>`
        : `<div class="empty" style="padding:22px"><span class="big">▤</span>Belum ada pekerjaan yang selesai untuk digambarkan.</div>`;

    const chartPanel = panel('14 Hari Terakhir',
        `<div class="panel-body">${chart}</div>`, '', 'hijau berhasil · merah gagal');

    // ---------------------------------------------------------------
    page.innerHTML =
        head('Dasbor', 'Ringkasan seluruh automasi di penyewa ' + (S.user.tenant || '') + '.',
            `<button class="btn" data-act="refresh">Muat ulang</button>
             <button class="btn primary" data-act="newJob">Jalankan proses</button>`)
        + cards
        + jobsPanel
        + `<div class="grid-3"><div>${robotsPanel}${queuesPanel}</div>
                              <div>${alertsPanel}${triggersPanel}${chartPanel}</div></div>`;
};

// =====================================================================
// Halaman: Proses & Paket
// =====================================================================

// Sub-tab: halaman yang saling berdekatan dijangkau tanpa kembali ke menu.
//
// Proses, pekerjaannya, pemicunya, paketnya, dan lognya adalah satu alur kerja
// yang sama. Menaruhnya sebagai lima butir terpisah di menu samping membuat
// orang harus mengingat di mana masing-masing berada; sebagai tab, hubungannya
// terlihat sendiri.
const SUBTABS = [
    { id: 'processes', label: 'Proses' },
    { id: 'jobs', label: 'Pekerjaan' },
    { id: 'triggers', label: 'Pemicu' },
    { id: 'packages', label: 'Paket' },
    { id: 'logs', label: 'Log' },
];

function subTabs(aktif) {
    return '<div class="subtabs">' + SUBTABS.map(function (tab) {
        return '<button class="subtab' + (tab.id === aktif ? ' on' : '') +
               '" data-act="goPage" data-page="' + tab.id + '">' + esc(t(tab.label)) + '</button>';
    }).join('') + '</div>';
}

ACTIONS.goPage = (data) => go(data.page);

PAGES.processes = async (page) => {
    const rows = await api('/api/processes');
    setCount('processes', rows.length);

    const kolom = [
        { id: 'name', label: 'Proses', sortable: true,
          render: (p) => '<b class="linky" data-act="openProcess" data-name="' + esc(p.name) + '">' +
                         esc(p.name) + '</b><span class="sub">' +
                         esc(p.description || 'Tanpa keterangan.') + '</span>' },
        { id: 'packageName', label: 'Paket', sortable: true,
          value: (p) => p.packageName || '',
          render: (p) => esc(p.packageName || '—') + ' <span class="tag gray">' +
                         esc(p.packageVersion || '—') + '</span>' },
        { id: 'environment', label: 'Lingkungan', sortable: true,
          value: (p) => p.environment || '' },
        { id: 'jobCount', label: 'Dijalankan', sortable: true, align: 'num',
          value: (p) => Number(p.jobCount) || 0 },
        { id: 'lastRunAt', label: 'Terakhir', sortable: true, align: 'num',
          value: (p) => p.lastRunAt || '',
          render: (p) => p.lastRunAt ? ago(p.lastRunAt) : 'belum pernah' },
        { id: 'tindakan', label: '', render: (p) =>
            '<button class="btn sm primary" data-act="runProcess" data-name="' + esc(p.name) + '">Jalankan</button> ' +
            '<button class="btn sm" data-act="scheduleProcess" data-name="' + esc(p.name) + '">Jadwalkan</button> ' +
            '<button class="btn sm danger" data-act="delProcess" data-name="' + esc(p.name) + '">Hapus</button>' },
    ];

    page.innerHTML = head('Proses', 'Automasi yang siap dijalankan robot.',
        `<button class="btn primary" data-act="newProcess">Proses baru</button>`)
        + subTabs('processes')
        + panel('Semua proses',
            `<div class="panel-body flush">${dataTable('processes', kolom, rows, {
                rowKey: (p) => p.name,
                openable: true,
                empty: 'Belum ada proses. Terbitkan dari JakForge Studio lewat Design → Terbitkan ke ForgeHub, atau buat di sini.',
                searchHint: 'Cari nama proses, paket, atau lingkungan…',
                bulk: [{ act: 'runSelectedProcesses', label: 'Jalankan terpilih' }],
            })}</div>`);
};

ACTIONS.runSelectedProcesses = async (data) => {
    const dipilih = tableSelection(data.key);
    if (!dipilih.length) return;

    if (!await confirmBox('Jalankan proses',
        'Jalankan ' + dipilih.length + ' proses yang terpilih sekarang?', 'Jalankan')) return;

    let berhasil = 0;
    for (const nama of dipilih) {
        try {
            await api('/api/jobs', { method: 'POST', body: { processName: nama, source: 'Manual' } });
            berhasil++;
        } catch (ex) { /* dilaporkan lewat jumlahnya di bawah */ }
    }

    tableState(data.key).selected = [];
    toast(berhasil + ' dari ' + dipilih.length + ' proses dijalankan.', berhasil ? 'ok' : 'bad');
    render();
};

/**
 * Satu proses, dibuka penuh: jalannya dan log tiap jalan.
 *
 * Ini yang membedakan daftar proses dari orkestrator sungguhan. Daftar hanya
 * menjawab "proses apa saja yang ada"; yang orang butuhkan berikutnya selalu
 * "apa yang terjadi waktu proses ini dijalankan" — dan jawabannya tidak boleh
 * berada di halaman lain yang harus disaring sendiri.
 */
PAGES.process = async (page) => {
    const nama = PAGES.process.selected;

    if (!nama) { go('processes'); return; }

    // Log dijemput dengan penyaring proses; pengelompokan per jalan sudah
    // ditangani renderLogGroups, jadi tidak ada dua cara menampilkan log.
    const [semua, jobs, logs] = await Promise.all([
        api('/api/processes'),
        api('/api/jobs?limit=100&process=' + encodeURIComponent(nama)),
        api('/api/logs?limit=300&process=' + encodeURIComponent(nama)),
    ]);

    const p = semua.filter((x) => x.name === nama)[0];

    if (!p) {
        page.innerHTML = head('Proses', '', '')
            + `<div class="panel"><div class="empty"><span class="big">◌</span>
               Proses "${esc(nama)}" sudah tidak ada.</div></div>`;
        return;
    }

    S.logAfterId = logs.length ? logs[logs.length - 1].id : 0;
    S.logLines = logs;

    const kartu = (judul, isi) =>
        `<div class="stat"><div class="k">${esc(judul)}</div><div class="v">${isi}</div></div>`;

    const berhasil = jobs.filter((j) => String(j.state).toUpperCase() === 'SUCCESSFUL').length;
    const gagal = jobs.filter((j) => String(j.state).toUpperCase() === 'FAULTED').length;

    const barisJob = jobs.map((j) => `<tr>
        <td>${stateTag(j.state)}</td>
        <td class="mono">${esc(j.id.substring(0, 12))}</td>
        <td>${esc(j.robotName || '—')}</td>
        <td>${esc(j.source || '—')}</td>
        <td class="num">${when(j.startedAt || j.createdAt)}</td>
        <td class="num">${esc(durasi(j.startedAt, j.endedAt))}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="watchJob" data-id="${esc(j.id)}"
                    data-name="${esc(p.name)}">Log jalan ini</button>
        </td>
    </tr>`);

    page.innerHTML = head(p.name, p.description || 'Tanpa keterangan.',
        `<button class="btn" data-act="backToProcesses">← Semua proses</button>
         <button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn" data-act="scheduleProcess" data-name="${esc(p.name)}">Jadwalkan</button>
         <button class="btn primary" data-act="runProcess" data-name="${esc(p.name)}">Jalankan</button>`)
        + `<div class="stats">
              ${kartu('Paket', esc(p.packageName || '—') + ' <span class="tag gray">' + esc(p.packageVersion || '—') + '</span>')}
              ${kartu('Lingkungan', esc(p.environment || '—'))}
              ${kartu('Dijalankan', p.jobCount)}
              ${kartu('Berhasil / gagal', '<span style="color:#2E7D52">' + berhasil + '</span> / <span style="color:#B4342A">' + gagal + '</span>')}
           </div>`
        + panel('Riwayat jalan', `<div class="panel-body flush">${table(
            ['Keadaan', 'Nomor', 'Robot', 'Sumber', 'Mulai', 'Lama', ''], barisJob,
            'Proses ini belum pernah dijalankan.')}</div>`)
        + panel('Log', `<div class="log-runs" id="logStream">${renderLogGroups(logs)}</div>`,
            '', 'jalan terbaru di atas · buka satu jalan untuk melihat langkahnya');
};

/** Lama satu jalan, dari mulai sampai selesai. */
function durasi(mulai, selesai) {
    if (!mulai) return '—';

    const a = new Date(mulai);
    const b = selesai ? new Date(selesai) : new Date();
    if (isNaN(a) || isNaN(b)) return '—';

    const detik = Math.max(0, Math.round((b - a) / 1000));

    if (detik < 60) return detik + ' dtk';
    if (detik < 3600) return Math.floor(detik / 60) + ' mnt ' + (detik % 60) + ' dtk';
    return Math.floor(detik / 3600) + ' jam ' + Math.floor((detik % 3600) / 60) + ' mnt';
}

PAGES.packages = async (page) => {
    const rows = await api('/api/packages');
    setCount('packages', rows.length);

    const body = rows.map((p) => `<tr>
        <td><b>${esc(p.name)}</b><span class="sub">${esc(p.description || '')}</span></td>
        <td><span class="tag violet">${esc(p.version)}</span></td>
        <td class="mono">${esc(p.entryPoint || '—')}</td>
        <td>${esc(p.publishedBy || '—')}</td>
        <td class="num">${bytes(p.sizeBytes)}</td>
        <td class="num">${when(p.publishedAt)}</td>
        <td style="white-space:nowrap">
            <a class="btn sm" href="/api/packages/${encodeURIComponent(p.name)}/${encodeURIComponent(p.version)}/content">Unduh</a>
            <button class="btn sm danger" data-act="delPackage" data-name="${esc(p.name)}" data-version="${esc(p.version)}">Hapus</button>
        </td>
    </tr>`);

    page.innerHTML = head('Paket', 'Berkas automasi yang diterbitkan dari Studio.',
        `<button class="btn" data-act="refresh">Muat ulang</button>`)
        + panel('Semua paket', `<div class="panel-body flush">${table(
            ['Paket', 'Versi', 'Titik masuk', 'Diterbitkan oleh', 'Ukuran', 'Waktu', 'Tindakan'], body,
            'Belum ada paket. Di Studio, buka tab Design lalu tekan Terbitkan ke ForgeHub.')}</div>`);
};

// =====================================================================
// Halaman: Pekerjaan & Pemicu
// =====================================================================

PAGES.jobs = async (page) => {
    const filter = PAGES.jobs.filter || '';
    const rows = await api('/api/jobs?limit=500' + (filter ? '&state=' + filter : ''));

    const kolom = [
        { id: 'processName', label: 'Proses', sortable: true,
          render: (j) => '<b class="linky" data-act="openProcess" data-name="' + esc(j.processName) + '">' +
                         esc(j.processName) + '</b><span class="sub mono">' + esc(j.id.substring(0, 12)) + '</span>' },
        { id: 'robotName', label: 'Robot', sortable: true,
          value: (j) => j.robotName || '', render: (j) => esc(j.robotName || '—') },
        { id: 'state', label: 'Keadaan', sortable: true, render: (j) => stateTag(j.state) },
        { id: 'progress', label: 'Kemajuan', render: (j) => progressBar(j.progress, j.state) },
        { id: 'source', label: 'Sumber', sortable: true },
        { id: 'startedAt', label: 'Mulai', sortable: true, align: 'num',
          value: (j) => j.startedAt || j.createdAt || '',
          render: (j) => when(j.startedAt || j.createdAt) },
        { id: 'endedAt', label: 'Selesai', sortable: true, align: 'num',
          value: (j) => j.endedAt || '', render: (j) => j.endedAt ? when(j.endedAt) : '—' },

        // Lama jalan: kolom yang paling dicari saat membandingkan dua jalan,
        // dan sebelumnya tidak ada sama sekali. Diurutkan menurut DETIK, bukan
        // menurut teksnya — kalau tidak, "9 dtk" berada sesudah "10 mnt".
        { id: 'durasi', label: 'Lama', sortable: true, align: 'num',
          value: (j) => detikJalan(j),
          render: (j) => esc(durasi(j.startedAt, j.endedAt)) },
        { id: 'tindakan', label: '', render: (j) =>
            (j.state === 'RUNNING' || j.state === 'PENDING')
                ? '<button class="btn sm" data-act="watchJob" data-id="' + esc(j.id) + '" data-name="' +
                  esc(j.processName) + '">Log</button> ' +
                  '<button class="btn sm danger" data-act="stopJob" data-id="' + esc(j.id) + '">Hentikan</button>'
                : '<button class="btn sm" data-act="watchJob" data-id="' + esc(j.id) + '" data-name="' +
                  esc(j.processName) + '">' + esc(t('Log')) + '</button>' },
    ];

    const options = ['', 'PENDING', 'RUNNING', 'SUCCESSFUL', 'FAULTED', 'STOPPED']
        .map((s) => `<option value="${s}"${s === filter ? ' selected' : ''}>${s ? esc(STATE_ID[s]) : 'Semua keadaan'}</option>`)
        .join('');

    page.innerHTML = head('Pantau Pekerjaan', 'Setiap kali proses dijalankan tercatat di sini.',
        `<div class="filters">
            <select id="jobFilter">${options}</select>
            <button class="btn primary" data-act="newJob">Jalankan proses</button>
         </div>`)
        + subTabs('jobs')
        + panel('Riwayat pekerjaan',
            `<div class="panel-body flush">${dataTable('jobs', kolom, rows, {
                rowKey: (j) => j.id,
                empty: 'Belum ada pekerjaan.',
                searchHint: 'Cari proses, robot, atau nomor pekerjaan…',
                bulk: [{ act: 'stopSelectedJobs', label: 'Hentikan terpilih', danger: true }],
            })}</div>`);

    $('jobFilter').addEventListener('change', (event) => {
        PAGES.jobs.filter = event.target.value;
        tableState('jobs').page = 1;
        render();
    });
};

/** Lama satu jalan dalam DETIK; dipakai untuk mengurutkan kolom Lama. */
function detikJalan(j) {
    if (!j.startedAt) return -1;
    const a = new Date(j.startedAt);
    const b = j.endedAt ? new Date(j.endedAt) : new Date();
    if (isNaN(a) || isNaN(b)) return -1;
    return Math.max(0, Math.round((b - a) / 1000));
}

ACTIONS.stopSelectedJobs = async (data) => {
    const dipilih = tableSelection(data.key);
    if (!dipilih.length) return;

    if (!await confirmBox('Hentikan pekerjaan',
        'Hentikan ' + dipilih.length + ' pekerjaan yang terpilih?', 'Hentikan', true)) return;

    // Satu per satu, dan kegagalan satu tidak menghentikan sisanya: pekerjaan
    // yang sudah selesai sendiri di antaranya tidak boleh membatalkan
    // penghentian yang lain.
    let berhasil = 0;
    for (const id of dipilih) {
        try {
            await api('/api/jobs/' + encodeURIComponent(id) + '/stop', { method: 'POST' });
            berhasil++;
        } catch (ex) { /* dilaporkan lewat jumlahnya di bawah */ }
    }

    tableState(data.key).selected = [];
    toast(berhasil + ' dari ' + dipilih.length + ' pekerjaan dihentikan.', berhasil ? 'ok' : 'bad');
    render();
};

PAGES.triggers = async (page) => {
    const rows = await api('/api/triggers');
    setCount('triggers', rows.filter((t) => t.enabled).length);

    const body = rows.map((t) => `<tr>
        <td><b>${esc(t.name)}</b></td>
        <td>${esc(t.processName)}</td>
        <td>${esc(t.robotName || 'robot mana saja')}</td>
        <td>tiap ${t.intervalMinutes} menit</td>
        <td>${t.enabled ? '<span class="tag green">Aktif</span>' : '<span class="tag gray">Mati</span>'}</td>
        <td class="num">${t.enabled ? when(t.nextRunAt) : '—'}</td>
        <td class="num">${t.lastRunAt ? ago(t.lastRunAt) : 'belum pernah'}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="toggleTrigger" data-name="${esc(t.name)}">${t.enabled ? 'Matikan' : 'Nyalakan'}</button>
            <button class="btn sm danger" data-act="delTrigger" data-name="${esc(t.name)}">Hapus</button>
        </td>
    </tr>`);

    page.innerHTML = head('Pemicu & Jadwal', 'Proses yang dijalankan sendiri menurut selang waktu.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newTrigger">Pemicu baru</button>`)
        + panel('Semua pemicu', `<div class="panel-body flush">${table(
            ['Pemicu', 'Proses', 'Robot', 'Selang', 'Keadaan', 'Berikutnya', 'Terakhir', 'Tindakan'], body,
            'Belum ada pemicu. Buat satu supaya proses berjalan tanpa ditekan tiap kali.')}</div>`);
};

// =====================================================================
// Halaman: Robot, Mesin, Lingkungan, Kredensial
// =====================================================================

PAGES.robots = async (page) => {
    const rows = await api('/api/robots');
    setCount('robots', rows.length);

    const body = rows.map((r) => `<tr>
        <td><b>${esc(r.name)}</b><span class="sub">${esc(r.description || '')}</span></td>
        <td>${esc(r.machineName || '—')}</td>
        <td><span class="tag ${r.type === 'Attended' ? 'blue' : 'violet'}">${esc(r.type)}</span></td>
        <td>${esc(r.environment || '—')}</td>
        <td>${stateTag(r.status)}</td>
        <td class="num">${(Number(r.cpuPercent) || 0).toFixed(1)} % / ${Math.round(Number(r.memoryMb) || 0)} MB</td>
        <td class="num">${ago(r.lastHeartbeatAt)}</td>
        <td><button class="btn sm danger" data-act="delRobot" data-name="${esc(r.name)}">Hapus</button></td>
    </tr>`);

    page.innerHTML = head('Robot', 'Setiap JakRunner yang menyambung muncul di sini dengan sendirinya.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newRobot">Daftarkan robot</button>`)
        + panel('Semua robot', `<div class="panel-body flush">${table(
            ['Robot', 'Mesin', 'Jenis', 'Lingkungan', 'Keadaan', 'CPU / Memori', 'Denyut terakhir', ''], body,
            'Belum ada robot. Isi jakrunner.json di sebelah JakRunner.exe dengan alamat ForgeHub ini, lalu jalankan JakRunner.')}</div>`);
};

PAGES.machines = async (page) => {
    const rows = await api('/api/machines');

    const body = rows.map((m) => `<tr>
        <td><b>${esc(m.name)}</b><span class="sub">${esc(m.description || '')}</span></td>
        <td><span class="tag gray">${esc(m.type)}</span></td>
        <td class="num">${m.robotCount}</td>
        <td class="num">${when(m.createdAt)}</td>
        <td><button class="btn sm danger" data-act="delMachine" data-name="${esc(m.name)}">Hapus</button></td>
    </tr>`);

    page.innerHTML = head('Mesin', 'Komputer tempat robot berjalan.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newMachine">Mesin baru</button>`)
        + panel('Semua mesin', `<div class="panel-body flush">${table(
            ['Mesin', 'Jenis', 'Robot', 'Didaftarkan', ''], body, 'Belum ada mesin.')}</div>`);
};

PAGES.environments = async (page) => {
    const rows = await api('/api/environments');

    const body = rows.map((e) => `<tr>
        <td><b>${esc(e.name)}</b></td>
        <td>${esc(e.description || '—')}</td>
        <td class="num">${e.robotCount}</td>
        <td><button class="btn sm danger" data-act="delEnvironment" data-name="${esc(e.name)}">Hapus</button></td>
    </tr>`);

    page.innerHTML = head('Lingkungan', 'Pemisah antara yang sedang diuji dan yang sudah dipakai sungguhan.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newEnvironment">Lingkungan baru</button>`)
        + panel('Semua lingkungan', `<div class="panel-body flush">${table(
            ['Lingkungan', 'Keterangan', 'Robot', ''], body, 'Belum ada lingkungan.')}</div>`);
};

PAGES.credentials = async (page) => {
    const rows = await api('/api/credentials');

    const body = rows.map((c) => `<tr>
        <td><b>${esc(c.name)}</b><span class="sub">${esc(c.description || '')}</span></td>
        <td>${esc(c.username || '—')}</td>
        <td><span class="tag gray">tersimpan tersandi</span></td>
        <td class="num">${when(c.createdAt)}</td>
        <td><button class="btn sm danger" data-act="delCredential" data-name="${esc(c.name)}">Hapus</button></td>
    </tr>`);

    page.innerHTML = head('Kredensial', 'Nama pengguna dan sandi yang dipakai robot untuk masuk ke aplikasi lain.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newCredential">Kredensial baru</button>`)
        + panel('Semua kredensial', `<div class="panel-body flush">${table(
            ['Nama', 'Pengguna', 'Sandi', 'Dibuat', ''], body, 'Belum ada kredensial.')}</div>`,
            '', 'nilai sandi tidak pernah ditampilkan di daftar');
};

// =====================================================================
// Halaman: Antrean, Aset, Gudang
// =====================================================================

PAGES.queues = async (page) => {
    const rows = await api('/api/queues');
    setCount('queues', rows.reduce((sum, q) => sum + q.newCount, 0));

    const body = rows.map((q) => `<tr>
        <td><b>${esc(q.name)}</b><span class="sub">${esc(q.description || '')}</span></td>
        <td class="num"><span class="tag amber">${q.newCount}</span></td>
        <td class="num"><span class="tag blue">${q.inProgressCount}</span></td>
        <td class="num"><span class="tag green">${q.successfulCount}</span></td>
        <td class="num"><span class="tag red">${q.failedCount}</span></td>
        <td class="num">${q.totalCount}</td>
        <td class="num">${q.maxRetries}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="queueItems" data-name="${esc(q.name)}">Lihat isi</button>
            <button class="btn sm" data-act="addItem" data-name="${esc(q.name)}">Tambah butir</button>
            <button class="btn sm danger" data-act="delQueue" data-name="${esc(q.name)}">Hapus</button>
        </td>
    </tr>`);

    let html = head('Antrean', 'Daftar pekerjaan yang dibagikan antar robot.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newQueue">Antrean baru</button>`)
        + panel('Semua antrean', `<div class="panel-body flush">${table(
            ['Antrean', 'Baru', 'Diproses', 'Berhasil', 'Gagal', 'Total', 'Coba ulang', 'Tindakan'], body,
            'Belum ada antrean.')}</div>`);

    // Isi antrean ditampilkan di bawah daftarnya, bukan di halaman terpisah:
    // yang dicari orang saat membuka isi adalah membandingkannya dengan angka
    // ringkasan di atas.
    if (PAGES.queues.open) {
        const items = await api('/api/queues/' + encodeURIComponent(PAGES.queues.open) + '/items?limit=200');

        const itemRows = items.map((i) => `<tr>
            <td class="mono">${esc(i.reference || i.id.substring(0, 10))}</td>
            <td>${stateTag(i.status)}</td>
            <td>${esc(i.priority)}</td>
            <td class="num">${i.retries}</td>
            <td>${esc(i.robotName || '—')}</td>
            <td class="mono" style="max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(i.content || '')}</td>
            <td class="mono" style="color:var(--red)">${esc(i.exception || '')}</td>
            <td class="num">${when(i.createdAt)}</td>
            <td><button class="btn sm danger" data-act="delItem" data-id="${esc(i.id)}">Hapus</button></td>
        </tr>`);

        html += panel('Isi antrean: ' + PAGES.queues.open,
            `<div class="panel-body flush">${table(
                ['Referensi', 'Keadaan', 'Prioritas', 'Ulangan', 'Robot', 'Isi', 'Kesalahan', 'Dibuat', ''],
                itemRows, 'Antrean ini kosong.')}</div>`,
            `<button class="btn sm" data-act="closeQueue">Tutup</button>`);
    }

    page.innerHTML = html;
};

PAGES.assets = async (page) => {
    const rows = await api('/api/assets');
    setCount('assets', rows.length);

    const body = rows.map((a) => `<tr>
        <td><b>${esc(a.name)}</b><span class="sub">${esc(a.description || '')}</span></td>
        <td><span class="tag ${a.type === 'Credential' || a.type === 'Secret' ? 'red' : 'gray'}">${esc(a.type)}</span></td>
        <td class="mono">${a.valueText !== null && a.valueText !== undefined
            ? esc(a.valueText)
            : (a.hasValue ? '<span style="color:var(--muted)">••••••••</span>' : '<span style="color:var(--muted)">kosong</span>')}</td>
        <td>${esc(a.scope)}</td>
        <td class="num">${when(a.updatedAt || a.createdAt)}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="editAsset" data-name="${esc(a.name)}" data-type="${esc(a.type)}">Ubah</button>
            <button class="btn sm danger" data-act="delAsset" data-name="${esc(a.name)}">Hapus</button>
        </td>
    </tr>`);

    page.innerHTML = head('Aset', 'Nilai yang dibaca robot saat berjalan — alamat berkas, ambang batas, sandi.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newAsset">Aset baru</button>`)
        + panel('Semua aset', `<div class="panel-body flush">${table(
            ['Aset', 'Tipe', 'Nilai', 'Cakupan', 'Diperbarui', 'Tindakan'], body,
            'Belum ada aset.')}</div>`);
};

PAGES.buckets = async (page) => {
    const rows = await api('/api/buckets');

    const body = rows.map((b) => `<tr>
        <td><b>${esc(b.name)}</b><span class="sub">${esc(b.description || '')}</span></td>
        <td class="num">${b.fileCount}</td>
        <td class="num">${bytes(b.totalBytes)}</td>
        <td class="num">${when(b.createdAt)}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="bucketFiles" data-name="${esc(b.name)}">Lihat berkas</button>
            <button class="btn sm danger" data-act="delBucket" data-name="${esc(b.name)}">Hapus</button>
        </td>
    </tr>`);

    let html = head('Gudang Berkas', 'Berkas yang dipakai bersama oleh proses.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newBucket">Gudang baru</button>`)
        + panel('Semua gudang', `<div class="panel-body flush">${table(
            ['Gudang', 'Berkas', 'Ukuran', 'Dibuat', 'Tindakan'], body, 'Belum ada gudang.')}</div>`);

    if (PAGES.buckets.open) {
        const name = PAGES.buckets.open;
        const files = await api('/api/buckets/' + encodeURIComponent(name) + '/files');

        const fileRows = files.map((f) => `<tr>
            <td><b>${esc(f.fileName)}</b></td>
            <td>${esc(f.contentType || '—')}</td>
            <td class="num">${bytes(f.sizeBytes)}</td>
            <td>${esc(f.uploadedBy || '—')}</td>
            <td class="num">${when(f.uploadedAt)}</td>
            <td style="white-space:nowrap">
                <a class="btn sm" href="/api/buckets/${encodeURIComponent(name)}/files/${encodeURIComponent(f.id)}/content">Unduh</a>
                <button class="btn sm danger" data-act="delFile" data-bucket="${esc(name)}" data-id="${esc(f.id)}">Hapus</button>
            </td>
        </tr>`);

        html += panel('Berkas dalam: ' + name,
            `<div class="panel-body flush">${table(
                ['Berkas', 'Tipe', 'Ukuran', 'Diunggah oleh', 'Waktu', 'Tindakan'], fileRows,
                'Gudang ini kosong.')}</div>`,
            `<button class="btn sm primary" data-act="uploadFile" data-name="${esc(name)}">Unggah berkas</button>
             <button class="btn sm" data-act="closeBucket">Tutup</button>`);
    }

    page.innerHTML = html;
};

// =====================================================================
// Halaman: Log & Peringatan
// =====================================================================

/**
 * Bagian kueri log yang sedang berlaku.
 *
 * Dipakai dua tempat — penggambaran pertama dan penjemputan berkala — dan
 * keduanya HARUS memakai penyaring yang sama. Kalau berbeda, penjemputan
 * berkala menambahkan baris yang tidak lolos penyaring, dan daftar yang tampil
 * perlahan berisi hal yang tidak diminta.
 */
function logFilterQuery() {
    // Halaman detail proses memakai aliran log yang SAMA, hanya dengan
    // penyaring yang berbeda. Kalau penjemputan berkala memakai penyaring
    // lain daripada gambar pertamanya, daftar yang tampil perlahan berisi
    // baris dari proses lain.
    if (S.page === 'process') {
        return '&process=' + encodeURIComponent(PAGES.process.selected || '');
    }

    const level = PAGES.logs.level || '';
    const search = PAGES.logs.q || '';
    const jobId = PAGES.logs.jobId || '';

    return (level ? '&level=' + encodeURIComponent(level) : '')
        + (search ? '&q=' + encodeURIComponent(search) : '')
        + (jobId ? '&jobId=' + encodeURIComponent(jobId) : '');
}

PAGES.logs = async (page) => {
    const level = PAGES.logs.level || '';
    const search = PAGES.logs.q || '';
    const jobId = PAGES.logs.jobId || '';

    // Halaman log digambar penuh setiap kali dibuka, lalu baris barunya
    // ditambahkan saja oleh pollLogs() — tanpa itu, daftar akan berkedip
    // dari awal tiap beberapa detik dan gulirannya melompat ke atas.
    S.logAfterId = 0;
    S.logLines = [];

    const rows = await api('/api/logs?limit=200' + logFilterQuery());
    S.logLines = rows;
    if (rows.length) S.logAfterId = rows[rows.length - 1].id;

    const options = ['', 'INFO', 'WARN', 'ERROR', 'DEBUG', 'TRACE']
        .map((l) => `<option value="${l}"${l === level ? ' selected' : ''}>${l || 'Semua tingkat'}</option>`).join('');

    const watching = jobId
        ? `<div class="panel" style="margin-bottom:14px">
               <div class="panel-head">
                   <span class="tag blue">Satu pekerjaan</span>
                   <h3 style="margin-left:4px">${esc(PAGES.logs.jobLabel || jobId)}</h3>
                   <span class="hint">hanya baris dari jalan ini</span>
                   <div class="spacer"></div>
                   <button class="btn sm" data-act="watchAll">Tampilkan semua log</button>
               </div>
           </div>`
        : '';

    page.innerHTML = head('Log Langsung',
        jobId ? 'Langkah demi langkah robot yang sedang mengerjakan pekerjaan ini.'
              : 'Baris log dari semua robot, diperbarui sendiri.',
        `<div class="filters">
            <input id="logSearch" placeholder="Cari isi pesan…" value="${esc(search)}" />
            <select id="logLevel">${options}</select>
            <button class="btn" data-act="refresh">Muat ulang</button>
            <button class="btn danger" data-act="clearLogs">Kosongkan</button>
         </div>`)
        + watching
        + panel('Jalan robot', `<div class="log-runs" id="logStream">${renderLogGroups(rows)}</div>`,
            '', 'jalan terbaru di atas · buka satu jalan untuk melihat langkahnya · diperbarui tiap 5 detik');

    // Guliran TIDAK lagi dipaksa ke bawah. Jalan terbaru ada di ATAS dan sudah
    // terbuka, jadi yang paling ingin dilihat orang sudah terlihat sejak awal;
    // menyeret ke ujung bawah justru memperlihatkan jalan yang paling lama.

    $('logLevel').addEventListener('change', (e) => { PAGES.logs.level = e.target.value; render(); });

    let debounce = null;
    $('logSearch').addEventListener('input', (e) => {
        clearTimeout(debounce);
        const value = e.target.value;
        debounce = setTimeout(() => { PAGES.logs.q = value; render(); }, 400);
    });
};

// =====================================================================
// Log dikelompokkan per JALAN, bukan satu aliran gabungan
// =====================================================================
//
// Satu aliran datar berisi semua robot sekaligus tidak bisa dibaca begitu ada
// lebih dari satu pekerjaan berjalan: baris dari dua proses berselang-seling,
// dan tidak ada cara melihat "apa yang terjadi pada jalan ini" tanpa memindai
// dengan mata.
//
// Jadi log disarangkan ke dalam jalan yang menghasilkannya, seperti orkestrator
// RPA pada umumnya: daftar jalan (terbaru di atas), masing-masing bisa dibuka
// untuk melihat langkah-langkahnya sendiri (terlama di atas, karena begitulah
// urutan kejadiannya).

/** Kunci pengelompokan sebuah baris. */
function logGroupKey(l) {
    if (l.jobId) return 'job:' + l.jobId;
    if (l.processName) return 'proc:' + l.processName;
    return 'lain';
}

/** Id elemen DOM untuk satu kelompok. Dibersihkan supaya aman sebagai id. */
function logGroupDomId(key) {
    return 'lg_' + key.replace(/[^A-Za-z0-9_-]/g, '_');
}

/**
 * Kelompokkan baris menjadi jalan-jalan.
 *
 * Baris datang urut menaik menurut id, jadi baris pertama sebuah kelompok
 * adalah awal jalannya dan yang terakhir adalah keadaan terkininya.
 */
function groupLogs(rows) {
    const peta = new Map();

    for (const l of rows) {
        const key = logGroupKey(l);
        let g = peta.get(key);

        if (!g) {
            g = {
                key: key,
                jobId: l.jobId || null,
                nama: l.processName || l.robotName || 'Tanpa nama proses',
                robot: l.robotName || '',
                baris: [],
                galat: 0,
                peringatan: 0,
                idTerakhir: 0
            };
            peta.set(key, g);
        }

        const lv = String(l.level || '').toUpperCase();
        if (lv === 'ERROR' || lv === 'FATAL') g.galat++;
        else if (lv === 'WARN' || lv === 'WARNING') g.peringatan++;

        g.baris.push(l);
        if (l.id > g.idTerakhir) g.idTerakhir = l.id;
    }

    // Jalan terbaru di atas: yang paling akhir menulis, itu yang paling
    // mungkin sedang dilihat orang.
    //
    // Kecuali "lain" -- baris yang datang tanpa pekerjaan maupun nama proses.
    // Itu bukan sebuah jalan, cuma tempat penampungan, jadi ia SELALU di bawah
    // betapapun barunya. Kalau tidak, satu baris nyasar bisa mendorong jalan
    // yang sedang berlangsung ke bawah layar.
    return Array.from(peta.values()).sort((a, b) => {
        if (a.key === 'lain') return 1;
        if (b.key === 'lain') return -1;
        return b.idTerakhir - a.idTerakhir;
    });
}

function renderLogLines(rows) {
    return rows.map((l) => `<div class="line">
        <span class="t">${clock(l.loggedAt)}</span>
        <span class="lv ${esc(String(l.level).toUpperCase())}">${esc(l.level)}</span>
        ${l.robotName ? `<span class="log-who">[${esc(l.robotName)}]</span>` : ''}
        <span>${esc(l.message)}</span>
    </div>`).join('');
}

/** Ringkasan di kepala kelompok: cukup untuk tahu perlu dibuka atau tidak. */
function logGroupSummary(g) {
    const bagian = [g.baris.length + ' baris'];

    if (g.galat) bagian.push(g.galat + ' galat');
    if (g.peringatan) bagian.push(g.peringatan + ' peringatan');

    const awal = g.baris[0];
    const akhir = g.baris[g.baris.length - 1];
    if (awal && akhir) bagian.push(clock(awal.loggedAt) + ' – ' + clock(akhir.loggedAt));

    return bagian.join(' · ');
}

/**
 * Satu jalan. Memakai <details> bawaan peramban, bukan buka-tutup buatan
 * sendiri: sudah bisa dipakai dengan papan ketik dan tetap benar tanpa JS.
 */
function renderLogGroup(g, terbuka) {
    const domId = logGroupDomId(g.key);
    const tag = g.galat ? '<span class="tag red">gagal</span>' : '<span class="tag blue">jalan</span>';

    return `<details class="log-run" id="${domId}"${terbuka ? ' open' : ''}>
        <summary>
            ${tag}
            <span class="log-run-name">${esc(g.nama)}</span>
            ${g.robot ? `<span class="log-who">[${esc(g.robot)}]</span>` : ''}
            <span class="log-run-meta" id="${domId}_meta">${esc(logGroupSummary(g))}</span>
            ${g.jobId ? `<button class="btn sm" data-act="watchJob"
                 data-id="${esc(g.jobId)}" data-name="${esc(g.nama)}">Hanya ini</button>` : ''}
        </summary>
        <div class="logs" id="${domId}_lines">${renderLogLines(g.baris)}</div>
    </details>`;
}

function renderLogGroups(rows) {
    if (!rows.length) {
        return '<div style="color:#8A7A63;padding:14px 0">Belum ada log. Jalankan sebuah proses dari JakRunner atau Studio.</div>';
    }

    const groups = groupLogs(rows);

    // Hanya jalan terbaru yang dibuka. Membuka semuanya mengembalikan persis
    // dinding teks yang ingin dihindari.
    return groups.map((g, i) => renderLogGroup(g, i === 0)).join('');
}

PAGES.alerts = async (page) => {
    const rows = await api('/api/alerts?limit=200');
    const unread = rows.filter((a) => !a.isRead).length;

    setCount('alerts', unread);
    $('alertBadge').hidden = !unread;
    $('alertBadge').textContent = unread;

    const body = rows.length
        ? `<div class="feed" style="max-height:none">${rows.map((a) => `
            <div class="item${a.isRead ? '' : ' unread'}">
                <span class="pip ${esc(a.severity)}"></span>
                <div style="flex:1">
                    <div class="t">${esc(a.title)}</div>
                    <div class="m">${esc(a.message || '')}</div>
                    <div class="w">${esc(a.source || '')} · ${when(a.createdAt)}</div>
                </div>
                ${a.isRead ? '' : `<button class="btn sm" data-act="readAlert" data-id="${a.id}">Tandai</button>`}
            </div>`).join('')}</div>`
        : `<div class="empty"><span class="big">✓</span>Tidak ada peringatan. Semuanya berjalan mulus.</div>`;

    page.innerHTML = head('Peringatan', 'Kejadian yang perlu diperhatikan: kegagalan, robot baru, paket terbit.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         ${unread ? `<button class="btn primary" data-act="readAll">Tandai semua terbaca</button>` : ''}`)
        + panel('Umpan peringatan', body);
};

// =====================================================================
// Halaman: Administrasi
// =====================================================================

PAGES.users = async (page) => {
    const rows = await api('/api/users');

    const body = rows.map((u) => `<tr>
        <td><b>${esc(u.username)}</b><span class="sub">${esc(u.displayName || '')}</span></td>
        <td>${esc(u.email || '—')}</td>
        <td><span class="tag ${u.role === 'Administrator' ? 'violet' : 'gray'}">${esc(u.role)}</span></td>
        <td>${u.isActive ? '<span class="tag green">Aktif</span>' : '<span class="tag gray">Nonaktif</span>'}</td>
        <td class="num">${u.lastLoginAt ? ago(u.lastLoginAt) : 'belum pernah'}</td>
        <td style="white-space:nowrap">
            <button class="btn sm" data-act="editUser" data-name="${esc(u.username)}" data-role="${esc(u.role)}"
                    data-display="${esc(u.displayName || '')}" data-email="${esc(u.email || '')}">Ubah</button>
            <button class="btn sm danger" data-act="delUser" data-name="${esc(u.username)}">Hapus</button>
        </td>
    </tr>`);

    page.innerHTML = head('Pengguna', 'Siapa saja yang boleh masuk ke ForgeHub ini.',
        `<button class="btn" data-act="refresh">Muat ulang</button>
         <button class="btn primary" data-act="newUser">Pengguna baru</button>`)
        + panel('Semua pengguna', `<div class="panel-body flush">${table(
            ['Pengguna', 'Surel', 'Peran', 'Keadaan', 'Masuk terakhir', 'Tindakan'], body, 'Tidak ada pengguna.')}</div>`);
};

PAGES.roles = async (page) => {
    const rows = await api('/api/roles');

    const body = rows.map((r) => `<tr>
        <td><b>${esc(r.name)}</b></td>
        <td>${esc(r.description || '—')}</td>
        <td class="mono" style="font-size:11.5px">${esc(r.permissions || '—')}</td>
        <td class="num">${r.userCount}</td>
    </tr>`);

    page.innerHTML = head('Peran', 'Kelompok hak yang bisa diberikan ke pengguna.',
        `<button class="btn" data-act="refresh">Muat ulang</button>`)
        + panel('Semua peran', `<div class="panel-body flush">${table(
            ['Peran', 'Keterangan', 'Hak', 'Pengguna'], body, 'Tidak ada peran.')}</div>`);
};

PAGES.licensing = async (page) => {
    const rows = await api('/api/licensing');

    const body = rows.map((l) => `<tr>
        <td><b>${esc(l.product)}</b></td>
        <td class="num">${l.total === 0 ? '<span class="tag green">Tanpa batas</span>' : l.total}</td>
        <td class="num">${l.used}</td>
        <td class="num">${l.expiresAt ? when(l.expiresAt) : '<span class="tag green">Tanpa masa berlaku</span>'}</td>
    </tr>`);

    page.innerHTML = head('Lisensi', 'Pemakaian robot dibanding jatah lisensi.',
        `<button class="btn" data-act="refresh">Muat ulang</button>`)
        + panel('Lisensi', `<div class="panel-body flush">${table(
            ['Produk', 'Jatah', 'Terpakai', 'Berlaku sampai'], body, 'Tidak ada lisensi.')}</div>`)
        + panel('Catatan', `<div class="panel-body" style="color:var(--muted);line-height:1.7">
            Pemasangan ForgeHub mandiri ini tidak membatasi jumlah robot. Baris di atas ada supaya
            pemakaian tetap terlihat, dan supaya bentuk datanya sudah siap jika nanti pembatasan diberlakukan.
        </div>`);
};

PAGES.settings = async (page) => {
    const s = await api('/api/settings');

    page.innerHTML = head('Pengaturan', 'Keterangan pemasangan ForgeHub ini.',
        `<button class="btn" data-act="changePassword">Ganti kata sandi</button>`)
        + `<div class="grid-2">
            ${panel('Server', `<div class="panel-body"><div class="kv">
                <div class="k">Penyewa</div><div class="v">${esc(s.tenant)}</div>
                <div class="k">Mesin</div><div class="v">${esc(s.machineName)}</div>
                <div class="k">Waktu server</div><div class="v">${when(s.serverTime)}</div>
                <div class="k">Folder data</div><div class="v mono">${esc(s.dataDirectory)}</div>
                <div class="k">Robot dianggap putus setelah</div><div class="v">${s.robotOfflineAfterSeconds} detik</div>
                <div class="k">Token berlaku</div><div class="v">${s.tokenLifetimeHours} jam</div>
            </div></div>`)}

            ${panel('Isi basis data', `<div class="panel-body"><div class="kv">
                <div class="k">Pengguna</div><div class="v">${s.counts.users}</div>
                <div class="k">Robot</div><div class="v">${s.counts.robots}</div>
                <div class="k">Proses</div><div class="v">${s.counts.processes}</div>
                <div class="k">Pekerjaan</div><div class="v">${s.counts.jobs}</div>
                <div class="k">Baris log</div><div class="v">${s.counts.logs}</div>
            </div></div>`)}
        </div>`

        + panel('Menyambungkan JakRunner',
            `<div class="panel-body" style="line-height:1.75">
                Buat berkas <b>jakrunner.json</b> di folder yang sama dengan <b>JakRunner.exe</b>:
                <pre class="mono" style="background:#2B2118;color:#D9CEB9;padding:14px 16px;border-radius:10px;overflow-x:auto">{
  "forgeHubUrl": "${esc(location.origin)}",
  "username": "${esc(S.user.username)}",
  "password": "&lt;kata sandi Anda&gt;",
  "robotName": "&lt;nama robot, boleh dikosongkan&gt;"
}</pre>
                JakRunner akan mendaftarkan dirinya sendiri pada denyut pertama — tidak perlu
                membuat robotnya lebih dulu di sini.
            </div>`)

        + panel('Menyambungkan JakForge Studio',
            `<div class="panel-body" style="line-height:1.75">
                Di Studio, buka tab <b>Design</b> → grup <b>ForgeHub</b> → <b>Sambungkan</b>,
                lalu isi alamat <span class="mono">${esc(location.origin)}</span> beserta nama pengguna dan
                kata sandi. Setelah tersambung, tombol <b>Terbitkan</b> mengirim proyek yang sedang
                terbuka ke ForgeHub sebagai paket, dan langsung tersedia untuk dijalankan robot.
            </div>`);
};

// =====================================================================
// Tindakan
// =====================================================================

ACTIONS.refresh = () => render();
ACTIONS.goJobs = () => go('jobs');
ACTIONS.goRobots = () => go('robots');
ACTIONS.goQueues = () => go('queues');

ACTIONS.newJob = async () => {
    const processes = await api('/api/processes');

    if (!processes.length) {
        toast('Belum ada proses yang bisa dijalankan. Terbitkan satu dari Studio dulu.', 'bad');
        return;
    }

    const robots = await api('/api/robots');

    const values = await form('Jalankan proses',
        'Pekerjaan masuk antrean dan diambil robot yang siap.',
        [
            { name: 'processName', label: 'Proses', type: 'select',
              options: processes.map((p) => p.name) },
            { name: 'robotName', label: 'Robot', type: 'select',
              options: [{ value: '', label: 'Robot mana saja yang siap' }].concat(robots.map((r) => r.name)) },
            { name: 'priority', label: 'Prioritas', type: 'select', value: 'Normal',
              options: ['High', 'Normal', 'Low'] },
        ], 'Jalankan');

    if (!values) return;

    await act(api('/api/jobs', { method: 'POST', body: values }), 'Pekerjaan dijadwalkan.');
};

ACTIONS.runProcess = async (data) => {
    await act(api('/api/jobs', {
        method: 'POST',
        body: { processName: data.name, source: 'Manual', priority: 'Normal' },
    }), 'Pekerjaan untuk ' + data.name + ' dijadwalkan.');
};

ACTIONS.stopJob = async (data) => {
    await act(api('/api/jobs/' + encodeURIComponent(data.id) + '/stop', { method: 'POST' }),
        'Permintaan berhenti dikirim.');
};

ACTIONS.jobInfo = async (data) => {
    const j = await api('/api/jobs/' + encodeURIComponent(data.id));

    $('dialog').innerHTML = `
        <button class="dlg-x" data-act="closeDialog" title="Tutup" aria-label="Tutup">✕</button>
        <h3>${esc(j.processName)}</h3>
        <p class="lead">${esc(j.info || '')}</p>
        <div class="kv" style="grid-template-columns:150px 1fr">
            <div class="k">Nomor</div><div class="v mono">${esc(j.id)}</div>
            <div class="k">Keadaan</div><div class="v">${stateTag(j.state)}</div>
            <div class="k">Robot</div><div class="v">${esc(j.robotName || '—')}</div>
            <div class="k">Sumber</div><div class="v">${esc(j.source)}</div>
            <div class="k">Prioritas</div><div class="v">${esc(j.priority)}</div>
            <div class="k">Dibuat</div><div class="v">${when(j.createdAt)}</div>
            <div class="k">Mulai</div><div class="v">${when(j.startedAt)}</div>
            <div class="k">Selesai</div><div class="v">${when(j.endedAt)}</div>
            <div class="k">Masukan</div><div class="v mono">${esc(j.inputJson || '—')}</div>
            <div class="k">Keluaran</div><div class="v mono">${esc(j.outputJson || '—')}</div>
        </div>
        <div class="actions"><button class="btn primary" onclick="closeDialog()">Tutup</button></div>`;

    $('veil').classList.add('open');
};

ACTIONS.newProcess = async () => {
    const environments = await api('/api/environments');

    const values = await form('Proses baru',
        'Biasanya proses dibuat sendiri saat Studio menerbitkan paket. Buat manual kalau paketnya diunggah dengan cara lain.',
        [
            { name: 'name', label: 'Nama proses' },
            { name: 'environment', label: 'Lingkungan', type: 'select',
              value: 'Production', options: environments.map((e) => e.name) },
            { name: 'description', label: 'Keterangan' },
        ], 'Buat');

    if (!values) return;
    if (!values.name) { toast('Nama proses wajib diisi.', 'bad'); return; }

    await act(api('/api/processes', { method: 'POST', body: values }), 'Proses dibuat.');
};

ACTIONS.delProcess = async (data) => {
    if (!await confirmBox('Hapus proses', 'Hapus proses "' + data.name + '"? Riwayat pekerjaannya tetap tersimpan.', 'Hapus', true)) return;
    await act(api('/api/processes/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Proses dihapus.');
};

ACTIONS.delPackage = async (data) => {
    if (!await confirmBox('Hapus paket', 'Hapus ' + data.name + ' ' + data.version + '?', 'Hapus', true)) return;
    await act(api('/api/packages/' + encodeURIComponent(data.name) + '/' + encodeURIComponent(data.version),
        { method: 'DELETE' }), 'Paket dihapus.');
};

ACTIONS.scheduleProcess = (data) => newTriggerFor(data.name);
ACTIONS.newTrigger = () => newTriggerFor(null);

/**
 * Dialog pembuatan pemicu.
 *
 * Dua kolom, dengan bagian yang dipisahkan: kiri tentang APA yang dijalankan,
 * kanan tentang KAPAN. Bentuk yang sama dipakai orkestrator lain, dan alasannya
 * praktis — daftar isian yang panjang dalam satu lajur memaksa orang membaca
 * seluruhnya untuk tahu mana yang wajib.
 *
 * Frekuensi disimpan sebagai MENIT, satu angka, bukan sebagai pasangan
 * (satuan, jumlah). Penjadwal hanya perlu tahu berapa lama jaraknya; menyimpan
 * satuannya juga berarti ada dua sumber kebenaran yang bisa berbeda.
 */
const FREKUENSI = [
    { value: '1', label: 'Menit' },
    { value: '60', label: 'Jam' },
    { value: '1440', label: 'Hari' },
    { value: '10080', label: 'Minggu' },
];

/** Zona waktu yang lazim dipakai di Indonesia, plus UTC sebagai acuan. */
const ZONA = [
    { value: 'UTC', label: '(UTC) Waktu Universal Terkoordinasi' },
    { value: 'Asia/Jakarta', label: '(UTC+7) WIB — Jakarta' },
    { value: 'Asia/Makassar', label: '(UTC+8) WITA — Makassar' },
    { value: 'Asia/Jayapura', label: '(UTC+9) WIT — Jayapura' },
    { value: 'Asia/Singapore', label: '(UTC+8) Singapura' },
];

async function newTriggerFor(processName) {
    const processes = await api('/api/processes');

    if (!processes.length) {
        toast('Belum ada proses untuk dijadwalkan.', 'bad');
        return;
    }

    const robots = await api('/api/robots');

    const values = await form('Buat Pemicu Waktu',
        'Proses dijalankan sendiri tiap selang waktu yang ditentukan.', [

        { type: 'section', label: 'Apa yang dijalankan' },

        { name: 'name', label: 'Nama pemicu *', wide: true,
          value: processName ? processName + ' — berkala' : '' },

        { name: 'processName', label: 'Proses *', type: 'select',
          value: processName || '', options: processes.map((p) => p.name) },

        { name: 'robotName', label: 'Robot', type: 'select',
          options: [{ value: '', label: 'Robot mana saja yang siap' }].concat(robots.map((r) => r.name)) },

        { name: 'priority', label: 'Prioritas pekerjaan', type: 'select',
          value: 'Normal', options: ['Low', 'Normal', 'High'] },

        { name: 'runtimeType', label: 'Jenis runtime', type: 'select',
          value: 'Unattended', options: ['Unattended', 'Attended'] },

        { type: 'section', label: 'Kapan dijalankan' },

        { name: 'mode', label: 'Cara menjadwalkan', type: 'select', wide: true,
          value: 'interval', options: [
              { value: 'interval', label: 'Selang waktu — ulangi tiap sekian menit/jam/hari' },
              { value: 'cron', label: 'Cron — waktu tertentu, mis. tiap hari kerja pukul 07:00' },
          ] },

        { name: 'timezone', label: 'Zona waktu *', type: 'select',
          value: 'Asia/Jakarta', options: ZONA },

        { name: 'frequency', label: 'Frekuensi', type: 'select',
          value: '60', options: FREKUENSI },

        { name: 'every', label: 'Ulangi tiap', type: 'number', value: '1',
          hint: 'Dipakai kalau caranya "Selang waktu".' },

        { name: 'cron', label: 'Ekspresi cron', wide: true, value: '',
          placeholder: '0 7 * * 1-5',
          hint: 'Lima ruas: menit jam tanggal bulan hari. Contoh: "0 7 * * 1-5" ' +
                'tiap hari kerja pukul 07:00; "*/15 * * * *" tiap 15 menit; ' +
                '"0 0 1 * *" tanggal 1 tiap bulan. Dipakai kalau caranya "Cron".' },

        { name: 'enabled', label: 'Langsung aktifkan', type: 'checkbox', value: true, wide: true },

    ], 'Buat pemicu', { columns: 2 });

    if (!values) return;

    if (!values.name) { toast('Nama pemicu wajib diisi.', 'bad'); return; }

    const pakaiCron = values.mode === 'cron';

    if (pakaiCron && !values.cron.trim()) {
        toast('Ekspresi cron wajib diisi kalau caranya Cron.', 'bad');
        return;
    }

    // Hanya SATU cara yang dikirim. Mengirim keduanya membuat server harus
    // menebak yang mana yang dimaksud, dan tebakan itu tidak pernah benar
    // untuk semua orang.
    const isi = {
        name: values.name,
        processName: values.processName,
        robotName: values.robotName,
        priority: values.priority,
        runtimeType: values.runtimeType,
        timezone: values.timezone,
        enabled: values.enabled,
    };

    if (pakaiCron) {
        isi.cron = values.cron.trim();
        isi.type = 'Cron';
    } else {
        const menit = (Number(values.frequency) || 60) * (Number(values.every) || 1);
        if (menit < 1) { toast('Jarak pengulangan minimal 1 menit.', 'bad'); return; }
        isi.intervalMinutes = menit;
        isi.type = 'Time';
    }

    await act(api('/api/triggers', { method: 'POST', body: isi }), 'Pemicu dibuat.');
}

ACTIONS.toggleTrigger = async (data) => {
    await act(api('/api/triggers/' + encodeURIComponent(data.name) + '/toggle', { method: 'POST' }), 'Pemicu diubah.');
};

ACTIONS.delTrigger = async (data) => {
    if (!await confirmBox('Hapus pemicu', 'Hapus pemicu "' + data.name + '"?', 'Hapus', true)) return;
    await act(api('/api/triggers/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Pemicu dihapus.');
};

ACTIONS.newRobot = async () => {
    const environments = await api('/api/environments');
    const machines = await api('/api/machines');

    const values = await form('Daftarkan robot',
        'Biasanya tidak perlu: JakRunner mendaftarkan dirinya sendiri saat menyambung.', [
        { name: 'name', label: 'Nama robot' },
        { name: 'machineName', label: 'Mesin', type: 'select',
          options: [{ value: '', label: '(belum ditentukan)' }].concat(machines.map((m) => m.name)) },
        { name: 'type', label: 'Jenis', type: 'select', value: 'Unattended', options: ['Unattended', 'Attended'] },
        { name: 'environment', label: 'Lingkungan', type: 'select',
          value: 'Production', options: environments.map((e) => e.name) },
        { name: 'description', label: 'Keterangan' },
    ], 'Daftarkan');

    if (!values) return;
    if (!values.name) { toast('Nama robot wajib diisi.', 'bad'); return; }

    await act(api('/api/robots', { method: 'POST', body: values }), 'Robot didaftarkan.');
};

ACTIONS.delRobot = async (data) => {
    if (!await confirmBox('Hapus robot', 'Hapus robot "' + data.name + '"? Ia akan mendaftar lagi kalau JakRunner-nya masih berjalan.', 'Hapus', true)) return;
    await act(api('/api/robots/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Robot dihapus.');
};

ACTIONS.newMachine = async () => {
    const values = await form('Mesin baru', '', [
        { name: 'name', label: 'Nama mesin' },
        { name: 'type', label: 'Jenis', type: 'select', value: 'Standard', options: ['Standard', 'Template'] },
        { name: 'description', label: 'Keterangan' },
    ], 'Buat');

    if (!values) return;
    if (!values.name) { toast('Nama mesin wajib diisi.', 'bad'); return; }

    await act(api('/api/machines', { method: 'POST', body: values }), 'Mesin dibuat.');
};

ACTIONS.delMachine = async (data) => {
    if (!await confirmBox('Hapus mesin', 'Hapus mesin "' + data.name + '"?', 'Hapus', true)) return;
    await act(api('/api/machines/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Mesin dihapus.');
};

ACTIONS.newEnvironment = async () => {
    const values = await form('Lingkungan baru', '', [
        { name: 'name', label: 'Nama lingkungan' },
        { name: 'description', label: 'Keterangan' },
    ], 'Buat');

    if (!values) return;
    if (!values.name) { toast('Nama lingkungan wajib diisi.', 'bad'); return; }

    await act(api('/api/environments', { method: 'POST', body: values }), 'Lingkungan dibuat.');
};

ACTIONS.delEnvironment = async (data) => {
    if (!await confirmBox('Hapus lingkungan', 'Hapus lingkungan "' + data.name + '"?', 'Hapus', true)) return;
    await act(api('/api/environments/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Lingkungan dihapus.');
};

ACTIONS.newCredential = async () => {
    const values = await form('Kredensial baru',
        'Sandinya disimpan tersandi dan tidak pernah muncul lagi di daftar.', [
        { name: 'name', label: 'Nama' },
        { name: 'username', label: 'Nama pengguna' },
        { name: 'password', label: 'Kata sandi', type: 'password' },
        { name: 'description', label: 'Keterangan' },
    ], 'Simpan');

    if (!values) return;
    if (!values.name) { toast('Nama kredensial wajib diisi.', 'bad'); return; }

    await act(api('/api/credentials', { method: 'POST', body: values }), 'Kredensial disimpan.');
};

ACTIONS.delCredential = async (data) => {
    if (!await confirmBox('Hapus kredensial', 'Hapus "' + data.name + '"?', 'Hapus', true)) return;
    await act(api('/api/credentials/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Kredensial dihapus.');
};

ACTIONS.newQueue = async () => {
    const values = await form('Antrean baru', '', [
        { name: 'name', label: 'Nama antrean' },
        { name: 'description', label: 'Keterangan' },
        { name: 'maxRetries', label: 'Percobaan ulang maksimal', type: 'number', value: '3',
          hint: 'Butir yang gagal dikembalikan ke antrean sampai sebanyak ini, baru dinyatakan gagal permanen.' },
        { name: 'acceptDuplicates', label: 'Izinkan referensi kembar', type: 'checkbox', value: false },
    ], 'Buat');

    if (!values) return;
    if (!values.name) { toast('Nama antrean wajib diisi.', 'bad'); return; }

    values.maxRetries = Number(values.maxRetries) || 3;

    await act(api('/api/queues', { method: 'POST', body: values }), 'Antrean dibuat.');
};

ACTIONS.delQueue = async (data) => {
    if (!await confirmBox('Hapus antrean',
        'Hapus antrean "' + data.name + '" beserta seluruh butir di dalamnya?', 'Hapus', true)) return;

    if (PAGES.queues.open === data.name) PAGES.queues.open = null;

    await act(api('/api/queues/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Antrean dihapus.');
};

ACTIONS.queueItems = (data) => { PAGES.queues.open = data.name; render(); };
ACTIONS.closeQueue = () => { PAGES.queues.open = null; render(); };

ACTIONS.addItem = async (data) => {
    const values = await form('Tambah butir ke ' + data.name, '', [
        { name: 'reference', label: 'Referensi', hint: 'Penanda unik, misalnya nomor faktur.' },
        { name: 'priority', label: 'Prioritas', type: 'select', value: 'Normal', options: ['High', 'Normal', 'Low'] },
        { name: 'content', label: 'Isi (JSON)', type: 'textarea', placeholder: '{"amount": 250000}' },
    ], 'Tambah');

    if (!values) return;

    await act(api('/api/queues/' + encodeURIComponent(data.name) + '/items',
        { method: 'POST', body: values }), 'Butir ditambahkan.');
};

ACTIONS.delItem = async (data) => {
    await act(api('/api/queues/items/' + encodeURIComponent(data.id), { method: 'DELETE' }), 'Butir dihapus.');
};

ACTIONS.newAsset = () => editAsset(null, 'Text');
ACTIONS.editAsset = (data) => editAsset(data.name, data.type);

async function editAsset(name, type) {
    const values = await form(name ? 'Ubah aset ' + name : 'Aset baru',
        'Aset bertipe Credential dan Secret disimpan tersandi.', [
        { name: 'name', label: 'Nama', value: name || '' },
        { name: 'type', label: 'Tipe', type: 'select', value: type || 'Text',
          options: ['Text', 'Integer', 'Bool', 'Credential', 'Secret'] },
        { name: 'value', label: 'Nilai' },
        { name: 'description', label: 'Keterangan' },
    ], 'Simpan');

    if (!values) return;
    if (!values.name) { toast('Nama aset wajib diisi.', 'bad'); return; }

    await act(api('/api/assets', { method: 'POST', body: values }), 'Aset disimpan.');
}

ACTIONS.delAsset = async (data) => {
    if (!await confirmBox('Hapus aset', 'Hapus aset "' + data.name + '"?', 'Hapus', true)) return;
    await act(api('/api/assets/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Aset dihapus.');
};

ACTIONS.newBucket = async () => {
    const values = await form('Gudang baru', '', [
        { name: 'name', label: 'Nama gudang' },
        { name: 'description', label: 'Keterangan' },
    ], 'Buat');

    if (!values) return;
    if (!values.name) { toast('Nama gudang wajib diisi.', 'bad'); return; }

    await act(api('/api/buckets', { method: 'POST', body: values }), 'Gudang dibuat.');
};

ACTIONS.delBucket = async (data) => {
    if (!await confirmBox('Hapus gudang', 'Hapus gudang "' + data.name + '" beserta isinya?', 'Hapus', true)) return;

    if (PAGES.buckets.open === data.name) PAGES.buckets.open = null;

    await act(api('/api/buckets/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Gudang dihapus.');
};

ACTIONS.bucketFiles = (data) => { PAGES.buckets.open = data.name; render(); };
ACTIONS.closeBucket = () => { PAGES.buckets.open = null; render(); };

ACTIONS.delFile = async (data) => {
    await act(api('/api/buckets/' + encodeURIComponent(data.bucket) + '/files/' + encodeURIComponent(data.id),
        { method: 'DELETE' }), 'Berkas dihapus.');
};

/**
 * Unggah berkas.
 *
 * Berkas dibaca jadi base64 di peramban lalu dikirim sebagai JSON — jalan yang
 * sama dengan penerbitan paket dari Studio, sehingga sisi server hanya punya
 * satu cara menerima berkas, bukan dua.
 */
ACTIONS.uploadFile = (data) => {
    const picker = document.createElement('input');
    picker.type = 'file';

    picker.addEventListener('change', () => {
        const file = picker.files[0];
        if (!file) return;

        if (file.size > 32 * 1024 * 1024) {
            toast('Berkas lebih dari 32 MB tidak diterima.', 'bad');
            return;
        }

        const reader = new FileReader();

        reader.onload = async () => {
            const base64 = String(reader.result).split(',')[1];

            await act(api('/api/buckets/' + encodeURIComponent(data.name) + '/files', {
                method: 'POST',
                body: { fileName: file.name, contentType: file.type, contentBase64: base64 },
            }), 'Berkas diunggah.');
        };

        reader.onerror = () => toast('Berkas gagal dibaca.', 'bad');
        reader.readAsDataURL(file);
    });

    picker.click();
};

ACTIONS.newUser = () => editUser(null, {});
ACTIONS.editUser = (data) => editUser(data.name, data);

async function editUser(username, current) {
    const roles = await api('/api/roles');

    const values = await form(username ? 'Ubah pengguna ' + username : 'Pengguna baru',
        username ? 'Kosongkan kata sandi kalau tidak ingin menggantinya.' : '', [
        { name: 'username', label: 'Nama pengguna', value: username || '' },
        { name: 'displayName', label: 'Nama tampilan', value: current.display || '' },
        { name: 'email', label: 'Surel', value: current.email || '' },
        { name: 'role', label: 'Peran', type: 'select', value: current.role || 'Automation User',
          options: roles.map((r) => r.name) },
        { name: 'password', label: 'Kata sandi', type: 'password' },
        { name: 'isActive', label: 'Aktif', type: 'checkbox', value: true },
    ], 'Simpan');

    if (!values) return;
    if (!values.username) { toast('Nama pengguna wajib diisi.', 'bad'); return; }

    await act(api('/api/users', { method: 'POST', body: values }), 'Pengguna disimpan.');
}

ACTIONS.delUser = async (data) => {
    if (!await confirmBox('Hapus pengguna', 'Hapus pengguna "' + data.name + '"?', 'Hapus', true)) return;
    await act(api('/api/users/' + encodeURIComponent(data.name), { method: 'DELETE' }), 'Pengguna dihapus.');
};

ACTIONS.changePassword = async () => {
    const values = await form('Ganti kata sandi', 'Berlaku untuk akun ' + S.user.username + '.', [
        { name: 'currentPassword', label: 'Kata sandi sekarang', type: 'password' },
        { name: 'newPassword', label: 'Kata sandi baru', type: 'password', hint: 'Minimal 6 karakter.' },
    ], 'Ganti');

    if (!values) return;

    await act(api('/api/auth/password', { method: 'POST', body: values }), 'Kata sandi diganti.');
};

ACTIONS.readAlert = async (data) => {
    await act(api('/api/alerts/' + encodeURIComponent(data.id) + '/read', { method: 'POST' }), null);
};

ACTIONS.readAll = async () => {
    await act(api('/api/alerts/read-all', { method: 'POST' }), 'Semua peringatan ditandai terbaca.');
};

/**
 * Nama proses yang sedang dibuka.
 *
 * DISIMPAN sebagai "selected", bukan "name". PAGES.process adalah sebuah
 * fungsi, dan setiap fungsi sudah punya properti bawaan "name" yang HANYA
 * BACA — menugaskannya gagal tanpa suara sedikit pun, dan halamannya lalu
 * terbuka kosong tanpa satu pun pesan galat.
 */
ACTIONS.openProcess = (data) => {
    PAGES.process.selected = data.name;
    go('process');
};

ACTIONS.backToProcesses = () => go('processes');

/**
 * Pantau log satu pekerjaan.
 *
 * Bukan jendela terpisah: halaman Log yang sama dipakai, hanya dengan
 * penyaringnya diisi. Satu tempat untuk membaca log berarti satu tempat yang
 * harus benar — dan tombol "Tampilkan semua log" mengembalikannya.
 */
ACTIONS.watchJob = (data) => {
    PAGES.logs.jobId = data.id;
    PAGES.logs.jobLabel = data.name;
    go('logs');
};

ACTIONS.watchAll = () => {
    PAGES.logs.jobId = null;
    PAGES.logs.jobLabel = null;
    render();
};

ACTIONS.clearLogs = async () => {
    if (!await confirmBox('Kosongkan log',
        'Hapus SELURUH baris log di penyewa ini? Tindakan ini tidak bisa dibatalkan.', 'Kosongkan', true)) return;

    await act(api('/api/logs', { method: 'DELETE' }), 'Log dikosongkan.');
};

$('btnAlerts').addEventListener('click', () => go('alerts'));
$('btnLogs').addEventListener('click', () => go('logs'));

// Pemilih bahasa di bilah atas DAN di layar masuk.
//
// Yang di layar masuk bukan hiasan: orang yang tidak membaca bahasa Indonesia
// harus bisa mengganti bahasanya SEBELUM masuk, bukan sesudah. Bilah atas baru
// terlihat setelah berhasil masuk.
(function () {
    const pemilih = ['langPick', 'langPickLogin']
        .map(function (id) { return document.getElementById(id); })
        .filter(function (el) { return el; });

    if (pemilih.length === 0) return;

    function gambar() {
        pemilih.forEach(function (el) {
            el.innerHTML = BAHASA.map(function (b) {
                return '<option value="' + b.id + '"' + (b.id === LANG ? ' selected' : '') +
                       '>' + esc(b.label) + '</option>';
            }).join('');
        });
    }

    gambar();

    pemilih.forEach(function (el) {
        el.addEventListener('change', function () {
            setLang(el.value);

            // Keduanya disamakan: mengganti bahasa di satu tempat tidak boleh
            // meninggalkan yang lain menunjuk bahasa yang lama.
            gambar();
            terjemahkanLayarMasuk();
        });
    });

    terjemahkanLayarMasuk();
})();

/**
 * Terjemahkan teks yang tertulis langsung di HTML layar masuk.
 *
 * Bagian ini tidak digambar ulang oleh render(), jadi ia harus disentuh
 * sendiri — kalau tidak, tombol Masuk tetap berbahasa Indonesia betapapun
 * bahasanya diganti.
 */
function terjemahkanLayarMasuk() {
    const bagian = [
        ['loginBtn', 'Masuk'],
    ];

    bagian.forEach(function (p) {
        const el = document.getElementById(p[0]);
        if (el) el.textContent = t(p[1]);
    });
}
$('whoBox').addEventListener('click', () => go('settings'));

// =====================================================================
// Pencarian menyeluruh
// =====================================================================

let searchTimer = null;

function hideSearch() { $('searchResults').classList.remove('open'); }

$('globalSearch').addEventListener('input', (event) => {
    clearTimeout(searchTimer);
    const query = event.target.value.trim();

    if (query.length < 2) { hideSearch(); return; }

    // Ditunda 250 ms: mengetik "TransactionQueue" tanpa jeda ini akan
    // mengirim enam belas permintaan pencarian, lima belas di antaranya
    // hasilnya sudah tidak diinginkan lagi saat tiba.
    searchTimer = setTimeout(async () => {
        try {
            const rows = await api('/api/search?q=' + encodeURIComponent(query));
            const box = $('searchResults');

            box.innerHTML = rows.length
                ? rows.map((r) => `<div class="row" data-page="${esc(r.page)}">
                       <span class="tag gray">${esc(r.kind)}</span>
                       <b>${esc(r.label)}</b>
                       <span class="detail">${esc(r.detail)}</span>
                   </div>`).join('')
                : '<div class="row"><span style="color:var(--muted)">Tidak ada yang cocok.</span></div>';

            box.classList.add('open');

            box.querySelectorAll('.row[data-page]').forEach((el) =>
                el.addEventListener('click', () => {
                    hideSearch();
                    $('globalSearch').value = '';
                    go(el.dataset.page);
                }));
        } catch (ex) {
            hideSearch();
        }
    }, 250);
});

document.addEventListener('click', (event) => {
    if (!event.target.closest('.search')) hideSearch();
});

// =====================================================================
// Penyegaran berkala
// =====================================================================

/**
 * Halaman yang isinya berubah sendiri disegarkan; sisanya tidak.
 *
 * Menggambar ulang halaman formulir tiap lima detik akan menghapus apa yang
 * sedang diketik orang, jadi daftar ini sengaja pendek.
 */
const LIVE_PAGES = ['dashboard', 'jobs', 'robots', 'queues', 'alerts'];

function startTimer() {
    if (S.timer) clearInterval(S.timer);

    S.timer = setInterval(async () => {
        if (document.hidden) return;
        if ($('veil').classList.contains('open')) return;

        try {
            if (S.page === 'logs' || S.page === 'process') await pollLogs();
            else if (LIVE_PAGES.includes(S.page)) await render();
            else await pollBadge();
        } catch (ex) {
            // Server yang sedang mati tidak boleh membanjiri layar dengan
            // pesan kesalahan tiap lima detik; penandanya cukup di menu samping.
            setConnected(false);
        }
    }, 5000);
}

/** Hanya menambahkan baris BARU, supaya guliran tidak melompat. */
async function pollLogs() {
    const rows = await api('/api/logs?limit=200&afterId=' + S.logAfterId + logFilterQuery());

    setConnected(true);

    if (!rows.length) return;

    const stream = $('logStream');
    if (!stream) return;

    // Halaman masih memuat pesan "belum ada log": ganti dengan gambar penuh.
    if (S.logLines.length === 0) {
        S.logLines = rows;
        S.logAfterId = rows[rows.length - 1].id;
        stream.innerHTML = renderLogGroups(rows);
        return;
    }

    S.logLines = S.logLines.concat(rows);
    S.logAfterId = rows[rows.length - 1].id;

    // Baris baru dimasukkan ke JALANNYA masing-masing.
    //
    // Kelompok yang sudah ada cukup ditambahi barisnya — menggambar ulang
    // seluruh daftar akan menutup kembali jalan yang sedang dibaca orang dan
    // melompatkan gulirannya. Jalan yang BARU muncul disisipkan di atas,
    // karena itulah yang paling mungkin sedang ditunggu.
    for (const g of groupLogs(rows)) {
        const domId = logGroupDomId(g.key);
        const wadah = document.getElementById(domId + '_lines');

        if (wadah) {
            const diBawah = wadah.scrollHeight - wadah.scrollTop - wadah.clientHeight < 40;

            wadah.insertAdjacentHTML('beforeend', renderLogLines(g.baris));

            // Ringkasan di kepala ikut diperbarui, kalau tidak jumlah barisnya
            // membeku di angka saat halaman dibuka.
            const meta = document.getElementById(domId + '_meta');
            if (meta) meta.textContent = logGroupSummary(kelompokPenuh(g.key));

            if (diBawah) wadah.scrollTop = wadah.scrollHeight;
        } else {
            stream.insertAdjacentHTML('afterbegin', renderLogGroup(g, true));
        }
    }
}

/**
 * Kelompok LENGKAP dari seluruh baris yang sudah diketahui halaman ini.
 *
 * Ringkasan di kepala harus menghitung semua baris jalan itu, bukan hanya
 * yang baru tiba — kalau tidak, angkanya menyusut tiap kali polling.
 */
function kelompokPenuh(key) {
    const semua = S.logLines.filter((l) => logGroupKey(l) === key);
    return groupLogs(semua)[0] || { baris: [], galat: 0, peringatan: 0 };
}

async function pollBadge() {
    const rows = await api('/api/alerts?unread=1&limit=99');

    setConnected(true);
    setCount('alerts', rows.length);

    $('alertBadge').hidden = !rows.length;
    $('alertBadge').textContent = rows.length;
}

function setConnected(ok) {
    $('connDot').className = 'dot ' + (ok ? 'on' : 'off');
    $('connText').textContent = ok ? 'Tersambung ke server' : 'Server tidak menjawab';
}

// =====================================================================
// Mulai
// =====================================================================

async function start() {
    $('login').style.display = 'none';
    $('app').classList.add('ready');

    if (!S.user) S.user = await api('/api/auth/me');

    $('tenantName').textContent = S.user.tenant || '—';
    $('whoName').textContent = S.user.username;
    $('whoRole').textContent = S.user.role;
    $('whoInitials').textContent = (S.user.username || 'FH').substring(0, 2).toUpperCase();

    buildNav();
    setConnected(true);

    try {
        const health = await api('/api/health');
        $('serverInfo').textContent = 'ForgeHub · ' + location.host;
    } catch (ex) { /* penandanya sudah diurus setConnected */ }

    const initial = location.hash.replace('#', '');
    go(PAGES[initial] ? initial : 'dashboard');

    startTimer();
}

// Sesi yang masih hidup dilanjutkan tanpa meminta masuk lagi.
(async function boot() {
    let saved = null;
    try { saved = sessionStorage.getItem(KEY); } catch (e) { /* mode privat */ }

    if (!saved) return;

    S.token = saved;

    try {
        S.user = await api('/api/auth/me');
        await start();
    } catch (ex) {
        S.token = null;
        try { sessionStorage.removeItem(KEY); } catch (e) { /* abaikan */ }
    }
})();
