$ErrorActionPreference = "Stop"

$base = "http://127.0.0.1:8090"
$cookie = "device_id=0f4a0042a35544bb81bbb13a1386a5ce; language=zh-CN; XSRF-TOKEN=1a3ff8e3-c423-4946-a1cf-4c95910fc553; SESSION=d827c096-550a-4160-a0c6-d2177d094043"
$headers = @{
  Cookie = $cookie
  Referer = "http://127.0.0.1:8090/console/qsl/data/import-export"
  Origin = "http://127.0.0.1:8090"
  "Content-Type" = "application/json"
}

function Invoke-QslApi {
  param(
    [Parameter(Mandatory=$true)][string]$Method,
    [Parameter(Mandatory=$true)][string]$Path,
    [object]$Body = $null,
    [hashtable]$ExtraHeaders = @{}
  )
  $uri = "$base$Path"
  $h = @{}
  foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] }
  foreach ($k in $ExtraHeaders.Keys) { $h[$k] = $ExtraHeaders[$k] }

  try {
    if ($null -eq $Body) {
      return Invoke-RestMethod -Uri $uri -Method $Method -Headers $h
    }
    $json = $Body | ConvertTo-Json -Depth 20
    return Invoke-RestMethod -Uri $uri -Method $Method -Headers $h -Body $json
  } catch {
    throw "API failed: $Method $Path :: $($_.Exception.Message)"
  }
}

function Normalize-Key {
  param([string]$callsign,[string]$date,[string]$time,[string]$freq,[string]$mode)
  $parts = @($callsign,$date,$time,$freq,$mode) | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() }
  return ($parts -join "|")
}

$outDir = Join-Path (Get-Location) "migration_output"
$qsoPath = Join-Path $outDir "qso_records.json"
$addrPath = Join-Path $outDir "address_books.json"
$cardSeedPath = Join-Path $outDir "card_seeds.json"
$csvPath = Join-Path $outDir "addresses.csv"

$qsoRecords = Get-Content -Raw -Path $qsoPath -Encoding UTF8 | ConvertFrom-Json
$addressBooks = Get-Content -Raw -Path $addrPath -Encoding UTF8 | ConvertFrom-Json
$cardSeeds = Get-Content -Raw -Path $cardSeedPath -Encoding UTF8 | ConvertFrom-Json

$cards = @((Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/qsl-card-records") | Where-Object { $_ -and $_.id })
foreach ($c in $cards) { [void](Invoke-QslApi -Method DELETE -Path "/apis/qsl.admin/v1/qsl-card-records/$($c.id)") }
$qso = @((Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/qso-records") | Where-Object { $_ -and $_.id })
foreach ($q in $qso) { [void](Invoke-QslApi -Method DELETE -Path "/apis/qsl.admin/v1/qso-records/$($q.id)") }
$addr = @((Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/address-books") | Where-Object { $_ -and $_.id })
foreach ($a in $addr) { [void](Invoke-QslApi -Method DELETE -Path "/apis/qsl.admin/v1/address-books/$($a.id)") }

$importStage1 = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/backup/import" -Body @{
  qsoRecords = $qsoRecords
  addressBooks = $addressBooks
}

$qsoAfter = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/qso-records")
$qsoIndex = @{}
foreach ($q in $qsoAfter) {
  $k = Normalize-Key -callsign $q.peerCallsign -date $q.qsoDate -time $q.qsoTime -freq $q.frequency -mode $q.mode
  $qsoIndex[$k] = [int64]$q.id
}

$seedCards = @()
foreach ($s in $cardSeeds) {
  $item = [ordered]@{
    peerCallsign = $s.peerCallsign
    cardType = $s.cardType
    cardDate = $s.cardDate
    cardTime = $s.cardTime
    productionStatus = $s.productionStatus
    sentStatus = $s.sentStatus
    receivedStatus = $s.receivedStatus
    sentAt = $s.sentAt
    receivedAt = $s.receivedAt
    reissueCount = [int]($s.reissueCount)
  }
  if ($s.cardType -in @("QSO","LISTEN")) {
    $k = Normalize-Key -callsign $s.peerCallsign -date $s.qsoDate -time $s.qsoTime -freq $s.frequency -mode $s.mode
    if ($qsoIndex.ContainsKey($k)) { $item.qsoRecordId = $qsoIndex[$k] }
  }
  $seedCards += $item
}
$importStage2 = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/backup/import" -Body @{ qslCardRecords = $seedCards }

$firstQso = $qsoAfter[0]
$secondQso = if ($qsoAfter.Count -gt 1) { $qsoAfter[1] } else { $qsoAfter[0] }

$qsoCard = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records" -Body @{
  peerCallsign = $firstQso.peerCallsign
  cardType = "QSO"
  cardDate = $firstQso.qsoDate
  cardTime = $firstQso.qsoTime
  qsoRecordId = [int64]$firstQso.id
}
$listenCard = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records" -Body @{
  peerCallsign = $secondQso.peerCallsign
  cardType = "LISTEN"
  cardDate = $secondQso.qsoDate
  cardTime = $secondQso.qsoTime
  qsoRecordId = [int64]$secondQso.id
}
$eyeballCard = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records" -Body @{
  peerCallsign = "BI1KBU"
  cardType = "EYEBALL"
  cardDate = (Get-Date).ToString("yyyy-MM-dd")
  cardTime = (Get-Date).ToString("HH:mm:ss")
}

$send1 = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records/send-confirm" -Body @{ cardIds = @([int64]$qsoCard.id,[int64]$listenCard.id); batchNo = "BATCH-WALK-001"; isReissue = $false }
$recv1 = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records/receive-confirm" -Body @{ cardIds = @([int64]$qsoCard.id); receiveRemark = "walkthrough" }
$reissuePrepare = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records/reissue-prepare" -Body @{ cardId = [int64]$qsoCard.id }
$sendReissue = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/qsl-card-records/send-confirm" -Body @{ cardIds = @([int64]$qsoCard.id); batchNo = "BATCH-WALK-REISSUE"; isReissue = $true }

$bindingEvidence = Invoke-QslApi -Method POST -Path "/apis/qsl.user/v1/my/callsign-bindings" -Body @{ callsign = "BI1TEST"; verifyMethod = "EVIDENCE"; evidenceUrl = "https://example.com/license.png" } -ExtraHeaders @{"X-User-Id"="u-walk-1"; "X-Operator"="ham-user"}
$bindingPhone = Invoke-QslApi -Method POST -Path "/apis/qsl.user/v1/my/callsign-bindings" -Body @{ callsign = "BI1AUTO"; verifyMethod = "PHONE"; phone = "13800000000" } -ExtraHeaders @{"X-User-Id"="u-walk-1"; "X-Operator"="ham-user"}
$bindingEvidenceId = $bindingEvidence.id
if (-not $bindingEvidenceId) {
  $bindingEvidenceId = (Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/callsign-bindings" |
    Where-Object { $_.callsign -eq "BI1TEST" } |
    Sort-Object id -Descending |
    Select-Object -ExpandProperty id -First 1)
}
if (-not $bindingEvidenceId) { throw "cannot resolve binding id for BI1TEST" }
$bindingApprove = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/callsign-bindings/$bindingEvidenceId/approve"

$reqNormal = Invoke-QslApi -Method POST -Path "/apis/qsl.user/v1/my/exchange-requests" -Body @{ requestType = "NORMAL"; bindCallsign = "BH1WALK"; note = "eyeball" } -ExtraHeaders @{"X-Role"="HAM"; "X-Operator"="ham-user"}
$reqReissue = Invoke-QslApi -Method POST -Path "/apis/qsl.user/v1/my/exchange-requests" -Body @{ requestType = "REISSUE"; qslCardRecordId = [int64]$qsoCard.id; reason = "mail lost" } -ExtraHeaders @{"X-Role"="HAM"; "X-Operator"="ham-user"}
$reqNormalId = $reqNormal.id
if (-not $reqNormalId) {
  $reqNormalId = (Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/exchange-requests" |
    Where-Object { $_.bindCallsign -eq "BH1WALK" } |
    Sort-Object id -Descending |
    Select-Object -ExpandProperty id -First 1)
}
$reqReissueId = $reqReissue.id
if (-not $reqReissueId) {
  $reqReissueId = (Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/exchange-requests" |
    Where-Object { $_.requestType -eq "REISSUE" -and $_.qslCardRecordId -eq $qsoCard.id } |
    Sort-Object id -Descending |
    Select-Object -ExpandProperty id -First 1)
}
if (-not $reqNormalId -or -not $reqReissueId) { throw "cannot resolve exchange request ids" }
$reqNormalApprove = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/exchange-requests/$reqNormalId/approve"
$reqReissueApprove = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/exchange-requests/$reqReissueId/approve"
$reqReject = Invoke-QslApi -Method POST -Path "/apis/qsl.user/v1/my/exchange-requests" -Body @{ requestType = "NORMAL"; bindCallsign = "BH1REJECT"; note = "reject" } -ExtraHeaders @{"X-Role"="HAM"; "X-Operator"="ham-user"}
$reqRejectId = $reqReject.id
if (-not $reqRejectId) {
  $reqRejectId = (Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/exchange-requests" |
    Where-Object { $_.bindCallsign -eq "BH1REJECT" } |
    Sort-Object id -Descending |
    Select-Object -ExpandProperty id -First 1)
}
if (-not $reqRejectId) { throw "cannot resolve reject request id" }
$reqRejectDone = Invoke-QslApi -Method POST -Path "/apis/qsl.admin/v1/exchange-requests/$reqRejectId/reject" -Body @{ reason = "incomplete" }

$publicQuery1 = Invoke-QslApi -Method GET -Path "/apis/qsl.public/v1/query/cards?callsign=BG"
$rateLimitResults = @()
for ($i=1; $i -le 7; $i++) {
  try {
    $null = Invoke-QslApi -Method GET -Path "/apis/qsl.public/v1/query/cards?callsign=BI"
    $rateLimitResults += [ordered]@{seq=$i; ok=$true}
  } catch {
    $rateLimitResults += [ordered]@{seq=$i; ok=$false; error=$_.Exception.Message}
  }
}

$reportSummary = Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/reports/summary"
$reportTrend = Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/reports/trend/monthly"
$reportType = Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/reports/card-type-distribution"
$audit = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/audit-logs?operationType=send_confirm")

Invoke-WebRequest -Uri "$base/apis/qsl.admin/v1/exports/cards" -Method POST -Headers $headers -Body (@{cardIds=@()} | ConvertTo-Json) -OutFile (Join-Path $outDir "walk_cards.csv") | Out-Null
Invoke-WebRequest -Uri "$base/apis/qsl.admin/v1/exports/envelopes" -Method POST -Headers $headers -Body (@{cardIds=@()} | ConvertTo-Json) -OutFile (Join-Path $outDir "walk_envelopes.csv") | Out-Null
Invoke-WebRequest -Uri "$base/apis/qsl.admin/v1/dashboard/export" -Method GET -Headers @{Cookie=$cookie; Referer="http://127.0.0.1:8090/console/qsl/dashboard"} -OutFile (Join-Path $outDir "walk_dashboard.csv") | Out-Null

$cardsFinal = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/qsl-card-records")
$qsoFinal = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/qso-records")
$addrFinal = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/address-books")
$requestsFinal = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/exchange-requests")
$bindingsFinal = @(Invoke-QslApi -Method GET -Path "/apis/qsl.admin/v1/callsign-bindings")

$report = [ordered]@{
  executedAt = (Get-Date).ToString("s")
  sourceCsv = $csvPath
  importStage1 = $importStage1
  importStage2 = $importStage2
  counts = [ordered]@{
    qso = $qsoFinal.Count
    address = $addrFinal.Count
    card = $cardsFinal.Count
    request = $requestsFinal.Count
    binding = $bindingsFinal.Count
    auditSendConfirm = $audit.Count
  }
  workflow = [ordered]@{
    send1 = $send1
    receive1 = $recv1
    reissuePrepare = $reissuePrepare
    sendReissue = $sendReissue
    reqNormalApprove = $reqNormalApprove
    reqReissueApprove = $reqReissueApprove
    reqRejectDone = $reqRejectDone
    bindingApprove = $bindingApprove
    phoneBindingStatus = $bindingPhone.status
  }
  publicQuerySampleCount = @($publicQuery1).Count
  rateLimitResults = $rateLimitResults
  reportSummary = $reportSummary
  reportTrendSize = @($reportTrend).Count
  reportTypeSize = @($reportType).Count
  exports = [ordered]@{
    cardsCsv = (Join-Path $outDir "walk_cards.csv")
    envelopesCsv = (Join-Path $outDir "walk_envelopes.csv")
    dashboardCsv = (Join-Path $outDir "walk_dashboard.csv")
  }
}

$reportPath = Join-Path $outDir "walkthrough_report.json"
$report | ConvertTo-Json -Depth 20 | Set-Content -Path $reportPath -Encoding utf8
Write-Output "DONE: $reportPath"
