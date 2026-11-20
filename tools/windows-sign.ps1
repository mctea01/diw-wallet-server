# Signs the Windows files of a stable server release (v* tag) on your own computer with a code signing certificate
# that never leaves its token (a YubiKey PIV slot or any smart card the Windows certificate store sees), and uploads
# them back to the same release under the same names. See docs/fork-setup.md, "Windows code signing".
#
# diw-server-<version>-windows-x86_64.zip: diw-server.exe (the GraalVM native prover) is signed and the archive is
# built again with the same folder. The ZK libraries are compiled into the executable as resources and cannot be
# signed afterwards; the executable unpacks them only when their SHA-256, pinned inside the signed executable, matches.
# diw-server-<version>-all.jar: the Windows ZK libraries inside (zk/native/windows-*/diw_zk.dll, diw_bb5.dll) are
# signed and their .sha256 entries rewritten, since ZkNative checks each library against its entry.
# The prover workflow attaches both files some minutes after the release is created; run this once they are there.
#
# Needs signtool (Windows SDK) and the GitHub CLI (gh auth login). With a YubiKey, the YubiKey Smart Card Minidriver
# puts the certificate into Cert:\CurrentUser\My while the key is inserted; Windows asks for the PIV PIN when signing.
#
#   .\tools\windows-sign.ps1 -Tag v0.5.3
#   .\tools\windows-sign.ps1 -Tag v0.5.3 -Thumbprint 0123ABCD... -NoUpload
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$Tag,
    [string]$Repo = "mctea01/diw-wallet-server",
    [string]$Thumbprint,
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [string]$WorkDir = (Join-Path $env:TEMP "diw-server-sign"),
    [switch]$NoUpload,
    [switch]$NoNotes
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

if ($Tag -notmatch "^v(\d+\.\d+\.\d+)$") { throw "Pass a stable release tag such as v0.5.3" }
$version = $Matches[1]

function Invoke-Checked([string]$exe, [string[]]$arguments) {
    & $exe @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed with exit code $LASTEXITCODE" }
}

$signtool = (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source
if (-not $signtool) {
    foreach ($root in @("${env:ProgramFiles(x86)}\Windows Kits\10\bin", "$env:ProgramFiles\Windows Kits\10\bin")) {
        if (-not $signtool -and (Test-Path $root)) {
            $signtool = Get-ChildItem $root -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match "\\x64\\" } | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
        }
    }
}
if (-not $signtool) { throw "signtool.exe not found: install the Windows SDK (Signing Tools for Desktop Apps)" }

if ($Thumbprint) {
    $Thumbprint = ($Thumbprint -replace "\s", "").ToUpperInvariant()
    $cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq $Thumbprint }
    if (-not $cert) { throw "No certificate $Thumbprint in Cert:\CurrentUser\My (is the YubiKey inserted?)" }
} else {
    $certs = @(Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert | Where-Object { $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) })
    if ($certs.Count -ne 1) {
        $certs | ForEach-Object { Write-Host ("  {0}  {1}  until {2:yyyy-MM-dd}" -f $_.Thumbprint, $_.Subject, $_.NotAfter) }
        throw "Found $($certs.Count) usable code signing certificates: pass -Thumbprint"
    }
    $cert = $certs[0]
}
Write-Host "Signing with $($cert.Subject) ($($cert.Thumbprint)), valid until $($cert.NotAfter.ToString('yyyy-MM-dd'))"

function Sign-File([string]$path) {
    Invoke-Checked $signtool @("sign", "/sha1", $cert.Thumbprint, "/fd", "SHA256", "/tr", $TimestampUrl, "/td", "SHA256",
        "/d", "diw-server", $path)
    Invoke-Checked $signtool @("verify", "/pa", $path)
}

if (Test-Path $WorkDir) { Remove-Item $WorkDir -Recurse -Force }
$download = Join-Path $WorkDir "download"
New-Item -ItemType Directory -Path $download | Out-Null
$zipName = "diw-server-$version-windows-x86_64.zip"
$jarName = "diw-server-$version-all.jar"
foreach ($name in @($zipName, $jarName)) {
    Invoke-Checked "gh" @("release", "download", $Tag, "--repo", $Repo, "--pattern", $name, "--dir", $download)
    if (-not (Test-Path (Join-Path $download $name))) { throw "$name is not in release $Tag yet (the prover workflow attaches it)" }
}
$uploads = @()

# The native prover: sign the executable, then the same archive layout again (one folder named like the archive).
$unpacked = Join-Path $WorkDir "zip"
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $download $zipName), $unpacked)
$exe = Get-ChildItem $unpacked -Recurse -Filter "diw-server.exe" | Select-Object -First 1
if (-not $exe) { throw "diw-server.exe not found in $zipName" }
Sign-File $exe.FullName
$zip = Join-Path $WorkDir $zipName
[IO.Compression.ZipFile]::CreateFromDirectory($exe.Directory.FullName, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
$uploads += $zip

# The jar: extract each Windows library, sign it, put it back with a new .sha256 (lowercase hex and a newline, as
# native/build.sh writes it).
$jar = Join-Path $download $jarName
$temp = Join-Path $WorkDir "jar"
New-Item -ItemType Directory -Path $temp | Out-Null
$archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Update)
$count = 0
try {
    foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -match "^zk/native/windows-[^/]+/[^/]+\.dll$" })) {
        $name = $entry.FullName
        $file = Join-Path $temp ([IO.Path]::GetFileName($name))
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $file, $true)
        if ((Get-AuthenticodeSignature $file).Status -ne "Valid") { Sign-File $file }
        $hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
        $entry.Delete()
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file, $name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        $old = $archive.GetEntry("$name.sha256")
        if ($old) { $old.Delete() }
        $writer = New-Object IO.StreamWriter(($archive.CreateEntry("$name.sha256")).Open(), (New-Object Text.UTF8Encoding $false))
        try { $writer.Write("$hash`n") } finally { $writer.Dispose() }
        Write-Host "  ${jarName}: $name"
        $count++
    }
} finally {
    $archive.Dispose()
}
if ($count -gt 0) { $uploads += $jar } else { Write-Warning "No zk/native/windows-*/ library in $jarName" }

foreach ($f in $uploads) { Write-Host ("{0}  SHA-256 {1}" -f [IO.Path]::GetFileName($f), (Get-FileHash $f -Algorithm SHA256).Hash.ToLowerInvariant()) }
if ($NoUpload) {
    Write-Host "Not uploaded (-NoUpload). Files are in $WorkDir"
    exit 0
}
Invoke-Checked "gh" (@("release", "upload", $Tag) + $uploads + @("--clobber", "--repo", $Repo))
Write-Host "Replaced in release ${Tag}: $(($uploads | ForEach-Object { [IO.Path]::GetFileName($_) }) -join ', ')"

# One line in the release body under the download guide (tools/release-notes.py ends it with the marker sentence).
if (-not $NoNotes) {
    $body = (& gh release view $Tag --repo $Repo --json body --jq .body | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "gh release view failed" }
    $subject = $cert.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false)
    $what = "Windows 原生證明器與 jar 內的 Windows 原生程式庫"
    $line = "$what 已以 $subject 的程式碼簽章憑證簽署（Authenticode）。"
    $marker = "每個檔案的 SHA-256 列在本頁最下方的檔案清單。"
    $lines = @($body.TrimEnd() -split "`r?`n" | Where-Object { -not $_.StartsWith("$what 已以 ") })
    $at = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].EndsWith($marker)) { $at = $i; break } }
    if ($at -ge 0) {
        $rest = if ($at + 1 -lt $lines.Count) { $lines[($at + 1)..($lines.Count - 1)] } else { @() }
        $lines = $lines[0..$at] + @("", $line) + $rest
    } else {
        $lines = @($line, "") + $lines
    }
    $bodyFile = Join-Path $WorkDir "notes.md"
    [IO.File]::WriteAllText($bodyFile, ($lines -join "`n"), (New-Object Text.UTF8Encoding $false))
    Invoke-Checked "gh" @("release", "edit", $Tag, "--repo", $Repo, "--notes-file", $bodyFile)
    Write-Host "Release notes updated"
}
