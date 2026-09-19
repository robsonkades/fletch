param(
    [string] $Root = 'target/local-date-performance',
    [string] $Output = 'docs/benchmark-results/data/local-date.json'
)
$ErrorActionPreference = 'Stop'
$directory = Join-Path $Root 'pairs'
$schedule = Get-Content -LiteralPath (Join-Path $directory 'schedule.json') -Raw | ConvertFrom-Json
$completed = @(Get-Content -LiteralPath (Join-Path $directory 'completed.json') -Raw | ConvertFrom-Json)
if ($completed.Count -ne $schedule.schedule.Count) { throw 'The scheduled comparison is incomplete.' }
$trajectories = foreach ($run in $completed) {
    $resultPath = Join-Path $directory $run.resultFile
    $result = @(Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json)[0]
    $logPath = [IO.Path]::ChangeExtension($resultPath, '.log')
    $warmup = @(Select-String -LiteralPath $logPath -Pattern '^# Warmup Iteration\s+\d+:\s+([0-9.]+)\s+ops/ms' | ForEach-Object {
        [double]::Parse($_.Matches[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
    })
    $scores = @($result.primaryMetric.rawData[0])
    if ($scores.Count -ne $schedule.measurementIterations -or $warmup.Count -ne $schedule.warmupIterations -or $scores.Count -lt 4) {
        throw "Unexpected iteration count: $resultPath"
    }
    [ordered]@{
        round = $run.round; caseIndex = $run.caseIndex; variant = $run.variant
        warmupScores = $warmup; measurementScores = $scores
        allocationBytes = @($result.secondaryMetrics.'gc.alloc.rate.norm'.rawData[0])
        gcCounts = @($result.secondaryMetrics.'gc.count'.rawData[0])
        gcTimeMs = @($result.secondaryMetrics.'gc.time'.rawData[0])
        lastTwoVersusFirstTwoDriftPercent = 100 * (($scores[-2] + $scores[-1]) / ($scores[0] + $scores[1]) - 1)
        jsonSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash
        logSha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
    }
}
$data = [ordered]@{
    baseCommit = '2a05a834ae3781cfdd54b17207af92f66403393f'
    control = 'Before the LocalDate optimization, including the unreleased value APIs; not published 1.3.0.'
    environment = (Get-Content -LiteralPath (Join-Path $Root 'environment.json') -Raw | ConvertFrom-Json)
    artifacts = (Get-Content -LiteralPath (Join-Path $Root 'artifacts/artifact-proof.json') -Raw | ConvertFrom-Json)
    schedule = $schedule
    summary = @(Get-Content -LiteralPath (Join-Path $directory 'summary.json') -Raw | ConvertFrom-Json)
    completed = $completed; trajectories = @($trajectories)
    forksWithAbsoluteDriftOverFivePercent = @($trajectories | Where-Object { [math]::Abs($_.lastTwoVersusFirstTwoDriftPercent) -gt 5 }).Count
    decision = (Get-Content -LiteralPath (Join-Path $Root 'decision.json') -Raw | ConvertFrom-Json)
    validation = (Get-Content -LiteralPath (Join-Path $Root 'validation.json') -Raw | ConvertFrom-Json)
    originalPlanSha256 = (Get-FileHash -LiteralPath (Join-Path $Root 'plan.md') -Algorithm SHA256).Hash
    originalRuntimePatchSha256 = (Get-FileHash -LiteralPath (Join-Path $Root 'runtime.patch') -Algorithm SHA256).Hash
    encoding = 'Published JSON: UTF-8 without BOM and LF newlines. Source and raw-output hashes identify original host bytes.'
}
$json = ConvertTo-Json -InputObject $data -Depth 25
[IO.File]::WriteAllText([IO.Path]::GetFullPath($Output), ($json -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "$($completed.Count) forks; $($data.forksWithAbsoluteDriftOverFivePercent) drift indicators; evidence exported to $Output"
