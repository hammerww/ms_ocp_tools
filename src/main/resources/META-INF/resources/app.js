const mapState = { snapshot: null, activeTab: 'external' };
const mapMessage = document.querySelector('#map-message');
const namespaceFreshness = document.querySelector('#namespace-freshness');
const casesPanel = document.querySelector('#cases-panel');
const deploymentsPanel = document.querySelector('#deployments-panel');
const unmappedPanel = document.querySelector('#unmapped-panel');
const impactPanel = document.querySelector('#impact-panel');
const externalPanel = document.querySelector('#external-panel');
const mapCaseSearch = document.querySelector('#map-case-search');
const mapElementSearch = document.querySelector('#map-element-search');
const mapSearch = document.querySelector('#map-search');
const mapNamespace = document.querySelector('#map-namespace');
const mapDeploymentState = document.querySelector('#map-state');
const mapTesting = document.querySelector('#map-testing');
const testingDialog = document.querySelector('#testing-dialog');
const caseNoteDialog = document.querySelector('#case-note-dialog');
const flowDialog = document.querySelector('#flow-dialog');
const flowFrame = document.querySelector('#flow-frame');
let flowTrigger = null;

document.querySelector('#tool-select').addEventListener('change', (event) => {
    document.querySelector('#ocp-map-tool').classList.toggle('hidden', event.target.value !== 'ocp-map');
    document.querySelector('#cms-tool').classList.toggle('hidden', event.target.value !== 'cms');
    document.querySelector('#tcp-check-tool').classList.toggle('hidden', event.target.value !== 'tcp-check');
});

document.querySelectorAll('.inner-tab').forEach((button) => button.addEventListener('click', () => {
    mapState.activeTab = button.dataset.tab;
    document.querySelectorAll('.inner-tab').forEach((item) => item.classList.toggle('active', item === button));
    externalPanel.classList.toggle('hidden', mapState.activeTab !== 'external');
    casesPanel.classList.toggle('hidden', mapState.activeTab !== 'cases');
    deploymentsPanel.classList.toggle('hidden', mapState.activeTab !== 'deployments');
    unmappedPanel.classList.toggle('hidden', mapState.activeTab !== 'unmapped');
    impactPanel.classList.toggle('hidden', mapState.activeTab !== 'impact');
    const external = mapState.activeTab === 'external';
    document.querySelector('#external-filters').classList.toggle('hidden', !external);
    document.querySelector('#ocp-map-filters').classList.toggle('hidden', external);
    document.querySelector('#namespace-summary').classList.toggle('hidden', external);
    mapMessage.classList.toggle('hidden', external);
    document.querySelector('#external-message').classList.toggle('hidden', !external);
    document.querySelector('#map-view-title').textContent = external ? 'Servicios externos' : 'Mapa operativo OCP';
    document.querySelector('#map-view-subtitle').textContent = external
        ? 'Disponibilidad, tiempos de respuesta e incidentes de integraciones y backends.'
        : 'Inventario actual de deployments, organizado por caso de prueba y leído directamente desde OpenShift.';
    window.onExternalTabChanged?.(external);
}));

document.querySelector('#refresh-map').addEventListener('click', () => {
    if (mapState.activeTab === 'external') window.loadExternalServices?.();
    else loadMap();
});
[mapCaseSearch, mapElementSearch, mapSearch, mapNamespace, mapDeploymentState, mapTesting].forEach((control) =>
    control.addEventListener(control.type === 'search' ? 'input' : 'change', renderMap));
[casesPanel, deploymentsPanel, unmappedPanel].forEach((panel) => panel.addEventListener('change', (event) => {
    const checkbox = event.target.closest('[data-testing-toggle]');
    if (checkbox) openTestingDialog(checkbox.dataset.namespace, checkbox.dataset.name, checkbox.checked);
}));
casesPanel.addEventListener('click', (event) => {
    const action = event.target.closest('[data-case-action]');
    if (!action) return;
    event.preventDefault();
    event.stopPropagation();
    const testCase = mapState.snapshot?.testCases.find((item) => item.id === Number(action.dataset.id));
    if (!testCase) return;
    if (action.dataset.caseAction === 'flow') {
        openFlowDialog(testCase, action);
        return;
    }
    document.querySelector('#case-note-title').textContent = `${testCase.code} · ${testCase.name}`;
    document.querySelector('#case-note-text').textContent = testCase.annotation || 'Este caso todavía no tiene una anotación.';
    caseNoteDialog.showModal();
});

document.querySelector('#testing-cancel').addEventListener('click', () => {
    testingDialog.close();
    renderMap();
});
document.querySelector('#testing-form').addEventListener('submit', saveTestingMark);
['#case-note-close', '#case-note-done'].forEach((selector) => document.querySelector(selector)
    .addEventListener('click', () => caseNoteDialog.close()));
window.addEventListener('message', (event) => {
    if (event.origin !== window.location.origin || event.data?.type !== 'ocp-flow-close') return;
    flowDialog.close();
});
flowDialog.addEventListener('close', () => {
    flowFrame.src = 'about:blank';
    flowTrigger?.focus();
    flowTrigger = null;
});

loadMap();
setInterval(() => mapState.snapshot && renderFreshness(), 30000);

async function loadMap() {
    setMapMessageTone();
    mapMessage.textContent = 'Cargando inventario…';
    try {
        const response = await fetch('/api/v1/ocp-map/snapshot', { headers: { Accept: 'application/json' } });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        mapState.snapshot = body;
        fillNamespaceFilter();
        renderMap();
        setMapMessageTone('success');
        mapMessage.textContent = `Vista generada ${relativeTime(body.generatedAt)}. El sensor continúa actualizando un namespace cada 5 segundos.`;
    } catch (error) {
        mapState.snapshot = null;
        setMapMessageTone('error');
        mapMessage.textContent = `${error.message}. La utilidad Validación CMS / Ambiente continúa disponible desde el selector superior.`;
        namespaceFreshness.replaceChildren();
        casesPanel.innerHTML = emptyState('Inventario no disponible');
        deploymentsPanel.innerHTML = '';
        unmappedPanel.innerHTML = '';
        impactPanel.innerHTML = '';
    }
}

function setMapMessageTone(tone = '') {
    mapMessage.className = `map-message${tone ? ` ${tone}` : ''}${mapState.activeTab === 'external' ? ' hidden' : ''}`;
}

function fillNamespaceFilter() {
    const selected = mapNamespace.value;
    mapNamespace.innerHTML = '<option value="">Todos los namespaces</option>';
    mapState.snapshot.namespaces.forEach((namespace) => {
        const option = document.createElement('option');
        option.value = namespace.name;
        option.textContent = namespace.name;
        mapNamespace.append(option);
    });
    mapNamespace.value = selected;
}

function renderMap() {
    if (!mapState.snapshot) return;
    renderFreshness();
    renderCases();
    renderDeployments();
    renderUnmapped();
    renderImpact();
}

function renderDeployments() {
    const deployments = mapState.snapshot.deployments.filter(matchesFilters).filter(matchesDeploymentCaseFilters);
    document.querySelector('#deployments-count').textContent = mapState.snapshot.deployments.length;
    deploymentsPanel.innerHTML = `<div class="panel-intro"><h3>Todos los microservicios</h3><p>${deployments.length} de ${mapState.snapshot.deployments.length} deployments coinciden con los filtros actuales.</p></div>
        <div class="deployment-list standalone">${deployments.length ? deployments.map(deploymentRow).join('') : emptyState('No hay microservicios para el filtro')}</div>`;
}

function renderFreshness() {
    const healthy = mapState.snapshot.namespaces.filter((namespace) => namespace.lastSuccessAt
        && Date.now() - Date.parse(namespace.lastSuccessAt) <= 60000).length;
    document.querySelector('#namespace-summary-text').textContent = `${healthy}/${mapState.snapshot.namespaces.length} con lectura reciente`;
    namespaceFreshness.innerHTML = mapState.snapshot.namespaces.map((namespace) => {
        const stale = !namespace.lastSuccessAt || Date.now() - Date.parse(namespace.lastSuccessAt) > 60000;
        return `<article class="freshness-card ${stale ? 'stale' : ''}">
            <strong>${escapeHtml(namespace.name)}</strong>
            <span>${namespace.lastSuccessAt ? `Última actualización exitosa ${relativeTime(namespace.lastSuccessAt)}` : 'Sin lectura exitosa'}</span>
            ${namespace.lastError ? `<small title="${escapeHtml(namespace.lastError)}">Último intento con error</small>` : ''}
        </article>`;
    }).join('');
}

function renderCases() {
    const cards = mapState.snapshot.testCases.map((testCase) => {
        if (!matchesCaseFilters(testCase) || !matchesCaseDeploymentFilters(testCase)) return '';
        const deployments = testCase.deployments.filter(matchesFilters);
        const stopped = deployments.filter((deployment) => deployment.active && deployment.state === 'APAGADO').length;
        const metadata = testCase.metadata || [];
        return `<details class="case-card">
            <summary class="case-card-summary"><div class="case-card-main"><span class="case-code">${escapeHtml(testCase.code)}</span><h3>${escapeHtml(testCase.name)}</h3><p>${escapeHtml(metadata.join(' · ') || testCase.description || '')}</p></div>
            <div class="case-summary-actions">${testCase.hasFlow ? `<button class="case-action" type="button" data-case-action="flow" data-id="${testCase.id}">Ver flujo</button>` : '<span class="case-action-empty">Sin flujo</span>'}<button class="case-action" type="button" data-case-action="note" data-id="${testCase.id}">Anotación</button><span class="case-summary ${stopped ? 'warning' : ''}">${stopped ? `${stopped} apagado${stopped > 1 ? 's' : ''}` : 'Sin apagados'}</span></div></summary>
            <div class="case-content">
                <details class="case-group"><summary>Elementos del caso <span>${testCase.elements.length}</span></summary><ul>${testCase.elements.map((item) => `<li>${escapeHtml(item)}</li>`).join('') || '<li>Sin elementos asociados</li>'}</ul></details>
                <details class="case-group"><summary>Microservicios del caso <span>${deployments.length}</span></summary><div class="deployment-list">${deployments.length ? deployments.map(deploymentRow).join('') : emptyState('Sin deployments para el filtro')}</div></details>
            </div>
        </details>`;
    }).filter(Boolean);
    casesPanel.innerHTML = cards.length ? cards.join('') : emptyState('No hay casos que coincidan con los filtros');
}

function openFlowDialog(testCase, trigger) {
    flowTrigger = trigger;
    flowFrame.title = `Flujo ${testCase.code} · ${testCase.name}`;
    flowFrame.src = `/flow?caseId=${encodeURIComponent(testCase.id)}&embedded=1`;
    flowDialog.showModal();
}

function renderUnmapped() {
    const deployments = mapState.snapshot.unmappedDeployments.filter(matchesFilters);
    document.querySelector('#unmapped-count').textContent = mapState.snapshot.unmappedDeployments.length;
    unmappedPanel.innerHTML = `<div class="panel-intro"><h3>Deployments sin flujo</h3><p>Se descubrieron en OCP, pero aún no están asociados manualmente a un caso de prueba.</p></div>
        <div class="deployment-list standalone">${deployments.length ? deployments.map(deploymentRow).join('') : emptyState('No hay deployments sin flujo para el filtro')}</div>`;
}

function renderImpact() {
    const impact = allDeployments()
        .filter((deployment) => deployment.active && deployment.testCases.length && matchesFilters(deployment))
        .filter(matchesDeploymentCaseFilters)
        .sort((a, b) => b.testCases.length - a.testCases.length || a.name.localeCompare(b.name));
    impactPanel.innerHTML = `<div class="panel-intro"><h3>Impacto al apagar</h3><p>Ordena los deployments activos por cantidad de casos afectados. Es una vista informativa y no ejecuta acciones en OCP.</p></div>
        <div class="impact-list">${impact.map((deployment, index) => `<article class="impact-row">
            <span class="rank">${index + 1}</span><div><strong>${escapeHtml(deployment.name)}</strong><small>${escapeHtml(deployment.namespace)} · ${deployment.testCases.map(escapeHtml).join(', ')} · estado ${escapeHtml(deployment.state)}</small></div>
            <span class="impact-count ${deployment.state === 'APAGADO' ? 'warning' : ''}">${deployment.testCases.length} casos</span>
        </article>`).join('') || emptyState('No hay datos activos para calcular impacto')}</div>`;
}

function deploymentRow(deployment) {
    const marked = Boolean(deployment.testingMark?.active);
    const expired = Boolean(deployment.testingMark && !deployment.testingMark.active);
    const replicas = deployment.desiredReplicas == null ? '—' : `${deployment.readyReplicas ?? 0}/${deployment.desiredReplicas}`;
    return `<article class="deployment-row ${deployment.active ? '' : 'inactive'}">
        <span class="state-dot ${deployment.state.toLowerCase()}" title="${escapeHtml(deployment.state)}"></span>
        <div class="deployment-main"><a href="${escapeHtml(deployment.consoleUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(deployment.name)} ↗</a><small>${escapeHtml(deployment.namespace)} · ready/desired ${replicas} · ${deployment.lastSeenAt ? `visto ${relativeTime(deployment.lastSeenAt)}` : 'aún no observado'}</small></div>
        <span class="state-label ${deployment.state.toLowerCase()}">${escapeHtml(deployment.state)}</span>
        <label class="testing-toggle ${expired ? 'expired' : ''}" title="${escapeHtml(deployment.testingMark ? markTitle(deployment.testingMark) : 'Marcar deployment en testing')}"><input type="checkbox" data-testing-toggle data-namespace="${escapeHtml(deployment.namespace)}" data-name="${escapeHtml(deployment.name)}" ${marked ? 'checked' : ''} ${deployment.active ? '' : 'disabled'}> ${expired ? 'testing vencido' : 'testing'}</label>
    </article>`;
}

function matchesFilters(deployment) {
    return matchesQuery(`${deployment.name} ${deployment.namespace}`, mapSearch.value)
        && (!mapNamespace.value || deployment.namespace === mapNamespace.value)
        && (!mapDeploymentState.value || deployment.state === mapDeploymentState.value)
        && (!mapTesting.checked || deployment.testingMark?.active);
}

function matchesCaseFilters(testCase) {
    return matchesQuery(`${testCase.code} ${testCase.name} ${testCase.description || ''} ${testCase.annotation || ''}`, mapCaseSearch.value)
        && matchesQuery(testCase.elements.join(' '), mapElementSearch.value);
}

function matchesCaseDeploymentFilters(testCase) {
    const hasDeploymentFilter = Boolean(mapSearch.value.trim() || mapNamespace.value
        || mapDeploymentState.value || mapTesting.checked);
    if (!hasDeploymentFilter) return true;
    return testCase.deployments.some(matchesFilters);
}

function matchesDeploymentCaseFilters(deployment) {
    if (!mapCaseSearch.value && !mapElementSearch.value) return true;
    return mapState.snapshot.testCases.some((testCase) =>
        deployment.testCases.includes(testCase.code) && matchesCaseFilters(testCase));
}

function openTestingDialog(namespace, name, adding) {
    const deployment = allDeployments().find((item) => item.namespace === namespace && item.name === name);
    document.querySelector('#testing-mode').value = adding ? 'add' : 'remove';
    document.querySelector('#testing-namespace-value').value = namespace;
    document.querySelector('#testing-name-value').value = name;
    document.querySelector('#testing-dialog-title').textContent = adding ? 'Marcar en testing' : 'Quitar marca de testing';
    document.querySelector('#testing-deployment-label').textContent = `${namespace} / ${name}`;
    document.querySelector('#testing-responsible').value = adding ? (deployment?.testingMark?.responsible || '') : '';
    document.querySelector('#testing-note').value = adding ? (deployment?.testingMark?.note || '') : '';
    document.querySelector('#testing-expiry').value = '';
    document.querySelector('#testing-expiry-label').classList.toggle('hidden', !adding);
    document.querySelector('#testing-error').textContent = '';
    testingDialog.showModal();
}

async function saveTestingMark(event) {
    event.preventDefault();
    if (!event.target.reportValidity()) return;
    const mode = document.querySelector('#testing-mode').value;
    const namespace = document.querySelector('#testing-namespace-value').value;
    const name = document.querySelector('#testing-name-value').value;
    const expiry = document.querySelector('#testing-expiry').value;
    const payload = {
        responsible: document.querySelector('#testing-responsible').value.trim(),
        note: document.querySelector('#testing-note').value.trim() || null
    };
    if (mode === 'add') payload.expiresAt = expiry ? new Date(expiry).toISOString() : null;
    try {
        const suffix = mode === 'add' ? 'testing-mark' : 'testing-mark/remove';
        const response = await fetch(`/api/v1/ocp-map/deployments/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/${suffix}`, {
            method: mode === 'add' ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(payload)
        });
        const body = response.status === 204 ? {} : await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        testingDialog.close();
        await loadMap();
    } catch (error) {
        document.querySelector('#testing-error').textContent = error.message;
    }
}

function allDeployments() {
    return mapState.snapshot?.deployments || [];
}

function relativeTime(value) {
    if (!value) return 'nunca';
    const seconds = Math.round((Date.parse(value) - Date.now()) / 1000);
    const formatter = new Intl.RelativeTimeFormat('es', { numeric: 'auto' });
    if (Math.abs(seconds) < 60) return formatter.format(seconds, 'second');
    const minutes = Math.round(seconds / 60);
    if (Math.abs(minutes) < 60) return formatter.format(minutes, 'minute');
    const hours = Math.round(minutes / 60);
    if (Math.abs(hours) < 48) return formatter.format(hours, 'hour');
    return formatter.format(Math.round(hours / 24), 'day');
}

function markTitle(mark) {
    return `Responsable: ${mark.responsible}${mark.note ? ` · ${mark.note}` : ''}${mark.expiresAt ? ` · ${mark.active ? 'expira' : 'venció'} ${relativeTime(mark.expiresAt)}` : ''}`;
}

function emptyState(text) { return `<div class="empty-state">${escapeHtml(text)}</div>`; }
function normalize(value) { return (value || '').toLocaleLowerCase('es').normalize('NFD').replace(/\p{Diacritic}/gu, ''); }
function matchesQuery(candidate, query) {
    const normalizedQuery = normalize(query).trim();
    if (!normalizedQuery) return true;
    const normalizedCandidate = normalize(candidate);
    if (normalizedCandidate.includes(normalizedQuery)) return true;
    const words = normalizedCandidate.split(/[^a-z0-9-]+/).filter(Boolean);
    return normalizedQuery.split(/\s+/).every((token) => words.some((word) =>
        word.includes(token) || (token.length >= 4 && levenshteinAtMostOne(word, token))));
}
function levenshteinAtMostOne(first, second) {
    if (Math.abs(first.length - second.length) > 1) return false;
    let i = 0; let j = 0; let differences = 0;
    while (i < first.length && j < second.length) {
        if (first[i] === second[j]) { i += 1; j += 1; continue; }
        if (++differences > 1) return false;
        if (first.length > second.length) i += 1; else if (second.length > first.length) j += 1; else { i += 1; j += 1; }
    }
    return differences + (i < first.length || j < second.length ? 1 : 0) <= 1;
}
function escapeHtml(value) { return String(value ?? '').replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char])); }

// Utilidad CMS: flujo existente, aislado del inventario PostgreSQL.
const form = document.querySelector('#validation-form');
const accessIdInput = document.querySelector('#access-id');
const environmentSelect = document.querySelector('#environment');
const validateButton = document.querySelector('#validate-button');
const registerButton = document.querySelector('#register-button');
const requestState = document.querySelector('#request-state');
const resultCard = document.querySelector('#result-card');
const registerPanel = document.querySelector('#register-panel');
const statusBadge = document.querySelector('#status-badge');
const resultMessage = document.querySelector('#result-message');
let validatedRequest = null;
const statusLabels = { CMS_NOT_FOUND: 'NO ESTÁ EN CMS', ENVIRONMENT_FOUND: 'ENCONTRADO', ENVIRONMENT_NOT_FOUND: 'NO ENCONTRADO', REGISTER_SUCCESS: 'REGISTRO CONFIRMADO', REGISTER_FAILED: 'REGISTRO FALLIDO', REGISTER_UNVERIFIED: 'REVISIÓN REQUERIDA', QUERY_ERROR: 'ERROR DE CONSULTA', DATABASE_ERROR: 'ERROR DE CMS', TIMEOUT: 'TIMEOUT', INVALID_REQUEST: 'SOLICITUD INVÁLIDA' };
const successStatuses = new Set(['ENVIRONMENT_FOUND', 'REGISTER_SUCCESS']);
const warningStatuses = new Set(['ENVIRONMENT_NOT_FOUND', 'CMS_NOT_FOUND']);

loadEnvironments();
form.addEventListener('submit', async (event) => {
    event.preventDefault(); if (!form.reportValidity()) return;
    const request = currentRequest(); setBusy(true, 'Consultando CMS y queryESb…'); registerPanel.classList.add('hidden');
    try { const result = await callCmsApi('/api/v1/cms/validate', request); validatedRequest = result.canRegister ? request : null; renderCmsResult(result); }
    catch (error) { validatedRequest = null; renderCmsError(error); } finally { setBusy(false, ''); }
});
registerButton.addEventListener('click', async () => {
    if (!validatedRequest || !sameRequest(validatedRequest, currentRequest())) { requestState.textContent = 'Los datos cambiaron. Vuelva a consultar antes de registrar.'; registerPanel.classList.add('hidden'); return; }
    if (!window.confirm(`¿Registrar la AccessID ${validatedRequest.accessId} en ${validatedRequest.environment}?`)) return;
    setBusy(true, 'Revalidando y registrando…'); registerButton.disabled = true;
    try { const result = await callCmsApi('/api/v1/cms/register', validatedRequest); validatedRequest = null; renderCmsResult(result); }
    catch (error) { validatedRequest = null; renderCmsError(error); } finally { registerButton.disabled = false; setBusy(false, ''); }
});
accessIdInput.addEventListener('input', invalidatePreviousValidation);
environmentSelect.addEventListener('change', invalidatePreviousValidation);

async function loadEnvironments() {
    try {
        const response = await fetch('/api/v1/cms/environments', { headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const environments = await response.json(); environmentSelect.replaceChildren();
        environments.forEach((environment) => { const option = document.createElement('option'); option.value = environment.label; option.textContent = environment.label; environmentSelect.append(option); });
        environmentSelect.disabled = false;
    } catch (error) { environmentSelect.innerHTML = '<option value="">No disponible</option>'; requestState.textContent = `No fue posible cargar los ambientes: ${error.message}`; }
}
async function callCmsApi(url, request) {
    const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(request) });
    const result = await response.json().catch(() => { throw new Error(`El servidor devolvió HTTP ${response.status} sin una respuesta válida.`); });
    if (!response.ok && !result.status) throw new Error(result.message || `Error HTTP ${response.status}`); return result;
}
function renderCmsResult(result) {
    resultCard.classList.remove('hidden'); statusBadge.textContent = statusLabels[result.status] || result.status; statusBadge.className = `status-badge ${statusClass(result.status)}`; resultMessage.textContent = result.message || 'Sin detalle.';
    setText('#detail-access-id', result.accessId); setText('#detail-date', result.interactionDate); setText('#detail-pid', result.pid); setText('#detail-exec-id', result.execId); setText('#detail-external-id', result.externalId); setText('#detail-found-environment', result.foundEnvironment); setText('#detail-correlation-id', result.correlationId);
    registerPanel.classList.toggle('hidden', !result.canRegister); if (result.canRegister) registerButton.textContent = `Registrar en ${result.requestedEnvironment}`;
}
function renderCmsError(error) { renderCmsResult({ status: 'QUERY_ERROR', message: `No fue posible comunicarse con OCP Tools: ${error.message}`, accessId: accessIdInput.value.trim(), canRegister: false }); }
function setBusy(busy, message) { validateButton.disabled = busy; accessIdInput.disabled = busy; environmentSelect.disabled = busy; requestState.textContent = message; }
function currentRequest() { return { accessId: accessIdInput.value.trim(), environment: environmentSelect.value }; }
function sameRequest(first, second) { return first.accessId === second.accessId && first.environment === second.environment; }
function invalidatePreviousValidation() { if (validatedRequest && !sameRequest(validatedRequest, currentRequest())) { validatedRequest = null; registerPanel.classList.add('hidden'); } }
function statusClass(status) { if (successStatuses.has(status)) return 'success'; if (warningStatuses.has(status)) return 'warning'; return 'error'; }
function setText(selector, value) { document.querySelector(selector).textContent = value || '—'; }

// Utilidad TCP: abre únicamente la conexión y la cierra después del handshake.
const tcpForm = document.querySelector('#tcp-check-form');
const tcpIpInput = document.querySelector('#tcp-ip');
const tcpPortInput = document.querySelector('#tcp-port');
const tcpCheckButton = document.querySelector('#tcp-check-button');
const tcpRequestState = document.querySelector('#tcp-request-state');
const tcpResultCard = document.querySelector('#tcp-result-card');
const tcpStatusBadge = document.querySelector('#tcp-status-badge');
const tcpResultMessage = document.querySelector('#tcp-result-message');
const tcpStatusLabels = {
    CONNECTED: 'CONEXIÓN EXITOSA', CONNECTION_REFUSED: 'CONEXIÓN RECHAZADA',
    TIMEOUT: 'TIMEOUT', NO_ROUTE: 'SIN RUTA', ERROR: 'ERROR DE CONEXIÓN',
    INVALID_REQUEST: 'SOLICITUD INVÁLIDA'
};

tcpForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!tcpForm.reportValidity()) return;
    setTcpBusy(true, 'Validando desde el pod…');
    try {
        const response = await fetch('/api/v1/tcp-check', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ ip: tcpIpInput.value.trim(), port: Number(tcpPortInput.value) })
        });
        const result = await response.json().catch(() => {
            throw new Error(`El servidor devolvió HTTP ${response.status} sin una respuesta válida.`);
        });
        if (!response.ok && !result.status) throw new Error(result.message || `Error HTTP ${response.status}`);
        renderTcpResult(result);
    } catch (error) {
        renderTcpResult({ status: 'ERROR', message: `No fue posible comunicarse con OCP Tools: ${error.message}`, ip: tcpIpInput.value.trim(), port: tcpPortInput.value });
    } finally {
        setTcpBusy(false, '');
    }
});

function renderTcpResult(result) {
    tcpResultCard.classList.remove('hidden');
    tcpStatusBadge.textContent = tcpStatusLabels[result.status] || result.status;
    tcpStatusBadge.className = `status-badge ${tcpStatusClass(result.status)}`;
    tcpResultMessage.textContent = result.message || 'Sin detalle.';
    setText('#tcp-detail-ip', result.ip);
    setText('#tcp-detail-port', result.port);
    setText('#tcp-detail-duration', Number.isFinite(result.durationMs) ? `${result.durationMs} ms` : null);
    setText('#tcp-detail-timeout', Number.isFinite(result.timeoutSeconds) ? `${result.timeoutSeconds} segundos` : null);
    setText('#tcp-detail-date', result.checkedAt ? new Date(result.checkedAt).toLocaleString('es-PE') : null);
    setText('#tcp-detail-correlation-id', result.correlationId);
}

function setTcpBusy(busy, message) {
    tcpIpInput.disabled = busy;
    tcpPortInput.disabled = busy;
    tcpCheckButton.disabled = busy;
    tcpRequestState.textContent = message;
}

function tcpStatusClass(status) {
    if (status === 'CONNECTED') return 'success';
    if (status === 'TIMEOUT') return 'warning';
    return 'error';
}
