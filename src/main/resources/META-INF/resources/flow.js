const SVG_NS = 'http://www.w3.org/2000/svg';
const NODE_WIDTH = 220;
const NODE_HEIGHT = 118;
const isEditor = document.body.dataset.flowMode === 'editor';
const queryParameters = new URLSearchParams(window.location.search);
const caseId = Number(queryParameters.get('caseId'));
const isEmbedded = queryParameters.get('embedded') === '1';
const stage = document.querySelector('#flow-stage');
const edgesLayer = document.querySelector('#flow-edges');
const pulsesLayer = document.querySelector('#flow-pulses');
const nodesLayer = document.querySelector('#flow-nodes');
const tempEdge = document.querySelector('#flow-temp-edge');
const statusText = document.querySelector('#flow-status-text');
const statusDot = document.querySelector('#flow-status-dot');
const exitButton = document.querySelector('#flow-exit');
const zoomLevel = document.querySelector('#zoom-level');
const minimap = document.querySelector('#flow-minimap');
const minimapEdges = document.querySelector('#minimap-edges');
const minimapNodes = document.querySelector('#minimap-nodes');
const minimapViewport = document.querySelector('#minimap-viewport');
const flowSidebar = document.querySelector('.flow-sidebar');
const sidebarToggle = document.querySelector('#toggle-flow-sidebar');

let model = { schemaVersion: 2, nodes: [], edges: [] };
let savedModel = structuredClone(model);
let selected = null;
let drag = null;
let connection = null;
let running = false;
let runToken = 0;
let sequence = 0;
let currentCase = null;
let camera = { x: 0, y: 0, width: 1400, height: 800 };
let pan = null;
let minimapTransform = null;

document.querySelector('#play-flow').addEventListener('click', playFlow);
document.querySelector('#reset-flow').addEventListener('click', resetFlow);
document.querySelector('#zoom-out').addEventListener('click', () => zoomCamera(1.2));
document.querySelector('#zoom-in').addEventListener('click', () => zoomCamera(1 / 1.2));
document.querySelector('#fit-flow').addEventListener('click', fitFlow);
stage.addEventListener('wheel', handleWheel, { passive: false });
stage.addEventListener('pointerdown', handleStagePointerDown);
stage.addEventListener('pointermove', handlePointerMove);
stage.addEventListener('pointerup', handlePointerUp);
stage.addEventListener('pointercancel', cancelPointerInteraction);
minimap.addEventListener('click', handleMinimapClick);
if (isEmbedded) {
    document.body.classList.add('embedded');
    exitButton.textContent = '× Cerrar';
    exitButton.addEventListener('click', (event) => {
        event.preventDefault();
        window.parent.postMessage({ type: 'ocp-flow-close' }, window.location.origin);
    });
}
if (isEditor) configureEditor();
loadFlowPage();

async function loadFlowPage() {
    if (!Number.isInteger(caseId) || caseId <= 0) {
        return fail('No se indicó un caso de prueba válido en la dirección.');
    }
    try {
        if (isEditor) {
            const snapshot = await fetchJson('/api/v1/ocp-map/admin/snapshot');
            currentCase = snapshot.testCases.find((item) => item.id === caseId && !item.archivedAt);
            if (!currentCase) throw new Error('Caso de prueba no encontrado o archivado.');
            updateSuggestedOptions();
        }
        const response = await fetch(`/api/v1/ocp-map/test-cases/${encodeURIComponent(caseId)}/flow`, {
            headers: { Accept: 'application/json' }
        });
        if (response.ok) {
            const detail = await response.json();
            currentCase ||= detail;
            model = normalizeModel(detail.flow);
        } else if (isEditor && response.status === 404) {
            model = { schemaVersion: 2, nodes: [], edges: [] };
        } else {
            const body = await response.json().catch(() => ({}));
            throw new Error(body.message || `HTTP ${response.status}`);
        }
        savedModel = structuredClone(model);
        document.querySelector('#flow-title').textContent = `${currentCase.code} · ${currentCase.name}`;
        document.title = `${isEditor ? 'Editar' : 'Flujo'} ${currentCase.code} · OCP Tools`;
        render();
        syncProperties();
        fitFlow();
        setStatus(isEditor
            ? (model.nodes.length ? 'Flujo cargado. Seleccione un elemento o una conexión para editarlo.' : 'Aún no hay flujo. Agregue una sugerencia o un elemento libre.')
            : 'Flujo cargado. La animación comenzará automáticamente.', 'done');
        if (!isEditor) window.setTimeout(playFlow, 450);
    } catch (error) {
        fail(error.message);
    }
}

function configureEditor() {
    sidebarToggle.addEventListener('click', toggleSidebar);
    document.querySelector('#add-suggested').addEventListener('click', () => {
        const id = Number(document.querySelector('#suggested-element').value);
        const element = currentCase?.elements.find((item) => item.id === id);
        if (!element) return setStatus('Seleccione un elemento asociado al caso.', 'error');
        addNode(element.name, element.id);
    });
    document.querySelector('#add-free').addEventListener('click', () => {
        const input = document.querySelector('#free-element-name');
        if (!input.value.trim()) return setStatus('Escriba el nombre del elemento libre.', 'error');
        addNode(input.value.trim(), null);
        input.value = '';
    });
    document.querySelector('#free-element-name').addEventListener('keydown', (event) => {
        if (event.key === 'Enter') { event.preventDefault(); document.querySelector('#add-free').click(); }
    });
    document.querySelector('#save-flow').addEventListener('click', saveFlow);
    document.querySelector('#import-flow').addEventListener('click', () => document.querySelector('#import-flow-file').click());
    document.querySelector('#import-flow-file').addEventListener('change', importFlowJson);
    document.querySelector('#export-flow').addEventListener('click', exportFlowJson);
    document.querySelector('#node-name').addEventListener('input', (event) => {
        const node = nodeById(selected?.id);
        if (node) { node.name = event.target.value; render(); }
    });
    document.querySelector('#node-detail').addEventListener('input', (event) => {
        const node = nodeById(selected?.id);
        if (node) { node.detail = event.target.value; render(); }
    });
    document.querySelector('#edge-trigger').addEventListener('change', (event) => {
        const edge = edgeById(selected?.id);
        if (edge) {
            edge.trigger = event.target.value;
            edge.responseLabel = edge.trigger === 'QUERY' ? (edge.responseLabel || '') : null;
            render(); syncProperties();
        }
    });
    document.querySelector('#edge-label').addEventListener('input', (event) => {
        const edge = edgeById(selected?.id);
        if (edge) { edge.label = event.target.value; render(); }
    });
    document.querySelector('#edge-response').addEventListener('input', (event) => {
        const edge = edgeById(selected?.id);
        if (edge) { edge.responseLabel = event.target.value; render(); }
    });
    document.querySelector('#delete-node').addEventListener('click', removeSelected);
    document.querySelector('#delete-edge').addEventListener('click', removeSelected);
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Delete' && !running && selected && !event.target.matches('input,textarea,select')) removeSelected();
    });
}

function normalizeModel(value) {
    const sourceVersion = Number(value?.schemaVersion) === 1 ? 1 : 2;
    const nodes = Array.isArray(value?.nodes) ? value.nodes.map((node) => ({
            id: String(node.id), catalogElementId: node.catalogElementId ?? null,
            name: String(node.name || ''), x: Number(node.x) || 0, y: Number(node.y) || 0,
            detail: String(node.detail || ''),
            waitMode: String(node.waitMode || 'ANY').toUpperCase()
        })) : [];
    const edges = Array.isArray(value?.edges) ? value.edges.map((edge, index) => ({
            id: String(edge.id), from: String(edge.from), to: String(edge.to),
            trigger: sourceVersion === 1 ? 'CONTINUE'
                : (String(edge.trigger || 'CONTINUE').toUpperCase() === 'QUERY' ? 'QUERY' : 'CONTINUE'),
            order: boundedInteger(edge.order, 1, 1000, 1),
            repetitions: 0,
            label: edge.label || null,
            responseLabel: sourceVersion === 2 && String(edge.trigger || '').toUpperCase() === 'QUERY'
                ? (edge.responseLabel || null) : null,
            _index: index
        })) : [];
    const bySource = new Map();
    edges.forEach((edge) => {
        if (!bySource.has(edge.from)) bySource.set(edge.from, []);
        bySource.get(edge.from).push(edge);
    });
    bySource.forEach((outgoing) => outgoing
        .sort((first, second) => first.order - second.order || first._index - second._index)
        .forEach((edge, index) => { edge.order = index + 1; delete edge._index; }));
    return { schemaVersion: 2, nodes, edges };
}

function render() {
    edgesLayer.replaceChildren();
    nodesLayer.replaceChildren();
    for (const edge of model.edges) renderEdge(edge);
    for (const node of model.nodes) renderNode(node);
    if (isEditor) updateSuggestedOptions();
    updateMinimap();
}

function renderNode(node) {
    const group = svgElement('g', {
        class: `flow-node${selected?.type === 'node' && selected.id === node.id ? ' selected' : ''}`,
        transform: `translate(${node.x},${node.y})`, 'data-node': node.id
    });
    const title = svgElement('title');
    title.textContent = `${node.name}${node.detail ? ` — ${node.detail}` : ''}`;
    group.append(title, svgElement('rect', { width: NODE_WIDTH, height: NODE_HEIGHT, rx: 17 }));
    appendWrappedText(group, node.name, 'name', 31, 18, 2, 27);
    appendWrappedText(group, node.detail || 'Sin detalle', 'sub', 82, 15, 2, 34);
    if (isEditor) group.append(svgElement('circle', { cx: NODE_WIDTH, cy: NODE_HEIGHT / 2, r: 9, class: 'flow-port' }));
    if (isEditor) group.addEventListener('pointerdown', (event) => handleNodePointerDown(event, node.id));
    nodesLayer.append(group);
}

function renderEdge(edge) {
    const query = edge.trigger === 'QUERY';
    const selectedClass = selected?.type === 'edge' && selected.id === edge.id ? ' selected' : '';
    const path = edgePath(edge, query ? -12 : 0);
    const group = svgElement('g', { class: 'flow-edge-group', 'data-edge': edge.id });
    const hit = svgElement('path', { d: path, class: 'flow-edge-hit' });
    const line = svgElement('path', {
        d: path,
        class: `flow-edge ${edge.trigger.toLowerCase()}${selectedClass}`,
        'data-phase': 'out'
    });
    group.append(hit, line);
    if (query) {
        const responseEdge = { from: edge.to, to: edge.from };
        const responsePath = edgePath(responseEdge, -12);
        group.append(
            svgElement('path', { d: responsePath, class: 'flow-edge-hit' }),
            svgElement('path', { d: responsePath, class: `flow-edge response${selectedClass}`, 'data-phase': 'return' })
        );
    }
    const points = edgePoints(edge, query ? -12 : 0);
    if (points) {
        group.append(edgeLabel(points, edge.label || (query ? 'Consultar' : 'Continuar'), edge.trigger.toLowerCase(), -9));
        if (query) {
            const responsePoints = edgePoints({ from: edge.to, to: edge.from }, -12);
            group.append(edgeLabel(responsePoints, edge.responseLabel || 'Respuesta', 'response', 16));
        }
        if (isEditor) {
            group.append(svgElement('circle', {
                cx: (points.sx + points.tx) / 2,
                cy: (points.sy + points.ty) / 2 + 16,
                r: 10,
                class: 'flow-edge-handle'
            }));
            const editMark = svgElement('text', {
                x: (points.sx + points.tx) / 2,
                y: (points.sy + points.ty) / 2 + 20,
                'text-anchor': 'middle',
                class: 'flow-edge-edit-mark'
            });
            editMark.textContent = '✎';
            group.append(editMark);
        }
    }
    if (isEditor) {
        group.addEventListener('click', (event) => {
            if (running) return;
            event.stopPropagation();
            select({ type: 'edge', id: edge.id });
        });
    }
    edgesLayer.append(group);
}

function edgeLabel(points, text, className, offsetY) {
    const label = svgElement('text', {
        x: (points.sx + points.tx) / 2,
        y: (points.sy + points.ty) / 2 + offsetY,
        'text-anchor': 'middle',
        class: `flow-edge-label ${className}`
    });
    label.textContent = truncate(text, 54);
    return label;
}

function appendWrappedText(group, value, className, startY, lineHeight, maxLines, maxCharacters) {
    const text = svgElement('text', { x: NODE_WIDTH / 2, y: startY, 'text-anchor': 'middle', class: className });
    const lines = wrapText(value, maxCharacters, maxLines);
    lines.forEach((line, index) => {
        const tspan = svgElement('tspan', { x: NODE_WIDTH / 2, dy: index === 0 ? 0 : lineHeight });
        tspan.textContent = line;
        text.append(tspan);
    });
    group.append(text);
}

function wrapText(value, maxCharacters, maxLines) {
    const words = String(value || '').trim().split(/\s+/).filter(Boolean);
    if (!words.length) return [''];
    const lines = [];
    let current = '';
    while (words.length && lines.length < maxLines) {
        const word = words.shift();
        const candidate = current ? `${current} ${word}` : word;
        if (candidate.length <= maxCharacters || !current) current = candidate;
        else { lines.push(current); current = word; }
    }
    if (current && lines.length < maxLines) lines.push(current);
    if (words.length) lines[lines.length - 1] = truncate(`${lines.at(-1)} ${words.join(' ')}`, maxCharacters);
    return lines.map((line) => truncate(line, maxCharacters));
}

function handleStagePointerDown(event) {
    if (event.target !== stage || event.button !== 0) return;
    const rect = stage.getBoundingClientRect();
    pan = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        x: camera.x,
        y: camera.y,
        scaleX: camera.width / Math.max(1, rect.width),
        scaleY: camera.height / Math.max(1, rect.height)
    };
    stage.setPointerCapture(event.pointerId);
    stage.classList.add('panning');
    if (isEditor && !running) select(null);
}

function handleNodePointerDown(event, id) {
    if (running) return;
    event.stopPropagation();
    const node = nodeById(id);
    const point = clientToSvg(event);
    select({ type: 'node', id });
    if (event.target.classList.contains('flow-port')) {
        connection = { from: id };
        tempEdge.hidden = false;
        tempEdge.setAttribute('d', `M ${node.x + NODE_WIDTH} ${node.y + NODE_HEIGHT / 2} L ${point.x} ${point.y}`);
    } else {
        drag = { id, dx: point.x - node.x, dy: point.y - node.y };
    }
    stage.setPointerCapture(event.pointerId);
}

function handlePointerMove(event) {
    if (pan) {
        camera.x = pan.x - (event.clientX - pan.startX) * pan.scaleX;
        camera.y = pan.y - (event.clientY - pan.startY) * pan.scaleY;
        applyCamera();
        return;
    }
    if (!isEditor || running) return;
    const point = clientToSvg(event);
    if (drag) {
        const node = nodeById(drag.id);
        node.x = Math.max(0, Math.min(5000, point.x - drag.dx));
        node.y = Math.max(0, Math.min(5000, point.y - drag.dy));
        render();
    }
    if (connection) {
        const node = nodeById(connection.from);
        tempEdge.setAttribute('d', `M ${node.x + NODE_WIDTH} ${node.y + NODE_HEIGHT / 2} L ${point.x} ${point.y}`);
    }
}

function handlePointerUp(event) {
    if (pan) {
        pan = null;
        stage.classList.remove('panning');
        if (stage.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
        return;
    }
    if (!isEditor || running) return;
    const hadInteraction = Boolean(connection || drag);
    if (!hadInteraction) return;
    if (connection) {
        const target = document.elementFromPoint(event.clientX, event.clientY)?.closest?.('[data-node]');
        const to = target?.dataset.node;
        if (to && to !== connection.from) {
            model.edges.push({ id: nextId('edge'), from: connection.from, to, trigger: 'CONTINUE',
                order: nextOperationOrder(connection.from), repetitions: 0, label: null, responseLabel: null });
            select({ type: 'edge', id: model.edges.at(-1).id });
        }
        connection = null;
        tempEdge.hidden = true;
    }
    drag = null;
    if (stage.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
    render();
}

function cancelPointerInteraction(event) {
    pan = null;
    drag = null;
    connection = null;
    tempEdge.hidden = true;
    stage.classList.remove('panning');
    if (stage.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
    render();
}

function addNode(name, catalogElementId) {
    if (model.nodes.length >= 100) return setStatus('El flujo admite como máximo 100 elementos.', 'error');
    const offset = (model.nodes.length % 4) * 22;
    const node = {
        id: nextId('node'), catalogElementId, name,
        x: Math.max(0, Math.min(5000 - NODE_WIDTH, camera.x + camera.width / 2 - NODE_WIDTH / 2 + offset)),
        y: Math.max(0, Math.min(5000 - NODE_HEIGHT, camera.y + camera.height / 2 - NODE_HEIGHT / 2 + offset)),
        detail: '', waitMode: 'ANY'
    };
    model.nodes.push(node);
    select({ type: 'node', id: node.id });
    render();
    setStatus(`${name} fue agregado al flujo.`);
}

function removeSelected() {
    if (!selected) return;
    if (selected.type === 'node') {
        model.nodes = model.nodes.filter((node) => node.id !== selected.id);
        model.edges = model.edges.filter((edge) => edge.from !== selected.id && edge.to !== selected.id);
    } else {
        model.edges = model.edges.filter((edge) => edge.id !== selected.id);
    }
    selected = null;
    render(); syncProperties(); setStatus('Elemento eliminado del diagrama.');
}

function select(value) {
    selected = value;
    if (isEditor && value) showSidebar();
    render();
    syncProperties();
}

function syncProperties() {
    if (!isEditor) return;
    document.querySelector('#empty-properties').hidden = Boolean(selected);
    document.querySelector('#node-properties').hidden = selected?.type !== 'node';
    document.querySelector('#edge-properties').hidden = selected?.type !== 'edge';
    if (selected?.type === 'node') {
        const node = nodeById(selected.id);
        document.querySelector('#node-name').value = node.name;
        document.querySelector('#node-detail').value = node.detail || '';
        renderNodeOperations(node);
    }
    if (selected?.type === 'edge') {
        const edge = edgeById(selected.id);
        document.querySelector('#edge-trigger').value = edge.trigger;
        document.querySelector('#edge-label').value = edge.label || '';
        document.querySelector('#edge-response').value = edge.responseLabel || '';
        document.querySelector('#edge-response-label').hidden = edge.trigger !== 'QUERY';
        window.requestAnimationFrame(() => document.querySelector('#edge-properties').scrollIntoView({ block: 'nearest' }));
    }
}

function renderNodeOperations(node) {
    const container = document.querySelector('#node-operations');
    container.replaceChildren();
    const outgoing = orderedOutgoing(node.id);
    if (!outgoing.length) {
        const empty = document.createElement('p');
        empty.className = 'operation-empty';
        empty.textContent = 'Este elemento todavía no tiene operaciones salientes.';
        container.append(empty);
        return;
    }
    outgoing.forEach((edge, index) => {
        const row = document.createElement('div');
        row.className = 'operation-row';
        const text = document.createElement('div');
        const name = document.createElement('strong');
        name.textContent = edge.label || (edge.trigger === 'QUERY' ? 'Consultar' : 'Continuar');
        const target = document.createElement('small');
        target.textContent = `${edge.trigger === 'QUERY' ? 'Consulta' : 'Continúa'} → ${nodeById(edge.to)?.name || edge.to}`;
        text.append(name, target);
        const controls = document.createElement('div');
        controls.className = 'operation-controls';
        controls.append(operationMoveButton(edge.id, -1, '↑', 'Mover antes', index === 0),
            operationMoveButton(edge.id, 1, '↓', 'Mover después', index === outgoing.length - 1));
        row.append(text, controls);
        container.append(row);
    });
}

function operationMoveButton(edgeId, delta, text, label, disabled) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'flow-button secondary';
    button.textContent = text;
    button.setAttribute('aria-label', label);
    button.disabled = disabled;
    button.addEventListener('click', () => moveOperation(edgeId, delta));
    return button;
}

function moveOperation(edgeId, delta) {
    const edge = edgeById(edgeId);
    if (!edge) return;
    const outgoing = orderedOutgoing(edge.from);
    const index = outgoing.findIndex((item) => item.id === edgeId);
    const next = index + delta;
    if (index < 0 || next < 0 || next >= outgoing.length) return;
    [outgoing[index], outgoing[next]] = [outgoing[next], outgoing[index]];
    outgoing.forEach((item, position) => { item.order = position + 1; });
    render();
    syncProperties();
    setStatus('Orden de operaciones actualizado.', 'done');
}

function orderedOutgoing(nodeId) {
    return model.edges.filter((edge) => edge.from === nodeId)
        .sort((first, second) => first.order - second.order || first.id.localeCompare(second.id));
}

function nextOperationOrder(nodeId) {
    return orderedOutgoing(nodeId).length + 1;
}

function updateSuggestedOptions() {
    if (!isEditor || !currentCase) return;
    const select = document.querySelector('#suggested-element');
    const selectedValue = select.value;
    const used = new Set(model.nodes.map((node) => node.catalogElementId).filter(Boolean));
    select.replaceChildren(new Option('Seleccione una sugerencia…', ''));
    currentCase.elements.filter((element) => !used.has(element.id)).forEach((element) =>
        select.append(new Option(element.name, String(element.id))));
    if ([...select.options].some((option) => option.value === selectedValue)) select.value = selectedValue;
}

async function importFlowJson(event) {
    const input = event.target;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > 1024 * 1024) return setStatus('El archivo JSON no puede superar 1 MB.', 'error');
    try {
        const imported = normalizeModel(JSON.parse(await file.text()));
        validateImportedModel(imported);
        runToken += 1;
        running = false;
        model = imported;
        selected = null;
        render();
        syncProperties();
        clearRuntimeClasses();
        fitFlow();
        setStatus('JSON importado para revisión. Use Guardar flujo para persistirlo.', 'done');
    } catch (error) {
        setStatus(`No se pudo importar el JSON: ${error.message}`, 'error');
    }
}

function validateImportedModel(imported) {
    if (!imported.nodes.length || imported.nodes.length > 100 || imported.edges.length > 300) {
        throw new Error('el flujo debe contener entre 1 y 100 elementos y hasta 300 conexiones');
    }
    const identifiers = /^[A-Za-z0-9_-]{1,64}$/;
    const nodeIds = new Set();
    for (const node of imported.nodes) {
        if (!identifiers.test(node.id) || nodeIds.has(node.id) || !node.name.trim()) {
            throw new Error('cada elemento debe tener id único y nombre');
        }
        nodeIds.add(node.id);
    }
    const edgeIds = new Set();
    for (const edge of imported.edges) {
        if (!identifiers.test(edge.id) || edgeIds.has(edge.id)) throw new Error('cada conexión debe tener un id único');
        if (!nodeIds.has(edge.from) || !nodeIds.has(edge.to) || edge.from === edge.to) {
            throw new Error('una conexión contiene un origen o destino inválido');
        }
        edgeIds.add(edge.id);
    }
}

function exportFlowJson() {
    const content = JSON.stringify(normalizeModel(model), null, 2);
    const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${String(currentCase?.code || 'flujo').toLowerCase().replace(/[^a-z0-9-]+/g, '-')}-flujo.json`;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    setStatus('JSON exportado. El archivo no modifica el flujo guardado.', 'done');
}

async function saveFlow() {
    if (running) return;
    const button = document.querySelector('#save-flow');
    button.disabled = true;
    try {
        const response = await fetch(`/api/v1/ocp-map/admin/test-cases/${encodeURIComponent(caseId)}/flow`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify(model)
        });
        const body = response.status === 204 ? {} : await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
        savedModel = structuredClone(model);
        setStatus('Flujo guardado correctamente en PostgreSQL.', 'done');
        if (isEmbedded) window.parent.postMessage({ type: 'ocp-flow-saved', caseId }, window.location.origin);
    } catch (error) {
        setStatus(error.message, 'error');
    } finally {
        button.disabled = false;
    }
}

async function playFlow() {
    if (running || !model.nodes.length) {
        if (!model.nodes.length) setStatus('El flujo todavía no contiene elementos.', 'error');
        return;
    }
    running = true;
    const token = ++runToken;
    toggleRunButtons(true);
    selected = null;
    render(); syncProperties(); clearRuntimeClasses();
    try {
        const incoming = new Set(model.edges.map((edge) => edge.to));
        const roots = model.nodes.filter((node) => !incoming.has(node.id));
        const processedEdges = new Set();
        const startNodes = roots.length ? roots : [model.nodes[0]];
        for (const root of startNodes) {
            if (token !== runToken) break;
            await executeNode(root.id, token, processedEdges, [], [], new Set());
        }
        for (const node of model.nodes) {
            if (token !== runToken) break;
            if (orderedOutgoing(node.id).some((edge) => !processedEdges.has(edge.id))) {
                await executeNode(node.id, token, processedEdges, [], [], new Set());
            }
        }
        if (token === runToken) {
            setRuntimeFocus([], []);
            setStatus('Visualización finalizada. Los circuitos completados quedan opacados.', 'done');
        }
    } catch (error) {
        if (token === runToken) setStatus(error.message, 'error');
    } finally {
        if (token === runToken) { running = false; toggleRunButtons(false); }
    }
}

async function executeNode(nodeId, token, processedEdges, queryEdges, queryNodes, visiting) {
    if (token !== runToken || visiting.has(nodeId)) return;
    const nextVisiting = new Set(visiting);
    nextVisiting.add(nodeId);
    const activeNodes = [...new Set([...queryNodes, nodeId])];
    setRuntimeFocus(queryEdges, activeNodes);
    await activateNode(nodeId, token);
    for (const edge of orderedOutgoing(nodeId)) {
        if (token !== runToken || processedEdges.has(edge.id)) continue;
        processedEdges.add(edge.id);
        const target = nodeById(edge.to);
        if (!target) continue;
        const pathEdges = [...queryEdges, edge.id];
        const pathNodes = [...new Set([...activeNodes, edge.to])];
        setRuntimeFocus(pathEdges, pathNodes);
        if (edge.trigger === 'QUERY') {
            setStatus(`${nodeById(edge.from).name} consulta a ${target.name}: ${edge.label || 'Consulta'}.`, 'running');
            await animateEdge(edge, 'out', token);
            await executeNode(edge.to, token, processedEdges, pathEdges, pathNodes, nextVisiting);
            if (token !== runToken) return;
            setRuntimeFocus(pathEdges, pathNodes);
            setStatus(`${target.name} responde a ${nodeById(edge.from).name}: ${edge.responseLabel || 'Respuesta recibida'}.`, 'running');
            await animateEdge(edge, 'return', token);
            await activateNode(edge.from, token);
        } else {
            setStatus(`${nodeById(edge.from).name}: ${edge.label || 'Continuar'} → ${target.name}.`, 'running');
            await animateEdge(edge, 'out', token);
            await executeNode(edge.to, token, processedEdges, queryEdges, pathNodes, nextVisiting);
        }
    }
}

function setRuntimeFocus(edgeIds, nodeIds) {
    const activeEdges = new Set(edgeIds);
    const activeNodes = new Set(nodeIds);
    nodesLayer.querySelectorAll('.flow-node').forEach((element) => {
        const active = activeNodes.has(element.dataset.node);
        element.classList.toggle('active-path', active);
        element.classList.toggle('dimmed', !active && (edgeIds.length > 0 || nodeIds.length > 0));
    });
    edgesLayer.querySelectorAll('.flow-edge-group').forEach((element) => {
        const active = activeEdges.has(element.dataset.edge);
        element.classList.toggle('active-path', active);
        element.classList.toggle('dimmed', !active && (edgeIds.length > 0 || nodeIds.length > 0));
    });
}

async function activateNode(id, token) {
    if (token !== runToken) return;
    const element = nodesLayer.querySelector(`[data-node="${cssEscape(id)}"]`);
    if (!element) return;
    element.classList.remove('done'); element.classList.add('active');
    setStatus(`${nodeById(id).name} recibe el flujo.`, 'running');
    await sleep(320);
    if (token !== runToken) return;
    element.classList.remove('active'); element.classList.add('done');
}

async function animateEdge(edge, phase, token) {
    const path = edgesLayer.querySelector(`[data-edge="${cssEscape(edge.id)}"] .flow-edge[data-phase="${phase}"]`);
    if (!path || token !== runToken) return;
    path.classList.add('active');
    const pulse = svgElement('circle', { r: 8, class: 'flow-pulse' });
    pulsesLayer.append(pulse);
    const length = path.getTotalLength();
    const started = performance.now();
    await new Promise((resolve) => {
        function frame(now) {
            if (token !== runToken) { pulse.remove(); resolve(); return; }
            const progress = Math.min(1, (now - started) / 650);
            const point = path.getPointAtLength(length * progress);
            pulse.setAttribute('cx', point.x); pulse.setAttribute('cy', point.y);
            if (progress < 1) requestAnimationFrame(frame);
            else {
                pulse.remove();
                path.classList.remove('active');
                path.classList.add('done');
                path.closest('.flow-edge-group')?.classList.add('done');
                resolve();
            }
        }
        requestAnimationFrame(frame);
    });
}

function resetFlow() {
    runToken += 1;
    running = false;
    pulsesLayer.replaceChildren();
    if (isEditor) model = structuredClone(savedModel);
    selected = null;
    render(); syncProperties(); clearRuntimeClasses(); toggleRunButtons(false);
    fitFlow();
    setStatus(isEditor ? 'Se restauró la última versión guardada.' : 'Visualización reiniciada.', 'done');
    if (!isEditor) window.setTimeout(playFlow, 250);
}

function toggleRunButtons(value) {
    document.querySelector('#play-flow').disabled = value;
    document.querySelector('#reset-flow').disabled = false;
    if (isEditor) {
        document.querySelector('#save-flow').disabled = value;
        document.querySelector('#import-flow').disabled = value;
        document.querySelector('#export-flow').disabled = value;
    }
}

function clearRuntimeClasses() {
    nodesLayer.querySelectorAll('.active,.done,.active-path,.dimmed').forEach((item) => item.classList.remove('active', 'done', 'active-path', 'dimmed'));
    edgesLayer.querySelectorAll('.active,.done,.active-path,.dimmed').forEach((item) => item.classList.remove('active', 'done', 'active-path', 'dimmed'));
}

function toggleSidebar() {
    if (!isEditor) return;
    if (flowSidebar.classList.contains('hidden-sidebar')) showSidebar();
    else hideSidebar();
}

function showSidebar() {
    if (!isEditor) return;
    flowSidebar.classList.remove('hidden-sidebar');
    document.body.classList.remove('sidebar-hidden');
    sidebarToggle.textContent = 'Ocultar panel';
}

function hideSidebar() {
    if (!isEditor) return;
    flowSidebar.classList.add('hidden-sidebar');
    document.body.classList.add('sidebar-hidden');
    sidebarToggle.textContent = 'Mostrar panel';
    setStatus('Panel oculto. El lienzo completo permanece disponible para navegar.', 'done');
}

function handleWheel(event) {
    event.preventDefault();
    const anchor = clientToSvg(event);
    zoomCamera(event.deltaY > 0 ? 1.12 : 1 / 1.12, anchor);
}

function zoomCamera(factor, anchor = null) {
    const center = anchor || { x: camera.x + camera.width / 2, y: camera.y + camera.height / 2 };
    const nextWidth = Math.max(340, Math.min(5200, camera.width * factor));
    const ratio = nextWidth / camera.width;
    const nextHeight = camera.height * ratio;
    camera.x = center.x - (center.x - camera.x) * ratio;
    camera.y = center.y - (center.y - camera.y) * ratio;
    camera.width = nextWidth;
    camera.height = nextHeight;
    applyCamera();
}

function fitFlow() {
    if (!model.nodes.length) {
        camera = { x: 0, y: 0, width: 1400, height: 800 };
        applyCamera();
        return;
    }
    const bounds = flowBounds(95);
    const rect = stage.getBoundingClientRect();
    const sidebarVisible = isEditor && !flowSidebar.classList.contains('hidden-sidebar');
    const reservedWidth = sidebarVisible ? Math.min(350, rect.width * .45) : 0;
    const visibleWidth = Math.max(1, rect.width - reservedWidth);
    const visibleAspect = Math.max(.5, visibleWidth / Math.max(1, rect.height));
    let visibleWorldWidth = Math.max(520, bounds.width);
    let height = Math.max(300, bounds.height);
    if (visibleWorldWidth / height > visibleAspect) height = visibleWorldWidth / visibleAspect;
    else visibleWorldWidth = height * visibleAspect;
    const width = visibleWorldWidth * rect.width / visibleWidth;
    camera = {
        x: bounds.x + bounds.width / 2 - visibleWorldWidth / 2,
        y: bounds.y + bounds.height / 2 - height / 2,
        width,
        height
    };
    applyCamera();
    setStatus('Flujo completo ajustado al lienzo.', 'done');
}

function applyCamera() {
    stage.setAttribute('viewBox', `${camera.x} ${camera.y} ${camera.width} ${camera.height}`);
    zoomLevel.textContent = `${Math.round(1400 / camera.width * 100)}%`;
    updateMinimap();
}

function flowBounds(padding = 0) {
    if (!model.nodes.length) return { x: 0, y: 0, width: 1400, height: 800 };
    const minX = Math.min(...model.nodes.map((node) => node.x)) - padding;
    const minY = Math.min(...model.nodes.map((node) => node.y)) - padding;
    const maxX = Math.max(...model.nodes.map((node) => node.x + NODE_WIDTH)) + padding;
    const maxY = Math.max(...model.nodes.map((node) => node.y + NODE_HEIGHT)) + padding;
    return { x: minX, y: minY, width: Math.max(1, maxX - minX), height: Math.max(1, maxY - minY) };
}

function updateMinimap() {
    minimapEdges.replaceChildren();
    minimapNodes.replaceChildren();
    if (!model.nodes.length) {
        minimap.hidden = true;
        minimapTransform = null;
        return;
    }
    minimap.hidden = false;
    const bounds = flowBounds(100);
    const scale = Math.min(210 / bounds.width, 110 / bounds.height);
    const offsetX = (220 - bounds.width * scale) / 2;
    const offsetY = (120 - bounds.height * scale) / 2;
    minimapTransform = { bounds, scale, offsetX, offsetY };
    const mapX = (value) => offsetX + (value - bounds.x) * scale;
    const mapY = (value) => offsetY + (value - bounds.y) * scale;
    for (const edge of model.edges) {
        const from = nodeById(edge.from);
        const to = nodeById(edge.to);
        if (!from || !to) continue;
        minimapEdges.append(svgElement('line', {
            x1: mapX(from.x + NODE_WIDTH / 2), y1: mapY(from.y + NODE_HEIGHT / 2),
            x2: mapX(to.x + NODE_WIDTH / 2), y2: mapY(to.y + NODE_HEIGHT / 2)
        }));
    }
    for (const node of model.nodes) {
        minimapNodes.append(svgElement('rect', {
            x: mapX(node.x), y: mapY(node.y),
            width: Math.max(3, NODE_WIDTH * scale), height: Math.max(3, NODE_HEIGHT * scale), rx: 2
        }));
    }
    minimapViewport.setAttribute('x', mapX(camera.x));
    minimapViewport.setAttribute('y', mapY(camera.y));
    minimapViewport.setAttribute('width', Math.max(2, camera.width * scale));
    minimapViewport.setAttribute('height', Math.max(2, camera.height * scale));
}

function handleMinimapClick(event) {
    if (!minimapTransform) return;
    const rect = minimap.querySelector('svg').getBoundingClientRect();
    const mapX = (event.clientX - rect.left) / Math.max(1, rect.width) * 220;
    const mapY = (event.clientY - rect.top) / Math.max(1, rect.height) * 120;
    const worldX = minimapTransform.bounds.x + (mapX - minimapTransform.offsetX) / minimapTransform.scale;
    const worldY = minimapTransform.bounds.y + (mapY - minimapTransform.offsetY) / minimapTransform.scale;
    camera.x = worldX - camera.width / 2;
    camera.y = worldY - camera.height / 2;
    applyCamera();
}

function edgePoints(edge, shift = 0) {
    const from = nodeById(edge.from); const to = nodeById(edge.to);
    if (!from || !to) return null;
    const fromCenter = { x: from.x + NODE_WIDTH / 2, y: from.y + NODE_HEIGHT / 2 };
    const toCenter = { x: to.x + NODE_WIDTH / 2, y: to.y + NODE_HEIGHT / 2 };
    const dx = toCenter.x - fromCenter.x; const dy = toCenter.y - fromCenter.y;
    const distance = Math.max(1, Math.hypot(dx, dy));
    const perpendicularX = -dy / distance;
    const perpendicularY = dx / distance;
    const fromScale = 1 / Math.max(Math.abs(dx) / (NODE_WIDTH / 2), Math.abs(dy) / (NODE_HEIGHT / 2), .001);
    const toScale = fromScale;
    return {
        sx: fromCenter.x + dx * fromScale + perpendicularX * shift,
        sy: fromCenter.y + dy * fromScale + perpendicularY * shift,
        tx: toCenter.x - dx * toScale + perpendicularX * shift,
        ty: toCenter.y - dy * toScale + perpendicularY * shift
    };
}

function edgePath(edge, shift = 0) {
    const point = edgePoints(edge, shift);
    if (!point) return '';
    const dx = point.tx - point.sx; const dy = point.ty - point.sy;
    if (Math.abs(dx) >= Math.abs(dy)) {
        const bend = Math.min(130, Math.max(45, Math.abs(dx) * .35));
        return `M ${point.sx} ${point.sy} C ${point.sx + Math.sign(dx || 1) * bend} ${point.sy}, ${point.tx - Math.sign(dx || 1) * bend} ${point.ty}, ${point.tx} ${point.ty}`;
    }
    const bend = Math.min(115, Math.max(45, Math.abs(dy) * .35));
    return `M ${point.sx} ${point.sy} C ${point.sx} ${point.sy + Math.sign(dy || 1) * bend}, ${point.tx} ${point.ty - Math.sign(dy || 1) * bend}, ${point.tx} ${point.ty}`;
}

function clientToSvg(event) {
    const point = stage.createSVGPoint(); point.x = event.clientX; point.y = event.clientY;
    return point.matrixTransform(stage.getScreenCTM().inverse());
}

function svgElement(name, attributes = {}) {
    const element = document.createElementNS(SVG_NS, name);
    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, value));
    return element;
}

function nextId(prefix) {
    let id;
    do { sequence += 1; id = `${prefix}_${Date.now().toString(36)}_${sequence}`; }
    while (model.nodes.some((node) => node.id === id) || model.edges.some((edge) => edge.id === id));
    return id;
}

function nodeById(id) { return model.nodes.find((node) => node.id === id); }
function edgeById(id) { return model.edges.find((edge) => edge.id === id); }
function boundedInteger(value, min, max, fallback) { const number = Number(value); return Number.isInteger(number) ? Math.max(min, Math.min(max, number)) : fallback; }
function truncate(value, max) { const text = String(value || ''); return text.length <= max ? text : `${text.slice(0, max - 1)}…`; }
function cssEscape(value) { return window.CSS?.escape ? CSS.escape(value) : String(value).replace(/[^A-Za-z0-9_-]/g, '\\$&'); }
function sleep(milliseconds) { return new Promise((resolve) => window.setTimeout(resolve, milliseconds)); }
function setStatus(message, type = '') { statusText.textContent = message; statusDot.className = type; }
function fail(message) { setStatus(message, 'error'); document.querySelector('#flow-title').textContent = 'Flujo no disponible'; toggleRunButtons(false); }
async function fetchJson(url) { const response = await fetch(url, { headers: { Accept: 'application/json' } }); const body = await response.json().catch(() => ({})); if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`); return body; }
