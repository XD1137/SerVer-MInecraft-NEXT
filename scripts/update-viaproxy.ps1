param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$TargetPath
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Repository = 'ViaVersion/ViaProxy'
$TargetPath = [System.IO.Path]::GetFullPath($TargetPath)
$TargetDirectory = Split-Path -Parent $TargetPath
$StatePath = "$TargetPath.update-state.json"
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nekoproxy-viaproxy-" + [guid]::NewGuid().ToString('N'))
$Headers = @{
    'User-Agent' = 'NekoProxy-AutoUpdater'
    'Accept' = 'application/vnd.github+json'
}

try {
    if ((Test-Path $TargetPath -PathType Leaf) -and (Test-Path $StatePath -PathType Leaf)) {
        try {
            $state = Get-Content -Raw -Path $StatePath | ConvertFrom-Json
            $lastCheck = [DateTimeOffset]::Parse($state.last_checked_utc)
            if (([DateTimeOffset]::UtcNow - $lastCheck).TotalHours -lt 24) {
                Write-Host '[ViaProxy] 最近 24 小时已检查过更新。'
                exit 0
            }
        } catch {
            # Invalid or old state is harmless; perform a fresh check.
        }
    }

    Write-Host '[ViaProxy] 正在查询 ViaProxy 官方最新 Release...'
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/latest" -Headers $Headers
    if (-not $release -or -not $release.tag_name) {
        throw '无法获取 ViaProxy 官方最新 Release 信息。'
    }

    $asset = $release.assets |
        Where-Object { $_.name -match '^ViaProxy-.+\.jar$' -and $_.name -notmatch '\+java8\.jar$' } |
        Select-Object -First 1

    if (-not $asset -or -not $asset.browser_download_url) {
        throw "官方 Release $($release.tag_name) 中没有找到 Java 17+ ViaProxy JAR。"
    }

    if (Test-Path $StatePath -PathType Leaf) {
        try {
            $state = Get-Content -Raw -Path $StatePath | ConvertFrom-Json
            if ($state.version -eq $asset.name -and $state.release_tag -eq $release.tag_name -and (Test-Path $TargetPath -PathType Leaf)) {
                $state.last_checked_utc = [DateTimeOffset]::UtcNow.ToString('o')
                $state | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $StatePath
                Write-Host "[ViaProxy] 已是最新官方版本 ($($release.tag_name))。"
                exit 0
            }
        } catch {
            # Rebuild state from the successful update below.
        }
    }

    New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
    $jarPath = Join-Path $TempRoot $asset.name
    Write-Host "[ViaProxy] 正在下载 $($asset.name)..."
    # Stream the release asset with HttpWebRequest so PowerShell 5.1 does not
    # invoke its fragile web response parser, and report real download progress.
    $downloadSucceeded = $false
    $lastDownloadError = $null
    for ($attempt = 1; $attempt -le 3 -and -not $downloadSucceeded; $attempt++) {
        $response = $null
        $inputStream = $null
        $outputStream = $null
        try {
            $request = [System.Net.HttpWebRequest]::Create([Uri]$asset.browser_download_url)
            $request.Method = 'GET'
            $request.UserAgent = $Headers['User-Agent']
            $request.AllowAutoRedirect = $true
            $request.Timeout = 30000
            $request.ReadWriteTimeout = 30000

            $response = $request.GetResponse()
            $totalBytes = $response.ContentLength
            $inputStream = $response.GetResponseStream()
            $outputStream = [System.IO.File]::Create($jarPath)
            $buffer = New-Object byte[] (64 * 1024)
            $downloadedBytes = [long]0
            $lastProgressUpdate = [DateTime]::UtcNow

            while (($bytesRead = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $outputStream.Write($buffer, 0, $bytesRead)
                $downloadedBytes += $bytesRead

                # Throttle console updates while keeping the progress bar responsive.
                if ((([DateTime]::UtcNow - $lastProgressUpdate).TotalMilliseconds -ge 150) -or ($totalBytes -gt 0 -and $downloadedBytes -ge $totalBytes)) {
                    $downloadedMb = $downloadedBytes / 1MB
                    if ($totalBytes -gt 0) {
                        $totalMb = $totalBytes / 1MB
                        $percent = [int][Math]::Min(100, [Math]::Floor(($downloadedBytes * 100.0) / $totalBytes))
                        Write-Progress -Activity "[ViaProxy] 下载 $($asset.name)" -Status ('{0:N1} / {1:N1} MB' -f $downloadedMb, $totalMb) -PercentComplete $percent
                    } else {
                        Write-Progress -Activity "[ViaProxy] 下载 $($asset.name)" -Status ('已下载 {0:N1} MB' -f $downloadedMb)
                    }
                    $lastProgressUpdate = [DateTime]::UtcNow
                }
            }

            $outputStream.Flush()
            $outputStream.Dispose()
            $outputStream = $null
            $inputStream.Dispose()
            $inputStream = $null
            $response.Dispose()
            $response = $null
            Write-Progress -Activity "[ViaProxy] 下载 $($asset.name)" -Completed

            if (-not (Test-Path $jarPath -PathType Leaf)) {
                throw '下载请求结束，但目标文件不存在。'
            }

            $downloadedSize = (Get-Item -LiteralPath $jarPath).Length
            if ($downloadedSize -le 0) {
                throw '下载的 ViaProxy JAR 是空文件。'
            }
            if ($asset.size -and $downloadedSize -ne [long]$asset.size) {
                throw "下载文件大小不匹配：期望 $($asset.size) 字节，实际 $downloadedSize 字节。"
            }

            $downloadSucceeded = $true
        } catch {
            $lastDownloadError = $_
            Remove-Item -LiteralPath $jarPath -Force -ErrorAction SilentlyContinue
            if ($attempt -lt 3) {
                Write-Warning "[ViaProxy] 下载失败（第 $attempt 次），3 秒后重试：$($_.Exception.Message)"
                Start-Sleep -Seconds 3
            }
        } finally {
            Write-Progress -Activity "[ViaProxy] 下载 $($asset.name)" -Completed
            if ($outputStream) { $outputStream.Dispose() }
            if ($inputStream) { $inputStream.Dispose() }
            if ($response) { $response.Dispose() }
        }
    }

    if (-not $downloadSucceeded) {
        throw "ViaProxy JAR 下载失败，已重试 3 次。最后错误：$($lastDownloadError.Exception.Message)"
    }

    if ($asset.digest -match '^sha256:(.+)$') {
        $expectedDigest = $Matches[1].ToLowerInvariant()
        $actualDigest = (Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLowerInvariant()
        if ($actualDigest -ne $expectedDigest) {
            throw '官方 ViaProxy JAR 的 SHA-256 校验失败。'
        }
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jarArchive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        if (-not $jarArchive.GetEntry('net/raphimc/viaproxy/ViaProxy.class')) {
            throw '下载的 JAR 未通过 ViaProxy 主类校验。'
        }
    } finally {
        $jarArchive.Dispose()
    }

    New-Item -ItemType Directory -Force -Path $TargetDirectory | Out-Null
    if (Test-Path $TargetPath -PathType Leaf) {
        $backupPath = "$TargetPath.backup.$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))"
        Copy-Item -Path $TargetPath -Destination $backupPath
        Write-Host "[ViaProxy] 已备份旧版本: $backupPath"
    }

    $stagedPath = "$TargetPath.download"
    Copy-Item -Path $jarPath -Destination $stagedPath -Force
    Move-Item -Path $stagedPath -Destination $TargetPath -Force

    @{
        release_tag = $release.tag_name
        version = $asset.name
        last_checked_utc = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $StatePath

    Write-Host "[ViaProxy] 已更新到官方 Release: $($release.tag_name) ($($asset.name))"
    Write-Host '[ViaProxy] 新版本将在本次启动中生效。'
} finally {
    if (Test-Path $TempRoot) {
        Remove-Item -Path $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
