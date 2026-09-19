param([string] $Directory = 'target/value-api-improvements')
$ErrorActionPreference = 'Stop'
$all = foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter '*-compilation.xml') {
    $xml = [xml][IO.File]::ReadAllText($file.FullName)
    $c2 = @{}
    foreach ($method in $xml.SelectNodes('//nmethod[@compiler="c2"]')) {
        $c2[$method.GetAttribute('compile_id')] = $method
    }
    $calls = foreach ($task in $xml.SelectNodes('//task')) {
        $id = $task.GetAttribute('compile_id')
        if (-not $c2.ContainsKey($id)) { continue }
        $symbols = @{}
        foreach ($symbol in $task.SelectNodes('.//klass | .//method')) {
            $symbols[$symbol.GetAttribute('id')] = $symbol
        }
        foreach ($call in $task.SelectNodes('.//call')) {
            $method = $symbols[$call.GetAttribute('method')]
            if ($null -eq $method) { continue }
            $holder = $symbols[$method.GetAttribute('holder')]
            if ($null -eq $holder -or -not $holder.GetAttribute('name').StartsWith('io.github.robsonkades.fletch.')) { continue }
            $verdict = $call.NextSibling
            while ($null -ne $verdict -and $verdict.LocalName -notin @('inline_success', 'inline_fail', 'call', 'parse', 'bc')) {
                $verdict = $verdict.NextSibling
            }
            if ($null -eq $verdict -or $verdict.LocalName -notin @('inline_success', 'inline_fail')) { continue }
            [ordered]@{
                compileId = $id
                caller = $task.GetAttribute('method')
                installedAtSeconds = [double]$c2[$id].GetAttribute('stamp')
                callee = $holder.GetAttribute('name') + '::' + $method.GetAttribute('name')
                calleeBytes = $method.GetAttribute('bytes')
                inlined = $verdict.LocalName -eq 'inline_success'
                reason = $verdict.GetAttribute('reason')
            }
        }
    }
    [ordered]@{
        source = $file.Name
        sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        c2Compilations = $c2.Count
        latestLibraryC2Seconds = @($c2.Values | Where-Object { $_.GetAttribute('method').StartsWith('io.github.robsonkades.fletch.') } | ForEach-Object { [double]$_.GetAttribute('stamp') } | Measure-Object -Maximum).Maximum
        libraryCalls = @($calls)
    }
}
ConvertTo-Json -InputObject @($all) -Depth 7 | Set-Content (Join-Path $Directory 'jit-analysis.json') -Encoding utf8
foreach ($capture in $all) {
    Write-Output "$($capture.source): $($capture.c2Compilations) C2 compilations; latest library C2 at $($capture.latestLibraryC2Seconds)s"
    $capture.libraryCalls | Where-Object { $_.callee -like '*TypeConverter*' -or $_.callee -like '*XmlMappingEngine*' -or $_.callee -like '*liveLocate' } |
        ForEach-Object { [pscustomobject]$_ } | Group-Object callee,calleeBytes,inlined,reason |
        Select-Object Count,Name | Format-Table -AutoSize | Out-String -Width 170
}
