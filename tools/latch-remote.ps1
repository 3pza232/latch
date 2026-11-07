#Requires -Version 7.0
<#
.SYNOPSIS
    Remote CLI for Latch over the Wi-Fi Agent gateway.

.DESCRIPTION
    Pairs once with the 6-digit code shown in Latch (Settings -> Agent 连接),
    stores the token locally, then manages rules through the REST API.

.EXAMPLE
    ./latch-remote.ps1 -Address 192.168.10.21:8765 -Code 602741 -Command status
    ./latch-remote.ps1 -Address 192.168.10.21:8765 -Command apply -Group adslot-lock
    ./latch-remote.ps1 -Address 192.168.10.21:8765 -Command enable -Rule <ruleId>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Address,
    [Parameter(Mandatory = $true)]
    [ValidateSet('status', 'rules', 'apply', 'verify', 'repair', 'enable', 'disable', 'enable-group', 'disable-group', 'delete', 'add', 'import', 'info')]
    [string]$Command,
    [string]$Code = '',
    [string]$Group = '',
    [string]$Rule = '',
    [string]$File = '',
    [string]$TokenFile = ''
)

$ErrorActionPreference = 'Stop'
if (-not $TokenFile) { $TokenFile = Join-Path $PSScriptRoot '.latch-token' }
$base = "http://$Address"

function Get-Token {
    if (Test-Path $TokenFile) {
        $saved = (Get-Content $TokenFile -Raw).Trim()
        if ($saved) { return $saved }
    }
    if (-not $Code) { throw 'No token stored. Pass -Code with the pairing code shown in Latch.' }
    $payload = @{ code = $Code } | ConvertTo-Json
    $response = Invoke-RestMethod -Uri "$base/pair" -Method Post -ContentType 'application/json; charset=utf-8' -Body $payload
    $response.token | Set-Content -Path $TokenFile -NoNewline
    return $response.token
}

$token = Get-Token
$headers = @{ Authorization = "Bearer $token" }

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Body)
    try {
        $args = @{ Uri = "$base$Path"; Method = $Method; Headers = $headers }
        if ($Body) { $args.ContentType = 'application/json; charset=utf-8'; $args.Body = $Body }
        return Invoke-RestMethod @args
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 401) {
            Remove-Item $TokenFile -Force -ErrorAction SilentlyContinue
            throw 'Token rejected (gateway restarted or pairing ended). Reconnect with -Code.'
        }
        throw
    }
}

switch ($Command) {
    'status' {
        $state = Invoke-Api -Method Get -Path '/api/state'
        $state.groups | Format-Table id, name, enabled, rules, applied, drifted, failed -AutoSize | Out-String -Width 200
        "SUMMARY groups=$($state.summary.groups) rules=$($state.summary.rules) applied=$($state.summary.applied) drifted=$($state.summary.drifted) failed=$($state.summary.failed)"
        "SHIZUKU $($state.shizuku)  GATEWAY $($state.gateway.status)"
    }
    'info' {
        (Invoke-WebRequest -Uri "$base/meta").Content
        "`n--- API ---"
        (Invoke-WebRequest -Uri "$base/api" -Headers $headers).Content
    }
    'rules' {
        (Invoke-WebRequest -Uri "$base/api/rules" -Headers $headers).Content
    }
    'apply' {
        if (-not $Group) { throw 'apply requires -Group <groupId>' }
        $result = Invoke-Api -Method Post -Path "/api/groups/$Group/apply"
        $applied = @($result.results | Where-Object state -eq 'APPLIED').Count
        "APPLIED $applied/$($result.results.Count) for $Group"
        $result.results | Where-Object state -ne 'APPLIED' |
            Format-Table ruleId, state, expected, actual -AutoSize | Out-String -Width 200
    }
    'verify' {
        $result = Invoke-Api -Method Post -Path '/api/verify'
        $applied = @($result.results | Where-Object state -eq 'APPLIED').Count
        "VERIFIED applied=$applied total=$($result.results.Count)"
        $result.results | Where-Object state -ne 'APPLIED' |
            Format-Table ruleId, state, expected, actual -AutoSize | Out-String -Width 200
    }
    'repair' {
        $result = Invoke-Api -Method Post -Path '/api/repair'
        "REPAIRED checked=$($result.checked) repaired=$($result.repaired) remainingDrifted=$($result.remainingDrifted) remainingFailed=$($result.remainingFailed)"
    }
    'enable' {
        if (-not $Rule) { throw 'enable requires -Rule <ruleId>' }
        $body = @{ enabled = $true } | ConvertTo-Json
        Invoke-Api -Method Post -Path "/api/rules/$Rule/enabled" -Body $body | Out-Null
        "ENABLED $Rule"
    }
    'disable' {
        if (-not $Rule) { throw 'disable requires -Rule <ruleId>' }
        $body = @{ enabled = $false } | ConvertTo-Json
        Invoke-Api -Method Post -Path "/api/rules/$Rule/enabled" -Body $body | Out-Null
        "DISABLED $Rule"
    }
    'enable-group' {
        if (-not $Group) { throw 'enable-group requires -Group <groupId>' }
        $body = @{ enabled = $true } | ConvertTo-Json
        Invoke-Api -Method Post -Path "/api/groups/$Group/enabled" -Body $body | Out-Null
        "ENABLED GROUP $Group"
    }
    'disable-group' {
        if (-not $Group) { throw 'disable-group requires -Group <groupId>' }
        $body = @{ enabled = $false } | ConvertTo-Json
        Invoke-Api -Method Post -Path "/api/groups/$Group/enabled" -Body $body | Out-Null
        "DISABLED GROUP $Group"
    }
    'import' {
        if (-not $File) { throw 'import requires -File <rule document json>' }
        if (-not (Test-Path $File)) { throw "File not found: $File" }
        $result = Invoke-Api -Method Post -Path '/api/import' -Body (Get-Content -Path $File -Raw)
        "IMPORTED groups=$($result.groups) rules=$($result.rules)"
    }
    'delete' {
        if (-not $Rule) { throw 'delete requires -Rule <ruleId>' }
        Invoke-Api -Method Delete -Path "/api/rules/$Rule" | Out-Null
        "DELETED $Rule"
    }
    'add' {
        if (-not $File) { throw 'add requires -File <json>' }
        if (-not (Test-Path $File)) { throw "File not found: $File" }
        $body = Get-Content -Path $File -Raw
        Invoke-Api -Method Post -Path '/api/rules' -Body $body | ConvertTo-Json -Depth 8
    }
}
