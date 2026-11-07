#Requires -Version 7.0
<#
.SYNOPSIS
    Agent-side CLI for Latch. Reads/writes the app's rule document and verifies it
    against the device state through adb.

.DESCRIPTION
    Latch stores rules in its private files directory. On debug builds the agent can read
    and replace that document with `run-as`, which makes rules fully scriptable without
    adding a network surface to the app.

    Commands:
      status   Print every rule with expected/actual state and a summary (default).
      apply    Apply enabled rules through adb shell (same command mapping as the app).
      export   Copy the rule document out to a local JSON file.
      import   Replace the rule document from a local JSON file and restart the app.

.PARAMETER Serial
    Target device serial. Optional when exactly one device is connected.

.PARAMETER File
    Local JSON path for import.

.PARAMETER Out
    Local JSON path for export.

.PARAMETER DryRun
    With apply: print commands without executing them.

.PARAMETER Json
    With status: print a machine-readable result instead of a table.

.EXAMPLE
    ./latch-agent.ps1 status
    ./latch-agent.ps1 apply -DryRun
    ./latch-agent.ps1 export -Out ./backup.json
    ./latch-agent.ps1 import -File ./rules.json
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'apply', 'export', 'import')]
    [string]$Command = 'status',

    [string]$Serial = '',
    [string]$File = '',
    [string]$Out = '',
    [switch]$DryRun,
    [switch]$Json,
    [switch]$All,
    [switch]$FailOnDrift
)

$ErrorActionPreference = 'Stop'
$Package = 'dev.latch.rule'
$RulesPath = 'files/rules.json'

$AdbArgs = @()
if ($Serial) { $AdbArgs += @('-s', $Serial) }

function Invoke-Shell([string]$CommandLine) {
    $output = & adb @AdbArgs shell $CommandLine 2>$null
    return ($output -join "`n")
}

function Resolve-Target {
    $online = @(
        & adb devices 2>$null |
            Select-String -Pattern '^(\S+)\s+device$' |
            ForEach-Object { $_.Matches[0].Groups[1].Value }
    )
    if ($online.Count -eq 0) { throw 'No authorized adb device found.' }
    if ($Serial) {
        if ($online -notcontains $Serial) { throw "Device $Serial is not online." }
        $script:AdbArgs = @('-s', $Serial)
        return
    }
    # USB entries are preferred over the same device exposed through wireless debugging.
    $usb = @($online | Where-Object { $_ -notmatch '_adb-tls-connect' })
    if ($usb.Count -eq 1) {
        $script:AdbArgs = @('-s', $usb[0])
        return
    }
    if ($usb.Count -gt 1) { throw 'Multiple USB devices; pass -Serial.' }
    if ($online.Count -eq 1) {
        $script:AdbArgs = @('-s', $online[0])
        return
    }
    throw 'Multiple online devices; pass -Serial.'
}

function Read-Rules {
    $raw = Invoke-Shell "run-as $Package cat $RulesPath"
    if ([string]::IsNullOrWhiteSpace($raw)) { throw 'Rule document is empty or missing.' }
    return $raw
}

function Get-Expected($rule) {
    $cap = $rule.capability
    switch ($cap.type) {
        'appOp' { return $cap.mode }
        'revokePermission' { return 'granted=false' }
        'forceStop' { return 'stopped' }
        'setEnabled' { return $(if ($cap.enabled) { 'enabled' } else { 'disabled' }) }
        default { return 'unknown' }
    }
}

function Get-Actual($rule) {
    $cap = $rule.capability
    $pkg = $rule.targetPackage
    switch ($cap.type) {
        'appOp' {
            $out = Invoke-Shell "cmd appops get $pkg $($cap.op)"
            $patterns = @(
                "Uid mode:\s*$($cap.op):\s*([A-Za-z_]+)",
                "(?m)^\s*$($cap.op):\s*([A-Za-z_]+)",
                'Default mode:\s*([A-Za-z_]+)'
            )
            foreach ($pattern in $patterns) {
                $match = [regex]::Match($out, $pattern)
                if ($match.Success) { return $match.Groups[1].Value }
            }
            return 'unknown'
        }
        'revokePermission' {
            $perm = $cap.permission
            $out = Invoke-Shell "dumpsys package $pkg | grep -F '${perm}: granted' || true"
            $match = [regex]::Match($out, 'granted=(true|false)')
            return $(if ($match.Success) { "granted=$($match.Groups[1].Value)" } else { 'unknown' })
        }
        'forceStop' {
            $out = Invoke-Shell "ps -A -o NAME | grep -F '$pkg' || true"
            return $(if ([string]::IsNullOrWhiteSpace($out)) { 'stopped' } else { 'running' })
        }
        'setEnabled' {
            $out = Invoke-Shell "dumpsys package $pkg | grep -E 'User 0:.*enabled=' || true"
            $match = [regex]::Match($out, 'enabled=(\d)')
            return $(if ($match.Success -and $match.Groups[1].Value -eq '1') { 'enabled' } else { 'disabled' })
        }
        default { return 'unknown' }
    }
}

function Get-ApplyCommand($rule) {
    $cap = $rule.capability
    $pkg = $rule.targetPackage
    switch ($cap.type) {
        'appOp' {
            $uidFlag = if ($cap.scope -eq 'uid') { ' --uid' } else { '' }
            return "cmd appops set$uidFlag $pkg $($cap.op) $($cap.mode)"
        }
        'revokePermission' { return "pm revoke --user 0 $pkg $($cap.permission)" }
        'forceStop' { return "am force-stop $pkg" }
        'setEnabled' {
            return $(if ($cap.enabled) { "pm enable $pkg" } else { "pm disable-user --user 0 $pkg" })
        }
        default { return $null }
    }
}

function Get-Rules {
    $doc = (Read-Rules) | ConvertFrom-Json
    foreach ($group in $doc.groups) {
        foreach ($rule in $group.rules) {
            [pscustomobject]@{
                Group         = $group.name
                GroupEnabled  = [bool]$group.enabled
                Id            = $rule.id
                Name          = $rule.name
                Target        = $rule.targetPackage
                Enabled       = [bool]$rule.enabled
                Expected      = Get-Expected $rule
                Actual        = Get-Actual $rule
                Rule          = $rule
            }
        }
    }
}

Resolve-Target

switch ($Command) {
    'status' {
        $rows = @(Get-Rules)
        $result = $rows | ForEach-Object {
            [pscustomobject]@{
                group    = $_.Group
                rule     = $_.Name
                target   = $_.Target
                enabled  = $_.Enabled
                expected = $_.Expected
                actual   = $_.Actual
                state    = if (-not $_.Enabled -or -not $_.GroupEnabled) { 'disabled' } elseif ($_.Expected -eq $_.Actual) { 'applied' } else { 'drifted' }
            }
        }
        $groups = $result | Group-Object group | ForEach-Object {
            [pscustomobject]@{
                group    = $_.Name
                rules    = $_.Count
                applied  = @($_.Group | Where-Object state -eq 'applied').Count
                drifted  = @($_.Group | Where-Object state -eq 'drifted').Count
                disabled = @($_.Group | Where-Object state -eq 'disabled').Count
            }
        }
        $summary = [pscustomobject]@{
            groups   = @($groups).Count
            rules    = $result.Count
            applied  = @($result | Where-Object state -eq 'applied').Count
            drifted  = @($result | Where-Object state -eq 'drifted').Count
            disabled = @($result | Where-Object state -eq 'disabled').Count
        }
        $details = $result | Where-Object { $All -or $_.state -eq 'drifted' } |
            Select-Object group, rule, target, expected, actual, state

        if ($Json) {
            @{ summary = $summary; groups = $groups; rules = $result; drifted = $details } | ConvertTo-Json -Depth 6
        } else {
            "GROUPS"
            $groups | Format-Table group, rules, applied, drifted, disabled -AutoSize | Out-String -Width 200
            if ($details) {
                "DRIFTED"
                $details | Format-Table group, rule, target, expected, actual -AutoSize | Out-String -Width 220
            }
            "SUMMARY groups=$($summary.groups) rules=$($summary.rules) applied=$($summary.applied) drifted=$($summary.drifted) disabled=$($summary.disabled)"
        }
        if ($FailOnDrift -and $summary.drifted -gt 0) { exit 1 }
    }
    'apply' {
        $pending = @(Get-Rules | Where-Object { $_.Enabled -and $_.Expected -ne $_.Actual })
        if ($pending.Count -eq 0) { 'Nothing to apply.'; break }
        foreach ($row in $pending) {
            $commandLine = Get-ApplyCommand $row.Rule
            if (-not $commandLine) { continue }
            if ($DryRun) { "DRY-RUN $commandLine"; continue }
            Invoke-Shell $commandLine | Out-Null
            $actual = Get-Actual $row.Rule
            $state = if ($actual -eq $row.Expected) { 'applied' } else { 'drifted' }
            "$state  $($row.Group) / $($row.Name)  expected=$($row.Expected) actual=$actual"
        }
    }
    'export' {
        if (-not $Out) { throw 'export requires -Out <path>.' }
        Read-Rules | Set-Content -Path $Out -Encoding utf8
        "Exported to $Out"
    }
    'import' {
        if (-not $File) { throw 'import requires -File <path>.' }
        if (-not (Test-Path $File)) { throw "File not found: $File" }
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $File))
        $base64 = [Convert]::ToBase64String($bytes)
        Invoke-Shell "am force-stop $Package" | Out-Null
        Invoke-Shell "run-as $Package sh -c 'echo $base64 | base64 -d > $RulesPath'" | Out-Null
        Invoke-Shell "am start -n $Package/.MainActivity" | Out-Null
        "Imported $File and restarted $Package"
    }
}
