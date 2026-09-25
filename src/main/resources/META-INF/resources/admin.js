const adminState = {
    snapshot: null,
    tab: 'cases',
    editingCase: null,
    caseMode: 'create',
    cloneSourceId: null,
    selectedElements: new Set(),
    selectedDeployments: new Set()
};
const adminMessage = document.querySelector('#admin-message');
const adminList = document.querySelector('#admin-list');
const adminSearch = document.querySelector('#admin-search');
const caseDialog = document.querySelector('#case-dialog');
const elementDialog = document.querySelector('#element-dialog');
const flowDialog = document.querySelector('#flow-dialog');
const flowFrame = document.querySelector('#flow-frame');
let flowTrigger = null;

document.querySelectorAll('[data-admin-tab]').forEach((button) => button.addEventListener('click', () => {
    adminState.tab = button.dataset.adminTab;
    document.querySelectorAll('[data-admin-tab]').forEach((item) => item.classList.toggle('active', item === button));
    document.querySelector('#new-case').classList.toggle('hidden', adminState.tab !== 'cases');
    document.querySelector('#new-element').classList.toggle('hidden', adminState.tab !== 'elements');
    renderAdmin();
}));
document.querySelector('#new-case').addEventListener('click', () => openCaseDialog());
document.querySelector('#new-element').addEventListener('click', () => openElementDialog());
document.querySelector('#case-form').addEventListener('submit', saveCase);
document.querySelector('#element-form').addEventListener('submit', saveElement);
document.querySelector('#element-search').addEventListener('input', renderElementChoices);
document.querySelector('#deployment-search').addEventListener('input', renderDeploymentChoices);
document.querySelector('#deployment-namespace').addEventListener('change', renderDeploymentChoices);
document.querySelector('#case-elements').addEventListener('change', (event) => {
    const checkbox = event.target.closest('input[type="checkbox"]');
    if (!checkbox) return;
    updateSelection(adminState.selectedElements, checkbox);
    renderElementChoices();
});
document.querySelector('#case-deployments').addEventListener('change', (event) => {
    const checkbox = event.target.closest('input[type="checkbox"]');
    if (!checkbox) return;
    updateSelection(adminState.selectedDeployments, checkbox);
    renderDeploymentChoices();
});
adminSearch.addEventListener('input', renderAdmin);
document.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', () =>
    document.querySelector(`#${button.dataset.close}`).close()));
adminList.addEventListener('click', handleListAction);
window.addEventListener('message', handleFlowMessage);
flowDialog.addEventListener('close', () => {
    flowFrame.src = 'about:blank';
    flowTrigger?.focus();
    flowTrigger = null;
});

loadAdmin();

async function loadAdmin() {
    adminMessage.className = 'map-message';
    adminMessage.textContent = 'Cargando catálogo…';
    try {
        const response = await fetch('/api/v1/ocp-map/admin/snapshot', { headers: { Accept: 'application/json' } });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        adminState.snapshot = body;
        renderSummary();
        renderAdmin();
        adminMessage.className = 'map-message success';
        adminMessage.textContent = `Catálogo actualizado ${relativeTime(body.generatedAt)}. Los cambios se guardan en PostgreSQL.`;
    } catch (error) {
        adminState.snapshot = null;
        adminMessage.className = 'map-message error';
        adminMessage.textContent = error.message;
        adminList.innerHTML = emptyState('Administración no disponible');
    }
}

function renderSummary() {
    const snapshot = adminState.snapshot;
    setText('#summary-cases', snapshot.testCases.filter((item) => !item.archivedAt).length);
    setText('#summary-elements', snapshot.elements.filter((item) => !item.archivedAt).length);
    setText('#summary-deployments', snapshot.deployments.length);
    setText('#summary-updated', relativeTime(snapshot.generatedAt));
}

function renderAdmin() {
    if (!adminState.snapshot) return;
    const query = adminSearch.value;
    let html;
    if (adminState.tab === 'cases') {
        html = adminState.snapshot.testCases.filter((item) => !item.archivedAt && matches(item.code + ' ' + item.name, query)).map(caseRow).join('');
    } else if (adminState.tab === 'elements') {
        html = adminState.snapshot.elements.filter((item) => !item.archivedAt && matches(item.name + ' ' + (item.description || ''), query)).map(elementRow).join('');
    } else {
        const cases = adminState.snapshot.testCases.filter((item) => item.archivedAt && matches(item.code + ' ' + item.name, query)).map((item) => archivedRow('case', item)).join('');
        const elements = adminState.snapshot.elements.filter((item) => item.archivedAt && matches(item.name, query)).map((item) => archivedRow('element', item)).join('');
        html = cases + elements;
    }
    adminList.innerHTML = html || emptyState('No hay registros que coincidan con la búsqueda');
}

function caseRow(testCase) {
    const inactive = testCase.deployments.filter((item) => !item.active).length;
    return `<article class="admin-row" data-case-row="${testCase.id}"><div><span class="case-code">${escapeHtml(testCase.code)}</span><h3>${escapeHtml(testCase.name)}</h3>
        <p>${testCase.elements.length} elementos · ${testCase.deployments.length} microservicios${inactive ? ` · ${inactive} sin observación actual` : ''} · <span data-flow-state>${testCase.hasFlow ? 'diagrama configurado' : 'sin diagrama'}</span> · actualizado ${relativeTime(testCase.updatedAt)}</p></div>
        <div class="row-actions"><button class="small-button primary" data-action="edit-flow" data-id="${testCase.id}">${testCase.hasFlow ? 'Editar flujo' : 'Crear flujo'}</button><button class="small-button" data-action="clone-case" data-id="${testCase.id}">Clonar</button><button class="small-button" data-action="edit-case" data-id="${testCase.id}">Editar</button><button class="small-button danger" data-action="archive-case" data-id="${testCase.id}">Archivar</button></div></article>`;
}

function elementRow(element) {
    return `<article class="admin-row"><div><h3>${escapeHtml(element.name)}</h3><p>${escapeHtml(element.description || 'Sin descripción')} · ${element.activeTestCaseCount} casos activos · actualizado ${relativeTime(element.updatedAt)}</p></div>
        <div class="row-actions"><button class="small-button" data-action="edit-element" data-id="${element.id}">Editar</button><button class="small-button danger" data-action="archive-element" data-id="${element.id}">Archivar</button></div></article>`;
}

function archivedRow(type, item) {
    const label = type === 'case' ? `${item.code} · ${item.name}` : item.name;
    return `<article class="admin-row"><div><span class="eyebrow">${type === 'case' ? 'Caso de prueba' : 'Elemento'}</span><h3>${escapeHtml(label)}</h3><p>Archivado ${relativeTime(item.archivedAt)}</p></div>
        <div class="row-actions"><button class="small-button" data-action="restore-${type}" data-id="${item.id}">Restaurar</button></div></article>`;
}

async function handleListAction(event) {
    const button = event.target.closest('[data-action]');
    if (!button) return;
    const id = Number(button.dataset.id);
    if (button.dataset.action === 'edit-case') return openCaseDialog(adminState.snapshot.testCases.find((item) => item.id === id));
    if (button.dataset.action === 'clone-case') return openCaseDialog(adminState.snapshot.testCases.find((item) => item.id === id), true);
    if (button.dataset.action === 'edit-flow') {
        const testCase = adminState.snapshot.testCases.find((item) => item.id === id);
        if (testCase) openFlowDialog(testCase, button);
        return;
    }
    if (button.dataset.action === 'edit-element') return openElementDialog(adminState.snapshot.elements.find((item) => item.id === id));
    const [verb, type] = button.dataset.action.split('-');
    const collection = type === 'case' ? 'test-cases' : 'elements';
    const endpointVerb = verb === 'archive' ? 'archive' : 'restore';
    if (verb === 'archive' && !window.confirm('El registro pasará a la vista de archivados. ¿Continuar?')) return;
    await mutate(`/api/v1/ocp-map/admin/${collection}/${id}/${endpointVerb}`, { method: 'POST' });
}

function openFlowDialog(testCase, trigger) {
    flowTrigger = trigger;
    flowFrame.title = `Editar flujo ${testCase.code} · ${testCase.name}`;
    flowFrame.src = `/admin/flow?caseId=${encodeURIComponent(testCase.id)}&embedded=1`;
    flowDialog.showModal();
}

function handleFlowMessage(event) {
    if (event.origin !== window.location.origin) return;
    if (event.data?.type === 'ocp-flow-close') {
        flowDialog.close();
        return;
    }
    if (event.data?.type !== 'ocp-flow-saved') return;
    const id = Number(event.data.caseId);
    const testCase = adminState.snapshot?.testCases.find((item) => item.id === id);
    if (testCase) testCase.hasFlow = true;
    const row = adminList.querySelector(`[data-case-row="${id}"]`);
    const button = row?.querySelector('[data-action="edit-flow"]');
    if (button) button.textContent = 'Editar flujo';
    const state = row?.querySelector('[data-flow-state]');
    if (state) state.textContent = 'diagrama configurado';
}

function openCaseDialog(testCase = null, clone = false) {
    adminState.caseMode = clone ? 'clone' : (testCase ? 'edit' : 'create');
    adminState.editingCase = clone ? null : testCase;
    adminState.cloneSourceId = clone ? testCase.id : null;
    adminState.selectedElements = new Set((testCase?.elements || []).map((item) => item.id));
    adminState.selectedDeployments = new Set((testCase?.deployments || []).map((item) => item.id));
    document.querySelector('#case-dialog-title').textContent = clone ? `Clonar ${testCase.code}` : (testCase ? 'Editar caso de prueba' : 'Nuevo caso de prueba');
    document.querySelector('#case-id').value = clone ? '' : (testCase?.id || '');
    document.querySelector('#case-clone-source-id').value = clone ? testCase.id : '';
    const lastCode = latestCaseCode();
    document.querySelector('#case-code').value = clone || !testCase ? lastCode : testCase.code;
    document.querySelector('#latest-code-hint').textContent = clone || !testCase
        ? (lastCode ? `Último código guardado: ${lastCode}. Modifíquelo manualmente antes de guardar.` : 'No hay códigos previos guardados.')
        : '';
    document.querySelector('#case-name').value = testCase?.name || '';
    document.querySelector('#case-description').value = testCase?.description || '';
    document.querySelector('#case-annotation').value = testCase?.annotation || '';
    document.querySelector('#case-order').value = testCase?.displayOrder ?? 0;
    document.querySelector('#case-metadata').value = (testCase?.metadata || []).join('\n');
    document.querySelector('#case-error').textContent = '';
    document.querySelector('#element-search').value = '';
    renderElementChoices();
    fillDeploymentNamespaces();
    document.querySelector('#deployment-search').value = '';
    document.querySelector('#deployment-namespace').value = '';
    renderDeploymentChoices();
    caseDialog.showModal();
}

function renderElementChoices() {
    const query = document.querySelector('#element-search').value;
    const elements = adminState.snapshot.elements.filter((item) =>
        !item.archivedAt || adminState.selectedElements.has(item.id));
    renderChoiceCollection(
        document.querySelector('#case-elements'),
        elements,
        adminState.selectedElements,
        renderElementChoice,
        'No hay elementos para el filtro',
        (item) => matches(item.name + ' ' + (item.description || ''), query)
    );
}

function renderElementChoice(item) {
    const selected = adminState.selectedElements.has(item.id);
    const disabled = Boolean(item.archivedAt) && !selected;
    return `<label class="${item.archivedAt ? 'inactive' : ''}"><input type="checkbox" value="${item.id}" ${selected ? 'checked' : ''} ${disabled ? 'disabled' : ''}><span>${escapeHtml(item.name)}${item.archivedAt ? '<small>Archivado</small>' : ''}</span></label>`;
}

function fillDeploymentNamespaces() {
    const select = document.querySelector('#deployment-namespace');
    select.innerHTML = '<option value="">Todos los namespaces</option>' + adminState.snapshot.scannedNamespaces.map((namespace) =>
        `<option value="${escapeHtml(namespace)}">${escapeHtml(namespace)}</option>`).join('');
}

function renderDeploymentChoices() {
    const query = document.querySelector('#deployment-search').value;
    const namespace = document.querySelector('#deployment-namespace').value;
    const deployments = adminState.snapshot.deployments;
    renderChoiceCollection(
        document.querySelector('#case-deployments'),
        deployments,
        adminState.selectedDeployments,
        renderDeploymentChoice,
        'No hay deployments para el filtro',
        (item) => (!namespace || item.namespace === namespace) && matches(item.name + ' ' + item.namespace, query)
    );
}

function renderDeploymentChoice(item) {
    return `<label class="${item.active ? '' : 'inactive'}"><input type="checkbox" value="${item.id}" ${adminState.selectedDeployments.has(item.id) ? 'checked' : ''}><span>${escapeHtml(item.name)}<small>${escapeHtml(item.namespace)} · ${item.active ? item.state : 'sin observación actual'}</small></span></label>`;
}

function renderChoiceCollection(container, items, selectedIds, renderChoice, emptyText, availableFilter = () => true) {
    if (!items.length) {
        container.innerHTML = emptyState(emptyText);
        return;
    }

    const selected = items.filter((item) => selectedIds.has(item.id));
    const available = items.filter((item) => !selectedIds.has(item.id) && availableFilter(item));

    container.innerHTML = [
        choiceGroup('Seleccionados', selected, renderChoice, 'No hay selecciones en este filtro.', true),
        choiceGroup('Disponibles', available, renderChoice, 'No hay opciones disponibles en este filtro.', false)
    ].join('');
}

function choiceGroup(title, items, renderChoice, emptyText, selected) {
    const content = items.length
        ? `<div class="choice-grid">${items.map(renderChoice).join('')}</div>`
        : `<p class="choice-group-empty">${escapeHtml(emptyText)}</p>`;
    return `<section class="choice-group ${selected ? 'choice-group-selected' : ''}" aria-label="${title}"><h4>${title} <span>(${items.length})</span></h4>${content}</section>`;
}

function choiceSummary(selected, available) {
    return `<div class="choice-summary"><span data-choice-count="selected">Seleccionados (${selected})</span><span data-choice-count="available">Disponibles (${available})</span></div>`;
}

function updateSelection(selection, checkbox) {
    const id = Number(checkbox.value);
    if (checkbox.checked) selection.add(id);
    else selection.delete(id);
}

function updateFlatChoiceSummary(container) {
    const choices = [...container.querySelectorAll('input[type="checkbox"]')];
    const selected = choices.filter((choice) => choice.checked).length;
    const selectedCounter = container.querySelector('[data-choice-count="selected"]');
    const availableCounter = container.querySelector('[data-choice-count="available"]');
    if (selectedCounter) selectedCounter.textContent = `Seleccionados (${selected})`;
    if (availableCounter) availableCounter.textContent = `Disponibles (${choices.length - selected})`;
}

async function saveCase(event) {
    event.preventDefault();
    if (!event.target.reportValidity()) return;
    const id = document.querySelector('#case-id').value;
    const cloneSourceId = document.querySelector('#case-clone-source-id').value;
    const deploymentIds = [...adminState.selectedDeployments];
    const inactive = adminState.snapshot.deployments.filter((item) => deploymentIds.includes(item.id) && !item.active);
    if (inactive.length && !window.confirm(`Se asociarán ${inactive.length} deployments sin observación actual. ¿Guardar de todas formas?`)) return;
    const payload = {
        code: document.querySelector('#case-code').value.trim(), name: document.querySelector('#case-name').value.trim(),
        description: document.querySelector('#case-description').value.trim() || null,
        annotation: document.querySelector('#case-annotation').value.trim() || null,
        displayOrder: Number(document.querySelector('#case-order').value || 0),
        metadata: document.querySelector('#case-metadata').value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean),
        elementIds: [...adminState.selectedElements], deploymentIds
    };
    try {
        const url = adminState.caseMode === 'clone'
            ? `/api/v1/ocp-map/admin/test-cases/${cloneSourceId}/clone`
            : `/api/v1/ocp-map/admin/test-cases${id ? `/${id}` : ''}`;
        await mutate(url, { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) }, false);
        caseDialog.close(); await loadAdmin();
    } catch (error) { document.querySelector('#case-error').textContent = error.message; }
}

function openElementDialog(element = null) {
    document.querySelector('#element-dialog-title').textContent = element ? 'Editar elemento' : 'Nuevo elemento';
    document.querySelector('#element-id').value = element?.id || '';
    document.querySelector('#element-name').value = element?.name || '';
    document.querySelector('#element-description').value = element?.description || '';
    document.querySelector('#element-order').value = element?.displayOrder ?? 0;
    document.querySelector('#element-error').textContent = '';
    elementDialog.showModal();
}

async function saveElement(event) {
    event.preventDefault(); if (!event.target.reportValidity()) return;
    const id = document.querySelector('#element-id').value;
    const payload = { name: document.querySelector('#element-name').value.trim(), description: document.querySelector('#element-description').value.trim() || null, displayOrder: Number(document.querySelector('#element-order').value || 0) };
    try {
        await mutate(`/api/v1/ocp-map/admin/elements${id ? `/${id}` : ''}`, { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) }, false);
        elementDialog.close(); await loadAdmin();
    } catch (error) { document.querySelector('#element-error').textContent = error.message; }
}

async function mutate(url, options, reload = true) {
    const response = await fetch(url, { ...options, headers: { 'Content-Type': 'application/json', Accept: 'application/json' } });
    const body = response.status === 204 ? {} : await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    if (reload) await loadAdmin();
    return body;
}

function matches(value, query) { return normalize(value).includes(normalize(query).trim()); }
function latestCaseCode() {
    const cases = adminState.snapshot?.testCases || [];
    if (!cases.length) return '';
    return [...cases].sort((left, right) => {
        const created = Date.parse(right.createdAt || 0) - Date.parse(left.createdAt || 0);
        return created || right.id - left.id;
    })[0].code || '';
}
function normalize(value) { return String(value || '').toLocaleLowerCase('es').normalize('NFD').replace(/\p{Diacritic}/gu, ''); }
function relativeTime(value) {
    if (!value) return 'nunca';
    const seconds = Math.round((Date.parse(value) - Date.now()) / 1000); const formatter = new Intl.RelativeTimeFormat('es', { numeric: 'auto' });
    if (Math.abs(seconds) < 60) return formatter.format(seconds, 'second'); const minutes = Math.round(seconds / 60);
    if (Math.abs(minutes) < 60) return formatter.format(minutes, 'minute'); const hours = Math.round(minutes / 60);
    if (Math.abs(hours) < 48) return formatter.format(hours, 'hour'); return formatter.format(Math.round(hours / 24), 'day');
}
function setText(selector, value) { document.querySelector(selector).textContent = value; }
function emptyState(text) { return `<div class="empty-state">${escapeHtml(text)}</div>`; }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char])); }
