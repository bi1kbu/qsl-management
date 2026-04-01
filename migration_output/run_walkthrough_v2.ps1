$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:8090"
$cookie = "device_id=0f4a0042a35544bb81bbb13a1386a5ce; language=zh-CN; XSRF-TOKEN=1a3ff8e3-c423-4946-a1cf-4c95910fc553; SESSION=d827c096-550a-4160-a0c6-d2177d094043"
$headers = @{ Cookie=$cookie; Referer="http://127.0.0.1:8090/console/qsl/data/import-export"; Origin="http://127.0.0.1:8090"; "Content-Type"="application/json" }

function Api([string]$method,[string]$path,[object]$body=$null,[hashtable]$extra=@{}) {
  $h=@{}; foreach($k in $headers.Keys){$h[$k]=$headers[$k]}; foreach($k in $extra.Keys){$h[$k]=$extra[$k]}
  $uri = "$base$path"
  try {
    if($null -eq $body){ return Invoke-RestMethod -Uri $uri -Method $method -Headers $h }
    return Invoke-RestMethod -Uri $uri -Method $method -Headers $h -Body ($body|ConvertTo-Json -Depth 20)
  } catch {
    throw "API $method $path failed: $($_.Exception.Message)"
  }
}

function AsArray($x){ if($null -eq $x){ @() } elseif($x -is [System.Array]){ $x } else { @($x) } }
function Key($c,$d,$t,$f,$m){ ((@($c,$d,$t,$f,$m)|%{([string]$_).Trim().ToLowerInvariant()}) -join '|') }

$outDir = Join-Path (Get-Location) "migration_output"
$qsoRecords = Get-Content -Raw -Path (Join-Path $outDir "qso_records.json") -Encoding UTF8 | ConvertFrom-Json
$addressBooks = Get-Content -Raw -Path (Join-Path $outDir "address_books.json") -Encoding UTF8 | ConvertFrom-Json
$cardSeeds = Get-Content -Raw -Path (Join-Path $outDir "card_seeds.json") -Encoding UTF8 | ConvertFrom-Json

# cleanup
foreach($c in AsArray (Api GET "/apis/qsl.admin/v1/qsl-card-records")){ if($c.id){ [void](Api DELETE "/apis/qsl.admin/v1/qsl-card-records/$($c.id)") } }
foreach($q in AsArray (Api GET "/apis/qsl.admin/v1/qso-records")){ if($q.id){ [void](Api DELETE "/apis/qsl.admin/v1/qso-records/$($q.id)") } }
foreach($a in AsArray (Api GET "/apis/qsl.admin/v1/address-books")){ if($a.id){ [void](Api DELETE "/apis/qsl.admin/v1/address-books/$($a.id)") } }
foreach($r in AsArray (Api GET "/apis/qsl.admin/v1/exchange-requests")){ if($r.id -and $r.status -ne 'CANCELED'){ [void](Api POST "/apis/qsl.user/v1/my/exchange-requests/$($r.id)/cancel" $null @{"X-Operator"="ham-user"}) } }
foreach($b in AsArray (Api GET "/apis/qsl.admin/v1/callsign-bindings")){ if($b.id){ [void](Api POST "/apis/qsl.admin/v1/callsign-bindings/$($b.id)/reject" @{reason="cleanup"}) } }

# import
$import1 = Api POST "/apis/qsl.admin/v1/backup/import" @{ qsoRecords=$qsoRecords; addressBooks=$addressBooks }
$qsoAfter = AsArray (Api GET "/apis/qsl.admin/v1/qso-records")
$qsoIdx=@{}
foreach($q in $qsoAfter){ $qsoIdx[(Key $q.peerCallsign $q.qsoDate $q.qsoTime $q.frequency $q.mode)] = [int64]$q.id }

$cards=@()
foreach($s in $cardSeeds){
  $it=[ordered]@{ peerCallsign=$s.peerCallsign; cardType=$s.cardType; cardDate=$s.cardDate; cardTime=$s.cardTime; productionStatus=$s.productionStatus; sentStatus=$s.sentStatus; receivedStatus=$s.receivedStatus; sentAt=$s.sentAt; receivedAt=$s.receivedAt; reissueCount=[int]$s.reissueCount }
  if($s.cardType -in @('QSO','LISTEN')){ $k=Key $s.peerCallsign $s.qsoDate $s.qsoTime $s.frequency $s.mode; if($qsoIdx.ContainsKey($k)){ $it.qsoRecordId=$qsoIdx[$k] } }
  $cards += $it
}
$import2 = Api POST "/apis/qsl.admin/v1/backup/import" @{ qslCardRecords=$cards }

$q1=$qsoAfter[0]; $q2=if($qsoAfter.Count -gt 1){$qsoAfter[1]}else{$qsoAfter[0]}
$qsoCard = Api POST "/apis/qsl.admin/v1/qsl-card-records" @{peerCallsign=$q1.peerCallsign;cardType='QSO';cardDate=$q1.qsoDate;cardTime=$q1.qsoTime;qsoRecordId=[int64]$q1.id}
$listenCard = Api POST "/apis/qsl.admin/v1/qsl-card-records" @{peerCallsign=$q2.peerCallsign;cardType='LISTEN';cardDate=$q2.qsoDate;cardTime=$q2.qsoTime;qsoRecordId=[int64]$q2.id}
$eyeballCard = Api POST "/apis/qsl.admin/v1/qsl-card-records" @{peerCallsign='BI1KBU';cardType='EYEBALL';cardDate=(Get-Date).ToString('yyyy-MM-dd');cardTime=(Get-Date).ToString('HH:mm:ss')}

$send1 = Api POST "/apis/qsl.admin/v1/qsl-card-records/send-confirm" @{cardIds=@([int64]$qsoCard.id,[int64]$listenCard.id);batchNo='BATCH-WALK-001';isReissue=$false}
$recv1 = Api POST "/apis/qsl.admin/v1/qsl-card-records/receive-confirm" @{cardIds=@([int64]$qsoCard.id);receiveRemark='walkthrough'}
$prep = Api POST "/apis/qsl.admin/v1/qsl-card-records/reissue-prepare" @{cardId=[int64]$qsoCard.id}
$send2 = Api POST "/apis/qsl.admin/v1/qsl-card-records/send-confirm" @{cardIds=@([int64]$qsoCard.id);batchNo='BATCH-WALK-REISSUE';isReissue=$true}

$bind1 = Api POST "/apis/qsl.user/v1/my/callsign-bindings" @{callsign='BI1TEST';verifyMethod='EVIDENCE';evidenceUrl='https://example.com/license.png'} @{"X-User-Id"='u-walk-1';"X-Operator"='ham-user'}
$bind2 = Api POST "/apis/qsl.user/v1/my/callsign-bindings" @{callsign='BI1AUTO';verifyMethod='PHONE';phone='13800000000'} @{"X-User-Id"='u-walk-1';"X-Operator"='ham-user'}
$bindId = $bind1.id
if(-not $bindId){
  $bindId = (AsArray (Api GET "/apis/qsl.admin/v1/callsign-bindings") | Where-Object { $_.callsign -eq 'BI1TEST' } | Sort-Object id -Descending | Select-Object -ExpandProperty id -First 1)
}
if(-not $bindId){ throw "binding id not found for BI1TEST" }
$bindApprove = Api POST "/apis/qsl.admin/v1/callsign-bindings/$bindId/approve"

$req1 = Api POST "/apis/qsl.user/v1/my/exchange-requests" @{requestType='NORMAL';bindCallsign='BH1WALK';note='eyeball'} @{"X-Role"='HAM';"X-Operator"='ham-user'}
$req2 = Api POST "/apis/qsl.user/v1/my/exchange-requests" @{requestType='REISSUE';qslCardRecordId=[int64]$qsoCard.id;reason='mail lost'} @{"X-Role"='HAM';"X-Operator"='ham-user'}
$req3 = Api POST "/apis/qsl.user/v1/my/exchange-requests" @{requestType='NORMAL';bindCallsign='BH1REJECT';note='reject'} @{"X-Role"='HAM';"X-Operator"='ham-user'}
$req1Id = $req1.id; if(-not $req1Id){ $req1Id = (AsArray (Api GET "/apis/qsl.admin/v1/exchange-requests") | Where-Object { $_.bindCallsign -eq 'BH1WALK' } | Sort-Object id -Descending | Select-Object -ExpandProperty id -First 1) }
$req2Id = $req2.id; if(-not $req2Id){ $req2Id = (AsArray (Api GET "/apis/qsl.admin/v1/exchange-requests") | Where-Object { $_.requestType -eq 'REISSUE' } | Sort-Object id -Descending | Select-Object -ExpandProperty id -First 1) }
$req3Id = $req3.id; if(-not $req3Id){ $req3Id = (AsArray (Api GET "/apis/qsl.admin/v1/exchange-requests") | Where-Object { $_.bindCallsign -eq 'BH1REJECT' } | Sort-Object id -Descending | Select-Object -ExpandProperty id -First 1) }
$req1Approve = Api POST "/apis/qsl.admin/v1/exchange-requests/$req1Id/approve"
$req2Approve = Api POST "/apis/qsl.admin/v1/exchange-requests/$req2Id/approve"
$req3Reject = Api POST "/apis/qsl.admin/v1/exchange-requests/$req3Id/reject" @{reason='incomplete'}

$publicSample = AsArray (Api GET "/apis/qsl.public/v1/query/cards?callsign=BG")
$rate=@()
for($i=1;$i -le 7;$i++){
  try{ $null = Api GET "/apis/qsl.public/v1/query/cards?callsign=BI"; $rate += [ordered]@{seq=$i;ok=$true} }
  catch{ $rate += [ordered]@{seq=$i;ok=$false;error=$_.Exception.Message} }
}

$summary = Api GET "/apis/qsl.admin/v1/reports/summary"
$trend = AsArray (Api GET "/apis/qsl.admin/v1/reports/trend/monthly")
$dist = AsArray (Api GET "/apis/qsl.admin/v1/reports/card-type-distribution")
$audit = AsArray (Api GET "/apis/qsl.admin/v1/audit-logs?operationType=send_confirm")

Invoke-WebRequest -Uri "$base/apis/qsl.admin/v1/exports/cards" -Method POST -Headers $headers -Body (@{cardIds=@()} | ConvertTo-Json) -OutFile (Join-Path $outDir "walk_cards.csv") | Out-Null
Invoke-WebRequest -Uri "$base/apis/qsl.admin/v1/exports/envelopes" -Method POST -Headers $headers -Body (@{cardIds=@()} | ConvertTo-Json) -OutFile (Join-Path $outDir "walk_envelopes.csv") | Out-Null
Invoke-WebRequest -Uri "$base/apis/qsl.admin/v1/dashboard/export" -Method GET -Headers @{Cookie=$cookie; Referer="http://127.0.0.1:8090/console/qsl/dashboard"} -OutFile (Join-Path $outDir "walk_dashboard.csv") | Out-Null

$cardsFinal = AsArray (Api GET "/apis/qsl.admin/v1/qsl-card-records")
$qsoFinal = AsArray (Api GET "/apis/qsl.admin/v1/qso-records")
$addrFinal = AsArray (Api GET "/apis/qsl.admin/v1/address-books")
$reqFinal = AsArray (Api GET "/apis/qsl.admin/v1/exchange-requests")
$bindFinal = AsArray (Api GET "/apis/qsl.admin/v1/callsign-bindings")

$report=[ordered]@{
  executedAt=(Get-Date).ToString('s')
  importStage1=$import1
  importStage2=$import2
  counts=[ordered]@{ qso=$qsoFinal.Count; address=$addrFinal.Count; card=$cardsFinal.Count; request=$reqFinal.Count; binding=$bindFinal.Count; auditSendConfirm=$audit.Count }
  workflow=[ordered]@{ send1=$send1.count; receive1=$recv1.count; reissuePrepared=$prep.prepared; sendReissue=$send2.count; bindingApprovedStatus=$bindApprove.status; phoneBindingStatus=$bind2.status; req1Status=$req1Approve.status; req2Status=$req2Approve.status; req3Status=$req3Reject.status }
  publicQuerySampleCount=$publicSample.Count
  rateLimit=$rate
  reportSummary=$summary
  reportTrendSize=$trend.Count
  reportTypeSize=$dist.Count
  exports=[ordered]@{ cardsCsv=(Join-Path $outDir "walk_cards.csv"); envelopesCsv=(Join-Path $outDir "walk_envelopes.csv"); dashboardCsv=(Join-Path $outDir "walk_dashboard.csv") }
}

$reportPath = Join-Path $outDir "walkthrough_report.json"
$report | ConvertTo-Json -Depth 20 | Set-Content -Path $reportPath -Encoding UTF8
Write-Output "DONE $reportPath"
