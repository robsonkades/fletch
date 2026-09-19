param(
    [string] $Root = 'target/value-api-improvements',
    [string] $Output = 'docs/benchmark-results/data',
    [string] $PriorRoot = 'target/value-api-performance'
)
$ErrorActionPreference = 'Stop'
function Write-Json($value, [string] $path) {
    $json = ConvertTo-Json -InputObject $value -Depth 25
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($path), ($json -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
$manifest = @()
foreach ($batch in @('mapping-repeat', 'probe-pairs', 'dispatch-pairs')) {
    $directory = Join-Path $Root $batch
    $schedule = Get-Content -LiteralPath (Join-Path $directory 'schedule.json') -Raw | ConvertFrom-Json
    $completed = @(Get-Content -LiteralPath (Join-Path $directory 'completed.json') -Raw | ConvertFrom-Json)
    if ($completed.Count -ne $schedule.schedule.Count) { throw "Incomplete batch: $batch" }
    $summary = @(Get-Content -LiteralPath (Join-Path $directory 'summary.json') -Raw | ConvertFrom-Json)
    $trajectories = foreach ($run in $completed) {
        $resultPath = Join-Path $directory $run.resultFile
        $result = @(Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json)[0]
        $logPath = [IO.Path]::ChangeExtension($resultPath, '.log')
        $warmup = @(Select-String -LiteralPath $logPath -Pattern '^# Warmup Iteration\s+\d+:\s+([0-9.]+)\s+ops/ms' | ForEach-Object {
            [double]::Parse($_.Matches[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        })
        $scores = @($result.primaryMetric.rawData[0])
        if ($scores.Count -ne $schedule.measurementIterations -or $warmup.Count -ne $schedule.warmupIterations) {
            throw "Unexpected iteration count: $resultPath"
        }
        if ($scores.Count -lt 4) { throw 'The drift indicator requires at least four measurement iterations.' }
        $drift = 100 * (($scores[-2] + $scores[-1]) / ($scores[0] + $scores[1]) - 1)
        [ordered]@{
            round = $run.round; caseIndex = $run.caseIndex; variant = $run.variant
            warmupScores = $warmup; measurementScores = $scores
            allocationBytes = @($result.secondaryMetrics.'gc.alloc.rate.norm'.rawData[0])
            gcCounts = @($result.secondaryMetrics.'gc.count'.rawData[0])
            gcTimeMs = @($result.secondaryMetrics.'gc.time'.rawData[0])
            lastTwoVersusFirstTwoDriftPercent = $drift
            jsonSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash
            logSha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
        }
    }
    $data = [ordered]@{
        schedule = $schedule; summary = $summary; completed = $completed
        trajectories = @($trajectories)
        forksWithAbsoluteDriftOverFivePercent = @($trajectories | Where-Object { [math]::Abs($_.lastTwoVersusFirstTwoDriftPercent) -gt 5 }).Count
    }
    $published = Join-Path $Output "value-api-improvements-$batch.json"
    Write-Json $data $published
    $manifest += @{ path = $published; normalizedJsonSha256 = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash }
    Write-Output "$batch`: $($completed.Count) forks; $($data.forksWithAbsoluteDriftOverFivePercent) drift indicators"
}
$diagnostics = foreach ($capture in (Get-Content -LiteralPath (Join-Path $Root 'jit-analysis.json') -Raw | ConvertFrom-Json)) {
    [ordered]@{
        source = $capture.source; sha256 = $capture.sha256; c2Compilations = $capture.c2Compilations
        converterDispatchDecisions = @($capture.libraryCalls | Where-Object { $_.callee -match 'TypeConverter::convert(NonString)?$' })
    }
}
$provenance = [ordered]@{
    baseCommit = '38d89b79066a27a299302d38531567b044df0239'
    baseRuntimeCommit = '8ac74fec5a3b7561b0568bf851d3aaab4aecbb26'
    mappingControl = 'Published Fletch 1.3.0 from Maven Central'
    prototypeControls = 'Unmodified value-API runtime at the base commit, not published 1.3.0'
    probeEnvironment = (Get-Content -LiteralPath (Join-Path $Root 'environment.json') -Raw | ConvertFrom-Json)
    dispatchSourceSnapshot = (Get-Content -LiteralPath (Join-Path $Root 'dispatch-source.json') -Raw | ConvertFrom-Json)
    mappingArtifacts = (Get-Content -LiteralPath (Join-Path $PriorRoot 'artifacts-verified/artifact-proof.json') -Raw | ConvertFrom-Json)
    probeArtifacts = (Get-Content -LiteralPath (Join-Path $Root 'probe-artifacts/artifact-proof.json') -Raw | ConvertFrom-Json)
    dispatchArtifacts = (Get-Content -LiteralPath (Join-Path $Root 'dispatch-artifacts/artifact-proof.json') -Raw | ConvertFrom-Json)
    effectiveDiagnosticJvmFlags = @(Select-String -LiteralPath (Join-Path $Root 'diagnostic-live-flags.txt') -Pattern 'CICompilerCount\s|FreqInlineSize\s|InlineSmallCode\s|MaxInlineSize\s|TieredCompilation\s|TieredStopAtLevel\s|InitialHeapSize\s|MaxHeapSize\s|ParallelGCThreads\s|UseG1GC\s' | ForEach-Object { $_.Line.Trim() })
    diagnostics = @($diagnostics)
    diagnosticScope = 'Four unscored fresh JVMs; C2 compile decisions are not dynamic call counts or CPU percentages. Diagnostic throughput is excluded from adoption decisions.'
    decisions = (Get-Content -LiteralPath (Join-Path $Root 'decisions.json') -Raw | ConvertFrom-Json)
    finalValidation = (Get-Content -LiteralPath (Join-Path $Root 'final-validation.json') -Raw | ConvertFrom-Json)
    originalPlansAndPatches = @(@('plan.md', 'dispatch-plan.md', 'probe.patch', 'dispatch.patch') | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $Root $_) -Algorithm SHA256).Hash }
    })
    publishedData = $manifest
    publishedDataEncoding = 'UTF-8 without BOM, LF newlines; normalize checkout line endings before checking published JSON hashes.'
}
Write-Json $provenance (Join-Path $Output 'value-api-improvements-provenance.json')
