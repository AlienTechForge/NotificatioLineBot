<#
.SYNOPSIS
    發一則通知，並把結果查回來。

.DESCRIPTION
    poc/notify.sh 的 PowerShell 版本。內容與行為相同，
    差別只在不需要 Git Bash。

    這支腳本同時是「怎麼簽章」的可執行文件 —— 接入時直接照抄。

.EXAMPLE
    $env:NOTIFY_BASE      = "https://notify.example.com"
    $env:NOTIFY_CLIENT_ID = "cli_xxx"
    $env:NOTIFY_SECRET    = "xxx"
    ./poc/notify.ps1 "備份完成"

.EXAMPLE
    ./poc/notify.ps1 -Text "公告內容" -Target ALL
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Text = "NotifyLine 測試訊息",

    [ValidateSet("SELF", "OWNER", "USER", "ALL")]
    [string] $Target = "OWNER",

    [string] $Base     = $env:NOTIFY_BASE,
    [string] $ClientId = $env:NOTIFY_CLIENT_ID,
    [string] $Secret   = $env:NOTIFY_SECRET
)

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "需要 PowerShell 7+（用 pwsh 開）。Windows 內建的 5.1 沒有 -SkipHttpErrorCheck，" +
          "錯誤回應會被轉成例外而看不到伺服器的錯誤碼。或改用 Git Bash 跑 poc/notify.sh。"
}

foreach ($pair in @{ NOTIFY_BASE = $Base; NOTIFY_CLIENT_ID = $ClientId; NOTIFY_SECRET = $Secret }.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($pair.Value)) {
        throw "請先設 $($pair.Key)，例如：`$env:$($pair.Key) = '...'"
    }
}
$Base = $Base.TrimEnd('/')

# ---------------------------------------------------------------- 簽章工具

function Get-Sha256Hex {
    param([byte[]] $Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-Signature {
    param([string] $SecretKey, [string] $Canonical)
    # 金鑰與 canonical string 都以 UTF-8 編碼。伺服器端也是 UTF-8，
    # 用 Default 編碼在中文環境會得到不同的位元組，簽章就對不起來。
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($SecretKey))
    try {
        return [System.Convert]::ToBase64String(
            $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Canonical)))
    } finally {
        $hmac.Dispose()
    }
}

function New-Nonce {
    return [System.Guid]::NewGuid().ToString()
}

<#
    canonical string：五段，以 LF 串接，「沒有」結尾換行。

    一定要用 "`n"（LF）而不是 [Environment]::NewLine —— 後者在 Windows 上是
    CRLF，多出來的 \r 會讓每一次簽章都失敗，而錯誤訊息只會說「簽章不符」。
#>
function New-Canonical {
    param([string] $Method, [string] $Path, [string] $Timestamp, [string] $Nonce, [byte[]] $Body)
    return ($Method, $Path, $Timestamp, $Nonce, (Get-Sha256Hex $Body)) -join "`n"
}

function Invoke-Signed {
    param(
        [string] $Method,
        [string] $Path,
        [byte[]] $Body = [byte[]]::new(0),
        [hashtable] $ExtraHeaders = @{}
    )
    $timestamp = [System.DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce     = New-Nonce
    $canonical = New-Canonical $Method $Path $timestamp $nonce $Body

    $headers = @{
        "X-Client-Id" = $ClientId
        "X-Timestamp" = $timestamp
        "X-Nonce"     = $nonce
        "X-Signature" = Get-Signature $Secret $canonical
    }
    foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] }

    $params = @{
        Uri                = "$Base$Path"
        Method             = $Method
        Headers            = $headers
        SkipHttpErrorCheck = $true
        StatusCodeVariable = "status"
    }
    if ($Body.Length -gt 0) {
        $params.Body        = $Body
        $params.ContentType = "application/json"
    }

    $response = Invoke-RestMethod @params
    return [pscustomobject]@{ Status = $status; Body = $response }
}

# ------------------------------------------------------------------- 送出

$notifyPath = "/api/v1/notifications"

# 手動組 JSON 並取 UTF-8 bytes。
# 雜湊必須算在「實際送出的那串位元組」上，所以先固定 bytes 再送，
# 不要讓序列化在中間跑第二次。
$json = ConvertTo-Json -Compress -Depth 5 @{
    target  = @{ type = $Target }
    message = @{ text = $Text }
}
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($json)

Write-Host "→ POST $Base$notifyPath  target=$Target"
$post = Invoke-Signed -Method POST -Path $notifyPath -Body $bodyBytes `
    -ExtraHeaders @{ "Idempotency-Key" = New-Nonce }

Write-Host "HTTP $($post.Status)"
$post.Body | ConvertTo-Json -Depth 6

if ($post.Status -ne 202) {
    Write-Host ""
    Write-Host "沒有受理。上面就是伺服器的回應。" -ForegroundColor Yellow
    exit 1
}

# ------------------------------------------------------------------- 查結果

# 202 只代表「已受理」。真正送出是非同步的，等一下再查。
Write-Host ""
Write-Host "→ 等待派送…"
Start-Sleep -Seconds 3

$detailPath = "$notifyPath/$($post.Body.data.notificationId)"
$get = Invoke-Signed -Method GET -Path $detailPath

$get.Body | ConvertTo-Json -Depth 6

Write-Host ""
Write-Host "status 的意思："
Write-Host "  QUEUED    已受理，還沒送"
Write-Host "  SENDING   派送中"
Write-Host "  SUCCEEDED LINE 已接受全部批次"
Write-Host "  PARTIAL   部分批次失敗"
Write-Host "  FAILED    全部失敗（看 batches[].errorCode）"
