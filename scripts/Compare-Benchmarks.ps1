<#
Runs paired JMH comparisons with one fresh JVM per jar/case/round.
The order of cases is shuffled with a recorded seed; jar order is reversed
on the next round. Iterations are retained inside their fork, never treated
as independent replications. CasesFile is a JSON array of objects with
"benchmark" (fully qualified or simple benchmark name) and "params" fields.
Optional "candidateBenchmark" compares two methods, including within the same jar.
Optional "threads" sets workers per case (default 1).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $ControlJar,
    [Parameter(Mandatory)][string] $CandidateJar,
    [Parameter(Mandatory)][string] $CasesFile,
    [Parameter(Mandatory)][string] $OutputDirectory,
    [ValidateRange(2, 10)][int] $Rounds = 2,
    [int] $Seed = 20260914,
    [ValidateRange(1, 100)][int] $WarmupIterations = 3,
    [ValidateRange(1, 100)][int] $MeasurementIterations = 5,
    [string] $Java = 'java',
    [string] $JvmArguments = '-Xms256m -Xmx256m',
    [ValidateRange(0, [long]::MaxValue)][long] $AffinityMask = 0
)
$ErrorActionPreference = 'Stop'
$jars = @{
    control = (Resolve-Path -LiteralPath $ControlJar).Path
    candidate = (Resolve-Path -LiteralPath $CandidateJar).Path
}
$cases = @(Get-Content -LiteralPath $CasesFile -Raw | ConvertFrom-Json)
if ($cases.Count -eq 0) { throw 'At least one benchmark case is required.' }
foreach ($case in $cases) {
    if ($null -ne $case.threads -and ($case.threads -isnot [long] -and $case.threads -isnot [int] -or
            $case.threads -lt 1 -or $case.threads -gt 1024)) {
        throw 'Case threads must be an integer between 1 and 1024.'
    }
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a new output directory to preserve previous results.' }
$directory = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$random = [System.Random]::new($Seed)
$schedule = @()
for ($round = 0; $round -lt $Rounds; $round++) {
    $order = @(0..($cases.Count - 1))
    for ($i = $order.Count - 1; $i -gt 0; $i--) {
        $j = $random.Next($i + 1)
        $order[$i], $order[$j] = $order[$j], $order[$i]
    }
    foreach ($index in $order) {
        $variants = if (($index + $round) % 2 -eq 0) { @('control', 'candidate') } else { @('candidate', 'control') }
        foreach ($variant in $variants) {
            $schedule += [pscustomobject]@{ round = $round; caseIndex = $index; variant = $variant; case = $cases[$index] }
        }
    }
}
$runnerProcess = [Diagnostics.Process]::GetCurrentProcess()
$originalAffinity = $null
try {
if ($AffinityMask -ne 0) {
    if (-not $IsWindows) { throw 'AffinityMask currently supports Windows only.' }
    $originalAffinity = $runnerProcess.ProcessorAffinity
    if (($AffinityMask -band $originalAffinity.ToInt64()) -ne $AffinityMask) {
        throw 'Requested CPUs are outside the current process affinity.'
    }
    $runnerProcess.ProcessorAffinity = [IntPtr]::new($AffinityMask)
}
$javaVersion = & $Java -version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Could not run the selected Java executable.' }
$metadata = [ordered]@{
    startedUtc = [DateTime]::UtcNow.ToString('o')
    java = $javaVersion.Trim()
    os = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    processors = [Environment]::ProcessorCount
    seed = $Seed
    rounds = $Rounds
    forksPerInvocation = 1
    threads = @($cases | ForEach-Object { if ($null -ne $_.threads) { [int]$_.threads } else { 1 } } | Sort-Object -Unique)
    warmupIterations = $WarmupIterations
    measurementIterations = $MeasurementIterations
    iterationSeconds = 1
    jvmArguments = $JvmArguments
    affinityMask = $AffinityMask
    effectiveRunnerAffinity = if ($IsWindows) { $runnerProcess.ProcessorAffinity.ToInt64() } else { $null }
    artifacts = @($jars.GetEnumerator() | ForEach-Object {
        @{ variant = $_.Key; path = $_.Value; sha256 = (Get-FileHash -LiteralPath $_.Value -Algorithm SHA256).Hash }
    })
    schedule = $schedule
}
$metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $directory 'schedule.json') -Encoding utf8
$completed = @()
foreach ($job in $schedule) {
    $stem = 'round-{0}-case-{1}-{2}' -f $job.round, $job.caseIndex, $job.variant
    $jsonPath = Join-Path $directory ($stem + '.json')
    $logPath = Join-Path $directory ($stem + '.log')
    $benchmark = if ($job.variant -eq 'candidate' -and $job.case.candidateBenchmark) {
        $job.case.candidateBenchmark
    } else { $job.case.benchmark }
    $threads = if ($null -ne $job.case.threads) { [int]$job.case.threads } else { 1 }
    $arguments = @('-jar', $jars[$job.variant], ([regex]::Escape($benchmark) + '$'),
        '-t', "$threads", '-f', '1', '-wi', "$WarmupIterations", '-i', "$MeasurementIterations",
        '-w', '1s', '-r', '1s', '-prof', 'gc', '-foe', 'true',
        '-jvmArgs', $JvmArguments, '-rf', 'json', '-rff', $jsonPath)
    foreach ($parameter in $job.case.params.PSObject.Properties) {
        $arguments += @('-p', ($parameter.Name + '=' + $parameter.Value))
    }
    Write-Output ('[{0}/{1}] {2}: {3}' -f ($completed.Count + 1), $schedule.Count, $stem, $benchmark)
    $started = [DateTime]::UtcNow
    & $Java @arguments *> $logPath
    if ($LASTEXITCODE -ne 0) { throw "JMH failed; see $logPath" }
    $result = @(Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json)
    if ($result.Count -ne 1 -or $result[0].mode -ne 'thrpt' -or $result[0].threads -ne $threads -or
            $result[0].forks -ne 1 -or -not $result[0].benchmark.EndsWith($benchmark) -or
            -not [double]::IsFinite($result[0].primaryMetric.score) -or
            $result[0].primaryMetric.score -le 0) {
        throw "Expected one finite positive throughput result in $jsonPath"
    }
    $completed += [pscustomobject]@{
        round = $job.round; caseIndex = $job.caseIndex; variant = $job.variant
        benchmark = $benchmark; threads = $threads
        startedUtc = $started.ToString('o'); endedUtc = [DateTime]::UtcNow.ToString('o')
        resultFile = [System.IO.Path]::GetFileName($jsonPath)
        score = $result[0].primaryMetric.score; unit = $result[0].primaryMetric.scoreUnit
        bytesPerOperation = $result[0].secondaryMetrics.'gc.alloc.rate.norm'.score
    }
    $completed | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $directory 'completed.json') -Encoding utf8
}
} finally {
    if ($null -ne $originalAffinity) { $runnerProcess.ProcessorAffinity = $originalAffinity }
    $runnerProcess.Dispose()
}
