param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$PocDirectory,
    [Security.SecureString]$MasterPassword,
    [switch]$IncludeHistory
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$poc = (Resolve-Path -LiteralPath $PocDirectory).Path
$master = if ($MasterPassword) { $MasterPassword } else { Read-Host 'Clave maestra KDBX' -AsSecureString }
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($master)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $unlockBody = @{ masterPassword = $plain } | ConvertTo-Json
    $session = Invoke-RestMethod -Method Post -Uri "$base/api/v1/external-services/admin/unlock" `
        -ContentType 'application/json' -Body $unlockBody
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $plain = $null
}
$headers = @{ 'X-External-Admin-Token' = $session.token }
$currentSnapshot = Invoke-RestMethod -Method Get -Uri "$base/api/v1/external-services/snapshot"
$credentialResponse = Invoke-RestMethod -Method Get -Uri "$base/api/v1/external-services/admin/credentials" -Headers $headers
# Invoke-RestMethod preserva un array JSON raíz como un único objeto Object[].
# Forzar su enumeración evita interpolar todos los IDs juntos al actualizar.
$currentCredentials = @($credentialResponse | ForEach-Object { $_ })

$urlRows = Get-Content -LiteralPath (Join-Path $poc 'data\servidores_validacion.txt') |
    Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') } |
    ForEach-Object {
        $parts = $_ -split '\s*\|\s*'
        [pscustomobject]@{
            Name = $parts[0].Trim(); Url = $parts[1].Trim()
            Expected = if ($parts.Count -gt 2 -and $parts[2]) { $parts[2].Trim() } else { '200,201,202,204,301,302,303,307,308,401,403' }
            Timeout = if ($parts.Count -gt 3 -and $parts[3]) { [int]$parts[3] } else { 10000 }
        }
    }

$serviceCatalog = @{}
$urlRows | Group-Object Name | ForEach-Object {
    $probeIndex = 0
    $probes = @($_.Group | ForEach-Object {
        $probeIndex++
        $uri = [Uri]$_.Url
        @{
            clientId = $probeIndex; name = "HTTP $probeIndex"; probeType = 'HTTP'; mandatory = $true
            displayOrder = $probeIndex; host = $uri.Host
            port = if ($uri.IsDefaultPort) { if ($uri.Scheme -eq 'https') { 443 } else { 80 } } else { $uri.Port }
            url = $_.Url; httpMethod = 'GET'; expectedStatuses = $_.Expected
            authType = 'NONE'; timeoutMs = $_.Timeout
        }
    })
    $serviceCatalog[$_.Name.ToLowerInvariant()] = @{
        name = $_.Name; environment = 'Testing'; systemName = $_.Name
        description = $null; probes = [System.Collections.ArrayList]@($probes)
    }
}

$dbRows = Get-Content -Raw -LiteralPath (Join-Path $poc 'data\db_connections.json') | ConvertFrom-Json
foreach ($db in $dbRows) {
    $credentialBody = @{
        name = $db.name; environment = $db.env; credentialType = 'DATABASE'; systemName = $db.name
        username = $db.user; password = $db.password; extra = @{}
    } | ConvertTo-Json -Depth 8
    $existingCredential = $currentCredentials | Where-Object {
        $_.name -eq $db.name -and $_.environment -eq $db.env -and $_.credentialType -eq 'DATABASE' -and $_.systemName -eq $db.name
    } | Select-Object -First 1
    if ($existingCredential) {
        $credential = Invoke-RestMethod -Method Put -Uri "$base/api/v1/external-services/admin/credentials/$($existingCredential.id)" -Headers $headers -ContentType 'application/json' -Body $credentialBody
    } else {
        $credential = Invoke-RestMethod -Method Post -Uri "$base/api/v1/external-services/admin/credentials" -Headers $headers -ContentType 'application/json' -Body $credentialBody
    }
    $key = $db.name.ToLowerInvariant()
    if (-not $serviceCatalog.ContainsKey($key)) {
        $serviceCatalog[$key] = @{
            name = $db.name; environment = $db.env; systemName = $db.name
            description = $null; probes = [System.Collections.ArrayList]@()
        }
    }
    $entry = $serviceCatalog[$key]
    $tcpId = 1000 + $entry.probes.Count + 1
    $dbId = $tcpId + 1
    [void]$entry.probes.Add(@{ clientId=$tcpId; name='TCP BD'; probeType='TCP'; mandatory=$true; displayOrder=$entry.probes.Count; host=$db.host; port=[int]$db.port; authType='NONE'; timeoutMs=[int]$db.timeout })
    [void]$entry.probes.Add(@{ clientId=$dbId; dependsOnClientId=$tcpId; credentialId=[long]$credential.id; name='Consulta BD'; probeType='DATABASE'; mandatory=$true; displayOrder=$entry.probes.Count; host=$db.host; port=[int]$db.port; dbEngine=$db.engine.ToUpper(); dbName=$db.database; dbService=$db.service; validationQuery=$db.query; authType='BASIC'; timeoutMs=[int]$db.timeout })
}

foreach ($entry in $serviceCatalog.Values) {
    $body = $entry | ConvertTo-Json -Depth 10
    $existingService = $currentSnapshot.services | Where-Object {
        $_.name -eq $entry.name -and $_.environment -eq $entry.environment
    } | Select-Object -First 1
    if ($existingService) {
        Invoke-RestMethod -Method Put -Uri "$base/api/v1/external-services/admin/services/$($existingService.id)" -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    } else {
        Invoke-RestMethod -Method Post -Uri "$base/api/v1/external-services/admin/services" -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    }
}

if ($IncludeHistory) {
    $batch = [System.Collections.Generic.List[object]]::new()
    foreach ($line in Get-Content -LiteralPath (Join-Path $poc 'data\historial.jsonl')) {
        if (-not $line.Trim()) { continue }
        $run = $line | ConvertFrom-Json
        foreach ($serviceResults in ($run.results | Group-Object name)) {
            $probeNumber = 0
            $converted = @($serviceResults.Group | ForEach-Object {
                $probeNumber++
                @{ probeName="HTTP $probeNumber"; probeType='HTTP'; mandatory=$true; status=$_.state; phase='HTTP'; message=$_.reason; durationMs=[long]$_.ms; responseCode=$_.status }
            })
            $states = @($serviceResults.Group | ForEach-Object { $_.state })
            $overall = if ($states -contains 'red') { 'red' } elseif ($states -contains 'yellow') { 'yellow' } else { 'green' }
            $duration = ($serviceResults.Group | Measure-Object -Property ms -Maximum).Maximum
            $batch.Add(@{
                serviceName=$serviceResults.Name; environment='Testing'; startedAt=$run.stamp; status=$overall; durationMs=[long]$duration
                results=$converted
            })
            if ($batch.Count -ge 200) {
                Invoke-RestMethod -Method Post -Uri "$base/api/v1/external-services/admin/import-history" -Headers $headers -ContentType 'application/json' -Body ($batch | ConvertTo-Json -Depth 8) | Out-Null
                $batch.Clear()
            }
        }
    }
    foreach ($line in Get-Content -LiteralPath (Join-Path $poc 'data\db_historial.jsonl')) {
        if (-not $line.Trim()) { continue }
        $run = $line | ConvertFrom-Json
        foreach ($result in $run.results) {
            $batch.Add(@{
                serviceName=$result.name; environment=$result.env; startedAt=$run.stamp; status=$result.state; durationMs=[long]$result.ms
                results=@(@{ probeName='Consulta BD'; probeType='DATABASE'; mandatory=$true; status=$result.state; phase=$result.phase; message=$result.reason; durationMs=[long]$result.ms; responseCode=$null })
            })
            if ($batch.Count -ge 200) {
                Invoke-RestMethod -Method Post -Uri "$base/api/v1/external-services/admin/import-history" -Headers $headers -ContentType 'application/json' -Body ($batch | ConvertTo-Json -Depth 8) | Out-Null
                $batch.Clear()
            }
        }
    }
    if ($batch.Count) {
        Invoke-RestMethod -Method Post -Uri "$base/api/v1/external-services/admin/import-history" -Headers $headers -ContentType 'application/json' -Body ($batch | ConvertTo-Json -Depth 8) | Out-Null
    }
}

Write-Host 'Migración preparada/completada. Revise la pestaña Servicios externos antes de habilitar el scheduler.'
