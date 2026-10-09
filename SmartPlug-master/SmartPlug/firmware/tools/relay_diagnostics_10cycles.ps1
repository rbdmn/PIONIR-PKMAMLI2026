param(
    [int]$Attempts = 10,
    [string]$Ssid,
    [string]$AdminPassword,
    [string]$OutputPath = 'D:\IoT\SmartPlug\firmware\artifacts\relay-diagnostics-10cycles.json'
)

$ErrorActionPreference = 'Stop'
$BaseUri = 'http://192.168.4.1'
$Interface = 'Wi-Fi'
$results = [System.Collections.Generic.List[object]]::new()

function Connect-DeviceAp {
    netsh wlan connect "name=$Ssid" "interface=$Interface" | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $deadline) {
        & ping.exe -n 1 -w 1000 192.168.4.1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function New-AdminSession {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $body = "username=admin&password=$([uri]::EscapeDataString($AdminPassword))"
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUri/api/v1/auth/login" -ContentType 'application/x-www-form-urlencoded' -Body $body -WebSession $session -TimeoutSec 5
    $script:CsrfToken = $login.csrf_token
    return $session
}

function Get-Diagnostics([Microsoft.PowerShell.Commands.WebRequestSession]$Session) {
    return Invoke-RestMethod -Uri "$BaseUri/api/v1/diagnostics" -WebSession $Session -TimeoutSec 5
}

function Get-EventCount($Diagnostics, [string]$Name) {
    return @($Diagnostics.events | Where-Object { $_.event -eq $Name }).Count
}

for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    $row = [ordered]@{
        attempt = $attempt
        started_at = (Get-Date).ToString('s')
        precheck = 'unknown'
        command_transport = 'not_sent'
        ap_lost = $false
        recovery_seconds = $null
        postcheck = 'not_available'
        reset_reason = $null
        uptime_ms = $null
        relay_state = $null
        off_queued = $null
        off_pulse_started = $null
        off_pulse_completed = $null
        note = $null
    }

    try {
        if (-not (Connect-DeviceAp)) {
            $row.precheck = 'device_not_reachable'
            $row.note = 'Stopped: AP did not recover before the next attempt.'
            $results.Add([pscustomobject]$row)
            break
        }
        $session = New-AdminSession
        $before = Get-Diagnostics $session
        $row.precheck = 'ok'
        if ($before.relay.state -ne 'on') {
            $row.note = "Stopped: expected relay on before controlled OFF, got $($before.relay.state)."
            $results.Add([pscustomobject]$row)
            break
        }

        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            Invoke-RestMethod -Method Post -Uri "$BaseUri/api/v1/relay" -ContentType 'application/x-www-form-urlencoded' -Headers @{ 'X-CSRF-Token' = $script:CsrfToken } -Body 'state=off' -WebSession $session -TimeoutSec 5 | Out-Null
            $row.command_transport = 'response_received'
        } catch {
            $row.command_transport = 'connection_lost_after_command'
        }

        Start-Sleep -Milliseconds 1200
        & ping.exe -n 1 -w 1000 192.168.4.1 | Out-Null
        $row.ap_lost = $LASTEXITCODE -ne 0
        if (-not (Connect-DeviceAp)) {
            $row.recovery_seconds = [math]::Round($clock.Elapsed.TotalSeconds, 1)
            $row.note = 'Stopped: AP did not recover after relay command.'
            $results.Add([pscustomobject]$row)
            break
        }
        $row.recovery_seconds = [math]::Round($clock.Elapsed.TotalSeconds, 1)
        $afterSession = New-AdminSession
        $after = Get-Diagnostics $afterSession
        $row.postcheck = 'ok'
        $row.reset_reason = $after.reset_reason
        $row.uptime_ms = $after.uptime_ms
        $row.relay_state = $after.relay.state
        $row.off_queued = Get-EventCount $after 'relay_off_queued'
        $row.off_pulse_started = @($after.events | Where-Object { $_.event -eq 'coil_pulse_started' -and $_.detail -eq 0 }).Count
        $row.off_pulse_completed = @($after.events | Where-Object { $_.event -eq 'coil_pulse_completed' -and $_.detail -eq 0 }).Count
        if ($row.reset_reason -eq 'External System' -and $row.off_pulse_completed -eq 0) {
            $row.note = 'Reset after OFF pulse started; completion event absent.'
        }
    } catch {
        $row.note = "Harness error: $($_.Exception.Message)"
    }
    $results.Add([pscustomobject]$row)
    Start-Sleep -Seconds 4
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$results | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -Path $OutputPath
$results | ConvertTo-Json -Depth 5
