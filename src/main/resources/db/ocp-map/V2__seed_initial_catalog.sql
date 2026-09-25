INSERT INTO ocp_namespace(name) VALUES
    ('testing'),
    ('testing-diversificacion'),
    ('testing-matrix')
ON CONFLICT DO NOTHING;

INSERT INTO test_case(code, name, description, display_order) VALUES
    ('CP1', 'Alta producto fija FTTH monoInternet · CRM → CMS', 'CRM · CMS · Orden creada', 1),
    ('CP2', 'Alta producto fija FTTH monoInternet · CRM → Pangea', 'CRM · Pangea · Orden creada', 2),
    ('CP3', 'Alta producto fija FTTH monoInternet · DITO → CMS', 'DITO · CMS · Orden creada', 3),
    ('CP4', 'Modificación de velocidad de producto FTTH', 'CRM · OMS · Modificación', 4),
    ('CP5', 'Agendamiento de reparación de servicio fijo', 'Front atención · Agendador · Reparación', 5),
    ('CP6', 'Cancelación de orden de alta FTTH', 'CRM · SVO · Cancelación', 6)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    display_order = EXCLUDED.display_order;

INSERT INTO test_case_element(test_case_id, name, display_order)
SELECT tc.id, seed.name, seed.display_order
FROM (VALUES
    ('CP1', 'CRM', 1), ('CP1', 'OMS', 2), ('CP1', 'SVO', 3), ('CP1', 'BUS', 4),
    ('CP1', 'API On-premise', 5), ('CP1', 'Agendador', 6),
    ('CP2', 'CRM', 1), ('CP2', 'OMS', 2), ('CP2', 'SVO', 3), ('CP2', 'API Azure', 4),
    ('CP2', 'Pangea', 5), ('CP2', 'Agendador', 6),
    ('CP3', 'DITO', 1), ('CP3', 'API On-premise', 2), ('CP3', 'OMS', 3),
    ('CP3', 'SVO', 4), ('CP3', 'CMS', 5),
    ('CP4', 'CRM', 1), ('CP4', 'OMS', 2), ('CP4', 'API On-premise', 3), ('CP4', 'Matrix', 4),
    ('CP5', 'Front atención', 1), ('CP5', 'API Azure', 2), ('CP5', 'Agendador', 3), ('CP5', 'Workforce', 4),
    ('CP6', 'CRM', 1), ('CP6', 'OMS', 2), ('CP6', 'SVO', 3), ('CP6', 'BUS', 4)
) AS seed(code, name, display_order)
JOIN test_case tc ON tc.code = seed.code
ON CONFLICT (test_case_id, name) DO UPDATE SET display_order = EXCLUDED.display_order;

INSERT INTO ocp_deployment(namespace, name, active)
SELECT seed.namespace, seed.name, FALSE
FROM (VALUES
    ('testing', 'ms-integracion-scoringburo'),
    ('testing-diversificacion', 'ms-ocp-integracion-validateaddress-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-addressroute-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-checkserviceabilitysystem-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-serviceavailability-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-retrieveavailableappointmentsv1-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-designandassignresourcesv1-camel'),
    ('testing', 'ms-integracion-geographicsitepangea-camel'),
    ('testing-matrix', 'ms-ocp-integracion-matrix-productcatalog-camel'),
    ('testing-matrix', 'ms-ocp-matrix-changeproductstatus-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-retrieveappointmentdetailsv1-camel'),
    ('testing-diversificacion', 'ms-ocp-integracion-repairandreplaceequipmentv1-camel'),
    ('testing', 'ms-integracion-listenercancelorder'),
    ('testing-diversificacion', 'ms-ocp-integracion-cancelticketevent-camel'),
    ('testing-diversificacion', 'ms-integracion-cancelreservationofdevice-camel')
) AS seed(namespace, name)
ON CONFLICT (namespace, name) DO NOTHING;

INSERT INTO test_case_deployment(test_case_id, deployment_id, display_order)
SELECT tc.id, dep.id, seed.display_order
FROM (VALUES
    ('CP1', 'testing', 'ms-integracion-scoringburo', 1),
    ('CP1', 'testing-diversificacion', 'ms-ocp-integracion-validateaddress-camel', 2),
    ('CP1', 'testing-diversificacion', 'ms-ocp-integracion-addressroute-camel', 3),
    ('CP1', 'testing-diversificacion', 'ms-ocp-integracion-checkserviceabilitysystem-camel', 4),
    ('CP1', 'testing-diversificacion', 'ms-ocp-integracion-serviceavailability-camel', 5),
    ('CP1', 'testing-diversificacion', 'ms-ocp-integracion-retrieveavailableappointmentsv1-camel', 6),
    ('CP1', 'testing-diversificacion', 'ms-ocp-integracion-designandassignresourcesv1-camel', 7),
    ('CP2', 'testing', 'ms-integracion-scoringburo', 1),
    ('CP2', 'testing-diversificacion', 'ms-ocp-integracion-validateaddress-camel', 2),
    ('CP2', 'testing-diversificacion', 'ms-ocp-integracion-checkserviceabilitysystem-camel', 3),
    ('CP2', 'testing-diversificacion', 'ms-ocp-integracion-serviceavailability-camel', 4),
    ('CP2', 'testing-diversificacion', 'ms-ocp-integracion-retrieveavailableappointmentsv1-camel', 5),
    ('CP2', 'testing-diversificacion', 'ms-ocp-integracion-designandassignresourcesv1-camel', 6),
    ('CP2', 'testing', 'ms-integracion-geographicsitepangea-camel', 7),
    ('CP3', 'testing-diversificacion', 'ms-ocp-integracion-validateaddress-camel', 1),
    ('CP3', 'testing-diversificacion', 'ms-ocp-integracion-addressroute-camel', 2),
    ('CP3', 'testing-diversificacion', 'ms-ocp-integracion-checkserviceabilitysystem-camel', 3),
    ('CP3', 'testing-diversificacion', 'ms-ocp-integracion-serviceavailability-camel', 4),
    ('CP3', 'testing-diversificacion', 'ms-ocp-integracion-retrieveavailableappointmentsv1-camel', 5),
    ('CP3', 'testing-diversificacion', 'ms-ocp-integracion-designandassignresourcesv1-camel', 6),
    ('CP4', 'testing-diversificacion', 'ms-ocp-integracion-checkserviceabilitysystem-camel', 1),
    ('CP4', 'testing-matrix', 'ms-ocp-integracion-matrix-productcatalog-camel', 2),
    ('CP4', 'testing-matrix', 'ms-ocp-matrix-changeproductstatus-camel', 3),
    ('CP5', 'testing-diversificacion', 'ms-ocp-integracion-retrieveavailableappointmentsv1-camel', 1),
    ('CP5', 'testing-diversificacion', 'ms-ocp-integracion-retrieveappointmentdetailsv1-camel', 2),
    ('CP5', 'testing-diversificacion', 'ms-ocp-integracion-repairandreplaceequipmentv1-camel', 3),
    ('CP6', 'testing', 'ms-integracion-listenercancelorder', 1),
    ('CP6', 'testing-diversificacion', 'ms-ocp-integracion-cancelticketevent-camel', 2),
    ('CP6', 'testing-diversificacion', 'ms-integracion-cancelreservationofdevice-camel', 3)
) AS seed(code, namespace, name, display_order)
JOIN test_case tc ON tc.code = seed.code
JOIN ocp_deployment dep ON dep.namespace = seed.namespace AND dep.name = seed.name
ON CONFLICT (test_case_id, deployment_id) DO UPDATE SET display_order = EXCLUDED.display_order;

UPDATE test_case
SET code = CASE code
    WHEN 'CP1' THEN 'CP-001'
    WHEN 'CP2' THEN 'CP-002'
    WHEN 'CP3' THEN 'CP-003'
    WHEN 'CP4' THEN 'CP-004'
    WHEN 'CP5' THEN 'CP-005'
    WHEN 'CP6' THEN 'CP-006'
END
WHERE code IN ('CP1', 'CP2', 'CP3', 'CP4', 'CP5', 'CP6');
