const externalState = { snapshot: null, token: null, expiresAt: 0, credentials: [], editing: null,
    history: null, historyMode: 'preset', historyHours: 24, historyFrom: null, historyTo: null,
    historyServiceId: null, historyLoading: false, historyError: null,
    historyRequest: 0, historyOpen: false };
const extPanel = document.querySelector('#external-panel');
const externalMessage = document.querySelector('#external-message');
const externalSearch = document.querySelector('#external-search');
const externalEnvironment = document.querySelector('#external-environment');
const externalStatus = document.querySelector('#external-status');
const unlockDialog = document.querySelector('#external-unlock-dialog');
const editorDialog = document.querySelector('#external-editor-dialog');
const credentialDialog = document.querySelector('#external-credential-dialog');

window.onExternalTabChanged = (active) => { if (active && !externalState.snapshot) loadExternalServices(); };
[externalSearch, externalEnvironment, externalStatus].forEach((control) =>
    control.addEventListener(control.type === 'search' ? 'input' : 'change', renderExternalServices));
document.querySelector('#external-unlock').addEventListener('click', () => unlockDialog.showModal());
document.querySelector('#external-unlock-cancel').addEventListener('click', () => unlockDialog.close());
document.querySelector('#external-unlock-form').addEventListener('submit', unlockExternalAdmin);
document.querySelector('#external-add').addEventListener('click', () => openServiceEditor());
document.querySelector('#external-credentials').addEventListener('click', openCredentialManager);
document.querySelector('#external-export').addEventListener('click', exportKdbx);
document.querySelector('#external-editor-close').addEventListener('click', () => editorDialog.close());
document.querySelector('#external-editor-cancel').addEventListener('click', () => editorDialog.close());
document.querySelector('#external-editor-form').addEventListener('submit', saveExternalService);
document.querySelector('#external-add-probe').addEventListener('click', () => addProbeRow());
document.querySelector('#external-create-group').addEventListener('click', createExternalGroup);
document.querySelector('#external-credential-close').addEventListener('click', () => credentialDialog.close());
document.querySelector('#external-credential-clear').addEventListener('click', resetCredentialForm);
document.querySelector('#external-credential-form').addEventListener('submit', saveCredential);
extPanel.addEventListener('click', handleExternalAction);
extPanel.addEventListener('change', handleExternalChange);
document.querySelector('#external-probe-editor').addEventListener('click', (event) => {
    const remove = event.target.closest('[data-remove-probe]');
    if (remove) { remove.closest('.probe-editor-row').remove(); refreshAllDependencies(); }
});
document.querySelector('#external-probe-editor').addEventListener('change', (event) => {
    if (event.target.matches('[data-probe-type]')) refreshProbeRow(event.target.closest('.probe-editor-row'));
    if (event.target.matches('[data-probe-dependency]')) event.target.closest('.probe-editor-row').dataset.dependsOn = event.target.value;
});
document.querySelector('#external-credential-list').addEventListener('click', handleCredentialAction);

loadExternalServices();
setInterval(() => {
    if (externalState.token && Date.now() >= externalState.expiresAt) lockExternalAdmin();
}, 15000);

window.loadExternalServices = loadExternalServices;
async function loadExternalServices() {
    setExternalMessageTone();
    externalMessage.textContent = 'Cargando servicios externos…';
    try {
        const response = await fetch('/api/v1/external-services/snapshot', { headers: { Accept: 'application/json' } });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        externalState.snapshot = body;
        fillExternalFilters();
        renderExternalServices();
        setExternalMessageTone(body.monitor?.tlsVerify === false ? 'warning' : 'success');
        externalMessage.textContent = externalMonitorMessage(body);
        await loadExternalHistory();
    } catch (error) {
        externalState.snapshot = null;
        extPanel.innerHTML = externalEmpty('Servicios externos no disponibles');
        setExternalMessageTone('error');
        externalMessage.textContent = error.message;
    }
}

function setExternalMessageTone(tone = '') {
    externalMessage.className = `map-message${tone ? ` ${tone}` : ''}${mapState.activeTab === 'external' ? '' : ' hidden'}`;
}

function fillExternalFilters() {
    const selected = externalEnvironment.value;
    const environments = [...new Set(externalState.snapshot.services.map((service) => service.environment))].sort();
    externalEnvironment.innerHTML = '<option value="">Todos los ambientes</option>' + environments
        .map((value) => `<option value="${externalEscape(value)}">${externalEscape(value)}</option>`).join('');
    externalEnvironment.value = selected;
    fillGroupSelect();
}

function renderExternalServices() {
    if (!externalState.snapshot) return;
    const expanded = new Set([...extPanel.querySelectorAll('.external-service-card[open]')]
        .map((card) => Number(card.dataset.serviceId)));
    const summary = externalState.snapshot.summary;
    const rangeSummary = externalState.history?.summary;
    const selectedRange = externalState.history ? historyRangeLabel(externalState.history) : '24 h';
    const query = normalizeExternal(externalSearch.value);
    const services = externalState.snapshot.services.filter((service) => {
        const targets = service.probes.map(probeTarget).join(' ');
        return (!query || normalizeExternal(`${service.name} ${service.environment} ${service.systemName} ${service.description || ''} ${targets}`).includes(query))
            && (!externalEnvironment.value || service.environment === externalEnvironment.value)
            && (!externalStatus.value || service.status === externalStatus.value);
    });
    extPanel.innerHTML = `<section class="external-kpis">
        ${kpi('Total', summary.total, `${summary.unknown || 0} sin datos`)}
        ${kpi('Operativos', summary.up, 'sin fallos obligatorios', 'up')}
        ${kpi('Advertencia', summary.warning, 'pendientes o informativos', 'warning')}
        ${kpi('Caídos', summary.down, 'fallo confirmado', 'down')}
        ${kpi(`Disponibilidad ${selectedRange}`, percent(rangeSummary?.availability), `${rangeSummary?.executions ?? 0} ejecuciones programadas`)}
        ${kpi(`P95 ${selectedRange}`, duration(rangeSummary?.p95DurationMs), `${rangeSummary?.warnings ?? 0} advertencias · ${rangeSummary?.downs ?? 0} caídas`)}
    </section>
    ${externalHistorySection()}
    <div class="panel-intro external-panel-intro"><div><h3>Servicios monitoreados</h3><p>${services.length} de ${summary.total} servicios coinciden con los filtros. ${summary.openIncidents} incidentes abiertos o por confirmar.</p></div><button class="button secondary compact-button" type="button" data-external-action="refresh">Actualizar</button></div>
    <div class="external-service-list">${services.length ? services.map(externalServiceCard).join('') : externalEmpty('No hay servicios para el filtro')}</div>`;
    expanded.forEach((id) => extPanel.querySelector(`.external-service-card[data-service-id="${id}"]`)?.setAttribute('open', ''));
}

function externalServiceCard(service) {
    const manualRun = service.lastRun?.manual ? service.lastRun : null;
    const displayStatus = manualRun?.status || service.status;
    const displayTone = statusTone(displayStatus);
    const probes = service.probes.map((probe) => {
        const manualResult = manualProbeResult(manualRun, probe);
        const result = manualResult || probe.lastResult;
        const status = result?.status || 'UNKNOWN';
        const source = manualResult ? ' · comprobación manual' : '';
        return `<article class="external-probe-row">
            <span class="state-dot ${status.toLowerCase()}"></span>
            <div><strong>${externalEscape(probe.name)}</strong><small>${externalEscape(probe.probeType)} · ${externalEscape(probeTarget(probe))}${probe.mandatory ? ' · obligatoria' : ' · informativa'}</small>${result ? `<small>${externalEscape(externalProbeResultDetail(result))} · ${result.durationMs} ms${source}</small>` : '<small>Sin ejecución programada</small>'}</div>
            <span class="external-probe-status ${status.toLowerCase()}">${statusLabel(status)}</span>
        </article>`;
    }).join('');
    const admin = externalUnlocked() ? `<div class="external-card-actions">
        <button type="button" data-external-action="run" data-id="${service.id}">Ejecutar ahora</button>
        <button type="button" data-external-action="edit" data-id="${service.id}">Editar</button>
        <button type="button" data-external-action="clone" data-id="${service.id}">Duplicar</button>
        <button type="button" class="danger-link" data-external-action="archive" data-id="${service.id}">Archivar</button>
    </div>` : '';
    const checked = manualRun ? `Manual ${externalRelativeTime(manualRun.finishedAt || manualRun.startedAt)}`
        : service.lastScheduledAt ? `Programado ${externalRelativeTime(service.lastScheduledAt)}` : 'Sin ejecución programada';
    const official = manualRun ? `<small>Estado programado: ${statusLabel(service.status)}</small>` : '';
    const description = visibleExternalDescription(service.description);
    return `<details class="external-service-card" data-service-id="${service.id}">
        <summary><div class="external-service-main"><span class="state-dot ${displayTone}"></span><div><h3>${externalEscape(service.name)}</h3><p>${externalEscape(service.environment)} · ${externalEscape(service.systemName)} · ${service.probes.length} prueba${service.probes.length === 1 ? '' : 's'}</p></div></div><div class="external-service-summary"><span>${checked}</span><strong class="external-status ${displayTone}">${statusLabel(displayStatus)}${manualRun ? ' · manual' : ''}</strong>${official}</div></summary>
        <div class="external-service-detail">${description ? `<p>${externalEscape(description)}</p>` : ''}${admin}<div class="external-probe-list">${probes}</div></div>
    </details>`;
}

async function loadExternalHistory() {
    if (!externalState.snapshot) return;
    const request = ++externalState.historyRequest;
    externalState.historyLoading = true;
    externalState.historyError = null;
    renderExternalServices();
    try {
        const response = await fetch(`/api/v1/external-services/history?${externalHistoryQuery()}`,
            { headers: { Accept: 'application/json' } });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        if (request !== externalState.historyRequest) return;
        externalState.history = body;
    } catch (exception) {
        if (request !== externalState.historyRequest) return;
        externalState.historyError = exception.message;
    } finally {
        if (request !== externalState.historyRequest) return;
        externalState.historyLoading = false;
        renderExternalServices();
    }
}

function externalHistorySection() {
    const ranges = [[1, '1 h'], [6, '6 h'], [24, '24 h'], [168, '7 d'], [720, '30 d']]
        .map(([hours, label]) => `<button type="button" class="${externalState.historyMode === 'preset' && externalState.historyHours === hours ? 'active' : ''}" data-external-action="history-range" data-hours="${hours}">${label}</button>`).join('');
    const services = externalState.snapshot.services.map((service) =>
        `<option value="${service.id}"${externalState.historyServiceId === service.id ? ' selected' : ''}>${externalEscape(service.name)} · ${externalEscape(service.environment)}</option>`).join('');
    let body = '';
    if (externalState.historyLoading) body = '<div class="external-history-state">Calculando KPI históricos…</div>';
    else if (externalState.historyError) body = `<div class="external-history-state error">${externalEscape(externalState.historyError)}</div>`;
    else if (!externalState.history) body = '<div class="external-history-state">Cargando historial…</div>';
    else body = externalHistoryBody(externalState.history);
    const custom = externalState.historyMode === 'custom' ? `<div class="external-custom-range">
        <label><span>Desde</span><input type="datetime-local" data-external-history-from value="${externalEscape(externalState.historyFrom || '')}"></label>
        <label><span>Hasta</span><input type="datetime-local" data-external-history-to value="${externalEscape(externalState.historyTo || '')}"></label>
        <button type="button" class="button secondary compact-button" data-external-action="history-custom-apply">Aplicar rango</button>
        <small>Zona horaria America/Lima · máximo 90 días.</small></div>` : '';
    const actions = `<div class="external-history-actions">${externalState.historyOpen ? `<button type="button" class="button secondary compact-button" data-external-action="history-export"${!externalState.historyLoading && externalState.history ? '' : ' disabled'}>Exportar KPI ZIP</button>` : ''}<button type="button" class="button secondary compact-button" data-external-action="history-toggle">${externalState.historyOpen ? 'Ocultar detalle' : 'Ver historial'}</button></div>`;
    return `<section class="external-history${externalState.historyOpen ? ' open' : ''}">
        <div class="external-history-heading"><div><h3>Historial y KPI</h3><p>Disponibilidad, latencia y estados de las ejecuciones programadas.</p></div>${actions}</div>
        ${externalState.historyOpen ? `<div class="external-history-controls"><div class="external-history-ranges">${ranges}<button type="button" class="${externalState.historyMode === 'custom' ? 'active' : ''}" data-external-action="history-custom">Personalizado</button></div><select data-external-history-service aria-label="Filtrar historial por servicio"><option value="">Todos los servicios</option>${services}</select></div>${custom}${body}` : ''}
    </section>`;
}

function externalHistoryBody(history) {
    const summary = history.summary;
    const grouped = new Map();
    (history.timeline || []).forEach((point) => {
        if (!grouped.has(point.serviceId)) grouped.set(point.serviceId, []);
        grouped.get(point.serviceId).push(point);
    });
    const timeline = history.services.length ? history.services.map((service) => {
        const points = grouped.get(service.serviceId) || [];
        const segments = points.map((point) => `<span class="${statusTone(point.status)}" title="${externalEscape(service.serviceName)} · ${externalDateTime(point.bucket)} · ${statusLabel(point.status)} · ${point.averageDurationMs ?? 0} ms"></span>`).join('');
        return `<div class="external-timeline-row"><strong title="${externalEscape(service.serviceName)}">${externalEscape(service.serviceName)}</strong><div class="external-timeline-track">${segments || '<em>Sin mediciones</em>'}</div></div>`;
    }).join('') : externalEmpty('No existen ejecuciones programadas en el rango');
    const rows = history.services.map((service) => `<tr>
        <td><strong>${externalEscape(service.serviceName)}</strong><small>${externalEscape(service.environment)} · ${externalEscape(service.systemName)}</small></td>
        <td>${service.executions}</td><td>${percent(service.availability)}</td>
        <td>${duration(service.averageDurationMs)}</td><td>${duration(service.p95DurationMs)}</td>
        <td>${service.warnings}</td><td>${service.downs}</td>
        <td><span class="external-status ${statusTone(service.lastStatus)}">${statusLabel(service.lastStatus)}</span><small>${externalDateTime(service.lastCheckedAt)}</small></td>
    </tr>`).join('');
    return `<div class="external-history-kpis">
        ${kpi('Ejecuciones', summary.executions, historyRangeLabel(history))}
        ${kpi('Disponibilidad', percent(summary.availability), 'UP + advertencias')}
        ${kpi('Latencia media', duration(summary.averageDurationMs), 'por ejecución')}
        ${kpi('Latencia p95', duration(summary.p95DurationMs), 'percentil 95')}
        ${kpi('Advertencias', summary.warnings, 'ejecuciones WARNING', 'warning')}
        ${kpi('Caídas', summary.downs, 'DOWN o error', 'down')}
    </div>
    <div class="external-timeline" aria-label="Línea de tiempo por servicio">${timeline}</div>
    <div class="external-history-table-wrap"><table class="external-history-table"><thead><tr><th>Servicio</th><th>Ejec.</th><th>Disponibilidad</th><th>Media</th><th>p95</th><th>Advert.</th><th>Caídas</th><th>Último estado</th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

async function exportExternalHistory() {
    if (!externalState.history) return;
    try {
        const response = await fetch(`/api/v1/external-services/history/export.zip?${externalHistoryQuery()}`,
            { headers: { Accept: 'application/zip' } });
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            throw new Error(body.message || `HTTP ${response.status}`);
        }
        const disposition = response.headers.get('Content-Disposition') || '';
        const filename = disposition.match(/filename="?([^";]+)"?/i)?.[1] || 'kpi-servicios-externos.zip';
        const link = document.createElement('a');
        link.href = URL.createObjectURL(await response.blob());
        link.download = filename;
        link.click();
        setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    } catch (exception) { alert(exception.message); }
}

async function unlockExternalAdmin(event) {
    event.preventDefault();
    const error = document.querySelector('#external-unlock-error');
    error.textContent = '';
    try {
        const response = await fetch('/api/v1/external-services/admin/unlock', {
            method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ masterPassword: document.querySelector('#external-master-password').value })
        });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        externalState.token = body.token;
        externalState.expiresAt = Date.parse(body.expiresAt);
        document.querySelector('#external-master-password').value = '';
        unlockDialog.close();
        document.querySelector('#external-unlock').classList.add('hidden');
        document.querySelector('#external-admin-actions').classList.remove('hidden');
        await loadCredentials();
        renderExternalServices();
    } catch (exception) { error.textContent = exception.message; }
}

function lockExternalAdmin() {
    externalState.token = null; externalState.expiresAt = 0; externalState.credentials = [];
    document.querySelector('#external-unlock').classList.remove('hidden');
    document.querySelector('#external-admin-actions').classList.add('hidden');
    renderExternalServices();
}

function externalUnlocked() { return Boolean(externalState.token && Date.now() < externalState.expiresAt); }
function adminHeaders(json = true) { return { ...(json ? { 'Content-Type': 'application/json' } : {}), Accept: 'application/json', 'X-External-Admin-Token': externalState.token }; }

async function handleExternalAction(event) {
    const action = event.target.closest('[data-external-action]');
    if (!action) return;
    if (action.dataset.externalAction === 'refresh') return loadExternalServices();
    if (action.dataset.externalAction === 'history-toggle') {
        externalState.historyOpen = !externalState.historyOpen;
        renderExternalServices();
        if (externalState.historyOpen && !externalState.history) loadExternalHistory();
        return;
    }
    if (action.dataset.externalAction === 'history-range') {
        externalState.historyMode = 'preset';
        externalState.historyHours = Number(action.dataset.hours);
        return loadExternalHistory();
    }
    if (action.dataset.externalAction === 'history-custom') {
        externalState.historyMode = 'custom';
        if (!externalState.historyFrom || !externalState.historyTo) initializeCustomHistoryRange();
        renderExternalServices();
        return;
    }
    if (action.dataset.externalAction === 'history-custom-apply') {
        externalState.historyFrom = document.querySelector('[data-external-history-from]')?.value || '';
        externalState.historyTo = document.querySelector('[data-external-history-to]')?.value || '';
        if (!externalState.historyFrom || !externalState.historyTo) return alert('Ingresa la fecha inicial y final.');
        const from = new Date(externalState.historyFrom);
        const to = new Date(externalState.historyTo);
        if (!(from < to)) return alert('La fecha inicial debe ser anterior a la final.');
        if (to - from > 90 * 24 * 60 * 60 * 1000) return alert('El rango personalizado no puede superar 90 días.');
        return loadExternalHistory();
    }
    if (action.dataset.externalAction === 'history-export') return exportExternalHistory();
    const service = externalState.snapshot.services.find((item) => item.id === Number(action.dataset.id));
    if (!service || !externalUnlocked()) return unlockDialog.showModal();
    if (action.dataset.externalAction === 'edit') return openServiceEditor(service);
    if (action.dataset.externalAction === 'run') return runService(service, action);
    if (action.dataset.externalAction === 'clone') return cloneService(service);
    if (action.dataset.externalAction === 'archive') return archiveService(service);
}

function handleExternalChange(event) {
    if (!event.target.matches('[data-external-history-service]')) return;
    externalState.historyServiceId = numberOrNull(event.target.value);
    loadExternalHistory();
}

function openServiceEditor(service = null) {
    externalState.editing = service;
    document.querySelector('#external-editor-title').textContent = service ? `Editar · ${service.name}` : 'Nuevo servicio';
    document.querySelector('#external-service-id').value = service?.id || '';
    document.querySelector('#external-service-name').value = service?.name || '';
    document.querySelector('#external-service-environment').value = service?.environment || 'Testing';
    document.querySelector('#external-service-system').value = service?.systemName || '';
    document.querySelector('#external-service-description').value = visibleExternalDescription(service?.description) || '';
    fillGroupSelect(service?.groupId);
    document.querySelector('#external-probe-editor').innerHTML = '';
    (service?.probes || []).forEach((probe, index) => addProbeRow(probe, index));
    if (!service) addProbeRow(null, 0);
    document.querySelector('#external-editor-error').textContent = '';
    editorDialog.showModal();
}

function addProbeRow(probe = null, index = null) {
    const container = document.querySelector('#external-probe-editor');
    const order = index == null ? container.children.length : index;
    const row = document.createElement('article');
    row.className = 'probe-editor-row';
    row.dataset.clientId = String(probe?.id || Date.now() + order);
    row.dataset.dependsOn = probe?.dependsOnProbeId || '';
    row.innerHTML = `<div class="probe-editor-heading"><strong>Prueba ${order + 1}</strong><button type="button" data-remove-probe aria-label="Quitar prueba">Quitar</button></div>
        <div class="probe-common-grid">
            <label><span>Nombre</span><input data-probe-name value="${externalEscape(probe?.name || '')}" required></label>
            <label><span>Tipo</span><select data-probe-type><option>TCP</option><option>HTTP</option><option>TOKEN_HTTP</option><option>DATABASE</option></select></label>
            <label><span>Criticidad</span><select data-probe-mandatory><option value="true">Obligatoria</option><option value="false">Informativa</option></select></label>
            <label><span>Depende de</span><select data-probe-dependency><option value="">Ninguna</option></select></label>
            <label><span>Timeout ms</span><input data-probe-timeout type="number" min="250" max="120000" value="${probe?.timeoutMs || 10000}" required></label>
        </div><div class="probe-specific"></div>`;
    container.append(row);
    row.querySelector('[data-probe-type]').value = probe?.probeType || 'TCP';
    row.querySelector('[data-probe-mandatory]').value = String(probe?.mandatory ?? true);
    row._probe = probe;
    refreshAllDependencies();
    refreshProbeRow(row);
}

function refreshAllDependencies() {
    const rows = [...document.querySelectorAll('.probe-editor-row')];
    rows.forEach((row, index) => {
        const select = row.querySelector('[data-probe-dependency]');
        const selected = row.dataset.dependsOn;
        select.innerHTML = '<option value="">Ninguna</option>' + rows.slice(0, index).map((candidate, candidateIndex) =>
            `<option value="${candidate.dataset.clientId}">Prueba ${candidateIndex + 1}</option>`).join('');
        select.value = selected;
    });
}

function refreshProbeRow(row) {
    const probe = row._probe || {};
    const type = row.querySelector('[data-probe-type]').value;
    const credentials = '<option value="">Sin credencial</option>' + externalState.credentials.map((item) =>
        `<option value="${item.id}">${externalEscape(item.name)} · ${externalEscape(item.environment)}</option>`).join('');
    let html = '';
    if (type === 'TCP') html = `<div class="probe-specific-grid"><label><span>Host</span><input data-probe-host value="${externalEscape(probe.host || '')}" required></label><label><span>Puerto</span><input data-probe-port type="number" min="1" max="65535" value="${probe.port || ''}" required></label></div>`;
    if (type === 'HTTP' || type === 'TOKEN_HTTP') html = `<div class="probe-specific-grid"><label class="wide"><span>URL</span><input data-probe-url type="url" value="${externalEscape(probe.url || '')}" required></label><label><span>Método</span><select data-probe-method><option>GET</option><option>HEAD</option><option>POST</option></select></label><label><span>Códigos esperados</span><input data-probe-statuses value="${externalEscape(probe.expectedStatuses || '200,201,202,204,301,302,401,403')}"></label><label><span>Autenticación</span><select data-probe-auth><option>NONE</option><option>BASIC</option><option>BEARER</option><option>API_KEY</option><option>OAUTH_CLIENT</option></select></label><label><span>Credencial</span><select data-probe-credential>${credentials}</select></label><label><span>Header API key</span><input data-probe-auth-header value="${externalEscape(probe.authHeader || '')}" placeholder="X-API-Key"></label><label class="wide"><span>Headers JSON (sin secretos)</span><textarea data-probe-headers rows="2">${externalEscape(probe.requestHeaders || '')}</textarea></label><label class="wide"><span>Body (admite &#36;{username}, &#36;{password}, &#36;{clientId}, &#36;{clientSecret}, &#36;{token})</span><textarea data-probe-body rows="3">${externalEscape(probe.requestBody || '')}</textarea></label><label><span>Texto esperado</span><input data-probe-expected-body value="${externalEscape(probe.expectedBody || '')}" title="Coincidencia literal, sensible a mayúsculas y minúsculas; vacío valida solo el código HTTP"><small>Contiene el texto literal; distingue mayúsculas.</small></label>${type === 'TOKEN_HTTP' ? `<label><span>Campo JSON del token</span><input data-probe-token-field value="${externalEscape(probe.tokenJsonField || 'access_token')}" required></label>` : ''}</div>`;
    if (type === 'DATABASE') html = `<div class="probe-specific-grid"><label><span>Host</span><input data-probe-host value="${externalEscape(probe.host || '')}" required></label><label><span>Puerto</span><input data-probe-port type="number" min="1" max="65535" value="${probe.port || ''}" required></label><label><span>Motor</span><select data-probe-db-engine><option>ORACLE</option><option>POSTGRESQL</option><option>SQLSERVER</option><option>MYSQL</option></select></label><label><span>Base de datos</span><input data-probe-db-name value="${externalEscape(probe.dbName || '')}"></label><label><span>Servicio Oracle</span><input data-probe-db-service value="${externalEscape(probe.dbService || '')}"></label><label><span>Credencial</span><select data-probe-credential required>${credentials}</select></label><label class="wide"><span>Consulta de validación</span><input data-probe-query value="${externalEscape(probe.validationQuery || '')}" placeholder="SELECT 1"></label></div>`;
    row.querySelector('.probe-specific').innerHTML = html;
    setOptionalValue(row, '[data-probe-method]', probe.httpMethod);
    setOptionalValue(row, '[data-probe-auth]', probe.authType);
    setOptionalValue(row, '[data-probe-credential]', probe.credentialId);
    setOptionalValue(row, '[data-probe-db-engine]', probe.dbEngine);
    row._probe = null;
}

async function saveExternalService(event) {
    event.preventDefault();
    if (!event.target.reportValidity()) return;
    const rows = [...document.querySelectorAll('.probe-editor-row')];
    const probes = rows.map((row, index) => probePayload(row, index));
    const payload = { name: value('#external-service-name'), environment: value('#external-service-environment'), systemName: value('#external-service-system'), description: value('#external-service-description') || null, groupId: numberOrNull(value('#external-service-group')), probes };
    const id = value('#external-service-id');
    try {
        await externalRequest(id ? `/admin/services/${id}` : '/admin/services', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) });
        editorDialog.close(); await loadExternalServices();
    } catch (exception) { document.querySelector('#external-editor-error').textContent = exception.message; }
}

async function createExternalGroup() {
    const name = prompt('Nombre del nuevo grupo:');
    if (!name?.trim()) return;
    try {
        const created = await externalRequest('/admin/groups', { method: 'POST', body: JSON.stringify({ name: name.trim(), parentId: null, displayOrder: externalState.snapshot.groups.length }) });
        externalState.snapshot.groups.push({ id: created.id, parentId: null, name: name.trim(), displayOrder: externalState.snapshot.groups.length });
        fillGroupSelect(created.id);
    } catch (exception) { document.querySelector('#external-editor-error').textContent = exception.message; }
}

function probePayload(row, index) {
    const get = (selector) => row.querySelector(selector)?.value?.trim() || null;
    const type = get('[data-probe-type]');
    return { clientId: Number(row.dataset.clientId), dependsOnClientId: numberOrNull(get('[data-probe-dependency]')), name: get('[data-probe-name]'), probeType: type, mandatory: get('[data-probe-mandatory]') === 'true', displayOrder: index, timeoutMs: Number(get('[data-probe-timeout]')), host: get('[data-probe-host]'), port: numberOrNull(get('[data-probe-port]')), url: get('[data-probe-url]'), httpMethod: get('[data-probe-method]'), requestHeaders: get('[data-probe-headers]'), requestBody: get('[data-probe-body]'), expectedStatuses: get('[data-probe-statuses]'), expectedBody: get('[data-probe-expected-body]'), tokenJsonField: get('[data-probe-token-field]'), authType: get('[data-probe-auth]') || 'NONE', authHeader: get('[data-probe-auth-header]'), credentialId: numberOrNull(get('[data-probe-credential]')), dbEngine: get('[data-probe-db-engine]'), dbName: get('[data-probe-db-name]'), dbService: get('[data-probe-db-service]'), validationQuery: get('[data-probe-query]') };
}

async function runService(service, button) {
    const original = button.textContent; button.disabled = true; button.textContent = 'Ejecutando…';
    try {
        const run = await externalRequest(`/admin/services/${service.id}/run`, { method: 'POST' });
        service.lastRun = run;
        renderExternalServices();
        await loadExternalServices();
    }
    catch (exception) { alert(exception.message); }
    finally { button.disabled = false; button.textContent = original; }
}

async function cloneService(service) {
    const name = prompt('Nombre del servicio duplicado:', `${service.name} - copia`);
    if (!name) return;
    try { await externalRequest(`/admin/services/${service.id}/clone`, { method: 'POST', body: JSON.stringify({ name }) }); await loadExternalServices(); }
    catch (exception) { alert(exception.message); }
}

async function archiveService(service) {
    if (!confirm(`¿Archivar ${service.name}? El historial se conserva.`)) return;
    try { await externalRequest(`/admin/services/${service.id}/archive`, { method: 'POST' }); await loadExternalServices(); }
    catch (exception) { alert(exception.message); }
}

async function loadCredentials() {
    const response = await externalRequest('/admin/credentials');
    externalState.credentials = response;
}

async function openCredentialManager() {
    try { await loadCredentials(); renderCredentialList(); resetCredentialForm(); credentialDialog.showModal(); }
    catch (exception) { alert(exception.message); }
}

function renderCredentialList() {
    document.querySelector('#external-credential-list').innerHTML = externalState.credentials.length ? externalState.credentials.map((item) => `<article><div><strong>${externalEscape(item.name)}</strong><small>${externalEscape(item.environment)} → ${externalEscape(item.credentialType)} → ${externalEscape(item.systemName)} · usuario ${externalEscape(item.username || '—')}</small></div><div><button type="button" data-credential-action="edit" data-id="${item.id}">Editar</button><button type="button" class="danger-link" data-credential-action="archive" data-id="${item.id}">Archivar</button></div></article>`).join('') : externalEmpty('No hay credenciales guardadas');
}

function handleCredentialAction(event) {
    const action = event.target.closest('[data-credential-action]'); if (!action) return;
    const credential = externalState.credentials.find((item) => item.id === Number(action.dataset.id)); if (!credential) return;
    if (action.dataset.credentialAction === 'edit') fillCredentialForm(credential);
    if (action.dataset.credentialAction === 'archive') archiveCredential(credential);
}

function fillCredentialForm(item) {
    document.querySelector('#external-credential-id').value = item.id;
    document.querySelector('#external-credential-form-title').textContent = `Editar · ${item.name}`;
    document.querySelector('#credential-name').value = item.name;
    document.querySelector('#credential-environment').value = item.environment;
    document.querySelector('#credential-type').value = item.credentialType;
    document.querySelector('#credential-system').value = item.systemName;
    document.querySelector('#credential-username').value = item.username || '';
    ['#credential-password', '#credential-token', '#credential-client-id', '#credential-client-secret'].forEach((selector) => document.querySelector(selector).value = '');
}

function resetCredentialForm() {
    document.querySelector('#external-credential-form').reset();
    document.querySelector('#external-credential-id').value = '';
    document.querySelector('#external-credential-form-title').textContent = 'Nueva credencial';
    document.querySelector('#credential-environment').value = 'Testing';
    document.querySelector('#credential-type').value = 'DATABASE';
    document.querySelector('#external-credential-error').textContent = '';
}

async function saveCredential(event) {
    event.preventDefault(); if (!event.target.reportValidity()) return;
    const id = value('#external-credential-id');
    const payload = { name: value('#credential-name'), environment: value('#credential-environment'), credentialType: value('#credential-type'), systemName: value('#credential-system'), username: value('#credential-username') || null, password: value('#credential-password') || null, token: value('#credential-token') || null, clientId: value('#credential-client-id') || null, clientSecret: value('#credential-client-secret') || null, extra: {} };
    try { await externalRequest(id ? `/admin/credentials/${id}` : '/admin/credentials', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) }); await loadCredentials(); renderCredentialList(); resetCredentialForm(); }
    catch (exception) { document.querySelector('#external-credential-error').textContent = exception.message; }
}

async function archiveCredential(item) {
    if (!confirm(`¿Archivar la credencial ${item.name}?`)) return;
    try { await externalRequest(`/admin/credentials/${item.id}/archive`, { method: 'POST' }); await loadCredentials(); renderCredentialList(); }
    catch (exception) { alert(exception.message); }
}

async function exportKdbx() {
    try {
        const response = await fetch('/api/v1/external-services/admin/export.kdbx',
            { headers: { ...adminHeaders(false), Accept: 'application/octet-stream' } });
        if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `HTTP ${response.status}`); }
        const blob = await response.blob(); const link = document.createElement('a');
        link.href = URL.createObjectURL(blob); link.download = 'ocp-tools-credenciales.kdbx'; link.click();
        setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    } catch (exception) { alert(exception.message); }
}

async function externalRequest(path, options = {}) {
    const response = await fetch(`/api/v1/external-services${path}`, { ...options, headers: { ...adminHeaders(options.body !== undefined), ...(options.headers || {}) } });
    const body = response.status === 204 ? {} : await response.json().catch(() => ({}));
    if (response.status === 401) { lockExternalAdmin(); throw new Error(body.message || 'La sesión administrativa venció'); }
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    return body;
}

function fillGroupSelect(selected = null) {
    const select = document.querySelector('#external-service-group'); if (!select || !externalState.snapshot) return;
    select.innerHTML = '<option value="">Sin grupo</option>' + externalState.snapshot.groups.map((group) => `<option value="${group.id}">${externalEscape(group.name)}</option>`).join('');
    select.value = selected || '';
}
function probeTarget(probe) { if (probe.url) return probe.url; if (probe.host) return `${probe.host}${probe.port ? `:${probe.port}` : ''}${probe.dbService ? `/${probe.dbService}` : probe.dbName ? `/${probe.dbName}` : ''}`; return 'Sin destino'; }
function kpi(label, value, detail, tone = '') { return `<article class="external-kpi ${tone}"><span>${label}</span><strong>${value}</strong><small>${detail}</small></article>`; }
function percent(value) { return value == null ? '—' : `${Number(value).toFixed(2)}%`; }
function duration(value) { return value == null ? '—' : `${value} ms`; }
function statusLabel(status) { return ({ UP: 'Operativo', WARNING: 'Advertencia', DOWN: 'Caído', ERROR: 'Error', UNKNOWN: 'Sin datos', SKIPPED: 'Omitido' })[status] || status || 'Sin datos'; }
function statusTone(status) { return status === 'ERROR' ? 'down' : String(status || 'UNKNOWN').toLowerCase(); }
function manualProbeResult(run, probe) { return run?.results?.find((result) => result.probeId === probe.id || result.probeName === probe.name) || null; }
function externalProbeResultDetail(result) {
    const message = result?.message || '';
    const code = Number.isInteger(result?.responseCode) ? `HTTP ${result.responseCode}` : '';
    if (!code || message.toUpperCase().includes(code)) return message || code;
    return `${code} · ${message}`;
}
function visibleExternalDescription(value) { return value === 'Migrado desde la prueba de concepto Node' ? '' : value; }
function externalMonitorMessage(snapshot) {
    const monitor = snapshot.monitor;
    if (!monitor) return `Monitoreo actualizado ${externalRelativeTime(snapshot.generatedAt)}.`;
    if (!monitor.enabled) return 'Monitor automático deshabilitado. Solo se ejecutarán comprobaciones manuales.';
    const latest = snapshot.services.map((service) => service.lastScheduledAt).filter(Boolean).sort().at(-1);
    const last = latest ? ` Último ciclo registrado ${externalRelativeTime(latest)}.`
        : ` El primer ciclo inicia aproximadamente ${formatInterval(monitor.initialDelaySeconds)} después del arranque.`;
    const tls = monitor.tlsVerify === false ? ' HTTPS cifrado sin validación del certificado.' : '';
    return `Monitor automático activo cada ${formatInterval(monitor.intervalSeconds)}, con hasta ${monitor.parallelism} servicios en paralelo.${last}${tls}`;
}
function formatInterval(seconds) { if (seconds % 3600 === 0) return `${seconds / 3600} h`; if (seconds % 60 === 0) return `${seconds / 60} min`; return `${seconds} s`; }
function rangeLabel(hours) { return hours < 24 ? `${hours} h` : hours === 24 ? '24 h' : `${hours / 24} d`; }
function historyRangeLabel(history) { return history?.hours ? rangeLabel(history.hours) : `${new Date(history.from).toLocaleDateString('es-PE')} – ${new Date(history.to).toLocaleDateString('es-PE')}`; }
function initializeCustomHistoryRange() {
    const to = new Date(); const from = new Date(to.getTime() - 24 * 60 * 60 * 1000);
    externalState.historyFrom = localDateTimeValue(from); externalState.historyTo = localDateTimeValue(to);
}
function localDateTimeValue(date) {
    const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
    return local.toISOString().slice(0, 16);
}
function externalHistoryQuery() {
    const query = new URLSearchParams();
    if (externalState.historyMode === 'custom') {
        query.set('from', new Date(externalState.historyFrom).toISOString());
        query.set('to', new Date(externalState.historyTo).toISOString());
    } else query.set('hours', String(externalState.historyHours));
    if (externalState.historyServiceId != null) query.set('serviceId', String(externalState.historyServiceId));
    return query.toString();
}
function externalDateTime(value) { return value ? new Date(value).toLocaleString('es-PE') : '—'; }
function csvValue(value) { return `"${String(value ?? '').replace(/"/g, '""')}"`; }
function normalizeExternal(value) { return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim(); }
function externalRelativeTime(value) { const seconds = Math.max(0, Math.floor((Date.now() - Date.parse(value)) / 1000)); if (seconds < 60) return 'hace segundos'; const minutes = Math.floor(seconds / 60); if (minutes < 60) return `hace ${minutes} min`; const hours = Math.floor(minutes / 60); return hours < 24 ? `hace ${hours} h` : `hace ${Math.floor(hours / 24)} d`; }
function externalEscape(value) { return String(value ?? '').replace(/[&<>'"]/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[character]); }
function externalEmpty(message) { return `<div class="empty-state">${externalEscape(message)}</div>`; }
function value(selector) { return document.querySelector(selector).value.trim(); }
function numberOrNull(value) { return value == null || value === '' ? null : Number(value); }
function setOptionalValue(root, selector, value) { const element = root.querySelector(selector); if (element && value != null) element.value = String(value); }
