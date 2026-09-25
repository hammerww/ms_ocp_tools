[CmdletBinding()]
param(
    [string]$Namespace = 'testing-pmx3',
    [string]$StatefulSet = 'ms-ocp-tools-postgresql',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\database-backups')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Oc {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $result = & oc @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "oc finalizó con código $LASTEXITCODE al ejecutar: oc $($Arguments -join ' ')"
    }
    return $result
}

$identity = (Invoke-Oc -Arguments @('whoami') | Out-String).Trim()
$server = (Invoke-Oc -Arguments @('whoami', '--show-server') | Out-String).Trim()
$podSelector = "app.kubernetes.io/name=$StatefulSet"
$pod = (Invoke-Oc -Arguments @('get', 'pods', '-n', $Namespace, '-l', $podSelector,
    '-o', 'jsonpath={.items[0].metadata.name}') | Out-String).Trim()

if ([string]::IsNullOrWhiteSpace($pod)) {
    throw "No se encontró un pod PostgreSQL con selector '$podSelector' en '$Namespace'."
}

$ready = (Invoke-Oc -Arguments @('get', 'pod', $pod, '-n', $Namespace,
    '-o', 'jsonpath={.status.containerStatuses[0].ready}') | Out-String).Trim()
if ($ready -ne 'true') {
    throw "El pod '$pod' no está Ready; no se generó respaldo."
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
$dumpPath = Join-Path $resolvedOutput "ocp-map-before-v6-$timestamp.dump"
$countsPath = Join-Path $resolvedOutput "ocp-map-before-v6-$timestamp-counts.csv"
$manifestPath = Join-Path $resolvedOutput "ocp-map-before-v6-$timestamp-manifest.json"
$remoteDump = "/tmp/ocp-map-before-v6-$timestamp.dump"

$rowCountSql = @'
SELECT 'catalog_element', COUNT(*) FROM catalog_element
UNION ALL SELECT 'ocp_deployment', COUNT(*) FROM ocp_deployment
UNION ALL SELECT 'ocp_namespace', COUNT(*) FROM ocp_namespace
UNION ALL SELECT 'test_case', COUNT(*) FROM test_case
UNION ALL SELECT 'test_case_annotation', COUNT(*) FROM test_case_annotation
UNION ALL SELECT 'test_case_deployment', COUNT(*) FROM test_case_deployment
UNION ALL SELECT 'test_case_element', COUNT(*) FROM test_case_element
UNION ALL SELECT 'test_case_flow', COUNT(*) FROM test_case_flow
UNION ALL SELECT 'test_case_metadata', COUNT(*) FROM test_case_metadata
UNION ALL SELECT 'testing_mark', COUNT(*) FROM testing_mark
UNION ALL SELECT 'testing_mark_history', COUNT(*) FROM testing_mark_history
UNION ALL SELECT 'external_group', COUNT(*) FROM external_group
UNION ALL SELECT 'external_credential', COUNT(*) FROM external_credential
UNION ALL SELECT 'external_service', COUNT(*) FROM external_service
UNION ALL SELECT 'external_probe', COUNT(*) FROM external_probe
UNION ALL SELECT 'external_run', COUNT(*) FROM external_run
UNION ALL SELECT 'external_probe_result', COUNT(*) FROM external_probe_result
UNION ALL SELECT 'external_incident', COUNT(*) FROM external_incident
UNION ALL SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history
ORDER BY 1;
'@

try {
    Write-Host "Generando pg_dump consistente en $Namespace/$pod..."
    Invoke-Oc -Arguments @('exec', '-n', $Namespace, $pod, '-c', 'postgresql', '--',
        '/bin/bash', '-ec',
        'PGPASSWORD="$POSTGRESQL_PASSWORD" pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRESQL_USER" --dbname="$POSTGRESQL_DATABASE" --file="$1"',
        'backup-script', $remoteDump) | Out-Null

    Write-Host 'Validando el catálogo del respaldo con pg_restore...'
    Invoke-Oc -Arguments @('exec', '-n', $Namespace, $pod, '-c', 'postgresql', '--',
        'pg_restore', '--list', $remoteDump) | Out-Null

    Write-Host 'Copiando el respaldo a la laptop...'
    $remoteReference = "${Namespace}/${pod}:$remoteDump"
    Push-Location $resolvedOutput
    try {
        Invoke-Oc -Arguments @('cp', $remoteReference,
            [System.IO.Path]::GetFileName($dumpPath), '-c', 'postgresql') | Out-Null
    }
    finally {
        Pop-Location
    }

    $dump = Get-Item -LiteralPath $dumpPath
    if ($dump.Length -le 0) {
        throw "El archivo '$dumpPath' está vacío."
    }

    Write-Host 'Guardando conteos de control previos a V6...'
    $counts = Invoke-Oc -Arguments @('exec', '-n', $Namespace, $pod, '-c', 'postgresql', '--',
        '/bin/bash', '-ec',
        'PGPASSWORD="$POSTGRESQL_PASSWORD" psql --no-psqlrc --tuples-only --no-align --field-separator="," --username="$POSTGRESQL_USER" --dbname="$POSTGRESQL_DATABASE" --command="$1"',
        'count-script', $rowCountSql)
    $counts | Set-Content -LiteralPath $countsPath -Encoding utf8

    $hash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        createdAt = (Get-Date).ToUniversalTime().ToString('o')
        cluster = $server
        namespace = $Namespace
        pod = $pod
        executedBy = $identity
        purpose = 'Respaldo previo a Flyway V6 y al despliegue 0.7.1 (monitor y KPI de Servicios externos).'
        dumpFile = $dump.Name
        dumpBytes = $dump.Length
        sha256 = $hash
        rowCountsFile = [System.IO.Path]::GetFileName($countsPath)
        pgRestoreCatalogValidated = $true
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8

    Write-Host ''
    Write-Host 'Respaldo verificado correctamente.' -ForegroundColor Green
    Write-Host "Dump:      $dumpPath"
    Write-Host "SHA-256:   $hash"
    Write-Host "Conteos:   $countsPath"
    Write-Host "Manifiesto:$manifestPath"
}
finally {
    & oc exec -n $Namespace $pod -c postgresql -- rm -f -- $remoteDump 2>$null
}
