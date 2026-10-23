#!/usr/bin/env node
'use strict';

/**
 * Servidor MCP (stdio) del Intercom LAN.
 * Habla con el demonio local (server.js) y lo arranca solo si no está corriendo.
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const { spawn } = require('node:child_process');

const PORT = Number(process.env.INTERCOM_PORT || 7777);
const PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26', '2024-11-05'];
const MAX_TEXT_BYTES = 256 * 1024;

function api(method, pathname, body) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
        const req = http.request(
            {
                host: '127.0.0.1',
                port: PORT,
                method,
                path: pathname,
                headers: payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {},
                timeout: 120_000,
            },
            (res) => {
                const chunks = [];
                res.on('data', (c) => chunks.push(c));
                res.on('end', () => {
                    let data;
                    try {
                        data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                    } catch {
                        data = {};
                    }
                    if (res.statusCode >= 400) {
                        reject(new Error(data.error || `HTTP ${res.statusCode}`));
                    } else {
                        resolve(data);
                    }
                });
            },
        );
        req.on('timeout', () => req.destroy(new Error('timeout')));
        req.on('error', reject);
        req.end(payload);
    });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function ensureDaemon() {
    try {
        return await api('GET', '/api/info');
    } catch {}
    spawn(process.execPath, [path.join(__dirname, 'server.js')], {
        detached: true,
        stdio: 'ignore',
        windowsHide: true,
        env: process.env,
    }).unref();
    for (let i = 0; i < 25; i++) {
        await wait(200);
        try {
            const me = await api('GET', '/api/info');
            await wait(3500); // tiempo para escuchar los anuncios de las otras PCs
            return me;
        } catch {}
    }
    throw new Error(`No pude iniciar el Intercom en el puerto ${PORT}.`);
}

function formatSize(n) {
    if (n < 1024) {
        return `${n} B`;
    }
    const units = ['KB', 'MB', 'GB'];
    let i = -1;
    do {
        n /= 1024;
        i++;
    } while (n >= 1024 && i < units.length - 1);
    return `${n.toFixed(1)} ${units[i]}`;
}

function formatResults(results) {
    return results.map((r) => (r.ok ? `✓ ${r.pc}` : `✗ ${r.pc}: ${r.error}`)).join('\n');
}

const tools = [
    {
        name: 'listar_pcs',
        description: 'Lista las PCs de la red local que tienen el Intercom abierto (y los datos de esta PC).',
        inputSchema: { type: 'object', properties: {} },
        async run() {
            const me = await ensureDaemon();
            const peers = await api('GET', '/api/peers');
            const lines = [`Esta PC: ${me.name} (${me.ips.join(', ') || 'sin red'}, puerto ${me.port})`, ''];
            lines.push(peers.length ? 'PCs conectadas:' : 'No hay otras PCs conectadas todavía.');
            for (const p of peers) {
                lines.push(`- ${p.name} (${p.host}:${p.port})`);
            }
            return lines.join('\n');
        },
    },
    {
        name: 'enviar_mensaje',
        description: 'Manda un mensaje de texto a una PC de la red (por nombre) o a "todos".',
        inputSchema: {
            type: 'object',
            properties: {
                pc: { type: 'string', description: 'Nombre de la PC destino (o parte del nombre), su IP, o "todos".' },
                texto: { type: 'string', description: 'Texto del mensaje.' },
            },
            required: ['pc', 'texto'],
        },
        async run({ pc, texto }) {
            await ensureDaemon();
            const { results } = await api('POST', '/api/message', { to: pc, text: texto });
            return `Mensaje enviado:\n${formatResults(results)}`;
        },
    },
    {
        name: 'enviar_archivo',
        description: 'Manda un archivo local de esta PC a otra PC de la red (o a "todos"). Llega a su carpeta Intercom/recibidos.',
        inputSchema: {
            type: 'object',
            properties: {
                pc: { type: 'string', description: 'Nombre de la PC destino (o parte del nombre), su IP, o "todos".' },
                ruta: { type: 'string', description: 'Ruta del archivo en esta PC.' },
            },
            required: ['pc', 'ruta'],
        },
        async run({ pc, ruta }) {
            await ensureDaemon();
            const { results } = await api('POST', '/api/send-path', { to: pc, path: path.resolve(ruta) });
            return `Archivo ${path.basename(ruta)} enviado:\n${formatResults(results)}`;
        },
    },
    {
        name: 'leer_mensajes',
        description: 'Lee los últimos mensajes del Intercom (recibidos y enviados), opcionalmente filtrados por PC.',
        inputSchema: {
            type: 'object',
            properties: {
                pc: { type: 'string', description: 'Filtrar por nombre de PC (opcional).' },
                limite: { type: 'number', description: 'Cantidad máxima de mensajes (default 30).' },
            },
        },
        async run({ pc, limite } = {}) {
            await ensureDaemon();
            const qs = new URLSearchParams({ limit: String(limite || 30) });
            if (pc) {
                qs.set('pc', pc);
            }
            const list = await api('GET', `/api/messages?${qs}`);
            if (!list.length) {
                return 'No hay mensajes.';
            }
            return list
                .map((m) => {
                    const when = new Date(m.at).toLocaleString('es-PY');
                    const who = m.direction === 'in' ? `${m.peerName} →` : `→ ${m.peerName}`;
                    const body = m.kind === 'file' ? `[archivo] ${m.fileName} (${formatSize(m.size)})` : m.text;
                    return `[${when}] ${who}: ${body}`;
                })
                .join('\n');
        },
    },
    {
        name: 'listar_archivos_recibidos',
        description: 'Lista los archivos que otras PCs mandaron a esta PC.',
        inputSchema: { type: 'object', properties: {} },
        async run() {
            const me = await ensureDaemon();
            const files = await api('GET', '/api/files');
            if (!files.length) {
                return `No hay archivos recibidos (carpeta: ${me.filesDir}).`;
            }
            return [`Carpeta: ${me.filesDir}`, ...files.map((f) => `- ${f.name} (${formatSize(f.size)}, ${new Date(f.at).toLocaleString('es-PY')})`)].join(
                '\n',
            );
        },
    },
    {
        name: 'leer_archivo_recibido',
        description: 'Devuelve la ruta de un archivo recibido y, si es texto y no es muy grande, su contenido.',
        inputSchema: {
            type: 'object',
            properties: { nombre: { type: 'string', description: 'Nombre del archivo recibido.' } },
            required: ['nombre'],
        },
        async run({ nombre }) {
            const me = await ensureDaemon();
            const file = path.join(me.filesDir, path.basename(String(nombre)));
            if (!fs.existsSync(file)) {
                throw new Error(`No existe "${nombre}" en ${me.filesDir}.`);
            }
            const size = fs.statSync(file).size;
            if (size <= MAX_TEXT_BYTES) {
                const buf = fs.readFileSync(file);
                if (!buf.includes(0)) {
                    return `Ruta: ${file}\n\n${buf.toString('utf8')}`;
                }
            }
            return `Ruta: ${file} (${formatSize(size)}). Es binario o grande: abrilo desde esa ruta.`;
        },
    },
    {
        name: 'agregar_pc',
        description: 'Conecta a mano una PC por IP, por si el descubrimiento automático no la encuentra.',
        inputSchema: {
            type: 'object',
            properties: {
                ip: { type: 'string', description: 'IP de la otra PC, ej. 192.168.0.15' },
                puerto: { type: 'number', description: 'Puerto del Intercom (default 7777).' },
            },
            required: ['ip'],
        },
        async run({ ip, puerto }) {
            await ensureDaemon();
            const r = await api('POST', '/api/peers', { host: ip, port: puerto || 7777 });
            return `Conectado con ${r.name} (${ip}).`;
        },
    },
    {
        name: 'ejecutar',
        description:
            'Ejecuta un comando en OTRA PC de la red y devuelve su salida. Esa PC debe tener la ejecución remota ACTIVADA (sino rechaza). Usar con cuidado.',
        inputSchema: {
            type: 'object',
            properties: {
                pc: { type: 'string', description: 'Nombre/alias de la PC destino (o su IP).' },
                comando: { type: 'string', description: 'Comando a ejecutar en esa PC.' },
                shell: { type: 'string', description: 'Shell: "cmd", "powershell" o "bash" (opcional; por defecto el del sistema).' },
                directorio: { type: 'string', description: 'Carpeta de trabajo en esa PC (opcional).' },
                timeout_ms: { type: 'number', description: 'Tiempo máximo en ms (opcional, default 120000).' },
            },
            required: ['pc', 'comando'],
        },
        async run({ pc, comando, shell, directorio, timeout_ms }) {
            await ensureDaemon();
            const r = await api('POST', '/api/exec', { to: pc, cmd: comando, shell, cwd: directorio, timeout: timeout_ms });
            const partes = [`PC: ${r.pc} · salida (exit ${r.code})${r.ok ? '' : ' ⚠'}${r.matado ? ' [cortado por timeout]' : ''}${r.cortado ? ' [salida truncada]' : ''}`];
            if (r.stdout && r.stdout.trim()) {
                partes.push('── stdout ──', r.stdout.trimEnd());
            }
            if (r.stderr && r.stderr.trim()) {
                partes.push('── stderr ──', r.stderr.trimEnd());
            }
            if ((!r.stdout || !r.stdout.trim()) && (!r.stderr || !r.stderr.trim())) {
                partes.push('(sin salida)');
            }
            return partes.join('\n');
        },
    },
    {
        name: 'permitir_ejecucion',
        description: 'Activa o desactiva, EN ESTA PC, que otras PCs puedan ejecutar comandos acá (opcionalmente solo para una PC).',
        inputSchema: {
            type: 'object',
            properties: {
                activar: { type: 'boolean', description: 'true para permitir, false para bloquear.' },
                pc_id: { type: 'string', description: 'id de una PC puntual para un permiso solo para ella (opcional).' },
            },
            required: ['activar'],
        },
        async run({ activar, pc_id }) {
            await ensureDaemon();
            const body = pc_id ? { peerId: pc_id, allow: activar } : { enabled: activar };
            const r = await api('POST', '/api/exec-config', body);
            return pc_id
                ? `Permiso para la PC ${pc_id}: ${activar ? 'permitido' : 'bloqueado'}.`
                : `Ejecución remota en esta PC: ${r.enabled ? 'ACTIVADA' : 'desactivada'}.`;
        },
    },
    {
        name: 'ver_pendientes',
        description: 'Lista los archivos entrantes que están esperando que vos los aceptes o rechaces.',
        inputSchema: { type: 'object', properties: {} },
        async run() {
            await ensureDaemon();
            const list = await api('GET', '/api/pending');
            if (!list.length) {
                return 'No hay archivos en espera.';
            }
            return list.map((p) => `- [${p.id}] ${p.fileName} (${formatSize(p.size)}) de ${p.fromName}`).join('\n');
        },
    },
    {
        name: 'resolver_pendiente',
        description: 'Acepta o rechaza un archivo entrante que está en espera (por su id, que da ver_pendientes).',
        inputSchema: {
            type: 'object',
            properties: {
                id: { type: 'string', description: 'id del archivo en espera.' },
                aceptar: { type: 'boolean', description: 'true para aceptar (guardar), false para rechazar (borrar).' },
            },
            required: ['id', 'aceptar'],
        },
        async run({ id, aceptar }) {
            await ensureDaemon();
            if (aceptar) {
                const r = await api('POST', '/api/pending/accept', { id });
                return `Aceptado: ${r.fileName}.`;
            }
            await api('POST', '/api/pending/reject', { id });
            return 'Rechazado.';
        },
    },
    {
        name: 'poner_alias',
        description: 'Le pone (o le saca) un nombre/alias propio a una PC, para referirte a ella más fácil.',
        inputSchema: {
            type: 'object',
            properties: {
                pc_id: { type: 'string', description: 'id de la PC (lo da listar_pcs).' },
                alias: { type: 'string', description: 'Alias nuevo. Vacío para sacarlo.' },
            },
            required: ['pc_id'],
        },
        async run({ pc_id, alias }) {
            await ensureDaemon();
            const r = await api('POST', '/api/alias', { id: pc_id, alias: alias || '' });
            return r.alias ? `Alias puesto: ${r.alias}.` : 'Alias removido.';
        },
    },
];

// ---------------------------------------------------------------- JSON-RPC por stdio

function reply(id, result) {
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function replyError(id, code, message) {
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

async function handle(msg) {
    const { id, method, params = {} } = msg;
    const isRequest = id !== undefined && id !== null;

    switch (method) {
        case 'initialize':
            return reply(id, {
                protocolVersion: PROTOCOL_VERSIONS.includes(params.protocolVersion) ? params.protocolVersion : PROTOCOL_VERSIONS[0],
                capabilities: { tools: {} },
                serverInfo: { name: 'lan-intercom', version: '1.0.0' },
                instructions:
                    'Intercomunicador entre PCs de la misma red local: listar PCs, mandar mensajes y archivos, leer lo recibido.',
            });
        case 'ping':
            return reply(id, {});
        case 'tools/list':
            return reply(id, { tools: tools.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) });
        case 'tools/call': {
            const tool = tools.find((t) => t.name === params.name);
            if (!tool) {
                return replyError(id, -32602, `Herramienta desconocida: ${params.name}`);
            }
            try {
                const text = await tool.run(params.arguments || {});
                return reply(id, { content: [{ type: 'text', text }] });
            } catch (err) {
                return reply(id, { content: [{ type: 'text', text: `Error: ${err.message}` }], isError: true });
            }
        }
        default:
            if (isRequest) {
                replyError(id, -32601, `Método no soportado: ${method}`);
            }
    }
}

const rl = readline.createInterface({ input: process.stdin });

rl.on('line', (line) => {
    if (!line.trim()) {
        return;
    }
    let msg;
    try {
        msg = JSON.parse(line);
    } catch {
        return replyError(null, -32700, 'JSON inválido');
    }
    handle(msg).catch((err) => console.error('[mcp]', err));
});
