# Signs the Windows files of a stable server release (v* tag) on your own computer with a code signing certificate
# that never leaves its token (a YubiKey PIV slot or any smart card the Windows certificate store sees), and uploads
# them back to the same release under the same names. See docs/fork-setup.md, "Windows code signing".
#
# diw-server-<version>-windows-x86_64.zip: diw-server.exe (the GraalVM native prover) and every other unsigned .exe
# and .dll of its folder and the folders below it are signed, none may be left unsigned, and the archive is built
# again with the same folder. The ZK libraries are compiled into the executable as resources and cannot be
# signed afterwards; the executable unpacks them only when their SHA-256, pinned inside the signed executable, matches.
# diw-server-<version>-all.jar: the Windows ZK libraries inside (zk/native/windows-*/diw_zk.dll, diw_bb5.dll) are
# signed and their .sha256 entries rewritten, since ZkNative checks each library against its entry.
# The prover workflow attaches both files some minutes after the release is created; run this once they are there.
#
# Signs with signtool (Windows SDK) and a certificate in Cert:\CurrentUser\My (with a YubiKey, the YubiKey Smart Card
# Minidriver puts it there while the key is inserted; Windows asks for the PIV PIN when signing), or with your own
# command (-SignCommand, for example a jsign script). Needs the GitHub CLI (gh auth login).
#
#   .\tools\windows-sign.ps1 -Tag v0.5.3
#   .\tools\windows-sign.ps1 -Tag v0.5.3 -SignCommand C:\tools\jsign-yubikey.cmd,"{file}"
#   .\tools\windows-sign.ps1 -Tag v0.5.3 -Thumbprint 0123ABCD... -NoUpload
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$Tag,
    [string]$Repo = "mctea01/diw-wallet-server",
    [string]$Thumbprint,
    [string[]]$SignCommand,
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

# -SignCommand: your own signing command (jsign, a wrapper script around it, another signer) instead of signtool and
# the certificate store. The first element is the program, the rest its arguments; {file} in an argument becomes the
# file to sign, and the file is appended when no argument has {file}. The command signs the file in place (with its
# own timestamp) and exits with 0; -Thumbprint and -TimestampUrl apply to signtool only.
if ($SignCommand) {
    # Only the program: its arguments may hold a PIN or a password.
    Write-Host "Signing with the custom command $($SignCommand[0])"
} else {
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
}
$Signer = $null

function Sign-File([string]$path) {
    if ($SignCommand) {
        $arguments = @($SignCommand | Select-Object -Skip 1 | ForEach-Object { $_.Replace("{file}", $path) })
        if (-not ($SignCommand | Where-Object { $_.Contains("{file}") })) { $arguments += $path }
        Invoke-Checked $SignCommand[0] $arguments
    } else {
        Invoke-Checked $signtool @("sign", "/sha1", $cert.Thumbprint, "/fd", "SHA256", "/tr", $TimestampUrl, "/td", "SHA256",
            "/d", "diw-server", $path)
    }
    # Whatever signed it, the file must now carry an Authenticode signature over its current content.
    $signature = Get-AuthenticodeSignature $path
    if (-not $signature.SignerCertificate -or $signature.Status -in @("NotSigned", "HashMismatch", "NotSupportedFileFormat")) {
        throw "$path has no valid signature after signing ($($signature.Status))"
    }
    if ($signature.Status -ne "Valid") { Write-Warning "$([IO.Path]::GetFileName($path)): $($signature.Status), $($signature.StatusMessage)" }
    $script:Signer = $signature.SignerCertificate
}

# Every .exe and .dll in a folder and all folders below it, by extension (not -Include, whose matching depends on the
# PowerShell version and the form of the path).
function Get-Binaries([string]$dir) {
    @(Get-ChildItem -LiteralPath $dir -Recurse -File -Force | Where-Object { $_.Extension -in @(".exe", ".dll") } | Sort-Object FullName)
}

function Get-Relative([string]$dir, [string]$path) {
    if ($path.StartsWith($dir, [StringComparison]::OrdinalIgnoreCase)) { $path.Substring($dir.Length).TrimStart('\') } else { $path }
}

# Signs each .exe and .dll of a folder tree that has no valid signature yet, then fails when any is still unsigned.
function Sign-Folder([string]$dir, [string]$what) {
    $all = Get-Binaries $dir
    $todo = @($all | Where-Object { (Get-AuthenticodeSignature $_.FullName).Status -ne "Valid" })
    Write-Host "Signing $($todo.Count) of $($all.Count) .exe and .dll files of $what"
    foreach ($b in $todo) {
        Write-Host "  $(Get-Relative $dir $b.FullName)"
        Sign-File $b.FullName
    }
    $left = @(Get-Binaries $dir | Where-Object { (Get-AuthenticodeSignature $_.FullName).Status -ne "Valid" })
    if ($left.Count -gt 0) {
        $left | ForEach-Object { Write-Host "  unsigned: $(Get-Relative $dir $_.FullName)" }
        throw "$($left.Count) files of $what have no valid signature"
    }
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

# The native prover: sign the executable and whatever .exe or .dll is with it, then the same archive layout again (one
# folder named like the archive).
$unpacked = Join-Path $WorkDir "zip"
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $download $zipName), $unpacked)
$exe = Get-ChildItem $unpacked -Recurse -Filter "diw-server.exe" | Select-Object -First 1
if (-not $exe) { throw "diw-server.exe not found in $zipName" }
Sign-Folder $exe.Directory.FullName $zipName
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
    $subject = $Signer.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false)
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
