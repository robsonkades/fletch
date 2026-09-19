param(
    [Parameter(Mandatory)][string] $BenchmarksJar,
    [Parameter(Mandatory)][string] $PublishedJar,
    [Parameter(Mandatory)][string] $CurrentJar,
    [Parameter(Mandatory)][string] $OutputDirectory
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Entry-Hashes([string] $path) {
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $path).Path)
    $hashes = @{}
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            if ($hashes.ContainsKey($entry.FullName)) { throw "Duplicate entry: $($entry.FullName)" }
            $stream = $entry.Open()
            try { $hashes[$entry.FullName] = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
            finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
    return $hashes
}

if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new artifact directory.' }
$output = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$candidate = Join-Path $output 'candidate-benchmarks.jar'
$control = Join-Path $output 'published-benchmarks.jar'
Copy-Item -LiteralPath $BenchmarksJar -Destination $candidate
Copy-Item -LiteralPath $BenchmarksJar -Destination $control
$published = Entry-Hashes $PublishedJar
$current = Entry-Hashes $CurrentJar
$runtimeNames = @($published.Keys + $current.Keys | Sort-Object -Unique | Where-Object { $_ -ne 'META-INF/MANIFEST.MF' })
function Replace-Runtime([string] $benchmark, [string] $library) {
    $baseline = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $library).Path)
    $archive = [IO.Compression.ZipFile]::Open($benchmark, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($name in $runtimeNames) {
            $old = $archive.GetEntry($name)
            if ($null -ne $old) { $old.Delete() }
            $replacement = $baseline.GetEntry($name)
            if ($null -eq $replacement) { continue }
            $created = $archive.CreateEntry($name)
            $created.LastWriteTime = $replacement.LastWriteTime
            $source = $replacement.Open()
            $destination = $created.Open()
            try { $source.CopyTo($destination) }
            finally { $destination.Dispose(); $source.Dispose() }
        }
    } finally { $archive.Dispose(); $baseline.Dispose() }
}
Replace-Runtime $candidate $CurrentJar
Replace-Runtime $control $PublishedJar
$harness = Entry-Hashes $candidate
foreach ($name in $current.Keys) {
    if ($name -ne 'META-INF/MANIFEST.MF' -and $harness[$name] -ne $current[$name]) {
        throw "Candidate does not contain the current library byte-for-byte: $name"
    }
}

$verified = Entry-Hashes $control
$allNames = @($harness.Keys + $verified.Keys | Sort-Object -Unique)
$checkedRuntime = 0
$checkedShared = 0
foreach ($name in $allNames) {
    if ($name -in $runtimeNames) {
        if ($verified[$name] -ne $published[$name]) { throw "Incorrect published runtime entry: $name" }
        $checkedRuntime++
    } else {
        if ($verified[$name] -ne $harness[$name]) { throw "Different harness/dependency entry: $name" }
        $checkedShared++
    }
}
$proof = [ordered]@{
    checkedRuntimeEntries = $checkedRuntime
    identicalOtherEntries = $checkedShared
    manifestPreserved = $verified['META-INF/MANIFEST.MF'] -eq $harness['META-INF/MANIFEST.MF']
    files = @($PublishedJar, $CurrentJar, $candidate, $control | ForEach-Object {
        $item = Get-Item -LiteralPath $_
        @{ path = $item.FullName; bytes = $item.Length; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
    })
}
$proof | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'artifact-proof.json') -Encoding utf8
$proof | ConvertTo-Json -Depth 5
