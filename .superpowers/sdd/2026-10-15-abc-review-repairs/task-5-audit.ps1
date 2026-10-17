param([switch]$Package)
$ErrorActionPreference = 'Stop'
$taskMigrations = @(Get-ChildItem -LiteralPath sql -Filter 'V*__*.sql' | Sort-Object { [int]($_.Name -replace '^V(\d+)__.*','$1') })
$taskProof = foreach ($taskMigration in $taskMigrations) {
    $taskVersion = [int]($taskMigration.Name -replace '^V(\d+)__.*','$1')
    $taskBaselines = if ($taskVersion -le 15) { @('6e87d5172b9aca6e9885d483be18493ed399a11f','6e560629a7b9f4be2b33860ad90e6b16268aedee','347adc0') } else { @(git log -1 --diff-filter=A --format=%H -- ('sql/' + $taskMigration.Name)) }
    $taskNewBlob = git rev-parse ('HEAD:sql/' + $taskMigration.Name)
    $taskCompared = @()
    foreach ($taskBaseline in $taskBaselines) {
        git cat-file -e ($taskBaseline + ':sql/' + $taskMigration.Name) 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        $taskOldBlob = git rev-parse ($taskBaseline + ':sql/' + $taskMigration.Name)
        if ($LASTEXITCODE -ne 0 -or $taskOldBlob -ne $taskNewBlob) { throw ('Committed migration byte mismatch: ' + $taskMigration.Name) }
        $taskCompared += $taskBaseline
    }
    if ($taskCompared.Count -eq 0 -or ($taskVersion -le 15 -and '347adc0' -notin $taskCompared)) { throw 'Migration union/first-add baseline missing' }
    $taskWorkingBlob = git hash-object -- $taskMigration.FullName
    if ($LASTEXITCODE -ne 0 -or $taskWorkingBlob -ne $taskNewBlob) { throw ('Working migration content mismatch: ' + $taskMigration.Name) }
    [pscustomobject]@{ file=$taskMigration.Name; baselines=$taskCompared; blob=$taskNewBlob; checkoutSha256=(Get-FileHash -LiteralPath $taskMigration.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$taskProof | ConvertTo-Json -Depth 3
if ($Package) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskJar = [IO.Compression.ZipFile]::OpenRead((Join-Path (Get-Location) 'backend/target/backend-0.0.1-SNAPSHOT.jar'))
    try {
        foreach ($taskMigration in $taskMigrations) {
            $taskEntry = $taskJar.GetEntry('BOOT-INF/classes/db/migration/' + $taskMigration.Name)
            if ($null -eq $taskEntry) { throw 'Packaged migration missing' }
            $taskStream = $taskEntry.Open()
            try { $taskHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskStream)).ToLowerInvariant() }
            finally { $taskStream.Dispose() }
            if ($taskHash -ne (Get-FileHash -LiteralPath $taskMigration.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Packaged migration differs' }
        }
        $taskForbidden = @($taskJar.Entries | Where-Object { $_.FullName -match '(application-test|h2-[0-9]|/test-classes/|/\.superpowers/|CONNECTION\.zip)' })
        if ($taskForbidden.Count -ne 0) { throw 'Unexpected test/internal artifact packaged' }
        [pscustomobject]@{ packagedMigrations=$taskMigrations.Count; forbiddenArtifacts=$taskForbidden.Count; jarSha256=(Get-FileHash -LiteralPath 'backend/target/backend-0.0.1-SNAPSHOT.jar' -Algorithm SHA256).Hash.ToLowerInvariant() } | ConvertTo-Json -Compress
    } finally { $taskJar.Dispose() }
}
# Report candidate locations only, never source values. Placeholders and explicit blank test values are excluded.
$taskConfigs = @(git ls-files | Where-Object { $_ -match '(\.(ya?ml|properties|pem|key)|(^|/)\.env[^/]*)$' })
$taskCandidates = @()
foreach ($taskConfig in $taskConfigs) {
    $taskLineNumber = 0
    foreach ($taskLine in Get-Content -LiteralPath $taskConfig) {
        $taskLineNumber++
        if ($taskLine -match '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{32,}') {
            $taskCandidates += [pscustomobject]@{ path=$taskConfig; line=$taskLineNumber; reason='private-key-or-token-signature'; value='[REDACTED]' }
        } elseif ($taskLine -match '^\s*(password|secret|api[-_]?key|access[-_]?key|token)\s*[:=]\s*(.+?)\s*$') {
            $taskValue = $Matches[2].Trim().Trim('"').Trim("'")
            if ($taskValue -and $taskValue -notmatch '^\$\{' -and $taskValue -notmatch '^<[^>]+>$') {
                $taskCandidates += [pscustomobject]@{ path=$taskConfig; line=$taskLineNumber; reason='literal-sensitive-setting-review'; value='[REDACTED]' }
            }
        }
    }
}
[pscustomobject]@{ trackedConfigs=$taskConfigs.Count; candidates=$taskCandidates; archiveTracked=[bool](git ls-files -- CONNECTION.zip) } | ConvertTo-Json -Depth 4
