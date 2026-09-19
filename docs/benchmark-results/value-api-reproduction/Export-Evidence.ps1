param([string] $Root = 'target/value-api-performance', [string] $Output = 'docs/benchmark-results/data')
$ErrorActionPreference = 'Stop'
function Write-Json($value, [string] $path) {
    $json = ConvertTo-Json -InputObject $value -Depth 20
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($path), ($json -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
$manifest = @()
foreach ($batch in @('regression', 'features')) {
    $directory = Join-Path $Root $batch
    $schedule = Get-Content (Join-Path $directory 'schedule.json') -Raw | ConvertFrom-Json
    $completed = @(Get-Content (Join-Path $directory 'completed.json') -Raw | ConvertFrom-Json)
    if ($completed.Count -ne $schedule.schedule.Count) { throw "Incomplete batch: $batch" }
    $summary = @(Get-Content (Join-Path $directory 'summary.json') -Raw | ConvertFrom-Json)
    $trajectories = foreach ($run in $completed) {
        $resultPath = Join-Path $directory $run.resultFile
        $result = @(Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json)[0]
        $logPath = [IO.Path]::ChangeExtension($resultPath, '.log')
        $warmup = @(Select-String -LiteralPath $logPath -Pattern '^# Warmup Iteration\s+\d+:\s+([0-9.]+)\s+ops/ms' | ForEach-Object {
            [double]::Parse($_.Matches[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        })
        $scores = @($result.primaryMetric.rawData[0])
        if ($scores.Count -ne 5 -or $warmup.Count -ne 3) { throw "Unexpected iteration count: $resultPath" }
        $drift = 100 * (($scores[3] + $scores[4]) / ($scores[0] + $scores[1]) - 1)
        [ordered]@{
            round = $run.round; caseIndex = $run.caseIndex; variant = $run.variant
            warmupScores = $warmup; measurementScores = $scores
            allocationBytes = @($result.secondaryMetrics.'gc.alloc.rate.norm'.rawData[0])
            gcCounts = @($result.secondaryMetrics.'gc.count'.rawData[0])
            gcTimeMs = @($result.secondaryMetrics.'gc.time'.rawData[0])
            secondHalfDriftPercent = $drift
            jsonSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash
            logSha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
        }
    }
    $data = [ordered]@{
        schedule = $schedule; summary = $summary; completed = $completed
        trajectories = @($trajectories)
        forksWithAbsoluteDriftOverFivePercent = @($trajectories | Where-Object { [math]::Abs($_.secondHalfDriftPercent) -gt 5 }).Count
    }
    $published = Join-Path $Output "value-api-$batch.json"
    Write-Json $data $published
    $manifest += @{ path = $published; normalizedJsonSha256 = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash }
    Write-Output "$batch`: $($completed.Count) forks; $($data.forksWithAbsoluteDriftOverFivePercent) drift indicators"
}
$provenance = [ordered]@{
    runtimeCommit = '8ac74fec5a3b7561b0568bf851d3aaab4aecbb26'
    baseline = 'Published Fletch 1.3.0 from Maven Central'
    environment = (Get-Content (Join-Path $Root 'environment.json') -Raw | ConvertFrom-Json)
    artifacts = (Get-Content (Join-Path $Root 'artifacts-verified/artifact-proof.json') -Raw | ConvertFrom-Json)
    effectiveJvmFlags = @(Select-String -Path (Join-Path $Root 'effective-jvm-flags.txt') -Pattern 'ActiveProcessorCount|CICompilerCount\s|ConcGCThreads|InitialHeapSize|MaxHeapSize\s|ParallelGCThreads|UseCompressedOops|UseG1GC' | ForEach-Object { $_.Line.Trim() })
    publishedData = $manifest
    publishedDataEncoding = 'UTF-8 without BOM, LF newlines; normalize checkout line endings before checking published JSON hashes.'
    experimentPlanSha256 = (Get-FileHash -LiteralPath (Join-Path $Root 'experiment-plan.md') -Algorithm SHA256).Hash
}
Write-Json $provenance (Join-Path $Output 'value-api-provenance.json')
