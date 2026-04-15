param(
    [string]$BaseUrl = "http://localhost:8090",
    [string]$ReadPat = "",
    [string]$EditPat = "",
    [string]$ImportJobName = "import-job-not-exists"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Scenario {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][int[]]$ExpectedStatus,
        [string]$Pat = "",
        [string]$Body = ""
    )

    if (($Role -eq "只读令牌" -or $Role -eq "编辑令牌") -and [string]::IsNullOrWhiteSpace($Pat)) {
        return [PSCustomObject]@{
            场景 = $Name
            身份 = $Role
            方法 = $Method
            路径 = $Path
            期望 = ($ExpectedStatus -join "/")
            实际 = "跳过"
            结果 = "SKIP"
            说明 = "未提供令牌"
        }
    }

    $url = "$BaseUrl$Path"
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Pat)) {
        $headers["Authorization"] = "Bearer $Pat"
    }

    $actualStatus = -1
    $note = ""

    try {
        if ($Method -eq "GET") {
            $response = Invoke-WebRequest -Method Get -Uri $url -Headers $headers -TimeoutSec 30
        } else {
            $contentType = "application/json; charset=utf-8"
            $payload = if ([string]::IsNullOrWhiteSpace($Body)) { "{}" } else { $Body }
            $response = Invoke-WebRequest -Method Post -Uri $url -Headers $headers -Body $payload -ContentType $contentType -TimeoutSec 30
        }
        $actualStatus = [int]$response.StatusCode
    } catch {
        $statusCodeValue = $_.Exception.Response.StatusCode.value__
        if ($null -ne $statusCodeValue) {
            $actualStatus = [int]$statusCodeValue
        } else {
            $actualStatus = -1
            $note = $_.Exception.Message
        }
    }

    $pass = $ExpectedStatus -contains $actualStatus
    return [PSCustomObject]@{
        场景 = $Name
        身份 = $Role
        方法 = $Method
        路径 = $Path
        期望 = ($ExpectedStatus -join "/")
        实际 = if ($actualStatus -lt 0) { "请求失败" } else { "$actualStatus" }
        结果 = if ($pass) { "PASS" } else { "FAIL" }
        说明 = $note
    }
}

$precheckBody = @"
{
  "format": "csv",
  "strategy": "skip",
  "sourceFile": "rbac-check.csv",
  "datasets": [
    {
      "dataset": "qso-record",
      "rows": []
    }
  ]
}
"@

$scenarios = @(
    @{ Name = "控制台报表读取"; Method = "GET"; Path = "/apis/console.api.qsl-management.halo.run/v1alpha1/reports/summary"; Body = ""; AnonymousExpected = @(401); ReadExpected = @(200); EditExpected = @(200) },
    @{ Name = "导入预检（编辑权限）"; Method = "POST"; Path = "/apis/console.api.qsl-management.halo.run/v1alpha1/imports/precheck"; Body = $precheckBody; AnonymousExpected = @(401); ReadExpected = @(403); EditExpected = @(200, 400) },
    @{ Name = "导入任务读取（只读权限）"; Method = "GET"; Path = "/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs/$ImportJobName"; Body = ""; AnonymousExpected = @(401); ReadExpected = @(200, 404); EditExpected = @(200, 404) },
    @{ Name = "公开总览读取"; Method = "GET"; Path = "/apis/api.qsl-management.halo.run/v1alpha1/overview-public/summary"; Body = ""; AnonymousExpected = @(200); ReadExpected = @(200); EditExpected = @(200) }
)

$results = @()

foreach ($scenario in $scenarios) {
    $results += Invoke-Scenario `
        -Name $scenario.Name `
        -Method $scenario.Method `
        -Path $scenario.Path `
        -Role "匿名" `
        -ExpectedStatus $scenario.AnonymousExpected `
        -Body $scenario.Body

    $results += Invoke-Scenario `
        -Name $scenario.Name `
        -Method $scenario.Method `
        -Path $scenario.Path `
        -Role "只读令牌" `
        -ExpectedStatus $scenario.ReadExpected `
        -Pat $ReadPat `
        -Body $scenario.Body

    $results += Invoke-Scenario `
        -Name $scenario.Name `
        -Method $scenario.Method `
        -Path $scenario.Path `
        -Role "编辑令牌" `
        -ExpectedStatus $scenario.EditExpected `
        -Pat $EditPat `
        -Body $scenario.Body
}

$results | Format-Table -AutoSize

$failed = $results | Where-Object { $_.结果 -eq "FAIL" }
if ($failed.Count -gt 0) {
    Write-Error "RBAC 运行态验收失败，请查看 FAIL 场景。"
}

Write-Host "RBAC 运行态验收完成。"

