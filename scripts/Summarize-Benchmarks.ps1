<#
Summarizes complete paired runs from Compare-Benchmarks.ps1. The unit is the
fresh-JVM pair, not the within-fork iteration. A descriptive 95% t interval
is computed on log ratios (no multiplicity correction or production claim).
Requires 2-10 paired rounds per case. All runs are included.
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string] $Directory)
$ErrorActionPreference = 'Stop'
$schedule = Get-Content -LiteralPath (Join-Path $Directory 'schedule.json') -Raw | ConvertFrom-Json
$runs = @(Get-Content -LiteralPath (Join-Path $Directory 'completed.json') -Raw | ConvertFrom-Json)
if ($runs.Count -ne $schedule.schedule.Count) { throw 'The scheduled comparison is incomplete.' }
$t95 = @(0, 12.706205, 4.302653, 3.182446, 2.776445, 2.570582, 2.446912, 2.364624, 2.306004, 2.262157)
$rows = foreach ($group in ($runs | Group-Object caseIndex)) {
    $caseIndex = [int] $group.Name
    $case = ($schedule.schedule | Where-Object caseIndex -eq $caseIndex | Select-Object -First 1).case
    $control = @($group.Group | Where-Object variant -eq 'control' | Sort-Object round)
    $candidate = @($group.Group | Where-Object variant -eq 'candidate' | Sort-Object round)
    $count = $control.Count
    if ($count -ne $candidate.Count -or $count -lt 2 -or $count -gt 10) { throw 'Expected 2-10 complete pairs per case.' }
    $logs = for ($i = 0; $i -lt $count; $i++) {
        if ($control[$i].round -ne $candidate[$i].round) { throw 'Mismatched rounds.' }
        if ($control[$i].unit -ne $control[0].unit -or $candidate[$i].unit -ne $control[0].unit) {
            throw 'Cannot compare different measurement units.'
        }
        [math]::Log($candidate[$i].score / $control[$i].score)
    }
    $mean = ($logs | Measure-Object -Average).Average
    $sumSquares = ($logs | ForEach-Object { ($_ - $mean) * ($_ - $mean) } | Measure-Object -Sum).Sum
    $margin = $t95[$count - 1] * [math]::Sqrt($sumSquares / ($count - 1) / $count)
    [pscustomobject]@{
        benchmark = $case.benchmark
        candidateBenchmark = if ($case.candidateBenchmark) { $case.candidateBenchmark } else { $case.benchmark }
        params = $case.params
        threads = if ($null -ne $case.threads) { [int]$case.threads } else { 1 }
        pairs = $count
        controlScore = ($control.score | Measure-Object -Average).Average
        candidateScore = ($candidate.score | Measure-Object -Average).Average
        unit = $control[0].unit
        changePercent = 100 * ([math]::Exp($mean) - 1)
        lower95Percent = 100 * ([math]::Exp($mean - $margin) - 1)
        upper95Percent = 100 * ([math]::Exp($mean + $margin) - 1)
        pairChangesPercent = @($logs | ForEach-Object { 100 * ([math]::Exp($_) - 1) })
        controlBytes = ($control.bytesPerOperation | Measure-Object -Average).Average
        candidateBytes = ($candidate.bytesPerOperation | Measure-Object -Average).Average
    }
}
ConvertTo-Json -InputObject @($rows) -Depth 6 | Set-Content -LiteralPath (Join-Path $Directory 'summary.json') -Encoding utf8
$rows | Select-Object @{n='benchmark';e={$_.benchmark.Split('.')[-1]}},
    @{n='params';e={$_.params.PSObject.Properties.Value -join '/'}}, threads, pairs,
    @{n='change %';e={[math]::Round($_.changePercent, 2)}},
    @{n='95% lower';e={[math]::Round($_.lower95Percent, 2)}},
    @{n='95% upper';e={[math]::Round($_.upper95Percent, 2)}},
    @{n='B/op before';e={[math]::Round($_.controlBytes)}},
    @{n='B/op after';e={[math]::Round($_.candidateBytes)}} | Format-Table -AutoSize | Out-String -Width 220
