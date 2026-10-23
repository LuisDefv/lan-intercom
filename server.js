#!/usr/bin/env node
'use strict';

/**
 * Intercom LAN — demonio que corre en cada PC.
 * - Descubre otras PCs por broadcast UDP en la misma red.
 * - Recibe mensajes y archivos de otras PCs (/ping, /inbox/*).
 * - Sirve la interfaz web y la API local (solo desde esta PC).
 */

const http = require('node:http');
const dgram = require('node:dgram');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { pipeline } = require('node:stream/promises');

const APP = 'lan-intercom';
const PORT = Number(process.env.INTERCOM_PORT || 7777);
const DISCOVERY_PORT = Number(process.env.INTERCOM_DISCOVERY_PORT || 7778);
const NAME = (process.env.INTERCOM_NAME || os.hostname()).slice(0, 60);
const computeGrupo = (k) => crypto.createHash('sha256').update(String(k || '')).digest('hex').slice(0, 12);
// Clave/grupo: mutable en caliente (se puede cambiar desde la interfaz) y
// persistido en disco, pisando al valor de entorno si existe un grupo guardado.
let KEY = process.env.INTERCOM_KEY || '';
let GRUPO = computeGrupo(KEY);
const DIR = path.resolve(process.env.INTERCOM_DIR || path.join(os.homedir(), 'Intercom'));
const FILES_DIR = path.join(DIR, 'recibidos');
const TMP_DIR = path.join(DIR, '.tmp');
const PENDING_DIR = path.join(DIR, '.pendientes');
const HISTORY_FILE = path.join(DIR, 'mensajes.json');
const ALIAS_FILE = path.join(DIR, 'alias.json');
const EXEC_FILE = path.join(DIR, 'exec.json');
const GROUP_FILE = path.join(DIR, 'grupo.json');
const PEER_TTL_MS = 12_000;
const ANNOUNCE_MS = 3_000;
const MAX_MESSAGES = 1000;

// Límite de archivos entrantes por PC: como máximo RATE_LIMIT_MAX dentro de una
// ventana deslizante de RATE_LIMIT_WINDOW_MS. Evita que una PC inunde a otra.
const RATE_LIMIT_MAX = Number(process.env.INTERCOM_FILE_LIMIT || 10);
const RATE_LIMIT_WINDOW_MS = Number(process.env.INTERCOM_FILE_WINDOW_MS || 10 * 60 * 1000);

// Los archivos entrantes NO se guardan directo: quedan a la espera de que el
// dueño de esta PC los acepte o rechace desde la interfaz.
const PENDING_TTL_MS = Number(process.env.INTERCOM_PENDING_TTL_MS || 60 * 60 * 1000);

// Ejecución remota de comandos: estado inicial del permiso. Arranca en el valor
// de INTERCOM_ALLOW_EXEC (apagado si no está), pero el dueño de esta PC lo
// prende/apaga desde la interfaz. Además SIEMPRE exige la clave compartida.
const EXEC_DEFAULT = /^(1|true|si|sí|yes|on)$/i.test(process.env.INTERCOM_ALLOW_EXEC || '');
const EXEC_TIMEOUT_MS = Number(process.env.INTERCOM_EXEC_TIMEOUT || 120_000);
const EXEC_MAX_OUTPUT = Number(process.env.INTERCOM_EXEC_MAX_OUTPUT || 1_000_000);

for (const d of [DIR, FILES_DIR, TMP_DIR, PENDING_DIR]) {
    fs.mkdirSync(d, { recursive: true });
}

const ID = loadOrCreateId();

function loadOrCreateId() {
    const file = path.join(DIR, 'id.txt');
    try {
        const id = fs.readFileSync(file, 'utf8').trim();
        if (id) {
            return id;
        }
    } catch {}
    const id = crypto.randomUUID();
    fs.writeFileSync(file, id);
    return id;
}

// ---------------------------------------------------------------- alias por PC

/** Mapa { peerId: alias } para ponerle nombre propio a cada PC. */
let aliases = loadJsonFile(ALIAS_FILE, {});

function loadJsonFile(file, fallback) {
    try {
        const data = JSON.parse(fs.readFileSync(file, 'utf8'));
        return data && typeof data === 'object' ? data : fallback;
    } catch {
        return fallback;
    }
}

function saveJsonFile(file, data) {
    try {
        fs.writeFileSync(file, JSON.stringify(data));
    } catch (err) {
        console.error('[persistencia]', err.message);
    }
}

function setAlias(id, alias) {
    id = String(id || '').trim();
    alias = String(alias || '').trim().slice(0, 60);
    if (!id) {
        throw httpError(400, 'Falta el id de la PC.');
    }
    if (alias) {
        aliases[id] = alias;
    } else {
        delete aliases[id];
    }
    saveJsonFile(ALIAS_FILE, aliases);
    emit('peers', listPeers());
    return alias;
}

// ------------------------------------------- permiso de ejecución remota (exec)

/** { enabled: bool, peers: { peerId: bool } } — override por PC sobre el global. */
let execState = normalizeExecState(loadJsonFile(EXEC_FILE, { enabled: EXEC_DEFAULT, peers: {} }));

function normalizeExecState(s) {
    return {
        enabled: Boolean(s && s.enabled),
        peers: s && s.peers && typeof s.peers === 'object' ? s.peers : {},
    };
}

function execAllowedFor(peerId) {
    if (peerId && Object.prototype.hasOwnProperty.call(execState.peers, peerId)) {
        return Boolean(execState.peers[peerId]);
    }
    return execState.enabled;
}

function setExecConfig({ enabled, peerId, allow }) {
    if (enabled !== undefined) {
        execState.enabled = Boolean(enabled);
    }
    if (peerId) {
        if (allow === null) {
            delete execState.peers[peerId];
        } else {
            execState.peers[peerId] = Boolean(allow);
        }
    }
    saveJsonFile(EXEC_FILE, execState);
    emit('exec-config', execState);
    return execState;
}

// ------------------------------------------------------- grupo (clave) mutable

// Si hay un grupo guardado en disco, pisa al de entorno.
(function loadGroup() {
    const saved = loadJsonFile(GROUP_FILE, null);
    if (saved && typeof saved.key === 'string') {
        KEY = saved.key;
        GRUPO = computeGrupo(KEY);
    }
})();

/** Cambia el grupo (clave). Limpia peers del grupo anterior y re-anuncia. */
function setGroup(key) {
    KEY = String(key || '');
    GRUPO = computeGrupo(KEY);
    saveJsonFile(GROUP_FILE, { key: KEY });
    peers.clear();
    emit('peers', listPeers());
    emit('group', { grupo: GRUPO, conClave: Boolean(KEY) });
    try {
        announce();
    } catch {}
    return { grupo: GRUPO, conClave: Boolean(KEY) };
}

// ---------------------------------------------------------------- utilidades

function httpError(status, message) {
    const err = new Error(message);
    err.status = status;
    return err;
}

function sendJson(res, status, data) {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(data));
}

function remoteIp(req) {
    const a = req.socket.remoteAddress || '';
    return a.startsWith('::ffff:') ? a.slice(7) : a;
}

function isLocal(req) {
    return ['127.0.0.1', '::1'].includes(remoteIp(req));
}

function keyOk(req) {
    if (!KEY) {
        return true;
    }
    const a = crypto.createHash('sha256').update(String(req.headers['x-intercom-key'] || '')).digest();
    const b = crypto.createHash('sha256').update(KEY).digest();
    return crypto.timingSafeEqual(a, b);
}

function readJson(req, limit = 1_000_000) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        req.on('data', (c) => {
            size += c.length;
            if (size > limit) {
                reject(httpError(413, 'cuerpo demasiado grande'));
                req.destroy();
                return;
            }
            chunks.push(c);
        });
        req.on('end', () => {
            try {
                resolve(size ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {});
            } catch {
                reject(httpError(400, 'JSON inválido'));
            }
        });
        req.on('error', reject);
    });
}

/** Nombre de archivo seguro para Windows/Linux/macOS. */
function safeName(name) {
    let base = String(name || '')
        .split(/[\\/]/)
        .pop()
        .replace(/[<>:"|?*\x00-\x1f]/g, '_')
        .replace(/^[.\s]+/, '')
        .trim()
        .slice(0, 180);
    if (/^(con|prn|aux|nul|com\d|lpt\d)(\.|$)/i.test(base)) {
        base = '_' + base;
    }
    return base || 'archivo';
}

function uniquePath(dir, name) {
    const ext = path.extname(name);
    const stem = name.slice(0, name.length - ext.length);
    for (let i = 0; ; i++) {
        const candidate = path.join(dir, i ? `${stem} (${i})${ext}` : name);
        if (!fs.existsSync(candidate)) {
            return candidate;
        }
    }
}

function localIPv4s() {
    const out = [];
    for (const [iface, addrs] of Object.entries(os.networkInterfaces())) {
        for (const a of addrs || []) {
            if (a.family === 'IPv4' && !a.internal) {
                out.push({ iface, address: a.address, netmask: a.netmask });
            }
        }
    }
    return out;
}

function ipToInt(ip) {
    return ip.split('.').reduce((acc, o) => ((acc << 8) | Number(o)) >>> 0, 0);
}

function intToIp(n) {
    return [24, 16, 8, 0].map((s) => (n >>> s) & 255).join('.');
}

function broadcastAddresses() {
    const set = new Set(['255.255.255.255']);
    for (const { address, netmask } of localIPv4s()) {
        const mask = ipToInt(netmask);
        set.add(intToIp(((ipToInt(address) & mask) | ~mask) >>> 0));
    }
    return [...set];
}

function openTarget(target) {
    const cmd = process.platform === 'win32' ? 'explorer' : process.platform === 'darwin' ? 'open' : 'xdg-open';
    try {
        spawn(cmd, [target], { detached: true, stdio: 'ignore' }).unref();
    } catch {}
}

// ---------------------------------------------------------------- eventos (SSE)

const sseClients = new Set();

function emit(type, data) {
    const payload = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const res of sseClients) {
        res.write(payload);
    }
}

setInterval(() => {
    for (const res of sseClients) {
        res.write(': ping\n\n');
    }
}, 25_000).unref();

// ---------------------------------------------------------------- PCs (peers)

const peers = new Map();
const manualHosts = new Map();

function upsertPeer({ id, name, host, port }) {
    if (!id || id === ID || !host || !port) {
        return;
    }
    const prev = peers.get(id);
    const peer = { id, name: String(name || host).slice(0, 60), host, port: Number(port), lastSeen: Date.now() };
    peers.set(id, peer);
    if (!prev || prev.name !== peer.name || prev.host !== peer.host || prev.port !== peer.port) {
        emit('peers', listPeers());
    }
}

function displayName(peer) {
    return aliases[peer.id] || peer.name;
}

function listPeers() {
    return [...peers.values()]
        .map((p) => ({ id: p.id, name: displayName(p), hostname: p.name, alias: aliases[p.id] || '', host: p.host, port: p.port, execAllowed: execAllowedFor(p.id) }))
        .sort((a, b) => a.name.localeCompare(b.name));
}

setInterval(() => {
    const now = Date.now();
    let changed = false;
    for (const [id, p] of peers) {
        if (now - p.lastSeen > PEER_TTL_MS) {
            peers.delete(id);
            changed = true;
        }
    }
    if (changed) {
        emit('peers', listPeers());
    }
}, 2000).unref();

function isAll(to) {
    return !to || ['todos', 'todas', 'all', '*'].includes(String(to).toLowerCase());
}

function resolveTargets(to) {
    const list = [...peers.values()];
    if (isAll(to)) {
        if (!list.length) {
            throw httpError(404, 'No hay otras PCs conectadas en la red.');
        }
        return list;
    }
    const t = String(to).toLowerCase();
    const matches = (p) => [p.name, aliases[p.id] || ''].filter(Boolean).map((s) => s.toLowerCase());
    const partial = list.filter((p) => matches(p).some((s) => s.includes(t)));
    const peer =
        list.find((p) => p.id === to) ||
        list.find((p) => matches(p).includes(t)) ||
        list.find((p) => p.host === to) ||
        (partial.length === 1 ? partial[0] : undefined);
    if (!peer) {
        const names = list.map((p) => displayName(p)).join(', ') || 'ninguna';
        throw httpError(404, `No encuentro la PC "${to}". Conectadas: ${names}.`);
    }
    return [peer];
}

/** Request HTTP a otra PC. `file` = { path, size } para mandar un archivo en streaming. */
function peerRequest(peer, method, pathname, { json, file } = {}) {
    return new Promise((resolve, reject) => {
        const headers = {};
        if (KEY) {
            headers['x-intercom-key'] = KEY;
        }
        let payload;
        if (json !== undefined) {
            payload = Buffer.from(JSON.stringify(json));
            headers['content-type'] = 'application/json';
            headers['content-length'] = payload.length;
        }
        if (file) {
            headers['content-type'] = 'application/octet-stream';
            headers['content-length'] = file.size;
        }
        const req = http.request(
            { host: peer.host, port: peer.port, method, path: pathname, headers, timeout: file ? 600_000 : 8_000 },
            (res) => {
                const chunks = [];
                res.on('data', (c) => chunks.push(c));
                res.on('end', () => {
                    const text = Buffer.concat(chunks).toString('utf8');
                    let data;
                    try {
                        data = JSON.parse(text);
                    } catch {
                        data = text;
                    }
                    if (res.statusCode >= 400) {
                        reject(new Error((data && data.error) || `HTTP ${res.statusCode}`));
                    } else {
                        resolve(data);
                    }
                });
            },
        );
        req.on('timeout', () => req.destroy(new Error('sin respuesta (timeout)')));
        req.on('error', reject);
        if (file) {
            const rs = fs.createReadStream(file.path);
            rs.on('error', (e) => req.destroy(e));
            rs.pipe(req);
        } else {
            req.end(payload);
        }
    });
}

async function pingHost(host, port) {
    const info = await peerRequest({ host, port }, 'GET', '/ping');
    if (!info || info.app !== APP) {
        throw new Error('ahí no hay un Intercom');
    }
    if (info.grupo !== GRUPO) {
        throw new Error('esa PC usa otra clave (INTERCOM_KEY)');
    }
    upsertPeer({ id: info.id, name: info.name, host, port: info.port || port });
    return info;
}

// ---------------------------------------------------------------- descubrimiento UDP

const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udp.on('message', (buf, rinfo) => {
    try {
        const m = JSON.parse(buf.toString('utf8'));
        if (m.app !== APP || m.id === ID || m.grupo !== GRUPO) {
            return;
        }
        upsertPeer({ id: m.id, name: m.name, host: rinfo.address, port: m.port });
    } catch {}
});

udp.on('error', (err) => console.error('[udp]', err.message));

function announce() {
    const msg = Buffer.from(JSON.stringify({ app: APP, id: ID, name: NAME, port: PORT, grupo: GRUPO }));
    for (const addr of broadcastAddresses()) {
        udp.send(msg, DISCOVERY_PORT, addr, () => {});
    }
    for (const { host, port } of manualHosts.values()) {
        pingHost(host, port).catch(() => {});
    }
}

// ---------------------------------------------------------------- mensajes

let messages = loadHistory();
let saveTimer = null;

function loadHistory() {
    try {
        const data = JSON.parse(fs.readFileSync(HISTORY_FILE, 'utf8'));
        return Array.isArray(data) ? data.slice(-MAX_MESSAGES) : [];
    } catch {
        return [];
    }
}

function saveHistory() {
    clearTimeout(saveTimer);
    saveTimer = null;
    try {
        fs.writeFileSync(HISTORY_FILE, JSON.stringify(messages));
    } catch (err) {
        console.error('[historial]', err.message);
    }
}

function addMessage(m) {
    const msg = { id: crypto.randomUUID(), at: new Date().toISOString(), ...m };
    messages.push(msg);
    if (messages.length > MAX_MESSAGES) {
        messages = messages.slice(-MAX_MESSAGES);
    }
    if (!saveTimer) {
        saveTimer = setTimeout(saveHistory, 1000);
    }
    emit('message', msg);
    return msg;
}

function filterMessages(pc, limit) {
    let list = messages;
    if (pc && !isAll(pc)) {
        const t = String(pc).toLowerCase();
        list = list.filter((m) => m.peerId === pc || String(m.peerName || '').toLowerCase().includes(t));
    }
    return list.slice(-Math.max(1, Math.min(Number(limit) || 50, MAX_MESSAGES)));
}

function listFiles() {
    return fs
        .readdirSync(FILES_DIR, { withFileTypes: true })
        .filter((d) => d.isFile())
        .map((d) => {
            const st = fs.statSync(path.join(FILES_DIR, d.name));
            return { name: d.name, size: st.size, at: st.mtime.toISOString() };
        })
        .sort((a, b) => b.at.localeCompare(a.at));
}

// --------------------------------------------- rate limit de archivos entrantes

const fileHits = new Map(); // peerId -> [timestamps]

/** Devuelve false si esa PC ya superó el límite de archivos en la ventana. */
function allowIncomingFile(peerId) {
    const key = peerId || 'desconocido';
    const now = Date.now();
    const hits = (fileHits.get(key) || []).filter((t) => now - t < RATE_LIMIT_WINDOW_MS);
    if (hits.length >= RATE_LIMIT_MAX) {
        fileHits.set(key, hits);
        return false;
    }
    hits.push(now);
    fileHits.set(key, hits);
    return true;
}

// --------------------------------------- archivos a la espera de confirmación

const pending = new Map(); // pendingId -> { id, fromId, fromName, host, fileName, size, tmpPath, at }

function listPending() {
    return [...pending.values()]
        .sort((a, b) => a.at.localeCompare(b.at))
        .map(({ id, fromId, fromName, fileName, size, at }) => ({ id, fromId, fromName, fileName, size, at }));
}

function addPending(entry) {
    pending.set(entry.id, entry);
    emit('pending', listPending());
}

function acceptPending(id) {
    const entry = pending.get(id);
    if (!entry) {
        throw httpError(404, 'Ese archivo ya no está en espera.');
    }
    const finalPath = uniquePath(FILES_DIR, safeName(entry.fileName));
    fs.renameSync(entry.tmpPath, finalPath);
    pending.delete(id);
    const fileName = path.basename(finalPath);
    addMessage({ direction: 'in', kind: 'file', peerId: entry.fromId, peerName: entry.fromName, fileName, size: fs.statSync(finalPath).size });
    emit('files', listFiles());
    emit('pending', listPending());
    return fileName;
}

function rejectPending(id) {
    const entry = pending.get(id);
    if (!entry) {
        throw httpError(404, 'Ese archivo ya no está en espera.');
    }
    fs.rmSync(entry.tmpPath, { force: true });
    pending.delete(id);
    addMessage({ direction: 'in', kind: 'file-rechazado', peerId: entry.fromId, peerName: entry.fromName, fileName: entry.fileName, size: entry.size });
    emit('pending', listPending());
}

// Limpia pendientes vencidos (archivo que nunca se aceptó ni rechazó).
setInterval(() => {
    const now = Date.now();
    for (const [id, e] of pending) {
        if (now - new Date(e.at).getTime() > PENDING_TTL_MS) {
            fs.rmSync(e.tmpPath, { force: true });
            pending.delete(id);
            emit('pending', listPending());
        }
    }
}, 30_000).unref();

// ------------------------------------------------------- ejecución de comandos

/** Elige el shell según el sistema operativo y el pedido. */
function shellFor(shell) {
    if (shell === 'powershell' || shell === 'pwsh') {
        return { cmd: process.platform === 'win32' ? 'powershell.exe' : 'pwsh', pre: ['-NoProfile', '-NonInteractive', '-Command'] };
    }
    if (shell === 'bash') {
        return { cmd: 'bash', pre: ['-c'] };
    }
    if (process.platform === 'win32') {
        return { cmd: process.env.ComSpec || 'cmd.exe', pre: ['/d', '/s', '/c'] };
    }
    return { cmd: '/bin/sh', pre: ['-c'] };
}

/** Corre un comando y captura salida acotada. Nunca rechaza: siempre resuelve. */
function runCommand({ cmd, shell, cwd, timeout }) {
    return new Promise((resolve) => {
        const sh = shellFor(shell);
        let child;
        try {
            child = spawn(sh.cmd, [...sh.pre, cmd], {
                cwd: cwd && fs.existsSync(cwd) ? cwd : DIR,
                windowsHide: true,
                env: process.env,
            });
        } catch (err) {
            return resolve({ ok: false, code: null, stdout: '', stderr: err.message, shell: sh.cmd });
        }
        let out = '';
        let err = '';
        let size = 0;
        let cortado = false;
        let matado = false;
        const cap = (buf, which) => {
            size += buf.length;
            if (size > EXEC_MAX_OUTPUT) {
                if (!cortado) {
                    cortado = true;
                    child.kill();
                }
                return;
            }
            if (which === 'out') {
                out += buf;
            } else {
                err += buf;
            }
        };
        child.stdout.on('data', (b) => cap(b, 'out'));
        child.stderr.on('data', (b) => cap(b, 'err'));
        const limite = Math.max(1000, Math.min(Number(timeout) || EXEC_TIMEOUT_MS, 600_000));
        const timer = setTimeout(() => {
            matado = true;
            child.kill('SIGKILL');
        }, limite);
        child.on('error', (e) => {
            clearTimeout(timer);
            resolve({ ok: false, code: null, stdout: out, stderr: (err + '\n' + e.message).trim(), shell: sh.cmd });
        });
        child.on('close', (code) => {
            clearTimeout(timer);
            resolve({
                ok: !matado && !cortado && code === 0,
                code,
                stdout: out.slice(0, EXEC_MAX_OUTPUT),
                stderr: err.slice(0, EXEC_MAX_OUTPUT),
                cortado,
                matado,
                shell: sh.cmd,
            });
        });
    });
}

function info() {
    return {
        id: ID,
        name: NAME,
        port: PORT,
        ips: localIPv4s().map((i) => i.address),
        filesDir: FILES_DIR,
        conClave: Boolean(KEY),
        grupo: GRUPO,
        execEnabled: execState.enabled,
        fileLimit: { max: RATE_LIMIT_MAX, windowMs: RATE_LIMIT_WINDOW_MS },
    };
}

// ---------------------------------------------------------------- envíos

async function sendText(to, text) {
    text = String(text ?? '').trim();
    if (!text) {
        throw httpError(400, 'El mensaje está vacío.');
    }
    const targets = resolveTargets(to);
    const broadcast = isAll(to);
    const results = await Promise.all(
        targets.map(async (peer) => {
            try {
                await peerRequest(peer, 'POST', '/inbox/message', {
                    json: { fromId: ID, fromName: NAME, fromPort: PORT, text, broadcast },
                });
                return { pc: peer.name, ok: true };
            } catch (err) {
                return { pc: peer.name, ok: false, error: err.message };
            }
        }),
    );
    if (!results.some((r) => r.ok)) {
        throw httpError(502, results.map((r) => `${r.pc}: ${r.error}`).join('; '));
    }
    addMessage({
        direction: 'out',
        kind: 'text',
        peerId: broadcast ? 'todos' : targets[0].id,
        peerName: broadcast ? 'Todos' : targets[0].name,
        text,
    });
    return results;
}

async function sendFile(to, filePath, name) {
    let st;
    try {
        st = fs.statSync(filePath);
    } catch {
        throw httpError(400, `No existe el archivo: ${filePath}`);
    }
    if (!st.isFile()) {
        throw httpError(400, `No es un archivo: ${filePath}`);
    }
    const targets = resolveTargets(to);
    const broadcast = isAll(to);
    const fileName = safeName(name || path.basename(filePath));
    const qs = new URLSearchParams({ name: fileName, fromId: ID, fromName: NAME, fromPort: String(PORT) });
    const results = await Promise.all(
        targets.map(async (peer) => {
            try {
                const r = await peerRequest(peer, 'POST', `/inbox/file?${qs}`, { file: { path: filePath, size: st.size } });
                return { pc: peer.name, ok: true, guardadoComo: r.fileName };
            } catch (err) {
                return { pc: peer.name, ok: false, error: err.message };
            }
        }),
    );
    if (!results.some((r) => r.ok)) {
        throw httpError(502, results.map((r) => `${r.pc}: ${r.error}`).join('; '));
    }
    addMessage({
        direction: 'out',
        kind: 'file',
        peerId: broadcast ? 'todos' : targets[0].id,
        peerName: broadcast ? 'Todos' : displayName(targets[0]),
        fileName,
        size: st.size,
    });
    return results;
}

async function sendExec(to, cmd, opts = {}) {
    cmd = String(cmd ?? '').trim();
    if (!cmd) {
        throw httpError(400, 'El comando está vacío.');
    }
    if (isAll(to)) {
        throw httpError(400, 'La ejecución remota es siempre a una sola PC, no a "todos".');
    }
    const peer = resolveTargets(to)[0];
    let result;
    try {
        result = await peerRequest(peer, 'POST', '/inbox/exec', {
            json: { fromId: ID, fromName: NAME, fromPort: PORT, cmd, shell: opts.shell, cwd: opts.cwd, timeout: opts.timeout },
        });
    } catch (err) {
        addMessage({ direction: 'out', kind: 'exec', peerId: peer.id, peerName: displayName(peer), cmd, ok: false, error: err.message });
        throw httpError(502, `${displayName(peer)}: ${err.message}`);
    }
    addMessage({ direction: 'out', kind: 'exec', peerId: peer.id, peerName: displayName(peer), cmd, ok: result.ok, code: result.code });
    return { pc: displayName(peer), ...result };
}

// ---------------------------------------------------------------- HTTP

const PUBLIC_DIR = path.join(__dirname, 'public');

// IP "principal": la de la interfaz con ruta por defecto (normalmente el WiFi).
// Truco sin dependencias: un socket UDP "conectado" a una IP externa no envía
// nada, pero revela qué interfaz local se usaría para salir.
let PRIMARY_IP = null;
(function detectPrimaryIp() {
    try {
        const s = dgram.createSocket('udp4');
        s.once('error', () => {
            try { s.close(); } catch {}
        });
        s.connect(80, '8.8.8.8', () => {
            try { PRIMARY_IP = s.address().address; } catch {}
            try { s.close(); } catch {}
        });
    } catch {}
})();

async function route(req, res) {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;

    // --- Público: lo usan las otras PCs de la red
    if (req.method === 'GET' && p === '/ping') {
        return sendJson(res, 200, { app: APP, id: ID, name: NAME, port: PORT, grupo: GRUPO });
    }

    if (p.startsWith('/inbox/')) {
        if (req.method !== 'POST') {
            return sendJson(res, 405, { error: 'método no permitido' });
        }
        if (!keyOk(req)) {
            return sendJson(res, 403, { error: 'clave incorrecta' });
        }
        const host = remoteIp(req);

        if (p === '/inbox/message') {
            const b = await readJson(req);
            upsertPeer({ id: b.fromId, name: b.fromName, host, port: b.fromPort });
            addMessage({
                direction: 'in',
                kind: 'text',
                peerId: b.fromId,
                peerName: String(b.fromName || host).slice(0, 60),
                text: String(b.text ?? '').slice(0, 20_000),
                broadcast: Boolean(b.broadcast),
            });
            return sendJson(res, 200, { ok: true });
        }

        if (p === '/inbox/file') {
            const q = url.searchParams;
            const fromId = q.get('fromId');
            const fromName = String(q.get('fromName') || host).slice(0, 60);
            upsertPeer({ id: fromId, name: fromName, host, port: q.get('fromPort') });
            if (!allowIncomingFile(fromId)) {
                const mins = Math.round(RATE_LIMIT_WINDOW_MS / 60_000);
                return sendJson(res, 429, { error: `límite de ${RATE_LIMIT_MAX} archivos cada ${mins} min alcanzado; probá más tarde` });
            }
            const fileName = safeName(q.get('name'));
            const tmp = path.join(PENDING_DIR, crypto.randomUUID());
            try {
                await pipeline(req, fs.createWriteStream(tmp));
            } catch (err) {
                fs.rmSync(tmp, { force: true });
                throw err;
            }
            const entry = {
                id: crypto.randomUUID(),
                fromId,
                fromName,
                host,
                fileName,
                size: fs.statSync(tmp).size,
                tmpPath: tmp,
                at: new Date().toISOString(),
            };
            addPending(entry);
            addMessage({ direction: 'in', kind: 'file-pendiente', peerId: fromId, peerName: fromName, fileName, size: entry.size, pendingId: entry.id });
            return sendJson(res, 200, { ok: true, pending: true, fileName });
        }

        if (p === '/inbox/exec') {
            const b = await readJson(req);
            upsertPeer({ id: b.fromId, name: b.fromName, host, port: b.fromPort });
            if (!execAllowedFor(b.fromId)) {
                return sendJson(res, 403, { error: 'ejecución remota no permitida en esta PC (activala desde la interfaz)' });
            }
            const cmd = String(b.cmd ?? '').trim();
            if (!cmd) {
                return sendJson(res, 400, { error: 'comando vacío' });
            }
            const result = await runCommand({ cmd, shell: b.shell, cwd: b.cwd, timeout: b.timeout });
            addMessage({
                direction: 'in',
                kind: 'exec',
                peerId: b.fromId,
                peerName: String(b.fromName || host).slice(0, 60),
                cmd,
                ok: result.ok,
                code: result.code,
            });
            return sendJson(res, 200, result);
        }

        return sendJson(res, 404, { error: 'no encontrado' });
    }

    // --- Local: interfaz y API solo desde esta misma PC
    if (!isLocal(req)) {
        return sendJson(res, 403, { error: 'solo accesible desde esta PC' });
    }

    if (req.method === 'GET' && (p === '/' || p === '/index.html')) {
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
        return fs.createReadStream(path.join(PUBLIC_DIR, 'index.html')).pipe(res);
    }

    if (req.method === 'GET' && p.startsWith('/files/')) {
        const name = safeName(decodeURIComponent(p.slice('/files/'.length)));
        const file = path.join(FILES_DIR, name);
        if (!fs.existsSync(file)) {
            return sendJson(res, 404, { error: 'archivo no encontrado' });
        }
        res.writeHead(200, {
            'content-type': 'application/octet-stream',
            'content-length': fs.statSync(file).size,
            'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
        });
        return fs.createReadStream(file).pipe(res);
    }

    if (req.method === 'GET') {
        switch (p) {
            case '/api/info':
                return sendJson(res, 200, info());
            case '/api/state':
                return sendJson(res, 200, {
                    me: info(),
                    peers: listPeers(),
                    messages: messages.slice(-300),
                    files: listFiles(),
                    pending: listPending(),
                    exec: execState,
                });
            case '/api/peers':
                return sendJson(res, 200, listPeers());
            case '/api/messages':
                return sendJson(res, 200, filterMessages(url.searchParams.get('pc'), url.searchParams.get('limit')));
            case '/api/files':
                return sendJson(res, 200, listFiles());
            case '/api/pending':
                return sendJson(res, 200, listPending());
            case '/api/exec-config':
                return sendJson(res, 200, execState);
            case '/api/group':
                // Solo localhost (gate isLocal); por eso puede incluir la clave.
                return sendJson(res, 200, { key: KEY, grupo: GRUPO, conClave: Boolean(KEY) });
            case '/api/pair': {
                // Datos para vincular el celular por QR. Solo accesible desde esta
                // PC (gate isLocal), por eso puede incluir la clave del grupo.
                // La IP principal (salida por defecto) va primera; después las
                // privadas útiles, y al final link-local / adaptadores virtuales.
                const rank = (ip) => (ip === PRIMARY_IP ? -1 : ip.startsWith('192.168.') ? 0 : ip.startsWith('10.') || ip.startsWith('172.') ? 1 : ip.startsWith('169.254.') ? 3 : 2);
                const ips = localIPv4s().map((i) => i.address).sort((a, b) => rank(a) - rank(b));
                const host = url.searchParams.get('host') || ips[0] || 'localhost';
                const payload = `intercom:pair?host=${encodeURIComponent(host)}&port=${PORT}&key=${encodeURIComponent(KEY)}&name=${encodeURIComponent(NAME)}`;
                return sendJson(res, 200, { host, port: PORT, name: NAME, key: KEY, ips, payload });
            }
            case '/api/events':
                res.writeHead(200, {
                    'content-type': 'text/event-stream; charset=utf-8',
                    'cache-control': 'no-cache',
                    connection: 'keep-alive',
                });
                res.write(': ok\n\n');
                sseClients.add(res);
                req.on('close', () => sseClients.delete(res));
                return;
        }
    }

    if (req.method === 'POST') {
        switch (p) {
            case '/api/message': {
                const b = await readJson(req);
                return sendJson(res, 200, { results: await sendText(b.to, b.text) });
            }
            case '/api/file': {
                const to = url.searchParams.get('to');
                const name = url.searchParams.get('name');
                resolveTargets(to);
                const tmp = path.join(TMP_DIR, crypto.randomUUID());
                try {
                    await pipeline(req, fs.createWriteStream(tmp));
                    return sendJson(res, 200, { results: await sendFile(to, tmp, name) });
                } finally {
                    fs.rmSync(tmp, { force: true });
                }
            }
            case '/api/send-path': {
                const b = await readJson(req);
                return sendJson(res, 200, { results: await sendFile(b.to, path.resolve(String(b.path || ''))) });
            }
            case '/api/peers': {
                const b = await readJson(req);
                const host = String(b.host || '').trim();
                const port = Number(b.port) || 7777;
                if (!host) {
                    throw httpError(400, 'Falta la IP.');
                }
                try {
                    const r = await pingHost(host, port);
                    manualHosts.set(`${host}:${port}`, { host, port });
                    return sendJson(res, 200, { ok: true, name: r.name });
                } catch (err) {
                    throw httpError(502, `No pude conectar con ${host}:${port} (${err.message}).`);
                }
            }
            case '/api/exec': {
                const b = await readJson(req);
                return sendJson(res, 200, await sendExec(b.to, b.cmd, { shell: b.shell, cwd: b.cwd, timeout: b.timeout }));
            }
            case '/api/exec-config': {
                const b = await readJson(req);
                return sendJson(res, 200, setExecConfig({ enabled: b.enabled, peerId: b.peerId, allow: b.allow }));
            }
            case '/api/pending/accept': {
                const b = await readJson(req);
                return sendJson(res, 200, { ok: true, fileName: acceptPending(String(b.id || '')) });
            }
            case '/api/pending/reject': {
                const b = await readJson(req);
                rejectPending(String(b.id || ''));
                return sendJson(res, 200, { ok: true });
            }
            case '/api/alias': {
                const b = await readJson(req);
                return sendJson(res, 200, { ok: true, alias: setAlias(b.id, b.alias) });
            }
            case '/api/group': {
                const b = await readJson(req);
                return sendJson(res, 200, setGroup(b.key));
            }
            case '/api/open-folder':
                openTarget(FILES_DIR);
                return sendJson(res, 200, { ok: true });
        }
    }

    return sendJson(res, 404, { error: 'no encontrado' });
}

const server = http.createServer((req, res) => {
    route(req, res).catch((err) => {
        if (!res.headersSent) {
            sendJson(res, err.status || 500, { error: err.message });
        } else {
            res.destroy();
        }
    });
});

server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
        console.error(`El puerto ${PORT} ya está en uso (¿ya está abierto el Intercom?). Abrí http://localhost:${PORT}`);
    } else {
        console.error(err);
    }
    process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
    udp.bind(DISCOVERY_PORT, () => {
        udp.setBroadcast(true);
        announce();
        setInterval(announce, ANNOUNCE_MS);
    });
    const ips = localIPv4s().map((i) => i.address).join(', ') || 'sin red';
    console.log(`Intercom "${NAME}" listo.`);
    console.log(`  Interfaz:  http://localhost:${PORT}`);
    console.log(`  IP en red: ${ips} (puerto ${PORT})`);
    console.log(`  Recibidos: ${FILES_DIR}`);
    console.log(`  Ejecución remota: ${execState.enabled ? 'ACTIVADA' : 'desactivada'} · archivos: máx ${RATE_LIMIT_MAX}/${Math.round(RATE_LIMIT_WINDOW_MS / 60_000)}min`);
    if (process.argv.includes('--abrir')) {
        openTarget(`http://localhost:${PORT}`);
    }
});

for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
        saveHistory();
        process.exit(0);
    });
}
