# Build legacy app and SSO mock, start the Docker stack and hot-deploy the WARs (PowerShell 5.1).
$ErrorActionPreference = 'Stop'
$next = 'docker\smoke.ps1; docker\smoke-migration.ps1'
$root = Split-Path -Parent $PSScriptRoot

mvn -q -B -f "$root\legacy\pom.xml" verify
if ($LASTEXITCODE -ne 0) { throw 'Build of legacy failed' }
mvn -q -B -f "$root\sso-mock\pom.xml" verify
if ($LASTEXITCODE -ne 0) { throw 'Build of sso-mock failed' }
mvn -q -B -f "$root\crm-web\pom.xml" verify
if ($LASTEXITCODE -ne 0) { throw 'Build of crm-web failed' }
Copy-Item "$root\legacy\sso-portal-api\target\sso-portal-api.jar" "$PSScriptRoot\liferay\sso-portal-api.jar" -Force
New-Item -ItemType Directory -Force "$PSScriptRoot\deploy" | Out-Null

Push-Location $PSScriptRoot
try {
    docker compose up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
} finally {
    Pop-Location
}

# Watch only logs from now on - older container runs would report a false "ready".
$deployStart = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
# Theme first: the portlets list it in required-deployment-contexts.
Copy-Item "$root\legacy\customercare-theme\target\customercare-theme.war" "$PSScriptRoot\deploy\" -Force
Copy-Item "$root\legacy\customercare-portlets\target\customercare-portlet.war" "$PSScriptRoot\deploy\" -Force

# RedisInsight 3.x ignores connection env vars - register the shared-session database through its API.
$api = 'http://localhost:5540/api/databases'
$existing = $null
for ($i = 0; $i -lt 30; $i++) {
    try { $existing = @(Invoke-RestMethod $api); break } catch { Start-Sleep -Seconds 2 }
}
if (-not ($existing | Where-Object { $_.name -eq 'crm-shared-session' })) {
    $body = '{"name":"crm-shared-session","host":"redis","port":6379}'
    Invoke-RestMethod -Method Post -Uri $api -ContentType 'application/json' -Body $body | Out-Null
}
# Finish only once the portlets are deployed, so smoke tests never hit a half-started stack.
# Exact log text: the webapp is named customercare-portlet (no "s").
$readyLog = 'portlets for customercare-portlet are available for use'
Write-Output 'Waiting for Liferay start and portlet deploy (usually 1-3 min, max 10 min)...'
Push-Location $PSScriptRoot
try {
    for ($i = 1; $i -le 120; $i++) {
        $logs = docker compose logs --since $deployStart liferay 2>$null | Out-String
        if ($logs.Contains($readyLog)) {
            Write-Output "Done in ~$($i * 5) s. Next: $next"
            return
        }
        Start-Sleep -Seconds 5
    }
    docker compose logs --tail 40 liferay
    throw 'Liferay did not deploy the portlets within 10 min'
} finally {
    Pop-Location
}
